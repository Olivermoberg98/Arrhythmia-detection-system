# Software Architecture Document
## ECG Arrhythmia Monitor — Android Application
### IEC 62304:2015 §5.3

**Document ID:** SAD-001  
**Version:** 1.0  
**Date:** 2026-01-01  
**Status:** DRAFT  

---

## 1. System Overview

The ECG Monitor system consists of two products forming a system under MDR Article 22:

```
┌─────────────────────┐         BLE (Kable)        ┌──────────────────────┐
│   ESP32 ECG Device  │ ─────────────────────────► │  Android Application │
│  (Hardware, IIa)    │     360 Hz ECG stream       │  (MDSW, IIa)         │
└─────────────────────┘                             └──────────────────────┘
                                                              │
                                                         Room DB
                                                    (Local device storage)
```

This document covers the Android application only. ESP32 firmware architecture is in a separate document.

---

## 2. Application Layer Architecture

```
┌──────────────────────────────────────────────────────┐
│                    UI LAYER                           │
│  ConsentScreen  LiveMonitorScreen  HistoryScreen      │
│  SessionDetailScreen  SettingsScreen                  │
│  (Jetpack Compose — stateless, driven by StateFlow)   │
└──────────────────┬───────────────────────────────────┘
                   │ StateFlow / Events
┌──────────────────▼───────────────────────────────────┐
│                 VIEWMODEL LAYER                       │
│  LiveMonitorViewModel    HistoryViewModel             │
│  SessionDetailViewModel  SettingsViewModel            │
│  (Android ViewModel — survives configuration change)  │
└──────────────────┬───────────────────────────────────┘
                   │ suspend functions / Flow
┌──────────────────▼───────────────────────────────────┐
│                REPOSITORY LAYER                       │
│  EcgRepository          ConsentManager               │
│  (Single source of truth — abstracts data sources)    │
└─────────┬────────────────────────┬───────────────────┘
          │                        │
┌─────────▼──────────┐  ┌──────────▼──────────────────┐
│    DATA LAYER       │  │       BLE LAYER              │
│  Room Database      │  │  BleManager (Kable)          │
│  - ecg_sessions     │  │  BlePermissions              │
│  - ecg_readings     │  │  ConnectionState             │
│  - arrhythmia_events│  └─────────────────────────────┘
│  - consent_records  │
│  - audit_log        │
└────────────────────┘
```

---

## 3. Module Descriptions

### 3.1 UI Layer — `ui/screens/`

| Screen | Responsibility |
|--------|---------------|
| `ConsentScreen` | First-launch gate; blocks access until consent recorded |
| `LiveMonitorScreen` | Displays real-time ECG stream and recording controls |
| `HistoryScreen` | Lists all stored sessions with sort and filter |
| `SessionDetailScreen` | Waveform playback, statistics, event timeline, export |
| `SettingsScreen` | BLE device management, data privacy controls |

All screens are stateless Composables — they observe `StateFlow` from their ViewModel and emit events upward via callbacks. No business logic lives in screens.

### 3.2 ViewModel Layer — `ui/viewmodels/`

| ViewModel | Key Responsibilities |
|-----------|---------------------|
| `LiveMonitorViewModel` | BLE data ingestion, R-peak detection, recording buffer management |
| `HistoryViewModel` | Session list with reactive sort, MIT-BIH initialisation |
| `SessionDetailViewModel` | Sliding window playback, event filtering, seek-to-position |
| `SettingsViewModel` | BLE scan/connect lifecycle, error propagation |

### 3.3 Repository Layer — `data/repository/`

`EcgRepository` is the single interface between ViewModels and the database. It owns:
- Session CRUD operations
- Statistics calculation (HR, beat counts, health status determination)
- MIT-BIH data loader initialisation

`ConsentManager` is a parallel repository handling:
- Consent record storage and validation
- Audit log writes
- Full data deletion (GDPR Article 17)

### 3.4 Data Layer — `data/database/`

Room database (`EcgDatabase`) with 5 entities:

| Table | Purpose | Safety relevance |
|-------|---------|-----------------|
| `ecg_sessions` | Session metadata and statistics | Core clinical data |
| `ecg_readings` | Raw voltage samples (360 Hz) | Core clinical data |
| `arrhythmia_events` | Detected events with type and confidence | Core clinical data |
| `consent_records` | Consent history — never deleted | GDPR compliance |
| `audit_log` | All data access events | ISO 14971 traceability |

Database version: **2**. All schema changes managed via explicit `Migration` objects — destructive migration is prohibited in release builds.

### 3.5 BLE Layer — `data/ble/`

| Class | Responsibility |
|-------|---------------|
| `BleManager` | Scan, connect, receive ECG data via Kable |
| `BlePermissions` | Runtime permission management (API 26/31+ aware) |
| `ConnectionState` | Sealed class representing all connection states |

Data flow from device:
```
ESP32 → BLE notification → BleManager.parseEcgData() → _ecgDataFlow → LiveMonitorViewModel
```

---

## 4. Navigation Architecture

Navigation uses Jetpack Navigation Compose with string routes:

```
NavigationHost
    ├── ConsentScreen (shown if no valid consent)
    └── MainAppNavigation (shown if consent valid)
            ├── live        → LiveMonitorScreen
            ├── history     → HistoryScreen
            ├── settings    → SettingsScreen
            └── session_detail/{sessionId} → SessionDetailScreen
```

The consent gate in `NavigationHost` is the single enforcement point for FR-CON-02 (user must accept consent before accessing recording functionality).

---

## 5. Data Flow — Recording Session

```
1. User taps "Start Recording" (LiveMonitorScreen)
2. LiveMonitorViewModel.startRecording() creates session entity
3. BleManager streams EcgData via ecgDataFlow
4. ViewModel buffers samples in memory
5. Each batch written to ecg_readings via EcgRepository
6. User taps "Stop Recording"
7. ViewModel calculates statistics
8. EcgRepository saves EcgSessionEntity with stats
9. ConsentManager.log(SESSION_RECORDED)
10. Navigate to SessionDetailScreen
```

---

## 6. Data Flow — Consent and Deletion

```
First launch:
1. NavigationHost checks ConsentManager.hasValidConsent()
2. If false → ConsentScreen shown
3. User accepts → ConsentManager.recordConsent(granted=true)
4. consent_records row written with timestamp + version
5. audit_log row written: CONSENT_GRANTED
6. NavigationHost shows MainAppNavigation

Deletion:
1. User taps "Delete All Recordings" in SettingsScreen
2. Confirmation dialog shown
3. ConsentManager.deleteAllUserData()
4. ecg_sessions, ecg_readings, arrhythmia_events, audit_log tables cleared
5. consent_records retained (legal requirement)
6. Final audit_log entry written: ALL_DATA_DELETED
```

---

## 7. Security Architecture

| Concern | Current Mitigation | Future Enhancement |
|---------|-------------------|-------------------|
| Data at rest | Android app sandbox | SQLCipher encryption |
| Data in transit | No network transmission | TLS 1.3 if cloud added |
| Access control | Android OS sandboxing | Biometric authentication |
| Consent audit trail | `consent_records` table (never deleted) | Immutable append-only log |
| Backup | No backup (sensitive data) | Encrypted backup if enabled |

To disable Android backup for the database, add to `AndroidManifest.xml`:
```xml
android:allowBackup="false"
android:fullBackupContent="false"
```

---

## 8. Document History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2026-01-01 | [Author] | Initial draft |
