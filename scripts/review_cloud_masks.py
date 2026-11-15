#!/usr/bin/env python3
"""Collect native CWA JPGs on this computer and build review-only masks.

Never writes Android assets. Latest/fileapi images and website animation images
have independent archives, masks, crop rectangles, and provenance manifests.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import logging
import os
import time
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

import numpy as np
from PIL import Image

from generate_cloud_backgrounds import annotation_mask, decode_luminance, download

ROOT = Path(__file__).resolve().parents[1]
ARCHIVE = ROOT / "build/cloud-debug/native-archive"
OUTPUT = ROOT / "build/cloud-review"
S3 = "https://cwaopendata.s3.ap-northeast-1.amazonaws.com/Observation"
MIN_SAMPLES = 12
MIN_SPAN_HOURS = 6


@dataclass(frozen=True)
class Layout:
    name: str
    dataset: str
    size: tuple[int, int]
    # Coordinates in the original image. Crop changes extent, never pixel scale.
    crop: tuple[int, int, int, int]
    logo: tuple[int, int, int, int]


LAYOUTS = (
    Layout("latest_taiwan", "O-C0042-004", (800, 800), (5, 36, 795, 778), (7, 752, 72, 796)),
    Layout("latest_east_asia", "O-B0032-003", (1200, 1200), (1, 55, 1199, 1176), (10, 1128, 77, 1172)),
    Layout("history_taiwan", "TWI_IR1_Gray_800", (800, 800), (1, 24, 799, 799), (7, 752, 72, 796)),
    Layout("history_east_asia", "LCC_IR1_Gray_1000", (1000, 1000), (1, 30, 999, 999), (8, 940, 75, 985)),
)


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    temporary.replace(path)


def observed_time(metadata: dict) -> str:
    dataset = metadata["cwaopendata"]["dataset"]
    obs = dataset.get("ObsTime", dataset.get("obsTime", {}))
    return obs.get("Datetime", obs.get("DateTime"))


def archive_native(layout: Layout, metadata_bytes: bytes, jpg: bytes, origin: str,
                   archive: Path = ARCHIVE) -> bool:
    metadata = json.loads(metadata_bytes)
    observed = observed_time(metadata)
    datetime.fromisoformat(observed)  # Reject missing/invalid effective time.
    decode_luminance(jpg, layout.size)
    directory = archive / layout.dataset
    stamp = datetime.fromisoformat(observed).strftime("%Y%m%dT%H%M%S%z")
    record_path = directory / f"{stamp}.json"
    digest = hashlib.sha256(jpg).hexdigest()
    if record_path.exists():
        existing = json.loads(record_path.read_text(encoding="utf-8"))
        if existing["sha256"] != digest:
            raise RuntimeError(f"{layout.dataset}: different JPEG for already archived observation {observed}")
        return False
    directory.mkdir(parents=True, exist_ok=True)
    image_path = directory / f"{stamp}.jpg"
    image_path.write_bytes(jpg)  # Preserve original bytes; no resize/re-encoding.
    write_json(record_path, {
        "dataset": layout.dataset, "observed_at": observed,
        "collected_at": datetime.now(timezone.utc).isoformat(),
        "origin": origin, "image": image_path.name, "sha256": digest,
        "size": layout.size, "metadata": metadata,
    })
    logging.info("archived %s %s", layout.dataset, observed)
    return True


def collect_once(archive: Path = ARCHIVE) -> bool:
    changed = False
    for layout in LAYOUTS[:2]:
        try:
            # Public S3 product metadata and JPG: no key is needed or transmitted.
            # Bracket image download with metadata reads to reject an update race.
            metadata = download(f"{S3}/{layout.dataset}.json")
            jpg = download(f"{S3}/{layout.dataset}.jpg")
            after = download(f"{S3}/{layout.dataset}.json")
            if json.loads(metadata) != json.loads(after):
                raise RuntimeError("metadata changed during download; retry at next poll")
            changed |= archive_native(layout, metadata, jpg, f"{S3}/{layout.dataset}.jpg", archive)
        except Exception:
            logging.exception("could not collect %s", layout.dataset)
    return changed


def import_previous(archive: Path = ARCHIVE) -> None:
    for layout in LAYOUTS[:2]:
        base = ROOT / "build/cloud-debug/live" / layout.dataset
        if base.with_suffix(".json").exists() and base.with_suffix(".jpg").exists():
            archive_native(layout, base.with_suffix(".json").read_bytes(),
                           base.with_suffix(".jpg").read_bytes(), "previous local download", archive)


def native_records(layout: Layout, archive: Path) -> list[dict]:
    records = []
    for path in sorted((archive / layout.dataset).glob("*.json")):
        record = json.loads(path.read_text(encoding="utf-8"))
        record["path"] = str(path.parent / record["image"])
        if hashlib.sha256(Path(record["path"]).read_bytes()).hexdigest() != record["sha256"]:
            raise RuntimeError(f"archive checksum mismatch: {record['path']}")
        records.append(record)
    return records


def history_records(layout: Layout) -> list[dict]:
    region = layout.name.removeprefix("history_")
    manifest = json.loads((ROOT / f"docs/cloud-templates/{region}.json").read_text(encoding="utf-8"))
    records = []
    for remote_path in manifest["paths"]:
        path = ROOT / "build/cloud-debug/frames" / Path(remote_path).name
        stamp = Path(remote_path).stem.removeprefix(layout.dataset + "-")
        observed = datetime.strptime(stamp, "%Y-%m-%d-%H-%M").replace(
            tzinfo=timezone(timedelta(hours=8))).isoformat()
        records.append({"path": str(path), "observed_at": observed,
                        "origin": f"https://www.cwa.gov.tw/Data/satellite/{remote_path}",
                        "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    return sorted(records, key=lambda record: record["observed_at"])


def exclusion_mask(layout: Layout) -> np.ndarray:
    width, height = layout.size
    result = np.ones((height, width), dtype=bool)
    left, top, right, bottom = layout.crop
    result[top:bottom, left:right] = False
    left, top, right, bottom = layout.logo
    result[top:bottom, left:right] = True
    return result


def review_mask(layout: Layout, records: list[dict], output: Path = OUTPUT,
                allow_draft: bool = False) -> dict:
    times = [datetime.fromisoformat(record["observed_at"]) for record in records]
    unique_count = len(set(times))
    span_hours = (max(times) - min(times)).total_seconds() / 3600 if times else 0
    enough = unique_count >= MIN_SAMPLES and span_hours >= MIN_SPAN_HOURS
    if unique_count < 2 or (not enough and not allow_draft):
        raise RuntimeError(f"{layout.name}: {unique_count} times / {span_hours:.2f}h; "
                           f"need {MIN_SAMPLES} times across {MIN_SPAN_HOURS}h, or --allow-draft")
    # All original JPGs stay archived; bound generation RAM with evenly spaced samples.
    selected = [records[index] for index in np.linspace(0, len(records) - 1, min(144, len(records)), dtype=int)]
    frames = np.stack([decode_luminance(Path(record["path"]).read_bytes(), layout.size) for record in selected])
    excluded = exclusion_mask(layout)
    annotations = annotation_mask(frames) & ~excluded
    destination = output / layout.name
    destination.mkdir(parents=True, exist_ok=True)
    Image.fromarray(annotations.astype(np.uint8) * 255).save(destination / "annotations.png")
    Image.fromarray(excluded.astype(np.uint8) * 255).save(destination / "exclusions.png")
    with Image.open(selected[-1]["path"]) as image:
        source = np.array(image.convert("RGB"))
    overlay = source.copy()
    overlay[annotations] = (source[annotations] * 0.25 + np.array([255, 0, 0]) * 0.75).astype(np.uint8)
    overlay[excluded] = (source[excluded] * 0.4 + np.array([255, 210, 0]) * 0.6).astype(np.uint8)
    Image.fromarray(overlay).save(destination / "overlay.png")
    Image.fromarray(overlay).crop(layout.crop).save(destination / "overlay_cropped.png")
    report = {
        "layout": layout.name, "dataset": layout.dataset,
        "status": "ready_for_visual_review" if enough else "DRAFT_INSUFFICIENT_HISTORY",
        "sample_count": len(selected), "unique_observations": unique_count,
        "span_hours": span_hours, "size": layout.size, "crop": layout.crop,
        "logo_exclusion": layout.logo, "annotation_pixels": int(annotations.sum()),
        "annotation_fraction": float(annotations.mean()), "excluded_fraction": float(excluded.mean()),
        "minimum_samples": MIN_SAMPLES, "minimum_span_hours": MIN_SPAN_HOURS,
        "algorithm": "persistent-ridge-connected-v1; no dilation; native pixels",
        "preview_observed_at": selected[-1]["observed_at"],
        "samples": selected,
        "android_assets_written": False,
    }
    write_json(destination / "manifest.json", report)
    logging.info("%s: %s, %s samples, %.2fh, mask %.2f%%", layout.name, report["status"],
                 len(selected), span_hours, annotations.mean() * 100)
    return report


def build_all(archive: Path, output: Path, allow_draft: bool, native_only: bool = False) -> None:
    for layout in LAYOUTS[:2] if native_only else LAYOUTS:
        try:
            records = native_records(layout, archive) if layout.name.startswith("latest_") else history_records(layout)
            review_mask(layout, records, output, allow_draft)
        except Exception:
            logging.exception("could not build %s", layout.name)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--collect-once", action="store_true")
    parser.add_argument("--watch", action="store_true", help="collect native images every 10 minutes until stopped")
    parser.add_argument("--build", action="store_true")
    parser.add_argument("--allow-draft", action="store_true", help="label insufficient-history masks as DRAFT")
    parser.add_argument("--native-only", action="store_true", help="skip the inactive animation mask previews")
    parser.add_argument("--archive", type=Path, default=ARCHIVE)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    parser.add_argument("--stop-file", type=Path, default=ROOT / "build/cloud-debug/STOP_NATIVE_COLLECTOR")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    if not (args.collect_once or args.watch or args.build):
        parser.error("choose --collect-once, --watch, or --build")
    if args.watch:
        (ROOT / "build/cloud-debug").mkdir(parents=True, exist_ok=True)
        (ROOT / "build/cloud-debug/native-collector.pid").write_text(str(os.getpid()), encoding="ascii")
        logging.info("local collector PID %s; interval 600s", os.getpid())
    import_previous(args.archive)
    if args.collect_once or args.watch:
        collect_once(args.archive)
    if args.build:
        build_all(args.archive, args.output, args.allow_draft, args.native_only)
    while args.watch and not args.stop_file.exists():
        # Interruptible local process, not an Android job or a registered service.
        for _ in range(600):
            if args.stop_file.exists():
                logging.info("stop file found; collector stopped")
                return
            time.sleep(1)
        if collect_once(args.archive) and args.build:
            build_all(args.archive, args.output, args.allow_draft, args.native_only)


if __name__ == "__main__":
    main()
