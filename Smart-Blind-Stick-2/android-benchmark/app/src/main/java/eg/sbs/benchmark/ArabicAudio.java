package eg.sbs.benchmark;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.List;

/** One short bundled Arabic phrase at a time, with no queued stale announcements. */
final class ArabicAudio implements AutoCloseable {
    private final Context context;
    private final AudioManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Consumer<String> status;
    private final AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    private MediaPlayer player;
    private AudioFocusRequest focus;
    private long generation;
    private boolean enabled = true;

    ArabicAudio(Context context, Consumer<String> status) {
        this.context = context;
        manager = context.getSystemService(AudioManager.class);
        this.status = status;
    }

    boolean isBusy() { return player != null; }
    boolean enabled() { return enabled; }

    void setEnabled(boolean value) {
        enabled = value;
        if (!value) stop();
    }

    boolean play(String asset, java.util.function.BooleanSupplier valid, Runnable started) {
        return play(List.of(asset), valid, started);
    }

    boolean play(List<String> assets, java.util.function.BooleanSupplier valid, Runnable started) {
        return play(assets, valid, valid, started, () -> {});
    }

    boolean play(List<String> assets, java.util.function.BooleanSupplier mayStart,
                 java.util.function.BooleanSupplier mayContinue, Runnable started, Runnable completed) {
        if (!enabled || player != null) return false;
        if (assets.isEmpty()) return false;
        long token = ++generation;
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(change -> {
                    if (token == generation && change < 0) stop();
                }, main).build();
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            stop();
            status.accept("الصوت مشغول بتطبيق آخر");
            return false;
        }
        return prepare(List.copyOf(assets), 0, token, new SpeechPlan(mayStart, mayContinue), started, completed);
    }

    private boolean prepare(List<String> assets, int index, long token,
                            SpeechPlan plan, Runnable started, Runnable completed) {
        String asset = assets.get(index);
        try {
            MediaPlayer candidate = new MediaPlayer();
            player = candidate;
            candidate.setAudioAttributes(attributes);
            try (var file = context.getAssets().openFd("speech/" + asset + ".m4a")) {
                candidate.setDataSource(file.getFileDescriptor(), file.getStartOffset(), file.getLength());
            }
            candidate.setOnPreparedListener(ready -> {
                if (token != generation || ready != player) return;
                if (!plan.valid(android.os.SystemClock.elapsedRealtime())) {
                    stop();
                    return;
                }
                try {
                    ready.start();
                    android.util.Log.i("SbsAudio", "Started " + asset);
                    if (index == 0) {
                        plan.started(android.os.SystemClock.elapsedRealtime());
                        started.run();
                    }
                    status.accept(manager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
                            ? "ارفع صوت الوسائط لسماع التنبيه" : "الصوت العربي يعمل");
                } catch (IllegalStateException error) {
                    stop();
                    status.accept("تعذر تشغيل الصوت");
                }
            });
            candidate.setOnCompletionListener(done -> {
                if (token == generation) {
                    android.util.Log.i("SbsAudio", "Completed " + asset);
                    if (index + 1 == assets.size()) {
                        completed.run();
                        status.accept("اكتمل الوصف الصوتي");
                        stop();
                    } else if (plan.valid(android.os.SystemClock.elapsedRealtime())) {
                        player = null;
                        done.release();
                        prepare(assets, index + 1, token, plan, started, completed);
                    } else {
                        stop();
                    }
                }
            });
            candidate.setOnErrorListener((failed, what, extra) -> {
                if (token == generation) {
                    stop();
                    status.accept("تعذر تشغيل الصوت، جرّب اختبار الصوت");
                }
                return true;
            });
            candidate.prepareAsync();
            return true;
        } catch (IOException | RuntimeException error) {
            stop();
            status.accept("تعذر فتح التنبيه الصوتي");
            return false;
        }
    }

    void stop() {
        generation++;
        if (player != null) {
            player.release();
            player = null;
        }
        if (focus != null) {
            manager.abandonAudioFocusRequest(focus);
            focus = null;
        }
    }

    @Override public void close() { stop(); }
}
