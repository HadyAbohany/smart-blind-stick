package eg.sbs.benchmark;

import java.util.List;

/** Room descriptions use object/direction clips; attention adds only the short word انتبه. */
final class DecisionSpeech {
    private DecisionSpeech() {}

    static String prefix(DecisionEngine.Decision d) {
        return d.reason() == DecisionEngine.Reason.VISUALLY_NEAR_CENTER ? "notice"
                : d.reason() == DecisionEngine.Reason.SEARCH_TARGET ? "target" : null;
    }

    static String prefixText(String asset) {
        if (asset == null) return "";
        return switch (asset) {
            case "notice" -> "انتبه";
            case "target" -> "الهدف المطلوب";
            default -> throw new IllegalArgumentException("Unknown speech asset: " + asset);
        };
    }

    static List<String> assets(DecisionEngine.Decision d) {
        String object = d.classId() + "_" + d.direction();
        String prefix = prefix(d);
        return prefix == null || d.still() ? List.of(object) : List.of(prefix, object);
    }

    static String objectPhrase(DecisionEngine.Decision d, String[] labels) {
        String direction = switch (d.direction()) {
            case 0 -> "على اليسار";
            case 2 -> "على اليمين";
            default -> "في المنتصف";
        };
        return labels[d.classId()] + " " + direction;
    }

    static String phrase(DecisionEngine.Decision d, String[] labels) {
        String object = objectPhrase(d, labels);
        String prefix = prefix(d);
        return prefix == null || d.still() ? object : prefixText(prefix) + ". " + object;
    }

    static String explanation(DecisionEngine.Decision d) {
        return switch (d.reason()) {
            case DESCRIPTION -> "وصف الموجود واتجاهه حسب الصورة";
            case SEARCH_TARGET -> "تم رصد هدف البحث";
            case VISUALLY_NEAR_CENTER -> "ظاهر كبير قدام الكاميرا؛ القرب تقدير بصري والمسافة مش مقاسة";
        };
    }
}
