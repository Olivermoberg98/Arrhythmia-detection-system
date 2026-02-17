#!/usr/bin/env python3
"""
STEP 1 — Convert dual-input .keras model to INT8-quantized TFLite.

Usage
-----
    python convert_model.py \
        --model   models/trained/model_XXXXXX.keras \
        --meta    data/processed/metadata.pkl \
        --ecg     data/processed/X_val.npy \
        --rr      data/processed/RR_val.npy \
        --out     src/ml/arrhythmia_model.tflite

If you don't have pre-split .npy files, you can generate them from your
processed dataset. See the bottom of this file for a helper snippet.

INT8 quantization notes
-----------------------
  Full-integer (weights + activations) is used because:
    - The ESP32's Xtensa LX6 has no SIMD FPU; INT8 convolutions run ~4×
      faster and fit more easily in IRAM.
    - TFLite Micro's optimised kernel set targets INT8.
  Input/output tensors are kept as FLOAT32 so the C code feeds normalised
  floats without any extra quantize/dequantize bookkeeping.
  Set QUANTIZE_IO = True if you want INT8 I/O (saves a tiny bit of flash).
"""

import argparse
import pickle
import sys
from pathlib import Path

import numpy as np
import tensorflow as tf

# --------------------------------------------------------------------------- #
QUANTIZE_IO      = False   # Keep False unless you also update the C inference code
N_CALIB_SAMPLES  = 500     # Calibration samples for range estimation (200-1000 typical)
RANDOM_SEED      = 42
# --------------------------------------------------------------------------- #


def load_pickle(path: str) -> dict:
    with open(path, "rb") as f:
        return pickle.load(f)


def make_representative_dataset(X_ecg: np.ndarray, X_rr: np.ndarray, n: int):
    """
    Generator yielding batches for TFLite's representative dataset API.
    Each call yields a list of numpy arrays, one per model input, with
    shape [1, input_dim]. The converter calls this ~100-200 times to
    determine activation ranges for every layer.
    """
    rng = np.random.default_rng(RANDOM_SEED)
    idx = rng.choice(len(X_ecg), size=min(n, len(X_ecg)), replace=False)
    ecg_cal = X_ecg[idx].astype(np.float32)
    rr_cal  = X_rr[idx].astype(np.float32)

    def generator():
        for i in range(len(ecg_cal)):
            yield [ecg_cal[i:i+1], rr_cal[i:i+1]]   # (1,250) and (1,3)

    return generator


def convert_float32(model: tf.keras.Model, out_path: Path) -> None:
    """Float32 reference — useful for accuracy comparison on-device."""
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    data = converter.convert()
    out_path.write_bytes(data)
    print(f"  [F32] Written → {out_path}  ({len(data):,} bytes)")


def convert_int8(model: tf.keras.Model,
                 X_ecg: np.ndarray, X_rr: np.ndarray,
                 out_path: Path) -> None:
    """INT8 quantized model — production build for ESP32."""
    converter = tf.lite.TFLiteConverter.from_keras_model(model)

    converter.optimizations             = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]

    if QUANTIZE_IO:
        converter.inference_input_type  = tf.int8
        converter.inference_output_type = tf.int8
    else:
        # Keep I/O float32; all internal ops and weights are INT8.
        converter.inference_input_type  = tf.float32
        converter.inference_output_type = tf.float32

    converter.representative_dataset = make_representative_dataset(
        X_ecg, X_rr, N_CALIB_SAMPLES
    )

    data = converter.convert()
    out_path.write_bytes(data)
    print(f"  [INT8] Written → {out_path}  ({len(data):,} bytes)")


def inspect_and_verify(tflite_path: Path,
                       X_ecg: np.ndarray, X_rr: np.ndarray,
                       n_samples: int = 5) -> None:
    """
    Print tensor details and run a handful of samples to catch shape /
    type mismatches before generating the C header.
    """
    interp = tf.lite.Interpreter(model_path=str(tflite_path))
    interp.allocate_tensors()

    in_details  = interp.get_input_details()
    out_details = interp.get_output_details()

    print(f"\n  [{tflite_path.name}] tensor details:")
    for i, d in enumerate(in_details):
        q = d.get("quantization", (0.0, 0))
        print(f"    Input  {i}: shape={d['shape']}  dtype={d['dtype'].__name__}"
              f"  scale={q[0]:.8f}  zero_pt={q[1]}")
    for i, d in enumerate(out_details):
        q = d.get("quantization", (0.0, 0))
        print(f"    Output {i}: shape={d['shape']}  dtype={d['dtype'].__name__}"
              f"  scale={q[0]:.8f}  zero_pt={q[1]}")

    # Map inputs by shape
    ecg_idx = None
    rr_idx  = None
    for d in in_details:
        if d["shape"][1] == X_ecg.shape[1]:
            ecg_idx = d["index"]
        elif d["shape"][1] == X_rr.shape[1]:
            rr_idx = d["index"]

    if ecg_idx is None or rr_idx is None:
        print("  WARNING: Could not match input tensors by shape — check model input order.")
        return

    print(f"\n  Running {n_samples} sample(s)...")
    for i in range(min(n_samples, len(X_ecg))):
        interp.set_tensor(ecg_idx, X_ecg[i:i+1].astype(np.float32))
        interp.set_tensor(rr_idx,  X_rr[i:i+1].astype(np.float32))
        interp.invoke()
        scores = interp.get_tensor(out_details[0]["index"])[0]
        print(f"    [{i}]  scores={np.round(scores.tolist(), 4)}"
              f"  → class {np.argmax(scores)}")

    print("  Verification passed ✓")


def print_next_steps(tflite_path: Path) -> None:
    print("\n" + "=" * 62)
    print("DONE.  Next steps:")
    print()
    print("  STEP 2 — Generate C array (run in your project root):")
    print(f"    xxd -i {tflite_path} | sed 's/unsigned/const unsigned/' \\")
    print(f"        > src/ml/model_data.h")
    print()
    print("  OR use the provided extract_constants.py which does both")
    print("  xxd conversion AND scaler extraction in one shot.")
    print("=" * 62)


# --------------------------------------------------------------------------- #
# Helper: generate calibration .npy files from processed data
# Uncomment and adapt if you don't have pre-split arrays.
# --------------------------------------------------------------------------- #
# import pandas as pd
# ds = np.load("data/processed/dataset.npz")
# np.save("data/processed/X_ecg_val.npy", ds["X_ecg"][:2000])
# np.save("data/processed/X_rr_val.npy",  ds["X_rr"][:2000])
# --------------------------------------------------------------------------- #


def main():
    parser = argparse.ArgumentParser(
        description="Convert dual-input Keras ECG model → INT8 TFLite"
    )
    parser.add_argument("--model", required=True,
                        help="Path to .keras model file")
    parser.add_argument("--meta",  required=True,
                        help="Path to metadata.pkl")
    parser.add_argument("--ecg",   required=True,
                        help="Calibration ECG array .npy  [N, 250]")
    parser.add_argument("--rr",    required=True,
                        help="Calibration RR array  .npy  [N, 3]")
    parser.add_argument("--out",   default="src/ml/arrhythmia_model.tflite",
                        help="Output path for INT8 .tflite")
    args = parser.parse_args()

    # ── Load ────────────────────────────────────────────────────────────── #
    print(f"\nLoading Keras model: {args.model}")
    # compile=False skips deserializing the optimizer and loss function.
    # This is safe for conversion — we only need the forward-pass graph.
    # It also bypasses errors from custom loss classes like SparseLabelSmoothingCE
    # that aren't registered in the current Python environment.
    model = tf.keras.models.load_model(args.model, compile=False)
    model.summary()

    print(f"\nLoading metadata: {args.meta}")
    meta = load_pickle(args.meta)
    beat_len   = meta.get("beat_length",   250)
    n_rr_feats = meta.get("n_rr_features", 3)
    classes    = meta.get("class_names",   ["Normal", "SVEB", "VEB"])
    print(f"  beat_length   = {beat_len}")
    print(f"  n_rr_features = {n_rr_feats}")
    print(f"  class_names   = {classes}")

    print(f"\nLoading calibration arrays:")
    X_ecg = np.load(args.ecg)
    X_rr  = np.load(args.rr)
    print(f"  X_ecg : {X_ecg.shape}  dtype={X_ecg.dtype}")
    print(f"  X_rr  : {X_rr.shape}  dtype={X_rr.dtype}")

    # ── Validate ─────────────────────────────────────────────────────────── #
    if X_ecg.shape[1] != beat_len:
        sys.exit(f"ERROR: X_ecg has {X_ecg.shape[1]} samples but beat_length={beat_len}")
    if X_rr.shape[1] != n_rr_feats:
        sys.exit(f"ERROR: X_rr has {X_rr.shape[1]} features but n_rr_features={n_rr_feats}")

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    # ── Convert ──────────────────────────────────────────────────────────── #
    f32_path = out_path.with_name(out_path.stem + "_f32.tflite")
    print("\nConverting → Float32 reference...")
    convert_float32(model, f32_path)

    print("Converting → INT8 quantized...")
    convert_int8(model, X_ecg, X_rr, out_path)

    # ── Verify ───────────────────────────────────────────────────────────── #
    print("\nVerifying INT8 model:")
    inspect_and_verify(out_path, X_ecg, X_rr)

    print_next_steps(out_path)


if __name__ == "__main__":
    main()