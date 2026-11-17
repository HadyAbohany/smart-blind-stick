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
    private static AlertPolicy.Box car() { return box(2, .2f, .2f, .8f, .95f); }
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
        require(d != null && d.level() == DecisionEngine.Level.INFO, "Peripheral object is an ordinary description");
        require(e.announcement(1_200) != null, "Room description includes peripheral objects automatically");
        require(e.isValid(d, 1_200), "Manual description can use peripheral object");
        e.update(List.of(), 1_300, 0);
        require(e.current() == null && !e.isValid(d, 1_300), "Missing objects cannot start a new description");
        require(e.announcement(1_300) == null, "Empty image remains silent");
    }
    private static void testPriority() {
        var normalCar = box(2, .35f, .45f, .65f, .9f);
        var d = confirm(smart(), List.of(normalCar, center(), left()), 1_000);
        require(d.level() == DecisionEngine.Level.INFO, "Ordinary center objects do not trigger attention");
        require(d.reason() == DecisionEngine.Reason.DESCRIPTION, "Vehicle class alone is not a proximity warning");
        var near = confirm(smart(), List.of(car(), center()), 1_000);
        require(near.classId() == 2 && near.reason() == DecisionEngine.Reason.VISUALLY_NEAR_CENTER,
                "Sustained large foreground object gets the short attention cue");
        var moderatelyLarge = confirm(smart(), List.of(box(56, .3f, .2f, .7f, .9f)), 1_000);
        require(moderatelyLarge.level() == DecisionEngine.Level.INFO, "Moderate prominence remains a description");
        var high = confirm(smart(), List.of(box(56, .4f, .01f, .6f, .4f)), 1_000);
        require(high.level() == DecisionEngine.Level.INFO, "Upper image does not count as lower foreground");
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
        for (long t = 3_800; t <= 11_200; t += 100) e.update(boxes, t, 0);
        require(e.announcement(11_200).classId() == 2, "Persistent visual attention may repeat after ten seconds");

        var restart = smart();
        var first = confirm(restart, List.of(center()), 1_000);
        restart.announced(first, 1_200);
        var second = confirm(restart, List.of(center()), 3_000);
        require(second.trackId() != first.trackId(), "Expired track has a new identity");
        require(restart.announcement(4_000) == null, "Re-identification cannot spam the same phrase");
    }
    private static void testEscalation() {
        var e = smart();
        var small = box(56, .35f, .4f, .65f, .85f);
        var first = confirm(e, List.of(small), 1_000);
        require(first.level() == DecisionEngine.Level.INFO, "Initial ordinary description");
        e.announced(first, 1_200);
        var large = box(56, .2f, .15f, .8f, .95f);
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
        require(d != null && d.level() == DecisionEngine.Level.INFO,
                "Small moving objects are described without a proximity warning");
        require(d.imageVelocity() > 0, "Image velocity is recorded without metric units");
        var growth = smart();
        for (int i = 0; i < 4; i++) {
            float w = .15f + i * .03f, h = .4f + i * .04f;
            d = growth.update(List.of(box(0, .5f - w / 2, .9f - h, .5f + w / 2, .9f)),
                    1_000 + i * 200, 0);
        }
        require(d.level() == DecisionEngine.Level.INFO && d.imageGrowth() >= 1.4f,
                "Image expansion alone is not enough to claim a near foreground object");
        var jitter = smart();
        float[] widths = {.18f, .18f, .23f, .18f};
        for (int i = 0; i < widths.length; i++) {
            float w = widths[i];
            d = jitter.update(List.of(box(0, .5f - w / 2, .4f, .5f + w / 2, .85f)),
                    1_000 + i * 200, 0);
        }
        require(d.level() == DecisionEngine.Level.INFO, "Size jitter does not trigger attention");
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
        var large = box(56, .25f, .15f, .75f, .85f);
        var medium = box(56, .26f, .25f, .74f, .85f);
        var small = box(56, .4f, .25f, .6f, .85f);
        var d = confirm(e, List.of(large), 1_000);
        require(d.level() == DecisionEngine.Level.CAUTION, "Sustained prominent foreground gets attention");
        d = e.update(List.of(medium), 1_300, 0);
        require(d.level() == DecisionEngine.Level.CAUTION, "Visual prominence has exit hysteresis");
        d = e.update(List.of(small), 1_400, 0);
        require(d.level() == DecisionEngine.Level.INFO, "An ordinary smaller object returns to description");
        e.update(List.of(), 1_500, 0);
        require(e.current() == null, "Hysteresis never invents an absent object");
        var spike = smart();
        var ordinary = box(56, .35f, .3f, .65f, .85f);
        confirm(spike, List.of(ordinary), 2_000);
        var big = box(56, .2f, .2f, .8f, .9f);
        require(spike.update(List.of(big), 2_300, 0).level() == DecisionEngine.Level.INFO,
                "A single prominent frame cannot trigger attention");
        spike.update(List.of(big), 2_400, 0);
        require(spike.update(List.of(big), 2_500, 0).level() == DecisionEngine.Level.CAUTION,
                "Prominence must persist for three observations");
    }
    private static void testRoomSummaryAndPacing() {
        var e = smart();
        var objects = List.of(left(), center(), box(39, .7f, .4f, .85f, .8f),
                box(67, .7f, .1f, .85f, .3f));
        confirm(e, objects, 1_000);
        var scene = e.describeScene(1_200);
        require(scene.size() == 3, "Manual room summary is limited to three confirmed descriptions");
        require(scene.stream().map(DecisionEngine.Decision::phraseKey).distinct().count() == 3,
                "Room summary does not repeat equivalent class and direction phrases");
        require(e.describeScene(1_800).isEmpty(), "An old scene cannot start a room summary");
        int emitted = 0;
        for (long t = 1_300; t <= 14_000; t += 100) {
            e.update(objects, t, 0);
            var next = e.announcement(t);
            if (next != null) { e.announced(next, t); emitted++; }
        }
        require(emitted == 3, "Automatic room descriptions allow three context items per fifteen seconds");
        for (long t = 14_100; t <= 16_500; t += 100) e.update(objects, t, 0);
        require(e.announcement(16_500) != null, "Unmentioned room items become eligible after the context pause");
    }
    private static void testSpeechContinuity() {
        var e = smart();
        var decision = confirm(e, List.of(center()), 1_000);
        boolean[] session = {true};
        long[] capture = {1_170};
        var plan = new SpeechPlan(() -> e.isValid(decision, 1_200),
                () -> session[0] && SpeechPlan.recentCamera(capture[0] + 100, capture[0]));
        require(plan.valid(1_200), "Fresh confirmed evidence permits the first clip");
        plan.started(1_200);
        e.update(List.of(), 1_300, 0);
        capture[0] = 1_300;
        require(!e.isValid(decision, 1_300) && plan.valid(1_300),
                "One missed detection must not cut an accepted sentence or its next clip");
        session[0] = false;
        require(!plan.valid(1_400), "Stopping, muting or changing target cancels further clips");
        session[0] = true;
        require(!plan.valid(1_100), "Backwards time cannot continue a sentence");
        plan.started(5_000);
        require(!plan.valid(1_200 + SpeechPlan.MAX_DURATION_MS + 1), "Later clips cannot reset the speech duration limit");
        require(SpeechPlan.recentCamera(2_799, 1_300), "Brief camera gaps do not clip speech");
        require(!SpeechPlan.recentCamera(2_800, 1_300), "A 1.5-second loss of usable camera updates stops speech");
        require(!SpeechPlan.recentCamera(1_200, 1_300), "Future camera timestamps cannot sustain speech");
        var pending = new SpeechPlan(() -> false, () -> true);
        require(!pending.valid(1_500), "Continuation grace cannot start an unconfirmed sentence");
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
            require(!phrase.contains("الطريق آمن") && !phrase.contains("تحرك يمين") && !phrase.contains("متر")
                    && !phrase.contains("عصاي") && !phrase.contains("تمهّل") && !phrase.contains("اتحقق"),
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
        scenario("room_peripheral_and_empty_scene", DecisionEngineCheck::testQuietScene);
        scenario("sustained_near_foreground_only_attention", DecisionEngineCheck::testPriority);
        scenario("search_and_caution_preemption", DecisionEngineCheck::testSearch);
        scenario("cooldown_and_candidate_starvation", DecisionEngineCheck::testRepeatAndStarvation);
        scenario("same_phrase_severity_escalation", DecisionEngineCheck::testEscalation);
        scenario("stale_duplicate_and_reversed_time", DecisionEngineCheck::testStaleAndClock);
        scenario("malformed_low_confidence_duplicate_boxes", DecisionEngineCheck::testInvalidInput);
        scenario("motion_is_description_without_near_evidence", DecisionEngineCheck::testMotion);
        scenario("shared_camera_motion_suppression", DecisionEngineCheck::testCameraMotion);
        scenario("identity_direction_and_class_changes", DecisionEngineCheck::testTracking);
        scenario("visual_proximity_hysteresis_and_confirmation", DecisionEngineCheck::testSeverityHold);
        scenario("room_summary_and_context_pacing", DecisionEngineCheck::testRoomSummaryAndPacing);
        scenario("speech_survives_detection_jitter", DecisionEngineCheck::testSpeechContinuity);
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
