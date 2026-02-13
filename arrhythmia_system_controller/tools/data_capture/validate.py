"""
validate.py - Preprocess and validate captured ECG data against the TFLite model.

This script:
1. Loads raw ECG CSV from capture.py
2. Applies the same signal processing as the ESP32 (filters + normalization)
3. Runs inference with your trained model
4. Compares results to ground truth labels (if provided)

Usage:
    python validate.py --data captured_data/raw_ecg.csv --model arrhythmia_model.tflite

Requirements:
    pip install numpy scipy tensorflow pandas matplotlib
"""

import argparse
import numpy as np
import pandas as pd
from pathlib import Path
from scipy import signal
import tensorflow as tf

# ---- Match ESP32 config.h constants ----
SAMPLE_RATE_HZ = 250
ML_WINDOW_SAMPLES = 1250  # 5 seconds
ML_STRIDE_SAMPLES = 625   # 2.5 seconds (50% overlap)

# Filter coefficients (from SignalProcessor.h)
# These are direct-form II biquad coefficients for fs=250 Hz
HP_B = np.array([0.99115, -1.98230, 0.99115])
HP_A = np.array([1.0, -1.98222, 0.98232])

LP_B = np.array([0.14413, 0.28826, 0.14413])
LP_A = np.array([1.0, -0.67736, 0.25390])

NOTCH_B = np.array([1.0, -0.61800, 1.0])
NOTCH_A = np.array([1.0, -0.59484, 0.92598])

CLASS_NAMES = ["Normal", "AFib", "Bradycardia", "Tachycardia", "Other"]

# ---- Signal processing matching ESP32 ----

def apply_filters(ecg_raw: np.ndarray) -> np.ndarray:
    """Apply the same 3-stage biquad filter chain as the ESP32."""
    x = ecg_raw.astype(np.float32)
    
    # High-pass (0.5 Hz)
    x = signal.lfilter(HP_B, HP_A, x)
    
    # Low-pass (40 Hz)
    x = signal.lfilter(LP_B, LP_A, x)
    
    # Notch (50 Hz)
    x = signal.lfilter(NOTCH_B, NOTCH_A, x)
    
    return x

def normalize_window(window: np.ndarray) -> np.ndarray:
    """Z-score normalization (matches SignalProcessor::getMLWindow)."""
    mean = np.mean(window)
    std = np.std(window)
    if std < 1e-6:
        std = 1e-6
    return (window - mean) / std

def extract_windows(ecg_filtered: np.ndarray) -> list:
    """
    Sliding window extraction with stride.
    Returns list of normalized windows ready for inference.
    """
    windows = []
    start = 0
    while start + ML_WINDOW_SAMPLES <= len(ecg_filtered):
        window_raw = ecg_filtered[start : start + ML_WINDOW_SAMPLES]
        window_norm = normalize_window(window_raw)
        windows.append(window_norm)
        start += ML_STRIDE_SAMPLES
    return windows

# ---- Inference ----

def run_inference(model_path: Path, windows: list) -> list:
    """Run TFLite inference on all windows."""
    interpreter = tf.lite.Interpreter(model_path=str(model_path))
    interpreter.allocate_tensors()
    
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()
    
    print(f"Model input shape:  {input_details[0]['shape']}")
    print(f"Model output shape: {output_details[0]['shape']}")
    
    results = []
    for i, window in enumerate(windows):
        # Reshape to [1, ML_WINDOW_SAMPLES]
        input_data = window.reshape(1, -1).astype(np.float32)
        interpreter.set_tensor(input_details[0]['index'], input_data)
        interpreter.invoke()
        
        output = interpreter.get_tensor(output_details[0]['index'])[0]
        class_id = np.argmax(output)
        confidence = output[class_id]
        
        results.append({
            'window_idx': i,
            'class_id': class_id,
            'class_name': CLASS_NAMES[class_id],
            'confidence': confidence,
            'scores': output
        })
    
    return results

# ---- Main ----

def main():
    parser = argparse.ArgumentParser(
        description="Validate ECG data preprocessing and model inference."
    )
    parser.add_argument("--data", type=str, required=True,
                        help="Path to raw ECG CSV from capture.py")
    parser.add_argument("--model", type=str, required=True,
                        help="Path to .tflite model file")
    parser.add_argument("--plot", action="store_true",
                        help="Plot filtered ECG and predictions")
    
    args = parser.parse_args()
    
    data_path = Path(args.data)
    model_path = Path(args.model)
    
    if not data_path.exists():
        print(f"Error: Data file not found: {data_path}")
        return
    
    if not model_path.exists():
        print(f"Error: Model file not found: {model_path}")
        return
    
    # ---- Load data ----
    print(f"Loading data from {data_path} ...")
    df = pd.read_csv(data_path)
    
    # Extract ECG raw column
    if 'ecg_raw' not in df.columns:
        print("Error: CSV must have 'ecg_raw' column (expected: timestamp_ms,ecg_raw,ecg_filtered)")
        return
    
    ecg_raw = df['ecg_raw'].values
    
    # Remove lead-off samples (sentinel value -32768)
    ecg_raw = ecg_raw[ecg_raw != -32768]
    
    print(f"  {len(ecg_raw)} samples ({len(ecg_raw)/SAMPLE_RATE_HZ:.1f} seconds)")
    
    # ---- Apply filters ----
    print("Applying signal processing (HP → LP → Notch) ...")
    ecg_filtered = apply_filters(ecg_raw)
    
    # ---- Extract windows ----
    print(f"Extracting {ML_WINDOW_SAMPLES}-sample windows with stride {ML_STRIDE_SAMPLES} ...")
    windows = extract_windows(ecg_filtered)
    print(f"  {len(windows)} windows extracted")
    
    if len(windows) == 0:
        print("Error: Not enough data for even one window.")
        return
    
    # ---- Run inference ----
    print(f"\nRunning inference with {model_path.name} ...")
    results = run_inference(model_path, windows)
    
    # ---- Display results ----
    print("\n" + "="*60)
    print("INFERENCE RESULTS")
    print("="*60)
    
    for r in results:
        t_start = r['window_idx'] * ML_STRIDE_SAMPLES / SAMPLE_RATE_HZ
        print(f"Window {r['window_idx']:2d} @ {t_start:6.1f}s: "
              f"{r['class_name']:15s} (conf={r['confidence']:.2f})")
    
    # ---- Summary stats ----
    class_counts = {}
    for r in results:
        name = r['class_name']
        class_counts[name] = class_counts.get(name, 0) + 1
    
    print("\n" + "-"*60)
    print("Summary:")
    for name, count in sorted(class_counts.items(), key=lambda x: -x[1]):
        pct = 100 * count / len(results)
        print(f"  {name:15s}: {count:3d} windows ({pct:5.1f}%)")
    
    # ---- Plot (optional) ----
    if args.plot:
        try:
            import matplotlib.pyplot as plt
            
            fig, axs = plt.subplots(2, 1, figsize=(12, 6), sharex=True)
            
            time_s = np.arange(len(ecg_filtered)) / SAMPLE_RATE_HZ
            axs[0].plot(time_s, ecg_filtered, linewidth=0.5)
            axs[0].set_ylabel("ECG (filtered)")
            axs[0].grid(True, alpha=0.3)
            
            # Overlay predictions as colored spans
            for r in results:
                start_idx = r['window_idx'] * ML_STRIDE_SAMPLES
                end_idx = start_idx + ML_WINDOW_SAMPLES
                t0 = start_idx / SAMPLE_RATE_HZ
                t1 = end_idx / SAMPLE_RATE_HZ
                
                color_map = {
                    "Normal": "green",
                    "AFib": "red",
                    "Bradycardia": "orange",
                    "Tachycardia": "purple",
                    "Other": "gray"
                }
                color = color_map.get(r['class_name'], "blue")
                axs[1].axvspan(t0, t1, alpha=0.3, color=color,
                               label=r['class_name'] if r['window_idx'] == 0 else "")
            
            axs[1].set_xlabel("Time (s)")
            axs[1].set_ylabel("Prediction")
            axs[1].set_ylim([0, 1])
            axs[1].legend(loc="upper right")
            axs[1].grid(True, alpha=0.3)
            
            plt.tight_layout()
            plt.show()
        
        except ImportError:
            print("\nPlotting requires matplotlib. Install: pip install matplotlib")

if __name__ == "__main__":
    main()
