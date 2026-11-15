#!/usr/bin/env python3
"""Check CWA documented projections against independently measured native graticules.

The 32 measured controls (plus source JPEG checksums) live in the JVM test fixture.
These diagnostic PNGs are original-size source imagery, not screenshots of the App.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
SPECIFICATION = "https://www.cwa.gov.tw/Data/data_catalog/3-7-2.pdf"


def taiwan(lat, lon):
    phi, delta = math.radians(lat), math.radians(lon - 121)
    b = math.cos(phi) * math.sin(delta)
    return .5 * math.log((1 + b) / (1 - b)), math.atan2(math.tan(phi), math.cos(delta))


def east_asia(lat, lon):
    a, b = math.radians(30), math.radians(60)
    n = math.log(math.cos(a) / math.cos(b)) / math.log(math.tan(math.pi/4+b/2) / math.tan(math.pi/4+a/2))
    factor = math.cos(a) * math.tan(math.pi/4+a/2)**n / n
    rho = factor / math.tan(math.pi/4+math.radians(lat)/2)**n
    theta = n * math.radians(lon-128.5)
    return rho * math.sin(theta), factor - rho * math.cos(theta)


def pixel(region, latitude, longitude, size):
    project = taiwan if region == "taiwan" else east_asia
    ll = project(19.0295, 116.1417) if region == "taiwan" else project(-1.503, 102.111)
    ur = project(28.2065, 126.2109) if region == "taiwan" else project(48.589, 155.270)
    point = project(latitude, longitude)
    return ((point[0] - ll[0]) / (ur[0] - ll[0]) * (size - 1),
            (1 - (point[1] - ll[1]) / (ur[1] - ll[1])) * (size - 1))


def check(region, fixture, image_path, output):
    image_bytes = image_path.read_bytes()
    if hashlib.sha256(image_bytes).hexdigest() != fixture["sha256"]:
        raise ValueError(f"{region}: source JPEG does not match measured-control fixture")
    source = Image.open(image_path).convert("RGB")
    size = fixture["size"]
    if source.size != (size, size):
        raise ValueError("Source size mismatch")
    canvas = Image.new("RGB", (size, size + 48), "#102333")
    canvas.paste(source, (0, 48))
    draw = ImageDraw.Draw(canvas)
    font = ImageFont.truetype("C:/Windows/Fonts/arial.ttf", 15)
    draw.text((8, 5), "SOURCE PIXELS: cyan=measured; green=projection; red=old linear Taiwan", fill="white", font=font)
    draw.text((8, 25), "Original-size diagnostic. Native JPG graticules, before crop/inpaint.", fill="white", font=font)
    points = []
    for control in fixture["controls"]:
        predicted = pixel(region, control["latitude"], control["longitude"], size)
        # Actual parser indexes source pixels with toInt (floor in this domain).
        sampled = [int(value) for value in predicted]
        observed = (control["x"], control["y"])
        error = math.dist(sampled, observed)
        entry = control | {"projected": predicted, "sampled": sampled, "sample_error_pixels": error}
        x, y = observed
        draw.ellipse((x-5, y+43, x+5, y+53), outline="cyan", width=1)
        x, y = sampled
        draw.line((x-5, y+48, x+5, y+48), fill="#50ff80", width=1)
        draw.line((x, y+43, x, y+53), fill="#50ff80", width=1)
        if region == "taiwan":
            old = ((control["longitude"]-115.976888855)/(126.02300114-115.976888855)*799,
                   (28.29937425-control["latitude"])/(28.29937425-19.100625745)*799)
            entry |= {"old_linear": old, "old_error_pixels": math.dist(old, observed)}
            ox, oy = old
            draw.line((ox-3, oy+45, ox+3, oy+51), fill="#ff6060", width=1)
            draw.line((ox-3, oy+51, ox+3, oy+45), fill="#ff6060", width=1)
        points.append(entry)
    output.mkdir(parents=True, exist_ok=True)
    canvas.save(output / f"{region}_source_controls.png")
    result = {"specification": SPECIFICATION, "control_count": len(points), "size": size,
              "rmse_pixels": math.sqrt(sum(p["sample_error_pixels"]**2 for p in points)/len(points)),
              "max_error_pixels": max(p["sample_error_pixels"] for p in points), "controls": points}
    if region == "taiwan":
        result["old_rmse_pixels"] = math.sqrt(sum(p["old_error_pixels"]**2 for p in points)/len(points))
        result["old_max_error_pixels"] = max(p["old_error_pixels"] for p in points)
    (output / f"{region}_report.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(f"{region}: {len(points)} measured controls, RMS {result['rmse_pixels']:.3f}px, max {result['max_error_pixels']:.3f}px")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--images", type=Path, default=ROOT / "build/cloud-debug/native-current")
    parser.add_argument("--output", type=Path, default=ROOT / "build/cloud-georeference")
    args = parser.parse_args()
    fixture = json.loads((ROOT / "app/src/test/resources/satellite_control_points.json").read_text())
    for region in ("taiwan", "east_asia"):
        check(region, fixture[region], args.images / f"{region}.jpg", args.output)


if __name__ == "__main__":
    main()
