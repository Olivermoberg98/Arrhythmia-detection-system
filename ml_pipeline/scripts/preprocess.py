"""
preprocess.py: Preprocess ECG Data

Summary:
✔ Patient-level splits following de Chazal et al. (2004) DS1/DS2 standard
✔ The AD8232 board's analogue filters, modelled at 360 Hz (see "Firmware parity")
✔ Exact rational downsample 360 → 250 Hz (resample_poly, factor 25/36)
✔ The firmware's own causal filter chain at 250 Hz (see "Firmware parity")
✔ Beat fiducial = QRS slope-energy centroid, computed exactly as the firmware does
✔ Asymmetric beat window: 96 pre / 32 post = 128 samples total
✔ RR interval features appended alongside morphology (3 features per beat)
✔ Normalization after splitting (train stats only — no leakage)
✔ 3-CLASS OUTPUT: Normal (N), Supraventricular (S), Ventricular (V)

Design decision — why 3 classes (N/S/V) and not 5:
─────────────────────────────────────────────────────────────────────────────
The AAMI/ANSI EC57 standard defines 5 superclasses: N, S, V, F (Fusion), Q
(Paced/unclassifiable).  We intentionally reduce to 3 for the following
reasons, each of which is a general principle worth carrying forward:

  DROPPED: Q — Paced beats  ('/', 'f' mapped to paced)
  ─────────────────────────────────────────────────────
  Reason 1 — Trivial morphology:
    Paced beats have a large rectangular stimulus artefact before the QRS
    that is visually and numerically distinct from any natural beat.  A model
    can learn to classify them with >99% accuracy from the first epoch,
    which means they contribute nothing to the model's generalisation ability
    while inflating the reported accuracy metric.

  Reason 2 — Not clinically relevant to detection:
    The clinical question the model answers is "is this an ectopic beat that
    needs follow-up?".  Paced beats are already known to the clinician — the
    patient has a pacemaker.  Detecting paced beats adds no decision value.

  Reason 3 — Dataset contamination:
    In MIT-BIH, paced beats are concentrated in a small number of records
    (102, 104, 107, 217, 219, 221, 231).  Including Q means those records
    cannot cleanly go into either train or test without leaking the easiest
    class into both.

  DROPPED: F — Fusion beats  ('F' symbol)
  ─────────────────────────────────────────
  Reason 1 — Ambiguous ground truth:
    A Fusion beat is a simultaneous activation of the ventricles by both a
    normal sinus impulse and a PVC.  Its surface-ECG morphology is literally
    a weighted average of N and V morphology — it lies on the manifold between
    the two classes.  Annotating it requires expert consensus and even experts
    disagree on borderline cases, making it a noisy label.

  Reason 2 — Empirically unlearnable at this dataset scale:
    MIT-BIH contains ~800 total Fusion beats, of which 373 come from a single
    record (208).  With inter-patient splitting, your test set may have very
    few or zero Fusion patients.  No published paper has achieved meaningful
    F-class recall on inter-patient MIT-BIH splits.

  Reason 3 — Clinical management equivalence:
    Clinically, Fusion beats are managed identically to PVCs (Ventricular
    ectopic beats) — they indicate the same underlying ventricular ectopy.
    Dropping F therefore loses little actionable information: the same ectopy
    shows up in the patient's V beats. (F is dropped, not merged into V —
    AAMI_CLASSES maps 'F' to -1.)

  Reason 4 — Alignment with published benchmarks:
    de Chazal et al. (2004), Kiranyaz et al. (2016), Yıldırım et al. (2018),
    and the majority of subsequent MIT-BIH classification papers either drop F
    or report it separately as a "known hard class" with caveat.  Using 3
    classes allows direct comparison to the established literature.

General principle for future decisions:
  Before including a class, ask:
    (a) Is it learnable from the available data at this scale?
    (b) Does it add clinically actionable information?
    (c) Does it distort evaluation of the classes you actually care about?
  If any answer is "no", dropping or merging is usually the right call.
─────────────────────────────────────────────────────────────────────────────

RR Interval Features — why they matter for SVEB detection:
─────────────────────────────────────────────────────────────────────────────
Supraventricular ectopic beats (SVEB / APBs) originate above the ventricles,
so the QRS morphology in the beat window is nearly identical to a normal sinus
beat.  The dominant discriminating features are TEMPORAL, not morphological:

  - SVEB beats arrive EARLY (short pre-ectopic RR interval)
  - They are often followed by a compensatory pause (long post-ectopic RR)
  - The pre_rr / post_rr ratio is distinctively < 1 for SVEB, ~1 for Normal

This is well established: de Chazal et al. (2004) showed RR features alone
achieve ~70% SVEB sensitivity.  Combining them with CNN morphology features
is the standard approach in all high-performing inter-patient classifiers.

Three RR features are computed per beat (from snapped R-peak positions):
  1. pre_rr_norm  = (time since last beat) / local_mean_rr
  2. post_rr_norm = (time until next beat) / local_mean_rr
  3. ratio_rr     = pre_rr / post_rr

local_mean_rr is the mean of the last RR_MEAN_WINDOW (8) intervals up to and
including pre_rr — the same causal running mean the firmware keeps. A whole-record
mean is not used because the firmware cannot know it: a 30-minute average
vs an 8-beat one gives different values whenever the rate drifts. 8 beats
(~6-8 s) is close to de Chazal et al.'s (2004) ~10 s "local average RR".

post_rr needs the next beat, so the firmware holds each beat until the next
R-peak is detected (one RR interval of latency) rather than approximating it.
Approximating post_rr = pre_rr would make ratio_rr a constant 1 and throw
away the compensatory-pause cue described above.

RR timing uses EVERY annotated beat, including the F/Q/paced beats that are
then dropped as labels: the firmware's detector sees those beats too, so
skipping them would make one interval span two beats. Beats without both a
predecessor and a successor (first/last in the record) are dropped, matching
the firmware, which never emits them.

These are then StandardScaler-normalized using training statistics only.
─────────────────────────────────────────────────────────────────────────────

Firmware parity — why training reproduces the ESP32 signal chain exactly:
─────────────────────────────────────────────────────────────────────────────
The model only ever sees beats produced by the firmware, so the training
beats must be produced the same way.  The usual offline choice, a zero-phase
4th-order Butterworth bandpass at 360 Hz, does not match the firmware, which
runs causal 2nd-order biquads (HP 0.5 Hz, LP 40 Hz, 50 Hz notch) at 250 Hz.
Causal filters shift and reshape the waveform (phase response, HP undershoot
after the QRS, the notch's ~4% passband gain), so a CNN trained on zero-phase
beats learns a morphology the device never produces.

The pipeline is therefore: raw MIT-BIH (360 Hz) → the AD8232 board's
analogue response (below) → resample_poly to 250 Hz (stands in for the ADC)
→ the exact config.h biquad coefficients, applied causally with lfilter.  Each annotated
beat is then moved to the same fiducial point the firmware's detector ends
on (below), so window alignment matches too.  Filter coefficients and RR_MEAN_WINDOW are
duplicated from config.h and must be kept in sync by hand.

The analogue stage matters because the board filters before the firmware
does, so the device signal is filtered twice.  The breakout (SparkFun
SEN-12650 clone, "V502") carries the resistor set of the AD8232 datasheet's
Figure 66 "Cardiac Monitor" circuit (10 MΩ, 1.5 MΩ, 1 MΩ, 100 kΩ, read off
the board; capacitors are unmarked and taken from the figure):
  - 2-pole HP (Figure 56 topology): fc = 10 / (2π·√(R1C1R2C2)) = 0.48 Hz,
    R1 = R2 = 10 MΩ, C1 = C2 = 0.33 µF, RCOMP ≈ 0.14·R1 → maximally flat
  - 2-pole Sallen-Key LP: R1 = R2 = 1 MΩ, C1 = 1.5 nF, C2 = 10 nF, gain 11
    → fc = 41.1 Hz, Q = 0.775 (datasheet Q formula)
  - total gain 1100 (handled by ADC_COUNTS_TO_MV in the app, not here)
It is applied causally at the native 360 Hz (bilinear transform, prewarped
at each corner), i.e. where it sits physically: before sampling.
MIT-BIH was itself recorded through a 0.1–100 Hz analogue chain, so this
approximates the device's view of the same heart.

Not modelled: component tolerances and board-to-board gain spread.
─────────────────────────────────────────────────────────────────────────────

Beat fiducial — why the slope-energy centroid and not the R-peak:
─────────────────────────────────────────────────────────────────────────────
The 128-sample window is placed relative to one point per beat, and that
point must land on the same part of the QRS every time.  The obvious choice,
the largest |filtered| sample, is unstable on RS-type complexes where the S
wave is about as deep as the R is tall (a normal variant; the first real
wearer of this device has one): it flips between R and S from beat to beat,
shifting the window ~30 ms and feeding the model inconsistent inputs.

The fiducial is instead the centroid of the squared first difference,
sum(t * d[t]^2) / sum(d[t]^2), over ±RPEAK_SNAP_HALF samples around the
current estimate, refined FIDUCIAL_ITERATIONS times.  Slope energy is
concentrated in the QRS (P and T are slow), and a weighted mean moves
continuously as the R/S balance changes instead of jumping between peaks.
It is the same quantity Pan-Tompkins' integrator is built on, so it is cheap
on the ESP32.  The firmware starts from its detector crest, training from the
database annotation; three refinements make both land on the same sample.
─────────────────────────────────────────────────────────────────────────────

Asymmetric Window — why 96 pre / 32 post instead of 64 / 64:
─────────────────────────────────────────────────────────────────────────────
A symmetric window centered on the R-peak devotes equal time before and after.
But the clinically informative morphology is asymmetric:

  BEFORE R-peak: P-wave (120–200ms before R), PR segment, previous T-wave
    → SVEB has an abnormal or absent P-wave in this region
    → More pre-R context helps the CNN distinguish N from S on morphology alone

  AFTER R-peak: QRS downstroke (~40ms) + T-wave (ends ~300ms after R)
    → 128ms (32 samples) is sufficient to capture the full T-wave

Total window is 128 samples (384ms pre / 128ms post = 512ms @ 250 Hz).
(Was 187/63 = 250 samples in an earlier draft; ESP32/app already agree on 128.)
─────────────────────────────────────────────────────────────────────────────
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

DATA_DIR   = os.path.join(os.path.dirname(__file__), '..', 'data', 'raw', 'mitdb')
OUTPUT_DIR = os.path.join(os.path.dirname(__file__), '..', 'data', 'processed')

# Sampling rates
ORIGINAL_FS = 360    # MIT-BIH native rate
TARGET_FS   = 250    # Must match ESP32 ADC rate

# Exact rational resampling ratio: 250/360 = 25/36
_GCD          = 10
RESAMPLE_UP   = TARGET_FS   // _GCD   # 25
RESAMPLE_DOWN = ORIGINAL_FS // _GCD   # 36

# Asymmetric beat window at TARGET_FS — 384 ms pre / 128 ms post (see docstring)
WINDOW_SIZE_BEFORE = 96
WINDOW_SIZE_AFTER  = 32
BEAT_LENGTH        = WINDOW_SIZE_BEFORE + WINDOW_SIZE_AFTER   # 128

# Number of RR interval features appended to each beat
N_RR_FEATURES = 3   # pre_rr_norm, post_rr_norm, ratio_rr

# AD8232 board analogue response (datasheet Figure 66, see docstring).
AD8232_HP_HZ = 0.482
AD8232_HP_Q  = 0.707
AD8232_LP_HZ = 41.1
AD8232_LP_Q  = 0.775

# Firmware filter chain — copied from config.h FilterCfg, fs = 250 Hz.
LOWCUT  = 0.5    # HP corner (metadata only)
HIGHCUT = 40.0   # LP corner (metadata only)
FW_HP_B    = [0.99115, -1.98230,  0.99115]
FW_HP_A    = [1.0,     -1.98222,  0.98232]
FW_LP_B    = [0.14413,  0.28826,  0.14413]
FW_LP_A    = [1.0,     -0.67736,  0.25390]
FW_NOTCH_B = [1.0,     -0.61800,  1.0]
FW_NOTCH_A = [1.0,     -0.59484,  0.92598]

# Fiducial window half-width and refinements — config.h FilterCfg::
# PT_RPEAK_SEARCH_HALF / PT_FIDUCIAL_ITERATIONS.
RPEAK_SNAP_HALF     = 15
FIDUCIAL_ITERATIONS = 3

# Running mean RR length — config.h MLCfg::RR_MEAN_WINDOW.
RR_MEAN_WINDOW = 8

# Patient-level splits — de Chazal et al. (2004) DS1/DS2 standard.
# VAL = 118, 201, 207, 223 (pulled from DS1 for minority coverage).
# 209 (383 S beats, the largest S source in DS1) stays in TRAIN — an earlier
# split had it in VAL instead, starving TRAIN to 253 S beats and causing
# S-class overfitting (run 20260307_154201). Swapped with 118.

TRAIN_RECORDS = [
    101, 106, 108, 109, 112, 114, 115, 116, 119,
    122, 124,
    203, 205, 208, 209, 215, 220, 230,
]

VAL_RECORDS = [
    118, 201, 207, 223,
]

TEST_RECORDS = [
    100, 103, 105, 111, 113, 117, 121, 123,
    200, 202, 210, 212, 213, 214, 219, 221,
    222, 228, 231, 232, 233, 234,
]

# AAMI beat-class mapping — 3-class output (N / S / V)
AAMI_CLASSES = {
    # N — Normal / non-ectopic
    'N': 0, 'L': 0, 'R': 0, 'e': 0, 'j': 0,
    # S — Supraventricular ectopic
    'A': 1, 'a': 1, 'J': 1, 'S': 1,
    # V — Ventricular ectopic
    'V': 2, 'E': 2,
    # Discarded (see docstring)
    'F': -1, 'f': -1, '/': -1, 'Q': -1,
}

CLASS_NAMES = {0: 'Normal', 1: 'Supraventricular', 2: 'Ventricular'}


# ============================================================================
# SIGNAL PROCESSING
# ============================================================================

def _analog_biquad(kind, f0, q, fs):
    """Bilinear-discretised 2nd-order analogue section, prewarped at f0."""
    w0 = 2 * fs * np.tan(np.pi * f0 / fs)
    a = [1.0, w0 / q, w0 * w0]
    b = [1.0, 0.0, 0.0] if kind == 'high' else [0.0, 0.0, w0 * w0]
    return signal.bilinear(b, a, fs)


def ad8232_analog(ecg_360):
    """The AD8232 board's 2-pole HP + 2-pole LP, causal, at the native rate."""
    b, a = _analog_biquad('high', AD8232_HP_HZ, AD8232_HP_Q, ORIGINAL_FS)
    x = signal.lfilter(b, a, ecg_360)
    b, a = _analog_biquad('low', AD8232_LP_HZ, AD8232_LP_Q, ORIGINAL_FS)
    return signal.lfilter(b, a, x)


def downsample_signal(ecg):
    """Downsample 360 → 250 Hz using exact rational resampling (25/36)."""
    return signal.resample_poly(ecg, RESAMPLE_UP, RESAMPLE_DOWN)


def firmware_filter(ecg_250):
    """The firmware's causal HP → LP → notch biquad chain, at 250 Hz."""
    x = signal.lfilter(FW_HP_B, FW_HP_A, ecg_250)
    x = signal.lfilter(FW_LP_B, FW_LP_A, x)
    return signal.lfilter(FW_NOTCH_B, FW_NOTCH_A, x)


def snap_rpeak(ecg_filtered, r_peak_idx):
    """Move a beat to its QRS slope-energy centroid, as the firmware does (see docstring)."""
    centre = float(r_peak_idx)
    for _ in range(FIDUCIAL_ITERATIONS):
        c  = int(np.floor(centre + 0.5))
        lo = max(1, c - RPEAK_SNAP_HALF)
        hi = min(len(ecg_filtered) - 1, c + RPEAK_SNAP_HALF)
        if hi <= lo:
            break
        d = ecg_filtered[lo:hi + 1] - ecg_filtered[lo - 1:hi]
        w = d * d
        if w.sum() > 0:
            centre = float((w * np.arange(lo, hi + 1)).sum() / w.sum())
    return int(np.floor(centre + 0.5))


def scale_rpeak(r_peak_original):
    """Map R-peak sample index from ORIGINAL_FS to TARGET_FS."""
    return round(r_peak_original * TARGET_FS / ORIGINAL_FS)


def extract_beat(ecg_signal, r_peak_idx):
    """
    Extract asymmetric beat window centred on R-peak.
    Returns None if the window falls outside signal boundaries.
    """
    start = r_peak_idx - WINDOW_SIZE_BEFORE
    end   = r_peak_idx + WINDOW_SIZE_AFTER
    if start < 0 or end >= len(ecg_signal):
        return None
    return ecg_signal[start:end]


# ============================================================================
# RR INTERVAL FEATURE COMPUTATION
# ============================================================================

def compute_rr_features(r_peaks_samples):
    """
    Compute 3 RR interval features for each beat in a record.

    Features (all dimensionless ratios — invariant to heart rate):
      1. pre_rr_norm  = pre-ectopic RR  / local_mean_RR
      2. post_rr_norm = post-ectopic RR / local_mean_RR
      3. ratio_rr     = pre_rr / post_rr
    local_mean_RR = mean of the last RR_MEAN_WINDOW intervals up to pre_rr.

    Why these three:
      - pre_rr_norm  < 1 strongly indicates a premature beat (SVEB or VEB)
      - post_rr_norm > 1 indicates a compensatory pause, common after PVCs
        and SVEB; the two classes differ in pause length
      - ratio_rr combines both: ~1 for Normal, <1 for premature-with-pause

    Boundary beats (first and last) have no pre or post interval; their rows
    are NaN and the caller drops them, as the firmware never emits them.

    Mirrors SignalProcessor.cpp: same intervals, same running mean, same
    order of operations. Change one, change both.

    Args:
        r_peaks_samples: list of R-peak sample indices (already at TARGET_FS)
    Returns:
        np.ndarray of shape (n_beats, 3)
    """
    n = len(r_peaks_samples)
    features = np.full((n, N_RR_FEATURES), np.nan, dtype=np.float32)
    if n < 3:
        return features

    rr = np.diff(np.asarray(r_peaks_samples, dtype=np.float64))   # samples
    for i in range(1, n - 1):
        pre_rr  = rr[i - 1]
        post_rr = rr[i]
        mean_rr = max(np.mean(rr[max(0, i - RR_MEAN_WINDOW):i]), 1e-3)

        features[i, 0] = pre_rr  / mean_rr                           # pre_rr_norm
        features[i, 1] = post_rr / mean_rr                           # post_rr_norm
        features[i, 2] = pre_rr  / post_rr if post_rr > 0 else 1.0   # ratio_rr

    return features


# ============================================================================
# RECORD PROCESSING
# ============================================================================

def process_record(record_num, channel=0):
    """
    Full pipeline for one MIT-BIH record:
      1. Load raw ECG + annotations
      2. AD8232 analogue response, then downsample to 250 Hz
      3. Firmware filter chain (causal biquads)
      4. First pass: every annotated beat, R-peak snapped like the firmware
      5. Compute RR features from that full beat sequence
      6. Second pass: extract windows for labelled beats with valid RR

    Returns:
        beats      : list of np.ndarray shape (BEAT_LENGTH,)
        rr_features: list of np.ndarray shape (N_RR_FEATURES,)
        labels     : list of int
    """
    record_path = os.path.join(DATA_DIR, str(record_num))
    try:
        record     = wfdb.rdrecord(record_path)
        annotation = wfdb.rdann(record_path, 'atr')
        ecg_signal = record.p_signal[:, channel]

        ecg_resampled = firmware_filter(downsample_signal(ad8232_analog(ecg_signal)))

        # ── First pass: every annotated beat, including discarded classes ──
        # The firmware detects F/Q/paced beats too, so their timing must stay
        # in the RR sequence even though they are not used as labels.
        all_beats = []   # (r_peak_250Hz, label)
        for r_orig, sym in zip(annotation.sample, annotation.symbol):
            if sym not in AAMI_CLASSES:
                continue
            r_peak = snap_rpeak(ecg_resampled, scale_rpeak(r_orig))
            all_beats.append((r_peak, AAMI_CLASSES[sym]))

        if not all_beats:
            return [], [], []

        r_peaks_all = [v[0] for v in all_beats]
        rr_all      = compute_rr_features(r_peaks_all)   # shape (n_beats, 3)

        # ── Second pass: extract windows and keep matching RR rows ──────
        beats       = []
        rr_features = []
        labels      = []

        for i, (r_peak, lbl) in enumerate(all_beats):
            if lbl == -1 or np.isnan(rr_all[i, 0]):
                continue
            beat = extract_beat(ecg_resampled, r_peak)
            if beat is not None:
                beats.append(beat)
                rr_features.append(rr_all[i])
                labels.append(lbl)

        return beats, rr_features, labels

    except Exception as exc:
        print(f"  Error processing record {record_num}: {exc}")
        return [], [], []


def process_split(record_numbers, split_name):
    """Process all records in a split and concatenate results."""
    all_beats = []
    all_rr    = []
    all_labels = []

    print(f"\nProcessing {split_name} split...")
    for rec in tqdm(record_numbers, desc=split_name):
        beats, rr, labels = process_record(rec)
        all_beats.extend(beats)
        all_rr.extend(rr)
        all_labels.extend(labels)

    X  = np.array(all_beats,  dtype=np.float32)
    RR = np.array(all_rr,     dtype=np.float32)
    y  = np.array(all_labels, dtype=np.int32)

    if len(X) > 0:
        assert X.shape[1]  == BEAT_LENGTH,    f"Beat length mismatch: {X.shape[1]} != {BEAT_LENGTH}"
        assert RR.shape[1] == N_RR_FEATURES,  f"RR feature count mismatch: {RR.shape[1]} != {N_RR_FEATURES}"

    print(f"  {split_name}: {len(y):,} beats  ECG shape {X.shape}  RR shape {RR.shape}")
    return X, RR, y


# ============================================================================
# CLASS FILTERING
# ============================================================================

def filter_common_classes(X_train, RR_train, y_train,
                           X_val,   RR_val,   y_val,
                           X_test,  RR_test,  y_test):
    """Keep only classes present in ALL three splits."""
    train_cls  = set(np.unique(y_train).tolist())
    val_cls    = set(np.unique(y_val).tolist())
    test_cls   = set(np.unique(y_test).tolist())
    common_cls = sorted(train_cls & val_cls & test_cls)

    print(f"\nClass analysis:")
    print(f"  Train only : {train_cls - val_cls - test_cls}")
    print(f"  Val only   : {val_cls  - train_cls - test_cls}")
    print(f"  Test only  : {test_cls - train_cls - val_cls}")
    print(f"  Common     : {common_cls}")

    all_cls = train_cls | val_cls | test_cls
    if len(common_cls) < len(all_cls):
        dropped = sorted(all_cls - set(common_cls))
        print(f"\n⚠  Dropping classes absent from ≥1 split: "
              f"{[CLASS_NAMES.get(c, c) for c in dropped]}")
        for arr_set in [(X_train, RR_train, y_train),
                        (X_val,   RR_val,   y_val),
                        (X_test,  RR_test,  y_test)]:
            pass   # handled below with masks

        t_mask = np.isin(y_train, common_cls)
        v_mask = np.isin(y_val,   common_cls)
        s_mask = np.isin(y_test,  common_cls)

        X_train, RR_train, y_train = X_train[t_mask], RR_train[t_mask], y_train[t_mask]
        X_val,   RR_val,   y_val   = X_val[v_mask],   RR_val[v_mask],   y_val[v_mask]
        X_test,  RR_test,  y_test  = X_test[s_mask],  RR_test[s_mask],  y_test[s_mask]
        print(f"  Removed: {(~t_mask).sum()} train, {(~v_mask).sum()} val, {(~s_mask).sum()} test")

    # Remap labels to consecutive integers
    label_map   = {old: new for new, old in enumerate(common_cls)}
    reverse_map = {new: old for old, new in label_map.items()}

    y_train = np.array([label_map[y] for y in y_train], dtype=np.int32)
    y_val   = np.array([label_map[y] for y in y_val],   dtype=np.int32)
    y_test  = np.array([label_map[y] for y in y_test],  dtype=np.int32)

    print("\nFinal label mapping:")
    for new_id, old_id in reverse_map.items():
        print(f"  {new_id} ← {CLASS_NAMES[old_id]}")

    return (X_train, RR_train, y_train,
            X_val,   RR_val,   y_val,
            X_test,  RR_test,  y_test,
            label_map, reverse_map)


# ============================================================================
# NORMALIZATION
# ============================================================================

def normalize_ecg_splits(X_train, X_val, X_test):
    """
    Fit StandardScaler on training ECG only, apply to all splits.
    Flattens to per-sample scalar normalization (same as before).
    """
    scaler = StandardScaler()
    scaler.fit(X_train.reshape(-1, 1))

    n_tr, n_v, n_te = len(X_train), len(X_val), len(X_test)
    X_train = scaler.transform(X_train.reshape(-1, 1)).reshape(n_tr, BEAT_LENGTH)
    X_val   = scaler.transform(X_val.reshape(-1, 1)).reshape(n_v,   BEAT_LENGTH)
    X_test  = scaler.transform(X_test.reshape(-1, 1)).reshape(n_te,  BEAT_LENGTH)
    return X_train, X_val, X_test, scaler


def normalize_rr_splits(RR_train, RR_val, RR_test):
    """
    Fit StandardScaler on training RR features only.
    RR features are ratios but may have different scales per feature,
    so per-feature standardization brings them onto a common scale for
    the dense layer. Fitted on training data only — no leakage.
    """
    rr_scaler = StandardScaler()
    rr_scaler.fit(RR_train)

    RR_train = rr_scaler.transform(RR_train).astype(np.float32)
    RR_val   = rr_scaler.transform(RR_val).astype(np.float32)
    RR_test  = rr_scaler.transform(RR_test).astype(np.float32)
    return RR_train, RR_val, RR_test, rr_scaler


# ============================================================================
# CLASS WEIGHTS
# ============================================================================

def compute_weights(y_train):
    """Sqrt-inverse-frequency class weights, normalized to mean=1."""
    classes = np.unique(y_train)
    counts  = Counter(y_train.tolist())
    total   = len(y_train)

    weights = {cls: (total / (len(classes) * counts[cls])) ** 0.5
               for cls in classes}

    mean_w  = sum(weights.values()) / len(classes)
    weights = {k: v / mean_w for k, v in weights.items()}

    print("\nClass weights (sqrt-inverse, mean=1):")
    for cls, w in sorted(weights.items()):
        print(f"  Class {cls} ({CLASS_NAMES.get(cls,'?')}): {w:.3f}  (n={counts[cls]:,})")
    return weights


# ============================================================================
# SAVE
# ============================================================================

def save_data(X_train, RR_train, y_train,
              X_val,   RR_val,   y_val,
              X_test,  RR_test,  y_test,
              ecg_scaler, rr_scaler,
              class_weights, reverse_map, output_dir):
    """Save all arrays, scalers, and metadata."""
    os.makedirs(output_dir, exist_ok=True)

    np.save(os.path.join(output_dir, 'X_train.npy'),  X_train)
    np.save(os.path.join(output_dir, 'RR_train.npy'), RR_train)
    np.save(os.path.join(output_dir, 'y_train.npy'),  y_train)
    np.save(os.path.join(output_dir, 'X_val.npy'),    X_val)
    np.save(os.path.join(output_dir, 'RR_val.npy'),   RR_val)
    np.save(os.path.join(output_dir, 'y_val.npy'),    y_val)
    np.save(os.path.join(output_dir, 'X_test.npy'),   X_test)
    np.save(os.path.join(output_dir, 'RR_test.npy'),  RR_test)
    np.save(os.path.join(output_dir, 'y_test.npy'),   y_test)

    with open(os.path.join(output_dir, 'ecg_scaler.pkl'), 'wb') as f:
        pickle.dump(ecg_scaler, f)
    with open(os.path.join(output_dir, 'rr_scaler.pkl'), 'wb') as f:
        pickle.dump(rr_scaler, f)
    with open(os.path.join(output_dir, 'class_weights.pkl'), 'wb') as f:
        pickle.dump(class_weights, f)

    class_names = {new: CLASS_NAMES[old] for new, old in reverse_map.items()}

    metadata = {
        'n_train'        : len(y_train),
        'n_val'          : len(y_val),
        'n_test'         : len(y_test),
        'beat_length'    : BEAT_LENGTH,
        'window_before'  : WINDOW_SIZE_BEFORE,
        'window_after'   : WINDOW_SIZE_AFTER,
        'n_rr_features'  : N_RR_FEATURES,
        'n_classes'      : len(class_names),
        'class_names'    : class_names,
        'class_weights'  : class_weights,
        'sampling_rate'  : TARGET_FS,
        'original_fs'    : ORIGINAL_FS,
        'resample_ratio' : f'{RESAMPLE_UP}/{RESAMPLE_DOWN}',
        'lowcut_hz'      : LOWCUT,
        'highcut_hz'     : HIGHCUT,
        'filter'         : 'AD8232 Fig. 66 analogue + firmware causal biquads',
        'rr_mean_window' : RR_MEAN_WINDOW,
        'train_records'  : TRAIN_RECORDS,
        'val_records'    : VAL_RECORDS,
        'test_records'   : TEST_RECORDS,
    }

    with open(os.path.join(output_dir, 'metadata.pkl'), 'wb') as f:
        pickle.dump(metadata, f)

    print(f"\n✔ Saved to: {os.path.abspath(output_dir)}")
    print(f"  Train : {len(y_train):,}  ECG {X_train.shape}  RR {RR_train.shape}")
    print(f"  Val   : {len(y_val):,}  ECG {X_val.shape}  RR {RR_val.shape}")
    print(f"  Test  : {len(y_test):,}  ECG {X_test.shape}  RR {RR_test.shape}")
    print(f"  Beat  : {BEAT_LENGTH} samples @ {TARGET_FS} Hz  "
          f"({WINDOW_SIZE_BEFORE} pre / {WINDOW_SIZE_AFTER} post)")
    print(f"  RR features : {N_RR_FEATURES}  (pre_rr_norm, post_rr_norm, ratio_rr)")
    print(f"  Classes     : {len(class_names)} — {list(class_names.values())}")


# ============================================================================
# MAIN
# ============================================================================

def main():
    print("=" * 70)
    print("ECG PREPROCESSING")
    print(f"  Window : {WINDOW_SIZE_BEFORE} pre + {WINDOW_SIZE_AFTER} post = "
          f"{BEAT_LENGTH} samples ({BEAT_LENGTH/TARGET_FS:.3f} s @ {TARGET_FS} Hz)")
    print(f"  Resample: {ORIGINAL_FS} → {TARGET_FS} Hz ({RESAMPLE_UP}/{RESAMPLE_DOWN})")
    print(f"  RR features: {N_RR_FEATURES}")
    print("=" * 70)

    if not os.path.exists(DATA_DIR):
        print(f"\nError: data directory not found:\n  {DATA_DIR}")
        return

    use_subset = input("\nUse subset for a quick test run? (y/n): ")
    if use_subset.lower() in ['y', 'yes']:
        train_recs = TRAIN_RECORDS[:5]
        val_recs   = VAL_RECORDS[:2]
        test_recs  = TEST_RECORDS[:2]
        print("  ⚡ Subset mode")
    else:
        train_recs = TRAIN_RECORDS
        val_recs   = VAL_RECORDS
        test_recs  = TEST_RECORDS

    # ── Step 1: Extract beats + RR features ───────────────────────────────
    print("\nStep 1: Extracting beats and RR features")
    X_train, RR_train, y_train = process_split(train_recs, "Train")
    X_val,   RR_val,   y_val   = process_split(val_recs,   "Val")
    X_test,  RR_test,  y_test  = process_split(test_recs,  "Test")

    print(f"\nExtracted: {len(y_train):,} train | {len(y_val):,} val | {len(y_test):,} test")

    # ── Step 2: Filter to classes present in all splits ───────────────────
    print("\nStep 2: Filtering to common classes")
    (X_train, RR_train, y_train,
     X_val,   RR_val,   y_val,
     X_test,  RR_test,  y_test,
     label_map, reverse_map) = filter_common_classes(
        X_train, RR_train, y_train,
        X_val,   RR_val,   y_val,
        X_test,  RR_test,  y_test
    )
    print(f"\nAfter filtering: {len(y_train):,} train | {len(y_val):,} val | {len(y_test):,} test")

    # ── Step 3: Normalize ECG and RR separately ────────────────────────────
    print("\nStep 3: Normalizing")
    print("  ECG  — StandardScaler fitted on train ECG samples")
    X_train, X_val, X_test, ecg_scaler = normalize_ecg_splits(X_train, X_val, X_test)

    print("  RR   — StandardScaler fitted on train RR features")
    RR_train, RR_val, RR_test, rr_scaler = normalize_rr_splits(RR_train, RR_val, RR_test)

    # ── Step 4: Class weights ──────────────────────────────────────────────
    print("\nStep 4: Computing class weights")
    class_weights = compute_weights(y_train)

    # ── Step 5: Save ───────────────────────────────────────────────────────
    print("\nStep 5: Saving")
    save_data(X_train, RR_train, y_train,
              X_val,   RR_val,   y_val,
              X_test,  RR_test,  y_test,
              ecg_scaler, rr_scaler,
              class_weights, reverse_map, OUTPUT_DIR)

    print("\n" + "=" * 70)
    print("✔ COMPLETE")
    print(f"  Beat shape : ({BEAT_LENGTH},)  + RR shape: ({N_RR_FEATURES},)")
    print(f"  Model takes TWO inputs: ecg_input + rr_input")
    print("=" * 70)
    print("\nNext: python scripts/04_train_model.py")


if __name__ == "__main__":
    main()