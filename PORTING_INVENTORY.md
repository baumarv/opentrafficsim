# Porting inventory — Freiburg scenario, cluster runs and performance work onto current upstream OTS

Status: **approved on 2026-10-13**, with the decisions recorded in section 8. The port has been carried out; what was
done and where it deviates is in `PORTING_NOTES.md` and `PERFORMANCE_NOTES.md`. Sections 0 to 7 are the inventory as
it was approved and are not updated to the state after the port.

## 0. Git setup (Phase 0)

| Item | Value |
|---|---|
| Upstream remote | `upstream` = `https://github.com/averbraeck/opentrafficsim` |
| Upstream default branch | `main` (there is no `master`) |
| New branch | `ots-upstream-benchmark`, pushed to `origin` |
| Based on upstream commit | `78f2601dd80aacdfd4950c6bfbe899ee904afee7` (OTS 1.8.1, 2026-10-09, "Incorporate cooperation triggered by indicator alone. (Issue #340)") |
| Source of the port | `origin/main` @ `f41175d53` |
| Merge base of source and upstream | `81241d09fc20976d61dcd8b7b7f5a5021ec786b1` (OTS 1.7.6, 2025-05-26) |
| Distance | source is 488 commits ahead of the merge base, upstream is 416 ahead |

Two deviations from the brief:

- The brief names `laneChangeIncentive_Reengineering` as the source. That branch exists neither locally nor on
  `origin`; `origin/main` was used instead, as decided. It is not modified.
- The brief says `upstream/master`. Upstream's branch is `main`.

The new branch is checked out as a separate git worktree (`../opentrafficsim-upstream-benchmark`), so the working
tree of the main checkout, which has uncommitted changes on `solve-parallel-anticipation`, is untouched.

## 1. What the diff consists of

`git diff 81241d09f origin/main` reports 1088 files. Most of it is not content:

| Share | Files | What it is |
|---|---|---|
| Eclipse metadata deleted | 181 | `.settings/`, `.classpath`, `.project` in every module |
| Generated JAXB sources | 255 | `ots-xml/.../generated`, `ots-opendrive/.../generated` — line endings only, **zero** content changes |
| Line-ending-only rewrites in `ots-road` | about 340 | whole-file CRLF/LF changes with no content change |
| Real changes | about 310 | classified below |

Real changes were isolated with `git diff --ignore-cr-at-eol --ignore-space-at-eol`. Outside the MiRoVA package,
`ots-demo`, `cluster/` and `docs/`, only 14 source and build files carry a real change. They are all listed in
sections D and E.

## 2. Classification

### A — Scenario

All under `ots-demo/src/main/java/org/opentrafficsim/demo/mirova/scenariomanagement/` unless a path is given.
"MiRoVA" in the last column means the file imports classes from `org.opentrafficsim.road.gtu.lane.tactical.mirova`.

**Framework**

| File | Role | MiRoVA dependency |
|---|---|---|
| `ScenarioGenerator.java` (1538 lines) | Abstract scenario: network, templates, routes, OD from CSV, demand preparation, seeds, planner selection | **Yes** — see section 6 |
| `ScenarioManager.java` | Variations, replications, run folders, `runParams.txt`, `errors.txt`, parallel execution | Calls `XmlParser.warmUpJAXBContext()` (item D3) |
| `ScenarioParameters.java`, `ParameterGridBuilder.java` | Parameter sets and grids | No |
| `ScenarioSimulationScript.java`, `AbstractSimulationScriptBase.java` | Headless and GUI run loop (a copy of upstream `AbstractSimulationScript`) | No |
| `ScenarioOutputConfiguration.java` | Output writers: `trajectory_data.csv`, `sampler_*.csv`, `detector_periodic.csv.zip`, `detector_positions.csv.zip`, `diffused_vehicles.csv` | No |
| `TacticalPlannerProvider.java` | Existing model-selection interface (ServiceLoader) | No |
| `StudyDefinition.java`, `StudyRegistry.java`, `TrafficFacility.java`, `FacilityRegistry.java` | Study and facility registries | No (registry lists MiRoVA studies) |
| `BuildProvenance.java` | Per-run `build.txt` | No |
| `libraries/DesiredSpeedLibrary.java`, `libraries/HeadwayDistributionLibrary.java` | Desired-speed and headway distributions | No |
| `ParameterTableGenerator.java` | Parameter table for documentation | Yes (`MirovaParameters`) — not needed for the benchmark |

**Freiburg scenario**

| File | Role | MiRoVA dependency |
|---|---|---|
| `scenarios/FreiburgNord.java` (713 lines) | The scenario: network, templates, routes, OD, detectors, sampler | **Yes** — see section 6 |
| `scenarios/FreiburgFacility.java` | Facility descriptor | No |
| `scenarios/DateStudy.java` | One run per date and replication, the study behind `dates` | No |
| `scenarios/FreiburgStudyParameters.java` | Shared study parameters | Yes — almost entirely MiRoVA parameter overrides |
| `scenarios/RunFreiburgNord.java`, `RunFreiburgNordNoDemand.java`, `RunFreiburgParallel.java`, `RunFreiburgMergeWatch.java` | Local and GUI runners | Yes, via `MirovaParameters` (except `NoDemand`) |
| `ots-demo/src/main/resources/mirova/FreiburgNord.xml` | Network | No |

**Tests that belong to A**: `DesiredSpeedCompressionTest`, `DesiredSpeedShiftTest`, `DesiredSpeedTruncationTest`,
`RunIdentityIT`, `TrajectoryColumnsIT`, `scenarios/PlannerOverrideRefusalTest`.

**In `ots-demo` but not part of the Freiburg benchmark** (proposed: not ported; say so if you want any of them):

- Other scenarios: `scenarios/MergeScenario.java` (26 MiRoVA imports, Bodegraven), `scenarios/SimpleHighwayScenario.java`,
  `RunMerge`, `RunParallelMergeScenarios`, `RunSimpleHighwayScenarios`, `TestReflection`, `mirova/SimpleSimulation.java`,
  `resources/mirova/MergeBodegraven.xml`.
- 21 MiRoVA parameter studies (`Freiburg*Study`, `VGain*Study`, `Phase05ReferenceStudy`) — they vary MiRoVA parameters
  and mean nothing under LMRS.
- 9 TaMA studies and `RunTamaRampEndGui` (`Tama*Study`) — they belong with the later TaMA adapter.
- `fsmtrace/` (harness, export, regression test and two trace files) — MiRoVA state-machine tracing.

### B — Cluster

| File | Role |
|---|---|
| `cluster/run_mirova.sbatch` | SLURM array, 2 runs per task, requires `MIROVA_CLUSTER_DIR`, stub and build-stamp checks |
| `cluster/mirova_env.sh` | Workspace resolution, JDK 17 (Adoptium) and Maven 3.9.9 tarball toolchain |
| `cluster/build_for_cluster.sh` | `mvn clean install -pl ots-demo -am -Dmaven.test.skip=true -Dmaven.javadoc.skip=true -Djacoco.skip=true`, then `cp.txt` |
| `cluster/stamp_build.sh`, `cluster/guard.sh` | Build stamp (`mirova-build.properties`) and precondition guards |
| `cluster/test_guard.sh`, `cluster/test_stamp_build.sh`, `cluster/test_tama_classpath_guard.sh` | Tests of the above |
| `cluster/run_local_parallel.sh`, `cluster/run_gui.sh` | Local parallel runs and GUI runs |
| `cluster/dates.txt`, `dates_calibration.txt`, `dates_extension.txt` | Date lists |
| `cluster/demand/demand_*.csv` (32 files), `generate_demand_csvs.py`, `generate_demand_csvs.ps1` | Pre-generated demand per date |
| `cluster/sum_defects.py`, `cluster/README.md` | Evaluation helper, documentation |
| `scenarios/RunMirovaClusterStudy.java` | Cluster entry point: `--study`, `--output`, `--count`, `--index`, `--tacticalPlanner`, study options |
| `ots-demo/.../ClusterTamaSupportTest.java` | Test of the TaMA classpath handling |
| `ots-demo/pom.xml` | Adds `jakarta.xml.bind-api`, `jaxb-runtime`, and a `tama` profile (runtime dependency on `tama-ots-bundle`) |
| `.gitattributes`, `.mvn/jvm.config`, root `pom.xml` (surefire `argLine`), `ots-xml/pom.xml`, `ots-opendrive/pom.xml`, `ots-xml/.../SchemaIncludesTest.java` | Build determinism: LF for scripts and generated sources, pinned locale, sorted schema list. The build stamp refuses a dirty tree, so the cluster build depends on these |

`test_tama_classpath_guard.sh`, `sum_defects.py` (MiRoVA defect counts) and the `tama` profile are TaMA- or
MiRoVA-specific; proposed to leave them out until the TaMA adapter arrives.

The Java version does not change (17), so the JDK tarball reference stays as it is.

### C — MiRoVA model (not ported)

| Location | Files |
|---|---|
| `ots-road/.../tactical/mirova/` (main) | 86: root 5, `core/` 49, `util/` 32 |
| `ots-road/.../tactical/mirova/` (test) | 2 |
| `ots-road/src/test/.../tactical/MirovaStateVocabularyTest.java` | 1 |
| `ots-animation/.../AnimationGtuData.java` | 202 added lines of MiRoVA getters for the GUI context window |
| `ots-demo/.../CircularRoadModel.java`, `CircularRoadSwing.java` | MiRoVA wired into an upstream demo |
| `docs/mirova/`, `docs/decoupling/`, `docs/campaigns/` and other fork docs, `CLAUDE.md`, `.agents/AGENTS.md`, `parallelization_feasibility_report.md` | 57 documentation files plus root notes |

Helpers inside the MiRoVA package that the scenario uses and that are **not** model code — these need a decision
(section 7, question 2):

| Class | Model imports | Used by |
|---|---|---|
| `util/logging/extendeddata/ExtendedDataGtuType` | none | `FreiburgNord` sampler column |
| `util/logging/extendeddata/ExtendedDataActionState`, `ExtendedDataLaneChangeDesireLeft`, `ExtendedDataLaneChangeDesireRight` | none; they read `DriverStateObservable` (item E1) | `FreiburgNord` sampler columns |
| `util/logging/extendeddata/ExtendedDataAccelerationDamping`, `ExtendedDataCurrentCFAcceleration` | one MiRoVA import each | `FreiburgNord` sampler columns |
| `util/logging/GtuDeletionDiagnostics` | none (implements `GtuErrorHandler`) | `FreiburgNord` as `OdOptions.ERROR_HANDLER` |

### D — Performance enhancements to OTS core

There are three candidates. No other performance change to OTS core exists on the source branch: the remaining
performance work is inside MiRoVA (tick caches, parameter snapshot, reduced perception queries) and is not ported.

| # | What it does | Files | State on source | Model-independent | Upstream today | Recommendation |
|---|---|---|---|---|---|---|
| **D1** | `LaneBasedGtu.CACHING = false`: switches off the `MultiKeyMap` position cache, whose key hashing cost more than the cache saved. Measured at about −16 % CPU over a production day, byte-identical output | Not a core edit. Set from `ScenarioGenerator.applyGtuPositionCaching()`, switch `-Dmirova.gtuPositionCaching` | Complete, adopted | Yes | **Obsolete.** The static `CACHING` flag and the `MultiKeyMap` cache were removed in upstream `da3fc0687` ("Major overhaul of LaneBasedGtu (Issue #59)"). What remains is an unconditional one-entry cache per GTU keyed on simulator time, with no switch | Do not port; delete the property. Report in `PERFORMANCE_NOTES.md` |
| **D2a** | `ParameterType`: hash code computed once in the constructor instead of on every map lookup | `ots-base/.../parameters/ParameterType.java` (commit `826a70ae8`) | Complete | Yes | **Already done upstream** (`private final int hashCode`) | Skip |
| **D2b** | `ParameterType.equals()`: early `false` when the cached hash codes differ, avoiding string comparisons on bucket collisions | same file | Complete | Yes | Not upstream; `equals()` still compares all four fields | Port candidate. Result-neutral by construction (unequal hashes imply unequal objects) |
| **D2c** | `ParameterSet.getParameter`: plain `if`/`throw` instead of `Throw.when(...)`, which allocates a varargs array and calls `getId()` on every successful get | `ots-base/.../parameters/ParameterSet.java` (commit `826a70ae8`) | Complete | Yes | Not upstream; `Throw.when` is still there | Port candidate. Result-neutral by construction (same exception, same message) |
| **D3** | `XmlParser`: one shared `JAXBContext` (lazy, double-checked) instead of `JAXBContext.newInstance(Ots.class)` per parse, plus `warmUpJAXBContext()` to avoid class-loading races when several runs are built in parallel | `ots-xml/.../parser/XmlParser.java` (commits `103f65579`, `0968a707c`, `dab213651`, `4e6370414`) | Works, but rough: `catch (Throwable)` with `System.err` and `printStackTrace` | Yes | Not upstream; still one context per parse. Upstream additionally wraps the unmarshal in `Locale.setDefault(Locale.US)`, which is global state and itself unsafe under parallel parsing | Port candidate, re-implemented cleanly. It affects start-up only, not the simulation |

Two points for your decision on D2b, D2c and D3:

- **Switchability.** The brief requires every enhancement to be switchable. For D2b and D2c the switch would be a
  static-final boolean read from a system property inside the hot path they optimise, which the JIT folds away but
  which adds code to `ots-base` purely to satisfy the rule. Upstream itself has no such switch convention any more
  (the only precedent, `LaneBasedGtu.CACHING`, is gone). I will implement the switches as required unless you prefer
  these two unswitched.
- **Expected effect under LMRS.** The measurements behind D2 were taken with MiRoVA, where parameter lookup was
  13.8–19.2 % of map and hash time. LMRS reads parameters differently, so the gain has to be re-measured in Phase 4
  and may be small. D3 only matters for the cluster in so far as each JVM parses the network once; its real benefit
  is local parallel runs in one JVM.

### E — Other changes to OTS core

| # | File | Change | Do A/B depend on it? |
|---|---|---|---|
| E1 | `ots-road/.../tactical/DriverStateObservable.java` (+ test) | New interface through which sampler columns read a planner's decision state | **Yes (A)** — four sampler columns of `FreiburgNord` |
| E2 | `ots-road/.../characteristics/LaneBasedGtuTemplate.java` | Added getter `getStrategicalPlannerFactory()` | **Yes (A)** — `ScenarioGenerator.buildOdsCharacteristicsGenerator` (line 1526). Avoidable: the scenario can keep the factory it built itself |
| E3 | `ots-xml/.../bindings/LengthBeginEndAdapter.java` | Locale-independent length parsing ("2.3 mm" read as 23 mm on a German JVM) | B indirectly. Upstream moved the parse to `LengthBeginEndType.valueOf` and now forces `Locale.US` around the unmarshal, which covers the XML path. The run scripts pin the locale as well. Likely no longer needed |
| E4 | `ots-xml/pom.xml` | Explicit sorted schema list instead of `*.xsd` (deterministic `ObjectFactory`); redundant `ots-road` dependency | **Yes (B)** — build stamp needs a clean tree after a build on Linux. Upstream still uses the wildcard |
| E5 | root `pom.xml`, `.mvn/jvm.config`, `.gitattributes`, `ots-opendrive/pom.xml` (comment only) | Locale pinned for Maven and surefire; LF for scripts and generated sources | **Yes (B)** — same reason; `*.sh`/`*.sbatch` LF is required on the cluster in any case |
| E6 | `ots-swing/.../AbstractSimulationScript.java` | Default `--simulationTime` changed from 3600 s to 600 s | No. Not ported |
| E7 | `ots-swing/.../OtsSimulationApplication.java` | Unused import and a blank Javadoc line | No. Not ported |
| E8 | `ots-web/.../TJunctionModel.java` | XML parsing removed from a test model to make the module compile | No. Not ported |
| E9 | `ots-demo/.../InputParameterHelper.java` | 4 added lines | No. Not ported |
| E10 | `ots-demo/src/main/resources/lmrs/shortMerge.xml` | Upstream demo network altered (speed limit, node positions, link type) | No. Not ported |
| E11 | `.gitignore`, `README.md` | Fork housekeeping | `.gitignore` entries for build outputs are worth carrying over |

E1, E2, E4 and E5 are core changes outside `ots-demo` that are not performance enhancements. Under your rules they
need your approval; see section 7.

### F — Unclear

| File | Why |
|---|---|
| `cp_tama.txt`, `temp_import_fix.txt`, `ots-demo/cluster/.cp_local.txt` | Look like committed local artefacts. Proposed: not ported |
| `tools/run_merge_gui.sh` | GUI launcher for the merge scenario; unclear whether you still use it |
| `docs/fork-merge-plan.md` | Appears to cover the same ground as this port; not read in detail |

## 3. Versions

| | Source (`origin/main`) | Upstream (`78f2601dd`) |
|---|---|---|
| OTS | 1.7.6 | 1.8.1 |
| Java | 17 | 17 |
| DJUNITS | 5.2.1 | 5.4.1 |
| DJUTILS | 2.3.1 | 2.4.2 |
| DSOL | 4.2.6 | 4.3.2 |
| JUnit | 5.12.0 | 6.0.0 |

Module changes upstream: `ots-draw` is gone (content now in `ots-animation`); the former `ots-animation` content is
in the new `ots-animation-data`; the three `ots-sim0mq*` modules became `ots-cosim` and `ots-cosim-0mq`.
The reactor for `-pl ots-demo -am` is: ots-base, ots-core, ots-kpi, ots-road, ots-trafficcontrol, ots-animation,
ots-animation-data, ots-swing, ots-xml, ots-demo. The build has not been run yet.

## 4. Upstream API changes that break A, B and D

Verified in source at both revisions unless marked "inferred". Upstream publishes no usable release notes
(`changes.xml` stops in 2022).

### Packages and classes

| Old | New |
|---|---|
| `org.opentrafficsim.road.gtu.lane.*` (`LaneBasedGtu`, `VehicleModel`, `tactical.*`, `tactical.lmrs.*`, `tactical.following.*`, `tactical.util.lmrs.LmrsParameters`) | `org.opentrafficsim.road.gtu.*` — the `.lane` segment is dropped throughout |
| `org.opentrafficsim.road.network.lane.{Lane, CrossSectionLink, LanePosition, LaneGeometryUtil}` | `org.opentrafficsim.road.network.*` |
| `road.network.lane.changing.LaneKeepingPolicy` | `road.network.LaneKeepingPolicy` |
| `road.network.lane.object.detector.{LoopDetector, SinkDetector}` | `road.network.object.detector.*` |
| `animation.GraphLaneUtil`, `animation.DefaultAnimationFactory` | `animation.data.util.*` (module `ots-animation-data`) |
| `draw.graphs.GraphPath` | `animation.graphs.GraphPath` |
| `draw.gtu.DefaultCarAnimation.GtuData.GtuMarker` | `animation.gtu.DefaultCarAnimation.GtuData.GtuMarker` |
| `animation.gtu.colorer.GtuColorer` | removed; `animation.colorer.Colorer<? super Gtu>`, concrete ones in `animation.data.gtu` |
| `swing.gui.OtsAnimationPanel`, `swing.gui.OtsSwingApplication` | removed; `OtsSimulationPanel`, and colorers, markers, toggles and tabs move to `OtsSimulationPanelDecorator` |

### Signatures

| Old | New | Hits |
|---|---|---|
| `XmlParser.setUrl(URL)`, `setFile`, `setParseConflict` | `setResource(String)` or `setStream(InputStream)` | `FreiburgNord` |
| `XmlParser.build()` returns `ExperimentRunControl` | returns record `ParseResult`; a second `build()` throws | `FreiburgNord` |
| `Network.getNode(String)` / `getLink(String)` return the object | return `Optional<Node>` / `Optional<Link>` | `FreiburgNord` (15 calls), `ScenarioGenerator` (2) |
| `OtsSimulatorInterface.initialize(Time startTime, ...)`, `getSimulatorAbsTime()`, `getStartTimeAbs()` | `initialize(Duration startTimeOfDay, ...)`, `getSimulatorTime()`, `getTimeOfDay()` | `AbstractSimulationScriptBase`, `ScenarioParameters`, `ScenarioSimulationScript` |
| `EventListener.notify(...) throws RemoteException` | no `RemoteException` | `AbstractSimulationScriptBase` (a `catch` becomes a compile error) |
| `LoopDetector(..., Time firstAggregation, ...)` | `Duration firstAggregation` | `FreiburgNord` |
| `RoadSampler.scheduleStartRecording(Time, ...)` / `scheduleStopRecording` | `Duration` | `FreiburgNord`, `ScenarioOutputConfiguration` |
| `OdMatrix(..., TimeVector, ...)`, `putDemandVector(..., TimeVector, ...)` | `DurationVector` | `ScenarioGenerator`, `FreiburgNord` |
| `ExtendedDataType.getValue(G)` returns `T` | returns `Optional<T>` | every sampler column class |
| `HeadwayDistribution.getName()` | `name()` (extends `NamedConstants`) | `HeadwayDistributionLibrary` |
| `ModelComponentFactory.getParameters()` | `getParameters(GtuType)` | `ScenarioGenerator` (the two MiRoVA factory overrides, which go away) |
| `new LmrsFactory(CarFollowingModelFactory, PerceptionFactory)` | `new LmrsFactory<>(Lmrs::new).setStream(stream)`, optional `.set(Setting.X, value[, gtuType])` | the new `lmrs` model option |
| `LaneBasedTacticalPlannerFactory.peekDesiredSpeed(GtuType, Speed, Speed, Parameters)` returns `Speed` | takes `SpeedLimits`, returns `Optional<Speed>`; `peekDesiredHeadway` returns `Optional<Length>` | anything `TacticalPlannerProvider` wraps |
| `Lane.getSpeedLimit(GtuType)` | `getSpeedLimits(GtuType[, Duration])` returns `SpeedLimits` | not called by A |
| `Duration/Length/Speed/Acceleration/Time.instantiateSI(double)` | removed in DJUNITS 5.4.1; `ofSI(double)` | 183 call sites in 31 files of `scenariomanagement` |
| `LaneBasedGtu.CACHING` | removed | `ScenarioGenerator` (item D1) |
| `XmlParser.warmUpJAXBContext()`, `LaneBasedGtuTemplate.getStrategicalPlannerFactory()` | fork-only, absent upstream | items D3, E2 |
| `org.djutils.io.URLResource` | upstream now uses `ResourceResolver` (2.4.2); whether `URLResource` still exists is **not verified** | `FreiburgNord` |

### `FreiburgNord.xml` against the current schema

The parser does not validate against the schema, so stale XML fails inside JAXB or is dropped silently.

1. **Speed limits on link types — breaks.** `<ots:SpeedLimit GtuType="NL.CAR" LegalSpeedLimit="200km/h"/>` no longer
   exists. New form: one `<ots:SpeedLimit>120km/h</ots:SpeedLimit>` plus `<ots:GtuTypeSpeedLimit Id="NL.TRUCK">…`.
   Six lines to rewrite (HIGHWAY 200/120, LOCAL 65/65, RAMP80 80/80). The speed-limit model behind it is new
   (`SpeedLimits`, `LaneSpeedLimits`), so the mapping of "car 200 km/h, truck 120 km/h" must be checked for
   equivalence, not just translated.
2. **Default includes — breaks (inferred, not run).** `<xi:include href="../xsd/defaults/default_*.xml">` must become
   `href="builtin:gtutypes"` and so on (five includes).
3. **Node direction — silent geometry change.** A missing `Direction` used to be derived from an attached straight
   link; it now defaults to 0°. Node `N2_3` has none and previously got about 2.73° from `L2a`. It must be written
   explicitly, or the geometry of `L2a`, `L3a` and `L_exit` shifts.
4. No effect on this file: `Network` child order, `Run/StartTime` → `StartTimeOfDay`, the stripe and defaults
   renames.

### Behaviour changes upstream that affect the benchmark without any compile error

- **`OdOptions.NO_LC_DIST`** default changed from `null` to 50 m: OD-generated vehicles may not change lane in their
  first 50 m. `FreiburgNord` uses the default.
- **LMRS itself** has changed in 416 commits (the tip commit alone adds cooperation triggered by the indicator). That
  is the point of benchmarking against current upstream, but it means results are not comparable with any LMRS run
  on 1.7.6.
- **Speed limits and time** are modelled differently (`SpeedLimits`, time-of-day `Duration` instead of absolute
  `Time`).

## 5. Output format — what Phase 5 will be compared against

Per run folder on the source branch: `trajectory_data.csv`, `sampler_<…>.csv`, `detector_periodic.csv.zip`
(`detector_periodic.csv` + `.csvm`), `detector_positions.csv.zip`, `diffused_vehicles.csv`, `build.txt`,
`runParams.txt`, `errors.txt`.

Known now, before porting:

- **Trajectory columns.** `FreiburgNord` registers six extended columns: `ActionState`, `LaneChangeDesireLeft`,
  `LaneChangeDesireRight`, `AccelerationDamping`, `GtuType`, `CurrentCFAcceleration`. Five of them describe MiRoVA
  state. Under LMRS they can be kept with empty values (identical columns, so the Python pipeline is unchanged) or
  dropped (different columns). Keeping them requires the helper classes of section C; see question 2.
- **`diffused_vehicles.csv`** is written by the deadlock-diffusion watchdog, which is MiRoVA and is not ported. The
  file would be absent or empty.
- **Metadata.** `build.txt` already records the build stamp, the planner and its description. Seed, date, start
  timestamp, upstream commit and switch states are not all in it today; Phase 3 adds them. Proposed: keep the name
  `build.txt` and add lines, rather than introduce a second file.

## 6. Where A and B hard-wire MiRoVA

A selection point already exists and is the natural place for `--model`:

- `TacticalPlannerProvider` — an interface loaded through `ServiceLoader`; `name()`, `apply(vehicleClass, built)`,
  `describe()`.
- `ScenarioGenerator.KEY_TACTICAL_PLANNER` (`tacticalPlanner`), `selectTacticalPlannerFactory(...)`, and
  `RunMirovaClusterStudy --tacticalPlanner=<name>`, fed by `MIROVA_TACTICAL_PLANNER` in the sbatch script.
- `mirova` is the built-in default and needs no provider; `tama` is found on the classpath.

Its weakness for this port is that it is a *wrapper*: the scenario always builds a MiRoVA factory first and hands it
to the provider. The hard-wired sites are:

| Site | What is wired |
|---|---|
| `ScenarioGenerator.buildStrategicalPlannerFactoryCar()` / `…Truck()` (lines 1425–1500) | `new MirovaTacticalPlannerFactory(new MirovaIdmPlusFactory(stream), new DefaultMirovaPerceptionFactory())` with parameter overrides |
| `ScenarioGenerator` static initialiser (line 619) | Collects parameter types from `MirovaParameters` by reflection, for the `car.`/`truck.` overrides |
| `ScenarioGenerator.MIROVA_PLANNER = "mirova"` and the default of `KEY_TACTICAL_PLANNER` | Default model |
| `ScenarioGenerator.applyGtuPositionCaching()` | `LaneBasedGtu.CACHING` (item D1) |
| `FreiburgNord.buildGtuTemplates()` | Same MiRoVA factory construction, `MirovaParameters` defaults |
| `FreiburgNord.buildRoadSamplers()` | Five MiRoVA sampler columns |
| `FreiburgNord.createVehiclesFromODMatrix()` (line 461) | `GtuDeletionDiagnostics` as error handler |
| `FreiburgStudyParameters`, `RunFreiburgNord`, `RunFreiburgParallel` | `MirovaParameters` keys |
| `StudyRegistry` | 21 of 22 registered studies are MiRoVA studies |
| `BuildProvenance` | TaMA stamp and composition property (harmless, but model-specific) |

Proposed design for Phase 2: turn the provider into a *factory* — `DriverModelProvider` with `key()`,
`createTacticalPlannerFactory(vehicleClass, stream, parameterOverrides)` and `describe()` — looked up by
`ServiceLoader`, with `lmrs` registered in `ots-demo` and `tama` added later from its own jar. The scenario then
never names a model. `--model` becomes the CLI argument (default `lmrs`); `--tacticalPlanner` can stay as an alias
if your submit scripts use it.

**Scenario-level settings that would carry over to the LMRS benchmark** (each to be confirmed in Phase 2, with the
value, in `PORTING_NOTES.md`): the desired-speed distributions (`DesiredSpeedLibrary`), the headway distribution,
vehicle templates (lengths, widths, maximum speeds), lane biases, the room checker, demand per date, and the
`car.`/`truck.` parameter overrides from `FreiburgStudyParameters`. The last group is the delicate one: most are
MiRoVA parameters and drop out, but any that set standard OTS parameters (`T`, `TMIN`, `TMAX`, `a`, `b`, `s0`, …)
would change LMRS away from its upstream defaults. I will list each and port none of them without your approval.

## 7. Decisions needed before Phase 2

1. **D items to port:** D2b, D2c, D3 (D1 and D2a are obsolete upstream). And: switches on D2b/D2c as the brief
   requires, or unswitched?
2. **Sampler columns under LMRS:** keep the six columns with empty MiRoVA values (needs `DriverStateObservable`, item
   E1, and the column classes — I would place all of them in `ots-demo` rather than `ots-road`, so no core change is
   needed), or drop the five MiRoVA columns and accept a different trajectory header?
3. **E2** (`LaneBasedGtuTemplate` getter): avoid it by restructuring the scenario (recommended), or port the getter?
4. **E4 and E5** (build determinism in `ots-xml/pom.xml`, root `pom.xml`, `.mvn/jvm.config`, `.gitattributes`): port
   them, as the build stamp depends on them? They touch build files outside `ots-demo` but no Java code.
5. **Scope of A:** Freiburg scenario, `DateStudy` and the framework only, as proposed — or also the Bodegraven merge
   and simple-highway scenarios?
6. **`GtuDeletionDiagnostics`:** port it into `ots-demo` (it has no model dependency and records why a GTU was
   deleted), or fall back to upstream `GtuErrorHandler.DELETE`?
7. **`OdOptions.NO_LC_DIST`:** take the new upstream default (50 m) or set it back to `null` to match the old
   scenario?
8. **Naming:** keep `MIROVA_*` environment variables, `mirova_env.sh`, `run_mirova.sbatch` and
   `RunMirovaClusterStudy` unchanged, so your submit habits and scripts keep working (recommended), or rename?

## 8. Decisions taken on this inventory

| # | Decision |
|---|---|
| 1 | Port D2b, D2c and D3, re-implemented against current upstream, each in its own commit. No runtime switches: they are pure refactorings, and the determinism check is "before the commit against after the commit". `ParameterSet.getParameter` keeps its check, exception type and message. Measure an LMRS baseline first. |
| 2 | Keep the six sampler columns, empty under LMRS, so the trajectory header is unchanged. The column classes go into `ots-demo`. `DriverStateObservable` is replaced by a model-neutral interface. |
| 3 | No getter on `LaneBasedGtuTemplate`; the scenario is restructured instead. |
| 4 | The `.gitattributes` LF rules are approved. `ots-xml/pom.xml`, the root `pom.xml` and `.mvn/jvm.config` are not changed. No build stamp. The locale is pinned at run time, in `run_mirova.sbatch` and the local runner. |
| 5 | Scope: the Freiburg scenario, `DateStudy` and the framework. The simple-highway scenario only if it ports trivially. No Bodegraven. |
| 6 | `GtuDeletionDiagnostics` is ported into `ots-demo`; the deletion behaviour must match. |
| 7 | `NO_LC_DIST` is set explicitly to `null`, as an explicit scenario parameter recorded in the run metadata. |
| 8 | `MIROVA_*` variables, `run_mirova.sbatch` and `RunMirovaClusterStudy` keep their names. |
| 9 | Node `N2_3` gets an explicit direction equal to the value the old parser derived. |
| 10 | Every `car.`/`truck.` override of a standard OTS parameter is listed, none is active without approval, and they can be switched as a set with `--lmrsParameters=default|scenario`, default `default`, recorded in the metadata. |
| 11 | `TacticalPlannerProvider` becomes a real factory behind `--model`, default `lmrs`, without any MiRoVA factory being built. |
