# ML Pipeline Setup Guide

This guide will help you set up the Python environment for model training and experimentation.

## Prerequisites
- Python 3.10.0 (already installed)
- VS Code
- Git

## Setup Steps

### 1. Navigate to ml_pipeline directory
```bash
cd Arrhythmia-detection-system/ml_pipeline
```

### 2. Create Virtual Environment
```bash
python -m venv .venv
```

### 3. Activate Virtual Environment

**Windows (PowerShell):**
```powershell
.venv\Scripts\Activate.ps1
```

**Windows (Command Prompt):**
```cmd
.venv\Scripts\activate.bat
```

**If you get execution policy error in PowerShell:**
```powershell
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
```

### 4. Upgrade pip
```bash
python -m pip install --upgrade pip
```

### 5. Install Dependencies
```bash
pip install -r requirements.txt
```

This will install:
- TensorFlow 2.15.0 (ML framework)
- NumPy, Pandas (data handling)
- SciPy (signal processing)
- WFDB (ECG dataset reader)
- Matplotlib, Seaborn (visualization)
- Jupyter (interactive notebooks)
- SHAP (explainable AI)
- And more...

**Note:** TensorFlow installation may take 5-10 minutes.

### 6. Verify Installation
```bash
python -c "import tensorflow as tf; print(f'TensorFlow version: {tf.__version__}')"
python -c "import wfdb; print('WFDB library OK')"
```

You should see:
```
TensorFlow version: 2.15.0
WFDB library OK
```

### 7. Set Up Jupyter in VS Code (Optional but Recommended)

1. Install **Jupyter extension** in VS Code (if not already installed)
2. Open any `.ipynb` file in the `notebooks/` folder
3. VS Code will prompt you to select a kernel
4. Choose: `.venv (Python 3.10.0)` from your virtual environment

## VS Code Configuration

### Recommended Extensions
- **Python** (Microsoft)
- **Jupyter** (Microsoft)
- **Pylance** (Microsoft)
- **PlatformIO IDE** (already installed for ESP32 work)

### Select Python Interpreter in VS Code
1. Press `Ctrl+Shift+P`
2. Type "Python: Select Interpreter"
3. Choose: `.\ml_pipeline\.venv\Scripts\python.exe`

## Workflow

### When working on ML tasks:
1. Activate virtual environment: `.venv\Scripts\Activate.ps1`
2. Work in `ml_pipeline/` directory
3. Use Jupyter notebooks for exploration
4. Save production code in `scripts/` or `src/`

### When working on ESP32:
1. Open PlatformIO project: `arrhythmia_system_controller/`
2. No virtual environment needed (PlatformIO handles dependencies)

## Troubleshooting

### "pip is not recognized"
- Make sure virtual environment is activated
- Check: `where python` should point to `.venv\Scripts\python.exe`

### TensorFlow installation fails
- Update pip: `python -m pip install --upgrade pip`
- Try: `pip install tensorflow-cpu==2.15.0` (CPU-only version)

### Jupyter kernel not found
- Make sure ipykernel is installed: `pip install ipykernel`
- Register kernel: `python -m ipykernel install --user --name=arrhythmia-venv`

## Next Steps

After setup is complete:
1. Download MIT-BIH dataset (we'll create a script for this)
2. Explore ECG signals in Jupyter notebook
3. Build preprocessing pipeline
4. Train your first model

See the numbered scripts in `scripts/` directory for the workflow.
