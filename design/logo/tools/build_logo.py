#!/usr/bin/env python3
"""CarLink logo generator.

Every concept is defined once, as a list of simple stroked/filled paths on the 108x108
adaptive-icon canvas (safe zone 21..87). From that single definition this script writes:

  design/logo/concepts/<id>_icon.svg        full icon (background + mark), dark
  design/logo/concepts/<id>_mark.svg        mark only (transparent), dark-UI colours
  design/logo/concepts/<id>_mono.svg        single-colour mark (themed icon / status bar)
  design/logo/concepts/<id>_lockup_dark.svg horizontal lockup with the wordmark, dark
  design/logo/concepts/<id>_lockup_day.svg  horizontal lockup, day variant
  design/logo/previews/<id>_board.html      presentation board (rendered to PNG by Chrome)
  design/logo/android/<id>/*.xml            Android vector drawables (foreground, monochrome,
                                            legacy, status-bar)

Run:  python3 design/logo/tools/build_logo.py   (then design/logo/tools/render.sh)
"""
import math
import os

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
# Preview-only wordmark font, referenced relative to design/logo/{concepts,previews}/.
FONT_URL = "../../../app/src/main/res/font/carlink_inter_semibold.ttf"

# Graphite and Ion tokens (design/RESEARCH_PREMIUM_UX.md section 6.1)
DARK = dict(bg="#0C1016", primary="#EEF2F6", accent="#5BD6F0", text="#EEF2F6", sub="#A9B3C0")
DAY = dict(bg="#E8EBEF", primary="#0D1117", accent="#006A7E", text="#0D1117", sub="#46505C")
ICON_BG = "#0C1016"


def P(x, y):
    return f"{x:.2f},{y:.2f}"


def pol(cx, cy, r, deg):
    """Point on a circle; 0 deg = 3 o'clock, angles grow clockwise (screen coordinates)."""
    a = math.radians(deg)
    return cx + r * math.cos(a), cy + r * math.sin(a)


def arc(cx, cy, r, a0, a1):
    """Clockwise arc from angle a0 to a1 (degrees, a1 > a0) as an SVG/Android path."""
    x0, y0 = pol(cx, cy, r, a0)
    x1, y1 = pol(cx, cy, r, a1)
    large = 1 if (a1 - a0) % 360 > 180 else 0
    return f"M{P(x0, y0)} A{r:.2f},{r:.2f} 0 {large} 1 {P(x1, y1)}"


def circle(cx, cy, r):
    return (f"M{P(cx - r, cy)} A{r:.2f},{r:.2f} 0 1 1 {P(cx + r, cy)} "
            f"A{r:.2f},{r:.2f} 0 1 1 {P(cx - r, cy)} Z")


def rrect(x, y, w, h, r):
    return (f"M{P(x + r, y)} H{x + w - r:.2f} A{r},{r} 0 0 1 {P(x + w, y + r)} V{y + h - r:.2f} "
            f"A{r},{r} 0 0 1 {P(x + w - r, y + h)} H{x + r:.2f} A{r},{r} 0 0 1 {P(x, y + h - r)} "
            f"V{y + r:.2f} A{r},{r} 0 0 1 {P(x + r, y)} Z")


# Element: (path, mode, stroke_width, role)  mode in {"stroke", "fill"}; role in {"primary", "accent"}

def concept_orbit():
    # A heavy dial ring (the car: steering wheel / instrument dial) left open at 1-2 o'clock,
    # where a solid node (the phone) docks. Reads as a "C" for CarLink.
    cx, cy, r, w = 54, 54, 21.5, 10
    gap_c, gap = -45, 86            # gap centre angle and width (deg)
    a0 = gap_c + gap / 2
    a1 = gap_c - gap / 2 + 360
    nx, ny = pol(cx, cy, r, gap_c)
    return fit([
        (arc(cx, cy, r, a0, a1), "stroke", w, "primary"),
        (circle(nx, ny, 6.6), "fill", 0, "accent"),
    ], 1.0, 0.5, -0.5)


def concept_ligature():
    # One continuous stroke: a "C" whose lower terminal runs out flat to the right as an "L".
    # The C is the car, the flat Ion run is the road/link handing off to the phone.
    w = 9.5
    cx, cy, r = 48, 52, 18.5
    sx, sy = pol(cx, cy, r, -48)      # upper terminal of the C
    by = cy + r                       # bottom of the C = baseline of the L foot
    c_part = f"M{P(sx, sy)} A{r},{r} 0 1 0 {P(cx, by)}"   # counter-clockwise round the left
    return fit([
        (c_part, "stroke", w, "primary"),
        (f"M{P(cx, by)} H74", "stroke", w, "accent"),
    ], 1.0, 1.0, 1.0)


def concept_merge():
    # Two lanes (iPhone, Android) flow into one (the car) and end in a live node.
    # Horizontal on purpose: no upward arrow, so it never reads like a navigation/AA mark.
    w = 10
    x0, ytop, ybot, ym = 27, 36, 72, 54
    xm = 55
    top = f"M{P(x0, ytop)} H31 C44,{ytop} 44,{ym} {P(xm, ym)}"
    bot = f"M{P(x0, ybot)} H31 C44,{ybot} 44,{ym} {P(xm, ym)}"
    return fit([
        (top, "stroke", w, "primary"),
        (bot, "stroke", w, "primary"),
        (f"M{P(xm, ym)} H62", "stroke", w, "accent"),
        (circle(77, ym, 6.5), "fill", 0, "accent"),
    ], 0.9, 1.5, 0)


def concept_cast():
    # A wide display (the car screen) with a phone docked in front of its corner:
    # the phone's interface "becomes" the car's. The display outline stops short of the phone.
    w = 6
    x, y, W, H, r = 28, 33, 45, 33, 7
    px, py, pw, ph, pr = 60, 48, 20, 29, 5.5   # phone (filled), over the display's lower-right corner
    g = 4.5                                     # clearance between phone and display stroke
    yr = py - g - w / 2                         # where the right edge stops
    xb = px - g - w / 2                         # where the bottom edge stops
    d = (f"M{P(x + W, yr)} V{y + r:.2f} A{r},{r} 0 0 0 {P(x + W - r, y)} H{x + r:.2f} "
         f"A{r},{r} 0 0 0 {P(x, y + r)} V{y + H - r:.2f} A{r},{r} 0 0 0 {P(x + r, y + H)} H{xb:.2f}")
    return fit([
        (d, "stroke", w, "primary"),
        (rrect(px, py, pw, ph, pr), "fill", 0, "accent"),
    ], 0.96, -0.5, 0.5)


CONCEPTS = [
    ("orbit", "Orbit", concept_orbit,
     "A heavy dial ring (the car: steering wheel, instrument dial) left open at the 1:30 position, "
     "where a solid Ion node (the phone) docks. The ring also reads as a C for CarLink."),
    ("ligature", "CL Ligature", concept_ligature,
     "One continuous stroke: a C whose lower terminal runs out flat as an L. The curve is the car, "
     "the Ion run is the road and the link to the phone. A typemelt monogram."),
    ("merge", "Merge", concept_merge,
     "Two lanes (iPhone and Android) flow into one road that ends in a live node: one app, any phone. "
     "Horizontal, so it never reads as an upward navigation arrow."),
    ("cast", "Cast", concept_cast,
     "A wide car display with a phone docked over its corner: the phone's interface becomes the car's. "
     "The most literal option, instantly understood by beginners."),
]


def fit(els, s=1.0, dx=0.0, dy=0.0):
    """Scale a concept about the canvas centre (54,54) and shift it; absolute commands only."""
    import re
    X = lambda x: 54 + (x - 54) * s + dx
    Y = lambda y: 54 + (y - 54) * s + dy
    out = []
    for d, mode, w, role in els:
        toks = re.findall(r"[MLHVCAZ]|-?\d+\.?\d*", d)
        res, i, cmd = [], 0, None
        while i < len(toks):
            t = toks[i]
            if t.isalpha():
                cmd = t; res.append(t); i += 1; continue
            if cmd in ("M", "L"):
                res.append(P(X(float(toks[i])), Y(float(toks[i + 1])))); i += 2
            elif cmd == "H":
                res.append(f"{X(float(t)):.2f}"); i += 1
            elif cmd == "V":
                res.append(f"{Y(float(t)):.2f}"); i += 1
            elif cmd == "C":
                res.append(" ".join(P(X(float(toks[i + k])), Y(float(toks[i + k + 1]))) for k in (0, 2, 4))); i += 6
            elif cmd == "A":
                rx, ry, rot, la, sw, x, y = toks[i:i + 7]
                res.append(f"{float(rx) * s:.2f},{float(ry) * s:.2f} {rot} {la} {sw} {P(X(float(x)), Y(float(y)))}"); i += 7
        nd = re.sub(r"([MLHVCAZ]) ", r"\1", " ".join(res))
        out.append((nd, mode, round(w * s, 2), role))
    return out


# ---------- SVG output ----------

def svg_elements(els, colors, mono=None):
    out = []
    for d, mode, w, role in els:
        c = mono or colors[role]
        if mode == "stroke":
            out.append(f'<path d="{d}" fill="none" stroke="{c}" stroke-width="{w}" '
                       f'stroke-linecap="round" stroke-linejoin="round"/>')
        else:
            out.append(f'<path d="{d}" fill="{c}"/>')
    return "\n  ".join(out)


def svg_doc(body, vb="0 0 108 108", w=108, h=108, extra=""):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{vb}" width="{w}" height="{h}">\n'
            f'{extra}  {body}\n</svg>\n')


FONT_FACE = ('<defs><style>@font-face{font-family:"CarLinkInter";'
             f'src:url("{FONT_URL}") format("truetype");font-weight:600;}}</style></defs>\n')


def lockup(els, colors, bg=False):
    # icon mark (cropped region 21..87) + wordmark. Height 66 units.
    rect = f'<rect width="300" height="86" fill="{colors["bg"]}"/>\n  ' if bg else ""
    body = (rect + f'<g transform="translate(-11,-11)">\n  {svg_elements(els, colors)}\n  </g>\n  '
            f'<text x="88" y="56" font-family="CarLinkInter, Inter, sans-serif" font-weight="600" '
            f'font-size="40" letter-spacing="-0.6" fill="{colors["text"]}">CarLink</text>')
    return svg_doc(body, vb="0 0 300 86", w=600, h=172, extra=FONT_FACE)


# ---------- Android vector output ----------

def vec_paths(els, colors, mono=None, indent="    "):
    out = []
    for d, mode, w, role in els:
        c = mono or colors[role]
        if mode == "stroke":
            out.append(f'{indent}<path android:pathData="{d}" android:strokeColor="{c}" '
                       f'android:strokeWidth="{w}" android:strokeLineCap="round" '
                       f'android:strokeLineJoin="round" android:fillColor="#00000000"/>')
        else:
            out.append(f'{indent}<path android:pathData="{d}" android:fillColor="{c}"/>')
    return "\n".join(out)


VEC_HEAD = ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- {comment} Generated by design/logo/tools/build_logo.py. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="{size}dp" android:height="{size}dp" '
            'android:viewportWidth="108" android:viewportHeight="108">\n')


def vec(els, comment, size=108, colors=DARK, mono=None, group=None):
    s = VEC_HEAD.format(comment=comment, size=size)
    if group:
        s += f'    <group {group}>\n' + vec_paths(els, colors, mono, "        ") + "\n    </group>\n"
    else:
        s += vec_paths(els, colors, mono) + "\n"
    return s + "</vector>\n"


def bbox(els):
    """Rough bbox from path coordinates (+half stroke). Good enough for status-bar fitting."""
    import re
    xs, ys = [], []
    for d, mode, w, role in els:
        toks = re.findall(r"[MLHVCAZ]|-?\d+\.?\d*", d)
        i, cmd, cur = 0, None, [0, 0]
        pad = w / 2 if mode == "stroke" else 0
        while i < len(toks):
            t = toks[i]
            if t.isalpha():
                cmd = t; i += 1
                if cmd == "Z":
                    continue
                continue
            if cmd in ("M", "L"):
                cur = [float(toks[i]), float(toks[i + 1])]; i += 2
            elif cmd == "H":
                cur[0] = float(toks[i]); i += 1
            elif cmd == "V":
                cur[1] = float(toks[i]); i += 1
            elif cmd == "C":
                cur = [float(toks[i + 4]), float(toks[i + 5])]; i += 6
            elif cmd == "A":
                cur = [float(toks[i + 5]), float(toks[i + 6])]; i += 7
            else:
                i += 1; continue
            xs += [cur[0] - pad, cur[0] + pad]; ys += [cur[1] - pad, cur[1] + pad]
    return min(xs), min(ys), max(xs), max(ys)


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)


def board_html(cid, name, els, rationale):
    def icon(colors=DARK, bgc=ICON_BG, mono=None):
        return svg_doc(f'<rect width="108" height="108" fill="{bgc}"/>\n  ' + svg_elements(els, colors, mono))

    full = icon()
    mark_dark = svg_doc(svg_elements(els, DARK))
    mono_svg = lambda fg, bgc: svg_doc(f'<rect width="108" height="108" fill="{bgc}"/>\n  ' +
                                        svg_elements(els, DARK, fg))
    # status-bar silhouette, fitted to 24dp with 1dp padding
    x0, y0, x1, y1 = SB[cid]
    status = svg_doc(svg_elements(els, DARK, "#FFFFFF"), vb=f"{x0} {y0} {x1 - x0} {y1 - y0}", w=24, h=24)

    def scaled(svg, px):
        return svg.replace('width="108" height="108"', f'width="{px}" height="{px}"', 1)

    def masked(svg, px, shape):
        # the mask crops the 108 canvas to its central 72 (viewport), as a launcher does
        inner = svg.replace('viewBox="0 0 108 108"', 'viewBox="18 18 72 72"').replace(
            'width="108" height="108"', f'width="{px}" height="{px}"', 1)
        rad = "50%" if shape == "circle" else "30%"
        return f'<div class="m" style="width:{px}px;height:{px}px;border-radius:{rad}">{inner}</div>'

    lock_dark = lockup(els, DARK).replace('width="600" height="172"', 'width="540" height="155"')
    lock_day = lockup(els, DAY).replace('width="600" height="172"', 'width="540" height="155"')
    safe = (svg_doc(f'<rect width="108" height="108" fill="{ICON_BG}"/>\n  '
                    '<rect x="21" y="21" width="66" height="66" fill="none" stroke="#FF7A70" '
                    'stroke-width="0.4" stroke-dasharray="2 2"/>\n  '
                    '<circle cx="54" cy="54" r="33" fill="none" stroke="#FFB547" stroke-width="0.4" '
                    'stroke-dasharray="2 2"/>\n  '
                    '<line x1="54" y1="0" x2="54" y2="108" stroke="#FFFFFF22" stroke-width="0.3"/>\n  '
                    '<line x1="0" y1="54" x2="108" y2="54" stroke="#FFFFFF22" stroke-width="0.3"/>\n  ' +
                    svg_elements(els, DARK)))

    themed = [("#F2DFD1", "#5C3A1F"), ("#D7E8CD", "#25401A"), ("#3A2E4A", "#E6D7FF")]
    themed_html = "".join(masked(mono_svg(fg, bgc), 96, "circle") for bgc, fg in themed)

    return f"""<!doctype html><html><head><meta charset="utf-8"><style>
@font-face{{font-family:"CarLinkInter";src:url("{FONT_URL}") format("truetype");font-weight:600}}
body{{margin:0;background:#07090D;font-family:CarLinkInter,sans-serif;color:#EEF2F6;width:1600px;height:1000px;overflow:hidden}}
.wrap{{display:grid;grid-template-columns:520px 1fr;height:1000px}}
.l{{padding:48px;background:#0C1016;display:flex;flex-direction:column;gap:24px}}
.r{{display:grid;grid-template-rows:1fr 1fr;}}
.dark{{background:radial-gradient(120% 140% at 0% 0%,#2A3F57 0%,#121A24 55%,#07090D 100%);padding:36px 44px;display:flex;flex-direction:column;gap:22px}}
.day{{background:radial-gradient(120% 140% at 0% 0%,#F3EEE6 0%,#E8EBEF 50%,#C9D0D8 100%);color:#0D1117;padding:36px 44px;display:flex;flex-direction:column;gap:22px}}
h1{{font-size:44px;margin:0;letter-spacing:-0.5px}} p{{font-size:19px;line-height:28px;color:#A9B3C0;margin:0;font-family:sans-serif}}
.cap{{font-size:13px;letter-spacing:1.5px;text-transform:uppercase;color:#8A95A3;font-family:sans-serif}}
.day .cap{{color:#5A6470}}
.row{{display:flex;gap:28px;align-items:flex-end;flex-wrap:nowrap}}
.m{{overflow:hidden;display:inline-block;line-height:0}}
.cell{{display:flex;flex-direction:column;gap:8px;align-items:center}}
svg{{display:block}}
.sb{{background:#1B1E24;border-radius:10px;padding:10px 14px;display:flex;gap:14px;align-items:center;font-family:sans-serif;font-size:15px}}
.sbday{{background:#F7F8FA;color:#0D1117}}
</style></head><body><div class="wrap">
<div class="l">
  <div class="cap">CarLink logo concept</div>
  <h1>{name}</h1>
  <p>{rationale}</p>
  <div class="cell" style="align-items:flex-start"><div class="cap">Master, 108 canvas, safe zone 66 (dashed)</div>{scaled(safe, 300)}</div>
  <div class="cap">Mark only</div>
  <div class="row">{scaled(mark_dark, 120)}<div style="background:#E8EBEF;line-height:0">{scaled(svg_doc(svg_elements(els, DAY)), 120)}</div></div>
</div>
<div class="r">
 <div class="dark">
  <div class="cap">Launcher masks, dark wallpaper (192 / 96 / 48 / 24 px)</div>
  <div class="row">
   {masked(full, 192, "squircle")}{masked(full, 192, "circle")}
   <div class="cell">{masked(full, 96, "squircle")}<span class="cap">96</span></div>
   <div class="cell">{masked(full, 48, "circle")}<span class="cap">48</span></div>
   <div class="cell">{masked(full, 24, "circle")}<span class="cap">24</span></div>
   <div class="cell"><div class="sb">{status}<span>12:45</span></div><span class="cap">status bar</span></div>
  </div>
  <div class="cap">Lockup, dark</div>
  <div>{lock_dark}</div>
 </div>
 <div class="day">
  <div class="cap">Day wallpaper and Android 13+ themed (monochrome) icons</div>
  <div class="row">
   {masked(full, 144, "squircle")}
   <div class="cell">{masked(full, 48, "squircle")}<span class="cap">48</span></div>
   <div class="cell">{masked(full, 24, "squircle")}<span class="cap">24</span></div>
   {themed_html}
   <div class="cell"><div class="sb sbday">{status.replace('#FFFFFF', '#0D1117')}<span>12:45</span></div><span class="cap">status bar</span></div>
  </div>
  <div class="cap">Lockup, day</div>
  <div>{lock_day}</div>
 </div>
</div></div></body></html>"""


SB = {}
try:
    import json
    MEASURED = json.load(open(os.path.join(ROOT, "tools", "extents.json")))
except (OSError, ValueError):
    MEASURED = {}


def main():
    global SB
    for cid, name, fn, why in CONCEPTS:
        els = fn()
        x0, y0, x1, y1 = bbox(els)
        # exact ink extents measured from a Chrome rendering by tools/measure.py (run it, then rebuild)
        ex = MEASURED.get(cid)
        if ex:
            x0, y0, x1, y1 = ex
        side = max(x1 - x0, y1 - y0)
        pad = side / 22.0           # 22dp optical inside 24dp
        cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
        half = side / 2 + pad
        SB[cid] = (round(cx - half, 2), round(cy - half, 2), round(cx + half, 2), round(cy + half, 2))

        c = os.path.join(ROOT, "concepts")
        write(f"{c}/{cid}_icon.svg", svg_doc(f'<rect width="108" height="108" fill="{ICON_BG}"/>\n  ' + svg_elements(els, DARK)))
        write(f"{c}/{cid}_mark.svg", svg_doc(svg_elements(els, DARK)))
        write(f"{c}/{cid}_mark_day.svg", svg_doc(svg_elements(els, DAY)))
        write(f"{c}/{cid}_mono.svg", svg_doc(svg_elements(els, DARK, "#000000")))
        write(f"{c}/{cid}_lockup_dark.svg", lockup(els, DARK))
        write(f"{c}/{cid}_lockup_day.svg", lockup(els, DAY))
        write(os.path.join(ROOT, "previews", f"{cid}_board.html"), board_html(cid, name, els, why))

        a = os.path.join(ROOT, "android", cid)
        write(f"{a}/carlink_icon_foreground.xml",
              vec(els, f"CarLink mark '{name}': adaptive-icon foreground, 108dp canvas, mark inside the 66dp safe zone."))
        write(f"{a}/carlink_icon_monochrome.xml",
              vec(els, f"CarLink mark '{name}': themed-icon (monochrome) layer; only alpha is used.", mono="#FFFFFFFF"))
        write(f"{a}/carlink_icon_legacy.xml", legacy(els, name))
        sx0, sy0, sx1, sy1 = SB[cid]
        s = 108.0 / (sx1 - sx0)
        grp = (f'android:scaleX="{s:.4f}" android:scaleY="{s:.4f}" '
               f'android:translateX="{-sx0 * s:.3f}" android:translateY="{-sy0 * s:.3f}"')
        write(f"{a}/ic_stat_carlink.xml",
              vec(els, f"CarLink mark '{name}': status-bar icon, white silhouette fitted to 24dp.", size=24,
                  mono="#FFFFFFFF", group=grp))
        # In-app brand mark, cropped tight to the glyph and split in two tintable layers so Compose
        # can colour it from the active day/night palette (see CarLinkMark in Components.kt).
        half_m = max(x1 - x0, y1 - y0) / 2 * (1 + 1 / 24)
        mx0, my0 = (x0 + x1) / 2 - half_m, (y0 + y1) / 2 - half_m
        sm = 108.0 / (2 * half_m)
        grp_m = (f'android:scaleX="{sm:.4f}" android:scaleY="{sm:.4f}" '
                 f'android:translateX="{-mx0 * sm:.3f}" android:translateY="{-my0 * sm:.3f}"')
        for role in ("primary", "accent"):
            part = [e for e in els if e[3] == role]
            write(f"{a}/carlink_logo_mark_{role}.xml",
                  vec(part, f"CarLink mark '{name}', {role} layer, cropped for in-app use; tinted in Compose.",
                      size=24, mono="#FFFFFFFF", group=grp_m))
        print(cid, "bbox", (x0, y0, x1, y1), "center", ((x0 + x1) / 2, (y0 + y1) / 2), "status", SB[cid])


def legacy(els, name):
    s = VEC_HEAD.format(comment=f"CarLink mark '{name}': Android 7.1 launchers without adaptive icons "
                                 "(rounded square + mark).", size=108)
    s += f'    <path android:pathData="{rrect(8, 8, 92, 92, 22)}" android:fillColor="{ICON_BG}"/>\n'
    s += '    <group android:pivotX="54" android:pivotY="54" android:scaleX="1.22" android:scaleY="1.22">\n'
    s += vec_paths(els, DARK, None, "        ") + "\n    </group>\n</vector>\n"
    return s


def overview():
    cols = []
    for i, (cid, name, fn, why) in enumerate(CONCEPTS, 1):
        els = fn()
        icon = svg_doc(f'<rect width="108" height="108" fill="{ICON_BG}"/>\n  ' + svg_elements(els, DARK))

        def m(px, rad):
            inner = icon.replace('viewBox="0 0 108 108"', 'viewBox="18 18 72 72"').replace(
                'width="108" height="108"', f'width="{px}" height="{px}"', 1)
            return f'<div class="m" style="width:{px}px;height:{px}px;border-radius:{rad}">{inner}</div>'
        lock = lockup(els, DARK).replace('width="600" height="172"', 'width="320" height="92"')
        cols.append(f'<div class="c"><div class="cap">{i}. {name}</div>{m(200, "30%")}'
                    f'<div class="row">{m(96, "50%")}{m(48, "50%")}{m(24, "50%")}</div>{lock}</div>')
    html = f"""<!doctype html><html><head><meta charset="utf-8"><style>
@font-face{{font-family:"CarLinkInter";src:url("{FONT_URL}") format("truetype");font-weight:600}}
body{{margin:0;width:1600px;height:720px;overflow:hidden;font-family:CarLinkInter,sans-serif;color:#EEF2F6;
background:radial-gradient(120% 140% at 0% 0%,#2A3F57 0%,#121A24 55%,#07090D 100%)}}
h1{{margin:48px 56px 0;font-size:40px;letter-spacing:-0.4px}}
.g{{display:grid;grid-template-columns:repeat(4,1fr);padding:36px 40px;gap:24px}}
.c{{display:flex;flex-direction:column;gap:30px;align-items:flex-start;padding:16px}}
.cap{{font-size:15px;letter-spacing:1.6px;text-transform:uppercase;color:#A9B3C0;font-family:sans-serif}}
.row{{display:flex;gap:18px;align-items:center}} .m{{overflow:hidden;line-height:0}} svg{{display:block}}
</style></head><body><h1>CarLink logo concepts</h1><div class="g">{''.join(cols)}</div></body></html>"""
    write(os.path.join(ROOT, "previews", "overview_board.html"), html)


RECOMMENDED = "orbit"   # the concept shipped in app/src/main/res/drawable


def alternates():
    """carlink_logo_alt{n}_foreground/monochrome.xml for the concepts that are not shipped."""
    n = 0
    for cid, name, fn, why in CONCEPTS:
        if cid == RECOMMENDED:
            continue
        n += 1
        els = fn()
        a = os.path.join(ROOT, "android")
        write(f"{a}/carlink_logo_alt{n}_foreground.xml",
              vec(els, f"Alternate {n}, '{name}': adaptive-icon foreground (108dp canvas)."))
        write(f"{a}/carlink_logo_alt{n}_monochrome.xml",
              vec(els, f"Alternate {n}, '{name}': themed-icon (monochrome) layer.", mono="#FFFFFFFF"))
        print(f"alt{n} = {cid}")


if __name__ == "__main__":
    main()
    overview()
    alternates()
