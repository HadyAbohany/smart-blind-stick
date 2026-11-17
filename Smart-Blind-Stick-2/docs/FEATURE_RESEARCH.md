# Feature decisions

تكاليف الإضافات تقريبية، والجداول بتفترض إن البطارية والموبايل والدائرة الأساسية موجودين. «قيمة» و«تميّز» أحكام هندسية أولية تُراجع مع المستخدمين.

| Feature | Category | User value / differentiation | Feasibility, hardware, extra EGP | Effort and reliability |
|---|---|---|---|---|
| Front ultrasonic + vibration | Essential MVP | High / low | High, HC-SR04 + ERM included | Medium; narrow cone, soft and angled targets missed |
| Water contact | Essential MVP | High / medium | High, replaceable probe included | Medium; conductivity, dirt, droplet bridging and corrosion |
| Camera YOLO | Essential MVP | Medium–high / low alone | Medium, camera and Android included | High; blur, contrast, heat and false labels |
| BLE, reconnect, status | Essential MVP | High / low | High, ESP32 included | Medium; permission, interference, background |
| GPS event fix and contact list | Essential MVP | High / low | High, onboard Android GNSS, 0 | Medium; indoor fix/permission and active SIM |
| One-handed SOS, cancellation, escalation | Essential MVP | High / medium together | Medium, tactile button and Android included | High; test network, accidental presses and explicit delivery status |
| Phone fall detection | Essential MVP | High / medium | Medium on body, gyro + accel, 0 | High; phone drops and slow collapses are human-factors challenges |
| Accessible simple Android interface | Essential MVP | High / medium in local languages | High, native controls, 0 | Medium; real TalkBack and large-font tasks |
| Rechargeable power | Essential MVP | High / low | High, USB bank included | Low; idle cutoff and cable pull-out matter |
| Pedestrian recognition | Recommended for Competition | Medium / low | High, existing COCO model, 0 | Medium; crowds and duplicate announcements |
| Vehicle and bicycle labels | Recommended for Competition | Medium / medium locally | Medium, existing COCO model, 0 | Medium; classes as context only, no speed or safe-crossing decision |
| Voice context, Arabic/English | Recommended for Competition | High / medium | High, offline voice packs or bundled phrases, 0 hardware | Medium; pack availability, street noise, audio masking. Measure comprehension. |
| Adjustable haptic intensity or pattern | Recommended for Competition | Medium–high / medium | High, same motor, 0 | Medium; constrain patterns that preserve near/water distinction |
| Connection-loss cue and visible degradation | Recommended for Competition | High / medium | High, existing BLE, 0 | Low–medium; dedup to avoid nuisance from transient radio gaps |
| Low-battery indicator | Recommended for Competition | High / medium | Medium, bank LEDs today; instrumented 5 V energy logger ~250 | Medium; external rail energy alone can't infer arbitrary initial charge |
| Ground-level obstacle zone | Future Enhancement | High / medium | Medium, second time-multiplexed sensor + mount ~100–250 | High; sweep, cane angle and cross-talk |
| Stair/upward step recognition | Future Enhancement | High / high | Medium, image labels + downward sensor/IMU ~300–900 | High; geometry and surface vary |
| Downward drop-off | Future Enhancement | High / high | Low until calibrated, ToF or dual beam + IMU ~400–1,200 | High; sloped ground, no return, specular surfaces. Controlled lab only. |
| Motion warning | Future Enhancement | Medium / medium | Medium, ego-motion and multi-frame tracking, 0 hardware initially | High; relative motion and cane sweep confound |
| Doorway context | Future Enhancement | Medium / medium | Medium, local labeled data, 0 hardware | High; occlusions and unknown door state |
| Direction estimation | Future Enhancement | Medium / medium | Medium, left/center/right optical cues, second sensor if range ~100–250 | Medium–high; describe direction relative to camera and track alignment |
| Find My Stick nearby | Future Enhancement | Medium / low | Medium, current motor if in BLE range; optional buzzer/driver ~40–100 | Medium; room acoustics and dead battery |
| Crosswalk markings for research | Future Enhancement | Medium / medium | Medium, edge dataset, 0 hardware | High; worn paint, occlusion and shadows |
| Crosswalk clearance / autonomous road guidance | Not Recommended | Apparent high / low | Low; needs more sensing and validation | Unacceptable false-clear risk for this build |
| Cloud positioning and continuous tracking | Not Recommended | Low for event SOS / low | Requires network/service and explicit privacy controls | Adds cost and privacy exposure |
| Replaceable probe + failure-aware demo | Recommended for Competition | High / high if measured | Medium, same BOM or spare module | Medium; measure swaps, corrosion and fault recovery |

للـ competition اختار 3 حاجات تتقاس: استمرار التحذير أثناء انقطاع الشبكة، قابلية تمييز الاهتزازات، واستبدال مجس الماء بزمن قليل. ضيف العربي لو التطبيق والـ TTS متاحين. اعرض باقي المهام كـ roadmap.
