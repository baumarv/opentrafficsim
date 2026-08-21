# MiRoVA Performance Profile — 2026-08-21, full production day

> Part of the performance investigation of 2026-08-20/21. The one-page summary, the decision it led to and the open items are in [`performance_investigation_synthesis.md`](performance_investigation_synthesis.md).

Re-analysis of the 2×2 matrix recordings. Follows up on
[`performance_profile_2026-08-20.md`](performance_profile_2026-08-20.md) and should be read
against it; this report deliberately reuses its section structure so the two can be compared line
by line.

Primary subject is **cell A** — stock djunits, `LaneBasedGtu.CACHING=true` — the closest thing to
today's unpatched production behaviour. **Cell D** (patched djunits, `CACHING=false`, the best
case) is used as a cross-check wherever a finding might be an artefact of unpatched OTS.

## What's new, what's confirmed, what changed

**New — the trajectory sampler is not a cost.** The 2026-08-20 report ran with recording off and
listed that as a limitation: "Study runs enable it, and the sampler adds cost that is not
represented here." Now measured with it on, the sampler is **0.51 % of CPU inclusive, 0.06 %
self-time, 0.02 % of allocation**. It is noise. That open question is closed, and the earlier
report's numbers do not need a correction factor for it.

**New — a djutils hotspot the short recording never showed.**
`org.djutils.immutablecollections.ImmutableAbstractMap.get` accounts for **12.3 % of all map/hash
time in cell A** (≈ 5 % of CPU) and **14.5 % in cell D**, where it becomes the second-largest
single cause. It does not appear anywhere in the 2026-08-20 report.

**New — DJUnits costs money outside `hashCode` too.** `ValueUtil.expressAsUnit` is **7.17 % of
CPU in self-time** (3.01 % before) and `Duration.instantiateSI` **3.29 %** — unit conversion and
scalar construction, not hashing. The hash-caching patch does not touch either: in cell D they are
still 6.83 % and 4.58 %. DJUnits self-time is **12.55 % in A and 12.36 % in D** — essentially
unchanged by the patch.

**Changed — DJUnits hashing is far less dominant over a full day.** `DoubleScalar.hashCode` is
**12.06 % of CPU**, against 40.5 % in the earlier report. And the split between its two consumers
inverts: **8.92 % via `RelativePosition`** (the position cache) against **2.99 % via
`ParameterType`**. Earlier recordings gave 61.7 %/36.5 % and then 2.69 %/25.17 %. Section 3 shows
why: the split tracks the traffic regime, and both earlier recordings were short enough to sit
inside one.

**Changed — the overall gain from patch + `CACHING=false` is ~15 %, not ~45 %.** Cell D uses
**84.8 %** of cell A's CPU samples (451 568 vs 532 367; wall clock 5 833 s vs 6 637 s). The 45 %
measured on a 25-minute window was regime-specific and should not be quoted.

**New — the profile is now joined to the traffic that produced it.** Pairing the recording with
the trajectory sampler of the same run gives cost per simulated vehicle-tick: states differ by a
factor of **120** per invocation, and the congestion question is settled — it is volume, not
per-vehicle cost. See [Cost per simulated vehicle-tick](#cost-per-simulated-vehicle-tick).

**New — the manoeuvre state machine is not where the time goes.** Attributing each sample to the state that triggered it (see [Which State or Pattern triggers the cost](#which-state-or-pattern-triggers-the-cost)) puts the entire pattern/state machinery at **11.4 % of CPU**, and a single Layer 2 incentive, `CruisingSpeedIncentive.computeDesire`, at **41 %**.

**Confirmed, at ~100× the confidence.** MiRoVA's own code owns **2.44 %** of self-time (1.70 %
before). Layer 1 still dominates inclusive time and Layers 2–5 remain cheap. The car-following
tick cache still works: **75.0 %** of model evaluations enter through the one cached entry point
(76.4 % before, on 4 952 samples).

## What was profiled

| | 2026-08-20 | **this report** |
|---|---|---|
| Window | 13:00–14:00, 20–60 min slices | **13:00–22:00, full 9 h** |
| Date | 2025-10-13 | 2025-10-07 |
| Trajectory recording | **off** | **on** (as production studies run it) |
| CPU samples analysed | 4 952 | **532 367** (A), 451 568 (D) |
| Allocation samples | 11 752 | **105 149** (A), 98 319 (D) |
| Wall clock | ~2 min | **6 637 s** (A), 5 833 s (D) |
| Machine | contended, then idle workstation | cluster, **non-exclusive** node |
| Noise threshold | ~1 % | **~0.1 %** |

Parameters are the study baseline composed exactly as `ScenarioManager.prepareRun` composes a real
run. The node was **not** exclusive: neighbour noise leaves attribution within a cell intact
(sampling is proportional to CPU actually received) but makes wall-clock and sample counts between
cells only indicative.

## CPU hotspots mapped to the architecture

### Who owns the innermost frame (self time)

```
                        cell A          cell D        2026-08-20
   JDK                  56.75%          47.90%          71.47%
   generic OTS          21.15%          30.68%          14.32%
   DJUnits              12.55%          12.36%           8.02%
   djutils               7.02%           5.18%           4.36%
   MiRoVA own code       2.44%           3.68%           1.70%
   trajectory sampler    0.06%           0.06%           (not present)
   DSOL                  0.03%           0.14%           0.14%
```

The conclusion of the original report survives its own sample size increasing a hundredfold:
**MiRoVA's own code is not where the time goes.** What shifted is the balance between the JDK and
OTS — with recording on and a full day, more time sits in OTS's own geometry and perception code
and proportionally less in raw collection work.

### Which layer the stack is under (inclusive), cell A

```
   32.85%  L1 Perception/Belief: InfrastructureContext
   27.32%  L1 Perception/Belief: NeighborsContext
   10.24%  L2 Cognition: DesireLayer
    7.18%  MiRoVA other
    6.57%  MirovaTacticalPlanner (loop)
    3.81%  L5 Reactive: car-following
    3.40%  L4 Intention: ManeuverPatterns
    3.03%  L1 Perception/Belief: ContextCategory
    1.91%  L1 Perception/Belief: VehicleContextManager
    1.57%  L1 Perception/Belief: EgoContext
    1.35%  L1 Perception/Belief: MacroTrafficContext
    0.44%  Trajectory sampler (measurement, not model)
    0.34%  L3 Decision: ArbitrationLayer
```

Layer 1 is **68 %** of inclusive time, up from 60 %. Arbitration is 0.34 %, and at this sample size
that is a real number rather than a rounding artefact: the decision logic genuinely costs nothing.
Read together with the ownership table — Layer 1 dominates while MiRoVA owns 2.44 % of self-time —
the reading is unchanged and now well beyond doubt: **Layer 1 is expensive because of what it asks
OTS for, not because of what it computes.**

### Top self frames, cell A

```
   25.76%  java.util.HashMap.getNode
    7.17%  org.djunits.value.util.ValueUtil.expressAsUnit
    6.82%  org.opentrafficsim.road.gtu.lane.LaneBasedGtu.position
    5.58%  java.util.AbstractSet.hashCode
    5.13%  java.lang.String.hashCode
    4.95%  org.djutils.multikeymap.MultiKeyMap.getFinalMap
    3.72%  java.util.HashMap.putVal
    3.29%  org.djunits.value.vdouble.scalar.Duration.instantiateSI
    2.86%  java.util.HashMap.put
    2.56%  org.opentrafficsim.core.gtu.perception.AbstractPerceptionCategory.computeIfAbsent
    2.10%  java.util.HashMap.computeIfAbsent
    2.07%  org.opentrafficsim.base.geometry.OtsLine2d.projectFractional
    0.98%  org.djunits.value.vdouble.scalar.base.DoubleScalar.hashCode
```

Map and hash operations total **41.12 %** of self-time (57.9 % in the earlier contended profile).
Attributing each such sample to its nearest non-JDK caller is where the picture has genuinely
changed:

```
   13.77%  org.opentrafficsim.base.parameters.ParameterSet.getParameter
   12.84%  org.djutils.multikeymap.MultiKeyMap.getSubMap
   12.32%  org.djutils.immutablecollections.ImmutableAbstractMap.get     <- new
    9.85%  org.djunits.quantity.Quantity.hashCode
    8.85%  org.djutils.multikeymap.MultiKeyMap.getValue
    5.91%  …mirova…BeliefLayer.ContextCategory.cacheValue
    4.88%  org.opentrafficsim.core.gtu.perception.AbstractPerceptionCategory.contextualKey
    4.25%  org.opentrafficsim.core.gtu.perception.AbstractPerceptionCategory.computeIfAbsent
    2.44%  org.djunits.unit.Unit.hashCode
```

In the earlier report `Quantity.hashCode` alone accounted for 56.5 % of this traffic. Here it is
9.85 %, and the load is spread across four mechanisms: OTS parameter lookup, djutils `MultiKeyMap`,
djutils `ImmutableAbstractMap`, and OTS's own perception cache keying (`contextualKey` +
`computeIfAbsent` ≈ 9 %). **The single-cause story from the short recording does not survive a
full day.**

### What remains after the patch — cell D

```
   24.22%  java.util.HashMap.getNode
    7.39%  org.opentrafficsim.core.gtu.plan.operational.OperationalPlan.getLocation
    6.83%  org.djunits.value.util.ValueUtil.expressAsUnit
    4.58%  org.djunits.value.vdouble.scalar.Duration.instantiateSI
    3.45%  org.opentrafficsim.base.geometry.OtsLine2d.projectFractional
```

`DoubleScalar.hashCode` collapses to **0.71 %**, and its `RelativePosition` half to **0.00 %** —
expected, since `CACHING=false` removes the position cache entirely and the patch handles the rest.
But map/hash is still **35.53 %** of CPU, now led by `ParameterSet.getParameter` (19.2 %) and
`ImmutableAbstractMap.get` (14.5 %). **Neither the patch nor disabling the position cache addresses
most of the map traffic in a production run.**

## Allocation

Kept separate from CPU, as before. Cell A: **1.33 TB sampled** over 6 637 s.

```
   -- by owner --                    -- top types --
   57.54%  JDK                       11.01%  java.lang.Object[]
   23.02%  generic OTS               10.30%  java.util.LinkedHashMap$LinkedKeyIterator
   12.01%  DJUnits                    7.55%  java.util.LinkedHashMap
    6.47%  djutils                    6.50%  java.util.LinkedHashMap$Entry
    0.94%  MiRoVA own code            6.43%  java.util.AbstractList$RandomAccessSubList
    0.02%  trajectory sampler         6.39%  java.util.HashMap$Node[]
                                      5.56%  org.djunits.value.vdouble.scalar.Length
                                      4.37%  org.djutils.draw.point.Point2d
```

By layer: `InfrastructureContext` 35.07 %, `NeighborsContext` 28.68 %, `DesireLayer` 9.56 %,
sampler 0.02 %.

`LinkedKeyIterator` — the iterator allocated inside `Quantity.hashCode` — is **10.30 % in A and
4.81 % in D**, a 53 % reduction from the patch. That matches what
[`djunits_patch_experiment.md`](djunits_patch_experiment.md) measured on the short window (56 %) and
confirms its correction of the original prediction: the patch removes about half of that churn, not
most of it, because `Unit.hashCode` is deliberately left uncached.

## The picture over the course of the day

Split into six equal **wall-clock** slices. These are *not* equal slices of simulated time: a
congested period simulates far more slowly, so a late slice covers less of the day than an early
one. The congested phase is identified from the model side, by the share of samples executing
congestion-handling states (`CongestedMergeState`, `CongestedCreepState`,
`CongestedFollowLeaderState`, `EmergencyStopState`).

| slice | samples | sampler | `DoubleScalar.hashCode` | map/hash | congestion states |
|---|---|---|---|---|---|
| #0 | 79 944 | 0.56 % | 9.04 % | 40.98 % | 0.103 % |
| #1 | 88 061 | 0.58 % | 9.57 % | 39.69 % | 0.193 % |
| #2 | 91 191 | 0.32 % | 10.59 % | 37.47 % | 0.166 % |
| **#3** | 90 808 | 0.43 % | **16.43 %** | 42.84 % | **0.399 %** |
| **#4** | 91 109 | 0.38 % | **15.82 %** | 42.68 % | 0.166 % |
| #5 | 91 254 | 0.77 % | 10.47 % | 42.99 % | 0.110 % |

**The DJUnits hashing share is regime-dependent: ~9–10 % in free flow, ~16 % under congestion — a
70 % increase.** The congestion indicator peaks in the same slice where it starts. That is the
mechanism one would expect: denser traffic means more perceived neighbours per vehicle, hence more
position lookups, hence more hashing.

This explains the variation the earlier work could not account for. The 61.7 % and 2.69 % figures
from two short recordings were not contradictory measurements of one quantity — they were samples
of a quantity that genuinely moves with the traffic state, taken from different parts of it. **A
recording shorter than the congestion cycle cannot produce a representative figure for this
hotspot**, which is the strongest methodological argument in this report for profiling full-length
runs.

The sampler share stays within 0.32–0.77 % throughout: it does not become expensive under load
either.

## Caching-effectiveness check

**Car-following tick cache — effective, now beyond doubt.** Samples inside a car-following model:
18 277, **3.43 % of CPU**. Entry points:

```
   74.99%  …BeliefLayer.EgoContext.getCurrentCarFollowingAcceleration   <- the cached path
   11.46%  …BeliefLayer.InfrastructureContext.computeAnticipatedSpeed
    4.19%  …DesireLayer.RouteIncentive.computeDesire
    2.45%  …MandatoryLaneChangePattern$AnticipateMergeState.next
    1.51%  MirovaTacticalPlanner.generateOperationalPlan
```

75.0 % against 76.4 % on 4 952 samples — the earlier finding was right, and it now rests on 18 277
model evaluations rather than 390. The remainder are genuinely different queries (anticipated
speed, hypothetical leaders), not repeats. No bypass.

**`ContextCategory` cache — 3.03 % of CPU** (2.65 % before, and 3.74 % in cell D, where it is a
larger share of a smaller total). Still real, still an order of magnitude below the map traffic
around it. The key-shortening change measured as unmeasurable on the short window; at this sample
size it would be resolvable, but cells A and D were both recorded *after* that change, so this run
cannot separate it.

## Tuning candidates, revised

Ordered by what a full production day actually shows. Percentages are cell A unless stated.

1. **`ParameterSet.getParameter` — 13.8 % of map/hash in A, 19.2 % in D.** Now the largest single
   cause, and the one the djunits patch leaves almost untouched. This is candidate 2 from the
   original report and it has only become more clearly the priority.
2. **`djutils ImmutableAbstractMap.get` — 12.3 % of map/hash in A, 14.5 % in D (≈ 5 % of CPU).**
   Entirely new; invisible in the short recording. Worth finding out what is being looked up in an
   immutable map often enough to cost this.
3. **OTS perception cache keying — `contextualKey` + `computeIfAbsent` ≈ 9 % of map/hash.** OTS
   core, not MiRoVA.
4. **DJUnits unit conversion and construction — `expressAsUnit` 7.17 %, `Duration.instantiateSI`
   3.29 %.** Separate from the hashing question and unaffected by the patch. Worth raising at Delft
   alongside the `hashCode` finding, since it is the same library and the same shape of problem.
5. **The position cache — 8.92 % of CPU via `RelativePosition` in A, 0.00 % in D.** Confirms that
   disabling it works, but see `djunits_patch_experiment.md`: the two interact, and the ~15 %
   overall gain here is the *combined* effect, not the position cache's alone.

Not worth pursuing: the trajectory sampler (0.51 %), arbitration (0.34 %), MiRoVA's own code
(2.44 % of self-time across the entire architecture).

## Limitations

- **Non-exclusive node.** Within-cell attribution holds; the A-vs-D wall-clock and sample-count
  comparison is indicative only, since the neighbours may have differed between the two runs.
- **Cells B and C were not analysed** — only `A.jfr` and `D.jfr` were available here. B (stock,
  `CACHING=false`) would separate the position cache's own contribution from the patch's.
- **The day-phase split is by wall clock, not simulated time.** No timestamped progress signal was
  emitted, so the slices in this part of the report are wall-clock sixths and the congestion phase
  is identified from model-side behaviour. [Cost per simulated vehicle-tick]
  (#cost-per-simulated-vehicle-tick) removes this limitation for the questions it answers, by
  reconstructing the mapping from the trajectory sampler's vehicle-tick counts. Emitting
  `[Progress] <wall> t=<sim>` from `RunProfileMatrix` would still be worth doing: it would make the
  mapping measured rather than reconstructed.
- One run per cell, so the A-vs-D difference has no error bar. The composition figures, resting on
  hundreds of thousands of samples each, are a different matter.

---

# Which State or Pattern triggers the cost

Every breakdown so far grouped cost either by architecture layer or by the immediate caller of one
hotspot. Neither answers "which manoeuvre state was in control when this time was spent". This
section attributes each sample to the state that triggered it, counting the whole sample against
that state regardless of how far down the stack the work actually happened.

Cell A, 532 367 samples. Cell D was not re-analysed this way; the findings below are structural
rather than patch-dependent, but that is an assumption, not a measurement.

## The attribution rule

Fixed before running, and reproducible:

> A frame is a **state frame** when its class lies under
> `org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.` and not under `.old.`.
>
> The two abstract base classes of that package, `ManeuverPattern` and `ActionState`, are
> recognised but never used as an attribution target. They are dispatch scaffolding —
> `ManeuverPattern.update` calls the current state's `update` — so attributing to them would say
> "some pattern was running" rather than which one. Samples whose only state frames are those
> bases are counted separately as **dispatch only**.
>
> A sample is attributed to the **outermost** concrete state frame, the one closest to the
> simulation loop. Samples with no state frame at all are bucketed as **no active state**.

Nested action states appear in stacks as `Outer$Inner`, so
`MandatoryLaneChangePattern$AnticipateMergeState` is distinguished from its siblings.

Two checks on the rule itself: **2.33 %** of samples had more than one distinct concrete state on
the stack (a pattern reaching into another, or a mid-transition frame), so the outermost-wins
tie-break decides a small enough minority not to affect the ranking. **Dispatch-only** samples are
**0.02 %** — the base classes almost never appear without a concrete state above them.

## The headline: the state machinery is not where the time goes

```
   88.61%  (no active state)
    2.52%  AnticipateDownstreamMergePattern$NearAnticipationState
    2.43%  GapOpenerPattern
    1.60%  SimpleLaneChangePattern
    1.30%  MandatoryLaneChangePattern$AnticipateMergeState
    1.08%  PreventUndercuttingPattern
    0.76%  MandatoryLaneChangePattern            (checkContext / checkAbility during selection)
    0.73%  AnticipateDownstreamMergePattern      (same)
    0.28%  GapOpenerPattern$OpenGapState
    0.10%  PreventUndercuttingPattern$ShadowingState
    0.10%  MandatoryLaneChangePattern$CongestedCreepState
    0.10%  PreventUndercuttingPattern$PrepareLaneChangeState
    0.08%  SimpleLaneChangePattern$PerformLaneChangeState
    0.08%  MandatoryLaneChangePattern$CongestedFollowLeaderState
    0.07%  MandatoryLaneChangePattern$ExecuteLaneChangeState
    0.04%  MandatoryLaneChangePattern$SynchroniseMergeSpeedState
    0.03%  MandatoryLaneChangePattern$MatchLeaderSpeedState
    0.03%  MandatoryLaneChangePattern$SolveParallelVehicleState
    0.02%  (dispatch only)
    0.01%  MandatoryLaneChangePattern$EmergencyStopState
```

**Everything the pattern and state machinery triggers adds up to about 11.4 % of CPU.** The
merge FSM that most of this project's engineering effort has gone into — all nine states of
`MandatoryLaneChangePattern` together — triggers **2.5 %**.

## Selection versus execution

A pattern costs something even when none of its states is running: `PatternSelector`
`.getAllRelevantPatterns` calls `isRunning`, `checkContext` and `checkAbility` on **every**
pattern for **every** vehicle in **every** tick. Those frames are attributed like any other — a
bare pattern name in the ranking above, with no `$State` suffix, is selection cost, not execution.
Splitting the same samples by which phase of `MirovaTacticalPlanner.update` they sit in:

```
   88.24%  no pattern frame at all
    6.77%  selection        (PatternSelector.getAllRelevantPatterns on the stack)
    4.98%  arbitration / execution (HybridPlanArbitrator.arbitrate on the stack)
    0.01%  pattern frame in neither phase
```

**Selection is the larger half of the machinery: 6.8 % against 5.0 % for actually running the
manoeuvres.** Broken down, it is almost purely the two checks — the *of which* column below is the
share sitting inside `isRunning`/`checkContext`/`checkAbility` themselves:

| pattern | selection cost | of which inside the checks |
|---|---|---|
| `GapOpenerPattern` | 2.43 % | 2.43 % |
| `SimpleLaneChangePattern` | 1.60 % | 1.60 % |
| `PreventUndercuttingPattern` | 1.08 % | 1.08 % |
| `MandatoryLaneChangePattern` | 0.75 % | 0.74 % |
| `AnticipateDownstreamMergePattern` | 0.73 % | 0.73 % |
| `PatternSelector` itself (loop, list building) | 0.18 % | — |

So `GapOpenerPattern`'s 2.43 % in the ranking is **not** the pattern opening gaps; it is
`GapOpenerPattern.checkContext` deciding whether it should. The same holds for
`SimpleLaneChangePattern` and `PreventUndercuttingPattern`. Only 4.98 % of total CPU is a state
machine actually executing, and `NearAnticipationState` (2.52 %) plus `AnticipateMergeState`
(1.30 %) are three quarters of that.

This is the cheapest thing in the whole profile to improve, because it is pure overhead per
vehicle-tick and independent of what the vehicle is doing. The mechanism table below shows what
the checks spend their time on: perception iteration, `LaneBasedGtu.position`, parameter lookup
and DJUnits hashing — in other words, they are expensive for exactly the same reasons everything
else is, and the fix is the same one: ask perception for less, and ask for it once.

## What the other 88.6 % is

The unattributed block is not background noise. Taking, for each such sample, the outermost frame
below `MirovaTacticalPlanner` (the first thing the planner reached for that tick):

| first call of the tactical planner | of the block | **of total CPU** |
|---|---|---|
| `DesireLayer.CruisingSpeedIncentive.computeDesire` | 46.45 % | **41.16 %** |
| `BeliefLayer.VehicleContextManager.updateFromPerception` | 28.81 % | **25.53 %** |
| (outside MiRoVA — OTS plan building, sampler I/O, network) | 14.12 % | 12.52 % |
| `DesireLayer.RouteIncentive.computeDesire` | 5.58 % | 4.95 % |
| `DesireLayer.KeepRightIncentive.computeDesire` | 1.62 % | 1.44 % |
| `BeliefLayer.VehicleContextManager.advanceTick` | 1.08 % | 0.96 % |
| `DesireLayer.ProhibitDeadEndIncentive.isApplicable` | 1.01 % | 0.89 % |
| `util.DeadlockDiffusionWatchdog.check` | 0.43 % | 0.38 % |
| `ArbitrationLayer.HybridPlanArbitrator.arbitrate` | 0.22 % | 0.20 % |
| `ArbitrationLayer.PatternSelector.getAllRelevantPatterns` | 0.21 % | 0.18 % |

**One incentive — `CruisingSpeedIncentive.computeDesire` — triggers 41 % of the entire
simulation's CPU.** It is the single largest consumer in the model by a wide margin, and it is a
Layer 2 desire computation, not a manoeuvre.

The mechanism is visible in its source: it calls `infrastructureContext.getAnticipatedSpeed()` for
`CURRENT`, `LEFT` and `RIGHT`, and `computeAnticipatedSpeed` iterates the full leader iterable
returned by `NeighborsContext.getLeaders(dir)` — the expensive OTS lane-structure walk that
positions every GTU it passes. It also queries `getLegalLaneChangePossibility` twice. Three
perception directions, every vehicle, every tick.

### An important caveat before anyone tunes this

The per-tick context caches mean **whoever touches perception first pays for populating them**, and
everyone afterwards reads cheaply. `CruisingSpeedIncentive` runs early in `update()`, so a large
part of its 41 % is cache-population cost that the patterns and the other incentives would
otherwise have paid themselves. Making this incentive cheaper would therefore move most of the cost
to the next consumer rather than remove it. What *would* remove it is asking perception for less —
fewer directions, a shorter horizon, or not recomputing anticipated speed for lanes whose desire
contribution is then discarded.

This also reconciles the two views that look contradictory at first glance. The layer breakdown puts
`InfrastructureContext` at 32.85 % and `NeighborsContext` at 27.32 % — that is *where the work
happens*. This table says `CruisingSpeedIncentive` at 41 % — that is *who asked for it*. Both are
inclusive attributions; they differ only in whether the innermost or the outermost frame decides.

## Free flow versus congestion

The congested part of the recording is identified from the profile itself: the share of samples
with a congestion-handling state anywhere on the stack, per wall-clock sixth. Only slice #3 stands
out — it carries more than twice the share of any other, while #1, #2 and #4 are indistinguishable
from each other.

```
  slice #0 :  79944 samples, 0.103% congestion-handling
  slice #1 :  88061 samples, 0.193% congestion-handling
  slice #2 :  91191 samples, 0.166% congestion-handling
  slice #3 :  90808 samples, 0.399% congestion-handling  <-- congested
  slice #4 :  91109 samples, 0.166% congestion-handling
  slice #5 :  91254 samples, 0.110% congestion-handling
```

Slice #3 is therefore the congested side and the other five the free-flowing one.

| State / Pattern | free flow | congested | factor |
|---|---|---|---|
| `AnticipateDownstreamMergePattern$NearAnticipationState` | 2.710 % | 1.617 % | **0.6×** |
| `GapOpenerPattern` | 2.302 % | 3.071 % | **1.3×** |
| `SimpleLaneChangePattern` | 1.514 % | 2.015 % | 1.3× |
| `MandatoryLaneChangePattern$AnticipateMergeState` | 1.240 % | 1.613 % | **1.3×** |
| `PreventUndercuttingPattern` | 1.148 % | 0.769 % | 0.7× |
| `MandatoryLaneChangePattern` (selection) | 0.748 % | 0.792 % | 1.1× |
| `AnticipateDownstreamMergePattern` (selection) | 0.742 % | 0.687 % | 0.9× |
| `GapOpenerPattern$OpenGapState` | 0.269 % | 0.333 % | 1.2× |
| `(no active state)` | 88.678 % | 88.303 % | 1.0× |

Below this the per-regime counts fall under a few hundred samples and the ratios stop being
meaningful. Reported for completeness but **not to be relied on**:
`MandatoryLaneChangePattern$CongestedCreepState` (4.3×), `$CongestedFollowLeaderState` (1.6×),
`$ExecuteLaneChangeState` (1.2×), `$SynchroniseMergeSpeedState` (0.8×), `$MatchLeaderSpeedState`
(0.4×), `$EmergencyStopState` (0.3×), `PreventUndercuttingPattern$ShadowingState` (0.9×),
`$PrepareLaneChangeState` (0.4×), `SimpleLaneChangePattern$PerformLaneChangeState` (0.7×).

**The architecture's premise shows up in the data.** The states that cost more under congestion
are exactly the ones that are supposed to: `GapOpenerPattern`, `AnticipateMergeState` and
`SimpleLaneChangePattern` at 1.3× each, `CongestedCreepState` far higher but on too few samples to
quote. The states that cost *less* are the anticipatory ones — `NearAnticipationState` (0.6×) and
`PreventUndercuttingPattern` (0.7×) — which is consistent with them being about *avoiding* a
situation that has, by then, already arrived.

So the explicit manoeuvre planning does concentrate its effort where a reactive model would
struggle. It simply does so at a total cost of a few percent, against 41 % for one speed-desire
computation.

### Does the cost mix move over the run?

The composition of the tactical layer barely moves across the day:

```
   slice      samples   selection   arbitration   any pattern frame
     #0        79 944      5.64%        6.52%           11.75%
     #1        88 061      5.95%        5.27%           10.84%
     #2        91 191      6.63%        3.93%           10.29%
     #3        90 808      7.48%        4.57%           11.70%   <- congested
     #4        91 109      8.11%        4.78%           12.53%
     #5        91 254      6.65%        5.03%           11.25%
```

The share of CPU spent inside the pattern machinery stays within 10.3–12.5 % everywhere, and the
congested slice is not the maximum of either column. What shifts is the balance *within* it:
selection climbs steadily, execution falls. More vehicles on the network means more patterns to
check per tick, and each check has more neighbours to look at — but no single manoeuvre becomes
dramatically more expensive to run.

JFR alone cannot finish the argument: every slice is equal in wall-clock time, so a congested
slice covers fewer simulated seconds and the vehicle-ticks behind it are invisible. The next
section pairs the recording with the trajectory output of the same run and settles it.

## What each state was triggering

Share of that state's own samples containing the mechanism; they overlap, since one stack can pass
through several.

| State | dominant mechanisms |
|---|---|
| `AnticipateDownstreamMergePattern$NearAnticipationState` (2.52 %) | `LaneBasedGtu.position` 43.1 %, perception iterables 12.6 %, `getParameter` 11.9 % |
| `GapOpenerPattern` (2.43 %) | perception iterables 55.6 %, `LaneBasedGtu.position` 36.7 %, `DoubleScalar.hashCode` 14.9 % |
| `SimpleLaneChangePattern` (1.60 %) | perception iterables 38.8 %, `position` 30.2 %, `ContextCategory` cache 22.6 %, `getParameter` 19.0 % |
| `MandatoryLaneChangePattern$AnticipateMergeState` (1.30 %) | perception iterables 32.0 %, `position` 23.2 %, `DoubleScalar.hashCode` 18.4 % |
| `PreventUndercuttingPattern` (1.08 %) | `ContextCategory` cache 25.3 %, `getParameter` 24.3 %, perception iterables 14.0 % |
| `MandatoryLaneChangePattern` (0.76 %, selection) | `DoubleScalar.hashCode` 34.3 %, `getParameter` 30.6 % |

Read the bare pattern rows as selection cost, per *Selection versus execution* above.
`GapOpenerPattern.checkContext` is over half perception iteration — it is the most
perception-hungry admission check in the model, and it runs on every vehicle in every tick
whether or not a gap is ever opened. `MandatoryLaneChangePattern`'s check is the opposite shape:
almost entirely DJUnits hashing and parameter lookup, 65 % of it in the two library mechanisms
rather than in the pattern's own logic.

## Limitations

- Cell D was not analysed this way, so it is unconfirmed whether the ranking shifts once the DJUnits
  patch removes the hashing underneath it. The mechanism shares would certainly change; the
  ordering probably would not, since it is driven by perception volume rather than by hashing.
- The regime split inherits the wall-clock caveat from the full-day report: slices are equal in wall
  time, not in simulated time. The section that follows resolves this, at the price of assuming
  constant cost per vehicle-tick — an assumption it then tests.
- Attribution charges the whole sample to the outermost state. That is the intended semantics — "who
  was in control" — but it means a state is credited with cost it did not itself write, including
  cache population it happened to trigger first.

---

# Cost per simulated vehicle-tick

Everything above measures how CPU is *distributed*. It cannot distinguish an expensive state from
a frequent one, and it cannot separate "congestion costs more per vehicle" from "congestion has
more vehicles". The trajectory sampler of the same run supplies what is missing: one row per
vehicle per tick, in simulated time, carrying the active `ActionState`. That is precisely the unit
of work the CPU was spent on.

| | |
|---|---|
| CPU samples | 532 367 |
| JVM CPU time, integrated from `jdk.CPULoad` | **6 544 s** → 12.29 ms per sample |
| Vehicle-ticks recorded | 1 825 644, dt = **0.20 s** |
| Links covered by the sampler | `L4a` only (the merge link) |
| Ticks with any state active | **45.5 %** |

Two measurement notes. CPU time is integrated from `jdk.CPULoad` rather than assumed from the
nominal 10 ms sampling period — the JFR sampler falls behind under load, and the real figure is
12.29 ms per sample, so assuming 10 would understate every cost below by a fifth. And the sampler
covers only `L4a`, while the profile covers the whole network: absolute `ms/tick` figures are
therefore upper bounds. That caveat scales every row by the same factor and leaves the ordering
intact.

## Which states are slow, and which are merely frequent

Execution-phase CPU only, which is the like-for-like counterpart of occupancy — the sampler
records a state as active when it *runs*, not when it is being checked for admission.

| state | CPU | vehicle-ticks | ms per tick |
|---|---|---|---|
| `PreventUndercutting:Shadowing` ⚠ | 0.104 % | 0.033 % | 11.140 |
| `NearAnticipationState` | 2.523 % | 1.880 % | **4.811** |
| `PreventUndercutting:PrepareLaneChange` ⚠ | 0.097 % | 0.142 % | 2.451 |
| `AnticipateMergeState` | 1.304 % | 2.027 % | **2.305** |
| `MatchLeaderSpeedState` | 0.031 % | 0.479 % | 0.235 |
| `PerformLaneChangeState` | 0.080 % | 1.732 % | 0.165 |
| `SynchroniseMergeSpeedState` | 0.036 % | 1.030 % | 0.127 |
| `OpenGapState` | 0.277 % | 14.592 % | **0.068** |
| `SolveParallelVehicleState` | 0.031 % | 2.099 % | 0.053 |
| `ExecuteLaneChange` | 0.072 % | 5.025 % | 0.051 |
| `CongestedFollowLeaderState` | 0.077 % | 6.427 % | 0.043 |
| `CongestedCreepState` | 0.103 % | 9.156 % | **0.040** |
| `EmergencyStopState` | 0.009 % | 0.910 % | 0.036 |
| _every vehicle-tick, all CPU included_ | 100 % | 100 % | 3.585 |

⚠ fewer than 2 000 ticks observed; the figure is noise.

**Per invocation the states differ by a factor of 120.** The two anticipation states are the
expensive ones — `NearAnticipationState` at 4.8 ms costs *more than an entire average
vehicle-tick* (3.6 ms) on top of it, and `AnticipateMergeState` adds 2.3 ms. Everything else is
between 0.04 and 0.24 ms, i.e. free by comparison.

This inverts the reading of the ranking further up. `OpenGapState` looked like a mid-table entry
at 0.28 % of CPU; it is in fact **the most frequently active state in the entire model** — 14.6 %
of all vehicle-ticks — and it is cheap. The congestion states are the same story: `CongestedCreep`
and `CongestedFollowLeader` together occupy **15.6 % of vehicle-ticks** and cost 0.18 % of CPU
between them.

That the anticipation states are the expensive ones is not a surprise once stated: they are the
ones that look far ahead. `NearAnticipationState`'s mechanism profile is 43 % `LaneBasedGtu
.position`, and the long-range anticipation deliberately extends the lookahead to sample speeds at
a downstream bottleneck. It is paying for distance.

## Is congestion more expensive per vehicle, or just more vehicles?

The wall-clock slices are mapped onto simulated time by giving each an equal share of
vehicle-ticks — which *is* the constant-cost hypothesis. If it holds, the congestion in the
simulated window and the CPU carrying a congestion state rise and fall together and their ratio
stays flat. A ratio climbing with occupancy would mean congestion genuinely costs more per
vehicle.

| slice | simulated window [h] | congestion occupancy | CPU with a congestion state | ratio |
|---|---|---|---|---|
| #0 | 0.00 – 1.13 | 7.34 % | 0.117 % | 0.0159 |
| #1 | 1.13 – 2.05 | 10.60 % | 0.090 % | 0.0085 |
| #2 | 2.05 – 2.63 | 15.77 % | 0.201 % | 0.0127 |
| #3 | 2.63 – 2.92 | 14.64 % | 0.186 % | 0.0127 |
| #4 | 2.92 – 3.20 | 22.46 % | 0.169 % | 0.0075 |
| #5 | 3.20 – 3.53 | 17.00 % | 0.162 % | 0.0096 |
| #6 | 3.53 – 3.78 | 35.90 % | 0.357 % | 0.0100 |
| #7 | 3.78 – 4.17 | 20.10 % | 0.440 % | 0.0219 |
| #8 | 4.17 – 4.48 | 24.27 % | 0.222 % | 0.0092 |
| #9 | 4.48 – 4.82 | 16.62 % | 0.110 % | 0.0066 |
| #10 | 4.82 – 5.73 | 9.21 % | 0.170 % | 0.0184 |
| #11 | 5.73 – 8.98 | 2.75 % | 0.050 % | 0.0183 |

The ratio scatters between 0.007 and 0.022 with **no trend against occupancy** — the two most
congested windows (#6 at 35.9 %, #8 at 24.3 %) sit at 0.010 and 0.009, below the free-flowing #11
at 0.018. The scatter is sampling noise: the congestion-state CPU counts are 20–200 samples per
slice. What matters is that the mapping, built on the assumption of a constant cost per tick,
places the CPU congestion peak (#6–#7) inside the simulated congestion peak. Had per-vehicle cost
exploded under congestion, the mapping would be stretched and the peaks displaced.

**So the answer is volume.** The load itself swings by an order of magnitude:

```
   hour     ticks   mean concurrent vehicles   mean speed [km/h]
     0     132561                    7.4              65.4
     1     148606                    8.3              59.9
     2     368443                   20.5              28.4
     3     502630                   27.9              22.5     <- peak
     4     418203                   23.2              28.4
     5     120639                    6.7              63.2
     6      58675                    3.3              92.3
     7      44841                    2.5              98.2
     8      31046                    1.7              99.2     <- 16x fewer than the peak
```

Hour 3 carries **16 times** the vehicle-ticks of hour 8 on the same stretch of road, at a quarter
of the speed. That, and not a per-vehicle cost explosion, is where the CPU goes. If anything the
mix shifts *towards* the cheap end under congestion: the states that dominate a jam are the
cheapest ones in the model, and the expensive anticipation states are the ones that run in free
flow.

## What this means for optimisation

- **Do not optimise the congestion states.** They occupy 15.6 % of vehicle-ticks and 0.18 % of
  CPU. There is nothing there.
- **`NearAnticipationState` and `AnticipateMergeState` are the only states worth touching**, and
  the reason they cost is lookahead distance, not the FSM. Shortening the horizon, or sampling it
  less often than every tick, is the lever.
- **The real target is still not a state.** 88 % of CPU runs with no state active at all, and
  6.8 % is admission checking. A vehicle-tick costs ~3.6 ms of which the manoeuvre being executed
  accounts for a few hundredths, except during anticipation.

## Limitations of the vehicle-tick analysis

- **The sampler covers `L4a` only.** Occupancy is merge-link occupancy while CPU is network-wide,
  so every `ms/tick` figure is an upper bound. The factor is common to all rows, so the ordering
  and the ratios between states survive; the absolute values do not. Registering more space-time
  regions with the sampler would fix this, at the price of a larger trajectory file.
- **Names are matched between two vocabularies.** The sampler writes `PerformLaneChangeState`, the
  profiler `SimpleLaneChangePattern$PerformLaneChangeState`; the join matches on the bare name. A
  state name reused by two patterns would be merged silently.
- **The congestion consistency check rests on 20–200 samples per slice.** It is strong enough to
  exclude a large per-vehicle cost increase under congestion, not to measure a small one.
- **One run.** Cells B, C and D were not analysed this way, though all four produced byte-identical
  trajectory output, so the occupancy side would be unchanged by construction.
