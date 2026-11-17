# BOM and Egyptian procurement

الأسعار بالجنيه المصري، صفحات الموردين اتراجعت 15 نوفمبر 2026. السعر المعروض مش ضمان للمخزون أو الشحن. عمود «تقدير» مخصص للمخصصات اللي لسه محتاجة عرض سعر. الأرقام لكل السطر.

| Component | Exact model / spec | Purpose | Qty | V | Current / power | EGP | Local supplier / evidence | Priority |
|---|---|---|---:|---|---|---:|---|---|
| U1 | ESP32-DevKitC V4 WROOM-32D | Local sensing + BLE | 1 | 5 in / 3.3 IO | 80 mA avg / 250 mA design surge | 450 | [Ekostra listing](https://ekostra.com/ar/product/purple-esp32-38pin-expansion-board/) | Required |
| U2 | HC-SR04 four-pin, 5 V mode | Range | 1 | 5 | 15 mA allowance | 40 | [Makers HC-SR04 from 40](https://makerselectronics.com/product/hc-sr04-ultrasonic-sensor-detector/) | Reuse if compatible |
| Camera | AI-Thinker ESP32-CAM + OV2640 + PSRAM | JPEG capture and AP | 1 | 5 | 200 mA avg / 500 mA design surge | 495 | [Makers](https://makerselectronics.com/product/esp32-cam-esp32-s-wifibluetooth-with-ov2640-camera-module/) | Required |
| Programmer | ESP32-CAM-MB | Flash camera | 1 shared | USB 5 | Programmer only | 75 | [Makers add-on](https://makerselectronics.com/product/esp32-cam-esp32-s-wifibluetooth-with-ov2640-camera-module/) | Borrow or buy |
| JP1 | 316 stainless M3 screws, insulated carrier | Wet bridge | 2 contacts | 3.3 pulse | ≤0.33 mA short-circuit excitation | 40 | Local fastener shop, estimate | Required |
| M1 | 5 V ERM, ≤100 mA loaded and ≤250 mA start/stall | Haptics | 1 | 5 | ≤0.5 W run | 85 | Makers/RAM enquiry, estimate. Qualify before purchase. | Required |
| U3 | SN74AHCT125N DIP-14 | Raises trigger to 5 V | 1 | 5 | 2 mA allowance incl. input | 25 | RAM enquiry, estimate | Required |
| Q1 | AO3400A SOT-23 + adapter | Motor driver | 1 each | 3.3 gate / 5 drain rail | 250 mA branch pulse | 25 | RAM/Makers enquiry, estimate | Required |
| D1 | 1N5819 | Flyback across motor | 1 | 40 V reverse part rating | ≥250 mA transient | 5 | RAM, estimate | Required |
| R1–R9 | 10k×4, 15k×1, 100Ω×1, 100k×3, ¼ W through-hole | Divider, pull, gate and probe | 9 | 3.3/5 | <1 mA per fixed-bias branch | 18 | RAM, estimate | Required |
| C1–C5 | 470µF/10 V, 220µF/10 V, 100nF/≥16 V ×3 | Bulk, decoupling and ADC | 5 | 5/3.3 | Inrush depends on source | 42 | RAM, estimate | Required |
| SW2 | Normally open tactile momentary, 12×12 mm with cap | Hold-to-SOS | 1 | 3.3 | ~0.33 mA through 10k | 20 | RAM, estimate | Required |
| Battery | Redmi PB100LZM | Protected rechargeable USB supply with charger and regulation | 1 | 5.1 output | 2.4 A rated one port | 845 | [iMedia listing](https://www.imediastores.com/product/xiaomi-redmi-pb100lzm-power-bank-10000mah-dual-usb/) · [Xiaomi specs](https://www.mi.com/eg/product/10000mah-redmi-power-bank/specs/) | Reuse equivalent if qualified |
| F1 | Bourns MF-R150 | Resettable branch protection | 1 | ≤60 V rated | 1.5 A hold nominal | 30 | RAM enquiry, estimate | Required |
| S1 | SPST panel switch with ≥2 A DC rating at 5 V | Power | 1 | 5 | ≥2 A contact rating | 35 | RAM, estimate | Required |
| Wiring | USB-A pigtail, JST-VH 2-way power, 4-way sensor and 2-way JST-XH for low-current leads, 22–24 AWG, heat-shrink | Power and removable wiring | 1 set | 5/3.3 | Power leads ≥2 A | 95 | RAM/Makers, estimate | Required |
| Prototyping | 400 tie-point breadboard, 50×70 mm perfboard and headers | Bench + soldered carrier | 1 set | 5/3.3 | Power star on soldered wires | 55 | RAM, estimate | Required |
| Housing | ABS electronics box, clamps, grommets, lens window, motor mount, screws | Strain relief and mounting | 1 set | — | — | 200 | Local fab, estimate | Required |
| Buzzer | Existing model TBD | Keep on V1 baseline, or optional later using its datasheet and driver | 0 new | TBD | TBD | 0 | Existing alarm hardware unknown | Optional |
| Android | Borrow existing Android 12+ with BLE, GNSS and gyro | AI, app, GPS, emergency | 1 | own battery | Measure per phone | 0 assumed | Inventory | Required |

M1 purchasing gate. المورد لازم يثبت تشغيل مستمر عند 5.25 V أو يحدد motor rated لنفس النطاق، والتيار يتقاس قبل اعتماده. البند متساب بمواصفة شراء لأن SKU محلي متاح وحدود التيار بتاعته متأكدناش منها. قطع 3 V تحتاج rail مختلف. راجع [AO3400A](https://www.aosmd.com/sites/default/files/res/datasheets/AO3400A.pdf) و[SN74AHCT125](https://www.ti.com/product/SN74AHCT125).

## Three tiers

| Tier | Budgeted total replacing the listed parts | Incremental cash outlay, with assumptions |
|---|---:|---|
| Minimum viable, all core subsystem paths on bench | EGP 2,580 reference replacement value | EGP 1,500 with reusable HC-SR04 (40), borrowed CAM-MB (75), borrowed bank (845), and temporary mount costing 80 instead of 200. |
| Recommended competition, replace all line items | **EGP 2,580** | EGP 2,540 with confirmed sensor reuse. EGP 2,580 if no other reuse, besides the cane and phone. EGP 1,695 if sensor and bank already available. |
| Advanced, same design + measured energy + removable spare sensor pod | EGP 3,240 estimate | EGP 3,200 with sensor reuse, or EGP 2,355 with sensor and bank reuse. Adds EGP 660 allowance for inline USB power logger (250), improved mount/spares (300), spare probe and sealing parts (110). |

Totals exclude new cane/phone, soldering tools, delivery, data/SMS charges and taxes where the supplier adds them. Set aside 15% contingency (~EGP 2,970 for the EGP 2,580 basket). Mount fabrication, battery and motor availability drive variance. Bench MVP means the full subsystem design is represented. Passing automatic SOS, vision and physical integration still requires testing.

## Existing parts and reuse

Arduino ونظام الإنذار الحالي موجودين، وباقي المواصفات مش معروفة. شغّل النسخة الحالية كـ baseline. لو الـ sensor فعلاً HC-SR04 أو compatible وتوصيله 4-pin Trigger/Echo ويفي بقياسات القبول، انقله بعد الاختبار. لو sensor بخرج analog أو UART أو مش ultrasonic، استخدم U2 في الـ BOM. Arduino نفسه يتستخدم للـ baseline واختبارات المقارنة. لو طلع Arduino بيدعم BLE ومناسب للـ timing والـ power، اعمل pin/firmware revision موثق قبل التفكير في استبدال U1. سجّل النتائج في [inventory.tsv](../prototypes/inventory.tsv).
