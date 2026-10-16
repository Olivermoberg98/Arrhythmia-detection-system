# Software Verification & Validation Plan
## ECG Arrhythmia Monitor — Android Application and Device Firmware
### IEC 62304:2015 §5.6 / §5.7

**Document ID:** SVVP-001  
**Version:** 1.1  
**Date:** 2026-10-16  
**Status:** DRAFT  

> **Regulatory status:** Educational project. This plan follows the structure of IEC 62304 as a design discipline. The system is **not a certified medical device** and has not been assessed by a Notified Body.

---

## 1. Purpose

This document defines how each requirement in SRS-001 is verified. Every Must-priority requirement must have a passing test before release. Should-priority requirements are tested where feasible.

§7 records the verification results that exist as of this version. Only results that were actually run are recorded; everything else is marked not yet verified.

---

## 2. Test Levels

| Level | Tool | Location |
|-------|------|----------|
| Unit (app) | JUnit 4 | `ecg_monitor_app/app/src/test/` |
| Integration (app) | Room in-memory + Coroutines test | `ecg_monitor_app/app/src/test/` |
| UI / Instrumented (app) | Compose Testing + Espresso, Room `MigrationTestHelper` | `ecg_monitor_app/app/src/androidTest/` |
| Static analysis (app) | detekt (default ruleset + baseline) | `.\gradlew.bat detekt` |
| Unit (firmware) | Unity, host build (`pio test -e native`) | `arrhythmia_system_controller/test/test_signal_processor/` |
| Offline signal-chain / model | Host build of the real `SignalProcessor.cpp` fed MIT-BIH; Python evaluation of the TFLite model | Developer scripts (not committed as tests) |
| Hardware-in-the-loop | Firmware on the ESP32-S3, output compared with the host build | Manual |
| Bench / human wearer | Injected test signal; recording on a real wearer | Manual |
| Manual (app) | Protocol below | Tester executes on real device |

**Development evidence:** some results below come from one-off replays or simulations run during development and recorded in a commit message. They are cited with the commit hash and marked as development evidence: they are real measurements but not a committed, repeatable test.

---

## 3. Test Cases by Requirement

### BLE Communication

| Test ID | Requirement | Type | Description | Pass Criteria |
|---------|-------------|------|-------------|---------------|
| TC-BLE-01 | FR-BLE-01 | Manual | Scan with nearby unnamed BLE devices present | Only named devices appear in list |
| TC-BLE-02 | FR-BLE-02 | Instrumented | Launch scan without Bluetooth permission granted | Permission dialog shown; no crash |
| TC-BLE-03 | FR-BLE-03 | UI | Connect to device, observe Live Monitor screen | Connection status banner updates to "Connected" |
| TC-BLE-04 | FR-BLE-04 | Manual | Start recording, then disable Bluetooth on device | Recording stops automatically |
| TC-BLE-05 | FR-BLE-05 | Manual | Disconnect BLE mid-recording | User notification displayed within 2 seconds |

### ECG Acquisition and Display

| Test ID | Requirement | Type | Description | Pass Criteria |
|---------|-------------|------|-------------|---------------|
| TC-ECG-01 | FR-ECG-01 | Manual | Connect ESP32 and observe Live Monitor | Waveform visible and scrolling |
| TC-ECG-02 | FR-ECG-02 | Manual | Observe waveform refresh rate | No visible stutter; updates at least every 100ms |
| TC-ECG-03 | FR-ECG-03 | Manual | Observe HR display during recording; compare with a manual beat count | Heart rate value displayed, updating, and within ±5 bpm of the manual count |
| TC-ECG-04 | FR-ECG-04 | Manual | Disconnect electrode mid-recording | Signal quality indicator changes to warn |
| TC-ECG-05 | FR-ECG-05 | Unit | Verify the recorded session's sampling rate field | samplingRate == 250 |
| TC-ECG-06 | FR-ECG-06 | Manual | Start a recording on a fresh install | Electrode placement guidance (red = RA, green = LA, yellow = RL) is shown |

### Recording

| Test ID | Requirement | Type | Description | Pass Criteria |
|---------|-------------|------|-------------|---------------|
| TC-REC-01 | FR-REC-01 | UI | Tap Start, tap Stop | Session created in database |
| TC-REC-02 | FR-REC-02 | UI | Attempt to record without BLE connected | Start button disabled or error shown |
| TC-REC-03 | FR-REC-03 | Integration | Force-kill app during recording | Partial session exists in DB on restart |
| TC-REC-04 | FR-REC-04 | Integration | Simulate crash during recording | Session has incomplete flag set |
| TC-REC-05 | FR-REC-05 | UI | Open incomplete session in History | Warning indicator visible |
| TC-REC-06 | FR-REC-06 | Unit | Attempt to save session with <10s of data | Session rejected or flagged |

### Arrhythmia Detection

| Test ID | Requirement | Type | Description | Pass Criteria |
|---------|-------------|------|-------------|---------------|
| TC-ARR-01 | FR-ARR-01 | Unit | Classify the beats of MIT-BIH record 100 (normal sinus) | >95% beats classified Normal |
| TC-ARR-02 | FR-ARR-01 | Unit | Classify the beats of MIT-BIH record 203 (PVC-heavy) | Ventricular (V) beats detected with >80% sensitivity |
| TC-ARR-03 | FR-ARR-02 | Unit | Check ArrhythmiaEventEntity after detection | confidence field populated 0.0–1.0 |
| TC-ARR-04 | FR-ARR-03, FR-ARR-04 | Unit | Session with 25% PVCs; session with 25% SVEBs | HealthStatus == CONCERNING for both |
| TC-ARR-05 | FR-ARR-03, FR-ARR-04 | Unit | Session with 15% PVCs; session with 15% SVEBs | HealthStatus == ATTENTION for both |
| TC-ARR-06 | FR-ARR-03, FR-ARR-04 | Unit | Session with 3% PVCs, 3% SVEBs, HR 70 | HealthStatus == NORMAL |
| TC-ARR-07 | FR-ARR-05, NFR-06 | Manual | Review all health status UI text | No occurrence of "diagnosis" or "condition" |
| TC-ARR-08 | FR-ARR-06 | Unit | `ArrhythmiaClassifier.decide()` with scores where plain argmax and `argmax(scores / thresholds)` disagree | Returns the `argmax(scores / thresholds)` class |
| TC-ARR-09 | FR-ARR-07 | Unit | Classify a synthetic beat given in ADC counts | Input is scaled by `ADC_COUNTS_TO_MV` before normalisation; normalised values within a few training sigmas |
| TC-ARR-10 | FR-ARR-08 | Inspection | Compare app assets with `ml_pipeline/src/ml/`; check the session's stored `modelVersion` | Assets identical to one training run; `modelVersion` identifies that run |
| TC-ARR-11 | FR-ARR-09 | Unit | Beat arriving one RR interval after its timestamp | Event sample index corresponds to the beat timestamp, not arrival time |
| TC-ARR-12 | FR-ARR-10 | Manual | Open Live Monitor and Session Detail | "Not a medical device" notice visible on both |

### Device Firmware

| Test ID | Requirement | Type | Description | Pass Criteria |
|---------|-------------|------|-------------|---------------|
| TC-FW-01 | FR-FW-03, FR-FW-06 | Unit (native) | `test_every_beat_detected_except_first_and_last` — synthetic ECG with known R-peaks | Every beat detected; first and last not emitted |
| TC-FW-02 | FR-FW-05 | Unit (native) | `test_window_is_centred_96_pre_32_post` | Window holds 96 samples before and 32 after the fiducial |
| TC-FW-03 | FR-FW-04 | Unit (native) | `test_fiducial_stable_when_s_is_as_deep_as_r` — RS beats with S alternating deeper/shallower than R | Fiducial does not jump between R and S |
| TC-FW-04 | FR-FW-06 | Unit (native) | `test_post_rr_is_the_real_next_interval` — premature beat + compensatory pause | `post_rr` equals the real next interval |
| TC-FW-05 | FR-FW-06 | Unit (native) | `test_rr_mean_is_last_8_intervals_up_to_pre_rr` | RR normaliser = mean of the last 8 intervals up to `pre_rr` |
| TC-FW-06 | FR-FW-06 | Unit (native) | `test_reset_drops_state_and_the_first_beat` | Reset clears detector state; first beat after reset not emitted |
| TC-FW-07 | FR-FW-07 | Replay | Replay recordings with single-sample lead-off dropouts injected (5/s) | Beat count equals the dropout-free count |
| TC-FW-08 | FR-FW-08 | Simulation | Reconnect transient ahead of 30 s of each MIT-BIH DS2 record, through the real SignalProcessor | No beats sent from the transient; detection afterwards unchanged |
| TC-FW-09 | FR-FW-09 | Inspection / Integration | Packet sizes and field order vs `BleManager` parsers | `DataPacket` 26 B, `BeatPacket` 275 B; app parses both |
| TC-FW-10 | FR-FW-01 | HIL | Measure sample interval on the device (timestamps over ≥60 s) | 250 Hz ± 0.1% |
| TC-PAR-01 | FR-FW-02, FR-FW-04, FR-FW-05, FR-FW-06 | Offline | Host build of the real `SignalProcessor.cpp` on MIT-BIH vs `preprocess.py` | Same fiducial sample for >99% of beats where the emulated ADC does not clip; windows match to int16 truncation |
| TC-HIL-01 | FR-FW-03, FR-FW-06 | HIL | Firmware on the ESP32-S3 fed MIT-BIH DS2, compared with the host build | R-peaks and RR features bit-identical |
| TC-DET-01 | FR-FW-03 | Replay | Recorded sessions replayed through the firmware detector vs an independent offline detector | Beat count within ±2 of the reference |

### Classifier Performance and Hardware

| Test ID | Requirement | Type | Description | Pass Criteria |
|---------|-------------|------|-------------|---------------|
| TC-ML-01 | FR-ARR-01 | Offline | INT8 TFLite model, end-to-end through the real SignalProcessor on board-filtered MIT-BIH DS2 (22 records) | Report macro-F1 and per-class F1 (no release threshold defined; see §6) |
| TC-HW-01 | FR-ARR-07 | Bench | Inject a known square wave (1 MΩ / 220 Ω divider from GPIO21) and measure the board gain | Gain within 10% of the 1100 assumed by `ADC_COUNTS_TO_MV`; flat to 40 Hz |
| TC-WEAR-01 | FR-ARR-01 | Human wearer | Record a healthy wearer with the verified lead II placement | No clipping; QRS < 1.41 mV; beats classified Normal |

### Signal Quality and Model Health

| Test ID | Requirement | Type | Description | Pass Criteria |
|---------|-------------|------|-------------|---------------|
| TC-SQ-01 | FR-SQ-01 | Unit | `SignalQualityAnalyzerTest`: clean, flat, low-amplitude, noisy, rail-clipped, baseline-wander and empty windows | Each flagged correctly; a sharp QRS alone does not trip the noise flag |
| TC-SQ-02 | FR-SQ-03, FR-ARR-07 | Unit | `SignalQualityAnalyzerTest` drift cases | Converted input lands in single sigmas; a unit mismatch shows as large drift |
| TC-SQ-03 | FR-SQ-04, FR-SQ-02 | Unit | `ClassificationMonitorTest`: confidence, low-confidence rate, entropy, poor-signal rate, suppressed events, inference timing, rate flags | All scalars and rate verdicts correct |
| TC-SQ-04 | FR-SQ-02 | Integration | Feed an abnormal classification on an unusable beat through `LiveMonitorViewModel` | No event created; suppressed count incremented |
| TC-SQ-05 | FR-SQ-05 | Manual | Detach an electrode; then record with correct and with wrong placement | Lead-off shown; T/R green for correct lead II, not green for the wrong lead |
| TC-DB-01 | FR-SQ-04 | Instrumented | `EcgDatabaseMigrationTest` (7 cases, v3→v4→v5) | Migrated schemas match the entities; data carried across |

### Consent and Data Governance

| Test ID | Requirement | Type | Description | Pass Criteria |
|---------|-------------|------|-------------|---------------|
| TC-CON-01 | FR-CON-01 | Instrumented | Fresh install, launch app | ConsentScreen shown before any other screen |
| TC-CON-02 | FR-CON-02 | Instrumented | Attempt to navigate to Live Monitor without consent | Redirected to ConsentScreen |
| TC-CON-03 | FR-CON-03 | Integration | Accept consent | Row in consent_records with correct timestamp and version |
| TC-CON-04 | FR-CON-04 | Integration | Change CONSENT_VERSION, relaunch app | ConsentScreen shown again to existing user |
| TC-CON-05 | FR-CON-05 | UI | Open Settings, tap Withdraw Consent | Confirmation dialog shown |
| TC-CON-06 | FR-CON-06 | Integration | Complete withdrawal flow | All sessions, readings, events deleted from DB |
| TC-CON-07 | FR-CON-07 | Integration | Complete withdrawal flow | consent_records table still contains rows |
| TC-CON-08 | FR-CON-08 | Manual | Read the consent text and compare with the data actually uploaded (TC-SYNC-01) | Text discloses the cloud upload of session metadata and events |

### Audit Logging

| Test ID | Requirement | Type | Description | Pass Criteria |
|---------|-------------|------|-------------|---------------|
| TC-AUD-01 | FR-AUD-01 | Integration | Complete a recording session | audit_log contains SESSION_RECORDED entry |
| TC-AUD-02 | FR-AUD-01 | Integration | Open a session from History | audit_log contains SESSION_VIEWED entry |
| TC-AUD-03 | FR-AUD-01 | Integration | Delete all data | audit_log contains ALL_DATA_DELETED entry |
| TC-AUD-04 | FR-AUD-02 | Integration | Query audit_log after any action | timestamp, action, sessionId all populated correctly |
| TC-AUD-05 | FR-AUD-03 | Integration | Delete all data, check consent_records | audit_log cleared; consent_records retained |
| TC-AUD-06 | FR-AUD-01 | Integration | Delete a single session; export a session as PDF and as CSV | audit_log contains SESSION_DELETED, SESSION_EXPORTED_PDF, SESSION_EXPORTED_CSV |

### Data Deletion

| Test ID | Requirement | Type | Description | Pass Criteria |
|---------|-------------|------|-------------|---------------|
| TC-DEL-01 | FR-DEL-01 | UI | Open Settings | "Delete All Recordings" button visible |
| TC-DEL-02 | FR-DEL-02 | UI | Tap Delete All Recordings | Confirmation dialog appears before deletion |
| TC-DEL-03 | FR-DEL-03 | Manual | Read confirmation dialog text | Dialog explains what is deleted and what is retained |
| TC-DEL-04 | FR-DEL-04 | Integration | Confirm deletion | ecg_sessions, ecg_readings, arrhythmia_events all empty |

### Cloud Synchronisation and Privacy

| Test ID | Requirement | Type | Description | Pass Criteria |
|---------|-------------|------|-------------|---------------|
| TC-SYNC-01 | FR-SYNC-01, NFR-02 | Manual | Record a session; inspect the uploaded Firestore documents | Session and event documents only; no ECG readings |
| TC-SYNC-02 | FR-SYNC-02 | Manual | Inspect the Firestore path after upload | Data under `users/{anonymous uid}/` only |
| TC-SYNC-03 | FR-SYNC-03 | Manual | Record a session offline, relaunch online | Session uploaded; `uploadedAt` set |
| TC-SYNC-04 | FR-SYNC-04 | Manual | Delete All, then withdraw consent, with uploaded sessions present | User's Firestore documents deleted in both cases |
| TC-SYNC-05 | FR-SYNC-05 | Manual | Delete a single uploaded session | Its Firestore session and event documents deleted |
| TC-PRIV-01 | NFR-08 | Manual | Run an Android cloud backup and a device-to-device transfer | Database not included in either |

---

## 4. Performance Tests

| Test ID | Requirement | Description | Pass Criteria |
|---------|-------------|-------------|---------------|
| TC-PERF-01 | NFR-03 | Record 60s session, measure UI frame rate | No frame drops below 15 FPS during waveform render |
| TC-PERF-02 | NFR-04 | Run 100 consecutive recordings in test harness | Zero crashes |
| TC-PERF-03 | NFR-05 | Measure DB write latency per sample batch | p95 < 50ms |

---

## 5. Regression Test Suite

There is no CI; tests run locally. Before merging to `main`, run:

- `pio test -e native` (TC-FW-01..06) — from `arrhythmia_system_controller/`
- `.\gradlew.bat test` (TC-SQ-01..03) and `.\gradlew.bat detekt` — from `ecg_monitor_app/`
- `.\gradlew.bat connectedAndroidTest` (TC-DB-01) when a device or emulator is available
- After any change to a cross-project contract or the model: TC-PAR-01 and TC-ML-01

Once implemented as automated tests, add: TC-CON-01, TC-CON-03, TC-CON-07 (consent gate); TC-ARR-04, TC-ARR-06, TC-ARR-08 (thresholds and decision rule); TC-DEL-04; TC-AUD-01, TC-AUD-03; TC-REC-01.

---

## 6. Known Limitations

- TC-ARR-02 sensitivity target of 80% is based on MIT-BIH only; clinical dataset required for regulatory submission
- No release threshold is defined for TC-ML-01. The shipped model's SVEB performance is low (F1 0.46); see RMF-001 RISK-001
- TC-PAR-01, TC-HIL-01, TC-ML-01, TC-FW-07, TC-FW-08 and TC-DET-01 are run with developer scripts that are not committed, so they cannot be re-run from the repository alone
- TC-HIL-01 was run on an earlier firmware build (`a789e2f`), before the 2 s settling hold-back (`0fdd577`) and the slope-energy-centroid fiducial (`c4ef8c5`). For the current firmware, parity is verified on the host build only (TC-PAR-01)
- TC-WEAR-01 covers one wearer
- MIT-BIH records 201 (validation) and 202 (DS2 test) are from the same subject (RMF-001 OA-7)
- TC-BLE-04 and TC-BLE-05 require physical hardware for full validation
- Performance tests (TC-PERF-*) must be run on a low-end reference device, not emulator

---

## 7. Verification Results to Date

Status as of 2026-10-16. **Pass** = run and passed; **Pass (dev)** = development evidence (§2); **Not run** = test defined but not executed; **Fails / not implemented** = the requirement is known not to be met.

| Test ID | Result | Evidence |
|---------|--------|----------|
| TC-FW-01..06 | Pass | `pio test -e native`, 2026-10-16: 6/6 passed (also in `752dd11`, `c4ef8c5`) |
| TC-SQ-01, TC-SQ-02 | Pass | `.\gradlew.bat test`, 2026-10-16: `SignalQualityAnalyzerTest` 10/10 passed |
| TC-SQ-03 | Pass | `.\gradlew.bat test`, 2026-10-16: `ClassificationMonitorTest` 15/15 passed |
| detekt | Pass | `.\gradlew.bat detekt`, 2026-10-16: no new findings |
| TC-PAR-01 | Pass | `c4ef8c5`: same fiducial sample for 92–100% of beats per record, within one sample for 99%; `a789e2f`: firmware chain reproduced in training. CLAUDE.md: windows match to ≈1 µV where the ADC does not clip; R-peaks on the same sample for >99% of beats |
| TC-HIL-01 | Pass (earlier firmware) | `a789e2f`: on-device output matches the host bit-for-bit in R-peaks and RR features |
| TC-ML-01 | Done (no threshold) | `c4ef8c5`: `model_ensemble_20261015_212815`, DS2 end-to-end macro-F1 0.772 (training path 0.780); SVEB F1 0.46 |
| TC-HW-01 | Pass | CLAUDE.md / `34862c5`: gain ≈1000–1090, flat to 40 Hz |
| TC-WEAR-01 | Pass (one wearer) | CLAUDE.md / `34862c5`, `c4ef8c5`: QRS 1.35 mV p-p, no clipping, 12 µV PR-segment noise, T/R 0.22; 100% of beats Normal, mean confidence 0.93 |
| TC-FW-07 | Pass (dev) | `e1f0c17`: 24/24 and 62/62 beats with dropouts injected (old policy: 0 and 5) |
| TC-FW-08 | Pass (dev) | `0fdd577`: beats sent from the transient 110 → 0, false beats 8 → 0, detection afterwards unchanged |
| TC-DET-01 | Pass (dev, earlier firmware) | `30b3a84`: 24/24, 31/32, 62/63 beats vs reference on three recorded sessions; before the centroid fiducial |
| TC-DB-01 | Not run | Instrumented tests not executed; migration SQL checked by hand against the v4 schema (`9d18d21`) |
| TC-CON-08 | Fails | Consent text states data is not transmitted (RMF-001 OA-1) |
| TC-AUD-06 | Fails | Session deletion and export not logged (OA-2) |
| TC-ARR-10 | Fails | Stored model version "1.0.0" does not identify the model (OA-6) |
| TC-REC-03..06, TC-ECG-06, TC-ARR-12, TC-SYNC-05 | Not implemented | OA-3; FR-ECG-06 open; notice on the consent screen only (OA-4); single-session delete is local only (OA-2) |
| All other test cases | Not run | — |

---

## 8. Document History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2026-01-01 | [Author] | Initial draft |
| 1.1 | 2026-10-16 | Oliver Moberg | Educational status stated. Scope extended to firmware. Test levels added (firmware native, offline, HIL, bench, wearer, detekt). TC-ECG-03/05, TC-ARR-01/02/04/05/06 updated (250 Hz, 3 classes, SVEB thresholds). Added TC-ECG-06, TC-ARR-08..12, TC-FW-01..10, TC-PAR-01, TC-HIL-01, TC-DET-01, TC-ML-01, TC-HW-01, TC-WEAR-01, TC-SQ-01..05, TC-DB-01, TC-CON-08, TC-AUD-06, TC-SYNC-01..05, TC-PRIV-01. Regression suite reflects local runs (no CI). Added §7 verification results |
