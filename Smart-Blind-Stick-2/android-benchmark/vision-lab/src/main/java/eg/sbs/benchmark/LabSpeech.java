package eg.sbs.benchmark;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import java.util.Comparator;
import java.util.function.Consumer;

/** Dynamic captions use an explicitly offline Arabic voice; images/text never go to a network service. */
final class LabSpeech implements AutoCloseable {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Consumer<String> status;
    private TextToSpeech engine;
    private boolean ready, closed;
    LabSpeech(Context context, Consumer<String> status) {
        this.status = status;
        engine = new TextToSpeech(context, result -> main.post(() -> {
            if (closed || result != TextToSpeech.SUCCESS || engine == null || engine.getVoices() == null) {
                status.accept("الصوت العربي الأوفلاين غير جاهز؛ النتيجة المكتوبة متاحة");
                return;
            }
            Voice selected = engine.getVoices().stream()
                    .filter(v -> v.getLocale().getLanguage().equals("ar") && !v.isNetworkConnectionRequired())
                    .max(Comparator.comparingInt(Voice::getQuality)).orElse(null);
            if (selected == null || engine.setVoice(selected) != TextToSpeech.SUCCESS) {
                status.accept("ثبّت صوت عربي من إعدادات النطق لقراءة الوصف الحر");
            } else {
                ready = true;
                engine.setSpeechRate(1f);
                status.accept("صوت عربي أوفلاين جاهز");
            }
        }));
    }
    boolean ready() { return ready; }
    boolean speak(String text) {
        if (!ready || closed || text == null || text.isBlank()) return false;
        return engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sbs-lab") == TextToSpeech.SUCCESS;
    }
    void stop() { if (engine != null) engine.stop(); }
    @Override public void close() {
        closed = true; ready = false;
        if (engine != null) { engine.stop(); engine.shutdown(); }
    }
}
