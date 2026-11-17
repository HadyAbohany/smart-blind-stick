package eg.sbs;

import ai.onnxruntime.*;
import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.Map;

final class YoloDetector implements VisionSession.Detector {
    private static final int SIZE = 320, AREA = SIZE * SIZE;
    private static final Map<Integer, String> LABELS = Map.of(
        0, "Person", 1, "Bicycle", 2, "Car", 3, "Motorcycle", 5, "Bus",
        7, "Truck", 13, "Bench", 16, "Dog", 56, "Chair");
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final String inputName;
    private final float[] rgb = new float[3 * AREA];
    private final int[] pixels = new int[AREA];
    private final Bitmap letterbox = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
    private final Canvas canvas = new Canvas(letterbox);
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private String previous = "";
    private long lastSpoken;

    YoloDetector(Context context) throws IOException, OrtException {
        byte[] model;
        try (var stream = context.getAssets().open("yolo26n.onnx")) {
            var buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            for (int count; (count = stream.read(chunk)) != -1;) {
                if (buffer.size() + count > 20_000_000) throw new IOException("Model too large");
                buffer.write(chunk, 0, count);
            }
            model = buffer.toByteArray();
        }
        try (var options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2);
            session = environment.createSession(model, options);
        }
        inputName = session.getInputNames().iterator().next();
    }

    @Override public String classify(Bitmap bitmap) {
        float scale = Math.min((float)SIZE / bitmap.getWidth(), (float)SIZE / bitmap.getHeight());
        int width = Math.round(bitmap.getWidth() * scale), height = Math.round(bitmap.getHeight() * scale);
        int x0 = (SIZE - width) / 2, y0 = (SIZE - height) / 2;
        canvas.drawColor(Color.rgb(114, 114, 114));
        canvas.drawBitmap(bitmap, null, new Rect(x0, y0, x0 + width, y0 + height), paint);
        letterbox.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE);
        for (int i = 0; i < AREA; i++) {
            int pixel = pixels[i];
            rgb[i] = Color.red(pixel) / 255f;
            rgb[AREA + i] = Color.green(pixel) / 255f;
            rgb[2 * AREA + i] = Color.blue(pixel) / 255f;
        }
        float best = .50f;
        String label = "";
        try (var input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(rgb), new long[]{1, 3, SIZE, SIZE});
             var outputs = session.run(Map.of(inputName, input))) {
            if (!(outputs.get(0).getValue() instanceof float[][][] batch) || batch.length != 1 ||
                batch[0].length == 0 || batch[0][0].length != 6)
                return "Model output incompatible. Expected one-to-one [1,N,6].";
            for (float[] box : batch[0]) {
                float cx = (box[0] + box[2]) / 2;
                String name = LABELS.get((int)box[5]);
                if (name != null && box[4] >= best && box[2] > box[0] && box[3] > box[1] &&
                    cx >= x0 + width * .25f && cx <= x0 + width * .75f) {
                    best = box[4];
                    label = name;
                }
            }
        } catch (OrtException failed) {
            throw new IllegalStateException("ONNX inference failed", failed);
        }
        long now = SystemClock.elapsedRealtime();
        boolean speak = !label.isEmpty() && label.equals(previous) && now - lastSpoken >= 4000;
        previous = label;
        if (speak) {
            lastSpoken = now;
            return "Context: " + label;
        }
        return label.isEmpty() ? "Vision active · no contextual label" : "Vision active · " + label;
    }

    @Override public void close() {
        letterbox.recycle();
        try { session.close(); } catch (OrtException ignored) {}
    }
}
