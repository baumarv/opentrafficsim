# Running MiRoVA Studies on bwUniCluster 3.0 (SLURM)

Runs a MiRoVA study on the cluster as a SLURM job array.

**One array task = one simulation run = one core**, and
**`SLURM_ARRAY_TASK_ID` *is* the run's global index** — there is no lookup table.

## Why one run per task

A single Freiburg-Nord run takes **90–120 minutes** wall-clock, while JVM startup and JAXB
warm-up cost seconds — **under 0.5% of a run**. Bundling runs to amortize startup therefore
optimizes something that isn't a cost.

What matters is scheduling elasticity. A task asking for one core can be backfilled into any
free core anywhere, at any time, independently of every other task. A task asking for N cores
must wait for N cores to line up on one node. With ~200 independent, hour-scale runs, one run
per task is strictly easier for SLURM to place, and partial progress accumulates immediately.

## Files

| File | Purpose |
|:---|:---|
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

This prints a single integer N and runs nothing. Set `#SBATCH --array=0-<N-1>`.

For the date study: 32 dates × 6 replications → `192` → `--array=0-191`.
For the parameter study: 17 variations × 6 replications → `102` → `--array=0-101`.

Optionally add `--manifest=$WS/output/manifest.tsv` to also write a human-readable
`index → scenario / variation / replication / seed / parameters` table. It is **informational
only** — execution addresses runs by index through the deterministic Java enumeration and
never reads this file.

## 5. Submit

```bash
sbatch --chdir="$(ws_find mirova)" cluster/run_mirova.sbatch
```

`--chdir` makes the relative `logs/` paths in the `#SBATCH --output`/`--error` directives land
in the workspace (those directives are literal and cannot call `ws_find` themselves).

Configure via environment variables — no need to edit the script:

| Variable | Default | Meaning |
|:---|:---|:---|
| `MIROVA_WORKSPACE` | *(required)* | Workspace name, resolved via `ws_find` |
| `MIROVA_STUDY` | `dates` | Study short name or `StudyDefinition` class name |
| `MIROVA_STUDY_OPTS` | `--dates=… --demand=… --strict=true` | Options passed to the study |
| `MIROVA_DEMAND_DIR` | `<ws>/demand` | Pre-generated demand CSVs |
| `MIROVA_OUTPUT_ROOT` | `<ws>/output/<study>` | Results root |
| `MIROVA_DATES_FILE` | `cluster/dates.txt` | Date list for the date study |
| `MIROVA_JAVA_HEAP` | `6g` | `-Xmx` for the JVM |

Example — run the parameter study instead:

```bash
export MIROVA_STUDY=paramgrid
export MIROVA_STUDY_OPTS="--demand=$(ws_find mirova)/demand --strict=true"
sbatch --chdir="$(ws_find mirova)" --array=0-101 cluster/run_mirova.sbatch
```

## 6. Resources per task

`--cpus-per-task=1` is correct: `AbstractSimulationScriptBase.runHeadless()` drives
`simulator.step()` in a plain `while` loop **on the calling thread** — no DSOL worker thread,
no executor inside a run. The script also sets `-XX:ActiveProcessorCount=1` so the JVM's GC
and JIT threads don't oversubscribe the single allocated core.

`--time=03:00:00` gives ~50% headroom over the measured 90–120 min. `--mem-per-cpu` is a
**placeholder for one run** — measure a single run's peak RSS locally, then set it together
with `MIROVA_JAVA_HEAP` (keep the heap below `--mem-per-cpu` for JVM off-heap memory).

---

## Studies

A study is a `StudyDefinition`: it registers scenarios, parameter variations and a replication
count into a `ScenarioManager`. Two are registered in `StudyRegistry`:

| Short name | Class | Shape |
|:---|:---|:---|
| `dates` | `FreiburgDateStudy` | One scenario per date, 1 variation each |
| `paramgrid` | `FreiburgParameterStudy` | One scenario, 17 one-at-a-time variations |

**Adding a third study requires no change to the batch script or the entry point** — write a
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
| `--start=`, `--end=` | `2025-09-25 13:00:00` / `16:00:00` | Simulated period |
| `--replications=` | `6` | Replications per variation |
| `--strict=` | `false` | Missing CSV is fatal |

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
