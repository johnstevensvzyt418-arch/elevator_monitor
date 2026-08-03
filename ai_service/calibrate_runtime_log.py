"""Build a five-sample MNK baseline from verified-normal backend runtime logs.

Input is the output of ``docker logs elevator-backend``. Only the first four
state features are reused; feature five is rebuilt from the receive timestamp.
Duplicate MQTT/HTTP copies are removed before calibration.
"""

from __future__ import annotations

import argparse
import re
import sys
from datetime import datetime
from pathlib import Path

import numpy as np

from calibrate_protocol import (
    FIRST_INTERVAL_SECONDS,
    MAX_CONTINUOUS_INTERVAL_SECONDS,
    WINDOW_SIZE,
    fit_baseline,
)
from protocol_baseline import FEATURE_SCHEMA


LINE_PATTERN = re.compile(
    r"^(?P<time>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}).*"
    r"deviceId=(?P<device>[A-Za-z0-9_-]+) features=\["
    r"(?P<features>[^]]+)\]"
)


def parse_sequences(lines: list[str], device_id: str | None) -> list[np.ndarray]:
    sequences: list[np.ndarray] = []
    current: list[list[float]] = []
    previous_time: float | None = None
    previous_signature: tuple[float, ...] | None = None

    def flush() -> None:
        nonlocal current
        if len(current) >= WINDOW_SIZE:
            sequences.append(np.asarray(current, dtype=np.float64))
        current = []

    for line in lines:
        match = LINE_PATTERN.search(line.strip())
        if not match or (device_id and match.group("device") != device_id):
            continue
        try:
            parsed = tuple(float(value.strip()) for value in match.group("features").split(","))
        except ValueError:
            continue
        if len(parsed) != 5:
            continue

        signature = parsed[:4]
        timestamp = datetime.strptime(match.group("time"), "%Y-%m-%d %H:%M:%S.%f").timestamp()
        if previous_time is not None:
            gap = timestamp - previous_time
            # MQTT direct + HTTP bridge copies arrive almost together.
            if signature == previous_signature and 0 <= gap <= 0.5:
                continue
            if gap < 0 or gap > MAX_CONTINUOUS_INTERVAL_SECONDS:
                flush()
            elif gap < 0.5 and previous_signature is not None and signature[1] != previous_signature[1]:
                # Conflicting floors within timestamp precision are data quality
                # conflicts, not normal calibration transitions.
                flush()

        interval = FIRST_INTERVAL_SECONDS
        if previous_time is not None and current:
            gap = timestamp - previous_time
            if 0 < gap <= MAX_CONTINUOUS_INTERVAL_SECONDS:
                interval = max(1.0, round(gap, 3))
        current.append([*signature, interval])
        previous_time = timestamp
        previous_signature = signature

    flush()
    return sequences


def save_baseline(sequences: list[np.ndarray], output: Path,
                  shrinkage: float, safety_factor: float) -> None:
    calibration_count = sum(sequence.shape[0] for sequence in sequences)
    if calibration_count < 50:
        raise ValueError(f"at least 50 continuous normal rows are required, got {calibration_count}")
    center, covariance_inv, raw_threshold, point_scores, window_scores = fit_baseline(
        sequences, shrinkage, safety_factor
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    np.savez(
        output,
        center=center,
        covariance_inv=covariance_inv,
        raw_threshold=np.asarray(raw_threshold),
        schema_version=np.asarray(FEATURE_SCHEMA),
        calibration_count=np.asarray(calibration_count),
        score_mode=np.asarray("p95"),
        window_size=np.asarray(WINDOW_SIZE),
        max_gap_seconds=np.asarray(MAX_CONTINUOUS_INTERVAL_SECONDS),
    )
    print(f"normal_rows={calibration_count}")
    print(f"continuous_sessions={len(sequences)}")
    print(f"five_sample_windows={len(window_scores)}")
    print(f"raw_threshold={raw_threshold:.6f}")
    print(f"normal_window_max={np.max(window_scores):.6f}")
    print(f"point_score_max={np.max(point_scores):.6f}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, help="log file; stdin is used when omitted")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--device-id")
    parser.add_argument("--shrinkage", type=float, default=0.35)
    parser.add_argument("--safety-factor", type=float, default=1.35)
    args = parser.parse_args()

    lines = args.input.read_text(encoding="utf-8", errors="replace").splitlines() \
        if args.input else sys.stdin.readlines()
    sequences = parse_sequences(lines, args.device_id)
    save_baseline(sequences, args.output, args.shrinkage, args.safety_factor)


if __name__ == "__main__":
    main()
