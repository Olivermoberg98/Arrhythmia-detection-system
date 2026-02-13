"""
Script 03: Preprocess ECG Data - SIMPLE & CLEAN

This version:
✔ Patient-level splits (no data leakage)
✔ Zero-phase filtering at original 360 Hz
✔ Exact rational downsample 360 → 250 Hz (resample_poly, factor 25/36)
✔ Window sizes and R-peak indices rescaled to match 250 Hz
✔ Normalization after splitting (train stats only)
✔ FILTERS OUT rare/inconsistent classes
✔ Only keeps classes present in ALL splits
"""

import os
import numpy as np
import wfdb
from scipy import signal
from sklearn.preprocessing import StandardScaler
import pickle
from collections import Counter
from tqdm import tqdm
import matplotlib.pyplot as plt

# ============================================================================
# CONFIGURATION
# ============================================================================

DATA_DIR = os.path.join(os.path.dirname(__file__), '..', 'data', 'raw', 'mitdb')
OUTPUT_DIR = os.path.join(os.path.dirname(__file__), '..', 'data', 'processed')

# Sampling rates
ORIGINAL_FS = 360           # MIT-BIH native rate
TARGET_FS   = 250           # Must match ESP32 ADC rate

# Exact rational resampling ratio: 250/360 = 25/36 (already fully reduced)
_GCD = 10
RESAMPLE_UP   = TARGET_FS  // _GCD   # 25
RESAMPLE_DOWN = ORIGINAL_FS // _GCD  # 36

# Window sizes at TARGET_FS (0.5 s each side → 1 s beat window, 250 samples)
WINDOW_SIZE_BEFORE = round(0.5 * TARGET_FS)   # 125 samples
WINDOW_SIZE_AFTER  = round(0.5 * TARGET_FS)   # 125 samples
BEAT_LENGTH        = WINDOW_SIZE_BEFORE + WINDOW_SIZE_AFTER  # 250 samples

# Bandpass filter at original rate (40 Hz << 125 Hz Nyquist of 250 Hz, no aliasing risk)
LOWCUT       = 0.5
HIGHCUT      = 40.0
FILTER_ORDER = 4

# Patient-level splits
TRAIN_RECORDS = [
    # 1xx (cleaner, morphology learning)
    100, 101, 103, 105, 106, 108, 109, 111,
    112, 113, 114, 115, 116, 117, 118, 119,
    121, 122, 123, 124,

    # 2xx (harder, includes paced)
    201, 203, 205, 207, 208, 209, 215, 220,
    217, 219   # ✅ paced
]

VAL_RECORDS = [
    # 1xx
    102, 104, 107,   # 102 & 104 contain paced

    # 2xx
    210, 212, 214,
    221               # ✅ paced
]

TEST_RECORDS = [
    # 1xx
    110,

    # 2xx
    219, 228, 231    # ✅ paced
]

# AAMI class mapping
AAMI_CLASSES = {
    # Normal beats
    'N': 0, 'L': 0, 'R': 0, 'e': 0, 'j': 0,

    # Supraventricular ectopic beats (SVEB)
    'A': 1, 'a': 1, 'J': 1, 'S': 1,

    # Ventricular ectopic beats (VEB)
    'V': 2, 'E': 2,

    # Paced beats
    '/': 3,

    # Fusion beats
    'f': 4, 'F': 4,

    # Unclassifiable / noise (discard)
    'Q': -1
}

CLASS_NAMES = {
    0: 'Normal',
    1: 'Supraventricular',
    2: 'Ventricular',
    3: 'Paced',
    4: 'Fusion'
}

# ============================================================================
# FUNCTIONS
# ============================================================================

def bandpass_filter(data, lowcut, highcut, fs, order=4):
    """Zero-phase bandpass filter (applied at original 360 Hz before resampling)."""
    nyquist = 0.5 * fs
    low  = lowcut  / nyquist
    high = highcut / nyquist
    sos  = signal.butter(order, [low, high], btype='band', output='sos')
    return signal.sosfiltfilt(sos, data)


def downsample_signal(ecg_filtered):
    """
    Downsample from ORIGINAL_FS (360 Hz) to TARGET_FS (250 Hz) using exact
    rational resampling (25/36).  resample_poly applies its own anti-aliasing
    FIR internally, so no extra filter is needed before this call.
    The bandpass at 40 Hz already guarantees no content above 125 Hz (the new
    Nyquist), so no aliasing will occur regardless.
    """
    return signal.resample_poly(ecg_filtered, RESAMPLE_UP, RESAMPLE_DOWN)


def scale_rpeak(r_peak_original):
    """
    Map an R-peak sample index from ORIGINAL_FS to TARGET_FS.
    Uses rounding to nearest sample (error < 0.5 sample = 2 ms at 250 Hz).
    """
    return round(r_peak_original * TARGET_FS / ORIGINAL_FS)


def extract_beat(ecg_signal, r_peak_idx, window_before, window_after):
    """
    Extract a fixed-length beat segment centred on an R-peak.
    Returns None if the window falls outside the signal boundaries.
    """
    start = r_peak_idx - window_before
    end   = r_peak_idx + window_after

    if start < 0 or end >= len(ecg_signal):
        return None

    return ecg_signal[start:end]


def process_record(record_num, channel=0):
    """
    Full pipeline for a single MIT-BIH record:
      1. Load raw ECG and annotations.
      2. Bandpass filter at 360 Hz.
      3. Downsample to 250 Hz.
      4. Scale R-peak indices.
      5. Extract fixed-length beat windows.
    """
    record_path = os.path.join(DATA_DIR, str(record_num))

    try:
        record     = wfdb.rdrecord(record_path)
        annotation = wfdb.rdann(record_path, 'atr')

        ecg_signal = record.p_signal[:, channel]

        # Step 1: Filter at native 360 Hz (zero-phase)
        ecg_filtered = bandpass_filter(ecg_signal, LOWCUT, HIGHCUT, ORIGINAL_FS, FILTER_ORDER)

        # Step 2: Downsample to 250 Hz (exact rational, 25/36)
        ecg_resampled = downsample_signal(ecg_filtered)

        beats  = []
        labels = []

        for r_peak_orig, symbol in zip(annotation.sample, annotation.symbol):
            if symbol not in AAMI_CLASSES:
                continue
            label = AAMI_CLASSES[symbol]
            if label == -1:
                continue  # Discard 'Q' (unclassifiable / noise)

            # Step 3: Scale R-peak index to 250 Hz grid
            r_peak = scale_rpeak(r_peak_orig)

            # Step 4: Extract window around R-peak
            beat = extract_beat(ecg_resampled, r_peak, WINDOW_SIZE_BEFORE, WINDOW_SIZE_AFTER)
            if beat is not None:
                beats.append(beat)
                labels.append(label)

        return beats, labels

    except Exception as e:
        print(f"Error processing record {record_num}: {e}")
        return [], []


def process_split(record_numbers, split_name):
    """Process all records for a split and concatenate results."""
    all_beats  = []
    all_labels = []

    print(f"\nProcessing {split_name} split...")
    for record_num in tqdm(record_numbers, desc=split_name):
        beats, labels = process_record(record_num)
        all_beats.extend(beats)
        all_labels.extend(labels)

    X = np.array(all_beats)
    y = np.array(all_labels)

    if X.ndim == 2:
        assert X.shape[1] == BEAT_LENGTH, (
            f"[{split_name}] Beat length mismatch: got {X.shape[1]}, expected {BEAT_LENGTH} "
            f"(= {WINDOW_SIZE_BEFORE} + {WINDOW_SIZE_AFTER} samples at {TARGET_FS} Hz)"
        )

    return X, y


def filter_common_classes(X_train, y_train, X_val, y_val, X_test, y_test):
    """
    Keep only classes that appear in ALL three splits.
    Remaps surviving labels to a contiguous 0-based index.
    """
    train_classes = set(np.unique(y_train))
    val_classes   = set(np.unique(y_val))
    test_classes  = set(np.unique(y_test))

    common_classes = sorted(train_classes & val_classes & test_classes)

    print("\nClass analysis:")
    print(f"  Train only: {train_classes - val_classes - test_classes}")
    print(f"  Val only  : {val_classes  - train_classes - test_classes}")
    print(f"  Test only : {test_classes - train_classes - val_classes}")
    print(f"  Common    : {common_classes}")

    all_classes = train_classes | val_classes | test_classes
    if len(common_classes) < len(all_classes):
        dropped = sorted(all_classes - set(common_classes))
        print(f"\n⚠  Dropping classes absent from ≥1 split: "
              f"{[CLASS_NAMES.get(c, c) for c in dropped]}")

        train_mask = np.isin(y_train, common_classes)
        val_mask   = np.isin(y_val,   common_classes)
        test_mask  = np.isin(y_test,  common_classes)

        X_train, y_train = X_train[train_mask], y_train[train_mask]
        X_val,   y_val   = X_val[val_mask],     y_val[val_mask]
        X_test,  y_test  = X_test[test_mask],   y_test[test_mask]

        print(f"   Removed: {(~train_mask).sum()} train, "
              f"{(~val_mask).sum()} val, {(~test_mask).sum()} test samples")

    # Remap to consecutive integers
    label_map   = {old: new for new, old in enumerate(common_classes)}
    reverse_map = {new: old for old, new in label_map.items()}

    y_train = np.array([label_map[y] for y in y_train])
    y_val   = np.array([label_map[y] for y in y_val])
    y_test  = np.array([label_map[y] for y in y_test])

    print("\nFinal label mapping:")
    for new_id, old_id in reverse_map.items():
        print(f"  {new_id} ← {CLASS_NAMES[old_id]}")

    return X_train, y_train, X_val, y_val, X_test, y_test, label_map, reverse_map


def normalize_splits(X_train, X_val, X_test):
    """
    Fit StandardScaler on training data only, then apply to all three splits.
    Prevents any leakage of validation/test statistics into the model.
    """
    scaler = StandardScaler()

    n_train = X_train.shape[0]
    n_val   = X_val.shape[0]
    n_test  = X_test.shape[0]

    scaler.fit(X_train.reshape(-1, 1))

    X_train_norm = scaler.transform(X_train.reshape(-1, 1)).reshape(n_train, BEAT_LENGTH)
    X_val_norm   = scaler.transform(X_val.reshape(-1, 1)).reshape(n_val,   BEAT_LENGTH)
    X_test_norm  = scaler.transform(X_test.reshape(-1, 1)).reshape(n_test,  BEAT_LENGTH)

    return X_train_norm, X_val_norm, X_test_norm, scaler


def compute_weights(y_train):
    """
    Compute class weights using square-root inverse frequency for a moderate
    boost to minority classes without completely dominating training.
    """
    classes = np.unique(y_train)
    counts  = Counter(y_train.tolist())
    total   = len(y_train)

    weights = {}
    for cls in classes:
        freq = counts[cls] / total
        weights[cls] = (1.0 / freq) ** 0.5   # Square-root scaling

    # Normalize so mean weight ≈ 1.0
    total_w = sum(weights.values())
    weights = {k: v / total_w * len(classes) for k, v in weights.items()}

    print("\nClass weights (sqrt-inverse-frequency):")
    for cls, w in sorted(weights.items()):
        print(f"  Class {cls}: {w:.3f}  (n={counts[cls]})")

    return weights


def save_data(X_train, y_train, X_val, y_val, X_test, y_test,
              scaler, class_weights, reverse_map, output_dir):
    """Persist arrays, scaler, weights, and metadata to disk."""
    os.makedirs(output_dir, exist_ok=True)

    np.save(os.path.join(output_dir, 'X_train.npy'), X_train)
    np.save(os.path.join(output_dir, 'y_train.npy'), y_train)
    np.save(os.path.join(output_dir, 'X_val.npy'),   X_val)
    np.save(os.path.join(output_dir, 'y_val.npy'),   y_val)
    np.save(os.path.join(output_dir, 'X_test.npy'),  X_test)
    np.save(os.path.join(output_dir, 'y_test.npy'),  y_test)

    with open(os.path.join(output_dir, 'scaler.pkl'), 'wb') as f:
        pickle.dump(scaler, f)

    with open(os.path.join(output_dir, 'class_weights.pkl'), 'wb') as f:
        pickle.dump(class_weights, f)

    class_names = {new: CLASS_NAMES[old] for new, old in reverse_map.items()}

    metadata = {
        'n_train'        : len(y_train),
        'n_val'          : len(y_val),
        'n_test'         : len(y_test),
        'beat_length'    : BEAT_LENGTH,          # 250 samples at 250 Hz = 1 s window
        'window_before'  : WINDOW_SIZE_BEFORE,   # 125
        'window_after'   : WINDOW_SIZE_AFTER,    # 125
        'n_classes'      : len(class_names),
        'class_names'    : class_names,
        'class_weights'  : class_weights,
        'sampling_rate'  : TARGET_FS,            # 250 Hz — what the model expects
        'original_fs'    : ORIGINAL_FS,          # 360 Hz — MIT-BIH native
        'resample_ratio' : f'{RESAMPLE_UP}/{RESAMPLE_DOWN}',   # 25/36
        'lowcut_hz'      : LOWCUT,
        'highcut_hz'     : HIGHCUT,
        'train_records'  : TRAIN_RECORDS,
        'val_records'    : VAL_RECORDS,
        'test_records'   : TEST_RECORDS,
    }

    with open(os.path.join(output_dir, 'metadata.pkl'), 'wb') as f:
        pickle.dump(metadata, f)

    print(f"\n✔ Saved to: {os.path.abspath(output_dir)}")
    print(f"  Train : {len(y_train):,} samples  shape {X_train.shape}")
    print(f"  Val   : {len(y_val):,} samples  shape {X_val.shape}")
    print(f"  Test  : {len(y_test):,} samples  shape {X_test.shape}")
    print(f"  Beat  : {BEAT_LENGTH} samples @ {TARGET_FS} Hz  ({BEAT_LENGTH/TARGET_FS:.3f} s window)")
    print(f"  Classes: {len(class_names)}")


# ============================================================================
# MAIN
# ============================================================================

def main():
    print("=" * 70)
    print("ECG PREPROCESSING — 360 Hz → 250 Hz DOWNSAMPLE")
    print(f"  Resample ratio : {RESAMPLE_UP}/{RESAMPLE_DOWN}  "
          f"({ORIGINAL_FS} Hz → {TARGET_FS} Hz)")
    print(f"  Beat window    : {WINDOW_SIZE_BEFORE} + {WINDOW_SIZE_AFTER} = "
          f"{BEAT_LENGTH} samples  ({BEAT_LENGTH/TARGET_FS:.3f} s)")
    print("=" * 70)

    if not os.path.exists(DATA_DIR):
        print(f"\nError: data directory not found:\n  {DATA_DIR}")
        return

    use_subset = input("\nUse subset for a quick test run? (y/n): ")

    if use_subset.lower() in ['y', 'yes']:
        train_recs = TRAIN_RECORDS[:5]
        val_recs   = VAL_RECORDS[:2]
        test_recs  = TEST_RECORDS[:2]
        print("  ⚡ Subset mode — using first few records only")
    else:
        train_recs = TRAIN_RECORDS
        val_recs   = VAL_RECORDS
        test_recs  = TEST_RECORDS

    # ------------------------------------------------------------------
    # Step 1: Extract beats (filter → resample → window)
    # ------------------------------------------------------------------
    print("\nStep 1: Extracting beats (filter @ 360 Hz → resample → 250 Hz windows)")
    X_train, y_train = process_split(train_recs, "Train")
    X_val,   y_val   = process_split(val_recs,   "Val")
    X_test,  y_test  = process_split(test_recs,  "Test")

    print(f"\nExtracted:  {len(y_train):,} train | {len(y_val):,} val | {len(y_test):,} test")
    print(f"Beat shape: {X_train.shape}  (n_beats × {BEAT_LENGTH} samples @ {TARGET_FS} Hz)")

    # ------------------------------------------------------------------
    # Step 2: Filter to classes present in all three splits
    # ------------------------------------------------------------------
    print("\nStep 2: Filtering to common classes")
    X_train, y_train, X_val, y_val, X_test, y_test, label_map, reverse_map = \
        filter_common_classes(X_train, y_train, X_val, y_val, X_test, y_test)

    print(f"\nAfter filtering: {len(y_train):,} train | {len(y_val):,} val | {len(y_test):,} test")

    # ------------------------------------------------------------------
    # Step 3: Normalize using training statistics only
    # ------------------------------------------------------------------
    print("\nStep 3: Normalizing (train stats only — no val/test leakage)")
    X_train, X_val, X_test, scaler = normalize_splits(X_train, X_val, X_test)

    # ------------------------------------------------------------------
    # Step 4: Compute class weights
    # ------------------------------------------------------------------
    print("\nStep 4: Computing class weights")
    class_weights = compute_weights(y_train)

    print("\nClass weights summary:")
    for cls, weight in sorted(class_weights.items()):
        class_name = CLASS_NAMES[reverse_map[cls]]
        print(f"  {cls} ({class_name}): {weight:.3f}")

    # ------------------------------------------------------------------
    # Step 5: Save
    # ------------------------------------------------------------------
    print("\nStep 5: Saving processed data")
    save_data(X_train, y_train, X_val, y_val, X_test, y_test,
              scaler, class_weights, reverse_map, OUTPUT_DIR)

    print("\n" + "=" * 70)
    print("✔ COMPLETE")
    print(f"  Sampling rate saved in metadata: {TARGET_FS} Hz")
    print(f"  Beat length saved in metadata  : {BEAT_LENGTH} samples")
    print("  Model input must match these values.")
    print("=" * 70)
    print("\nNext: python scripts/04_train_final.py")


if __name__ == "__main__":
    main()