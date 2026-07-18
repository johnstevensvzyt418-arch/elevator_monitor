"""Create an MNK protocol baseline from normal elevator history TSV data."""

from __future__ import annotations

import argparse
import csv
from pathlib import Path

import numpy as np

from protocol_baseline import FEATURE_SCHEMA, aggregate_scores, temporal_vectors


DOOR_VALUES = {"00": 0.0, "01": 1.0, "02": 2.0, "03": 3.0}
DIRECTION_VALUES = {"00": 0.0, "01": 1.0, "02": 2.0}


def parse_floor(value: str) -> float:
    try:
        return float(int(value))
    except (TypeError, ValueError):
        return 0.0


def load_normal_features(path: Path) -> np.ndarray:
    features: list[list[float]] = []
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        for row in csv.reader(handle, delimiter="\t"):
            if len(row) < 8:
                continue
            _, current_floor, target_floor, direction, speed, door, status, alarm = row[:8]
            if status != "00" or alarm.strip():
                continue
            floor = parse_floor(current_floor)
            target = parse_floor(target_floor)
            if target <= 0:
                target = floor
            try:
                speed_value = max(0.0, float(speed or 0.0))
            except ValueError:
                speed_value = 0.0
            features.append([
                DOOR_VALUES.get(door, 0.0),
                floor,
                target,
                DIRECTION_VALUES.get(direction, 0.0),
                speed_value,
            ])
    values = np.asarray(features, dtype=np.float64)
    if values.shape[0] < 50:
        raise ValueError(f"at least 50 normal rows are required, got {values.shape[0]}")
    return values


def fit_baseline(values: np.ndarray, shrinkage: float, safety_factor: float):
    vectors = temporal_vectors(values)
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
    for end in range(10, values.shape[0] + 1):
        start = max(0, end - 20)
        local_vectors = temporal_vectors(values[start:end])
        local_diff = local_vectors - center
        local_scores = np.einsum(
            "ti,ij,tj->t", local_diff, covariance_inv, local_diff, optimize=True
        )
        window_scores.append(aggregate_scores(local_scores, "p95"))

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

    values = load_normal_features(args.input)
    center, covariance_inv, raw_threshold, point_scores, window_scores = fit_baseline(
        values, args.shrinkage, args.safety_factor
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    np.savez(
        args.output,
        center=center,
        covariance_inv=covariance_inv,
        raw_threshold=np.asarray(raw_threshold),
        schema_version=np.asarray(FEATURE_SCHEMA),
        calibration_count=np.asarray(values.shape[0]),
        score_mode=np.asarray("p95"),
    )
    print(f"schema={FEATURE_SCHEMA}")
    print(f"normal_rows={values.shape[0]}")
    print(f"raw_threshold={raw_threshold:.6f}")
    print(f"normal_window_p50={np.percentile(window_scores, 50):.6f}")
    print(f"normal_window_p95={np.percentile(window_scores, 95):.6f}")
    print(f"normal_window_max={np.max(window_scores):.6f}")
    print(f"covariance_condition={np.linalg.cond(covariance_inv):.2f}")
    print(f"point_score_max={np.max(point_scores):.6f}")


if __name__ == "__main__":
    main()
