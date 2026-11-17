// AI-Thinker ESP32-CAM with OV2640 and PSRAM. Arduino ESP32 3.2.1.
#include <WiFi.h>
#include <WebServer.h>
#include <esp_camera.h>
#include <esp_system.h>
#include <Preferences.h>

WebServer web(80);
bool cameraReady;
uint32_t lastFrame;

void frame() {
    uint32_t now = millis();
    if (!cameraReady) return web.send(503, "text/plain", "Camera unavailable");
    if (now - lastFrame < 90) return web.send(429, "text/plain", "Retry later");
    lastFrame = now;
    camera_fb_t* fb = esp_camera_fb_get();
    if (!fb) return web.send(503, "text/plain", "Capture failed");
    WiFiClient client = web.client();
    client.setTimeout(500);
    web.sendHeader("Cache-Control", "no-store");
    web.setContentLength(fb->len);
    web.send(200, "image/jpeg", "");
    client.write(fb->buf, fb->len);
    esp_camera_fb_return(fb);
}

void setup() {
    Serial.begin(115200);
    camera_config_t cfg = {};
    cfg.ledc_channel = LEDC_CHANNEL_0;
    cfg.ledc_timer = LEDC_TIMER_0;
    cfg.pin_d0 = 5; cfg.pin_d1 = 18; cfg.pin_d2 = 19; cfg.pin_d3 = 21;
    cfg.pin_d4 = 36; cfg.pin_d5 = 39; cfg.pin_d6 = 34; cfg.pin_d7 = 35;
    cfg.pin_xclk = 0; cfg.pin_pclk = 22; cfg.pin_vsync = 25; cfg.pin_href = 23;
    cfg.pin_sccb_sda = 26; cfg.pin_sccb_scl = 27;
    cfg.pin_pwdn = 32; cfg.pin_reset = -1;
    cfg.xclk_freq_hz = 20000000;
    cfg.pixel_format = PIXFORMAT_JPEG;
    cfg.frame_size = FRAMESIZE_VGA;
    cfg.jpeg_quality = 14;
    cfg.fb_count = 2;
    cfg.fb_location = CAMERA_FB_IN_PSRAM;
    cfg.grab_mode = CAMERA_GRAB_LATEST;
    cameraReady = psramFound() && esp_camera_init(&cfg) == ESP_OK;

    Preferences config;
    config.begin("sbs-cam", false);
    String key = config.getString("wifi", "");
    if (key.isEmpty()) {
        char generated[17];
        snprintf(generated, sizeof(generated), "%08lx%08lx",
                 (unsigned long)esp_random(), (unsigned long)esp_random());
        key = generated;
        config.putString("wifi", key);
    }
    config.end();
    char ssid[24];
    snprintf(ssid, sizeof(ssid), "SBS-CAM-%06lX", (unsigned long)(ESP.getEfuseMac() & 0xffffff));
    WiFi.mode(WIFI_AP);
    WiFi.softAP(ssid, key.c_str(), 6, false, 1);
    Serial.printf("SSID %s, WPA2 key %s, camera=%d\n", ssid, key.c_str(), cameraReady);
    web.on("/frame.jpg", HTTP_GET, frame);
    web.begin();
}

void loop() {
    web.handleClient();
    delay(2);
}
