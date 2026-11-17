# Phase 1 · Minimum Electronics Bench Purchase

**GO مشروط لسلة واحدة.** افحص الموجود وموعد المعمل أولًا. الهدف اختبار local range/water/haptic/SOS button، وتوصيل BLE لو هاتف متاح بعد التأكيد. مصدر المعمل current-limited هو حماية المصدر في المرحلة دي. الـ BOM الكامل والـ camera setup بيتأجلوا لحد Gate B.

| القطعة | الكمية | مواصفة الشراء | مخصص EGP |
|---|---:|---|---:|
| U1 | 1 | Espressif ESP32-DevKitC V4 WROOM-32E/32D أو DevKitC-compatible مُراجع بنفس الست GPIO و5V/3V3/GND. لازم صورة/marking وpinout. عرض EK1332 Type-C مرجع سعر فقط. | 450 مرجع |
| U2 | 0 أو 1 | HC-SR04 4-pin في 5V mode لو المستشعر الحالي غير مثبت التوافق. اطلب رد على pin order وارتفاع Echo. | 0–40 سعر يبدأ من |
| U3 | 1 | SN74AHCT125N DIP-14. | 25 تقدير |
| Q1 | 1 | AO3400A + SOT-23 breakout مركّب/ملحوم، pinout واضح. | 25 تقدير |
| M1 | 1 | ERM بمواصفة مورد حتى 5.25V، ≤100mA run و≤250mA start/stall. لا تعتمد SKU موتور 3V. | 85 تقدير |
| D1 | 1 | 1N5819 Schottky axial stripe واضح. | 5 تقدير |
| R1–R9 | 9 | 10k×4، 15k×1، 100Ω×1، 100k×3. خُد R1 وR2 1%. | 18 تقدير |
| C1/C2/C4/C5 | 4 | 470µF ≥10V ×1 و100nF ceramic ≥16V ×3. C3 بتاع الكاميرا لاحقًا. | 42 مخصص من BOM |
| SW2 | 1 | Normally open momentary switch مناسب low-current contact. | 20 تقدير |
| JP1 | 2 electrodes + mount | 316 stainless، حامل عازل وتوصيل قابل للفصل. | 40 تقدير |
| Breadboard | 1 set | عريض أو لوحين لـ38-pin board عشان تسيب access للـ GPIO. استخدم wiring مباشر خارج breadboard للـ power star. | 55 مخصص من BOM |
| Bench wiring | 1 set | 22–24 AWG، jumpers قصيرة وأسلاك 5V/GND مميزة، connector keyed للـ supply، وكابل USB **data** حسب U1. | 95 مخصص. أكد سعر الكابل لو اتسعر لوحده |

**الإجمالي التخطيطي 860–900 جنيه** حسب إعادة استخدام U2. لو سعر البورد المطابق اختلف عن عرض الـ Type-C، عدّل الإجمالي. مفيش أسعار مؤكدة للموتور المؤهل أو معظم الإكسسوارات، والمجموع مش عرض سعر. الشحن وأجهزة المعمل والموبايل وأي كابل بسعر منفصل خارج الإجمالي. [DevKitC supplier listing](https://ekostra.com/ar/product/purple-esp32-38pin-expansion-board/) · [HC-SR04 example listing](https://makerselectronics.com/product/hc-sr04-ultrasonic-sensor-detector/) · [BOM reference](docs/COMPONENTS_BOM.md)

**لازم تتأكد إن المعمل يوفّر** 5V current-limited supply (≥1A محلي و≥2A لاختبار المرحلة اللاحقة)، DMM، oscilloscope، أداة قياس peak current، وحمل إلكتروني أو fixture مؤهل لمرحلة الكاميرا. دي متطلبات للوصول مش قطع مؤكدة إنكم تملكوها. استخدم Arduino الحالي كـ baseline، والمستشعر الحالي يتعاد استخدامه بعد scope test. ألغِ بند سعر القطعة المعادة من السلة الفعلية.

**التوفير الآمن.** وفّر تمن المصدر المحمول والعلبة والـ perfboard لحد ما دائرتكم تعدّي. استعارة مصدر معمل ومبرمج CAM-MB وهاتف مؤكد توفّر من غير حذف level shifting أو flyback. وحدة 30-pin أرخص ممكن تبقى بديل بعد إثبات matching pin map والـ power path ونجاح نفس الاختبارات، ومفيش داعي تغيّر الـ GPIO أو الـ architecture عشان فرق سعر صغير. احتفظ بـ AHCT125 في أول عينة عشان U2 variant مجهول. تشيله في نسخة تكلفة محسنة فقط بعد ضمان 3.3V trigger بجميع دفعات المستشعر المقصودة وإعادة اختبار. جرب isolated stainless electrodes بدل شراء probe module قائم على خرج 5V غير موثق.

**مؤجّل.** Camera + programmer، C3/J2، bank، enclosure، S1، الـ F1 النهائي، وأي SBC. للنسخة المحمولة اختبر بديل F1 مثل MF-R135 بعد حساب drop/thermal وقياس حمل الفرعين. MF-R135 في حد ذاته ما يضمنش إن كاميرا faulty ما تسقطش rail. لا تعتمد رقم `MF-R150` الموجود في BOM الأصلي. [Bourns part list](https://www.bourns.com/docs/Product-Datasheets/mfr.pdf)
