package eg.sbs.benchmark;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Switch;
import android.media.AudioManager;
import android.os.VibrationEffect;
import android.os.VibratorManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final int PICK_JPEG = 10, SAVE_JSON = 11, CAMERA_PERMISSION = 12;
    private static final int STILL_ITERATIONS = 60;
    private static final int LIVE = 0, IMAGE = 1, REPORT = 2;
    private static final int ACCENT = 0xff75e2c2;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout root;
    private TextView status, confidence, guidance, audioStatus;
    private ArabicAudio audio;
    private String[] arabicLabels;
    private DecisionEngine.Decision lastDecision, playingDecision;
    private boolean smartDecisions = true;
    private int targetClass = -1;
    private Switch decisionToggle;
    private Spinner targetPicker;
    private TextView decisionReason;
    private boolean haptics;
    private final Runnable stalledCameraAudio = () -> {
        if (audio != null) audio.stop();
    };
    private final Runnable staleSpeech = this::expireGuidance;

    private void expireGuidance() {
        lastDecision = null;
        playingDecision = null;
        if (audio != null) audio.stop();
        if (active != null) active.policy.invalidate();
        if (preview != null) preview.select(null);
        if (guidance != null) guidance.setText("الصورة متأخرة، في انتظار تحديث");
        if (decisionReason != null) decisionReason.setText("لا يوجد تحديث حديث؛ المسافة وحالة الطريق غير معروفتين");
        if (active != null) active.report.recordDecision(YoloEngine.now() - active.report.created, null, "STALE_FRAME");
    }
    private DetectionView preview;
    private Button startButton, pickButton;
    private SeekBar confidenceBar;
    private Spinner modelPicker;
    private int tab = LIVE, modelSize = 320;
    private float threshold = .25f;
    private Uri selected;
    private String filename;
    private BenchmarkRun lastRun, exportRun;
    private ActiveRun active;

    private static final class ActiveRun {
        final BenchmarkRun report;
        final AtomicBoolean inFlight = new AtomicBoolean();
        final DecisionEngine policy;
        volatile boolean running = true;
        YoloEngine engine; // Accessed only by the inference worker.
        RearJpegCamera camera;
        long lastFrame;

        ActiveRun(BenchmarkRun report, boolean smart, int target) {
            this.report = report;
            policy = new DecisionEngine(smart ? DecisionEngine.Mode.SMART : DecisionEngine.Mode.LEGACY,
                    report.threshold, target);
            report.decisionMode = policy.mode().name();
            report.searchClass = target;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        smartDecisions = getPreferences(MODE_PRIVATE).getBoolean("smart_decisions", true);
        try (var reader = new BufferedReader(new InputStreamReader(
                getAssets().open("coco-ar.txt"), StandardCharsets.UTF_8))) {
            var labels = new java.util.ArrayList<String>();
            for (String line; (line = reader.readLine()) != null;) labels.add(line);
            arabicLabels = labels.toArray(new String[0]);
            if (arabicLabels.length != 80) throw new IOException("Arabic labels missing");
        } catch (IOException error) {
            throw new IllegalStateException("Bundled Arabic labels are invalid", error);
        }
        audio = new ArabicAudio(this, message -> {
            if (audioStatus != null) audioStatus.setText(message);
        });
        showTab(LIVE);
    }

    private void showTab(int selectedTab) {
        if (active != null) finish(active, "Stopped when changing tabs");
        audio.stop();
        main.removeCallbacks(staleSpeech);
        main.removeCallbacks(stalledCameraAudio);
        lastDecision = playingDecision = null;
        tab = selectedTab;
        var scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        root.setPadding(padding, padding, padding, padding);
        scroll.addView(root);
        setContentView(scroll);
        root.addView(text("SBS YOLO Benchmark", 24, Color.WHITE));
        var navigation = new LinearLayout(this);
        String[] tabs = {"Live camera", "JPEG test", "Report"};
        for (int i = 0; i < tabs.length; i++) {
            final int destination = i;
            var button = new Button(this);
            button.setText(tabs[i]);
            button.setAllCaps(false);
            button.setEnabled(i != tab);
            button.setOnClickListener(view -> showTab(destination));
            navigation.addView(button, new LinearLayout.LayoutParams(0, dp(52), 1));
        }
        root.addView(navigation);
        if (tab == REPORT) {
            drawReport();
        } else {
            drawControls();
        }
    }

    private void drawControls() {
        root.addView(text(YoloEngine.BACKEND, 14, ACCENT));
        modelPicker = new Spinner(this);
        modelPicker.setContentDescription("Model input size");
        var models = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"YOLO26n · 640 × 640", "YOLO26n · 320 × 320"});
        modelPicker.setAdapter(models);
        modelPicker.setSelection(modelSize == 640 ? 0 : 1);
        modelPicker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                modelSize = position == 0 ? 640 : 320;
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        root.addView(modelPicker, new LinearLayout.LayoutParams(-1, dp(48)));
        confidence = text(confidenceText(), 14, Color.WHITE);
        root.addView(confidence);
        confidenceBar = new SeekBar(this);
        confidenceBar.setContentDescription("Minimum detection confidence");
        confidenceBar.setMin(5);
        confidenceBar.setMax(95);
        confidenceBar.setProgress(Math.round(threshold * 100));
        confidenceBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar slider, int progress, boolean fromUser) {
                threshold = progress / 100f;
                confidence.setText(confidenceText());
            }
            @Override public void onStartTrackingTouch(SeekBar slider) {}
            @Override public void onStopTrackingTouch(SeekBar slider) {}
        });
        root.addView(confidenceBar, new LinearLayout.LayoutParams(-1, dp(40)));

        decisionToggle = new Switch(this);
        decisionToggle.setText("طبقة القرار الذكية · اقفلها للنسخة القديمة");
        decisionToggle.setMinHeight(dp(48));
        decisionToggle.setChecked(smartDecisions);
        decisionToggle.setOnCheckedChangeListener((view, enabled) -> {
            smartDecisions = enabled;
            getPreferences(MODE_PRIVATE).edit().putBoolean("smart_decisions", enabled).apply();
            targetPicker.setEnabled(enabled);
            audio.stop();
            lastDecision = playingDecision = null;
            preview.select(null);
            guidance.setText("في انتظار نتيجة مؤكدة");
            decisionReason.setText(enabled ? "التنبيه حسب أهمية المشهد · المسافة غير معروفة"
                    : "القرار القديم: اسم الجسم واتجاهه");
        });
        root.addView(decisionToggle);
        root.addView(text("ابحث عن جسم · التنبيهات المهمة لها الأولوية", 14, Color.WHITE));
        targetPicker = new Spinner(this);
        targetPicker.setContentDescription("هدف البحث");
        String[] targets = new String[81];
        targets[0] = "بدون بحث";
        System.arraycopy(arabicLabels, 0, targets, 1, 80);
        targetPicker.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, targets));
        targetPicker.setSelection(targetClass + 1);
        targetPicker.setEnabled(smartDecisions);
        targetPicker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                int target = position - 1;
                if (target == targetClass) return;
                targetClass = target;
                audio.stop();
                lastDecision = playingDecision = null;
                preview.select(null);
                guidance.setText("في انتظار نتيجة مؤكدة");
                if (active != null) {
                    active.policy.setTargetClass(target);
                    active.report.searchClass = target;
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        root.addView(targetPicker, new LinearLayout.LayoutParams(-1, dp(48)));

        if (tab == IMAGE) {
            pickButton = button("Choose JPEG", this::pickImage);
            root.addView(pickButton);
        } else {
            pickButton = null;
        }
        startButton = button(tab == LIVE ? "Start live test" : "Run 60 iterations", this::toggleRun);
        startButton.setEnabled(tab == LIVE || selected != null);
        root.addView(startButton);
        status = text(tab == LIVE ? "Rear camera · 15 min after warm-up" :
                (filename == null ? "Choose a JPEG from device storage" : filename), 14, Color.WHITE);
        root.addView(status);
        guidance = text("في انتظار نتيجة مؤكدة", 18, ACCENT);
        guidance.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        root.addView(guidance);
        decisionReason = text(smartDecisions ? "التنبيه حسب أهمية المشهد · المسافة غير معروفة"
                : "القرار القديم: اسم الجسم واتجاهه", 14, Color.WHITE);
        root.addView(decisionReason);
        preview = new DetectionView(this);
        var previewLayout = new LinearLayout.LayoutParams(-1, dp(230));
        previewLayout.topMargin = dp(8);
        root.addView(preview, previewLayout);
        audioStatus = text("صوت عربي أوفلاين · الاتجاه حسب الصورة", 14, Color.WHITE);
        root.addView(audioStatus);
        var audioToggle = new Switch(this);
        audioToggle.setText("التنبيهات الصوتية");
        audioToggle.setChecked(audio.enabled());
        audioToggle.setMinHeight(dp(48));
        audioToggle.setOnCheckedChangeListener((button, enabled) -> audio.setEnabled(enabled));
        root.addView(audioToggle);
        var hapticToggle = new Switch(this);
        hapticToggle.setText("اهتزاز الهاتف مع التنبيه");
        hapticToggle.setChecked(haptics);
        hapticToggle.setMinHeight(dp(48));
        hapticToggle.setOnCheckedChangeListener((button, enabled) -> haptics = enabled);
        root.addView(hapticToggle);
        var voiceActions = new LinearLayout(this);
        voiceActions.addView(button("اختبار الصوت", () -> {
            audio.stop();
            playingDecision = null;
            audio.play("test", () -> true, () -> {});
        }), new LinearLayout.LayoutParams(0, dp(52), 1));
        voiceActions.addView(button("وصف الآن", this::describeNow),
                new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(voiceActions);

    }

    private void drawReport() {
        status = text(lastRun == null ? "Run a live camera or JPEG test to generate a report." :
                lastRun.report(), 15, Color.WHITE);
        status.setTextIsSelectable(true);
        root.addView(status);
        if (lastRun != null) root.addView(button("Export JSON", this::chooseExport));
    }

    private void toggleRun() {
        if (active != null) {
            finish(active, "Stopped manually");
        } else if (tab == LIVE && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        } else if (tab == LIVE || selected != null) {
            start(tab == LIVE);
        }
    }

    private void start(boolean live) {
        if (active != null) return;
        int size = modelPicker.getSelectedItemPosition() == 0 ? 640 : 320;
        var run = new ActiveRun(new BenchmarkRun(size, threshold, live, live ? "Rear camera" : filename),
                smartDecisions, targetClass);
        active = run;
        audio.stop();
        lastDecision = playingDecision = null;
        main.removeCallbacks(staleSpeech);
        main.removeCallbacks(stalledCameraAudio);
        preview.show(null);
        guidance.setText("في انتظار نتيجة مؤكدة");
        status.setText("Loading YOLO26n " + size + "…");
        startButton.setText("Stop test");
        modelPicker.setEnabled(false);
        confidenceBar.setEnabled(false);
        decisionToggle.setEnabled(false);
        if (pickButton != null) pickButton.setEnabled(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        run.report.sampleDevice(this);
        Uri image = selected;
        worker.execute(() -> {
            try {
                if (!run.running) return;
                long start = YoloEngine.now();
                run.engine = new YoloEngine(getAssets(), size);
                long load = YoloEngine.now() - start;
                main.post(() -> {
                    if (active != run) return;
                    run.report.load = load;
                    if (live) {
                        startCamera(run);
                    } else {
                        status.setText("JPEG warm-up 0 / " + BenchmarkRun.WARMUP);
                        worker.execute(() -> benchmarkImage(run, image));
                    }
                });
            } catch (Exception | LinkageError error) {
                reportError(run, error);
            }
        });
    }

    private void startCamera(ActiveRun run) {
        status.setText("Opening rear camera…");
        run.lastFrame = YoloEngine.now();
        int degrees = switch (getDisplay().getRotation()) {
            case Surface.ROTATION_90 -> 90;
            case Surface.ROTATION_180 -> 180;
            case Surface.ROTATION_270 -> 270;
            default -> 0;
        };
        run.camera = new RearJpegCamera(this, degrees, new RearJpegCamera.Listener() {
            @Override public void onReady(String cameraInput) {
                main.post(() -> {
                    if (active != run) return;
                    run.report.cameraInput = cameraInput;
                    status.setText(cameraInput + " · waiting for first frame");
                });
            }

            @Override public boolean wantsFrame() {
                return run.running && run.inFlight.compareAndSet(false, true);
            }

            @Override public void onJpeg(byte[] jpeg, long received, long captured) {
                if (!run.running) return;
                // Submit through the lifecycle owner so camera shutdown and executor shutdown are ordered.
                main.post(() -> {
                    if (active == run) worker.execute(() -> processLive(run, jpeg, received, captured));
                });
            }

            @Override public void onError(String message) {
                main.post(() -> {
                    if (active != run) return;
                    finish(run, message);
                    audio.play("unavailable", () -> true, () -> {});
                });
            }
        });
        updateLiveStatus(run);
    }

    private void processLive(ActiveRun run, byte[] jpeg, long received, long captured) {
        if (!run.running) return;
        try {
            var prediction = run.engine.predict(jpeg, run.report.threshold);
            main.post(() -> {
                if (active != run) {
                    prediction.image().recycle();
                    return;
                }
                try {
                    preview.show(prediction);
                    long now = YoloEngine.now();
                    run.lastFrame = now;
                    if (run.report.warmed < BenchmarkRun.WARMUP) {
                        run.report.warmed++;
                        if (run.report.warmed == BenchmarkRun.WARMUP) {
                            run.report.measuredFrom = now;
                            main.postDelayed(() -> finish(run, "Completed 15-minute live test"),
                                    BenchmarkRun.LIVE_DURATION_MS);
                            samplePeriodically(run);
                        }
                    } else {
                        run.report.record(prediction, prediction.image().getWidth(),
                                prediction.image().getHeight(), now, received, captured);
                        updateGuidance(run, prediction, now, captured > 0 ? captured : received, false);
                    }
                } finally {
                    run.inFlight.set(false);
                }
            });
        } catch (Exception | LinkageError error) {
            reportError(run, error);
        }
    }

    private void updateLiveStatus(ActiveRun run) {
        if (active != run) return;
        long now = YoloEngine.now();
        var report = run.report;
        if (now - run.lastFrame > 20_000_000_000L) {
            finish(run, "Camera timed out waiting for a processed frame");
            return;
        }
        if (report.warmed > 0 && report.warmed < BenchmarkRun.WARMUP) {
            status.setText("Camera warm-up " + report.warmed + " / " + BenchmarkRun.WARMUP);
        } else if (!report.samples.isEmpty()) {
            var frame = report.samples.get(report.samples.size() - 1);
            status.setText(BenchmarkRun.format("%.1f FPS · model %.1f ms · processing %.1f ms\n"
                            + "%d frames · %.0f / 900 s",
                    report.currentFps(now), BenchmarkRun.ms(frame.inference()),
                    BenchmarkRun.ms(frame.processing()), report.samples.size(), report.elapsedSeconds()));
        }
        main.postDelayed(() -> updateLiveStatus(run), 500);
    }

    private void samplePeriodically(ActiveRun run) {
        main.postDelayed(() -> {
            if (active != run) return;
            run.report.sampleDevice(this);
            samplePeriodically(run);
        }, 5_000);
    }

    private void benchmarkImage(ActiveRun run, Uri uri) {
        try {
            byte[] jpeg;
            try (var stream = getContentResolver().openInputStream(uri)) {
                if (stream == null) throw new IOException("Unable to open the selected JPEG");
                var buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[16384];
                for (int count; (count = stream.read(chunk)) != -1;) {
                    if (buffer.size() + count > YoloEngine.JPEG_LIMIT) {
                        throw new IOException("JPEG exceeds 32 MiB");
                    }
                    buffer.write(chunk, 0, count);
                    if (!run.running) return;
                }
                jpeg = buffer.toByteArray();
            }
            for (int i = 0; i < BenchmarkRun.WARMUP && run.running; i++) {
                run.engine.predict(jpeg, run.report.threshold).image().recycle();
                int warmed = i + 1;
                main.post(() -> {
                    if (active != run) return;
                    run.report.warmed = warmed;
                    status.setText("JPEG warm-up " + warmed + " / " + BenchmarkRun.WARMUP);
                });
            }
            if (!run.running) return;
            long start = YoloEngine.now();
            main.post(() -> {
                if (active != run) return;
                run.report.measuredFrom = start;
                samplePeriodically(run);
            });
            for (int i = 1; i <= STILL_ITERATIONS && run.running; i++) {
                var prediction = run.engine.predict(jpeg, run.report.threshold);
                long end = YoloEngine.now();
                int iteration = i;
                int width = prediction.image().getWidth(), height = prediction.image().getHeight();
                boolean last = i == STILL_ITERATIONS;
                main.post(() -> {
                    if (active != run) {
                        if (last) prediction.image().recycle();
                        return;
                    }
                    run.report.record(prediction, width, height, end, 0, 0);
                    if (last) {
                        preview.show(prediction);
                        run.report.measuredUntil = end;
                        finish(run, "Completed JPEG test");
                        updateGuidance(run, prediction, YoloEngine.now(), end, true);
                    } else if (iteration % 5 == 0) {
                        status.setText("JPEG iterations " + iteration + " / " + STILL_ITERATIONS);
                    }
                });
                if (!last) prediction.image().recycle();
            }
        } catch (Exception | LinkageError error) {
            reportError(run, error);
        }
    }

    private void updateGuidance(ActiveRun run, YoloEngine.Prediction prediction,
                                long nowNs, long sourceNs, boolean still) {
        long start = YoloEngine.now();
        long now = nowNs / 1_000_000;
        long age = sourceNs > 0 && nowNs >= sourceNs ? (nowNs - sourceNs) / 1_000_000 : -1;
        List<AlertPolicy.Box> boxes = prediction.boxes().stream()
                .map(box -> new AlertPolicy.Box(box.classId(), box.score(),
                        box.x1(), box.y1(), box.x2(), box.y2()))
                .collect(java.util.stream.Collectors.toList());
        run.report.recordReplay(now, age, run.policy.targetClass(), still, boxes);
        var decision = still ? run.policy.describeStill(boxes) : run.policy.update(boxes, now, age);
        lastDecision = decision;
        preview.select(decision == null ? null : decision.selection());
        boolean stale = age < 0 || age > SceneTracker.MAX_AGE_MS;
        if (decision == null) {
            guidance.setText(stale && !still ? "الصورة متأخرة، في انتظار تحديث"
                    : "لا يوجد جسم مؤكد حاليًا؛ ده لا يعني إن الطريق خالي");
            decisionReason.setText(run.policy.targetClass() >= 0
                    ? "البحث مستمر عن: " + arabicLabels[run.policy.targetClass()]
                    : "الكاميرا لا تقيس المسافة ولا تؤكد خلو الطريق");
        } else {
            guidance.setText(DecisionSpeech.phrase(decision, arabicLabels)
                    + (still ? " · صورة ثابتة" : ""));
            decisionReason.setText(still ? "وصف صورة فقط؛ لا يتم استنتاج حركة أو خطورة"
                    : DecisionSpeech.explanation(decision));
        }
        main.removeCallbacks(staleSpeech);
        main.removeCallbacks(stalledCameraAudio);
        if (!still) {
            if (playingDecision != null && audio.isBusy() && !run.policy.isValid(playingDecision, now)) {
                audio.stop();
                playingDecision = null;
            }
            if (stale) {
                audio.stop();
                playingDecision = null;
            } else {
                main.postDelayed(staleSpeech, Math.max(1, SceneTracker.MAX_AGE_MS - age));
                main.postDelayed(stalledCameraAudio, 1_500);
            }
        }
        run.report.recordDecision(nowNs - run.report.created, decision,
                stale && !still ? "STALE_FRAME" : decision == null ? "NO_CONFIRMED_OBJECT" : "CONFIRMED");
        run.report.decisionNanos += YoloEngine.now() - start;
        run.report.decisionFrames++;
        var announcement = still ? decision : run.policy.announcement(now);
        if (announcement != null) {
            if (audio.isBusy() && run.policy.canInterrupt(announcement, playingDecision)) {
                audio.stop();
                playingDecision = null;
            }
            if (!audio.isBusy()) announce(run, announcement, still);
        }
    }

    private void announce(ActiveRun run, DecisionEngine.Decision decision, boolean still) {
        boolean accepted = audio.play(DecisionSpeech.assets(decision),
                () -> still ? tab == IMAGE && active == null && lastDecision == decision
                        : active == run && run.policy.isValid(decision, android.os.SystemClock.elapsedRealtime()),
                () -> {
            long now = android.os.SystemClock.elapsedRealtime();
            if (!still) run.policy.announced(decision, now);
            run.report.alerts.add(new BenchmarkRun.AlertEvent(now * 1_000_000 - run.report.created,
                    decision, DecisionSpeech.phrase(decision, arabicLabels), run.policy.targetClass()));
            if (haptics) {
                var vibrator = getSystemService(VibratorManager.class).getDefaultVibrator();
                if (vibrator.hasVibrator()) {
                    if (decision.level() == DecisionEngine.Level.CAUTION) {
                        vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 100, 80, 100}, -1));
                    } else {
                        vibrator.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE));
                    }
                }
            }
        });
        if (accepted) playingDecision = decision;
    }

    private void describeNow() {
        boolean still = tab == IMAGE && active == null;
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastDecision == null || (!still && (active == null
                || !active.policy.isValid(lastDecision, now)))) {
            audioStatus.setText("في انتظار نتيجة حديثة ومؤكدة");
            return;
        }
        audio.stop();
        var decision = lastDecision;
        var run = active;
        boolean accepted = audio.play(DecisionSpeech.assets(decision),
                () -> still ? tab == IMAGE && active == null && lastDecision == decision
                        : active == run && run != null
                                && run.policy.isValid(decision, android.os.SystemClock.elapsedRealtime()),
                () -> {
                    long at = android.os.SystemClock.elapsedRealtime();
                    if (run != null) {
                        run.policy.announced(decision, at);
                        run.report.alerts.add(new BenchmarkRun.AlertEvent(at * 1_000_000 - run.report.created,
                                decision, DecisionSpeech.phrase(decision, arabicLabels), run.policy.targetClass()));
                    }
                });
        if (accepted) playingDecision = decision;
    }

    private void reportError(ActiveRun run, Throwable error) {
        String reason = error.getClass().getSimpleName() + ": " + error.getMessage();
        main.post(() -> finish(run, reason));
    }

    private void finish(ActiveRun run, String reason) {
        if (active != run) return;
        active = null;
        run.running = false;
        audio.stop();
        lastDecision = playingDecision = null;
        main.removeCallbacks(staleSpeech);
        main.removeCallbacks(stalledCameraAudio);
        if (run.report.measuredUntil == 0) run.report.measuredUntil = YoloEngine.now();
        run.report.finishReason = reason;
        run.report.sampleDevice(this);
        lastRun = run.report;
        if (run.camera != null) run.camera.close();
        worker.execute(() -> {
            if (run.engine == null) return;
            try {
                run.engine.close();
            } catch (ai.onnxruntime.OrtException error) {
                android.util.Log.w("SbsBenchmark", "Closing ONNX session", error);
            }
        });
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        startButton.setText(tab == LIVE ? "Start live test" : "Run 60 iterations");
        modelPicker.setEnabled(true);
        confidenceBar.setEnabled(true);
        decisionToggle.setEnabled(true);
        if (pickButton != null) pickButton.setEnabled(true);
        status.setText(reason + BenchmarkRun.format("\n%d frames · %.2f average FPS · See Report",
                run.report.samples.size(), run.report.averageFps()));
    }

    private void pickImage() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("image/jpeg"), PICK_JPEG);
    }

    private void chooseExport() {
        exportRun = lastRun;
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "sbs-yolo26n-" + exportRun.size
                        + (exportRun.live ? "-camera.json" : "-jpeg.json")), SAVE_JSON);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == PICK_JPEG && tab == IMAGE) {
            audio.stop();
            lastDecision = playingDecision = null;
            preview.select(null);
            selected = uri;
            filename = displayName(uri);
            status.setText(filename);
            startButton.setEnabled(true);
        } else if (request == SAVE_JSON && exportRun != null) {
            var report = exportRun;
            exportRun = null;
            worker.execute(() -> {
                try (var stream = getContentResolver().openOutputStream(uri, "wt")) {
                    if (stream == null) throw new IOException("Unable to open the export destination");
                    stream.write(report.json().toString(2).getBytes(StandardCharsets.UTF_8));
                } catch (Exception error) {
                    main.post(() -> android.widget.Toast.makeText(this,
                            "Export failed: " + error.getMessage(), android.widget.Toast.LENGTH_LONG).show());
                    return;
                }
                main.post(() -> android.widget.Toast.makeText(this, "JSON exported",
                        android.widget.Toast.LENGTH_SHORT).show());
            });
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (RuntimeException error) {
            android.util.Log.w("SbsBenchmark", "Reading JPEG name", error);
        }
        return "Selected JPEG";
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != CAMERA_PERMISSION || tab != LIVE) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) start(true);
        else status.setText("Camera permission is required for the live test. JPEG tests are available.");
    }

    @Override protected void onPause() {
        if (active != null) finish(active, "Stopped when the benchmark left the foreground");
        audio.stop();
        lastDecision = playingDecision = null;
        main.removeCallbacks(staleSpeech);
        main.removeCallbacks(stalledCameraAudio);
        super.onPause();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        worker.shutdown();
        audio.close();
        super.onDestroy();
    }

    private String confidenceText() { return BenchmarkRun.format("Minimum confidence %.2f", threshold); }

    private TextView text(String message, int size, int color) {
        var text = new TextView(this);
        text.setText(message);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setPadding(0, dp(8), 0, dp(5));
        return text;
    }

    private Button button(String title, Runnable action) {
        var button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setTextColor(0xff10241e);
        button.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
}
