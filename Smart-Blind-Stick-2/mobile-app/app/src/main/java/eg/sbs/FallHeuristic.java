package eg.sbs;

// Experimental features, using monotonic milliseconds and body-carried phone orientation.
final class FallHeuristic {
    private long unloaded = -10000, impact = -10000, stillSince = -1;
    private double rotation;
    private boolean candidate;

    boolean sample(long now, double accelerationG, double rotationRadiansPerSec, double dt) {
        if (!candidate && accelerationG < 0.55 && now - unloaded >= 750) {
            unloaded = now;
            rotation = 0;
        }
        if (!candidate && now - unloaded < 750)
            rotation += rotationRadiansPerSec * Math.min(dt, 0.08);
        if (!candidate && now - unloaded < 750 && accelerationG >= 2.5) {
            candidate = true;
            impact = now;
            stillSince = -1;
        }
        if (!candidate) return false;
        if (now - impact > 4500) {
            candidate = false;
            return false;
        }
        if (now > impact) rotation += rotationRadiansPerSec * Math.min(dt, 0.08);
        boolean still = accelerationG > 0.85 && accelerationG < 1.15 && rotationRadiansPerSec < 0.25;
        if (!still) stillSince = -1;
        else if (stillSince < 0) stillSince = now;
        if (rotation < 0.65 || stillSince < 0 || now - stillSince < 1800) return false;
        candidate = false;
        unloaded = -10000;
        return true;
    }
}
