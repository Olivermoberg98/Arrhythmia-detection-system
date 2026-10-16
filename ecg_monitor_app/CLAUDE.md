# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

`ecg_monitor_app` (applicationId `com.ecgmonitor.app`) is the Android companion app for an ECG arrhythmia-monitoring device (ESP32-S3 + AD8232; ECG only, no PPG). It pairs over BLE, streams ECG samples and per-heartbeat "beat window" packets, runs arrhythmia classification with TensorFlow Lite on the phone (inference lives here permanently, not on the ESP32), stores sessions locally in Room, and optionally syncs session metadata (not raw waveforms) to Firebase Firestore under anonymous auth. It's an educational project, not a certified medical device — keep that framing in UI copy.

This module lives inside the larger `Arrhythmia-detection-system` monorepo, alongside `arrhythmia_system_controller/` (ESP32 firmware) and `ml_pipeline/` (Python model training) — this CLAUDE.md only covers `ecg_monitor_app/`. Cross-project contracts (sample rate, beat window, RR features, classes, ADC→mV conversion, BLE GATT layout) are in the root `CLAUDE.md` — read it before touching `ArrhythmiaClassifier` or `BleManager` parsing.

- Kotlin 2.0.20, AGP 8.9.1, Gradle 8.13, KSP 2.0.20-1.0.25
- compileSdk 36, targetSdk 34, minSdk 26
- Single Gradle module (`:app`), no product flavors
- UI is 100% Jetpack Compose (Material 3) — no XML layouts

## Build & run commands

Run from `ecg_monitor_app/`:

```
.\gradlew.bat assembleDebug          # debug build
.\gradlew.bat assembleRelease        # release build (minify disabled, uses proguard-rules.pro)
.\gradlew.bat installDebug           # install debug build on connected device/emulator
.\gradlew.bat test                   # JVM unit tests
.\gradlew.bat connectedAndroidTest   # instrumented tests, incl. Room migration tests (needs device/emulator)
.\gradlew.bat lint                   # standard Android Lint (no custom ruleset configured)
.\gradlew.bat detekt                 # Kotlin static analysis (default ruleset, baselined against pre-existing findings)
```

Building requires `app/google-services.json` locally (gitignored, not in the repo) — Firebase features won't build without it.

## Architecture

MVVM, two layers deep (no domain/UseCase layer — ViewModels call repositories directly):

- **Screens (Compose)** — `app/src/main/java/com/ecgmonitor/app/ui/screens/` (`LiveMonitorScreen`, `HistoryScreen`, `SessionDetailScreen`, `SettingsScreen`, `ConsentScreen`)
- **ViewModels** — `app/src/main/java/com/ecgmonitor/app/ui/viewmodels/`, all extend `AndroidViewModel`, expose state via `StateFlow`
- **Repositories** — `app/src/main/java/com/ecgmonitor/app/data/repository/EcgRepository.kt`, `data/consent/ConsentManager.kt`
- **Room DB** — `app/src/main/java/com/ecgmonitor/app/data/database/` (`EcgDatabase.kt` is at version 5 with explicit `MIGRATION_1_2`..`MIGRATION_4_5` — bump the version and add a migration, don't rely on destructive fallback in release)
- **BLE** — `app/src/main/java/com/ecgmonitor/app/data/ble/BleManager.kt`, built on **Kable** (`com.juul.kable`)
- **ML inference** — `app/src/main/java/com/ecgmonitor/app/data/ml/ArrhythmiaClassifier.kt`, loads `assets/arrhythmia_model.tflite` + `assets/model_metadata.json`; converts firmware ADC counts to mV (`ADC_COUNTS_TO_MV`) before normalising; picks the class with `decide()` = `argmax(scores / thresholds)`, the rule the thresholds were tuned for. `isValid` (`score >= threshold`) is always true while the thresholds sum to ≤ 1, so it rarely gates anything
- **Beat timing** — the firmware emits each beat at the *next* R-peak (it needs the real `post_rr`), so beat packets arrive one RR interval late. Anything that places a beat in time must use `BeatWindow.timestampMs` (the R-peak), not arrival time — see `LiveMonitorViewModel.eventSampleIndex`
- **Model health** — `data/ml/SignalQualityAnalyzer.kt` (per-beat signal quality) and `data/ml/ClassificationMonitor.kt` (confidence, drift, beat-rate plausibility; scalar summaries only, safe to sync)
- **Demo data / export** — `data/mitbih/MitBihDataLoader.kt` (bundled MIT-BIH records), `utils/ExportManager.kt`
- **Firebase sync** — `app/src/main/java/com/ecgmonitor/app/data/firebase/FirebaseRepository.kt`, `SyncManager.kt`
- **Navigation** — `app/src/main/java/com/ecgmonitor/app/navigation/NavigationHost.kt` gates every screen behind `ConsentManager.hasValidConsent()`

**DI is manual, not a framework.** `EcgMonitorApp` (`Application` subclass) constructs app-scoped singletons (`bleManager`, `syncManager`, `consentManager`, `firebaseRepository`) as public `lateinit var`s in `onCreate()`. ViewModels reach them via `(application as EcgMonitorApp).bleManager`, and construct their own repository/classifier instances directly in field initializers.

## Module ownership / don't-touch boundaries

- **`app/src/main/assets/arrhythmia_model.tflite` and `model_metadata.json`** — trained by the separate `ml_pipeline/` (Python) and checked in as generated artifacts. Don't hand-edit; regenerate via that pipeline and copy both files together (the metadata's normalisation stats must match the model).
- **`app/google-services.json`** — gitignored, supplied locally per environment. Never commit it.
- **`docs/regulatory/`** (monorepo root, one level up) — an IEC 62304/GDPR/MDR-style documentation set (`SAD-001` architecture, `SRS-001` requirements, `RMF-001` risk file, etc.) that describes this app's consent flow, data handling, and architecture. Ask before editing these; if you change consent, data retention, BLE protocol, or classifier behavior, flag that these docs may need a matching update.
- **`mitbih_100.json` / `mitbih_203.json`** (`app/src/main/assets/mitbih/`) — fixed reference/demo recordings loaded by `MitBihDataLoader.kt`; don't regenerate casually.
- **BLE is Kable only.** The unused RxAndroidBle/RxJava dependencies and the `viewBinding` flag were removed; don't reintroduce them or XML layouts without discussing first.

## Conventions

- **UI is Compose-only.** No XML layouts exist; keep new screens in Compose. The one exception is `ui/components/EcgChartView.kt` / `EcgChartComposable.kt`, which wrap the classic View-based MPAndroidChart via `AndroidView` — follow that existing pattern if a chart needs MPAndroidChart specifically.
- **No domain/UseCase layer.** Business logic lives in repositories, called directly from ViewModels — don't introduce a new architectural layer without discussion.
- **No DI framework** (no Hilt/Koin/Dagger). New app-scoped dependencies go through `EcgMonitorApp`'s manual singleton wiring, matching the existing `bleManager`/`syncManager`/`consentManager`/`firebaseRepository` pattern.
- **Streaming data uses `MutableSharedFlow` with a bounded buffer and `DROP_OLDEST`** (see `BleManager.kt`, 64-slot buffer) for packet-loss tolerance — follow this pattern for new streaming sources rather than an unbounded channel.
- **Room schemas are exported to `app/schemas` and committed** (`room.schemaLocation` KSP arg). Bumping `@Database(version = ...)` must produce a new schema JSON *and* a `MigrationTestHelper` case in `EcgDatabaseMigrationTest` — the exported JSON is what makes the migration testable at all.
- **Firebase sync uploads metadata only** (session stats + arrhythmia events, batched 500/batch) — raw waveform samples intentionally stay local-only in Room to stay free-tier compatible. Preserve this split when touching sync code.
- **Commit message style**: `Update (Scope): Description`, e.g. `Update (App): Fix the data base storage via Firebase` — scope is typically `App`, `ESP`, or the changed area.
- **Branches**: short-lived `feat/*` / `fix/*` branches merged to `main` via PR.
- **Test coverage is limited to the ML monitoring layer** (`SignalQualityAnalyzerTest`, `ClassificationMonitorTest`) plus `EcgDatabaseMigrationTest` (v3→v5 only: schemas 1–2 were never exported, so `MIGRATION_1_2`/`MIGRATION_2_3` are untested) — don't assume repositories, ViewModels, BLE parsing, or the classifier itself are under test (see TODO below).
- **No ktlint/.editorconfig** — match the surrounding file's style manually; there's no automated formatter to defer to. **detekt** is configured (default ruleset, `app/detekt-baseline.xml` suppresses pre-existing findings) — new code should pass `.\gradlew.bat detekt` cleanly; don't add to the baseline to silence a new finding, fix it or ask.

## My working preferences

- Keep responses concise, no unnecessary explanation.
- Prefer minimal diffs — don't refactor or reformat code beyond what's asked.
- Never create new files unless explicitly requested.
- Ask before adding new dependencies.

## TODO

- **Open actions from the regulatory review** (full list and status: `docs/regulatory/RMF-001_RiskManagementFile.md` §6). When you fix one, remove its line here and update its OA status in RMF-001:
  - **OA-1** — `ConsentScreen` says data "is not transmitted to any server, cloud service", but Firestore sync runs after consent. Fix the text and bump `CONSENT_VERSION` so users are re-prompted. Most serious one (GDPR).
  - **OA-2** — `HistoryViewModel.deleteSession` deletes locally only; the cloud copy stays. `SESSION_DELETED` and `SESSION_EXPORTED_PDF/CSV` are never logged.
  - **OA-3** — recordings are buffered in memory and written only on Stop, so a crash loses everything. No incomplete flag, no 10 s minimum (FR-REC-03..06).
  - **OA-4** — the "not a medical device" notice is only on `ConsentScreen`; add it to Live Monitor and Session Detail (FR-ARR-10).
  - **OA-5** — `res/xml/data_extraction_rules.xml` is the empty template; with targetSdk 34 device-to-device transfer probably still copies the database (NFR-08).
- **Add real test coverage, prioritizing `BleManager.parseStreamPacket`** (and `parseBeatPacket`) before refactoring. `parseStreamPacket` is the one flagged complexity issue worth taking seriously (`CyclomaticComplexMethod` + `LongMethod` in `app/detekt-baseline.xml`), and BLE parsing + the classifier (incl. the ADC→mV conversion) currently have zero tests — don't simplify either without tests to catch regressions first.

## Open questions

- Whether `docs/regulatory/` is meant to be kept strictly in sync with code changes (as IEC 62304-style docs normally would be) or is best-effort. Until decided, flag affected docs rather than editing them.
