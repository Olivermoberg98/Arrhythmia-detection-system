"""
Script 04: Train Arrhythmia Detection Model - CLEAN VERSION

This script trains a 1D CNN for ECG arrhythmia detection with:
✓ Lower learning rate (3e-4)
✓ Class weights (handles imbalance)
✓ ReduceLROnPlateau
✓ Patient-level evaluation
"""

import os
import numpy as np
import pickle
import tensorflow as tf
from tensorflow import keras
from tensorflow.keras import layers, models
from tensorflow.keras.callbacks import (ModelCheckpoint, EarlyStopping, 
                                         ReduceLROnPlateau, TensorBoard)
from tensorflow.keras.losses import SparseCategoricalCrossentropy
from sklearn.metrics import classification_report, confusion_matrix
import matplotlib.pyplot as plt
import seaborn as sns
from datetime import datetime

# ============================================================================
# CONFIGURATION
# ============================================================================

DATA_DIR = os.path.join(os.path.dirname(__file__), '..', 'data', 'processed')
MODEL_DIR = os.path.join(os.path.dirname(__file__), '..', 'models', 'trained')
LOGS_DIR = os.path.join(os.path.dirname(__file__), '..', 'models', 'logs')

# Training parameters
BATCH_SIZE = 128
EPOCHS = 100
LEARNING_RATE = 3e-4  # Lower learning rate for ECG

# ============================================================================
# LOAD DATA
# ============================================================================

def load_data():
    """Load preprocessed data."""
    print("Loading data...")
    
    X_train = np.load(os.path.join(DATA_DIR, 'X_train.npy'))
    y_train = np.load(os.path.join(DATA_DIR, 'y_train.npy'))
    X_val = np.load(os.path.join(DATA_DIR, 'X_val.npy'))
    y_val = np.load(os.path.join(DATA_DIR, 'y_val.npy'))
    X_test = np.load(os.path.join(DATA_DIR, 'X_test.npy'))
    y_test = np.load(os.path.join(DATA_DIR, 'y_test.npy'))
    
    with open(os.path.join(DATA_DIR, 'metadata.pkl'), 'rb') as f:
        metadata = pickle.load(f)
    
    with open(os.path.join(DATA_DIR, 'class_weights.pkl'), 'rb') as f:
        class_weights = pickle.load(f)
    
    print(f"✓ Data loaded")
    print(f"  Train: {len(y_train)} samples")
    print(f"  Val: {len(y_val)} samples")
    print(f"  Test: {len(y_test)} samples")
    print(f"  Classes: {len(metadata['class_names'])}")
    
    return (X_train, y_train), (X_val, y_val), (X_test, y_test), metadata, class_weights


# ============================================================================
# MODEL ARCHITECTURES
# ============================================================================

def build_standard_model(input_shape, num_classes):
    """
    Standard 1D CNN (~50K parameters).
    
    Architecture:
    - 3 convolutional blocks with increasing filters (32→64→128)
    - Kernel sizes: 5→5→3
    - MaxPooling after each block
    - Dropout for regularization
    - Global average pooling
    - Dense classification layers
    """
    model = models.Sequential([
        layers.Input(shape=input_shape),
        layers.Reshape((input_shape[0], 1)),
        
        # Block 1
        layers.Conv1D(32, 5, activation='relu', padding='same', kernel_regularizer=keras.regularizers.l2(3e-4)),
        layers.BatchNormalization(),
        layers.MaxPooling1D(2),
        layers.Dropout(0.2),
        
        # Block 2
        layers.Conv1D(64, 5, activation='relu', padding='same'),
        layers.BatchNormalization(),
        layers.MaxPooling1D(2),
        layers.Dropout(0.2),
        
        # Block 3
        layers.Conv1D(128, 3, activation='relu', padding='same'),
        layers.BatchNormalization(),
        layers.MaxPooling1D(2),
        layers.Dropout(0.3),
        
        # Classification
        layers.GlobalAveragePooling1D(),
        layers.Dense(64, activation='relu'),
        layers.Dropout(0.3),
        layers.Dense(32, activation='relu'),
        layers.Dropout(0.2),
        layers.Dense(num_classes, activation='softmax')
    ])
    
    return model


def build_lightweight_model(input_shape, num_classes):
    """
    Lightweight 1D CNN (~20K parameters).
    
    Better for ESP32 deployment.
    Fewer filters, simpler architecture.
    """
    model = models.Sequential([
        layers.Input(shape=input_shape),
        layers.Reshape((input_shape[0], 1)),
        
        # Block 1
        layers.Conv1D(16, 7, activation='relu', padding='same'),
        layers.MaxPooling1D(2),
        layers.Dropout(0.2),
        
        # Block 2
        layers.Conv1D(32, 5, activation='relu', padding='same'),
        layers.MaxPooling1D(2),
        layers.Dropout(0.2),
        
        # Block 3
        layers.Conv1D(64, 3, activation='relu', padding='same'),
        layers.GlobalAveragePooling1D(),
        
        # Classification
        layers.Dense(32, activation='relu'),
        layers.Dropout(0.3),
        layers.Dense(num_classes, activation='softmax')
    ])
    
    return model


# ============================================================================
# VISUALIZATION
# ============================================================================

def plot_history(history, save_path=None):
    """Plot training history."""
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(15, 5))
    
    # Accuracy
    ax1.plot(history.history['accuracy'], label='Train', linewidth=2)
    ax1.plot(history.history['val_accuracy'], label='Validation', linewidth=2)
    ax1.set_xlabel('Epoch')
    ax1.set_ylabel('Accuracy')
    ax1.set_title('Model Accuracy')
    ax1.legend()
    ax1.grid(alpha=0.3)
    
    # Loss
    ax2.plot(history.history['loss'], label='Train', linewidth=2)
    ax2.plot(history.history['val_loss'], label='Validation', linewidth=2)
    ax2.set_xlabel('Epoch')
    ax2.set_ylabel('Loss')
    ax2.set_title('Model Loss')
    ax2.legend()
    ax2.grid(alpha=0.3)
    
    plt.tight_layout()
    if save_path:
        plt.savefig(save_path, dpi=300, bbox_inches='tight')
    plt.show()


def plot_confusion_matrix(y_true, y_pred, class_names, save_path=None):
    """Plot confusion matrix."""
    cm = confusion_matrix(y_true, y_pred)
    cm_norm = cm.astype('float') / cm.sum(axis=1)[:, np.newaxis]
    
    plt.figure(figsize=(10, 8))
    sns.heatmap(cm_norm, annot=True, fmt='.2f', cmap='Blues',
                xticklabels=class_names, yticklabels=class_names)
    plt.xlabel('Predicted')
    plt.ylabel('True')
    plt.title('Confusion Matrix (Normalized)')
    plt.tight_layout()
    
    if save_path:
        plt.savefig(save_path, dpi=300, bbox_inches='tight')
    plt.show()


# ============================================================================
# EVALUATION
# ============================================================================

def evaluate(model, X_test, y_test, class_names):
    """Evaluate model on test set."""
    print("\n" + "=" * 70)
    print("TEST SET EVALUATION (Inter-Patient)")
    print("=" * 70)
    
    y_pred_probs = model.predict(X_test, verbose=0)
    y_pred = np.argmax(y_pred_probs, axis=1)
    
    test_loss, test_acc = model.evaluate(X_test, y_test, verbose=0)
    
    print(f"\nTest Accuracy: {test_acc:.4f}")
    print(f"Test Loss: {test_loss:.4f}")
    
    print("\nPer-Class Performance:")
    print(classification_report(y_test, y_pred, 
                                target_names=class_names, 
                                digits=4))
    
    return y_pred


# ============================================================================
# MAIN TRAINING PIPELINE
# ============================================================================

def main():
    print("=" * 70)
    print("ARRHYTHMIA DETECTION MODEL TRAINING")
    print("=" * 70)
    
    # Check data exists
    if not os.path.exists(DATA_DIR):
        print(f"\n⚠️  Error: {DATA_DIR} not found")
        print("Run script 03_preprocess.py first")
        return
    
    os.makedirs(MODEL_DIR, exist_ok=True)
    os.makedirs(LOGS_DIR, exist_ok=True)
    
    # Load data
    (X_train, y_train), (X_val, y_val), (X_test, y_test), metadata, class_weights = load_data()
    
    # Get class names (in order)
    class_ids = sorted(metadata['class_names'].keys())
    class_names = [metadata['class_names'][i] for i in class_ids]
    
    input_shape = (metadata['beat_length'],)
    num_classes = len(class_names)
    
    print(f"\nConfiguration:")
    print(f"  Input shape: {input_shape}")
    print(f"  Classes: {num_classes} - {class_names}")
    print(f"  Learning rate: {LEARNING_RATE}")
    print(f"  Batch size: {BATCH_SIZE}")
    
    print(f"\nClass weights:")
    for cls_id in class_ids:
        weight = class_weights.get(cls_id, 1.0)
        print(f"  {class_names[cls_id]}: {weight:.2f}")
    
    # Choose architecture
    print("\nSelect architecture:")
    print("1. Standard CNN (~50K params)")
    print("2. Lightweight CNN (~20K params, better for ESP32)")
    choice = input("Choice (1/2): ").strip()
    
    if choice == "2":
        print("\nBuilding lightweight model...")
        model = build_lightweight_model(input_shape, num_classes)
    else:
        print("\nBuilding standard model...")
        model = build_standard_model(input_shape, num_classes)
    
    model.summary()
    
    params = model.count_params()
    print(f"\nParameters: {params:,}")
    print(f"Size (float32): ~{params * 4 / 1024:.1f} KB")
    print(f"Size (int8): ~{params / 1024:.1f} KB")
    
    # Compile
    model.compile(
        optimizer=keras.optimizers.Adam(LEARNING_RATE),
        loss = 'sparse_categorical_crossentropy',
        metrics=['accuracy']
    )
    
    # Callbacks
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    model_path = os.path.join(MODEL_DIR, f'model_{timestamp}.keras')
    
    callbacks = [
        ModelCheckpoint(model_path, monitor='val_accuracy', 
                       save_best_only=True, verbose=1),
        EarlyStopping(monitor='val_loss', patience=8, mode='max', 
                     restore_best_weights=True, verbose=1),
        ReduceLROnPlateau(monitor='val_loss', factor=0.5, 
                         patience=5, min_lr=1e-6, verbose=1),
        TensorBoard(log_dir=os.path.join(LOGS_DIR, timestamp))
    ]
    
    # Train
    print("\n" + "=" * 70)
    print("TRAINING")
    print("=" * 70)
    print(f"\nMax epochs: {EPOCHS}")
    print(f"Early stopping: 15 epochs patience")
    print(f"LR reduction: 0.5x every 5 epochs if no improvement")
    print("\nTraining started...\n")
    
    history = model.fit(
        X_train, y_train,
        batch_size=BATCH_SIZE,
        epochs=EPOCHS,
        validation_data=(X_val, y_val),
        class_weight=class_weights,
        callbacks=callbacks,
        verbose=1
    )
    
    print("\n✓ Training complete!")
    
    # Plot history
    plot_path = os.path.join(MODEL_DIR, f'history_{timestamp}.png')
    plot_history(history, plot_path)
    
    # Evaluate
    y_pred = evaluate(model, X_test, y_test, class_names)
    
    # Confusion matrix
    cm_path = os.path.join(MODEL_DIR, f'confusion_{timestamp}.png')
    plot_confusion_matrix(y_test, y_pred, class_names, cm_path)
    
    # Save results
    results = {
        'timestamp': timestamp,
        'model_path': model_path,
        'params': params,
        'learning_rate': LEARNING_RATE,
        'best_val_acc': max(history.history['val_accuracy']),
        'best_val_loss': min(history.history['val_loss']),
        'class_weights': class_weights,
        'metadata': metadata
    }
    
    results_path = os.path.join(MODEL_DIR, f'results_{timestamp}.pkl')
    with open(results_path, 'wb') as f:
        pickle.dump(results, f)
    
    print("\n" + "=" * 70)
    print("✓ TRAINING COMPLETE")
    print("=" * 70)
    print(f"\nBest val accuracy: {results['best_val_acc']:.4f}")
    print(f"Model saved: {model_path}")
    print(f"Results saved: {results_path}")
    print(f"\nNext: Convert model to TFLite (script 05)")
    print(f"\nTensorBoard: tensorboard --logdir={LOGS_DIR}")


if __name__ == "__main__":
    main()