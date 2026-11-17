package eg.sbs.benchmark;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Offline, deterministic decision policy; no claims about free space, depth or physical speed. */
final class DecisionEngine {
    static final String VERSION = "1.0";
    enum Mode { SMART, LEGACY }
    enum Level { INFO, NOTICE, CAUTION }
    enum Action { DESCRIBE, LOCATE_TARGET, PAY_ATTENTION, SLOW_AND_CHECK }
    enum Reason { DESCRIPTION, CENTER_OBJECT, LARGE_CENTER_OBJECT, CENTER_VEHICLE,
        ENTERING_CENTER, IMAGE_EXPANSION, SEARCH_TARGET }

    record Decision(AlertPolicy.Selection selection, Level level, Reason reason, double priority,
                    boolean target, boolean still, float area, float imageGrowth, float imageVelocity) {
        int trackId() { return selection.trackId(); }
        int classId() { return selection.classId(); }
        int direction() { return selection.direction(); }
        float confidence() { return selection.score(); }
        boolean automatic() { return level != Level.INFO || target; }
        Action action() {
            return switch (level) {
                case CAUTION -> Action.SLOW_AND_CHECK;
                case NOTICE -> Action.PAY_ATTENTION;
                case INFO -> target ? Action.LOCATE_TARGET : Action.DESCRIBE;
            };
        }
        String phraseKey() { return classId() + ":" + direction(); }
    }
    private record Emission(int trackId, Level level, long at) {}
    private record HeldRisk(Level level, Reason reason, int direction, long until) {}
    private final SceneTracker tracker = new SceneTracker();
    private final AlertPolicy legacy = new AlertPolicy();
    private final Mode mode;
    private final float threshold;
    private final Map<String, Emission> spoken = new HashMap<>();
    private final Map<Integer, HeldRisk> heldRisks = new HashMap<>();
    private List<Decision> candidates = List.of();
    private Decision current;
    private long sourceAt = -1, lastAnnouncement = -1;
    private Level lastLevel = Level.INFO;
    private int targetClass = -1;

    DecisionEngine(Mode mode, float threshold, int targetClass) {
        this.mode = mode;
        this.threshold = Float.isFinite(threshold) ? Math.max(.45f, Math.min(1, threshold)) : .45f;
        setTargetClass(targetClass);
    }

    void setTargetClass(int classId) {
        if (classId < -1 || classId >= 80) throw new IllegalArgumentException("Invalid target class");
        if (targetClass == classId) return;
        targetClass = classId;
        // Old target decisions must not start playing while a new target is selected.
        candidates = List.of();
        current = null;
        // Searching again is an explicit request, not an automatic repeat.
        if (classId >= 0) for (int d = 0; d < 3; d++) spoken.remove(classId + ":" + d);
    }

    Mode mode() { return mode; }
    int targetClass() { return targetClass; }
    Decision current() { return current; }

    Decision update(List<AlertPolicy.Box> boxes, long now, long age) {
        current = null;
        candidates = List.of();
        if (now < 0 || age < 0 || age > SceneTracker.MAX_AGE_MS || age > now
                || (sourceAt >= 0 && now - age <= sourceAt)) {
            invalidate();
            return null;
        }
        sourceAt = now - age;
        if (mode == Mode.LEGACY) {
            var selection = legacy.update(SceneTracker.clean(boxes, threshold), now, age);
            if (selection != null) {
                current = new Decision(selection, Level.INFO, Reason.DESCRIPTION, 0, false, false, 0, 1, 0);
                candidates = List.of(current);
            }
        } else {
            var choices = new ArrayList<Decision>();
            var observations = tracker.update(boxes, now, age, threshold);
            var seen = new java.util.HashSet<Integer>();
            for (var observation : observations) {
                seen.add(observation.id());
                if (observation.confirmed()) choices.add(evaluate(observation, sourceAt));
            }
            heldRisks.keySet().retainAll(seen);
            choices.sort(Comparator.comparingDouble(Decision::priority).reversed());
            candidates = List.copyOf(choices);
            if (!choices.isEmpty()) current = choices.get(0);
        }
        return current;
    }

    Decision describeStill(List<AlertPolicy.Box> boxes) {
        // One JPEG is a description; repeated inference is never evidence of motion or urgency.
        return SceneTracker.clean(boxes, threshold).stream().map(box -> {
            boolean target = mode == Mode.SMART && box.classId() == targetClass;
            int direction = SceneTracker.direction(box.center());
            double priority = (target ? 100 : 0) + (direction == 1 ? 2 : 0) + box.area() + .3 * box.score();
            return new Decision(new AlertPolicy.Selection(0, box.classId(), direction, box.score()),
                    Level.INFO, target ? Reason.SEARCH_TARGET : Reason.DESCRIPTION,
                    priority, target, true, box.area(), 1, 0);
        }).max(Comparator.comparingDouble(Decision::priority)).orElse(null);
    }

    private Decision evaluate(SceneTracker.Observation o, long at) {
        var box = o.box();
        boolean relevant = relevant(box.classId());
        Level level = Level.INFO;
        Reason reason = Reason.DESCRIPTION;
        if (relevant && o.forward()) {
            level = Level.NOTICE;
            reason = Reason.CENTER_OBJECT;
            if (vehicle(box.classId()) && box.area() >= .035f) {
                level = Level.CAUTION;
                reason = Reason.CENTER_VEHICLE;
            } else if (box.area() >= .14f && box.y2() >= .75f) {
                level = Level.CAUTION;
                reason = Reason.LARGE_CENTER_OBJECT;
            }
            if (o.expanding()) {
                level = Level.CAUTION;
                reason = Reason.IMAGE_EXPANSION;
            }
        }
        if (relevant && o.entering()) {
            level = Level.CAUTION;
            reason = Reason.ENTERING_CENTER;
        }
        // Avoid switching wording on every threshold wobble; never retain absent observations.
        var previous = heldRisks.get(o.id());
        if (previous != null && previous.direction() == o.direction() && at < previous.until()
                && previous.level().ordinal() >= level.ordinal()) {
            level = previous.level();
            reason = previous.reason();
        } else {
            heldRisks.put(o.id(), new HeldRisk(level, reason, o.direction(), at + 600));
        }
        boolean target = box.classId() == targetClass;
        if (target && level == Level.INFO) reason = Reason.SEARCH_TARGET;
        double priority = level.ordinal() * 100 + (target ? 35 : 0)
                + (o.forward() ? 15 : 0) + (vehicle(box.classId()) ? 5 : 0)
                + box.area() * 10 + box.score();
        return new Decision(new AlertPolicy.Selection(o.id(), box.classId(), o.direction(), box.score()),
                level, reason, priority, target, false, box.area(), o.imageGrowth(), o.imageVelocity());
    }

    Decision announcement(long now) {
        if (!fresh(now)) return null;
        if (mode == Mode.LEGACY) return legacy.announcement(now) == null ? null : current;
        // Select an eligible candidate, not just the highest-scoring already-spoken object.
        for (var candidate : candidates) {
            if (!candidate.automatic()) continue;
            var previous = spoken.get(candidate.phraseKey());
            boolean stronger = previous != null && candidate.level().ordinal() > previous.level().ordinal();
            boolean interrupt = candidate.level() == Level.CAUTION && lastLevel != Level.CAUTION;
            long gap = candidate.level() == Level.CAUTION ? 1_600 : 2_500;
            if (lastAnnouncement >= 0 && now - lastAnnouncement < (interrupt ? 750 : gap)) continue;
            long repeat = candidate.level() == Level.CAUTION ? 6_000 : 12_000;
            if (previous != null && !stronger && now - previous.at() < repeat) continue;
            return candidate;
        }
        return null;
    }

    boolean canInterrupt(Decision next, Decision playing) {
        return mode == Mode.SMART && next != null && playing != null
                && next.level() == Level.CAUTION && playing.level().ordinal() < next.level().ordinal();
    }

    boolean isValid(Decision decision, long now) {
        if (decision == null || !fresh(now)) return false;
        return candidates.stream().anyMatch(c -> c.trackId() == decision.trackId()
                && c.classId() == decision.classId() && c.direction() == decision.direction()
                && c.level() == decision.level() && c.reason() == decision.reason()
                && c.target() == decision.target());
    }

    void announced(Decision decision, long now) {
        if (!isValid(decision, now)) return;
        if (mode == Mode.LEGACY) {
            legacy.announced(decision.selection(), now);
            return;
        }
        spoken.put(decision.phraseKey(), new Emission(decision.trackId(), decision.level(), now));
        lastAnnouncement = now;
        lastLevel = decision.level();
    }

    private boolean fresh(long now) {
        return sourceAt >= 0 && now >= sourceAt && now - sourceAt <= SceneTracker.MAX_AGE_MS;
    }

    void invalidate() {
        tracker.clear();
        heldRisks.clear();
        legacy.update(List.of(), 0, SceneTracker.MAX_AGE_MS + 1);
        candidates = List.of();
        current = null;
        sourceAt = -1;
    }

    int retainedTracks() { return tracker.retainedTracks(); }
    int rememberedPhrases() { return spoken.size(); }

    private static boolean vehicle(int c) { return c == 1 || c == 2 || c == 3 || c == 5 || c == 6 || c == 7; }
    private static boolean relevant(int c) {
        return c == 0 || vehicle(c) || c == 10 || c == 12 || c == 13 || (c >= 15 && c <= 24)
                || c == 26 || c == 28 || c == 36 || (c >= 56 && c <= 62)
                || c == 68 || c == 69 || c == 71 || c == 72;
    }
}
