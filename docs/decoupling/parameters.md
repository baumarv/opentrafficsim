# MiRoVA parameter reference

Every parameter the model reads, where it is read from, and whether it is resolved once into the
snapshot or looked up live. Produced in Phase 0.5 as input for the `DriverParameters` design in
Phase 1, and to be reconciled against the paper's parameter table.

**State: after the Phase 0.5 commits.** Two parameters were deleted in that work
(`SOCIAL_INTERACTION_COOLDOWN`, `TTC_EMERGENCY_BRAKING`) and one was withdrawn
(`RELAXATION_TAU_SPEED`); they are listed in §5 with the reason.

**Read counts** are call sites in `ots-road/.../mirova`, excluding the snapshot's own assignment.
A count of 0 means the parameter is resolved and stored and then never consulted.

---

## 1. Snapshot or live — the rule

`MirovaParameterSnapshot` resolves a parameter once, when the vehicle is constructed. That is only
sound for a parameter nothing writes at runtime. After Phase 0.5 exactly **two** parameters are
written at runtime anywhere in the tree:

| Parameter | Written by | Why it must stay live |
|---|---|---|
| `ParameterTypes.T` | `MirovaCarFollowingUtil.followWithReducedHeadway` and `followDistanceAndSpeedWithReducedHeadway`, set and reset around one car-following call; also the OTS LMRS `Tailgating` class when a vehicle runs under an LMRS planner | A snapshot would freeze the desired headway at its construction value and the yield behaviour would silently stop working |
| `ParameterTypes.LOOKAHEAD` | `InfrastructureContext.distanceToLaneChangeExtendedLookahead`, raised to the extended value and reset in a `finally` | Same |

Everything else is constant for a vehicle's life and belongs in the snapshot. As of Phase 0.5 every
constant parameter is read from it; see the commit *"read constant parameters from the snapshot
everywhere"*.

---

## 2. MiRoVA parameters (`MirovaParameters`)

| ID | Java name | Type | Default | Unit | Read where | Access | Set by scenarios |
|---|---|---|---|---|---|---|---|
| `DFREE` | `DFREE` | Double | 0.365 | – | `MirovaTacticalPlanner.getDFree`, `SimpleLaneChangePattern`, `KeepRightIncentive`, `HybridPlanArbitrator`, `GapOpenerPattern` | snapshot `dFree` | no |
| `DMAND` | `DMAND` | Double | 0.577 | – | `MirovaTacticalPlanner.getDMand`, `MandatoryLaneChangePattern` (3), `GapOpenerPattern`, `EgoContext` (2 thresholds) | snapshot `dMand` | no |
| `DSEARCH` | `DSEARCH` | Double | 0.788 | – | **nowhere** | snapshot `dSearch` | no |
| `EMERGENCY_STOPPING_DISTANCE` | `emergencyStoppingDistance` | Length | 5.0 | m | `DeadlockDiffusionWatchdog` | live (host-side safeguard) | no |
| `VEHICLE_DIFFUSION_TIME` | `vehicleDiffusionTime` | Duration | 60.0 | s | `DeadlockDiffusionWatchdog` | live (host-side safeguard) | no |
| `MANDATORY_LANE_CHANGE_LOOK_AHEAD_DISTANCE` | `mandatoryLaneChangeLookAheadDistance` | Length | 500.0 | m | **nowhere** | snapshot | no |
| `EXTENDED_LOOK_AHEAD_DISTANCE` | `extendedLookAheadDistance` | Length | 1000.0 | m | `InfrastructureContext`, `MandatoryLaneChangePattern` (2) | snapshot | no |
| `CONGESTED_LANE_CHANGE_DURATION` | `congestedLaneChangeDuration` | Duration | 1.5 | s | **only in commented-out code** (`MandatoryLaneChangePattern:2506`) | snapshot | no |
| `bCritMirova` | `B_CRIT` | Acceleration | −3.5 | m/s² | `MirovaIdmPlus.combineInteractionTerm` | snapshot | no |
| `bMaxMirova` | `B_MAX` | Acceleration | −6.0 | m/s² | `MirovaIdmPlus`, `MirovaCarFollowingUtil.requiredDeceleration` | snapshot | no |
| `VGAIN` | `vGain` | Speed | 69.6 km/h | m/s | `CruisingSpeedIncentive`, `SocialInteractionsIncentives` (2), via `getVGain()` | **live** (snapshot has SI only) | **yes, 11×** |
| `VCRIT` | `vCrit` | Speed | 60.0 km/h | m/s | **nowhere** (`getVCrit()` has no caller) | live | no |
| `SOCIO_SPEED_SENSITIVITY` | `socioSpeedSensitivity` | Double | 0.25 | – | `SocialInteractionsIncentives` | snapshot | yes, 4× |
| `SAFETY_DISTANCE_REDUCTION_FACTOR_LANE_CHANGE` | `safetyDistanceReductionFactorLaneChange` | Double | 0.5 | – | `EgoContext` (2), `NeighborsContext` (3), `GapOpenerPattern`, `MandatoryLaneChangePattern`, `PreventUndercuttingPattern` (2) | snapshot | yes, 10× |
| `MIN_FOLLOWER_DECELERATION_THRESHOLD` | `minFollowerDecelerationThreshold` | Acceleration | −2.0 | m/s² | `EgoContext.computeFollowerDecelerationThreshold` | snapshot | yes, 5× |
| `MAX_FOLLOWER_DECELERATION_THRESHOLD` | `maxFollowerDecelerationThreshold` | Acceleration | −4.0 | m/s² | same | snapshot | yes, 5× |
| `MIN_EGO_DECELERATION_THRESHOLD` | `minEgoDecelerationThreshold` | Acceleration | −2.0 | m/s² | `EgoContext.computeEgoDecelerationThreshold` | snapshot | yes, 3× |
| `MAX_EGO_DECELERATION_THRESHOLD` | `maxEgoDecelerationThreshold` | Acceleration | −4.0 | m/s² | same | snapshot | yes, 3× |
| `COOPERATIVE_DECELERATION_THRESHOLD` | `cooperativeDecelerationThreshold` | Acceleration | −3.0 | m/s² | `GapOpenerPattern`, `AnticipateDownstreamMergePattern` | snapshot | yes, 8× |
| `PREEMPTIVE_COOPERATIVE_DECELERATION` | `preemptiveCooperativeDeceleration` | Acceleration | −1.0 | m/s² | `GapOpenerPattern`, `AnticipateDownstreamMergePattern` (4) | snapshot | yes, 1× |
| `COOPERATIVE_LANE_CHANGES_ENABLED` | `cooperativeLaneChangesEnabled` | Boolean | true | – | `GapOpenerPattern`, `AnticipateDownstreamMergePattern` | snapshot | yes, 4× |
| `FAR_ANTICIPATION_ENABLED` | `farAnticipationEnabled` | Boolean | true | – | `AnticipateDownstreamMergePattern` (3) — **all in the disabled pattern** | snapshot | yes, 6× |
| `CONSIDER_GAP_OPENING_LOOKAHEAD_DISTANCE` | `considerGapOpeningLookaheadDistance` | Length | 100.0 | m | `GapOpenerPattern.findNewCandidate` | snapshot | no |
| `UNDERCUTTING_TIME_HEADWAY` | `undercuttingTTCThreshold` | Duration | 5.0 | s | `NeighborsContext.checkRightSideOvertakingAhead` | snapshot | no |
| `tau_relax_s` | `RELAXATION_TAU_SPACE` | Duration | **20.0** | s | `EgoContext` (2: trigger, lifetime cap) | snapshot | no |
| `aRelaxAbort` | `RELAXATION_ABORT_DECELERATION` | Acceleration | −1.0 | m/s² | `MirovaCarFollowingUtil` | snapshot | yes, 5× |
| `tRelaxFade` | `RELAXATION_FADE_DURATION` | Duration | 1.0 | s | `MirovaCarFollowingUtil` | snapshot | yes, 3× |
| `relaxMaxLifetime` | `RELAXATION_MAX_LIFETIME_FACTOR` | Double | 3.0 | ×τ_s | `EgoContext.updateFromPerception` | snapshot | yes, 3× |
| `aRelaxDamping` | `RELAXATION_ACC_DAMPING_FACTOR` | Double | 0.40 | – | `EgoContext.getRelaxationAccelerationFactor` | snapshot | yes, 10× |
| `aRelaxDampingEnabled` | `RELAXATION_ACC_DAMPING_ENABLED` | Boolean | true | – | same | snapshot | yes, 6× |
| `CF_MAX_LEADERS` | `CF_MAX_LEADERS` | Double | 2 | count | `LongitudinalControl` | snapshot | no |
| `aScale` | `ACCELERATION_SCALING_FACTOR` | Double | 1.0 | – | **nowhere** | snapshot | no |
| `aMaxMirova` | `A_MAX` | Acceleration | 3.5 | m/s² | `EgoContext.computeMaxPhysicalAcceleration*` (2) | snapshot `aMaxSi` | yes, 6× |
| `STANDSTILL_SPEED_THRESHOLD` | `standstill_speed_threshold` | Speed | 20.0 km/h | m/s | **nowhere** (its only reader, `CongestionIncentive`, was deleted) | snapshot | no |
| `capDropEnabled` | `CAPACITY_DROP_ENABLED` | Boolean | false | – | `CapacityDrop.isEnabled` | snapshot + live fallback | yes, 9× |
| `tDischargeAddon` | `T_DISCHARGE_ADDON` | Duration | 0.5 | s | `CapacityDrop.absoluteHeadwayTime` | snapshot + live fallback | no |
| `vCritDischarge` | `V_CRIT_DISCHARGE` | Speed | 40.0 km/h | m/s | `CapacityDrop.absoluteHeadwayTime` | snapshot + live fallback | no |
| `tDischargeFraction` | `T_DISCHARGE_FRACTION` | Double | 0.0 | – | `CapacityDrop.relativeDesiredHeadway` | snapshot + live fallback | yes, 1× |
| `vCritDischargeFraction` | `V_CRIT_DISCHARGE_FRACTION` | Double | 0.0 | – | `CapacityDrop` (2) | snapshot + live fallback | yes, 1× |

### Phase 0.5 behaviour switches

All Boolean, all default **false**, all reproducing the model every published result was produced
with. See `phase05-report.md` for what each one changes.

| ID | Java name | Read where |
|---|---|---|
| `bcEmaActualDt` | `EMA_ALPHA_FROM_ACTUAL_DT` | `MandatoryLaneChangePattern.AnticipateMergeState` |
| `bcLeaderOwnModel` | `LEADER_HEADWAY_FROM_OWN_MODEL` | `GapOpenerPattern.leaderCanCooperate` |
| `bcMeanSpeedFromLeaders` | `MEAN_SPEED_FROM_PERCEIVED_LEADERS` | `MacroTrafficContext.computeAverageSpeed` |
| `bcMergeRefRangeLimited` | `MERGE_REFERENCE_RANGE_LIMITED` | `MandatoryLaneChangePattern.getMergeReferenceSpeed` |
| `bcContextOrderFixed` | `CONTEXT_UPDATE_ORDER_FIXED` | `VehicleContextManager.orderedCategories` |

---

## 3. OTS parameters MiRoVA reads

Snapshotted unless noted.

| Key | Default | Read where | Snapshot field |
|---|---|---|---|
| `ParameterTypes.DT` | 0.2 s (overridden by the factory; OTS default 0.5 s) | plan validity in 9 places, EMA coefficient | `dtScalar`, `dtSi` |
| `ParameterTypes.T` | 1.2 s | `EgoContext` (2), `NeighborsContext`, `PreventUndercuttingPattern` (2), `MirovaIdmPlus`, `CapacityDrop`, `MirovaCarFollowingUtil` (2, read-and-write) | **live, deliberately** |
| `ParameterTypes.LOOKAHEAD` | 295 m | `InfrastructureContext` (3), `RouteIncentive`, `KeepRightIncentive`, `SocialInteractionsIncentives` (3), `GapOpenerPattern` (2), `LongitudinalControl` | **live, deliberately** |
| `ParameterTypes.S0` | 3.0 m | `EgoContext` (2), `NeighborsContext`, `MirovaCarFollowingUtil`, `MirovaIdmPlus`, `GapOpenerPattern` | `s0Scalar`, `s0Si` |
| `ParameterTypes.A` | 1.25 m/s² | `CruisingSpeedIncentive`, `MandatoryLaneChangePattern.rampAcceleration`, `MirovaIdmPlus` | `aSi` |
| `ParameterTypes.B` | 2.09 m/s² | (snapshot only) | `bSi` — **unread** |
| `ParameterTypes.BCRIT` | 3.5 m/s² | OTS internals | `bCritSi` — **unread** |
| `ParameterTypes.VCONG` | 60 km/h | `KeepRightIncentive`, `CruisingSpeedIncentive`, `MandatoryLaneChangePattern`, `GapOpenerPattern`, `AnticipateDownstreamMergePattern` | `vCongScalar`, `vCongSi` |
| `ParameterTypes.T0` | 43 s | `RouteIncentive` | `t0Si` |
| `ParameterTypes.LCDUR` | 3.0 s | planner construction, `MandatoryLaneChangePattern:2318` | live |
| `ParameterTypes.FSPEED` | 1.0 | OTS `AbstractIdm.DESIRED_SPEED`, W99, and scenario draws | live (OTS-side) |
| `ParameterTypes.LOOKBACK` / `LOOKBACKOLD` / `PERCEPTION` | 200 m / – / – | OTS perception internals | – |

### E.4 — which of the four wholesale OTS sets are actually needed

`MirovaTacticalPlannerFactory.getDefaultParameters` calls `setDefaultParameters` on four OTS classes.
Traced through everything MiRoVA can reach (its own code, the six perception categories
`DefaultMirovaPerceptionFactory` installs, `SpeedLimitUtil`, `CarFollowingUtil`, `AbstractIdm`,
`LaneChange`, `LaneOperationalPlanBuilder`):

| Set | Keys it installs | Verdict |
|---|---|---|
| `ConflictUtil` | `MIN_GAP`, `S0_CONF`, `TIME_FACTOR`, `STOP_AREA`, `TI` (+ `B`, `BCRIT`, `S0`) | **None of the conflict-specific keys is read.** MiRoVA registers no conflict handling. `B`/`BCRIT`/`S0` come from elsewhere as well. |
| `TrafficLightUtil` | `BCRIT` | **Redundant** — the factory sets `BCRIT` explicitly two lines later. |
| `LmrsUtil` | `DT`, `T`, `BCRIT`, `TMIN`, `TMAX`, `TAU` | `DT`, `T`, `BCRIT` are needed. **`TMIN`, `TMAX`, `TAU` are not read by anything MiRoVA reaches.** Their only readers are `perception/mental/AdaptationHeadway` and `TaskHeadwayBased`, and `DefaultMirovaPerceptionFactory` installs no mental module. |
| `LmrsParameters` | `DFREE`, `DSYNC`, `DCOOP`, `DLEFT`, `DRIGHT`, `DLC`, `VGAIN`, `SOCIO` | **None is read.** `DLEFT`/`DRIGHT` are read only by `perception/mental/TaskLaneChanging`, likewise not installed. MiRoVA has its own `DFREE`/`DMAND` and its own `vGain`. |

**Consequence worth acting on:** `MergeScenario` and `SimpleHighwayScenario` set `ParameterTypes.TMIN`
and `TMAX` on **MiRoVA** planner factories. Neither IDM+ nor Wiedemann 99 reads them, and the mental
module that would is not installed, so those writes have no effect. (The same calls on the *LMRS*
factories in `MergeScenario` are legitimate — LMRS `Tailgating` moves `T` between them.)

For contract v1 the core needs: `DT` (as the requested interval), `T`, `S0`, `A`, `B`, `BCRIT`, `T0`,
`FSPEED`, `LCDUR`, `VCONG` — plus the MiRoVA set. `LOOKAHEAD`/`LOOKBACK` become *query range
arguments*, not driver parameters.

---

## 4. Distributions (E.7 classification)

`DesiredSpeedLibrary` holds 28 named desired-speed distributions plus `trucks`;
`HeadwayDistributionLibrary` holds one arrival-headway distribution.

The rule the review gave: *whatever the same person would bring to a different road is a driver
parameter; a facility-specific calibrated variant is scenario configuration that parametrises it.*

| Group | Examples | Classification | Rationale |
|---|---|---|---|
| Distribution **shape** for the desired-speed factor | the lognormal/uniform families the methods build | **core** | The spread of `FSPEED` across a population is driver heterogeneity, carried to any road |
| Literature defaults | `hoogendoornCars`, `hoogendoornTrucks` | **core** | Published population distributions, not tuned to Freiburg |
| Nominal speed-limit variants | `cars100kmh`, `cars120kmh`, `cars130kmh`, `carsUnrestricted`, `trucks` | **core**, parametrised by the limit | The limit comes from the host as infrastructure; the factor distribution is the driver's |
| Limit × density calibrated variants | `carsLimit120_DensityHigh`, `trucksLimit80_DensityClass2_Modified`, and the 20 siblings | **host** | Calibrated to a facility and a traffic state; they select and parametrise a core distribution |
| Arrival headway | `shiftedExponential` | **host** | Demand generation, not driver behaviour — it never enters the tactical model |

**Cannot classify without you:** the `_Modified` truck variants. Whether the modification is a
correction to the population (core) or a facility fit (host) is not derivable from the code.

---

## 5. Parameters that are declared but never read

Each of these is resolved for every vehicle and then never consulted. None was deleted here, because
several appear in study grids and removing them would break a scenario definition — that is your call.

| Parameter | Since | Note |
|---|---|---|
| `DSEARCH` (0.788) | — | No reader anywhere. Presumably intended as the third LMRS threshold. |
| `aScale` / `ACCELERATION_SCALING_FACTOR` (1.0) | — | The acceleration curve scales on `aMaxMirova` instead. |
| `STANDSTILL_SPEED_THRESHOLD` (20 km/h) | Phase 0.5 | Its only reader was `CongestionIncentive`, deleted as unregistered. |
| `MANDATORY_LANE_CHANGE_LOOK_AHEAD_DISTANCE` (500 m) | — | Superseded by `EXTENDED_LOOK_AHEAD_DISTANCE`. |
| `CONGESTED_LANE_CHANGE_DURATION` (1.5 s) | — | Read only from commented-out code in `ExecuteLaneChangeState`. The intent — a shorter lane change in a queue — is worth reviving as part of the lane-change command in contract v1. |
| `VCRIT` (60 km/h) | — | `getVCrit()` has no caller. |
| snapshot `bCritSi` | — | Snapshot field for `ParameterTypes.BCRIT` that nothing reads; the OTS models read the parameter themselves. **Correction:** an earlier revision of this table also listed `bSi` here. It is wrong — `MandatoryLaneChangePattern` reads it to build the comfortable deceleration for the ramp-end stop. `bSi` stays. |
| `FAR_ANTICIPATION_ENABLED` | — | Read only inside `AnticipateDownstreamMergePattern`, which is not registered. Set by six studies, where it therefore does nothing. |

### Deleted in Phase 0.5

| Parameter | Why |
|---|---|
| `SOCIAL_INTERACTION_COOLDOWN` (6 s) | Declared and snapshotted, never read. Its counter `timeSinceLastLaneChange` had no reader either and advanced by the `DT` parameter rather than the actual step. |
| `TTC_EMERGENCY_BRAKING` (2 s) | Existed only to feed `MirovaCarFollowingUtil.getKinematicEmergencyBrake`, whose last call site was a comment. |
| `RELAXATION_TAU_SPEED` (8 s) | The speed buffer it governed was never fed; see the relaxation commit. |
