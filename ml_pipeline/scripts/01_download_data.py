"""
01_download_data.py: Download MIT-BIH Arrhythmia Database

This script downloads the MIT-BIH Arrhythmia Database from PhysioNet.
The database contains 48 half-hour excerpts of two-channel ambulatory ECG recordings
with expert annotations of arrhythmias.

Dataset info: https://physionet.org/content/mitdb/1.0.0/
"""

import os
import wfdb
from tqdm import tqdm

# Configuration
DATA_DIR = os.path.join(os.path.dirname(__file__), '..', 'data', 'raw', 'mitdb')
DATABASE_NAME = 'mitdb'

# MIT-BIH record numbers (48 recordings)
RECORD_NUMBERS = [
    100, 101, 102, 103, 104, 105, 106, 107, 108, 109,
    111, 112, 113, 114, 115, 116, 117, 118, 119, 121,
    122, 123, 124, 200, 201, 202, 203, 205, 207, 208,
    209, 210, 212, 213, 214, 215, 217, 219, 220, 221,
    222, 223, 228, 230, 231, 232, 233, 234
]


def download_mitdb():
    """Download MIT-BIH Arrhythmia Database from PhysioNet."""
    
    print("=" * 60)
    print("MIT-BIH Arrhythmia Database Download")
    print("=" * 60)
    print(f"Downloading to: {os.path.abspath(DATA_DIR)}")
    print(f"Total records: {len(RECORD_NUMBERS)}")
    print()
    
    # Create directory if it doesn't exist
    os.makedirs(DATA_DIR, exist_ok=True)
    
    # Download each record
    print("Downloading records...")
    for record_num in tqdm(RECORD_NUMBERS, desc="Progress"):
        record_name = str(record_num)
        
        try:
            # Download record (downloads .dat, .hea, .atr files)
            wfdb.dl_files(
                'mitdb',  # Database name
                DATA_DIR,  # Download directory
                [f'{record_name}.dat', f'{record_name}.hea', f'{record_name}.atr']
            )
        except Exception as e:
            print(f"\nWarning: Failed to download record {record_name}: {e}")
            continue
    
    print("\n" + "=" * 60)
    print("Download complete!")
    print("=" * 60)
    
    # Verify download
    verify_download()


def verify_download():
    """Verify that all files were downloaded correctly."""
    
    print("\nVerifying downloaded files...")
    
    missing_files = []
    for record_num in RECORD_NUMBERS:
        record_name = str(record_num)
        
        # Check for required files
        dat_file = os.path.join(DATA_DIR, f'{record_name}.dat')
        hea_file = os.path.join(DATA_DIR, f'{record_name}.hea')
        atr_file = os.path.join(DATA_DIR, f'{record_name}.atr')
        
        if not all([os.path.exists(f) for f in [dat_file, hea_file, atr_file]]):
            missing_files.append(record_name)
    
    if missing_files:
        print(f"\n⚠️  Warning: Missing files for records: {missing_files}")
    else:
        print("✓ All files downloaded successfully!")
    
    # Show dataset statistics
    print(f"\nDataset location: {os.path.abspath(DATA_DIR)}")
    print(f"Total records: {len(RECORD_NUMBERS)}")
    print(f"Successfully downloaded: {len(RECORD_NUMBERS) - len(missing_files)}")
    
    # Try to load one record as a test
    print("\nTesting data loading...")
    try:
        test_record = wfdb.rdrecord(os.path.join(DATA_DIR, '100'))
        test_annotation = wfdb.rdann(os.path.join(DATA_DIR, '100'), 'atr')
        
        print(f"✓ Successfully loaded test record 100")
        print(f"  - Signal length: {test_record.sig_len} samples")
        print(f"  - Sampling frequency: {test_record.fs} Hz")
        print(f"  - Number of annotations: {len(test_annotation.symbol)}")
        print(f"  - Recording duration: {test_record.sig_len / test_record.fs / 60:.1f} minutes")
    except Exception as e:
        print(f"✗ Error loading test record: {e}")


def get_dataset_info():
    """Display information about the MIT-BIH dataset."""
    
    print("\n" + "=" * 60)
    print("MIT-BIH Arrhythmia Database Information")
    print("=" * 60)
    print("""
    The MIT-BIH Arrhythmia Database contains:
    - 48 half-hour excerpts of two-channel ambulatory ECG recordings
    - Obtained from 47 subjects (25 men aged 32-89, 22 women aged 23-89)
    - Digitized at 360 samples per second per channel
    - 11-bit resolution over a 10 mV range
    - Expert annotations for beat-by-beat arrhythmia classification
    
    Common annotation symbols:
    - N: Normal beat
    - L: Left bundle branch block beat
    - R: Right bundle branch block beat
    - A: Atrial premature beat
    - V: Premature ventricular contraction
    - /: Paced beat
    - And many more...
    
    Reference:
    Moody GB, Mark RG. The impact of the MIT-BIH Arrhythmia Database.
    IEEE Eng in Med and Biol 20(3):45-50 (May-June 2001).
    """)


if __name__ == "__main__":
    get_dataset_info()
    
    # Ask user confirmation
    response = input("\nDo you want to download the MIT-BIH dataset? (y/n): ")
    
    if response.lower() in ['y', 'yes']:
        download_mitdb()
    else:
        print("Download cancelled.")
