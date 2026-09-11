# vgain-grid-1 — cluster run of the speed-gain grid

**Prepared, not submitted.** Every command below is meant to be run on bwUniCluster 3.0 by hand.

| | |
|:---|:---|
| Tag | `vgain-grid-1` (annotated; tag object `db9e7ce88f30b455cd3177277d8ed619772e57d6`) |
| Commit | `abe0b4095925c696c3d57de4ca050c2aeb357ec0` |
| Study | `vgaingrid` — `VGainGridStudy` |
| Runs | **90** = 2 dates × 9 cells × 5 replications |
| Array | **45** tasks, two runs each → `--array=0-44` |

This file was committed *after* the tag, so it is not part of the tagged checkout. Read it from
`decoupling_phase05` or from here; run everything from the checkout of the tag.

## What runs

Car speed gain crossed with truck speed gain. Everything else is the production set.

| car ↓ / truck → | 30 km/h | 60 km/h | 108 km/h (30 m/s) |
|:---|:---|:---|:---|
| **15 km/h** | **`intended`** | `car15_truck60` | `car15_truck108` |
| **30 km/h** | `car30_truck30` | `car30_truck60` | `car30_truck108` |
| **54 km/h (15 m/s)** | `car54_truck30` | `car54_truck60` | **`published`** |

- `intended` resolves to the production study's `production` variant, and `published` to its `legacy`
  variant, in every parameter. Every pair of cells differs only in `car.VGAIN` and `truck.VGAIN`.
  This was checked with the resolved-parameter comparison of
  `docs/decoupling/bc4-and-reference-check.md`, on both dates.
- Dates: **2025-10-27** (congested) and **2025-09-22** (free flow), each simulated 13:00–22:00.
- Replication seeds: `42`, `1000045`, `2000048`, `3000051`, `4000054`. They are the same in every cell
  and on both dates.
- Run index = `45·d + 5·c + r`:
  - `d` is 0 for 2025-10-27 and 1 for 2025-09-22;
  - `c` is the cell's position reading the table row by row (`intended` = 0 … `published` = 8);
  - `r` is the replication, 0–4.

## 1. Workspace and clone at the tag

```bash
ws_allocate mirova <days>            # only if the workspace does not exist yet; check with ws_list
export MIROVA_WORKSPACE=mirova
WS="$(ws_find mirova)"
cd "$WS"
git clone --branch vgain-grid-1 https://github.com/baumarv/opentrafficsim.git vgain-grid-1
cd vgain-grid-1
git rev-parse HEAD
git describe --tags
```

Expected: `abe0b4095925c696c3d57de4ca050c2aeb357ec0` and `vgain-grid-1`. Git also notes that it is in
"detached HEAD" state, which is what a tag checkout is.

It is a separate directory, so an existing checkout and its build are left alone.

## 2. Build

```bash
export MIROVA_WORKSPACE=mirova
export MIROVA_CP_FILE="$WS/cp-vgain-grid-1.txt"
./cluster/build_for_cluster.sh
ls -l "$WS/demand/demand_2025-10-27.csv" "$WS/demand/demand_2025-09-22.csv"
```

`MIROVA_CP_FILE` gives this campaign its own classpath file. Without it, the build would overwrite
`$WS/cp.txt`, which a campaign from another checkout may still be reading.

Expected:

- Step `[2/4]` runs `mvn clean install -pl ots-demo -am …` and ends in `BUILD SUCCESS`. The build is
  always clean, and it has to run online.
- `Copied <n> demand CSV(s) to <ws>/demand`.
- The last line is `Done. Classpath written to <ws>/cp-vgain-grid-1.txt (<bytes> bytes).`
- `ls` lists both demand files. Without them every run aborts, because the study runs with
  `--strict=true`.

## 3. Count and manifest (runs nothing)

```bash
source cluster/mirova_env.sh
activate_toolchain "$WS"
java -cp "$(cat "$MIROVA_CP_FILE")" \
  org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.RunMirovaClusterStudy \
  --study=vgaingrid --output="$WS/output/vgain-grid-1" \
  --demand="$WS/demand" --strict=true --replications=5 \
  --manifest="$WS/vgain-grid-1-manifest.tsv" --count
awk -F'\t' 'NR>4 {print $2}' "$WS/vgain-grid-1-manifest.tsv" | sort | uniq -c
```

Expected:

- The last line of the count is `90`, and stderr says `Wrote manifest with 90 runs to …`.
- `awk` prints 18 lines, each `5 FreiburgNord_<date>_13-00_to_22-00_<cell>`: nine cells on each of the
  two dates.

## 4. Submit

```bash
export MIROVA_WORKSPACE=mirova
export MIROVA_CLUSTER_DIR="$PWD/cluster"
export MIROVA_CP_FILE="$WS/cp-vgain-grid-1.txt"
export MIROVA_STUDY=vgaingrid
export MIROVA_OUTPUT_ROOT="$WS/output/vgain-grid-1"
export MIROVA_STUDY_OPTS="--demand=$WS/demand --strict=true --replications=5"
sbatch --chdir="$WS" --array=0-44 cluster/run_mirova.sbatch
```

Expected: `Submitted batch job <jobid>`.

**`MIROVA_STUDY_OPTS` must be set.** The script's default passes `--replications=20`, which this study
honours. The array would then address only the first 90 of 360 runs, and they would not be the design
above.

Resources are the script's defaults: `--time=02:00:00`, `--mem-per-cpu=8G`, heap `6g` per run. For
scale: one run of 2025-09-22 over the same 13:00–22:00 window took 453 s on the workstation.

## 5. Expected output

### Per array task: `$WS/logs/mirova_<jobid>_<T>.out`

```
Compilation stub check: <k> class directories on the classpath, none carries a stub.
==========================================================
Array task <T> on <node>
Workspace:   <ws>
Study:       vgaingrid (90 runs total)
Study opts:  --demand=<ws>/demand --strict=true --replications=5
Cores:       2 (affinity: <cpu> <cpu>)
Output:      <ws>/output/vgain-grid-1
==========================================================
[slot 0] run <2T> pinned to CPU <cpu> -> <ws>/logs/mirova_<jobid>_<T>_run<2T>.log
[slot 1] run <2T+1> pinned to CPU <cpu> -> <ws>/logs/mirova_<jobid>_<T>_run<2T+1>.log
Run <2T> finished successfully (log: …).
Run <2T+1> finished successfully (log: …).
Array task <T> finished with exit code 0 (runs: <2T> <2T+1>).
```

If `taskset` is unavailable, the slot lines read `unpinned (OS scheduler)` instead.

If the first line is `ERROR: <n> class file(s) contain an Eclipse 'Unresolved compilation problem' stub`
instead, the task exits `2` without starting a run. Rebuild (step 2) and resubmit the failed indices.

### Per run: `$WS/logs/mirova_<jobid>_<T>_run<n>.log`

It ends with:

```
[PROGRESS] 1/1 simulations completed (100%, 0 failed)
Execution finished. Shutting down.
```

After that line the JVM exits by itself: `RunMirovaClusterStudy` ends the process, so a finished run
does not hold its task until the walltime.

### Results

```
<ws>/output/vgain-grid-1/FreiburgNord_<date>_13-00_to_22-00_<cell>/variation_0/runParams.txt
<ws>/output/vgain-grid-1/FreiburgNord_<date>_13-00_to_22-00_<cell>/variation_0/run_seed_<seed>/
    detector_periodic.csv.zip
    detector_positions.csv.zip
    diffused_vehicles.csv
    sampler_RoadSampler_[samplingInterval=null].csv.zip
```

- 18 scenario folders, five `run_seed_*` folders each: 90 in total.
- `runParams.txt` names the cell, in `grid.cell=<cell>`, `grid.carVGainKmh=<15.0|30.0|54.0>` and
  `grid.truckVGainKmh=<30.0|60.0|108.0>`, next to the `car.VGAIN` / `truck.VGAIN` it ran with.

### Completion check

```bash
sacct -j <jobid> --format=JobID%20,State,ExitCode,Elapsed | grep -v '\.'
find "$WS/output/vgain-grid-1" -mindepth 3 -maxdepth 3 -type d -name 'run_seed_*' | wc -l
grep -L "Execution finished. Shutting down." "$WS"/logs/mirova_<jobid>_*_run*.log
```

Expected:

- `sacct` shows 45 tasks, `COMPLETED` with exit code `0:0`.
- `find` prints `90`.
- `grep -L` prints nothing, because every run log reached its last line.

To rerun individual tasks, name them: `sbatch --chdir="$WS" --array=3,17 cluster/run_mirova.sbatch`,
with the same exports as in step 4.
