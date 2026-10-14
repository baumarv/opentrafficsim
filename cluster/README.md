# Running the Freiburg-Nord studies: locally and on bwUniCluster 3.0

This directory holds everything needed to run a study of the scenario framework in
`org.opentrafficsim.demo.mirova` as independent single runs: on a workstation, or as a SLURM job array.

A study is a list of runs. Each run is one day, one replication (seed), one driver model. The only study on this
branch is `dates`: one scenario per date, one variation each, `--replications` seeds per date.

## What selects what

| Option (command line) | Environment variable (sbatch) | Default | Meaning |
|---|---|---|---|
| `--model=<key>` | `MIROVA_MODEL` | `lmrs` | Driver model. `lmrs` is upstream's LMRS with upstream settings. `MIROVA_TACTICAL_PLANNER` and `--tacticalPlanner` are accepted as the old names. |
| `--preset=<name\|file>` | `MIROVA_PRESET` | none | A named combination of driver model options from `cluster/presets/<name>.preset`. Options given explicitly win over it. |
| `--replications=<n>` | `MIROVA_REPLICATIONS` | 20 (sbatch), 6 (study) | Seeds per date. |
| `--dates=<file\|list>` | `MIROVA_DATES_FILE` | `cluster/dates.txt` | Dates to simulate. |
| `--demand=<dir\|file>` | `MIROVA_DEMAND_DIR` | `<workspace>/demand` | Pre-generated demand CSVs, `demand_<date>.csv`. |
| `--strict=true` | (set by sbatch) | `false` | A missing or empty demand CSV is fatal instead of falling back to synthetic demand. |
| `--simulationTime=<duration>` | — | whole demand window (13:00–22:00) | For smoke tests only, e.g. `900s`. |

## What a run writes

Into `<output>/<scenario>/variation_<i>/run_seed_<seed>/`:

| File | Content |
|---|---|
| `run.properties` | Run metadata, see below. Written before the simulation starts and completed when it ends. |
| `sampler_RoadSampler_[samplingInterval=0.20000000_s].csv.zip` | Trajectories on the merge section `L4a`, every 0.2 s, with the six extended columns. |
| `detector_periodic.csv.zip`, `detector_positions.csv.zip` | Loop detectors on `L3a`, `L5a`, `L7a`: flow and harmonic mean speed per minute, and their positions. |
| `diffused_vehicles.csv` | Header only. No driver model on this branch removes vehicles to dissolve a deadlock; the file is kept so that post-processing finds it. |
| `removed_vehicles.csv`, `removed_vehicles_traces.txt` | One row per vehicle OTS removed after an exception (id, type, time, route destination, link, lane, position, speed, exception, trace hash), and the distinct stack traces by hash. Header only when none was removed. |
| `errors.txt` | Only when the run failed. |

`runParams.txt` lies one level up, in the variation folder.

`run.properties` records: scenario and day (`scenario`, `demand.start`, `demand.end`, `demand.csv`), `seed`,
`replication`, `model`, `preset`, `noLaneChangeDistance`, `start.timestamp`, `end.timestamp`,
`ots.upstream.commit`, `git.commit`, `git.dirty`, `performance.switches`, `jvm.locale`, `jvm.version`, `status`,
what the driver model reported about itself (`model.*`, including every applied parameter override), and
`gtus.removed`.

`performance.switches=none` is literal: the performance changes on this branch are unconditional and have no
runtime switch. Whether a run has them is decided by `git.commit` alone.

## Locally

Build from clean after every checkout, then run:

```bash
MAVEN_OPTS="-Duser.language=en -Duser.country=US" \
    mvn clean install -pl ots-demo -am -Dmaven.test.skip=true -Dmaven.javadoc.skip=true -Djacoco.skip=true

# 15-minute smoke test of one day, a few seconds of wall clock
cluster/run_local_parallel.sh --study=dates --output=out/smoke --slots=1 -- \
    --dates=2025-09-16 --demand=cluster/demand --strict=true --replications=1 --simulationTime=900s

# what would be run, without running it
cluster/run_local_parallel.sh --study=dates --output=out/smoke --dry-run -- \
    --dates=cluster/dates.txt --demand=cluster/demand --strict=true --replications=2
```

The locale is pinned for the build because `ots-xml` generates its JAXB classes into the tracked source tree, and
the generator writes its Javadoc through a localised bundle. Without the pin a build on a German machine rewrites the
generated files and leaves the tree dirty.

The runner pins `-Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8` for every run. Do the same in an IDE
run configuration: without it the same network XML can parse differently on a machine with a decimal comma, and the
unit of one trajectory column is written in another byte encoding.

## Watching a run in the GUI

Run the class `org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.RunFreiburgNordGui` from the IDE; it
takes no arguments. Its settings are code at the top of the class, written with OpenTrafficSim's own types:

```java
private static final LocalDate DATE = LocalDate.of(2025, 9, 22);
private static final long SEED = 42L;

private static LmrsSettings lmrsSettings()
{
    return new LmrsSettings()
            .set(Setting.SYNCHRONIZATION, Synchronization.PASSIVE)
            .set(Setting.COOPERATION, Cooperation.PASSIVE)
            .set(Setting.GAP_ACCEPTANCE, GapAcceptance.INFORMED)
            .set(Setting.INCENTIVE_KEEP, true)
            // ...
}
```

As committed every setting has upstream's value, so the GUI shows the benchmark. On start the class prints the
composition read back from the factory that was built, and which settings differ from upstream
(`car.settings=upstream defaults` or `car.settings=changed: SYNCHRONIZATION, ...`).

`cluster/run_gui.sh` starts the same class from a shell and builds the class path. A GUI run is for looking: it
writes its output only when the simulation reaches its end, and no `run.properties`.

## Composing the LMRS differently

The same options work for evaluated runs, after `--` in `run_local_parallel.sh` and in `MIROVA_STUDY_OPTS` on the
cluster:

| Option | Values (upstream default first) |
|---|---|
| `--lmrs.synchronization` | `PASSIVE`, `PASSIVE_MOVING`, `ALIGN_GAP`, `ALIGN_GAP_MOVING` |
| `--lmrs.cooperation` | `PASSIVE`, `NONE`, `PASSIVE_MOVING` |
| `--lmrs.gapAcceptance` | `INFORMED`, `EGO_HEADWAY` |
| `--lmrs.fullerImplementation` | `NONE`, `SUMMATIVE`, `ANTICIPATION_RELIANCE`, `ATTENTION_MATRIX` |
| `--lmrs.incentiveRoute`, `…SpeedWithCourtesy`, `…Keep` | `true`, `false` |
| `--lmrs.incentiveGetInLane`, `…Courtesy`, `…Queue`, `…StayRight` | `false`, `true` |
| `--lmrs.accelerationNoSlowLaneOvertake`, `--lmrs.accelerationSpeedLimitTransition` | `false`, `true` |
| `--lmrs.socialPressure`, `--lmrs.tailgating`, `--lmrs.socioLaneChange`, `--lmrs.socioSpeed` | `false`, `true` |
| `--car.<parameter>=<value>`, `--truck.<parameter>=<value>` | any parameter of `ParameterTypes` or `LmrsParameters`, with its unit: `--car.T=1.1s`, `--car.vGain=50km/h`, `--car.dCoop=0.8` |

Only what is named is set. `run.properties` records the composition read back from the factory that was built:
every setting as `model.setting.<class>.<SETTING>`, the ones that differ from upstream in
`model.<class>.settings=changed: ...`, and parameter overrides as `model.override.*`. A name or value the provider
does not know stops the run during setup. A run whose settings line does not say `upstream defaults` is not the
upstream-default benchmark.

## Presets

A preset is a file in `cluster/presets/` with one driver model option per line, the same keys the command line
takes (`lmrs.<setting>`, `car.<parameter>`, `truck.<parameter>`). It gives a combination a name, so that it is
selected explicitly and recorded by that name (`preset=` in `run.properties`), beside the composition read back
from the factory.

| Preset | Content |
|---|---|
| `moving-ego-headway` | `synchronization=ALIGN_GAP_MOVING`, `cooperation=PASSIVE_MOVING`, `gapAcceptance=EGO_HEADWAY`; everything else as upstream. |

```bash
cluster/run_local_parallel.sh --study=dates --output=out/preset --slots=2 -- \
    --dates=2025-09-22 --demand=cluster/demand --strict=true --replications=2 --preset=moving-ego-headway
MIROVA_PRESET=moving-ego-headway sbatch --chdir="$(ws_find mirova-ots)" cluster/run_mirova.sbatch
```

## On bwUniCluster 3.0

The cluster has no Java or Maven modules. `build_for_cluster.sh` provisions JDK 17 (Adoptium) and Maven 3.9.9 as
tarballs into `<workspace>/tools`, builds, writes the classpath to `<workspace>/cp.txt`, records the commit it
built in `<workspace>/cp_build.env`, and copies the demand CSVs into `<workspace>/demand`.

```bash
ws_allocate mirova-ots <days>
export MIROVA_WORKSPACE=mirova-ots
cd "$(ws_find mirova-ots)" && git clone -b ots-upstream-benchmark <repository url> opentrafficsim && cd opentrafficsim

cluster/build_for_cluster.sh

# number of runs N of the study; the array is 0..ceil(N/2)-1 because each task runs two
java -cp "$(cat "$(ws_find mirova-ots)/cp.txt")" \
    org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.RunMirovaClusterStudy \
    --study=dates --output="$(ws_find mirova-ots)/output/dates" \
    --dates=cluster/dates.txt --demand="$(ws_find mirova-ots)/demand" --replications=20 --count

export MIROVA_CLUSTER_DIR="$PWD/cluster"     # required, see below
MIROVA_DRY_RUN=1 SLURM_ARRAY_TASK_ID=0 bash cluster/run_mirova.sbatch      # prints the commands of task 0
sbatch --chdir="$(ws_find mirova-ots)" cluster/run_mirova.sbatch
```

Three things about the batch script that are deliberate:

- **`MIROVA_CLUSTER_DIR` must be exported before `sbatch`.** SLURM copies the script's content into a spool file and
  runs that copy, so the script cannot locate its own directory.
- **One array task runs two simulation runs.** On the `cpu` partition SLURM allocates two logical CPUs per task
  anyway (one physical core), so the second one carries a second run. Task `T` runs the global indices `2T` and
  `2T+1`; an odd total is fine. `MIROVA_RUNS_PER_TASK` changes it.
- **`--array`, `--mem-per-cpu` and `--time` are placeholders inherited from the MiRoVA campaigns.** Measure one full
  LMRS day before a production submission; see the header of `run_mirova.sbatch`.

`cluster/UPSTREAM_COMMIT` names the upstream OpenTrafficSim commit the branch is based on. Update it whenever the
branch is moved onto a newer upstream; every run copies it into its metadata.

## Files

| File | Purpose |
|---|---|
| `run_mirova.sbatch` | SLURM array script. |
| `build_for_cluster.sh` | Toolchain, build, classpath, build record, demand. |
| `mirova_env.sh` | Workspace resolution and toolchain provisioning, shared by the two above. |
| `run_local_parallel.sh` | The same runs on a workstation, N JVMs at a time. |
| `run_gui.sh` | Starts `RunFreiburgNordGui` from a shell. |
| `guard.sh`, `test_guard.sh` | Preconditions that end a script instead of printing a value, and their test. |
| `UPSTREAM_COMMIT` | The upstream commit the branch is based on. |
| `dates.txt`, `dates_calibration.txt`, `dates_extension.txt` | Date lists. |
| `demand/`, `generate_demand_csvs.py`, `generate_demand_csvs.ps1` | Demand per date, and how it was generated. |
