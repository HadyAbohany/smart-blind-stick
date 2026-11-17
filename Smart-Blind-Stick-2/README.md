# Smart Blind Stick 2.0

**الاختيار التنفيذي** هو ESP32-DevKitC للتحذير المحلي، وESP32-CAM بكاميرا OV2640 مثبتة على العصاية. الـ Android يشغّل YOLO26n offline، ويستقبل البيانات بوصلة BLE. توصيل الكاميرا Wi-Fi محلي على شبكة خاصة. الـ power bank يشغّل العصاية من غير لابتوب، والاهتزاز المحلي مستقل عن كل وظائف الموبايل.

الموجود بالفعل Arduino ومستشعر مسؤول عن الإنذار عند الاقتراب. الموديل والتوصيل لسه غير مؤكدين. حافظ على V1 وهي شغالة خلال الاختبارات. استخدم جسم العصاية، والمستشعر لو اختبار التوافق نجح، والـ Arduino كـ baseline.

التكلفة المرجعية **2,580 جنيه** من غير تمن العصاية والموبايل. تبقى **2,540 جنيه** مع إعادة استخدام المستشعر المتوافق، أو **1,695 جنيه** مع إعادة استخدام المستشعر والـ power bank. أول تجربة كاملة على الترابيزة ممكن تنزل لـ **1,500 جنيه** بالشروط المفصلة في الـ BOM. النتائج estimated وتتضمن بنود محتاجة عرض سعر.

## Build in this order

1. Fill in the [existing inventory](prototypes/inventory.tsv), then review the [BOM and local listings](docs/COMPONENTS_BOM.md).
2. Open the [electrical schematic](hardware/schematics/schematic.svg), [exact pin mapping](docs/WIRING_GUIDE.md), [power budget](docs/POWER_BUDGET.md) and [placement guide](hardware/assembly/power-and-placement.md).
3. Flash the [local and camera sketches](firmware/README.md) after circuit bring-up. Execute the [host logic tests](hardware/simulations/README.md).
4. Build the [Android starter](mobile-app/README.md) and package the exported [offline YOLO model](ai/model_research/README.md). Use demo APK for workflow drills and lab APK only on the supervised bench.
5. Work through the [test plan](docs/TEST_PLAN.md) and [validation status](VALIDATION_STATUS.md) before demonstrating with participants.

## Design and research

[Product requirements](docs/PRODUCT_VISION.md) · [Architecture comparison](docs/ARCHITECTURE_COMPARISON.md) · [System and protocol](docs/TECHNICAL_ARCHITECTURE.md) · [Feature priorities](docs/FEATURE_RESEARCH.md) · [Roadmap](docs/ROADMAP.md) · [Competition strategy](docs/COMPETITION_STRATEGY.md)

This is a university assistive prototype for supervised evaluation alongside a normal mobility cane.
