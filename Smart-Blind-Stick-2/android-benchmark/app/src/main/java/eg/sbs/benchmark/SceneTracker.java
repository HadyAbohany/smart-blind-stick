package eg.sbs.benchmark;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Camera-image evidence only. Coordinates and velocities never imply metric distance. */
final class SceneTracker {
    static final long MAX_AGE_MS = 500;
    private static final long LOST_MS = 900, CONTINUITY_MS = 450;
    private static final int MAX_BOXES = 40, MAX_TRACKS = 80;

    record Observation(int id, AlertPolicy.Box box, int direction, boolean confirmed,
                       boolean forward, boolean entering, boolean expanding,
                       boolean cameraMotion, float imageVelocity, float imageGrowth) {}
    private record Sample(long at, float x, float y, float area) {}
    private record Match(Track track, int box, float quality) {}

    private static final class Track {
        final int id;
        final ArrayDeque<Sample> history = new ArrayDeque<>();
        AlertPolicy.Box box;
        long lastSeen, stableFrom;
        int hits, direction;
        boolean forward;

        Track(int id, AlertPolicy.Box box, long at) {
            this.id = id;
            this.box = box;
            lastSeen = stableFrom = at;
            direction = direction(box.center());
        }
    }

    private final List<Track> tracks = new ArrayList<>();
    private int nextId = 1;
    private long lastNow = -1, lastFrame = -1;
    private List<Observation> observations = List.of();

    static boolean valid(AlertPolicy.Box b, float threshold) {
        return b != null && b.classId() >= 0 && b.classId() < 80
                && Float.isFinite(b.score()) && b.score() >= Math.max(.45f, threshold) && b.score() <= 1
                && Float.isFinite(b.x1()) && Float.isFinite(b.y1())
                && Float.isFinite(b.x2()) && Float.isFinite(b.y2())
                && b.x1() >= 0 && b.y1() >= 0 && b.x2() <= 1 && b.y2() <= 1
                && b.x2() > b.x1() && b.y2() > b.y1() && b.area() >= .003f;
    }

    static List<AlertPolicy.Box> clean(List<AlertPolicy.Box> detections, float threshold) {
        if (detections == null) return List.of();
        var sorted = detections.stream().filter(b -> valid(b, threshold))
                .sorted(Comparator.comparingDouble(AlertPolicy.Box::score).reversed()).limit(200)
                .collect(java.util.stream.Collectors.toList());
        var result = new ArrayList<AlertPolicy.Box>();
        for (var box : sorted) {
            boolean duplicate = result.stream().anyMatch(other -> other.classId() == box.classId()
                    && overlap(other, box) > .8f);
            if (!duplicate) result.add(box);
            if (result.size() == MAX_BOXES) break;
        }
        return result;
    }

    List<Observation> update(List<AlertPolicy.Box> detections, long now, long age, float threshold) {
        observations = List.of();
        if (now < 0 || age < 0 || age > MAX_AGE_MS || age > now
                || (lastNow >= 0 && (now <= lastNow || now - age <= lastFrame))) {
            clear();
            return observations;
        }
        long at = now - age;
        lastNow = now;
        lastFrame = at;
        tracks.removeIf(t -> at - t.lastSeen > LOST_MS);
        var boxes = clean(detections, threshold);
        var matches = new ArrayList<Match>();
        for (Track track : tracks) {
            for (int i = 0; i < boxes.size(); i++) {
                var box = boxes.get(i);
                if (track.box.classId() != box.classId()) continue;
                float iou = overlap(track.box, box);
                if (iou >= .20f) matches.add(new Match(track, i, iou));
            }
        }
        matches.sort(Comparator.comparingDouble(Match::quality).reversed());
        boolean[] assigned = new boolean[boxes.size()];
        var updated = new ArrayList<Track>();
        var driftX = new ArrayList<Float>();
        var driftY = new ArrayList<Float>();
        var scales = new ArrayList<Float>();
        for (var match : matches) {
            Track t = match.track();
            if (assigned[match.box()] || updated.contains(t)) continue;
            var box = boxes.get(match.box());
            long gap = at - t.lastSeen;
            if (gap <= CONTINUITY_MS) {
                driftX.add(box.center() - t.box.center());
                driftY.add(centerY(box) - centerY(t.box));
                scales.add(box.area() / t.box.area());
            }
            if (gap > CONTINUITY_MS) {
                t.hits = 0;
                t.stableFrom = at;
                t.history.clear();
                t.forward = false;
            }
            int dir = stableDirection(t.direction, box.center());
            if (dir != t.direction) {
                t.stableFrom = at;
                t.hits = 0;
            }
            t.direction = dir;
            accept(t, box, at);
            updated.add(t);
            assigned[match.box()] = true;
        }
        for (int i = 0; i < boxes.size(); i++) {
            if (assigned[i]) continue;
            var t = new Track(nextId++, boxes.get(i), at);
            accept(t, boxes.get(i), at);
            tracks.add(t);
            updated.add(t);
        }
        while (tracks.size() > MAX_TRACKS) {
            tracks.remove(tracks.stream().min(Comparator.comparingLong(t -> t.lastSeen)).orElseThrow());
        }
        // Shared translation/zoom is evidence of camera motion, not several approaching objects.
        boolean cameraMotion = driftX.size() >= 3 && (Math.abs(median(driftX)) >= .025f
                || Math.abs(median(driftY)) >= .025f || Math.abs(median(scales) - 1) >= .12f);
        var result = new ArrayList<Observation>();
        for (var t : updated) {
            Sample first = t.history.peekFirst(), last = t.history.peekLast();
            long span = last.at() - first.at();
            float velocity = span >= 400 ? (last.x() - first.x()) * 1000 / span : 0;
            float growth = span >= 400 ? last.area() / first.area() : 1;
            boolean continuousMotion = span >= 400 && t.history.size() >= 4;
            float predictedX = t.box.center() + velocity * .8f;
            boolean inward = (t.box.center() < .38f && velocity > .08f && predictedX >= .38f)
                    || (t.box.center() > .62f && velocity < -.08f && predictedX <= .62f);
            boolean entering = continuousMotion && !cameraMotion && inward && t.box.y2() >= .60f
                    && consistent(t.history, false);
            boolean expanding = continuousMotion && !cameraMotion && growth >= 1.40f
                    && consistent(t.history, true);
            result.add(new Observation(t.id, t.box, t.direction,
                    t.hits >= 3 && at - t.stableFrom >= 180, t.forward, entering, expanding,
                    cameraMotion, velocity, growth));
        }
        observations = List.copyOf(result);
        return observations;
    }

    private static void accept(Track t, AlertPolicy.Box box, long at) {
        t.box = box;
        t.lastSeen = at;
        t.hits++;
        // This is an image region, not a measured walkable corridor.
        float left = t.forward ? .31f : .38f, right = t.forward ? .69f : .62f;
        float intersection = Math.max(0, Math.min(box.x2(), right) - Math.max(box.x1(), left));
        float fraction = intersection / Math.min(box.x2() - box.x1(), right - left);
        t.forward = fraction >= .45f && box.y2() >= (t.forward ? .55f : .62f);
        t.history.addLast(new Sample(at, box.center(), centerY(box), box.area()));
        while (t.history.size() > 8 || (t.history.size() > 1 && at - t.history.peekFirst().at() > 1_200)) {
            t.history.removeFirst();
        }
    }

    private static boolean consistent(ArrayDeque<Sample> history, boolean growth) {
        Sample previous = null;
        int positive = 0, negative = 0;
        for (Sample s : history) {
            if (previous != null) {
                float change = growth ? s.area() / previous.area() - 1 : s.x() - previous.x();
                if (change > (growth ? .025f : .005f)) positive++;
                if (change < (growth ? -.025f : -.005f)) negative++;
            }
            previous = s;
        }
        return growth ? positive >= 3 && negative == 0
                : (positive >= 3 && negative == 0) || (negative >= 3 && positive == 0);
    }

    private static float median(List<Float> values) {
        var sorted = new ArrayList<>(values);
        sorted.sort(Float::compare);
        return sorted.get(sorted.size() / 2);
    }

    void clear() {
        tracks.clear();
        observations = List.of();
        lastNow = lastFrame = -1;
    }

    int retainedTracks() { return tracks.size(); }
    static int direction(float x) { return x < .33f ? 0 : x > .67f ? 2 : 1; }
    private static int stableDirection(int previous, float x) {
        if (previous == 0 && x < .37f) return 0;
        if (previous == 2 && x > .63f) return 2;
        if (previous == 1 && x >= .29f && x <= .71f) return 1;
        return direction(x);
    }
    private static float centerY(AlertPolicy.Box b) { return (b.y1() + b.y2()) / 2; }
    static float overlap(AlertPolicy.Box a, AlertPolicy.Box b) {
        float intersection = Math.max(0, Math.min(a.x2(), b.x2()) - Math.max(a.x1(), b.x1()))
                * Math.max(0, Math.min(a.y2(), b.y2()) - Math.max(a.y1(), b.y1()));
        return intersection / (a.area() + b.area() - intersection);
    }
}
