package eg.sbs.benchmark;

import java.util.List;

/** Regression checks for temporal decisions without a camera or Android runtime. */
public final class AlertPolicyCheck {
    private static AlertPolicy.Box person(float x) {
        return new AlertPolicy.Box(0, .9f, x, .2f, x + .2f, .8f);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static AlertPolicy.Selection confirm(AlertPolicy policy, List<AlertPolicy.Box> boxes, long time) {
        policy.update(boxes, time, 30);
        policy.update(boxes, time + 100, 30);
        return policy.update(boxes, time + 200, 30);
    }
    public static void main(String[] args) {
        var policy = new AlertPolicy();
        require(policy.update(List.of(person(.1f)), 0, 30) == null, "Single frame must not speak");
        policy.update(List.of(person(.1f)), 100, 30);
        var left = policy.update(List.of(person(.1f)), 200, 30);
        require(left != null && left.direction() == 0, "Stable left object");
        policy.announced(left, 200);
        require(policy.announcement(300) == null, "Global cooldown");
        policy.update(List.of(person(.12f)), 400, 30);
        require(policy.current().trackId() == left.trackId(), "Matched box retains identity");
        policy.update(List.of(person(.12f)), 500, 30);
        require(policy.announcement(3_000) == null, "Same object is not repeatedly announced");

        var reacquired = confirm(policy, List.of(person(.12f)), 3_500);
        require(reacquired != null && reacquired.trackId() != left.trackId(), "Expired track gets a new ID");
        require(policy.announcement(3_700) == null, "ID reset does not repeat the same phrase");

        var crowd = new AlertPolicy();
        var center = confirm(crowd, List.of(person(.05f), person(.40f)), 0);
        require(center != null && center.direction() == 1, "Central object has priority");
        var bad = new AlertPolicy.Box(0, .44f, .4f, .1f, .6f, .8f);
        require(confirm(new AlertPolicy(), List.of(bad), 0) == null, "Low confidence is silent");
        require(crowd.update(List.of(person(.4f)), 300, 501) == null, "Stale frames are silent");
        require(crowd.update(List.of(person(.4f)), 400, 10) == null, "Freshness reset requires confirmation");

        var jitter = new AlertPolicy();
        var object = confirm(jitter, List.of(person(.20f)), 0);
        require(object.direction() == 0, "Starts left");
        jitter.update(List.of(person(.24f)), 300, 10);
        require(jitter.current().direction() == 0, "Direction has boundary hysteresis");

        var moving = new AlertPolicy();
        var start = confirm(moving, List.of(person(.1f)), 0);
        moving.announced(start, 200);
        moving.update(List.of(person(.2f)), 300, 10);
        moving.update(List.of(person(.3f)), 400, 10);
        moving.update(List.of(person(.3f)), 500, 10);
        var middle = moving.update(List.of(person(.3f)), 600, 10);
        require(middle != null && middle.trackId() == start.trackId() && middle.direction() == 1,
                "Moving track retains identity with a confirmed direction change");
        require(moving.announcement(2_800) != null, "Direction change becomes eligible after cooldown");

        var crossing = new AlertPolicy();
        var before = confirm(crossing, List.of(person(.1f), person(.65f)), 0);
        crossing.update(List.of(person(.65f)), 300, 10);
        require(crossing.current().trackId() != before.trackId(), "Same class keeps separate identities");
        require(confirm(new AlertPolicy(), List.of(
                new AlertPolicy.Box(56, .9f, .1f, .2f, .101f, .201f)), 0) == null, "Tiny boxes ignored");
        System.out.println("Alert policy regression checks passed");
    }
}
