package eg.sbs.benchmark;

/** Generated captions are descriptions only and never become metric depth or movement decisions. */
final class CaptionGuard {
    record Result(boolean accepted, String text, String reason) {}
    static final String PROMPT = "صف الأجسام الظاهرة في الصورة بالعربية في جملتين قصيرتين فقط. "
            + "اذكر العلاقات المكانية الواضحة فقط. لا تخمن أجسامًا مخفية. "
            + "لا تذكر أي مسافة رقمية ولا تعليمات مشي أو حركة أو استخدام عصا.";

    static Result inspect(String text) {
        if (text == null) return new Result(false, "", "empty_caption");
        String clean = text.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").strip();
        if (clean.isEmpty() || clean.indexOf('\ufffd') >= 0) return new Result(false, "", "empty_caption");
        if (clean.length() > 900) clean = clean.substring(0, 900);
        if (clean.matches("(?is).*[0-9٠-٩][0-9٠-٩.,٫ ]*\\s*(متر|أمتار|سنتيمتر|سم|cm\\b|meters?\\b|metres?\\b|m\\b).*")
                || clean.matches("(?is).*(تحرّ?ك|امش|اتّ?جه|استخدم العصا|اتحقق بالعصا|تحقق بالعصا|walk forward|turn left|turn right).*"))
            return new Result(false, "", "unsupported_distance_or_navigation_claim");
        String normalized = clean.replaceAll("[\\u064B-\\u065F\\u0670]", "");
        if (normalized.matches("(?is).*(مسافة|أمتار|امتار|متر|سنتيمتر|العصا|عصاية|عصايه|(?<![\\p{L}])(تحرك|امش|امشي|اتجه|توقف|قف|لف|استخدم|استعمل)(?![\\p{L}])).*"))
            return new Result(false, "", "unsupported_distance_or_navigation_claim");
        long arabic = clean.codePoints().filter(c -> c >= 0x0620 && c <= 0x064a).count();
        if (arabic < 8) return new Result(false, "", "arabic_description_not_produced");
        return new Result(true, clean, "generated_image_description_not_a_measurement");
    }
}
