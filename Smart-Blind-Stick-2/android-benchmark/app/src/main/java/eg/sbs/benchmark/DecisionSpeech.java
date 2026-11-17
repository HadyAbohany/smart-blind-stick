package eg.sbs.benchmark;

import java.util.List;

/** The UI, audio manifest and exported report share the same Arabic wording. */
final class DecisionSpeech {
    private DecisionSpeech() {}

    static String prefix(DecisionEngine.Decision decision) {
        return switch (decision.reason()) {
            case CENTER_OBJECT -> "notice";
            case LARGE_CENTER_OBJECT -> "large_center";
            case CENTER_VEHICLE -> "vehicle_center";
            case ENTERING_CENTER -> "entering_center";
            case IMAGE_EXPANSION -> "image_expansion";
            case SEARCH_TARGET -> "target";
            default -> null;
        };
    }

    static String prefixText(String asset) {
        if (asset == null) return "";
        return switch (asset) {
            case "notice" -> "انتبه";
            case "large_center" -> "تمهّل، واتحقق بالعصاية من اللي قدامك";
            case "vehicle_center" -> "انتبه لمركبة قدام الكاميرا. تمهّل";
            case "entering_center" -> "حركة باتجاه منتصف الصورة. تمهّل وانتبه";
            case "image_expansion" -> "حجم الجسم بيزيد في الصورة. تمهّل وانتبه";
            case "target" -> "الهدف المطلوب";
            default -> throw new IllegalArgumentException("Unknown speech asset: " + asset);
        };
    }

    static List<String> assets(DecisionEngine.Decision decision) {
        String object = decision.classId() + "_" + decision.direction();
        String prefix = prefix(decision);
        return prefix == null || decision.still() ? List.of(object) : List.of(prefix, object);
    }

    static String phrase(DecisionEngine.Decision decision, String[] labels) {
        String direction = switch (decision.direction()) {
            case 0 -> "على اليسار";
            case 2 -> "على اليمين";
            default -> "في المنتصف";
        };
        String object = labels[decision.classId()] + " " + direction;
        String prefix = prefix(decision);
        return prefix == null || decision.still() ? object : prefixText(prefix) + ". " + object;
    }

    static String explanation(DecisionEngine.Decision decision) {
        return switch (decision.reason()) {
            case DESCRIPTION -> "وصف عند الطلب";
            case SEARCH_TARGET -> "تم رصد هدف البحث";
            case CENTER_OBJECT -> "جسم مؤكد في المنطقة الأمامية للصورة";
            case LARGE_CENTER_OBJECT -> "يشغل مساحة كبيرة في المنطقة الأمامية؛ المسافة غير معروفة";
            case CENTER_VEHICLE -> "مركبة في المنطقة الأمامية؛ اتجاه حركتها الفعلي غير معروف";
            case ENTERING_CENTER -> "حركة متتابعة داخل الصورة؛ ليست تقديرًا لمسار فعلي";
            case IMAGE_EXPANSION -> "زيادة متتابعة في مساحة الصندوق؛ ليست قياسًا للمسافة";
        };
    }
}
