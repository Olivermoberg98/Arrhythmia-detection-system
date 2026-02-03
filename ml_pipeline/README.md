# ML Pipeline - Arrhythmia Detection

This directory contains all the machine learning code for training, evaluating, and converting models for arrhythmia detection.

## Overview

The ML pipeline is separate from the ESP32 firmware and runs on your laptop/PC. It handles:
1. Data acquisition and preprocessing
2. Model training and fine-tuning
3. Model evaluation and explainability (XAI)
4. Model conversion to TensorFlow Lite for ESP32 deployment

## Directory Structure

```
ml_pipeline/
├── data/
│   ├── raw/              # Downloaded datasets (gitignored)
│   ├── processed/        # Preprocessed data (gitignored)
│   └── personal/         # Your own collected data (gitignored)
├── models/
│   ├── trained/          # Trained models (.h5, .keras) (gitignored)
│   └── converted/        # TFLite models for ESP32 (tracked in git)
├── notebooks/            # Jupyter notebooks for exploration
├── scripts/              # Numbered scripts for workflow
│   ├── 01_download_data.py
│   ├── 02_explore_data.py
│   ├── 03_preprocess.py
│   ├── 04_train_model.py
│   └── 05_convert_model.py
├── src/                  # Reusable Python modules
│   ├── data_loader.py
│   ├── preprocessing.py
│   ├── models.py
│   └── utils.py
├── requirements.txt      # Python dependencies
└── SETUP.md             # Setup instructions
```

## Workflow

### Phase 1: Setup (Do This First!)
1. Follow instructions in `SETUP.md` to create virtual environment
2. Install dependencies: `pip install -r requirements.txt`
3. Verify installation works

### Phase 2: Data Collection (Week 1)
1. **Download public dataset:**
   ```bash
   python scripts/01_download_data.py
   ```
   This downloads MIT-BIH Arrhythmia Database (~100MB)

2. **Explore the data:**
   ```bash
   python scripts/02_explore_data.py
   ```
   Visualize ECG signals and understand different beat types

### Phase 3: Preprocessing (Week 1-2)
3. **Preprocess data:**
   ```bash
   python scripts/03_preprocess.py
   ```
   - Filter noise
   - Normalize signals
   - Segment beats
   - Create train/validation/test splits

### Phase 4: Model Training (Week 2-4)
4. **Train model:**
   ```bash
   python scripts/04_train_model.py
   ```
   - Load preprocessed data
   - Fine-tune foundation model or train from scratch
   - Evaluate performance
   - Save best model

### Phase 5: Conversion (Week 4)
5. **Convert to TensorFlow Lite:**
   ```bash
   python scripts/05_convert_model.py
   ```
   - Convert .h5/.keras to .tflite
   - Apply INT8 quantization
   - Convert to C array for ESP32
   - Validate conversion accuracy

## Python vs ESP32 Workflow

**Python (this directory):**
- Data science and ML experimentation
- Train models on powerful laptop/desktop
- Use full TensorFlow with GPU support (if available)
- Iterate quickly with large datasets

**ESP32 (arrhythmia_system_controller):**
- Real-time embedded inference
- Uses tiny converted models (.tflite → C array)
- Limited to TensorFlow Lite Micro
- Optimized for speed and memory

## Model Pipeline Flow

```
Public Dataset (MIT-BIH)
         ↓
[Python] Download & Explore
         ↓
[Python] Preprocess & Filter
         ↓
[Python] Train/Fine-tune Model → model.h5
         ↓
[Python] Evaluate & XAI Analysis
         ↓
[Python] Convert to TFLite → model.tflite (quantized)
         ↓
[Python] Convert to C Array → model_data.h
         ↓
[C++] Include in ESP32 Firmware
         ↓
[ESP32] Real-time Inference
```

## Key Concepts

### Foundation Model Fine-tuning
Instead of training from scratch, we:
1. Find a pre-trained ECG model (trained on millions of samples)
2. "Fine-tune" it on our specific task (arrhythmia detection)
3. Benefit: Better accuracy with less data and training time

### Quantization
Convert 32-bit float model → 8-bit integer model:
- **Before:** 1MB model, slow inference
- **After:** 250KB model, 4x faster, fits on ESP32
- **Cost:** Tiny accuracy drop (~1-2%)

### XAI (Explainable AI)
Make the model interpretable:
- SHAP values: Which parts of ECG signal mattered most?
- Attention maps: Where did the model "look"?
- Useful for: Debugging, trust, medical validation

## Tips

1. **Start small:** Use a subset of data first (e.g., 5 records) to test pipeline
2. **Use Jupyter:** Great for interactive exploration and plotting
3. **Save often:** Training can take hours, checkpoint regularly
4. **Document decisions:** Keep notes on what works/doesn't work
5. **Version control:** Commit working models and preprocessing configs

## Current ML Trends (2025-2026)

Modern approaches we'll use:
- **Transfer learning** from foundation models (not training from scratch)
- **Efficient architectures** (MobileNet-style for embedded)
- **Quantization-aware training** (train knowing it'll be quantized)
- **Federated learning** concepts (privacy-preserving, though not implemented yet)
- **Explainable AI** (required for medical applications)

## Resources

- [TensorFlow Lite Guide](https://www.tensorflow.org/lite)
- [WFDB Python Package](https://wfdb.readthedocs.io/)
- [MIT-BIH Database](https://physionet.org/content/mitdb/1.0.0/)
- [PhysioNet](https://physionet.org/) - Medical signal databases

## Next Steps After Setup

1. Run `01_download_data.py` to get MIT-BIH dataset
2. Run `02_explore_data.py` to understand ECG signals
3. Read through the notebook examples (coming soon)
4. Start experimenting!

## Questions?

Common issues and solutions will be documented here as we encounter them.
