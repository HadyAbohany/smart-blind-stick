package eg.sbs.benchmark;

import android.os.Build;
import android.os.Debug;
import android.content.Context;
import android.os.PowerManager;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class LabReport {
    final String startedAt = java.time.Instant.now().toString();
    final String mode;
    final long startedNs = YoloEngine.now();
    private final List<Long> processing = new ArrayList<>();
    private final List<JSONObject> depth = new ArrayList<>();
    private final List<JSONObject> events = new ArrayList<>();
    private final List<JSONObject> telemetry = new ArrayList<>();
    private int dropped;
    private long endedNs;
    String finishReason = "running";
    LabReport(String mode) { this.mode = mode; }
    synchronized void processing(long nanos) { if (processing.size() < 10_000) processing.add(nanos); else dropped++; }
    synchronized void depth(DepthMath.Estimate e, String subject) {
        if (e == null || depth.size() >= 1000) return;
        try {
            depth.add(new JSONObject().put("elapsed_ms",elapsedMs()).put("available",e.available())
                    .put("subject",subject).put("source",e.source()).put("reason",e.reason())
                    .put("meters",e.available()?e.meters():JSONObject.NULL)
                    .put("spatial_mad_m",e.available()?e.medianAbsoluteDeviation():JSONObject.NULL)
                    .put("valid_samples",e.accepted()).put("attempted_samples",e.attempted()));
        } catch (JSONException error) { throw new IllegalStateException(error); }
    }
    synchronized void event(String type, String detail) {
        if (events.size() >= 1000) return;
        try { events.add(new JSONObject().put("elapsed_ms",elapsedMs()).put("type",type).put("detail",detail)); }
        catch (JSONException error) { throw new IllegalStateException(error); }
    }
    synchronized void sample(Context context) {
        if (telemetry.size() >= 1000) return;
        try { telemetry.add(new JSONObject().put("elapsed_ms",elapsedMs()).put("pss_kib",Debug.getPss())
                .put("thermal_status",context.getSystemService(PowerManager.class).getCurrentThermalStatus())); }
        catch (JSONException error) { throw new IllegalStateException(error); }
    }
    synchronized void finish(String reason) { if (endedNs == 0) { endedNs = YoloEngine.now(); finishReason = reason; } }
    private double elapsedMs() { return (YoloEngine.now()-startedNs)/1e6; }
    synchronized JSONObject json() throws JSONException {
        long[] sorted = processing.stream().mapToLong(Long::longValue).sorted().toArray();
        double duration = ((endedNs==0?YoloEngine.now():endedNs)-startedNs)/1e9;
        return new JSONObject().put("app_version",eg.sbs.visionlab.BuildConfig.VERSION_NAME)
                .put("onnxruntime","1.24.2").put("mode",mode).put("started_utc",startedAt).put("device",Build.MANUFACTURER+" "+Build.MODEL)
                .put("android_api",Build.VERSION.SDK_INT).put("duration_seconds",duration)
                .put("measured_frames",processing.size()).put("finish_reason",finishReason)
                .put("processing_median_ms",sorted.length==0?JSONObject.NULL:sorted[sorted.length/2]/1e6)
                .put("processing_p95_ms",sorted.length==0?JSONObject.NULL:sorted[(int)Math.ceil(.95*sorted.length)-1]/1e6)
                .put("dropped_processing_samples",dropped)
                .put("depth_samples",new JSONArray(depth)).put("events",new JSONArray(events))
                .put("telemetry",new JSONArray(telemetry)).put("images_recorded",false)
                .put("distance_calibrated_against_ground_truth",false);
    }
}
