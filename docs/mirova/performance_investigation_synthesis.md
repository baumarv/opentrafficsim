# MiRoVA Performance Investigation — Synthesis and Decision

One-page summary of the performance investigation of 2026-08-20/21, the decision it led to, and
what remains open. The four detailed reports behind it:

| Document | What it establishes |
|---|---|
| [`performance_profile_2026-08-20.md`](performance_profile_2026-08-20.md) | First architecture-aware profile, 25-minute window. MiRoVA's own code owns 1.70 % of CPU self-time. |
| [`djunits_hashcode_finding.md`](djunits_hashcode_finding.md) | Why DJUnits scalars are expensive as map keys, verified against the DJUnits 5.2.1 source. |
| [`djunits_patch_experiment.md`](djunits_patch_experiment.md) | A patched DJUnits that caches those hashes, built and measured in a 2×2 matrix against the position cache. |
| [`performance_profile_2026-08-21_full_day.md`](performance_profile_2026-08-21_full_day.md) | The same analysis over a full 9-hour production day, plus State/Pattern attribution and cost per simulated vehicle-tick. |

## The finding in one paragraph

**MiRoVA's own code was never the bottleneck.** It owns **1.7 % – 2.4 %** of CPU self-time,
consistently, from the first 5 000-sample recording to the 532 367-sample production day. Neither
is the state machine: the entire pattern and state machinery accounts for **11.4 %** of CPU, and
**more than half of that is admission checking** (`isRunning`/`checkContext`/`checkAbility` on every
pattern for every vehicle in every tick — 6.8 %) rather than executing a manoeuvre (5.0 %). The
cost sits in what MiRoVA *asks OTS for*: perception iteration, `LaneBasedGtu.position`, DJUnits
scalar hashing and parameter lookup.

## What the layers actually cost

- **Largest single trigger: `CruisingSpeedIncentive.computeDesire`, 41 % of CPU.** It asks
  `InfrastructureContext.getAnticipatedSpeed()` for CURRENT, LEFT and RIGHT, each of which walks the
  full leader iterable, plus `getLegalLaneChangePossibility` twice — three directions, every
  vehicle, every tick. **Caveat that matters for anyone tempted to optimise it:** the per-tick
  context caches mean whoever touches perception first pays to populate them. Most of that 41 %
  would move to the next consumer rather than disappear. What removes it is asking perception for
  *less* — fewer directions, a shorter horizon, or not recomputing anticipated speed for lanes
  whose desire contribution is discarded anyway.
- **Only two states are genuinely expensive per invocation:** `NearAnticipationState` at 4.8 ms per
  vehicle-tick and `AnticipateMergeState` at 2.3 ms, against 0.04 – 0.24 ms for every other state.
  The reason is lookahead distance, not FSM overhead — long-range anticipation extends the horizon
  to sample speeds at a downstream bottleneck, and pays for the distance.
- **The congestion states are the cheapest in the model.** `CongestedCreepState` and
  `CongestedFollowLeaderState` occupy **15.6 % of all vehicle-ticks** for **0.18 % of CPU**.
  `OpenGapState` is the most frequently active state of all (14.6 % of ticks) and costs 0.068 ms
  per tick.
- **Congestion is a volume effect, not a per-vehicle cost increase.** Tested rather than assumed,
  by joining the profile to the same run's trajectory output: the peak hour carries **16×** the
  vehicle-ticks of the quiet hour on the same stretch, while the ratio of congestion CPU to
  congestion occupancy shows no trend across the day.

## What still costs, after both fixes

1. **`ParameterSet.getParameter` — 13.8 % of map/hash time with the position cache on, 19.2 % with
   both fixes applied.** The largest single remaining cause, and untouched by either fix below.
2. **DJUnits scalar hashing, regime-dependent: ~9–10 % of CPU in free flow, ~16 % under
   congestion.** The single most regime-sensitive cost in the model.
3. `ImmutableAbstractMap.get` (~5 % of CPU), `ValueUtil.expressAsUnit` (7.2 % self-time) and
   `Duration.instantiateSI` (3.3 %) — unit conversion and scalar construction, which the hash patch
   does not touch at all.

## The decision

Two independent fixes were measured over a full production day, as a 2×2 matrix (single run per
cell, so anything under a couple of percentage points is not measured):

| cell | djunits | `LaneBasedGtu.CACHING` | CPU relative to baseline |
|---|---|---|---|
| A | stock 5.2.1 | `true` (OTS default) | 100.0 % |
| **B** | **stock 5.2.1** | **`false`** | **84.3 %** |
| C | patched | `true` | 90.3 % |
| D | patched | `false` | 84.8 % |

The two are not independent effects: the patch makes cheap exactly the hash that the position cache
was protecting against. B and D are within noise of each other.

**Adopted: `LaneBasedGtu.CACHING = false` for MiRoVA scenarios.** It delivers essentially the whole
measured gain at zero dependency risk — a built-in OTS flag, pure memoisation, verified to produce
byte-identical output.

**Not adopted: the DJUnits patch.** It adds ~5.5 points on top of `CACHING=false`, inside noise
territory, against a real operational cost: a custom patched jar that has to be installed by hand
into every environment's `.m2`, is invisible to CI, and silently falls back to stock wherever
someone forgets — the exact failure mode that bit this project repeatedly during the cluster work.
The patch and its measurements are kept as a documented finding for the in-person Delft
conversation, not as shipped code. `djunits.version` stays stock `5.2.1`.

## Where `CACHING=false` is set

`ScenarioGenerator.buildSimulationScript` — the single funnel through which every MiRoVA scenario
is constructed, whether by `ScenarioManager.prepareRun` in a study or by a direct runner such as
`RunFreiburgMergeWatch`. Deliberately not a JVM-wide default and not a change to OTS's own default:
the flag belongs to OTS and other users of the library keep its behaviour.
`-Dmirova.gtuPositionCaching=true` restores the cache for anyone who wants to re-measure.

Verified in the production code path, not only in the standalone experiment: two runs of the
`dates` study for 2025-10-07 through `RunMirovaClusterStudy`, differing only in that flag, produce
byte-identical detector and trajectory output.

## Open items

- ~~**`ParameterSet.getParameter` is the obvious next target**~~ — **done**, see
  [`parameter_access_and_units.md`](parameter_access_and_units.md). Two things were wrong with it:
  `ParameterType.hashCode()` was recomputed per lookup and reached the DJUnits chain above through
  its default value, and the success path allocated a varargs array via `Throw.when`. Both are fixed
  in `ots-base`, so unlike the rejected DJUnits patch this ships through the normal build. On top of
  that, MiRoVA now reads constant parameters from a per-vehicle snapshot rather than looking them up
  at all: 171 call sites in active code became 74. Verified byte-identical on a 60-minute
  Freiburg-Nord run; the size of the CPU saving has not been re-profiled.
- **Cells B and C of the matrix were never profiled**, only timed. B (stock, cache off) would
  separate the position cache's own contribution from the patch's.
- **`CruisingSpeedIncentive` asks perception for three directions every tick.** Whether all three
  are needed every tick is a modelling question, not a performance one, and it is where the largest
  single block of CPU is.
- **Emitting `[Progress] <wall> t=<sim>` from the runners** would make the wall-clock-to-simulated
  mapping measured rather than reconstructed from vehicle-tick counts.

## Working record

The experiment branch `perf/djunits-hash-cache-experiment` is kept as the archived working record:
the patch tooling, `cluster/profile_matrix.sh` and `RunProfileMatrix.java` live there and are
deliberately not merged into production. The evaluation pipeline that produced these numbers is a
permanent part of the Python tooling, in `diss_mvb/scripts/simulation/ots/profiling/` — including
the State/Pattern attribution and the trajectory join described above.
