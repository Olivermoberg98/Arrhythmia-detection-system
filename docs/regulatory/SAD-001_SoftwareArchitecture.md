# Software Architecture Document
## ECG Arrhythmia Monitor — Android Application and Device Firmware
### IEC 62304:2015 §5.3

**Document ID:** SAD-001  
**Version:** 1.1  
**Date:** 2026-10-16  
**Status:** DRAFT  

> **Regulatory status:** Educational project. This document follows the structure of IEC 62304 as a design discipline. The system is **not a certified medical device**, has not been assessed by a Notified Body, and must not be used for clinical purposes.

---

## 1. System Overview

The ECG Monitor system consists of an acquisition device, an Android application, a cloud metadata store, and an offline training pipeline:

```
┌──────────────────────────┐   BLE GATT (NimBLE / Kable)    ┌──────────────────────────┐
│  ESP32-S3 + AD8232 board │ ─────────────────────────────► │   Android Application    │
│  250 Hz, filter, detect  │   stream: 10 samples / packet  │  TFLite classifier (N/S/V)│
│  beats, RR features      │   beat:   128-sample window +  │  quality gate, monitoring │
└──────────────────────────┘           3 RR features        └────────────┬─────────────┘
                                                                         │
                                         ┌───────────────────────────────┼──────────────────────┐
                                         │                               │                      │
                                    Room DB (local)            Firebase Firestore         model assets
                              sessions, readings, events,   session metadata + events   (from ml_pipeline,
                                consent, audit log          only — no raw waveforms     trained offline)
```

**Inference runs on the phone, permanently** (moved from the ESP32 in `c3f9e59`). The firmware's job ends at filtered samples, beat windows and RR features; the app classifies.

Section 2 summarises the firmware, because the app and the model depend on its exact behaviour (SRS-001 §3.9). Sections 3–8 cover the Android application.

---

## 2. Device Firmware Architecture (ESP32-S3)

**Hardware:** ESP32-S3-N16R8 camera board; AD8232 board (red "AD8232 Heart Monitor" V502, SparkFun SEN-12650 clone, datasheet Figure 66 circuit: analogue 2-pole HP 0.48 Hz, 2-pole LP 41 Hz, gain ≈1100); 3-lead cable, disposable electrodes; USB-powered. Pins: GPIO1 (ADC1_CH0) = AD8232 output; GPIO42 / GPIO41 = lead-off LO+ / LO−.

| Module | File | Responsibility |
|--------|------|----------------|
| Configuration | `src/config.h` | All cross-project constants: sample rate, filter coefficients, detector parameters, beat window, RR window, BLE UUIDs and packet structs |
| ECG sensor | `src/sensors/ecg/ECGSensor.cpp` | Timer-driven ADC sampling at 250 Hz; lead-off pins |
| Signal processor | `src/signal_processing/SignalProcessor.cpp` | Causal biquads (HP 0.5 Hz, LP 40 Hz, notch 50 Hz); Pan-Tompkins R-peak detection; slope-energy-centroid fiducial; beat ring buffer; RR features |
| Main loop | `src/main.cpp` | Lead-off debounce (250 ms); 2 s settling hold-back after power-up / leads-on; packet assembly |
| BLE server | `src/ble/BLEManager.cpp` | NimBLE GATT service with stream and beat characteristics |

Signal chain:
```
AD8232 (analogue HP/LP, gain) → ADC 12-bit @ 250 Hz → raw − 2048 (counts)
  → HP 0.5 Hz → LP 40 Hz → notch 50 Hz          (causal biquads)
  ├─► stream: DataPacket (seq, timestamp, lead-off, 10 samples)          26 B
  └─► Pan-Tompkins detection → fiducial (QRS slope-energy centroid, ±15 samples, 3 refinements)
        → 128-sample window (96 before / 32 after)
        → wait for the NEXT R-peak (real post_rr) → RR features [pre_rr_norm, post_rr_norm, ratio_rr]
        → BeatPacket (marker, beat timestamp, beat number, 128 samples, 3 RR features)   275 B
```

Timing consequence: a beat packet arrives one RR interval after its beat; its timestamp still marks the beat. No beats are sent during the 2 s settling period. The first beat after a reset and the last beat before one are never sent.

The training pipeline (`ml_pipeline/scripts/preprocess.py`) reproduces this chain on MIT-BIH, including a model of the board's analogue filters, so the model sees the same inputs in training and on the device.

---

## 3. Application Layer Architecture

```
┌──────────────────────────────────────────────────────┐
│                    UI LAYER                           │
│  ConsentScreen  LiveMonitorScreen  HistoryScreen      │
│  SessionDetailScreen  SettingsScreen  CloudSyncCard   │
│  (Jetpack Compose — stateless, driven by StateFlow)   │
└──────────────────┬───────────────────────────────────┘
                   │ StateFlow / Events
┌──────────────────▼───────────────────────────────────┐
│                 VIEWMODEL LAYER                       │
│  LiveMonitorViewModel    HistoryViewModel             │
│  SessionDetailViewModel  SettingsViewModel            │
│  (Android ViewModel — survives configuration change)  │
└───┬──────────────┬───────────────────┬───────────────┘
    │              │                   │ suspend functions / Flow
┌───▼──────────┐ ┌─▼──────────────────────────────┐ ┌──────────────────────┐
│  ML LAYER    │ │        REPOSITORY LAYER         │ │      BLE LAYER       │
│ Arrhythmia-  │ │  EcgRepository  ConsentManager  │ │  BleManager (Kable)  │
│  Classifier  │ │  SyncManager                    │ │  BlePermissions      │
│ SignalQuality│ └──────┬───────────────┬──────────┘ │  ConnectionState     │
│  Analyzer    │        │               │            └──────────────────────┘
│ Classification│ ┌─────▼──────────┐ ┌──▼───────────────────┐
│  Monitor     │ │  DATA LAYER     │ │   CLOUD LAYER        │
└──────────────┘ │  Room Database  │ │  FirebaseRepository  │
                 │  (5 tables)     │ │  (Firestore, anon.   │
                 └─────────────────┘ │   auth)              │
                                     └──────────────────────┘
```

**Dependency injection is manual.** `EcgMonitorApp` (the `Application` subclass) creates the app-scoped singletons `bleManager`, `consentManager`, `firebaseRepository` and `syncManager` in `onCreate()`. ViewModels reach them through the application and create their own repository and classifier instances. There is no domain/use-case layer.

---

## 4. Module Descriptions

### 4.1 UI Layer — `ui/screens/`

| Screen | Responsibility |
|--------|---------------|
| `ConsentScreen` | First-launch gate; blocks access until consent recorded |
| `LiveMonitorScreen` | Real-time ECG stream, recording controls, lead-off indicator, "Signal & Model Health" card (quality, drift, T/R, suppressed count, beat count) |
| `HistoryScreen` | Lists all stored sessions with sort and filter |
| `SessionDetailScreen` | Waveform playback, statistics, event timeline, export |
| `SettingsScreen` | BLE device management, cloud sync status (`CloudSyncCard`), data privacy controls |

All screens are stateless Composables — they observe `StateFlow` from their ViewModel and emit events upward via callbacks. No business logic lives in screens.

### 4.2 ViewModel Layer — `ui/viewmodels/`

| ViewModel | Key Responsibilities |
|-----------|---------------------|
| `LiveMonitorViewModel` | Stream ingestion and dropped-packet counting; beat-window classification with quality gating; heart rate from the firmware's beats; T/R; recording buffer management |
| `HistoryViewModel` | Session list with reactive sort, MIT-BIH initialisation |
| `SessionDetailViewModel` | Sliding window playback, event filtering, seek-to-position |
| `SettingsViewModel` | BLE scan/connect lifecycle, error propagation |

The app runs no R-peak detector of its own: heart rate and beat timing come from the firmware's beat packets (`1aa2fce`).

### 4.3 ML Layer — `data/ml/`

| Class | Responsibility |
|-------|---------------|
| `ArrhythmiaClassifier` | Loads `assets/arrhythmia_model.tflite` (INT8 weights, float32 I/O) and `assets/model_metadata.json`; converts ADC counts to mV (`ADC_COUNTS_TO_MV` = (3100/4096)/1100); normalises with the training scalers; runs TFLite; chooses the class with `decide()` = `argmax(scores / thresholds)` |
| `SignalQualityAnalyzer` | Scores each beat window (FLAT, SATURATED, NOISY, BASELINE_WANDER → 0–1 score); abnormal classifications on unusable beats are withheld; reports distance from training |
| `ClassificationMonitor` | Per-session health scalars: confidence, low-confidence rate, entropy, poor-signal rate, suppressed events, drift, classification rate with a plausibility flag, inference time. Scalars only, safe to sync |

The shipped model is `model_ensemble_20261015_212815`: the softmax average of 4 training runs in one TFLite file. Model and metadata are generated by `ml_pipeline/` and must be copied together.

### 4.4 Repository Layer — `data/repository/`, `data/consent/`, `data/firebase/`

`EcgRepository` is the single interface between ViewModels and the database. It owns:
- Session CRUD operations
- Statistics calculation (HR, beat counts, health status determination)
- MIT-BIH data loader initialisation

`ConsentManager` is a parallel repository handling:
- Consent record storage and validation
- Audit log writes
- Local data deletion (GDPR Article 17)

`SyncManager` uploads each saved session in the background, retries sessions without a server-acknowledged upload at every launch, and deletes local and cloud data together (`deleteEverywhere()`). Uploads are skipped without valid consent.

### 4.5 Data Layer — `data/database/`

Room database (`EcgDatabase`) with 5 entities:

| Table | Purpose | Safety relevance |
|-------|---------|-----------------|
| `ecg_sessions` | Session metadata, statistics, model-health scalars, `uploadedAt` sync marker | Core clinical data |
| `ecg_readings` | Filtered ECG samples (250 Hz, ADC counts) | Core clinical data |
| `arrhythmia_events` | Detected events with type and confidence | Core clinical data |
| `consent_records` | Consent history — never deleted | GDPR compliance |
| `audit_log` | Data access events | ISO 14971 traceability |

Database version: **5**, with explicit `MIGRATION_1_2` .. `MIGRATION_4_5`. Schemas are exported to `app/schemas` (v3 onwards) for migration tests. Destructive migration is prohibited in release builds.

### 4.6 BLE Layer — `data/ble/`

| Class | Responsibility |
|-------|---------------|
| `BleManager` | Scan (named devices only), connect, MTU negotiation, subscribe to the stream and beat characteristics via Kable, parse packets (`parseStreamPacket`, `parseBeatPacket`) |
| `BlePermissions` | Runtime permission management (API 26/31+ aware) |
| `ConnectionState` | Sealed class representing all connection states (in `BleManager.kt`) |

Data flow from device:
```
ESP32 stream notify → parseStreamPacket() → ecgPacketFlow (SharedFlow, 64, DROP_OLDEST) → LiveMonitorViewModel
ESP32 beat notify   → parseBeatPacket()   → beatWindowFlow                              → LiveMonitorViewModel
lead-off byte       →                       leadOffState (StateFlow)                     → LiveMonitorViewModel
```

### 4.7 Cloud Layer — `data/firebase/`

`FirebaseRepository` signs in anonymously and writes to Firestore under `users/{uid}/sessions/{sessionId}` and `users/{uid}/events/{sessionId}_{sampleIndex}`, batched at 500 writes. Uploaded: session statistics (including the user-entered session name), health status, model-health scalars, and arrhythmia events (time, type, confidence). **Not uploaded:** ECG readings, consent records, audit log. All calls are bounded by timeouts.

---

## 5. Navigation Architecture

Navigation uses Jetpack Navigation Compose with type-safe routes (`navigation/Screen.kt`):

```
NavigationHost
    ├── ConsentScreen (shown if no valid consent)
    └── MainAppNavigation (shown if consent valid)
            ├── LiveMonitor   → LiveMonitorScreen
            ├── History       → HistoryScreen
            ├── Settings      → SettingsScreen
            └── SessionDetail(sessionId) → SessionDetailScreen
```

The consent gate in `NavigationHost` is the single enforcement point for FR-CON-02 (user must accept consent before accessing recording functionality).

---

## 6. Data Flow — Recording Session

```
1. User taps "Start Recording" (LiveMonitorScreen); allowed only while connected
2. LiveMonitorViewModel.startRecording() creates the session in memory
3. BleManager streams packets via ecgPacketFlow and beats via beatWindowFlow
4. ViewModel buffers samples and abnormal events in memory; each beat is quality-scored,
   classified, and recorded in the ClassificationMonitor
5. User taps "Stop Recording", or the BLE connection drops (recording stops automatically)
6. ViewModel calculates heart-rate statistics and takes the monitoring snapshot
7. EcgRepository saves the session, all readings and events in one write
8. SyncManager uploads the session metadata and events in the background
9. ConsentManager.log(SESSION_RECORDED); on upload, SESSION_UPLOADED_CLOUD
10. Navigate to SessionDetailScreen
```

Nothing is written to the database before step 7, so a crash during recording loses the recording (RMF-001 RISK-006, OA-3).

---

## 7. Data Flow — Consent and Deletion

```
First launch:
1. NavigationHost checks ConsentManager.hasValidConsent()
2. If false → ConsentScreen shown
3. User accepts → ConsentManager.recordConsent(granted=true)
4. consent_records row written with timestamp + version
5. audit_log row written: CONSENT_GRANTED
6. NavigationHost shows MainAppNavigation

Delete All / Withdraw consent:
1. User taps "Delete All" or "Withdraw Consent" in SettingsScreen
2. Confirmation dialog shown
3. SyncManager.deleteEverywhere():
   a. ConsentManager.deleteAllUserData() clears ecg_sessions, ecg_readings,
      arrhythmia_events, audit_log; consent_records retained (legal requirement);
      final audit_log entry: ALL_DATA_DELETED
   b. FirebaseRepository.deleteAllCloudData() deletes the user's Firestore documents
4. Withdrawal additionally records consent granted=false (CONSENT_WITHDRAWN)

Single session delete (History): local copy only; the cloud copy remains (OA-2).
```

---

## 8. Security Architecture

| Concern | Current Mitigation | Future Enhancement |
|---------|-------------------|-------------------|
| Data at rest | Android app sandbox | SQLCipher encryption |
| Data in transit | TLS (Firestore SDK); raw waveforms never leave the device | — |
| Cloud access control | Anonymous auth; per-user document path | Review Firestore security rules (not evidenced) |
| Access control | Android OS sandboxing | Biometric authentication |
| Consent audit trail | `consent_records` table (never deleted) | Immutable append-only log |
| Backup | `android:allowBackup="false"`, `android:fullBackupContent="false"` | Exclude device-to-device transfer in `data_extraction_rules.xml` (OA-5) |

---

## 9. Document History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2026-01-01 | [Author] | Initial draft |
| 1.1 | 2026-10-16 | Oliver Moberg | Educational status stated; removed device-class claims. System overview updated (ESP32-S3 + AD8232, 250 Hz, two BLE characteristics, on-phone inference, Firestore). Added §2 firmware architecture and signal chain, ML layer (§4.3), SyncManager, cloud layer (§4.7). Database v5. BLE flows, navigation and recording/deletion data flows corrected to the implementation. Security table updated for cloud sync and backup |
