#!/usr/bin/env python
# -*- coding: utf-8 -*-

"""Batch-generate full-day demand CSVs for all study dates.

Reads dates from cluster/dates.txt (or a user-specified dates file) and produces
one full-day demand CSV per date in cluster/demand/demand_{date}.csv.

Usage:
    python cluster/generate_demand_csvs.py [--dates <path>] [--output-dir <path>] [--force] [--aggregation <min>]
"""

import argparse
import os
import sys
from pathlib import Path
from typing import List, Tuple
import pandas as pd

# Add diss_mvb to sys.path if not present
REPO_ROOT = Path(__file__).resolve().parents[1]
DISS_MVB_ROOT = Path("D:/Mitarbeitende/gw2128/repositories/diss_mvb")
if str(DISS_MVB_ROOT) not in sys.path:
    sys.path.insert(0, str(DISS_MVB_ROOT))

try:
    from diss_mvb.scripts.evaluation.fielddata.detectors.io.prepare_simulation_demand import (
        prepare_demand,
    )
except ImportError as err:
    print(f"[ERROR] Could not import prepare_demand from diss_mvb: {err}")
    print(f"Make sure diss_mvb is available at {DISS_MVB_ROOT}")
    sys.exit(1)


def read_dates(dates_file: Path) -> List[str]:
    """Reads dates from dates.txt, ignoring comments and blank lines."""
    if not dates_file.is_file():
        raise FileNotFoundError(f"Dates file not found: {dates_file.resolve()}")
    
    dates = []
    with open(dates_file, "r", encoding="utf-8") as f:
        for line in f:
            stripped = line.strip()
            if stripped and not stripped.startswith("#"):
                dates.append(stripped)
    return dates


def validate_demand_csv(csv_path: Path, min_rows: int = 100, min_total_demand: float = 1.0) -> Tuple[bool, str, int, float]:
    """Validates that a demand CSV exists, has headers, sufficient rows, and non-zero demand."""
    if not csv_path.is_file():
        return False, "File missing", 0, 0.0
    
    if csv_path.stat().st_size == 0:
        return False, "File is empty (0 bytes)", 0, 0.0
    
    try:
        df = pd.read_csv(csv_path)
        required_cols = {"time_sec", "timestamp", "origin", "destination", "gtu_type", "demand_veh_h"}
        missing_cols = required_cols - set(df.columns)
        if missing_cols:
            return False, f"Missing required columns: {missing_cols}", len(df), 0.0
        
        total_demand = df["demand_veh_h"].sum()
        row_count = len(df)
        
        if row_count < min_rows:
            return False, f"Row count {row_count} below minimum {min_rows}", row_count, total_demand
        
        if total_demand < min_total_demand:
            return False, f"Total demand sum {total_demand:.1f} veh/h below minimum {min_total_demand}", row_count, total_demand
        
        return True, "Valid", row_count, total_demand
    except Exception as e:
        return False, f"Error parsing CSV: {e}", 0, 0.0


def batch_generate_demands(
    dates_file: Path,
    output_dir: Path,
    knotenpunkt: str = "AS Freiburg-Nord",
    fahrtrichtung: str = "Karlsruhe",
    aggregation: int = 5,
    smooth_breakdowns: bool = False,
    force: bool = False,
):
    """Batch-generates full-day demand CSVs for all dates in dates_file."""
    dates = read_dates(dates_file)
    output_dir.mkdir(parents=True, exist_ok=True)
    
    print(f"============================================================")
    print(f"Batch Demand Generator")
    print(f"Dates file:      {dates_file.resolve()} ({len(dates)} date(s))")
    print(f"Output folder:   {output_dir.resolve()}")
    print(f"Junction:        {knotenpunkt} ({fahrtrichtung})")
    print(f"Aggregation:     {aggregation} min")
    print(f"Smoothing:       {'Enabled' if smooth_breakdowns else 'Disabled (raw detector flows)'}")
    print(f"Force overwrite: {force}")
    print(f"============================================================\n")
    
    results = []
    
    for idx, date in enumerate(dates, 1):
        target_csv = output_dir / f"demand_{date}.csv"
        print(f"[{idx}/{len(dates)}] Processing date: {date}")
        
        if not force and target_csv.is_file():
            is_valid, reason, rows, total_demand = validate_demand_csv(target_csv)
            if is_valid:
                print(f"  -> CACHED: {target_csv.name} already exists and is valid ({rows} rows, {total_demand:,.1f} sum veh/h). Skipping.")
                results.append({
                    "date": date,
                    "status": "CACHED",
                    "file": target_csv.name,
                    "rows": rows,
                    "total_demand": total_demand,
                    "details": "Existing file passed validation"
                })
                continue
            else:
                print(f"  -> Invalid existing file ({reason}). Regenerating...")
        
        start_dt = f"{date} 00:00:00"
        end_dt = f"{date} 23:55:00"
        
        try:
            prepare_demand(
                knotenpunkt=knotenpunkt,
                fahrtrichtung=fahrtrichtung,
                start_date=start_dt,
                end_date=end_dt,
                aggregation=aggregation,
                output_file=str(target_csv),
                smooth_breakdowns=smooth_breakdowns,
            )
            
            is_valid, reason, rows, total_demand = validate_demand_csv(target_csv)
            if is_valid:
                print(f"  -> SUCCESS: Generated {target_csv.name} ({rows} rows, {total_demand:,.1f} sum veh/h)")
                results.append({
                    "date": date,
                    "status": "SUCCESS",
                    "file": target_csv.name,
                    "rows": rows,
                    "total_demand": total_demand,
                    "details": "Generated and validated"
                })
            else:
                print(f"  -> FAILED VALIDATION: {target_csv.name} ({reason})")
                results.append({
                    "date": date,
                    "status": "INVALID",
                    "file": target_csv.name,
                    "rows": rows,
                    "total_demand": total_demand,
                    "details": reason
                })
        except Exception as e:
            print(f"  -> ERROR generating {date}: {e}")
            results.append({
                "date": date,
                "status": "FAILED",
                "file": target_csv.name,
                "rows": 0,
                "total_demand": 0.0,
                "details": str(e)
            })
        print()

    # Print final summary table
    print("\n" + "=" * 80)
    print(f"{'Date':<12} | {'Status':<8} | {'Rows':<6} | {'Total Flow (veh/h)':<20} | {'Details'}")
    print("-" * 80)
    success_cnt = 0
    cached_cnt = 0
    fail_cnt = 0
    
    for r in results:
        status = r["status"]
        if status == "SUCCESS":
            success_cnt += 1
        elif status == "CACHED":
            cached_cnt += 1
        else:
            fail_cnt += 1
        print(f"{r['date']:<12} | {status:<8} | {r['rows']:<6} | {r['total_demand']:>18,.1f} | {r['details']}")
    
    print("=" * 80)
    print(f"Summary: {success_cnt} newly generated, {cached_cnt} cached/skipped, {fail_cnt} failed (Total: {len(dates)})")
    print("=" * 80)
    
    if fail_cnt > 0:
        sys.exit(1)


def main():
    parser = argparse.ArgumentParser(description="Batch-generate full-day demand CSVs for study dates.")
    parser.add_argument(
        "--dates",
        type=Path,
        default=REPO_ROOT / "cluster" / "dates.txt",
        help="Path to dates.txt file (default: cluster/dates.txt)",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=REPO_ROOT / "cluster" / "demand",
        help="Target folder for demand CSVs (default: cluster/demand)",
    )
    parser.add_argument(
        "--knotenpunkt",
        type=str,
        default="AS Freiburg-Nord",
        help="Junction name in database (default: 'AS Freiburg-Nord')",
    )
    parser.add_argument(
        "--fahrtrichtung",
        type=str,
        default="Karlsruhe",
        help="Travel direction (default: 'Karlsruhe')",
    )
    parser.add_argument(
        "--aggregation",
        type=int,
        default=5,
        help="Aggregation interval in minutes (default: 5)",
    )
    parser.add_argument(
        "--smooth",
        dest="smooth_breakdowns",
        action="store_true",
        help="Enable breakdown smoothing (default: disabled / raw flows)",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Force regeneration even if valid demand CSV already exists",
    )
    
    args = parser.parse_args()
    batch_generate_demands(
        dates_file=args.dates,
        output_dir=args.output_dir,
        knotenpunkt=args.knotenpunkt,
        fahrtrichtung=args.fahrtrichtung,
        aggregation=args.aggregation,
        smooth_breakdowns=args.smooth_breakdowns,
        force=args.force,
    )


if __name__ == "__main__":
    main()
