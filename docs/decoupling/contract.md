# Phase 1 — The `mirova-core` contract

What a host simulator must provide, and what it gets back. Drafted from the Phase 0 inventory and the
decisions taken in Phase 0.5; the Kotlin drafts in [`contract/`](contract/) are the normative form,
this document is the reasoning behind them.

**Status: draft for review. Nothing here has been compiled** — kit-ifv/kotlin-units is not in this
workspace, so the drafts are syntactically checked by eye only. No code has been migrated and no
module has been created.

**Files.** [`CoreTypes.kt`](contract/CoreTypes.kt) · [`WorldView.kt`](contract/WorldView.kt) ·
[`DriverAgent.kt`](contract/DriverAgent.kt) · [`DriverParameters.kt`](contract/DriverParameters.kt) ·
[`CarFollowing.kt`](contract/CarFollowing.kt) · [`Diagnostics.kt`](contract/Diagnostics.kt)

**The shape of it.** The host implements `WorldView` and calls `DriverAgent.step`. Everything else —
belief, desire, intention, arbitration, car following, relaxation — is the core's, and the host cannot
see it.

```
  host                             core
  ────                             ────
  WorldView          ─── read ──▶  Belief ▶ Desire ▶ Intention ▶ Arbitration ▶ Reactive
  step(now, world)   ─── call ──▶
                     ◀── return ─  TacticalCommand
  onLaneChange*()    ─── call ──▶
                     ◀── record ─  Diagnostics
```

---

## 1. The `WorldView` query list

Thirteen queries plus the ego state, down from the twenty-one of inventory §B.

**What went.** Query 6 (`vehicleAlongside`) — only a commented-out call site remained; the live use is
now the `isAlongside` flag on a perceived vehicle. Query 15 (`density`) — no live caller, and the one
quantity kotlin-units lacks. Query 16 (`anticipatedLaneDrop`) — its only consumer is
`AnticipateDownstreamMergePattern`, which is not registered. Query 12 shrank from an OTS *speed limit
prospect* to a list of legal limits with distances (decision A.5). Queries 17 and 18 —
`downstreamAdjacentLane` and `meanSpeedOnSegment`, the god-view pair — collapse into the single
semantic query `expectedMergeSpeed`, bounded by the driver's own perception range (BC-6).

**`Absent` against `Unknown`.** `Absent` means *there is nothing there* and is a normal answer the core
models. `Unknown` means *I cannot answer* and forces a defined fallback. A host must never answer
`Absent` where it means `Unknown`: that turns "I cannot see a kilometre downstream" into "the road
ahead is empty", which is a different model.

| # | Query | Returns | Range | `Absent` means | `Unknown` | Consumers (layer) | Near-field host |
|---|---|---|---|---|---|---|---|
| — | `ego` | `EgoState` | — | — | never | 1, 4 | trivially |
| 1 | `leaders(lane, range)` | `Sequence<PerceivedVehicle>` | argument, ≤ 295 m today | empty sequence: no vehicle ahead in range | never | 1, 3, 4 | yes |
| 2 | `followers(lane, range)` | `Sequence<PerceivedVehicle>` | argument, ≤ 200 m today | empty sequence | never | 1, 2, 3 | yes |
| 3 | `laneExists(lane)` | `Boolean` | cross-section | — | never | 1, 2 | yes |
| 4 | `laneIsUsable(side)` | `Boolean` | adjacent | — | never | 1, 2 | yes |
| 5 | `laneChangePossibility(side, range)` | `Observation<Distance>` | argument | a change to that side is not legal here at all | never | 1, 2 | yes |
| 6 | `routeRequirement(lane, range)` | `Observation<RouteRequirement>` | argument | the route asks nothing of this lane within range | permitted: a host with no route model | 1, 2, 3 | yes, from the scenario's own route |
| 7 | `distanceToLaneEnd(lane, range)` | `Observation<Distance>` | argument | the lane does not end within range | never | 1, 3 | yes |
| 8 | `speedLimits(lane, range)` | `Sequence<SpeedLimitAhead>` | argument | never empty — the limit in force is always first | never | 1, 2, 4 | yes |
| 9 | `parallelMergeAhead(side, range)` | `Boolean` | argument | — | never | 2 | yes |
| 10 | `meanSpeed(lane, range)` | `Observation<Speed>` | argument | the lane is empty within range | permitted | 1, 3 | approximately; see below |
| 11 | `expectedMergeSpeed(side, range)` | `Observation<Speed>` | argument, ≤ perception range | that lane is visible but empty | **expected** | 3 | often `Unknown` |

Units throughout: `Distance` = Long micrometres, `Speed` and `Acceleration` = Double SI,
`Duration` = `kotlin.time.Duration`.

**No default ranges.** Every range is an argument with no default value. That is the whole point of
Phase 0.5's central finding: the Java model raised `ParameterTypes.LOOKAHEAD` around a perception call
to widen it, the perception memoised per tick keyed by relative lane alone, and the widening therefore
never took effect — measured at **100.0000 % of 136,504,913 calls served from a stale memo**. A range
that is an argument cannot be defeated by a cache the caller does not control. The core passes its own
perception range, which is a *driver* property that stays inside the core.

**Query 10, `meanSpeed`, is provisional.** It is the last aggregate a host supplies that the core
could derive itself — `InfrastructureContext.getAnticipatedSpeed` already computes the same quantity
from the leaders it perceives, and BC-5 makes exactly that substitution. If BC-5 is adopted after the
comparison campaign, this query leaves the contract and §1 has twelve entries. It is retained in v1
only because that comparison has not been run.

**Query 11 is the one a driving simulator will decline.** `expectedMergeSpeed` replaces a scan over
`lane.getGtuList()` on a lane found up to a kilometre downstream: every vehicle's position and speed,
read directly, for a lane the ego cannot see. The core's fallback when the answer is `Unknown` is the
legal speed limit capped at the ego's own desired speed — a cascade that already exists in
`MandatoryLaneChangePattern.getMergeReferenceSpeed`, so `Unknown` degrades to behaviour the model
already has rather than to something new.

---

## 2. Tick consistency and laziness

**A `WorldView` is valid for exactly one tick.** Within that tick, every query returns the same answer
however often it is called. Two layers asking the same question must not be able to disagree — the
Belief layer asks for the current-lane leaders, and so does longitudinal control three layers later.

**Implementations should memoise; the core does not rely on it.** OTS's perception already caches per
GTU per tick, and a re-implementation would be wasteful. What the core does rely on is that the
memoisation key covers everything the answer depends on. The Phase 0.5 defect was a memo keyed by
`(query, relative lane)` for an answer that also depended on the look-ahead. **In this contract the
range is part of the query signature, so a correct memo key is the argument list.**

**Laziness is load-bearing on `leaders` and `followers`.** Longitudinal control takes two leaders
(`cfMaxLeaders`); every other caller takes one. A host that materialises 295 m of traffic to answer a
call that consumes one element pays for the whole range on every tick of every vehicle. The `Sequence`
return type says: compute on demand, and expect the consumer to stop early.

**The core retains nothing.** Neither the `WorldView` nor anything reachable from it survives the
`step` call it was passed to. `PerceivedVehicle` values may be read freely during the tick; across
ticks only `ParticipantId` is remembered. A host may therefore pool and recycle its perception objects.

---

## 3. `PerceivedVehicle`: observable fields only

| Field | Type | Why it is observable |
|---|---|---|
| `id` | `ParticipantId` | Recognition — a driver knows the car in front is the same car as a second ago |
| `netGap` | `Distance` | Bumper to bumper; **signed**, negative once the ego has drawn level, which is how the merge logic detects a vehicle alongside. Must not be clamped. |
| `speed` | `Speed` | |
| `acceleration` | `Acceleration` | Visible through brake lights and closing rate |
| `length` | `Distance` | |
| `indicator` | `IndicatorState` | The one genuinely inter-agent signal in the model, and the trigger for cooperative gap opening |
| `isAlongside` | `Boolean` | Longitudinal overlap |

**Three fields are gone, each deliberately.**

| Removed | Read at | Replaced by |
|---|---|---|
| the neighbour's `desiredSpeed` | `SocialInteractionsIncentives:151` | **BC-4**: the speed the vehicle was last seen holding while unobstructed, falling back to the legal limit scaled by the mean factor this driver has observed |
| the neighbour's `parameters` | `GapOpenerPattern:290`, `SocialInteractionsIncentives:153` | **BC-2**: the ego's own parameters. `egoSocialPressure` already did this. |
| the neighbour's `carFollowingModel` | `GapOpenerPattern:291` | same |

Each of the three is a driver reading another driver's mind, and each is behind a Phase 0.5 switch
whose comparison run decides whether the substitution is acceptable. **Whichever way the comparison
goes, these fields are not in the contract** — a driving simulator with a human in the loop cannot
supply them at all.

---

## 4. `DriverAgent` lifecycle

```
create(id, overrides)          parameters drawn once, from the core's distributions
                               with the host's random stream
  │
  ├── step(now, world) ─▶ TacticalCommand      repeatedly, now non-decreasing
  ├── onLaneChangeCompleted(side)              host reports arrival
  ├── onLaneChangeAborted(side)                host refused or abandoned
  │
  └── dispose()                                no step afterwards
```

**Construction resolves everything constant.** The parameter set is read once into an internal
snapshot of primitives; nothing is looked up per tick. This is `MirovaParameterSnapshot` made total:
after Phase 0.5 the Java model had exactly one runtime parameter write left, and §8 removes it.

**`step` may be called twice at the same instant** and returns the same command — the agent treats it
as one tick. Calling with a `now` before the previous call throws. There is no `reset`: a driver whose
history should be forgotten is a new driver.

**The two callbacks exist because the host executes lateral motion.** Only the host knows when a
vehicle has arrived in the target lane. Until `onLaneChangeCompleted` fires, the agent believes the
movement is under way and keeps the manoeuvre pattern that requested it alive. `onLaneChangeAborted`
unwinds it: the pattern is abandoned, and any relaxation opened for a leader on the target lane fades
over one second (`relaxFade`) rather than vanishing, so the acceleration stays continuous.

The Java model detected completion as `!isChangingLane() && !originLane.equals(gtu.getLane())` —
which requires holding a host `Lane` across ticks. **The core never holds a lane identity**, so
completion has to be told to it.

**`dispose` is a courtesy to the host**, not a correctness requirement: a run with hundreds of
thousands of vehicles wants the per-agent caches released at a known moment.

---

## 5. `TacticalCommand`

```kotlin
data class TacticalCommand(
    val acceleration: Acceleration,
    val laneChange: LaneChangeRequest?,   // (side, duration)
    val indicator: IndicatorState,
    val nextEvaluationAfter: Duration,
)
```

Four fields, and nothing in them names a lane, a link, a road or a position.

**`acceleration`** arrives already bounded by the vehicle limits the host reported through
`EgoState.maxAccelerationAt` and `maxDeceleration`, so the host need not clamp. A host that clamps
anyway for its own reasons may; the agent observes the result next tick through
`EgoState.acceleration`.

**`laneChange`** carries a duration. The Java model took it from `ParameterTypes.LCDUR`, with a
commented-out `congestedLaneChangeDuration` at `MandatoryLaneChangePattern:2431` showing that a
per-manoeuvre duration was wanted. Decision A.7 kept that parameter for exactly this. Repeating the
same request while a change is under way is not a new request; a request to the *other* side is a
reversal, and the host should treat the first as aborted and say so.

**`indicator`** is always the full desired state, never a delta, so a host that drops a command still
converges.

**`nextEvaluationAfter`** is an upper bound, not a schedule. A host stepping at a fixed interval
ignores it; an event-driven host uses it to avoid waking a free-flowing driver as often as one
negotiating a merge. Calling earlier is always allowed. Calling **later** changes the behaviour, and a
host that cannot honour the bound should say so at integration time rather than silently drift.

**Vehicle removal is not a command.** `DeadlockDiffusionWatchdog` calls `gtu.destroy()`; that stays
entirely with the host. The core may at most emit a `LimitReached` diagnostic saying it is stuck.

---

## 6. Time

**The host owns the clock; the core owns its own history.** `step(now, world)` passes an absolute
simulation time as a `kotlin.time.Duration` measured from the start of the simulation — not a delta,
and not a wall clock.

*Why absolute rather than `dt`.* The relaxation decays as `exp(-(t - t₀)/τ)` and the observation
expiry counts elapsed time. Both are expressed against an origin, and reconstructing that origin by
accumulating deltas accumulates error too. The agent derives `dt` as the difference from the previous
call, which is what the smoothing coefficient needs.

**Every time-dependent quantity uses the actual elapsed time, never a configured step.** This is
decision BC-1: `MandatoryLaneChangePattern` derived its EMA coefficient as `params.dtSi * 0.25`, a
constant from the `DT` *parameter*, which is right only while the host steps at exactly `DT`. In the
core the coefficient is `α = 1 − exp(−dt/τ)` from the measured `dt`. `ParameterTypes.DT` is therefore
**not** a driver parameter; its role is taken by `nextEvaluationAfter`.

**There is no wall-clock time and no simulation calendar in the core.**

---

## 7. `DriverParameters`

Thirty-eight keys, listed in full in [`DriverParameters.kt`](contract/DriverParameters.kt) and
grouped there as: car following (8), desired speed (2), desire thresholds (3), social interaction (7),
gap acceptance (5), relaxation (6), lane-change execution (2), capacity drop (5).

**Typed keys, closed set.** A key not in `DriverParameterKeys` cannot be set. The Java model let any
scenario write any string-keyed parameter into a shared set, and Phase 0.5 found several studies that
had been setting keys nothing reads — `TMIN` and `TMAX` on MiRoVA factories (only the uninstalled
mental module reads them), `FAR_ANTICIPATION_ENABLED` on a pattern that is not registered.

**Deliberately absent.**

| Not a parameter | Because |
|---|---|
| `LOOKAHEAD`, `LOOKBACK` | Query range arguments (§1). Making them arguments is what makes the Phase 0.5 defect unrepresentable. |
| `DT` | Not a driver property. Becomes `nextEvaluationAfter` (§6). |
| `EXTENDED_LOOK_AHEAD_DISTANCE` | Its one live use is the range of `expectedMergeSpeed`, bounded by perception (BC-6). Its three threshold uses tested "is there a route lane change in range at all", which `routeRequirement` answers directly. |
| the six `bc*` switches | They exist to compare the Java model against itself. The core implements one behaviour per decision, not a switch. |
| the eight parameters deleted in Phase 0.5 | Read nowhere. |

**Distributions: shapes in the core, calibration in the host, entropy in the host.** The spread of the
desired-speed factor across a population is driver heterogeneity and travels with the driver to any
road, so the shape belongs to the core. `carsLimit120_DensityHigh` and its twenty siblings are fits to
a facility and a traffic state, so they belong to the host, which selects and parametrises a core
shape. The `RandomStream` is always the host's: reproducibility is a property of a run, the host owns
the run, and a core that seeded itself would make two runs of the same scenario differ.

**One classification is still open** — see Q3.

---

## 8. Car following, and where relaxation lives

**IDM+ only in v1.** Wiedemann 99 is *not* dead in OTS — `SimpleHighwayScenario` builds a
`Wiedemann99Factory` for cars and for trucks and hands both to the MiRoVA planner factory, which the
Phase 0 inventory got wrong — but it is an OTS car-following model configured by an OTS scenario, and
nothing in the MiRoVA layers depends on which model sits underneath. It stays in OTS (decision A.3).
The `CarFollowingModel` interface exists so that a host *may* substitute one, not because the core
needs two.

**The headway factor is an argument.**

```kotlin
fun followingAcceleration(speed, desiredSpeed, gap, leaderSpeed,
                          parameters, headwayFactor: Double = 1.0): Acceleration
```

The Java model expressed "follow with a reduced headway" by writing `ParameterTypes.T`, calling the
model, and resetting it in a `finally`. That was the last runtime parameter write in the tree, and it
is a hidden channel: the caller cannot see it, forgetting the reset corrupts every later call, and a
second thread would read the wrong value. **With the factor as an argument, nothing in the core writes
a parameter at runtime.**

**Relaxation is not in the car-following model.** It is a property of the driver's belief about a
particular leader, applied by the core *before* the call, by passing a buffered `gap`. The model
itself is memoryless and therefore substitutable.

The model is **one-parameter**, contrary to both `CLAUDE.md` §5 and the ITSC paper, which describe two.
The speed buffer the Java code declared was never fed; `RELAXATION_TAU_SPEED` was withdrawn with it in
Phase 0.5. Four mechanisms, all in the core:

| Mechanism | Parameter | Value | Applied |
|---|---|---|---|
| exponential decay of the headway deficit | `tauRelax` | **20 s** (not the paper's 15 s) | Belief, on the gap before the CF call |
| acceleration damping while relaxed | `relaxDamping`, `relaxDampingOn` | 0.40, on | Reactive, on the CF result |
| lifetime cap | `relaxLifetime` × `tauRelax` | 3 τ = 60 s | Belief |
| fade-out on abort | `relaxFade`, `relaxAbortB` | 1 s, −1.0 m/s² | Reactive |

τ = 20 s is a calibration, not the literature value, and the published results (TR-B, HEUREKA) rest on
it.

**`desiredGap` is a separate method** rather than an inversion of `followingAcceleration`, because gap
acceptance asks what a gap *should be*, and deriving that by inverting the acceleration would tie
gap acceptance to one model's algebra.

---

## 9. Diagnostics

**A port, not a logger.** The core writes no file, opens no stream and knows no logging framework; it
emits typed events and the host decides what becomes of them. `DefectDiagnostics` in the Java model —
counters behind one system property, a CSV path in another, a shutdown hook to write the report — is
precisely the arrangement this replaces.

Six events, a closed set so a host can exhaust it in a `when`: `PatternSwitched`, `StateChanged`,
`LaneChange`, `Relaxation`, `UnknownAnswered`, `LimitReached`.

**`Diagnostics.NONE` is the default** and every method on it is empty, so a JIT that has seen only that
implementation removes the call. Anything expensive must be built inside the implementation, not at the
call site.

**Diagnostics must never change what the model computes.** A run with them on and one with them off
must produce identical trajectories. `isEnabled` exists only to guard work the core would not otherwise
do.

**`UnknownAnswered` is the event to watch during integration.** A near-field host produces them
legitimately; a stream of them from a host that ought to know the answer means the adapter is not
wired up. That is a class of bug the Java model had no way to notice — see the memoisation defect,
which was invisible for years and needed 136 million instrumented calls to prove.

---

## 10. Errors and threading

**Fail fast, and do not catch.** Decision A.6: none of the 56 `try`/`catch` blocks in the Java tree
migrates. They exist because OTS declares `ParameterException`, `GtuException`, `NetworkException` and
`OperationalPlanException` as checked exceptions on almost every call, and the catches swallow them,
substitute a default, and continue with a corrupted belief. Kotlin has no checked exceptions, so the
pressure that created them is gone.

- A malformed parameter set throws at **construction**, not at the first tick.
- A `WorldView` contract violation — a negative range, a leader sequence that is not sorted, a `now`
  that goes backwards — throws `IllegalArgumentException` immediately.
- A state the model cannot resolve is **not** an error: no acceptable gap at a ramp end, a required
  deceleration beyond `bMax`. Each has a defined answer and a `LimitReached` diagnostic.
- The core never returns a sentinel acceleration to signal failure. There is no `NaN` path.

**Threading.** An agent is not thread-safe and must be stepped by one thread at a time. Different
agents are independent and may be stepped concurrently — the core holds no shared mutable state
between agents, and the shared objects it does hold (`DriverAgentFactory`, distributions, the
`CarFollowingModel`) are immutable. Two constraints then fall on the host: its `WorldView` must be safe
for concurrent reads, and its `RandomStream` must be safe for concurrent draws or per-agent. Today's
runs are single-threaded per JVM, so this is a promise about what the core does not preclude, not a
feature that has been exercised.

**Absent values inside the core.** `Observation` is a port-boundary type. Inside the core an absent
distance is a named sentinel, `Distance.ABSENT`, resolved once in the Belief layer, so the hot path
neither allocates nor unwraps. kotlin-units backs `Distance` with Long micrometres and has no infinity;
the sentinel is a large value with headroom, and **no saturating arithmetic and no change to
kit-ifv/kotlin-units** (decision A.4). Arithmetic on the sentinel is a bug the Belief layer is
responsible for preventing, not something the type system catches — see Q2.

---

## 11. Versioning

**`mirova-core` v1 is the contract in this document.** Semantic versioning, with the port and the
model versioned together:

- **Patch** — behaviour identical, internals only.
- **Minor** — a new optional query with a defined fallback, a new parameter key with a default that
  reproduces prior behaviour, a new diagnostic event. A host built against an earlier minor keeps
  working. *A new event in a sealed hierarchy breaks an exhaustive `when` at compile time* — hosts
  should add an `else` branch.
- **Major** — anything that changes a trajectory. Removing a query, changing what a query means,
  changing a default, changing the model.

**Model version and contract version are the same number.** A driver model whose behaviour changes is
a different model, and pretending otherwise is how the published results lose their meaning.

**Every release states which reference run reproduces it.** The Phase 0.5 rule holds beyond Phase 0.5:
behaviour-changing corrections and behaviour-preserving migration must never be mixed, or a deviation
in the equivalence tests cannot be attributed to either.

---

## 12. Open questions

**Q1 — `meanSpeed` (query 10): keep or drop?**
It is the last aggregate the host supplies that the core could compute itself, and BC-5 makes exactly
that substitution. The comparison run has not happened. **If BC-5 is adopted, I drop the query.** If it
is not, the query stays and every near-field host must approximate it. This is the one entry in §1
whose fate depends on a campaign result rather than on a design decision.

**Q2 — how much should `Distance.ABSENT` be defended?**
Decision A.4 fixed the sentinel and ruled out saturating arithmetic. What is left open is whether the
Belief layer's discipline is enforced anywhere — a debug-build assertion on arithmetic involving the
sentinel, an `ABSENT`-aware `min`, or nothing but review. I would take the debug assertion; it costs
nothing in a release build and it catches the class of bug that `POSITIVE_INFINITY` used to hide.

**Q3 — the `_Modified` truck distributions.**
`parameters.md` §4 classifies the distribution library, and the `_Modified` truck variants cannot be
classified from the code. If the modification corrects the population, the shape belongs in the core;
if it fits a facility, it belongs in the host. You know which it was.

**Q4 — should `routeRequirement` be answerable as `Unknown`?**
I have allowed it, for a driving simulator with no route model. But a driver who does not know where
they are going has no mandatory lane-change desire at all, which is a large behavioural difference to
hide behind a fallback. The alternative is to require every host to answer it, and let a
simulator without routes answer `Absent` — "the route asks nothing of this lane" — which is at least
honest about what the driver then does. **I lean towards requiring it.**

**Q5 — `nextEvaluationAfter` when the host cannot honour it.**
The contract says calling later changes the behaviour. It does not say what happens if a host does it
anyway: nothing detects it, and the drift is silent. A cheap guard is for the agent to emit a
`LimitReached` diagnostic when the measured `dt` exceeds what it asked for by some margin. Worth it?

**Q6 — `mirova_model_reference.md` is still not in any working repository.**
Searched again across `opentrafficsim`, `diss_mvb`, `trajectory_pipeline`, `mirova`, `mirova_main`,
Downloads, Desktop and Documents. Inventory §C.6 measured the relaxation implementation against it
from memory of its description; that comparison should be redone against the actual document, or the
document declared lost and §C.6 rewritten to compare only against the paper.

**Q7 — the `Side`/`RelativeLane` split.**
Two enums where one might do: `RelativeLane` has a `CURRENT` that a lane-change request must never
carry, and `Side` has no `CURRENT` for exactly that reason. The Java model used
`LateralDirectionality` with a `NONE` member for both roles, and the resulting "can this be `NONE`
here" checks are scattered through the intention layer. I think two types is right, but it is two
types where a reviewer might expect one.
