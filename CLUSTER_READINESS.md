# Cluster-run readiness

What was checked before a production run on bwUniCluster, what differs from the old fork's cluster output, and the
commands for a real test submission. Nothing was submitted.

## 1. The cluster code path, run locally

`RunMirovaClusterStudy` with the arguments `run_mirova.sbatch` generates, started by `run_local_parallel.sh`, which
passes the same JVM options as the batch script:

```
java -Xmx6g -XX:ActiveProcessorCount=1 -XX:-OmitStackTraceInFastThrow -Duser.language=en -Duser.country=US
     -Dfile.encoding=UTF-8 -Dmirova.gitCommit=<commit> -Dmirova.upstreamCommit=<commit>
     ...RunMirovaClusterStudy --study=dates --output=<dir> --index=<0|1>
     --dates=2025-09-22 --demand=<dir> --strict=true --replications=2 --model=lmrs
```

| | Seed 42 (index 0) | Seed 1000045 (index 1) |
|---|---|---|
| Status | finished | finished |
| Wall clock / process CPU | 224.5 s / 240.0 s | 143.7 s / 159.4 s |
| Trajectory rows (link `L4a`), sampled per plan step at the time | 685 042, 19 095 vehicles | 346 898, 19 275 vehicles |
| of these on lane `Ramp` | 156 690 | 21 030 |
| Detector rows | 8 103 | 8 103 |
| Vehicles removed (`gtus.removed`) | 55 | 78 |
| `git.dirty` | false | false |

The two seeds differ strongly in the number of trajectory rows on the ramp lane, i.e. in how much the ramp queues.
Both ran on a workstation under load from other work; the times are an order of magnitude, not a benchmark.

## 2. Output against the old fork's cluster output

Compared with `mirova/output/ots/final_v3/FreiburgNord_2025-09-22_13-00_to_22-00_final/variation_0/run_seed_42`
(MiRoVA, run on the cluster): structure and columns, not values.

### Directory structure

| Level | Old | New |
|---|---|---|
| Output root | `<root>/` | same |
| Scenario | `FreiburgNord_<date>_13-00_to_22-00_final` | `FreiburgNord_<date>_13-00_to_22-00`. The suffix was the label of the old study (`final`); `DateStudy` has none. The date pattern the pipeline reads (`\d{4}-\d{2}-\d{2}`, `_to_22-00_`) is unchanged. |
| Variation | `variation_0/` with `runParams.txt` | same |
| Run | `run_seed_<seed>/` | same; seeds are 42 + k · 1 000 003 as before |
| Logs | `<workspace>/logs/mirova_<job>_<task>_run<index>.log` | same |

### Files in a run folder

| File | Old | New | Difference |
|---|---|---|---|
| `sampler_RoadSampler_[…].csv.zip` | `[samplingInterval=null]` | `[samplingInterval=0.20000000_s]` | **renamed**; same two members (`.csv`, `.csv.header`), renamed the same way |
| `detector_periodic.csv.zip` | yes | yes | same name, same two members (`.csv`, `.csvm`) |
| `detector_positions.csv.zip` | yes | yes | same |
| `diffused_vehicles.csv` | yes | yes | same header; never has rows now |
| `run.properties` | not in `final_v3`; written by later fork versions | yes | new content, see `cluster/README.md` |
| `build.txt` | written by later fork versions | no | replaced by `run.properties` |
| `removed_vehicles.csv`, `removed_vehicles_traces.txt` | no | yes | **new files** |

### Trajectories (RoadSampler)

| Property | Old | New |
|---|---|---|
| Sampled link | `L4a`, the merge section with the on-ramp lane | same |
| Lanes | `Lane1`, `Lane2`, `Ramp` | same |
| Columns | `traj#,linkId,laneId,gtuId,t,x,v,a,ActionState,LaneChangeDesireLeft,LaneChangeDesireRight,AccelerationDamping,GtuType,CurrentCFAcceleration` | identical |
| Units in the header file | s, m, m/s, m/s2; desires and damping as `FloatDuration` | identical |
| Sampling step | 0.2 s (MiRoVA plan step) | **0.2 s, fixed interval** (96.8 % exactly 0.200; the rest are rows at lane entry and exit) |
| `ActionState` | MiRoVA state names, `none` | always `none` |
| `LaneChangeDesireLeft/Right`, `AccelerationDamping`, `CurrentCFAcceleration` | numbers | always `NaN` |
| `GtuType` | `NL.CAR`, `NL.TRUCK` | same |

**Sampling.** The first local run of this check sampled per plan step and gave 0.5 s, the LMRS time step. The
sampler now has a fixed 0.2 s interval; each sample is evaluated on the vehicle's operational plan at the sample time,
not interpolated. The simulation is unchanged by it (detector files and removed vehicles identical). The file name
contains the interval and changes accordingly. Seed 42: 1 651 672 rows instead of 685 042, 24.7 MB zipped. Details
in `PORTING_NOTES.md`, "Trajectory sampling".

### Detectors

| Property | Old | New |
|---|---|---|
| Detectors | `det_L3a.Lane1`, `det_L3a.Lane2`, `det_L5a.Lane1`, `det_L5a.Lane2`, `det_L7a.Lane1` | same five |
| Columns | `id,GTU type,t,q,vHarm` | identical |
| Units | s, /h, km/h | identical |
| Aggregation | 60 s, first interval at 0 s, last at 32 340 s | identical |
| Rows | 1 620 per detector (1 623 for `det_L3a.Lane2`), three GTU-type groups (all, car, truck) | identical counts |
| Positions | `L3a` 113.87 / 113.83 m, `L5a` 371.06 / 370.79 m, `L7a` 74.42 m | `L3a` 113.79 / 113.59 m, `L5a` 371.19 / 371.18 m, `L7a` 74.42 m; shifts of 0.08, 0.23, 0.13 and 0.39 m |
| Metadata file | class `…road.network.lane.object.detector.LoopDetector$6` | `…road.network.object.detector.LoopDetector$6` |

`det_L6a`, which the scenario and the pipeline name, existed in neither: the network has no link `L6a`.

### `runParams.txt`

Old: the 27 `car.`/`truck.` overrides and the demand keys. New, for a default run:
`demandSmooth, demandCsv, demandStartDate, seed, demandEndDate, demandAggregation, enableTrajectoryRecording, demandCsvStrict, model, skipDemandPrep`.
A preset or explicit options add `preset`, `lmrs.<setting>`, `car.<parameter>`, `truck.<parameter>`.

## 3. What the Python pipeline would trip over

Read from `diss_mvb/scripts/simulation/ots` (globs, column names, the two `runParams.txt` parsers). I did not run
the pipeline.

| # | Point | Effect |
|---|---|---|
| 1 | **File name `sampler_RoadSampler_[samplingInterval=0.20000000_s].csv.zip`.** | The globs I found (`*sampler_RoadSampler*`) still match. A hard-coded `[samplingInterval=null]`, also as the name of the member inside the zip, does not. About 3 % of the rows lie off the 0.2 s grid (lane entry and exit); code that assumes an exact grid should not. |
| 2 | **`ActionState` is always `none`.** | `analytics/ramp_strategies.py` classifies merges by state name; it will find no strategy. `cli/compare_cells.py` already has a branch for runs without state names (written for TaMA). `dashboards/apps/trajectories.py` shows one state. |
| 3 | **Desire and damping columns are `NaN`.** | Plots of desire and of car-following acceleration are empty; means over these columns are `NaN`. |
| 4 | **No `car.T`, `truck.T`, `car.aRelaxDamping`, … in `runParams.txt`.** | `io/params_parser.py` builds the variation label from them; with none present it falls back to the folder name. No error. |
| 5 | **Scenario folder has no suffix after `_to_22-00`.** | The label "tag" is empty and the fallback label is used. No error. |
| 6 | **`runParams.txt` is split on `,`.** | Unchanged format. A value that contains a comma would break both parsers; none of the keys a cluster run writes has one. (The GUI class puts an `LmrsSettings{…}` object with commas into its parameters, but it writes no `runParams.txt`.) |
| 7 | **New files in the run folder.** | The pipeline opens files by name or by `*sampler_RoadSampler*`; I found no glob over `*.csv` in a run folder, so the two `removed_vehicles` files are not picked up by accident. |
| 8 | **`det_L6a`** is configured in two dashboards. | Absent as before. |
| 9 | **Detector positions** shifted by up to 0.39 m. | Only matters where a position is hard-coded; I found none. |
| 10 | **Trajectory header file encoding.** | Identical to the cluster output with `-Dfile.encoding=UTF-8`, which all runners set. |

## 4. Open before a production run

1. **Sampling** is settled: fixed 0.2 s interval; the file name change has to reach the pipeline.
2. **Removed vehicles**: tens per run, all at the end of the network, see `DELETION_DIAGNOSIS.md`. No effect on
   detectors or trajectories.
3. **Memory and time** are still the fork's values (`--mem-per-cpu=8G`, heap 6 g, `--time=02:00:00`). A full LMRS
   day took 2.4 to 8 minutes of CPU here.

## 5. Test submission on bwUniCluster 3.0

**The workspace `mirova-ots` is shared with the old MiRoVA checkout** (`$WS/opentrafficsim`); the benchmark
checkout lives in `$WS/ots-benchmark`. Every benchmark build and submission must export `MIROVA_CP_FILE` (here
`$WS/cp_benchmark.txt`): the default `$WS/cp.txt` is the MiRoVA checkout's classpath, a benchmark build without the
export overwrites it, and the next MiRoVA job would then run the benchmark build. The build record follows the name
(`cp_benchmark_build.env`). `MIROVA_OUTPUT_ROOT` must not be left at its default `$WS/output/dates` either, which
both checkouts use with the same folder names. Details in `cluster/README.md`, "Sharing the workspace".

One day (2025-09-22), two seeds, one array task with two runs, 30 minutes. Not submitted. The dry run of exactly
this configuration was done locally under Git Bash with a mock workspace and prints the two expected `java`
commands (indices 0 and 1); the commands below themselves have not been run on the cluster.

```bash
# --- every session: workspace, and the export that keeps the MiRoVA checkout's classpath intact ---
export MIROVA_WORKSPACE=mirova-ots
WS="$(ws_find mirova-ots)"
export MIROVA_CP_FILE="$WS/cp_benchmark.txt"

# --- once: checkout (skip if it exists) -------------------------------------------------------------
cd "$WS"
git clone -b ots-upstream-benchmark https://github.com/baumarv/opentrafficsim.git ots-benchmark

# --- build: toolchain, clean install, classpath, build record, demand ---------------------------
cd "$WS/ots-benchmark"
git pull --ff-only
cluster/build_for_cluster.sh
ls -l "$WS/cp_benchmark.txt" "$WS/cp_benchmark_build.env"     # the benchmark's own files; cp.txt untouched

# --- configure the test: one day, two seeds --------------------------------------------------------
export MIROVA_CLUSTER_DIR="$PWD/cluster"
export MIROVA_OUTPUT_ROOT="$WS/output/benchmark/test_lmrs"
export MIROVA_STUDY_OPTS="--dates=2025-09-22 --demand=$WS/demand --strict=true --replications=2"

# --- look before submitting: prints the two java commands of task 0, starts nothing ---------------
MIROVA_DRY_RUN=1 SLURM_ARRAY_TASK_ID=0 bash cluster/run_mirova.sbatch

# --- submit: one task = two runs, 30 minutes ------------------------------------------------------
JOBID=$(sbatch --parsable --array=0-0 --time=00:30:00 --chdir="$WS" cluster/run_mirova.sbatch)
echo "$JOBID"
squeue -j "$JOBID"

# --- check -----------------------------------------------------------------------------------------
sacct -j "$JOBID" --format=JobID%20,JobName%14,State,ExitCode,Elapsed,TotalCPU,MaxRSS,ReqMem,AllocTRES%32
seff "${JOBID}_0"
cat "$WS/logs/mirova_${JOBID}_0.out"
tail -n 5 "$WS"/logs/mirova_${JOBID}_0_run*.log
ls "$MIROVA_OUTPUT_ROOT"/FreiburgNord_2025-09-22_13-00_to_22-00/variation_0/run_seed_*/
grep -E '^(status|seed|model|preset|git|ots|wall|process|gtus|exits)' \
    "$MIROVA_OUTPUT_ROOT"/FreiburgNord_2025-09-22_13-00_to_22-00/variation_0/run_seed_*/run.properties
```

What to expect: `State=COMPLETED`, `ExitCode=0:0`; two run folders (`run_seed_42`, `run_seed_1000045`), each with
seven files; `status=finished`, `git.dirty=false`, `gtus.removed` in the tens. `MaxRSS` of the `.batch` step is
the number to size `--mem-per-cpu` and `MIROVA_JAVA_HEAP` from; it covers both JVMs.

`--array` and `--time` on the command line override the `#SBATCH` lines of the script. The `MIROVA_*`
exports, `MIROVA_CP_FILE` included, reach the job because `sbatch` exports the submitting environment by default.

The run uses the default configuration `final-v2` (`cluster/README.md`). For upstream's LMRS as it is, add
`export MIROVA_PRESET=upstream`; for another preset, its name. Either way use another
`MIROVA_OUTPUT_ROOT`.
