"""
Script 02: Explore ECG Data

This script helps you visualize and understand ECG signals from the MIT-BIH database.
You'll learn to identify normal heartbeats and common arrhythmias.

Run this after downloading the dataset with script 01.
"""

import os
import numpy as np
import matplotlib.pyplot as plt
import wfdb
from collections import Counter

# Configuration
DATA_DIR = os.path.join(os.path.dirname(__file__), '..', 'data', 'raw', 'mitdb')


def load_record(record_number):
    """
    Load a single ECG record with annotations.
    
    Args:
        record_number: Record ID (e.g., 100, 101, etc.)
    
    Returns:
        record: WFDB record object
        annotation: WFDB annotation object
    """
    record_path = os.path.join(DATA_DIR, str(record_number))
    
    # Load signal
    record = wfdb.rdrecord(record_path)
    
    # Load annotations
    annotation = wfdb.rdann(record_path, 'atr')
    
    return record, annotation


def plot_ecg_segment(record, annotation, start_time=0, duration=10, channel=0):
    """
    Plot a segment of ECG with annotations.
    
    Args:
        record: WFDB record object
        annotation: WFDB annotation object
        start_time: Start time in seconds
        duration: Duration to plot in seconds
        channel: Which ECG channel to plot (0 or 1)
    """
    fs = record.fs  # Sampling frequency
    start_sample = int(start_time * fs)
    end_sample = int((start_time + duration) * fs)
    
    # Extract signal segment
    signal = record.p_signal[start_sample:end_sample, channel]
    time = np.arange(len(signal)) / fs + start_time
    
    # Find annotations in this segment
    ann_indices = np.where(
        (annotation.sample >= start_sample) & 
        (annotation.sample < end_sample)
    )[0]
    
    # Create plot
    plt.figure(figsize=(15, 5))
    plt.plot(time, signal, 'b-', linewidth=0.5, label='ECG Signal')
    
    # Mark annotations
    for idx in ann_indices:
        sample = annotation.sample[idx]
        symbol = annotation.symbol[idx]
        time_point = sample / fs
        
        # Get signal value at annotation point
        signal_idx = sample - start_sample
        if 0 <= signal_idx < len(signal):
            signal_value = signal[signal_idx]
            plt.plot(time_point, signal_value, 'ro', markersize=8)
            plt.text(time_point, signal_value + 0.1, symbol, 
                    fontsize=10, ha='center', color='red')
    
    plt.xlabel('Time (seconds)', fontsize=12)
    plt.ylabel('Amplitude (mV)', fontsize=12)
    plt.title(f'Record {record.record_name} - Channel {channel}', fontsize=14)
    plt.grid(True, alpha=0.3)
    plt.legend()
    plt.tight_layout()
    plt.show()


def get_annotation_statistics(annotation):
    """
    Get statistics about annotations in the record.
    
    Args:
        annotation: WFDB annotation object
    
    Returns:
        dict: Statistics about beat types
    """
    # Count each annotation type
    symbol_counts = Counter(annotation.symbol)
    
    # Total annotations
    total = len(annotation.symbol)
    
    print("\n" + "=" * 60)
    print("Annotation Statistics")
    print("=" * 60)
    print(f"Total beats: {total}\n")
    
    # Sort by frequency
    for symbol, count in symbol_counts.most_common():
        percentage = (count / total) * 100
        description = get_annotation_description(symbol)
        print(f"{symbol:5s} - {description:40s}: {count:5d} ({percentage:5.1f}%)")
    
    return symbol_counts


def get_annotation_description(symbol):
    """Get human-readable description of annotation symbol."""
    descriptions = {
        'N': 'Normal beat',
        'L': 'Left bundle branch block beat',
        'R': 'Right bundle branch block beat',
        'A': 'Atrial premature beat',
        'a': 'Aberrated atrial premature beat',
        'V': 'Premature ventricular contraction',
        'F': 'Fusion of ventricular and normal beat',
        '/': 'Paced beat',
        'f': 'Fusion of paced and normal beat',
        'Q': 'Unclassifiable beat',
        'E': 'Ventricular escape beat',
        'j': 'Nodal (junctional) escape beat',
        'S': 'Supraventricular premature beat',
        '[': 'Start of ventricular flutter/fibrillation',
        '!': 'Ventricular flutter wave',
        ']': 'End of ventricular flutter/fibrillation',
        'e': 'Atrial escape beat',
        'n': 'Supraventricular escape beat',
        '+': 'Rhythm change',
        '~': 'Signal quality change',
        '|': 'Isolated QRS-like artifact',
        's': 'ST segment change',
        'T': 'T-wave change',
        '*': 'Systole',
        'D': 'Diastole',
        '=': 'Measurement annotation',
        '"': 'Comment annotation',
        '@': 'Link to external data',
    }
    return descriptions.get(symbol, 'Unknown')


def explore_record(record_number, segments=None):
    """
    Comprehensive exploration of a single record.
    
    Args:
        record_number: Record ID to explore
        segments: List of (start_time, duration) tuples to plot
    """
    print(f"\n{'=' * 60}")
    print(f"Exploring Record {record_number}")
    print(f"{'=' * 60}")
    
    # Load data
    record, annotation = load_record(record_number)
    
    # Display basic info
    print(f"\nSignal Information:")
    print(f"  Sampling frequency: {record.fs} Hz")
    print(f"  Duration: {record.sig_len / record.fs / 60:.1f} minutes")
    print(f"  Number of channels: {record.n_sig}")
    print(f"  Signal names: {record.sig_name}")
    
    # Annotation statistics
    get_annotation_statistics(annotation)
    
    # Plot segments
    if segments is None:
        segments = [(0, 10), (60, 10), (300, 10)]  # Default: 3 segments
    
    print(f"\nPlotting {len(segments)} ECG segments...")
    for start, duration in segments:
        plot_ecg_segment(record, annotation, start_time=start, duration=duration)


def compare_normal_vs_arrhythmia():
    """
    Compare normal beats vs different arrhythmias across records.
    Useful for understanding what you're trying to detect.
    """
    print("\n" + "=" * 60)
    print("Comparing Normal vs Arrhythmia Patterns")
    print("=" * 60)
    
    # Records with high percentage of specific arrhythmias
    interesting_records = {
        100: "Mostly normal with some PVCs",
        106: "High rate of PVCs",
        200: "Frequent PVCs and fusion beats",
        207: "Bundle branch block",
        208: "Frequent PVCs",
    }
    
    for record_num, description in interesting_records.items():
        record_path = os.path.join(DATA_DIR, str(record_num))
        if os.path.exists(record_path + '.dat'):
            print(f"\n{record_num}: {description}")
            explore_record(record_num, segments=[(0, 10)])
        else:
            print(f"Record {record_num} not found (may not be downloaded yet)")


if __name__ == "__main__":
    print("MIT-BIH ECG Data Explorer")
    print("=" * 60)
    
    # Check if data exists
    if not os.path.exists(DATA_DIR):
        print(f"\n⚠️  Error: Data directory not found: {DATA_DIR}")
        print("Please run script 01_download_data.py first to download the dataset.")
        exit(1)
    
    # Explore a good example record (100 has variety of beats)
    print("\nExploring record 100 (good example with variety)...")
    explore_record(100, segments=[(0, 10), (60, 10), (180, 10)])
    
    # Optional: Compare different records
    response = input("\nWould you like to explore more records with arrhythmias? (y/n): ")
    if response.lower() in ['y', 'yes']:
        compare_normal_vs_arrhythmia()
