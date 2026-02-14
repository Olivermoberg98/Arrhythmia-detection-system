# Software Verification & Validation Plan
## ECG Arrhythmia Monitor — Android Application
### IEC 62304:2015 §5.6 / §5.7

**Document ID:** SVVP-001  
**Version:** 1.0  
**Date:** 2026-01-01  
**Status:** DRAFT  

---

## 1. Purpose

This document defines how each requirement in SRS-001 is verified. Every Must-priority requirement must have a passing test before release. Should-priority requirements are tested where feasible.

---

## 2. Test Levels

| Level | Tool | Location |
|-------|------|----------|
| Unit | JUnit 4 + MockK | `app/src/test/` |
| Integration | Room in-memory + Coroutines test | `app/src/test/` |
| UI / Instrumented | Compose Testing + Espresso | `app/src/androidTest/` |
| Manual | Protocol below | Tester executes on real device |

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
| TC-ECG-03 | FR-ECG-03 | Manual | Observe HR display during recording | Heart rate value displayed and updating |
| TC-ECG-04 | FR-ECG-04 | Manual | Disconnect electrode mid-recording | Signal quality indicator changes to warn |
| TC-ECG-05 | FR-ECG-05 | Unit | Verify EcgReadingEntity sampling rate field | samplingRate == 360 |

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
| TC-ARR-01 | FR-ARR-01 | Unit | Feed MIT-BIH record 100 (normal sinus) | >95% beats classified Normal |
| TC-ARR-02 | FR-ARR-01 | Unit | Feed MIT-BIH record 203 (PVC-heavy) | PVC events detected with >80% sensitivity |
| TC-ARR-03 | FR-ARR-02 | Unit | Check ArrhythmiaEventEntity after detection | confidence field populated 0.0–1.0 |
| TC-ARR-04 | FR-ARR-04 | Unit | Session with 25% PVCs | HealthStatus == CONCERNING |
| TC-ARR-05 | FR-ARR-04 | Unit | Session with 15% PVCs | HealthStatus == ATTENTION |
| TC-ARR-06 | FR-ARR-04 | Unit | Session with 3% PVCs, HR 70 | HealthStatus == NORMAL |
| TC-ARR-07 | FR-ARR-05 | Manual | Review all health status UI text | No occurrence of "diagnosis" or "condition" |

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

### Audit Logging

| Test ID | Requirement | Type | Description | Pass Criteria |
|---------|-------------|------|-------------|---------------|
| TC-AUD-01 | FR-AUD-01 | Integration | Complete a recording session | audit_log contains SESSION_RECORDED entry |
| TC-AUD-02 | FR-AUD-01 | Integration | Open a session from History | audit_log contains SESSION_VIEWED entry |
| TC-AUD-03 | FR-AUD-01 | Integration | Delete all data | audit_log contains ALL_DATA_DELETED entry |
| TC-AUD-04 | FR-AUD-02 | Integration | Query audit_log after any action | timestamp, action, sessionId all populated correctly |
| TC-AUD-05 | FR-AUD-03 | Integration | Delete all data, check consent_records | audit_log cleared; consent_records retained |

### Data Deletion

| Test ID | Requirement | Type | Description | Pass Criteria |
|---------|-------------|------|-------------|---------------|
| TC-DEL-01 | FR-DEL-01 | UI | Open Settings | "Delete All Recordings" button visible |
| TC-DEL-02 | FR-DEL-02 | UI | Tap Delete All Recordings | Confirmation dialog appears before deletion |
| TC-DEL-03 | FR-DEL-03 | Manual | Read confirmation dialog text | Dialog explains what is deleted and what is retained |
| TC-DEL-04 | FR-DEL-04 | Integration | Confirm deletion | ecg_sessions, ecg_readings, arrhythmia_events all empty |

---

## 4. Performance Tests

| Test ID | Requirement | Description | Pass Criteria |
|---------|-------------|-------------|---------------|
| TC-PERF-01 | NFR-03 | Record 60s session, measure UI frame rate | No frame drops below 15 FPS during waveform render |
| TC-PERF-02 | NFR-04 | Run 100 consecutive recordings in test harness | Zero crashes |
| TC-PERF-03 | NFR-05 | Measure DB write latency per sample batch | p95 < 50ms |

---

## 5. Regression Test Suite

The following tests must pass on every PR to `main`:

- TC-CON-01, TC-CON-03, TC-CON-07 (consent gate)
- TC-ARR-01, TC-ARR-04, TC-ARR-06 (detection thresholds)
- TC-DEL-04 (deletion completeness)
- TC-AUD-01, TC-AUD-03 (audit log integrity)
- TC-REC-01 (basic recording)

---

## 6. Known Limitations

- TC-ARR-02 sensitivity target of 80% is based on MIT-BIH only; clinical dataset required for regulatory submission
- TC-BLE-04 and TC-BLE-05 require physical hardware for full validation
- Performance tests (TC-PERF-*) must be run on a low-end reference device, not emulator

---

## 7. Document History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2026-01-01 | [Author] | Initial draft |
