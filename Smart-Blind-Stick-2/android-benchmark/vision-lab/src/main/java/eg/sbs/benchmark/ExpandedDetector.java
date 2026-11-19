package eg.sbs.benchmark;

import ai.onnxruntime.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;

final class ExpandedDetector implements AutoCloseable {
    record Label(String english, String arabic) {}
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final String input;
    private final boolean endToEnd;
    final List<Label> labels;
    private static final int SIZE = 320;

    ExpandedDetector(Context context, ModelStore store) throws Exception {
        var list = new ArrayList<Label>();
        try (var file = context.getAssets().open("yoloe-labels.json")) {
            var data = new JSONObject(new String(file.readAllBytes(), StandardCharsets.UTF_8));
            endToEnd = data.getString("output_layout").equals("end2end");
            var names = data.getJSONArray("labels");
            for (int i = 0; i < names.length(); i++) {
                var name = names.getJSONObject(i); list.add(new Label(name.getString("en"), name.getString("ar")));
            }
        }
        labels = List.copyOf(list);
        try (var options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1);
            session = environment.createSession(store.require("yoloe").getAbsolutePath(), options);
        }
        input = session.getInputNames().iterator().next();
        var info = (TensorInfo) session.getInputInfo().get(input).getInfo();
        if (info.type != OnnxJavaType.FLOAT || !Arrays.equals(info.getShape(), new long[]{1,3,SIZE,SIZE})) {
            session.close(); throw new java.io.IOException("Unexpected YOLOE input");
        }
    }

    YoloEngine.Prediction predict(byte[] jpeg, float threshold) throws Exception {
        long start = YoloEngine.now();
        Bitmap image = LabImage.decode(jpeg);
        Bitmap canvasImage = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        try {
            float scale = Math.min((float)SIZE/image.getWidth(), (float)SIZE/image.getHeight());
            int w = Math.max(1, Math.round(image.getWidth()*scale)), h = Math.max(1, Math.round(image.getHeight()*scale));
            int left = (SIZE-w)/2, top = (SIZE-h)/2;
            var canvas = new Canvas(canvasImage); canvas.drawColor(Color.rgb(114,114,114));
            canvas.drawBitmap(image, null, new Rect(left,top,left+w,top+h), new Paint(Paint.FILTER_BITMAP_FLAG));
            int[] pixels = new int[SIZE*SIZE];
            canvasImage.getPixels(pixels,0,SIZE,0,0,SIZE,SIZE);
            var rgb = ByteBuffer.allocateDirect(pixels.length*12).order(ByteOrder.nativeOrder()).asFloatBuffer();
            for (int channel = 0; channel < 3; channel++) for (int pixel : pixels)
                rgb.put(((pixel >> (16-channel*8)) & 255)/255f);
            rgb.rewind();
            long prepared = YoloEngine.now(), inferred;
            float[][] output;
            try (var tensor = OnnxTensor.createTensor(environment,rgb,new long[]{1,3,SIZE,SIZE});
                 var result = session.run(Map.of(input,tensor))) {
                inferred = YoloEngine.now(); output = ((float[][][])result.get(0).getValue())[0];
            }
            var detections = new ArrayList<YoloEngine.Detection>();
            for (var box : LabBoxes.decode(output,labels.size(),endToEnd,threshold,left,top,w,h))
                detections.add(new YoloEngine.Detection(box.label(),labels.get(box.label()).english(),box.score(),box.x1(),box.y1(),box.x2(),box.y2()));
            return new YoloEngine.Prediction(image,List.copyOf(detections),prepared-start,inferred-prepared,YoloEngine.now()-inferred);
        } catch (Exception | LinkageError e) { image.recycle(); throw e; }
        finally { canvasImage.recycle(); }
    }
    @Override public void close() throws OrtException { session.close(); }
}
