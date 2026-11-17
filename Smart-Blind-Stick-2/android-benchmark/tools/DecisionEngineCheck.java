package eg.sbs.benchmark;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Executes the production policy directly on the JVM, with no Android or camera mocks. */
public final class DecisionEngineCheck {
    private static int checks;
    private static final List<String> scenarios = new ArrayList<>();
    private static double stressP95Ms;
    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void scenario(String name, Runnable test) {
        test.run();
        scenarios.add(name);
        System.out.println("PASS " + name);
    }
    private static AlertPolicy.Box box(int c, float x1, float y1, float x2, float y2) {
        return new AlertPolicy.Box(c, .9f, x1, y1, x2, y2);
    }
    private static AlertPolicy.Box left() { return box(56, .02f, .3f, .22f, .85f); }
    private static AlertPolicy.Box center() { return box(0, .41f, .4f, .59f, .85f); }
    private static AlertPolicy.Box car() { return box(2, .35f, .45f, .65f, .9f); }
    private static DecisionEngine smart() { return new DecisionEngine(DecisionEngine.Mode.SMART, .25f, -1); }
    private static DecisionEngine.Decision confirm(DecisionEngine engine, List<AlertPolicy.Box> boxes, long start) {
        engine.update(boxes, start, 30);
        engine.update(boxes, start + 100, 30);
        return engine.update(boxes, start + 200, 30);
    }
    private static void testConfirmation() {
        var e = smart();
        require(e.update(List.of(center()), 1_000, 30) == null, "One frame cannot confirm");
        require(e.update(List.of(center()), 1_100, 30) == null, "Two frames cannot confirm");
        require(e.update(List.of(center()), 1_200, 30) != null, "Three stable frames confirm");
        require(e.announcement(1_200) != null, "Forward object is eligible");
        var rapid = smart();
        rapid.update(List.of(center()), 1_000, 0);
        rapid.update(List.of(center()), 1_050, 0);
        require(rapid.update(List.of(center()), 1_100, 0) == null, "Three rapid frames still need 180 ms");
    }
    private static void testQuietScene() {
        var e = smart();
        var d = confirm(e, List.of(left()), 1_000);
        require(d != null && d.level() == DecisionEngine.Level.INFO, "Peripheral chair remains describable");
        require(e.announcement(1_200) == null, "Peripheral clutter does not speak automatically");
        require(e.isValid(d, 1_200), "Manual description can use peripheral object");
        e.update(List.of(), 1_300, 0);
        require(e.current() == null && !e.isValid(d, 1_300), "Missing objects cannot start speaking");
        require(e.announcement(1_300) == null, "Empty image never claims a clear path");
    }
    private static void testPriority() {
        var phone = box(67, .3f, .1f, .7f, .95f);
        var d = confirm(smart(), List.of(phone, center(), left()), 1_000);
        require(d.classId() == 0 && d.reason() == DecisionEngine.Reason.CENTER_OBJECT,
                "Relevant forward person beats a larger phone");
        var vehicle = confirm(smart(), List.of(car(), center()), 1_000);
        require(vehicle.classId() == 2 && vehicle.level() == DecisionEngine.Level.CAUTION,
                "Forward vehicle has caution priority");
        var large = confirm(smart(), List.of(box(56, .3f, .2f, .7f, .9f)), 1_000);
        require(large.reason() == DecisionEngine.Reason.LARGE_CENTER_OBJECT, "Large forward furniture is prominent");
        var high = confirm(smart(), List.of(box(56, .4f, .01f, .6f, .4f)), 1_000);
        require(high.level() == DecisionEngine.Level.INFO, "Upper image is not the lower forward region");
        require(confirm(smart(), List.of(left(), center()), 1_000).direction() == 1, "Forward region wins");
    }
    private static void testSearch() {
        var e = new DecisionEngine(DecisionEngine.Mode.SMART, .25f, 56);
        var target = confirm(e, List.of(left()), 1_000);
        require(target.target() && target.reason() == DecisionEngine.Reason.SEARCH_TARGET, "Requested target found");
        require(e.announcement(1_200) != null, "Requested peripheral target can speak");
        e.announced(target, 1_200);
        e.update(List.of(left(), car()), 1_300, 30);
        e.update(List.of(left(), car()), 1_400, 30);
        var danger = e.update(List.of(left(), car()), 1_500, 30);
        require(danger.classId() == 2, "Caution overrides target");
        require(e.announcement(1_949) == null, "Escalation is rate limited for 750 ms");
        var interrupt = e.announcement(1_950);
        require(interrupt != null && interrupt.level() == DecisionEngine.Level.CAUTION,
                "Caution bypasses ordinary 2.5-second cooldown");
        require(e.canInterrupt(interrupt, target), "Caution can preempt target speech");
        require(!e.canInterrupt(target, interrupt), "Target cannot preempt caution");
        e.setTargetClass(39);
        require(!e.isValid(target, 1_951) && e.current() == null, "Changing target cancels pending old speech");
        var bottle = box(39, .04f, .3f, .15f, .6f);
        require(confirm(e, List.of(bottle), 2_000).classId() == 39, "New target takes effect");
        try { e.setTargetClass(80); throw new AssertionError("Invalid target accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void testRepeatAndStarvation() {
        var e = new DecisionEngine(DecisionEngine.Mode.SMART, .25f, 56);
        var boxes = List.of(car(), left());
        var d = confirm(e, boxes, 1_000);
        e.announced(d, 1_200);
        for (long t = 1_300; t <= 3_700; t += 100) e.update(boxes, t, 0);
        var next = e.announcement(3_700);
        require(next != null && next.classId() == 56, "Suppressed top candidate does not starve another target");
        e.announced(next, 3_700);
        require(e.announcement(3_701) == null, "Global cooldown applies");
        for (long t = 3_800; t <= 7_200; t += 100) e.update(boxes, t, 0);
        require(e.announcement(7_200).classId() == 2, "Persistent caution may repeat after six seconds");

        var restart = smart();
        var first = confirm(restart, List.of(center()), 1_000);
        restart.announced(first, 1_200);
        var second = confirm(restart, List.of(center()), 3_000);
        require(second.trackId() != first.trackId(), "Expired track has a new identity");
        require(restart.announcement(4_000) == null, "Re-identification cannot spam the same phrase");
    }
    private static void testEscalation() {
        var e = smart();
        var small = box(56, .4f, .4f, .6f, .85f);
        var first = confirm(e, List.of(small), 1_000);
        require(first.level() == DecisionEngine.Level.NOTICE, "Initial notice");
        e.announced(first, 1_200);
        var large = box(56, .33f, .3f, .67f, .9f);
        e.update(List.of(large), 1_400, 0);
        var next = e.update(List.of(large), 1_950, 0);
        require(next == null, "Gap above continuity threshold requires confirmation");
        e.update(List.of(large), 2_050, 0);
        next = e.update(List.of(large), 2_150, 0);
        require(next != null && next.level() == DecisionEngine.Level.CAUTION, "Reconfirmed larger object escalates");
        require(e.announcement(2_150) != null, "Stronger same-class direction bypasses phrase repeat");
    }
    private static void testStaleAndClock() {
        var e = smart();
        var d = confirm(e, List.of(center()), 1_000);
        require(!e.isValid(d, 1_671), "Capture time, not UI receipt, defines freshness");
        require(e.announcement(1_671) == null, "No delayed announcement without updates");
        require(e.update(List.of(center()), 1_700, 501) == null, "Stale frame is silent");
        require(e.retainedTracks() == 0, "Stale frame resets evidence");
        require(e.update(List.of(center()), 1_800, 0) == null, "Fresh frame after staleness needs reconfirmation");
        require(e.update(List.of(center()), 1_900, -1) == null, "Negative age is rejected");
        require(e.update(List.of(center()), 2_000, 0) == null, "Restart confirmation");
        require(e.update(List.of(center()), 2_000, 0) == null, "Duplicate timestamp cannot count as evidence");
        e.update(List.of(center()), 2_100, 0);
        require(e.update(List.of(center()), 2_050, 0) == null, "Backwards monotonic time is rejected");
        e.invalidate();
        require(e.current() == null && !e.isValid(d, 2_200), "Explicit invalidation cancels pending speech");
        var repeated = smart();
        for (int i = 0; i < 30; i++) {
            require(repeated.update(List.of(center()), 3_000 + i * 10, i * 10) == null,
                    "Replaying one capture with new receipt times cannot confirm");
        }
    }
    private static void testInvalidInput() {
        var invalid = new ArrayList<AlertPolicy.Box>();
        invalid.add(null);
        invalid.add(new AlertPolicy.Box(-1, .9f, .3f, .3f, .6f, .9f));
        invalid.add(new AlertPolicy.Box(80, .9f, .3f, .3f, .6f, .9f));
        invalid.add(new AlertPolicy.Box(0, Float.NaN, .3f, .3f, .6f, .9f));
        invalid.add(new AlertPolicy.Box(0, 1.1f, .3f, .3f, .6f, .9f));
        invalid.add(new AlertPolicy.Box(0, .44f, .3f, .3f, .6f, .9f));
        invalid.add(box(0, Float.NaN, .2f, .6f, .9f));
        invalid.add(box(0, .3f, .2f, Float.POSITIVE_INFINITY, .9f));
        invalid.add(box(0, -.1f, .2f, .6f, .9f));
        invalid.add(box(0, .6f, .2f, .3f, .9f));
        invalid.add(box(0, .3f, .2f, .6f, 1.01f));
        invalid.add(box(0, .4f, .4f, .401f, .401f));
        require(confirm(smart(), invalid, 1_000) == null, "Malformed and weak detections are filtered");
        var strict = new DecisionEngine(DecisionEngine.Mode.SMART, .95f, -1);
        require(confirm(strict, List.of(center()), 1_000) == null, "User confidence above 45 percent is respected");
        require(confirm(smart(), null, 1_000) == null, "Null batch is empty, not a crash");
        var duplicate = new SceneTracker();
        duplicate.update(List.of(center(), center(), center()), 1_000, 0, .45f);
        require(duplicate.retainedTracks() == 1, "Duplicate same-class boxes do not make extra tracks");
    }
    private static void testMotion() {
        var e = smart();
        DecisionEngine.Decision d = null;
        for (int i = 0; i < 4; i++) {
            float x = .04f + i * .04f;
            d = e.update(List.of(box(0, x, .3f, x + .18f, .85f)), 1_000 + i * 200, 0);
        }
        require(d != null && d.reason() == DecisionEngine.Reason.ENTERING_CENTER,
                "Consistent image movement toward center gets attention");
        require(d.imageVelocity() > 0, "Image velocity is recorded without metric units");
        var growth = smart();
        for (int i = 0; i < 4; i++) {
            float w = .15f + i * .03f, h = .4f + i * .04f;
            d = growth.update(List.of(box(0, .5f - w / 2, .9f - h, .5f + w / 2, .9f)),
                    1_000 + i * 200, 0);
        }
        require(d.reason() == DecisionEngine.Reason.IMAGE_EXPANSION && d.imageGrowth() >= 1.4f,
                "Sustained image expansion is explicit evidence, not asserted proximity");
        var jitter = smart();
        float[] widths = {.18f, .18f, .23f, .18f};
        for (int i = 0; i < widths.length; i++) {
            float w = widths[i];
            d = jitter.update(List.of(box(0, .5f - w / 2, .4f, .5f + w / 2, .85f)),
                    1_000 + i * 200, 0);
        }
        require(d.reason() != DecisionEngine.Reason.IMAGE_EXPANSION, "One size spike is not expansion");
    }
    private static void testCameraMotion() {
        var t = new SceneTracker();
        List<SceneTracker.Observation> observations = List.of();
        for (int i = 0; i < 4; i++) {
            float x = .04f + i * .04f;
            observations = t.update(List.of(box(0, x, .3f, x + .18f, .85f),
                    box(56, x + .3f, .25f, x + .46f, .8f),
                    box(62, x + .5f, .1f, x + .66f, .55f)), 1_000 + i * 200, 0, .45f);
        }
        require(observations.size() == 3, "Three matched scene objects");
        require(observations.stream().allMatch(SceneTracker.Observation::cameraMotion), "Shared image translation is detected");
        require(observations.stream().noneMatch(o -> o.entering() || o.expanding()),
                "Shared camera movement suppresses individual motion escalation");
    }
    private static void testTracking() {
        var e = smart();
        var a = confirm(e, List.of(left(), center()), 1_000);
        var b = e.update(List.of(box(0, .42f, .4f, .60f, .85f)), 1_300, 0);
        require(b.trackId() == a.trackId(), "Matched object retains identity");
        require(!e.isValid(a, 1_900), "Old track snapshots expire");
        var t = new SceneTracker();
        for (int i = 0; i < 3; i++) t.update(List.of(box(0, .2f, .3f, .4f, .85f)), 1_000 + i * 100, 0, .45f);
        var o = t.update(List.of(box(0, .24f, .3f, .44f, .85f)), 1_300, 0, .45f).get(0);
        require(o.direction() == 0, "Direction hysteresis suppresses boundary jitter");
        var mixed = smart();
        var first = confirm(mixed, List.of(center()), 1_000);
        var changed = mixed.update(List.of(box(56, .41f, .4f, .59f, .85f)), 1_300, 0);
        require(changed == null && !mixed.isValid(first, 1_300), "Class change is not stable evidence");
    }
    private static void testSeverityHold() {
        var e = smart();
        var large = box(56, .3f, .2f, .7f, .9f);
        var small = box(56, .41f, .4f, .59f, .85f);
        var d = confirm(e, List.of(large), 1_000);
        require(d.level() == DecisionEngine.Level.CAUTION, "Starts with visual caution");
        d = e.update(List.of(small), 1_300, 0);
        require(d.level() == DecisionEngine.Level.CAUTION, "Short size wobble does not change spoken wording");
        e.update(List.of(small), 1_500, 0);
        d = e.update(List.of(small), 1_800, 0);
        require(d.level() == DecisionEngine.Level.NOTICE, "Old caution does not persist indefinitely");
        e.update(List.of(), 1_900, 0);
        require(e.current() == null, "Severity hold never invents an absent object");
    }
    private static void testStillImagesAndSpeech() {
        var e = smart();
        var d = e.describeStill(List.of(car()));
        require(d.still() && d.level() == DecisionEngine.Level.INFO, "JPEG cannot infer urgency");
        require(e.announcement(1_000) == null, "JPEG does not prime the live announcer");
        require(DecisionSpeech.assets(d).equals(List.of("2_1")), "JPEG uses an ordinary object phrase");
        var target = new DecisionEngine(DecisionEngine.Mode.SMART, .45f, 56);
        require(target.describeStill(List.of(left(), car())).classId() == 56, "JPEG search selects requested object");
        String[] labels = new String[80];
        java.util.Arrays.fill(labels, "جسم");
        for (var reason : DecisionEngine.Reason.values()) {
            var event = new DecisionEngine.Decision(new AlertPolicy.Selection(1, 0, 1, .9f),
                    DecisionEngine.Level.CAUTION, reason, 1, false, false, .1f, 1, 0);
            String phrase = DecisionSpeech.phrase(event, labels);
            require(!phrase.contains("الطريق آمن") && !phrase.contains("تحرك يمين") && !phrase.contains("متر"),
                    "No unmeasured safety, turn or distance claim: " + reason);
            require(!DecisionSpeech.assets(event).isEmpty(), "Every decision has bundled speech");
        }
    }
    private static void testLegacy() {
        var e = new DecisionEngine(DecisionEngine.Mode.LEGACY, .25f, 56);
        var d = confirm(e, List.of(left()), 1_000);
        require(d.reason() == DecisionEngine.Reason.DESCRIPTION && !d.target(), "Legacy ignores new search semantics");
        require(e.announcement(1_200) != null, "Legacy still automatically describes peripheral objects");
        e.announced(d, 1_200);
        require(e.announcement(1_300) == null, "Legacy cooldown preserved");
        require(DecisionSpeech.assets(d).equals(List.of("56_0")), "Legacy speech unchanged");
        require(e.update(List.of(left()), 1_400, 501) == null, "Legacy still rejects stale frames");
    }
    private static void testStress() {
        var random = new Random(20261117);
        var e = smart();
        long[] times = new long[10_000];
        long now = 1_000;
        int confirmed = 0, announcements = 0;
        for (int frame = 0; frame < times.length; frame++, now += 100) {
            var boxes = new ArrayList<AlertPolicy.Box>();
            // Persistent tracks exercise actual decisions alongside rapidly changing clutter.
            for (int i = 0; i < 12; i++) {
                float x = .02f + .22f * (i % 4) + .015f * (float) Math.sin(frame * .09);
                float y = .05f + .25f * (i / 4);
                boxes.add(new AlertPolicy.Box(i, .99f, x, y, x + .15f, y + .20f));
            }
            for (int i = 0; i < 48; i++) {
                float x = random.nextFloat() * .8f, y = random.nextFloat() * .6f;
                boxes.add(new AlertPolicy.Box(random.nextInt(80), .5f + random.nextFloat() * .5f,
                        x, y, x + .12f, y + .3f));
            }
            long start = System.nanoTime();
            var d = e.update(boxes, now, frame % 77 == 0 ? 501 : 20);
            var announce = e.announcement(now);
            times[frame] = System.nanoTime() - start;
            if (d != null) confirmed++;
            if (announce != null) {
                announcements++;
                e.announced(announce, now);
            }
            require(e.retainedTracks() <= 80 && e.rememberedPhrases() <= 240, "Memory remains bounded");
            require(d == null || (Double.isFinite(d.priority()) && Float.isFinite(d.imageGrowth())
                    && Float.isFinite(d.imageVelocity())), "Valid numerical decision");
            require(announce == null || e.isValid(announce, now), "Only fresh, confirmed candidates can be spoken");
        }
        require(confirmed > 5_000 && announcements > 50, "Crowded replay exercises stable decisions and speech eligibility");
        java.util.Arrays.sort(times);
        stressP95Ms = times[(int) Math.ceil(times.length * .95) - 1] / 1e6;
        System.out.printf(Locale.US, "Crowded synthetic replay: 10,000 frames, desktop p95 %.3f ms%n", stressP95Ms);
    }

    public static void main(String[] args) throws Exception {
        scenario("temporal_confirmation", DecisionEngineCheck::testConfirmation);
        scenario("quiet_peripheral_and_empty_scene", DecisionEngineCheck::testQuietScene);
        scenario("semantic_and_forward_priority", DecisionEngineCheck::testPriority);
        scenario("search_and_caution_preemption", DecisionEngineCheck::testSearch);
        scenario("cooldown_and_candidate_starvation", DecisionEngineCheck::testRepeatAndStarvation);
        scenario("same_phrase_severity_escalation", DecisionEngineCheck::testEscalation);
        scenario("stale_duplicate_and_reversed_time", DecisionEngineCheck::testStaleAndClock);
        scenario("malformed_low_confidence_duplicate_boxes", DecisionEngineCheck::testInvalidInput);
        scenario("image_motion_and_growth_evidence", DecisionEngineCheck::testMotion);
        scenario("shared_camera_motion_suppression", DecisionEngineCheck::testCameraMotion);
        scenario("identity_direction_and_class_changes", DecisionEngineCheck::testTracking);
        scenario("bounded_severity_hysteresis", DecisionEngineCheck::testSeverityHold);
        scenario("still_image_and_arabic_wording", DecisionEngineCheck::testStillImagesAndSpeech);
        scenario("legacy_runtime_fallback", DecisionEngineCheck::testLegacy);
        scenario("bounded_10000_frame_crowded_replay", DecisionEngineCheck::testStress);
        String json = String.format(Locale.US,
                "{\n  \"status\": \"passed\",\n  \"scenarios\": %d,\n  \"assertions\": %d,\n"
                + "  \"synthetic_stress_frames\": 10000,\n  \"desktop_stress_p95_ms\": %.4f,\n"
                + "  \"physical_phone_tested\": false,\n  \"camera_accuracy_tested\": false,\n"
                + "  \"scenario_names\": [%s]\n}\n", scenarios.size(), checks, stressP95Ms,
                scenarios.stream().map(s -> "\"" + s + "\"").collect(java.util.stream.Collectors.joining(", ")));
        if (args.length > 0) Files.writeString(Path.of(args[0]), json);
        System.out.println("Decision engine: " + scenarios.size() + " scenarios, " + checks + " checks passed");
    }
}
