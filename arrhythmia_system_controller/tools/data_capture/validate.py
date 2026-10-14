"""
validate.py - Run the app's classifier offline on ECG captured by capture.py.

This script mirrors the Android app's inference path (ArrhythmiaClassifier.kt):
1. Loads the firmware-filtered ECG from a capture.py CSV
2. Finds R-peaks and cuts 128-sample beat windows (96 pre + 32 post R-peak)
3. Computes the 3 RR features [pre_rr_norm, post_rr_norm, ratio_rr]
4. Converts ADC counts to mV, normalises with model_metadata.json, and runs
   the dual-input TFLite model per beat

Usage:
    python validate.py --data captured_data/raw_ecg.csv \
        --model ../../../ml_pipeline/src/ml/arrhythmia_model.tflite \
        --metadata ../../../ml_pipeline/src/ml/model_metadata.json

Requirements:
    pip install numpy scipy tensorflow pandas matplotlib
"""

import argparse
import json
import numpy as np
import pandas as pd
from pathlib import Path
from scipy import signal
import tensorflow as tf

# ---- Match config.h / model_metadata.json ----
SAMPLE_RATE_HZ = 250
BEAT_PRE_SAMPLES = 96
BEAT_POST_SAMPLES = 32
N_RR_FEATURES = 3
RR_MEAN_WINDOW = 8  # config.h MLCfg::RR_MEAN_WINDOW

# Must match ArrhythmiaClassifier.ADC_COUNTS_TO_MV (board-gain dependent)
ADC_COUNTS_TO_MV = (3100.0 / 4096.0) / 1100.0

# R-peak search: 250 ms refractory period caps detection at 240 bpm
MIN_RR_SAMPLES = int(0.25 * SAMPLE_RATE_HZ)

# ---- Beat extraction ----

def detect_r_peaks(ecg: np.ndarray) -> np.ndarray:
    """Offline R-peak detection; an approximation of the firmware's Pan-Tompkins."""
    prominence = 0.5 * np.percentile(np.abs(ecg - np.median(ecg)), 99)
    peaks, _ = signal.find_peaks(ecg, distance=MIN_RR_SAMPLES, prominence=prominence)
    return peaks

def compute_rr_features(r_peaks: np.ndarray) -> np.ndarray:
    """Same definition as preprocess.py::compute_rr_features and the firmware.

    Mean RR is the running mean of the last RR_MEAN_WINDOW intervals up to pre_rr.
    First/last beats have no pre/post interval: NaN rows, dropped by the caller.
    """
    n = len(r_peaks)
    features = np.full((n, N_RR_FEATURES), np.nan, dtype=np.float32)
    rr = np.diff(r_peaks.astype(np.float64))
    for i in range(1, n - 1):
        pre_rr, post_rr = rr[i - 1], rr[i]
        mean_rr = max(np.mean(rr[max(0, i - RR_MEAN_WINDOW):i]), 1e-3)
        features[i, 0] = pre_rr / mean_rr
        features[i, 1] = post_rr / mean_rr
        features[i, 2] = pre_rr / post_rr if post_rr > 0 else 1.0
    return features

def extract_beats(ecg: np.ndarray, r_peaks: np.ndarray, rr_features: np.ndarray):
    """Returns (beats, rr, r_peak_idx) for beats whose window fits in the signal."""
    beats, rr, kept = [], [], []
    for i, r in enumerate(r_peaks):
        start, end = r - BEAT_PRE_SAMPLES, r + BEAT_POST_SAMPLES
        if start < 0 or end > len(ecg) or np.isnan(rr_features[i, 0]):
            continue
        beats.append(ecg[start:end])
        rr.append(rr_features[i])
        kept.append(r)
    return np.array(beats, dtype=np.float32), np.array(rr, dtype=np.float32), np.array(kept)

# ---- Inference ----

def load_metadata(path: Path) -> dict:
    with open(path) as f:
        meta = json.load(f)
    assert meta["beat_length"] == BEAT_PRE_SAMPLES + BEAT_POST_SAMPLES, "beat_length mismatch"
    assert meta["n_rr_features"] == N_RR_FEATURES, "n_rr_features mismatch"
    assert meta["sample_rate_hz"] == SAMPLE_RATE_HZ, "sample_rate_hz mismatch"
    return meta

def _set_input(interpreter, detail, data: np.ndarray) -> None:
    # Quantise only if the model kept integer I/O; the app feeds float32.
    if detail["dtype"] != np.float32:
        scale, zero = detail["quantization"]
        data = np.round(data / scale + zero).astype(detail["dtype"])
    interpreter.set_tensor(detail["index"], data.reshape(detail["shape"]))

def run_inference(model_path: Path, meta: dict, beats_mv: np.ndarray, rr: np.ndarray) -> list:
    """Normalise and classify each beat exactly as ArrhythmiaClassifier.classify does."""
    interpreter = tf.lite.Interpreter(model_path=str(model_path))
    interpreter.allocate_tensors()

    # Tell the two inputs apart by their last dimension (128 vs 3).
    inputs = interpreter.get_input_details()
    ecg_in = next(d for d in inputs if d["shape"][-1] == meta["beat_length"])
    rr_in = next(d for d in inputs if d["shape"][-1] == N_RR_FEATURES)
    out = interpreter.get_output_details()[0]

    ecg_mean, ecg_std = meta["ecg_scaler"]["mean"], meta["ecg_scaler"]["std"]
    rr_mean = np.array(meta["rr_scaler"]["mean"], dtype=np.float32)
    rr_std = np.array(meta["rr_scaler"]["std"], dtype=np.float32)
    thresholds = meta["thresholds"]
    class_names = [c["name"] for c in meta["classes"]]

    results = []
    for i in range(len(beats_mv)):
        ecg_norm = (beats_mv[i] - ecg_mean) / (ecg_std + 1e-6)
        rr_norm = (rr[i] - rr_mean) / (rr_std + 1e-6)
        _set_input(interpreter, ecg_in, ecg_norm.astype(np.float32))
        _set_input(interpreter, rr_in, rr_norm.astype(np.float32))
        interpreter.invoke()

        scores = interpreter.get_tensor(out["index"])[0].astype(np.float32)
        if out["dtype"] != np.float32:
            scale, zero = out["quantization"]
            scores = (scores - zero) * scale
        class_id = int(np.argmax(scores))
        results.append({
            "beat_idx": i,
            "class_id": class_id,
            "class_name": class_names[class_id],
            "confidence": float(scores[class_id]),
            "is_valid": bool(scores[class_id] >= thresholds[class_id]),
        })
    return results

# ---- Main ----

def main():
    parser = argparse.ArgumentParser(
        description="Classify captured ECG beats with the app's TFLite model."
    )
    parser.add_argument("--data", type=str, required=True,
                        help="Path to CSV from capture.py (timestamp_ms,ecg_raw,ecg_filtered)")
    parser.add_argument("--model", type=str, required=True,
                        help="Path to .tflite model file")
    parser.add_argument("--metadata", type=str, required=True,
                        help="Path to model_metadata.json shipped with the model")
    parser.add_argument("--plot", action="store_true",
                        help="Plot filtered ECG with classified R-peaks")

    args = parser.parse_args()

    data_path = Path(args.data)
    model_path = Path(args.model)
    meta_path = Path(args.metadata)

    for p in (data_path, model_path, meta_path):
        if not p.exists():
            print(f"Error: File not found: {p}")
            return

    meta = load_metadata(meta_path)

    # ---- Load data ----
    print(f"Loading data from {data_path} ...")
    df = pd.read_csv(data_path)

    if "ecg_filtered" not in df.columns:
        print("Error: CSV must have 'ecg_filtered' column (expected: timestamp_ms,ecg_raw,ecg_filtered)")
        return

    # Firmware-filtered ADC counts: the same signal the beat windows are cut from.
    ecg = df["ecg_filtered"].values.astype(np.float32)
    print(f"  {len(ecg)} samples ({len(ecg)/SAMPLE_RATE_HZ:.1f} seconds)")

    # ---- Beats ----
    r_peaks = detect_r_peaks(ecg)
    rr_features = compute_rr_features(r_peaks)
    beats, rr, r_peaks = extract_beats(ecg, r_peaks, rr_features)
    print(f"  {len(beats)} beats extracted")

    if len(beats) == 0:
        print("Error: No complete beat windows found.")
        return

    # ---- Run inference ----
    print(f"\nRunning inference with {model_path.name} ...")
    results = run_inference(model_path, meta, beats * ADC_COUNTS_TO_MV, rr)

    # ---- Display results ----
    print("\n" + "="*60)
    print("INFERENCE RESULTS")
    print("="*60)

    for r, peak in zip(results, r_peaks):
        flag = "" if r["is_valid"] else "  (below threshold)"
        print(f"Beat {r['beat_idx']:4d} @ {peak / SAMPLE_RATE_HZ:7.2f}s: "
              f"{r['class_name']:17s} (conf={r['confidence']:.2f}){flag}")

    # ---- Summary stats ----
    class_counts = {}
    for r in results:
        name = r["class_name"]
        class_counts[name] = class_counts.get(name, 0) + 1

    print("\n" + "-"*60)
    print("Summary:")
    for name, count in sorted(class_counts.items(), key=lambda x: -x[1]):
        pct = 100 * count / len(results)
        print(f"  {name:17s}: {count:4d} beats ({pct:5.1f}%)")
    n_invalid = sum(not r["is_valid"] for r in results)
    print(f"  Below threshold  : {n_invalid:4d} beats")

    # ---- Plot (optional) ----
    if args.plot:
        try:
            import matplotlib.pyplot as plt

            fig, ax = plt.subplots(figsize=(12, 4))
            time_s = np.arange(len(ecg)) / SAMPLE_RATE_HZ
            ax.plot(time_s, ecg * ADC_COUNTS_TO_MV, linewidth=0.5, color="black")

            colors = {c["name"]: c["color"] for c in meta["classes"]}
            for r, peak in zip(results, r_peaks):
                ax.scatter(peak / SAMPLE_RATE_HZ, ecg[peak] * ADC_COUNTS_TO_MV,
                           color=colors[r["class_name"]], s=20, zorder=3)

            ax.set_xlabel("Time (s)")
            ax.set_ylabel("ECG (mV)")
            ax.grid(True, alpha=0.3)
            plt.tight_layout()
            plt.show()

        except ImportError:
            print("\nPlotting requires matplotlib. Install: pip install matplotlib")

if __name__ == "__main__":
    main()
