"""
Script 04: Train Arrhythmia Detection Model

Architecture and training strategy based on:

[1] de Chazal et al. (2004) — DS1/DS2 inter-patient split standard
    IEEE Trans. Biomed. Eng. 51(7), 1196-1206.

[2] Hannun et al. (2019) — Residual 1D CNN for ECG arrhythmia detection
    Nature Medicine 25, 65-69.
    → Residual connections in build_residual_model()

[3] Yıldırım et al. (2018) — 1D CNN architecture for ECG beat classification
    Computers in Biology and Medicine 100, 109-119.
    → Kernel size and filter progression in build_standard_model()

[4] Müller et al. (2019) — When does label smoothing help?
    NeurIPS 2019.
    → SparseCategoricalCrossentropy(label_smoothing=0.1)

[5] Loshchilov & Hutter (2017) — SGDR: Cosine Annealing with Warm Restarts
    ICLR 2017.
    → CosineDecayRestarts LR schedule instead of ReduceLROnPlateau

[6] Class-balanced batch sampling — ensures every batch contains
    examples from every class regardless of global imbalance.
    Inspired by: Johnson & Khoshgoftaar (2019), survey on imbalanced
    learning, Journal of Big Data 6(1).
    → build_balanced_dataset()

[7] Per-class threshold optimization — post-hoc calibration of decision
    thresholds on the validation set to maximize macro F1.
    Used in clinical ECG systems: Ribeiro et al. (2020), Nature Comms 11.
    → optimize_thresholds()

Changes vs. previous (broken focal-loss) version:
  REMOVED  focal loss         — gamma=2 + linear weights caused model collapse
  REMOVED  linear class weights — doubled the minority correction, broke Normal
  REMOVED  random oversampling  — compounded the above issues
  ADDED    label smoothing      — prevents overconfident softmax [4]
  ADDED    balanced batch sampler — guaranteed minority exposure each batch [6]
  ADDED    cosine annealing      — smoother LR decay [5]
  ADDED    residual 1D CNN       — better gradient flow, less overfitting [2]
  ADDED    threshold optimization — fixes poor recall post-training [7]
"""

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
#
# SparseCategoricalCrossentropy gained label_smoothing only in TF 2.16.
# This custom loss replicates the behaviour for any TF version:
#   smoothed_label = (1 - ε) * one_hot  +  ε / K
# where ε = smoothing, K = num_classes.
# Müller et al. (2019) NeurIPS — "When does label smoothing help?"  [4]
# ============================================================================

class SparseLabelSmoothingCE(keras.losses.Loss):
    """
    Sparse integer label cross-entropy with label smoothing.
    Works with TF 2.15 and later.
    """
    def __init__(self, num_classes: int, smoothing: float = 0.1, **kwargs):
        super().__init__(**kwargs)
        self.num_classes = num_classes
        self.smoothing   = smoothing

    def call(self, y_true, y_pred):
        y_pred   = tf.clip_by_value(y_pred, 1e-7, 1.0)
        y_true   = tf.cast(tf.reshape(y_true, [-1]), tf.int32)
        y_onehot = tf.one_hot(y_true, self.num_classes)               # (batch, K)

        # Mix hard label with uniform distribution
        smooth_labels = (
            (1.0 - self.smoothing) * y_onehot
            + self.smoothing / self.num_classes
        )
        loss = -tf.reduce_sum(smooth_labels * tf.math.log(y_pred), axis=-1)
        return tf.reduce_mean(loss)

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

BATCH_SIZE    = 128
EPOCHS        = 100
LEARNING_RATE = 3e-4

# Label smoothing epsilon [4]
LABEL_SMOOTHING = 0.1

# Cosine annealing schedule [5]
COSINE_T0   = 20   # first restart after 20 epochs
COSINE_TMUL = 2    # double period each restart: 20 → 40 → 80

# Balanced sampler base target — used to compute per-class draw counts.
# Instead of a flat equal count, we scale each class by sqrt of its frequency
# relative to the majority class.  This keeps minority classes present in every
# batch while giving Normal proportionally more representation so the Normal
# decision boundary is well-calibrated.
# Majority class will draw ~SAMPLER_BASE samples; smallest class draws ~8.
SAMPLER_BASE = 32


# ============================================================================
# LOAD DATA
# ============================================================================

def load_data():
    print("Loading data...")

    X_train = np.load(os.path.join(DATA_DIR, 'X_train.npy'))
    y_train = np.load(os.path.join(DATA_DIR, 'y_train.npy'))
    X_val   = np.load(os.path.join(DATA_DIR, 'X_val.npy'))
    y_val   = np.load(os.path.join(DATA_DIR, 'y_val.npy'))
    X_test  = np.load(os.path.join(DATA_DIR, 'X_test.npy'))
    y_test  = np.load(os.path.join(DATA_DIR, 'y_test.npy'))

    with open(os.path.join(DATA_DIR, 'metadata.pkl'), 'rb') as f:
        metadata = pickle.load(f)

    print(f"Data loaded:")
    for split_name, y in [("Train", y_train), ("Val", y_val), ("Test", y_test)]:
        unique, counts = np.unique(y, return_counts=True)
        print(f"\n  {split_name} ({len(y):,} total):")
        for cls, cnt in zip(unique, counts):
            cname = metadata['class_names'].get(cls, str(cls))
            print(f"    {cname:>20s}: {cnt:>6,}  ({100*cnt/len(y):.1f}%)")

    return (X_train, y_train), (X_val, y_val), (X_test, y_test), metadata


# ============================================================================
# ECG AUGMENTATION
# Applied only on training data via tf.data.  Each oversampled copy gets
# a different random transform every epoch.
#
# Physiologically motivated transforms:
#   amplitude  — gain differences between electrodes / body impedance
#   noise      — electrode contact noise, EMG artifact
#   dc_offset  — baseline wander from breathing / movement
#   time_shift — beat segmentation jitter (±4 samples = ±16 ms @ 250 Hz)
# ============================================================================

@tf.function
def augment_ecg(x, y):
    x = x * tf.random.uniform([], 0.85, 1.15)           # amplitude ±15%
    x = x + tf.random.normal(tf.shape(x), stddev=0.02)  # Gaussian noise
    x = x + tf.random.uniform([], -0.04, 0.04)          # DC offset
    shift = tf.random.uniform([], -4, 4, dtype=tf.int32)
    x = tf.roll(x, shift=shift, axis=0)                 # time jitter
    return x, y


# ============================================================================
# BALANCED BATCH SAMPLING  [6]
#
# Build one infinite Dataset per class, then sample equally from all.
# This guarantees minority class exposure in every batch without oversampling
# the raw array — augmentation handles variation within repeated minority beats.
# ============================================================================

def compute_sampler_counts(y, num_classes, base=SAMPLER_BASE, min_samples=8):
    """
    Compute per-class draw counts scaled by sqrt(class_freq / majority_freq).

    Normal (majority) draws ~base samples per batch step.
    Minority classes draw proportionally fewer, but at least min_samples,
    ensuring they appear in every batch without being over-represented.

    Example with base=32, 3 classes at 92/0.7/7.3% frequency:
      Normal           → 32
      Ventricular      → ~19
      Supraventricular → ~8 (floored by min_samples)

    vs. flat equal (32 each) which gave Normal only 33% of the batch despite
    representing 92% of real data — collapsing the Normal decision boundary
    and producing high false-positive rates on minority classes.
    """
    counts     = Counter(y.tolist())
    max_count  = max(counts[c] for c in range(num_classes))
    per_class  = {}
    for cls in range(num_classes):
        n          = counts.get(cls, 1)
        ratio      = n / max_count               # 0…1
        draw       = int(base * (ratio ** 0.5))  # sqrt scaling
        per_class[cls] = max(draw, min_samples)
    return per_class


def build_balanced_dataset(X, y, num_classes, samples_per_class,
                            augment=False, seed=42):
    """
    Returns a tf.data.Dataset that yields sqrt-proportionally balanced batches.

    samples_per_class: dict {class_id: n_samples} from compute_sampler_counts().
    Batch size = sum(samples_per_class.values()).
    """
    class_datasets = []
    total_draws    = sum(samples_per_class.values())

    for cls in range(num_classes):
        idx = np.where(y == cls)[0]
        ds  = tf.data.Dataset.from_tensor_slices(
            (X[idx].astype(np.float32),
             np.full(len(idx), cls, dtype=np.int32))
        )
        ds = ds.shuffle(len(idx), seed=seed, reshuffle_each_iteration=True)
        ds = ds.repeat()
        if augment:
            ds = ds.map(augment_ecg, num_parallel_calls=tf.data.AUTOTUNE)
        class_datasets.append(ds)

    # Sampling weight proportional to desired draw count per class
    draw_weights = [samples_per_class[c] / total_draws for c in range(num_classes)]
    balanced = tf.data.Dataset.sample_from_datasets(
        class_datasets, weights=draw_weights, seed=seed,
        stop_on_empty_dataset=False
    )
    return balanced.batch(total_draws).prefetch(tf.data.AUTOTUNE)


def build_standard_dataset(X, y, batch_size=BATCH_SIZE):
    """Standard (non-balanced) tf.data pipeline for val/test."""
    ds = tf.data.Dataset.from_tensor_slices(
        (X.astype(np.float32), y.astype(np.int32))
    )
    return ds.batch(batch_size).prefetch(tf.data.AUTOTUNE)


# ============================================================================
# MODEL ARCHITECTURES
# ============================================================================

def residual_block_1d(x, filters, kernel_size, l2_reg=1e-3):
    """
    Pre-activation residual block (He et al. 2016).
    1×1 projection on skip if channel count changes.
    """
    reg      = keras.regularizers.l2(l2_reg)
    shortcut = x

    x = layers.BatchNormalization()(x)
    x = layers.Activation('relu')(x)
    x = layers.Conv1D(filters, kernel_size, padding='same',
                      kernel_regularizer=reg)(x)

    x = layers.BatchNormalization()(x)
    x = layers.Activation('relu')(x)
    x = layers.Conv1D(filters, kernel_size, padding='same',
                      kernel_regularizer=reg)(x)

    if shortcut.shape[-1] != filters:
        shortcut = layers.Conv1D(filters, 1, padding='same')(shortcut)

    return layers.Add()([x, shortcut])


def build_residual_model(input_shape, num_classes, l2_reg=1e-3):
    """
    Residual 1D CNN inspired by Hannun et al. (2019) [2].
    ~35K parameters.  Better gradient flow than plain CNN.
    """
    reg    = keras.regularizers.l2(l2_reg)
    inputs = keras.Input(shape=input_shape)
    x      = layers.Reshape((input_shape[0], 1))(inputs)

    # Stem
    x = layers.Conv1D(32, 7, padding='same', kernel_regularizer=reg)(x)
    x = layers.BatchNormalization()(x)
    x = layers.Activation('relu')(x)
    x = layers.MaxPooling1D(2)(x)       # 250 → 125

    # Stage 1 — 32 filters
    x = residual_block_1d(x, 32, 5, l2_reg)
    x = layers.MaxPooling1D(2)(x)       # 125 → 62
    x = layers.Dropout(0.2)(x)

    # Stage 2 — 64 filters
    x = residual_block_1d(x, 64, 5, l2_reg)
    x = layers.MaxPooling1D(2)(x)       # 62 → 31
    x = layers.Dropout(0.3)(x)

    # Stage 3 — 128 filters
    x = residual_block_1d(x, 128, 3, l2_reg)
    x = layers.Dropout(0.3)(x)

    # Head
    x       = layers.GlobalAveragePooling1D()(x)
    x       = layers.Dense(64, activation='relu', kernel_regularizer=reg)(x)
    x       = layers.Dropout(0.4)(x)
    outputs = layers.Dense(num_classes, activation='softmax')(x)

    return keras.Model(inputs, outputs, name='residual_1d_cnn')


def build_standard_model(input_shape, num_classes, l2_reg=1e-3):
    """
    Standard 1D CNN with increased regularization.
    Based on Yıldırım et al. (2018) [3]. ~50K parameters.
    """
    reg = keras.regularizers.l2(l2_reg)
    return models.Sequential([
        layers.Input(shape=input_shape),
        layers.Reshape((input_shape[0], 1)),

        layers.Conv1D(32, 5, activation='relu', padding='same', kernel_regularizer=reg),
        layers.BatchNormalization(), layers.MaxPooling1D(2), layers.Dropout(0.3),

        layers.Conv1D(64, 5, activation='relu', padding='same', kernel_regularizer=reg),
        layers.BatchNormalization(), layers.MaxPooling1D(2), layers.Dropout(0.3),

        layers.Conv1D(128, 3, activation='relu', padding='same', kernel_regularizer=reg),
        layers.BatchNormalization(), layers.MaxPooling1D(2), layers.Dropout(0.4),

        layers.GlobalAveragePooling1D(),
        layers.Dense(64, activation='relu', kernel_regularizer=reg),
        layers.Dropout(0.4),
        layers.Dense(32, activation='relu'),
        layers.Dropout(0.3),
        layers.Dense(num_classes, activation='softmax')
    ], name='standard_1d_cnn')


def build_lightweight_model(input_shape, num_classes, l2_reg=1e-3):
    """Lightweight 1D CNN for ESP32. ~20K parameters."""
    reg = keras.regularizers.l2(l2_reg)
    return models.Sequential([
        layers.Input(shape=input_shape),
        layers.Reshape((input_shape[0], 1)),

        layers.Conv1D(16, 7, activation='relu', padding='same', kernel_regularizer=reg),
        layers.MaxPooling1D(2), layers.Dropout(0.3),

        layers.Conv1D(32, 5, activation='relu', padding='same', kernel_regularizer=reg),
        layers.BatchNormalization(), layers.MaxPooling1D(2), layers.Dropout(0.3),

        layers.Conv1D(64, 3, activation='relu', padding='same', kernel_regularizer=reg),
        layers.BatchNormalization(), layers.GlobalAveragePooling1D(),

        layers.Dense(32, activation='relu'),
        layers.Dropout(0.4),
        layers.Dense(num_classes, activation='softmax')
    ], name='lightweight_1d_cnn')


# ============================================================================
# PER-CLASS THRESHOLD OPTIMIZATION  [7]
#
# After training, default argmax systematically under-predicts minority classes
# because their softmax values are calibrated to the imbalanced distribution.
# We sweep thresholds on validation set, pick the combination that maximises
# macro-F1, then apply at test time.
# ============================================================================

def optimize_thresholds(model, X_val, y_val, num_classes, n_steps=50):
    """
    Find per-class decision thresholds maximising macro-F1 on validation set.
    Decision rule: class = argmax(probs / thresholds).
    """
    print("\nOptimising per-class thresholds on validation set...")
    probs = model.predict(X_val, verbose=0)

    # Phase 1: find best global threshold
    grid          = np.linspace(0.05, 0.95, n_steps)
    best_thresh   = np.ones(num_classes) * 0.5
    best_f1       = -1.0

    for t in grid:
        t_arr    = np.full(num_classes, t)
        y_pred   = np.argmax(probs / t_arr, axis=1)
        macro_f1 = f1_score(y_val, y_pred, average='macro', zero_division=0)
        if macro_f1 > best_f1:
            best_f1     = macro_f1
            best_thresh = t_arr.copy()

    # Phase 2: refine each class independently
    for cls in range(num_classes):
        for t in grid:
            t_try      = best_thresh.copy()
            t_try[cls] = t
            y_pred     = np.argmax(probs / t_try, axis=1)
            macro_f1   = f1_score(y_val, y_pred, average='macro', zero_division=0)
            if macro_f1 > best_f1:
                best_f1           = macro_f1
                best_thresh[cls]  = t

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
        (axes[0], cm,      'd',    f'Raw Counts {title}'),
        (axes[1], cm_norm, '.2f',  f'Normalized {title}'),
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

def evaluate(model, X_test, y_test, class_names, thresholds=None, label='Test'):
    print(f"\n{'='*70}\n{label} SET EVALUATION\n{'='*70}")

    probs = model.predict(X_test, verbose=0)
    if thresholds is not None:
        y_pred = np.argmax(probs / thresholds, axis=1)
    else:
        y_pred = np.argmax(probs, axis=1)

    test_acc = float(np.mean(y_pred == y_test))
    print(f"\nAccuracy: {test_acc:.4f}")

    print("\nPer-Class Performance:")
    print(classification_report(y_test, y_pred,
                                target_names=class_names,
                                digits=4, zero_division=0))

    cm = confusion_matrix(y_test, y_pred)
    print("Raw Confusion Matrix:")
    header = " " * 22 + "  ".join(f"{n:>18}" for n in class_names)
    print(header)
    for i, row in enumerate(cm):
        print(f"  {class_names[i]:>20}  " +
              "  ".join(f"{v:>18}" for v in row))

    macro_f1 = f1_score(y_test, y_pred, average='macro', zero_division=0)
    print(f"\nMacro F1: {macro_f1:.4f}  ← primary metric for imbalanced data")
    return y_pred


# ============================================================================
# MAIN
# ============================================================================

def main():
    print("=" * 70)
    print("ARRHYTHMIA DETECTION MODEL TRAINING")
    print("=" * 70)

    if not os.path.exists(DATA_DIR):
        print(f"\n⚠  {DATA_DIR} not found — run preprocess.py first")
        print("\nIMPORTANT: Before re-running preprocess.py, run analyze_mitbih.py")
        print("to confirm your patient split has the correct class distribution.")
        return

    os.makedirs(MODEL_DIR, exist_ok=True)
    os.makedirs(LOGS_DIR, exist_ok=True)

    # ------------------------------------------------------------------
    # Load
    # ------------------------------------------------------------------
    (X_train, y_train), (X_val, y_val), (X_test, y_test), metadata = load_data()

    class_ids   = sorted(metadata['class_names'].keys())
    class_names = [metadata['class_names'][i] for i in class_ids]
    input_shape = (metadata['beat_length'],)
    num_classes = len(class_names)

    # Compute sqrt-proportional per-class draw counts
    sampler_counts = compute_sampler_counts(y_train, num_classes, base=SAMPLER_BASE)
    eff_batch      = sum(sampler_counts.values())
    print(f"\nSampler draw counts per class (sqrt-proportional, base={SAMPLER_BASE}):")
    for c, n in sampler_counts.items():
        print(f"  {class_names[c]:>20s}: {n} draws/step  (n_train={Counter(y_train.tolist()).get(c,0):,})")
    print(f"  Effective batch size: {eff_batch}")

    # ------------------------------------------------------------------
    # Architecture
    # ------------------------------------------------------------------
    print("\nSelect architecture:")
    print("1. Residual 1D CNN  (~35K params)  ← RECOMMENDED")
    print("2. Standard 1D CNN  (~50K params)")
    print("3. Lightweight CNN  (~20K params, ESP32)")
    choice = input("Choice (1/2/3): ").strip()

    if choice == "2":
        model     = build_standard_model(input_shape, num_classes)
        arch_name = "standard"
    elif choice == "3":
        model     = build_lightweight_model(input_shape, num_classes)
        arch_name = "lightweight"
    else:
        model     = build_residual_model(input_shape, num_classes)
        arch_name = "residual"

    model.summary()
    params = model.count_params()
    print(f"\nParameters : {params:,}")
    print(f"Size float32: ~{params * 4 / 1024:.1f} KB")

    # ------------------------------------------------------------------
    # Class weights — sqrt-inverse, capped at 10× minimum
    # The balanced sampler does the heavy lifting for minority exposure;
    # class_weight provides a secondary correction to the loss scale.
    # ------------------------------------------------------------------
    counts  = Counter(y_train.tolist())
    total   = len(y_train)
    raw_w   = {c: (total / (num_classes * counts.get(c, 1))) ** 0.5
               for c in range(num_classes)}
    min_w   = min(raw_w.values())
    cap_w   = min_w * 10
    capped  = {c: min(w, cap_w) for c, w in raw_w.items()}
    mean_w  = sum(capped.values()) / num_classes
    class_weights = {c: w / mean_w for c, w in capped.items()}

    print("\nClass weights (sqrt-inverse, capped 10×, mean=1):")
    for c, w in class_weights.items():
        print(f"  {class_names[c]:>20s}: {w:.3f}  (n={counts.get(c, 0):,})")

    # ------------------------------------------------------------------
    # Datasets
    # ------------------------------------------------------------------
    print("\nBuilding datasets...")
    train_ds = build_balanced_dataset(
        X_train, y_train, num_classes,
        samples_per_class=sampler_counts,
        augment=True
    )
    val_ds = build_standard_dataset(X_val, y_val)
    print(f"  ✔ Balanced training dataset built")
    print(f"  ✔ Standard validation dataset built")

    # ------------------------------------------------------------------
    # LR schedule — cosine annealing with warm restarts  [5]
    # ------------------------------------------------------------------
    steps_per_epoch = len(y_train) // eff_batch
    lr_schedule = keras.optimizers.schedules.CosineDecayRestarts(
        initial_learning_rate=LEARNING_RATE,
        first_decay_steps=COSINE_T0 * steps_per_epoch,
        t_mul=float(COSINE_TMUL),
        m_mul=1.0,
        alpha=1e-6
    )

    # ------------------------------------------------------------------
    # Compile — label smoothing CE  [4]
    # Custom class used for TF 2.15 compatibility (SparseCategoricalCrossentropy
    # only gained label_smoothing support in TF 2.16).
    # ------------------------------------------------------------------
    model.compile(
        optimizer=keras.optimizers.Adam(lr_schedule),
        loss=SparseLabelSmoothingCE(num_classes=num_classes,
                                    smoothing=LABEL_SMOOTHING),
        metrics=['accuracy']
    )
    print(f"\nLoss: SparseLabelSmoothingCE(num_classes={num_classes}, smoothing={LABEL_SMOOTHING})")
    print(f"LR:   CosineDecayRestarts(T0={COSINE_T0} epochs, Tmul={COSINE_TMUL})")

    # ------------------------------------------------------------------
    # Callbacks
    # ------------------------------------------------------------------
    timestamp  = datetime.now().strftime("%Y%m%d_%H%M%S")
    model_path = os.path.join(MODEL_DIR, f'model_{arch_name}_{timestamp}.keras')

    callbacks = [
        ModelCheckpoint(model_path, monitor='val_accuracy',
                        save_best_only=True, verbose=1),
        EarlyStopping(monitor='val_loss', patience=15,
                      mode='min',           # ← lower val_loss = better
                      restore_best_weights=True, verbose=1),
        TensorBoard(log_dir=os.path.join(LOGS_DIR, timestamp))
    ]

    # ------------------------------------------------------------------
    # Train
    # ------------------------------------------------------------------
    print("\n" + "=" * 70)
    print("TRAINING")
    print("=" * 70)
    # steps_per_epoch required because train_ds uses .repeat() (infinite stream).
    # One epoch = seeing every training sample once on average.
    steps_per_epoch = max(1, len(y_train) // eff_batch)

    print(f"Epochs (max)  : {EPOCHS}")
    print(f"Steps/epoch   : {steps_per_epoch}  ({len(y_train):,} samples / {eff_batch} batch)")
    print(f"Early stopping: patience=15  val_loss  mode=min")
    print(f"Augmentation  : amplitude, noise, DC offset, time-shift\n")

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
    # Evaluate — baseline (argmax) then threshold-tuned
    # ------------------------------------------------------------------
    y_pred_baseline = evaluate(model, X_test, y_test, class_names,
                               thresholds=None, label='Test (argmax baseline)')

    thresholds = optimize_thresholds(model, X_val, y_val, num_classes)

    y_pred_tuned = evaluate(model, X_test, y_test, class_names,
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
    print(f"Results saved     : {results_path}")
    print(f"\nInference with threshold correction:")
    print(f"  probs = model.predict(X)")
    print(f"  pred  = np.argmax(probs / np.array({list(thresholds.round(3))}), axis=1)")
    print(f"\nTensorBoard: tensorboard --logdir={LOGS_DIR}")


if __name__ == "__main__":
    main()