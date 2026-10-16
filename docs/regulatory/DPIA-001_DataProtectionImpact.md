# Data Protection Impact Assessment
## ECG Arrhythmia Monitor — Android Application
### GDPR Article 35 / MDR Annex I Section 17

**Document ID:** DPIA-001  
**Version:** 1.1  
**Date:** 2026-10-16  
**Status:** DRAFT — Requires review by Data Protection Officer; cloud sync is already deployed (see §7)  

> **Regulatory status:** Educational project; not a certified medical device. GDPR applies regardless: the app processes health data.

---

## 1. Purpose and Trigger

This DPIA is required because the app processes **special category health data** (ECG recordings and cardiac arrhythmia classifications) under GDPR Article 9. A DPIA is mandatory before processing begins when data is sensitive by nature.

Version 1.0 required this document to be updated and re-approved before any cloud upload was implemented. Cloud sync to Firebase Firestore has since been implemented and is active. This version (1.1) documents the processing as it now exists. **The §7 prerequisites were not completed before deployment;** their status is recorded there.

---

## 2. Data Controller and Processor

| Role | Party | Detail |
|------|-------|--------|
| Data Controller | [Organisation name] | Determines purposes and means of processing |
| Data Processor | Google (Firebase Authentication, Cloud Firestore) | Stores session metadata and arrhythmia events; requires a Data Processing Agreement (not evidenced) |

---

## 3. Description of Processing

### 3.1 What Data Is Collected

| Data Type | Category | Stored | Legal Basis | Retention |
|-----------|----------|--------|-------------|-----------|
| ECG waveform samples (filtered ADC counts, timestamp) | Health — special category | Device only | Explicit consent (Art. 9(2)(a)) | Until user deletes |
| Arrhythmia event classifications (time, type, confidence) | Health — special category | Device + Firestore | Explicit consent | Until user deletes |
| Session statistics (start/end time, duration, HR, beat counts, health status) | Health — derived | Device + Firestore | Explicit consent | Until user deletes |
| Session name (free text entered by the user) | May contain identifiers | Device + Firestore | Explicit consent | Until user deletes |
| Model-health scalars (confidence, drift, signal-quality rates, inference time) | Health — derived (technical) | Device + Firestore | Explicit consent | Until user deletes |
| Firebase anonymous-auth UID | Pseudonymous identifier | Device + Firebase | Explicit consent | Until user deletes cloud data |
| Consent records (timestamp, version, granted/withdrawn) | Administrative | Device only | Legal obligation (Art. 6(1)(c)) | Permanent — legal record |
| Audit log (action, timestamp, session ID) | Administrative | Device only | Legitimate interest (security, compliance) | Until user deletes |

The anonymous UID is a pseudonym, not anonymisation: the cloud data remains personal health data under GDPR.

### 3.2 What Data Is NOT Collected
- Name, email, or any direct identifier (unless the user types one into a session name)
- Location data (the location permission is requested only because Android requires it for BLE scanning on older versions; location is not read or stored)
- Device identifiers beyond Android app sandbox and the Firebase anonymous UID
- Contact information
- Any data from other apps

### 3.3 Data Flows (Local)

```
User body → AD8232 + ESP32-S3 → BLE → Android app → Room DB (device storage)
```

Raw ECG waveforms stay in the Android app sandbox and are never uploaded.

### 3.4 Data Flows (Cloud Sync — implemented)

```
Android app → HTTPS/TLS → Firebase Authentication (anonymous) + Cloud Firestore
             users/{uid}/sessions/{sessionId}            session metadata
             users/{uid}/events/{sessionId}_{index}      arrhythmia events
```

Upload starts automatically after each saved recording and retries pending sessions at every app launch. It runs whenever the user holds valid consent; there is no separate opt-in or setting to turn it off. The Firestore region has not been confirmed from the project configuration.

---

## 4. Necessity and Proportionality

| Principle | Assessment |
|-----------|------------|
| **Purpose limitation** | ✅ Data collected only for personal health monitoring; no secondary use without fresh consent |
| **Data minimisation** | ⚠️ Raw waveforms are kept local, which is proportionate. The free-text session name is uploaded although the cloud copy does not need it |
| **Storage limitation** | ✅ User can delete all data at any time; no automatic retention period enforced by app |
| **Accuracy** | ✅ Data is recorded as-is from sensor; no modification after storage |
| **Integrity and confidentiality** | ⚠️ Local data relies on the Android sandbox (SQLCipher not implemented); cloud data relies on Firestore security rules that have not been reviewed |
| **Transparency** | ❌ The consent text states data "is not transmitted to any server, cloud service, or third party", which is no longer true (Risk 6) |

---

## 5. Risk Assessment

### Risk 1 — Unauthorised Access via Device Compromise
| Field | Detail |
|-------|--------|
| Threat | Attacker gains physical or remote access to device |
| Likelihood | Low (requires device unlock or malware) |
| Impact | High (health data exposed) |
| Current mitigation | Android app sandbox |
| Residual risk | Medium |
| Additional mitigation needed | SQLCipher database encryption; biometric app lock |

### Risk 2 — Data Exposed via Android Backup or Device Transfer
| Field | Detail |
|-------|--------|
| Threat | Android Auto Backup uploads the database to Google Drive, or device-to-device transfer copies it to another phone, without user awareness |
| Likelihood | Low for cloud backup; Medium for device-to-device transfer |
| Impact | High |
| Current mitigation | `android:allowBackup="false"` and `android:fullBackupContent="false"` set in AndroidManifest.xml. `data_extraction_rules.xml` is the unmodified template; with targetSdk 34 this probably leaves device-to-device transfer enabled (to be confirmed) |
| Residual risk | Medium |
| **Required mitigation** | Exclude the database from device-to-device transfer in `data_extraction_rules.xml` (RMF-001 OA-5) |

### Risk 3 — Consent Record Insufficient
| Field | Detail |
|-------|--------|
| Threat | User disputes they gave consent; no auditable record |
| Likelihood | Low |
| Impact | Medium (regulatory non-compliance) |
| Current mitigation | `consent_records` table with timestamp and version |
| Residual risk | Low |

### Risk 4 — Cloud Data Breach
| Field | Detail |
|-------|--------|
| Threat | Firebase breach or misconfiguration exposes user health data |
| Likelihood | Low–Medium (Firebase is secure by default only if the security rules restrict each user to their own path; rules not reviewed) |
| Impact | High (health metadata and events; no raw waveforms) |
| Current mitigation | Anonymous auth; per-user document path; TLS in transit; raw waveforms not uploaded |
| Residual risk | Medium |
| **Required** | Security rules review; EU region confirmation; Data Processing Agreement with Google (§7) |

### Risk 5 — Re-identification of Anonymised Data
| Field | Detail |
|-------|--------|
| Threat | If data is ever shared, ECG patterns could be linked to individuals |
| Likelihood | Low (no sharing implemented; raw ECG not in the cloud) |
| Impact | High |
| Current mitigation | No sharing implemented |
| Required if sharing planned | Formal anonymisation assessment; ethics review |

### Risk 6 — Processing Not Disclosed to the User
| Field | Detail |
|-------|--------|
| Threat | Users consent to a text that says nothing is transmitted, while session metadata and events upload to Firestore. Consent given on that basis is not informed (Art. 7, Art. 13) |
| Likelihood | High (affects every user) |
| Impact | High (invalid legal basis for the cloud processing) |
| Current mitigation | None |
| Residual risk | High |
| **Required mitigation** | Update the consent text to describe the cloud sync, increment `CONSENT_VERSION` so existing users are re-prompted (RMF-001 OA-1, SRS-001 FR-CON-08) |

### Risk 7 — Identifiers in Free-Text Session Names
| Field | Detail |
|-------|--------|
| Threat | A user types a name or other identifier into a session name, which is uploaded with the health data |
| Likelihood | Medium |
| Impact | Medium (links the pseudonymous cloud record to a person) |
| Current mitigation | None |
| Residual risk | Medium |
| Additional mitigation needed | Stop uploading the session name, or warn the user that it is uploaded |

---

## 6. Data Subject Rights Implementation

| Right | GDPR Article | Implementation Status |
|-------|-------------|----------------------|
| Right to be informed | Art. 13/14 | ❌ ConsentScreen explains local processing but states that nothing is transmitted (Risk 6) |
| Right of access | Art. 15 | ⚠️ User can view data in app; no formal export of consent records or of the cloud copy |
| Right to erasure | Art. 17 | ⚠️ "Delete All" deletes local and cloud data. Deleting a single session leaves its cloud copy (RMF-001 OA-2) |
| Right to withdraw consent | Art. 7(3) | ✅ "Withdraw Consent" in Settings; deletes local and cloud data |
| Right to data portability | Art. 20 | ✅ CSV and PDF export in SessionDetail |
| Right to restrict processing | Art. 18 | ⚠️ Not explicitly implemented; cloud sync cannot be paused separately from consent |
| Right to object | Art. 21 | ✅ Covered by consent withdrawal |

---

## 7. Consultation

Version 1.0 listed these as prerequisites for cloud implementation. Cloud sync was implemented before they were completed. Status as of 2026-10-16:

| # | Prerequisite | Status |
|---|--------------|--------|
| 1 | **DPO review** of this document (if organisation has >250 employees or processes health data at scale) | ⏳ Not done |
| 2 | **Data Processing Agreement** signed with Google (Firebase) | ⏳ Not evidenced |
| 3 | **Firebase Security Rules** reviewed — ensure users can only read/write their own data | ⏳ Not evidenced (rules are not in the repository) |
| 4 | **EU data residency** confirmed — Firebase region set to `europe-west` before first write | ⏳ Not confirmed |
| 5 | **Updated consent text** — the ConsentScreen still states "data is not transmitted"; must be updated and `CONSENT_VERSION` incremented | ❌ Not done (Risk 6) |

---

## 8. Immediate Actions Required

| Action | Priority | Owner | Status |
|--------|----------|-------|--------|
| Add `android:allowBackup="false"` to AndroidManifest.xml | **Critical** | Developer | ✅ Done |
| Update consent text for cloud sync and increment `CONSENT_VERSION` | **Critical** | Developer | ⏳ Open (OA-1) |
| Complete §7 items 2–4 (DPA, security rules, region) | **Critical** | Developer | ⏳ Open |
| Delete the cloud copy when a single session is deleted | High | Developer | ⏳ Open (OA-2) |
| Exclude the database from device-to-device transfer | High | Developer | ⏳ Open (OA-5) |
| Stop uploading, or disclose, the free-text session name | Medium | Developer | ⏳ Open |
| Implement SQLCipher database encryption | High | Developer | ⏳ Open |
| Add biometric app lock | Medium | Developer | ⏳ Open |

---

## 9. Document History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2026-01-01 | [Author] | Initial draft — local-only version |
| 1.1 | 2026-10-16 | Oliver Moberg | Updated for the implemented Firebase cloud sync: Google added as processor; data inventory with storage location; cloud data flow; Risk 2 updated (backup disabled, device transfer open); Risk 4 now current; added Risk 6 (consent text does not disclose sync) and Risk 7 (free-text session name uploaded); rights table and §7/§8 converted to status lists |

*Must be reviewed and re-approved once the §7 items and Risk 6 are resolved.*
