#!/usr/bin/env python3
"""Build native fileapi masks from the local archive; never use animation JPGs."""
import argparse
import json
import logging
from pathlib import Path

import numpy as np
from PIL import Image

from generate_cloud_backgrounds import decode_luminance
from review_cloud_masks import ARCHIVE, LAYOUTS, OUTPUT, ROOT, native_records, review_mask, write_json


def build(layout, archive, output, manifests, allow_draft=False):
    report = review_mask(layout, native_records(layout, archive), OUTPUT, allow_draft)
    # The reviewed mask and exact original-resolution crop are the source of truth.
    review = OUTPUT / layout.name
    annotations = np.asarray(Image.open(review / "annotations.png")) >= 128
    excluded = np.asarray(Image.open(review / "exclusions.png")) >= 128
    frames = np.stack([decode_luminance(Path(sample["path"]).read_bytes(), layout.size)
                       for sample in report["samples"]])
    baseline = int(np.percentile(frames[:, ~annotations & ~excluded], 5))
    region = layout.name.removeprefix("latest_")
    output.mkdir(parents=True, exist_ok=True)
    for name, values in (
        ("background", np.full(annotations.shape, baseline, dtype=np.uint8)),
        ("annotations", annotations.astype(np.uint8) * 255),
        ("exclusions", excluded.astype(np.uint8) * 255),
    ):
        Image.fromarray(values).save(output / f"cloud_native_{name}_{region}.png", optimize=True)
    report["source"] = "CWA fileapi latest native product; NOT website animation"
    report["source_id"] = layout.dataset + "|fileapi-native"
    report["baseline"] = baseline
    report["samples"] = [{key: sample[key] for key in ("observed_at", "origin", "sha256")}
                         | {"archive_image": str(Path(sample["path"]).relative_to(ROOT)).replace("\\", "/")}
                         for sample in report["samples"]]
    report.pop("android_assets_written")
    write_json(manifests / f"native_{region}.json", report)
    logging.info("%s: %s, baseline %s, output %s", region, report["status"], baseline, output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, default=ARCHIVE)
    parser.add_argument("--output-dir", type=Path, default=ROOT / "build/native-cloud-templates")
    parser.add_argument("--manifest-dir", type=Path, default=ROOT / "build/native-cloud-templates")
    parser.add_argument("--allow-draft", action="store_true")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO)
    for layout in LAYOUTS[:2]:
        build(layout, args.archive, args.output_dir, args.manifest_dir, args.allow_draft)


if __name__ == "__main__":
    main()
