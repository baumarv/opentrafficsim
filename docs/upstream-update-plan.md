# Updating the OTS fork against upstream: where it stands and what the options are

**This is a draft for Marvin, and it is a plan only.** Nothing was rebased, merged, committed or pushed, and
no branch or config in any existing repository was changed. The only file this work created is this
document. Every number below was measured, and the commands are given so each one can be checked again.

- **Measured on:** 2026-09-16.
- **Fork read at:** `decoupling_phase05` = `2ccc1b362` (the worktree `ots-fork-plan`), plus `main` = `4859cdaff`.
- **Upstream:** <https://github.com/averbraeck/opentrafficsim>, verified with `git ls-remote`. It has the
  branches `main` and `feature-redesign-speed-limits`, and the tags `v1.6.0` to `v1.8.1`.
- **Comparison clone:** `D:/Mitarbeitende/gw2128/repositories/tama-workspace/scratch/ots-upstream`. It was
  cloned with `--no-checkout`, and the fork was added as the remote `fork` inside that clone only. The fork
  repository itself still has no `upstream` remote.

```sh
git clone --no-checkout https://github.com/averbraeck/opentrafficsim ots-upstream
cd ots-upstream
git remote add fork D:/Mitarbeitende/gw2128/repositories/opentrafficsim
git fetch fork main decoupling_phase05 agent/tama-planner-selection --no-tags
```

> **A trap for every diff below:** most OTS files in the fork were committed with CRLF line endings. A
> plain `git diff` therefore reports **607 modified files** in the fork. With `--ignore-cr-at-eol`, only
> **17 of them are substantive**. Every count in this document uses `--ignore-cr-at-eol`, and `-M` where
> upstream renamed files.

---

## 1. Where the fork sits

| | SHA | Date | Notes |
|---|---|---|---|
| **Merge base** of the fork and upstream `main` | `81241d09fc20976d61dcd8b7b7f5a5021ec786b1` | 2025-05-26 | wjschakel, "Merge branch 'geometry' …". `git describe` gives `v1.7.6-57-g81241d09f`, and `--contains` gives `v1.8.0~168`. |
| First fork commit | `1ad165bd7` ("Initial commit baumarvs OTS") | 2025-06-11 | Its parent is the merge base. All 381 commits base..`decoupling_phase05` are Marvin's: 358 as "Marvin Baumann", 23 as "Baumann". |
| Nearest upstream tag | `v1.7.6` = `52579e747` | 2025-01-27 | The base is 57 commits after it and 168 before `v1.8.0`. Against the base, 1.7.6 differs in 156 `ots-road` files (+1713/−889). |
| `v1.8.0` | `f3bdcc260` | 2026-01-20 | 179 commits after the base. |
| **`v1.8.1`, the latest release** | `8136fb3bf` | 2026-08-29 | 387 commits after the base. |
| **Upstream `main` head** | `9b2ab14a3` | 2026-09-08 | "Fix: no dFree towards shoulder". 404 commits after the base: 317 + 84 by W.J. Schakel, 3 by A. Verbraeck. |

The merge base is the same for `main` and `decoupling_phase05`, so all fork branches share one base.

**Distance from the base:**

| Branch | Commits since the base |
|---|---|
| fork `decoupling_phase05` | 381 |
| fork `main` | 77 |
| upstream `main` | 404 |

**Library versions:**

| | Base / fork | `v1.8.0`, `v1.8.1`, upstream `main` |
|---|---|---|
| POM version | `1.7.6` | 1.8.0 / 1.8.1 / 1.8.1 |
| JDK | 17 | 17 |
| DJUnits | 5.2.1 | 5.4.1 |
| DSOL | 4.2.6 | 4.3.2 |

The fork's POM still says 1.7.6, but its code is not 1.7.6. It is the unreleased snapshot `81241d09f`.

```sh
mb=$(git merge-base origin/main fork/decoupling_phase05)   # 81241d09f
git describe --tags $mb
git describe --tags --contains $mb
git rev-list --count $mb..origin/main
git log -1 --format='%H %ci' v1.8.1
```

---

## 2. What the fork changed relative to the base

```sh
git diff --no-renames --name-status $mb fork/decoupling_phase05      # 249 A, 607 M, 0 D
git diff --no-renames --ignore-cr-at-eol --numstat $mb fork/decoupling_phase05
```

### 2.1 MiRoVA additions: new files that belong to us (`decoupling_phase05`)

| Area | Files | Lines |
|---|---|---|
| `ots-road/.../road/gtu/lane/tactical/mirova/` (main) | 86 | 20 162 |
| — of which `core/` (Belief 11, Desire 8, Intention 10, Reactive 12, Arbitration 4, VehicleTypes 1, root 3) | 49 | |
| — of which `util/` (29 in `util/logging`) | 32 | |
| — package root | 7 | |
| `ots-road/src/test/.../mirova/` (`GoldenFixtureGenerator`, `CarFollowingFixtureGenerator`) | 2 | 1 023 |
| `ots-demo/.../demo/mirova/` (main) | 59 | 13 586 |
| — `scenariomanagement` 13, `scenarios` 38 (17 `Freiburg*Study`), `libraries` 3, `fsmtrace` 3, `SimpleSimulation` | | |
| `ots-demo` tests (`FsmTraceRegressionTest`, README, two `.trace.csv.gz`) | 4 | 226 + 2 binary |
| `ots-demo/src/main/resources/mirova/` (`FreiburgNord.xml`, `MergeBodegraven.xml`) | 2 | 680 |
| `cluster/` (sbatch, env, README with 1 023 lines, 34 demand CSVs, date lists) | 43 | 34 577 |
| `docs/mirova/` | 27 | 8 118 |
| `docs/decoupling/`, `docs/cluster/` | 18 | 5 753 |
| `tools/` (`run_merge_gui.sh`) | 1 | 49 |
| Root clutter (`CLAUDE.md`, `.agents/AGENTS.md`, `.vscode/*`, `parallelization_feasibility_report.md`, `temp_import_fix.txt`) | 7 | ≈510 |
| **Total** | **249** | **≈84 700** |

In all, the MiRoVA Java is 43 369 lines, main and test, in `ots-road` and `ots-demo`.

**`main` (`4859cdaff`, 2026-06-03)** is much older:

- It adds 123 files: 98 in `tactical/mirova` (20 035 lines), 19 in `demo/mirova` (3 863), and one resource.
- It has no `cluster/`, no `docs/mirova` and no `docs/decoupling`.
- `main..published-model` is 275 commits. **`main` is therefore not the published model.** Of the two
  branches, only `decoupling_phase05` contains the tag `published-model` (`9eb7ab856`).

**Structural point:** MiRoVA does not sit *on top of* OTS. It sits **inside OTS's own packages**
(`org.opentrafficsim.road.gtu.lane.tactical.mirova`) and **inside OTS's modules**. One OTS module,
`ots-animation`, even imports MiRoVA (§2.2). That placement is the main thing that makes any update
expensive.

### 2.2 Modifications to upstream OTS files (the file exists at the base and was edited)

The 607 "modified" files break down as follows:

- **251** are generated JAXB classes in `ots-xml/.../xml/generated/` and `ots-opendrive/.../generated/`.
  They were regenerated under a German locale by `1ad165bd7`, so only the comment text changed
  ("Java-Klasse für …"). Not one non-comment line differs.
- **339** differ only in CR/LF.
- **17 are substantive**, and all 17 are listed below. Line counts are with `--ignore-cr-at-eol`.

| # | File | + / − | What it does | Kind |
|---|---|---|---|---|
| 1 | `ots-base/.../parameters/ParameterSet.java` | +7 / −2 | `getParameter` throws directly instead of `Throw.when(...)`, avoiding varargs allocation on a hot path | performance, no behaviour change |
| 2 | `ots-base/.../parameters/ParameterType.java` | +21 / −7 | `hashCode` precomputed in the constructor; `equals` short-circuits on unequal hashes | performance, no behaviour change |
| 3 | `ots-road/.../generator/characteristics/LaneBasedGtuTemplate.java` | +8 / −0 | adds the getter `getStrategicalPlannerFactory()` | API addition |
| 4 | `ots-xml/.../xml/parser/XmlParser.java` | +58 / −1 | static, lazily locked `JAXBContext` singleton, plus `warmUpJAXBContext()` (called by `ScenarioManager`) | thread-safety for parallel runs |
| 5 | `ots-xml/pom.xml` | +5 | explicit dependency on `ots-road` | build |
| 6 | `ots-animation/.../data/AnimationGtuData.java` | +202 | GUI getters that import `MirovaTacticalPlanner`, `EgoContext`, `Desire`, `ActionState` | **OTS module depends on MiRoVA** |
| 7 | `ots-demo/pom.xml` | +10 | `jakarta.xml.bind-api` and `jaxb-runtime` dependencies | build |
| 8 | `ots-demo/.../CircularRoadModel.java` | +13 / −4 | demo switched from LMRS to `MirovaTacticalPlannerFactory`; density 30→15; vMax 200→160/80 | demo |
| 9 | `ots-demo/.../CircularRoadSwing.java` | +60 / −1 | extended sampler, CSV written to a **hard-coded `D:\Mitarbeitende\…` path**, 600 s run length | demo, local hack |
| 10 | `ots-demo/.../InputParameterHelper.java` | +4 | `FSPEED` 1.4 for cars, 0.8 for trucks | demo |
| 11 | `ots-demo/src/main/resources/lmrs/shortMerge.xml` | +5 / −5 | speed limits 20→80 km/h, node positions, link type | demo |
| 12 | `ots-swing/.../script/AbstractSimulationScript.java` | +1 / −1 | default `--simulationTime` 3600 s → **600 s** | changes an upstream default |
| 13 | `ots-swing/.../gui/OtsSimulationApplication.java` | +2 | unused import and a stray blank line | noise |
| 14 | `ots-web/.../test/TJunctionModel.java` | +1 / −14 | XML parsing removed (build workaround) | build hack |
| 15 | `ots-road/.classpath` | +2 / −1 | Eclipse | noise |
| 16 | `.gitattributes` | +4 | `*.sh`/`*.sbatch` forced to `eol=lf` | repo |
| 17 | `.gitignore` / `README.md` | +45 / +29 −28 | repo | repo |

**Deleted upstream files: none** (`--diff-filter=D` is empty).

**`main`** has only #3, #8–#13 and #15. Rows #1, #2, #4–#7, #14 and #16 came later, on the
`decoupling_phase05` line.

**`agent/tama-planner-selection`** (2 commits on `decoupling_phase05`) adds no new change to an OTS source
file. It changes only `ots-demo/pom.xml` (a `tama` Maven profile), `ScenarioGenerator.java` and a new
`TacticalPlannerProvider.java`.

**MiRoVA also depends on upstream OTS without modifying it.** Examples: it sets `LaneBasedGtu.CACHING =
false`, a public static field; and it relies on the memoisation behaviour of `InfrastructurePerception`
(BC-7). §3 shows that both of these are gone or changed upstream.

---

## 3. What upstream changed since the base, in the areas the fork touches

Across the whole repository, base..`origin/main`:

- **404 commits**, 2 072 files, +86 359 / −86 461 lines (`-M --ignore-cr-at-eol`).
- **`ots-road` alone:** 514 files, +25 314 / −31 251.

Up to `v1.8.1` the figures are 387 commits, 2 065 files and +98 956 / −99 599 (not CR-normalised). The 17
commits after 1.8.1 are small LMRS, generator and XML fixes.

### 3.0 Where `v1.8.1` and `main` differ

- **The two package renames are both in `9a962e967` (#277):** `road.gtu.lane.*` → `road.gtu.*` and
  `road.network.lane.*` → `road.network.*`. Also `lane/plan/operational` → `gtu/operational`.
- **Base..`v1.8.1`** (`-M --ignore-cr-at-eol`): 387 commits, 2 063 files, +85 473 / −86 116.
- **`v1.8.1`..`main`:** 72 files, +1 348 / −807. The changes are LMRS incentives, the `LmrsFactory`, GTU
  template resolution and the XML parser (`a95085abc`, `813899e5b`, `10fccb745`, `cc6af1c37`, `9b2ab14a3`,
  `13b80d7b9`, `c89089d33`, `4386725af`).

Per area, counted over old and new paths together:

```sh
git rev-list --count $mb..origin/main -- <old> <new>
git diff -M --ignore-cr-at-eol --shortstat $mb origin/main -- <old> <new>
```

### 3.1 Summary by area

| Area | Commits | Files, + / − | Model underneath MiRoVA changes? | API break for MiRoVA |
|---|---|---|---|---|
| A. Car following and speed limits | 21 | 45, +1 093 / −3 414 | **Yes**, through the desired speed | yes |
| B. Operational plan, lane change, `LaneBasedGtu` | 44 | 22, +2 715 / −3 590 | **Yes** | yes, large |
| C. Tactical planner API and LMRS | 90 | 71, +6 189 / −4 633 | yes for LMRS traffic; possibly for MiRoVA | yes |
| D. Perception (incl. headway types) | 61 | 167, +8 755 / −8 298 | **Yes**, possibly large | yes, the whole layer |
| E. Parameters | 20 | 31, +313 / −935 | no, the values are the same | yes |
| F. Simulator, time, libraries | 34 | 74, +515 / −631 | no in the OTS code (DSOL internals not inspected) | yes, large |
| G. GTU generation / OD | 30 | 30, +644 / −667 | **Possibly** | small |
| H. XML (parser + xsd) | 63 | 40, +3 627 / −2 493 | n/a | **the network files must be rewritten** |

### 3.2 The substantive changes, by area

**A. Car following.**
- **IDM+ itself is unchanged.** `AbstractIdm.followingAcceleration`, `dynamicDesiredHeadway` and
  `IdmPlus.combineInteractionTerm` changed only in types (`Headway` → `PerceivedObject`) and
  `instantiateSI` → `ofSI`. For identical inputs the acceleration is identical (from reading the code;
  no run was made).
- **The desired speed was rewritten** (`93dc892ee`, `398309a6e`, #292). `SpeedLimitInfo`,
  `SpeedLimitProspect` and `SpeedLimitTypes` were replaced by `SpeedLimits` (a lane limit and a GTU-type
  limit):
  - a new `FSPEED_GTU`;
  - `FSPEED` is capped at 1 on an "enforced" limit;
  - a fallback of 130 km/h × `FSPEED` when there is no limit.
- **An apparent upstream defect, also in `v1.8.1`:** when a GTU-type limit exists, the computed speed is
  never returned. The method falls through to the 130 km/h fallback (`AbstractIdm.java` lines 241–261 on
  `main`). MiRoVA's Freiburg network defines GTU-type limits (`NL.CAR` 200 km/h, `NL.TRUCK` 120 km/h), so
  this path would be hit.
- **API:**
  - The signatures of `CarFollowingModel.followingAcceleration(…, SpeedLimits, Speed maxVehicleSpeed, …)`
    and `DesiredSpeedModel.desiredSpeed(Parameters, SpeedLimits, Speed)` changed.
  - `CarFollowingUtil.stop/freeAcceleration/approachTargetSpeed` now take a `TacticalContext`. The formula
    of `approachTargetSpeed` is unchanged.
- **Behavioural adaptation** (`a5a51d7e4`, `e6f528b61`) matters only with the Fuller model active.

**B. Operational plan and lane change** (`da3fc0687`, #59):
- **`LaneChange` is deleted** (−838 lines) and has no replacement class:
  - the lane-change state lives on the GTU (`setLaneChangeDirection`);
  - the path is always a Bezier (`bezierToTarget`) over a `tManeuver` (`LCDUR`, 3 s).
- **`LaneOperationalPlanBuilder`:** `buildAccelerationPlan`, `buildAccelerationLaneChangePlan`,
  `createPathAlongCenterLine` and `scheduleLaneChangeFinalization` are removed. What remains is
  `buildPlanFromSimplePlan(gtu, plan, tManeuver, deviation)`. Paths now include lateral deviation and drift
  toward the lane centre.
- **`SimpleOperationalPlan`** lost `setIndicatorIntent*`, `getIndicatorIntent` and `setTurnIndicator`
  (MiRoVA uses them 11 times). Indicators now go through `TacticalContext` intents.
- **Lane bookkeeping:** `LaneBookkeeping` (`c04eaa6c4`) has a **default of `START`**, which moves the GTU
  to the target lane at the start of the change. Before, the GTU was registered on both lanes until a
  scheduled finalisation. **This changes when other drivers perceive a lane changer in the new lane**, which
  is exactly what MiRoVA's cut-in detection (`NeighborsContext` leader-ID edge trigger) and the relaxation
  react to.
- **Positions:** the multi-lane position API is gone (`positions()`, `fractionalPositions`,
  `getReferencePosition`). **`LaneBasedGtu.CACHING` no longer exists.** There is a single-slot position
  cache, fixed in `c660de221`.

**C. Tactical planner API and LMRS.**
- **Planner API:**
  - `generateOperationalPlan` throws `OperationalPlanException`.
  - `AbstractLaneBasedTacticalPlanner` loses `buildLanePathInfo`/`determineNextSplit`; `LanePathInfo` is
    removed (`aabef1dc8`).
  - The factory gains `peekDesiredSpeed(GtuType, SpeedLimits, Speed, Parameters)`/`peekDesiredHeadway`,
    returning `Optional`.
  - `TacticalContext`/`TacticalContextEgo` (`7f8603fa9`, `e0fd70f8f`, `717aa7116`) take over
    `getDesiredSpeed`/`getCarFollowingAcceleration` from `LaneBasedGtu`, where they are removed.
  - `DesireBased` moved to `tactical.lmrs`.
- **`LmrsFactory` rewrite** (`0e688c801`, `1e2dfadf5`, `a95085abc`): a settings bean per GTU type, with
  defaults PASSIVE/PASSIVE/INFORMED.
- **LMRS behaviour:**
  - lane-change desire during a lane change is no longer forced to 1/0 (`ede7f7278`);
  - Synchronization loses a term below `VCONG` (`887da56f3`);
  - gap acceptance and cooperation use deviation, and a left/right swap in `DirectDefaultSimplePerception`
    is fixed (`e66571b82`);
  - `AccelerationLaneChangers` (`82c3b37b2`);
  - `IncentiveStayOnSlowLanes` is off below `VCONG`;
  - no `dFree` toward a shoulder (`9b2ab14a3`).

**D. Perception.**
- **Headway types:** the `headway` package (`Headway`, `HeadwayGtu`, `HeadwayGtuReal`, …) is replaced by
  `perception.object` (`PerceivedGtu`, `PerceivedObject`, …) (`4850ab4a1`, `556984626`, `7baff3309`).
  MiRoVA uses `HeadwayGtu` 201 times in 19 files.
- **Mental-model classes** became `ArTask*`/`ChannelTask*`/`AttentionMatrix` (`33982de3c`).
  `DirectDefaultSimplePerception` is removed. `getPerceptionCategoryOrNull` became `Optional`.
- **The memoisation key is unchanged.** `legalLaneChange`/`physicalLaneChangeInfo` are still keyed by
  relative lane (and direction), not by `LOOKAHEAD`. **BC-7 (the inert extended look-ahead) persists.**
- **`LaneStructure` changes results:**
  - `df69ac37c`: `start()` no longer inserts non-existent lanes into the cross-section, so "does a LEFT
    lane exist" can answer differently.
  - Downstream/upstream iteration no longer range-filters GTUs; only ego is skipped.
  - Merge distance carries over to downstream branches.

**E. Parameters.**
- **`ParameterTypes`:**
  - `LOOKBACKOLD` removed;
  - `PERCEPTION` → `LC_INFO` (id `lcInfo`);
  - **`LOOKAHEAD` id `"Look-ahead"` → `"x0"`** (default 295 m unchanged);
  - `FSPEED_GTU` added.
  - All other defaults unchanged: a 1.25, b 2.09, s0 3, T 1.2, `LCDUR` 3, `VCONG` 60.
- **`Parameters` API:**
  - `getParameterOrNull` → `Optional getOptionalParameter`;
  - a default value is now required (`e9084d7e6`);
  - claimed parameters, where `setParameter` throws on a claimed type and LMRS claims `DLEFT`/`DRIGHT`
    (`89002bbdb`, `0fe0c3b8c`).
- **The hot path is unchanged:** `hashCode` is still recomputed and `Throw.when` is still used. See §4.
- **`LmrsParameters`:** defaults unchanged; `LAMBDA_V` added.

**F. Simulator and time.**
- **Libraries:** DJUnits 5.2.1 → 5.4.1, DJUTILS 2.3.1 → 2.4.2, DSOL 4.2.6 → 4.3.2.
- **Time:** simulator time `Time` → `Duration`; `getSimulatorAbsTime` removed (`fc8f46cde`, `f80a5ada8`).
- **Other API:** `RemoteException`/`Serializable` removed, scheduling by lambda, `Logger.ots()`.
- **RNG:** the model-default streams are unchanged (`default` = MT(10), `generation` = MT(11)); MiRoVA
  seeds its own anyway. **Gap:** whether DSOL 4.3.2's `MersenneTwister` or distributions changed internally
  was not inspected.

**G. Generation.**
- **A probable upstream defect, also in `v1.8.1`:** `OdApplier.DemandNode.nextTimeSlice` (`f5d87dc5f`,
  the Optional refactor) inverted a condition. It never takes an earlier child time slice, and it can throw
  on an empty one. Arrivals are identical only when all OD pairs share one time vector. **This is relevant
  to the Freiburg demand CSVs** (not verified whether they share one vector).
- **No-lane-change distance:** `OdOptions.NO_LC_DIST` now defaults to 50 m (it was null), and XML
  generators now use 50 m (`f4cb47127`, `10a0bdcb6`).
- **Bookkeeping:** generators default to `START`.
- **Room checkers and placement:**
  - the `CfRoomChecker` fallback headway uses desired speed;
  - `GeneratorPositions` lane choice uses the new desired-speed peek, so the change from A flows in.
- **The number of draws per vehicle is unchanged** for a flat template set. The resulting stream can still
  differ.

**H. XML.**
- **Parser:** split into phases (`97d04293e`, `4386725af`). It still calls `JAXBContext.newInstance` on
  every parse.
- **xsd:** `ots-model.xsd` +1 178 lines (`192200f29`: the model specification now mirrors `LmrsFactory`,
  and `IdmPlus`/`Synchronization`/… elements are removed). `LinkType` speed limits became
  `SpeedLimit`/`TemporalSpeedLimit`/`GtuTypeSpeedLimit` with `Enforced`; `Default` → `Builtin`.
- **Consequence:** `FreiburgNord.xml`/`MergeBodegraven.xml` use `<ots:SpeedLimit GtuType=…
  LegalSpeedLimit=…>` (10 elements) and will not validate unchanged.

### 3.3 Gaps: not measured

- No simulation was run on `v1.8.1`, so every "changes the model" verdict above comes from reading the
  code, not from a comparison run.
- The size of each effect on the Freiburg results is unknown.
- Also not checked: DSOL internals of the RNG; whether the two upstream defects (desired speed with a
  GTU-type limit, the `OdApplier` time slice) are already reported upstream; and whether the Freiburg demand
  uses per-OD time vectors.

---

## 4. The fork's OTS modifications, one by one, against upstream `main`

| # | Fork modification | Upstream today | Verdict |
|---|---|---|---|
| 1 | `ParameterSet.getParameter` without `Throw.when` | Unchanged: upstream still calls `Throw.when(result == null, …)` (`ParameterSet.java:176`) | **Still needed** for performance. Re-applies trivially. Worth offering upstream as a PR. |
| 2 | `ParameterType` cached `hashCode` | Unchanged: upstream still recomputes the hash (`ParameterType.java:179`). Upstream `e9084d7e6` (#305, "Require default value") touched the same file. | **Still needed.** A small textual conflict in the constructor. Semantically compatible, because the hash fields stay final. Upstream candidate. |
| 3 | `LaneBasedGtuTemplate.getStrategicalPlannerFactory()` | The getter does not exist upstream (the field is still private). The file moved from `gtu/lane/...` only in its imports; the class stays in `road.gtu.generator.characteristics`. | **Still needed** while `ScenarioGenerator` uses it. It could be avoided by keeping the factory reference in `ScenarioGenerator` itself. |
| 4 | `XmlParser` JAXB singleton and `warmUpJAXBContext()` | Upstream still calls `JAXBContext.newInstance(Ots.class)` per parse (`XmlParser.java:288`). The parser was also restructured into phases (`97d04293e`, `4386725af`), so the surrounding code differs. | **Still needed** for parallel runs. **Conflicts** textually with the phase restructure, so it must be re-implemented rather than re-applied. Upstream candidate. |
| 5 | `ots-xml` → `ots-road` dependency | Upstream does not declare it. | Probably unnecessary. Check whether it was only a Maven or Eclipse resolution workaround. |
| 6 | `AnimationGtuData` MiRoVA getters | The module was renamed `ots-animation` → **`ots-animation-data`** (`5fac1a42e`, #230), and animation decoration was restructured (`2cfd8aa8e`). | **Conflicts**, and it is **wrong in any design**: an OTS module must not import MiRoVA. Move it into a MiRoVA GTU data or colourer class that is registered from the scenario. |
| 7 | `ots-demo` JAXB dependencies | Needed only because `ots-demo` runs XML parsing on its own classpath. | Re-check after an update. Unnecessary if the MiRoVA scenario code leaves `ots-demo`. |
| 8–11 | Demo tweaks (`CircularRoadModel`/`Swing`, `InputParameterHelper`, `shortMerge.xml`) | These files were reworked upstream: FD demo fixes, icons, `ScenarioTacticalPlanner`, speed limits. | **Drop.** They are experiments and include a hard-coded local path. Anything worth keeping belongs in `demo/mirova`. |
| 12 | `AbstractSimulationScript` default 600 s | Still 3600 s upstream. | **Drop.** Pass `-t` explicitly. `AbstractSimulationScriptBase` in MiRoVA does not need the default. |
| 13, 15, 17 | Import, `.classpath`, README | — | Drop. |
| 14 | `TJunctionModel` XML parsing removed | Upstream `ots-web` still parses. | Drop. It was a build workaround. |
| 16 | `.gitattributes` LF for `*.sh` | Upstream `44be04d6c` (#188) documents a newline policy. | Keep, since it is ours. Harmless. |
| — | `LaneBasedGtu.CACHING = false`, set by `ScenarioGenerator` | **The field no longer exists.** The overhaul `da3fc0687` (#59) replaced the `MultiKeyMap` position cache with a single-slot, per-time cache (`cachedPositionTime`/`cachedPosition`). Upstream `c660de221` also fixed `enterLane()` not clearing it. | **Obsoleted**, and the switch will not compile. The performance reason that led to `CACHING=false` (`performance_investigation_synthesis.md`) is very likely gone, but that is not yet measured. |
| — | The fork's `TacticalPlannerProvider` (tama branch) | Upstream has `LmrsFactory.TacticalPlannerProvider<P>` (`2cf8e0a40`, #246/#327), which is unrelated. | No conflict (a different package), but a naming clash worth avoiding. |

**Result:** of the 17 substantive edits, **4 are worth keeping** (#1–#4) and all four are upstreamable. One
must be moved out of OTS (#6). The rest are drop-able noise or local hacks. The fork's *OTS* delta is small.
The cost of an update lies almost entirely in **porting MiRoVA's 86 + 59 Java files to the changed upstream
APIs** (§3), and in **re-validating the model** (§5).

---

## 5. What an update means for the published results

The published results were produced on this fork: base `81241d09f` plus MiRoVA. The parameterisation is
pinned by the tag `published-model` (`9eb7ab856`, 2026-09-10) and reproduced by the `legacy` study variant.
MiRoVA inherits the following from OTS:

- IDM+ through `MirovaCarFollowingUtil`;
- desired speed through `SpeedLimitInfo`/`FSPEED`;
- perception (`HeadwayGtu`, neighbour and infrastructure categories, their per-tick memoisation);
- the operational plan and lane-change path (`LaneOperationalPlanBuilder`, `LaneChange`, `SimpleOperationalPlan`);
- lane bookkeeping and GTU positions;
- vehicle generation and RNG consumption (`LaneBasedGtuGenerator`, `GeneratorPositions`, OD applier,
  `MersenneTwister` streams `generation`/`default`);
- XML network parsing, including speed limits.

§3 shows upstream changes in every one of these. **An update is therefore a behaviour change of the whole
model, even if not a single line of MiRoVA changes.** It must be labelled `[behaviour change]`, and it cannot
sit behind a MiRoVA switch, because the old OTS code is simply gone.

### What would have to be re-run

1. **The regression gates first.** `FsmTraceRegressionTest` (the two `.trace.csv.gz` files), the golden and
   car-following fixtures generated by `GoldenFixtureGenerator`/`CarFollowingFixtureGenerator` (the TaMA
   golden JSONs carry `sourceCommit` `e93306f95`), and the `Phase05ReferenceStudy` recordings. After an
   update these will not be byte-identical. That is expected, and it is the measurement of how far the
   model moved. They have to be regenerated and re-committed as a new reference, and TaMA's golden and
   replay fixtures (`freiburg-nord.jsonl`, 39.8 MB) with them.
2. **The calibration chain in `docs/decoupling/recalibration-inventory.md` §1.1**, steps 0–16. In
   practice that means the production confirmation (`FreiburgSettledStudy`/`FreiburgValidationStudy`/
   `FreiburgProductionStudy`), the OAT screen (`FreiburgSensitivityStudy`, 10 cells × 36 runs), the
   congested-branch grid (`FreiburgCongestedBranchStudy`), the final ensemble (`FreiburgFinalStudy`, 50
   seeds at `T` = 1.00/1.30), and out-of-sample validation on the seven extension dates. The inventory
   already argues that `T` × damping and `fGap` × `s0` are jointly fitted and move together.
3. **The pending `vGain` recalibration** (`VGainGridStudy`, `VGainScreeningStudy`,
   `VGainRelaxationSensitivityStudy`, `vgaintau`: 1 920 runs at 16 dates × 30 replications). **Do not do
   this and the OTS update at the same time.** If both change at once, neither effect can be attributed.
4. `parameter_sensitivity.md` and the `nightly_settled` and `rng_and_arrivals` result sets (currently
   untracked in the main checkout) describe the old substrate and would have to be marked as such.

### What could not be reproduced afterwards, and how it stays reproducible

The published numbers cannot be reproduced on an updated fork. There is no switch for "old IDM+ path",
"old perception memoisation" or "old speed limits", and back-porting them would be a fork of OTS in its own
right. They stay reproducible only by keeping the old substrate frozen:

- The tag **`published-model`** (`9eb7ab856`), which contains the base `81241d09f`, must be **pushed to
  `origin`** and never moved. Add an annotated tag on the exact commit the paper runs used, if that differs.
- The tagged fork build is an artefact of its own. ADR-001 notes that TaMA resolves OTS from `mavenLocal()`
  only, so a local jar is not a durable record. Options: a build of the tag published to GitHub Packages,
  or at least `mvn install` output archived alongside `cluster/` output.
- Also record: JDK 17, DJUnits 5.2.1, DSOL 4.2.6, the demand CSVs (the database is reachable only from
  Marvin's machine), and the `diss_mvb` evaluation commit.
- ADR-015 already states that TaMA reproduces the driving behaviour "but not artefacts of the host". An OTS
  update changes those host artefacts, so TaMA-on-new-OTS will diverge from the published numbers in
  exactly the ways §3 lists.

---

## 6. Options

Effort is in person-days for one person who knows the code. It excludes cluster wall-clock time.

### (a) Stay on the current base, cherry-pick only needed upstream fixes

- **What:** keep `81241d09f`. Cherry-pick individual upstream fixes only when a concrete symptom is traced
  to one. Candidates worth reading, not applying blindly, because each is a behaviour change:
  - `c660de221`: `enterLane()` cache;
  - `6d7ede96e`, `a5a51d7e4`, `e6f528b61`: `fSpeed` becoming 0 or negative in behavioural adaptation;
  - `c89089d33`: infinite loop in `DefaultLaneBasedGtuCharacteristicsGeneratorOd`;
  - `384db66dd` and `218c4bbec`: detector trigger distance, relevant to `LoopDetector` data;
  - `282b845f4`: path truncation.

  Most of them sit on top of the #59 overhaul and the package rename, so they will not apply cleanly and
  would need hand-porting.
- **Also do:** the cheap hygiene. Drop the demo hacks #8–#15, move #6 out of `ots-animation`, and offer
  #1, #2 and #4 upstream.
- **Effort:** 1–2 days for the hygiene, then about 0.5–2 days per fix.
- **Risk:** low for the results. The cost grows over time, because the fork falls further behind: 404
  commits today, and 1.8.x is not API-compatible. Upstream bug fixes and the new LMRS will not reach
  MiRoVA.

### (b) Rebase or port the fork onto a recent upstream release (1.8.1, or `main`)

**What it involves:**

- A rebase of 381 commits is not realistic: every commit touches renamed packages. The realistic form is a
  **port**: a new branch from `v1.8.1`, re-apply #1–#4 by hand, move the MiRoVA tree and port it.
- **The package rename** `road.gtu.lane` → `road.gtu` (`9a962e967`) and `road.network.lane` →
  `road.network`. 115 of the 145 MiRoVA main files import from those packages: 633 + 93 import lines.
- **Removed or replaced API:**
  - `HeadwayGtu` → `PerceivedGtu`: 201 uses in 19 files;
  - `LaneChange`: 81 uses in 27 files; `SimpleOperationalPlan`: 82 uses in 12 files; `LanePathInfo`: 3 uses;
  - simulator `Time` → `Duration`: 29 imports; `getSimulatorAbsTime` removed, 12 uses;
  - `SpeedLimitInfo` → the redesigned speed limits: 37 uses in 7 files;
  - `RemoteException` removed; `Try.*` cleanup: 37 uses;
  - `ParameterTypes.PERCEPTION` removed (1 use); `LOOKAHEAD`'s id changed to `x0`; `FSPEED` split into
    `FSPEED` and `FSPEED_GTU`;
  - `instantiateSI` → `ofSI`: 310 uses (whether 5.4.1 still accepts `instantiateSI` was not checked, so this is a gap);
  - `LmrsFactory`, used 13 times in 5 files, was rewritten;
  - `buildAccelerationLaneChangePlan` is removed, and lane bookkeeping now defaults to `START`, which
    affects what MiRoVA's cut-in trigger sees;
  - `LaneBasedGtu.CACHING` gone.
- **The XML networks must be rewritten:** `<ots:SpeedLimit GtuType=… LegalSpeedLimit=…>` in `LinkType`
  became `GtuTypeSpeedLimit` / `SpeedLimit` / `TemporalSpeedLimit` (10 elements in the two files).
- **Then full re-validation** per §5.
- TaMA's `tama-ots` adapter would need the same port (§6c), and its Gradle pin `ots = "1.7.6"` would
  change.

**Effort:**

| Step | Person-days |
|---|---|
| Mechanical port to compile | 8–12 |
| Semantic port: lane change and operational plan, perception memoisation, speed limits, generation | 5–10 |
| Regenerating gates and fixtures, and explaining every difference | 5–10 |
| Recalibration and re-validation campaigns | 10–20, plus cluster time |
| **Total** | **≈30–50** |

**Risk: high.**

- The port happens at the same time as the `vGain` recalibration, so attribution gets hard.
- Keeping MiRoVA inside `org.opentrafficsim.*` means the next upstream refactor repeats the exercise.
- Upstream `main` is moving fast (package renames, the LMRS factory rewrite, speed limits within the last
  8 months).

### (c) Reduce the fork to as little as possible: TaMA as the tactical planner

**The state today**, from `tama-stage11` at `5d065f4`, `docs/adr`, `docs/checkpoints/stage11.md` and fork
branch `agent/tama-planner-selection` = `c56066531`:

- **TaMA code, all Kotlin:**

  | Module | Main | Test |
  |---|---|---|
  | `tama-api` | 7 files / 2 283 lines, JDK 8 | — |
  | `tama-core` | 39 / 9 550, JDK 8 | 25 / 11 869 |
  | `tama-ots` | 10 / 3 286, JDK 17 | 6 / 1 828 |
  | `tama-testkit` | 5 / 1 195 | — |

- **How it plugs in:** `ServiceLoader.load(TacticalPlannerProvider.class)` in
  `ScenarioGenerator.selectTacticalPlannerFactory`, selected by the scenario parameter `tacticalPlanner`
  (default `mirova`), through the existing `setTacticalPlannerFactoryHook`. TaMA registers
  `edu.kit.ifv.tama.ots.TamaPlannerProvider`.
- **How it gets OTS:** Gradle pins `org.opentrafficsim:ots-road/ots-core/ots-demo:1.7.6` from `mavenLocal()`
  only, meaning the fork's installed jars. The record and live tools put a fork snapshot's
  `target/classes` first.
- **What it needs from the fork's OTS edits (#1–#17): none.** A grep for `getStrategicalPlannerFactory`,
  `warmUpJAXBContext`, `AnimationGtuData` and `JAXBContext` in `tama-ots` found nothing.
- **What it does need from MiRoVA:**
  - *Main code:* `OtsParameters.kt` and `OtsWorldView.kt` read `MirovaParameters.*` (about 30 parameter
    types). `RecordingObserver.kt` imports `MirovaTacticalPlanner`, `MirovaTacticalPlannerObserver`, the
    Belief contexts, `ActionState`, three patterns and `MirovaCarFollowingUtil`.
    `TamaPlannerProvider.kt` implements the fork's `TacticalPlannerProvider`.
  - *Tests:* `ScenarioGenerator`, `ScenarioManager`, `ScenarioParameters`, `FreiburgNord`,
    `Phase05ReferenceStudy`, `MirovaTacticalPlanner(Factory)`.
  - *Imports by package:* 13 from `mirova.core`, 4 from `tactical.mirova`, 7 from `demo.mirova.scenariomanagement`,
    3 from `…scenarios`. The rest are OTS packages that §3 shows were renamed (`road.gtu.lane.*`: 38
    imports; `road.network.lane`: 5).

**What (c) would require:**

1. **Cut the adapter's main-code dependency on MiRoVA.**
   - Move the parameter types TaMA reads into `tama-ots` (or `tama-api`) as TaMA's own `ParameterType`s,
     with the same ids and defaults, so XML and study overrides keep working.
   - Move `RecordingObserver`'s MiRoVA-specific half into a test-only or "equivalence" module.

   ADR-001 already says the Java MiRoVA stays "for as long as the equivalence tests need something to
   compare against". This makes that literal: the equivalence harness depends on the pinned fork, and the
   production adapter does not.
2. **A host module outside the OTS tree.** Move `demo/mirova/scenariomanagement` (13 files),
   `scenarios` (38), `libraries` (3), the XML resources, `cluster/` and the study registry into a separate
   repository or module (e.g. `mirova-studies` or `tama-host`). It would depend on **released**
   `org.opentrafficsim:ots-*` artefacts from Maven Central. That module owns `TacticalPlannerProvider`,
   the JAXB warm-up (#4 re-implemented as a wrapper, or dropped if upstream takes the PR) and the
   parameter-set performance fix (#1, #2: upstream PR, or accept the cost).
3. **Port the adapter and host module to 1.8.x APIs.** That means `tama-ots`'s 10 files plus the host
   module, instead of 145 MiRoVA files. `TacticalContext`/`TacticalContextEgo` and `PerceivedGtu` upstream
   are a better fit for a WorldView adapter than the 1.7.6 types.
4. **Re-validate TaMA on the new OTS** against the frozen published numbers (§5). Here that is the *only*
   re-validation, because the Java MiRoVA is no longer ported.

**What (c) leaves behind:**

- **The whole Java MiRoVA** (`road.gtu.lane.tactical.mirova`, 86 files / 20 162 lines), the fixture
  generators, `fsmtrace` and `FsmTraceRegressionTest`. All of it stays on the pinned fork, frozen at
  `published-model`, as the reference implementation.
- **The published model is then reproducible only on the pinned fork**, plus a durable build of it (see §5).
  TaMA on 1.8.x reproduces MiRoVA's *driving behaviour* (ADR-014), not its host artefacts (ADR-015). The
  published numbers are therefore reproduced by running the tag, not by running TaMA.
- **Equivalence tests** (golden and replay) keep running against the fork, which means a second OTS
  version on the build machine (1.7.6-snapshot for equivalence, 1.8.x for production).

**Effort:**

| Step | Person-days |
|---|---|
| Decouple `tama-ots` main from MiRoVA | 3–5 |
| Extract the host and study module and switch it to released OTS | 5–8 |
| Port the adapter and host to 1.8.x | 5–8 |
| Split the build: equivalence against the fork, production against 1.8.x | 2–3 |
| Re-validation, same campaign as in (b) but only once | 10–20 |
| **Total** | **≈25–45** |

Much of the re-validation overlaps with work TaMA needs anyway.

**Risk: medium.**

- The equivalence claim is made on the old substrate and the calibration on the new one, and the gap
  between them must be measured, not assumed.
- Two OTS versions have to coexist in the build.
- In return, the next upstream change touches about 15 adapter files instead of 145, and no MiRoVA or TaMA
  code lives in `org.opentrafficsim.*` packages any more.

### Recommendation

**I would take (c), sequenced behind (a):**

1. **Now:** (a)'s hygiene.
   - Push `published-model` and archive its build.
   - Drop the demo hacks; move `AnimationGtuData`'s MiRoVA getters out of `ots-animation`.
   - Open upstream PRs for #1, #2 and #4.
   - Take no upstream behaviour into the fork while the `vGain` recalibration runs, so that recalibration
     stays attributable to `vGain` alone.
2. **Then (c):** decouple `tama-ots` from MiRoVA main code, extract the host and study module, and only
   then target `v1.8.1` (or the next 1.8.x). Do it from the TaMA side, not by porting MiRoVA Java.
3. **Not (b).** Porting 145 Java files that are about to be superseded by TaMA spends most of the effort
   on code with no future. It also leaves MiRoVA inside OTS's packages, so the next upstream refactor
   costs the same again.

The decision is Marvin's. The one input that changes this ranking is whether the Java MiRoVA itself must
keep evolving after the recalibration. If it must, (b) becomes necessary at some point, and it is cheapest
done right after a recalibration is closed and tagged, not during one.

---

## Addendum (2026-09-16, after the provenance work in `fork-merge-plan.md`)

- **The published model is pinned by commit, not by the tag `published-model`.** That tag (`9eb7ab856`, never pushed)
  reproduces neither campaign. `final_v1`, the reference standard, ran at `fbce85dbe` (one cell re-run there is
  byte-identical to the archive). Wherever this plan says "push the tag `published-model`", read: create and push
  `campaign-final-v1` on `fbce85dbe` (proposed in `fork-merge-plan.md` §D.5). An archived build of that commit is what
  keeps the published results reproducible after any upstream update.
- **IDE project files** (184 tracked in the fork, 170 still tracked upstream): if the fork stops tracking them
  (`fork-merge-plan.md` §I), every upstream file of that kind changed since the base becomes a modify/delete conflict
  in a rebase or merge onto upstream. Mechanical, but it adds to option (b).
- **Every run now records its build** (`agent/run-provenance`), so the re-runs an update requires (§5) will carry the
  commit they ran on.

