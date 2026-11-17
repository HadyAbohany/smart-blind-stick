package eg.sbs.benchmark;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Replays normalized detections through the actual engine. Playback availability is not simulated. */
public final class DecisionReplay {
    private static final String HEADER = "scenario,mode,target_class,now_ms,age_ms,class_id,confidence,x1,y1,x2,y2,expected_reason,expected_announcement";
    private static final class Frame {
        final String scenario, mode;
        final long now, age;
        final int target;
        final List<AlertPolicy.Box> boxes = new ArrayList<>();
        String reason, announcement;
        Frame(String[] c) {
            scenario = c[0]; mode = c[1]; target = Integer.parseInt(c[2]);
            now = Long.parseLong(c[3]); age = Long.parseLong(c[4]);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: DecisionReplay input.csv output.tsv");
        var lines = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).equals(HEADER)) throw new IllegalArgumentException("Unexpected CSV header");
        var frames = new LinkedHashMap<String, Frame>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank() || lines.get(i).startsWith("#")) continue;
            var c = lines.get(i).split(",", -1);
            if (c.length != 13) throw new IllegalArgumentException("Expected 13 columns at line " + (i + 1));
            var f = frames.computeIfAbsent(c[0] + ":" + c[3], key -> new Frame(c));
            if (!f.mode.equals(c[1]) || f.target != Integer.parseInt(c[2]) || f.age != Long.parseLong(c[4])) {
                throw new IllegalArgumentException("Inconsistent metadata for a frame at line " + (i + 1));
            }
            if (!c[5].isBlank()) f.boxes.add(new AlertPolicy.Box(Integer.parseInt(c[5]), Float.parseFloat(c[6]),
                    Float.parseFloat(c[7]), Float.parseFloat(c[8]), Float.parseFloat(c[9]), Float.parseFloat(c[10])));
            if (!c[11].isBlank()) f.reason = c[11];
            if (!c[12].isBlank()) f.announcement = c[12];
        }
        var engines = new LinkedHashMap<String, DecisionEngine>();
        var output = new StringBuilder("scenario\tnow_ms\tclass_id\tdirection\tlevel\treason\taction\teligible_announcement\n");
        int checks = 0;
        for (Frame f : frames.values()) {
            var engine = engines.computeIfAbsent(f.scenario, key -> new DecisionEngine(
                    DecisionEngine.Mode.valueOf(f.mode), .45f, f.target));
            if (!engine.mode().name().equals(f.mode)) throw new IllegalArgumentException("Mode changes need a new scenario");
            engine.setTargetClass(f.target);
            var decision = engine.update(f.boxes, f.now, f.age);
            var announcement = engine.announcement(f.now);
            String reason = decision == null ? "NONE" : decision.reason().name();
            String eligible = announcement == null ? "NONE" : announcement.reason().name();
            if (f.reason != null) {
                checks++;
                if (!f.reason.equals(reason)) throw new AssertionError(f.scenario + " at " + f.now
                        + ": expected " + f.reason + ", got " + reason);
            }
            if (f.announcement != null) {
                checks++;
                if (!f.announcement.equals(eligible)) throw new AssertionError(f.scenario + " at " + f.now
                        + ": expected eligible announcement " + f.announcement + ", got " + eligible);
            }
            output.append(String.format(Locale.US, "%s\t%d\t%s\t%s\t%s\t%s\t%s\t%s%n", f.scenario, f.now,
                    decision == null ? "" : decision.classId(), decision == null ? "" : decision.direction(),
                    decision == null ? "" : decision.level(), reason, decision == null ? "" : decision.action(), eligible));
            if (announcement != null) engine.announced(announcement, f.now);
        }
        Files.writeString(Path.of(args[1]), output, StandardCharsets.UTF_8);
        System.out.println("CSV replay: " + engines.size() + " scenarios, " + frames.size() + " frames, " + checks + " expectations passed");
    }
}
