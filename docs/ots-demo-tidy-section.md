## Task 2 — Tidying the MiRoVA part of ots-demo

**Audit only. Nothing here has been deleted or edited.** The format follows
[`decoupling/cache-audit.md`](decoupling/cache-audit.md): each entry says what the thing is, what refers
to it (grep evidence), and a recommendation. **Marvin decides.**

Read at `agent/fork-tidy-plan` = `decoupling_phase05` tip `2ccc1b362`. Also searched: `diss_mvb` at
`a70da43` (tracked files, `git grep`) and `tama-stage11` at `5d065f4`.

**How sure each "unused" is:**

- **[verified unused]**: no caller on any path, and the thing is not reachable by reflection or by string
  lookup. Either nothing mentions it, or the only mentions are comments or docs.
- **[no grep reference]**: grep finds no caller. It could still be reached through `StudyRegistry.resolve`
  (`Class.forName` on a fully qualified name, `StudyRegistry.java:92`), an IDE launch config, or a command
  line typed by hand.
- **[verified set, not read]**: a value is written into `ScenarioParameters` or a `Parameters` set, and I
  followed every reader.

Scale of what is audited: 55 Java files, 13 747 lines, under `ots-demo/.../demo/mirova`. The 88 files under
`ots-road/.../tactical/mirova` are only covered where the demo code reaches into them.

---

### 2.1 Summary: the entries that matter most

| # | Entry | Kind | Recommendation |
|---|---|---|---|
| T2-1 | `RunFreiburgMergeWatch` runs speed gain 15/30 **m/s** while its Javadoc says km/h and "study baseline 15.0". The `freiburg-merge` FSM reference trace inherits that value. | stale hand copy | Fix the copy, or label it `legacy` on purpose; decide what the trace should pin |
| T2-2 | `MergeScenario` ignores `demand` and `mergeShare` from the run parameters, so `RunParallelMergeScenarios` and `FsmTraceHarness` set values that nothing reads | set, not read | Read `params`, or stop setting the values; note that the MERGE trace depends on it |
| T2-3 | `farAnticipationEnabled` is set in 3 files, 6 call sites, but its only reader, `AnticipateDownstreamMergePattern`, is not registered | set, not read | Drop the sets, or keep them deliberately for the day the pattern returns |
| T2-4 | `ScenarioGenerator` silently drops `car.`/`truck.` keys it cannot resolve | trap | Fail loudly on an unknown key |
| T2-5 | The production parameter set is spread over 6 classes, 4 of them historical calibration campaigns | scattered defaults | Put it in one class, then mark the campaigns historical |
| T2-6 | `docs/decoupling/parameters.md` is stale in five places, including the TMIN/TMAX example this task started from | stale doc | Correct it |
| T2-7 | `SimpleHighwayScenario` + the Wiedemann 99 family: about 1 460 lines, no publication, no cluster use, no Python use | retire candidate | Retire; costs one skipped test case plus doc/ADR amendments in OTS and TaMA |
| T2-8 | `--array` in `run_mirova.sbatch` is hand-set, and a too-small range drops runs without an error | hand-maintained count | Guard with `SLURM_ARRAY_TASK_MAX`, or submit through a wrapper |
| T2-9 | 21 of 27 desired-speed distributions are selected by no scenario | unused library entries | Keep the file, mark which entries are live |
| T2-10 | Local runners with hard-coded `D:\` paths and legacy parameter copies (`RunFreiburgParallel`, `RunFreiburgNord`, `RunMerge`, …) | superseded | Retire, or replace with one parameterised runner |

---

### 2.2 Parameters that are set but that nothing reads

#### T2-0 — The known example (TMIN/TMAX on MiRoVA factories) is already fixed

- **What:** `docs/decoupling/parameters.md:128-131` says that `MergeScenario` and `SimpleHighwayScenario`
  set `ParameterTypes.TMIN`/`TMAX` on MiRoVA planner factories.
- **Evidence:** commit `3b73c7ab7` ("delete the parameters nothing reads") removed those calls from
  `SimpleHighwayScenario`. In `MergeScenario` they are commented out on the MiRoVA factories
  (`MergeScenario.java:236-237`, `:289-290`). The live calls sit on LMRS factories (`:257-258`, `:322-323`).
  The LMRS builders that contain them, `buildLmrsStrategicalPlannerFactoryCar/Truck` (`:252`, `:317`), are
  **[verified unused]**: their only call sites are the commented lines `:187` and `:189`.
- **Recommendation:** fix the paragraph in `parameters.md` (see T2-6). In `MergeScenario`, remove the two
  dead LMRS builders, the commented W99 blocks (`:216-226`, `:271-280`), and the imports that only those
  blocks use (`:67-71`, `:89`). About 60 lines, `[no behaviour change]`.

#### T2-1 — `RunFreiburgMergeWatch` sets the published speed gain but says it sets the intended one

- **What:** `RunFreiburgMergeWatch.java:160-161` declares `CAR_V_GAIN = 15.0` with the Javadoc
  "[km/h]. Study baseline: 15.0", and `:251` declares `TRUCK_V_GAIN = 30.0`. Both are applied as
  `Speed.instantiateSI(...)` (`:374`, `:392`), which gives 54 and 108 km/h. Since `abe9125bd` the study
  baseline is 15 and 30 **km/h** (`FreiburgStudyParameters.CAR_V_GAIN`/`TRUCK_V_GAIN`). The class header
  (`:32-36`) still promises that the defaults "reproduce the calibration the studies use".
- **Referenced by:**
  - `FsmTraceHarness.parametersFor` → `RunFreiburgMergeWatch.watchParameters` (`FsmTraceHarness.java:226`).
    The committed `freiburg-merge.trace.csv.gz` therefore pins the **legacy** speed gain. Its README
    (`src/test/resources/mirova/fsmtrace/README.md:22-24`) presents this as "a change to that calibration
    reaches the regression net". That stopped being true when `abe9125bd` changed the calibration and
    left this copy alone.
  - `tools/run_merge_gui.sh`, `cluster/README.md:811`, and four docs under `docs/mirova`.
- **Same pattern elsewhere:** `RunFreiburgNord.java:46-47` and `RunFreiburgParallel.java:88`, `:100` also
  write `Speed.instantiateSI(15.0 / 30.0)`.
- **Recommendation:** decide what the merge-watch is for.
  - If it is "the production model, editable": take the speed gain from `FreiburgStudyParameters` as the
    other constants already do. This changes behaviour and needs a re-recorded trace.
  - If it should stay on the published model: rename the constants to say so and fix the Javadoc.
  - Either way, most of its 26 constants copy values from `FreiburgStudyParameters`, and the copy has now
    drifted once. Deriving every default from the production class, and keeping only the
    `System.getProperty` overrides here, removes that risk.

#### T2-2 — `MergeScenario` ignores `demand` and `mergeShare` from the run parameters

- **What, [verified set, not read]:**
  - `demand`: the only reader is `createVehiclesFromGenerator` (`MergeScenario.java:168`), which is not
    called; its call is commented out at `:160`. The OD path hard-codes a ramp from 1000 to 6500 veh/h
    (`:392-394`, with `// params.getDemand()` left in the comment).
  - `mergeShare`: read from `this.defaultParameters` rather than from `params`, both for routes
    (`:191`, `:193`) and for OD demand (`:407-410`). The default is 0.2 (`:493`).
  - The comment at `:491` says "5% trucks" next to `0.1`.
- **Referenced by (writes with no effect):**
  - `RunParallelMergeScenarios.java:45`: `setMergeShare(0.1)`, which silently runs at 0.2.
  - `RunMerge.java:28`: `setMergeShare(0.2)`, the same value as the default, so no visible difference.
  - `FsmTraceHarness.java:236`, `:238`: `setDemand(4000)`, `setMergeShare(0.2)` for the MERGE case.
    `setDemand` has an effect only for HIGHWAY.
  - `FreiburgNord.java:560, 563`: default `demand` 4500 and `mergeShare` 0.2. They are read only by the
    synthetic-demand fallback (`:355-390`), which strict mode (`KEY_DEMAND_CSV_STRICT`, set by every study)
    makes unreachable.
- **Recommendation:** let `MergeScenario` read `params`. This is a `[behaviour change]` only for
  callers passing a non-default value. The MERGE trace passes the default and should stay byte-identical;
  verify with `FsmTraceRegressionTest`. Otherwise remove the ineffective `set` calls. Also retire
  `createVehiclesFromGenerator` in `MergeScenario` (**[verified unused]**).

#### T2-3 — `farAnticipationEnabled`

- **What:** `MirovaParameters.farAnticipationEnabled` (`FAR_ANTICIPATION_ENABLED`, default `true`) is
  snapshotted (`MirovaParameterSnapshot.java:365`) and read only by `AnticipateDownstreamMergePattern`.
  That pattern's registration is commented out at `MirovaTacticalPlannerFactory.java:213`, with a
  measured reason (`:197-212`). The same holds for `preemptiveCooperativeDeceleration`, but only the
  pattern's own read of it is dead: `GapOpenerPattern` also reads it and is live.
- **Set by, [verified set, not read]:** `FreiburgStudyParameters.java:261`, `:278`;
  `RunFreiburgMergeWatch.java:377`, `:396`; `RunFreiburgParallel.java:91`, `:104`. That is 3 files and 6
  calls. The count is also the correction to `parameters.md:174`, which says "six studies".
- **Recommendation:** keep the parameter, since the pattern is retained by decision. Either remove the six
  `false` writes (`[no behaviour change]`), or mark them in the code as deliberate insurance for when the
  pattern is re-registered. At present they suggest to a reader that the far anticipation is switched off
  by configuration. In fact it is switched off by the missing registration.

#### T2-4 — Unknown `car.`/`truck.` keys are dropped without a word

- **What:** `ScenarioGenerator.buildStrategicalPlannerFactoryCar/Truck` look each key up in
  `PARAMETER_TYPES` and apply it only `if (pt != null)` (`ScenarioGenerator.java:1238`, `:1277`). A typo,
  or the id of a parameter that has since been deleted (for example the five removed in `3b73c7ab7`),
  runs as the model default with no warning.
- **Evidence it has not bitten yet:** every `car.`/`truck.` key in the demo tree is built from
  `<Type>.<FIELD>.getId()`. That is 20 distinct parameters, all of which resolve today. No study uses a
  literal id string.
- **Recommendation:** throw on an unresolved key, `[no behaviour change]` for every current study. This is
  the same class of trap as the SI/km/h speed gain that `fe5275866` closed.

#### T2-5 — Other scenario-parameter keys without readers

- `ScenarioParameters.KEY_RANDOM_STREAM` (`ScenarioParameters.java:119`): **[verified unused]**, no
  setter, getter or use.
- `setNetworkName`/`getNetworkName` and `setDesiredSpeedDistribution`/`getDesiredSpeedDistribution`
  (`:219-233`): **[verified unused]**, no caller of any of the four.
- `RELAXATION_ACC_DAMPING_ENABLED = true` together with `RELAXATION_DAMPING = 1.00`
  (`FreiburgStudyParameters.java:138`, `:266-267`, `:281-282`): not dead, but a no-op pair. With factor 1.00, "enabled"
  is identical to disabled; `FreiburgDampingStudy`'s Javadoc proves this. Worth a comment, not a change.
- **Recommendation:** remove the three unused accessors and the constant, `[no behaviour change]`.

---

### 2.3 Studies and parameter grids

#### T2-6a — What each registered study is for, and what depends on it

All 22 studies are registered in `StudyRegistry.java:48-69`. None is named in `cluster/`, except `dates`
(the default in `run_mirova.sbatch:329`) and the campaign log in `cluster/README.md`. The `diss_mvb`
pipeline reads output folders generically and names no study. The one study-specific trace in it is a
hard-coded `_final` run path in `scripts/presentation/merge_slide/build_episodes.py:36`. TaMA imports
`Phase05ReferenceStudy` (`RecordFreiburgNord.kt`, `RunFreiburgNordLive.kt`), which reaches
`FreiburgCongestedBranchStudy.forCell` and `FreiburgProductionStudy`.

| Short name | Class (lines) | Role today | Depended on by other classes | Recommendation |
|---|---|---|---|---|
| `final` | `FreiburgFinalStudy` (177) | The published ensemble (ITSC/TR-B) | `RunFreiburgMergeWatch` constants | **keep** |
| `production` | `FreiburgProductionStudy` (190) | Production set + `legacy` variant | Phase05, VGain×3, Capacity, CapDrop, Relaxation, Susceptibility, Final | **keep**; see T2-5b |
| `phase05` | `Phase05ReferenceStudy` (281) | Golden reference and core set; **used by TaMA** | TaMA recorder | **keep** |
| `vgaintau`, `vgainscreen`, `vgaingrid` | 217 / 191 / 194 | Recalibration inputs (`recalibration-inventory.md`) | each other | **keep** |
| `dates` | `DateStudy` (187) | Generic date×set study; `resolveDates`/`resolveDemandCsvs` used by 20 studies | all | **keep** |
| `congested` | `FreiburgCongestedBranchStudy` (194) | Historical campaign, but its `forCell` builds the production cell | Production, Phase05, VGain×3 | **keep until T2-5b**, then historical |
| `combos` | `FreiburgCombinationStudy` (220) | Historical campaign; `forCombination` is part of the production chain | 16 studies | same as above |
| `carparams`, `sensitivity` | 186 / 190 | Historical; export `SAFETY_DISTANCE_FACTOR`, `BASE_HEADWAY`, `BASE_DAMPING` into the production chain | Congested, Settled, Smoothness, Validation, VGainScreen | same as above |
| `damping` | 138 | Historical (campaign 2); exports `resolveHeadwayCombination` | Behaviour, Car, MergeGrid | historical |
| `paramgrid`, `mergegrid`, `behaviour`, `validation`, `smoothness`, `settled`, `capacity`, `capdrop`, `susceptibility`, `relaxation` | 135–246 each, 1 755 total | Calibration campaigns 1–13 of `recalibration-inventory.md:27-39` | a few static constants | **mark as historical** |

**Why "historical" and not "retire":** `docs/decoupling/recalibration-inventory.md` uses these classes as
the record of the calibration path (their Javadoc holds the measured findings), and states at `:142` that a
rerun "needs nothing structural". Deleting them would lose that record and the ability to rerun a campaign
at a pinned commit.

**Recommendation:** move the historical campaigns into a `scenarios.historical` package, or list them in a
separate `StudyRegistry` block so that `knownStudies()` shows them as historical. Add a one-line
"historical: campaign N, superseded by …" to each Javadoc. Output folder names come from
`FreiburgStudyParameters.scenarioName` plus labels, not from the package, so this is
`[no behaviour change]`, but re-check `StudyRegistry.resolve` callers that pass fully qualified names.

#### T2-5b — The production parameter set lives in six places

- **What:** the cell that `production`, `final`, `phase05` and the three `vgain*` studies resolve is
  assembled from:
  1. `FreiburgStudyParameters.baseBehaviorParams` (T, a, b, s0, vGain, and more);
  2. `FreiburgCombinationStudy.forCombination` (T, damping, safety factor);
  3. `FreiburgCarStudy.SAFETY_DISTANCE_FACTOR = 0.40` (`FreiburgCarStudy.java:67`), re-exported by
     Sensitivity `:54` and CongestedBranch `:59`;
  4. `FreiburgSensitivityStudy.BASE_HEADWAY`/`BASE_DAMPING = 1.00` (`:57`, `:61`);
  5. `FreiburgCongestedBranchStudy.forCell` (b, s0, a) (`:178-193`);
  6. `FreiburgProductionStudy.B = 1.75`, `A_CAR = 1.4` (`:63`, `:72`).

  TaMA's `docs/checkpoints/stage8b.md:63-65` had to trace this chain by hand to find out that production
  damping is 1.00.
- **Duplication inside the chain:** `FreiburgBehaviourStudy.java:69,72` and `FreiburgCarStudy.java:64,67`
  both declare `ACC_DAMPING_FACTOR = 0.80` and `SAFETY_DISTANCE_FACTOR = 0.40` independently.
- **Recommendation:** add one `FreiburgProductionParameters` (or extend `FreiburgStudyParameters`) that
  states the whole cell. `forCell`/`forCombination` would then delegate to it, and the historical campaigns
  would reference it rather than being referenced by it. Once that is done, T2-6a's "keep until T2-5b"
  rows become freely retirable. `ParameterTableGenerator` already resolves the set through the real code
  path, so a byte-compare of `parametertable.tex` before and after proves the refactor is
  `[no behaviour change]`. Also note `recalibration-inventory.md:44`: `production` resolves T = 1.10/1.40
  and `final` resolves 1.00/1.30. A single class would make that fork visible rather than implicit.

#### T2-6c — `ParameterGridBuilder` (356 lines)

- **What:** a generic Cartesian-product builder.
- **Referenced by:** `FreiburgParameterStudy` only (`paramgrid`), plus two docs. It carries
  `@author Antigravity Agent`, as does `RunFreiburgParallel`, against the KIT header standard. Every later
  grid study hand-rolls nested loops instead, and `FreiburgCombinationStudy.java` Javadoc (lines 60-66)
  explicitly defers "a `List<Dimension>` walked by a generic cartesian product".
- **Recommendation:** either make it the single grid mechanism (a merge, when a study next needs a new
  axis), or retire it together with `paramgrid`. Keeping a generic builder that one historical study uses
  is the worst of both.

---

### 2.4 Distribution libraries

#### T2-9 — `DesiredSpeedLibrary` (755 lines, 27 public factory methods)

| Entry | Selected by | Status |
|---|---|---|
| `carsLimit140_DensityLow` (`:284`) | `FreiburgNord.java:220` | **live, production** |
| `trucksLimit100_DensityClass1_Modified` (`:698`) | `FreiburgNord.java:227` | **live, production** |
| `hoogendoornCars` (`:345`), `hoogendoornTrucks` (`:387`) | `MergeScenario.java:198,205`, `SimpleHighwayScenario.java:179,186` | live (MERGE trace); becomes MERGE-only if T2-7 is done |
| `carsUnrestricted` (`:110`), `trucks` (`:441`) | `SimpleSimulation.java:252,255` only | dies with `SimpleSimulation` (T2-10) |
| `cars100kmh`, `cars120kmh`, `cars130kmh` | — | **[no grep reference]** |
| 8 other `carsLimit{80,100,120,140}_Density*` | — | **[no grep reference]** |
| 10 other `trucksLimit{80,100}_DensityClass*` (5 plain, 5 `_Modified`) | — | **[no grep reference]** |

- Nothing in TaMA or `diss_mvb` calls the library. TaMA's ADR-008 names `carsLimit120_DensityHigh` only as
  a classification example.
- The entries are static methods called from Java. Selection by string (`KEY_DESIRED_SPEED_DISTRIBUTION`)
  exists as a parameter key, but it has no reader (T2-5), so no hidden reflective use is possible.
- **Correction:** `parameters.md:141` says "28 named distributions plus `trucks`". There are 27 methods
  including `trucks`.
- **Recommendation:** keep the file. The unused fits are cheap and document the source data, and the
  open decision `contract.md` Q3 on the `_Modified` variants concerns exactly these entries. Add a header
  table marking the two production entries. Retiring the 21 unused methods (about 560 lines) is fine too,
  but only after Q3 is settled.
- **One observation, not a finding:** FreiburgNord takes the *low-density, 140 km/h* car fit for every
  time of day. Whether that was intended is outside this audit.

#### `HeadwayDistributionLibrary` (80 lines, 1 method)

`shiftedExponential` is used by `FreiburgNord.java:474`. **Keep.** A one-method "library" could be folded
into `FreiburgNord`, but the saving is trivial.

---

### 2.5 T2-7 — `SimpleHighwayScenario` and the Wiedemann 99 family

**What it is:** a synthetic 3-lane motorway scenario that runs MiRoVA on `Wiedemann99Factory` for cars and
trucks (`SimpleHighwayScenario.java:242-290`, W99 data logger at `:362`). No result from it is in any
publication.

**What would be removed:**

| File | Lines |
|---|---|
| `ots-demo/.../scenarios/SimpleHighwayScenario.java` | 576 |
| `ots-demo/.../scenarios/RunSimpleHighwayScenarios.java` (hard-coded `D:\` path, 11 commented-out code lines) | 51 |
| `ots-road/.../ReactiveLayer/Wiedemann99.java` | 323 |
| `ots-road/.../ReactiveLayer/AbstractWiedemannModel.java` | 261 |
| `ots-road/.../ReactiveLayer/W99ParameterTypes.java` | 94 |
| `ots-road/.../ReactiveLayer/AbstractWiedemannFactory.java` | 78 |
| `ots-road/.../ReactiveLayer/Wiedemann99Factory.java` | 29 |
| `ots-road/.../util/logging/extendeddata/ExtendedDataW99DrivingMode.java` | 49 |
| W99 imports and commented W99 blocks in `MergeScenario.java` (`:67-71`, `:89`, `:216-226`, `:271-280`) | about 27 |
| **Total** | **about 1 490** |

It would also remove the one `ParameterSet`-as-state wart that `parameter_access_and_units.md:272-274`
records (`CURRENT_DRIVING_MODE` written by `Wiedemann99`).

**What it would cost, meaning everything that depends on it:**

| Dependant | Evidence | Cost |
|---|---|---|
| Tests | `FsmTraceHarness.Case.HIGHWAY` (`FsmTraceHarness.java:66`, `:202`); `FsmTraceRegressionTest.highwayTraceIsUnchanged` (`:72-76`) | **None in coverage.** No reference trace exists, because the scenario "currently aborts on the deadlock watchdog" (`fsmtrace/README.md:28`), so the test always skips. Remove the enum constant and the test method. No `ots-road` test references W99 (`grep` over `ots-road/src/test`). |
| GUI / IDE | `.vscode/launch.json` has no entry for it; `RunSimpleHighwayScenarios` has no references | none |
| Cluster | no reference in `cluster/` | none |
| Python (`diss_mvb`) | no reference to `SimpleHighway`, `W99` or `Wiedemann99`. The Wiedemann hits there are *field-data* estimation of Wiedemann-74 thresholds (`estimate_car_following.py`, `run_behavioral_analysis.py`), unrelated to the OTS class | none |
| TaMA | ADR-011 (`docs/adr/011-idm-plus-only-in-v1.md:11-21`) and `tama-api/.../CarFollowingModel.kt:25-26` justify "W99 stays in OTS" by `SimpleHighwayScenario` being alive | Amend the ADR and the KDoc sentence. No code dependency. |
| OTS decoupling docs | `contract.md:399-400`, `:596`; `contract/CarFollowing.kt:19-20`; `contract-traceability.md:171`, `:238-239`; `inventory.md:167-168`, `:391`, `:453`; `phase05-report.md:180-193`, `:675`, `:699` (decision A.3) | Add a follow-up note to A.3 ("retired in …"). Do not rewrite the history. |
| OTS model docs | `layer4_reactive_control.md:88-94`; `scenarios_and_simulations.md`; `scenariomanagement_architecture.md`; `fsm_reengineering_plan.md`; `parameter_access_and_units.md:189`, `:234`, `:272-274` | Remove or annotate the sections |
| `DesiredSpeedLibrary.hoogendoorn*` | also used by `MergeScenario` | stays |

**Recommendation: retire, in one `[no behaviour change]` commit.** The live model never instantiates W99;
only a scenario that cannot currently complete a run does. The commit would amend Phase 0.5 decision A.3
and TaMA ADR-011 rather than contradict them: their premise was "alive", and "alive" meant only that the
class is instantiated. If Marvin wants a W99 comparison for the thesis later, a pinned tag
(`pre-w99-retirement`) keeps it reproducible at no maintenance cost.

---

### 2.6 T2-10 — Local runners and prototypes that are superseded

| Class | Lines | References | What is wrong | Recommendation |
|---|---|---|---|---|
| `demo/mirova/SimpleSimulation.java` | 359 | `.vscode/launch.json:62-64`; `troubleshooting_and_compilation.md` (about a *different* upstream tutorial class, `docs/08-tutorials` likewise) | Pre-scenariomanagement prototype. Uses OTS `IdmPlusFactory`, not `MirovaIdmPlus` (`:261`, `:263`), so no relaxation. Hard-coded `D:\` output path (`:354`); 31 commented-out code lines (the most in the tree). | **retire** (with its launch entry) |
| `RunMerge.java` | 34 | `.vscode/launch.json:69-71` | Output path `D:\...\bodegraven\lmrs\run_N` (`:20`) though it runs MiRoVA; loop `run < 1` printed as "of 10"; `setMergeShare` ineffective (T2-2) | **retire** or fold into one merge runner |
| `RunParallelMergeScenarios.java` | 71 | `launch.json:76-78`; `scenarios_and_simulations.md` | `setMergeShare(0.1)` silently ignored (T2-2); hard-coded path `:30` | **retire** or fix with T2-2 |
| `RunFreiburgParallel.java` | 134 | `FreiburgCombinationStudy.java:75` (Javadoc); `scenariomanagement_architecture.md:92`, `:241`; `README.md:33`; `mirova_parameters_documentation.md`; `scenarios_and_simulations.md`; `troubleshooting_and_compilation.md` | A hand-kept copy of an old baseline: T 1.00/1.30 and 0.90/1.20, damping 0.8, `RED_FAC` 0.60, speed gain in SI, cooperative deceleration −2.0/−0.5 against the study's −3.0/−1.0 (`:90`, `:102`). Output folder `freiburg_20250923_AnticipateMergeFix`; `@author Antigravity Agent`. `scenariomanagement_architecture.md:241` still recommends it as *the* way to run a scenario locally. `diss_mvb/.../dashboard_comparison.py:10` hard-codes a `freiburg_parallel` output of it. | **retire**; point the doc at `cluster/run_local_parallel.sh --study=…` or `RunFreiburgMergeWatch` |
| `RunFreiburgNord.java` | 56 | `launch.json:83-85`; `scenarios_and_simulations.md` | T 1.00/1.30 and speed gain 15/30 SI (`:44-47`), nothing else of the production set; hard-coded path `:29` | **retire**, or turn into "production cell with GUI" delegating to T2-5b |
| `RunFreiburgNordNoDemand.java` | 51 | `scenarios_and_simulations.md` only | Hard-coded path `:21` | **[no grep reference]** in code → **retire** |
| `TestReflection.java` | 204 | `troubleshooting_and_compilation.md`, `scenariomanagement_architecture.md` | A live, useful JAXB diagnostic, but named like a unit test and filed under `scenarios` | **keep; move and rename** (e.g. `tools/JaxbClassLoadingCheck`) |

Retiring the first six removes about 705 lines and the hard-coded `D:\` output paths in the runners.
`RunFreiburgMergeWatch` is **kept**: it is the documented interactive tool and feeds the FSM trace (but
see T2-1).

---

### 2.7 Hand-maintained things that tooling should own

Precedents: the campaign count went stale twice, and the OTS snapshot was hand-copied until a
`snapshotOts` task existed.

#### T2-8 — The SLURM array range

- **What:** `run_mirova.sbatch:60` hard-codes `#SBATCH --array=0-159`. The header table (`:32-35`) marks
  one row "<- current" by hand. `cluster/README.md` repeats per-campaign `--array=` values at `:185-190`,
  `:257`, `:267`, `:282`, `:549`, `:616`, `:673`, `:726`, `:835`, `:997`.
- **Why it matters:** the script *does* ask the study for `TOTAL_RUNS` (`:418-428`) and refuses a task
  whose indices are all past the end (`:491-495`). It does **not** notice a range that is too *small*:
  runs above `2·(max task + 1)` are never launched, and nothing reports it.
- **Recommendation:** in the batch script, compare `SLURM_ARRAY_TASK_MAX` against
  `ceil(TOTAL_RUNS / RUNS_PER_TASK) − 1` and fail task 0 on a mismatch. Or add
  `cluster/submit_study.sh`, which runs `--count` and passes `--array` on the command line; the `#SBATCH`
  line then becomes a documented placeholder. Either way, drop the "<- current" marker.

#### T2-8b — Date lists

- `cluster/dates.txt` (16 dates) and `dates_extension.txt` (7) restate the same seven dates by hand.
  `dates_calibration.txt` (3) is a subset.
- `cluster/README.md:41` still calls `dates.txt` a "**template — swap in the real 32 dates**", and `:190`
  sizes a 32-date campaign. The file holds 16 real dates. `cluster/demand/` holds 32 files, which are
  16 dates × {plain, `_wide`}, which is probably where "32" came from.
- **Recommendation:** fix the two README lines now. Longer term, one annotated date file with tags
  (`calibration`, `extension`) and a `--dates=cluster/dates.txt#extension` selector in
  `DateStudy.resolveDates` would remove the copies. That is optional; the lists change rarely.

#### T2-8c — Hard-coded workstation paths in code that runs on the cluster path

| Location | Path | Guarded? |
|---|---|---|
| `ScenarioManager.java:260`, `:262` | `mirova\venv\Scripts\python.exe`, `diss_mvb\scripts\simulation\ots\plot…` | skipped by `MIROVA_SKIP_POSTPROCESSING` |
| `ScenarioGenerator.java:869`, `:871` | same venv; `diss_mvb\scripts\evaluation\…` | skipped by `skipDemandPrep` / `MIROVA_SKIP_DEMAND_PREP` |
| `FreiburgNord.java:304` | `diss_mvb\scripts\evaluation\fielddata\…` | — |
| `cluster/generate_demand_csvs.ps1:30`, `generate_demand_csvs.py:22` | venv, `diss_mvb` root | local tool |
| `tools/run_merge_gui.sh:19` | `D:/otsrun` default, env-overridable | OK |
| six runners in T2-10 | `D:\Mitarbeitende\…\mirova\output\…` | — |

**Recommendation:** one `MIROVA_PYTHON` / `MIROVA_DISS_MVB` pair (system property or environment variable),
read in one place, with the current paths as the fallback. `[no behaviour change]` on this workstation.

#### T2-8d — Parameter documentation that duplicates Java defaults

- `docs/mirova/parametertable.tex` is **tool-owned** (`ParameterTableGenerator`, resolved from the real
  code path). This is the model to follow.
- `docs/mirova/mirova_parameters_documentation.md` (70 lines, last touched 2026-07-31) is a hand-written
  default table and is already wrong:
  - it lists `DSEARCH` as an LMRS threshold, although it was deleted and then restored for BC-9;
  - it names `followerDecelerationThreshold`/`egoDecelerationThreshold`, although the code has min/max
    pairs;
  - it lists a `TR` reaction time that MiRoVA does not read.

  **Recommendation: retire** in favour of `docs/decoupling/parameters.md` plus `parametertable.tex`, or
  generate it from `MirovaParameters` the way the `.tex` is generated.
- `docs/decoupling/parameters.md` and `default-parameters.md` carry default values by hand. They are
  records of a phase and acceptable as such, but see T2-6 for the places where they have already drifted.
- `FsmTraceHarness` HIGHWAY/MERGE inputs (`:113-119`) are stated explicitly on purpose (README). Fine.

---

### 2.8 Things a reader would take for current that are not

#### T2-6 — `docs/decoupling/parameters.md`

| Line | Claims | Actually |
|---|---|---|
| 106 | `bSi` — **unread** | read by `MandatoryLaneChangePattern.java:1585`; §5 of the same file corrects it, but the table was not updated |
| 107 | `bCritSi` exists, unread | deleted in `3b73c7ab7` |
| 128-131 | MergeScenario and SimpleHighwayScenario set TMIN/TMAX on MiRoVA factories | fixed in `3b73c7ab7` (T2-0) |
| 141 | 28 named distributions plus `trucks` | 27 methods including `trucks` |
| 162-176 (§5 "declared but never read") | lists DSEARCH, aScale, STANDSTILL_SPEED_THRESHOLD, MANDATORY_…LOOK_AHEAD, VCRIT, bCritSi as current | all deleted (listed again at 177-188) |
| 174 | FAR_ANTICIPATION_ENABLED "set by six studies" | 3 files (T2-3) |
| 180 | DSEARCH deleted | restored by `3b3a5ad43` for BC-9 and read via `MirovaTacticalPlanner.java:468` |
| 44, 171 | CONGESTED_LANE_CHANGE_DURATION is read from commented code in `ExecuteLaneChangeState` | the commented read is in `MandatoryLaneChangePattern.java:2524-2528` |

**Recommendation:** one docs commit that brings §3/§5 to the post-A.7, post-BC-9 state.

#### Model docs describing deleted classes as live

| Doc | Stale content | Code state |
|---|---|---|
| `docs/mirova/layer2_desire.md:123-126` | §5 `CongestionIncentive` "Currently Disabled", links the source file | class deleted (`8cea03479`) |
| `docs/mirova/layer3_decision_intention.md:104` | `CongestedMergeState` "Dispatcher below 15 km/h" | deleted in `148062f6d` (2026-09-08); doc last touched 2026-08-27 |
| `docs/mirova/mandatory_lane_change_pattern.md:79-109`, `:126`, `:220`, `:305`, `:386` | state diagram and table built around `CongestedMergeState`, incl. `RECOVERY_SPEED_THRESHOLD` | same |
| `docs/mirova/README.md:24` | lists `CompositeActionState` among key classes | does not exist; it is a *planned* name in `fsm_reengineering_plan.md` |
| `docs/mirova/scenariomanagement_architecture.md:6` | `ContextManager`, `KnowledgeChunk` | no such classes; the planner has `getKnowledgeChunks()` returning `DesireIncentive` |
| `docs/mirova/scenariomanagement_architecture.md:241` | "run a single scenario locally: edit and run `RunFreiburgParallel`" | superseded (T2-10) |
| `docs/mirova/scenarios_and_simulations.md` (604 lines, 2026-08-20) | module tree without `StudyRegistry`, `DateStudy`, any `*Study`, `fsmtrace`; calls `MergeScenario` "abstract" | 22 studies exist; `MergeScenario` is concrete |
| `docs/mirova/layer4_reactive_control.md:88` | W99 "implemented and calibrated" | see T2-7 |
| `CLAUDE.md:47-48` | layer table names `ContextManager`, `KnowledgeChunk` | as above |
| `CLAUDE.md:104-109` (§6) | `checkContext`/`AnticipateMergeState` call `getDistanceToLaneChangeExtendedLookahead()` and the raise "is very likely inert" | method deleted under decision A.1 (`MandatoryLaneChangePattern.java:674` comment; `phase05-report.md:697`) |

`GapSearchPattern`/`AccelToGap` appear only in `CLAUDE.md:14`, already flagged there as historical. No doc
or code uses them. **Nothing to do.**

**Recommendation:** update `layer2`, `layer3`, `mandatory_lane_change_pattern.md` and `README.md` to the
post-`148062f6d` state, and correct `CLAUDE.md` §3 and §6 (Marvin's file). Retire or rewrite
`scenarios_and_simulations.md` against `scenariomanagement_architecture.md`, which is newer (2026-09-11)
and covers the same ground.

#### Point-in-time reports without a "historical" marker

`docs/mirova/`: `performance_profile_2026-08-20.md` (437), `performance_profile_2026-08-21_full_day.md` (684),
`djunits_hashcode_finding.md` (341), `djunits_patch_experiment.md` (271), `calibration_status_briefing.md`
(463), `congested_branch_review_request.md` (361). They are superseded respectively by
`performance_investigation_synthesis.md`, and by `parameter_sensitivity.md` and
`recalibration-inventory.md`. Two of them cite the now-deleted `ValueUtil` and `MirovaPositionUtil`.
**Recommendation: mark as historical** (a dated banner, or a `docs/mirova/archive/` folder) and keep them.
They are the evidence behind decisions.

`cluster/README.md` (1 022 lines) is about 20 % runbook and 80 % campaign log (from about `:380` on).
**Recommendation:** split it into `README.md` (runbook) and `CAMPAIGNS.md` (history), so the runbook's
stale lines (T2-8b) are not buried.

#### Commented-out code

A heuristic count (comment lines ending in `;`, `{` or `}`) finds 81 lines in `demo/mirova` and 34 in
`road/.../mirova`. Most sit in `SimpleSimulation` (31), `MergeScenario` (29) and
`RunSimpleHighwayScenarios` (11), all covered above. Several in `ots-road` are *documented* retentions:
`MirovaTacticalPlannerFactory.java:213` (with its measured reason) and the
`congestedLaneChangeDuration` block kept by decision. **Recommendation:** remove the blocks in the three
demo files together with T2-0/T2-7/T2-10. Leave the documented `ots-road` ones.

#### Stray files at the repository root

| File | Size | Last commit | Recommendation |
|---|---|---|---|
| `temp_import_fix.txt` | 99 KB, UTF-16, 1 105 lines of a Java source dump | `e38752f48`, 2026-05-13 | **[verified unused]** → retire |
| `parallelization_feasibility_report.md` | 24 KB | `54d8efe2b`, 2026-08-06 | superseded by `performance_investigation_synthesis.md` → move to `docs/mirova/archive/` |

---

### 2.9 Suggested order

1. **Docs only:** T2-6, the stale model docs, `cluster/README.md:41/190`, and the CLAUDE.md notes.
   No risk.
2. **`[no behaviour change]` code:** T2-4 (fail on unknown key), T2-5 (dead accessors), T2-0 (LMRS/W99
   leftovers in `MergeScenario`), T2-3 (drop the `farAnticipationEnabled` writes), the root stray files.
3. **T2-5b:** consolidate the production cell, proven by a byte-identical `parametertable.tex` and the
   Phase 0.5 reference recording. Then mark the campaigns historical (T2-6a).
4. **T2-7 and T2-10:** retire the W99 family, `SimpleHighwayScenario`, and the superseded runners. Amend
   A.3 and TaMA ADR-011.
5. **Decisions with a behaviour or trace consequence:** T2-1 (merge-watch speed gain → re-record
   `freiburg-merge` or relabel) and T2-2 (MergeScenario reads `params`). Each gets its own commit, labelled
   `[behaviour change]` if a trace moves.
6. **Tooling:** T2-8 (array guard or submit wrapper) and T2-8c (path configuration).
