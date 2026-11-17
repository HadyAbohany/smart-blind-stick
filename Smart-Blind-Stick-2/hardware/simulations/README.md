# Host logic replay

Run from the project root.

```sh
c++ -std=c++17 -Wall -Wextra -Werror hardware/simulations/logic_test.cpp -o /tmp/sbs-logic
/tmp/sbs-logic
```

This executes the same alert policy compiled into the firmware. It checks threshold hysteresis, no-return release, water debounce, and motor priority. Arduino GPIO, voltage levels, ADC thresholds, timing on ESP32, radio, camera, and wet probes require separate verification. A Wokwi board simulation would need a separate peripheral model for the analog probe and cannot establish physical or phone integration behavior.

Run the BLE packet and synthetic fall checks with JDK 17+.

```sh
javac --release 17 -d /tmp/sbs-java mobile-app/app/src/main/java/eg/sbs/{Packet,FallHeuristic}.java hardware/simulations/ProtocolTest.java
java -ea -cp /tmp/sbs-java eg.sbs.ProtocolTest
python3 hardware/schematics/check.py
```

The Java fixture exercises a constructed packet, malformed and expired packet handling, and one synthetic multi-signal fall sequence. It does not replay real falls or emulate Android permissions.
