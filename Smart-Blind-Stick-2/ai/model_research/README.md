# Offline camera classification

## أول اختيار

YOLO26n COCO على Android. أول baseline هو ONNX Runtime CPU مع threads=2 عشان الـ Java integration مباشر. اختبر YOLO11n بنفس الصور للمقارنة. لو زمن الموبايل أو حرارة الجهاز ماعدّوش الاختبار، جرّب NCNN CPU/Vulkan بالـ Android SDK/JNI وقارن دقة نفس الصور بعد التصدير. LiteRT Interpreter/CompiledModel اختيار للموبايلات المناسبة، لكن Ultralytics علّم TFLite export كـ deprecated. اختبر conversion artifacts وdelegate operator coverage قبل اعتماده. MobileNet SSD مقياس خفيف للمقارنة البحثية. إعادة التدريب ممكن تبدأ بعد قياس gap واضح في بيانات محلية. [YOLO26](https://docs.ultralytics.com/models/yolo26/) · [NCNN](https://docs.ultralytics.com/integrations/ncnn) · [TFLite export](https://docs.ultralytics.com/integrations/tflite/) · [ONNX Runtime](https://onnxruntime.ai/docs/get-started/with-java.html) · [LiteRT](https://developers.google.com/edge/litert/android/development)

## Export and install

On a development computer, create a Python 3.11 virtual environment. Install an Ultralytics release supporting YOLO26 and its ONNX export dependencies. Obtain the official pretrained `yolo26n.pt` and record its hash and the exact exporter version. Run from the project root.

```sh
yolo export model=/absolute/path/yolo26n.pt format=onnx imgsz=320 batch=1 dynamic=False end2end=True nms=False simplify=False opset=17
cp /absolute/path/yolo26n.onnx mobile-app/app/src/main/assets/yolo26n.onnx
python ai/inference/replay.py /absolute/path/yolo26n.onnx /absolute/path/consented-frames
```

Check with ONNX tooling that the input is float32 NCHW [1,3,320,320] and the first output is [1,N,6] rows (x1,y1,x2,y2,confidence,COCO class). Compare boxes with the Ultralytics visual reference. The asset is excluded from git. Weights and runtime export compatibility need bench verification. Follow the [Ultralytics licensing terms](https://www.ultralytics.com/license) before distribution.

## Capture and alert policy

- 640×480 JPEG at source and letterbox to 320² RGB normalized 0–1. Re-test 416/640 only with a matching export and app tensor size. Start with the actual module's measured HFOV in the 55–75° design range. Mount near the grip and adjust pitch to bring the walking corridor into frame. Measure coverage on a floor/vertical grid with normal cane sweep.
- Initial allowlist is person, bicycle, car, motorcycle, bus, truck, bench, dog and chair. Labels describe camera context. For competition, assess person/bicycle/chair first so speech stays sparse. Treat glare, blur, dimness, crowds and occlusion as likely failure cases. Custom classes such as bollards, curb edges and doorways need scene annotations and a separate false-alert assessment.
- Start at score 0.50, within central 50% horizontally, two consecutive outputs of the same class and at least 4 s between speech attempts. Tighten by class against validation scenes. This prototype uses class consistency rather than object identity. Keep only one in-flight frame. Silence contextual speech when inference + fetch exceeds 500 ms, telemetry is stale, near/wet is active, or emergency countdown is active. Firmware continues independently.
- Test target ≥5 processed FPS sustained for 15 min, p95 camera-to-class ≤500 ms and p95 camera-to-first-audible context ≤1 s after temporal gating. Two confirmations at 5 FPS already add about 200 ms. At 1 m/s, 500 ms covers another 0.5 m. Use the result for optional context. Scope local haptic onset independently.
- HC-SR04 is one acoustic cone. A central camera box is weak evidence of overlap. Align at 0.5, 1 and 1.5 m with different reflectors, map both fields, and timestamp readings on a common phone monotonic clock with measured link skew. Log offsets across sweep angles. Even close timestamps and angular overlap cannot identify which object returned the echo. App never appends a range to a class. Firmware independently triggers haptics on the nearest reliable return.
- COCO weights can support initial categories. Fine-tune only after collecting consented Egyptian scenes and identifying recurring missed relevant classes. Split by route/day/person, keep a held-out site, compare precision/recall by light and size, and report uncertainty per class. Avoid shipping images or location traces by default.
