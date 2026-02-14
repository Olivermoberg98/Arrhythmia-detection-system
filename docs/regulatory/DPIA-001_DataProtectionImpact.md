# Data Protection Impact Assessment
## ECG Arrhythmia Monitor — Android Application
### GDPR Article 35 / MDR Annex I Section 17

**Document ID:** DPIA-001  
**Version:** 1.0  
**Date:** 2026-01-01  
**Status:** DRAFT — Requires review by Data Protection Officer before any cloud deployment  

---

## 1. Purpose and Trigger

This DPIA is required because the app processes **special category health data** (ECG recordings and cardiac arrhythmia classifications) under GDPR Article 9. A DPIA is mandatory before processing begins when data is sensitive by nature.

This document must be **updated and re-approved** before implementing any cloud upload, backend API, or third-party analytics.

---

## 2. Data Controller and Processor

| Role | Party | Detail |
|------|-------|--------|
| Data Controller | [Organisation name] | Determines purposes and means of processing |
| Data Processor | None (current) | No third parties process data in current local-only version |
| Future Processor (planned) | Firebase / Google | If cloud sync implemented — requires Data Processing Agreement |

---

## 3. Description of Processing

### 3.1 What Data Is Collected

| Data Type | Category | Legal Basis | Retention |
|-----------|----------|-------------|-----------|
| ECG waveform samples (voltage, timestamp) | Health — special category | Explicit consent (Art. 9(2)(a)) | Until user deletes |
| Arrhythmia event classifications | Health — special category | Explicit consent | Until user deletes |
| Session statistics (HR, beat counts, health status) | Health — derived | Explicit consent | Until user deletes |
| Consent records (timestamp, version, granted/withdrawn) | Administrative | Legal obligation (Art. 6(1)(c)) | Permanent — legal record |
| Audit log (action, timestamp, session ID) | Administrative | Legitimate interest (security, compliance) | Until user deletes |

### 3.2 What Data Is NOT Collected
- Name, email, or any direct identifier
- Location data
- Device identifiers beyond Android app sandbox
- Contact information
- Any data from other apps

### 3.3 Data Flows (Current — Local Only)

```
User body → ESP32 sensor → BLE → Android app → Room DB (device storage)
```

No data leaves the device in the current version. All storage is within Android's app sandbox.

### 3.4 Data Flows (Planned — Cloud Sync)

```
Android app → HTTPS/TLS → Firebase Firestore → Google Cloud infrastructure (EU region)
```

**This flow requires this DPIA to be updated and a Data Processing Agreement with Google to be signed before implementation.**

---

## 4. Necessity and Proportionality

| Principle | Assessment |
|-----------|------------|
| **Purpose limitation** | ✅ Data collected only for personal health monitoring; no secondary use without fresh consent |
| **Data minimisation** | ✅ Only clinically necessary fields stored; no metadata beyond timestamps |
| **Storage limitation** | ✅ User can delete all data at any time; no automatic retention period enforced by app |
| **Accuracy** | ✅ Data is recorded as-is from sensor; no modification after storage |
| **Integrity and confidentiality** | ⚠️ Currently relies on Android sandbox; database encryption (SQLCipher) not yet implemented |

---

## 5. Risk Assessment

### Risk 1 — Unauthorised Access via Device Compromise
| Field | Detail |
|-------|--------|
| Threat | Attacker gains physical or remote access to device |
| Likelihood | Low (requires device unlock or malware) |
| Impact | High (health data exposed) |
| Current mitigation | Android app sandbox; data local only |
| Residual risk | Medium |
| Additional mitigation needed | SQLCipher database encryption; biometric app lock |

### Risk 2 — Data Exposed via Android Backup
| Field | Detail |
|-------|--------|
| Threat | Android Auto Backup uploads database to Google Drive without user awareness |
| Likelihood | Medium (enabled by default on Android) |
| Impact | High (health data in Google cloud without explicit consent) |
| Current mitigation | None implemented |
| Residual risk | High |
| **Required mitigation** | Set `android:allowBackup="false"` and `android:fullBackupContent="false"` in AndroidManifest.xml immediately |

### Risk 3 — Consent Record Insufficient
| Field | Detail |
|-------|--------|
| Threat | User disputes they gave consent; no auditable record |
| Likelihood | Low |
| Impact | Medium (regulatory non-compliance) |
| Current mitigation | `consent_records` table with timestamp and version |
| Residual risk | Low |

### Risk 4 — Cloud Data Breach (Future)
| Field | Detail |
|-------|--------|
| Threat | Firebase breach or misconfiguration exposes user health data |
| Likelihood | Low (Firebase has strong security) |
| Impact | Critical |
| Current mitigation | N/A — not yet implemented |
| **Required before implementation** | Security rules review; EU region selection; Data Processing Agreement with Google; updated DPIA section |

### Risk 5 — Re-identification of Anonymised Data
| Field | Detail |
|-------|--------|
| Threat | If data is ever shared, ECG patterns could be linked to individuals |
| Likelihood | Low (current local-only) |
| Impact | High |
| Current mitigation | No sharing implemented |
| Required if sharing planned | Formal anonymisation assessment; ethics review |

---

## 6. Data Subject Rights Implementation

| Right | GDPR Article | Implementation Status |
|-------|-------------|----------------------|
| Right to be informed | Art. 13/14 | ✅ ConsentScreen explains all processing |
| Right of access | Art. 15 | ⚠️ User can view data in app; no formal export of consent records yet |
| Right to erasure | Art. 17 | ✅ "Delete All Recordings" in Settings |
| Right to withdraw consent | Art. 7(3) | ✅ "Withdraw Consent" in Settings |
| Right to data portability | Art. 20 | ✅ CSV and PDF export in SessionDetail |
| Right to restrict processing | Art. 18 | ⚠️ Not explicitly implemented; data deletion covers this in practice |
| Right to object | Art. 21 | ✅ Covered by consent withdrawal |

---

## 7. Consultation

Before any cloud implementation, the following must occur:

1. **DPO review** of this document (if organisation has >250 employees or processes health data at scale)
2. **Data Processing Agreement** signed with Google (Firebase)
3. **Firebase Security Rules** reviewed — ensure users can only read/write their own data
4. **EU data residency** confirmed — Firebase region must be set to `europe-west` before first write
5. **Updated consent text** — current ConsentScreen states "data is not transmitted"; this must be updated before cloud sync is enabled, and `CONSENT_VERSION` incremented

---

## 8. Immediate Actions Required

These must be resolved regardless of cloud plans:

| Action | Priority | Owner |
|--------|----------|-------|
| Add `android:allowBackup="false"` to AndroidManifest.xml | **Critical** | Developer |
| Implement SQLCipher database encryption | High | Developer |
| Add biometric app lock | Medium | Developer |

---

## 9. Document History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2026-01-01 | [Author] | Initial draft — local-only version |

*Must be updated to v1.1 before any cloud implementation is deployed.*
