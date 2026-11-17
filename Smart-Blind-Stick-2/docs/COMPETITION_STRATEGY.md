# Research and competition strategy

**المشكلة.** العصاية العادية بتدي feedback ميكانيكي أساسي. العوائق فوق ارتفاع طرفها، البلل في المسار، واسترجاع موقف طوارئ بالموبايل ليهم قيود مختلفة. الجمهور الأول مستخدمين عصاية مكفوفين أو ضعاف بصر في مصر. اسأل مختص Orientation & Mobility ومستخدمين موافقين عن الأولويات قبل تثبيت الـ UX.

WeWALK Smart Cane 2 عنده standalone obstacle/haptic/voice وsmartphone navigation. خلي كمان العصاية البيضاء العادية وArduino V1 baseline في التجربة. مقارنتكم الأساسية محلية على التكلفة وسلوك الفشل ووضوح الإشارة. وجود AI وBluetooth في نفسه مش ادعاء novelty. [WeWALK user manual](https://support.wewalk.io/en/article/wewalk-smart-cane-2-users-manual/)

**ثلاث أفكار تتقاس.**

1. قياس استمرار التحذير أثناء أعطال حقيقية ومقصودة في الـ phone/Wi-Fi/BLE على نفس المسار. سجّل latency local تحت كل fault، واعرض disconnect قدام لجنة التحكيم.
2. طقم تركيب قابل للفك يعيد استخدام العصاية والمستشعر، ومجس مية قابل للتبديل من غير تغيير بقية الدائرة. قيس زمن الاستبدال، الوزن، التكلفة، والانحراف بعد إعادة التركيب.
3. تقييم cue recognition وآثار الكلام على عبء المستخدم. قارن اهتزاز فقط مقابل اهتزاز + صوت سياقي قليل، مع تعريب الرسائل واختبار فهمها بعد ما تظهر فعليًا في التطبيق.

**Comparative experiment.** Pre-register low-risk route and a within-condition sequence with 30 bench trials per mode. Modes are V1, V2 local only, V2 combined and V2 with injected connection loss. Randomize obstacle placement and mode order. Use identical obstacle geometry and haptic/alert onset measurement. Report near detection rate, median/p95 cue onset and false alerts per minute. In a separately approved supervised usability pilot, compare task completion, haptic recognition and subjective workload without measuring or claiming reduced injuries. Include CIs, missed cases and test fixture design. Normalize hardware cost by reused vs new parts. Avoid aggregating by video frame when measuring user-facing alerts.

**مسابقات.** IEEE YESIST12 Special Track يدعم hardware والعمل في جودة الحياة ومواءمة الهدف الاجتماعي. شوف دورة التقديم التالية والـ pilot المحلي أو direct route. AASTMT استضاف فعاليات سابقة في مصر، فتابع مكتب الابتكار بالجامعة أو IEEE Student Branch لمعرفة الدورة والفرص المحلية. ISEF يوفر engineering rubric مفيد في التقييم، ومشاركته الأساسية لمرحلة ما قبل الجامعة. العرض يقدر يناسب embedded engineering أو assistive tech، والـ AI track محتاج نتائج مقارنة النموذج وقيود بياناته. [IEEE YESIST12 Special Track](https://ieeeyesist12.org/tracks/special-track/) · [Egypt event history](https://ieeeyesist12.org/wp-content/uploads/2025/01/IEEE-YESIST12-2023-Grand-Finale-Report.pdf) · [ISEF judging](https://www.societyforscience.org/isef/grand-award/criteria/) · [ISEF pre-college rules](https://www.societyforscience.org/isef/international-rules/)

**Demo readiness.** اعرض baseline Arduino، وبعدها حط عائق/مياه في tray، افصل الموبايل، وافتح الموبايل تاني عشان تعرض category. شغّل mock fall وسجّل cancellation. اعرض SMS receipt تم قياسه مسبقًا، واعمل إرسال live فقط برقم معمل موافق ومتوقع الرسالة. احتفظ بفيديو fallback محلي. قبل تقديم social impact، وضّح مهام الاستخدام المدعومة، التكلفة الفعلية، قابلية الإصلاح بالعناصر المحلية، والقيود اللي اتقاست.
