#include <cassert>
#include "../../firmware/stick/Logic.h"

int main() {
    Alerts alerts;
    assert(!alerts.near && !alerts.wet);
    alerts.range(90);
    assert(alerts.near && alerts.motor(0, false));
    alerts.range(100);
    assert(alerts.near);
    alerts.range(0xffff);
    alerts.range(110);
    assert(alerts.near);
    alerts.range(110);
    assert(!alerts.near);
    alerts.water(1300);
    alerts.water(1300);
    assert(!alerts.wet);
    alerts.water(1300);
    assert(alerts.wet && !alerts.motor(160, false));
    assert(alerts.motor(160, true));
    alerts.range(60);
    assert(!alerts.motor(160, true));
    for (int i = 0; i < 5; ++i) alerts.water(800);
    assert(!alerts.wet);
}
