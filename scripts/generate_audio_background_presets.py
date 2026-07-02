#!/usr/bin/env python3
"""
Generate audio player background candidates for manual selection.

Usage:
  python3 scripts/generate_audio_background_presets.py
  python3 scripts/generate_audio_background_presets.py --count 80 --seed 20260702

The script writes paired previews:
  plans/audio_background_presets/plain/*.png
  plans/audio_background_presets/decorated/*.png

Each pair uses the same palette. The plain variant keeps only the base
background; the decorated variant adds a motif such as vinyl rings, waves,
clouds, grid, leaves, citrus slices, mountains, or light ribbons.
"""

from __future__ import annotations

import argparse
import csv
import math
import random
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable, Sequence

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUT = ROOT / "plans" / "audio_background_presets"


@dataclass(frozen=True)
class Candidate:
    index: int
    motif: str
    palette: tuple[tuple[int, int, int], tuple[int, int, int], tuple[int, int, int]]
    reverse: bool


PALETTES: tuple[tuple[str, tuple[tuple[int, int, int], ...]], ...] = (
    ("midnight_neon", ((7, 10, 24), (126, 46, 255), (0, 229, 255))),
    ("black_gold", ((6, 6, 8), (81, 50, 10), (255, 200, 87))),
    ("peach_sunset", ((255, 106, 61), (255, 211, 110), (127, 99, 255))),
    ("sea_mint", ((33, 212, 180), (183, 246, 110), (53, 167, 255))),
    ("aurora_candy", ((255, 79, 163), (172, 92, 255), (37, 216, 255))),
    ("sky_citrus", ((38, 184, 255), (255, 202, 69), (255, 112, 106))),
    ("rose_soda", ((255, 77, 135), (255, 179, 199), (129, 124, 255))),
    ("cyber_pop", ((0, 212, 255), (255, 47, 179), (255, 242, 92))),
    ("forest_morning", ((27, 167, 132), (255, 209, 102), (58, 134, 255))),
    ("lemon_coast", ((249, 248, 113), (66, 230, 149), (59, 178, 255))),
    ("violet_dusk", ((106, 92, 255), (255, 122, 89), (255, 209, 102))),
    ("plum_lake", ((68, 37, 137), (104, 216, 214), (255, 197, 214))),
    ("mango_lagoon", ((255, 184, 77), (0, 188, 212), (49, 93, 251))),
    ("ruby_ice", ((220, 38, 38), (236, 72, 153), (125, 211, 252))),
    ("olive_glow", ((35, 79, 30), (164, 222, 44), (255, 214, 102))),
)

MOTIFS = (
    "ribbons",
    "vinyl",
    "sunset",
    "waves",
    "stripes",
    "clouds",
    "rose_lines",
    "cyber_grid",
    "forest",
    "citrus",
    "mountains",
    "orbs",
)


def lerp(a: int, b: int, t: float) -> int:
    return round(a + (b - a) * t)


def mix(c1: tuple[int, int, int], c2: tuple[int, int, int], t: float) -> tuple[int, int, int]:
    return tuple(lerp(a, b, t) for a, b in zip(c1, c2))


def rgba(color: tuple[int, int, int], alpha: int) -> tuple[int, int, int, int]:
    return color[0], color[1], color[2], alpha


def gradient(size: tuple[int, int], colors: Sequence[tuple[int, int, int]], reverse: bool) -> Image.Image:
    w, h = size
    img = Image.new("RGB", size)
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = ((w - x if reverse else x) + y) / (w + h)
            if t < 0.52:
                c = mix(colors[0], colors[1], t / 0.52)
            else:
                c = mix(colors[1], colors[2], (t - 0.52) / 0.48)
            px[x, y] = c
    return img.convert("RGBA")


def add_readability(img: Image.Image) -> None:
    w, h = img.size
    overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    for y in range(h):
        t = y / max(1, h - 1)
        alpha = round(92 * (1 - min(t, 0.45) / 0.45)) if t < 0.45 else round(28 + 112 * ((t - 0.45) / 0.55))
        draw.line([(0, y), (w, y)], fill=(0, 0, 0, min(145, max(24, alpha))))
    img.alpha_composite(overlay)


def draw_ribbon(draw: ImageDraw.ImageDraw, w: int, h: int, y0: float, y1: float, color: tuple[int, int, int, int]) -> None:
    points = [
        (-0.18 * w, y0 * h),
        (0.16 * w, (y0 - 0.1) * h),
        (0.42 * w, (y1 + 0.08) * h),
        (1.18 * w, y1 * h),
        (1.18 * w, (y1 + 0.12) * h),
        (0.48 * w, (y1 + 0.02) * h),
        (0.18 * w, (y0 + 0.18) * h),
        (-0.18 * w, (y0 + 0.11) * h),
    ]
    draw.polygon(points, fill=color)


def draw_waves(draw: ImageDraw.ImageDraw, w: int, h: int, color: tuple[int, int, int, int], phase: float) -> None:
    for band in range(2):
        y_base = h * (0.42 + band * 0.22)
        amp = h * (0.075 + band * 0.03)
        pts = []
        for x in range(0, w + 8, 8):
            y = y_base + math.sin((x / w) * math.tau * 1.6 + phase + band) * amp
            pts.append((x, y))
        pts += [(w, h), (0, h)]
        draw.polygon(pts, fill=color)


def draw_grid(draw: ImageDraw.ImageDraw, w: int, h: int, color: tuple[int, int, int, int], step: int) -> None:
    for x in range(-w, w * 2, step):
        draw.line([(x, 0), (x + w // 2, h)], fill=color, width=1)
    for y in range(0, h, step):
        draw.line([(0, y), (w, y + h * 0.08)], fill=color, width=1)


def draw_vinyl(draw: ImageDraw.ImageDraw, w: int, h: int, accent: tuple[int, int, int]) -> None:
    cx, cy = int(w * 0.28), int(h * 0.28)
    r = int(min(w, h) * 0.27)
    draw.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(0, 0, 0, 100))
    for i in range(6):
        rr = r * (0.28 + i * 0.11)
        draw.ellipse((cx - rr, cy - rr, cx + rr, cy + rr), outline=(255, 255, 255, 48), width=2)
    draw.ellipse((cx - r * 0.15, cy - r * 0.15, cx + r * 0.15, cy + r * 0.15), fill=rgba(accent, 120))


def draw_horizon(draw: ImageDraw.ImageDraw, w: int, h: int, color: tuple[int, int, int, int]) -> None:
    base = h * 0.68
    pts = [(0, base), (w * 0.2, h * 0.58), (w * 0.5, h * 0.7), (w * 0.72, h * 0.6), (w, h * 0.72), (w, h), (0, h)]
    draw.polygon(pts, fill=color)


def draw_clouds(draw: ImageDraw.ImageDraw, w: int, h: int) -> None:
    y = h * 0.34
    color = (255, 255, 255, 96)
    draw.ellipse((-w * 0.1, y, w * 0.35, y + h * 0.16), fill=color)
    draw.ellipse((w * 0.18, y - h * 0.03, w * 0.72, y + h * 0.16), fill=color)
    draw.ellipse((w * 0.55, y + h * 0.02, w * 1.1, y + h * 0.2), fill=color)


def draw_citrus(draw: ImageDraw.ImageDraw, w: int, h: int) -> None:
    cx, cy, r = int(w * 0.75), int(h * 0.2), int(min(w, h) * 0.17)
    draw.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(255, 246, 129, 145), outline=(255, 255, 255, 180), width=3)
    for i in range(10):
        a = math.tau * i / 10
        draw.line((cx, cy, cx + math.cos(a) * r * 0.9, cy + math.sin(a) * r * 0.9), fill=(255, 255, 255, 150), width=2)


def draw_leaves(draw: ImageDraw.ImageDraw, w: int, h: int, color: tuple[int, int, int, int]) -> None:
    for cx, cy, rw, rh in ((0.68, 0.18, 0.18, 0.1), (0.82, 0.48, 0.14, 0.11), (0.18, 0.62, 0.12, 0.08)):
        x, y = w * cx, h * cy
        pts = [(x, y - h * rh), (x + w * rw, y), (x, y + h * rh), (x - w * rw, y)]
        draw.polygon(pts, fill=color)


def decorate(img: Image.Image, candidate: Candidate) -> None:
    w, h = img.size
    draw = ImageDraw.Draw(img, "RGBA")
    c0, c1, c2 = candidate.palette
    motif = candidate.motif
    if motif == "ribbons":
        draw_ribbon(draw, w, h, -0.08, 0.24, rgba(c2, 145))
        draw_ribbon(draw, w, h, 0.35, 0.58, rgba(c1, 105))
        draw_grid(draw, w, h, (255, 255, 255, 35), max(20, w // 18))
    elif motif == "vinyl":
        draw_vinyl(draw, w, h, c2)
        draw_ribbon(draw, w, h, 0.52, 0.78, rgba(c2, 120))
    elif motif == "sunset":
        draw.ellipse((w * 0.64, h * 0.14, w * 0.86, h * 0.32), fill=(255, 244, 168, 190))
        draw_horizon(draw, w, h, (52, 29, 82, 120))
    elif motif == "waves":
        draw_waves(draw, w, h, (255, 255, 255, 78), candidate.index * 0.71)
    elif motif == "stripes":
        for x in range(-w, w * 2, max(52, w // 7)):
            draw.polygon([(x, 0), (x + w * 0.08, 0), (x + w * 0.72, h), (x + w * 0.64, h)], fill=(255, 255, 255, 42))
    elif motif == "clouds":
        draw.ellipse((w * 0.14, h * 0.1, w * 0.36, h * 0.26), fill=(255, 244, 168, 190))
        draw_clouds(draw, w, h)
    elif motif == "rose_lines":
        draw_waves(draw, w, h, (255, 255, 255, 72), 1.7)
        draw_grid(draw, w, h, (255, 255, 255, 26), max(26, w // 14))
    elif motif == "cyber_grid":
        draw_grid(draw, w, h, (255, 255, 255, 70), max(18, w // 16))
        draw_ribbon(draw, w, h, 0.1, 0.38, (0, 27, 52, 115))
    elif motif == "forest":
        draw.ellipse((w * 0.08, h * 0.08, w * 0.32, h * 0.28), fill=(255, 238, 150, 170))
        draw_leaves(draw, w, h, (56, 184, 102, 92))
        draw_horizon(draw, w, h, (38, 84, 65, 122))
    elif motif == "citrus":
        draw_citrus(draw, w, h)
        draw_waves(draw, w, h, rgba(c2, 76), 0.5)
    elif motif == "mountains":
        draw.ellipse((w * 0.62, h * 0.12, w * 0.8, h * 0.26), fill=(255, 226, 160, 145))
        draw_horizon(draw, w, h, (48, 25, 84, 125))
    elif motif == "orbs":
        for _ in range(5):
            x, y = random.randint(0, w), random.randint(0, h)
            r = random.randint(w // 10, w // 4)
            draw.ellipse((x - r, y - r, x + r, y + r), fill=(255, 255, 255, random.randint(22, 52)))


def make_candidate(index: int, rng: random.Random) -> Candidate:
    motif = MOTIFS[index % len(MOTIFS)]
    _, base = rng.choice(PALETTES)
    colors = list(base)
    rng.shuffle(colors)
    return Candidate(index=index, motif=motif, palette=(colors[0], colors[1], colors[2]), reverse=rng.choice((False, True)))


def render(candidate: Candidate, size: tuple[int, int], decorated: bool) -> Image.Image:
    img = gradient(size, candidate.palette, candidate.reverse)
    if decorated:
        decorate(img, candidate)
    add_readability(img)
    return img


def save_contact_sheet(images: Sequence[tuple[str, Image.Image]], path: Path, columns: int = 4) -> None:
    if not images:
        return
    thumb_w, thumb_h = 180, 320
    label_h = 28
    rows = math.ceil(len(images) / columns)
    sheet = Image.new("RGB", (columns * thumb_w, rows * (thumb_h + label_h)), (24, 24, 28))
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.load_default()
    for i, (label, img) in enumerate(images):
        x = (i % columns) * thumb_w
        y = (i // columns) * (thumb_h + label_h)
        thumb = img.resize((thumb_w, thumb_h), Image.Resampling.LANCZOS).convert("RGB")
        sheet.paste(thumb, (x, y))
        draw.text((x + 6, y + thumb_h + 8), label, fill=(235, 235, 240), font=font)
    sheet.save(path)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate audio background preset candidates.")
    parser.add_argument("--count", type=int, default=48, help="number of candidates to generate")
    parser.add_argument("--seed", type=int, default=20260702, help="random seed for repeatable output")
    parser.add_argument("--width", type=int, default=1080, help="preview width")
    parser.add_argument("--height", type=int, default=1920, help="preview height")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT, help="output directory")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    rng = random.Random(args.seed)
    plain_dir = args.out / "plain"
    decorated_dir = args.out / "decorated"
    plain_dir.mkdir(parents=True, exist_ok=True)
    decorated_dir.mkdir(parents=True, exist_ok=True)
    size = (args.width, args.height)
    manifest_path = args.out / "manifest.csv"
    plain_sheet = []
    decorated_sheet = []
    with manifest_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["id", "motif", "palette", "plain", "decorated"])
        for i in range(args.count):
            candidate = make_candidate(i, rng)
            name = f"{i:03d}_{candidate.motif}"
            plain = render(candidate, size, decorated=False)
            decorated = render(candidate, size, decorated=True)
            plain_path = plain_dir / f"{name}_plain.png"
            decorated_path = decorated_dir / f"{name}_decorated.png"
            plain.save(plain_path)
            decorated.save(decorated_path)
            palette = " ".join("#%02X%02X%02X" % c for c in candidate.palette)
            writer.writerow([name, candidate.motif, palette, plain_path.relative_to(args.out), decorated_path.relative_to(args.out)])
            plain_sheet.append((name, plain))
            decorated_sheet.append((name, decorated))
    save_contact_sheet(plain_sheet, args.out / "contact_sheet_plain.jpg")
    save_contact_sheet(decorated_sheet, args.out / "contact_sheet_decorated.jpg")
    print(f"Generated {args.count} candidates in {args.out}")
    print(f"Open {args.out / 'contact_sheet_decorated.jpg'} and {args.out / 'contact_sheet_plain.jpg'}")


if __name__ == "__main__":
    main()
