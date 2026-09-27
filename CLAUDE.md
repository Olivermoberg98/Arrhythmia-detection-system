# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

`Arrhythmia-detection-system` is a monorepo for an educational (non-medical-device) single-lead ECG arrhythmia monitor. An ESP32-S3 reads an AD8232 ECG front end, filters the signal, detects R-peaks, and streams both a continuous waveform and per-beat windows over BLE to an Android app, which runs a TensorFlow Lite classifier and stores/syncs sessions. Models are trained offline in Python on the MIT-BIH Arrhythmia Database.

**Not a certified medical device.** No clinical use. Keep this framing in docs and UI copy.

### Sub-projects

| Path | What it is | Toolchain |
|---|---|---|
| `arrhythmia_system_controller/` | ESP32-S3 firmware: ADC sampling, filtering, R-peak detection, BLE server | PlatformIO / Arduino / C++ |
| `ecg_monitor_app/` | Android companion app: BLE client, TFLite inference, Room storage, Firebase sync | Kotlin / Compose / Gradle |
| `ml_pipeline/` | Model training: MIT-BIH download, preprocessing, training, TFLite conversion | Python 3.10 / TF 2.15 |
| `docs/regulatory/` | IEC 62304 / GDPR / MDR-style documentation set | Markdown + CSV |

**`ecg_monitor_app/` has its own CLAUDE.md** with app-level architecture, conventions and build commands. Read it before touching Android code; don't duplicate its content here.

## Where inference actually runs

Inference runs **on the Android app, not on the ESP32** (see commit `c3f9e59 Update (App and ESP): Move the inference from ESP side to the App`). The firmware's job ends at filtered samples + R-peak-aligned beat windows + RR features, which it ships over BLE; the app classifies.

Consequences worth knowing before you act on stale signals:
- `arrhythmia_system_controller/src/` has **no `ml/` directory and no TFLite code**. The `-DTFLITE_TENSOR_ARENA_SIZE=60000` build flag in `platformio.ini` is vestigial.
- `ml_pipeline/extract_constants.py` emits `model_data.h` / `model_config.h` (C arrays + constexpr constants) for an on-ESP32 path that is no longer wired up. The live artifacts are the `.tflite` + `model_metadata.json` consumed by the app.
- The root `README.md` still describes ESP32-side inference, a MAX30102 PPG sensor, and a "Flutter/React Native" app. **None of those match the code.** Treat the README as aspirational; prefer this file and the code.

## Cross-project contracts

These constants are duplicated across three languages. Changing one without the others silently corrupts classification — there is no runtime check that they agree.

| Contract | Value | Defined in |
|---|---|---|
| Sample rate | 250 Hz | `config.h` `SamplingCfg::ECG_SAMPLE_RATE_HZ`, `model_metadata.json`, `preprocess.py` |
| Beat window | 128 samples = 96 pre + 32 post R-peak | `config.h` `SamplingCfg::BEAT_*`, `model_metadata.json` `beat_length` |
| RR features | 3: `[pre_rr_norm, post_rr_norm, ratio_rr]` | `config.h` `MLCfg::N_RR_FEATURES`, `preprocess.py` |
| Classes | 3: 0=Normal, 1=Supraventricular, 2=Ventricular | `config.h` `MLCfg::Classification`, `model_metadata.json` |
| Filters | HP 0.5 Hz, LP 40 Hz, notch 50 Hz (2nd-order biquads @ 250 Hz) | `config.h` `FilterCfg`, mirrored in `preprocess.py` and `tools/data_capture/validate.py` |
| ECG units | Firmware ships ADC counts (`raw - 2048`); the scaler is in **millivolts** — convert before normalising | `ArrhythmiaClassifier.ADC_COUNTS_TO_MV` = (3100/4096)/1100, board-gain dependent |
| Normalisation | ECG mean/std + per-feature RR mean/std | `model_metadata.json` (app) — training stats only, no leakage |
| BLE GATT | service + stream/beat/config characteristic UUIDs, `DataPacket` (10 samples), `BeatPacket` (275 B) | `config.h` `BLECfg` |

**When you change any of these, grep all four places**: `arrhythmia_system_controller/src/config.h`, `ml_pipeline/scripts/preprocess.py`, `ml_pipeline/src/ml/model_metadata.json`, and the app's classifier + BLE parser. Say so explicitly in the change description.

`arrhythmia_system_controller/tools/data_capture/validate.py` is **already out of sync** (`ML_WINDOW_SAMPLES = 1250`, "5 seconds", plus PPG columns in `capture.py`'s expected header) — it predates the 128-sample beat window. Don't trust it as a reference for current constants.

## Signal chain / lead configuration

The model is trained on MIT-BIH **channel 0**, which is **modified limb lead II (MLII)** in 46 of 48 records (exceptions: 102 and 104 use V5; record 114's signals are reversed). `preprocess.py::process_record` hardcodes `channel=0`.

This makes the lead a real input contract, not a detail: **hardware electrode placement must reproduce MLII**, or the classifier sees morphology it was never trained on. The AD8232's differential is `LA − RA`, so the LA electrode goes at the left-lower-torso (LL) position to yield lead II. Any change to lead choice in preprocessing invalidates the hardware setup guide and vice versa.

Firmware pin assignment (ESP32-S3 constrained: ADC1 is GPIO1–10, GPIO4–10 taken by the camera DVP bus, GPIO26–37 by flash + octal PSRAM):
- `GPIO1` (ADC1_CH0) — AD8232 analog OUTPUT
- `GPIO42` / `GPIO41` — lead-off detect LO+ / LO−

## Build & run commands

**Firmware** (`arrhythmia_system_controller/`) — default env is `esp32s3cam` (ESP32-S3-N16R8 camera board, CH343 bridge, flash size and partitions overridden to 16 MB because the board def is N8R8):

```
pio run                          # build default env
pio run -t upload                # flash — hold BOOT for the whole upload, auto-download-mode is unreliable
pio device monitor -b 921600     # serial monitor (esp32_exception_decoder filter)
pio run -e esp32dev_debug        # debug build, CORE_DEBUG_LEVEL=4
```
No serial ports are hardcoded in `platformio.ini` — pass `--upload-port` / `--port` yourself.

**ML pipeline** (`ml_pipeline/`, venv at `.venv`):

```
.venv\Scripts\Activate.ps1
python scripts/01_download_data.py      # MIT-BIH from PhysioNet (~100 MB)
python scripts/02_explore_data.py
python scripts/analyze_mitbih.py        # per-record AAMI class tallies + DS1/DS2 split comparison
python scripts/preprocess.py
python scripts/train_model.py
python convert_model.py --model models/trained/model_<ts>.keras ...   # → INT8 TFLite
python extract_constants.py --tflite ... --out_cfg src/ml/model_config.h
```
`ml_pipeline/README.md` documents a `scripts/03_preprocess.py`..`05_convert_model.py` numbering and a `src/` module layout (`data_loader.py`, `models.py`, …) **that does not exist** — the real entry points are the unnumbered `scripts/preprocess.py`, `scripts/train_model.py`, and the top-level `convert_model.py` / `extract_constants.py`. Use the commands above.

**App** (`ecg_monitor_app/`): see `ecg_monitor_app/CLAUDE.md`.

## Conventions

- **Commit messages**: `Update (Scope): Description`, scope is `App`, `ESP`, `ML`, `docs`, `gitignore`, or a combination (`Update (ML, ESP and App): ...`). `Feature (Scope): ...` and `New (Scope): ...` also appear.
- **Branches**: short-lived `feat/*` and `fix/*` branches merged to `main` via PR (only one PR in history so far; earlier work was committed directly to `main`).
- **ML design decisions are documented in long module docstrings**, not in separate design docs. `preprocess.py`'s header explains the 3-class (N/S/V) choice, the asymmetric 96/32 window, and the RR features, each with literature citations. `train_model.py` cites de Chazal 2004, Hannun 2019, Yıldırım 2018, Müller 2019. **Follow this pattern** — when you change a modelling decision, update the docstring rationale in the same edit.
- **Patient-level (inter-patient) splits following de Chazal et al. (2004) DS1/DS2.** Never split beats randomly across patients; it inflates metrics and is the single easiest way to silently break this pipeline's validity.
- **Normalisation stats come from the training split only.** No leakage.
- **Filter coefficients are precomputed constants**, not runtime-designed — they're written out as direct-form II biquad arrays so C++ and Python match bit-for-bit-ish.

## Don't touch without asking

- **`docs/regulatory/`** — `SAD-001` architecture, `SRS-001` requirements, `RMF-001` risk file, `DPIA-001`, `SDP-001`, `SVVP-001`, `TM-001` traceability matrix. Ask before editing. If you change consent, data retention, the BLE protocol, classifier behavior, or the signal chain, **flag that these may need a matching update** rather than editing them silently.
- **`ml_pipeline/models/trained/*`** — training run outputs keyed by timestamp (`model_residual_<ts>.keras`, `results_*.pkl`, confusion/history PNGs). Gitignored; don't delete or regenerate casually, the `results_*.pkl` files feed `extract_constants.py`.
- **`ecg_monitor_app/app/google-services.json`** — gitignored, per-environment. Never commit.
- **Generated model artifacts** (`ml_pipeline/src/ml/arrhythmia_model.tflite`, `model_metadata.json`, and their copies in the app's `assets/`) — regenerate via the pipeline, never hand-edit.
- **`ml_pipeline/data/`** — all subdirs gitignored (`raw/`, `processed/`, `personal/`). `personal/` is for the user's own ECG recordings; treat as sensitive.

## My working preferences

- Keep responses concise, no unnecessary explanation.
- Prefer minimal diffs — don't refactor or reformat beyond what's asked.
- Never create new files unless explicitly requested.
- Ask before adding new dependencies.
- Keep inline code comments to 1–2 lines; full rationale goes in chat, not the file. (Exception: the ML pipeline's established long-docstring convention above.)

## Known stale / open questions

- **Root `README.md` is substantially out of date** — describes ESP32-side inference, a MAX30102 PPG sensor (no firmware driver exists), a Flutter/React Native app, and a week-by-week roadmap that no longer tracks reality. Worth a rewrite; ask before doing it as a side effect of another task.
- **PPG / MAX30102 is unimplemented.** Referenced in the README, `capture.py`'s expected CSV header, and the hardware BOM, but there is no sensor driver, no BLE field, and no app support. Unclear whether it's planned or dropped.
- **The on-ESP32 inference path** (`extract_constants.py` → `model_data.h`/`model_config.h`, `TFLITE_TENSOR_ARENA_SIZE`) is dead code relative to the current architecture — unclear whether it's kept for a planned move back to the edge or is simply orphaned. The README's "privacy-first, no cloud" claim is also in tension with the app's Firebase sync (metadata only, but still off-device).
- **No CI.** No GitHub Actions or other automation; tests run locally only, and the firmware and ML pipeline have effectively no test coverage (`arrhythmia_system_controller/test/` contains only a README).
- **Firmware has no automated tests.** `pio test` has nothing to run. The signal chain (filters, Pan-Tompkins, beat extraction) is the highest-value untested code in the repo.
