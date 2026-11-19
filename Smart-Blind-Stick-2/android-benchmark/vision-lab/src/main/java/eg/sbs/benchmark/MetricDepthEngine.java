package eg.sbs.benchmark;

import ai.onnxruntime.*;
import android.graphics.Bitmap;
import java.nio.*;
import java.util.Map;

final class MetricDepthEngine implements AutoCloseable {
    record Result(float[] meters, int width, int height, long nanos) {}
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final String input;
    MetricDepthEngine(ModelStore models) throws Exception {
        try (var options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1);
            session = environment.createSession(models.require("metric_depth").getAbsolutePath(),options);
        }
        input = session.getInputNames().iterator().next();
        var info = (TensorInfo)session.getInputInfo().get(input).getInfo();
        if (info.type != OnnxJavaType.FLOAT || info.getShape().length != 4) {
            session.close(); throw new java.io.IOException("Unexpected metric depth input");
        }
    }
    Result predict(Bitmap image) throws Exception {
        long start = YoloEngine.now();
        int longest = Math.max(image.getWidth(),image.getHeight());
        int width = LabImage.depthDimension(image.getWidth(),longest), height = LabImage.depthDimension(image.getHeight(),longest);
        Bitmap resized = Bitmap.createScaledBitmap(image,width,height,true);
        int[] pixels = new int[width*height];
        resized.getPixels(pixels,0,width,0,0,width,height);
        if (resized != image) resized.recycle();
        var values = ByteBuffer.allocateDirect(pixels.length*12).order(ByteOrder.nativeOrder()).asFloatBuffer();
        float[] mean = {.485f,.456f,.406f}, std = {.229f,.224f,.225f};
        for (int channel=0;channel<3;channel++) for (int pixel:pixels)
            values.put((((pixel>>(16-channel*8))&255)/255f-mean[channel])/std[channel]);
        values.rewind();
        try (var tensor=OnnxTensor.createTensor(environment,values,new long[]{1,3,height,width});
             var result=session.run(Map.of(input,tensor))) {
            float[][] rows=((float[][][])result.get(0).getValue())[0];
            int outHeight=rows.length,outWidth=rows[0].length;
            float[] flat=new float[outHeight*outWidth];
            for(int y=0;y<outHeight;y++) {
                if(rows[y].length!=outWidth) throw new java.io.IOException("Invalid depth output");
                System.arraycopy(rows[y],0,flat,y*outWidth,outWidth);
            }
            return new Result(flat,outWidth,outHeight,YoloEngine.now()-start);
        }
    }
    @Override public void close() throws OrtException { session.close(); }
}
