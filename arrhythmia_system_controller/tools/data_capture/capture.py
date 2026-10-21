"""
capture.py – Serial data capture tool for arrhythmia system.

Usage:
    python capture.py --port COM3 --duration 60 --out raw_ecg.csv

Expects CSV lines from firmware built with -DENABLE_SERIAL_STREAM=1:
    timestamp_ms,ecg_raw,ecg_filtered

Writes a clean CSV file with:
  - Header validation
  - Dropped-frame detection (gap > 2× expected period)
  - Statistics summary on exit

Requirements: pip install pyserial
"""

import argparse
import csv
import sys
import time
from pathlib import Path

try:
    import serial
    import serial.tools.list_ports
except ImportError:
    sys.exit("pyserial not found. Run: pip install pyserial")

# ---- Constants -------------------------------------------------------
BAUD_RATE      = 921600
SAMPLE_RATE_HZ = 250
PERIOD_MS      = 1000 / SAMPLE_RATE_HZ           # 4 ms
DROP_THRESHOLD = PERIOD_MS * 2.5                  # flag if gap > 10 ms

EXPECTED_HEADER = "timestamp_ms,ecg_raw,ecg_filtered"

# ---- Helpers ---------------------------------------------------------

def list_ports():
    ports = serial.tools.list_ports.comports()
    if not ports:
        print("No serial ports found.")
        return
    print("Available ports:")
    for p in ports:
        print(f"  {p.device}  –  {p.description}")


def capture(port: str, duration: float, out_path: Path):
    print(f"Opening {port} at {BAUD_RATE} baud …")
    try:
        ser = serial.Serial(port, BAUD_RATE, timeout=2.0)
    except serial.SerialException as e:
        sys.exit(f"Cannot open port: {e}")

    # Flush stale data
    ser.reset_input_buffer()
    time.sleep(0.1)

    stats = {
        "total_samples":   0,
        "dropped_frames":  0,
        "lead_off_events": 0,
    }

    prev_ts_ms   = None
    header_found = False
    start_time   = time.monotonic()
    deadline     = start_time + duration

    out_path.parent.mkdir(parents=True, exist_ok=True)

    with open(out_path, "w", newline="") as csvfile:
        writer = csv.writer(csvfile)
        writer.writerow(EXPECTED_HEADER.split(","))

        print(f"Capturing for {duration:.0f} s → {out_path}")
        print("Press Ctrl+C to stop early.\n")

        try:
            while time.monotonic() < deadline:
                raw_line = ser.readline()
                if not raw_line:
                    continue

                try:
                    line = raw_line.decode("ascii", errors="replace").strip()
                except Exception:
                    continue

                # --- Header line from firmware ---
                if line == EXPECTED_HEADER:
                    header_found = True
                    continue

                if not header_found:
                    continue

                # --- Data line ---
                parts = line.split(",")
                if len(parts) != 3:
                    continue

                try:
                    ts_ms    = int(parts[0])
                    ecg_raw  = int(parts[1])
                    ecg_filt = float(parts[2])
                except ValueError:
                    continue

                # ---- Drop detection ----
                if prev_ts_ms is not None:
                    delta = ts_ms - prev_ts_ms
                    if delta > DROP_THRESHOLD:
                        stats["dropped_frames"] += 1
                prev_ts_ms = ts_ms

                # ---- Lead-off detection (raw == -32768) ----
                if ecg_raw == -32768:
                    stats["lead_off_events"] += 1

                writer.writerow([ts_ms, ecg_raw, f"{ecg_filt:.4f}"])
                stats["total_samples"] += 1

                # Live progress every 500 samples
                if stats["total_samples"] % 500 == 0:
                    elapsed = time.monotonic() - start_time
                    actual_hz = stats["total_samples"] / elapsed if elapsed > 0 else 0
                    print(f"  {stats['total_samples']} samples  "
                          f"{actual_hz:.1f} Hz  "
                          f"{stats['dropped_frames']} drops", end="\r")

        except KeyboardInterrupt:
            print("\nCapture interrupted by user.")

    ser.close()

    # ---- Summary ----
    elapsed = time.monotonic() - start_time
    print(f"\n{'─'*50}")
    print(f"Capture complete.")
    print(f"  Duration      : {elapsed:.1f} s")
    print(f"  Total samples : {stats['total_samples']}")
    actual_hz = stats['total_samples'] / elapsed if elapsed > 0 else 0
    print(f"  Effective rate: {actual_hz:.1f} Hz  (expected {SAMPLE_RATE_HZ} Hz)")
    print(f"  Dropped frames: {stats['dropped_frames']}")
    print(f"  Lead-off events:{stats['lead_off_events']}")
    print(f"  Output file   : {out_path}")
    print(f"{'─'*50}")


# ---- CLI -------------------------------------------------------------
if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="Capture ECG data from ESP32 over Serial."
    )
    parser.add_argument("--port",     type=str,   default=None,
                        help="Serial port (e.g. COM3 or /dev/ttyUSB0). "
                             "Omit to list available ports.")
    parser.add_argument("--duration", type=float, default=60.0,
                        help="Capture duration in seconds (default: 60).")
    parser.add_argument("--out",      type=str,
                        default="captured_data/raw_ecg.csv",
                        help="Output CSV file path.")

    args = parser.parse_args()

    if args.port is None:
        list_ports()
        sys.exit(0)

    capture(
        port=args.port,
        duration=args.duration,
        out_path=Path(args.out),
    )
