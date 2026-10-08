#!/usr/bin/env python3
"""Measure each concept's monochrome mark: ink bbox, bbox centre, ink centroid and the farthest
ink pixel from the canvas centre (must stay <= 33 units = the 66dp safe-zone circle)."""
import glob
import json
import os
import subprocess
import sys

import numpy as np
from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SCALE = 10  # px per canvas unit
tmp = sys.argv[1] if len(sys.argv) > 1 else "/tmp"
extents = {}
for svg in sorted(glob.glob(os.path.join(ROOT, "concepts", "*_mono.svg"))):
    cid = os.path.basename(svg)[:-9]
    png = os.path.join(tmp, f"{cid}_measure.png")
    html = os.path.join(tmp, f"{cid}_measure.html")
    body = open(svg).read().replace('width="108" height="108"', f'width="{108 * SCALE}" height="{108 * SCALE}"', 1)
    open(html, "w").write(f"<html><body style='margin:0;background:#fff'>{body}</body></html>")
    subprocess.run(["google-chrome", "--headless=new", "--disable-gpu", "--hide-scrollbars",
                    "--force-device-scale-factor=1", f"--screenshot={png}",
                    f"--window-size={108 * SCALE},{108 * SCALE}", "file://" + html],
                   check=True, capture_output=True)
    a = 1.0 - np.asarray(Image.open(png).convert("L")).astype(float) / 255.0
    ys, xs = np.nonzero(a > 0.5)
    x0, x1, y0, y1 = xs.min() / SCALE, (xs.max() + 1) / SCALE, ys.min() / SCALE, (ys.max() + 1) / SCALE
    cx, cy = (a.sum(0) * np.arange(a.shape[1])).sum() / a.sum() / SCALE, (a.sum(1) * np.arange(a.shape[0])).sum() / a.sum() / SCALE
    r = np.sqrt(((xs + .5) / SCALE - 54) ** 2 + ((ys + .5) / SCALE - 54) ** 2).max()
    extents[cid] = [round(x0, 2), round(y0, 2), round(x1, 2), round(y1, 2)]
    print(f"{cid:9s} bbox x {x0:5.1f}..{x1:5.1f} y {y0:5.1f}..{y1:5.1f}  size {x1 - x0:4.1f}x{y1 - y0:4.1f}  "
          f"bbox-centre ({(x0 + x1) / 2:5.1f},{(y0 + y1) / 2:5.1f})  centroid ({cx:5.1f},{cy:5.1f})  "
          f"max-r {r:4.1f} {'OK' if r <= 33 else 'OUTSIDE SAFE CIRCLE'}  ink {a.sum() / SCALE ** 2:6.0f}u2")

with open(os.path.join(ROOT, "tools", "extents.json"), "w") as f:
    json.dump(extents, f, indent=1)
