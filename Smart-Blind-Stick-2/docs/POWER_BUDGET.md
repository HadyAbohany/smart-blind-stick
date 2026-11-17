# Power budget

| 5 V load | Design average | Design peak allowance |
|---|---:|---:|
| ESP32 BLE board | 80 mA | 250 mA |
| HC-SR04 + buffer + probe | 18 mA | 35 mA |
| Motor | 20 mA at assumed 20% duty | 250 mA at start/stall |
| ESP32-CAM with stream | 200 mA | 500 mA |
| Total | 318 mA | 1.035 A before capacitor inrush |

التيارات assumptions للتصميم. الـ phone battery منفصلة. الـ USB input 2 A minimum مع wiring rated ≥2 A. قيس start-up overshoot/inrush والموتور والـ Wi-Fi transmit burst متزامنين. MF-R150 hold بيتغير مع الحرارة. الفيوز جزء من الحماية، ومش current limiter سريع. قيس جهد الكاميرا والمتحكم أثناء peak وكفاءة البنـك تحت load منخفض.

- **Protected USB bank, recommended.** Redmi PB100LZM rated output 5.5 Ah at 5.1 V under its test condition، حوالي 28 Wh usable at rated condition. نحجز 15% للـ temperature/aging/cutoff، حوالي 23.8 Wh. التصميم الكامل 5.1 × 0.318 ≈ 1.62 W، حوالي **14.7 h estimated**. Local-only 0.118 A حوالي **39.5 h theoretical** قبل أثر low-load shutdown. حتى مع camera off، جرّب عشر دقايق idle ثم ساعتين بدون alerts. افصل البطارية عن العصاية للشحن. [Xiaomi rated output](https://www.mi.com/eg/product/10000mah-redmi-power-bank/specs/)
- **Smaller protected USB bank, alternative.** Xiaomi Magnetic Power Bank 5000 rated 2.8 Ah at 5 V via cable. 14 Wh ×0.85 / 1.59 W ≈ **7.5 h estimated**. Verify USB output at 5 V/≥2 A without PD negotiation using the chosen cable, mounting weight, idle cutoff and retail quote. [Xiaomi 5000 output specs](https://www.mi.com/eg/product/xiaomi-magnetic-power-bank-5000/)
- **Custom protected single-cell pack, future.** Example design calculation 3.7 V×3 Ah×0.85 boost efficiency×0.85 reserve ≈ 8 Wh or roughly 5 h with camera. Select a protected pack, matched charger with load sharing if needed, a regulated 5 V boost rated ≥2 A output with appropriate battery peak current (roughly 3.7 A at 3.2 V and 85% efficiency for 10 W), fuse and mechanically protected enclosure. USB bank integrates charging, regulation and protection and avoids loose cells. Avoid exposed lithium cells and charging while worn.

Measure total cane mass and added mass, runtime in representative haptic duty cycles, phone percentage consumed per hour and 5 V minimum over long runs. Bank LEDs indicate charge at the prototype; 5 V rail alone doesn't expose state of charge. Optional inline logger can estimate Wh consumed after calibration and reset at a known full charge. Avoid a GPIO shortcut to the battery cell.
