# Circuit bring-up

- [Readable SVG schematic](../hardware/schematics/schematic.svg)
- [Connection sheet with terminal names and polarities](../hardware/schematics/connection-sheet.svg)
- [Complete terminal-to-net map](../hardware/schematics/nets.tsv) and [parts](../hardware/schematics/parts.tsv)
- [Power diagram and assembly order](../hardware/assembly/power-and-placement.md)

| DevKitC mark | Direction | Connect to | Electrical purpose |
|---|---|---|---|
| 5V | in | S1 switched 5 V star | Board regulator input; disconnect external supply for USB flashing |
| GND | return | common ground star | Common reference, dedicated motor/camera return |
| 3V3 | out | R9 to IO33 | SOS pull-up only |
| IO26 | out | SN74AHCT125N pin 2, R7 100k to GND | 3.3 V trigger to 5 V buffer |
| IO27 | in | Junction R1 10k from Echo and R2 15k to GND | Reduce 5 V echo to nominal 3.0 V |
| IO25 | out | R3 100Ω to AO3400A gate, R4 100k from gate to ground | Haptic switch |
| IO32 | out | R5 10k to probe A | Excite the probe briefly each cycle |
| IO34 | analog input | Probe B, R6 100k and C5 100nF to GND | ADC1, external pull-down required |
| IO33 | in, active low | SW2 to GND, R9 10k to 3V3 | Hold SOS for 1.5 s |
| J2.1 / J2.2 | rail | ESP32-CAM 5V / GND | Camera has its own MCU and no data wire to U1 |

## Checklist before energizing

1. تحقق من ترتيب الأرجل من الـ silkscreen ومن الـ datasheet. ESP32 WROOM pin numbers في الجدول GPIO. بورد تاني أو clone يحتاج مراجعة. HC-SR04 أربع أرجل وعليه VCC/Trig/Echo/GND. الـ buffer notch لازم يتطابق مع pin 1.
2. من 5 V echo، الحساب 5 × 15/(10+15) = 3.00 V. عند rail 5.25 V ومع أسوأ tolerance 1%، حوالي 3.18 V. على rail 4.75 V ومع أسوأ tolerance، حوالي 2.83 V لو echo فعلاً وصل لنفس rail. قيس high/min و overshoot على مقسم الجهد وافحص الجهد والـ GPIO logic limits في الحالة الفعلية. AHCT على 5 V بيقبل 3.3 V input لأن TTL VIH min = 2 V. [Espressif DevKitC](https://docs.espressif.com/projects/esp-dev-kits/en/latest/esp32/esp32-devkitc/user_guide.html) · [TI AHCT125](https://www.ti.com/product/SN74AHCT125)
3. راجع MOSFET pinout على الـ breakout. Short gate للـ GND في أول اختبار موتور، بعدين افحص gate pull-down وdiode orientation. قيس motor start/run current والـ rail مع الكاميرا. لو الجهد خرج من 4.75–5.25 V عند الأطراف، اقفل وافحص الكابل والـ inrush.
4. قيس probe مع 10k بدل مية، بعدين tap water ومية منخفضة التوصيل. عند قصر الـ electrodes، أقصى تيار 3.3/10k = 0.33 mA. C5 بيثبت الـ ADC. ADC ممكن يوصل saturation عند البلل القوي، والهدف threshold مش قياس تركيز. ركّب الـ probe بعيد عن المعدن الأرضي.
5. ممنوع وصل 5V خارجي وUSB programmer للـ DevKitC في نفس الوقت. افصل J2 قبل CAM-MB. افحص polarity للمكثفات والـ bank. ابدأ على مصدر current-limited، وخلّي الفيوز وحماية الـ bank في المسار.
6. قرب المقبض ركّب المشابك من غير حواف حادة. افحص تمدد الأسلاك والانحناء. الـ HC-SR04 بوجه مفتوح يحتاج تشغيل جاف في النسخة الأولى. أي ادّعاء IP يحتاج اختبار حقيقي. الحساس المبلول ممكن يدي miss أو echo خاطئ.

One ultrasonic zone is the shipped design. For a second zone, use another buffer channel with its own trigger and echo divider and a new schematic/firmware revision. Ping sequentially and measure crosstalk. No spare GPIO is assigned by the current firmware.
