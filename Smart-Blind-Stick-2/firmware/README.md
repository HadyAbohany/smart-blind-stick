# Firmware bring-up

Install Arduino-ESP32 3.2.1 from Espressif in Arduino IDE. Compile and flash `stick/stick.ino` as ESP32 Dev Module (WROOM-32). Use USB alone during flashing. Read the persistent six-digit prototype pairing PIN at 115200 baud. Erase the stick's flash and forget the bond on Android before transferring it to a different operator. Factory provisioning still needs a physical pairing window and controlled bond reset.

Compile `camera/camera.ino` as AI Thinker ESP32-CAM with PSRAM. Use CAM-MB with the cane power disconnected. Read the per-camera SSID/key over USB at 115200. Reinstall on the 5 V/GND locking lead after flashing. `http://192.168.4.1/frame.jpg` returns a single JPEG on the camera network. Keep that network private. No video is saved or passed to a server.

Serial is for enrollment and diagnostics. Normal operation uses battery power. Probe thresholds are initial raw ADC guesses. Log dry, damp, wet and contaminated values and calibrate before use. The local SOS pulse indicates a button event only. See [the wire protocol](../docs/TECHNICAL_ARCHITECTURE.md).
