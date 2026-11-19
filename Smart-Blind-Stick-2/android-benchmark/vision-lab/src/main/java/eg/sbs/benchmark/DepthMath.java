package eg.sbs.benchmark;

import java.util.ArrayList;
import java.util.Collections;

/** Unit-testable metric sampling. A coherent image is not a guarantee of physical accuracy. */
final class DepthMath {
    enum Source { ARCORE_RAW, DEPTH_ANYTHING_METRIC, RELATIVE }
    record Estimate(boolean available, float meters, float medianAbsoluteDeviation,
                    int accepted, int attempted, Source source, String reason) {
        static Estimate unavailable(Source source, String reason, int accepted, int attempted) {
            return new Estimate(false, Float.NaN, Float.NaN, accepted, attempted, source, reason);
        }
    }
    /** Maps normalized coordinates using an affine transform; out-of-frame samples are rejected. */
    record Transform(float xx, float xy, float x0, float yx, float yy, float y0) {
        static Transform identity() { return new Transform(1, 0, 0, 0, 1, 0); }
        float x(float x, float y) { return xx * x + xy * y + x0; }
        float y(float x, float y) { return yx * x + yy * y + y0; }
        static Transform unrotate(int degrees) {
            return switch ((degrees % 360 + 360) % 360) {
                case 0 -> identity();
                case 90 -> new Transform(0, 1, 0, -1, 0, 1);
                case 180 -> new Transform(-1, 0, 1, 0, -1, 1);
                case 270 -> new Transform(0, -1, 1, 1, 0, 0);
                default -> throw new IllegalArgumentException("Right-angle rotation required");
            };
        }
        Transform then(Transform t) {
            return new Transform(t.xx * xx + t.xy * yx, t.xx * xy + t.xy * yy,
                    t.xx * x0 + t.xy * y0 + t.x0, t.yx * xx + t.yy * yx,
                    t.yx * xy + t.yy * yy, t.yx * x0 + t.yy * y0 + t.y0);
        }
    }

    static Estimate sample(float[] depthMeters, byte[] confidence, int width, int height,
                           float x1, float y1, float x2, float y2, Transform map,
                           Source source, long ageMs) {
        if (source == Source.RELATIVE) return Estimate.unavailable(source, "relative_depth_has_no_metric_scale", 0, 0);
        if (ageMs < 0 || ageMs > 500) return Estimate.unavailable(source, "stale_depth", 0, 0);
        if (depthMeters == null || width < 1 || height < 1 || (long) width * height != depthMeters.length
                || (confidence != null && confidence.length != depthMeters.length)
                || !Float.isFinite(x1) || !Float.isFinite(x2) || !Float.isFinite(y1) || !Float.isFinite(y2)
                || x1 < 0 || y1 < 0 || x2 > 1 || y2 > 1 || x2 <= x1 || y2 <= y1 || map == null)
            return Estimate.unavailable(source, "invalid_geometry", 0, 0);
        if (source == Source.ARCORE_RAW && confidence == null)
            return Estimate.unavailable(source, "missing_raw_depth_confidence", 0, 0);
        // Use the central 50% of each object, reducing background sampling at box edges.
        float left = x1 + (x2 - x1) * .25f, top = y1 + (y2 - y1) * .25f;
        float boxWidth = (x2 - x1) * .5f, boxHeight = (y2 - y1) * .5f;
        var values = new ArrayList<Float>();
        var visited = new java.util.HashSet<Integer>();
        int attempted = 0;
        for (int y = 0; y < 7; y++) for (int x = 0; x < 7; x++) {
            float u = left + boxWidth * (x + .5f) / 7, v = top + boxHeight * (y + .5f) / 7;
            float dx = map.x(u, v), dy = map.y(u, v);
            if (!Float.isFinite(dx) || !Float.isFinite(dy) || dx < 0 || dy < 0 || dx >= 1 || dy >= 1) continue;
            int index = Math.min(height - 1, (int) (dy * height)) * width + Math.min(width - 1, (int) (dx * width));
            if (!visited.add(index)) continue;
            attempted++;
            float meters = depthMeters[index];
            if (Float.isFinite(meters) && meters >= .15f && meters <= 10
                    && (confidence == null || Byte.toUnsignedInt(confidence[index]) >= 128)) values.add(meters);
        }
        if (values.size() < 7 || values.size() < attempted * .4f)
            return Estimate.unavailable(source, "insufficient_valid_depth", values.size(), attempted);
        Collections.sort(values);
        float median = median(values);
        var deviations = new ArrayList<Float>();
        for (float value : values) deviations.add(Math.abs(value - median));
        Collections.sort(deviations);
        float mad = median(deviations);
        float tolerance = Math.max(.1f, median * .15f);
        long coherent = deviations.stream().filter(d -> d <= tolerance).count();
        if (mad > tolerance || coherent < values.size() * .6f)
            return Estimate.unavailable(source, "inconsistent_depth", values.size(), attempted);
        return new Estimate(true, median, mad, values.size(), attempted, source,
                source == Source.ARCORE_RAW ? "raw_depth_estimate" : "monocular_metric_model_estimate");
    }

    private static float median(java.util.List<Float> sorted) {
        int size = sorted.size();
        return (sorted.get((size - 1) / 2) + sorted.get(size / 2)) * .5f;
    }

    static final class Attention {
        private int hits;
        private long since = -1, last = -1;
        private boolean near;
        boolean update(Estimate estimate, long now, boolean foreground) {
            if (now < 0 || estimate == null || !estimate.available() || !foreground
                    || estimate.source() != Source.ARCORE_RAW || (last >= 0 && (now <= last || now - last > 750))) {
                hits = 0; since = -1; near = false;
                last = now;
                return false;
            }
            last = now;
            if (estimate.meters() > (near ? 1.1f : .9f)) {
                hits = 0; since = -1; near = false;
                return false;
            }
            if (since < 0) since = now;
            hits++;
            near = hits >= 3 && now - since >= 200;
            return near;
        }
    }
}
