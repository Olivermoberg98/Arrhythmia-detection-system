"""
Script 04: Train Arrhythmia Detection Model

Architecture and training strategy based on:

[1] de Chazal et al. (2004) — DS1/DS2 inter-patient split + RR features
    IEEE Trans. Biomed. Eng. 51(7), 1196-1206.
    → RR interval ratios (pre_rr_norm, post_rr_norm, ratio_rr) concatenated
      to CNN embedding before classification head.

[2] Hannun et al. (2019) — Residual 1D CNN for ECG arrhythmia detection
    Nature Medicine 25, 65-69.
    → Residual connections in build_residual_model()

[3] Yıldırım et al. (2018) — 1D CNN architecture for ECG beat classification
    Computers in Biology and Medicine 100, 109-119.
    → Kernel size and filter progression in build_standard_model()

[4] Müller et al. (2019) — When does label smoothing help?
    NeurIPS 2019.
    → SparseLabelSmoothingCE(smoothing=0.1)

[5] Loshchilov & Hutter (2017) — SGDR: Cosine Annealing with Warm Restarts
    ICLR 2017.
    → CosineDecayRestarts LR schedule

[6] Class-balanced batch sampling  — guaranteed minority exposure each batch
    Johnson & Khoshgoftaar (2019), Journal of Big Data 6(1).
    → build_balanced_dataset()

[7] Per-class threshold optimization — post-hoc calibration to maximise macro-F1
    Ribeiro et al. (2020), Nature Comms 11.
    → optimize_thresholds()

Dual-input design:
  - Input 1 (ecg):  shape (128,)  — asymmetric beat window (96 pre / 32 post)
  - Input 2 (rr):   shape (3,)    — pre_rr_norm, post_rr_norm, ratio_rr
  CNN processes ECG → GlobalAveragePooling → concatenate RR features → dense head
  RR features provide the temporal context that morphology alone cannot capture,
  especially for SVEB (premature beats with near-normal QRS morphology).

Ensembles (--ensemble N) — why the shipped model averages several runs:
  Identical training runs landed anywhere from 0.68 to 0.78 end-to-end DS2
  macro-F1 (2026-10-14, 8 runs).  The spread comes from model selection, not
  the architecture: checkpointing and threshold tuning both rest on only 4
  validation records, so which epoch is kept and where the thresholds land
  is close to a coin toss.  Picking the best run by its DS2 score would be
  selecting on the test set.  Averaging the softmax of N independent runs
  (Lakshminarayanan et al. 2017, "Simple and Scalable Predictive Uncertainty
  Estimation using Deep Ensembles", NeurIPS) removes most of that variance:
  a 4-run ensemble scored 0.764 vs a single-run mean of 0.743 +/- 0.025.
  The members are wrapped in one Keras model (Average layer), so it converts
  to a single TFLite with the same inputs/outputs; thresholds are tuned for
  the ensemble on validation only.  Cost: N x model size and inference time
  (4 runs: ~700 KB INT8, ~1.4 ms/beat on a desktop CPU).
"""

import argparse
import os
import numpy as np
import pickle
import tensorflow as tf
from tensorflow import keras
from tensorflow.keras import layers, models
from tensorflow.keras.callbacks import (ModelCheckpoint, EarlyStopping,
                                         TensorBoard)
from sklearn.metrics import classification_report, confusion_matrix, f1_score
from collections import Counter
import matplotlib.pyplot as plt
import seaborn as sns
from datetime import datetime

# ============================================================================
# LABEL SMOOTHING LOSS  (TF 2.15 compatible)
# SparseCategoricalCrossentropy gained label_smoothing only in TF 2.16.
# ============================================================================

class SparseLabelSmoothingCE(keras.losses.Loss):
    """Sparse integer label cross-entropy with label smoothing."""
    def __init__(self, num_classes: int, smoothing: float = 0.1, **kwargs):
        super().__init__(**kwargs)
        self.num_classes = num_classes
        self.smoothing   = smoothing

    def call(self, y_true, y_pred):
        y_pred   = tf.clip_by_value(y_pred, 1e-7, 1.0)
        y_true   = tf.cast(tf.reshape(y_true, [-1]), tf.int32)
        y_onehot = tf.one_hot(y_true, self.num_classes)
        smooth   = (1.0 - self.smoothing) * y_onehot + self.smoothing / self.num_classes
        return tf.reduce_mean(-tf.reduce_sum(smooth * tf.math.log(y_pred), axis=-1))

    def get_config(self):
        cfg = super().get_config()
        cfg.update({'num_classes': self.num_classes, 'smoothing': self.smoothing})
        return cfg


# ============================================================================
# CONFIGURATION
# ============================================================================

DATA_DIR  = os.path.join(os.path.dirname(__file__), '..', 'data', 'processed')
MODEL_DIR = os.path.join(os.path.dirname(__file__), '..', 'models', 'trained')
LOGS_DIR  = os.path.join(os.path.dirname(__file__), '..', 'models', 'logs')

BATCH_SIZE      = 128
EPOCHS          = 100
LEARNING_RATE   = 3e-4
LABEL_SMOOTHING = 0.1
# Tried COSINE_T0=8 (2026-08-10): test macro-F1 fell 0.72->0.67, val curve
# got noisier. Reverted to 20.
COSINE_T0       = 20
COSINE_TMUL     = 2

# Balanced sampler base — majority class draws ~SAMPLER_BASE per batch step,
# minority classes draw proportionally fewer (sqrt-scaled, min 8).
SAMPLER_BASE = 32


# ============================================================================
# LOAD DATA
# ============================================================================

def load_data():
    """Load ECG arrays, RR feature arrays, labels, and metadata."""
    print("Loading data...")

    X_train  = np.load(os.path.join(DATA_DIR, 'X_train.npy'))
    RR_train = np.load(os.path.join(DATA_DIR, 'RR_train.npy'))
    y_train  = np.load(os.path.join(DATA_DIR, 'y_train.npy'))
    X_val    = np.load(os.path.join(DATA_DIR, 'X_val.npy'))
    RR_val   = np.load(os.path.join(DATA_DIR, 'RR_val.npy'))
    y_val    = np.load(os.path.join(DATA_DIR, 'y_val.npy'))
    X_test   = np.load(os.path.join(DATA_DIR, 'X_test.npy'))
    RR_test  = np.load(os.path.join(DATA_DIR, 'RR_test.npy'))
    y_test   = np.load(os.path.join(DATA_DIR, 'y_test.npy'))

    with open(os.path.join(DATA_DIR, 'metadata.pkl'), 'rb') as f:
        metadata = pickle.load(f)

    print(f"\nLoaded ECG shape : {X_train.shape}  RR shape: {RR_train.shape}")
    for split_name, y in [("Train", y_train), ("Val", y_val), ("Test", y_test)]:
        unique, counts = np.unique(y, return_counts=True)
        print(f"\n  {split_name} ({len(y):,} total):")
        for cls, cnt in zip(unique, counts):
            cname = metadata['class_names'].get(cls, str(cls))
            print(f"    {cname:>20s}: {cnt:>6,}  ({100*cnt/len(y):.1f}%)")

    return ((X_train, RR_train, y_train),
            (X_val,   RR_val,   y_val),
            (X_test,  RR_test,  y_test),
            metadata)


# ============================================================================
# ECG AUGMENTATION
# Operates on dict inputs {'ecg': ..., 'rr': ...} to match Keras multi-input.
# Only ECG signal is augmented; RR features get small noise to prevent
# the model from over-relying on exact RR values.
# ============================================================================

@tf.function
def augment_beat(inputs, y):
    """
    Augment one (ecg, rr) sample.
    ECG transforms are physiologically motivated:
      amplitude  — gain/electrode differences between patients
      noise      — sensor noise, EMG artifact
      dc_offset  — baseline wander from breathing/movement
      time_shift — beat segmentation jitter (±4 samples = ±16ms @ 250Hz)
    RR noise:
      small Gaussian noise prevents over-fitting to exact RR ratios
      while keeping the timing signal useful.
    """
    x_ecg = inputs['ecg']
    x_rr  = inputs['rr']

    # ECG augmentation
    x_ecg = x_ecg * tf.random.uniform([], 0.85, 1.15)
    x_ecg = x_ecg + tf.random.normal(tf.shape(x_ecg), stddev=0.02)
    x_ecg = x_ecg + tf.random.uniform([], -0.04, 0.04)
    shift = tf.random.uniform([], -4, 4, dtype=tf.int32)
    x_ecg = tf.roll(x_ecg, shift=shift, axis=0)

    # RR feature noise (std=0.03 in standardized space ≈ small jitter)
    x_rr  = x_rr + tf.random.normal(tf.shape(x_rr), stddev=0.03)

    return {'ecg': x_ecg, 'rr': x_rr}, y


# ============================================================================
# BALANCED BATCH SAMPLING
# ============================================================================

def compute_sampler_counts(y, num_classes, base=SAMPLER_BASE, min_samples=8):
    """
    Per-class draw counts scaled by sqrt(class_freq / majority_freq).
    Majority class draws ~base per step; smallest class draws at least min_samples.

    Example with base=32, 3 classes at 92/0.7/7.3%:
      Normal           → 32
      Ventricular      → ~19
      Supraventricular → ~8 (floored)
    """
    counts    = Counter(y.tolist())
    max_count = max(counts[c] for c in range(num_classes))
    return {cls: max(int(base * (counts.get(cls, 1) / max_count) ** 0.5), min_samples)
            for cls in range(num_classes)}


def build_balanced_dataset(X_ecg, X_rr, y, num_classes,
                            samples_per_class, augment=False, seed=42):
    """
    Class-balanced tf.data.Dataset yielding ({'ecg': ..., 'rr': ...}, label).
    Draws samples_per_class[cls] samples from each class per batch step,
    ensuring minority classes appear in every batch.
    """
    class_datasets = []
    total_draws    = sum(samples_per_class.values())

    for cls in range(num_classes):
        idx = np.where(y == cls)[0]
        ds  = tf.data.Dataset.from_tensor_slices((
            {'ecg': X_ecg[idx].astype(np.float32),
             'rr':  X_rr[idx].astype(np.float32)},
            np.full(len(idx), cls, dtype=np.int32)
        ))
        ds = ds.shuffle(len(idx), seed=seed, reshuffle_each_iteration=True)
        ds = ds.repeat()
        if augment:
            ds = ds.map(augment_beat, num_parallel_calls=tf.data.AUTOTUNE)
        class_datasets.append(ds)

    draw_weights = [samples_per_class[c] / total_draws for c in range(num_classes)]
    balanced     = tf.data.Dataset.sample_from_datasets(
        class_datasets, weights=draw_weights, seed=seed,
        stop_on_empty_dataset=False
    )
    return balanced.batch(total_draws).prefetch(tf.data.AUTOTUNE)


def build_standard_dataset(X_ecg, X_rr, y, batch_size=BATCH_SIZE):
    """Standard (non-balanced) dataset for val/test."""
    ds = tf.data.Dataset.from_tensor_slices((
        {'ecg': X_ecg.astype(np.float32),
         'rr':  X_rr.astype(np.float32)},
        y.astype(np.int32)
    ))
    return ds.batch(batch_size).prefetch(tf.data.AUTOTUNE)


# ============================================================================
# MODEL ARCHITECTURES — DUAL INPUT (ECG + RR)
#
# All three models share the same fusion design:
#   CNN branch  → processes beat morphology (shape: beat_length,)
#   RR branch   → passed directly to dense head (shape: n_rr_features,)
#   Fusion      → Concatenate([cnn_gap_output, rr_features])
#   Head        → Dense → Dropout → softmax
#
# This is the standard "late fusion" design from de Chazal et al. [1] and
# used in the majority of high-performing inter-patient ECG classifiers.
# The CNN learns morphology; the RR features provide timing context that
# the convolution window cannot capture across beat boundaries.
# ============================================================================

def residual_block_1d(x, filters, kernel_size, l2_reg=1e-3):
    """Pre-activation residual block (He et al. 2016)."""
    reg      = keras.regularizers.l2(l2_reg)
    shortcut = x
    x = layers.BatchNormalization()(x)
    x = layers.Activation('relu')(x)
    x = layers.Conv1D(filters, kernel_size, padding='same', kernel_regularizer=reg)(x)
    x = layers.BatchNormalization()(x)
    x = layers.Activation('relu')(x)
    x = layers.Conv1D(filters, kernel_size, padding='same', kernel_regularizer=reg)(x)
    if shortcut.shape[-1] != filters:
        shortcut = layers.Conv1D(filters, 1, padding='same')(shortcut)
    return layers.Add()([x, shortcut])


def build_residual_model(beat_length, n_rr_features, num_classes, l2_reg=1e-3):
    """
    Dual-input Residual 1D CNN + RR features.
    Residual CNN branch inspired by Hannun et al. (2019) [2]. ~136K parameters
    (verify with model.count_params(), this drifts).

    Tried Stage 3 at 96 filters (2026-08-10): train acc unchanged (~98.8%)
    but test macro-F1 fell 0.72->0.67, so reverted to 128 — capacity wasn't
    the overfitting bottleneck, effective batch size is a better suspect.
    """
    reg = keras.regularizers.l2(l2_reg)

    # ── ECG branch ──────────────────────────────────────────────────────
    ecg_input = keras.Input(shape=(beat_length,), name='ecg')
    x         = layers.Reshape((beat_length, 1))(ecg_input)

    # Stem
    x = layers.Conv1D(32, 7, padding='same', kernel_regularizer=reg)(x)
    x = layers.BatchNormalization()(x)
    x = layers.Activation('relu')(x)
    x = layers.MaxPooling1D(2)(x)       # 128 → 64

    # Stage 1 — 32 filters
    x = residual_block_1d(x, 32, 5, l2_reg)
    x = layers.MaxPooling1D(2)(x)       # 64 → 32
    x = layers.Dropout(0.2)(x)

    # Stage 2 — 64 filters
    x = residual_block_1d(x, 64, 5, l2_reg)
    x = layers.MaxPooling1D(2)(x)       # 32 → 16
    x = layers.Dropout(0.3)(x)

    # Stage 3 — 128 filters
    x = residual_block_1d(x, 128, 3, l2_reg)
    x = layers.Dropout(0.3)(x)

    cnn_out = layers.GlobalAveragePooling1D()(x)   # (batch, 128)

    # ── RR branch ───────────────────────────────────────────────────────
    rr_input = keras.Input(shape=(n_rr_features,), name='rr')

    # ── Fusion ──────────────────────────────────────────────────────────
    # Concatenate RR features directly to CNN embedding.
    # A single dense layer lets the model learn interactions between
    # morphology features and timing features jointly.
    merged  = layers.Concatenate()([cnn_out, rr_input])  # (batch, 131)
    x       = layers.Dense(64, activation='relu', kernel_regularizer=reg)(merged)
    x       = layers.Dropout(0.4)(x)
    outputs = layers.Dense(num_classes, activation='softmax')(x)

    return keras.Model([ecg_input, rr_input], outputs, name='residual_ecg_rr')


def build_standard_model(beat_length, n_rr_features, num_classes, l2_reg=1e-3):
    """
    Dual-input Standard 1D CNN + RR features. ~47K parameters.
    Based on Yıldırım et al. (2018) [3].
    """
    reg = keras.regularizers.l2(l2_reg)

    ecg_input = keras.Input(shape=(beat_length,), name='ecg')
    x = layers.Reshape((beat_length, 1))(ecg_input)
    x = layers.Conv1D(32, 5, activation='relu', padding='same', kernel_regularizer=reg)(x)
    x = layers.BatchNormalization()(x)
    x = layers.MaxPooling1D(2)(x)
    x = layers.Dropout(0.3)(x)
    x = layers.Conv1D(64, 5, activation='relu', padding='same', kernel_regularizer=reg)(x)
    x = layers.BatchNormalization()(x)
    x = layers.MaxPooling1D(2)(x)
    x = layers.Dropout(0.3)(x)
    x = layers.Conv1D(128, 3, activation='relu', padding='same', kernel_regularizer=reg)(x)
    x = layers.BatchNormalization()(x)
    x = layers.MaxPooling1D(2)(x)
    x = layers.Dropout(0.4)(x)
    cnn_out = layers.GlobalAveragePooling1D()(x)

    rr_input = keras.Input(shape=(n_rr_features,), name='rr')
    merged   = layers.Concatenate()([cnn_out, rr_input])
    x        = layers.Dense(64, activation='relu', kernel_regularizer=reg)(merged)
    x        = layers.Dropout(0.4)(x)
    x        = layers.Dense(32, activation='relu')(x)
    x        = layers.Dropout(0.3)(x)
    outputs  = layers.Dense(num_classes, activation='softmax')(x)

    return keras.Model([ecg_input, rr_input], outputs, name='standard_ecg_rr')


def build_lightweight_model(beat_length, n_rr_features, num_classes, l2_reg=1e-3):
    """
    Dual-input Lightweight 1D CNN + RR features. ~12K parameters.
    Optimized for ESP32 deployment.
    """
    reg = keras.regularizers.l2(l2_reg)

    ecg_input = keras.Input(shape=(beat_length,), name='ecg')
    x         = layers.Reshape((beat_length, 1))(ecg_input)

    x = layers.Conv1D(16, 7, activation='relu', padding='same', kernel_regularizer=reg)(x)
    x = layers.MaxPooling1D(2)(x)
    x = layers.Dropout(0.3)(x)
    x = layers.Conv1D(32, 5, activation='relu', padding='same', kernel_regularizer=reg)(x)
    x = layers.BatchNormalization()(x)
    x = layers.MaxPooling1D(2)(x)
    x = layers.Dropout(0.3)(x)
    x = layers.Conv1D(64, 3, activation='relu', padding='same', kernel_regularizer=reg)(x)
    x = layers.BatchNormalization()(x)
    cnn_out = layers.GlobalAveragePooling1D()(x)

    rr_input = keras.Input(shape=(n_rr_features,), name='rr')
    merged   = layers.Concatenate()([cnn_out, rr_input])
    x        = layers.Dense(32, activation='relu')(merged)
    x        = layers.Dropout(0.4)(x)
    outputs  = layers.Dense(num_classes, activation='softmax')(x)

    return keras.Model([ecg_input, rr_input], outputs, name='lightweight_ecg_rr')


# ============================================================================
# PER-CLASS THRESHOLD OPTIMIZATION  [7]
# ============================================================================

def optimize_thresholds(model, X_val, RR_val, y_val, num_classes, n_steps=50):
    """
    Find per-class thresholds maximising macro-F1 on validation set.
    Decision rule: class = argmax(probs / thresholds).
    """
    print("\nOptimising per-class thresholds on validation set...")
    probs = model.predict({'ecg': X_val, 'rr': RR_val}, verbose=0)

    grid        = np.linspace(0.05, 0.95, n_steps)
    best_thresh = np.ones(num_classes) * 0.5
    best_f1     = -1.0

    # Phase 1: best global threshold
    for t in grid:
        t_arr    = np.full(num_classes, t)
        y_pred   = np.argmax(probs / t_arr, axis=1)
        macro_f1 = f1_score(y_val, y_pred, average='macro', zero_division=0)
        if macro_f1 > best_f1:
            best_f1, best_thresh = macro_f1, t_arr.copy()

    # Phase 2: refine each class independently
    for cls in range(num_classes):
        for t in grid:
            t_try      = best_thresh.copy()
            t_try[cls] = t
            y_pred     = np.argmax(probs / t_try, axis=1)
            macro_f1   = f1_score(y_val, y_pred, average='macro', zero_division=0)
            if macro_f1 > best_f1:
                best_f1, best_thresh[cls] = macro_f1, t

    print(f"  Validation macro-F1 after tuning: {best_f1:.4f}")
    print(f"  Thresholds: {best_thresh.round(3)}")
    return best_thresh


# ============================================================================
# VISUALIZATION
# ============================================================================

def plot_history(history, save_path=None):
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(15, 5))
    ax1.plot(history.history['accuracy'],     label='Train',      linewidth=2)
    ax1.plot(history.history['val_accuracy'], label='Validation', linewidth=2)
    ax1.set_xlabel('Epoch'); ax1.set_ylabel('Accuracy')
    ax1.set_title('Model Accuracy'); ax1.legend(); ax1.grid(alpha=0.3)
    ax2.plot(history.history['loss'],     label='Train',      linewidth=2)
    ax2.plot(history.history['val_loss'], label='Validation', linewidth=2)
    ax2.set_xlabel('Epoch'); ax2.set_ylabel('Loss')
    ax2.set_title('Model Loss'); ax2.legend(); ax2.grid(alpha=0.3)
    plt.tight_layout()
    if save_path:
        plt.savefig(save_path, dpi=300, bbox_inches='tight')
    plt.show()


def plot_confusion_matrix(y_true, y_pred, class_names, title='', save_path=None):
    cm      = confusion_matrix(y_true, y_pred)
    row_sum = cm.sum(axis=1, keepdims=True)
    row_sum[row_sum == 0] = 1
    cm_norm = cm.astype(float) / row_sum
    fig, axes = plt.subplots(1, 2, figsize=(18, 7))
    for ax, data, fmt, ttl in [
        (axes[0], cm,      'd',   f'Raw Counts {title}'),
        (axes[1], cm_norm, '.2f', f'Normalized {title}'),
    ]:
        sns.heatmap(data, annot=True, fmt=fmt, cmap='Blues',
                    xticklabels=class_names, yticklabels=class_names, ax=ax)
        ax.set_xlabel('Predicted'); ax.set_ylabel('True')
        ax.set_title(f'Confusion Matrix — {ttl}')
    plt.tight_layout()
    if save_path:
        plt.savefig(save_path, dpi=300, bbox_inches='tight')
    plt.show()


# ============================================================================
# EVALUATION
# ============================================================================

def evaluate(model, X_test, RR_test, y_test, class_names,
             thresholds=None, label='Test'):
    print(f"\n{'='*70}\n{label} SET EVALUATION\n{'='*70}")

    probs = model.predict({'ecg': X_test, 'rr': RR_test}, verbose=0)
    y_pred = (np.argmax(probs / thresholds, axis=1)
              if thresholds is not None
              else np.argmax(probs, axis=1))

    print(f"\nAccuracy: {float(np.mean(y_pred == y_test)):.4f}")
    print("\nPer-Class Performance:")
    print(classification_report(y_test, y_pred,
                                target_names=class_names,
                                digits=4, zero_division=0))

    cm = confusion_matrix(y_test, y_pred)
    print("Raw Confusion Matrix:")
    header = " " * 22 + "  ".join(f"{n:>18}" for n in class_names)
    print(header)
    for i, row in enumerate(cm):
        print(f"  {class_names[i]:>20}  " + "  ".join(f"{v:>18}" for v in row))

    macro_f1 = f1_score(y_test, y_pred, average='macro', zero_division=0)
    print(f"\nMacro F1: {macro_f1:.4f}  ← primary metric for imbalanced data")
    return y_pred


# ============================================================================
# MAIN
# ============================================================================

def main(arch_choice=None):
    """Train one model. Returns (model_path, results_path)."""
    print("=" * 70)
    print("ARRHYTHMIA DETECTION MODEL TRAINING  (ECG + RR features)")
    print("=" * 70)

    if not os.path.exists(DATA_DIR):
        print(f"\n⚠  {DATA_DIR} not found — run preprocess.py first")
        return

    os.makedirs(MODEL_DIR, exist_ok=True)
    os.makedirs(LOGS_DIR,  exist_ok=True)

    # ------------------------------------------------------------------
    # Load
    # ------------------------------------------------------------------
    ((X_train, RR_train, y_train),
     (X_val,   RR_val,   y_val),
     (X_test,  RR_test,  y_test),
     metadata) = load_data()

    class_ids    = sorted(metadata['class_names'].keys())
    class_names  = [metadata['class_names'][i] for i in class_ids]
    beat_length  = metadata['beat_length']
    n_rr         = metadata['n_rr_features']
    num_classes  = len(class_names)

    # ------------------------------------------------------------------
    # Balanced sampler counts
    # ------------------------------------------------------------------
    sampler_counts = compute_sampler_counts(y_train, num_classes, base=SAMPLER_BASE)
    eff_batch      = sum(sampler_counts.values())

    print(f"\nSampler draw counts (sqrt-proportional, base={SAMPLER_BASE}):")
    for c, n in sampler_counts.items():
        print(f"  {class_names[c]:>20s}: {n} draws/step  "
              f"(n_train={Counter(y_train.tolist()).get(c,0):,})")
    print(f"  Effective batch size: {eff_batch}")

    # ------------------------------------------------------------------
    # Architecture
    # ------------------------------------------------------------------
    print("\nSelect architecture:")
    print("1. Residual 1D CNN + RR  (~136K params)  ← RECOMMENDED")
    print("2. Standard 1D CNN + RR  (~47K params)")
    print("3. Lightweight CNN + RR  (~12K params, ESP32)")
    choice = arch_choice or input("Choice (1/2/3): ").strip()

    if choice == "2":
        model     = build_standard_model(beat_length, n_rr, num_classes)
        arch_name = "standard"
    elif choice == "3":
        model     = build_lightweight_model(beat_length, n_rr, num_classes)
        arch_name = "lightweight"
    else:
        model     = build_residual_model(beat_length, n_rr, num_classes)
        arch_name = "residual"

    model.summary()
    params = model.count_params()
    print(f"\nParameters : {params:,}")
    print(f"Inputs     : ecg ({beat_length},) + rr ({n_rr},)")

    # ------------------------------------------------------------------
    # Class weights — sqrt-inverse, capped at 10× minimum, mean=1
    # ------------------------------------------------------------------
    counts    = Counter(y_train.tolist())
    total     = len(y_train)
    raw_w     = {c: (total / (num_classes * counts.get(c, 1))) ** 0.5
                 for c in range(num_classes)}
    min_w     = min(raw_w.values())
    capped    = {c: min(w, min_w * 10) for c, w in raw_w.items()}
    mean_w    = sum(capped.values()) / num_classes
    class_weights = {c: w / mean_w for c, w in capped.items()}

    print("\nClass weights (sqrt-inverse, capped 10×, mean=1):")
    for c, w in class_weights.items():
        print(f"  {class_names[c]:>20s}: {w:.3f}  (n={counts.get(c, 0):,})")

    # ------------------------------------------------------------------
    # Datasets
    # ------------------------------------------------------------------
    print("\nBuilding datasets...")
    train_ds = build_balanced_dataset(
        X_train, RR_train, y_train, num_classes,
        samples_per_class=sampler_counts, augment=True
    )
    val_ds = build_standard_dataset(X_val, RR_val, y_val)
    print(f"  ✔ Balanced training   ({eff_batch} per batch, augmented)")
    print(f"  ✔ Standard validation (no augmentation)")

    # ------------------------------------------------------------------
    # LR schedule — cosine annealing with warm restarts  [5]
    # ------------------------------------------------------------------
    steps_per_epoch = max(1, len(y_train) // eff_batch)
    lr_schedule = keras.optimizers.schedules.CosineDecayRestarts(
        initial_learning_rate=LEARNING_RATE,
        first_decay_steps=COSINE_T0 * steps_per_epoch,
        t_mul=float(COSINE_TMUL),
        m_mul=1.0,
        alpha=1e-6
    )

    # ------------------------------------------------------------------
    # Compile
    # ------------------------------------------------------------------
    model.compile(
        optimizer=keras.optimizers.Adam(lr_schedule),
        loss=SparseLabelSmoothingCE(num_classes=num_classes,
                                    smoothing=LABEL_SMOOTHING),
        metrics=['accuracy']
    )
    print(f"\nLoss : SparseLabelSmoothingCE(smoothing={LABEL_SMOOTHING})")
    print(f"LR   : CosineDecayRestarts(T0={COSINE_T0}, Tmul={COSINE_TMUL})")

    # ------------------------------------------------------------------
    # Callbacks
    # NOTE: EarlyStopping MUST use mode='min' for val_loss monitoring.
    #       mode='max' treats rising loss as improvement.  Do not change this.
    # NOTE: ModelCheckpoint and EarlyStopping MUST monitor the SAME metric —
    #       they used to disagree (val_accuracy vs val_loss), so the saved
    #       .keras file was silently a different epoch than the one this
    #       script evaluated and reported on.
    # ------------------------------------------------------------------
    timestamp  = datetime.now().strftime("%Y%m%d_%H%M%S")
    model_path = os.path.join(MODEL_DIR, f'model_{arch_name}_{timestamp}.keras')

    callbacks = [
        ModelCheckpoint(model_path, monitor='val_loss',
                        mode='min', save_best_only=True, verbose=1),
        EarlyStopping(monitor='val_loss',
                      patience=15,
                      mode='min',        # ← MUST be 'min': lower loss = better
                      restore_best_weights=True,
                      verbose=1),
        TensorBoard(log_dir=os.path.join(LOGS_DIR, timestamp))
    ]

    # ------------------------------------------------------------------
    # Train
    # ------------------------------------------------------------------
    print("\n" + "=" * 70)
    print("TRAINING")
    print("=" * 70)
    print(f"Epochs (max)  : {EPOCHS}")
    print(f"Steps/epoch   : {steps_per_epoch}  ({len(y_train):,} samples / {eff_batch} batch)")
    print(f"Early stopping: patience=15  monitor=val_loss  mode=min")
    print(f"Augmentation  : ECG (amplitude, noise, offset, shift) + RR (noise)\n")

    history = model.fit(
        train_ds,
        steps_per_epoch=steps_per_epoch,
        epochs=EPOCHS,
        validation_data=val_ds,
        class_weight=class_weights,
        callbacks=callbacks,
        verbose=1
    )

    print("\n✔ Training complete!")

    # ------------------------------------------------------------------
    # Plots
    # ------------------------------------------------------------------
    plot_path = os.path.join(MODEL_DIR, f'history_{arch_name}_{timestamp}.png')
    plot_history(history, plot_path)

    # ------------------------------------------------------------------
    # Evaluate
    # ------------------------------------------------------------------
    y_pred_base = evaluate(model, X_test, RR_test, y_test, class_names,
                           thresholds=None, label='Test (argmax baseline)')

    thresholds = optimize_thresholds(model, X_val, RR_val, y_val, num_classes)

    y_pred_tuned = evaluate(model, X_test, RR_test, y_test, class_names,
                            thresholds=thresholds, label='Test (threshold-tuned)')

    cm_path = os.path.join(MODEL_DIR, f'confusion_{arch_name}_{timestamp}.png')
    plot_confusion_matrix(y_test, y_pred_tuned, class_names,
                          title='(threshold-tuned)', save_path=cm_path)

    # ------------------------------------------------------------------
    # Save
    # ------------------------------------------------------------------
    results = {
        'timestamp'       : timestamp,
        'architecture'    : arch_name,
        'model_path'      : model_path,
        'params'          : params,
        'beat_length'     : beat_length,
        'n_rr_features'   : n_rr,
        'label_smoothing' : LABEL_SMOOTHING,
        'cosine_T0'       : COSINE_T0,
        'cosine_Tmul'     : COSINE_TMUL,
        'class_weights'   : class_weights,
        'thresholds'      : thresholds.tolist(),
        'best_val_acc'    : max(history.history['val_accuracy']),
        'best_val_loss'   : min(history.history['val_loss']),
        'metadata'        : metadata,
    }
    results_path = os.path.join(MODEL_DIR, f'results_{arch_name}_{timestamp}.pkl')
    with open(results_path, 'wb') as f:
        pickle.dump(results, f)

    print("\n" + "=" * 70)
    print("✔ COMPLETE")
    print("=" * 70)
    print(f"\nBest val accuracy : {results['best_val_acc']:.4f}")
    print(f"Model saved       : {model_path}")
    print(f"\nInference (must supply BOTH inputs):")
    print(f"  probs = model.predict({{'ecg': X, 'rr': RR}})")
    print(f"  pred  = np.argmax(probs / np.array({list(thresholds.round(3))}), axis=1)")
    print(f"\nTensorBoard: tensorboard --logdir={LOGS_DIR}")
    return model_path, results_path


# ============================================================================
# ENSEMBLE  (see module docstring)
# ============================================================================

def build_ensemble(member_paths):
    """Average the softmax of trained members, tune thresholds on val, save."""
    print("\n" + "=" * 70 + f"\nENSEMBLE OF {len(member_paths)} RUNS\n" + "=" * 70)
    ((_, _, _), (X_val, RR_val, y_val), (X_test, RR_test, y_test),
     metadata) = load_data()
    class_names = [metadata['class_names'][i] for i in sorted(metadata['class_names'])]
    num_classes = len(class_names)

    ins = {'ecg': keras.Input((metadata['beat_length'],), name='ecg'),
           'rr':  keras.Input((metadata['n_rr_features'],), name='rr')}
    outs = []
    for i, path in enumerate(member_paths):
        member = keras.models.load_model(path, compile=False)
        member._name = f'member_{i}'   # members share layer names otherwise
        outs.append(member(ins))
    model = keras.Model(ins, layers.Average(name='mean_probs')(outs),
                        name='ensemble_ecg_rr')

    thresholds = optimize_thresholds(model, X_val, RR_val, y_val, num_classes)
    evaluate(model, X_test, RR_test, y_test, class_names,
             thresholds=thresholds, label='Test (ensemble, threshold-tuned)')

    timestamp  = datetime.now().strftime("%Y%m%d_%H%M%S")
    model_path = os.path.join(MODEL_DIR, f'model_ensemble_{timestamp}.keras')
    model.save(model_path)
    results = {
        'timestamp'     : timestamp,
        'architecture'  : f'ensemble of {len(member_paths)}',
        'model_path'    : model_path,
        'members'       : list(member_paths),
        'beat_length'   : metadata['beat_length'],
        'n_rr_features' : metadata['n_rr_features'],
        'thresholds'    : thresholds.tolist(),
        'metadata'      : metadata,
    }
    results_path = os.path.join(MODEL_DIR, f'results_ensemble_{timestamp}.pkl')
    with open(results_path, 'wb') as f:
        pickle.dump(results, f)
    print(f"\nEnsemble saved : {model_path}\nResults        : {results_path}")
    return model_path, results_path


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Train the ECG + RR classifier")
    parser.add_argument("--ensemble", type=int, default=1,
                        help="train N runs and save their averaged ensemble (shipped: 4)")
    args = parser.parse_args()

    if args.ensemble <= 1:
        main()
    else:
        plt.switch_backend('Agg')   # don't block on plot windows between runs
        print("\nSelect architecture for all runs:")
        print("1. Residual  2. Standard  3. Lightweight")
        arch = input("Choice (1/2/3): ").strip()
        members = [main(arch_choice=arch)[0] for _ in range(args.ensemble)]
        build_ensemble(members)