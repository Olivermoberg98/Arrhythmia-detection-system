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

Hardware: ESP32-S3-N16R8 camera board + AD8232 single-lead ECG module + disposable electrodes, USB-powered. **ECG only — there is no PPG/MAX30102 and none is planned.** Training data: MIT-BIH Arrhythmia Database (PhysioNet, Goldberger et al. 2000), via the WFDB library.

## Where inference runs (decided)

Inference runs **on the Android app, using the phone's compute — permanently, not on the ESP32** (commit `c3f9e59`). The firmware's job ends at filtered samples + R-peak-aligned beat windows + RR features, which it ships over BLE; the app classifies. Don't reintroduce edge inference.

The on-ESP32 path has been removed (TFLite arena flag, C-header generation). The live artifacts are `arrhythmia_model.tflite` (INT8 weights, float32 I/O) + `model_metadata.json`, copied together into the app's `assets/`. INT8 was originally chosen for TFLite Micro; the phone doesn't need it, but the shipped thresholds were tuned against the INT8 build, so switching to the `_f32` model is a classifier behaviour change — ask first.

There is no root `README.md`; this file is the project's top-level documentation.

## Cross-project contracts

These constants are duplicated across C++, Python, JSON and Kotlin. Changing one without the others silently corrupts classification — there is no runtime check that they agree.

| Contract | Value | Defined in |
|---|---|---|
| Sample rate | 250 Hz | `config.h` `SamplingCfg::ECG_SAMPLE_RATE_HZ`, `model_metadata.json`, `preprocess.py` |
| Beat window | 128 samples = 96 pre + 32 post R-peak | `config.h` `SamplingCfg::BEAT_*`, `model_metadata.json` `beat_length` |
| RR features | 3: `[pre_rr_norm, post_rr_norm, ratio_rr]`; norm = mean of the last 8 RR intervals up to `pre_rr`; `post_rr` is real, so each beat is emitted at the **next** R-peak; first/last beat dropped | `config.h` `MLCfg::RR_MEAN_WINDOW`, `SignalProcessor.cpp`, `preprocess.py::compute_rr_features`, `validate.py` |
| R-peak position | Snapped to max \|filtered\| within ±15 samples | `config.h` `FilterCfg::PT_RPEAK_SEARCH_HALF`, `preprocess.py::snap_rpeak` |
| Classes | 3: 0=Normal, 1=Supraventricular, 2=Ventricular | `config.h` `MLCfg::Classification`, `model_metadata.json` |
| Filters | HP 0.5 Hz, LP 40 Hz, notch 50 Hz (2nd-order biquads @ 250 Hz, **causal**) — training applies the identical coefficients with `lfilter` after resampling to 250 Hz | `config.h` `FilterCfg`, copied into `preprocess.py` (`FW_*`) and `tools/data_capture/validate.py` |
| Decision rule | `class = argmax(probs / thresholds)` — what `train_model.py` tunes the thresholds for | `train_model.py::optimize_thresholds`, `ArrhythmiaClassifier.decide` |
| ECG units | Firmware ships ADC counts (`raw - 2048`); the scaler is in **millivolts** — convert before normalising | `ArrhythmiaClassifier.ADC_COUNTS_TO_MV` = (3100/4096)/1100, board-gain dependent |
| Normalisation | ECG mean/std + per-feature RR mean/std | `model_metadata.json` (app) — training stats only, no leakage |
| BLE GATT | service + stream/beat/config characteristic UUIDs, `DataPacket` (10 samples), `BeatPacket` (275 B) | `config.h` `BLECfg` |

**When you change any of these, grep all four places**: `arrhythmia_system_controller/src/config.h`, `ml_pipeline/scripts/preprocess.py`, `ml_pipeline/src/ml/model_metadata.json`, and the app's classifier + BLE parser. Say so explicitly in the change description.

`arrhythmia_system_controller/tools/data_capture/validate.py` is a fifth copy of the beat-window, RR-feature and ADC→mV constants (it mirrors `ArrhythmiaClassifier.classify` offline, and checks `model_metadata.json`'s shape fields on load). Keep it in sync too.

**Training reproduces the firmware, not the other way round.** `preprocess.py` resamples raw MIT-BIH to 250 Hz, runs the firmware's causal biquads, snaps annotated R-peaks like the detector, and computes RR features with the firmware's running mean (rationale in its "Firmware parity" docstring section). Verified by compiling the real `SignalProcessor.cpp` on the host and feeding it MIT-BIH: where the emulated ADC doesn't clip, beat windows match training to ~1 µV (int16 truncation) and R-peaks land on the same sample for >99% of beats.

Known residuals: the AD8232 board's own analogue filtering isn't modelled, and the ADC saturates at ±1.41 mV electrode-referred (12-bit, gain 1100) — large QRS complexes clip on the device but not in training.

## Signal chain / lead configuration

The model is trained on MIT-BIH **channel 0**, which is **modified limb lead II (MLII)** in 46 of 48 records (exceptions: 102 and 104 use V5; record 114's signals are reversed). `preprocess.py::process_record` hardcodes `channel=0`.

This makes the lead a real input contract, not a detail: **hardware electrode placement must reproduce MLII**, or the classifier sees morphology it was never trained on. The AD8232's differential is `LA − RA`, so the LA electrode goes at the left-lower-torso (LL) position to yield lead II. Any change to lead choice in preprocessing invalidates this electrode placement and vice versa. There is no separate hardware setup guide — this section is it.

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
python convert_model.py --model models/trained/model_<ts>.keras ...   # → INT8 + f32 TFLite in src/ml/
python extract_constants.py --meta data/processed/metadata.pkl --ecg_scaler data/processed/ecg_scaler.pkl \
    --rr_scaler data/processed/rr_scaler.pkl --results models/trained/results_<ts>.pkl   # → src/ml/model_metadata.json
```
Then copy `src/ml/arrhythmia_model.tflite` + `model_metadata.json` into `ecg_monitor_app/app/src/main/assets/`. Set `PYTHONIOENCODING=utf-8` if output is redirected (the scripts print box-drawing characters).

**Scalers and model must come from the same run.** `data/processed/*_scaler.pkl` is overwritten by every `preprocess.py` run, so re-running `extract_constants.py` after a new preprocess without retraining would ship wrong normalisation. The shipped model is `model_residual_20261014_161509` (DS2 test macro-F1 0.792 training-path; 0.756 end-to-end through the firmware code with ADC clipping). After retraining, also update `ArrhythmiaClassifier.EXPECTED_DRIFT_SCORE` (mean |z| of `X_train.npy`).

**Offline check of a model on captured ECG**: `python tools/data_capture/validate.py --data <csv> --model <tflite> --metadata <json>` (from `arrhythmia_system_controller/`). Note `capture.py` currently receives nothing: the serial CSV `printf` in `main.cpp` is commented out.

`ml_pipeline/README.md` documents a `scripts/03_preprocess.py`..`05_convert_model.py` numbering and a `src/` module layout (`data_loader.py`, `models.py`, …) **that does not exist**, and describes ESP32 deployment. Use the commands above.

**App** (`ecg_monitor_app/`): see `ecg_monitor_app/CLAUDE.md`.

## Conventions

- **Commit messages**: `Update (Scope): Description`, scope is `App`, `ESP`, `ML`, `docs`, `gitignore`, or a combination (`Update (ML, ESP and App): ...`). `Feature (Scope): ...` and `New (Scope): ...` also appear.
- **Branches**: short-lived `feat/*` and `fix/*` branches merged to `main` via PR. This is the expected workflow going forward; early history was committed directly to `main`.
- **ML design decisions are documented in long module docstrings**, not in separate design docs. `preprocess.py`'s header explains the 3-class (N/S/V) choice, the asymmetric 96/32 window, and the RR features, each with literature citations. `train_model.py` cites de Chazal 2004, Hannun 2019, Yıldırım 2018, Müller 2019. **Follow this pattern** — when you change a modelling decision, update the docstring rationale in the same edit.
- **Patient-level (inter-patient) splits following de Chazal et al. (2004) DS1/DS2.** Never split beats randomly across patients; it inflates metrics and is the single easiest way to silently break this pipeline's validity.
- **Normalisation stats come from the training split only.** No leakage.
- **Filter coefficients are precomputed constants**, not runtime-designed — they're written out as direct-form II biquad arrays so C++ and Python match bit-for-bit-ish.

## Don't touch without asking

- **`docs/regulatory/`** — `SAD-001` architecture, `SRS-001` requirements, `RMF-001` risk file, `DPIA-001`, `SDP-001`, `SVVP-001`, `TM-001` traceability matrix. Ask before editing. If you change consent, data retention, the BLE protocol, classifier behavior, or the signal chain, **flag that these may need a matching update** rather than editing them silently.
- **`ml_pipeline/models/trained/*`** — training run outputs keyed by timestamp (`model_residual_<ts>.keras`, `results_*.pkl`, confusion/history PNGs). Gitignored; don't delete or regenerate casually — `results_*.pkl` holds each run's thresholds, which `extract_constants.py` writes into `model_metadata.json`.
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

Resolved: PPG/MAX30102 is dropped; inference stays on the phone; the old root README and the on-ESP32 inference code were removed; `validate.py` was rebuilt for the beat-window pipeline; `local.properties` is untracked.

- **`ml_pipeline/README.md` is stale** (wrong script names, ESP32 deployment). Use the commands in this file.
- **ADC headroom**: ±1.41 mV electrode-referred full scale is tight for a 1–3 mV QRS; clipping distorts beats and shifts R-peaks. Board-gain dependent, unverified on hardware.
- **Privacy framing**: processing is on-device (ESP32 + phone), but the app syncs session *metadata* to Firebase. Don't describe the system as "no cloud"; raw waveforms stay local.
- **No CI.** No GitHub Actions or other automation; tests run locally only.
- **Firmware and ML pipeline have no automated tests.** `pio test` has nothing to run (`test/` contains only a README). The signal chain (filters, Pan-Tompkins, beat extraction) is the highest-value untested code in the repo, followed by the cross-project contract table above — nothing checks that the four copies agree.
