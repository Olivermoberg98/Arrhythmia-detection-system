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

Hardware: ESP32-S3-N16R8 camera board + AD8232 single-lead ECG module (red "AD8232 Heart Monitor" V502, a SparkFun SEN-12650 clone built on the datasheet's Figure 66 cardiac-monitor circuit: analogue 2-pole HP 0.48 Hz, 2-pole LP 41 Hz, gain 1100) + disposable electrodes, USB-powered. **ECG only — there is no PPG/MAX30102 and none is planned.** Training data: MIT-BIH Arrhythmia Database (PhysioNet, Goldberger et al. 2000), via the WFDB library.

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
| Beat fiducial | QRS slope-energy centroid (Σt·d²/Σd² of the filtered first difference) over ±15 samples, 3 refinements; the window is 96 before / 32 after it. Not the R peak: max-\|deflection\| flipped between R and S on RS-type beats | `config.h` `FilterCfg::PT_RPEAK_SEARCH_HALF` / `PT_FIDUCIAL_ITERATIONS`, `SignalProcessor.cpp`, `preprocess.py::snap_rpeak`, `validate.py::snap_fiducial` |
| Classes | 3: 0=Normal, 1=Supraventricular, 2=Ventricular | `config.h` `MLCfg::Classification`, `model_metadata.json` |
| Filters | Board (analogue): HP 0.48 Hz + LP 41.1 Hz (Q 0.775), both 2-pole, modelled in `preprocess.py::ad8232_analog` at 360 Hz. Firmware: HP 0.5 Hz, LP 40 Hz, notch 50 Hz (2nd-order biquads @ 250 Hz, **causal**) — training applies the identical coefficients with `lfilter` after resampling to 250 Hz | `config.h` `FilterCfg`, copied into `preprocess.py` (`FW_*`) and `tools/data_capture/validate.py` |
| Decision rule | `class = argmax(probs / thresholds)` — what `train_model.py` tunes the thresholds for | `train_model.py::optimize_thresholds`, `ArrhythmiaClassifier.decide` |
| ECG units | Firmware ships ADC counts (`raw - 2048`); the scaler is in **millivolts** — convert before normalising | `ArrhythmiaClassifier.ADC_COUNTS_TO_MV` = (3100/4096)/1100, board-gain dependent |
| Normalisation | ECG mean/std + per-feature RR mean/std | `model_metadata.json` (app) — training stats only, no leakage |
| BLE GATT | service + stream/beat/config characteristic UUIDs, `DataPacket` (10 samples), `BeatPacket` (275 B) | `config.h` `BLECfg` |

**When you change any of these, grep all four places**: `arrhythmia_system_controller/src/config.h`, `ml_pipeline/scripts/preprocess.py`, `ml_pipeline/src/ml/model_metadata.json`, and the app's classifier + BLE parser. Say so explicitly in the change description.

`arrhythmia_system_controller/tools/data_capture/validate.py` is a fifth copy of the beat-window, RR-feature and ADC→mV constants (it mirrors `ArrhythmiaClassifier.classify` offline, and checks `model_metadata.json`'s shape fields on load). Keep it in sync too.

**Training reproduces the firmware, not the other way round.** `preprocess.py` applies the board's analogue filters to raw MIT-BIH, resamples to 250 Hz, runs the firmware's causal biquads, snaps annotated R-peaks like the detector, and computes RR features with the firmware's running mean (rationale in its "Firmware parity" docstring section). Verified by compiling the real `SignalProcessor.cpp` on the host and feeding it MIT-BIH: where the emulated ADC doesn't clip, beat windows match training to ~1 µV (int16 truncation) and R-peaks land on the same sample for >99% of beats.

Known residuals: the board's capacitor values are unmarked (taken from datasheet Figure 66) and component tolerances aren't modelled; the ADC saturates at ±1.41 mV electrode-referred (12-bit, gain 1100) — large QRS complexes clip on the device but not in training.

## Signal chain / lead configuration

The model is trained on MIT-BIH **channel 0**, which is **modified limb lead II (MLII)** in 46 of 48 records (exceptions: 102 and 104 use V5; record 114's signals are reversed). `preprocess.py::process_record` hardcodes `channel=0`.

This makes the lead a real input contract, not a detail: **hardware electrode placement must reproduce MLII**, or the classifier sees morphology it was never trained on. The AD8232's differential is `LA − RA`, so the LA electrode goes at the left-lower-torso (LL) position to yield lead II. Any change to lead choice in preprocessing invalidates this electrode placement and vice versa. There is no separate hardware setup guide — this section is it.

**Verified placement for this kit (2026-10-15).** The electrode cable's colours do **not** follow the IEC convention — mapping found with the lead-off pins (unsnap one snap at a time: LO+ alone = LA, LO− alone = RA, both = RL):

| Snap | Board input | Pad position |
|---|---|---|
| Red | RA (−IN) | under the right collarbone, 2–3 cm from the sternum |
| **Green** | **LA (+IN)** | lower-left ribcage, costal margin at the anterior axillary line |
| **Yellow** | **RL (reference)** | lower-right ribcage, mirror of green |

This gave the only recording that matches training: QRS 1.35 mV p-p, no clipping, 12 µV PR-segment noise, T/R 0.22, upright P/T, 100 % Normal. Moving green out to the left mid-axillary line doubled the amplitude (clips past ±1.41 mV) and added muscle noise, without changing the R/S balance. The wearer's QRS is RS-type (|S| ≈ |R|) — a normal variant, not a placement error.

Practical lessons: **use fresh pads and let them settle 1–2 min** (old pads gave 3–4× smaller signals; freshly applied ones saturate the AD8232 and trip fast-restore cycling until the offset settles); keep the mains-powered PC from adding 50 Hz (USB isolator or battery); nothing may touch the board's RL/LA/RA holes — they are in parallel with the jack, and soldered header pins there shorted the inputs. Board gain was measured by injecting a known square wave (1 MΩ/220 Ω divider from GPIO21): ≈1000–1090, flat to 40 Hz, matching the Figure 66 model.

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
pio test -e native                # host unit tests for SignalProcessor (needs MSYS2 gcc on PATH)
```
No serial ports are hardcoded in `platformio.ini` — pass `--upload-port` / `--port` yourself.

**ML pipeline** (`ml_pipeline/`, venv at `.venv`):

```
.venv\Scripts\Activate.ps1
python scripts/01_download_data.py      # MIT-BIH from PhysioNet (~100 MB)
python scripts/02_explore_data.py
python scripts/analyze_mitbih.py        # per-record AAMI class tallies + DS1/DS2 split comparison
python scripts/preprocess.py
python scripts/train_model.py --ensemble 4   # shipped config: 4 runs averaged; omit for a single run
python convert_model.py --model models/trained/model_ensemble_<ts>.keras ...   # → INT8 + f32 TFLite in src/ml/
python extract_constants.py --meta data/processed/metadata.pkl --ecg_scaler data/processed/ecg_scaler.pkl \
    --rr_scaler data/processed/rr_scaler.pkl --results models/trained/results_<ts>.pkl   # → src/ml/model_metadata.json
```
Then copy `src/ml/arrhythmia_model.tflite` + `model_metadata.json` into `ecg_monitor_app/app/src/main/assets/`. Set `PYTHONIOENCODING=utf-8` if output is redirected (the scripts print box-drawing characters).

**Scalers and model must come from the same run.** `data/processed/*_scaler.pkl` is overwritten by every `preprocess.py` run, so re-running `extract_constants.py` after a new preprocess without retraining would ship wrong normalisation. The shipped model is `model_ensemble_20261015_212815`: the softmax average of 4 independently trained residual runs (`train_model.py --ensemble 4`), merged into one TFLite (same inputs/outputs as a single model, ~700 KB INT8), trained with the slope-energy-centroid beat fiducial. Single runs vary too much to ship one: identical training landed anywhere from 0.68 to 0.78 end-to-end macro-F1, mostly because threshold tuning and checkpoint selection rely on only 4 validation records. Thresholds are tuned on validation only. DS2 end-to-end 0.772 (firmware path, board-filtered input), training-path 0.780. After retraining, also update `ArrhythmiaClassifier.EXPECTED_DRIFT_SCORE` (mean |z| of `X_train.npy`).

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
- **Test coverage is thin.** `test/test_signal_processor` (`pio test -e native`) covers SignalProcessor with synthetic ECG: detection, the 96/32 window, real `post_rr`, the 8-interval RR mean, reset. Not covered: main.cpp's lead-off/settling logic, BLE, and the ML pipeline (end-to-end parity with MIT-BIH was verified by hand, not by a committed test). If a native test fails to link with a silent `ld returned 5`, see the `UNITY_WEAK_ATTRIBUTE` note in `platformio.ini`.
