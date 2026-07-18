"""Protocol-aware anomaly scoring for the MNK monitoring feature schema."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import numpy as np


FEATURE_SCHEMA = "mnk-v2"
DISPLAY_THRESHOLD = 90.0


def temporal_vectors(sequence: np.ndarray) -> np.ndarray:
    """Build state + first-difference vectors from a [N, 5] sequence."""
    values = np.asarray(sequence, dtype=np.float64)
    if values.ndim != 2 or values.shape[1] != 5:
        raise ValueError(f"sequence must have shape [N, 5], got {list(values.shape)}")
    if values.shape[0] < 2:
        raise ValueError("sequence must contain at least two samples")
    if not np.isfinite(values).all():
        raise ValueError("sequence contains NaN or infinite values")

    deltas = np.diff(values, axis=0)
    return np.concatenate([values[1:], deltas], axis=1)


def aggregate_scores(scores: np.ndarray, mode: str) -> float:
    if mode == "mean":
        return float(np.mean(scores))
    if mode == "p95":
        return float(np.percentile(scores, 95))
    if mode == "max":
        return float(np.max(scores))
    raise ValueError(f"unknown score mode: {mode}")


@dataclass(frozen=True)
class BaselineMetadata:
    schema_version: str
    calibration_count: int
    raw_threshold: float
    score_mode: str


class ProtocolBaselineScorer:
    """Regularized Mahalanobis scorer calibrated on normal MNK telemetry."""

    def __init__(self, baseline_file: str | Path, score_mode: str = "p95"):
        params = np.load(str(baseline_file), allow_pickle=False)
        self.center = np.asarray(params["center"], dtype=np.float64)
        self.covariance_inv = np.asarray(params["covariance_inv"], dtype=np.float64)
        self.raw_threshold = float(np.asarray(params["raw_threshold"]).item())
        self.schema_version = str(np.asarray(params["schema_version"]).item())
        self.calibration_count = int(np.asarray(params["calibration_count"]).item())
        saved_mode = str(np.asarray(params["score_mode"]).item())
        self.score_mode = score_mode or saved_mode

        if self.schema_version != FEATURE_SCHEMA:
            raise ValueError(
                f"baseline schema {self.schema_version!r} does not match {FEATURE_SCHEMA!r}"
            )
        if self.center.shape != (10,) or self.covariance_inv.shape != (10, 10):
            raise ValueError("invalid MNK baseline dimensions")
        if not np.isfinite(self.raw_threshold) or self.raw_threshold <= 0:
            raise ValueError("raw_threshold must be positive and finite")

    @property
    def metadata(self) -> BaselineMetadata:
        return BaselineMetadata(
            schema_version=self.schema_version,
            calibration_count=self.calibration_count,
            raw_threshold=self.raw_threshold,
            score_mode=self.score_mode,
        )

    def score(self, sequence: np.ndarray) -> tuple[float, float]:
        vectors = temporal_vectors(sequence)
        diff = vectors - self.center
        timestep_scores = np.einsum(
            "ti,ij,tj->t", diff, self.covariance_inv, diff, optimize=True
        )
        raw_score = aggregate_scores(timestep_scores, self.score_mode)
        normalized_score = raw_score / self.raw_threshold * DISPLAY_THRESHOLD
        return float(normalized_score), float(raw_score)
