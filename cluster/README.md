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
count into a `ScenarioManager`. Three are registered in `StudyRegistry`:

| Short name | Class | Shape |
|:---|:---|:---|
| `dates` | `FreiburgDateStudy` | One scenario per date, 1 variation each |
| `paramgrid` | `FreiburgParameterStudy` | One scenario, 17 one-at-a-time variations |
| `combos` | `FreiburgCombinationStudy` | Headway combinations × safety distance factors × every date |

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

This study runs the **Cartesian product of two lists**, both in `FreiburgCombinationStudy`:

| List | Contents | Varies |
|:---|:---|:---|
| `COMBINATIONS` | `("standard", 1.00, 1.30)`, `("tighter", 0.90, 1.20)` | car / truck desired headway `T` |
| `SAFETY_DISTANCE_FACTORS` | `0.60`, `0.80` | lane-change safety distance reduction factor |

giving **4 variations per date**. The safety distance factor is applied to **cars and trucks
alike**, so a grid cell is described by two numbers rather than three. Extending either
dimension is one entry in the respective list; nothing depends on their lengths.

Every variation starts from `FreiburgStudyParameters.forDate(...)` and overrides **only** those
values, so a cell differs from the `dates` study in exactly the swept parameters by
construction — and `standard` × `0.60` reproduces that study's setting exactly, since `0.60` is
`FreiburgStudyParameters.RED_FAC`.

Total runs = dates × combinations × factors × replications:

| Dates | Replications | Total |
|---:|---:|---:|
| 3 | 6 | `3 × 2 × 2 × 6 = 72` |
| 9 | 6 | `9 × 2 × 2 × 6 = 216` |
| 32 | 6 | `32 × 2 × 2 × 6 = 768` |

Output folders name the date **and** both swept values, so a result is identifiable without
resolving an index against the source:

```
FreiburgNord_2025-09-22_13-00_to_22-00_standard_sdr0.60/variation_0/run_seed_42/
FreiburgNord_2025-09-22_13-00_to_22-00_standard_sdr0.80/variation_0/run_seed_42/
FreiburgNord_2025-09-22_13-00_to_22-00_tighter_sdr0.60/variation_0/run_seed_42/
FreiburgNord_2025-09-22_13-00_to_22-00_tighter_sdr0.80/variation_0/run_seed_42/
```

The `sdr` suffix is formatted with `Locale.ROOT`, so the decimal separator is a dot on every
node — these names are what post-processing matches on, and must not depend on the format
locale of whichever machine ran the job. `runParams.txt` additionally records
`headwayCombination` and `safetyDistanceFactor`.

Registration order is date-major, then combination, then factor, so
`index = (((dateIndex × 2) + comboIndex) × 2 + factorIndex) × replications + replication`.
Use `--manifest=` to print the mapping rather than deriving it by hand.

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

## Batched mode (fallback)

`RunFreiburgParallelCluster` remains as a secondary entry point: a comma-separated date list
plus `parallelThreads`/`replications`, executed through `ScenarioManager.runAll`. It is not
used by `run_mirova.sbatch` and is kept for scenarios whose per-run overhead is *not*
negligible, and for running several dates in one interactive session. It shares the
`FreiburgStudyParameters` definition with the `dates` study, so the two cannot drift apart.

```bash
java -Xmx48g -cp "$(cat $(ws_find mirova)/cp.txt)" \
  org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.RunFreiburgParallelCluster \
  $(ws_find mirova)/output/batched 16 6 "2025-09-22,2025-09-23" $(ws_find mirova)/demand --strict
```
