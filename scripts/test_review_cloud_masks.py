"""Offline checks for the separate local native-image archive and review gate."""
import hashlib
import io
import json
import tempfile
import unittest
from pathlib import Path

import numpy as np
from PIL import Image

from review_cloud_masks import ROOT, Layout, LAYOUTS, archive_native, exclusion_mask, native_records, review_mask


class ReviewCloudMasksTest(unittest.TestCase):
    def setUp(self):
        parent = (ROOT / "build/cloud-review-tests").resolve()
        parent.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=parent)
        self.assertTrue(Path(self.temporary.name).resolve().is_relative_to(parent))
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.layout = Layout("latest_test", "test-product", (60, 60), (1, 4, 59, 59), (2, 54, 8, 58))
        self.image = io.BytesIO()
        pixels = np.full((60, 60, 3), 80, dtype=np.uint8)
        pixels[:, 30] = 255
        Image.fromarray(pixels).save(self.image, format="JPEG", quality=100)

    def archive(self, observed):
        metadata = json.dumps({"cwaopendata": {"dataset": {"ObsTime": {"Datetime": observed}}}}).encode()
        return archive_native(self.layout, metadata, self.image.getvalue(), "test", self.root / "archive")

    def test_archives_original_bytes_once_per_observation_and_keeps_times(self):
        self.assertTrue(self.archive("2026-11-09T10:00:00+08:00"))
        self.assertFalse(self.archive("2026-11-09T10:00:00+08:00"))
        self.assertTrue(self.archive("2026-11-10T10:00:00+08:00"))
        records = native_records(self.layout, self.root / "archive")
        self.assertEqual(len(records), 2)
        self.assertEqual(Path(records[0]["path"]).read_bytes(), self.image.getvalue())
        self.assertEqual(records[0]["sha256"], hashlib.sha256(self.image.getvalue()).hexdigest())

    def test_corrupted_archive_fails_checksum_validation(self):
        self.archive("2026-11-09T10:00:00+08:00")
        records = native_records(self.layout, self.root / "archive")
        Path(records[0]["path"]).write_bytes(b"corrupted")
        with self.assertRaisesRegex(RuntimeError, "checksum"):
            native_records(self.layout, self.root / "archive")

    def test_two_frames_cannot_silently_become_a_finished_mask(self):
        self.archive("2026-11-09T10:00:00+08:00")
        self.archive("2026-11-10T10:00:00+08:00")
        records = native_records(self.layout, self.root / "archive")
        with self.assertRaisesRegex(RuntimeError, "need 12"):
            review_mask(self.layout, records, self.root / "review")
        report = review_mask(self.layout, records, self.root / "review", allow_draft=True)
        self.assertEqual(report["status"], "DRAFT_INSUFFICIENT_HISTORY")
        self.assertFalse(report["android_assets_written"])
        destination = self.root / "review/latest_test"
        with Image.open(destination / "overlay.png") as image:
            self.assertEqual(image.size, (60, 60))
        with Image.open(destination / "overlay_cropped.png") as image:
            self.assertEqual(image.size, (58, 55))
        with Image.open(destination / "annotations.png") as image:
            mask = np.asarray(image)
        self.assertTrue(mask[5:53, 30].all())
        self.assertFalse(mask[:4].any())

    def test_repeated_effective_time_does_not_count_as_history(self):
        self.archive("2026-11-09T10:00:00+08:00")
        records = native_records(self.layout, self.root / "archive") * 144
        with self.assertRaisesRegex(RuntimeError, "1 times"):
            review_mask(self.layout, records, self.root / "review", allow_draft=True)

    def test_native_and_animation_layouts_are_distinct_even_at_equal_size(self):
        native_tw, native_ea, history_tw, history_ea = LAYOUTS
        self.assertEqual(native_tw.size, history_tw.size)
        self.assertNotEqual(native_tw.dataset, history_tw.dataset)
        self.assertNotEqual(native_tw.crop, history_tw.crop)
        self.assertEqual(native_ea.size, (1200, 1200))
        self.assertEqual(history_ea.size, (1000, 1000))
        self.assertEqual(len({layout.name for layout in LAYOUTS}), 4)

    def test_crop_and_logo_do_not_exclude_normal_interior(self):
        for layout in LAYOUTS:
            mask = exclusion_mask(layout)
            left, top, right, bottom = layout.crop
            self.assertTrue(mask[:top].all())
            self.assertTrue(mask[bottom:].all())
            self.assertFalse(mask[top + 1, left + 1])
            self.assertFalse(mask[bottom - 1, right - 1])
            x0, y0, x1, y1 = layout.logo
            self.assertTrue(mask[y0:y1, x0:x1].all())


if __name__ == "__main__":
    unittest.main()
