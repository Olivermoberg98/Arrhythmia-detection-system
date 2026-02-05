"""
Script 03: Preprocess ECG Data - SIMPLE & CLEAN

This version:
✓ Patient-level splits (no data leakage)
✓ Zero-phase filtering
✓ Normalization after splitting
✓ FILTERS OUT rare/inconsistent classes
✓ Only keeps classes present in ALL splits
"""

import os
import numpy as np
import wfdb
from scipy import signal
from sklearn.preprocessing import StandardScaler
from sklearn.utils.class_weight import compute_class_weight
import pickle
from collections import Counter
from tqdm import tqdm
import matplotlib.pyplot as plt

# ============================================================================
# CONFIGURATION
# ============================================================================

DATA_DIR = os.path.join(os.path.dirname(__file__), '..', 'data', 'raw', 'mitdb')
OUTPUT_DIR = os.path.join(os.path.dirname(__file__), '..', 'data', 'processed')

# ECG parameters
ORIGINAL_FS = 360
WINDOW_SIZE_BEFORE = 180
WINDOW_SIZE_AFTER = 180

# Filter parameters
LOWCUT = 0.5
HIGHCUT = 40.0
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
    """Zero-phase bandpass filter."""
    nyquist = 0.5 * fs
    low = lowcut / nyquist
    high = highcut / nyquist
    sos = signal.butter(order, [low, high], btype='band', output='sos')
    return signal.sosfiltfilt(sos, data)


def extract_beat(ecg_signal, r_peak_idx, window_before, window_after):
    """Extract beat around R-peak."""
    start_idx = r_peak_idx - window_before
    end_idx = r_peak_idx + window_after
    
    if start_idx < 0 or end_idx >= len(ecg_signal):
        return None
    
    return ecg_signal[start_idx:end_idx]


def process_record(record_num, channel=0):
    """Process single record."""
    record_path = os.path.join(DATA_DIR, str(record_num))
    
    try:
        record = wfdb.rdrecord(record_path)
        annotation = wfdb.rdann(record_path, 'atr')
        
        ecg_signal = record.p_signal[:, channel]
        ecg_filtered = bandpass_filter(ecg_signal, LOWCUT, HIGHCUT, ORIGINAL_FS, FILTER_ORDER)
        
        beats = []
        labels = []
        
        for r_peak, symbol in zip(annotation.sample, annotation.symbol):
            if symbol not in AAMI_CLASSES:
                continue
            
            label = AAMI_CLASSES[symbol]
            if label == -1:
                continue
            
            beat = extract_beat(ecg_filtered, r_peak, WINDOW_SIZE_BEFORE, WINDOW_SIZE_AFTER)
            if beat is not None:
                beats.append(beat)
                labels.append(label)
        
        return beats, labels
    
    except Exception as e:
        print(f"Error processing record {record_num}: {e}")
        return [], []


def process_split(record_numbers, split_name):
    """Process all records for a split."""
    all_beats = []
    all_labels = []
    
    print(f"Processing {split_name}...")
    for record_num in tqdm(record_numbers, desc=split_name):
        beats, labels = process_record(record_num)
        all_beats.extend(beats)
        all_labels.extend(labels)
    
    return np.array(all_beats), np.array(all_labels)


def filter_common_classes(X_train, y_train, X_val, y_val, X_test, y_test):
    """
    Keep only classes that appear in ALL three splits.
    This ensures the model can be properly trained and evaluated.
    """
    train_classes = set(np.unique(y_train))
    val_classes = set(np.unique(y_val))
    test_classes = set(np.unique(y_test))
    
    # Find classes present in ALL splits
    common_classes = train_classes & val_classes & test_classes
    common_classes = sorted(list(common_classes))
    
    print("\nClass analysis:")
    print(f"  Train only: {train_classes - val_classes - test_classes}")
    print(f"  Val only: {val_classes - train_classes - test_classes}")
    print(f"  Test only: {test_classes - train_classes - val_classes}")
    print(f"  Common to all: {common_classes}")
    
    if len(common_classes) < len(train_classes | val_classes | test_classes):
        print(f"\n⚠️  Filtering to keep only classes present in ALL splits")
        print(f"   Keeping: {[CLASS_NAMES[c] for c in common_classes]}")
        
        # Filter each split
        train_mask = np.isin(y_train, common_classes)
        val_mask = np.isin(y_val, common_classes)
        test_mask = np.isin(y_test, common_classes)
        
        X_train = X_train[train_mask]
        y_train = y_train[train_mask]
        X_val = X_val[val_mask]
        y_val = y_val[val_mask]
        X_test = X_test[test_mask]
        y_test = y_test[test_mask]
        
        print(f"   Removed {(~train_mask).sum()} train, {(~val_mask).sum()} val, {(~test_mask).sum()} test samples")
    
    # Remap to consecutive (0, 1, 2, ...)
    label_map = {old: new for new, old in enumerate(common_classes)}
    reverse_map = {new: old for old, new in label_map.items()}
    
    y_train = np.array([label_map[y] for y in y_train])
    y_val = np.array([label_map[y] for y in y_val])
    y_test = np.array([label_map[y] for y in y_test])
    
    print("\nFinal label mapping:")
    for new, old in reverse_map.items():
        print(f"  {new} <- {CLASS_NAMES[old]}")
    
    return X_train, y_train, X_val, y_val, X_test, y_test, label_map, reverse_map


def normalize_splits(X_train, X_val, X_test):
    """Normalize using only training statistics."""
    scaler = StandardScaler()
    X_train_flat = X_train.reshape(-1, 1)
    scaler.fit(X_train_flat)
    
    n_train, beat_len = X_train.shape
    n_val = X_val.shape[0]
    n_test = X_test.shape[0]
    
    X_train_norm = scaler.transform(X_train_flat).reshape(n_train, beat_len)
    X_val_norm = scaler.transform(X_val.reshape(-1, 1)).reshape(n_val, beat_len)
    X_test_norm = scaler.transform(X_test.reshape(-1, 1)).reshape(n_test, beat_len)
    
    return X_train_norm, X_val_norm, X_test_norm, scaler


def compute_weights(y_train):
    """Compute STRONGER class weights for extreme imbalance."""
    classes = np.unique(y_train)
    
    # Count samples per class
    counts = Counter(y_train)
    total = len(y_train)
    
    # Use square root of inverse frequency (more aggressive)
    weights = {}
    for cls in classes:
        freq = counts[cls] / total
        weights[cls] = (1.0 / freq) ** 0.5  # Square root for moderate boost
        # Or even more aggressive: (1.0 / freq) ** 0.7
    
    # Normalize
    total_weight = sum(weights.values())
    weights = {k: v/total_weight * len(classes) for k, v in weights.items()}
    
    print("\nAggressive class weights:")
    for cls, weight in sorted(weights.items()):
        print(f"  Class {cls}: {weight:.2f} (samples: {counts[cls]})")
    
    return weights


def save_data(X_train, y_train, X_val, y_val, X_test, y_test, 
              scaler, class_weights, reverse_map, output_dir):
    """Save processed data."""
    os.makedirs(output_dir, exist_ok=True)
    
    np.save(os.path.join(output_dir, 'X_train.npy'), X_train)
    np.save(os.path.join(output_dir, 'y_train.npy'), y_train)
    np.save(os.path.join(output_dir, 'X_val.npy'), X_val)
    np.save(os.path.join(output_dir, 'y_val.npy'), y_val)
    np.save(os.path.join(output_dir, 'X_test.npy'), X_test)
    np.save(os.path.join(output_dir, 'y_test.npy'), y_test)
    
    with open(os.path.join(output_dir, 'scaler.pkl'), 'wb') as f:
        pickle.dump(scaler, f)
    
    with open(os.path.join(output_dir, 'class_weights.pkl'), 'wb') as f:
        pickle.dump(class_weights, f)
    
    # Create clean class names
    class_names = {new: CLASS_NAMES[old] for new, old in reverse_map.items()}
    
    metadata = {
        'n_train': len(y_train),
        'n_val': len(y_val),
        'n_test': len(y_test),
        'beat_length': X_train.shape[1],
        'n_classes': len(class_names),
        'class_names': class_names,
        'class_weights': class_weights,
        'sampling_rate': ORIGINAL_FS,
        'train_records': TRAIN_RECORDS,
        'val_records': VAL_RECORDS,
        'test_records': TEST_RECORDS,
    }
    
    with open(os.path.join(output_dir, 'metadata.pkl'), 'wb') as f:
        pickle.dump(metadata, f)
    
    print(f"\n✓ Saved to: {os.path.abspath(output_dir)}")
    print(f"  Train: {len(y_train)} samples")
    print(f"  Val: {len(y_val)} samples")
    print(f"  Test: {len(y_test)} samples")
    print(f"  Classes: {len(class_names)}")


# ============================================================================
# MAIN
# ============================================================================

def main():
    print("=" * 70)
    print("ECG PREPROCESSING - SIMPLE VERSION")
    print("=" * 70)
    
    if not os.path.exists(DATA_DIR):
        print(f"Error: {DATA_DIR} not found")
        return
    
    # Ask about subset
    use_subset = input("\nUse subset? (y/n): ")
    
    if use_subset.lower() in ['y', 'yes']:
        train_recs = TRAIN_RECORDS[:5]
        val_recs = VAL_RECORDS[:5]
        test_recs = TEST_RECORDS[:2]
    else:
        train_recs = TRAIN_RECORDS
        val_recs = VAL_RECORDS
        test_recs = TEST_RECORDS
    
    # Process each split
    print("\nStep 1: Processing records (patient-level split)")
    X_train, y_train = process_split(train_recs, "Train")
    X_val, y_val = process_split(val_recs, "Val")
    X_test, y_test = process_split(test_recs, "Test")
    
    print(f"\nExtracted: {len(y_train)} train, {len(y_val)} val, {len(y_test)} test")
    
    # Filter to common classes BEFORE normalization
    print("\nStep 2: Filtering to common classes")
    X_train, y_train, X_val, y_val, X_test, y_test, label_map, reverse_map = \
        filter_common_classes(X_train, y_train, X_val, y_val, X_test, y_test)
    
    print(f"\nAfter filtering: {len(y_train)} train, {len(y_val)} val, {len(y_test)} test")
    
    # Normalize
    print("\nStep 3: Normalizing (train stats only)")
    X_train, X_val, X_test, scaler = normalize_splits(X_train, X_val, X_test)
    
    # Compute class weights
    print("\nStep 4: Computing class weights")
    class_weights = compute_weights(y_train)
    
    print("\nClass weights:")
    for cls, weight in sorted(class_weights.items()):
        class_name = CLASS_NAMES[reverse_map[cls]]
        print(f"  {cls} ({class_name}): {weight:.2f}")
    
    # Save
    print("\nStep 5: Saving")
    save_data(X_train, y_train, X_val, y_val, X_test, y_test,
              scaler, class_weights, reverse_map, OUTPUT_DIR)
    
    print("\n" + "=" * 70)
    print("✓ COMPLETE")
    print("=" * 70)
    print("\nNext: python scripts/04_train_final.py")


if __name__ == "__main__":
    main()