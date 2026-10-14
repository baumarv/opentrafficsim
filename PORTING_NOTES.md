# Porting notes — what was ported, how it was adapted, and where the scenario deviates from the fork

Branch `ots-upstream-benchmark`, based on upstream OpenTrafficSim `78f2601dd` (1.8.1). Source of the port:
`origin/main` @ `f41175d53` (fork, based on OTS 1.7.6). The classification behind these notes is in
`PORTING_INVENTORY.md`; the performance work is in `PERFORMANCE_NOTES.md`; how to run is in `cluster/README.md`; what
was checked before a cluster run is in `CLUSTER_READINESS.md`; why vehicles are removed is in `DELETION_DIAGNOSIS.md`.

## 1. What is on the branch

| Commit | Content |
|---|---|
| `feat(cluster)` | `cluster/`: SLURM array script, cluster build, local runner, guards, date lists, demand CSVs. `.gitattributes`. |
| `feat(demo): scenario framework …` | `org.opentrafficsim.demo.mirova.scenariomanagement`: framework, driver model selection, sampler columns, run metadata. |
| `feat(demo): Freiburg-Nord scenario …` | `scenarios/`: `FreiburgNord`, `FreiburgFacility`, `FreiburgStudyParameters`, `DateStudy`, `RunMirovaClusterStudy`; `FreiburgNord.xml`. |
| `build: store the generated JAXB sources with LF …` | Line endings of 248 generated files, no content change. See 6.1. |
| three `perf(...)` commits | See `PERFORMANCE_NOTES.md`. |
| `test(demo)` | Three ported library tests and one new test of the model selection. |
| `feat(demo): the LMRS can be composed per run …` and three follow-ups | LMRS settings, `LmrsSettings`, `RunFreiburgNordGui`, `cluster/run_gui.sh`. See 2.1 and 2.2. |
| `feat(demo): one row per removed vehicle …` | `removed_vehicles.csv` and its traces. See 6.3. |
| `feat(demo): presets replace the scenario parameter set …` | Presets; the scenario parameter set is removed. See 3. |

No class of the MiRoVA driver model is on the branch. No LMRS logic and no LMRS default parameter is changed.

## 2. Model selection

`TacticalPlannerProvider` of the fork wrapped a MiRoVA factory the scenario always built first. It is replaced:

| New class | Role |
|---|---|
| `DriverModelProvider` | Interface. `key()`, `createStrategicalPlannerFactory(Request)`, `describe()`. A provider builds the complete factory; nothing is built to be replaced. |
| `DriverModels` | Resolves a key: built-in providers, plus every provider found through `ServiceLoader`. Exactly one match or the run stops during setup. |
| `models.LmrsDriverModelProvider` | Key `lmrs`. `new LmrsFactory<>(List.of(gtuType), Lmrs::new).setStream(stream)`, no setting changed, used as its own parameter factory. |
| `ParameterOverrides` | Turns `car.<id>` / `truck.<id>` entries into parameter types and typed values. Knows no model. |

A request to a provider carries the vehicle class, the GTU type, the random stream, the parameter overrides of that
vehicle class, and the **model settings**: every scenario parameter whose key starts with the model key and a dot
(`lmrs.<name>`), without the prefix. What the names mean is the model's business.

A scenario calls `buildStrategicalPlannerFactory("car", DefaultsNl.CAR)` and never names a model. To add TaMA: a jar
with a `DriverModelProvider` of key `tama` and a `META-INF/services` entry on the classpath. No scenario class changes.

Command line: `--model=<key>`, default `lmrs`. `--tacticalPlanner` is accepted as the old name. The scenario
parameter is `model` (was `tacticalPlanner`).

Checks kept from the fork, in simpler form: a scenario that builds its vehicles without selecting a model, or with
another model than the run asked for, is refused at the end of setup, before the first simulation step.

### 2.1 Composing the LMRS

Upstream's `LmrsFactory` has settings beyond parameters: the synchronization, cooperation and gap-acceptance
strategies, the lane-change and acceleration incentives, social interactions, the perception model. They can be
set per run in two ways, both ending in `LmrsFactory.set(Setting, value)` and both setting only what is named:

| Way | Form | Used by |
|---|---|---|
| Typed | an `LmrsSettings` object under the scenario parameter `lmrs.settings`: `new LmrsSettings().set(Setting.SYNCHRONIZATION, Synchronization.ALIGN_GAP)`. Setting and value are upstream's own constants and objects, checked by the compiler. | programs, `RunFreiburgNordGui` |
| Text | scenario parameters `lmrs.<name>=<value>`, from `--lmrs.<name>=<value>` or a preset. Names: `synchronization`, `cooperation`, `gapAcceptance`, `fullerImplementation`, and the switches `incentiveRoute`, `incentiveGetInLane`, `incentiveSpeedWithCourtesy`, `incentiveCourtesy`, `incentiveQueue`, `incentiveStayRight`, `incentiveKeep`, `accelerationSpeedLimitTransition`, `accelerationNoSlowLaneOvertake`, `socialPressure`, `tailgating`, `socioLaneChange`, `socioSpeed`. Strategy names are parsed by upstream's own converters. | cluster and local runs |

A name or value the provider does not know stops the run during setup.

**The command-line options `LmrsFactory` itself defines are not used.** Their defaults differ from the defaults of a
factory built in code: the perception model is `ATTENTION_MATRIX` on the command line and `NONE` in code. Parsing
through them would therefore change the model of a run that sets nothing.

Without any setting the factory is exactly `new LmrsFactory<>(List.of(gtuType), Lmrs::new).setStream(stream)`; a
one-hour run gives the same detector and trajectory output as before these options existed.

**Metadata.** The provider does not report what it was asked to set. It reads the factory it built back
(`LmrsSettings.read`) and compares it with a factory built the way upstream builds one. `run.properties` holds:

| Key | Content |
|---|---|
| `model.<class>.factory` | the factory class |
| `model.<class>.settings` | `upstream defaults`, or `changed: SYNCHRONIZATION, COOPERATION, …` |
| `model.setting.<class>.<SETTING>` | every setting whose value can be named, 37 per vehicle class, by the name of its constant in `LmrsFactory.Setting`: `model.setting.car.SYNCHRONIZATION=PASSIVE` |
| `model.<class>.parameterOverrides` | `none (upstream defaults)`, or `applied, see override.*` |
| `model.override.<class>.<parameter>` | each applied parameter override with its value |

`<class>` is `car` or `truck`. Because the settings are read back, the record states the composition that ran and
stays right if upstream changes a default. Settings that hold functions or collections (the car-following model,
custom incentives) cannot be named and are not listed.

### 2.2 The GUI runner

`RunFreiburgNordGui` opens one day in the OTS GUI. It takes no arguments; its settings are code at the top of the
class: `LocalDate DATE`, `long SEED`, `Optional<Duration> SIMULATION_TIME`, an `LmrsSettings` with every setting
written out on upstream's value, and a method for typed driver parameters. On start it prints the composition read
back from the factory, so a changed line shows as `settings=changed: …`. It sets `Locale.US` itself, because an IDE
passes no JVM options, and finds `cluster/demand` from the working directory upwards. `cluster/run_gui.sh` starts
the class from a shell. A GUI run writes output only when the simulation reaches its end, and no `run.properties`.

## 3. LMRS parameters: what the benchmark runs on

By default **nothing** is overridden: every parameter is what upstream's `LmrsFactory` sets, including the values it
draws per vehicle (`FSPEED`, `FSPEED_GTU`).

The fork's Freiburg studies set 27 `car.`/`truck.` overrides. Of these, 19 were MiRoVA parameters and are gone.
The remaining eight addressed **standard OTS parameters**. They are listed here **for reference only**:

| Override | Fork value | Upstream LMRS value |
|---|---|---|
| `car.T` | 1.10 s | 1.20 s, relaxed down to `TMIN` 0.56 s around lane changes and back up to `TMAX` 1.20 s |
| `car.a` | 1.4 m/s² | 1.25 m/s² |
| `car.b` | 2.0 m/s² | 2.09 m/s² |
| `car.s0` | 2.0 m | 3.0 m |
| `truck.T` | 1.40 s | 1.20 s, as for cars |
| `truck.a` | 1.25 m/s² | 1.25 m/s² (the override equals the default) |
| `truck.b` | 2.0 m/s² | 2.09 m/s² |
| `truck.s0` | 4.0 m | 3.0 m |

The upstream values were printed from `LmrsFactory.getParameters` for `NL.CAR` and `NL.TRUCK` on this branch. The
factory gives cars and trucks the same parameter values; they differ by GTU type, dimensions and maximum speed.
The fork values are from `FreiburgStudyParameters` on `origin/main`.

**None of the eight exists in the code.** The switch `--lmrsParameters=default|scenario` that applied them as a
set was removed, with its metadata key and its tests; `--lmrsParameters` is now refused with a message. A driver
model runs on its own parameters unless a run changes them explicitly:

- single parameters with `--car.<parameter>=<value>` and `--truck.<parameter>=<value>`, the value with its unit
  (`--car.T=1.1s`, `--car.vGain=50km/h`); any parameter of `ParameterTypes` or `LmrsParameters`;
- a **preset**: a file in `cluster/presets/` with driver model options, one `key=value` per line, selected with
  `--preset=<name|file>` (`MIROVA_PRESET` on the cluster) and recorded by name as `preset=` in `run.properties`.
  Options given explicitly win over the preset; a key that is not a driver model option is refused.

One preset exists: `moving-ego-headway` — `lmrs.synchronization=ALIGN_GAP_MOVING`, `lmrs.cooperation=PASSIVE_MOVING`,
`lmrs.gapAcceptance=EGO_HEADWAY`, everything else as upstream.

Should the eight values be wanted again, they are a preset of eight lines (`car.T=1.1s` and so on); nothing in the
code has to change.

One point about `T` in the LMRS: it is not a constant. It is relaxed down to `TMIN` around lane changes and back up
to `TMAX`, so setting `T` alone does not mean what it meant in MiRoVA.

**Stricter than the fork:** an override that names no parameter, or cannot be converted to the parameter's type,
stops the run. The fork ignored the first silently and printed a warning for the second.

## 4. Scenario-level settings that carry over unchanged

| Setting | Value | Where |
|---|---|---|
| Network | `FreiburgNord.xml`, see 5 | resources |
| Simulated period | 13:00–22:00 of the date | `FreiburgStudyParameters` |
| Demand | per date from `cluster/demand/demand_<date>.csv`, 5-minute steps, stepwise interpolation, strict mode | `DateStudy`, `ScenarioGenerator.parseOdMatrixFromCsv` |
| Vehicle dimensions | car 4.0 × 2.0 m, truck 12.0 × 2.5 m | `FreiburgNord` |
| Maximum-speed distribution, cars | `DesiredSpeedLibrary.carsLimit140_DensityLow`, unshifted | `FreiburgNord.buildGtuTemplates` |
| Maximum-speed distribution, trucks | `DesiredSpeedLibrary.trucksLimit100_DensityClass1_Modified` | same |
| Lane bias | vehicles by speed (150/80 km/h), trucks on the right | `FreiburgNord.getLaneBiases` |
| Headway distribution | OTS default (exponential) on both inflows; `-Dmirova.mainlineHeadwayFloor` still available | `FreiburgNord.applyHeadwayDistributions` |
| Seeds | 42 + replication × 1 000 003; behaviour stream derived by SplitMix64 | `ScenarioManager`, `ScenarioGenerator` |
| Random streams | `generation` = seed, `default` = seed + 1 | `AbstractSimulationScriptBase` |
| Detectors | `L3a`, `L5a`, `L7a` (and `L6a`, which the network does not have), mid-lane, 60 s, harmonic mean speed | `FreiburgNord.buildRoadSamplers` |
| Trajectory sampler | link `L4a`; `-Dmirova.samplerLinks` still available | same |
| Watchdog | aborts a run when no detector saw a vehicle for 180 s after a 300 s grace period | `ScenarioSimulationScript` |

How these act under the LMRS, which is worth knowing before reading results: the template's speed distribution is
the vehicle's **maximum speed**. With a 200 km/h car limit on the mainline the LMRS desired speed is bounded by
that maximum speed for nearly every car, so the distribution keeps the role it had. This is a reading of the code,
not a measurement.

## 5. `FreiburgNord.xml` — three changes

1. **Node `N2_3` has an explicit direction: `0.04764983805372385 rad(E)` = 2.7301346149602415°.**
   Up to 1.7.6 the parser derived a missing node direction from an attached straight link; here `L2a` from `N1_4`
   (522.867, −10.759) to `N2_3` (748.552, 0.003), i.e. `atan2(10.762, 225.685)`. 1.8 defaults a missing direction to
   0°. The value is written in radians with all digits, so the parser reads back the same double the old code
   computed. `N2_3` was the only active node without a direction.
2. **Speed limits of the link types** are rewritten in the new schema. The general limit is the former car value; a
   `GtuTypeSpeedLimit` for `NL.TRUCK` is added where the truck value differed.

   | Link type | Fork | Now |
   |---|---|---|
   | `HIGHWAY` | car 200, truck 120 km/h | `SpeedLimit` 200, `GtuTypeSpeedLimit NL.TRUCK` 120 |
   | `LOCAL` | car 65, truck 65 | `SpeedLimit` 65 |
   | `RAMP80` | car 80, truck 80 | `SpeedLimit` 80 |

   The values are the same; what a limit *means* to the LMRS is not. 1.8 distinguishes a lane-level limit from a
   GTU-type limit and applies different adherence factors to them (`FSPEED`, `FSPEED_GTU`). That is upstream
   behaviour and part of what is being benchmarked.
3. **Default includes** use `builtin:gtutypes` and the like; the resolver no longer maps `../xsd/defaults/…`.

## 6. Deviations from the fork's scenario

### 6.1 Things I had to decide and you should know about

- **Line endings of the generated JAXB sources (one extra commit).** You approved the `.gitattributes` LF rules.
  Upstream stores 248 of the 261 generated files with CRLF, so the rule alone left the tree permanently modified.
  The commit `build: store the generated JAXB sources with LF line endings` normalises them: line endings only,
  `git diff --ignore-cr-at-eol` is empty. It touches files outside `ots-demo`. If you would rather not carry it,
  revert it together with the two generated-source lines in `.gitattributes`; the price is that a build on Linux
  leaves the tree modified and a cluster run records its commit as dirty.
- **The build needs the locale pinned too**, not only the runs. `ots-xml` regenerates its sources on every build and
  XJC writes Javadoc through a localised bundle. Since `pom.xml` and `.mvn/jvm.config` stay untouched as you asked,
  `build_for_cluster.sh` sets `MAVEN_OPTS` and the README says so for manual builds.
- **`run.properties` replaces `build.txt`.** There is no build stamp. Every run writes `run.properties` before
  the simulation and completes it afterwards. The key `tacticalPlanner` is kept beside `model`.
- **`diffused_vehicles.csv` is written with its header and no rows.** Nothing on this branch removes vehicles to
  dissolve a deadlock; the file is kept so that post-processing finds it.

### 6.2 Behaviour

| Topic | Fork | This branch |
|---|---|---|
| Driver model | MiRoVA (or TaMA) | upstream LMRS |
| Driver parameters | 27 overrides always applied | none by default, see 3 |
| `OdOptions.NO_LC_DIST` | 1.7.6 default: none | set explicitly to the scenario parameter `noLaneChangeDistance`, which is absent, i.e. `null`. 1.8 would default to 50 m. Recorded in `run.properties`. |
| Deadlock handling | MiRoVA `DeadlockDiffusionWatchdog` removed stuck vehicles | not ported. Only the run-level watchdog remains, which aborts the run. |
| GTU position cache | `LaneBasedGtu.CACHING=false` | the flag no longer exists upstream; nothing to set |
| Output at end of run | written up to twice (stepping loop and end event) | written once |
| Unknown parameter override | ignored | run stops |
| Lane geometry | 1.7.6 offset lines | 1.8.1 offset lines; lane lengths differ by up to 0.39 m (6.3) |

### 6.3 Output files

Same files, same names, same columns. Compared against a fork run
(`capacity_v1/…/run_seed_42`, written 2026-09-02):

| File | Result |
|---|---|
| `sampler_RoadSampler_[…].csv.zip` (now `[samplingInterval=0.20000000_s]`, see below) | Header identical: `traj#,linkId,laneId,gtuId,t,x,v,a,ActionState,LaneChangeDesireLeft,LaneChangeDesireRight,AccelerationDamping,GtuType,CurrentCFAcceleration`. Under LMRS: `ActionState` is `none`; the two desires, the damping and the car-following acceleration are `NaN`; `GtuType` is filled. |
| `sampler_RoadSampler_[…].csv.zip` | **Renamed** to `sampler_RoadSampler_[samplingInterval=0.20000000_s].csv.zip`, because the sampler now has a fixed 0.2 s interval. Columns unchanged. See "Trajectory sampling" below. |
| `…csv.header` | Identical, provided the run uses `-Dfile.encoding=UTF-8` (the runners set it). Without it a Windows JVM writes the `²` of `m/s²` in another encoding. |
| `detector_periodic.csv` | Header identical: `id,GTU type,t,q,vHarm`. |
| `detector_periodic.csvm`, `detector_positions.csvm` | One difference each: the class name in the first data row is `…road.network.object.detector.LoopDetector$6` instead of `…road.network.lane.object.detector.LoopDetector$6`. Unavoidable, the class moved upstream. |
| `detector_positions.csv` | Header identical. Positions differ because lane lengths do: `L3a.Lane1` 113.871 → 113.793 m, `L3a.Lane2` 113.827 → 113.593 m, `L5a.Lane1` 371.064 → 371.193 m, `L5a.Lane2` 370.794 → 371.182 m, `L7a.Lane1` unchanged. The detectors sit at half the lane length. `L5a` does not touch node `N2_3`, so this is upstream's geometry code and not the node direction. |
| `diffused_vehicles.csv` | Header identical, no rows. |
| `run.properties` | New content, see `cluster/README.md`. |
| `build.txt` | No longer written. |
| `runParams.txt` | Same format; carries `model` instead of `tacticalPlanner`, and no `car.`/`truck.` keys unless a run sets some. |
| `removed_vehicles.csv`, `removed_vehicles_traces.txt` | New. One row per vehicle OTS removed after an exception, and the distinct stack traces. Header only when none was removed. |

### Trajectory sampling: a fixed 0.2 s interval

The road sampler records every vehicle on the sampled lanes every **0.2 s**, independent of the driver model
(`FreiburgNord.SAMPLING_INTERVAL`, set through `RoadSampler.Factory.setFrequency`). Before, it had no interval and
wrote a row whenever a vehicle executed a plan step, which made the sampling step the driver model's time step:
0.2 s in MiRoVA, 0.5 s in the LMRS. The LMRS time step itself is unchanged.

**A sample is evaluated on the operational plan, not interpolated between plan steps.** Checked in the code of
`RoadSampler` at `78f2601dd`: with an interval, the sampler schedules an event at every multiple of the interval
(`scheduleSamplingInterval`) and in it (`notifySample`) reads `gtu.getPosition(lane, REFERENCE_POSITION)`,
`gtu.getSpeed()` and `gtu.getAcceleration()` at the current simulation time. `LaneBasedGtu.getPosition(lane, rel,
time)` takes the location from `getOperationalPlan(time).getLocation(time, …)` and projects it on the lane;
`Gtu.getSpeed(time)` and `getAcceleration(time)` are likewise evaluated on the plan that is valid at that time. The
extended columns are read at the same instant. An LMRS plan consists of segments of constant acceleration, so the
values are those of the plan at the sample time, exactly.

Besides the samples on the 0.2 s grid, the sampler records a vehicle when it enters or leaves a sampled lane (on a
lane change, and when it enters or leaves `L4a`), as before. These rows are off the grid: 3.2 % of the rows.

Seed 42 of 2025-09-22, before and after:

| | 0.5 s (plan steps) | 0.2 s (fixed interval) |
|---|---|---|
| File | `sampler_RoadSampler_[samplingInterval=null].csv.zip` | `sampler_RoadSampler_[samplingInterval=0.20000000_s].csv.zip` |
| Members | `…[samplingInterval=null].csv`, `….csv.header` | `…[samplingInterval=0.20000000_s].csv`, `….csv.header` |
| Rows | 685 042 | 1 651 672 |
| Vehicles | 19 095 | 19 095 |
| Step within a trajectory | 0.5 s in 93 % | 0.2 s in 96.8 % |
| Zip size | about 13 MB | 24.7 MB |
| Header member content | — | identical |
| Detector files, removed vehicles | — | identical: the sampling does not change the simulation |

**The file name changes.** It is `sampler_` plus the sampler's `toString()`, which contains the interval. The
pipeline finds the file with `*sampler_RoadSampler*`, which still matches; anything that spells out
`[samplingInterval=null]` has to change to `[samplingInterval=0.20000000_s]`.

### 6.4 Code

| Topic | Change |
|---|---|
| Packages | `road.gtu.lane.*` → `road.gtu.*`, `road.network.lane.*` → `road.network.*`, `road.network.lane.object.detector` → `road.network.object.detector`, `animation.GraphLaneUtil` → `animation.data.util`, `draw.graphs.GraphPath` → `animation.graphs` |
| DJUNITS | `instantiateSI` → `ofSI` |
| Time | start time and recording times are `Duration` (time of day), no longer `Time`; `OdMatrix` takes a `DurationVector` |
| Network lookups | `getNode`/`getLink` return `Optional`; the scenario uses `orElseThrow()` |
| Parser | `XmlParser.setResource(String)` replaces `setUrl(URL)` |
| GUI | `AbstractSimulationScriptBase` uses `OtsSimulationPanel` and `OtsSimulationPanelDecorator`; `OtsAnimationPanel` and `OtsSwingApplication` no longer exist |
| Logging | tinylog is gone upstream; `silenceBackgroundThreads` uses `Logger.setLogLevel(Level.ERROR)` |
| Listeners | `notify` no longer declares `RemoteException` |
| Sampler columns | moved from `ots-road` (MiRoVA package) to `ots-demo`, package `…scenariomanagement.sampling`; `getValue` returns `Optional` |
| `DriverStateObservable` | replaced by `sampling.DriverStateReporter` in `ots-demo`: `manoeuvreStateName()`, `laneChangeDesireLeft()`, `laneChangeDesireRight()`, `accelerationScalingFactor()`, `carFollowingAcceleration()`. No MiRoVA type, no core change. The column **names** and descriptions in the output are unchanged, including the description text "Current MiRoVA ActionState", so that the header file stays byte-identical. |
| `LaneBasedGtuTemplate.getStrategicalPlannerFactory()` | not ported; `ScenarioGenerator` keeps the factories it built in `strategicalPlannerFactories` |
| `GtuDeletionDiagnostics` | moved to `ots-demo`; reads `LaneBasedGtu.getPosition()` because `getReferencePosition()` no longer exists; `total()` added for the metadata |
| Units at method boundaries | `ScenarioParameters.setDemand/getDemand` take and return `Frequency`; `FreiburgNord.totalDemand` returns `Frequency`; the three `DesiredSpeedLibrary.carsLimit140_DensityLow…` variants take `Speed` instead of a `double` in km/h |
| Javadoc, headers, imports | every ported class has the KIT header; wildcard imports and empty tags removed; German comments translated |

The desired-speed scenario parameters (`desiredSpeedShiftCarKmh` and the like) are still plain numbers in km/h,
because their keys say so and studies pass them as text. They are wrapped into a `Speed` where they enter the
library. With the defaults (no shift, no truncation, no compression) the distribution is bit-identical to the
fork's; with a non-default value the round trip through `Speed` can differ in the last bit.

## 7. Not ported

| What | Why |
|---|---|
| Everything under `tactical/mirova` in `ots-road` | The rule. |
| 21 MiRoVA parameter studies, `ParameterGridBuilder`, `ParameterTableGenerator` | They vary MiRoVA parameters. `ParameterGridBuilder` has no user left. |
| 9 TaMA studies, `RunTamaRampEndGui`, `cluster/run_gui.sh`, the `tama` Maven profile, `test_tama_classpath_guard.sh` | They belong with the TaMA adapter. |
| `MergeScenario`, `RunMerge`, `MergeBodegraven.xml` | No Bodegraven, as decided. |
| `SimpleHighwayScenario` | Not trivial: it builds Wiedemann 99 and MiRoVA factories itself. The smoke test is the Freiburg scenario with `--simulationTime=900s`, which takes about eight seconds. |
| `fsmtrace/` | MiRoVA state-machine tracing. |
| `stamp_build.sh`, `test_stamp_build.sh`, `BuildProvenance` | No build stamp, as decided. |
| `sum_defects.py`, the `--diag` option of the local runner | MiRoVA defect counters. |
| `TacticalPlannerFactoryHook` | It existed for a TaMA recording harness; a provider can do the same. |
| Runners `RunFreiburgNord`, `RunFreiburgParallel`, `RunFreiburgMergeWatch`, `RunFreiburgNordNoDemand` | They set MiRoVA parameters or hard-coded local paths. `run_local_parallel.sh` covers their use. |
| Tests `RunIdentityIT`, `TrajectoryColumnsIT`, `PlannerOverrideRefusalTest`, `ClusterTamaSupportTest`, `ScenarioParameterKeysResolveTest`, the three TaMA study tests | They test MiRoVA or TaMA behaviour, or mechanisms that changed shape. `DriverModelSelectionTest` covers the new selection. |
| Changes to `CircularRoadModel`, `CircularRoadSwing`, `shortMerge.xml`, `AbstractSimulationScript`, `TJunctionModel`, `AnimationGtuData`, `ots-xml/pom.xml`, root `pom.xml`, `.mvn/jvm.config` | Not needed, or not approved. |

Hard-coded local paths remain in `ScenarioGenerator.prepareSimulationDemand` and
`ScenarioManager.runAll` (Python interpreter and scripts of the demand preparation and the plotting step). They are
defaults behind environment variables, are skipped on the cluster and in `DateStudy`, and were left as they are.

## 8. Verification (Phase 5)

| # | Check | Result |
|---|---|---|
| 1 | `mvn clean install -pl ots-demo -am -Dmaven.test.skip=true -Dmaven.javadoc.skip=true -Djacoco.skip=true` | Build success, 32 s, on the final code. The tree is clean afterwards when the build JVM runs under `en_US` (`MAVEN_OPTS`, see 6.1). |
| 2 | 15-minute headless run, `--model=lmrs`, through `cluster/run_local_parallel.sh` | Completes in about 12 s and writes the trajectory file, both detector files, `diffused_vehicles.csv` and `run.properties`. |
| 3 | Determinism | Full day (2025-09-22, seed 42): baseline five times, and each performance commit at least twice. The content of all seven output files is identical in every run. Runtimes are in `PERFORMANCE_NOTES.md`. |
| 4 | Output format | Same files and columns as a fork run; the differences are listed in 6.3. |
| 5 | SLURM dry run | `MIROVA_DRY_RUN=1 SLURM_ARRAY_TASK_ID=3 bash cluster/run_mirova.sbatch` prints the two `java` commands of the task (indices 6 and 7) with the workspace paths, `--model=lmrs`, the locale and encoding options and both commit properties, and starts nothing. An odd run count leaves the last slot empty; a missing `MIROVA_CLUSTER_DIR` stops the script. Run under Git Bash on Windows with a mock workspace, not on the cluster. |
| — | Unit tests | `ots-demo`: 15 run, 0 failed (three ported, one new). `ots-base`: 58 run, 0 failed. `cluster/test_guard.sh`: 16 passed. |
| — | Class header, Javadoc, imports | A script over all ported main classes finds no class without the header, no public or protected member without Javadoc, no missing `@param`/`@return`, no empty tag, no unescaped generic, no wildcard import. |

Three checks you asked for specifically:

- **Locale.** A one-hour run started with `-Duser.language=de -Duser.country=DE` (recorded as `jvm.locale=de_DE`)
  produces trajectory and detector files identical to the `en_US` run, and no row of a full-day trajectory file has
  a column count other than the header's, so no number is written with a decimal comma. The pin in the runners is
  therefore a precaution on this scenario rather than a fix; `FreiburgNord.xml` is also parsed identically, because
  upstream's parser now forces `Locale.US` itself while it unmarshals.
- **Deletion behaviour.** A full-day run with upstream's plain `GtuErrorHandler.DELETE` in place of
  `GtuDeletionDiagnostics` gives output identical to the run with the diagnostics. The handler removes the same
  vehicles at the same times; it only adds the tally. A comparison of *which* vehicles are removed against the fork
  is not meaningful, because the driver model and the OTS version both differ.
- **`N2_3`.** See section 5: 0.04764983805372385 rad, 2.7301346149602415°.

## 9. Observations from the LMRS runs that you may want to look at

These are not consequences of the port; they are what upstream 1.8.1 does on this network. I did not investigate
or change any of them.

- **55 vehicles are removed during the day 2025-09-22**, all in the last 50 m of the network, after missing the
  sink. Diagnosed in `DELETION_DIAGNOSIS.md`: one root cause, no effect on detectors or trajectories, and the old
  fork had the same defect with the LMRS, more often and without reporting it.
- **Lane lengths differ from 1.7.6 by up to 0.39 m** (6.3).
- **Cars and trucks get the same LMRS parameter values** from upstream's factory (section 3).
- **A full LMRS day costs about 5 to 8 minutes of CPU time** on the workstation, depending on its load. The
  `--time=02:00:00` and `--mem-per-cpu=8G` of `run_mirova.sbatch` are inherited and generous.
