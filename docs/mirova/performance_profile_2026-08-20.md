# MiRoVA Performance Profile — 2026-08-20

Diagnosis only. No performance changes were made; the tuning candidates at the end are
proposals awaiting review.

## Summary

The tactical layer's own algorithms are **not** where the time goes. MiRoVA code owns
**1.7 %** of sampled CPU self-time. Nearly everything else is spent inside two OTS library
mechanisms that MiRoVA calls very often, both of which use an object as a `HashMap` key
whose `hashCode()` recursively hashes DJUnits scalars:

| Cost | Share of CPU | Root cause |
|---|---|---|
| `LaneBasedGtu.position(...)` cache | ~25 % | `RelativePosition.hashCode()` → 3 × `DoubleScalar.hashCode()` |
| `ParameterSet.getParameter(...)` | ~15 % | `ParameterType.hashCode()` → `DoubleScalar.hashCode()` |

`DoubleScalar.hashCode()` walks `Unit` → `Quantity` → `SIDimensions`, and `Quantity.hashCode()`
iterates a `LinkedHashMap` key set — allocating an iterator on **every call**. That single
implementation detail accounts for **40 % of CPU** and **15 % of all allocation**.

MiRoVA's own caching works. The per-tick car-following cache is effective (76 % of all
car-following model evaluations funnel through the one cached entry point, and the models
together are only 7.9 % of CPU), and the string-keyed `ContextCategory` cache costs 2.6 % of
CPU — real, but an order of magnitude below the two hotspots above.

## What was profiled

| | |
|---|---|
| Scenario | `FreiburgNord` via `RunFreiburgMergeWatch`, headless |
| Demand window | 2025-10-13 13:00–14:00, 5-min aggregation, unsmoothed |
| Behaviour parameters | `FreiburgStudyParameters` baseline (car/truck differentiated) |
| Seed | 42 |
| Trajectory recording | **off** (see limitations) |
| JDK | OpenJDK 17.0.2, JFR built in, no extra tooling |
| Recording | `-XX:StartFlightRecording=filename=…,settings=profile,dumponexit=true -XX:FlightRecorderOptions=stackdepth=128` |
| Analysed window | steady state only, 4 952 CPU samples / 11 752 allocation samples |

`settings=profile` was used rather than `default` because it enables `jdk.ObjectAllocationSample`;
allocation is analysed separately from CPU throughout. `stackdepth=128` was necessary — the
default truncates the deep perception call chains before the interesting frames.

### Traffic density — the run was not artificially light

| Detector | mean flow | max flow | mean harmonic speed |
|---|---|---|---|
| `det_L3a.Lane1` | 438 veh/h | 900 | 90.7 km/h |
| `det_L3a.Lane2` | 675 veh/h | 1 620 | 115.2 km/h |
| `det_L5a.Lane1` | 638 veh/h | 1 440 | **62.1 km/h** |
| `det_L5a.Lane2` | 807 veh/h | 1 920 | **62.4 km/h** |
| `det_L7a.Lane1` | 307 veh/h | 780 | 63.8 km/h |

Sustained OD demand was 2 650–3 000 veh/h (≈ 19 % trucks), roughly 1 100 vehicles generated per
25 simulated minutes. Speeds drop from 91–115 km/h upstream (L3a) to ~62 km/h at the merge
(L5a), so the window covers both free flow and dense/congested merging — the regime the
tactical layer is most active in. Zero diffusion (deadlock-removal) events occurred.

## CPU hotspots mapped to the architecture

### Who owns the innermost frame (self time)

```
   71.47%  JDK (collections / String)
   14.32%  generic OTS
    8.02%  DJUnits
    4.36%  djutils (MultiKeyMap, geometry)
    1.70%  MiRoVA own code
    0.14%  DSOL
```

### Which layer the stack is under (inclusive)

```
   29.64%  L1 Perception/Belief: InfrastructureContext
   19.45%  L1 Perception/Belief: NeighborsContext
   10.88%  L2 Cognition: DesireLayer
    9.07%  L5 Reactive: car-following
    8.54%  MirovaTacticalPlanner (loop)
    8.10%  MiRoVA other
    3.37%  L1 Perception/Belief: VehicleContextManager
    3.19%  L4 Intention: ManeuverPatterns
    2.83%  L1 Perception/Belief: ContextCategory
    2.12%  L1 Perception/Belief: MacroTrafficContext
    1.13%  L1 Perception/Belief: EgoContext
    1.09%  (no MiRoVA frame — pure OTS/DSOL)
    0.57%  L3 Decision: ArbitrationLayer
```

Read these two tables together. Layer 1 dominates the *inclusive* view, but MiRoVA owns almost
none of the self-time — Layer 1 is expensive because it is the layer that calls OTS perception
and GTU positioning, not because its own code is slow. **Layers 2–5 are cheap.** Arbitration,
which the architecture treats as a central step, is 0.57 %.

### Top self frames

```
   31.14%  java.lang.String.hashCode
   10.16%  java.util.HashMap.getNode
    8.68%  java.lang.StringLatin1.hashCode
    3.39%  java.util.Arrays.hashCode
    3.01%  org.djunits.value.util.ValueUtil.expressAsUnit
    2.24%  java.util.HashMap.putVal
    2.18%  org.opentrafficsim.base.geometry.OtsLine2d.projectFractional
    1.82%  org.opentrafficsim.core.gtu.perception.AbstractPerceptionCategory.computeIfAbsent
    1.49%  org.djunits.value.util.ValueUtil.expressAsSIUnit
    1.17%  org.opentrafficsim.road.gtu.lane.LaneBasedGtu.position
    1.13%  org.djunits.unit.Unit.hashCode
    1.07%  org.djunits.quantity.Quantity.hashCode
```

Map and hash operations account for **57.9 % of CPU** in self-time. Attributing each of those
samples to its nearest non-JDK caller:

```
   56.47%  org.djunits.quantity.Quantity.hashCode
    4.47%  org.djunits.unit.si.SIDimensions.hashCode
    4.43%  org.djunits.unit.Unit.hashCode
    3.63%  org.opentrafficsim.base.parameters.ParameterType.hashCode
    3.56%  org.opentrafficsim.base.parameters.ParameterSet.getParameter
    3.28%  org.djutils.multikeymap.MultiKeyMap.getSubMap
    2.34%  …mirova…BeliefLayer.ContextCategory.cacheValue
    2.20%  …mirova…BeliefLayer.ContextCategory.getCachedValue
    1.82%  org.opentrafficsim.core.gtu.perception.AbstractPerceptionCategory.computeIfAbsent
    1.78%  …mirova…BeliefLayer.VehicleContextManager.getAllCategories
```

### The dominant chain, verbatim from `jfr print`

```
jdk.ExecutionSample {
  startTime = 14:10:00.026
  sampledThread = "main" (javaThreadId = 1)
  state = "STATE_RUNNABLE"
  stackTrace = [
    java.util.Arrays.hashCode(byte[]) line: 4383
    org.djunits.unit.si.SIDimensions.hashCode() line: 337
    org.djunits.quantity.Quantity.hashCode() line: 428
    org.djunits.unit.Unit.hashCode() line: 674
    org.djunits.value.vdouble.scalar.base.DoubleScalar.hashCode() line: 293
    java.util.Objects.hashCode(Object) line: 103
    org.opentrafficsim.core.gtu.RelativePosition.hashCode() line: 1
    java.util.HashMap.hash(Object) line: 338
    java.util.HashMap.put(Object, Object) line: 610
    org.djutils.multikeymap.MultiKeyMap.put(Object, Object[]) line: 92
    org.opentrafficsim.road.gtu.lane.LaneBasedGtu.position(Lane, RelativePosition, Time) line: 1222
    org.opentrafficsim.road.gtu.lane.LaneBasedGtu.position(Lane, RelativePosition) line: 1061
    org.opentrafficsim.road.gtu.lane.perception.structure.LaneStructure.lambda$position$30(…) line: 459
    …
  ]
}
```

`RelativePosition` holds three `Length` scalars. Hashing it hashes all three; each one walks
`Unit` → `Quantity` → `SIDimensions` and ends in `Arrays.hashCode(byte[])` / `String.hashCode`.
This happens on *every* `MultiKeyMap.put`/`get` inside the GTU position cache — a cache whose
lookup is more expensive than the value it protects.

Splitting `DoubleScalar.hashCode` (40.5 % of CPU) by who asked for it:

```
   61.70%  org.opentrafficsim.core.gtu.RelativePosition.hashCode   → LaneBasedGtu.position cache
   36.51%  org.opentrafficsim.base.parameters.ParameterType.hashCode → ParameterSet.getParameter
    1.70%  org.opentrafficsim.road.gtu.lane.perception.headway.AbstractHeadway.hashCode
```

`ParameterSet.getParameter` appears on the stack in **17.1 %** of samples. Its callers:

```
   13.07%  …mirova…ReactiveLayer.MirovaIdmPlus$1.desiredHeadway
    7.89%  org.opentrafficsim.road.gtu.lane.tactical.following.AbstractIdm.followingAcceleration
    6.48%  org.opentrafficsim.road.gtu.lane.tactical.following.AbstractIdm.dynamicHeadwayTerm
    5.77%  …mirova…ManeuverPatterns.GapOpenerPattern.findNewCandidate
    5.65%  …mirova…DesireLayer.RouteIncentive.computeDesire
    5.42%  …mirova…BeliefLayer.InfrastructureContext.computeAnticipatedSpeed
    5.18%  …mirova…ReactiveLayer.MirovaIdmPlus.combineInteractionTerm
    4.36%  org.opentrafficsim.road.gtu.lane.perception.categories.DirectInfrastructurePerception.computeLaneChangePossibility
```

Roughly half is inside the IDM car-following evaluation, which re-reads its parameters from the
map on every call.

## Allocation hotspots

Kept deliberately separate from the CPU analysis. **GC itself is not a problem**: 37 young
collections, **0.1 s total pause time**, no old collections. The objects die immediately and G1
reclaims them cheaply. The cost of the allocation shows up as *CPU in the allocation path*, not
as GC pauses — so this is not a "GC pressure" finding.

### By layer (inclusive)

```
   33.37%  L1 Perception/Belief: InfrastructureContext
   28.40%  L1 Perception/Belief: NeighborsContext
    9.86%  L2 Cognition: DesireLayer
    8.42%  MirovaTacticalPlanner (loop)
    7.32%  MiRoVA other
    4.17%  L1 Perception/Belief: VehicleContextManager
    3.69%  L5 Reactive: car-following
    1.78%  L4 Intention: ManeuverPatterns
```

MiRoVA code owns only **1.0 %** of allocating frames — again, Layer 1 is charged because it
calls OTS perception, not because it allocates itself.

### Top allocated types

```
   17.06%  java.util.LinkedHashMap$LinkedKeyIterator
   10.34%  org.djutils.draw.point.Point2d
    8.60%  java.lang.Object[]
    6.61%  java.util.LinkedHashMap
    5.50%  java.util.LinkedHashMap$Entry
    5.36%  java.util.HashMap$Node[]
    4.98%  org.djunits.value.vdouble.scalar.Length
    4.62%  java.util.AbstractList$RandomAccessSubList
    4.61%  double[]
```

The largest single type is an *iterator*. 87.6 % of those come from the hashCode chain:

```
   57.77%  org.djunits.quantity.Quantity.hashCode
   29.83%  org.djunits.unit.Unit.hashCode
    4.35%  org.opentrafficsim.road.gtu.lane.perception.structure.LaneStructure.nextLateral
```

`Point2d` (10.3 %) is unrelated — it comes from
`LaneOperationalPlanBuilder.createPathAlongCenterLine`, i.e. building the geometric path for
every operational plan. That is generic OTS, outside MiRoVA.

`Length` at 5.0 % is the only sizeable DJUnits-object allocation attributable to modelling work
itself, and it is modest.

## Caching-effectiveness check (CLAUDE.md § "Performance")

The project states two principles: prefer O(1) lookups, and use per-tick ID-based caching so the
car-following model is evaluated at most once per leader per tick. Both hold up.

**Car-following tick cache — effective.** Samples inside any car-following model total 390
(**7.9 % of CPU**), and their entry points are:

```
   76.41%  …BeliefLayer.EgoContext.getCurrentCarFollowingAcceleration   ← the cached path
    4.10%  …DesireLayer.RouteIncentive.computeDesire
    3.33%  …BeliefLayer.InfrastructureContext.computeAnticipatedSpeed
    3.08%  …MandatoryLaneChangePattern$AnticipateMergeState.next
    2.31%  …SimpleLaneChangePattern$PerformLaneChangeState.executeControl
```

Three quarters of all model evaluations go through the single cached entry point, and the
remainder are genuinely different queries (anticipated speed, hypothetical leaders) rather than
repeats of the same one. Car-following is not dominating despite being called constantly —
which is exactly the outcome the cache was introduced for. **No evidence of bypass.**

**The string-keyed context cache — works, but is not free.** `ContextCategory.getCachedValue` +
`cacheValue` account for 4.54 % of the map/hash time ≈ **2.6 % of CPU**. Every access hashes a
`String` key, several of which are built by concatenation (e.g.
`"laneAverageSpeed_" + lane.getId() + "_" + start + "_" + end + "_" + n + "_" + dir` in
`InfrastructureContext`), so the key is *constructed* before it is hashed. Correct, but with a
measurable constant.

**Where the O(1) principle is violated is outside MiRoVA**: `MultiKeyMap` and `ParameterSet` are
O(1) in lookups but with a hash function that is O(size of the unit system) *and* allocating.

## Tuning candidates — proposals only, nothing implemented

Ordered by expected impact. Percentages are share of sampled CPU in the steady-state window.

### 1. Avoid repeated `gtu.position(...)` queries per tick — ~25 % of CPU
**Layer:** L1 Perception/Belief (`InfrastructureContext`, `NeighborsContext`), triggered through
OTS perception.
**Evidence:** `RelativePosition.hashCode` is 61.7 % of `DoubleScalar.hashCode`, which is 40.5 % of
CPU; the dominant stack is `LaneBasedGtu.position` → `MultiKeyMap.put`.
**Idea:** cache the positions a vehicle needs once per tick in the context layer, the same way
`EgoContext.tickAccelerationCache` already does for accelerations, so the expensive OTS cache is
consulted once instead of repeatedly.
**Constraints:** none from CLAUDE.md — this is added caching, not a change to DJUnits usage or
the car-following wrapper. The risk is correctness of cache invalidation, not policy.

### 2. Hoist parameter reads out of the per-call car-following path — ~7–8 % of CPU
**Layer:** L5 Reactive (`MirovaIdmPlus`) plus OTS `AbstractIdm`.
**Evidence:** `ParameterSet.getParameter` is on the stack in 17.1 % of samples; ~half of that is
`MirovaIdmPlus.desiredHeadway`, `AbstractIdm.followingAcceleration`, `dynamicHeadwayTerm` and
`combineInteractionTerm`, all of which re-read the same parameters on every evaluation.
**Idea:** read the parameters once per tick (or per plan) and pass them down.
**Constraints:** **CLAUDE.md-sensitive.** `MirovaCarFollowingUtil` must remain the mandatory
entry point for all acceleration calculations, and `ParameterTypes.T` must not be mutated in
tactical states. Caching parameter *reads* violates neither, but any change here must not turn
into a second path around the wrapper, and must not reintroduce parameter-hacking. Scope
carefully.

### 3. Root cause: `RelativePosition.hashCode` / `ParameterType.hashCode` — up to ~40 % of CPU
**Layer:** none — this is OTS core and DJUnits, entirely outside MiRoVA.
**Evidence:** the verbatim stack above; `Quantity.hashCode` allocating a `LinkedKeyIterator` per
call accounts for 15 % of all allocation on its own.
**Idea:** cache the hash in `RelativePosition`/`ParameterType` (both are effectively immutable),
or key the maps on identity/id instead of on DJUnits-bearing objects.
**Constraints:** **CLAUDE.md-sensitive and out of MiRoVA's scope.** This touches DJUnits/OTS
rather than MiRoVA. It is by far the highest-leverage single change and would benefit every OTS
user, but it means patching or upstreaming a change to a dependency. Candidates 1 and 2 are the
MiRoVA-side mitigations that avoid needing it.

### 4. Audit `InfrastructureContext`'s per-tick work — ~30 % inclusive CPU, 33 % of allocation
**Layer:** L1 Perception/Belief.
**Evidence:** the single largest inclusive consumer on both axes. `computeAnticipatedSpeed` and
`distanceToLaneChangeExtendedLookahead` alone are 8.8 % of the `getParameter` traffic, and the
extended lookahead is configured to 1 000 m.
**Idea:** check which lazily-computed values are genuinely consumed every tick versus computed
because something asks eagerly; the long-range lookahead in particular is expensive and may not
need re-evaluation at every tick.
**Constraints:** none from CLAUDE.md, but this is behaviour-adjacent — changing how often
anticipation is refreshed can change what vehicles do.

### 5. Shorten or intern the `ContextCategory` cache keys — ~2.6 % of CPU
**Layer:** L1 Perception/Belief.
**Evidence:** `getCachedValue` + `cacheValue` = 4.54 % of map/hash time.
**Idea:** enum or int keys instead of concatenated strings, especially for the composite keys.
**Constraints:** none. Small but cheap and low-risk.

Not worth pursuing on this evidence: the arbitration layer (0.57 %), `RelaxationState` (0.02 %),
the maneuver-pattern state machines (3.19 %). Whatever is slow, it is not the decision logic.

## Limitations — read before acting on these numbers

- **The machine was under heavy external load** during the recording (another user's job; 100 %
  CPU across 32 cores). Sampling is proportional to the CPU time the thread actually received,
  so the *relative* attribution stands, but no absolute throughput figure from this run is
  meaningful, and memory-bandwidth contention may somewhat inflate the share of the
  memory-bound hashing hotspots. **Re-measure on an idle machine before judging the size of any
  improvement.**
- Trajectory recording was **off**. Study runs enable it, and the sampler adds cost that is not
  represented here.
- A first, shorter recording was discarded: its samples fell almost entirely in the JVM startup
  and network-parsing phase rather than in steady-state simulation. Only the steady-state window
  of the second run is reported above. Anyone repeating this should check the per-minute sample
  distribution before analysing.
- 4 952 CPU samples give good confidence for the large buckets and poor confidence below ~1 %.
  Nothing in the candidate list rests on a sub-1 % figure.
