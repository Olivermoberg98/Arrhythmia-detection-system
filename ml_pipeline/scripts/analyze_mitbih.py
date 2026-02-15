"""
analyze_mitbih.py  —  MIT-BIH Arrhythmia Database Record Analyser

Reads every available record, tallies beat annotations per AAMI class, and
produces two output files:
    mitbih_analysis.csv   — one row per record, easy to open in Excel
    mitbih_analysis.json  — full detail including raw symbol counts

Also prints a split-comparison table: your current split vs. the canonical
DS1/DS2 split from de Chazal et al. (2004) "Automatic classification of
heartbeats using ECG morphology and heartbeat interval features",
IEEE Trans. Biomed. Eng. 51(7), pp 1196-1206.

Usage:
    python analyze_mitbih.py
    python analyze_mitbih.py --data_dir /path/to/mitdb
"""

import os
import sys
import json
import argparse
import csv
from collections import Counter, defaultdict

try:
    import wfdb
except ImportError:
    sys.exit("wfdb not found.  Install with:  pip install wfdb")

# ============================================================================
# AAMI beat-class mapping  (ANSI/AAMI EC57:1998)
# ============================================================================
# Each MIT-BIH annotation symbol maps to one of five AAMI superclasses:
#   N  — Normal / bundle-branch-block / escape beats (non-ectopic)
#   S  — Supraventricular ectopic beats  (SVEB)
#   V  — Ventricular ectopic beats       (VEB)
#   F  — Fusion beats
#   Q  — Paced / unclassifiable
#
# Reference Table 1 of de Chazal et al. 2004.

AAMI_MAP = {
    # N superclass
    'N': 'N',   # Normal
    'L': 'N',   # Left bundle branch block
    'R': 'N',   # Right bundle branch block
    'e': 'N',   # Atrial escape
    'j': 'N',   # Nodal (junctional) escape
    # S superclass
    'A': 'S',   # Atrial premature beat
    'a': 'S',   # Aberrated atrial premature beat
    'J': 'S',   # Nodal (junctional) premature beat
    'S': 'S',   # Supraventricular premature beat
    # V superclass
    'V': 'V',   # Premature ventricular contraction
    'E': 'V',   # Ventricular escape beat
    # F superclass
    'F': 'F',   # Fusion of ventricular and normal beat
    # Q superclass
    '/': 'Q',   # Paced beat
    'f': 'Q',   # Fusion of paced and normal beat
    'Q': 'Q',   # Unclassifiable beat
}

AAMI_CLASSES = ['N', 'S', 'V', 'F', 'Q']

# ============================================================================
# Canonical DS1/DS2 inter-patient split
# Source: de Chazal et al. 2004, Table II.
# DS1 used for TRAINING, DS2 for TESTING.
# This split was chosen to balance SVEB and VEB counts across the two sets.
# ============================================================================
DS1 = [101, 106, 108, 109, 112, 114, 115, 116, 118, 119,
       122, 124, 201, 203, 205, 207, 208, 209, 215, 220, 223, 230]

DS2 = [100, 103, 105, 111, 113, 117, 121, 123,
       200, 202, 204, 206, 210, 212, 213, 214,
       219, 221, 222, 228, 231, 232, 233, 234]

# All 48 standard MIT-BIH records
ALL_RECORDS = sorted(set(DS1 + DS2))

# ============================================================================
# Current split in your preprocess.py  (for comparison)
# ============================================================================
CURRENT_TRAIN = [
    100, 101, 103, 105, 106, 108, 109, 111,
    112, 113, 114, 115, 116, 117, 118, 119,
    121, 122, 123, 124,
    201, 203, 205, 207, 208, 209, 215, 220,
    217, 219   # NOTE: 219 also appears in current TEST — data leakage!
]
CURRENT_VAL  = [102, 104, 107, 210, 212, 214, 221]
CURRENT_TEST = [110, 219, 228, 231]   # 219 is duplicated from TRAIN


# ============================================================================
# Analysis functions
# ============================================================================

def analyse_record(record_path: str) -> dict | None:
    """
    Load one MIT-BIH record and return a stats dict.
    Returns None if the record cannot be loaded.
    """
    try:
        ann = wfdb.rdann(record_path, 'atr')
    except Exception as e:
        print(f"  [SKIP] {os.path.basename(record_path)}: {e}")
        return None

    raw_counts   = Counter(ann.symbol)
    aami_counts  = Counter()
    ignored_syms = Counter()

    for sym in ann.symbol:
        if sym in AAMI_MAP:
            aami_counts[AAMI_MAP[sym]] += 1
        else:
            ignored_syms[sym] += 1

    total_classified = sum(aami_counts.values())

    return {
        'total_annotations' : len(ann.symbol),
        'total_classified'  : total_classified,
        'ignored'           : sum(ignored_syms.values()),
        'ignored_symbols'   : dict(ignored_syms),
        'aami'              : dict(aami_counts),
        'raw_symbols'       : dict(raw_counts),
    }


def build_analysis_table(data_dir: str) -> list[dict]:
    """
    Iterate over all MIT-BIH records and return a list of result rows.
    """
    rows = []
    print(f"\nScanning records in: {os.path.abspath(data_dir)}\n")
    print(f"{'Record':>8}  {'Total':>7}  {'N':>7}  {'S':>7}  {'V':>7}  {'F':>5}  {'Q':>5}  {'Ignored':>7}  Split")
    print("-" * 72)

    for rec in ALL_RECORDS:
        rec_path = os.path.join(data_dir, str(rec))
        stats = analyse_record(rec_path)
        if stats is None:
            continue

        a   = stats['aami']
        row = {
            'record'           : rec,
            'total_classified' : stats['total_classified'],
            'N'                : a.get('N', 0),
            'S'                : a.get('S', 0),
            'V'                : a.get('V', 0),
            'F'                : a.get('F', 0),
            'Q'                : a.get('Q', 0),
            'ignored'          : stats['ignored'],
            'ignored_symbols'  : stats['ignored_symbols'],
            'raw_symbols'      : stats['raw_symbols'],
            'ds1_ds2'          : 'DS1' if rec in DS1 else 'DS2',
            'current_split'    : (
                'TRAIN' if rec in CURRENT_TRAIN else
                'VAL'   if rec in CURRENT_VAL   else
                'TEST'  if rec in CURRENT_TEST  else
                'UNASSIGNED'
            ),
            'leakage_warning'  : rec in CURRENT_TRAIN and rec in CURRENT_TEST,
        }
        rows.append(row)

        split_tag = f"{row['ds1_ds2']:3s} / {row['current_split']}"
        leak_tag  = ' ⚠ LEAKAGE' if row['leakage_warning'] else ''
        print(f"  {rec:>6}  {row['total_classified']:>7,}  "
              f"{row['N']:>7,}  {row['S']:>7,}  {row['V']:>7,}  "
              f"{row['F']:>5,}  {row['Q']:>5,}  "
              f"{row['ignored']:>7,}  {split_tag}{leak_tag}")

    return rows


def print_split_summary(rows: list[dict]) -> None:
    """Print class-count totals for each proposed split."""

    def totals(record_list, label):
        subset = [r for r in rows if r['record'] in record_list]
        n = sum(r['N'] for r in subset)
        s = sum(r['S'] for r in subset)
        v = sum(r['V'] for r in subset)
        f = sum(r['F'] for r in subset)
        q = sum(r['Q'] for r in subset)
        total = n + s + v + f + q
        print(f"  {label:<30}  N={n:>6,}  S={s:>5,}  V={v:>5,}  F={f:>4,}  Q={q:>5,}  total={total:>7,}")

    print("\n" + "=" * 72)
    print("SPLIT COMPARISON: CLASS COUNTS")
    print("=" * 72)

    print("\n--- Canonical DS1/DS2 (de Chazal et al. 2004) ---")
    totals(DS1, "DS1  → train")
    totals(DS2, "DS2  → test")
    print("  Note: DS2 is the standard test set. Take a validation subset from DS1.")

    print("\n--- Suggested DS1 validation split ---")
    # Suggested: pull a few DS1 records that still maintain class balance
    # Records with notable SVEB content in DS1: 201, 209, 223
    DS1_VAL  = [209, 223]
    DS1_TRAIN = [r for r in DS1 if r not in DS1_VAL]
    totals(DS1_TRAIN, "DS1 train (minus val)")
    totals(DS1_VAL,   "DS1 val   (held out)")
    totals(DS2,       "DS2  → test")

    print("\n--- Your current split ---")
    totals(CURRENT_TRAIN, "current TRAIN (has leakage!)")
    totals(CURRENT_VAL,   "current VAL")
    totals(CURRENT_TEST,  "current TEST")

    print("\n" + "=" * 72)
    print("RECOMMENDED ACTION:")
    print("  Update TRAIN_RECORDS, VAL_RECORDS, TEST_RECORDS in preprocess.py")
    print("  to use the DS1/DS2 split shown above.")
    print("  DS1_TRAIN =", sorted(DS1_TRAIN))
    print("  DS1_VAL   =", sorted(DS1_VAL))
    print("  DS2_TEST  =", sorted(DS2))
    print()
    print("  S (SVEB) will be much better represented in your test set.")
    print("=" * 72)


def save_csv(rows: list[dict], out_path: str) -> None:
    fieldnames = ['record', 'total_classified', 'N', 'S', 'V', 'F', 'Q',
                  'ignored', 'ds1_ds2', 'current_split', 'leakage_warning']
    with open(out_path, 'w', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames, extrasaction='ignore')
        writer.writeheader()
        writer.writerows(rows)
    print(f"\n✔  CSV  saved: {out_path}")


def save_json(rows: list[dict], out_path: str,
              ds1_train: list, ds1_val: list, ds2: list) -> None:
    """
    Save full detail + the recommended split lists so preprocess.py can import them.
    """
    payload = {
        'records': rows,
        'recommended_split': {
            'description': 'de Chazal et al. 2004 DS1/DS2 with DS1 validation hold-out',
            'TRAIN_RECORDS': sorted(ds1_train),
            'VAL_RECORDS'  : sorted(ds1_val),
            'TEST_RECORDS' : sorted(ds2),
        },
        'leakage_warnings': [r['record'] for r in rows if r['leakage_warning']],
        'aami_class_definitions': {
            'N': 'Normal, LBBB, RBBB, atrial escape, nodal escape',
            'S': 'Atrial premature, aberrated APB, nodal premature, SVP',
            'V': 'Premature ventricular contraction, ventricular escape',
            'F': 'Fusion of ventricular and normal beat',
            'Q': 'Paced, fusion of paced & normal, unclassifiable',
        },
    }
    # Make rows JSON-serialisable (remove nested dicts that have int keys etc.)
    with open(out_path, 'w') as f:
        json.dump(payload, f, indent=2, default=str)
    print(f"✔  JSON saved: {out_path}")


# ============================================================================
# Main
# ============================================================================

def main():
    parser = argparse.ArgumentParser(description='Analyse MIT-BIH records')
    parser.add_argument('--data_dir', default=None,
                        help='Path to mitdb directory (default: auto-detect)')
    parser.add_argument('--out_dir', default=None,
                        help='Output directory (default: same as data_dir)')
    args = parser.parse_args()

    # ---- locate data ----
    if args.data_dir:
        data_dir = args.data_dir
    else:
        # Try common project layouts relative to this script
        candidates = [
            os.path.join(os.path.dirname(__file__), '..', 'data', 'raw', 'mitdb'),
            os.path.join(os.path.dirname(__file__), 'data', 'raw', 'mitdb'),
            os.path.join(os.path.dirname(__file__), '..', 'mitdb'),
        ]
        data_dir = next((p for p in candidates if os.path.isdir(p)), None)
        if data_dir is None:
            sys.exit(
                "Could not find mitdb directory. "
                "Pass --data_dir /path/to/mitdb explicitly."
            )

    data_dir = os.path.abspath(data_dir)
    out_dir  = os.path.abspath(args.out_dir or data_dir)
    os.makedirs(out_dir, exist_ok=True)

    print("=" * 72)
    print("MIT-BIH Arrhythmia Database — Record Analysis")
    print(f"Data directory : {data_dir}")
    print(f"Output directory: {out_dir}")
    print("=" * 72)

    # ---- analyse ----
    rows = build_analysis_table(data_dir)
    if not rows:
        sys.exit("No records could be read. Check --data_dir path.")

    # ---- proposed split ----
    DS1_VAL   = [209, 223]
    DS1_TRAIN = [r for r in DS1 if r not in DS1_VAL]

    print_split_summary(rows)

    # ---- save outputs ----
    csv_path  = os.path.join(out_dir, 'mitbih_analysis.csv')
    json_path = os.path.join(out_dir, 'mitbih_analysis.json')
    save_csv(rows, csv_path)
    save_json(rows, json_path, DS1_TRAIN, DS1_VAL, DS2)

    # ---- final class totals across entire dataset ----
    total_N = sum(r['N'] for r in rows)
    total_S = sum(r['S'] for r in rows)
    total_V = sum(r['V'] for r in rows)
    total_F = sum(r['F'] for r in rows)
    total_Q = sum(r['Q'] for r in rows)
    grand   = total_N + total_S + total_V + total_F + total_Q
    print(f"\nDataset totals across {len(rows)} records:")
    print(f"  N (Normal):           {total_N:>7,}  ({100*total_N/grand:.1f}%)")
    print(f"  S (Supraventricular): {total_S:>7,}  ({100*total_S/grand:.1f}%)")
    print(f"  V (Ventricular):      {total_V:>7,}  ({100*total_V/grand:.1f}%)")
    print(f"  F (Fusion):           {total_F:>7,}  ({100*total_F/grand:.1f}%)")
    print(f"  Q (Paced/Unknown):    {total_Q:>7,}  ({100*total_Q/grand:.1f}%)")
    print(f"  Total classified:     {grand:>7,}")


if __name__ == '__main__':
    main()