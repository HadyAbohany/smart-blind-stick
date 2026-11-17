package eg.sbs;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.*;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

final class VisionSession implements AutoCloseable {
    interface Detector extends AutoCloseable {
        String classify(Bitmap frame);
        @Override void close();
    }
    private final ConnectivityManager connectivity;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Context context;
    private final Consumer<String> status;
    private volatile Network current;
    private boolean registered;

    VisionSession(Context context, Consumer<String> status) {
        connectivity = context.getSystemService(ConnectivityManager.class);
        this.context = context.getApplicationContext();
        this.status = status;
    }
    void start(String ssid, String key) {
        WifiNetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
            .setSsid(ssid).setWpa2Passphrase(key).build();
        NetworkRequest request = new NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier).build();
        connectivity.requestNetwork(request, callback, 30000);
        registered = true;
    }
    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) {
            current = network;
            try {
                worker.execute(() -> stream(network));
            } catch (RejectedExecutionException closed) {
                current = null;
            }
        }
        @Override public void onLost(Network network) {
            if (network.equals(current)) {
                current = null;
                status.accept("Camera link lost");
            }
        }
        @Override public void onUnavailable() { status.accept("Camera network unavailable"); }
    };

    private void stream(Network network) {
        Detector candidate = null;
        try {
            candidate = new YoloDetector(context);
        } catch (IOException | ai.onnxruntime.OrtException unavailable) {
            status.accept("Model unavailable. Waiting for camera frames.");
        }
        try (Detector detector = candidate) {
            fetch(network, detector);
        }
    }

    private void fetch(Network network, Detector detector) {
        while (network.equals(current) && !Thread.currentThread().isInterrupted()) {
            long start = android.os.SystemClock.elapsedRealtime();
            HttpURLConnection connection = null;
            int interval = 180;
            try {
                connection = (HttpURLConnection) network.openConnection(new URL("http://192.168.4.1/frame.jpg"));
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setConnectTimeout(800);
                connection.setReadTimeout(800);
                if (connection.getResponseCode() != 200) throw new IOException("Camera HTTP error");
                try (var input = connection.getInputStream()) {
                    int length = connection.getContentLength();
                    if (length < 1 || length > 300000) throw new IOException("Invalid frame size");
                    byte[] jpeg = new byte[length];
                    new java.io.DataInputStream(input).readFully(jpeg);
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
                    if (bounds.outWidth != 640 || bounds.outHeight != 480)
                        throw new IOException("Unexpected frame dimensions");
                    Bitmap frame = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                    if (frame == null) throw new IOException("Invalid frame");
                    try {
                        String label = detector == null ? "Camera receiving JPEG frames. Detector not installed."
                            : detector.classify(frame);
                        if (network.equals(current)) {
                            if (detector != null &&
                                android.os.SystemClock.elapsedRealtime() - start > 500)
                                status.accept("Vision latency above 500 ms. Context muted.");
                            else status.accept(label);
                        }
                    } finally {
                        frame.recycle();
                    }
                }
            } catch (IOException | RuntimeException problem) {
                if (network.equals(current)) status.accept("Camera or detector unavailable");
                interval = 700;
            } finally {
                if (connection != null) connection.disconnect();
            }
            try {
                Thread.sleep(Math.max(0, interval - (android.os.SystemClock.elapsedRealtime() - start)));
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
            }
        }
    }
    @Override public void close() {
        current = null;
        if (registered) connectivity.unregisterNetworkCallback(callback);
        worker.shutdownNow();
    }
}
