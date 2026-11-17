package eg.sbs.benchmark;

import android.content.Context;
import android.os.Build;
import android.os.Debug;
import android.os.PowerManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.ToLongFunction;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class BenchmarkRun {
    static final int WARMUP = 5;
    static final long LIVE_DURATION_MS = 15 * 60 * 1000L;
    record Sample(long at, long prep, long inference, long post, long camera, long sensor) {
        long processing() { return prep + inference + post; }
    }
    record AlertEvent(long elapsed, DecisionEngine.Decision decision, String phrase, int targetClass) {}
    record DecisionEvent(long elapsed, DecisionEngine.Decision decision, String state) {}
    record ReplayFrame(long nowMs, long ageMs, int targetClass, boolean still, List<AlertPolicy.Box> boxes) {}
    final List<AlertEvent> alerts = new ArrayList<>();
    final List<DecisionEvent> decisions = new ArrayList<>();
    final List<ReplayFrame> replayFrames = new ArrayList<>();
    String decisionMode = "SMART", lastDecisionKey;
    int searchClass = -1, droppedDecisionEvents, droppedReplayFrames;
    long decisionNanos;
    int decisionFrames;
    record Telemetry(long time, long pssKiB, int thermal) {}

    final int size;
    final float threshold;
    final boolean live;
    final String source;
    final String startedAt = Instant.now().toString();
    final long created = YoloEngine.now();
    final List<Sample> samples = new ArrayList<>();
    final List<Telemetry> telemetry = new ArrayList<>();
    long measuredFrom, measuredUntil, load = -1;
    String finishReason = "Running", cameraInput;
    int warmed;
    List<YoloEngine.Detection> lastBoxes = List.of();
    int imageWidth, imageHeight;

    BenchmarkRun(int size, float threshold, boolean live, String source) {
        this.size = size;
        this.threshold = threshold;
        this.live = live;
        this.source = source;
    }

    void recordDecision(long elapsed, DecisionEngine.Decision decision, String state) {
        String key = state + (decision == null ? "" : ":" + decision.trackId() + ":" + decision.classId()
                + ":" + decision.direction() + ":" + decision.level() + ":" + decision.reason()
                + ":" + decision.target());
        if (key.equals(lastDecisionKey)) return;
        lastDecisionKey = key;
        if (decisions.size() == 2_000) {
            droppedDecisionEvents++;
            return;
        }
        decisions.add(new DecisionEvent(elapsed, decision, state));
    }

    void recordReplay(long nowMs, long ageMs, int target, boolean still, List<AlertPolicy.Box> boxes) {
        if (replayFrames.size() == 2_000) {
            droppedReplayFrames++;
            return;
        }
        replayFrames.add(new ReplayFrame(nowMs, ageMs, target, still, SceneTracker.clean(boxes, threshold)));
    }

    void sampleDevice(Context context) {
        int thermal = context.getSystemService(PowerManager.class).getCurrentThermalStatus();
        telemetry.add(new Telemetry(YoloEngine.now() - created, Debug.getPss(), thermal));
    }

    void record(YoloEngine.Prediction prediction, int width, int height, long at, long received, long captured) {
        lastBoxes = prediction.boxes();
        imageWidth = width;
        imageHeight = height;
        samples.add(new Sample(at, prediction.prep(), prediction.inference(), prediction.post(),
                received > 0 ? at - received : -1, captured > 0 && captured < at ? at - captured : -1));
    }

    double elapsedSeconds() {
        if (measuredFrom == 0) return 0;
        return (Math.max(0, (measuredUntil == 0 ? YoloEngine.now() : measuredUntil) - measuredFrom)) / 1e9;
    }

    double averageFps() {
        double elapsed = elapsedSeconds();
        return elapsed == 0 ? 0 : samples.size() / elapsed;
    }

    double currentFps(long now) {
        if (measuredFrom == 0) return 0;
        long from = Math.max(measuredFrom, now - 3_000_000_000L);
        int count = 0;
        for (int i = samples.size() - 1; i >= 0 && samples.get(i).at() > from; i--) count++;
        return count / Math.max(1.0, (now - from) / 1e9);
    }

    private static double percentile(long[] sorted, double p) {
        return sorted.length == 0 ? -1 : ms(sorted[(int) Math.ceil(p * sorted.length) - 1]);
    }

    private static double median(long[] sorted) {
        if (sorted.length == 0) return -1;
        return (ms(sorted[(sorted.length - 1) / 2]) + ms(sorted[sorted.length / 2])) / 2;
    }

    private long[] values(ToLongFunction<Sample> metric) {
        return samples.stream().mapToLong(metric).filter(value -> value >= 0).sorted().toArray();
    }

    private String latency(String name, ToLongFunction<Sample> metric) {
        long[] sorted = values(metric);
        return sorted.length == 0 ? "" : format("%s   %.1f / %.1f ms\n",
                name, median(sorted), percentile(sorted, .95));
    }

    String report() {
        StringBuilder text = new StringBuilder();
        text.append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append("\nAndroid ").append(Build.VERSION.RELEASE).append(" · API ").append(Build.VERSION.SDK_INT)
                .append("\nSoC ").append(chipset()).append("\n\n")
                .append("YOLO26n ").append(size).append(" × ").append(size).append(" · ONNX FP32\n")
                .append(YoloEngine.BACKEND).append('\n')
                .append(live ? "Rear camera · " + (cameraInput == null ? "JPEG" : cameraInput) : "JPEG · " + source)
                .append(format("\nConfidence ≥ %.2f\n", threshold))
                .append(finishReason).append('\n')
                .append(load < 0 ? "" : format("Model load %.1f ms\n", ms(load)))
                .append(format("\n%d measured frames · %.1f s\n", samples.size(), elapsedSeconds()));
        if (!samples.isEmpty()) {
            text.append(format("Average %.2f processed FPS\n\nMedian / p95\n", averageFps()))
                    .append(latency("JPEG decode + preprocessing", Sample::prep))
                    .append(latency("Model inference", Sample::inference))
                    .append(latency("Postprocessing", Sample::post))
                    .append(latency("Total processing", Sample::processing))
                    .append(latency("Camera callback → UI", Sample::camera))
                    .append(latency("Camera sensor → UI", Sample::sensor))
                    .append("Last frame detections ").append(lastBoxes.size()).append('\n');
        }
        text.append("\nDecision mode ").append(decisionMode)
                .append(" · engine ").append(DecisionEngine.VERSION).append('\n');
        text.append(format("Spoken alerts %d · decision layer %.2f ms average\n",
                alerts.size(), decisionFrames == 0 ? 0 : ms(decisionNanos) / decisionFrames));
        if (!telemetry.isEmpty()) {
            Telemetry first = telemetry.get(0), last = telemetry.get(telemetry.size() - 1);
            text.append(format("\nProcess PSS %.1f → %.1f MiB · sampled peak %.1f MiB",
                    first.pssKiB() / 1024.0, last.pssKiB() / 1024.0, peakPss() / 1024.0))
                    .append("\nThermal ").append(thermalName(first.thermal()))
                    .append(" → ").append(thermalName(last.thermal()))
                    .append(" · peak ").append(thermalName(peakThermal()));
        }
        return text.toString();
    }

    JSONObject json() throws JSONException {
        var report = new JSONObject();
        report.put("started_utc", startedAt);
        report.put("device", Build.MANUFACTURER + " " + Build.MODEL);
        report.put("android", Build.VERSION.RELEASE);
        report.put("api", Build.VERSION.SDK_INT);
        report.put("chipset", chipset());
        report.put("abis", new JSONArray(Arrays.asList(Build.SUPPORTED_ABIS)));
        report.put("mode", live ? "live_camera" : "stored_jpeg");
        report.put("source", live ? cameraInput : source);
        report.put("model", "YOLO26n");
        report.put("format", "ONNX FP32");
        report.put("size", size);
        report.put("backend", YoloEngine.BACKEND);
        report.put("confidence", threshold);
        report.put("warmup_completed", warmed);
        report.put("model_load_ms", load < 0 ? JSONObject.NULL : ms(load));
        report.put("measured_frames", samples.size());
        report.put("duration_sec", elapsedSeconds());
        report.put("average_fps", averageFps());
        report.put("finish_reason", finishReason);

        var latency = new JSONObject();
        addLatency(latency, "preprocess", Sample::prep);
        addLatency(latency, "inference", Sample::inference);
        addLatency(latency, "postprocess", Sample::post);
        addLatency(latency, "processing_total", Sample::processing);
        addLatency(latency, "camera_callback_to_ui", Sample::camera);
        addLatency(latency, "camera_sensor_to_ui", Sample::sensor);
        report.put("latency_ms", latency);

        var frames = new JSONArray();
        for (Sample frame : samples) {
            var sample = new JSONObject();
            sample.put("elapsed_ms", ms(frame.at() - measuredFrom));
            sample.put("preprocess_ms", ms(frame.prep()));
            sample.put("inference_ms", ms(frame.inference()));
            sample.put("postprocess_ms", ms(frame.post()));
            sample.put("processing_ms", ms(frame.processing()));
            sample.put("camera_callback_to_ui_ms", frame.camera() < 0 ? JSONObject.NULL : ms(frame.camera()));
            sample.put("camera_sensor_to_ui_ms", frame.sensor() < 0 ? JSONObject.NULL : ms(frame.sensor()));
            frames.put(sample);
        }
        report.put("frames", frames);

        var conditions = new JSONArray();
        for (Telemetry entry : telemetry) {
            var sample = new JSONObject();
            sample.put("elapsed_since_start_ms", ms(entry.time()));
            sample.put("pss_kib", entry.pssKiB());
            sample.put("thermal_status", thermalName(entry.thermal()));
            conditions.put(sample);
        }
        report.put("telemetry", conditions);
        report.put("peak_sampled_pss_kib", peakPss());
        report.put("peak_thermal_status", thermalName(peakThermal()));

        var boxes = new JSONArray();
        for (var box : lastBoxes) {
            var detection = new JSONObject();
            detection.put("class_id", box.classId());
            detection.put("class", box.label());
            detection.put("confidence", box.score());
            detection.put("normalized_xyxy", new JSONArray(List.of(box.x1(), box.y1(), box.x2(), box.y2())));
            boxes.put(detection);
        }
        report.put("last_frame_width", imageWidth);
        report.put("last_frame_height", imageHeight);
        report.put("last_frame_detections", boxes);
        report.put("app_version", BuildConfig.VERSION_NAME);
        report.put("decision_engine_version", DecisionEngine.VERSION);
        report.put("decision_mode", decisionMode);
        report.put("final_search_class_id", searchClass < 0 ? JSONObject.NULL : searchClass);
        report.put("metric_distance_available", false);
        report.put("decision_average_ms", decisionFrames == 0 ? 0 : ms(decisionNanos) / decisionFrames);
        var spoken = new JSONArray();
        for (AlertEvent event : alerts) {
            spoken.put(decisionJson(event.decision())
                    .put("elapsed_since_start_ms", ms(event.elapsed())).put("phrase_ar", event.phrase())
                    .put("speech_status", "started")
                    .put("search_class_id", event.targetClass() < 0 ? JSONObject.NULL : event.targetClass()));
        }
        report.put("spoken_alerts", spoken);
        var timeline = new JSONArray();
        for (var event : decisions) {
            var entry = event.decision() == null ? new JSONObject() : decisionJson(event.decision());
            timeline.put(entry.put("elapsed_since_start_ms", ms(event.elapsed())).put("state", event.state()));
        }
        report.put("decision_events", timeline);
        report.put("dropped_decision_events", droppedDecisionEvents);
        var replay = new JSONArray();
        for (var frame : replayFrames) {
            var input = new JSONArray();
            for (var box : frame.boxes()) {
                input.put(new JSONArray(List.of(box.classId(), box.score(), box.x1(), box.y1(), box.x2(), box.y2())));
            }
            replay.put(new JSONObject().put("now_ms", frame.nowMs()).put("age_ms", frame.ageMs())
                    .put("target_class", frame.targetClass()).put("still_image", frame.still()).put("boxes", input));
        }
        report.put("decision_replay", replay);
        report.put("dropped_replay_frames", droppedReplayFrames);
        return report;
    }

    private static JSONObject decisionJson(DecisionEngine.Decision d) throws JSONException {
        return new JSONObject().put("track_id", d.trackId()).put("class_id", d.classId())
                .put("image_direction", d.direction()).put("confidence", d.confidence())
                .put("level", d.level().name()).put("reason", d.reason().name())
                .put("suggested_action", d.action().name())
                .put("priority", d.priority()).put("search_target", d.target()).put("still_image", d.still())
                .put("image_box_area", d.area()).put("image_area_ratio", d.imageGrowth())
                .put("image_horizontal_velocity_per_sec", d.imageVelocity());
    }

    private void addLatency(JSONObject report, String key, ToLongFunction<Sample> metric) throws JSONException {
        long[] sorted = values(metric);
        if (sorted.length != 0) report.put(key, new JSONObject()
                .put("median", median(sorted)).put("p95", percentile(sorted, .95)));
    }

    private long peakPss() { return telemetry.stream().mapToLong(Telemetry::pssKiB).max().orElse(0); }
    private int peakThermal() { return telemetry.stream().mapToInt(Telemetry::thermal).max().orElse(-1); }
    static double ms(long ns) { return ns / 1e6; }
    static String format(String pattern, Object... arguments) { return String.format(Locale.US, pattern, arguments); }

    private static String chipset() {
        if (!Build.UNKNOWN.equals(Build.SOC_MODEL)) return Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL;
        return Build.HARDWARE;
    }

    private static String thermalName(int state) {
        return switch (state) {
            case 0 -> "none";
            case 1 -> "light";
            case 2 -> "moderate";
            case 3 -> "severe";
            case 4 -> "critical";
            case 5 -> "emergency";
            case 6 -> "shutdown";
            default -> "unknown";
        };
    }
}
