#include <Arduino.h>
#include <BLEDevice.h>
#include <BLESecurity.h>
#include <Preferences.h>
#include <esp_system.h>
#include "Logic.h"

constexpr int TRIG = 26, ECHO = 27, MOTOR = 25, EXCITE = 32, WATER = 34, BUTTON = 33;
constexpr char SERVICE[] = "6f7d0001-48c0-4d8c-bf23-dcb46985b611";
constexpr char DATA[] = "6f7d0002-48c0-4d8c-bf23-dcb46985b611";
Alerts alerts;
BLECharacteristic* telemetry;
uint32_t bootId, eventId, eventAt, lastRange, lastWater, lastPacket;
uint32_t pressedAt;
bool pressed, fired;
uint16_t cm = 0xffff, raw, seq;
bool probeOn;

class ServerEvents : public BLEServerCallbacks {
    void onDisconnect(BLEServer* server) override { server->startAdvertising(); }
};

void put16(uint8_t* out, uint16_t value) {
    out[0] = value;
    out[1] = value >> 8;
}
void put32(uint8_t* out, uint32_t value) {
    put16(out, value);
    put16(out + 2, value >> 16);
}
void publish(uint32_t now) {
    bool recent = eventId && now - eventAt < 60000;
    uint8_t packet[18] = {1, uint8_t((alerts.near ? 1 : 0) | (alerts.wet ? 2 : 0) |
                                     (cm == 0xffff ? 4 : 0) | (recent ? 8 : 0))};
    put16(packet + 2, seq++);
    put16(packet + 4, cm);
    put16(packet + 6, raw);
    put32(packet + 8, bootId);
    put32(packet + 12, eventId);
    put16(packet + 16, recent ? uint16_t(now - eventAt) : 0xffff);
    telemetry->setValue(packet, sizeof(packet));
}

void setup() {
    pinMode(MOTOR, OUTPUT);
    digitalWrite(MOTOR, LOW);
    pinMode(TRIG, OUTPUT);
    digitalWrite(TRIG, LOW);
    pinMode(ECHO, INPUT);
    pinMode(EXCITE, OUTPUT);
    digitalWrite(EXCITE, LOW);
    pinMode(WATER, INPUT);
    pinMode(BUTTON, INPUT_PULLUP);
    analogReadResolution(12);
    analogSetPinAttenuation(WATER, ADC_11db);
    Serial.begin(115200);
    bootId = esp_random();
    Preferences config;
    config.begin("sbs", false);
    uint32_t pin = config.getUInt("blePin", 0);
    if (!pin) {
        pin = 100000 + esp_random() % 900000;
        config.putUInt("blePin", pin);
    }
    config.end();
    Serial.printf("Bench pairing PIN: %06lu\n", (unsigned long)pin);
    BLEDevice::init("Smart Stick 2");
    BLEDevice::setEncryptionLevel(ESP_BLE_SEC_ENCRYPT_MITM);
    auto* security = new BLESecurity();
    security->setStaticPIN(pin);
    // setStaticPIN alone configures Secure Connections without bonding in core 3.2.1.
    security->setAuthenticationMode(ESP_LE_AUTH_REQ_SC_MITM_BOND);
    security->setRespEncryptionKey(ESP_BLE_ENC_KEY_MASK | ESP_BLE_ID_KEY_MASK);
    auto* server = BLEDevice::createServer();
    server->setCallbacks(new ServerEvents());
    auto* service = server->createService(SERVICE);
    telemetry = service->createCharacteristic(DATA, BLECharacteristic::PROPERTY_READ);
    telemetry->setAccessPermissions(ESP_GATT_PERM_READ_ENC_MITM);
    publish(millis());
    service->start();
    auto* advertising = BLEDevice::getAdvertising();
    advertising->addServiceUUID(SERVICE);
    advertising->setScanResponse(true);
    advertising->start();
}

void loop() {
    uint32_t now = millis();
    if (now - lastRange >= 65) {
        lastRange = now;
        digitalWrite(TRIG, HIGH);
        delayMicroseconds(12);
        digitalWrite(TRIG, LOW);
        unsigned long pulse = pulseIn(ECHO, HIGH, 24500);
        cm = pulse ? uint16_t((pulse * 343 + 10000) / 20000) : 0xffff;
        alerts.range(cm);
    }

    now = millis();
    if (!probeOn && now - lastWater >= 100) {
        lastWater = now;
        digitalWrite(EXCITE, HIGH);
        probeOn = true;
    } else if (probeOn && now - lastWater >= 12) {
        raw = analogRead(WATER);
        digitalWrite(EXCITE, LOW);
        alerts.water(raw);
        probeOn = false;
    }

    const bool down = digitalRead(BUTTON) == LOW;
    if (down && !pressed) pressedAt = now;
    if (!down) fired = false;
    if (down && !fired && now - pressedAt >= 1500) {
        fired = true;
        ++eventId;
        eventAt = now;
        publish(now);
    }
    pressed = down;

    digitalWrite(MOTOR, alerts.motor(now, eventId && now - eventAt < 850));
    if (now - lastPacket >= 150) {
        lastPacket = now;
        publish(now);
    }
    delay(1);
}
