#pragma once
#include <stdint.h>

struct Alerts {
    bool near = false;
    bool wet = false;
    uint8_t clearCount = 0;
    uint8_t wetCount = 0;
    uint8_t dryCount = 0;

    void range(uint16_t cm) {
        if (cm <= 90) {
            near = true;
            clearCount = 0;
        } else if (cm >= 105) {
            if (clearCount < 3) ++clearCount;
            if (clearCount == 3) near = false;
        } else {
            clearCount = 0;
        }
    }

    void water(uint16_t raw) {
        wetCount = raw >= 1200 ? (wetCount < 3 ? wetCount + 1 : 3) : 0;
        dryCount = raw <= 850 ? (dryCount < 5 ? dryCount + 1 : 5) : 0;
        if (wetCount == 3) wet = true;
        if (dryCount == 5) wet = false;
    }

    bool motor(uint32_t now, bool sosAck) const {
        if (near) return now % 220 < 145;
        if (sosAck) return now % 360 < 250;
        const uint32_t phase = now % 850;
        return wet && (phase < 100 || (phase >= 200 && phase < 300));
    }
};
