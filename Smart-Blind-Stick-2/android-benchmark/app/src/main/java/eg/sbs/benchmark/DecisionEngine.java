package eg.sbs.benchmark;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Offline room descriptions with conservative image-based attention, never metric range. */
final class DecisionEngine {
    static final String VERSION = "1.1";
    enum Mode { SMART, LEGACY }
    enum Level { INFO, NOTICE, CAUTION }
    enum Action { DESCRIBE, LOCATE_TARGET, PAY_ATTENTION }
    enum Reason { DESCRIPTION, VISUALLY_NEAR_CENTER, SEARCH_TARGET }

    record Decision(AlertPolicy.Selection selection, Level level, Reason reason, double priority,
                    boolean target, boolean still, float area, float imageGrowth, float imageVelocity) {
        int trackId() { return selection.trackId(); }
        int classId() { return selection.classId(); }
        int direction() { return selection.direction(); }
        float confidence() { return selection.score(); }
        Action action() {
            return level == Level.CAUTION ? Action.PAY_ATTENTION : target ? Action.LOCATE_TARGET : Action.DESCRIBE;
        }
        String phraseKey() { return classId() + ":" + direction(); }
    }
    private record Emission(Level level, long at) {}
    private record NearEvidence(long since, long last, int hits, boolean confirmed) {}
    private final SceneTracker tracker = new SceneTracker();
    private final AlertPolicy legacy = new AlertPolicy();
    private final Mode mode;
    private final float threshold;
    private final Map<String, Emission> spoken = new HashMap<>();
    private final Map<Integer, NearEvidence> near = new HashMap<>();
    private List<Decision> candidates = List.of();
    private Decision current;
    private long sourceAt = -1, lastAnnouncement = -1, contextWindow = -1;
    private int contextCount, targetClass = -1;
    private Level lastLevel = Level.INFO;

    DecisionEngine(Mode mode, float threshold, int targetClass) {
        this.mode = mode;
        this.threshold = Float.isFinite(threshold) ? Math.max(.45f, Math.min(1, threshold)) : .45f;
        setTargetClass(targetClass);
    }

    void setTargetClass(int classId) {
        if (classId < -1 || classId >= 80) throw new IllegalArgumentException("Invalid target class");
        if (targetClass == classId) return;
        targetClass = classId;
        candidates = List.of();
        current = null;
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
            var seen = new java.util.HashSet<Integer>();
            for (var o : tracker.update(boxes, now, age, threshold)) {
                seen.add(o.id());
                boolean prominent = nearCenter(o, sourceAt);
                if (!o.confirmed()) continue;
                var b = o.box();
                boolean target = b.classId() == targetClass;
                var level = prominent ? Level.CAUTION : Level.INFO;
                var reason = prominent ? Reason.VISUALLY_NEAR_CENTER : target ? Reason.SEARCH_TARGET : Reason.DESCRIPTION;
                double priority = (prominent ? 200 : 0) + (target ? 35 : 0)
                        + (o.forward() ? 10 : 0) + b.area() * 10 + b.score();
                choices.add(new Decision(new AlertPolicy.Selection(o.id(), b.classId(), o.direction(), b.score()),
                        level, reason, priority, target, false, b.area(), o.imageGrowth(), o.imageVelocity()));
            }
            near.keySet().retainAll(seen);
            choices.sort(Comparator.comparingDouble(Decision::priority).reversed());
            candidates = List.copyOf(choices);
            if (!choices.isEmpty()) current = choices.get(0);
        }
        return current;
    }

    private boolean nearCenter(SceneTracker.Observation o, long at) {
        var previous = near.get(o.id());
        // Visual prominence is an approximate cue, not a measured distance. Require sustained evidence.
        float minimumArea = previous != null && previous.confirmed() ? .26f : .32f;
        if (!o.forward() || o.box().y2() < .72f || o.box().area() < minimumArea) {
            near.remove(o.id());
            return false;
        }
        long since = previous == null || at - previous.last() > 450 ? at : previous.since();
        int hits = previous == null || at - previous.last() > 450 ? 1 : previous.hits() + 1;
        boolean confirmed = hits >= 3 && at - since >= 180;
        near.put(o.id(), new NearEvidence(since, at, hits, confirmed));
        return confirmed;
    }

    List<Decision> describeScene(long now) {
        if (!fresh(now)) return List.of();
        return distinct(candidates);
    }

    List<Decision> describeStillScene(List<AlertPolicy.Box> boxes) {
        var choices = new ArrayList<Decision>();
        for (var box : SceneTracker.clean(boxes, threshold)) {
            boolean target = mode == Mode.SMART && box.classId() == targetClass;
            int direction = SceneTracker.direction(box.center());
            double priority = (target ? 100 : 0) + (direction == 1 ? 2 : 0) + box.area() + .3 * box.score();
            choices.add(new Decision(new AlertPolicy.Selection(0, box.classId(), direction, box.score()),
                    Level.INFO, target ? Reason.SEARCH_TARGET : Reason.DESCRIPTION,
                    priority, target, true, box.area(), 1, 0));
        }
        choices.sort(Comparator.comparingDouble(Decision::priority).reversed());
        return distinct(choices);
    }

    Decision describeStill(List<AlertPolicy.Box> boxes) {
        var scene = describeStillScene(boxes);
        return scene.isEmpty() ? null : scene.get(0);
    }

    private List<Decision> distinct(List<Decision> choices) {
        var keys = new java.util.HashSet<String>();
        var result = new ArrayList<Decision>();
        for (var d : choices) {
            if (keys.add(d.phraseKey())) result.add(d);
            if (result.size() == (mode == Mode.LEGACY ? 1 : 3)) break;
        }
        return List.copyOf(result);
    }

    Decision announcement(long now) {
        if (!fresh(now)) return null;
        if (mode == Mode.LEGACY) return legacy.announcement(now) == null ? null : current;
        for (var c : candidates) {
            var previous = spoken.get(c.phraseKey());
            boolean stronger = previous != null && c.level().ordinal() > previous.level().ordinal();
            boolean interrupt = c.level() == Level.CAUTION && lastLevel != Level.CAUTION;
            long gap = c.level() == Level.CAUTION ? 1_600 : 2_500;
            if (lastAnnouncement >= 0 && now - lastAnnouncement < (interrupt ? 750 : gap)) continue;
            long repeat = c.level() == Level.CAUTION ? 10_000 : c.target() ? 12_000 : 30_000;
            if (previous != null && !stronger && now - previous.at() < repeat) continue;
            if (c.level() == Level.INFO && !c.target() && contextWindow >= 0
                    && now - contextWindow < 15_000 && contextCount >= 3) continue;
            return c;
        }
        return null;
    }

    boolean canInterrupt(Decision next, Decision playing) {
        return mode == Mode.SMART && next != null && playing != null
                && next.level() == Level.CAUTION && playing.level().ordinal() < next.level().ordinal();
    }

    boolean isValid(Decision d, long now) {
        if (d == null || !fresh(now)) return false;
        return candidates.stream().anyMatch(c -> c.trackId() == d.trackId() && c.classId() == d.classId()
                && c.direction() == d.direction() && c.level() == d.level() && c.reason() == d.reason()
                && c.target() == d.target());
    }

    void announced(Decision d, long now) {
        if (!isValid(d, now)) return;
        if (mode == Mode.LEGACY) {
            legacy.announced(d.selection(), now);
            return;
        }
        spoken.put(d.phraseKey(), new Emission(d.level(), now));
        lastAnnouncement = now;
        lastLevel = d.level();
        if (d.level() == Level.INFO && !d.target()) {
            if (contextWindow < 0 || now - contextWindow >= 15_000) {
                contextWindow = now;
                contextCount = 0;
            }
            contextCount++;
        }
    }

    private boolean fresh(long now) {
        return sourceAt >= 0 && now >= sourceAt && now - sourceAt <= SceneTracker.MAX_AGE_MS;
    }

    void invalidate() {
        tracker.clear();
        near.clear();
        legacy.update(List.of(), 0, SceneTracker.MAX_AGE_MS + 1);
        candidates = List.of();
        current = null;
        sourceAt = -1;
    }

    int retainedTracks() { return tracker.retainedTracks(); }
    int rememberedPhrases() { return spoken.size(); }
}
