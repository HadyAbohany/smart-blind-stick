package eg.sbs.benchmark;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Single-worker inference path for camera JPEGs, selected files and future Wi-Fi snapshots. */
final class YoloEngine implements AutoCloseable {
    static final String BACKEND = "ONNX Runtime 1.20.0 · CPU (2 threads)";
    static final int JPEG_LIMIT = 32 * 1024 * 1024;
    record Detection(int classId, String label, float score, float x1, float y1, float x2, float y2) {}
    record Prediction(Bitmap image, List<Detection> boxes, long prep, long inference, long post) {
        long processing() { return prep + inference + post; }
    }

    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final String inputName;
    private final int size;
    private final String[] labels;
    private final int[] pixels;
    private final float[] channels;
    private final FloatBuffer rgb;
    private final Bitmap letterbox;
    private final Canvas canvas;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

    YoloEngine(AssetManager assets, int size) throws IOException, OrtException {
        this.size = size;
        byte[] model;
        try (var stream = assets.open("yolo26n-" + size + ".onnx")) {
            var bytes = new ByteArrayOutputStream(10 * 1024 * 1024);
            byte[] chunk = new byte[16384];
            for (int count; (count = stream.read(chunk)) != -1;) bytes.write(chunk, 0, count);
            model = bytes.toByteArray();
        }
        try (var reader = new BufferedReader(new InputStreamReader(
                assets.open("coco-labels.txt"), StandardCharsets.UTF_8))) {
            List<String> names = new ArrayList<>();
            for (String line; (line = reader.readLine()) != null;) names.add(line);
            labels = names.toArray(new String[0]);
        }
        if (labels.length != 80) throw new IOException("Expected 80 COCO labels");
        pixels = new int[size * size];
        channels = new float[3 * pixels.length];
        rgb = ByteBuffer.allocateDirect(channels.length * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        letterbox = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        canvas = new Canvas(letterbox);
        try (var options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2);
            options.setInterOpNumThreads(1);
            session = environment.createSession(model, options);
        }
        try {
            var inputs = session.getInputInfo();
            var outputs = session.getOutputInfo();
            if (inputs.size() != 1 || outputs.size() != 1) {
                throw new IOException("Expected one model input and one output");
            }
            inputName = inputs.keySet().iterator().next();
            checkTensor(inputs.get(inputName).getInfo(), 1, 3, size, size);
            checkTensor(outputs.values().iterator().next().getInfo(), 1, 300, 6);
        } catch (IOException | OrtException error) {
            close();
            throw error;
        }
    }

    private static void checkTensor(Object info, long... shape) throws IOException {
        if (!(info instanceof TensorInfo tensor) || tensor.type != OnnxJavaType.FLOAT
                || !Arrays.equals(tensor.getShape(), shape)) {
            throw new IOException("Expected float32 " + Arrays.toString(shape) + ", received " + info);
        }
    }

    Prediction predict(byte[] jpeg, float threshold) throws IOException, OrtException {
        long start = now();
        if (jpeg.length < 3 || jpeg.length > JPEG_LIMIT || jpeg[0] != (byte) 0xff
                || jpeg[1] != (byte) 0xd8 || jpeg[2] != (byte) 0xff) {
            throw new IOException("Choose a valid JPEG up to 32 MiB");
        }
        Bitmap image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(jpeg)),
                (decoder, info, source) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
                    var dimensions = info.getSize();
                    int longestSide = Math.max(dimensions.getWidth(), dimensions.getHeight());
                    if (longestSide > 1600) {
                        float scale = 1600f / longestSide;
                        decoder.setTargetSize(Math.max(1, Math.round(dimensions.getWidth() * scale)),
                                Math.max(1, Math.round(dimensions.getHeight() * scale)));
                    }
                });
        boolean complete = false;
        try {
            float scale = Math.min((float) size / image.getWidth(), (float) size / image.getHeight());
            int width = Math.max(1, Math.round(image.getWidth() * scale));
            int height = Math.max(1, Math.round(image.getHeight() * scale));
            int left = (size - width) / 2;
            int top = (size - height) / 2;
            canvas.drawColor(Color.rgb(114, 114, 114));
            canvas.drawBitmap(image, null, new Rect(left, top, left + width, top + height), paint);
            letterbox.getPixels(pixels, 0, size, 0, 0, size, size);
            int area = pixels.length;
            for (int i = 0; i < area; i++) {
                int pixel = pixels[i];
                channels[i] = Color.red(pixel) / 255f;
                channels[area + i] = Color.green(pixel) / 255f;
                channels[2 * area + i] = Color.blue(pixel) / 255f;
            }
            rgb.put(channels).rewind();
            float[][] rows;
            long prepared, inferred;
            try (var tensor = OnnxTensor.createTensor(environment, rgb, new long[]{1, 3, size, size})) {
                prepared = now();
                try (var outputs = session.run(Map.of(inputName, tensor))) {
                    inferred = now();
                    rows = ((float[][][]) outputs.get(0).getValue())[0];
                }
            }
            List<Detection> detections = new ArrayList<>();
            for (float[] row : rows) {
                float score = row[4];
                int label = (int) row[5];
                if (!Float.isFinite(score) || score < threshold || score > 1
                        || row[5] != label || label < 0 || label >= labels.length
                        || !Float.isFinite(row[0]) || !Float.isFinite(row[1])
                        || !Float.isFinite(row[2]) || !Float.isFinite(row[3])) continue;
                float x1 = clamp((row[0] - left) / width), x2 = clamp((row[2] - left) / width);
                float y1 = clamp((row[1] - top) / height), y2 = clamp((row[3] - top) / height);
                if (x2 > x1 && y2 > y1) {
                    detections.add(new Detection(label, labels[label], score, x1, y1, x2, y2));
                }
            }
            complete = true;
            return new Prediction(image, List.copyOf(detections),
                    prepared - start, inferred - prepared, now() - inferred);
        } finally {
            if (!complete) image.recycle();
        }
    }

    static long now() { return SystemClock.elapsedRealtimeNanos(); }
    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }

    @Override public void close() throws OrtException {
        letterbox.recycle();
        session.close();
    }
}
