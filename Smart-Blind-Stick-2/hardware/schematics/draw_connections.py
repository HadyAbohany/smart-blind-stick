"""Generate the editable SVG schematic and pin matrix from the net and parts tables."""
import csv
from html import escape
from pathlib import Path

root = Path(__file__).resolve().parent

def load(name):
    with (root / name).open() as source:
        return list(csv.reader(source, delimiter="\t"))[1:]

nets = dict(load("nets.tsv"))
parts = dict(load("parts.tsv"))
elements = ['<svg xmlns="http://www.w3.org/2000/svg" width="1240" height="1180" viewBox="0 0 1240 1180" role="img" aria-labelledby="title"><title id="title">Smart Blind Stick 2.0 electrical schematic</title><style>text{font:16px monospace;fill:#162b3b}.heading{font:bold 26px sans-serif}.section{font:bold 20px sans-serif}.label{font:bold 15px monospace;fill:#06675f} .wire{stroke:#24736d;stroke-width:2;fill:none}</style><rect width="1240" height="1180" fill="#f5f9fa"/>']

def txt(x, y, value, style=""):
    elements.append(f'<text x="{x}" y="{y}" class="{style}">{escape(value)}</text>')

def panel(x, y, w, h, title, rows):
    elements.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="8" fill="white" stroke="#b4c5ca"/>')
    txt(x+16, y+32, title, "section")
    for i, (net, detail) in enumerate(rows):
        row = y + 67 + 28*i
        txt(x+16, row, net, "label")
        elements.append(f'<path class="wire" d="M {x+162} {row-5} h 25"/>')
        txt(x+200, row, detail)

txt(32, 44, "Smart Blind Stick 2.0 · electrical schematic", "heading")
txt(32, 72, "Rev A · Equal net names join electrically · NC terminals remain open · GPIO uses IO labels")
panel(32, 95, 1176, 202, "Power distribution · USB power bank, 5 V / 2 A minimum", [
    ("5V → SW5", "USB VBUS → F1 1.5 A hold → S1 → switched 5 V star"),
    ("SW5", "U1.5V, U2.VCC, U3.14, J2.1 (camera 5 V), M1+, D1 cathode, C1+, C3+"),
    ("SW5 → GND", "C1 470 µF at star, C2 100 nF at U3, C3 220 µF at camera, C4 100 nF at U2"),
    ("GND", "Common ground. Return camera and motor directly to star. J2.2 → camera GND."),
    ("USB", "Disconnect J1 before programming U1 by USB. Camera: unplug J2 before using MB.")])
panel(32, 315, 1176, 231, "Ultrasonic · HC-SR04 and SN74AHCT125N", [
    ("TRIG3", "U1.IO26 → U3.2 (1A). R7 100k to GND."),
    ("TRIG5", "U3.3 (1Y) → U2.TRIG. U3.1 (1OE) to GND."),
    ("BUFFER_DISABLE", "U3.4, .10, .13 via R8 10k to SW5. U3.5, .9, .12, .7 to GND."),
    ("NO CONNECTION", "U3.6, .8, .11 left open."),
    ("ECHO5 → ECHO3", "U2.ECHO → R1 10k → junction → U1.IO27. R2 15k from junction to GND."),
    ("SW5 / GND", "U2 VCC / GND. Probe echo junction before connecting U1.IO27.")])
panel(32, 564, 1176, 171, "Haptics · 5 V motor and AO3400A low-side switch", [
    ("GATE", "U1.IO25 → R3 100Ω → Q1 pin 1. R4 100k from gate to GND."),
    ("MOTOR_LOW", "Q1 pin 3 → M1− and D1 anode. Q1 pin 2 → GND."),
    ("SW5", "M1+ and D1 stripe/cathode. D1 is in parallel with M1."),
    ("M1", "Use isolated 5 V ERM rated ≤100 mA running / ≤250 mA stall.")])
panel(32, 753, 1176, 202, "Water and manual SOS", [
    ("PROBE_A", "U1.IO32 → R5 10k → JP1.1. Pulse high only while sampling."),
    ("WATER_ADC", "JP1.2 → U1.IO34. R6 100k and C5 100 nF each to GND."),
    ("JP1", "Insulated mount. Water bridges two 316 stainless contacts."),
    ("SOS", "U1.IO33 → SW2 normally open → GND. R9 10k from IO33 to U1.3V3."),
    ("3V3", "From U1 regulator only. Never bridge to SW5.")])
txt(32, 1000, "U1 ESP32-DevKitC V4 WROOM-32D/32E  ·  Camera AI-Thinker ESP32-CAM + OV2640")
txt(32, 1032, "Exact terminal inventory in nets.tsv and parts.tsv. Fuse selection and load headroom need bench checks.")
txt(32, 1064, "Ground, 5 V and connector pin order must be verified on the physical boards before energizing.")
elements.append("</svg>")
(root / "connection-sheet.svg").write_text("\n".join(elements) + "\n")
