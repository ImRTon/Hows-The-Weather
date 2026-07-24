#!/usr/bin/env python3
"""Build fixed CWA satellite basemap estimates from public animation frames.

The CWA grayscale infrared JPG products composite a temperature-dependent cloud
layer over fixed cartography. A per-pixel temporal mode is therefore a useful
estimate of that fixed background. The generated grayscale PNGs are consumed
only by the experimental cloud visualization; they must not feed rain decisions.
"""

from __future__ import annotations

import argparse
import io
import re
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from PIL import Image


ANIMATION_LIST_URL = "https://www.cwa.gov.tw/Data/js/obs_img/Observe_sat.js"
ANIMATION_BASE_URL = "https://www.cwa.gov.tw/Data/satellite"
FRAME_PATTERN = re.compile(
    r"""["']img["']\s*:\s*["']([^"']+)["']\s*,\s*["']text["']""",
)


@dataclass(frozen=True)
class Product:
    prefix: str
    output_name: str
    expected_size: tuple[int, int]


PRODUCTS = (
    Product("TWI_IR1_Gray_800", "cloud_background_taiwan.png", (800, 800)),
    Product("LCC_IR1_Gray_1000", "cloud_background_east_asia.png", (1000, 1000)),
)


def download(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "HowsTheWeather-background-builder/1"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read()


def frame_paths(script: str, prefix: str, sample_count: int) -> list[str]:
    matches = [
        path
        for path in FRAME_PATTERN.findall(script)
        if path.startswith(prefix + "/") and ".." not in path
    ]
    matches = list(dict.fromkeys(matches))
    if len(matches) < sample_count:
        raise RuntimeError(f"{prefix}: only {len(matches)} frames are available")
    indices = np.linspace(0, len(matches) - 1, sample_count, dtype=int)
    return [matches[index] for index in sorted(set(indices))]


def decode_luminance(data: bytes, expected_size: tuple[int, int]) -> np.ndarray:
    with Image.open(io.BytesIO(data)) as image:
        if image.size != expected_size:
            raise RuntimeError(f"unexpected image size {image.size}, expected {expected_size}")
        rgb = np.asarray(image.convert("RGB"), dtype=np.float32)
    return np.clip(
        rgb[..., 0] * 0.2126 + rgb[..., 1] * 0.7152 + rgb[..., 2] * 0.0722,
        0,
        255,
    ).astype(np.uint8)


def temporal_mode(frames: np.ndarray, bin_width: int = 4, rows_per_chunk: int = 32) -> np.ndarray:
    """Returns the center of the densest luminance bin for every source pixel."""
    _, height, width = frames.shape
    bin_count = (256 + bin_width - 1) // bin_width
    result = np.empty((height, width), dtype=np.uint8)
    for top in range(0, height, rows_per_chunk):
        bottom = min(height, top + rows_per_chunk)
        chunk = frames[:, top:bottom, :]
        pixel_count = (bottom - top) * width
        pixel_indices = np.arange(pixel_count)
        counts = np.zeros((bin_count, pixel_count), dtype=np.uint16)
        for frame in chunk:
            bins = (frame.reshape(-1) // bin_width).astype(np.intp)
            np.add.at(counts, (bins, pixel_indices), 1)
        winning_bins = counts.argmax(axis=0).astype(np.uint8)
        result[top:bottom, :] = (
            winning_bins.reshape(bottom - top, width) * bin_width + bin_width // 2
        ).clip(0, 255)
    return result


def build_product(product: Product, script: str, sample_count: int, output_dir: Path) -> None:
    paths = frame_paths(script, product.prefix, sample_count)
    def load(index_and_path: tuple[int, str]) -> tuple[int, np.ndarray]:
        index, path = index_and_path
        data = download(f"{ANIMATION_BASE_URL}/{path}")
        return index, decode_luminance(data, product.expected_size)

    with ThreadPoolExecutor(max_workers=6) as executor:
        loaded = list(executor.map(load, enumerate(paths)))
    loaded.sort(key=lambda item: item[0])
    frames = np.stack([frame for _, frame in loaded])

    background = temporal_mode(frames)
    output_dir.mkdir(parents=True, exist_ok=True)
    output = output_dir / product.output_name
    Image.fromarray(background).save(output, optimize=True)
    print(f"{product.prefix}: {len(paths)} frames -> {output}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--samples", type=int, default=144)
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("app/src/main/res/raw"),
    )
    args = parser.parse_args()
    if args.samples < 12:
        parser.error("--samples must be at least 12")
    script = download(ANIMATION_LIST_URL).decode("utf-8")
    for product in PRODUCTS:
        build_product(product, script, args.samples, args.output_dir)


if __name__ == "__main__":
    main()
