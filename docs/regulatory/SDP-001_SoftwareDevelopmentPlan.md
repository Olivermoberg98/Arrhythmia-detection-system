# Software Development Plan
## ECG Arrhythmia Monitor — Android Application, Device Firmware and ML Pipeline
### IEC 62304:2015 §5.1

**Document ID:** SDP-001  
**Version:** 1.2  
**Date:** 2026-10-21  
**Software Safety Class:** Class B — applied as a design discipline, not a claimed classification  
**Status:** DRAFT  

> **Regulatory status:** Educational project. This plan follows the structure of IEC 62304 as a design discipline. The system is **not a certified medical device** and has not been assessed by a Notified Body.

---

## 1. Development Environment

The repository is a monorepo with three software items:

| Item | Path | Toolchain |
|------|------|-----------|
| Android application | `ecg_monitor_app/` | Kotlin / Jetpack Compose / Gradle |
| Device firmware | `arrhythmia_system_controller/` | PlatformIO / Arduino framework / C++ |
| ML pipeline (offline) | `ml_pipeline/` | Python 3.10 / TensorFlow 2.15 |

### 1.1 Android Application

| Item | Choice | Rationale |
|------|--------|-----------|
| IDE | Android Studio Narwhal 2025.1.1 | Official Android IDE, current stable release |
| Language | Kotlin 2.0.20 | Type-safe, null-safe, official Android language |
| Min SDK | API 26 (Android 8.0) | Covers >95% of active Android devices |
| Target SDK | API 34 (Android 14); compileSdk 36 | Current target |
| Build System | Gradle 8.13 with AGP 8.9.1, KSP 2.0.20-1.0.25 | Standard Android build toolchain |
| UI Framework | Jetpack Compose (Material 3); no XML layouts | Declarative, testable UI |
| Architecture | MVVM + Repository, no domain layer, manual DI | Separation of concerns, testable ViewModels |

### 1.2 Device Firmware

| Item | Choice |
|------|--------|
| Target | ESP32-S3-N16R8 camera board (`esp32s3cam` environment; Freenove S3 board definition overridden to 16 MB flash) |
| Framework | Arduino on `espressif32` via PlatformIO |
| BLE stack | NimBLE-Arduino |
| Host tests | `native` environment (Unity), MSYS2 gcc |

### 1.3 ML Pipeline

| Item | Choice |
|------|--------|
| Language | Python 3.10 in `ml_pipeline/.venv` |
| Training | TensorFlow 2.15 (Keras), scikit-learn, SciPy |
| Data | MIT-BIH Arrhythmia Database via `wfdb` 4.1.2; inter-patient DS1/DS2 split (de Chazal 2004) |
| Output | `arrhythmia_model.tflite` (INT8 weights, float32 I/O) + `model_metadata.json`, copied into the app's `assets/` |

---

## 2. Version Control

| Item | Detail |
|------|--------|
| System | Git |
| Remote | GitHub |
| Branching | `main` (stable); short-lived `feat/*`, `fix/*` and `docs/*` branches merged via pull request |
| Commit policy | `Update (Scope): Description`, where Scope is `App`, `ESP`, `ML`, `docs` or a combination (e.g. `Update (ESP, ML and App): ...`); `Feature (Scope)` and `New (Scope)` also used. Commit bodies record the rationale, measured verification results, and any cross-project contract change |
| Tagging | Releases to be tagged as `v1.0.0`, `v1.0.1` etc. following semantic versioning (no releases tagged yet) |
| PR policy | Changes to `main` via pull request. Early history was committed directly to `main` |

---

## 3. Key Dependencies (SOUP)

### 3.1 Android Application

| Library | Version | Purpose |
|---------|---------|---------|
| Jetpack Compose BOM | 2024.09.03 | UI framework |
| Room | 2.6.1 | Local database (SQLite) |
| Kable | 0.30.0 | Bluetooth LE communication |
| Kotlin Coroutines | 1.7.3 | Async operations |
| TensorFlow Lite | 2.14.0 (support 0.4.4) | On-device inference |
| Firebase BOM | 33.7.0 (Auth, Firestore) | Cloud metadata sync |
| Timber | 5.0.1 | Structured logging |
| MPAndroidChart | 3.1.0 | ECG waveform rendering |
| detekt | 1.23.7 | Static analysis |

Android dependency versions are pinned in `libs.versions.toml` and reviewed for security advisories before each release. Dependency injection is manual (no framework).

### 3.2 Firmware and ML Pipeline

| Library | Version | Purpose |
|---------|---------|---------|
| NimBLE-Arduino | ^2.3.7 (range, not pinned) | BLE GATT server |
| espressif32 platform / Arduino-ESP32 core | 6.12.0 (pinned) / 2.0.17 | Hardware abstraction. Pinned because newer platforms ship core 3.x, which removed the timer API `ECGSensor` uses (found by CI, 2026-10-21) |
| TensorFlow | 2.15.0 | Model training and TFLite conversion |
| NumPy / SciPy / scikit-learn | 1.24.3 / 1.11.1 / 1.3.0 | Signal processing, scalers |
| wfdb | 4.1.2 | MIT-BIH access |

Python versions are pinned in `ml_pipeline/requirements.txt` and the PlatformIO platform in `platformio.ini`. The NimBLE version range is not pinned.

---

## 4. Software Architecture Summary

The application follows MVVM with a unidirectional data flow:

```
UI Layer (Compose Screens)
    ↕ StateFlow / Events
ViewModel Layer
    ↕ suspend functions
Repository Layer (EcgRepository, ConsentManager, SyncManager)  +  ML Layer (classifier, quality, monitor)
    ↕
Data Layer (Room DB, BleManager, FirebaseRepository)
```

The firmware filters, detects beats, and sends beat windows; inference runs only in the app. Full architectural detail, including the firmware signal chain, is in SAD-001.

---

## 5. Coding Standards

- Kotlin static analysis enforced via `detekt` (default ruleset; `app/detekt-baseline.xml` holds pre-existing findings only — new code must pass without adding to it). No ktlint or `.editorconfig`; match the surrounding file's style
- No `!!` (force-unwrap) in production code — explicit null handling required
- All database operations in coroutines on `Dispatchers.IO`
- No business logic in Composable functions — ViewModels only
- User-facing strings: target is `strings.xml`; currently all UI text, including the consent text, is hardcoded in Compose
- All health status UI text reviewed against IEC 62366 use error analysis
- Streaming data uses `MutableSharedFlow` with a bounded buffer and `DROP_OLDEST`
- Firmware and training: filter coefficients are precomputed constants (direct-form II biquads), never designed at runtime, so C++ and Python match
- ML: modelling decisions are documented in the module docstrings of `preprocess.py` and `train_model.py`, with literature citations. Splits are patient-level (DS1/DS2) only; normalisation statistics come from the training split only

---

## 6. Risk-Driven Development Priorities

Per ISO 14971, features associated with higher residual risks receive additional review:

| Feature | Risk Ref | Additional Measures |
|---------|----------|---------------------|
| Arrhythmia detection algorithm | RISK-001, RISK-002 | Performance testing against MIT-BIH DS2 end-to-end through the real firmware code; ensemble of 4 runs |
| Health status display | RISK-007 | UI text reviewed against IEC 62366; usability testing |
| BLE disconnection handling | RISK-008 | Integration tests with simulated disconnection |
| Consent and data deletion | RISK-005 | Manual test protocol; audit log verification; cloud deletion checked |
| Firmware signal chain and detector | RISK-009, RISK-010, RISK-012 | Native unit tests; replay of recorded and MIT-BIH data through a host build |
| Cross-project contracts | RISK-014 | Change procedure in §8.1; firmware-vs-training parity check after any change |

---

## 7. Testing Strategy

Full test plans and results are in SVVP-001. Summary:

| Level | Tool | Scope |
|-------|------|-------|
| Unit tests (app) | JUnit 4 | Signal quality, classification monitoring, BLE packet parsing |
| Integration tests (app) | Room in-memory DB | Database operations |
| Instrumented tests (app) | Room `MigrationTestHelper`, Compose Testing | Migrations (v3→v6); screen navigation, consent flow (planned) |
| Unit tests (firmware) | Unity, `pio test -e native` | SignalProcessor: detection, window, fiducial, RR features, reset |
| Offline verification | Host build of `SignalProcessor.cpp`; TFLite evaluation | Firmware-vs-training parity; DS2 performance |
| Manual tests | Test protocol in SVVP-001 | BLE connection, ECG display, export, wearer recordings |

GitHub Actions (`.github/workflows/ci.yml`) runs the firmware native tests and build, the app unit tests, detekt and debug build, and the model-artifact and contract check on every push to `main` and every pull request; a pull request is merged only when it passes (SVVP-001 §5). Instrumented and offline tests still run locally.

Minimum coverage target: **80% for safety-critical classes** (arrhythmia detection, consent management). Not currently met: `ArrhythmiaClassifier`, `ConsentManager` and the ViewModels have no tests. Coverage is not measured.

---

## 8. Configuration Management

- `libs.versions.toml` is the single source of truth for Android dependency versions; `requirements.txt` for the ML pipeline
- `google-services.json` (Firebase config) is excluded from version control via `.gitignore`; distributed to team via secure channel
- Database schema version tracked in `EcgDatabase.kt` (currently 6); migrations required for all schema changes and a new exported schema JSON plus a migration test case for each — `fallbackToDestructiveMigration()` is prohibited in release builds
- Build variants: `debug` (logging enabled, test data allowed) and `release` (target: logging disabled. Currently `Timber.DebugTree` is planted unconditionally, so release builds also log, and minification is disabled)
- **Model artifacts:** `arrhythmia_model.tflite` and `model_metadata.json` are generated by the pipeline and never hand-edited. They are copied into the app's `assets/` together. Scalers and model must come from the same training run (`preprocess.py` overwrites the scalers on every run). Training-run outputs in `ml_pipeline/models/trained/` are kept; their `results_*.pkl` holds the tuned thresholds. The shipped model is `model_ensemble_20261015_212815`
- Firmware has no hardcoded serial ports; the board's environment is fixed in `platformio.ini`

### 8.1 Cross-Project Contracts

Sample rate, beat window, beat fiducial, RR features, classes, filters, decision rule, ECG units, normalisation and the BLE layout are duplicated in `config.h`, `preprocess.py`, `model_metadata.json`, `validate.py` and the app's classifier and BLE parser, with no runtime check that they agree. CI checks only that the app's model and metadata equal the pipeline output and that the metadata's sample rate, beat window and class count match `config.h` (SVVP-001 TC-CI-01). Any change to one must be made in all of them and named in the commit message, followed by the parity check (SVVP-001 TC-PAR-01) and a review of SRS-001 §3.9 and RMF-001 RISK-014.

---

## 9. Problem Resolution

All defects found during development or testing are tracked as GitHub Issues with:
- Severity label (Critical / Major / Minor)
- Reference to affected SRS requirement and risk ID where applicable
- Assigned milestone for resolution
- Linked to the PR that resolves it

To date, defects have been recorded in commit messages rather than as GitHub Issues. Defects found by document review are listed as open actions in RMF-001 §6.

Critical defects (those affecting safety-related requirements marked Must in SRS-001) block release until resolved.

---

## 10. Release Checklist

Before any release build:
- [ ] All Must requirements in SRS-001 verified against SVVP-001
- [ ] Risk Management File reviewed — no new unmitigated risks
- [ ] Database migration tested on upgrade from previous version
- [ ] Consent version reviewed — bump `CONSENT_VERSION` if text changed
- [ ] ProGuard rules verified — no safety-critical classes removed (if minification is enabled)
- [ ] `google-services.json` present for release signing
- [ ] CI green on the release commit (firmware tests and build, app tests, detekt and build, TC-CI-01)
- [ ] Cross-project contracts agree across all five locations (§8.1)
- [ ] App `assets/` model and metadata identical to `ml_pipeline/src/ml/` and from one training run; `ArrhythmiaClassifier.EXPECTED_DRIFT_SCORE` updated after retraining
- [ ] DPIA-001 reviewed for any change to data collected or transmitted

---

## 11. Document History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2026-01-01 | [Author] | Initial draft |
| 1.1 | 2026-10-16 | Oliver Moberg | Educational status stated. Scope extended to firmware and ML pipeline. Environment corrected (targetSdk 34, compileSdk 36, Gradle 8.13). Branching and commit policy match practice. Dependencies updated with exact versions, TFLite and Firebase added, manual DI. Coding standards: detekt instead of ktlint; strings, release logging and minification stated as they are. Added firmware/ML risk priorities, test levels, model-artifact configuration management, cross-project contracts (§8.1), and release-checklist items |
| 1.2 | 2026-10-21 | Oliver Moberg | CI added (§7, §8.1, release checklist). espressif32 platform pinned to 6.12.0 (SOUP §3.2). Database v6; migration tests v3→v6; BLE parsing now unit-tested |
