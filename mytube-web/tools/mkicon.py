#!/usr/bin/env python3
"""WebTube লঞ্চার-আইকন জেনারেটর — ডিজাইন B "W Monogram" (অনুমোদিত)।
লিগ্যাসি mipmap PNG (গোল-কোণা ব্যাকগ্রাউন্ড-সহ) + adaptive foreground PNG
(স্বচ্ছ, কনটেন্ট ৫২% নিরাপদ-অঞ্চলে) — সব density-তে।"""
from PIL import Image, ImageDraw
import os

SS = 4  # 512 -> 2048 supersample (AA-এর জন্য)
BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

DARK = (15, 17, 16, 255)      # #0F1110
WHITE = (242, 242, 242, 255)  # #F2F2F2
GREEN = (52, 211, 153, 255)   # #34D399

# ডিজাইন-B জ্যামিতি (512-ক্যানভাস)
W_PTS = [(86, 170), (142, 362), (198, 214), (254, 362), (310, 170)]
W_W = 46
BADGE = (294, 294, 426, 426)
BADGE_R = 38
TRI = [(342, 330), (342, 390), (392, 360)]


def draw_w_badge(dr, T, S):
    """T: (x,y)->(X,Y) রূপান্তর; S: স্ট্রোক/ব্যাসার্ধ-স্কেল।"""
    pts = [T(x, y) for (x, y) in W_PTS]
    dr.line(pts, fill=WHITE, width=int(W_W * S), joint="curve")
    r = W_W * S / 2.0
    for (X, Y) in pts:  # গোল ক্যাপ
        dr.ellipse([X - r, Y - r, X + r, Y + r], fill=WHITE)
    x1, y1 = T(BADGE[0], BADGE[1])
    x2, y2 = T(BADGE[2], BADGE[3])
    dr.rounded_rectangle([x1, y1, x2, y2], radius=BADGE_R * S, fill=GREEN)
    dr.polygon([T(x, y) for (x, y) in TRI], fill=DARK)


def legacy(n):
    """লিগ্যাসি আইকন: গোল-কোণা গাঢ় টাইল + W + ব্যাজ।"""
    img = Image.new("RGBA", (512 * SS, 512 * SS), (0, 0, 0, 0))
    dr = ImageDraw.Draw(img)
    dr.rounded_rectangle([0, 0, 512 * SS - 1, 512 * SS - 1],
                         radius=116 * SS, fill=DARK)
    draw_w_badge(dr, lambda x, y: (x * SS, y * SS), SS)
    return img.resize((n, n), Image.LANCZOS)


def foreground(n):
    """Adaptive foreground: শুধু W+ব্যাজ, স্বচ্ছ ক্যানভাসে কেন্দ্রে (৫২%)।"""
    img = Image.new("RGBA", (2048, 2048), (0, 0, 0, 0))
    dr = ImageDraw.Draw(img)
    s = (0.52 * 2048) / (363 * SS)  # কনটেন্ট-প্রস্থ 363 → ৫২% ক্যানভাস
    cx, cy = 244.5, 286.5           # কনটেন্ট-বক্স কেন্দ্র

    def T(x, y):
        return (1024 + (x - cx) * SS * s, 1024 + (y - cy) * SS * s)

    draw_w_badge(dr, T, SS * s)
    return img.resize((n, n), Image.LANCZOS)


DENS = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}

if __name__ == "__main__":
    for d, k in DENS.items():
        p = os.path.join(BASE, "res", "mipmap-" + d)
        os.makedirs(p, exist_ok=True)
        legacy(int(48 * k)).save(os.path.join(p, "ic_launcher.png"))
        foreground(int(108 * k)).save(os.path.join(p, "ic_fg.png"))
        print(d, "ic_launcher", int(48 * k), "+ ic_fg", int(108 * k), "OK")
    print("সব density সম্পন্ন")
