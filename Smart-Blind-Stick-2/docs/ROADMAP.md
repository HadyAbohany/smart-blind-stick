# Seven-phase roadmap

| Phase / planning window | Tasks and tools | Dependency | Deliverable | Main risk | Acceptance |
|---|---|---|---|---|---|
| 1 · Week 1 | Inspect existing Arduino/sensor, photo and oscilloscope, document cane geometry and requirements | Team hardware | Populated inventory and V1 baseline | Incorrect sensor identification | Sensor pinout/waveforms and owner confirmed, BOM approved |
| 2 · Weeks 1–2 | Build SW5 star and peripheral branches with DMM, current-limited source and scope. Flash local firmware. | 1 and parts | Rev A perfboard + serial logs | Brownouts, 5 V ECHO, wet sensor drift | Electrical, range and water gates pass independently of phone |
| 3 · Weeks 2–3 | Verify camera AP, export ONNX/local data, profile target phone camera pipeline | Camera/programmer and handset | Model manifest, mount and timing report | Lossy video, heat, accuracy | Android capture plus sustained vision gate on at least one phone |
| 4 · Weeks 2–4 | Build Android demo, enroll, complete TalkBack dashboard and reconnect. Android Studio/ADB | Stable GATT contract, or hardware after 2 | Signed-off BLE/UX demo | Bonding and simultaneous radios | Encrypted reads and reconnect gates on two phones |
| 5 · Weeks 3–5 | Implement and verify contacts/GNSS/draft and lab SMS. Replay IMU fixtures, add release session service if scope permits | 4 for stick button; mock can start early | Cancel and delivery matrix, event-state tests | False triggers, OEM stops, SIM restrictions | Mock/cancel sends zero, supervised stable-network send gate passes |
| 6 · Weeks 5–6 | Run combined power, interference, failure and usability trials | 2–5 | Results dataset, labeled failures, revised mounts | Shared supply and user confusion | Tests in TEST_PLAN with logged exceptions and lead review |
| 7 · Weeks 7–8 | Practice standalone live demo and poster/technical paper, publish BOM and limitations | 6 | Demo, engineering log, plain-language results, video fallback | Overclaiming results / unreliable venue Wi-Fi | Repeat three complete dry runs, include deliberate link loss and countdown cancel |

Critical path follows hardware identification → electrical gates → BLE/camera paths working concurrently → emergency coverage and combined tests → competition rehearsal. Camera bench work and offline model evaluation can run while the hardware team brings up local haptics. App UI, telemetry parser, mock escalation and accessible review can start before BLE hardware. Competition research and sourcing can proceed independently. Label any delay or scope cut with the test gate affected.
