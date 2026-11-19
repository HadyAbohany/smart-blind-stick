package eg.sbs.benchmark;

import android.app.Activity;
import android.graphics.Bitmap;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.Image;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.view.Surface;
import com.google.ar.core.*;
import com.google.ar.core.exceptions.NotYetAvailableException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Copies RGB and raw/confidence depth from one ARCore frame before handing data to a worker. */
final class ArDepthCamera implements GLSurfaceView.Renderer, AutoCloseable {
    record Depth(float[] meters, byte[] confidence, int width, int height,
                 DepthMath.Transform imageToDepth, long capturedAtNs, long sensorTimestampNs, String state) {}
    interface Listener {
        boolean wantsFrame();
        void onFrame(byte[] jpeg, long receivedNs, Depth depth);
        void onError(String message);
    }
    private final Activity activity;
    private final GLSurfaceView surface;
    private final Listener listener;
    private final Session session;
    private final int rotationDegrees;
    private volatile boolean closed, resumed;
    private long lastFrame;

    ArDepthCamera(Activity activity, GLSurfaceView surface, Listener listener) throws Exception {
        this.activity = activity; this.surface = surface; this.listener = listener;
        session = new Session(activity);
        try {
            Config config = session.getConfig();
            Config.DepthMode mode = session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)
                    ? Config.DepthMode.RAW_DEPTH_ONLY : Config.DepthMode.AUTOMATIC;
            if (!session.isDepthModeSupported(mode)) throw new IllegalStateException("Depth API is not supported on this device");
            config.setDepthMode(mode);
            config.setFocusMode(Config.FocusMode.AUTO);
            config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
            session.configure(config);
            var manager = activity.getSystemService(CameraManager.class);
            var info = manager.getCameraCharacteristics(session.getCameraConfig().getCameraId());
            Integer orientation = info.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int display = switch (activity.getDisplay().getRotation()) {
                case Surface.ROTATION_90 -> 90;
                case Surface.ROTATION_180 -> 180;
                case Surface.ROTATION_270 -> 270;
                default -> 0;
            };
            rotationDegrees = ((orientation == null ? 90 : orientation) - display + 360) % 360;
            surface.setEGLContextClientVersion(3);
            surface.setPreserveEGLContextOnPause(true);
            surface.setRenderer(this);
            session.resume();
            resumed = true;
            surface.onResume();
        } catch (Exception error) {
            closed = true; session.close(); throw error;
        }
    }

    @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        if (closed) return;
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        session.setCameraTextureName(textures[0]);
    }
    @Override public void onSurfaceChanged(GL10 gl, int width, int height) {
        if (!closed && width > 0 && height > 0)
            session.setDisplayGeometry(activity.getDisplay().getRotation(), width, height);
        GLES20.glViewport(0, 0, width, height);
    }
    @Override public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (closed || !resumed) return;
        boolean accepted = false;
        try {
            Frame frame = session.update();
            if (frame.getTimestamp() == 0 || frame.getTimestamp() == lastFrame) return;
            lastFrame = frame.getTimestamp();
            if (!listener.wantsFrame()) return;
            accepted = true;
            long received = YoloEngine.now();
            try (Image image = frame.acquireCameraImage()) {
                Depth depth = frame.getCamera().getTrackingState() == TrackingState.TRACKING
                        ? copyDepth(frame, image.getWidth(), image.getHeight(), received)
                        : new Depth(null, null, 0, 0, DepthMath.Transform.identity(), received, 0, "tracking_unavailable");
                Bitmap raw = yuv(image), oriented = LabImage.rotate(raw, rotationDegrees);
                byte[] jpeg;
                try { jpeg = LabImage.jpeg(oriented); }
                finally { if (oriented != raw) oriented.recycle(); raw.recycle(); }
                listener.onFrame(jpeg, received, depth);
            }
        } catch (NotYetAvailableException unavailable) {
            // A missing CPU image must release the caller's in-flight slot as well.
            if (accepted) listener.onFrame(null, YoloEngine.now(), null);
        } catch (Exception | LinkageError error) {
            if (!closed) listener.onError("ARCore: " + error.getMessage());
        }
    }

    private Depth copyDepth(Frame frame, int width, int height, long received) {
        try (Image raw = frame.acquireRawDepthImage16Bits(); Image confidence = frame.acquireRawDepthConfidenceImage()) {
            if (raw.getWidth() != confidence.getWidth() || raw.getHeight() != confidence.getHeight()
                    || raw.getTimestamp() != confidence.getTimestamp()
                    || frame.getTimestamp() < raw.getTimestamp()
                    || frame.getTimestamp() - raw.getTimestamp() > 250_000_000L)
                return new Depth(null, null, 0, 0, DepthMath.Transform.identity(), received, 0, "unpaired_or_stale_depth");
            int w = raw.getWidth(), h = raw.getHeight();
            float[] meters = new float[w*h]; byte[] quality = new byte[w*h];
            var plane = raw.getPlanes()[0]; var conf = confidence.getPlanes()[0];
            ByteBuffer data = plane.getBuffer().duplicate().order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer c = conf.getBuffer().duplicate();
            int base = data.position(), confBase = c.position();
            for (int y=0;y<h;y++) for (int x=0;x<w;x++) {
                meters[y*w+x] = Short.toUnsignedInt(data.getShort(base+y*plane.getRowStride()+x*plane.getPixelStride()))/1000f;
                quality[y*w+x] = c.get(confBase+y*conf.getRowStride()+x*conf.getPixelStride());
            }
            float[] from = {0,0,width,0,0,height}, to = new float[6];
            frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS,from,Coordinates2d.TEXTURE_NORMALIZED,to);
            var rawToDepth = new DepthMath.Transform(to[2]-to[0],to[4]-to[0],to[0],to[3]-to[1],to[5]-to[1],to[1]);
            return new Depth(meters,quality,w,h,DepthMath.Transform.unrotate(rotationDegrees).then(rawToDepth),received-(frame.getTimestamp()-raw.getTimestamp()),raw.getTimestamp(),"raw_depth_with_confidence");
        } catch (NotYetAvailableException | IllegalStateException unavailable) {
            return new Depth(null,null,0,0,DepthMath.Transform.identity(),received,0,"depth_not_available_yet");
        }
    }

    private static Bitmap yuv(Image image) {
        int width=image.getWidth(),height=image.getHeight();
        int[] pixels=new int[width*height];
        var planes=image.getPlanes();
        var y=planes[0].getBuffer().duplicate();var u=planes[1].getBuffer().duplicate();var v=planes[2].getBuffer().duplicate();
        int by=y.position(),bu=u.position(),bv=v.position();
        for(int row=0;row<height;row++) for(int col=0;col<width;col++) {
            int yy=(y.get(by+row*planes[0].getRowStride()+col*planes[0].getPixelStride())&255)-16;
            int uu=(u.get(bu+(row/2)*planes[1].getRowStride()+(col/2)*planes[1].getPixelStride())&255)-128;
            int vv=(v.get(bv+(row/2)*planes[2].getRowStride()+(col/2)*planes[2].getPixelStride())&255)-128;
            int r=clamp((298*Math.max(0,yy)+409*vv+128)>>8);
            int g=clamp((298*Math.max(0,yy)-100*uu-208*vv+128)>>8);
            int b=clamp((298*Math.max(0,yy)+516*uu+128)>>8);
            pixels[row*width+col]=0xff000000|(r<<16)|(g<<8)|b;
        }
        return Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888);
    }
    private static int clamp(int value) { return Math.max(0,Math.min(255,value)); }
    @Override public void close() {
        closed=true; resumed=false;
        surface.onPause();
        try { session.pause(); } finally { session.close(); }
    }
}
