# MiRoVA Scenario Management & Cluster Architecture — Overview

**Scope:** This document describes the **orchestration / scenario-management layer**
(`ots-demo/.../mirova/scenariomanagement/`) and the cluster-tooling layer built on top of it
(`cluster/`). It does **not** describe the tactical-cognitive driving-behavior architecture (the
layered BDI model, `ContextManager`, `KnowledgeChunk`, `PatternSelector`, `ManeuverPattern`, in
`ots-road/.../tactical/mirova/`) — that is documented in
[layer1_perception_belief.md](layer1_perception_belief.md) through
[layer4_reactive_control.md](layer4_reactive_control.md), [arbitration.md](arbitration.md) and the
ITSC paper, and is unaffected by any of this.

Companion document: [troubleshooting_and_compilation.md](troubleshooting_and_compilation.md) —
this one is *what connects to what*, that one is *what breaks and how to fix it* (JAXB ClassLoader
issues, `.m2` sync, fast-build flags, inconsistent `ots-xml` state).

Status: after the cluster migration (workspace, global run addressing, bundling), the baseline
unification and the facility generalization. Update this when structural changes land.

---

## 1. The core idea

A **study** (`StudyDefinition`) describes *what* to simulate: which scenarios, with which parameter
variations, how many replications. The **`ScenarioManager`** executes it — locally as a batch with a
thread pool, or on the cluster as one individually addressable simulation per SLURM array task. Both
execution paths run through the **same enumeration logic**, so a run started locally or on the
cluster always gets the same seed and the same parameters.

```
StudyDefinition               ScenarioManager                    ScenarioGenerator
  (what to simulate?)   →       (how to execute?)           →      (what IS the scenario?)
  dates/paramgrid/combos         enumeration, seeds,                FreiburgNord: network,
                                 output folders, parallelism        GTU templates, OD matrix, ...
```

---

## 2. The engine classes (`scenariomanagement/`, excluding `scenarios/`)

| Class | Role |
|---|---|
| **`ScenarioManager`** | The execution engine. Manages registered scenarios + parameter variations, computes seeds (`seedFor`), executes runs — either as a full batch (`runAll`) or individually addressed via a global index (`runByGlobalIndex`, `countRuns`, `describeRuns`). Both paths share the same internal enumeration (scenario → variation → replication) — this is the foundation of cluster addressing. |
| **`ScenarioGenerator`** (abstract) | Defines *what* a scenario is: road network, GTU templates, routes, OD matrix/demand, sampling configuration. Every concrete traffic facility is a subclass of this. |
| **`ScenarioParameters`** | A typed key-value container for everything configurable (seed, timings, demand, arbitrary car/truck parameters). `applyOverridesFrom(...)` merges two instances — the mechanism by which a variation overrides the baseline. |
| **`ScenarioSimulationScript`** / **`AbstractSimulationScriptBase`** | The actual simulation execution (DSOL integration, `runHeadless`, watchdog deadlock detection). Not a user entry point — instantiated per run by `ScenarioManager`. |
| **`ParameterGridBuilder`** | Builds either a full Cartesian product (`build()`) or a one-at-a-time sweep (`buildIsolated()`) over several parameter dimensions from a baseline. Foundation of the `paramgrid` study. |
| **`ScenarioOutputConfiguration`** | What and how output is recorded: samplers, loop detectors, time windows, extended-data types (the `ExtendedData*` classes in `ots-road`), CSV/ZIP output. |
| **`StudyDefinition`** (interface) | The contract for a study: `getName()`, `getDescription()`, `register(ScenarioManager, Map<String, String>)`. Registration must be **deterministic** — a prerequisite for global-index addressing across independently started processes. |
| **`StudyRegistry`** | Resolves a study short name (`dates`, `paramgrid`, `combos`) to its `StudyDefinition` implementation. A new study can also run by its fully qualified class name without being registered here. |
| **`TrafficFacility`** (interface) | What a study needs to know about the facility it simulates: generator class, behavioural baseline, scenario naming, per-date parameters. See Section 8. |
| **`FacilityRegistry`** | Resolves a facility short name (`freiburg`) to its `TrafficFacility` implementation, or accepts a fully qualified class name. Mirrors `StudyRegistry`. |

There are **three** concrete `ScenarioGenerator` subclasses: `FreiburgNord` (the only one the
cluster studies use), plus `MergeScenario` and `SimpleHighwayScenario`, which are driven by their
own local runners.

---

## 3. The three current studies (`scenariomanagement/scenarios/`)

All three obtain their behavioral baseline from the facility (Section 8), which for Freiburg is
**`FreiburgStudyParameters.baseBehaviorParams()`** — the shared behavioral baseline (`T`, `vGain`, `A_MAX`, cooperation thresholds, relaxation/capacity-drop flags etc. for car
and truck). That is the single place these values are defined; all three studies only override what
they actually vary.

| Study | Short name | Varies | Built on |
|---|---|---|---|
| **`DateStudy`** | `dates` | One fixed parameter combination across multiple days; **facility-agnostic**, `--facility=` defaults to `freiburg` | `TrafficFacility.forDate(...)` |
| **`FreiburgParameterStudy`** | `paramgrid` | Several parameter dimensions (one-at-a-time sweep) on a fixed period | `ParameterGridBuilder.buildIsolated()` |
| **`FreiburgCombinationStudy`** | `combos` | Named parameter combinations across multiple days — currently headway pairs × safety-distance factors | Cartesian product of two fixed lists, each cell from `forDate(...)` |

**When to use which?** `dates` for the plain multi-day validation study (one parameter set, many
days, many replications). `paramgrid` for "how does the model react to changes in individual
parameters" (sensitivity analysis around a baseline). `combos` for "I want to compare a handful of
specific, hand-picked parameter sets against each other" (not a systematic sweep, but targeted
cases) — structurally what `RunFreiburgParallel.java` does locally / ad hoc, just cluster-capable
and across days.

`FreiburgNord` itself (592 lines) is the concrete `ScenarioGenerator` implementation: network, GTU
templates, routes, OD-matrix parsing including time-window slicing/re-basing, strict-demand
validation, watchdog configuration.

---

## 4. How a run is addressed

A single simulation run is uniquely determined by three coordinates: **scenario (registration order)
→ variation (list order) → replication index.**

`ScenarioManager` walks this structure in exactly the same order whether via `runAll` (full batch,
local) or `runByGlobalIndex(n)` (single run, cluster) — both call the same private `enumerateRuns()`
and the same `prepareRun(...)`.

A run's **seed** is `generator.getDefaultParameters().getSeed() + replicationIndex` — note it comes
from the *generator's* defaults, not from the caller's variation, and it depends only on the
replication index. Replication 3 therefore gets the same seed on every date and in every variation
(here: 45). That is intentional (comparability across days), but easy to mistake for "unique seed
per run."

**Why this matters:** because both execution paths share the same enumeration, `--index=17` on the
cluster produces exactly the run (scenario, parameters, seed) that a local `runAll` batch would have
produced at position 17 — verified, not just assumed.

---

## 5. The cluster entry point: `RunMirovaClusterStudy`

```
RunMirovaClusterStudy --study=<name|class> --output=<dir> (--count | --manifest=<file> | --index=<n>) [--key=value ...]
```

- `--count` — total number of runs for this study (with the given options), no simulation.
- `--index=<n>` — executes exactly this one run (0-based, global index).
- `--manifest=<file>` — writes a human-readable index → scenario / variation / replication / seed /
  parameters table, purely informational, not part of execution logic.
- Any further `--key=value` is passed through to the selected study, which documents which keys it
  honors (e.g. `--demand=`, `--dates=`, `--replications=`, `--strict=true`).

Adding a new study requires **no** change to this entry point or the sbatch script — only a new
`StudyDefinition` implementation, optionally a short-name entry in `StudyRegistry`.

---

## 6. The cluster-tooling layer (`cluster/`)

| File | Role |
|---|---|
| `mirova_env.sh` | Single source for workspace resolution (`resolve_workspace`, requires `MIROVA_WORKSPACE`, no `$HOME` fallback) and Java/Maven toolchain activation (`activate_toolchain`) — since bwUniCluster 3.0 provides no Java/Maven module, both are provisioned as tarballs into the workspace. |
| `build_for_cluster.sh` | Builds the project (`mvn install -pl ots-demo -am -Dmaven.test.skip=true -Dmaven.javadoc.skip=true -Djacoco.skip=true`), provisions Java/Maven idempotently with download validation, writes `cp.txt` (classpath). |
| `run_mirova.sbatch` | The SLURM job array script. One array task = two bundled individual runs (global indices `2×TaskID` and `2×TaskID+1`). Each of the two runs gets `-XX:ActiveProcessorCount=1` and its own log file; CPU affinity is read from the task's own affinity mask at runtime (`taskset -cp $$`), never assumed. Requires `MIROVA_CLUSTER_DIR` — `sbatch` runs a *copy* of the script from the job spool directory, so it cannot locate its own directory. |
| `dates.txt` | The date list for the `dates`/`combos` studies — currently a placeholder (9 dates carried over from the earlier nine-date study), still to be replaced with the real 32 dates. |
| `generate_demand_csvs.ps1` / `.py` | Generate the full-day demand CSVs on the Windows workstation, where the detector database is reachable. Never run on the cluster. |
| `README.md` | Operational guide: workspace allocation, build, array sizing, submission, studies, calibration. |
| `demand/` | One full 24-hour demand CSV per date (`demand_{date}.csv`), from which each study slices its own time window. Generated, therefore **git-ignored** — not part of the repository. |

**Why one array task = two runs, not one:** The original decision was "one run per task" (maximum
scheduling elasticity), since JVM/JAXB warmup overhead is negligible against a 90–120 min run.
Empirically, the partition allocates at least 2 cores per task regardless — one core would otherwise
sit idle (measured: 49.8% CPU efficiency with one run per task, 95.4% with two bundled runs on a
normally loaded node). The script now requests `--cpus-per-task=2` explicitly rather than relying on
that rounding.

Because two JVMs then live inside a limit of `2 × --mem-per-cpu`, the memory constraint is
`2 × MIROVA_JAVA_HEAP < 2 × mem-per-cpu − (2 × off-heap per JVM)`; the limit is per *task*, so
overshooting OOM-kills the healthy run along with the greedy one. See `cluster/README.md`.

**Known noise source, not a bug:** on a cluster with `OverSubscribe=OK`, a neighboring job on the
same node can noticeably slow down your own run (observed: ~5–6× on one specific node). Not a
concern for a retry on a different node, but worth accounting for in walltime calculations for the
full study.

---

## 7. Cleanup: done

Four superseded classes were removed after confirming, for each, that nothing referenced them —
including as string literals, in case of reflective lookup by name:

- `Scenario.java` — a parameter holder superseded by `ScenarioParameters`.
- `Run9DatesLargeStudy.java` — superseded by the date study plus `cluster/dates.txt`.
- `RunFreiburgParallel_ParameterStudy.java` — superseded by `FreiburgParameterStudy`, which was
  extracted from it and then corrected: its inline baseline had drifted from the evaluation study's.
- `RunFreiburgParallelCluster.java` — a batched secondary cluster entry point, kept while per-run
  overhead was still an open question. It no longer is: the two-runs-per-task bundling in
  `run_mirova.sbatch` fills the allocation better than a batched entry point would.

One class was deliberately **kept**:

- **`TestReflection.java`** — despite the name and the absence of references, this is not scratch
  code but a diagnostic for a recurring problem. It enumerates the generated
  `org.opentrafficsim.xml.generated.*` classes **as the JVM sees them**, loads each one and calls
  `getDeclaredFields()` on it and its inner classes, and reports every class that fails with a
  `NoClassDefFoundError` — precisely the failure mode of
  [troubleshooting_and_compilation.md](troubleshooting_and_compilation.md) issues 1 and 5, the
  GlassFish JAXB annotation reader tripping over an inconsistent `ots-xml` artifact. It also prints
  which artifact the classes came from (`.m2` JAR vs. a module's `target/classes`), which is usually
  the actual answer. Run it with the same classpath as the failing simulation; it exits 1 when any
  class fails. See the "Diagnosis" block of issue 5.

**Before deleting anything else:** verify nothing still references it — including by name as a
string — and don't remove on suspicion alone.

---

## 8. Facility generalization

"Where do we simulate" is now an abstraction of its own, so a new traffic facility does not require
rewriting the study layer.

| Piece | Role |
|---|---|
| **`TrafficFacility`** (interface) | `getName()`, `getGeneratorClass()`, `baseBehaviorParams()`, `scenarioName(date, suffixParts...)`, `forDate(date, demandCsvPath, strict)` — everything a study needs to know about *where* it simulates. |
| **`FacilityRegistry`** | Resolves a short name (`freiburg`) to an implementation, or a fully qualified class name for one not registered. Mirrors `StudyRegistry` exactly. |
| **`FreiburgFacility`** | The first implementation. A deliberately thin adapter over `FreiburgStudyParameters`: the baseline values stay where they are, because an empirical dataset is being collected against them and moving numbers is how numbers change. |

`DateStudy` (formerly `FreiburgDateStudy`) is now **facility-agnostic** — a date list crossed with one
fixed parameter set is a generic concept. It takes `--facility=`, defaulting to `freiburg`, and is
still registered under the unchanged short name `dates`.

`FreiburgParameterStudy` and `FreiburgCombinationStudy` keep their Freiburg-specific *content* — which
dimensions to sweep, which named combinations — because designing a facility-agnostic way to declare
that, without a second real facility to validate the shape against, would be guessing. They do resolve
their generator class and baseline through `FacilityRegistry` like `DateStudy` does, so the coupling
point is uniform even where the content is not.

Adding a facility therefore means: a `ScenarioGenerator` subclass, a `TrafficFacility` implementation,
and optionally a short name in `FacilityRegistry`. The `dates` study then works on it immediately; a
sweep or combination study for it is still a new class, until a second facility shows what the generic
shape should be.

The generalization was a pure refactor: all 372 runs of the three studies (54 + 102 + 216) produce
byte-identical scenario names, variation ordering, seeds and parameter maps before and after, verified
by diffing the manifests and one full executed run.

---

## 9. Quick reference: how do I...

**...run a single scenario locally:** edit and run `RunFreiburgParallel.java` (the scratch space —
occasionally extracted into a real study, see `combos`).

**...run an existing study on the cluster:** `--study=<name> --count` for the total run count N, then
`sbatch` with `--array=0-<ceil(N/2)-1>` (bundling — two runs per task, and an odd N simply leaves the
last task with one run) and the study-specific `--key=value` options via `MIROVA_STUDY_OPTS`.

**...add a new study:** a new class implementing `StudyDefinition`, built on
`FreiburgStudyParameters.baseBehaviorParams()`, registering scenarios/variations deterministically.
Optionally add a short-name entry in `StudyRegistry`.

**...add a new traffic facility:** a `ScenarioGenerator` subclass, a `TrafficFacility` implementation
(generator class, baseline parameters, scenario naming, per-date parameters), and optionally a short
name in `FacilityRegistry`. The `dates` study then runs on it via `--facility=<name>` with no code
change. A parameter or combination study for the new facility is still a separate class — see
Section 8 for why that content is deliberately not generalized yet.
