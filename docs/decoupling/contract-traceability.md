# Contract traceability

Every live row of inventory §A against the contract element that takes it over. Companion to
[`contract.md`](contract.md) and the drafts in [`contract/`](contract/).

**Which behaviour.** The contract elements below implement the core's reference model, not the
Phase 0.5 reference run: BC-1, BC-2, BC-4, BC-6 and BC-8 are in by construction, BC-5 is undecided.
See [`contract.md`](contract.md) §0.

**Reading it.** *Core* = the element in the Kotlin drafts. *Adapter* = `mirova-ots` only; the core
never sees it. *Host* = neither, it stays a simulator concern. *Gone* = deleted in Phase 0.5 or
deliberately dropped, with the reason.

Rows deleted in Phase 0.5 are listed in §9 rather than silently omitted, so the table can be checked
against the inventory row by row.

---

## A.1 Entry points and lifecycle

| Inventory row | Disposition | Contract element |
|---|---|---|
| `MirovaTacticalPlanner:51` — `extends AbstractLaneBasedTacticalPlanner` | split | `DriverAgent` (core) + an OTS adapter keeping the supertype |
| `:181` — `generateOperationalPlan(Time, DirectedPoint2d)` | core | `DriverAgent.step(now, world)`; the `DirectedPoint2d` was never read |
| `:186`, `:207` — `OperationalPlan.standStill` | adapter | — |
| `:193` — `Gtu.getFront/getReferencePosition/isDestroyed` gate | host | the host decides whether to step the agent at all |
| `:197` — `getCarFollowingAcceleration()` on the skip path | core | `CarFollowingModel.freeAcceleration` |
| `:198` — `SimpleOperationalPlan` | core | `TacticalCommand` |
| `:210` — `LaneOperationalPlanBuilder.buildPlanFromSimplePlan` | adapter | — |
| `:67/:154/:520` — OTS `LaneChange` | split | core emits `LaneChangeRequest`; host reports through `EgoState.laneChange` and `onLaneChangeCompleted`/`Aborted` |
| `:283-305` — `TurnIndicatorStatus` | core | `TacticalCommand.indicator` |
| `:159/:311` — `getSimulatorTime()` | core | the `now` argument of `step` |
| `:156/:565-601` — `Parameters`, `getParameter` | core | `DriverParameters`, `DriverParameterKeys` |
| `:656` — `ParameterTypes.DT` as the tick increment | core | the measured `dt`; see contract §6 |
| DJUnits throughout | core | kotlin-units |
| `MirovaTacticalPlannerFactory:48` | split | `DriverAgentFactory` (core); the OTS factory delegates |
| `:105-108` — four wholesale OTS parameter sets | gone | traced in `parameters.md` §E.4: `ConflictUtil` and `LmrsParameters` contribute nothing MiRoVA reads; `TrafficLightUtil` is redundant; of `LmrsUtil` only `DT`, `T`, `BCRIT` are read |
| `:110-125` — the `ParameterTypes` block | core | `DriverParameterKeys`; `LOOKAHEAD`/`LOOKBACK` become query range arguments |
| `:130` — `setParameter(DT, 0.2 s)` | core | `TacticalCommand.nextEvaluationAfter` |
| `:73` — `LaneBasedGtu.setParameters` | adapter | — |
| `DefaultMirovaPerceptionFactory` (whole file) | adapter | becomes the OTS `WorldView` implementation |

---

## A.2 Belief layer

| Inventory row | Disposition | Contract element |
|---|---|---|
| `EgoContext:701` — `EgoPerception.getSpeed()` | core | `WorldView.ego.speed` |
| `EgoContext:522` — `getDesiredSpeed()` | core | computed: `speedLimits(...).first().limit × fSpeed`, capped by `ego.maxSpeed`. Settles §E.2 in favour of core-computed. |
| `EgoContext:578` — `getLength()` | core | `EgoState.length` |
| `EgoContext:262/:327/:408` — `desiredHeadway` | core | `CarFollowingModel.desiredGap` |
| `EgoContext:720/:758` — `ParameterTypes.T` live | core | `DriverParameterKeys.DESIRED_HEADWAY`, snapshotted; the live read existed only because the CF util wrote it |
| `EgoContext:150…:914` — `getSimulatorTime()` | core | `step(now, …)` |
| `EgoContext:249/:316/:390` — `HeadwayGtu` in signatures | core | `PerceivedVehicle` |
| `EgoContext:533` — `LateralDirectionality` | core | `Side` |
| `EgoContext:998-1016` — the piece-wise max-acceleration curve | host | `EgoState.maxAccelerationAt(speed)` — a vehicle property |
| `NeighborsContext:674/:720` — `getLeaders/getFollowers` | core | `WorldView.leaders/followers(lane, range)` |
| `NeighborsContext:763` — `isGtuAlongside` | gone | deleted in Phase 0.5 (only a commented-out caller); the live need is `PerceivedVehicle.isAlongside` |
| `NeighborsContext:306` — `ParameterTypes.T` | core | as above |
| `NeighborsContext:1296-1303` — `LanePosition`, link id | adapter | diagnostics only |
| `NeighborsContext:25-31` — CF imports | gone | deleted |
| `NeighborsContext` BC-4 estimator (added in Phase 0.5) | core | Belief layer; feeds the social pressure term in place of `HeadwayGtu.getDesiredSpeed()` |
| `InfrastructureContext:602/:604` — `getLegalLaneChangeInfo` | core | `WorldView.routeRequirement(lane, range)` |
| `InfrastructureContext:709` — `getLegalLaneChangePossibility` | core | `WorldView.laneChangePossibility(side, range)` |
| `InfrastructureContext:687` — `getSpeedLimitProspect(...).getSpeedLimitInfo(d)` | core | `WorldView.speedLimits(lane, range)`, reduced per A.5 |
| `InfrastructureContext:272` — `SpeedLimitUtil.getLegalSpeedLimit` | core | `SpeedLimitAhead.limit` |
| `InfrastructureContext:633-639/:718-724` — `LaneStructure`, `LaneRecord` | core | `laneExists` + `distanceToLaneEnd` |
| `InfrastructureContext:643/:930/:1062/:1198/:1212` — `nextLanes`, `accessibleAdjacentLanesLegal`, `getAdjacentLane` | core | **not exposed as topology**: folded into `laneIsUsable`, `parallelMergeAhead`, `expectedMergeSpeed` |
| `InfrastructureContext:725/:935/:1066` — `instanceof Shoulder` | core | `laneIsUsable(side)` |
| `InfrastructureContext:902/:1037` — `buildLanePathInfo` | adapter | feeds `expectedMergeSpeed` |
| `InfrastructureContext:813` — `Lane.getGtuList()` | core | `expectedMergeSpeed(side, range)`, range-bounded by BC-6 |
| `InfrastructureContext:759/:761` — `setParameterResettable(LOOKAHEAD)` | gone | the mechanism was deleted in Phase 0.5 after it was measured to be a no-op; the range is now a query argument |
| `InfrastructureContext:445/:654/:1215` — `LOOKAHEAD` read | core | query range argument |
| `InfrastructureContext:1137/:1239` — `LaneDropInfo`/`DownstreamLaneInfo` hold a `Lane` | gone | no host lane reference in the core; the queries return values, not handles |
| `MacroTrafficContext:269` — `TrafficPerception.getSpeed(lane)` | core | `WorldView.meanSpeed(lane, range)` — **provisional**, see contract Q1 |
| `MacroTrafficContext:288` — `getDensity(lane)` | gone | deleted in Phase 0.5; no caller, and kotlin-units has no linear density |
| `RelaxationState` (DJUnits only) | core | Belief layer, one-parameter model |
| `ContextCategory:3-4` — `RelativeLane`, `LateralDirectionality` | core | `RelativeLane`, `Side` |
| `VehicleContextManager`, `UpdatableContext`, `ContextValue`, `RelaxationDiagnostics` | core | move as-is; the update order is fixed by BC-8 |

---

## A.3 Desire layer

| Inventory row | Disposition | Contract element |
|---|---|---|
| `DesireIncentive:73-77` — direct perception access | core | every read goes through the Belief layer |
| `DesireIncentive:79` — `gtu.getParameters()` | core | `DriverParameters` |
| `DesireIncentive:195-198` — `getLegalLaneChangeInfo` | core | `routeRequirement` — mandatory for every host, no `Unknown` |
| `RouteIncentive:103` — `laneStructure.exists` | core | `laneExists` |
| `RouteIncentive:105` — speed limit at 0 m per lane | core | `speedLimits(lane, range).first()` |
| `RouteIncentive:106` — `getCarFollowingModel().desiredSpeed` | core | computed desired speed (see A.2, `EgoContext:522`) |
| `RouteIncentive:91-92` — `LOOKAHEAD`, `T0` | core | range argument; `ROUTE_TIME_HORIZON` |
| `RouteIncentive:137-166` | core | `routeRequirement`, `laneChangePossibility` |
| `KeepRightIncentive:53-55` — `getCrossSection().contains(RIGHT)` | core | `laneExists(RIGHT)` |
| `KeepRightIncentive:87-89` — `VCONG`, `LOOKAHEAD` | core | `CONGESTED_SPEED`; range argument |
| `SocialInteractionsIncentives:151` — `follower.getDesiredSpeed()` | core | **BC-4** estimator; the field is not in `PerceivedVehicle` |
| `SocialInteractionsIncentives:153` — the follower's `LOOKAHEAD` | gone | reading another driver's parameters; the ego's own range is used, as `egoSocialPressure` already did |
| `SocialInteractionsIncentives:94/:116` | core | `laneChangePossibility` |
| `CruisingSpeedIncentive:89/:118` — `A`, `VCONG` | core | `DESIRED_ACCELERATION`, `CONGESTED_SPEED` |
| `ProhibitDeadEndIncentive:60` — `getParallelMerge(dir)` | core | `parallelMergeAhead(side, range)` |
| `CongestionIncentive` | gone | deleted in Phase 0.5 — never registered |
| `Desire:1` — `LateralDirectionality` | core | `Side` |

---

## A.4 Intention layer

| Inventory row | Disposition | Contract element |
|---|---|---|
| `ManeuverPattern:10`, `ActionState:10` — `SimpleOperationalPlan` as the FSM return type | core | `TacticalCommand` |
| `Transition:3-6` — four OTS checked exceptions | gone | Kotlin has no checked exceptions; fail-fast per A.6 |
| `LateralExecution:118` — completion via `gtu.getLane()` | core | `onLaneChangeCompleted(side)`; the core holds no lane identity |
| `LateralExecution:94-103` — indicator intents | core | `TacticalCommand.indicator` |
| `SimpleLaneChangePattern:164/:196` | core | `EgoState.laneChange` |
| `SimpleLaneChangePattern:198` — DJUnits arithmetic | core | kotlin-units |
| `SimpleLaneChangePattern:79` — live `DFREE` | core | `DESIRE_FREE`, snapshotted |
| `MandatoryLaneChangePattern:359-363` — downstream lane + segment mean speed | core | `expectedMergeSpeed(side, range)` — the two queries collapse into one |
| `:480/:1380` — physical and route distance to lane end | core | `distanceToLaneEnd`, `routeRequirement` |
| `:618/:1350` — `getDistanceToLaneChangeExtendedLookahead()` | core | `routeRequirement(lane, range)` with the range passed explicitly |
| `:1271` — `SPEED_SMOOTHING_FACTOR = params.dtSi * 0.25` | core | **BC-1**: `α = 1 − exp(−dt/τ)` on the measured `dt` |
| `:698` — live snapshot parameter read | core | resolved at construction |
| `:2426/:2470` — `gtu.getLane()`, `isChangingLane()` | core | `EgoState.laneChange` |
| `:2431/:2501` — commented-out `LCDUR` mutation | core | `LaneChangeRequest.duration`, with `CONGESTED_LANE_CHANGE_DURATION` revived for it |
| `:529` — `gtu.getId()` | core | `ParticipantId` |
| `GapOpenerPattern:290-291` — the leader's CF model and parameters | core | **BC-2**: the ego's own parameters |
| `GapOpenerPattern:67-72` — two `System.getProperty` reads | gone | deleted with the mechanism in Phase 0.5 |
| `GapOpenerPattern:350/:566` — `LOOKAHEAD` | core | range argument |
| `GapOpenerPattern:210-211` — indicator reads | core | `PerceivedVehicle.indicator` — observable, kept |
| `PreventUndercuttingPattern:439/:617` — `T` | core | `DESIRED_HEADWAY` |
| `PreventUndercuttingPattern:362/:553` — `follow*WithReducedHeadway` | core | `followingAcceleration(..., headwayFactor)` |
| `PreventUndercuttingPattern:369` — `getAverageSpeed(LEFT)` | core | `meanSpeed(LEFT, range)` (provisional, Q1) |
| `AnticipateDownstreamMergePattern:338/:364/:357` | gone from v1 | pattern kept but `MIROVA-DISABLED`; if revived it needs `expectedMergeSpeed` and one further derived query |
| `AnticipateAdjacentCongestionPattern:107/:120` | gone | deleted in Phase 0.5 |
| `helpers/GapCandidate`, `helpers/HeuristicGapSelector` | gone | deleted in Phase 0.5 |

---

## A.5 Arbitration layer

| Inventory row | Disposition | Contract element |
|---|---|---|
| `HybridPlanArbitrator:16/:170` — `SimpleOperationalPlan` | core | `TacticalCommand` |
| `HybridPlanArbitrator:191` — `params.dtScalar` in the plan | core | `nextEvaluationAfter` |
| `HybridPlanArbitrator:14` — `LateralDirectionality` | core | `Side` |
| `ScoredOperationalPlan` | core | scores a `TacticalCommand` |
| `PatternSelector` — `ParameterException` only | core | no checked exception |
| `PlanArbitrator`, `MaxUtilityArbitrator` | gone | deleted in Phase 0.5 |

---

## A.6 Reactive layer

| Inventory row | Disposition | Contract element |
|---|---|---|
| `MirovaCarFollowingUtil` — six `CarFollowingUtil` calls | core | equivalents over the core IDM+ |
| `MirovaCarFollowingUtil:355-390` — `setParameterResettable(T)` | core | `headwayFactor` argument (contract §8) |
| `MirovaCarFollowingUtil:92` — `getSimulatorTime()` | core | `step(now, …)` |
| `MirovaCarFollowingUtil.getKinematicEmergencyBrake` | gone | deleted in Phase 0.5 with `TTC_EMERGENCY_BRAKING` |
| `LongitudinalControl:105` — `considerSpeedLimitTransitions` | core | anticipation of an upcoming lower limit, computed from `speedLimits`. The OTS routine reacted only to curvature and speed-bump types, which nothing populates — 0 finite candidates in 141,337,948 evaluations (§E.5). |
| `LongitudinalControl:106` — `getSpeedLimitProspect(CURRENT)` | core | `speedLimits(CURRENT, range)` |
| `LongitudinalControl:83/:137` — `isChangingLane()`, `getFraction()` | core | `EgoState.laneChange` (`side`, `fraction`); the `> 0.5` rule stays in the core |
| `LongitudinalControl:126` — `LOOKAHEAD` | core | range argument |
| `MirovaIdmPlus:36` — `extends AbstractIdm` | core | own IDM+ behind `CarFollowingModel` |
| `MirovaIdmPlus:65` — live `T` | core | snapshotted; factor as argument |
| `MirovaIdmPlusFactory:21/:31` — `StreamInterface` | core | `RandomStream`, injected by the host |
| `CapacityDrop:169` — `Parameters`, `T` | core | the five `DISCHARGE_*` / `CAPACITY_DROP_*` keys |
| `DynamicHeadwayProvider:22` | core | `CarFollowingModel` |
| `Wiedemann99` and its four companions | host | **stays in OTS** (A.3). Alive: `SimpleHighwayScenario` builds a `Wiedemann99Factory` for cars and trucks. No MiRoVA layer depends on it. |

---

## A.7 Utilities, watchdog and logging

| Inventory row | Disposition | Contract element |
|---|---|---|
| `DeadlockDiffusionWatchdog:169` — `gtu.destroy()` | host | entirely; the core may emit `LimitReached` and nothing more |
| `DeadlockDiffusionWatchdog:112` — simulator time | host | — |
| `DeadlockDiffusionWatchdog:96/:119` — `EMERGENCY_STOPPING_DISTANCE`, `VEHICLE_DIFFUSION_TIME` | host | host-side configuration, not driver parameters — which is why they are not in `DriverParameterKeys` |
| `GtuDeletionDiagnostics:55` | host | — |
| `util/logging/extendeddata/*` (21 files) | host | stays in `mirova-ots` |
| `MirovaCsvLogger` | host | one possible `Diagnostics` implementation |
| `FsmTraceRecorder` | core | moves with the FSM; it is the regression net for layer 3, and it becomes a `Diagnostics` consumer |
| `MergeGateDiagnostics`, `VehicleDiffusionLogger` | host | `Diagnostics` implementations |
| `DefectDiagnostics` (added in Phase 0.5) | host | its role is `Diagnostics`; the system properties and the shutdown hook do not migrate |
| `util/units/DimensionlessUnitMirova` | gone | deleted in Phase 0.5 |

---

## A.8 Host side (`ots-demo`)

| Inventory row | Disposition | Contract element |
|---|---|---|
| `ScenarioGenerator:703` — reflective `setParameter` over string keys | core | `DriverParameters.with(key, value)`; the reflection goes, and an unknown key is a compile error |
| `ScenarioGenerator:1130/:1168` — factory construction | adapter | `MirovaOtsAdapterFactory(coreFactory, worldViewFactory)` |
| `AbstractSimulationScriptBase:290` — named DSOL streams | host | `RandomStream`, injected |
| `DesiredSpeedLibrary`, `HeadwayDistributionLibrary` | split | shapes → core `ParameterDistribution`; the calibrated `Limit×Density` variants and the arrival headway → host. One case unresolved: contract Q3. |
| `MergeScenario:238-241`, `SimpleHighwayScenario:266-311` — `T`/`TMIN`/`TMAX` | split | `T` → `DESIRED_HEADWAY` per vehicle class. **`TMIN`/`TMAX` on MiRoVA factories have no effect** (only the uninstalled mental module reads them) and were dropped in Phase 0.5. The same calls on the *LMRS* factories are legitimate and stay. |

---

## 9. Rows that no longer exist

Deleted in Phase 0.5, listed so the inventory can be walked row by row: `helpers/GapCandidate`,
`helpers/HeuristicGapSelector`, `MaxUtilityArbitrator`, `PlanArbitrator`, `CongestionIncentive`,
`AnticipateAdjacentCongestionPattern`, `DimensionlessUnitMirova`, `NeighborsContext.isGtuAlongside`,
`MacroTrafficContext.getDensity*`, `MirovaCarFollowingUtil.getKinematicEmergencyBrake`, the extended
look-ahead mechanism in `InfrastructureContext`, the relaxation speed buffer, and eight parameters
(`SOCIAL_INTERACTION_COOLDOWN`, `TTC_EMERGENCY_BRAKING`, `RELAXATION_TAU_SPEED`, `DSEARCH`,
`ACCELERATION_SCALING_FACTOR`, `standstill_speed_threshold`, `mandatoryLaneChangeLookAheadDistance`,
`vCrit`). 1835 lines.

`AnticipateDownstreamMergePattern` is **kept** and marked `MIROVA-DISABLED`.

---

## 10. Gaps — inventory rows with no contract element

Four, and each is deliberate.

1. **`AnticipateDownstreamMergePattern` is not covered by contract v1.** The pattern is retained in the
   Java tree but not registered, so it has no live rows. Reviving it needs `expectedMergeSpeed` (which
   exists) and a query for the lane adjacent to a downstream lane drop (`:357`), which does not. **If
   the pattern is to live, that query has to be designed; if not, it should be deleted.**

2. **The deadlock watchdog has no core presence.** §E.10 asked whether it should. The contract answers
   no: it deletes vehicles, which is a host power the core must not have, and its two parameters are
   host configuration. What the core offers instead is the `LimitReached` diagnostic, from which a host
   can build its own watchdog. **The consequence is that a host without a watchdog will deadlock where
   OTS today recovers**, and that is worth stating plainly.

3. **`meanSpeed` may not survive** (Q1). If BC-5 is adopted, `MacroTrafficContext:269` and
   `PreventUndercuttingPattern:369` map to a core computation over perceived leaders instead of to a
   query, and §1 of the contract loses an entry.

4. **The Wiedemann 99 rows have no core element by design.** They are in the table as *host*, not as
   a gap. Inventory §A.6 and §D listed W99 as dead code and counted its 556 lines among the dead
   lines; both are corrected.
