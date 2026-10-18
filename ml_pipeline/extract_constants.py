#!/usr/bin/env python3
"""
STEP 2 — Scaler / threshold extraction into the app's model_metadata.json.

Usage
-----
    python extract_constants.py \
        --meta        data/processed/metadata.pkl \
        --ecg_scaler  data/processed/ecg_scaler.pkl \
        --rr_scaler   data/processed/rr_scaler.pkl \
        --results     models/trained/results_XXXXXX.pkl \
        --out         src/ml/model_metadata.json

Output
------
  model_metadata.json — beat/RR shape, classes, thresholds and training-split
  scalers, read by the app's ArrhythmiaClassifier. "version" is the training
  run id taken from the results filename (results_ensemble_20261015_212815.pkl
  -> "ensemble_20261015_212815"); the app stores it with every session as
  modelVersion, so it must identify the run, not a fixed string (RMF-001 OA-6). Copy it into the app's
  assets/ together with the .tflite it was extracted for.
"""

import argparse
import pickle
import sys
from pathlib import Path

import numpy as np


# --------------------------------------------------------------------------- #
# Helpers
# --------------------------------------------------------------------------- #

def load_pkl(path: str):
    with open(path, "rb") as f:
        return pickle.load(f)


def normalise_class_names(raw) -> list[str]:
    """
    class_names may be stored as:
      - a list  : ["Normal", "SVEB", "VEB"]
      - a dict  : {0: "Normal", 1: "SVEB", 2: "VEB"}
    Always returns a sorted list.
    """
    if isinstance(raw, dict):
        return [raw[k] for k in sorted(raw.keys())]
    return list(raw)


# --------------------------------------------------------------------------- #
# Metadata writer
# --------------------------------------------------------------------------- #

def write_mobile_metadata(
    meta: dict,
    ecg_mean: np.ndarray,
    ecg_std: np.ndarray,
    rr_mean: np.ndarray,
    rr_std: np.ndarray,
    thresholds: list[float],
    version: str,
    out: Path,
) -> None:
    """Generate JSON metadata for Android app."""
    import json
    
    class_names = normalise_class_names(meta.get("class_names", ["Normal", "SVEB", "VEB"]))
    
    # Color mapping
    colors = {
        "Normal": "#4CAF50",           # Green
        "Supraventricular": "#FF9800", # Orange
        "SVEB": "#FF9800",             # Orange (alias)
        "Ventricular": "#F44336",      # Red
        "VEB": "#F44336"               # Red (alias)
    }
    
    metadata = {
        "version": version,
        "beat_length": int(meta.get("beat_length", 250)),
        "n_rr_features": int(meta.get("n_rr_features", 3)),
        "sample_rate_hz": 250,
        "classes": [
            {
                "id": i,
                "name": name,
                "color": colors.get(name, "#9E9E9E")
            }
            for i, name in enumerate(class_names)
        ],
        "thresholds": [float(t) for t in thresholds],
        "ecg_scaler": {
            "mean": float(ecg_mean[0] if len(ecg_mean) == 1 else ecg_mean.mean()),
            "std": float(ecg_std[0] if len(ecg_std) == 1 else ecg_std.mean()),
            "per_position": len(ecg_mean) > 1
        },
        "rr_scaler": {
            "mean": rr_mean.tolist(),
            "std": rr_std.tolist()
        }
    }
    
    out.write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    print(f"  model_metadata.json -> {out}")

# --------------------------------------------------------------------------- #
# Main
# --------------------------------------------------------------------------- #

def main():
    parser = argparse.ArgumentParser(
        description="Generate the app's model_metadata.json from training pickles"
    )
    parser.add_argument("--meta",       required=True)
    parser.add_argument("--ecg_scaler", required=True)
    parser.add_argument("--rr_scaler",  required=True)
    parser.add_argument("--results",    required=True,
                        help="results_XXXXXX.pkl containing thresholds")
    parser.add_argument("--out",        default="src/ml/model_metadata.json")
    parser.add_argument("--version",    default=None,
                        help="model version; defaults to the run id in the --results filename")
    args = parser.parse_args()

    # ── Load ─────────────────────────────────────────────────────────────── #
    meta       = load_pkl(args.meta)
    ecg_scaler = load_pkl(args.ecg_scaler)
    rr_scaler  = load_pkl(args.rr_scaler)
    results    = load_pkl(args.results)
    version    = args.version or Path(args.results).stem.removeprefix("results_")

    # ── Extract scaler arrays ─────────────────────────────────────────────── #
    ecg_mean = np.asarray(ecg_scaler.mean_,  dtype=np.float32).ravel()
    ecg_std  = np.asarray(ecg_scaler.scale_, dtype=np.float32).ravel()
    rr_mean  = np.asarray(rr_scaler.mean_,   dtype=np.float32).ravel()
    rr_std   = np.asarray(rr_scaler.scale_,  dtype=np.float32).ravel()

    # ── Extract thresholds ────────────────────────────────────────────────── #
    thresholds = None
    for key in ("thresholds", "optimal_thresholds", "class_thresholds",
                "decision_thresholds"):
        if key in results:
            thresholds = list(results[key])
            print(f"  Found thresholds under key '{key}': {thresholds}")
            break

    if thresholds is None:
        print("WARNING: Could not find thresholds in results pkl.")
        print("  Keys present:", list(results.keys()))
        print("  Using hardcoded defaults [0.234, 0.05, 0.05]")
        thresholds = [0.234, 0.05, 0.05]

    # ── Normalise metadata ────────────────────────────────────────────────── #
    beat_len    = int(meta.get("beat_length",   250))
    n_rr        = int(meta.get("n_rr_features", 3))
    class_names = normalise_class_names(
        meta.get("class_names", ["Normal", "SVEB", "VEB"])
    )

    ecg_is_global = (len(ecg_mean) == 1)

    # ── Sanity print ─────────────────────────────────────────────────────── #
    print("\n── Extracted constants summary ──────────────────────────────")
    print(f"  version           : {version}")
    print(f"  beat_length       : {beat_len}")
    print(f"  n_rr_features     : {n_rr}")
    print(f"  class_names       : {class_names}")
    print(f"  thresholds        : {thresholds}")
    print(f"  ecg scaler mode   : {'GLOBAL (length 1)' if ecg_is_global else f'per-position (length {len(ecg_mean)})'}")
    if ecg_is_global:
        print(f"  ECG_MEAN          : {ecg_mean[0]:.6f}")
        print(f"  ECG_STD           : {ecg_std[0]:.6f}")
    else:
        print(f"  ecg_mean range    : [{ecg_mean.min():.4f}, {ecg_mean.max():.4f}]")
        print(f"  ecg_std  range    : [{ecg_std.min():.4f}, {ecg_std.max():.4f}]")
    print(f"  rr_mean           : {rr_mean.tolist()}")
    print(f"  rr_std            : {rr_std.tolist()}")
    print("─────────────────────────────────────────────────────────────\n")

    # ── Validate ─────────────────────────────────────────────────────────── #
    if not ecg_is_global and len(ecg_mean) != beat_len:
        sys.exit(
            f"ERROR: ecg_scaler.mean_ has length {len(ecg_mean)} "
            f"but beat_length={beat_len}.\n"
            f"  Expected either length 1 (global scaler) or length {beat_len} "
            f"(per-position scaler)."
        )
    if len(rr_mean) != n_rr:
        sys.exit(
            f"ERROR: rr_scaler.mean_ has length {len(rr_mean)} "
            f"but n_rr_features={n_rr}"
        )
    if len(thresholds) != len(class_names):
        sys.exit(
            f"ERROR: {len(thresholds)} thresholds but {len(class_names)} classes"
        )

    # ── Write metadata ────────────────────────────────────────────────────── #
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    print("Generating model_metadata.json for Android...")
    write_mobile_metadata(
        meta, ecg_mean, ecg_std, rr_mean, rr_std, thresholds, version, out_path
    )

    print("\nAll done ✓")
    print(f"  Copy {out_path.name} + the matching .tflite into ecg_monitor_app/app/src/main/assets/")


if __name__ == "__main__":
    main()