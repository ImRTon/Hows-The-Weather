"""Offline regressions for extracting fixed map lines from moving clouds."""
import unittest

import numpy as np

from generate_cloud_backgrounds import annotation_mask, exclusion_mask


class CloudMaskTest(unittest.TestCase):
    def test_moving_clouds_and_persistent_bright_cloud_mass_are_not_map_lines(self):
        frames = np.full((24, 80, 80), 80, dtype=np.uint8)
        for index, frame in enumerate(frames):
            frame[10:28, 5 + index:20 + index] = 245
            frame[45:70, 40:70] = 240  # persistent cloud with a textured interior
            frame[48:67, 43:67] = 244
            frame[:, 30] = 255
            frame[35, :] = 255
            frame[:, 29] = 150  # actual one-pixel antialiasing edge

        mask = annotation_mask(frames)

        self.assertTrue(mask[5:30, 30].all())
        self.assertTrue(mask[35, 2:78].all())
        self.assertTrue(mask[5:30, 29].all())
        self.assertFalse(mask[10:28, 5:28].any())
        self.assertFalse(mask[50:65, 45:65].any())
        self.assertFalse(mask[5:30, 28].any())  # no blanket widening
        self.assertFalse(mask[5:30, 31].any())
        self.assertFalse(mask[45:70, 40:70].any())

    def test_bold_coastline_junctions_connected_to_thin_lines_are_included(self):
        frames = np.full((24, 60, 60), 80, dtype=np.uint8)
        frames[:, :, 30] = 255
        frames[:, 20:28, 27:35] = 255

        mask = annotation_mask(frames)

        self.assertTrue(mask[20:28, 27:35].all())
        self.assertFalse(mask[20:28, 26].any())
        self.assertFalse(mask[20:28, 35].any())

    def test_cloud_that_temporarily_stops_on_a_bright_line_does_not_widen_mask(self):
        frames = np.full((24, 60, 60), 80, dtype=np.uint8)
        frames[:, :, 30] = 255
        frames[4:20, 15:45, 25:36] = 255

        mask = annotation_mask(frames)

        self.assertTrue(mask[10:50, 30].all())
        self.assertFalse(mask[15:45, 25:30].any())
        self.assertFalse(mask[15:45, 31:36].any())

    def test_exclusions_fit_title_logo_and_one_pixel_border(self):
        for size in (800, 1000):
            mask = exclusion_mask(size, size)
            self.assertLess(mask.mean(), 0.04)
            self.assertTrue(mask[:round(size * 0.03), :].all())
            self.assertTrue(mask[int(size * 0.97), int(size * 0.05)])
            self.assertFalse(mask[size // 2, 2])
            self.assertFalse(mask[size // 2, size - 3])
            self.assertFalse(mask[size - 3, size // 2])


if __name__ == "__main__":
    unittest.main()
