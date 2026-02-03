# Arrhythmia Detection System

A modern AI-powered arrhythmia detection system using ECG and PPG sensors with ESP32, featuring foundation model fine-tuning, explainable AI, and edge deployment.

## 🎯 Project Goals

This project demonstrates cutting-edge AI techniques applied to embedded medical devices:
- **Foundation Model Fine-tuning**: Leverage pre-trained ECG models instead of training from scratch
- **Explainable AI (XAI)**: Understand *why* the model makes predictions (SHAP, attention visualization)
- **Edge AI Deployment**: Run optimized models on ESP32 microcontroller in real-time
- **Privacy-First**: All processing happens on-device, no cloud required
- **Modern Development Practices**: Full ML pipeline, version control, documentation

## 📋 System Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                    Arrhythmia Detection System                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌──────────────┐      ┌──────────────┐      ┌──────────────┐ │
│  │  ECG Sensor  │──────│   ESP32-S3   │──────│  Mobile App  │ │
│  │  (AD8232)    │      │              │  BLE │ (Bluetooth)  │ │
│  └──────────────┘      │  - Filtering │      └──────────────┘ │
│                        │  - TFLite AI │                        │
│  ┌──────────────┐      │  - Real-time │                        │
│  │  PPG Sensor  │──────│    Inference │                        │
│  │ (MAX30102)   │      │              │                        │
│  └──────────────┘      └──────────────┘                        │
│                                                                 │
│  Features:                                                      │
│  ✓ Detects AFib, PVCs, and other arrhythmias                  │
│  ✓ Explainable predictions with XAI                           │
│  ✓ On-device processing (privacy-preserving)                  │
│  ✓ Real-time ECG/PPG visualization                            │
│  ✓ Trend analysis and event logging                           │
└─────────────────────────────────────────────────────────────────┘
```

## 🛠️ Hardware Components

| Component | Model | Purpose | Price |
|-----------|-------|---------|-------|
| Microcontroller | ESP32-S3 DevKit | Main processor, BLE communication | ~$12 |
| ECG Sensor | AD8232 | Single-lead ECG signal acquisition | ~$10 |
| PPG Sensor | GY-MAX30102 | Heart rate, SpO2 measurement | ~$8 |
| Electrodes | Disposable ECG pads (50pk) | ECG signal contact | ~$12 |
| Power | USB-C (development phase) | 5V power supply | - |

**Total Cost (Development):** ~$42-50

## 📁 Project Structure

```
Arrhythmia-detection-system/
├── README.md                          # This file
├── LICENSE
├── .gitignore
│
├── arrhythmia_system_controller/      # ESP32 Firmware (PlatformIO)
│   ├── platformio.ini                 # PlatformIO configuration
│   ├── src/                           # C++ source code
│   │   ├── main.cpp                   # Main program
│   │   ├── sensors/                   # ECG/PPG sensor drivers
│   │   ├── ml/                        # TensorFlow Lite inference
│   │   ├── signal_processing/         # Filtering, preprocessing
│   │   └── communication/             # BLE communication
│   └── lib/                           # Libraries
│
├── ml_pipeline/                       # Python ML Development
│   ├── README.md                      # ML workflow documentation
│   ├── SETUP.md                       # Environment setup guide
│   ├── requirements.txt               # Python dependencies
│   ├── data/                          # Datasets (gitignored)
│   ├── models/                        # Trained models
│   ├── notebooks/                     # Jupyter notebooks
│   ├── scripts/                       # Python scripts
│   └── src/                           # Reusable Python modules
│
├── mobile_app/                        # Mobile Application (future)
│   └── (Flutter/React Native - coming soon)
│
└── docs/                              # Documentation
    ├── hardware_setup.md              # Hardware assembly guide
    ├── ml_workflow.md                 # ML training workflow
    └── deployment.md                  # Deployment instructions
```

## 🚀 Quick Start

### Prerequisites
- **Hardware:** ESP32-S3, AD8232, MAX30102, electrodes
- **Software:** 
  - Python 3.10+ (for ML pipeline)
  - PlatformIO (for ESP32 development)
  - VS Code (recommended)

### Setup

#### 1. Clone Repository
```bash
git clone https://github.com/yourusername/arrhythmia-detection-system.git
cd arrhythmia-detection-system
```

#### 2. Set Up ML Pipeline (Python)
```bash
cd ml_pipeline
python -m venv .venv
.venv\Scripts\Activate.ps1  # Windows PowerShell
pip install -r requirements.txt
```

See `ml_pipeline/SETUP.md` for detailed instructions.

#### 3. Download Training Data
```bash
python scripts/01_download_data.py
```

#### 4. Explore Data
```bash
python scripts/02_explore_data.py
# Or open notebooks/01_ecg_basics.ipynb in Jupyter
```

#### 5. ESP32 Development (after hardware arrives)
- Open `arrhythmia_system_controller` in PlatformIO
- Upload firmware to ESP32
- Monitor serial output

## 📚 Learning Path

This project is designed as a learning journey through modern AI development:

### Week 1-2: Data & Understanding
- [ ] Set up Python environment
- [ ] Download MIT-BIH dataset
- [ ] Understand ECG signals and arrhythmias
- [ ] Explore data in Jupyter notebooks
- [ ] Learn signal processing basics

### Week 3-4: Preprocessing & Model Training
- [ ] Build preprocessing pipeline
- [ ] Find pre-trained ECG foundation model
- [ ] Fine-tune on arrhythmia detection
- [ ] Evaluate model performance
- [ ] Implement basic XAI (SHAP)

### Week 5-6: Model Optimization
- [ ] Quantize model for embedded deployment
- [ ] Convert to TensorFlow Lite
- [ ] Test inference speed vs accuracy trade-offs
- [ ] Generate C array for ESP32

### Week 7-8: Hardware Integration
- [ ] Wire sensors to ESP32
- [ ] Test ECG/PPG data acquisition
- [ ] Implement signal filtering on ESP32
- [ ] Deploy TFLite model
- [ ] Real-time inference testing

### Week 9-12: Mobile App & Polish
- [ ] Design mobile app UI
- [ ] Implement BLE communication
- [ ] Build visualization features
- [ ] Add trend analysis
- [ ] Documentation and demos

## 🧠 AI/ML Concepts Demonstrated

This project showcases modern (2025-2026) AI techniques:

1. **Transfer Learning**: Fine-tuning foundation models instead of training from scratch
2. **Explainable AI (XAI)**: Understanding model decisions with SHAP values
3. **Edge AI**: Running neural networks on microcontrollers
4. **Model Compression**: Quantization (INT8) for embedded deployment
5. **Signal Processing**: Filtering, normalization, feature extraction
6. **Privacy-Preserving ML**: On-device processing, no cloud needed

## 🔬 Technical Highlights

### Foundation Model Fine-tuning
- Start with models pre-trained on millions of ECG samples
- Adapt to specific arrhythmia detection task
- Achieve better accuracy with less training data

### Explainable AI (XAI)
- SHAP values: Which ECG features influenced the decision?
- Attention visualization: Where did the model "look"?
- Critical for medical applications and debugging

### Edge Deployment
- Convert TensorFlow model → TensorFlow Lite → C array
- INT8 quantization: 4x smaller, 4x faster
- Real-time inference on ESP32 (<100ms latency)

### Privacy-First Architecture
- All data processing on-device
- No cloud uploads required
- GDPR/HIPAA friendly design

## 📊 Performance Targets

| Metric | Target | Notes |
|--------|--------|-------|
| Model Accuracy | >95% | On MIT-BIH test set |
| Inference Latency | <100ms | On ESP32 |
| Model Size | <500KB | After quantization |
| Power Consumption | <50mA | During active monitoring |
| False Positive Rate | <5% | For arrhythmia detection |

## 🤝 Contributing

This is a learning project, but suggestions and improvements are welcome!

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Submit a pull request

## 📄 License

[Your chosen license - e.g., MIT]

## 🙏 Acknowledgments

- **MIT-BIH Arrhythmia Database**: PhysioNet (Goldberger et al., 2000)
- **TensorFlow Team**: For TensorFlow Lite Micro
- **WFDB Library**: For ECG data processing tools
- **Open Source Community**: For countless libraries and examples

## 📞 Contact

[Your name]
- GitHub: [@yourusername](https://github.com/yourusername)
- LinkedIn: [Your LinkedIn]
- Email: your.email@example.com

## 🎓 Educational Purpose

**Disclaimer**: This is an educational project and NOT a medical device. It is not intended for clinical use, diagnosis, or treatment. Always consult qualified healthcare professionals for medical advice.

## 🗺️ Roadmap

- [x] Project planning and setup
- [x] Hardware selection
- [x] Python environment setup
- [ ] Data collection and exploration
- [ ] Preprocessing pipeline
- [ ] Model training and fine-tuning
- [ ] XAI implementation
- [ ] Model conversion and optimization
- [ ] ESP32 firmware development
- [ ] Hardware integration
- [ ] Mobile app development
- [ ] Documentation and demos
- [ ] Portfolio presentation materials

---

**Status**: 🚧 In Development - Hardware ordered, ML pipeline setup in progress
