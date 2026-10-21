# Machine Learning Development and Data Management
## ECG Arrhythmia Monitor — Beat Classifier
### EU AI Act Art. 9–15 / AAMI CR34971 / GMLP, applied alongside IEC 62304 and ISO 14971

**Document ID:** MLD-001  
**Version:** 1.0  
**Date:** 2026-10-21  
**Status:** DRAFT  

> **Regulatory status:** Educational project. The system is **not a certified medical device**, has not been assessed by a Notified Body, and must not be used for clinical purposes. This document applies the requirements that would govern an AI-enabled medical device as a design discipline, and states plainly where the project does not meet them.

---

## 1. Purpose and Regulatory Context

The MDR is technology-neutral: a machine-learning model is "software" under Annex VIII Rule 11 and GSPR 17, and the MDR has no specific requirements for training data, bias, model updates or drift. This document covers those topics for the beat classifier. The documents it sits alongside are SRS-001 (requirements), RMF-001 (risk), SVVP-001 (verification), SAD-001 (architecture), SDP-001 (development process) and TM-001 (traceability).

| Framework | Relevance to this classifier |
|-----------|------------------------------|
| MDR 2017/745 | Software for arrhythmia detection would be at least Class IIa under Rule 11, and so needs a Notified Body |
| EU AI Act 2024/1689 | An AI device that needs a Notified Body is **high-risk** under Art. 6(1). The Art. 9–15 and 17 obligations apply from **2 August 2028** for AI in Annex I products (Digital Omnibus, Regulation 2026/1744) |
| MDCG 2025-6 | AI Act requirements are met within the *same* MDR technical file, QMS and conformity assessment; the AI Act does not change the MDR class |
| AAMI CR34971:2022 | Application of ISO 14971 to machine learning (used for §6) |
| GMLP (FDA / Health Canada / MHRA, 2021) | Ten guiding principles for good machine learning practice (used in §12) |
| ISO/IEC 23894, ISO/IEC 42001 | AI risk management and AI management systems; referenced, not applied |

Section numbers below map to AI Act articles where one applies.

---

## 2. Model Description (Model Card)

| Item | Detail |
|------|--------|
| **Function** | Classifies each detected heartbeat as Normal (N), Supraventricular ectopic (S) or Ventricular ectopic (V), following the AAMI EC57 superclasses |
| **Not intended for** | Atrial fibrillation or any rhythm-level diagnosis; fusion (F), paced or unclassifiable (Q) beats; leads other than lead II; paediatric use; any clinical decision (SRS-001 §1, FR-ARR-01) |
| **Inputs** | 128-sample beat window at 250 Hz (96 before / 32 after the QRS slope-energy centroid), in mV, normalised with the training scaler; 3 RR features `[pre_rr_norm, post_rr_norm, ratio_rr]`, normalised per feature |
| **Output** | Softmax probabilities for N/S/V. Class = `argmax(probs / thresholds)`, thresholds `[0.050, 0.050, 0.068]` tuned on validation (FR-ARR-06) |
| **Architecture** | Residual 1D CNN (stem + 3 residual stages, 32/64/128 filters, global average pooling), RR features concatenated before a 64-unit dense head; ~136 k parameters per member |
| **Ensemble** | Softmax average of 4 independently trained members, merged into one TFLite graph (§4.4) |
| **Shipped artifact** | `arrhythmia_model.tflite`: INT8 weights, float32 inputs and outputs, ~700 KB; `model_metadata.json` with scalers and thresholds |
| **Version identifier** | `ensemble_20261015_212815` (training-run id), stored with every session as `modelVersion` (FR-ARR-08) |
| **Runs on** | The Android phone (TensorFlow Lite 2.14). The ESP32 only supplies beat windows and RR features |
| **Learning mode** | **Locked.** No on-device or field learning; the model changes only through the controlled retraining in §11 |

---

## 3. Data and Data Governance (AI Act Art. 10)

### 3.1 Source

| Item | Detail |
|------|--------|
| Dataset | MIT-BIH Arrhythmia Database v1.0.0 (Moody & Mark 2001), via PhysioNet (Goldberger et al. 2000) |
| Licence | Open Data Commons Attribution License v1.0 |
| Collection | 48 half-hour, two-channel ambulatory (Holter) recordings from 47 subjects, Beth Israel Hospital Boston, 1975–1979. Records 100–124 were chosen at random from a larger Holter set; records 200–234 were selected to include less common but clinically significant arrhythmias |
| Original format | 360 Hz, 11-bit over a 10 mV range |
| Annotation | Beat labels by two or more cardiologists working independently, with disagreements resolved by consensus |
| Lead used | Channel 0 = modified limb lead II (MLII) in 46 of 48 records. `preprocess.py` hardcodes channel 0; records 102 and 104 (V5) are excluded as paced records anyway |

### 3.2 Preparation

| Step | Choice and rationale |
|------|----------------------|
| Signal chain | Raw signal → model of the AD8232 board's analogue filters → resample to 250 Hz → the firmware's causal biquads. The model therefore sees the same morphology the device produces (rationale in `preprocess.py`, "Firmware parity") |
| Fiducial | Annotated R-peaks snapped to the QRS slope-energy centroid exactly as the firmware does |
| Label mapping | N ← N, L, R, e, j; S ← A, a, J, S; V ← V, E. **Dropped:** F (fusion), f, / (paced), Q. Rationale in the `preprocess.py` docstring |
| Excluded records | 102, 104, 107, 217 (paced), per de Chazal et al. 2004 |
| Excluded beats | First and last beat of each record, which have no predecessor or successor, as the firmware also never emits them |
| RR features | The firmware's 8-beat causal running mean, computed over every annotated beat, including dropped classes, so intervals match the device |
| Normalisation | ECG and RR scalers fitted on the training split only |

### 3.3 Splits

Patient-level inter-patient split, de Chazal et al. (2004). No patient's beats appear in more than one split, with one known exception: **records 201 (validation) and 202 (test) come from the same subject** (RMF-001 OA-7, accepted limitation of the standard split).

| Split | Records | Patients (M/F) | Age range (median) | N | S | V | Total |
|-------|---------|----------------|--------------------|---|---|---|-------|
| Train (DS1) | 18 | 10 / 8 | 23–87 (62) | 38,442 | 540 | 2,891 | 41,873 |
| Validation (DS1) | 4: 118, 201, 207, 223 | 3 / 1 | 68–89 (73) | 7,382 | 403 | 897 | 8,682 |
| Test (DS2) | 22 | 11 / 11 | 24–89 (69); 2 unknown | 44,218 | 1,836 | 3,219 | 49,273 |

Record 209, the largest S source in DS1, is kept in training: an earlier split that moved it to validation left only 253 S beats for training and over-fitted the S class.

### 3.4 Representativeness and Known Gaps

| Gap | Effect |
|-----|--------|
| 47 subjects, one hospital, 1970s recording equipment | The population and hardware differ from current users and from the AD8232 board |
| No ethnicity, body habitus or medication data; only age and sex | Bias cannot be assessed beyond age and sex (§5.4) |
| Few S beats in training (540, 1.3 %) | S is the weakest class (§5) |
| 75 % of test S beats come from one record (232) | The S result is close to a single-patient result (§5.3) |
| Holter recordings with clinical electrodes | Device recordings use disposable pads on a home user; ADC clipping above ±1.41 mV happens on the device but not in training (RMF-001 RISK-009) |
| Validation set of 4 records | Threshold tuning and checkpoint selection are noisy (§4.4) |
| Device data from one wearer only | No labelled device-acquired dataset exists (TC-WEAR-01) |

**Not done:** a formal datasheet or bias audit of the training set, and any labelled data collected with the actual device.

---

## 4. Training and Model Selection

### 4.1 Procedure

| Item | Setting |
|------|---------|
| Loss | Sparse categorical cross-entropy with label smoothing 0.1 (Müller et al. 2019) |
| Class imbalance | Class-balanced batch sampling (Johnson & Khoshgoftaar 2019) |
| Optimiser | Adam, LR 3e-4, cosine decay with warm restarts (Loshchilov & Hutter 2017), batch 128, up to 100 epochs |
| Regularisation | L2 1e-3, dropout 0.2–0.4 |
| Early stopping and checkpoint | Both on validation loss (they must monitor the same metric) |
| Thresholds | Per-class thresholds chosen on validation to maximise macro-F1 for the `argmax(probs / thresholds)` rule |

### 4.2 Test-set Discipline

DS2 is used only to report results. It is not used for early stopping, checkpointing, threshold tuning or choosing between runs. Choosing the best single run by its DS2 score would be selection on the test set; this is why the ensemble in §4.4 is used instead.

### 4.3 Conversion

Keras → TFLite with INT8 weights, calibrated on validation data, float32 inputs and outputs. A float32 reference model (`arrhythmia_model_f32.tflite`) is also produced. The thresholds were tuned against the INT8 build, so switching the app to the float32 model is a classifier behaviour change (§11).

### 4.4 Run-to-run Variance and the Ensemble

Identical training runs gave end-to-end DS2 macro-F1 between 0.68 and 0.78 (8 runs, single-run mean 0.743 ± 0.025). The spread comes from model selection resting on 4 validation records, not from the architecture. Averaging 4 independent runs (Lakshminarayanan et al. 2017) reduces this variance without using the test set. The ensemble's thresholds are tuned on validation only.

---

## 5. Performance Evaluation (AI Act Art. 15 accuracy)

### 5.1 Protocol

Two evaluation paths on DS2:

- **End-to-end:** board-filtered MIT-BIH through the real firmware `SignalProcessor` (host build), then the INT8 model (TC-ML-01). This is the closest available stand-in for the device.
- **Training path:** the preprocessed DS2 test set, with the same INT8 model and decision rule. Per-class, per-record and subgroup results below use this path, because per-beat record identity is not kept by the end-to-end scripts.

### 5.2 Results

| Metric | End-to-end | Training path |
|--------|-----------|---------------|
| Macro-F1 | **0.772** | 0.779 |
| S (SVEB) F1 | 0.46 | 0.48 |

Training path, per class (2026-10-21 run of the shipped model):

| Class | Beats | Sensitivity | Positive predictivity | F1 |
|-------|-------|-------------|-----------------------|----|
| N | 44,218 | 0.986 | 0.963 | 0.974 |
| S | 1,836 | **0.429** | 0.554 | 0.483 |
| V | 3,219 | 0.792 | 0.988 | 0.879 |

Confusion matrix (rows = true, columns = predicted):

| | N | S | V |
|---|---|---|---|
| **N** | 43,586 | 625 | 7 |
| **S** | 1,025 | 787 | 24 |
| **V** | 662 | 9 | 2,548 |

The dominant error is **missed ectopic beats classified as Normal**: 56 % of S beats and 21 % of V beats. That is the false-negative hazard (RMF-001 RISK-001). For context, de Chazal et al. (2004), on the same split with a linear classifier, reported SVEB sensitivity 75.9 % / positive predictivity 38.5 % and VEB 77.7 % / 81.9 %. This model trades S sensitivity for higher positive predictivity.

### 5.3 Per-patient Variation and Uncertainty

Results vary strongly by patient:

- **S:** record 232 contributes 1,381 of the 1,836 test S beats (75 %), with S sensitivity 0.40. Records 202 (0.36), 210 (0.05) and 213 (0.00) score lower.
- **V:** sensitivity ranges from 0.12 (213) and 0.37 (202) to 0.96–0.98 (200, 221).

Patient-level bootstrap (2,000 resamples of the 22 DS2 records), 95 % intervals:

| Metric | Point | 95 % interval |
|--------|-------|---------------|
| Macro-F1 | 0.779 | 0.650–0.810 |
| S F1 | 0.483 | **0.109–0.540** |
| V F1 | 0.879 | 0.672–0.955 |

With 22 test patients, the S result is not known to better than roughly ±0.2. That uncertainty matters more than the second decimal of any single figure.

### 5.4 Subgroups (age and sex)

| Subgroup | Records | Macro-F1 | S sensitivity (n) | V sensitivity (n) |
|----------|---------|----------|-------------------|-------------------|
| Male | 11 | 0.72 | 0.32 (185) | 0.84 (2,806) |
| Female | 11 | 0.71 | 0.44 (1,651) | 0.49 (413) |
| Age < 65 | 9 | 0.65 | 0.07 (121) | 0.83 (2,138) |
| Age ≥ 65 | 11 | 0.77 | 0.46 (1,706) | 0.72 (1,017) |

The differences are large, but **they cannot be attributed to age or sex**. Each subgroup has 9–11 patients, and its result is dominated by which arrhythmias those patients happen to have. For example, female V sensitivity is driven mostly by record 228 (362 V beats, sensitivity 0.45), and female S results by record 232. Separating a real demographic effect would need many more patients per group. This is recorded as a limitation, not as evidence of fairness or of bias.

### 5.5 Device and Wearer Evidence

- One healthy wearer, with the verified lead II placement: 100 % of beats Normal, mean confidence 0.93 (TC-WEAR-01).
- Board gain measured at ≈1000–1090 vs 1100 assumed (TC-HW-01).
- No arrhythmia has been recorded with the device, so device-level sensitivity is unmeasured.

### 5.6 Acceptance Criteria

**None are defined** (SVVP-001 §6). A product would fix minimum sensitivity and positive predictivity per class, with confidence bounds, before the test set is opened. Against any reasonable clinical criterion, the S class would fail. This is acceptable only because the intended use is educational.

---

## 6. ML-Specific Risk Analysis (AAMI CR34971)

Machine-learning failure modes and where they are controlled. Severity and probability scoring is in RMF-001.

| ML hazard | Control | Status | RMF-001 |
|-----------|---------|--------|---------|
| Missed ectopic beats (false negatives), esp. S | Ensemble; RR features; per-class thresholds; educational intended use; disclaimer | Residual Medium, accepted for education only | RISK-001 |
| Distribution shift: device vs MIT-BIH (hardware, electrodes, population) | Training reproduces the firmware chain; drift score vs training; signal-quality gate | Partial — no labelled device data | RISK-003, RISK-014 |
| Contract mismatch between firmware, pipeline and app | Contract table; parity tests; CI artifact and contract check | Implemented | RISK-014 |
| ADC clipping on device, not in training | Rail-clipping quality flag | Partial | RISK-009 |
| Over-fitting to a 4-record validation set | Ensemble; test set used only for reporting | Implemented; validation set still small | RISK-001 |
| Label noise and dropped F/Q classes | Expert consensus labels; F/Q excluded from scope | Accepted | RISK-001 |
| Quantisation error | INT8 calibrated on validation; thresholds tuned on the INT8 build | Implemented | RISK-014 |
| Confidence not used as a gate (`isValid` is always true with the shipped thresholds) | Signal-quality gate instead | Known limitation | RISK-002 |
| Automation bias: user trusts the output | "Not a medical device" notice; graded health status; raw counts shown | Implemented, not usability-tested | RISK-007 |
| Model tampering (APK assets) | None | Not implemented (§9) | — |

---

## 7. Transparency and Human Oversight (AI Act Art. 13–14)

**What the user sees:**
- the class of each detected event and session counts per class;
- a three-level health status (Normal / Attention / Concerning);
- live signal-quality indicators;
- a "not a medical device" notice on the Live Monitor and Session Detail screens.

**What the system does not do:** it takes no autonomous action, raises no alarms, and never blocks the user from seeing raw counts.

**Oversight mechanisms:**
- Abnormal classifications on beats that fail the signal-quality gate are withheld and counted (FR-SQ-02).
- The user can review every session and export it.

**Gaps:**
- The per-event confidence is stored but not shown.
- There are no instructions for use stating the performance figures in §5 and the limitations in §3.4. Only the consent screen and the README describe limitations.
- Comprehension has not been tested with users (IEC 62366).

---

## 8. Record-keeping and Traceability (AI Act Art. 12)

| Record | Where |
|--------|-------|
| Model version per session | `ecg_sessions.modelVersion` = training-run id (FR-ARR-08) |
| Per-event class, confidence and time (device timestamp) | `arrhythmia_events` (FR-ARR-02, FR-ARR-09) |
| Per-session model-health scalars (confidence, entropy, drift, poor-signal rate, suppressed events, inference time) | `ecg_sessions`, synced to Firestore as metadata (FR-SQ-04) |
| User actions | `audit_log` (FR-AUD-01) |
| Training run outputs | `ml_pipeline/models/trained/` (models, `results_*.pkl` with thresholds, plots); kept, gitignored |
| Shipped artifact identity | CI checks app assets against the pipeline output (TC-CI-01) |

**Gaps:**
- A training run does not record the git commit of the code that produced it, or a hash of the processed data.
- Run outputs are local only, not archived.

---

## 9. Robustness and Cybersecurity (AI Act Art. 15)

**Robustness:**
- Lead-off debounce and a settling hold-back in the firmware (FR-FW-07/08).
- Per-beat signal-quality gate (flat, low-amplitude, noisy, clipped, baseline wander).
- A drift score against the training statistics; it exposed a units bug on its first real run.

**Not assessed:** robustness to motion artefact on the real device, other ECG front ends, and other electrode types.

**Cybersecurity:**
- The BLE link has no pairing or encryption, and its config characteristic is writable by any nearby device.
- The model and metadata sit unprotected in the APK assets.
- No threat model exists. Adversarial manipulation of inputs is not considered.

These gaps would have to be closed before any real use (IEC 81001-5-1, MDCG 2019-16).

---

## 10. Post-market Monitoring

Per-session monitoring scalars (§8) are synced to Firestore, so drift, low-confidence rate and poor-signal rate could be monitored across users.

**Not in place:**
- action thresholds on those scalars;
- a process for reviewing them;
- any ground-truth feedback (labelled arrhythmias from the field).

Without ground truth, field performance cannot be measured. Only input drift and signal quality can be.

---

## 11. Change Control for the Model (Predetermined Change Plan)

The model is locked. A new model may ship only through this procedure.

**Allowed without changing this document** (retraining within the current specification):
- retraining on the same data and splits;
- a new ensemble seed;
- hyperparameter changes within §4.1.

**Required for every new model:**
1. `preprocess.py` → `train_model.py --ensemble 4` → `convert_model.py` → `extract_constants.py`, all from the same run, because the scalers are overwritten on every preprocess.
2. Re-run TC-ML-01 end-to-end and record the per-class results in SVVP-001 §7.
3. Re-run TC-PAR-01 if any signal-chain contract changed.
4. Update `ArrhythmiaClassifier.EXPECTED_DRIFT_SCORE`.
5. Copy the model and metadata together into the app's `assets/`; CI (TC-CI-01) must pass.
6. Review the RMF-001 RISK-001/002/014 residuals against the new results.

**Significant changes** need a revision of SRS-001, RMF-001 and this document before release:
- new or merged classes, or a change in intended use;
- a change of decision rule;
- a change of input contract (sample rate, window, fiducial, RR features, units, lead);
- a change of training data or splits;
- INT8 → float32;
- a change of architecture family.

In a real device these would also require Notified Body assessment unless pre-agreed (AI Act Art. 43(4)).

**Gap:** no acceptance criteria exist (§5.6), so this procedure cannot yet decide whether a new model is better or acceptable.

---

## 12. Development Tools, Including AI-assisted Development

| Tool | Use | Control |
|------|-----|---------|
| TensorFlow 2.15, scikit-learn, SciPy, wfdb | Training and evaluation | Pinned in `requirements.txt` (SOUP, SDP-001 §3.2) |
| TensorFlow Lite 2.14 | On-phone inference | Pinned in `libs.versions.toml` |
| AI coding assistant (Claude Code) | Used to write and review code and documentation (commits marked `Co-Authored-By`) | Changes go through pull requests, unit tests, CI and the author's review |

ISO 13485 §4.1.6 requires software tools used in the quality system and in development to be validated for their intended use. **That validation has not been done** for any of these tools. For an AI assistant, validation would mean showing that its outputs are always independently verified, by review and tests. That is the control in place, but it is not documented as a validation.

---

## 13. Gap Summary

| Requirement | Source | Status |
|-------------|--------|--------|
| Risk management covering ML | AI Act Art. 9, ISO 14971, CR34971 | ✅ RMF-001 + §6 |
| Data governance: provenance, splits, no leakage | Art. 10, GMLP 3–5 | ✅ §3 |
| Data representativeness and bias examination | Art. 10(2)(f–g) | ⚠️ §3.4 and §5.4 done, but the dataset cannot support a conclusion |
| Technical documentation of the model | Art. 11, Annex IV | ⚠️ This document; not a full Annex IV file |
| Automatic logging and traceability | Art. 12 | ⚠️ §8; run-to-code link missing |
| Transparency and instructions for use | Art. 13 | ⚠️ §7; no IFU with performance figures |
| Human oversight | Art. 14 | ⚠️ §7; not usability-tested |
| Accuracy, with metrics declared | Art. 15 | ⚠️ §5; no acceptance criteria |
| Robustness | Art. 15 | ⚠️ §9 |
| Cybersecurity | Art. 15, GSPR 17.4 | ❌ §9 |
| Quality management system | Art. 17, MDR Art. 10, ISO 13485 | ❌ Out of scope (see `README.md`) |
| Post-market monitoring | AI Act Art. 72, MDR Art. 83 | ⚠️ Data collected; no process |
| Predetermined change control | Art. 43(4) | ✅ §11 (criteria missing) |
| Clinical performance on the device | MDR Art. 61 | ❌ Out of scope |

---

## 14. Document History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2026-10-21 | Oliver Moberg | Initial version. Per-class, per-record, bootstrap and subgroup results computed for the shipped model `ensemble_20261015_212815` on the training path (macro-F1 0.779, matching the 0.780 recorded at training time) |
