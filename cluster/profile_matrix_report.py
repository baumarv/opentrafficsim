#!/usr/bin/env python3
"""
profile_matrix_report.py
========================
Consolidates the four JFR text dumps produced by ``profile_matrix.sh`` into a single
comparison table, so the matrix is read as one result rather than as four reports to
cross-reference by hand.

Usage::

    python3 cluster/profile_matrix_report.py <result directory>

Self-contained on purpose: it runs on the cluster node right after the matrix, where the
diss_mvb evaluation package is not available. The richer, reusable version of this analysis
lives in ``diss_mvb/scripts/simulation/ots/profiling``.

Copyright (c) 2026 Marvin Baumann / KIT.
"""

import os
import re
import sys
from collections import Counter

FRAME = re.compile(r"^\s{4}([\w$.<>\[\]]+\.[\w$<>]+)\(")
UNITS = {"bytes": 1, "kB": 1024, "MB": 1024 ** 2, "GB": 1024 ** 3}

CELLS = [
    ("A", "stock   / CACHING=true"),
    ("B", "stock   / CACHING=false"),
    ("C", "patched / CACHING=true"),
    ("D", "patched / CACHING=false"),
]

DOUBLE_SCALAR = "org.djunits.value.vdouble.scalar.base.DoubleScalar.hashCode"
QUANTITY = "org.djunits.quantity.Quantity.hashCode"
SI_DIMENSIONS = "org.djunits.unit.si.SIDimensions.hashCode"
POSITION = "org.opentrafficsim.road.gtu.lane.LaneBasedGtu.position"


def parse(path, event):
    """Yields (frames, weight_string, object_class) for every main-thread sample."""
    if not os.path.isfile(path):
        return []
    out = []
    inside = False
    frames, thread, weight, obj = [], None, None, None
    with open(path, encoding="utf-8", errors="replace") as handle:
        for line in handle:
            if line.startswith(event + " {"):
                inside, frames, thread, weight, obj = True, [], None, None, None
                continue
            if not inside:
                continue
            stripped = line.strip()
            if stripped.startswith("sampledThread = ") or stripped.startswith("eventThread = "):
                thread = stripped.split('"')[1] if '"' in stripped else None
            elif stripped.startswith("weight = "):
                weight = stripped[len("weight = "):]
            elif stripped.startswith("objectClass = "):
                obj = stripped[len("objectClass = "):]
            elif stripped == "}":
                if frames and thread == "main":
                    out.append((frames, weight, obj))
                inside = False
            else:
                match = FRAME.match(line)
                if match:
                    frames.append(match.group(1))
    return out


def weight_bytes(raw):
    if not raw:
        return 0.0
    match = re.match(r"([\d.]+)\s*(bytes|kB|MB|GB)?", raw.replace(",", "."))
    return float(match.group(1)) * UNITS[match.group(2) or "bytes"] if match else 0.0


def analyse(result_dir, cell):
    """Returns the metrics for one cell, or None if its dumps are missing."""
    execs = parse(os.path.join(result_dir, "%s_exec.txt" % cell), "jdk.ExecutionSample")
    allocs = parse(os.path.join(result_dir, "%s_alloc.txt" % cell), "jdk.ObjectAllocationSample")
    if not execs:
        return None

    n = len(execs)

    def share(predicate):
        return 100.0 * sum(1 for fr, _, _ in execs if any(predicate(f) for f in fr)) / n

    via_parameter = via_position = 0
    for frames, _, _ in execs:
        if DOUBLE_SCALAR not in frames:
            continue
        above = frames[frames.index(DOUBLE_SCALAR) + 1:]
        if any("ParameterType.hashCode" in f for f in above):
            via_parameter += 1
        elif any("RelativePosition.hashCode" in f for f in above):
            via_position += 1

    alloc_total = sum(weight_bytes(w) for _, w, _ in allocs) or 1.0
    linked_key_iterator = sum(weight_bytes(w) for _, w, oc in allocs
                              if oc and "LinkedKeyIterator" in oc)

    return {
        "cpu_samples": n,
        "alloc_samples": len(allocs),
        "double_scalar": share(lambda f: f == DOUBLE_SCALAR),
        "via_parameter": 100.0 * via_parameter / n,
        "via_position": 100.0 * via_position / n,
        "quantity": share(lambda f: f == QUANTITY),
        "si_dimensions": share(lambda f: f == SI_DIMENSIONS),
        "gtu_position": share(lambda f: f.startswith(POSITION)),
        "alloc_mb": alloc_total / 1024 ** 2,
        "linked_key_iterator": 100.0 * linked_key_iterator / alloc_total,
    }


ROWS = [
    ("CPU samples (~ CPU time)", "cpu_samples", "%d"),
    ("DoubleScalar.hashCode", "double_scalar", "%.2f%%"),
    ("  via ParameterType", "via_parameter", "%.2f%%"),
    ("  via RelativePosition", "via_position", "%.2f%%"),
    ("Quantity.hashCode", "quantity", "%.2f%%"),
    ("SIDimensions.hashCode", "si_dimensions", "%.2f%%"),
    ("LaneBasedGtu.position", "gtu_position", "%.2f%%"),
    ("LinkedKeyIterator of allocation", "linked_key_iterator", "%.2f%%"),
    ("sampled allocation (MB)", "alloc_mb", "%.0f"),
]


def main(argv):
    if len(argv) != 2:
        print(__doc__.strip())
        return 2
    result_dir = argv[1]

    results = {cell: analyse(result_dir, cell) for cell, _ in CELLS}
    present = [c for c, _ in CELLS if results[c]]
    if not present:
        print("No usable JFR dumps found in %s" % result_dir)
        return 1

    width = 34
    print("%-*s %14s %14s %14s %14s" % (width, "", "A", "B", "C", "D"))
    print("%-*s %14s %14s %14s %14s"
          % (width, "", "stock/on", "stock/off", "patch/on", "patch/off"))
    print("-" * (width + 4 * 15))
    for label, key, fmt in ROWS:
        cells = []
        for cell, _ in CELLS:
            cells.append(fmt % results[cell][key] if results[cell] else "-")
        print("%-*s %14s %14s %14s %14s" % (width, label, *cells))

    baseline = results.get("A")
    if baseline and baseline["cpu_samples"]:
        print()
        print("CPU time relative to A (lower is better; sample count is proportional to CPU time):")
        for cell, description in CELLS:
            if not results[cell]:
                continue
            ratio = 100.0 * results[cell]["cpu_samples"] / baseline["cpu_samples"]
            print("  %s  %-26s %6.1f%%" % (cell, description, ratio))

    print()
    print("Read the interaction, not the columns in isolation: the djunits patch makes the hash")
    print("that the position cache protects against cheap, so B and C are not independent effects")
    print("and D is not their sum. If D is close to C, the position cache is no longer worth its")
    print("maintenance cost once the patch is in; if D is worse than C, the cache is paying off")
    print("again precisely because the hash got cheap.")
    print()
    print("Single run per cell, so small differences are noise. Treat anything under a couple of")
    print("percentage points as not measured.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
