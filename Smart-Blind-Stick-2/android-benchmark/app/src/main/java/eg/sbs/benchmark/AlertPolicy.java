package eg.sbs.benchmark;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Bounded, frame-to-frame IoU tracking. All timestamps are monotonic milliseconds. */
final class AlertPolicy {
    static final long MAX_FRAME_AGE_MS = 500;
    private static final long TRACK_TIMEOUT_MS = 900;
    private static final long GLOBAL_COOLDOWN_MS = 2_500;
    private static final long REPEAT_MS = 12_000;
    record Box(int classId, float score, float x1, float y1, float x2, float y2) {
        float center() { return (x1 + x2) / 2; }
        float area() { return (x2 - x1) * (y2 - y1); }
    }
    record Selection(int trackId, int classId, int direction, float score) {}

    private static final class Track {
        final int id;
        Box box;
        long lastSeen, stableFrom;
        int hits = 1, direction;
        int announcedDirection = -1;
        long announcedAt = Long.MIN_VALUE;

        Track(int id, Box box, long now) {
            this.id = id;
            this.box = box;
            direction = direction(box.center());
            lastSeen = stableFrom = now;
        }
    }
    private record Match(Track track, int index, float overlap) {}
    private final List<Track> tracks = new ArrayList<>();
    private int nextId = 1;
    private long lastAnnouncement = Long.MIN_VALUE;
    private Selection current;
    private final long[] lastPhrase = new long[80 * 3];

    AlertPolicy() {
        java.util.Arrays.fill(lastPhrase, Long.MIN_VALUE);
    }

    Selection update(List<Box> detections, long now, long frameAge) {
        current = null;
        tracks.removeIf(track -> now - track.lastSeen > TRACK_TIMEOUT_MS);
        if (frameAge < 0 || frameAge > MAX_FRAME_AGE_MS) {
            tracks.clear();
            return null;
        }
        List<Box> boxes = detections.stream()
                .filter(box -> box.classId() >= 0 && box.classId() < 80 && Float.isFinite(box.score())
                        && box.score() >= .45f && box.score() <= 1 && box.x1() >= 0 && box.y1() >= 0
                        && box.x2() <= 1 && box.y2() <= 1 && box.x2() > box.x1()
                        && box.y2() > box.y1() && box.area() >= .003f)
                .sorted(Comparator.comparingDouble(Box::score).reversed()).limit(40).collect(java.util.stream.Collectors.toList());
        List<Match> matches = new ArrayList<>();
        for (Track track : tracks) {
            for (int i = 0; i < boxes.size(); i++) {
                Box box = boxes.get(i);
                float overlap = overlap(track.box, box);
                if (track.box.classId() == box.classId() && overlap >= .20f) {
                    matches.add(new Match(track, i, overlap));
                }
            }
        }
        matches.sort(Comparator.comparingDouble(Match::overlap).reversed());
        boolean[] assigned = new boolean[boxes.size()];
        List<Track> updated = new ArrayList<>();
        for (Match match : matches) {
            Track track = match.track();
            if (assigned[match.index()] || updated.contains(track)) continue;
            Box box = boxes.get(match.index());
            int direction = stableDirection(track.direction, box.center());
            if (direction != track.direction || now - track.lastSeen > 450) {
                track.stableFrom = now;
                track.hits = 0;
            }
            track.box = box;
            track.direction = direction;
            track.lastSeen = now;
            track.hits++;
            assigned[match.index()] = true;
            updated.add(track);
        }
        for (int i = 0; i < boxes.size(); i++) {
            if (assigned[i]) continue;
            Track track = new Track(nextId++, boxes.get(i), now);
            tracks.add(track);
            updated.add(track);
        }
        // Bound retained unmatched tracks even in crowded, rapidly changing scenes.
        while (tracks.size() > 80) {
            Track oldest = tracks.stream().min(Comparator.comparingLong(track -> track.lastSeen)).orElseThrow();
            tracks.remove(oldest);
        }
        Track best = updated.stream()
                .filter(track -> track.hits >= 3 && now - track.stableFrom >= 180)
                .max(Comparator.comparingDouble(AlertPolicy::priority)).orElse(null);
        if (best != null) current = new Selection(best.id, best.box.classId(), best.direction, best.box.score());
        return current;
    }

    Selection announcement(long now) {
        if (current == null || (lastAnnouncement != Long.MIN_VALUE
                && now - lastAnnouncement < GLOBAL_COOLDOWN_MS)) return null;
        long last = lastPhrase[current.classId() * 3 + current.direction()];
        if (last != Long.MIN_VALUE && now - last < REPEAT_MS) return null;
        Track track = find(current.trackId());
        if (track == null || (track.announcedDirection == track.direction
                && now - track.announcedAt < REPEAT_MS)) return null;
        return current;
    }

    void announced(Selection selection, long now) {
        Track track = find(selection.trackId());
        if (track == null) return;
        track.announcedAt = now;
        track.announcedDirection = selection.direction();
        lastAnnouncement = now;
        lastPhrase[selection.classId() * 3 + selection.direction()] = now;
    }

    Selection current() { return current; }

    private Track find(int id) {
        return tracks.stream().filter(track -> track.id == id).findFirst().orElse(null);
    }

    private static double priority(Track track) {
        return (track.direction == 1 ? 2 : 0) + track.box.area() + .3 * track.box.score();
    }

    private static int direction(float center) { return center < .33f ? 0 : center > .67f ? 2 : 1; }

    private static int stableDirection(int previous, float center) {
        if (previous == 0 && center < .37f) return 0;
        if (previous == 2 && center > .63f) return 2;
        if (previous == 1 && center >= .29f && center <= .71f) return 1;
        return direction(center);
    }

    private static float overlap(Box a, Box b) {
        float intersection = Math.max(0, Math.min(a.x2(), b.x2()) - Math.max(a.x1(), b.x1()))
                * Math.max(0, Math.min(a.y2(), b.y2()) - Math.max(a.y1(), b.y1()));
        return intersection / (a.area() + b.area() - intersection);
    }
}
