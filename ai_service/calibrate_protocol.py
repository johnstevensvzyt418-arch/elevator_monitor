"""Create an MNK protocol baseline from normal elevator history TSV data."""

from __future__ import annotations

import argparse
import csv
from datetime import datetime
from pathlib import Path

import numpy as np

from protocol_baseline import FEATURE_SCHEMA, aggregate_scores, temporal_vectors


DOOR_VALUES = {"00": 0.0, "01": 1.0, "02": 2.0, "03": 3.0}
DIRECTION_VALUES = {"00": 0.0, "01": 1.0, "02": 2.0}
WINDOW_SIZE = 5
FIRST_INTERVAL_SECONDS = 1.0
MAX_CONTINUOUS_INTERVAL_SECONDS = 10.0


def parse_floor(value: str) -> float:
    try:
        return float(int(value))
    except (TypeError, ValueError):
        return 0.0


def parse_timestamp_seconds(value: str) -> float | None:
    text = (value or "").strip()
    if not text:
        return None
    try:
        timestamp = float(text)
    except ValueError:
        try:
            timestamp = datetime.fromisoformat(text.replace("Z", "+00:00")).timestamp()
        except ValueError:
            return None
    return timestamp / 1000.0 if abs(timestamp) > 100_000_000_000 else timestamp


def load_normal_sequences(path: Path) -> list[np.ndarray]:
    sequences: list[np.ndarray] = []
    current: list[list[float]] = []
    previous_timestamp: float | None = None
    previous_signature: tuple[float, ...] | None = None

    def flush() -> None:
        nonlocal current
        if len(current) >= WINDOW_SIZE:
            sequences.append(np.asarray(current, dtype=np.float64))
        current = []

    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        for row in csv.reader(handle, delimiter="\t"):
            if len(row) < 8:
                continue
            timestamp_text, current_floor, target_floor, direction, _speed, door, status, alarm = row[:8]
            if status != "00" or alarm.strip():
                flush()
                previous_timestamp = None
                previous_signature = None
                continue
            floor = parse_floor(current_floor)
            target = parse_floor(target_floor)
            if target <= 0:
                target = floor
            timestamp = parse_timestamp_seconds(timestamp_text)
            if timestamp is None:
                flush()
                previous_timestamp = None
                previous_signature = None
                continue

            signature = (
                DOOR_VALUES.get(door, 0.0),
                floor,
                target,
                DIRECTION_VALUES.get(direction, 0.0),
            )
            if timestamp == previous_timestamp and signature == previous_signature:
                continue

            interval_seconds = FIRST_INTERVAL_SECONDS
            if previous_timestamp is not None:
                gap = timestamp - previous_timestamp
                same_time_floor_conflict = (
                    gap == 0
                    and previous_signature is not None
                    and floor != previous_signature[1]
                )
                if gap < 0 or gap > MAX_CONTINUOUS_INTERVAL_SECONDS or same_time_floor_conflict:
                    flush()
                elif gap > 0:
                    interval_seconds = gap

            current.append([*signature, interval_seconds])
            previous_timestamp = timestamp
            previous_signature = signature

    flush()
    total_rows = sum(sequence.shape[0] for sequence in sequences)
    if total_rows < 50:
        raise ValueError(f"at least 50 continuous normal rows are required, got {total_rows}")
    return sequences


def fit_baseline(sequences: list[np.ndarray], shrinkage: float, safety_factor: float):
    vectors = np.concatenate([temporal_vectors(values) for values in sequences], axis=0)
    center = np.median(vectors, axis=0)
    centered = vectors - center
    covariance = np.cov(centered, rowvar=False)
    diagonal = np.diag(np.diag(covariance))
    scale = max(float(np.trace(covariance) / covariance.shape[0]), 1e-6)
    regularized = (
        (1.0 - shrinkage) * covariance
        + shrinkage * diagonal
        + np.eye(covariance.shape[0]) * scale * 1e-3
    )
    covariance_inv = np.linalg.pinv(regularized)

    point_diff = vectors - center
    point_scores = np.einsum(
        "ti,ij,tj->t", point_diff, covariance_inv, point_diff, optimize=True
    )
    window_scores = []
    for values in sequences:
        for end in range(WINDOW_SIZE, values.shape[0] + 1):
            local_vectors = temporal_vectors(values[end - WINDOW_SIZE:end])
            local_diff = local_vectors - center
            local_scores = np.einsum(
                "ti,ij,tj->t", local_diff, covariance_inv, local_diff, optimize=True
            )
            window_scores.append(aggregate_scores(local_scores, "p95"))

    if len(window_scores) < 10:
        raise ValueError(f"at least 10 five-sample windows are required, got {len(window_scores)}")

    p99 = float(np.percentile(window_scores, 99))
    median = float(np.median(window_scores))
    mad = float(np.median(np.abs(np.asarray(window_scores) - median)))
    raw_threshold = max(p99 * safety_factor, median + 8.0 * mad, 1.0)
    return center, covariance_inv, raw_threshold, point_scores, np.asarray(window_scores)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--shrinkage", type=float, default=0.35)
    parser.add_argument("--safety-factor", type=float, default=1.35)
    args = parser.parse_args()

    sequences = load_normal_sequences(args.input)
    calibration_count = sum(sequence.shape[0] for sequence in sequences)
    center, covariance_inv, raw_threshold, point_scores, window_scores = fit_baseline(
        sequences, args.shrinkage, args.safety_factor
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    np.savez(
        args.output,
        center=center,
        covariance_inv=covariance_inv,
        raw_threshold=np.asarray(raw_threshold),
        schema_version=np.asarray(FEATURE_SCHEMA),
        calibration_count=np.asarray(calibration_count),
        score_mode=np.asarray("p95"),
        window_size=np.asarray(WINDOW_SIZE),
        max_gap_seconds=np.asarray(MAX_CONTINUOUS_INTERVAL_SECONDS),
    )
    print(f"schema={FEATURE_SCHEMA}")
    print(f"normal_rows={calibration_count}")
    print(f"continuous_sessions={len(sequences)}")
    print(f"window_size={WINDOW_SIZE}")
    print(f"raw_threshold={raw_threshold:.6f}")
    print(f"normal_window_p50={np.percentile(window_scores, 50):.6f}")
    print(f"normal_window_p95={np.percentile(window_scores, 95):.6f}")
    print(f"normal_window_max={np.max(window_scores):.6f}")
    print(f"covariance_condition={np.linalg.cond(covariance_inv):.2f}")
    print(f"point_score_max={np.max(point_scores):.6f}")


if __name__ == "__main__":
    main()
