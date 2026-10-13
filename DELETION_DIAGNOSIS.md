# Deletion diagnosis — why OTS 1.8.1 removes vehicles in the Freiburg-Nord LMRS runs

Day 2025-09-22, seed 42, `--model=lmrs`, upstream defaults, branch `ots-upstream-benchmark`. Diagnosis only; nothing
was fixed.

## Result in short

- **One root cause explains all 55 removals of the day.** A vehicle that completes a lane change a few metres
  before a sink detector does not trigger that sink. It drives on to the end of the last lane, 50 m further, and
  fails there in one of two ways, which are what the error handler reports.
- **The measured results are not affected.** Every removal happens in the last 50 m of the network, downstream of
  every loop detector and of the trajectory sampler. The vehicle should have been removed 50 m earlier and is
  removed 50 m later.
- **The 46 exceptions without a stack trace were HotSpot's fast-throw**, as you suspected. With
  `-XX:-OmitStackTraceInFastThrow` all of them have a trace, and it is the same one as the three that had it before.
- **The old fork had the same defect, 13 times as often, and hid it.** With the LMRS of OTS 1.7.6 on the same day
  and seed, 706 vehicles reached the end of `L5a` without being caught by the sink. 1.7.6 destroyed them inside
  the plan builder with a log line and never called the error handler.

## Data

| | |
|---|---|
| Run | `RunMirovaClusterStudy --study=dates --dates=2025-09-22 --replications=1 --index=0 --model=lmrs`, with `-XX:-OmitStackTraceInFastThrow` and `-Dmirova.samplerLinks=L5a` |
| Per-vehicle table | `diagnosis/2025-09-22_seed42/removed_vehicles_diagnosis.csv`, one row per removed vehicle |
| Stack traces | `diagnosis/2025-09-22_seed42/removed_vehicles_traces.txt`, two distinct traces |
| Script | `diagnosis/removal_diagnosis.py <run folder> <output csv>` |

Sampling `L5a` instead of `L4a` does not change the simulation: the 55 removals are the same vehicles at the same
times as in the run with the default sampler.

Columns of the table: `gtu_id`, `gtu_type`, `sim_time_s`, `route_destination`, `roaming`, `link_id`, `lane_id`,
`position_m`, `speed_mps`, `cause`, `trace_hash`, `upstream_detector`, `upstream_detector_v_kmh`,
`in_breakdown_interval`, and five columns from the trajectory: `final_lane_on_L5a`, `sink_position_m`,
`lane_change_ends_at_x_m`, `lane_change_end_to_sink_m`, `samples_past_sink`, `last_sample_x_m`.

`in_breakdown_interval` is true when the nearest upstream detector (`det_L5a`, at 371 m, the lane the vehicle is
nearest to) reports a harmonic mean speed below 86.2 km/h in the minute of the removal.

## What the 55 removals have in common

| Property | Value |
|---|---|
| Vehicles on `L5a` during the day | 19 092 |
| Removed | 55 (0.29 %): 54 cars, 1 truck |
| Route destination | `N5_3` for all 55, the regular end of the mainline. None is a vehicle that missed the exit. |
| Where | all on `L5a`, at 742.4 m to 744.7 m; the lanes are 742.39 m and 742.36 m long |
| Speed at removal | 27.6 to 42.1 m/s, median 35.9 m/s. Free-flowing vehicles. |
| In a breakdown interval | 4 of 55 (detector speed 82.0 to 86.1 km/h); median detector speed at removal 109.1 km/h |
| Time | spread over the whole day, 636 s to 29 853 s |
| Lane change on `L5a` | **all 55** end a lane change onto `Lane1` between 2.6 m and 8.9 m before the sink; median 2.8 m |
| Samples past the sink | 3 to 5 per vehicle (0.5 s steps), i.e. the vehicle drives the remaining 50 m normally |
| Other vehicles past the sink | none. Of 19 092 vehicles, exactly these 55 are sampled more than 1 m past a sink, and all 55 are removed. |

The sinks are at `END-50m` on both lanes of `L5a`, i.e. at 692.39 m (`Lane1`) and 692.36 m (`Lane2`). A typical
case (GTU 438, positions of the reference point):

| t [s] | lane | x [m] |
|---|---|---|
| 633.1 | Lane2 | 653.0 |
| 633.6 | Lane2 | 671.0 |
| 634.1 | Lane2 and Lane1 | 689.2 / 689.7 — last sample on both lanes: the lane change ends here |
| 634.6 | Lane1 | 708.0 — past the sink at 692.39, not removed |
| 635.1 | Lane1 | 726.6 |
| 635.6 | Lane1 | 744.9 — past the end of the lane; removed at 635.64 s |

**What is not established.** That the lane change ending just before the sink *is* the trigger condition is an
inference from the fact that it holds for 55 of 55 and that no vehicle without it gets past a sink. I did not find
the faulty line. About 2 200 other vehicles are recorded on both lanes within 20 m upstream of the sink and are
removed correctly; I could not tell from the trajectories how many of them had already finished their lane change,
because their record ends at the sink.

**Do loop detectors lose vehicles the same way?** Not measurably. On `L5a`, 19 090 trajectories cross the detector
position and the two detectors count 19 080 vehicles (0.05 % fewer).

## Root causes

### RC1 — a sink is not triggered by a vehicle that finished a lane change just upstream of it

- **Where:** `org.opentrafficsim.road.gtu.LaneBasedGtu.findDetectorTriggers` and
  `findDetectorTriggersInEpisode` (`LaneBasedGtu.java`, lines 957 to 1075 at `78f2601dd`), which collect the
  detectors a plan will reach, per lane "episode" of the plan. This is where a detector on the target lane would
  have to be found; I did not identify the faulty statement.
- **Evidence:** the table above. 55 of 55 removed vehicles; 0 other vehicles past a sink.
- **Consequence:** the vehicle is not destroyed, continues, and runs off the network (RC2, RC3).

Draft issue:

> **SinkDetector not triggered after a lane change that ends just upstream of it**
>
> OTS 1.8.1 (`78f2601dd`). Two-lane link, one `SinkDetector` per lane at `END-50m` (XML `<Sink>`), LMRS with
> default settings, 0.5 s time step. A GTU whose lane change ends 2.6 to 8.9 m upstream of the sink is not
> destroyed by the sink on its new lane. It continues to the end of the lane and then fails with one of the
> exceptions in the two issues below. In a 9-hour run with 19 092 GTUs on the link this happens 55 times; in every
> case the GTU was recorded on both lanes for the last time 2.6 to 8.9 m before the sink (median 2.8 m), and no GTU
> without that pattern passes a sink. Loop detectors on the same link are not affected measurably (19 080 counted
> against 19 090 trajectories crossing). Expected: the sink on the lane the GTU is on destroys it. Suspected place:
> `LaneBasedGtu.findDetectorTriggersInEpisode`, the hand-over between the origin-lane episode and the target-lane
> episode of a plan. I can provide the network, the demand and the GTU ids.

### RC2 — a GTU that is on no lane makes its own perception throw (49 of 55)

- **Where:** `org.opentrafficsim.road.gtu.perception.structure.LaneStructure.update`, `LaneStructure.java:475`:
  `LanePosition position = this.egoGtu.getPosition();` followed by `position.lane()`. For a GTU that has left its
  last lane ("roaming"), `getPosition()` returns `null`.
- **Trace** (`d3ab7379`): `Lmrs.generateOperationalPlan:87` → `LmrsUtil.determinePlan:117` →
  `DirectNeighborsPerception.getLeaders:160` → `computeLeaders:170` → `LaneStructure.getDownstreamGtus:184` →
  `LaneStructure.update:475`, `NullPointerException: Cannot invoke "LanePosition.lane()" because "position" is null`.
- **Why it had no trace before:** it is thrown from one place many times, so HotSpot stopped filling in the stack
  ("fast throw"). 46 of the 49 appeared as `NullPointerException: null`.
- **It is a consequence of RC1 here**, but it is a defect of its own: any GTU that reaches the end of a network
  without a sink ends this way.

Draft issue:

> **NullPointerException in LaneStructure.update for a roaming GTU**
>
> OTS 1.8.1 (`78f2601dd`). When a GTU with an LMRS tactical planner has left its last lane and is roaming,
> `LaneStructure.update` (line 475) calls `egoGtu.getPosition()`, gets `null`, and dereferences it. The exception
> surfaces as `RuntimeException: Unable to get leaders from LaneStructure` from `DirectNeighborsPerception.computeLeaders`.
> OTS 1.7.6 handled the same situation in `LaneOperationalPlanBuilder` with the error "GTU … has nowhere to go and
> no sink detector either" and destroyed the GTU. Expected: a defined behaviour for a GTU that runs off the network
> — destroy it with a clear message, or an exception that says so — instead of a NullPointerException from
> perception.

### RC3 — the plan builder extracts a lane segment that starts beyond the end of the lane (6 of 55)

- **Where:** `org.opentrafficsim.road.gtu.operational.LaneOperationalPlanBuilder.getTargetLanePosition`,
  `LaneOperationalPlanBuilder.java:570`:
  `startPosition.lane().getCenterLine().extract(startPosition.position(), startPosition.lane().getLength())`.
  With a start position of 743.6 to 744.7 m on a lane of 742.39 m, `PolyLine2d.extract` refuses the interval.
- **Trace** (`be159789`): `Lmrs.generateOperationalPlan:137` → `buildPlanFromSimplePlan:158` → `getPath:187` →
  `bezierToHorizon:204` → `getTargetPoint:514` → `getTargetLanePosition:570` → `OtsLine2d.extract:191/204` →
  `PolyLine2d.extract:836`, `IllegalArgumentException: Bad interval (744.67..742.39; length … is 742.39)`.
- **The six vehicles** are still registered on `L5a/Lane1` (not roaming) with a position up to 2.3 m past its end.
  The other 49 had already been taken off the lane when they failed. Which of the two happens is a matter of where
  the time step falls.
- **A consequence of RC1 here** as well.

Draft issue:

> **IllegalArgumentException "Bad interval" in LaneOperationalPlanBuilder.getTargetLanePosition at the end of a lane**
>
> OTS 1.8.1 (`78f2601dd`). `getTargetLanePosition` (line 570) extracts the lane centre line from the GTU's position
> to the lane length. When the GTU is still registered on the lane but its reference position is beyond the lane
> length (observed: up to 2.3 m beyond, on the last lane of the network), `PolyLine2d.extract` throws
> `Bad interval (744.67..742.39)`. Expected: the position is clamped to the lane length, or the GTU is treated as
> having left the lane.

## Sink position and lane length, 1.8.1 against the old fork

| | Old fork (OTS 1.7.6) | This branch (OTS 1.8.1) |
|---|---|---|
| `L5a/Lane1` length | 742.13 m | 742.39 m |
| `L5a/Lane2` length | 741.59 m | 742.36 m |
| Sink definition | `Position="END-50m"`, both lanes | unchanged |
| Sink position | 692.13 m / 691.59 m | 692.39 m / 692.36 m |
| Sink predicate | `SinkDetector.DESTINATION` (XML default) | unchanged |
| Time step of the LMRS | 0.5 s | 0.5 s |

The lane lengths are twice the detector positions in `detector_positions.csv`; the detectors sit at half the lane
length. The sink moved by 0.26 m and 0.77 m because the lanes are that much longer.

**Does a vehicle skip the sink within one time step?** In the sense of "the step is longer than the detector":
no. A detector is triggered by an event scheduled at the time the plan reaches it, not by sampling, so a step of
18 m at 36 m/s does not jump over it, and 19 037 of 19 092 vehicles are removed by the sinks. In the sense of "the
sink is missed in the step in which something else happens": yes, that is RC1 — the step in which the lane change
ends.

## The old fork with LMRS, same day and seed

Run on `origin/main` (`f41175d53`, OTS 1.7.6) with the LMRS of that version and its default parameters, selected
through a `TacticalPlannerProvider` on the class path. The fork was not modified.

| | Old fork, LMRS 1.7.6 | This branch, LMRS 1.8.1 |
|---|---|---|
| Vehicles counted on the `L5a` detectors | 19 016 | 19 080 |
| Vehicles that reached the end of `L5a` past the sink | 706, plus 1 on `L6_ramp` | 55 |
| How they were removed | `LaneOperationalPlanBuilder.createPathAlongCenterLine:172` logs "GTU … on link L5a has nowhere to go and no sink detector either" and calls `gtu.destroy()`. The error handler is not involved. | exception, then the error handler (RC2, RC3) |
| Removed through the error handler | 4, all `PolyLine2d.extract: Bad interval (738.6..738.59)` on `L5a/Lane2` | 55 |
| Visible in the output | no | yes, `removed_vehicles.csv` and `gtus.removed` |

So the defect behind RC1 is older than 1.8.1 and was 13 times as frequent in 1.7.6 (3.7 % of the vehicles on
`L5a` against 0.29 %). What changed is that 1.8.1 no longer has the silent `destroy()` and the failure reaches the
error handler. The fork's MiRoVA runs were not examined for this; the count above is for the LMRS on the fork.

## The second seed

Seed 1000045 of the same day: 78 removals. 73 are RC2 near the end of `L5a`, 4 are RC3 on `L5a`, and **one is RC2
near `L_exit`** (nearest lane `LeftShoulder`), i.e. on the exit ramp and not at a sink. That one is not explained
by RC1 and was not investigated; the per-vehicle record is in that run's `removed_vehicles.csv`.

## What this means for the runs

- Removals at the end of `L5a` do not touch the detectors (the last one is at 371 m) or the sampler (`L4a`).
  `gtus.removed` in `run.properties` will be in the tens per run and is expected until RC1 is fixed upstream.
- A removal anywhere else is a different matter. `removed_vehicles.csv` has the link and lane of each one, so a
  check "all removals are on `L5a` beyond 742 m" is one line in post-processing.
- If the removals at the network end are to disappear without touching OTS: moving the sinks to the very end of
  `L5a` does not help (the vehicle would still miss it), but a second sink a few metres behind the first on each
  lane very likely would. That is a change to the scenario and was not made.
