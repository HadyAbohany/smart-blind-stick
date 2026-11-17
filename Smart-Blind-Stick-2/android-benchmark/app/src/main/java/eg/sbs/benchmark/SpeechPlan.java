package eg.sbs.benchmark;

import java.util.function.BooleanSupplier;

/** A fresh decision is needed to start; accepted short speech survives detection jitter. */
final class SpeechPlan {
    static final long CAMERA_TIMEOUT_MS = 1_500;
    static final long MAX_DURATION_MS = 20_000;
    private final BooleanSupplier mayStart, mayContinue;
    private long startedAt = -1;

    SpeechPlan(BooleanSupplier mayStart, BooleanSupplier mayContinue) {
        this.mayStart = mayStart;
        this.mayContinue = mayContinue;
    }

    boolean valid(long now) {
        if (now < 0) return false;
        if (startedAt < 0) return mayStart.getAsBoolean();
        return now >= startedAt && now - startedAt <= MAX_DURATION_MS && mayContinue.getAsBoolean();
    }

    void started(long now) {
        if (startedAt < 0) startedAt = now;
    }

    static boolean recentCamera(long now, long capturedAt) {
        return capturedAt >= 0 && now >= capturedAt && now - capturedAt < CAMERA_TIMEOUT_MS;
    }
}
