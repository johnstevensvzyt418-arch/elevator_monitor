import tempfile
import unittest
from pathlib import Path

import numpy as np

from calibrate_protocol import WINDOW_SIZE, fit_baseline, load_normal_sequences
from calibrate_runtime_log import parse_sequences


class CalibrationTest(unittest.TestCase):
    def test_tsv_uses_interval_and_splits_large_gaps(self):
        rows = []
        for session_start in (1_000, 2_000):
            for offset in range(30):
                floor = 4 if offset < 15 else 1
                direction = "02" if offset < 15 else "00"
                rows.append(
                    f"{session_start + offset}\t{floor:02d}\t01\t{direction}\t0.0\t00\t00\t"
                )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "normal.tsv"
            path.write_text("\n".join(rows), encoding="utf-8")
            sequences = load_normal_sequences(path)

        self.assertEqual(2, len(sequences))
        self.assertTrue(all(sequence.shape[1] == 5 for sequence in sequences))
        self.assertTrue(all(sequence[0, 4] == 1.0 for sequence in sequences))
        _, _, _, _, window_scores = fit_baseline(sequences, 0.35, 1.35)
        self.assertEqual(2 * (30 - WINDOW_SIZE + 1), len(window_scores))

    def test_runtime_log_removes_bridge_duplicate(self):
        lines = []
        for index in range(6):
            second = index + 1
            feature = f"[0.0,{4 if index < 3 else 1}.0,1.0,2.0,0.0]"
            lines.append(
                f"2026-08-03 10:00:{second:02d}.000 [ai-worker-1] DEBUG x - "
                f"deviceId=dev1 features={feature} windowSize=20"
            )
            lines.append(
                f"2026-08-03 10:00:{second:02d}.100 [ai-worker-1] DEBUG x - "
                f"deviceId=dev1 features={feature} windowSize=20"
            )

        sequences = parse_sequences(lines, "dev1")

        self.assertEqual(1, len(sequences))
        self.assertEqual(6, sequences[0].shape[0])
        self.assertTrue(np.all(sequences[0][:, 4] == 1.0))


if __name__ == "__main__":
    unittest.main()
