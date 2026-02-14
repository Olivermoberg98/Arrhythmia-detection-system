# Software Requirements Specification
## ECG Monitor — Android Application
### IEC 62304:2015 Compliant

**Document ID:** SRS-001  
**Version:** 1.0  
**Date:** 2026-01-01  
**Software Safety Class:** Class B (harm possible but not life-threatening if mitigations in place)  
**Status:** DRAFT  

---

## 1. Intended Use

The ECG Monitor app acquires, displays, and analyses ECG waveform data from a paired ESP32 ECG sensor via Bluetooth LE. It provides arrhythmia event detection and health status indicators for personal monitoring by adult consumers. It is not intended for clinical diagnosis.

---

## 2. System Context

| Component | Role |
|-----------|------|
| ESP32 ECG Device | Acquires ECG signal at 360 Hz; transmits via BLE |
| Android Application | Receives, displays, stores, and analyses ECG data |
| Room Database (local) | Persistent storage of sessions, readings, events |
| User | Initiates recordings; reviews results |

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
| FR-ECG-03 | The app shall display the current heart rate derived from R-R intervals | Must | — |
| FR-ECG-04 | The app shall display a signal quality indicator | Must | RISK-003 |
| FR-ECG-05 | Sampling rate shall be 360 Hz (configurable for future hardware) | Must | — |

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
| FR-ARR-01 | The app shall classify each beat as: Normal, PVC, AFib, or Other | Must | RISK-001 |
| FR-ARR-02 | Detection confidence score shall be stored with each event | Must | RISK-001 |
| FR-ARR-03 | Health status shall be determined as: Normal / Attention / Concerning per defined thresholds | Must | RISK-001, RISK-007 |
| FR-ARR-04 | Health status thresholds: Concerning = AFib present OR >20% PVCs; Attention = >10% PVCs OR HR >100 or <50; Normal = otherwise | Must | RISK-001 |
| FR-ARR-05 | The app shall not use the word "diagnosis" or "condition" in health status displays | Must | RISK-007 |

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

---

## 4. Non-Functional Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| NFR-01 | App shall support Android API 26 (Android 8) and above | Must |
| NFR-02 | App shall not transmit any user data over the network | Must |
| NFR-03 | Live waveform rendering shall not drop below 15 FPS | Should |
| NFR-04 | App shall not crash during a recording session (crash rate < 0.1% of sessions) | Must |
| NFR-05 | Database write latency for recording samples shall not exceed 50ms | Should |
| NFR-06 | All user-facing health status messages shall be reviewed for clarity by a non-technical user | Must |
| NFR-07 | Consent text shall be reviewed by a legal or regulatory professional before release | Must |

---

## 5. Constraints and Assumptions

- Algorithm accuracy is dependent on electrode placement quality; signal quality guidance is provided in the UI
- The app depends on Android BLE stack reliability, which varies by device manufacturer
- MIT-BIH database is used for development and initial validation only; clinical dataset required for regulatory submission
- ESP32 hardware must pass IEC 60601-1 electrical safety testing before human use

---

## 6. Traceability Summary

| Requirement Group | Related Risk(s) | Related Test Cases |
|-------------------|-----------------|-------------------|
| FR-BLE | RISK-008 | TC-BLE-01 to TC-BLE-05 (TBD) |
| FR-ARR | RISK-001, RISK-002, RISK-003 | TC-ARR-01 to TC-ARR-05 (TBD) |
| FR-CON, FR-AUD | RISK-005 | TC-SEC-01 to TC-SEC-07 (TBD) |
| FR-REC | RISK-006 | TC-REC-01 to TC-REC-06 (TBD) |

*Test cases to be defined in the Software Verification Plan (SVP-001).*

---

## 7. Document History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2026-01-01 | [Author] | Initial draft |

*This document must be reviewed by a qualified regulatory professional before Notified Body submission.*
