"""
What did each performance defect cost? Wall-clock measurements of the MiRoVA/OTS stack, one cell per code state
and switch setting, strictly one run at a time. Answers questions 1-3 of docs/mirova/performance_improvements.md:
DJUnits hashing, parameter access, the GTU position cache. Question 4 (TaMA) is tools/perf/planners_live.sh.

RunFreiburgMergeWatch, seed 42, 2025-10-13 13:00, 60 simulated minutes: the protocol P6 was measured with. Cells
cross the code state (before P6, after each of its three stages) with LaneBasedGtu.CACHING and with a DJUnits
build that caches its scalar hash codes. Every cell must produce byte-identical traffic; the script checks that
and refuses the result otherwise. Because only the code under test differs, model changes made later on main
cannot enter the comparison.

The patched DJUnits is the jar the August experiment built (5.2.1-mirova-patched, in the local ~/.m2). It is put
on the classpath in place of the stock jar for the cells that ask for it; no pom and no installation changes.

Timing: from the first "[SIM " line to "[OUTPUT] Writing sampler output", i.e. the simulation loop without
network construction, demand preparation and output writing. One discarded warm-up run per cell, then the
measured repetitions, the cell order rotated every repetition so that drift in machine load and position in the
sequence hit every cell equally.

Usage:
    python tools/perf/time_cells.py --out <dir> --tree pre=../ots-perf-pre --tree s1=../ots-perf-s1 \
           --tree s2=../ots-perf-s2 --tree post=../ots-perf-post [--reps 5] [--cells a,b] [--minutes 60]

Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved.
BSD-style license. See OpenTrafficSim License.
"""

import argparse
import csv
import hashlib
import math
import os
import shutil
import statistics
import subprocess
import sys
import threading
import time
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
M2 = Path(os.environ.get("M2_REPO", Path.home() / ".m2" / "repository"))
DJUNITS_STOCK = "djunits-5.2.1.jar"
DJUNITS_PATCHED = M2 / "org/djunits/djunits/5.2.1-mirova-patched/djunits-5.2.1-mirova-patched.jar"

MERGE_WATCH = "org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.RunFreiburgMergeWatch"
START_MARKER = "[SIM "
END_MARKER = "[OUTPUT] Writing sampler output"
# RunFreiburgMergeWatch leaves its JVM alive after a headless run (AWT threads). The run is over when this line
# has appeared and the process has been quiet for a moment.
LAST_OUTPUT_MARKER = "wrote detector positions"
QUIET_AFTER_LAST_S = 3.0
RUN_TIMEOUT_S = 3600
IDENTITY_FILES = ("detector_periodic.csv", "detector_positions.csv")
CONGESTED_KMH = 60.0

# Tree name -> the commit the worktree must be at.
TREES = {"pre": "826a70ae8~1", "s1": "826a70ae8", "s2": "aa830ed4d", "post": "8df187cf3"}


def die(msg):
    """End the script. A precondition that fails is not a warning."""
    print(f"[perf] REFUSING: {msg}", file=sys.stderr)
    sys.exit(1)


class Cell:
    """One configuration: which code state, and the two switches."""

    def __init__(self, name, tree, caching, patched):
        self.name, self.tree, self.caching, self.patched = name, tree, caching, patched


def attribution_cells():
    c = [Cell(f"{t}-cacheOff", t, False, False) for t in ("pre", "s1", "s2", "post")]
    c += [Cell("pre-cacheOn", "pre", True, False), Cell("post-cacheOn", "post", True, False),
          Cell("pre-cacheOn-patched", "pre", True, True), Cell("pre-cacheOff-patched", "pre", False, True),
          Cell("post-cacheOff-patched", "post", False, True)]
    return c


# ---------------------------------------------------------------------------------------------------------------
# classpath
# ---------------------------------------------------------------------------------------------------------------

def dependency_classpath(tree: Path) -> str:
    cp_file = tree / "cp_perf.txt"
    if not cp_file.exists() or cp_file.stat().st_size == 0:
        print(f"[perf] resolving dependency classpath for {tree.name}", flush=True)
        cmd = ["mvn", "-o", "-q", "-pl", "ots-demo", "dependency:build-classpath",
               f"-Dmdep.outputFile={cp_file}", "-Dmdep.includeScope=runtime"]
        r = subprocess.run(cmd, cwd=tree, shell=(os.name == "nt"))
        if r.returncode != 0 or not cp_file.exists():
            die(f"could not resolve the classpath in {tree}")
    return cp_file.read_text().strip()


def module_classpath(tree: Path) -> list:
    """The worktree's own classes first, ots-xml first of all (JAXB episode, see PROFILING.md).

    dependency:build-classpath resolves the ots-* modules to the jars installed in ~/.m2, which are the same for
    every tree; without the module classes in front, every cell would run the same code and trivially agree.
    """
    modules = ["ots-xml"] + sorted(p.parent.parent.name for p in tree.glob("ots-*/target/classes")
                                   if p.parent.parent.name != "ots-xml")
    dirs = [tree / m / "target" / "classes" for m in modules]
    if not all(d.is_dir() for d in dirs) or not (tree / "ots-demo/target/classes").is_dir():
        die(f"{tree} is not compiled")
    stubs = [p for d in dirs for p in d.rglob("*.class") if b"Unresolved compilation" in p.read_bytes()]
    if stubs:
        die(f"{len(stubs)} class file(s) in {tree} carry an IDE compilation stub, e.g. {stubs[0]}")
    return [str(d) for d in dirs]


def classpath(tree: Path, patched: bool) -> str:
    entries = module_classpath(tree) + dependency_classpath(tree).split(os.pathsep)
    stock = [e for e in entries if e.endswith(DJUNITS_STOCK)]
    if len(stock) != 1:
        die(f"expected exactly one {DJUNITS_STOCK} on the classpath of {tree}, found {len(stock)}")
    if patched:
        if not DJUNITS_PATCHED.exists():
            die(f"the patched DJUnits jar is not at {DJUNITS_PATCHED}")
        entries = [str(DJUNITS_PATCHED) if e == stock[0] else e for e in entries]
    return os.pathsep.join(entries)


# ---------------------------------------------------------------------------------------------------------------
# one run
# ---------------------------------------------------------------------------------------------------------------

def command(cell, cp, run_dir, minutes):
    return ["java", "-Xmx6g", "-XX:ActiveProcessorCount=1", "-Djava.awt.headless=true",
            "-Duser.language=en", "-Duser.country=US",
            f"-Dmirova.gtuPositionCaching={'true' if cell.caching else 'false'}",
            "-Dmirova.gui=false", "-Dmirova.seed=42", f"-Dmirova.minutes={minutes}",
            "-Dmirova.demandStart=2025-10-13 13:00:00", "-Dmirova.demandEnd=2025-10-13 14:00:00",
            f"-Dmirova.outputDir={run_dir}", "-cp", cp, MERGE_WATCH]


def run_once(cmd, cwd, run_dir):
    """Returns (loop seconds, total seconds)."""
    run_dir.mkdir(parents=True, exist_ok=True)
    log_path = run_dir / "run.log"
    t0 = time.perf_counter()
    marks, last_line_at = {}, [t0]
    with open(log_path, "w", encoding="utf-8") as log:
        proc = subprocess.Popen(cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, encoding="utf-8", errors="replace", bufsize=1)

        def reader():
            for line in proc.stdout:
                now = time.perf_counter()
                last_line_at[0] = now
                log.write(f"{now - t0:10.3f} {line}")
                for key, marker in (("start", START_MARKER), ("end", END_MARKER), ("last", LAST_OUTPUT_MARKER)):
                    if key not in marks and marker in line:
                        marks[key] = now

        th = threading.Thread(target=reader, daemon=True)
        th.start()
        while proc.poll() is None:
            time.sleep(0.2)
            now = time.perf_counter()
            if "last" in marks and now - last_line_at[0] > QUIET_AFTER_LAST_S:
                proc.kill()
            elif now - t0 > RUN_TIMEOUT_S:
                proc.kill()
                proc.wait()
                die(f"run timed out after {RUN_TIMEOUT_S} s: {log_path}")
        th.join(10)
    if "last" not in marks:
        die(f"run ended (exit {proc.returncode}) before writing its detector output; see {log_path}")
    if "start" not in marks or "end" not in marks:
        die(f"run did not print both timing markers; see {log_path}")
    return marks["end"] - marks["start"], marks["end"] - t0


def find_output(run_dir: Path, name: str):
    hits = sorted(run_dir.rglob(name + ".zip")) or sorted(run_dir.rglob(name))
    return hits[0] if hits else None


def read_output(path: Path) -> bytes:
    if path.suffix == ".zip":
        with zipfile.ZipFile(path) as z:
            return b"".join(z.read(n) for n in sorted(z.namelist()))
    return path.read_bytes()


def output_digest(run_dir: Path) -> str:
    h = hashlib.sha256()
    for name in IDENTITY_FILES:
        p = find_output(run_dir, name)
        if p is None:
            return f"missing:{name}"
        h.update(name.encode() + b"\0" + read_output(p))
    return h.hexdigest()[:16]


def traffic(run_dir: Path):
    """Vehicles past det_L3a and congested lane-minutes there (harmonic speed below 60 km/h).

    Only the all-types rows count: the file also carries one row per GTU type for every interval, and summing
    those as well counts every vehicle twice (the stage 11 figures of 6 422 vehicles did exactly that).
    """
    p = find_output(run_dir, "detector_periodic.csv")
    if p is None:
        return math.nan, math.nan
    if p.suffix == ".zip":
        with zipfile.ZipFile(p) as z:
            text = z.read("detector_periodic.csv").decode()
    else:
        text = p.read_text()
    rows = [r for r in csv.DictReader(text.splitlines())
            if r["id"].startswith("det_L3a.") and r["GTU type"] == "" and r["q"] != "NaN"]
    if len(rows) < 2:
        return math.nan, math.nan
    ts = sorted({float(r["t"]) for r in rows})
    dt = min(b - a for a, b in zip(ts, ts[1:]) if b > a) if len(ts) > 1 else 60.0
    vehicles = sum(float(r["q"]) * dt / 3600.0 for r in rows)
    congested = sum(dt / 60.0 for r in rows if float(r["vHarm"]) < CONGESTED_KMH)
    return round(vehicles), round(congested, 1)


# ---------------------------------------------------------------------------------------------------------------
# summary
# ---------------------------------------------------------------------------------------------------------------

CONTRASTS = [
    ("Q3 position cache, original code", "pre-cacheOff", "pre-cacheOn"),
    ("Q3 position cache, today's code", "post-cacheOff", "post-cacheOn"),
    ("Q2 stage 1: ParameterType hash + varargs", "s1-cacheOff", "pre-cacheOff"),
    ("Q2 stage 2: parameter snapshot", "s2-cacheOff", "s1-cacheOff"),
    ("Q2 stages 1+2: parameter access", "s2-cacheOff", "pre-cacheOff"),
    ("stage 3: DJUnits allocations (not parameter access)", "post-cacheOff", "s2-cacheOff"),
    ("P6 all three stages (the ledger entry)", "post-cacheOff", "pre-cacheOff"),
    ("Q1 DJUnits hash caching, original code", "pre-cacheOn-patched", "pre-cacheOn"),
    ("Q1 DJUnits hash caching, original code, cache off", "pre-cacheOff-patched", "pre-cacheOff"),
    ("Q1 DJUnits hash caching, what is left today", "post-cacheOff-patched", "post-cacheOff"),
    ("everything: original -> today", "post-cacheOff", "pre-cacheOn"),
    ("today vs original with only the DJUnits patch", "post-cacheOff", "pre-cacheOn-patched"),
]


def contrast(by, faster, slower, label):
    if faster not in by or slower not in by:
        return None
    f, s = by[faster], by[slower]
    ratio = statistics.median(f) / statistics.median(s)
    if min(len(f), len(s)) < 3:
        overlap = "n too small"
    else:
        overlap = "no overlap" if max(f) < min(s) else ("reversed, no overlap" if min(f) > max(s) else "OVERLAP")
    return (f"| {label} | `{slower}` -> `{faster}` | {100 * ratio:.1f} % | {100 * (1 - ratio):+.1f} % | "
            f"x{1 / ratio:.2f} | {overlap} |")


def summarise(rows, cells, out: Path, header):
    by = {}
    for r in rows:
        if r["measured"]:
            by.setdefault(r["cell"], []).append(r["loop_s"])
    digests = {c.name: sorted({r["digest"] for r in rows if r["cell"] == c.name}) for c in cells}
    flat = {d for ds in digests.values() for d in ds}
    valid = len(flat) == 1 and not any(d.startswith("missing") for d in flat)

    lines = ["# Timing: what each defect cost", "", header, "", "## Traffic identity", "",
             f"{'Identical in every run of every cell' if valid else '**DIFFERENT - invalid**'}: "
             f"{', '.join(f'{n}: {ds}' for n, ds in digests.items())}",
             "", "## Per cell (simulation loop, seconds)", "",
             "| cell | n | min | median | max | vehicles det_L3a | congested lane-min |",
             "|---|---|---|---|---|---|---|"]
    for c in cells:
        if c.name not in by:
            continue
        v = by[c.name]
        tr = next((r for r in rows if r["cell"] == c.name and r["measured"]), {})
        lines.append(f"| {c.name} | {len(v)} | {min(v):.2f} | {statistics.median(v):.2f} | {max(v):.2f} | "
                     f"{tr.get('vehicles', '')} | {tr.get('congested_min', '')} |")
    lines += ["", "## Contrasts", "",
              "| question | slower -> faster | time left | saved | speed-up | ranges |", "|---|---|---|---|---|---|"]
    for label, faster, slower in CONTRASTS:
        line = contrast(by, faster, slower, label)
        if line:
            lines.append(line)
    text = "\n".join(lines) + "\n"
    (out / "summary.md").write_text(text, encoding="utf-8")
    print(text, flush=True)
    if not valid:
        die("traffic differs between runs; the timings above are not a performance result")


# ---------------------------------------------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--tree", action="append", default=[], help="name=path, name one of " + ", ".join(TREES))
    ap.add_argument("--reps", type=int, default=5, help="measured repetitions per cell")
    ap.add_argument("--warmup", type=int, default=1, help="discarded runs per cell before measuring")
    ap.add_argument("--cells", default=None, help="comma-separated subset of the cells")
    ap.add_argument("--minutes", type=float, default=60.0, help="simulated minutes; 60 is the protocol")
    ap.add_argument("--demand-cache", type=Path, default=REPO / ".demand_cache")
    args = ap.parse_args()

    cells = attribution_cells()
    if args.cells:
        wanted = args.cells.split(",")
        unknown = [w for w in wanted if w not in {c.name for c in cells}]
        if unknown:
            die(f"unknown cells {unknown}")
        cells = [c for c in cells if c.name in wanted]

    trees = {}
    for spec in args.tree:
        name, _, path = spec.partition("=")
        if name not in TREES:
            die(f"unknown tree name {name}")
        trees[name] = Path(path).resolve()
    for name in {c.tree for c in cells}:
        if name not in trees:
            die(f"--tree {name}=<worktree at {TREES[name]}> is needed")
        tree = trees[name]
        head = subprocess.run(["git", "-C", str(tree), "rev-parse", "HEAD"], capture_output=True, text=True)
        want = subprocess.run(["git", "-C", str(tree), "rev-parse", TREES[name]], capture_output=True, text=True)
        if head.returncode != 0 or want.returncode != 0 or head.stdout.strip() != want.stdout.strip():
            die(f"{tree} is not at {TREES[name]}")
        dirty = subprocess.run(["git", "-C", str(tree), "status", "--porcelain", "--untracked-files=no"],
                               capture_output=True, text=True).stdout.strip()
        if dirty:
            die(f"{tree} has local changes to tracked files:\n{dirty}")

    cps = {c.name: classpath(trees[c.tree], c.patched) for c in cells}

    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    # RunFreiburgMergeWatch looks for its demand cache three levels above the run directory: <out>/runs/<cell>/<rep>.
    if args.demand_cache.is_dir() and not (out / ".demand_cache").exists():
        shutil.copytree(args.demand_cache, out / ".demand_cache")

    header = (f"Commits: {', '.join(f'{n}={TREES[n]}' for n in sorted({c.tree for c in cells}))}. "
              f"RunFreiburgMergeWatch, seed 42, 2025-10-13 13:00, {args.minutes:g} min. "
              f"{args.reps} measured repetitions per cell after {args.warmup} warm-up, order rotated, "
              f"one run at a time.")
    rows = []
    for rep in range(args.warmup + args.reps):
        k = rep % len(cells)
        order = cells[k:] + cells[:k]
        for pos, cell in enumerate(order):
            run_dir = out / "runs" / cell.name / f"rep{rep}"
            if run_dir.exists():
                shutil.rmtree(run_dir)
            loop_s, total_s = run_once(command(cell, cps[cell.name], run_dir, args.minutes), trees[cell.tree],
                                       run_dir)
            vehicles, congested = traffic(run_dir)
            row = {"rep": rep, "pos": pos, "cell": cell.name, "measured": rep >= args.warmup,
                   "loop_s": round(loop_s, 3), "total_s": round(total_s, 3), "digest": output_digest(run_dir),
                   "vehicles": vehicles, "congested_min": congested}
            rows.append(row)
            print(f"[perf] rep {rep}{' (warm-up)' if rep < args.warmup else ''} {cell.name:22s} "
                  f"loop {loop_s:8.2f} s  out {row['digest']}", flush=True)
            with open(out / "timings.csv", "w", newline="") as f:
                w = csv.DictWriter(f, fieldnames=list(row))
                w.writeheader()
                w.writerows(rows)
            # The trajectories are not needed for timing; keep a long matrix's disk footprint bounded.
            for p in run_dir.rglob("sampler_*"):
                p.unlink()
    summarise(rows, cells, out, header)


if __name__ == "__main__":
    main()
