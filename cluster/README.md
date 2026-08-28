# Running MiRoVA Studies on bwUniCluster 3.0 (SLURM)

Runs a MiRoVA study on the cluster as a SLURM job array.

**One array task = two simulation runs, one per core, run concurrently.** Array task *T*
executes global run indices **2·T** and **2·T+1**.

## Why two runs per task

A run is single-threaded and takes **90–120 minutes**, while JVM startup costs seconds, so
bundling buys nothing in startup terms — the earlier design deliberately ran one run per task
for maximum scheduling elasticity.

What changed is an empirical finding: on the `cpu` partition SLURM allocates **two logical
CPUs even for `--cpus-per-task=1`**. Observed as `AllocTRES=cpu=2` on jobs `6366033`,
`6366103` and `6366286`; the partition reports `MaxCPUsPerNode=192` across 80 nodes
(`TotalCPUs=15360`), i.e. 96 physical cores × 2 SMT threads, so a physical core looks like the
smallest allocatable unit. The completed canary run `6366286` shows the cost directly:

```
CPU Utilized:   00:18:25
Wall-clock:     00:18:29
CPU Efficiency: 49.82%      <- one core busy, one allocated and idle
```

Since the allocation is two cores either way, the second one now carries a second run. This
does not weaken the scheduling argument: a two-core task is still the smallest thing the
partition hands out, so it is just as backfillable as the one-core request was.

`--cpus-per-task=2` is requested explicitly rather than relying on that rounding, so the
allocation stays correct if the rounding behavior ever changes.

## Files

| File | Purpose |
|:---|:---|
| `generate_demand_csvs.ps1` / `.py` | Generates full-day demand CSVs locally on Windows from detector database |
| `mirova_env.sh` | Single definition of workspace, `JAVA_HOME`/`PATH` and toolchain provisioning (sourced, not executed) |
| `build_for_cluster.sh` | Provisions Java/Maven, builds the modules, writes `cp.txt` into the workspace |
| `dates.txt` | Date list for the date study (**template — swap in the real 32 dates**) |
| `run_mirova.sbatch` | The SLURM batch script |

---

## 1. Allocate a workspace (do this first)

`$HOME` is small, quota-limited and not intended for simulation I/O. bwUniCluster provides
**workspaces**: large, fast (Lustre) scratch storage with an explicit lifetime.

```bash
ws_allocate mirova <days>          # you choose the lifetime
export MIROVA_WORKSPACE=mirova     # every script below resolves this via ws_find
```

> ### ⚠️ Workspace data is NOT backed up
>
> A workspace expires after the lifetime you chose at `ws_allocate`, and can be renewed only a
> **limited number of times** (`ws_extend`). When it expires the data is deleted, with no
> backup and no recovery.
>
> **Copy results that matter to `$HOME` or off the cluster before expiry.** Check remaining
> lifetime with `ws_list`.

Every script fails with a clear error if `MIROVA_WORKSPACE` is unset or `ws_find` can't
resolve it — deliberately, rather than silently filling up `$HOME`.

The build script creates this layout inside the workspace:

```
$(ws_find mirova)/
├── cp.txt        # runtime classpath
├── demand/       # pre-generated demand CSVs  <- put yours here
├── output/       # simulation results, per study
└── logs/         # SLURM job logs
```

### Generating Demand CSVs Locally (Windows Workstation)

Before running the date study on the cluster, generate the full-day demand CSVs on your local Windows machine where the detector PostgreSQL database is accessible:

```powershell
# In opentrafficsim repository on Windows:
.\cluster\generate_demand_csvs.ps1
```

This reads `cluster/dates.txt`, queries `detektoren_autobahn_freiburg`, and creates full-day (`00:00:00`–`23:55:00`, 5-min aggregation) files named `demand_{date}.csv` in `cluster/demand/`. Then upload the contents of `cluster/demand/` to `$(ws_find mirova)/demand/` on the cluster.

The generator is idempotent and validates output integrity (non-zero volume, expected row count). Use `-Force` to regenerate existing files:

```powershell
.\cluster\generate_demand_csvs.ps1 -Force
```

## 2. Get the repository

```bash
cd "$(ws_find mirova)"
git clone <repo-url> .        # note the trailing dot
```

> The **trailing `.`** clones into the current directory. Without it you get
> `$(ws_find mirova)/opentrafficsim/` instead — which is perfectly fine, but then run that
> copy's scripts (`opentrafficsim/cluster/build_for_cluster.sh`). The scripts locate the
> project from their own location, so either layout works; what does *not* work is running
> `mvn` from the workspace root when the repo is one level down — that produces the confusing
> `Could not find the selected project in the reactor` error. `build_for_cluster.sh` checks
> for the reactor `pom.xml` up front and says so explicitly rather than letting Maven fail.

Keeping the repository in the workspace is recommended (the script warns if it's under
`$HOME`) — but the source tree is small, so `$HOME` works if you prefer it backed up.

## 3. Build

```bash
export MIROVA_WORKSPACE=mirova
./cluster/build_for_cluster.sh
```

### There is no Java or Maven module on this cluster

`module spider` on bwUniCluster 3.0 lists **zero** Java and **zero** Maven entries — the module
tree is CAE/simulation software (Abaqus, Ansys, VASP, Comsol, …) plus Python/R/Julia/Matlab.
There is nothing to `module load`, and any instruction telling you to is wrong for this cluster.

`build_for_cluster.sh` therefore provisions the toolchain into `<workspace>/tools/` itself, on
first run only — a second run reuses it and downloads nothing:

| Tool | Source | Note |
|:---|:---|:---|
| Temurin JDK 17 | `api.adoptium.net/v3/binary/latest/17/…/eclipse` | The API redirects to the right asset; the naive GitHub `releases/latest/download/<generic-name>` URL **404s** |
| Maven 3.9.9 | `archive.apache.org/dist/maven/maven-3/…` | **Not** `dlcdn.apache.org`, which only mirrors currently-supported releases and returns a ~200-byte error page for 3.9.9 |

Both endpoints answer failures with a small page **and HTTP 200**, so a naive download leaves a
file that only fails later inside `tar`, with a cryptic `gzip: stdin: not in gzip format`. Every
download is therefore checked for minimum size *and* gzip readability, and a failure reports the
byte count, the host to check, and the first bytes of what actually arrived.

### Build recipe

```bash
mvn install -pl ots-demo -am -Dmaven.test.skip=true -Dmaven.javadoc.skip=true -Djacoco.skip=true
```

- `install`, not `package`: `ots-demo` resolves `ots-road`/`ots-xml` from `.m2`, so changes
  there only take effect once installed
  (see [docs/mirova/troubleshooting_and_compilation.md](../docs/mirova/troubleshooting_and_compilation.md)).
- All three skip flags together. `-Dmaven.javadoc.skip=true` is the one a plain `-DskipTests`
  build is missing, and its absence is what makes a first build attempt fail: the javadoc plugin
  errors on pre-existing Javadoc issues in `ots-road`, unrelated to these studies.
- The batch script launches `java -cp` directly instead of `mvn exec:java`, avoiding the
  GlassFish JAXB ClassLoader failures documented in the troubleshooting guide.

### Using the toolchain in an interactive shell

`cluster/mirova_env.sh` is the single definition of `JAVA_HOME` and `PATH`; the build script and
the batch script source exactly this, so nothing can drift:

```bash
export MIROVA_WORKSPACE=mirova
source cluster/mirova_env.sh
activate_toolchain "$(resolve_workspace)"
java -version && mvn -version
```

## 4. Pick the array size

The run enumeration lives in Java, so ask the entry point how many runs the study has:

```bash
WS=$(ws_find mirova)
java -cp "$(cat $WS/cp.txt)" \
  org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.RunMirovaClusterStudy \
  --study=dates --output=$WS/output/dates \
  --dates=cluster/dates.txt --demand=$WS/demand --count
```

This prints a single integer N and runs nothing. Since each task runs **two** runs, set

```
#SBATCH --array=0-<ceil(N/2)-1>
```

| Study | N | Tasks = ⌈N/2⌉ | `--array` |
|:---|---:|---:|:---|
| dates, 32 dates × 6 replications | 192 | 96 | `0-95` |
| dates, 9 placeholder dates × 6 | 54 | 27 | `0-26` |
| paramgrid, 17 variations × 6 | 102 | 51 | `0-50` |
| damping, 9 dates × 2 variations × 10 | 180 | 90 | `0-89` |
| mergegrid, 3 dates × 9 variations × 10 | 270 | 135 | `0-134` |
| paramgrid, 17 variations × 1 | 17 | 9 | `0-8` |

An **odd** N is fine, as in the last row: the final task finds that its second index
(`2·8+1 = 17`) is beyond the study's 17 runs and launches only one process, logging
`no run at global index 17 (study has 17); nothing to launch.` The script asks the study for
N itself at task start, so this needs no manual bookkeeping — set `MIROVA_TOTAL_RUNS` to skip
that extra JVM start if you prefer.

Sizing the array *larger* than ⌈N/2⌉ is rejected: a task with no runs at all exits `2` rather
than reporting success for doing nothing.

Optionally add `--manifest=$WS/output/manifest.tsv` to also write a human-readable
`index → scenario / variation / replication / seed / parameters` table. It is **informational
only** — execution addresses runs by index through the deterministic Java enumeration and
never reads this file.

## 5. Submit

```bash
cd <repository>                                   # e.g. $(ws_find mirova)/opentrafficsim
export MIROVA_WORKSPACE=mirova
export MIROVA_CLUSTER_DIR="$PWD/cluster"
sbatch --chdir="$(ws_find mirova)" cluster/run_mirova.sbatch
```

`MIROVA_CLUSTER_DIR` is **required**. `sbatch` copies the submitted script's *content* into
`/var/spool/slurmd/job<id>/slurm_script` and executes that copy, so the script cannot locate
its own directory and would not find `mirova_env.sh` beside it. It therefore refuses to guess:
without the export the job exits immediately with an explanatory message rather than failing
obscurely a few lines later. This applies however you invoke `sbatch` — relative path, absolute
path, any working directory.

`--chdir` makes the relative `logs/` paths in the `#SBATCH --output`/`--error` directives land
in the workspace (those directives are literal and cannot call `ws_find` themselves).

Configure via environment variables — no need to edit the script:

| Variable | Default | Meaning |
|:---|:---|:---|
| `MIROVA_WORKSPACE` | *(required)* | Workspace name, resolved via `ws_find` |
| `MIROVA_CLUSTER_DIR` | *(required)* | Path of the repository's `cluster/` directory; `sbatch` copies the script, so it cannot find itself |
| `MIROVA_STUDY` | `dates` | Study short name or `StudyDefinition` class name |
| `MIROVA_STUDY_OPTS` | `--dates=… --demand=… --strict=true` | Options passed to the study |
| `MIROVA_DEMAND_DIR` | `<ws>/demand` | Pre-generated demand CSVs |
| `MIROVA_OUTPUT_ROOT` | `<ws>/output/<study>` | Results root |
| `MIROVA_DATES_FILE` | `cluster/dates.txt` | Date list for the date study |
| `MIROVA_JAVA_HEAP` | `6g` | `-Xmx` **per run**; two runs share `2 × --mem-per-cpu` |
| `MIROVA_RUNS_PER_TASK` | `2` | Runs bundled per array task |
| `MIROVA_TOTAL_RUNS` | *(asked of the study)* | Skips the `--count` call at task start |

Example — run the parameter study instead:

```bash
export MIROVA_CLUSTER_DIR="$PWD/cluster"
export MIROVA_STUDY=paramgrid
export MIROVA_STUDY_OPTS="--demand=$(ws_find mirova)/demand --strict=true"
sbatch --chdir="$(ws_find mirova)" --array=0-50 cluster/run_mirova.sbatch   # 102 runs -> 51 tasks
```

Example — the damping bound study (`aRelaxDamping` 0.90 and 1.00 on the best cell of the
combination campaign, nine dates, ten replications):

```bash
export MIROVA_CLUSTER_DIR="$PWD/cluster"
export MIROVA_STUDY=damping
export MIROVA_STUDY_OPTS="--dates=cluster/dates.txt --demand=$(ws_find mirova)/demand --replications=10 --strict=true"
sbatch --chdir="$(ws_find mirova)" --array=0-89 cluster/run_mirova.sbatch   # 180 runs -> 90 tasks
```

Ten replications rather than the default six, so the cells are directly comparable with the
`combos` campaign's ten seeds (42–51) at damping 0.60 and 0.80. Its output folders carry the
same `<combination>_adamp<d>_sdr<s>` names, so the Python evaluation reads both campaigns with
one `--output-dir` each, or with a single one if the results are collected side by side.

Example — the fine merge parameter grid (damping × safety distance on three calibration
dates):

```bash
export MIROVA_CLUSTER_DIR="$PWD/cluster"
export MIROVA_STUDY=mergegrid
export MIROVA_STUDY_OPTS="--dates=cluster/dates_calibration.txt --demand=$(ws_find mirova)/demand --replications=10 --strict=true"
sbatch --chdir="$(ws_find mirova)" --array=0-134 cluster/run_mirova.sbatch   # 270 runs -> 135 tasks
```

## 6. Resources per task

Each run is single-threaded: `AbstractSimulationScriptBase.runHeadless()` drives
`simulator.step()` in a plain `while` loop **on the calling thread** — no DSOL worker thread,
no executor inside a run. The task therefore places two such processes on its two cores, each
with `-XX:ActiveProcessorCount=1` so neither JVM's GC and JIT threads oversubscribe the pair.

Where `taskset` is available, the two processes are pinned to distinct CPUs taken from the
task's own affinity mask (so the pinning respects the cgroup SLURM placed the job in, rather
than assuming CPUs 0 and 1). Without `taskset`, placement is left to the OS scheduler, which
spreads two runnable single-threaded processes across two free cores by itself; the log line
per slot states which of the two applied.

`--time=03:00:00` gives ~50% headroom over the measured 90–120 min. Because the two runs are
**concurrent**, this stays a per-run budget rather than a sum.

### Memory: calibrate for two JVMs, not one

`--mem-per-cpu` is a **placeholder**. It is applied *per core*, so a task's memory limit is
`2 × --mem-per-cpu` — but there are also **two** JVMs living inside that limit, and
`MIROVA_JAVA_HEAP` is per run. The constraint to satisfy is therefore:

```
2 × MIROVA_JAVA_HEAP  <  2 × mem-per-cpu  −  (2 × off-heap per JVM)
```

which per run reduces to `JAVA_HEAP < mem-per-cpu − off-heap`. The headroom has to cover
**two** JVMs' non-heap footprint — metaspace, code cache, GC structures, thread stacks, direct
buffers — not one's; roughly 0.5–1 GB each. The current defaults (`mem-per-cpu=8G`,
`JAVA_HEAP=6g`) leave about 2 GB per run for that.

Measure a single run's peak RSS first, then set both together. Getting this wrong is not a
per-run failure: exceeding the task's limit gets the **whole task** OOM-killed, so the healthy
run dies alongside the greedy one.

---

## Studies

A study is a `StudyDefinition`: it registers scenarios, parameter variations and a replication
count into a `ScenarioManager`. Five are registered in `StudyRegistry`:

| Short name | Class | Shape |
|:---|:---|:---|
| `dates` | `DateStudy` | One scenario per date, 1 variation each; facility-agnostic |
| `paramgrid` | `FreiburgParameterStudy` | One scenario, 17 one-at-a-time variations |
| `combos` | `FreiburgCombinationStudy` | Headway combinations × damping factors × safety distance factors × every date |
| `damping` | `FreiburgDampingStudy` | Damping 0.90 and 1.00 on the best `combos` cell (`tighter`, sdr 0.50) × every date |
| `mergegrid` | `FreiburgMergeGridStudy` | Damping × safety distance, 3 × 3, on the `tighter` combination × a few calibration dates |

**Adding a further study requires no change to the batch script or the entry point** — write a
new `StudyDefinition`, then select it either by adding a short name to `StudyRegistry` or by
passing its fully qualified class name to `--study=`.

### `dates` options

| Option | Default | Meaning |
|:---|:---|:---|
| `--dates=` | *(required)* | Comma-separated dates, or a file with one date per line |
| `--demand=` | *(required)* | Demand CSV file, or directory of per-date CSVs |
| `--pattern=` | `demand_{date}.csv` | Per-date file name pattern inside the directory |
| `--replications=` | `6` | Replications per date |
| `--strict=` | `false` | Missing CSV is fatal instead of falling back to synthetic demand |
| `--facility=` | `freiburg` | Traffic facility to simulate, by short name or `TrafficFacility` class name |

### `paramgrid` options

| Option | Default | Meaning |
|:---|:---|:---|
| `--demand=` | *(unset)* | When given, uses the CSV as-is and disables Python demand prep |
| `--start=`, `--end=` | `2025-09-25 13:00:00` / `22:00:00` | Simulated period (same daily window as the other studies) |
| `--replications=` | `6` | Replications per variation |
| `--strict=` | `false` | Missing CSV is fatal |

### `combos` options

Same options as `dates` — it reuses that study's date-list reading and per-date demand
resolution, including the up-front check that every CSV exists.

| Option | Default | Meaning |
|:---|:---|:---|
| `--dates=` | *(required)* | Comma-separated dates, or a file with one date per line |
| `--demand=` | *(required)* | Demand CSV file, or directory of per-date CSVs |
| `--pattern=` | `demand_{date}.csv` | Per-date file name pattern inside the directory |
| `--replications=` | `6` | Replications per date **and** combination |
| `--strict=` | `false` | Missing CSV is fatal instead of falling back to synthetic demand |

This study runs the **Cartesian product of three lists**, all in `FreiburgCombinationStudy`:

| List | Contents | Varies |
|:---|:---|:---|
| `COMBINATIONS` | `("standard", 1.00, 1.30)`, `("tighter", 0.90, 1.20)` | car / truck desired headway `T` |
| `ACC_DAMPING_FACTORS` | `0.60`, `0.80` | relaxation acceleration damping factor |
| `SAFETY_DISTANCE_FACTORS` | `0.50`, `0.60` | lane-change safety distance reduction factor |

giving **8 variations per date**. Both factors are applied to **cars and trucks alike**, so a
grid cell is described by three numbers rather than six. Extending any dimension is one entry in
the respective list; nothing depends on their lengths.

The safety distance values are the two the study has actually used: `0.50` until 2026-07-31 and
`0.60` since 2026-08-06, the latter being the baseline `FreiburgStudyParameters.RED_FAC`. The
damping baseline is `0.80`, so the cell `standard × adamp0.80 × sdr0.60` reproduces the
evaluation study's setting exactly.

Every variation starts from `FreiburgStudyParameters.forDate(...)` and overrides **only** those
values, so a cell differs from the `dates` study in exactly the swept parameters by construction.

Total runs = dates × combinations × damping × safety distance × replications:

| Dates | Replications | Total | Array (2 runs/task) |
|---:|---:|---:|:---|
| 3 | 6 | `3 × 8 × 6 = 144` | `0-71` |
| 9 | 6 | `9 × 8 × 6 = 432` | `0-215` |
| **9** | **10** | **`9 × 8 × 10 = 720`** | **`0-359`** |
| 32 | 6 | `32 × 8 × 6 = 1536` | `0-767` |

The bold row is the weekend campaign: the nine dates of `cluster/dates.txt`, both headway
combinations, both damping factors, both safety distance factors, ten replications. Verified with
`--count`, not derived by hand:

```
--study=combos --dates=cluster/dates.txt --demand=<dir> --strict=true --replications=10 --count
720
```

⚠️ At 90–120 min per run, the full 32-date grid is roughly **2300–3100 core-hours**. Consider
running the grid on a subset of dates and the full date list only for the baseline cell. The
720-run campaign is **1100–1400 core-hours**; at two runs per task that is 360 tasks, so roughly
**13 tasks running concurrently** finish it inside a weekend, and anything more is headroom against
requeues. Both figures predate `LaneBasedGtu.CACHING=false`, which cut measured CPU to 84.3 % of
the previous baseline — they are therefore conservative rather than wrong.

Output folders name the date **and** all three swept values, so a result is identifiable without
resolving an index against the source:

```
FreiburgNord_2025-09-22_13-00_to_22-00_standard_adamp0.60_sdr0.50/variation_0/run_seed_42/
FreiburgNord_2025-09-22_13-00_to_22-00_standard_adamp0.60_sdr0.60/variation_0/run_seed_42/
FreiburgNord_2025-09-22_13-00_to_22-00_standard_adamp0.80_sdr0.50/variation_0/run_seed_42/
...                                                            (8 per date)
FreiburgNord_2025-09-22_13-00_to_22-00_tighter_adamp0.80_sdr0.60/variation_0/run_seed_42/
```

The `adamp`/`sdr` suffixes are formatted with `Locale.ROOT`, so the decimal separator is a dot on
every node — these names are what post-processing matches on, and must not depend on the format
locale of whichever machine ran the job. `runParams.txt` additionally records
`headwayCombination`, `accDampingFactor` and `safetyDistanceFactor`.

Registration order is date-major, then combination, then damping factor, then safety distance
factor. Use `--manifest=` to print the index-to-run mapping rather than deriving it by hand.

### `damping` — bounding the relaxation damping axis

`combos` measured damping at 0.60 and 0.80 and found every calibration metric improving
monotonically towards 0.80, with no sign of flattening. `aRelaxDamping` is the **minimum**
factor applied to positive acceleration while headway relaxation is active
(`f = 1 - (1 - aRelaxDamping) * ratio` in `EgoContext.getRelaxationAccelerationFactor`), so a
larger value means *weaker* damping: the trend says the damping currently applied is too strong.

This study adds 0.90 and 1.00 on the single best cell — `tighter` (T = 0.90 / 1.20) with safety
distance reduction 0.50 — rather than re-running the whole grid. Its purpose is a bound, not a
search: it answers how much of the ~312 veh/h capacity deficit weaker damping can close **at
all**. If 1.00 recovers only a fraction, the remainder is elsewhere and no finer search along
this axis is worth its core-hours.

**1.00 is the "damping off" case.** With `aRelaxDamping = 1.00` the factor is 1.00 for every
ratio, which is exactly what `aRelaxDampingEnabled = false` produces — so the boolean needs no
cell of its own.

| Constant | Value | Meaning |
|:---|:---|:---|
| `HEADWAY_LABEL` | `tighter` | Looked up in `FreiburgCombinationStudy.COMBINATIONS`, so the two studies cannot drift apart |
| `SAFETY_DISTANCE_FACTOR` | `0.50` | Fixed; outperformed 0.60 on every `combos` cell |
| `ACC_DAMPING_FACTORS` | `0.90`, `1.00` | The two new points of the axis |

Total runs = dates × 2 × replications, i.e. 9 × 2 × 10 = **180** for the campaign above.
Combined with `combos`, the damping axis on that cell is four points long: 0.60, 0.80, 0.90,
1.00.

### `mergegrid` — the fine damping × safety distance grid

What the two earlier campaigns established, measured on the **bottleneck** flow (mainline
plus ramp — the merge serves both, so the mainline detector alone reports only the
mainline's share of the discharge):

| | q_pre [veh/h] | capacity drop |
|:---|---:|---:|
| damping 0.60, sdr 0.50 | 2684 | 10.1 % |
| damping 0.80, sdr 0.50 | 2811 | 2.3 % |
| damping 0.90, sdr 0.50 | 2766 | −0.6 % |
| damping 1.00, sdr 0.50 | 2787 | −1.9 % |
| **empirical** | **3456** | **9.8 %** |

Capacity saturates on the damping axis above 0.80 — that axis is spent. The capacity drop
runs the other way and is reproduced at 0.60, turning negative from 0.90 upwards, which
would mean the bottleneck discharges more after breaking down than before. Safety distance
is the steeper lever and moves both quantities the same way: 0.60 → 0.50 gained 151 veh/h
at damping 0.60 and 216 veh/h at 0.80, raising the capacity drop in both cases.

The grid therefore spans damping from the value that reproduces the drop to the value that
maximises capacity, crossed with safety distances below the best one measured so far:

| Constant | Value |
|:---|:---|
| `HEADWAY_LABEL` | `tighter` (T = 0.90 / 1.20) |
| `ACC_DAMPING_FACTORS` | `0.60`, `0.70`, `0.80` |
| `SAFETY_DISTANCE_FACTORS` | `0.40`, `0.45`, `0.50` |

Values below 0.40 are deliberately excluded: accepting gaps under 40 % of the safe distance
buys capacity with implausible behaviour rather than with better modelling, and that trade
should be a decision rather than a side effect of a sweep.

Two cells — damping 0.60 and 0.80 at safety distance 0.50 — are already simulated by
`combos` on all nine dates and are re-run here on purpose. If they reproduce on these three
dates, the rest of the grid can be read against the earlier campaign.

**On the three dates.** `cluster/dates_calibration.txt` holds them, chosen for an identified
empirical breakdown and a spread of demand — explicitly *not* for fitting well already.
Calibrating on the days a model happens to reproduce improves the metric without improving
the model. 2025-09-23 and 2025-10-08 are excluded because they have no empirical breakdown
at all (drop −0.7 % and −7.8 %), so capacity is not estimable there; the remaining six dates
stay untouched as validation.

Total runs = dates × 9 × replications, i.e. 3 × 9 × 10 = **270**.

#### The third run: after the discharge section stopped throttling itself

The second `mergegrid` campaign was read against a bottleneck discharge of 2368-2569 veh/h
against an empirical 3095 +/- 118, and that reading does not survive what came after it.
The cross-section the discharge is measured at, det_L5a, was being held at exactly
`VCONG` = 60 km/h by `AnticipateDownstreamMergePattern`: its activation could not tell a
lane drop from the end of the modelled network, and on the last link every lane ends. With
the pattern deactivated the same detector runs at 89 and 107 km/h.

**The standing capacity deficit is therefore an open question, not a finding.** It was
measured on a section that throttled itself, and re-establishing it - or not - is the main
purpose of this run.

Three model changes separate this campaign from the previous one:

| | change | measured effect |
|:---|:---|:---|
| merge FSM | kinematic gate, discretionary desire, ramp acceleration | merge speed deficit 40 -> 4 km/h, +18 merges/h |
| `AnticipateDownstreamMergePattern` | deactivated | det_L5a +29 and +47 km/h, nothing else moved |
| `MirovaIdmPlus` | braking bounds corrected, physical net moved to the utility | no deceleration below B_MAX, standstills 33.7 -> 26.5 per seed |

Same grid, same three dates, same seeds as `mergegrid_v2`, so the cells compare directly:

```bash
export MIROVA_CLUSTER_DIR="$PWD/cluster"
export MIROVA_STUDY=mergegrid
export MIROVA_OUTPUT_ROOT="$(ws_find mirova)/output/mergegrid_v3"
export MIROVA_STUDY_OPTS="--dates=cluster/dates_calibration.txt --demand=$(ws_find mirova)/demand --replications=10 --strict=true"
sbatch --chdir="$(ws_find mirova)" --array=0-134 cluster/run_mirova.sbatch   # 270 runs -> 135 tasks
```

What to read first, in this order:

1. **Speed at det_L5a.** It must no longer sit at 60 km/h. If it does, something still
   reaches for `VCONG` on the final link and every capacity figure from this campaign is
   as unusable as the last one's.
2. **Queue discharge against the empirical reference.** The reference comes from all nine
   field days, not the three simulated ones - one breakdown per day is one observation, and
   three of them put the empirical discharge at 3001 +/- 401 veh/h where nine put it at
   3095 +/- 118. Only the wider set resolves a deficit of the size at issue.
3. **Capacity drop, jam duration, onset.** All three were already inside the empirical
   intervals in `v2`; they should stay there. A regression here would mean the merge work
   was undone by one of the later changes.
4. **Jam speed**, which was the one merge metric still clearly short at 29-38 km/h against
   47 +/- 7. If it is still short once the discharge section runs free, the congested
   branch of the fundamental diagram is the next thing to look at, not the merge.

Two operational checks, both earned by earlier campaigns: empty run directories always
came in task-sized pairs on the highest-demand date, which `sacct -j <jobid>
--format=JobID,State,ExitCode,MaxRSS,Elapsed` separates into `TIMEOUT` and
`OUT_OF_MEMORY` in one look; and `ParameterTypes.A` is still at the OTS default of
1.25 m/s for cars and trucks alike, so if the discharge is still short after this run,
that is the first thing to set rather than another sweep of this grid.

#### The fourth run: after the free branch stopped being held back

`mergegrid_v3` was read twice and only the second reading counts. The first went through
an evaluation that kept the per-GTU-type rows alongside the cross-class total in every
detector interval, so station flows read roughly double and the campaign appeared to miss
the empirical discharge by 8 %. Corrected, four of the nine cells sit inside the field
interval of 3095 +/- 118 veh/h. **The capacity deficit that drove the last three campaigns
was largely a counting error in the evaluation, not a property of the model.**

Two things remained after that correction, and both point the same way: the pre-breakdown
flow was still 10-19 % short, and the capacity drop came out negative - the simulation
discharged faster than it had flowed before breaking down, which is not a breakdown. Taken
together they say the model broke down too early rather than discharging too slowly.

The cause was found in the reactive layer. The physical net in `MirovaCarFollowingUtil`
applied to every car-following call rather than to the relaxation discontinuity it was
written for, and its kinematic form produces a small negative value for any vehicle closing
on a leader at all. Free acceleration was therefore capped for anyone catching up with a
slower vehicle, costing 7-8 km/h across the whole free branch at every flow level. Since
breakdown is detected against a shared critical speed, a free branch held 7 km/h low crosses
that speed at a lower flow - which is the pre-breakdown deficit.

Three changes separate this campaign from `v3`, each measured over 10 paired seeds:

| | change | measured effect |
|:---|:---|:---|
| physical net | scoped to relaxed perception | free branch +4.5 km/h, merges +42, standstills 26.5 -> 36.7 |
| `a`, `b`, `s0` | Kesting values per vehicle class | standstills back to 33.5 median, merge speed restored |
| follower thresholds | -2.5 / -5.0 instead of -2.0 / -4.0 | standstills 37.4 -> 29.9, the collapsing seed 84 -> 28 |

The net change is a free branch 3.8 km/h faster, 36 more merges per run, and 3.4 more ramp
standstills than before any of it - with a mechanism that is physically defensible rather
than one that worked by slowing everything down.

Same grid, same three dates, same seeds as `v3`, so the cells compare directly:

```bash
export MIROVA_CLUSTER_DIR="$PWD/cluster"
export MIROVA_STUDY=mergegrid
export MIROVA_OUTPUT_ROOT="$(ws_find mirova)/output/mergegrid_v4"
export MIROVA_STUDY_OPTS="--dates=cluster/dates_calibration.txt --demand=$(ws_find mirova)/demand --replications=10 --strict=true"
sbatch --chdir="$(ws_find mirova)" --array=0-134 cluster/run_mirova.sbatch   # 270 runs -> 135 tasks
```

What to read first, in this order:

1. **Pre-breakdown flow.** This is the campaign's purpose. If the free branch now runs at
   its proper speed, the flow at which the model breaks down should rise towards the
   empirical 3397 +/- 146 veh/h. If it does not, the early breakdown has a second cause
   and the free-branch speed was not it.
2. **Capacity drop.** It must approach the empirical +8.7 %. A discharge that still exceeds
   the pre-breakdown flow means what the pipeline calls a breakdown is not one, and the
   event definition itself needs revisiting before any further calibration.
3. **Queue discharge**, which four `v3` cells already matched. It should stay inside
   3095 +/- 118 rather than climb with the faster free branch. Damping 0.80 already
   overshot at 3305 in `v3`, so the optimum on that axis now sits inside the grid - the
   earlier reading that it lay at the upper edge came from the contaminated evaluation.
4. **Ramp standstills**, from the trajectories. Expect roughly 3 more per run than the
   `v3` code produced. More than that on the cluster's higher-demand dates would mean the
   follower thresholds do not carry over from the single watched day they were tuned on.

Two evaluation notes. Delete `detector_runs_cache.csv` under each variation before reading
any campaign parsed before the counting fix, or the corrected loader will read the old
cache. And `s0` interacts multiplicatively with `safetyDistanceReductionFactorLaneChange`
in the relaxation trigger, so the `sdr` axis is not comparable across campaigns either side
of this change - within `v4` it is, against `v3` it is not.

#### The fifth run: the car side, which was never tested

Three car parameters moved at once when the Kesting values were adopted - acceleration 1.25 to
1.40, comfortable deceleration 2.09 to 2.00, stopping distance 3.0 to 2.0 m - and none of them
was ever varied on its own. The truck side has since been settled by a factorial on one date,
but cars carry 80 % of the traffic here, so the larger gap stayed open. This campaign closes it.

What that factorial settled, and what this run therefore holds fixed:

| parameter | value | measured over |
|:---|:---|:---|
| `a` trucks | 1.25 | 359 runs; strongest axis of all, monotone over 0.7/1.0/1.3 - standstills 340 -> 244 per run, right lane +11.9 km/h, jam -11.7 min |
| `s0` trucks | 4.0 m | 108 runs; 19 % fewer standstills than 3.0 |
| `T` trucks | 1.20 s | no effect on standstills, weak on speeds - not worth breaking comparability |
| follower | -2.0 / -4.0 | -2.5 raised standstills by 29 %, which is most of their near-doubling from v3 to v4 |

The truck acceleration is the one worth dwelling on. It had been set to the field median of
0.7 m/s2, which looks right until one measures what the trucks then do: a median of 0.28 m/s2
while accelerating, and 0.51 below 10 km/h. IDM reads the parameter as a ceiling that the free
and interaction terms cut into, and the field figure is itself an average over a process that
starts higher - so setting the parameter to the observed mean guarantees accelerations below it.
The trajectory output now records the GTU type, which is what made that measurable at all;
classifying by speed had put 54 % of the fleet in the truck class where the demand holds 20 %.

The grid:

```bash
export MIROVA_CLUSTER_DIR="$PWD/cluster"
export MIROVA_STUDY=carparams
export MIROVA_OUTPUT_ROOT="$(ws_find mirova)/output/carparams_v1"
export MIROVA_STUDY_OPTS="--dates=cluster/dates_calibration.txt --demand=$(ws_find mirova)/demand --replications=10 --strict=true"
sbatch --chdir="$(ws_find mirova)" --array=0-89 cluster/run_mirova.sbatch   # 180 runs -> 90 tasks
```

Car acceleration at 1.25, 1.40 and 1.70, car stopping distance at 2.0 and 3.0 m: six cells per
date, three dates, ten seeds. The third acceleration level is not a bracket around Kesting's
1.40 but an opening upwards, for the same ceiling reason the truck result exposed.

What to read first:

1. **The control cell.** Acceleration 1.25 with stopping distance 3.0 restores what both
   carried before the Kesting set, so this campaign answers on its own whether everything else
   changed since `v3` was worth it - without the confounding that made the last three
   comparisons so hard to read.
2. **Jam speed and jam duration**, which are the metrics that regressed in `v4`: 28-36 km/h
   against an empirical 47 +/- 7, and 72-159 min against 48-94. If the car stopping distance
   carries that, the 3.0 m cells will show it plainly.
3. **Pre-breakdown flow**, still 10-19 % short and unmoved by the free-branch repair. The car
   acceleration is the remaining untested candidate for it.
4. **Capacity**, but read it from the corrected evaluation. The figures the earlier campaigns
   were judged by were wrong twice over - a congestion threshold refitted per run, and a
   capacity taken from the first of several breakdown episodes - and the corrected reading puts
   the simulation on top of the field rather than far below it. Anything concluded about
   capacity before that correction needs re-reading, not extending.

#### The sixth run: validation on all nine dates

The car sweep settled the last open parameter, and with it the model reaches a state worth
validating rather than tuning further. Held fixed here: damping 0.80 and safety distance 0.40,
truck acceleration 1.25 and stopping distance 4.0 m, follower thresholds -2.0 / -4.0, car
acceleration 1.40 and stopping distance 2.0 m.

Where that set stands, read against the corrected capacity evaluation:

| metric | simulation | field | |
|:---|:---|:---|:---|
| queue discharge | 3048-3255 | 2977-3214 | four of six cells inside |
| breakdown onset | 174-189 min | 170-212 | every cell inside |
| jam duration | 45-63 min | 48-94 | inside at car s0 = 2.0 |
| pre-breakdown flow | 3008-3164 | 3251-3543 | 4-11 % short, was 10-19 % |
| jam speed | 30-42 km/h | 40-54 | inside only at car s0 = 3.0 |
| capacity drop | -7.5 to +3.3 % | 5-13 % | right sign only at car s0 = 2.0 |

The car stopping distance is the trade-off that remains: 3.0 m buys jam speed at the cost of jam
duration and the sign of the capacity drop, 2.0 m the reverse. 2.0 wins on the count of intervals
hit, which is why it is the value carried forward - not because the conflict is resolved.

Two questions, and the design answers both:

```bash
export MIROVA_CLUSTER_DIR="$PWD/cluster"
export MIROVA_STUDY=validation
export MIROVA_OUTPUT_ROOT="$(ws_find mirova)/output/validation_v1"
export MIROVA_STUDY_OPTS="--dates=cluster/dates.txt --demand=$(ws_find mirova)/demand --replications=10 --strict=true"
sbatch --chdir="$(ws_find mirova)" --array=0-134 cluster/run_mirova.sbatch   # 270 runs -> 135 tasks
```

**Does it generalise?** Six of the nine dates have never been calibrated on. Their empirical
pre-breakdown flows span 2940 to 4044 veh/h, i.e. a range wider than the three calibration dates
cover, which is the point: a set fitted to the middle of that range has to hold at both ends.

One date, 2025-09-22, has no persistent breakdown at all - its only congestion episode lasts two
intervals - and it is the sharper test on its own. A model tuned to break down at the right flow
must also manage *not* to break down on a day the site did not, and a calibration measured only
on days that do break down cannot see that failure mode. Read the false-breakdown rate there
before anything else.

(The note in `dates_calibration.txt` that 2025-09-23 and 2025-10-08 have no identified breakdown
predates the current detection and no longer holds: both yield one, at 2988 and 2940 veh/h. That
file selected the calibration subset and its reasoning is unaffected, but the claim itself is
stale.)

**Does the headway close the rest?** The desired headway is the parameter with the strongest
reported influence on bottleneck discharge, well ahead of the acceleration, so it is the natural
candidate for a pre-breakdown flow that is still 4 to 11 % short. Three combinations - the
standard 1.00 / 1.30, the tighter 0.90 / 1.20 the campaigns have used, and a tightest 0.80 / 1.10
extending the axis where the gap points.

One caveat carried into the reading: the empirical reference now rests on eight days rather than
nine. The persistence rule that a breakdown must last fifteen minutes discarded one day's event,
which is the intended behaviour but changes the reference the simulation is scored against.
Check which day that is before treating the interval as unchanged.

##### What it found

The day is 2025-09-22: its only congestion episode lasts two intervals, so the fifteen-minute rule
discards it. The reference now rests on eight days at a queue discharge of 3115 +/- 127 veh/h.

The headway axis is exhausted rather than open. Averaged over all nine dates:

| combination | discharge | pre 15 min | pre 5 min | onset | jam | jam speed | drop |
|:---|:---|:---|:---|:---|:---|:---|:---|
| tightest 0.8/1.1 | 3193 ok | 3006 | 3210 ok | 170 ok | 51 | 39.3 | -8.3 |
| **tighter 0.9/1.2** | **3105 ok** | 3098 | **3280 ok** | **181 ok** | **64 ok** | 35.0 | -1.1 |
| standard 1.0/1.3 | 2921 | 2990 | **3170 ok** | **167 ok** | 126 | 25.6 | +1.3 |
| field (8 days) | 2988-3242 | 3216-3535 | 3146-3766 | 165-215 | 59-97 | 40-49 | 4-11 % |

The pair already in use wins on four of seven metrics. Tightening further lowers the fifteen-minute
pre-breakdown flow, shortens jams below the field interval and worsens the capacity drop.

**The pre-breakdown deficit turns out to be about duration, not level.** The flow in the last
five-minute interval before breakdown is inside the field interval for all three combinations; only
the fifteen-minute mean leading into it is short. The model reaches the right breakdown flow and
fails to hold it for a quarter of an hour, which is a different fault from insufficient capacity and
was invisible under the earlier capacity measurement.

Per day, in the winning cell, the errors are: discharge median -54 veh/h with a median absolute
error of 106; pre-breakdown flow negative on **every** day, median -292; jam duration median -11 min,
absolute 28; jam speed median -9 km/h, absolute 13. Only the pre-breakdown flow is a systematic bias;
the rest is scatter around zero.

The jam speed error is not scatter either, but it is not a bias: on days the field saw a fast jam
(47-51 km/h) the model is 15-20 km/h low, on days it saw a slow one (37-39) the model is 6-16 high.
The model produces much the same jam every time where the site does not - it reproduces the mean and
not the day-to-day variation.

**The discrimination test.** Breakdown rate over ten runs per day, against whether the site broke
down:

| combination | hit rate on the 8 breakdown days | false rate on 2025-09-22 |
|:---|:---|:---|
| standard | 82 % | 60 % |
| tighter | 61 % | 30 % |
| tightest | 34 % | 10 % |

A sensitivity/specificity trade-off, and `tighter` sits in the middle of it. The ordering is right -
the day that did not break down gets the lowest rate, and the three highest-demand days get the
highest - but the separation is thin, 30 % against 40 % for the nearest real day. Note that a correct
model should not give 100 % on a day that broke down: the field day is one draw from a probability,
so rates of 60-90 % there and 30 % on a day that did not are not in themselves a contradiction.

#### Re-running it after the merge FSM changes

The first `mergegrid` campaign ran before the on-ramp merging behaviour itself was
corrected, so its cells describe a model that merged some 40 km/h below the stream it was
joining. Re-running the identical grid on the identical three dates is what makes the two
comparable: same design, same seeds, only the model changed.

What changed in between, and what a 10-seed paired comparison on
`RunFreiburgMergeWatch` measured for it (2025-10-13 13:00, seeds 42–51, |t| > 2.26 would
be significant at n = 10):

| | effect | t |
|:---|---:|---:|
| completed merges | **+18 per hour** | +2.5 |
| merge position p90 | **−11 m**, i.e. fewer vehicles running to the ramp end | −3.0 |
| standstills on the ramp | +22 vehicles, not distinguishable from noise | +0.7 |
| max ramp acceleration | 1.25 → 3.0 m/s² | — |

The standstill mean is dominated by one seed of ten in which the facility collapsed to
5 km/h and never recovered, where the previous code recovered from the same disturbance.
Without it the fixes come out ahead on that metric too. **That tail is the thing to watch
in the campaign results**: a jam the model cannot dissolve shows up as an outlier in jam
duration, not in the averages.

Write the results somewhere other than the first campaign's output root, or the evaluation
will mix the two:

```bash
export MIROVA_CLUSTER_DIR="$PWD/cluster"
export MIROVA_STUDY=mergegrid
export MIROVA_OUTPUT_ROOT="$(ws_find mirova)/output/mergegrid_v2"
export MIROVA_STUDY_OPTS="--dates=cluster/dates_calibration.txt --demand=$(ws_find mirova)/demand --replications=10 --strict=true"
sbatch --chdir="$(ws_find mirova)" --array=0-134 cluster/run_mirova.sbatch   # 270 runs -> 135 tasks
```

Two things to check on the first tasks that come back, both seen in the previous
campaigns:

- **Empty run directories.** 10 of 180 runs of the `damping` campaign and 16 of 270 of the
  first `mergegrid` campaign produced no files at all, always both runs of a task and
  concentrated on the highest-demand date at low damping. `sacct -j <jobid>
  --format=JobID,State,ExitCode,MaxRSS,Elapsed` separates `TIMEOUT` from
  `OUT_OF_MEMORY` in one look; the former argues for `--time=04:00:00`, the latter for a
  higher `--mem-per-cpu`.
- **Whether tasks report `COMPLETED` at all.** The local merge-watch runner leaves its JVM
  alive after finishing, because AWT threads are not daemons. `run_mirova.sbatch` waits on
  the run PIDs, so if `RunMirovaClusterStudy` shared that behaviour every task would burn
  its full three hours regardless of when the simulation ended. It does not - a local run
  of it exits cleanly - but a campaign whose tasks all report `TIMEOUT` while their output
  is complete would be the symptom, and `System.exit(0)` at the end of `main` the fix.

## Global run index

A run is addressed by its index into the study's enumeration:

> for each registered scenario (registration order),
> for each of its parameter variations (list order),
> for each replication index.

This is the **same enumeration** `ScenarioManager.runAll` executes as a batch — both walk the
one `enumerateRuns()` definition, so index *i* addresses the same run either way. The
addressing therefore generalises from date sweeps (one variation per scenario) to parameter
grids (many variations per scenario) with no change to the batch script.

Results land in `<output>/<scenario>/variation_<variationIndex>/run_seed_<seed>/`. Variation
folders are named deterministically (not by random UUID as in the batched path), so concurrent
array tasks of the same variation share one folder and a re-submitted task reuses it.

`runParams.txt` is written per variation folder, exactly as in the batched path — written only
when absent and moved into place atomically, so concurrent tasks can't produce a torn file.
`errors.txt` is written per *run* folder, so a failing task can't clobber a sibling's report.

## Seed fidelity

The seed is derived from the scenario generator's own default parameters plus the replication
index, never from a hardcoded constant:

```java
seed = generator.getDefaultParameters().getSeed() + replicationIndex;
```

Both execution paths share one private `ScenarioManager.prepareRun(...)`, so this holds by
construction. Verified for 3 dates × 6 replications: the pre-refactor loop arithmetic, the
public `ScenarioManager.seedFor(...)`, and the seed actually attached to the prepared run all
produce 42–47 identically.

Note the seed depends only on the replication index, **not** on the date or variation — two
variations at the same replication index share a seed. That is pre-existing behavior, preserved
deliberately so cluster results stay comparable with the existing local dataset.

## Strict demand mode

By default a missing demand CSV produces a warning and **synthetic** step-wise demand. For a
validation study that would silently fabricate the input, so the batch script passes
`--strict=true`, which:

- checks every date's CSV at **registration** time, before any simulation starts, and aborts
  with a clear per-date listing;
- makes `createVehiclesFromODMatrix` throw rather than fall back, should a file become
  unreadable later — the run exits non-zero and SLURM records the task as failed.

Equivalent switches elsewhere: the scenario parameter `demandCsvStrict`
(`FreiburgNord.KEY_DEMAND_CSV_STRICT`, takes precedence) or `MIROVA_STRICT_DEMAND=1`.

Strict mode also rejects a CSV that exists and parses but carries **no usable demand** — a
truncated file, a period filtered to nothing, or unexpected column/node names that make every
row be skipped. Such an input used to produce a vehicle-free run detected only indirectly, and
late, by the deadlock watchdog; it now fails immediately with the CSV path and the computed
total demand in the message.

The threshold is `demandCsvMinTotal`, default `1.0` veh/h summed over all intervals, origins,
destinations and GTU types. It sits just above zero on purpose: the check is meant to catch a
structurally broken input, not to judge whether traffic was light. A real 13:00–22:00 day sums
to five or six digits here, while every one of those failure modes sums to exactly zero — so a
genuinely quiet measurement period is never rejected.

## Python steps on the cluster

Neither the virtual environment nor the evaluation scripts exist on the cluster, so both Python
invocations are disabled:

| Step | Where | Disabled by |
|:---|:---|:---|
| Demand preparation (`prepare_simulation_demand.py`) | `ScenarioGenerator.prepareSimulationDemand` | scenario parameter `skipDemandPrep` (set by both studies) or `MIROVA_SKIP_DEMAND_PREP=1` |
| Post-run plotting (`plot_scenario_results.py`) | `ScenarioManager.runAll` | `runByGlobalIndex` never invokes it; batched path via `runAll(threads, gui, true)` or `MIROVA_SKIP_POSTPROCESSING=1` |

Skipping demand preparation also matters for correctness: on a cache hit that step
**overwrites** the configured `demandCsv` with its own generated file, discarding the path
passed on the command line.

The two-argument `runAll(threads, gui)` still behaves exactly as before for all existing local
runners. Python paths can be redirected via `MIROVA_PYTHON`, `MIROVA_DEMAND_SCRIPT` and
`MIROVA_PLOT_SCRIPT`.

Post-processing and plotting happen afterwards, off the cluster, on the copied-back output tree.

## Running several runs in one process

`RunMirovaClusterStudy` executes exactly one run per invocation. There used to be a second,
batched entry point for cases where per-run overhead might matter; it has been removed, because
that concern turned out not to exist here — a run takes 90–120 minutes against seconds of JVM
startup, and the two-runs-per-task bundling in `run_mirova.sbatch` already fills the allocation
(49.8% CPU efficiency with one run per task, 95.4% with two).

To run several runs locally in one go — e.g. interactively, without SLURM — loop over the
indices `--count` reports:

```bash
WS=$(ws_find mirova)
CLASS=org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.RunMirovaClusterStudy
N=$(java -cp "$(cat $WS/cp.txt)" $CLASS --study=dates --output=$WS/output/dates \
      --dates=cluster/dates.txt --demand=$WS/demand --count)

for i in $(seq 0 $((N - 1))); do
  java -Xmx6g -XX:ActiveProcessorCount=1 -cp "$(cat $WS/cp.txt)" $CLASS \
    --study=dates --output=$WS/output/dates --index=$i \
    --dates=cluster/dates.txt --demand=$WS/demand --strict=true &
done
wait
```

Add `-P` batching (or `xargs -P`) if you do not want all of them at once. The single-run path is
the same one the cluster uses, so results are identical either way.
