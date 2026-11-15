#!/usr/bin/env python3
"""Extract fixed satellite cartography from a time series, independently of clouds.

Only persistent, narrow bright features become annotation masks. A temporal mode
of the whole image must not be used as a mask: slow clouds survive that statistic.
Outputs are used only by the experimental cloud visualization.
"""

from __future__ import annotations

import argparse
import io
import json
import re
import ssl
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
    context = ssl.create_default_context()
    # Python 3.13's stricter X.509 extension checks reject CWA's certificate.
    # Keep certificate-chain and hostname verification enabled.
    context.verify_flags &= ~ssl.VERIFY_X509_STRICT
    with urllib.request.urlopen(request, timeout=30, context=context) as response:
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


def annotation_mask(frames: np.ndarray) -> np.ndarray:
    """Require temporal persistence AND thin-line contrast, not just brightness."""
    low, high = np.percentile(frames, [5, 95], axis=0)
    contrast_votes = np.zeros(frames.shape[1:], dtype=np.uint16)
    for frame in frames:
        padded = np.pad(frame.astype(np.int16), 2, mode="edge")
        # A thin ridge is brighter than BOTH sides of at least one axis. A broad
        # bright cloud edge has a bright interior on one side and fails this test.
        ridge_background = np.minimum(
            np.maximum(padded[2:-2, :-4], padded[2:-2, 4:]),
            np.maximum(padded[:-4, 2:-2], padded[4:, 2:-2]),
        )
        contrast_votes += (frame.astype(np.int16) - ridge_background >= 18)
    stable_bright = (low >= 225) & (high - low <= 24)
    core = stable_bright & (contrast_votes >= len(frames) * 0.25)

    def neighbors(mask: np.ndarray) -> np.ndarray:
        padded = np.pad(mask, 1)
        return np.logical_or.reduce([
            padded[dy:dy + mask.shape[0], dx:dx + mask.shape[1]]
            for dy in range(3) for dx in range(3)
        ])

    # Recover thicker coastline junctions only along connected persistent white
    # pixels. This cannot grow across a normal gray cloud/background pixel.
    while True:
        expanded = core | (neighbors(core) & stable_bright)
        if np.array_equal(expanded, core):
            break
        core = expanded
    adjacent = neighbors(core)
    # Include only persistent antialiased edge pixels immediately next to a core.
    # There is no unconditional dilation into normal cloud pixels.
    return core | (adjacent & (low >= 120) & (high - low <= 24))


def exclusion_mask(width: int, height: int) -> np.ndarray:
    """The reviewed animation title, one-pixel frame, and tightly bounded logo."""
    mask = np.zeros((height, width), dtype=bool)
    mask[:round(height * 0.03), :] = True
    mask[-1, :] = True
    mask[:, :1] = True
    mask[:, -1:] = True
    logo = {(800, 800): (7, 752, 72, 796), (1000, 1000): (8, 940, 75, 985)}[(width, height)]
    left, top, right, bottom = logo
    mask[top:bottom, left:right] = True
    return mask


def build_product(
    product: Product, script: str, sample_count: int, output_dir: Path, cache_dir: Path,
    manifest_dir: Path = Path("docs/cloud-templates"),
) -> None:
    paths = frame_paths(script, product.prefix, sample_count)
    cache_dir.mkdir(parents=True, exist_ok=True)
    def load(index_and_path: tuple[int, str]) -> tuple[int, np.ndarray]:
        index, path = index_and_path
        cached = cache_dir / Path(path).name
        if not cached.exists():
            cached.write_bytes(download(f"{ANIMATION_BASE_URL}/{path}"))
        data = cached.read_bytes()
        return index, decode_luminance(data, product.expected_size)

    with ThreadPoolExecutor(max_workers=6) as executor:
        loaded = list(executor.map(load, enumerate(paths)))
    loaded.sort(key=lambda item: item[0])
    frames = np.stack([frame for _, frame in loaded])

    annotations = annotation_mask(frames)
    exclusions = exclusion_mask(*product.expected_size)
    annotations &= ~exclusions
    # Use a scalar clear-sky floor; a per-pixel background can contain persistent
    # clouds and would subtract real cloud structure even with a correct mask.
    baseline = int(np.percentile(frames[:, ~annotations & ~exclusions], 5))
    background = np.full(frames.shape[1:], baseline, dtype=np.uint8)
    output_dir.mkdir(parents=True, exist_ok=True)
    output = output_dir / product.output_name
    Image.fromarray(background).save(output, optimize=True)
    stem = product.output_name.removeprefix("cloud_background_").removesuffix(".png")
    Image.fromarray(annotations.astype(np.uint8) * 255).save(output_dir / f"cloud_annotations_{stem}.png", optimize=True)
    Image.fromarray(exclusions.astype(np.uint8) * 255).save(output_dir / f"cloud_exclusions_{stem}.png", optimize=True)
    manifest = json.dumps({
        "algorithm": "persistent-ridge-connected-v1",
        "paths": paths, "sample_count": len(paths), "size": product.expected_size,
        "low_percentile": 5, "high_percentile": 95, "min_core_luminance": 225,
        "max_luminance_spread": 24, "min_ridge_contrast": 18, "min_ridge_fraction": 0.25,
        "antialias_min_luminance": 120, "unconditional_dilation": 0,
        "baseline": baseline, "annotation_pixels": int(annotations.sum()),
        "excluded_pixels": int(exclusions.sum()),
        "source": "CWA website animation for both latest and historical frames",
        "crop": [1, round(product.expected_size[1] * 0.03), product.expected_size[0] - 1, product.expected_size[1] - 1],
    }, indent=2)
    (cache_dir / f"{stem}_manifest.json").write_text(manifest, encoding="utf-8")
    manifest_dir.mkdir(parents=True, exist_ok=True)
    (manifest_dir / f"{stem}.json").write_text(manifest, encoding="utf-8")
    print(f"{product.prefix}: {len(paths)} frames, floor={baseline}, annotation={annotations.mean():.2%}, excluded={exclusions.mean():.2%} -> {output}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--samples", type=int, default=144)
    parser.add_argument("--cache-dir", type=Path, default=Path("build/cloud-debug/frames"))
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("build/cloud-debug/animation-templates"),
    )
    args = parser.parse_args()
    if args.samples < 12:
        parser.error("--samples must be at least 12")
    script = download(ANIMATION_LIST_URL).decode("utf-8")
    for product in PRODUCTS:
        build_product(product, script, args.samples, args.output_dir, args.cache_dir)


if __name__ == "__main__":
    main()
