package eg.sbs;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.hardware.*;
import android.location.*;
import android.net.Uri;
import android.os.*;
import android.speech.tts.TextToSpeech;
import android.telephony.*;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.util.*;
import java.util.function.Supplier;

@SuppressLint("MissingPermission")
public final class MainActivity extends Activity implements SensorEventListener {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout body;
    private TextView linkStatus, dashboard, visionStatus, emergency, location, smsStatus;
    private Button cancel, contactSend;
    private EditText numbers, ssid, key;
    private CheckBox live, detect;
    private TextToSpeech speech;
    private boolean speechReady, visible;
    private StickLink stick;
    private VisionSession vision;
    private SharedPreferences prefs;
    private long deadline, gpsGeneration, cameraGeneration;
    private boolean mock, physicalAlert;
    private long lastTelemetry;
    private List<String> recipients = List.of();
    private Location fix;
    private CancellationSignal gps;
    private FallHeuristic fall = new FallHeuristic();
    private double gyro;
    private long gyroTime, previousSample;
    private Runnable afterPermission;
    private final Supplier<String[]> wifiPermissions = () -> Build.VERSION.SDK_INT >= 33
        ? new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}
        : new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("sbs", MODE_PRIVATE);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(24), dp(20), dp(24));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(body);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            var bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setContentView(scroll);
        heading("Smart Blind Stick");
        linkStatus = line("Stick disconnected");
        dashboard = line("Distance — · Water — · Battery: bank LEDs");
        stick = new StickLink(this, ui, this::link, this::telemetry);
        button("Find and connect to stick", () -> permit(stick::start,
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT));
        heading("Emergency");
        numbers = field("Contact numbers, one per line", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        numbers.setText(prefs.getString("numbers", ""));
        button("Save emergency contacts", this::saveContacts);
        location = line("Location not requested");
        button("Get phone location", () -> permit(this::gps,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION));
        emergency = line("Emergency workflow idle");
        emergency.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        button("Start phone SOS countdown", () -> begin(false, "Phone SOS"));
        button("Simulate a fall", () -> begin(true, "Simulated fall"));
        cancel = button("Cancel emergency", this::cancel);
        cancel.setEnabled(false);
        contactSend = button("Open SMS draft for a contact", this::compose);
        contactSend.setEnabled(false);
        smsStatus = line("");
        detect = new CheckBox(this);
        detect.setText("Enable experimental fall sensing while this screen is open");
        detect.setMinHeight(dp(48));
        detect.setOnCheckedChangeListener((view, checked) -> sensors(checked));
        body.addView(detect);
        if (BuildConfig.LAB_SMS) {
            live = new CheckBox(this);
            live.setText("Arm automatic SMS for real events in this session");
            live.setMinHeight(dp(48));
            live.setOnCheckedChangeListener((view, checked) -> {
                if (checked) permit(() -> speak("Lab SMS armed"), Manifest.permission.SEND_SMS);
            });
            body.addView(live);
        }
        heading("Stick camera");
        ssid = field("Camera Wi-Fi name", InputType.TYPE_CLASS_TEXT);
        key = field("Camera Wi-Fi key", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        visionStatus = line("Camera idle");
        button("Connect to stick camera", () -> permit(this::camera, wifiPermissions.get()));
        button("Disconnect camera", () -> { closeCamera(); visionStatus.setText("Camera idle"); });
        speech = new TextToSpeech(this, init -> {
            speechReady = init == TextToSpeech.SUCCESS;
            if (speechReady) speech.setLanguage(Locale.ENGLISH);
        });
    }

    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + .5f); }
    private TextView line(String text) {
        TextView widget = new TextView(this);
        widget.setTextSize(18);
        widget.setPadding(0, dp(8), 0, dp(8));
        widget.setText(text);
        body.addView(widget);
        return widget;
    }
    private void heading(String title) {
        TextView widget = line(title);
        widget.setTextSize(24);
        widget.setAccessibilityHeading(true);
    }
    private Button button(String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setMinHeight(dp(52));
        button.setOnClickListener(view -> action.run());
        body.addView(button);
        return button;
    }
    private EditText field(String label, int type) {
        TextView labelView = line(label);
        EditText box = new EditText(this);
        box.setId(View.generateViewId());
        labelView.setLabelFor(box.getId());
        box.setInputType(type);
        box.setMinHeight(dp(48));
        body.addView(box);
        return box;
    }
    private boolean has(String permission) {
        return checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
    private void permit(Runnable action, String... permissions) {
        if (Arrays.stream(permissions).allMatch(this::has)) action.run();
        else {
            afterPermission = action;
            requestPermissions(permissions, 10);
        }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        Runnable action = afterPermission;
        afterPermission = null;
        boolean granted = results.length > 0 && Arrays.stream(results).allMatch(result -> result == 0);
        if (granted && action != null) action.run();
        else {
            if (live != null && !has(Manifest.permission.SEND_SMS)) live.setChecked(false);
            notice("Permission declined. Local stick alerts are still independent.");
        }
    }
    private void link(String message) {
        linkStatus.setText(message);
        dashboard.setText("Distance — · Water — · Battery: bank LEDs");
        if (message.contains("disconnected") || message.contains("interrupted")) speak(message);
    }
    private void telemetry(Packet packet) {
        physicalAlert = (packet.flags() & 3) != 0;
        lastTelemetry = SystemClock.elapsedRealtime();
        linkStatus.setText("Stick connected");
        dashboard.setText((packet.centimeters() == 65535 ? "Distance unavailable" : packet.centimeters() + " cm")
            + " · Water " + (packet.wet() ? "detected" : "not detected")
            + " (" + packet.waterRaw() + ") · Battery: bank LEDs");
        String eventKey = packet.boot() + ":" + packet.event();
        if (visible && packet.recentSos() && !eventKey.equals(prefs.getString("lastEvent", ""))) {
            prefs.edit().putString("lastEvent", eventKey).apply();
            begin(false, "Stick SOS");
        }
    }
    private void saveContacts() {
        Set<String> parsed = new LinkedHashSet<>();
        for (String row : numbers.getText().toString().split("\n")) {
            String number = row.trim().replace(" ", "").replace("-", "");
            if (number.isEmpty()) continue;
            if (!number.matches("[+]?[0-9]{7,15}") || parsed.size() >= 5) {
                notice("Enter up to five valid emergency numbers with country code where needed.");
                return;
            }
            parsed.add(number);
        }
        prefs.edit().putString("numbers", String.join("\n", parsed)).apply();
        notice(parsed.isEmpty() ? "Emergency contacts cleared." : "Emergency contacts saved.");
    }
    private void gps() {
        if (gps != null) gps.cancel();
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            location.setText("Precise location unavailable");
            return;
        }
        long generation = ++gpsGeneration;
        LocationManager manager = getSystemService(LocationManager.class);
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            location.setText("Enable phone location for a GPS fix");
            return;
        }
        location.setText("Waiting for GPS");
        gps = new CancellationSignal();
        manager.getCurrentLocation(LocationManager.GPS_PROVIDER, gps, getMainExecutor(), result -> {
            if (generation != gpsGeneration) return;
            fix = result;
            location.setText(freshFix() ? "GPS fix · accuracy " + Math.round(result.getAccuracy()) + " m"
                : "Fresh GPS fix unavailable");
        });
    }
    private boolean freshFix() {
        return fix != null && fix.hasAccuracy() && fix.getAccuracy() <= 100
            && SystemClock.elapsedRealtimeNanos() - fix.getElapsedRealtimeNanos() <= 60_000_000_000L;
    }
    private void begin(boolean test, String reason) {
        if (deadline != 0 && (!mock || test)) return;
        ui.removeCallbacks(tick);
        String saved = prefs.getString("numbers", "");
        recipients = saved.isEmpty() ? List.of() : List.of(saved.split("\n"));
        mock = test;
        deadline = SystemClock.elapsedRealtime() + 20000;
        cancel.setEnabled(true);
        contactSend.setEnabled(false);
        notice(reason + ". Cancel within 20 seconds.");
        gps();
        ui.postDelayed(tick, 1000);
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining > 0) {
                emergency.setText("Emergency countdown · " + ((remaining + 999) / 1000) + " seconds");
                ui.postDelayed(this, Math.min(1000, remaining));
                return;
            }
            deadline = 0;
            cancel.setEnabled(false);
            if (mock) notice("Simulated escalation complete. No messages sent.");
            else if (recipients.isEmpty()) notice("SOS expired. Save an emergency contact to send a message.");
            else if (visible && live != null && live.isChecked() && has(Manifest.permission.SEND_SMS)) sendLab();
            else {
                contactSend.setEnabled(true);
                notice("SOS ready. Open a contact's SMS draft and press Send.");
            }
        }
    };
    private void cancel() {
        ui.removeCallbacks(tick);
        deadline = 0;
        cancel.setEnabled(false);
        notice("Emergency cancelled.");
    }
    private String message() {
        if (!freshFix()) return "Smart Stick SOS. Please contact me. GPS unavailable.";
        return String.format(Locale.US, "Smart Stick SOS. Please contact me. GPS: https://maps.google.com/?q=%.5f,%.5f",
            fix.getLatitude(), fix.getLongitude());
    }
    private void compose() {
        String[] targets = recipients.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("Choose emergency contact").setItems(targets, (dialog, index) -> {
            Intent draft = new Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", targets[index], null));
            draft.putExtra("sms_body", message());
            try { startActivity(draft); }
            catch (ActivityNotFoundException missing) { notice("No SMS app available."); }
        }).show();
    }
    private void sendLab() {
        contactSend.setEnabled(true);
        int subscription = SubscriptionManager.getDefaultSmsSubscriptionId();
        if (!SubscriptionManager.isValidSubscriptionId(subscription)) {
            notice("Select a default SMS SIM or use an SMS draft.");
            return;
        }
        SmsManager manager = getSystemService(SmsManager.class).createForSubscriptionId(subscription);
        String body = message();
        if (manager == null || manager.divideMessage(body).size() != 1) {
            notice("Message exceeds one SMS. Open an SMS draft.");
            return;
        }
        int count = 0;
        for (String number : recipients) {
            Intent result = new Intent(this, SmsResult.class)
                .setData(Uri.parse("sbs-result:" + UUID.randomUUID()));
            PendingIntent sent = PendingIntent.getBroadcast(this, 0, result, PendingIntent.FLAG_IMMUTABLE);
            try {
                manager.sendTextMessage(number, null, body, sent, null);
                count++;
            } catch (RuntimeException failed) {
                sent.cancel();
            }
        }
        notice("SMS submitted for " + count + " of " + recipients.size() + " contacts. Check send status.");
    }
    private void camera() {
        closeCamera();
        long generation = cameraGeneration;
        VisionSession session = new VisionSession(this, message -> ui.post(() -> {
            if (vision == null || generation != cameraGeneration) return;
            visionStatus.setText(message);
            if (visible && deadline == 0 && !physicalAlert && lastTelemetry != 0 &&
                SystemClock.elapsedRealtime() - lastTelemetry < 800 && message.startsWith("Context: "))
                speak(message.substring(9));
        }));
        vision = session;
        try { session.start(ssid.getText().toString().trim(), key.getText().toString()); }
        catch (RuntimeException invalid) {
            closeCamera();
            visionStatus.setText("Camera setup failed. Check Wi-Fi credentials and permissions.");
        }
    }
    private void closeCamera() {
        cameraGeneration++;
        if (vision != null) vision.close();
        vision = null;
    }
    private void sensors(boolean enable) {
        SensorManager manager = getSystemService(SensorManager.class);
        manager.unregisterListener(this);
        previousSample = 0;
        gyroTime = 0;
        fall = new FallHeuristic();
        if (!enable || !visible) return;
        Sensor accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor gyroscope = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if (accelerometer == null || gyroscope == null ||
            !manager.registerListener(this, accelerometer, 20000) ||
            !manager.registerListener(this, gyroscope, 20000)) {
            detect.setChecked(false);
            notice("Fall sensing requires an accelerometer and gyroscope.");
        }
    }
    @Override public void onSensorChanged(SensorEvent sample) {
        double x = sample.values[0], y = sample.values[1], z = sample.values[2];
        double magnitude = Math.sqrt(x*x + y*y + z*z);
        if (sample.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            gyro = magnitude;
            gyroTime = sample.timestamp;
            return;
        }
        long delta = sample.timestamp - previousSample;
        previousSample = sample.timestamp;
        if (gyroTime == 0 || Math.abs(sample.timestamp - gyroTime) > 100000000 || delta > 200000000) return;
        if (fall.sample(sample.timestamp / 1000000, magnitude / SensorManager.GRAVITY_EARTH, gyro, delta / 1e9))
            begin(false, "Possible fall");
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    private void notice(String text) { emergency.setText(text); speak(text); }
    private void speak(String text) {
        if (speechReady && !getSystemService(android.view.accessibility.AccessibilityManager.class).isTouchExplorationEnabled())
            speech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sbs");
    }
    private final Runnable refreshSms = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            smsStatus.setText(prefs.getString("smsResult", ""));
            ui.postDelayed(this, 1000);
        }
    };
    @Override protected void onResume() {
        super.onResume();
        visible = true;
        sensors(detect.isChecked());
        ui.post(refreshSms);
    }
    @Override protected void onPause() {
        visible = false;
        sensors(false);
        ui.removeCallbacks(refreshSms);
        super.onPause();
    }
    @Override protected void onDestroy() {
        stick.stop();
        closeCamera();
        if (gps != null) gps.cancel();
        if (speech != null) speech.shutdown();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
