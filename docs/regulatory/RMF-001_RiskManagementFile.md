# Risk Management File
## ECG Arrhythmia Monitor — App + Device
### ISO 14971:2019 Compliant

**Document ID:** RMF-001  
**Version:** 1.0  
**Date:** 2026-01-01  
**Status:** DRAFT — Requires clinical review before submission  

---

## 1. Scope and Intended Use

**Intended use:** Personal ECG monitoring for general consumers to detect and review cardiac arrhythmia events. Not intended for clinical diagnosis.

**Intended users:** Adults with no clinical training, self-monitoring cardiac health at home.

**Use environment:** Home, ambulatory (not clinical setting).

**System components:**
- ESP32-based ECG acquisition device (wearable patch or chest leads)
- Android mobile application (ECG Monitor v1.0)

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

---

## 3. Hazard Analysis

### RISK-001: False Negative — Missed Arrhythmia
| Field | Value |
|-------|-------|
| **Hazard** | Arrhythmia detection algorithm fails to flag a clinically significant event |
| **Hazardous situation** | User experiences AFib or dangerous PVC pattern; app shows "Normal" |
| **Harm** | User does not seek medical attention; delayed treatment of cardiac event |
| **Severity** | 4 (Critical — could lead to stroke or cardiac emergency) |
| **Probability (pre-mitigation)** | 3 (ML models have known false negative rates) |
| **Pre-mitigation Risk** | Medium |
| **Mitigations** | M1: Validate algorithm against MIT-BIH + clinical dataset; M2: Disclaimer in UI that app is not a clinical diagnostic device; M3: "Attention" threshold is conservative (flags >10% PVC); M4: Consent screen includes explicit "not a substitute for medical advice" |
| **Probability (post-mitigation)** | 2 |
| **Residual Risk** | Low |
| **Acceptable?** | Yes |

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
| **Mitigations** | M1: Three-level health status (Normal/Attention/Concerning) reduces alarm rate; M2: UI shows raw beat counts so user can assess themselves; M3: "Consult a doctor" phrasing rather than "you have a condition" |
| **Probability (post-mitigation)** | 2 |
| **Residual Risk** | Negligible |
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
| **Mitigations** | M1: Signal quality indicator in Live Monitor UI; M2: Recording guidance shown before starting a session; M3: Sessions with >20% invalid samples flagged in session detail; M4: Hardware design includes EMC shielding per IEC 60601-1 |
| **Probability (post-mitigation)** | 2 |
| **Residual Risk** | Low |
| **Acceptable?** | Yes |

---

### RISK-004: Electrical Safety — Patient Leakage Current
| Field | Value |
|-------|-------|
| **Hazard** | Leakage current through ECG electrodes to patient |
| **Hazardous situation** | User receives electric shock through sensor contact |
| **Harm** | Ventricular fibrillation; cardiac arrest |
| **Severity** | 5 (Catastrophic) |
| **Probability (pre-mitigation)** | 2 (ESP32 prototype lacks medical-grade isolation) |
| **Pre-mitigation Risk** | Medium |
| **Mitigations** | M1: Galvanic isolation between patient connection and ESP32 power rail (ISO requirement per IEC 60601-1); M2: Maximum allowable patient leakage current: 10μA (Type CF per IEC 60601-1); M3: Hardware validation testing required before any human use; M4: Battery-powered operation only (no mains connection during patient contact) |
| **Probability (post-mitigation)** | 1 |
| **Residual Risk** | Low |
| **Acceptable?** | Yes — contingent on hardware passing IEC 60601-1 testing |

---

### RISK-005: Data Breach — Unauthorised Access to Health Records
| Field | Value |
|-------|-------|
| **Hazard** | ECG recordings accessed by unauthorised party |
| **Hazardous situation** | Health data disclosed — insurance discrimination; psychological distress |
| **Harm** | Privacy violation; discrimination risk |
| **Severity** | 3 |
| **Probability (pre-mitigation)** | 3 (local storage without encryption) |
| **Pre-mitigation Risk** | Medium |
| **Mitigations** | M1: Data stored locally only — no cloud transmission; M2: Android app sandboxing restricts access to app data; M3: Consent screen documents what is stored; M4: Deletion capability ensures GDPR right to erasure; M5: Future: SQLCipher database encryption |
| **Probability (post-mitigation)** | 2 |
| **Residual Risk** | Low |
| **Acceptable?** | Yes |

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
| **Mitigations** | M1: Incremental write to database (not a single commit at end); M2: Session marked as "incomplete" if app terminates unexpectedly; M3: Incomplete sessions displayed with warning indicator in History |
| **Probability (post-mitigation)** | 2 |
| **Residual Risk** | Low |
| **Acceptable?** | Yes |

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
| **Mitigations** | M1: Persistent disclaimer in Session Detail screen; M2: Consent screen explicitly states app is not a diagnostic tool; M3: UI language avoids clinical terms ("health indicator" not "diagnosis"); M4: IEC 62366 usability testing to validate comprehension |
| **Probability (post-mitigation)** | 2 |
| **Residual Risk** | Low |
| **Acceptable?** | Yes |

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
| **Mitigations** | M1: Visual disconnection indicator (connection status banner in Live Monitor); M2: Recording stops automatically and alerts user on BLE loss; M3: Data gaps flagged in session detail view |
| **Probability (post-mitigation)** | 2 |
| **Residual Risk** | Low |
| **Acceptable?** | Yes |

---

## 4. Risk Summary Table

| Risk ID | Description | Pre-mitigation | Post-mitigation | Acceptable |
|---------|-------------|----------------|-----------------|------------|
| RISK-001 | False negative arrhythmia | Medium | Low | ✅ |
| RISK-002 | False positive alarm | Low | Negligible | ✅ |
| RISK-003 | Signal quality / noise | Medium | Low | ✅ |
| RISK-004 | Electrical safety | Medium | Low | ✅ (hardware test required) |
| RISK-005 | Data breach | Medium | Low | ✅ |
| RISK-006 | Software crash | Medium | Low | ✅ |
| RISK-007 | User misinterpretation | Medium | Low | ✅ |
| RISK-008 | BLE disconnection | Medium | Low | ✅ |

**Overall residual risk: Acceptable**, subject to hardware passing IEC 60601-1 electrical safety testing.

---

## 5. Risk Control Verification

The following mitigations require formal verification before regulatory submission:

| Mitigation | Verification Method | Status |
|-----------|---------------------|--------|
| ML algorithm false negative rate | Performance study vs clinical dataset | ⏳ Pending |
| IEC 60601-1 leakage current | Accredited lab testing | ⏳ Pending |
| Usability testing (IEC 62366) | Formative + summative testing | ⏳ Pending |
| Signal quality indicator | Software testing | ⏳ Pending |
| Incomplete session handling | Software testing | ⏳ Pending |

---

## 6. Document History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2026-01-01 | [Author] | Initial draft |

*This document must be reviewed by a qualified clinical/regulatory professional before submission to a Notified Body.*
