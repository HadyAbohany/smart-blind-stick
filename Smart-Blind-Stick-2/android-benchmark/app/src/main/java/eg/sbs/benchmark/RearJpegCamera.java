package eg.sbs.benchmark;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

final class RearJpegCamera implements AutoCloseable {
    interface Listener {
        void onReady(String cameraInput);
        boolean wantsFrame();
        void onJpeg(byte[] jpeg, long received, long capture);
        void onError(String message);
    }

    private final HandlerThread thread = new HandlerThread("Rear JPEG camera");
    private final Handler handler;
    private final CameraManager manager;
    private final Listener listener;
    private ImageReader reader;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private boolean opening;
    private boolean closed;

    RearJpegCamera(Context context, int displayDegrees, Listener listener) {
        this.listener = listener;
        manager = context.getSystemService(CameraManager.class);
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(() -> open(displayDegrees));
    }

    @SuppressLint("MissingPermission")
    private void open(int displayDegrees) {
        try {
            for (String id : manager.getCameraIdList()) {
                var info = manager.getCameraCharacteristics(id);
                Integer facing = info.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
                var config = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (config == null) continue;
                Size[] sizes = config.getOutputSizes(ImageFormat.JPEG);
                if (sizes == null || sizes.length == 0) continue;
                Size selected = Arrays.stream(sizes).min(Comparator.comparingLong(size ->
                        Math.abs((long) size.getWidth() - 640) + Math.abs((long) size.getHeight() - 480)))
                        .orElseThrow();
                int orientation = (info.get(CameraCharacteristics.SENSOR_ORIENTATION) - displayDegrees + 360) % 360;
                Integer clock = info.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
                boolean realtime = clock != null && clock == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME;
                reader = ImageReader.newInstance(selected.getWidth(), selected.getHeight(), ImageFormat.JPEG, 3);
                reader.setOnImageAvailableListener(source -> {
                    try (var image = source.acquireLatestImage()) {
                        if (!closed && image != null && listener.wantsFrame()) {
                            long received = YoloEngine.now();
                            var buffer = image.getPlanes()[0].getBuffer();
                            byte[] jpeg = new byte[buffer.remaining()];
                            buffer.get(jpeg);
                            listener.onJpeg(jpeg, received, realtime ? image.getTimestamp() : 0);
                        }
                    } catch (RuntimeException error) {
                        fail("Camera JPEG: " + error.getMessage());
                    }
                }, handler);
                opening = true;
                try {
                    manager.openCamera(id, new CameraDevice.StateCallback() {
                        @Override public void onOpened(CameraDevice device) {
                            opening = false;
                            camera = device;
                            if (closed) {
                                release();
                            } else {
                                configure(selected, orientation, realtime);
                            }
                        }
                        @Override public void onDisconnected(CameraDevice device) {
                            opening = false;
                            device.close();
                            fail("Rear camera disconnected");
                        }
                        @Override public void onError(CameraDevice device, int error) {
                            opening = false;
                            device.close();
                            fail("Camera2 error " + error);
                        }
                    }, handler);
                } catch (Exception error) {
                    opening = false;
                    throw error;
                }
                return;
            }
            fail("No rear camera with JPEG output");
        } catch (Exception error) {
            fail("Opening rear camera: " + error.getMessage());
        }
    }

    private void configure(Size size, int orientation, boolean realtime) {
        try {
            var surface = reader.getSurface();
            var configuration = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    List.of(new OutputConfiguration(surface)), handler::post,
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession capture) {
                            if (closed) {
                                capture.close();
                                return;
                            }
                            try {
                                session = capture;
                                var request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                request.addTarget(surface);
                                request.set(CaptureRequest.JPEG_ORIENTATION, orientation);
                                request.set(CaptureRequest.JPEG_QUALITY, (byte) 85);
                                capture.setRepeatingRequest(request.build(), null, handler);
                                listener.onReady("JPEG " + size + (realtime ? " · realtime sensor clock" : ""));
                            } catch (Exception error) {
                                fail("Starting JPEG capture: " + error.getMessage());
                            }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession capture) {
                            capture.close();
                            fail("Rear camera rejected the JPEG capture configuration");
                        }
                    });
            camera.createCaptureSession(configuration);
        } catch (Exception error) {
            fail("Configuring rear camera: " + error.getMessage());
        }
    }

    private void fail(String error) {
        if (!closed) listener.onError(error);
        release();
    }

    private void release() {
        closed = true;
        if (session != null) {
            session.close();
            session = null;
        }
        if (camera != null) {
            camera.close();
            camera = null;
        }
        if (reader != null) {
            reader.close();
            reader = null;
        }
        if (!opening) thread.quitSafely();
    }

    @Override public void close() { handler.post(this::release); }
}
