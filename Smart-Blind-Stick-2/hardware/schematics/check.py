"""Static checks on circuit endpoints, firmware pin assignments, and nominal design bounds."""
import csv
import re
import xml.etree.ElementTree as ET
from pathlib import Path

here = Path(__file__).resolve().parent

def load(filename):
    with (here / filename).open() as source:
        return list(csv.reader(source, delimiter="\t"))[1:]

rows = load("nets.tsv")
assert len({name for name, _ in rows}) == len(rows)
part_names = {ref for ref, _ in load("parts.tsv")}
actual = {}
for name, endpoints in rows:
    terminals = endpoints.split()
    assert len(terminals) >= 2, name
    for terminal in terminals:
        assert terminal not in actual, terminal
        assert terminal.split(".")[0] in part_names, terminal
        actual[terminal] = name
required = {
    "U3.14": "SW5", "U3.7": "GND", "U3.1": "GND", "U3.3": "TRIG5",
    "U2.TRIG": "TRIG5", "U2.ECHO": "ECHO5", "R1.1": "ECHO5", "R1.2": "ECHO3",
    "R2.1": "ECHO3", "R2.2": "GND", "Q1.G": "GATE", "Q1.S": "GND",
    "Q1.D": "MOTOR_LOW", "D1.K": "SW5", "D1.A": "MOTOR_LOW",
    "M1.+": "SW5", "M1.-": "MOTOR_LOW", "J2.1": "SW5", "J2.2": "GND",
}
for terminal, expected in required.items():
    assert actual[terminal] == expected, terminal
firmware = (here.parents[1] / "firmware/stick/stick.ino").read_text()
for variable, net in {
    "TRIG": "TRIG3", "ECHO": "ECHO3", "MOTOR": "MOTOR_GATE", "EXCITE": "WATER_EXCITE",
    "WATER": "WATER_ADC", "BUTTON": "SOS",
}.items():
    pin = re.search(fr"\b{variable} = (\d+)", firmware).group(1)
    assert actual[f"U1.IO{pin}"] == net, (variable, pin)
assert 5.25 * 15.15 / (9.9 + 15.15) < 3.2
assert 4.75 * 14.85 / (10.1 + 14.85) > 2.8
for svg in ("schematic.svg", "connection-sheet.svg"):
    ET.parse(here / svg)
print(f"{len(actual)} unique terminals, six firmware pin assignments, divider bounds and two valid SVG XML files")
