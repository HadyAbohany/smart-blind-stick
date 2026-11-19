package eg.sbs.benchmark;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class LabBoxes {
    record Box(int label, float score, float x1, float y1, float x2, float y2) {}
    static List<Box> decode(float[][] values, int classes, boolean endToEnd, float threshold,
                            int padX, int padY, int scaledWidth, int scaledHeight) {
        if (classes < 1 || classes > 2000 || values == null || values.length == 0
                || scaledWidth < 1 || scaledHeight < 1) throw new IllegalArgumentException("Invalid detection tensor");
        var boxes = new ArrayList<Box>();
        if (endToEnd) {
            for (int i = 0; i < Math.min(values.length, 1000); i++) {
                var row = values[i];
                if (row == null || row.length < 6) continue;
                int label = (int) row[5];
                if (row[5] != label) continue;
                add(boxes, label, row[4], row[0], row[1], row[2], row[3], classes,
                        threshold, padX, padY, scaledWidth, scaledHeight);
            }
        } else {
            if (values.length < classes + 4 || values[0] == null) throw new IllegalArgumentException("Invalid raw detection head");
            int columns = values[0].length;
            for (float[] row : values) if (row == null || row.length != columns) throw new IllegalArgumentException("Ragged detection tensor");
            for (int i = 0; i < Math.min(columns, 30_000); i++) {
                int label = -1; float score = 0;
                for (int c = 0; c < classes; c++) if (Float.isFinite(values[c + 4][i]) && values[c + 4][i] > score) {
                    label = c; score = values[c + 4][i];
                }
                float x = values[0][i], y = values[1][i], w = values[2][i], h = values[3][i];
                if (w <= 0 || h <= 0) continue;
                add(boxes, label, score, x-w/2, y-h/2, x+w/2, y+h/2, classes,
                        threshold, padX, padY, scaledWidth, scaledHeight);
            }
        }
        boxes.sort(Comparator.comparingDouble(Box::score).reversed());
        var kept = new ArrayList<Box>();
        for (Box candidate : boxes) {
            boolean duplicate = kept.stream().anyMatch(b -> overlap(b, candidate) > (b.label == candidate.label ? .45f : .8f));
            if (!duplicate) kept.add(candidate);
            if (kept.size() == 50) break;
        }
        return List.copyOf(kept);
    }
    private static void add(List<Box> boxes, int label, float score, float x1, float y1, float x2, float y2,
                            int classes, float threshold, int px, int py, int width, int height) {
        if (label < 0 || label >= classes || !Float.isFinite(score) || score < threshold || score > 1
                || !Float.isFinite(x1) || !Float.isFinite(x2) || !Float.isFinite(y1) || !Float.isFinite(y2)) return;
        x1 = clamp((x1 - px) / width); x2 = clamp((x2 - px) / width);
        y1 = clamp((y1 - py) / height); y2 = clamp((y2 - py) / height);
        if (x2 > x1 && y2 > y1) boxes.add(new Box(label, score, x1, y1, x2, y2));
    }
    private static float clamp(float v) { return Math.max(0, Math.min(1, v)); }
    private static float overlap(Box a, Box b) {
        float intersection = Math.max(0, Math.min(a.x2,b.x2)-Math.max(a.x1,b.x1))
                * Math.max(0, Math.min(a.y2,b.y2)-Math.max(a.y1,b.y1));
        return intersection / ((a.x2-a.x1)*(a.y2-a.y1)+(b.x2-b.x1)*(b.y2-b.y1)-intersection);
    }
}
