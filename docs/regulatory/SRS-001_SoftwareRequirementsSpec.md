# Software Requirements Specification
## ECG Monitor — Android Application and Device Firmware Interface
### IEC 62304:2015 Compliant

**Document ID:** SRS-001  
**Version:** 1.1  
**Date:** 2026-10-16  
**Software Safety Class:** Class B (harm possible but not life-threatening if mitigations in place) — applied as a design discipline, not a claimed classification  
**Status:** DRAFT  

> **Regulatory status:** Educational project. This document follows the structure of IEC 62304 as a design discipline. The system is **not a certified medical device**, has not been assessed by a Notified Body, and must not be used for clinical purposes.

---

## 1. Intended Use

The ECG Monitor is an educational single-lead ECG arrhythmia monitor. An ESP32-S3 device with an AD8232 front end acquires and filters one ECG lead, detects heartbeats, and streams the waveform and per-beat windows over Bluetooth LE to the Android app. The app classifies each beat (Normal / Supraventricular / Ventricular), displays the results, and stores sessions. It is intended for learning and demonstration by adults, not for clinical diagnosis, patient monitoring, or any treatment decision.

---

## 2. System Context

| Component | Role |
|-----------|------|
| AD8232 board (red "AD8232 Heart Monitor" V502) | Analogue ECG front end: 2-pole HP 0.48 Hz, 2-pole LP 41 Hz, gain ≈1100 |
| ESP32-S3 firmware | Samples ECG at 250 Hz; filters; detects beats; extracts beat windows and RR features; transmits via BLE (§3.9) |
| Android Application | Receives, displays, classifies (TensorFlow Lite on the phone), stores, and analyses ECG data |
| Room Database (local) | Persistent storage of sessions, readings, events, consent records, audit log |
| Firebase Firestore (cloud) | Receives session metadata and arrhythmia events; raw waveforms are not uploaded (§3.10) |
| ML pipeline (offline, Python) | Trains the classifier on MIT-BIH and produces `arrhythmia_model.tflite` + `model_metadata.json` |
| User | Applies electrodes; initiates recordings; reviews results |

---

## 3. Functional Requirements

### 3.1 BLE Communication

| ID | Requirement | Priority | Risk Ref |
|----|-------------|----------|----------|
| FR-BLE-01 | The app shall scan for nearby BLE devices and display only those with valid device names | Must | RISK-008 |
| FR-BLE-02 | The app shall request required Bluetooth permissions before scanning | Must | — |
| FR-BLE-03 | The app shall display current connection status on the Live Monitor screen | Must | RISK-008 |
| FR-BLE-04 | The app shall automatically stop recording if BLE connection is lost | Must | RISK-008 |
| FR-BLE-05 | The app shall notify the user of BLE disconnection during recording | Must | RISK-008 |

### 3.2 ECG Acquisition and Display

| ID | Requirement | Priority | Risk Ref |
|----|-------------|----------|----------|
| FR-ECG-01 | The app shall display a live ECG waveform during an active BLE connection | Must | — |
| FR-ECG-02 | The waveform display shall update at minimum every 100ms | Must | — |
| FR-ECG-03 | The app shall display the current heart rate derived from the R-R intervals of the beats detected by the device firmware (FR-FW-03) | Must | RISK-010 |
| FR-ECG-04 | The app shall display a signal quality indicator | Must | RISK-003 |
| FR-ECG-05 | Sampling rate shall be 250 Hz, matching the device firmware (FR-FW-01) and the model metadata | Must | RISK-014 |
| FR-ECG-06 | The app shall show electrode placement guidance for the supplied cable (lead II: red = RA, green = LA, yellow = RL) before recording | Should | RISK-011, RISK-012 |

### 3.3 Recording

| ID | Requirement | Priority | Risk Ref |
|----|-------------|----------|----------|
| FR-REC-01 | The user shall be able to start and stop a recording session | Must | — |
| FR-REC-02 | Recording shall only be available when a BLE device is connected | Must | — |
| FR-REC-03 | The app shall write recording data incrementally during capture | Must | RISK-006 |
| FR-REC-04 | Sessions interrupted by app crash or BLE loss shall be marked as incomplete | Must | RISK-006 |
| FR-REC-05 | The user shall be informed if a recording is incomplete | Must | RISK-006 |
| FR-REC-06 | Minimum recording length is 10 seconds | Should | RISK-001 |

### 3.4 Arrhythmia Detection

| ID | Requirement | Priority | Risk Ref |
|----|-------------|----------|----------|
| FR-ARR-01 | The app shall classify each received beat as one of three AAMI classes: Normal (N), Supraventricular ectopic (S), or Ventricular ectopic (V). The app shall not claim to detect atrial fibrillation | Must | RISK-001, RISK-002 |
| FR-ARR-02 | Detection confidence score shall be stored with each event | Must | RISK-001 |
| FR-ARR-03 | Health status shall be determined as: Normal / Attention / Concerning per defined thresholds | Must | RISK-001, RISK-007 |
| FR-ARR-04 | Health status thresholds: Concerning = >20% PVCs OR >20% SVEBs; Attention = >10% PVCs OR >10% SVEBs OR average HR >100 or <50; Normal = otherwise | Must | RISK-001, RISK-002 |
| FR-ARR-05 | The app shall not use the word "diagnosis" or "condition" in health status displays | Must | RISK-007 |
| FR-ARR-06 | The class shall be chosen as `argmax(scores / thresholds)`, using the per-class thresholds in `model_metadata.json` that the training pipeline tuned for this rule | Must | RISK-001, RISK-002, RISK-014 |
| FR-ARR-07 | The app shall convert firmware ADC counts to millivolts (`ADC_COUNTS_TO_MV` = (3100/4096)/1100) before normalising beat windows with the training scaler | Must | RISK-009, RISK-014 |
| FR-ARR-08 | The model (`arrhythmia_model.tflite`) and its `model_metadata.json` shall be shipped as a pair from the same training run, and the model version that produced each session's classifications shall be stored with the session | Must | RISK-014 |
| FR-ARR-09 | Events shall be placed in time by the beat's own timestamp from the device, not by packet arrival time (beat packets arrive one RR interval late) | Must | RISK-015 |
| FR-ARR-10 | An informational "not a medical device" notice shall be visible on the Live Monitor and Session Detail screens, not only on the consent screen | Must | RISK-007 |

### 3.5 Session History

| ID | Requirement | Priority | Risk Ref |
|----|-------------|----------|----------|
| FR-HIS-01 | The app shall list all stored sessions with summary statistics | Must | — |
| FR-HIS-02 | Sessions shall be sortable by: date, duration, arrhythmia count | Must | — |
| FR-HIS-03 | The user shall be able to view a session in detail, including waveform playback | Must | — |
| FR-HIS-04 | The user shall be able to export a session as PDF and CSV | Should | — |

### 3.6 Consent and Data Governance

| ID | Requirement | Priority | Risk Ref |
|----|-------------|----------|----------|
| FR-CON-01 | The app shall display a consent screen on first launch | Must | RISK-005 |
| FR-CON-02 | The user must actively accept consent before accessing any recording functionality | Must | RISK-005 |
| FR-CON-03 | Consent acceptance shall be recorded with timestamp and consent version | Must | RISK-005 |
| FR-CON-04 | The user shall be re-prompted if the consent version has changed | Must | RISK-005 |
| FR-CON-05 | The user shall be able to withdraw consent from the Settings screen | Must | RISK-005 |
| FR-CON-06 | Withdrawing consent shall trigger deletion of all user-generated data | Must | RISK-005 |
| FR-CON-07 | Consent records shall not be deleted, even when other data is erased | Must | RISK-005 |
| FR-CON-08 | The consent text shall accurately describe all data transmission, including the cloud upload of session metadata and events (§3.10) | Must | RISK-005 |

### 3.7 Audit Logging

| ID | Requirement | Priority | Risk Ref |
|----|-------------|----------|----------|
| FR-AUD-01 | The app shall log: session recorded, session viewed, session deleted, session exported, all-data-deleted, consent-granted, consent-withdrawn, app-opened | Must | RISK-005 |
| FR-AUD-02 | Each audit entry shall include: timestamp, action type, session ID (where applicable) | Must | RISK-005 |
| FR-AUD-03 | Audit log shall be stored locally and preserved until user explicitly deletes all data | Must | RISK-005 |

### 3.8 Data Deletion

| ID | Requirement | Priority | Risk Ref |
|----|-------------|----------|----------|
| FR-DEL-01 | The Settings screen shall provide a "Delete All Recordings" option | Must | RISK-005 |
| FR-DEL-02 | Deletion shall require a confirmation dialog before executing | Must | RISK-005 |
| FR-DEL-03 | The confirmation dialog shall explain what is deleted and what is retained | Must | RISK-005 |
| FR-DEL-04 | Deletion shall remove: all sessions, readings, arrhythmia events, and audit log entries | Must | RISK-005 |

### 3.9 Device Firmware Interface (ESP32-S3)

These requirements specify the device behaviour that the app and the trained model depend on. The values are cross-project contracts: they are duplicated in `config.h`, `preprocess.py`, `model_metadata.json`, `validate.py`, and the app, with no runtime check that they agree.

| ID | Requirement | Priority | Risk Ref |
|----|-------------|----------|----------|
| FR-FW-01 | The firmware shall sample the AD8232 output at 250 Hz (ADC1, GPIO1) | Must | RISK-014 |
| FR-FW-02 | The firmware shall filter the ECG with the fixed causal biquads in `config.h` (HP 0.5 Hz, LP 40 Hz, notch 50 Hz). The training pipeline shall apply the identical coefficients | Must | RISK-003, RISK-014 |
| FR-FW-03 | The firmware shall detect R-peaks with a Pan-Tompkins detector that tracks signal and noise peak levels separately and keeps the threshold above a floor relative to the signal level | Must | RISK-010 |
| FR-FW-04 | The beat fiducial shall be the QRS slope-energy centroid (Σt·d²/Σd² of the filtered first difference) over ±15 samples, refined 3 times | Must | RISK-010, RISK-014 |
| FR-FW-05 | Each beat window shall contain 128 filtered samples: 96 before and 32 after the fiducial | Must | RISK-014 |
| FR-FW-06 | Each beat shall carry 3 RR features `[pre_rr_norm, post_rr_norm, ratio_rr]`, normalised by the mean of the last 8 RR intervals up to `pre_rr`. `post_rr` shall be the real next interval, so each beat is sent at the next R-peak; the first and last beat are not sent | Must | RISK-014, RISK-015 |
| FR-FW-07 | Lead-off shall be debounced over 250 ms: shorter dropouts hold the last good sample and do not reset the detector; only a sustained lead-off resets the signal chain | Must | RISK-003, RISK-010 |
| FR-FW-08 | No beat shall be sent for 2 s after power-up or leads-on, while the analogue and digital high-pass filters settle | Must | RISK-012 |
| FR-FW-09 | The firmware shall expose the BLE GATT service with a stream characteristic (`DataPacket`: sequence number, timestamp, lead-off byte, 10 samples, 26 B) and a beat characteristic (`BeatPacket`: marker, beat timestamp, beat number, 128 samples, 3 RR features, 275 B), with UUIDs as defined in `config.h` `BLECfg` | Must | RISK-008, RISK-014 |

### 3.10 Cloud Synchronisation

| ID | Requirement | Priority | Risk Ref |
|----|-------------|----------|----------|
| FR-SYNC-01 | Cloud sync shall upload session metadata (statistics, health status, model-health scalars) and arrhythmia events only. Raw ECG readings shall never leave the device | Must | RISK-005 |
| FR-SYNC-02 | Cloud data shall be stored under a per-user path keyed by a Firebase anonymous-auth UID | Must | RISK-005 |
| FR-SYNC-03 | Sessions whose upload has not been acknowledged by the server shall stay queued and be retried at the next launch | Should | — |
| FR-SYNC-04 | "Delete All" and consent withdrawal shall also delete the user's cloud data | Must | RISK-005 |
| FR-SYNC-05 | Deleting a single session shall also delete its cloud copy | Must | RISK-005 |

### 3.11 Signal Quality and Model Health

| ID | Requirement | Priority | Risk Ref |
|----|-------------|----------|----------|
| FR-SQ-01 | Each beat window shall be scored for signal quality (flags FLAT, SATURATED, NOISY, BASELINE_WANDER; 0–1 composite score) | Must | RISK-003, RISK-009, RISK-012 |
| FR-SQ-02 | An abnormal classification on a beat whose quality is not usable shall be withheld from the events and counted as suppressed | Must | RISK-002, RISK-003, RISK-009 |
| FR-SQ-03 | The app shall display a live drift score (mean \|z\| of the input against the training scaler), banded against the training value `EXPECTED_DRIFT_SCORE` | Must | RISK-011, RISK-014 |
| FR-SQ-04 | Per-session model-health scalars (confidence, low-confidence rate, entropy, poor-signal rate, suppressed events, drift, classification rate and its plausibility flag, inference time) shall be stored with the session | Should | RISK-014 |
| FR-SQ-05 | The Live Monitor shall display lead-off status and a live T/R amplitude ratio as an electrode-placement indicator | Must | RISK-003, RISK-011, RISK-012 |

---

## 4. Non-Functional Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| NFR-01 | App shall support Android API 26 (Android 8) and above | Must |
| NFR-02 | App shall not transmit raw ECG waveform data over the network. The only network transmission is the metadata and events in §3.10 | Must |
| NFR-03 | Live waveform rendering shall not drop below 15 FPS | Should |
| NFR-04 | App shall not crash during a recording session (crash rate < 0.1% of sessions) | Must |
| NFR-05 | Database write latency for recording samples shall not exceed 50ms | Should |
| NFR-06 | All user-facing health status messages shall be reviewed for clarity by a non-technical user | Must |
| NFR-07 | Consent text shall be reviewed by a legal or regulatory professional before release | Must |
| NFR-08 | Health data shall be excluded from Android cloud backup and from device-to-device transfer | Must |

---

## 5. Constraints and Assumptions

- Algorithm accuracy depends on electrode placement reproducing lead II (the model is trained on MIT-BIH channel 0, MLII) and on fresh, settled electrode pads. This kit's cable colours are non-standard (red = RA, green = LA, yellow = RL)
- The ADC full scale is about ±1.41 mV referred to the electrodes; larger QRS complexes clip on the device but not in training
- The app depends on Android BLE stack reliability, which varies by device manufacturer
- MIT-BIH (22 DS1 records for training and validation, 22 DS2 records for testing) is used for development and validation only; a clinical dataset would be required for any regulatory submission
- The device is USB-powered and has no galvanic isolation. It has not been tested to IEC 60601-1 and must only be connected to a person when powered from a battery or through a USB isolator (see RMF-001 RISK-004)

---

## 6. Traceability Summary

| Requirement Group | Related Risk(s) | Related Test Cases |
|-------------------|-----------------|-------------------|
| FR-BLE | RISK-008 | TC-BLE-01 to TC-BLE-05 |
| FR-ECG | RISK-003, RISK-010, RISK-011, RISK-012, RISK-014 | TC-ECG-01 to TC-ECG-06 |
| FR-REC | RISK-001, RISK-006 | TC-REC-01 to TC-REC-06 |
| FR-ARR | RISK-001, RISK-002, RISK-007, RISK-009, RISK-014, RISK-015 | TC-ARR-01 to TC-ARR-12, TC-ML-01, TC-HW-01, TC-WEAR-01 |
| NFR | RISK-005, RISK-006, RISK-007 | TC-PERF-01 to TC-PERF-03, TC-PRIV-01, TC-SYNC-01 |
| FR-CON, FR-AUD, FR-DEL | RISK-005 | TC-CON-01 to TC-CON-08, TC-AUD-01 to TC-AUD-06, TC-DEL-01 to TC-DEL-04 |
| FR-FW | RISK-003, RISK-008, RISK-010, RISK-012, RISK-014, RISK-015 | TC-FW-01 to TC-FW-10, TC-PAR-01, TC-HIL-01, TC-DET-01 |
| FR-SYNC | RISK-005 | TC-SYNC-01 to TC-SYNC-05 |
| FR-SQ | RISK-002, RISK-003, RISK-009, RISK-011, RISK-012, RISK-014 | TC-SQ-01 to TC-SQ-05 |

*Test cases are defined in the Software Verification & Validation Plan (SVVP-001); the full requirement ↔ risk ↔ test links are in TM-001.*

---

## 7. Document History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2026-01-01 | [Author] | Initial draft |
| 1.1 | 2026-10-16 | Oliver Moberg | Educational status stated. Intended use and system context updated (ESP32-S3 + AD8232, on-phone inference, Firebase). FR-ECG-03/05, FR-ARR-01/04, NFR-02 revised (250 Hz, 3 classes N/S/V, SVEB-graded health status, metadata-only sync). Added FR-ECG-06, FR-ARR-06..10, FR-CON-08, §3.9 FR-FW-01..09, §3.10 FR-SYNC-01..05, §3.11 FR-SQ-01..05, NFR-08. Constraints updated (lead II, ADC headroom, electrical safety) |

*This document must be reviewed by a qualified regulatory professional before Notified Body submission.*
