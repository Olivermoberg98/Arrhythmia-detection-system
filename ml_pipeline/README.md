# ML pipeline

Offline training of the heartbeat classifier that runs in the Android app. It takes the
MIT-BIH Arrhythmia Database, reproduces the device's signal chain, trains a dual-input
residual 1D CNN and exports a TensorFlow Lite model plus its normalisation and threshold
metadata.

| | |
|---|---|
| **Task** | Per-beat classification into 3 AAMI superclasses: Normal (N), Supraventricular (S), Ventricular (V) |
| **Inputs** | 128-sample beat window at 250 Hz (96 before / 32 after the fiducial) + 3 RR features `[pre_rr_norm, post_rr_norm, ratio_rr]` |
| **Split** | Inter-patient DS1 (train/val) / DS2 (test), de Chazal et al. (2004): no patient appears in both |
| **Model** | Softmax average of 4 independently trained residual CNNs, merged into one TFLite (~700 KB, INT8 weights, float32 I/O) |
| **Decision rule** | `argmax(probs / thresholds)`, with per-class thresholds tuned on validation only |
| **Result** | DS2 macro-F1 **0.772** end-to-end (firmware filter path, board-filtered input) |

## Design highlights

The rationale for each modelling decision, with literature citations, is in the module
docstrings of [`scripts/preprocess.py`](scripts/preprocess.py) and
[`scripts/train_model.py`](scripts/train_model.py). In short:

- **Training reproduces the firmware.** Raw MIT-BIH (360 Hz) is passed through a model of
  the AD8232 board's analogue filters, resampled to 250 Hz, and filtered with the firmware's
  exact causal biquad coefficients. Beats are aligned on the same QRS slope-energy-centroid
  fiducial the firmware's detector uses, and RR features use the firmware's 8-beat running
  mean. When the real `SignalProcessor.cpp` is compiled on the host and fed MIT-BIH, its beat
  windows match the training data to about 1 µV.
- **3 classes, not 5.** Paced (Q) beats are trivially separable and leak across splits.
  Fusion (F) beats are ambiguous and too rare for inter-patient learning, so they are merged
  into V.
- **RR features** carry the timing cue (premature beat, compensatory pause) that
  supraventricular beats need, because their QRS shape is nearly normal.
- **Ensembling.** Single runs varied from 0.68 to 0.78 macro-F1 because checkpointing and
  threshold tuning rest on only 4 validation records. Averaging 4 runs removes most of that
  variance without selecting on the test set.
- **No leakage.** Normalisation statistics come from the training split only.

## Setup

Python 3.10 (TensorFlow 2.15).

```bash
cd ml_pipeline
python -m venv .venv
.venv\Scripts\Activate.ps1        # Windows PowerShell
# source .venv/bin/activate       # Linux / macOS
pip install -r requirements.txt
```

Set `PYTHONIOENCODING=utf-8` if you redirect script output, because the scripts print
box-drawing characters.

## Workflow

```bash
python scripts/01_download_data.py     # MIT-BIH from PhysioNet (~100 MB) → data/raw/
python scripts/02_explore_data.py      # optional: plots of records and beat types
python scripts/analyze_mitbih.py       # optional: per-record AAMI class tallies, DS1/DS2 comparison
python scripts/preprocess.py           # → data/processed/ (beats, RR features, scalers, metadata)
python scripts/train_model.py --ensemble 4   # → models/trained/ (omit --ensemble for a single run)

python convert_model.py --model models/trained/model_ensemble_<ts>.keras \
    --meta data/processed/metadata.pkl \
    --ecg data/processed/X_val.npy --rr data/processed/RR_val.npy   # → src/ml/*.tflite

python extract_constants.py --meta data/processed/metadata.pkl \
    --ecg_scaler data/processed/ecg_scaler.pkl --rr_scaler data/processed/rr_scaler.pkl \
    --results models/trained/results_<ts>.pkl                       # → src/ml/model_metadata.json
```

Then copy `src/ml/arrhythmia_model.tflite` and `src/ml/model_metadata.json` together into
`ecg_monitor_app/app/src/main/assets/`. CI checks that the two copies are identical.

> **The scalers and the model must come from the same run.** Every `preprocess.py` run
> overwrites `data/processed/*_scaler.pkl`. If you run `extract_constants.py` after a new
> preprocess without retraining, the app ships with the wrong normalisation.

After retraining, also update `ArrhythmiaClassifier.EXPECTED_DRIFT_SCORE` in the app (the
mean |z| of `X_train.npy`), which the app uses for input-drift monitoring.

## Layout

```
ml_pipeline/
├── scripts/            # 01_download → preprocess → train_model (+ exploration/analysis)
├── convert_model.py    # Keras → INT8 and float32 TFLite
├── extract_constants.py# scalers + thresholds → model_metadata.json
├── src/ml/             # shipped artifacts (tracked): arrhythmia_model.tflite, _f32 reference, model_metadata.json
├── notebooks/          # ECG basics walkthrough
├── data/               # raw/, processed/, personal/ (all gitignored)
└── models/trained/     # per-run Keras models, results and plots (gitignored)
```

## Cross-project contracts

The sample rate, beat window, RR feature definition, filter coefficients and class list are
duplicated in the firmware (`arrhythmia_system_controller/src/config.h`), in this pipeline,
in `model_metadata.json` and in the app's classifier. Nothing checks at runtime that they
agree, so a change to one must be made in all of them.

## Data

MIT-BIH Arrhythmia Database: Moody GB, Mark RG. *The impact of the MIT-BIH Arrhythmia
Database.* IEEE Eng Med Biol 20(3):45-50 (2001). Distributed by PhysioNet: Goldberger AL et
al., *Circulation* 101(23):e215-e220 (2000). Available under the
[Open Data Commons Attribution License v1.0](https://physionet.org/content/mitdb/1.0.0/).
