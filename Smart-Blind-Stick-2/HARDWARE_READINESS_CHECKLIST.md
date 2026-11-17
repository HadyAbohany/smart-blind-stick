# Hardware Readiness Checklist

تاريخ المراجعة 15 نوفمبر 2026. كل خانة محتاجة evidence باسم البورد ونسخة الكود ورقم الاختبار. اللي عليه ✓ اتعمل على الكمبيوتر. باقي الخانات محتاجة عمل في المعمل. سجّل في [bench-results.tsv](prototypes/bench-results.tsv)، واحتفظ بصور الـ scope ونتائج build مع كل run.

## قبل طلب قطع Phase 1

- [ ] صوّر Arduino الحالي وموديل المستشعر وعدد أطرافه والـ alarm الحالي. وثّق مصدره وتوصيله واعمل baseline. جسم العصاية والـ Arduino والمستشعر والإنذار هم المعروفين.
- [ ] احجز مصدر 5V معملي current-limited، DMM، scope وقناة لقياس current/oscilloscope shunt، وكابل USB data مناسب. خُد موافقة الوصول للمعمل قبل الاعتماد على الاستعارة.
- [ ] لـ U1 اطلب صورة front/back، module marking، pin map للـ six IOs و5V/GND و3V3، نوع USB والـ regulator/schematic لو clone. صفحة الـ BOM بتعرض Type-C وDevKitC-style، ودليل Espressif V4 بيعرض Micro USB. الصفحة وحدها مش دليل إن البورد الرسمي هو اللي هيتشحن. [Ekostra listing](https://ekostra.com/ar/product/purple-esp32-38pin-expansion-board/) · [Espressif official board](https://docs.espressif.com/projects/esp-dev-kits/en/latest/esp32/esp32-devkitc/user_guide.html)
- [ ] أكد رقم موتور ERM أو مورد يضمن مدى جهد حتى 5.25V وتيار ≤100mA run و≤250mA start/stall. اشترِ وحدة اختبار واحدة. قرر NC outputs مفتوحة في U3. علّم `MF-R150` كـ part number غير معتمد. [Bourns series](https://www.bourns.com/docs/Product-Datasheets/mfr.pdf)
- [x] الـ checker طابق ست GPIO assignments و74 terminal. C++ host logic وJava packet/fall tests عدّوا. الـ Python replay CLI ظهر بنجاح من غير weights.

## Gate A — Local electronics

- [ ] Build stick على Arduino-ESP32 3.2.1 واحفظ تقرير compilation وboard target. شغّل USB وحده أثناء flash. حدد power source واحد.
- [ ] راجع cold wiring بالـ DMM عند كل طرف. الـ 5V, 3V3 وGND واضحين ومفيش short. افحص AO3400A وflyback polarity وC1/C2/C4/C5؛ الـ NC pins غير متوصلة ببعض.
- [ ] شغّل board والـ buffer قبل توصيل IO27. قيس U2 Trigger وECHO3 بالـ scope قبل التوصيل، وبعده مع BLE والموتور. قيس VDD وbounds تحت الحمل. راجع low/high وringing وفق [خطوة 3](BENCH_TEST_PROCEDURE.md).
- [ ] الموتور ≤100mA أثناء التشغيل و≤250mA start/stall طبقًا لمواصفة الاختيار/القياس الآمن. Gate off أثناء reset، local supply مستقرة، والـ clamp waveform معقولة. تحقق من انقطاع وفرملة التحذير.
- [ ] عدّت near/wet tests والـ overlap priority وthreshold calibration. اتسجل نطاق البلل والحالات اللي بتفوت. اتوثق سلوك failed sensor، وأي failure محتاج تعديل اتعاد اختباره.
- [ ] Power cycles وlocal warnings مع phone off / BLE off ووقت pairing رفض أو فقد. SOS طويل مرة واحدة لكل hold، ومفيش false events في short press وreboot اختبار البداية مرتين بحالة الزر. عالج active-at-boot قبل عرض إرسال live.
- [ ] على الموبايل المتاح: build demo APK، pairing صحيح ومرفوض، fresh packets، reconnect median/p95، loss/stale display. جرّب mock/cancel من غير SMS وأكد مظاهر فقد GPS.

## Gate B — قبل شراء أو استعارة عينة الكاميرا

- [ ] Gate A موقعة، ووقت المعمل متاح لمراحل اختبار الكاميرا. اتحدد هاتف Android 12+ فعلي (model/SoC/OS/RAM) ويملك BLE وWi‑Fi. اتأكد من IMU لو هتجرّب fall.
- [ ] Build الكاميرا بArduino ESP32 3.2.1 للـ AI Thinker target. أكد من المورد OV2640 وPSRAM، توافق CAM-MB، وسعر وسياسة استبدال. عينة واحدة تكفي لإثبات المنظومة.
- [ ] Benchmark A على الموبايل بنفس artifact: ≥5.0 FPS simulated with 60ms injected JPEG transport، request→decision p95 ≤500ms including simulated transport، والـ boxes/classes متطابقة عمليًا مع reference. سجّل الـ profile. لو 60ms assumption ما تحققتش بعد شراء العينة، بيحكم benchmark B الحقيقي.

## Gate C — قبل شراء المنظومة الكاملة

- [ ] Benchmark B live حقق 5.0 processed FPS في كل 15min run وعلى الأقل في كل block ‏5min، وcapture→decision p95 ≤500ms والـ cue p95 ≤1s في حالات الاختبار المتفق عليها. سجّل precision/recall وlate/missing.
- [ ] BLE وcamera AP وcellular SMS اشتغلوا بالتوازي على الهاتف المستهدف. اتسجل فشل BLE وAP وrecoveries. اختبر SMS من جهاز لشريحة اختبار بموافقة صاحبها. تأكيد الاستلام من الطرف الآخر؛ cancel/mock ما يبعتوش.
- [ ] الباور المدمج بيفضل 4.75–5.25V على كل بورد من power-up ولحد النهاية تحت حمل، ومن غير resets. فرع الكاميرا عليه protection validated، وfault/inrush على الفرع ما يوقفش التحذير. استخدم electronic load محدود التيار لتقييم faults بدل short صريح مع البطارية.
- [ ] الـ bank اجتاز ساعتين idle محلي، ≥6h representative full, USB strain relief، وقياس الوزن/الحرارة. اتأكد من الـ verified SKU وتكلفة فعلية؛ الوثائق والـ BOM تتحدث بقطع الاختبار.
- [ ] لو background emergency مطلوبة للنسخة النهائية: إثبات session مستمر وnotification cancel في screen-off/OEM/Doze، وfallback عند restart/no coverage. Phase 1 والكاميرا ممكن يكملوا بscope معملي والـ background feature لسه blocked.
