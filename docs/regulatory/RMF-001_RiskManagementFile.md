# Risk Management File
## ECG Arrhythmia Monitor — App + Device
### ISO 14971:2019 Compliant

**Document ID:** RMF-001  
**Version:** 1.1  
**Date:** 2026-10-16  
**Status:** DRAFT — Requires clinical review before submission  

> **Regulatory status:** Educational project. This file follows the structure of ISO 14971 as a design discipline. The system is **not a certified medical device**, has not been assessed by a Notified Body, and must not be used for clinical purposes.

---

## 1. Scope and Intended Use

**Intended use:** Educational single-lead ECG arrhythmia monitoring: acquiring one ECG lead, classifying beats as Normal / Supraventricular / Ventricular, and reviewing the results. Not intended for clinical diagnosis, patient monitoring, or treatment decisions.

**Intended users:** Adults with no clinical training, using the system for learning and demonstration.

**Use environment:** Home or bench (not clinical setting).

**System components:**
- ESP32-S3-N16R8 board running the acquisition firmware (filtering, beat detection, BLE)
- AD8232 ECG board (red "AD8232 Heart Monitor" V502, SparkFun SEN-12650 clone; datasheet Figure 66 circuit) with 3-lead cable and disposable electrodes, USB-powered
- Android mobile application (ECG Monitor), which runs the TensorFlow Lite classifier on the phone
- Firebase Firestore (session metadata and events only)

---

## 2. Risk Acceptability Criteria

### Severity Scale (S)
| Level | Description |
|-------|-------------|
| 1 | Negligible — no health consequence |
| 2 | Minor — temporary discomfort |
| 3 | Serious — injury requiring medical intervention |
| 4 | Critical — permanent impairment |
| 5 | Catastrophic — death |

### Probability Scale (P)
| Level | Description |
|-------|-------------|
| 1 | Improbable — < 1 in 1,000,000 uses |
| 2 | Remote — 1 in 100,000 uses |
| 3 | Occasional — 1 in 10,000 uses |
| 4 | Probable — 1 in 1,000 uses |
| 5 | Frequent — > 1 in 100 uses |

### Risk Matrix
| | S1 | S2 | S3 | S4 | S5 |
|---|---|---|---|---|---|
| **P5** | Low | Medium | High | Unacceptable | Unacceptable |
| **P4** | Low | Medium | Medium | High | Unacceptable |
| **P3** | Low | Low | Medium | Medium | High |
| **P2** | Negligible | Low | Low | Medium | Medium |
| **P1** | Negligible | Negligible | Low | Low | Medium |

**Acceptance:** Negligible/Low = acceptable. Medium = ALARP review required. High/Unacceptable = must mitigate before release.

**Mitigation status notation (v1.1):** each mitigation is marked **[Implemented]**, **[Partial]**, or **[Not implemented]**, and with its verification evidence where some exists (SVVP-001 test IDs). Residual probabilities are only lowered for implemented mitigations.

---

## 3. Hazard Analysis

### RISK-001: False Negative — Missed Arrhythmia
| Field | Value |
|-------|-------|
| **Hazard** | Arrhythmia detection algorithm fails to flag a clinically significant event |
| **Hazardous situation** | User experiences a run of ectopic beats; app shows "Normal" |
| **Harm** | User does not seek medical attention; delayed treatment of cardiac event |
| **Severity** | 4 (Critical — could lead to stroke or cardiac emergency) |
| **Probability (pre-mitigation)** | 3 (ML models have known false negative rates) |
| **Pre-mitigation Risk** | Medium |
| **Known performance (v1.1)** | Shipped model `model_ensemble_20261015_212815` (softmax average of 4 runs). On MIT-BIH DS2 (22 records), end-to-end through the real firmware code on board-filtered input: macro-F1 0.772; training path 0.780; **SVEB F1 0.46** — supraventricular beats are often missed (TC-ML-01). Trained on 18 DS1 records with thresholds tuned on 4 more; one real wearer tested (TC-WEAR-01). The model has 3 classes and **cannot detect atrial fibrillation** |
| **Mitigations** | M1: Validate algorithm against MIT-BIH DS2 [Implemented — TC-ML-01] and a clinical dataset [Not implemented]; M2: Disclaimer in UI that app is not a clinical diagnostic device [Partial — consent screen only, FR-ARR-10 open]; M3: Health status "Attention" at >10% PVC or >10% SVEB (FR-ARR-04) [Implemented]; M4: Consent screen includes explicit "not a substitute for medical advice" [Implemented]; M5: Ensemble of 4 runs to reduce run-to-run variance (single runs ranged 0.68–0.78 macro-F1) [Implemented]; M6: Intended use restricted to education (SRS-001 §1) [Implemented] |
| **Probability (post-mitigation)** | 3 |
| **Residual Risk** | Medium |
| **Acceptable?** | ALARP for the educational intended use only. Not acceptable for any clinical use |

---

### RISK-002: False Positive — Unnecessary Medical Alarm
| Field | Value |
|-------|-------|
| **Hazard** | Algorithm over-flags normal beats as arrhythmias |
| **Hazardous situation** | User sees "Concerning" status for a normal recording |
| **Harm** | User anxiety; unnecessary medical consultation; over-treatment |
| **Severity** | 2 (Minor — financial/psychological burden) |
| **Probability (pre-mitigation)** | 3 |
| **Pre-mitigation Risk** | Low |
| **Observed history** | Before v1.1 every recorded session read "Concerning": any SVEB set Concerning (fixed in `9d18d21`), and the detector over-counted beats, producing false SVEBs (fixed in `30b3a84`) |
| **Mitigations** | M1: Three-level health status (Normal/Attention/Concerning), graded by percentage for both PVC and SVEB [Implemented]; M2: UI shows raw beat counts so user can assess themselves [Implemented]; M3: "Consult a doctor" phrasing rather than "you have a condition" [Partial — TC-ARR-07 not run]; M4: Abnormal classifications on poor-quality beats are withheld (FR-SQ-02) [Implemented — gate tested by TC-SQ-01, suppression path TC-SQ-04 not yet verified]. **Limitation:** the confidence check `isValid` (score ≥ class threshold) is always true with the shipped thresholds (0.05 / 0.05 / 0.068, sum < 1), so events are gated only by signal quality, never by confidence |
| **Probability (post-mitigation)** | 3 |
| **Residual Risk** | Low |
| **Acceptable?** | Yes |

---

### RISK-003: Poor Signal Quality — Noisy ECG
| Field | Value |
|-------|-------|
| **Hazard** | Motion artefact or poor electrode contact produces corrupt waveform |
| **Hazardous situation** | Algorithm classifies noise as arrhythmia events |
| **Harm** | False positive (see RISK-002) or false negative (see RISK-001) depending on direction of error |
| **Severity** | 3 |
| **Probability (pre-mitigation)** | 4 (home use environments have high motion artefact) |
| **Pre-mitigation Risk** | Medium |
| **Mitigations** | M1: Signal quality indicator in Live Monitor UI (quality score, flags, lead-off, T/R) [Implemented]; M2: Recording guidance shown before starting a session [Not implemented — FR-ECG-06]; M3: Sessions with >20% invalid samples flagged in session detail [Not implemented; the per-session poor-signal rate is stored (FR-SQ-04) but not flagged]; M4: Hardware EMC shielding per IEC 60601-1 [Not implemented — the board is unshielded]; M5: Per-beat quality gate withholds abnormal events (FR-SQ-01/02) [Implemented — TC-SQ-01]; M6: Lead-off debounced over 250 ms so single-sample glitches do not wipe the detector (FR-FW-07) [Implemented — TC-FW-07, development evidence]; M7: 50 Hz notch filter (FR-FW-02) [Implemented — TC-PAR-01] |
| **Probability (post-mitigation)** | 3 |
| **Residual Risk** | Medium |
| **Acceptable?** | ALARP for the educational intended use |

---

### RISK-004: Electrical Safety — Patient Leakage Current
| Field | Value |
|-------|-------|
| **Hazard** | Leakage current through ECG electrodes to patient |
| **Hazardous situation** | User receives electric shock through sensor contact. The device is USB-powered with no galvanic isolation, so a mains-powered USB host (PC, charger) is a leakage path to the electrodes |
| **Harm** | Ventricular fibrillation; cardiac arrest |
| **Severity** | 5 (Catastrophic) |
| **Probability (pre-mitigation)** | 2 (prototype lacks medical-grade isolation) |
| **Pre-mitigation Risk** | Medium |
| **Mitigations** | M1: Galvanic isolation between patient connection and power rail [Not implemented]; M2: Patient leakage current ≤ 10 μA (Type CF per IEC 60601-1) [Not verified — no measurement]; M3: Hardware validation testing before human use [Not implemented — no IEC 60601-1 testing]; M4: Power only from a battery or through a USB isolator while electrodes are attached [Procedural — documented in developer documentation, not enforced]; M5: Series resistors on the AD8232 board's electrode inputs (180 kΩ / 360 kΩ, per the board's Figure 66 circuit) limit fault current [Present on hardware; values not measured] |
| **Probability (post-mitigation)** | 2 |
| **Residual Risk** | Medium |
| **Acceptable?** | **Not acceptable**, except for supervised educational bench use with a battery-powered or USB-isolated host |

---

### RISK-005: Data Breach — Unauthorised Access to Health Records
| Field | Value |
|-------|-------|
| **Hazard** | ECG recordings or derived health data accessed by unauthorised party |
| **Hazardous situation** | Health data disclosed — insurance discrimination; psychological distress |
| **Harm** | Privacy violation; discrimination risk |
| **Severity** | 3 |
| **Probability (pre-mitigation)** | 3 (local storage without encryption; cloud copy of metadata) |
| **Pre-mitigation Risk** | Medium |
| **Mitigations** | M1: Raw waveforms stored locally only; only session metadata and events are uploaded to Firestore over TLS under an anonymous per-user UID (FR-SYNC-01/02) [Implemented — confirmed by code inspection, no test]; M2: Android app sandboxing restricts access to app data [Implemented]; M3: Consent screen documents what is stored [**Non-conformance** — the consent text says data is never transmitted, see OA-1]; M4: Deletion capability ensures GDPR right to erasure [Partial — "Delete All" and withdrawal also delete cloud data; single-session delete leaves the cloud copy, OA-2]; M5: SQLCipher database encryption [Not implemented]; M6: Android backup disabled (`allowBackup="false"`) [Implemented; device-to-device transfer not excluded, OA-5]; M7: Firestore security rules reviewed, EU region confirmed, Data Processing Agreement [Not evidenced — see DPIA-001] |
| **Probability (post-mitigation)** | 3 |
| **Residual Risk** | Medium |
| **Acceptable?** | No — open actions OA-1, OA-2, OA-5 and the DPIA-001 §7 items must be closed |

---

### RISK-006: Software Crash During Recording
| Field | Value |
|-------|-------|
| **Hazard** | App crashes mid-recording |
| **Hazardous situation** | Partial or corrupt session saved; user unaware of incomplete data |
| **Harm** | Missed arrhythmia event in the uncaptured portion |
| **Severity** | 3 |
| **Probability (pre-mitigation)** | 3 |
| **Pre-mitigation Risk** | Medium |
| **Mitigations** | M1: Incremental write to database [Not implemented — the recording is buffered in memory and written once on Stop, so a crash loses the whole recording]; M2: Session marked as "incomplete" if app terminates unexpectedly [Not implemented]; M3: Incomplete sessions displayed with warning indicator in History [Not implemented]. See OA-3 |
| **Probability (post-mitigation)** | 3 |
| **Residual Risk** | Medium |
| **Acceptable?** | No — open action OA-3 |

---

### RISK-007: Misinterpretation of Health Status by User
| Field | Value |
|-------|-------|
| **Hazard** | User misunderstands "Normal" status as medical clearance |
| **Hazardous situation** | User with symptoms dismisses them because app shows "Normal" |
| **Harm** | Delayed presentation to clinician |
| **Severity** | 4 |
| **Probability (pre-mitigation)** | 3 |
| **Pre-mitigation Risk** | Medium |
| **Mitigations** | M1: Persistent disclaimer in Session Detail screen [Not implemented — FR-ARR-10, OA-4]; M2: Consent screen explicitly states app is not a diagnostic tool [Implemented]; M3: UI language avoids clinical terms ("health indicator" not "diagnosis"); the misleading "AFib" label was renamed to "SVEB" (`9d18d21`) [Implemented — TC-ARR-07 not run]; M4: IEC 62366 usability testing to validate comprehension [Not implemented] |
| **Probability (post-mitigation)** | 3 |
| **Residual Risk** | Medium |
| **Acceptable?** | No — open action OA-4 |

---

### RISK-008: BLE Interference or Disconnection During Recording
| Field | Value |
|-------|-------|
| **Hazard** | BLE connection drops mid-recording |
| **Hazardous situation** | Data gaps in recording without user awareness |
| **Harm** | Missed events; false confidence in a complete recording |
| **Severity** | 2 |
| **Probability (pre-mitigation)** | 4 (BLE is lossy in home environments) |
| **Pre-mitigation Risk** | Medium |
| **Mitigations** | M1: Visual disconnection indicator (connection status banner in Live Monitor) [Implemented]; M2: Recording stops automatically on BLE loss and the session is saved [Implemented; no explicit alert beyond the banner — FR-BLE-05 partial]; M3: Data gaps flagged in session detail view [Not implemented; dropped packets are counted live from the stream sequence number] |
| **Probability (post-mitigation)** | 3 |
| **Residual Risk** | Low |
| **Acceptable?** | Yes |

---

### RISK-009: ADC Saturation — Clipped QRS Complexes
| Field | Value |
|-------|-------|
| **Hazard** | The ADC full scale is about ±1.41 mV referred to the electrodes (12-bit ADC, board gain ≈1100). A larger QRS clips on the device; the training data is not clipped |
| **Hazardous situation** | Clipped beats have distorted morphology and a shifted fiducial; the classifier sees inputs it was not trained on |
| **Harm** | False negative (RISK-001) or false positive (RISK-002) |
| **Severity** | 3 |
| **Probability (pre-mitigation)** | 4 (normal QRS amplitudes are 1–3 mV; moving one electrode to the mid-axillary line doubled the amplitude past the clip level) |
| **Pre-mitigation Risk** | Medium |
| **Mitigations** | M1: SATURATED quality flag (>2% of samples at the rail) withholds abnormal classifications (FR-SQ-01/02) [Implemented — TC-SQ-01]; M2: Verified electrode placement gives a 1.35 mV QRS with no clipping (TC-WEAR-01) [Implemented, developer documentation]; M3: Board gain measured at ≈1000–1090, flat to 40 Hz (TC-HW-01). **Residual:** clipped beats classified Normal are not suppressed, and a suppressed ventricular beat becomes a missed event |
| **Probability (post-mitigation)** | 3 |
| **Residual Risk** | Medium |
| **Acceptable?** | ALARP for the educational intended use |

---

### RISK-010: R-peak Detector Errors
| Field | Value |
|-------|-------|
| **Hazard** | The firmware detector misses beats or detects non-QRS waves. Known weaknesses (developer observation): (a) with a small QRS (wrong lead or old pads) the detector can lock onto P waves; (b) very large PVCs raise the adaptive threshold above the normal beats, which are then missed (MIT-BIH record 228 on the host build; masked on the device by clipping). Previously fixed: threshold ratchet onto T waves (`30b3a84`); lead-off chatter wiping the detector (`e1f0c17`) |
| **Hazardous situation** | Wrong heart rate, wrong RR features and misplaced beat windows |
| **Harm** | False negative (RISK-001) or false positive (RISK-002); misleading heart rate |
| **Severity** | 3 |
| **Probability (pre-mitigation)** | 3 |
| **Pre-mitigation Risk** | Medium |
| **Mitigations** | M1: Pan-Tompkins with separate signal/noise levels and a threshold floor (FR-FW-03) [Implemented — TC-FW-01, TC-DET-01 development evidence]; M2: Slope-energy-centroid fiducial, stable on RS-type beats (FR-FW-04) [Implemented — TC-FW-03, TC-PAR-01]; M3: Lead-off debounce (FR-FW-07) [Implemented — TC-FW-07]; M4: Heart rate taken from the firmware detector, a single validated detector (FR-ECG-03) [Implemented]; M5: Classification-rate plausibility flag per session (FR-SQ-04) [Implemented — TC-SQ-03]; M6: T/R readout reveals a wrong lead (FR-SQ-05) [Implemented, not verified]. **Residual:** weaknesses (a) and (b) are not fixed |
| **Probability (post-mitigation)** | 3 |
| **Residual Risk** | Medium |
| **Acceptable?** | ALARP for the educational intended use |

---

### RISK-011: Use Error — Electrode Placement and Cable Colours
| Field | Value |
|-------|-------|
| **Hazard** | The model is trained on lead II (MIT-BIH MLII). This kit's cable colours are non-standard (red = RA, green = LA, yellow = RL); placing the electrodes by the conventional IEC colours gives the wrong lead |
| **Hazardous situation** | The classifier sees morphology it was never trained on |
| **Harm** | False negative (RISK-001) or false positive (RISK-002) |
| **Severity** | 3 |
| **Probability (pre-mitigation)** | 4 (likely for any user without the kit-specific instructions) |
| **Pre-mitigation Risk** | Medium |
| **Mitigations** | M1: Verified placement and cable mapping documented (CLAUDE.md "Signal chain / lead configuration") [Implemented, developer documentation only]; M2: In-app placement guidance (FR-ECG-06) [Not implemented]; M3: Live T/R readout, green below 0.35 (FR-SQ-05) [Implemented, not verified]; M4: Live drift score against training (FR-SQ-03) [Implemented — TC-SQ-02 partial] |
| **Probability (post-mitigation)** | 3 |
| **Residual Risk** | Medium |
| **Acceptable?** | ALARP for the educational intended use; FR-ECG-06 recommended |

---

### RISK-012: Use Error — Electrode Pad Condition and Settling
| Field | Value |
|-------|-------|
| **Hazard** | Re-used pads give 3–4× smaller signals. Freshly applied pads saturate the AD8232 and trigger fast-restore cycling for 1–2 minutes. Power-up and leads-on produce a filter-settling transient |
| **Hazardous situation** | Small or saturated signals and false beats during settling are classified |
| **Harm** | False negative (RISK-001) or false positive (RISK-002) |
| **Severity** | 3 |
| **Probability (pre-mitigation)** | 4 |
| **Pre-mitigation Risk** | Medium |
| **Mitigations** | M1: No beats sent for 2 s after power-up or leads-on (FR-FW-08) [Implemented — TC-FW-08 development evidence: beats sent during the simulated transient 110 → 0, false beats 8 → 0]. Covers the filter transient only, not the 1–2 min pad settling; M2: FLAT and SATURATED quality flags (FR-SQ-01) [Implemented — TC-SQ-01]; M3: Guidance "use fresh pads, let them settle 1–2 min" [Developer documentation only; FR-ECG-06 not implemented] |
| **Probability (post-mitigation)** | 3 |
| **Residual Risk** | Medium |
| **Acceptable?** | ALARP for the educational intended use |

---

### RISK-013: Hardware Handling — Contact with Board Input Pads
| Field | Value |
|-------|-------|
| **Hazard** | The AD8232 board's RL/LA/RA holes are in parallel with the electrode jack; anything touching them (e.g. soldered header pins) shorts the inputs |
| **Hazardous situation** | No usable ECG or a distorted signal |
| **Harm** | No valid recording; possible misclassification of a distorted signal |
| **Severity** | 2 |
| **Probability (pre-mitigation)** | 3 |
| **Pre-mitigation Risk** | Low |
| **Mitigations** | M1: Keep the input holes free, no header pins (developer documentation) [Implemented on the reference hardware]; M2: FLAT quality flag (FR-SQ-01) [Implemented — TC-SQ-01] |
| **Probability (post-mitigation)** | 2 |
| **Residual Risk** | Low |
| **Acceptable?** | Yes |

---

### RISK-014: Signal-Chain Contract Mismatch and Distribution Shift
| Field | Value |
|-------|-------|
| **Hazard** | Constants the model depends on (sample rate, filters, beat window, fiducial, RR features, units, scalers, thresholds) are duplicated across firmware, training, metadata, offline validation and the app, with no runtime check that they agree |
| **Hazardous situation** | A mismatch silently corrupts every classification. This has happened: raw ADC counts were fed to a millivolt scaler, putting inputs ~180 training sigmas out of range (`25a98f1`); `post_rr` was sent as `pre_rr`, making one RR feature constant (`a789e2f`) |
| **Harm** | Systematic false negatives or false positives (RISK-001, RISK-002) |
| **Severity** | 3 |
| **Probability (pre-mitigation)** | 3 |
| **Pre-mitigation Risk** | Medium |
| **Mitigations** | M1: Cross-project contract table and change procedure (CLAUDE.md) [Implemented]; M2: Live and per-session drift score against the training value (FR-SQ-03/04); it exposed the units bug on its first real run [Implemented — TC-SQ-02 partial]; M3: Training reproduces the firmware chain; firmware vs training parity on MIT-BIH (TC-PAR-01) and on the ESP32-S3 (TC-HIL-01) [Implemented]; M4: Native unit tests for window and RR features (TC-FW-01..06) [Implemented]; M5: ADC→mV conversion before normalising (FR-ARR-07) [Implemented — conversion factor depends on board gain, measured ≈1000–1090 vs 1100 assumed, TC-HW-01]; M6: Model and metadata shipped as a pair (FR-ARR-08) [Partial — the stored model version "1.0.0" does not identify the model, OA-6]. **Residual:** no automated cross-component check; no CI |
| **Probability (post-mitigation)** | 2 |
| **Residual Risk** | Low |
| **Acceptable?** | Yes |

---

### RISK-015: Result Latency
| Field | Value |
|-------|-------|
| **Hazard** | Each beat is sent at the next R-peak (its `post_rr` must be real), so its classification appears at least one RR interval (≈1 s at 60 bpm) after the beat, plus BLE and inference time |
| **Hazardous situation** | Results shown late, or events placed at the wrong time if arrival time is used |
| **Harm** | Negligible — the system raises no real-time alarms |
| **Severity** | 1 |
| **Probability (pre-mitigation)** | 5 |
| **Pre-mitigation Risk** | Low |
| **Mitigations** | M1: Events are placed by the beat's own timestamp, not arrival time (FR-ARR-09) [Implemented, not verified] |
| **Probability (post-mitigation)** | 5 |
| **Residual Risk** | Low |
| **Acceptable?** | Yes |

---

## 4. Risk Summary Table

| Risk ID | Description | Pre-mitigation | Post-mitigation | Acceptable |
|---------|-------------|----------------|-----------------|------------|
| RISK-001 | False negative arrhythmia | Medium | Medium | ⚠️ ALARP (educational use only) |
| RISK-002 | False positive alarm | Low | Low | ✅ |
| RISK-003 | Signal quality / noise | Medium | Medium | ⚠️ ALARP |
| RISK-004 | Electrical safety | Medium | Medium | ❌ (supervised bench use with battery/isolated host only) |
| RISK-005 | Data breach | Medium | Medium | ❌ (OA-1, OA-2, OA-5, DPIA §7) |
| RISK-006 | Software crash | Medium | Medium | ❌ (OA-3) |
| RISK-007 | User misinterpretation | Medium | Medium | ❌ (OA-4) |
| RISK-008 | BLE disconnection | Medium | Low | ✅ |
| RISK-009 | ADC saturation / clipping | Medium | Medium | ⚠️ ALARP |
| RISK-010 | R-peak detector errors | Medium | Medium | ⚠️ ALARP |
| RISK-011 | Electrode placement / cable colours | Medium | Medium | ⚠️ ALARP |
| RISK-012 | Pad condition / settling | Medium | Medium | ⚠️ ALARP |
| RISK-013 | Contact with board input pads | Low | Low | ✅ |
| RISK-014 | Contract mismatch / drift | Medium | Low | ✅ |
| RISK-015 | Result latency | Low | Low | ✅ |

**Overall residual risk: Not acceptable for any use beyond the educational intended use.** Within it, use is acceptable only on a battery-powered or USB-isolated host (RISK-004), and only once the open actions in §6 for RISK-005, RISK-006 and RISK-007 are closed.

---

## 5. Risk Control Verification

| Mitigation | Verification Method | Status |
|-----------|---------------------|--------|
| ML algorithm performance on MIT-BIH DS2 | End-to-end evaluation through the real firmware code (TC-ML-01) | ✅ Done — macro-F1 0.772, SVEB F1 0.46 |
| ML algorithm false negative rate on clinical data | Performance study vs clinical dataset | ⏳ Pending |
| Firmware vs training signal-chain parity | Host build of the real SignalProcessor on MIT-BIH (TC-PAR-01); on-device run (TC-HIL-01) | ✅ Done (TC-HIL-01 on an earlier firmware build) |
| Firmware beat window / RR features / fiducial | Native unit tests (TC-FW-01..06) | ✅ 6/6 pass (2026-10-16) |
| Settling hold-back, lead-off debounce | Replay / simulation (TC-FW-07, TC-FW-08) | ✅ Development evidence only, not a committed test |
| Signal quality gate | Unit tests (TC-SQ-01) | ✅ 10/10 pass (2026-10-16) |
| Board gain / ADC→mV factor | Injected known signal (TC-HW-01) | ✅ Done — ≈1000–1090 |
| Electrode placement on a real wearer | Recording review (TC-WEAR-01) | ✅ One wearer |
| IEC 60601-1 leakage current | Accredited lab testing | ⏳ Pending — not planned for this educational project |
| Usability testing (IEC 62366) | Formative + summative testing | ⏳ Pending |
| Incomplete session handling | Software testing | ❌ Not implemented (OA-3) |

---

## 6. Open Actions

Defects found during the v1.1 review (code inspection, 2026-10-16). They affect implemented software and need code changes, which are outside this document update.

| ID | Finding | Affects | Requirement |
|----|---------|---------|-------------|
| OA-1 | Consent text says data "is not transmitted to any server, cloud service, or third party", but session metadata and events upload to Firestore automatically after consent. `CONSENT_VERSION` was not incremented | RISK-005, DPIA-001 | FR-CON-08 |
| OA-2 | Deleting a single session deletes only the local copy; the cloud copy remains. Session deletion and PDF/CSV export are not written to the audit log | RISK-005 | FR-SYNC-05, FR-AUD-01 |
| OA-3 | Recordings are buffered in memory and written only on Stop; a crash loses the whole recording. No incomplete flag, no minimum length | RISK-006 | FR-REC-03..06 |
| OA-4 | The "not a medical device" notice is shown only on the consent screen | RISK-001, RISK-007 | FR-ARR-10 |
| OA-5 | `data_extraction_rules.xml` is the empty template; with targetSdk 34, device-to-device transfer probably still includes the database (to be confirmed) | RISK-005 | NFR-08 |
| OA-6 | `model_metadata.json` version is "1.0.0" for every model, and that is what sessions store as `modelVersion`; it does not identify the shipped ensemble | RISK-014 | FR-ARR-08 |
| OA-7 | MIT-BIH records 201 (validation) and 202 (DS2 test) are from the same subject, so threshold tuning and the test set share one patient. A property of the standard de Chazal split; recorded as a limitation, no fix planned | RISK-001 | — |

---

## 7. Document History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2026-01-01 | [Author] | Initial draft |
| 1.1 | 2026-10-16 | Oliver Moberg | Educational status stated; scope updated (ESP32-S3 + AD8232 board, on-phone inference, Firestore). RISK-001..008 re-evaluated against the implementation, with mitigation status and evidence; residuals raised where mitigations are not implemented. RISK-004 rewritten for the USB-powered, unisolated device. Added RISK-009..015, §6 open actions OA-1..7; summary and verification tables updated |

*This document must be reviewed by a qualified clinical/regulatory professional before submission to a Notified Body.*
