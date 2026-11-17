"""Render the circuit as standard schematic symbols joined by named nets."""
import csv
from html import escape
from pathlib import Path

root = Path(__file__).resolve().parent
with (root / "nets.tsv").open() as source:
    rows = list(csv.reader(source, delimiter="\t"))[1:]
net_for = {terminal: net for net, terminals in rows for terminal in terminals.split()}
out = ['''<svg xmlns="http://www.w3.org/2000/svg" width="1400" height="1140" viewBox="0 0 1400 1140" role="img" aria-labelledby="title">
<title id="title">Smart Blind Stick 2.0 Rev A circuit schematic</title>
<style>
text{font:15px monospace;fill:#152534}
.title{font:bold 26px sans-serif}.section{font:bold 19px sans-serif}
.net{font:bold 14px monospace;fill:#005f54}.ref{font:bold 15px monospace}
.wire{stroke:#165952;stroke-width:2;fill:none;stroke-linecap:round;stroke-linejoin:round}
.part{stroke:#142a3a;stroke-width:2;fill:white;stroke-linecap:round;stroke-linejoin:round}
</style>
<rect width="1400" height="1140" fill="#f2f7f7"/>
''']

def text(x, y, message, style="", align="start"):
    out.append(f'<text x="{x}" y="{y}" class="{style}" text-anchor="{align}">{escape(message)}</text>')

def path(d, style="wire"):
    out.append(f'<path class="{style}" d="{d}"/>')

def panel(x, y, width, height, title):
    out.append(f'<rect x="{x}" y="{y}" width="{width}" height="{height}" fill="white" stroke="#aabdbe" rx="8"/>')
    text(x+16, y+30, title, "section")

def endpoint(x, y, terminal, left):
    net = net_for[terminal]
    if net == "NC":
        path(f"M{x-4} {y-4} l8 8 M{x-4} {y+4} l8 -8")
        return
    direction = -1 if left else 1
    path(f"M{x} {y} h{direction*12} l{direction*4} -4 m{-direction*4} 4 l{direction*4} 4")
    text(x+direction*20, y+5, net, "net", "end" if left else "start")

def block(x, y, width, ref, name, pins, right=False):
    height = len(pins)*29 + 50
    out.append(f'<rect x="{x}" y="{y}" width="{width}" height="{height}" class="part"/>')
    text(x+9, y+20, ref, "ref")
    text(x+9, y+39, name)
    for index, (pin, label) in enumerate(pins):
        row = y + 59 + index*29
        src = x+width if right else x
        offset = 24 if right else -24
        path(f"M{src} {row} h{offset}")
        endpoint(src+offset, row, f"{ref}.{pin}", not right)
        text(x+width-8 if right else x+8, row+5, label, align="end" if right else "start")

def branch(x, y, ref, value, terminals, kind):
    path(f"M{x-54} {y} H{x-23} M{x+23} {y} H{x+54}")
    endpoint(x-54, y, f"{ref}.{terminals[0]}", True)
    endpoint(x+54, y, f"{ref}.{terminals[1]}", False)
    text(x, y-14, ref + " " + value, "ref", "middle")
    symbols = {
        "r": f"M{x-23} {y} l5 -7 6 14 6 -14 6 14 6 -14 6 14 5 -7 h6",
        "cap": f"M{x-23} {y} h18 m0 -9 v18 M{x+5} {y-9} v18 m0 -9 h18",
        "diode": f"M{x-23} {y} h13 l18 -9 v18 l-18 -9 m0 -10 v20 M{x+8} {y} h15",
        "switch": f"M{x-23} {y} h8 l27 -12 M{x+15} {y} h8",
        "motor": f"M{x-23} {y} h11 M{x+12} {y} h11",
        "fuse": f"M{x-23} {y} h46",
        "probe": f"M{x-23} {y} h16 m0 -6 v12 M{x+7} {y-6} v12 m0 -6 h16",
    }
    if kind == "fuse":
        out.append(f'<rect x="{x-15}" y="{y-6}" width="30" height="12" class="part"/>')
    if kind == "motor":
        out.append(f'<circle cx="{x}" cy="{y}" r="12" class="part"/>')
        text(x, y+5, "M", align="middle")
    path(symbols[kind], "part")

text(32, 42, "Smart Blind Stick 2.0 · Rev A circuit", "title")
text(32, 69, "Identical net labels connect across panels. NC pins left open. For power and assembly see the connection sheet.")
panel(25, 92, 438, 563, "U1 local controller and U2 ranger")
block(154, 138, 205, "U1", "ESP32-DevKitC V4", [(pin, pin) for pin in
    ["5V", "GND", "3V3", "IO26", "IO27", "IO25", "IO32", "IO34", "IO33"]])
block(160, 457, 164, "U2", "HC-SR04", [(pin, pin) for pin in ["VCC", "TRIG", "ECHO", "GND"]])
panel(480, 92, 430, 563, "Trigger shifter and echo divider")
block(652, 138, 178, "U3", "SN74AHCT125N", [(str(i), n) for i,n in enumerate(
    ["1OE", "1A", "1Y", "2OE", "2A", "2Y", "GND", "3Y", "3A", "3OE", "4Y", "4A", "4OE", "VCC"],1)])
# The two echo resistors and buffer bias continue in the panel below.
panel(928, 92, 447, 563, "Input and power distribution")
for i, (ref, value, terms, kind) in enumerate([
    ("F1","1.5A hold",("1","2"),"fuse"), ("S1","2A DC",("1","2"),"switch"),
    ("C1","470µF",("+","-"),"cap"), ("C2","100nF U3",("1","2"),"cap"),
    ("C3","220µF CAM",("+","-"),"cap"), ("C4","100nF U2",("1","2"),"cap")]):
    branch(1144, 160+i*64, ref, value, terms, kind)
block(1028, 521, 110, "J1", "USB 5V", [("VBUS","VBUS"),("GND","GND")])
block(1166, 521, 122, "J2", "CAM 5V", [("1","1 / 5V"),("2","2 / GND")], right=True)

panel(25, 673, 438, 403, "Motor and flyback")
branch(226, 735, "R3", "100Ω", ("1","2"), "r")
branch(226, 794, "R4", "100k", ("1","2"), "r")
branch(226, 853, "M1", "5V ERM", ("+","-"), "motor")
branch(226, 912, "D1", "1N5819", ("K","A"), "diode")
x, y = 232, 1014
path(f"M{x-26} {y} h28 M{x+14} {y-25} v50 M{x+14} {y-20} h23 v-10 h25 M{x+14} {y+20} h23 v10 h25 M{x+14} {y} h23 v20 M{x+17} {y} l9 -5 v10 z", "part")
endpoint(x-26, y, "Q1.G", True)
endpoint(x+62, y-30, "Q1.D", False)
endpoint(x+62, y+30, "Q1.S", False)
text(x+8, y-47, "Q1 AO3400A", "ref", "middle")

panel(480, 673, 430, 403, "Echo, probe and button bias")
for i,(ref,value) in enumerate([("R1","10k"), ("R2","15k"), ("R7","100k"), ("R8","10k"), ("R9","10k")]):
    branch(694, 735+i*66, ref, value, ("1","2"), "r")

panel(928, 673, 447, 403, "Water contact and SOS")
for i,(ref,value,kind) in enumerate([
    ("R5","10k","r"), ("JP1","316 electrodes","probe"), ("R6","100k","r"),
    ("C5","100nF","cap"), ("SW2","momentary N.O.","switch")]):
    branch(1144, 735+i*66, ref, value, ("1","2"), kind)
text(32, 1112, "All pin names and polarities are listed in nets.tsv and parts.tsv. USB programming requires isolated external power.")
out.append("</svg>")
(root / "schematic.svg").write_text("\n".join(out) + "\n")
