# Phase 1 — The `mirova-core` contract

What a host simulator must provide, and what it gets back. Drafted from the Phase 0 inventory and the
decisions taken in Phase 0.5; the Kotlin drafts in [`contract/`](contract/) are the normative form,
this document is the reasoning behind them.

**Status: revised after the first review. Nothing here has been compiled**, but the unit literals are
now checked against the real library: kotlin-units is vendored in the standalone repository
(`tactical-maneuver-architecture/third-party/kotlin-units`), and `.meters`, `.metersPerSecondSquared`
and `.kmh` are its extensions, with `unaryMinus` defined on both `Distance` and `Acceleration`. No code
has been migrated and no module has been created here.

**Naming.** `mirova-core` and the package `edu.kit.ifv.mirova.api` are placeholders. The standalone
library's name is not yet decided and nothing is renamed until it is.

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

## 0. Reference model of the core — which behaviour this is

**The core does not reproduce the Phase 0.5 reference run.** That follows from two decisions taken
separately: the non-observable fields leave the contract whichever way their comparison goes (§3), and
the core implements one behaviour per decision rather than a switch (§7). Together they mean the core
embodies OTS **with a specific set of BC switches enabled**, and that set is not the reference set of
all switches off.

| Switch | In the core? | Why |
|---|---|---|
| BC-1 — EMA coefficient from the actual `dt` | **yes** | §6: every time constant is evaluated on elapsed time. A step-dependent constant is not portable to a host whose step the core does not control. |
| BC-2 — leader cooperation judged with the ego's own parameters | **yes** | §3: the neighbour's parameters and car-following model are not in `PerceivedVehicle`. |
| BC-4 — follower desired speed estimated from observation | **yes** | §3: the neighbour's desired speed is not in `PerceivedVehicle`. |
| BC-5 — mean speed from perceived leaders | **undecided** | Tied to Q1. If adopted, `meanSpeed` leaves the contract; if not, it stays and every host must answer it. |
| BC-6 — merge reference range limited to perception | **yes** | §1: `expectedMergeSpeed` is bounded by what the driver can see. |
| BC-7 — extended look-ahead | **n/a** | The mechanism was deleted in Phase 0.5 after it was measured to be inert. |
| BC-8 — contexts updated in dependency order | **yes** | §4: the core fixes the order `Ego → Neighbors → Infrastructure → MacroTraffic`. The Java model inherited a `HashMap` iteration order, which is deterministic in practice but accidental, and a core whose belief layer depends on an accident is not a library. |
| BC-9 — θ interpolated between `dMand` and `dSearch` | **undecided** | Q9. The core implements one or the other: either θ tapers as the model describes, or it steps at `dMand` as the code does. Adopting it adds `dSearch` to the key list as a 39th key — that the contract has no place for it today is itself the evidence that the step function is what the core would otherwise inherit. |

**Consequences, each of which has to be acted on and not just noted.**

1. **The equivalence reference for migration is an OTS run with exactly this set enabled**, not the
   Phase 0.5 reference run. Two candidates are registered in `Phase05ReferenceStudy`, `coreset` and
   `coreset-interp` (the same plus BC-9), and both run in the pending campaign alongside the
   per-switch variants. **Which of the two is the core reference is decided on that campaign** —
   which is why both are run.
2. **Migration order.** Stages 0–2 — relaxation, parameters, IDM+ — are unaffected and can start
   whenever. The Desire and Intention layers, and the parts of Belief that BC-2, BC-4 and BC-8 touch,
   **cannot be migrated before the campaign has been evaluated**, because until then the target
   behaviour is not fixed. Inventory §D carries the dependency.
3. **If a BC performs badly, reverting is not available.** The original behaviour of BC-2 and BC-4
   cannot be expressed in the core at all — the fields they replace are not in the contract. The
   response to a bad result is a *better observable substitute*, not a return to reading another
   driver's mind. This is stated again in §3, where it belongs.
4. **Core v1 is not the model behind the published results.** TR-B and HEUREKA rest on the reference
   set. The core needs its own validation on Freiburg-Nord before anything is published from it; see
   §11.

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
| 6 | `routeRequirement(lane, range)` | `Observation<RouteRequirement>` | argument | the route asks nothing of this lane within range — **including because the host models no route** | **not permitted** | 1, 2, 3 | yes |
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

**Query 6 admits no `Unknown`** (review decision). A driver who does not know where they are going has
no mandatory lane-change desire at all; letting that arrive as a missing value would hide a large
behavioural difference behind a fallback. A host with no route model answers `Absent`, which is honest
about what the driver then does.

**Query 10, `meanSpeed`, is provisional.** It is the last aggregate a host supplies that the core
could derive itself — `InfrastructureContext.getAnticipatedSpeed` already computes the same quantity
from the leaders it perceives, and BC-5 makes exactly that substitution. If BC-5 is adopted after the
comparison campaign, this query leaves the contract and §1 has twelve entries.

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

**`Sequence` is v0.x, not settled.** It allocates at the port boundary — an iterator per lane, per
tick, per vehicle, on the hottest path there is. An index-based alternative,
`leader(lane, index, range): Observation<PerceivedVehicle>`, allocates nothing and expresses the same
access pattern less elegantly. **Which is right is a measurement, and the measurement does not exist:
the question stays open until there is a JMH benchmark of the tick path.** Until then `Sequence`
stands, because the readable form should be the one that has to justify replacing itself.

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
| the neighbour's `desiredSpeed` | `SocialInteractionsIncentives:151` | **BC-4**, specified below |
| the neighbour's `parameters` | `GapOpenerPattern:290`, `SocialInteractionsIncentives:153` | **BC-2**: the ego's own parameters. `egoSocialPressure` already did this. |
| the neighbour's `carFollowingModel` | `GapOpenerPattern:291` | same |

**Reverting is not an option.** The comparison campaign decides whether these substitutions are *good
enough*, not whether they happen: the fields they replace are not in the contract and a host with a
human driver in the loop cannot supply them at all. If BC-2 turns out to increase ramp standstills, or
BC-4 to weaken the social pressure where it matters, the answer is a **better observable substitute** —
not a return to reading another driver's mind.

### BC-4 in full

The estimator, as implemented and verified against the code
(see [`bc4-and-reference-check.md`](bc4-and-reference-check.md)):

- **Observation.** A follower on the **ego's own lane** counts as unobstructed when the net gap
  exceeds `s0 + v_follower · T` — computed with the *ego's* `s0` and `T`, since the follower's are not
  observable — and its acceleration is at or above −0.1 m/s². Only the current-lane follower can be
  judged, because only there is the ego the follower's leader.
- **Memory.** The last unobstructed speed per follower, expiring after ten seconds and bounded to
  sixteen followers, oldest evicted.
- **Fallback.** The legal limit scaled by the mean speed factor this driver has observed unobstructed
  vehicles keeping; 1.0, i.e. the bare limit, before any observation. A population value, not the
  ego's own preference — substituting the ego's would make a slow truck conclude that the car behind
  it wants no more than the truck does, and the social pressure would vanish in exactly the situation
  the term exists for.
- **Floor.** Never below the follower's current speed, which is a lower bound on what it wants.

The OTS implementation counts the expiry in **ticks** (50), which is ten seconds only at a 0.2 s step.
**In the core it is a `Duration`**, per §6. The defect is left in the Java code so the pending campaign
measures one thing; it is recorded in the check report.

---

## 4. `DriverAgent` lifecycle

```
create(id, overrides)          parameters drawn once, from the core's distributions
                               with the host's per-agent random stream
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

**Inside one `step`, the belief contexts update in dependency order:**
`Ego → Neighbors → Infrastructure → MacroTraffic`. Ego holds the relaxation and the per-tick
acceleration cache that the others consult; Neighbors detects the cut-in that opens a relaxation;
Infrastructure reads perceived leaders for the anticipated speed; MacroTraffic aggregates. The Java
model ran `MacroTraffic → Infrastructure → Neighbors → Ego`, which is the iteration order of the
`HashMap` the categories happened to live in — deterministic in practice, accidental in origin. This
is BC-8, and the core takes the dependency order.

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
negotiating a merge. Calling earlier is always allowed. Calling **later** changes the behaviour, and
the agent says so: it emits an `EvaluationLate` diagnostic carrying the bound it asked for and the
interval that actually elapsed. Silent drift was the point of the complaint; a measurable one is
acceptable.

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

**Every time-dependent quantity uses the actual elapsed time, never a configured step and never a tick
count.** This is decision BC-1 generalised: `MandatoryLaneChangePattern` derived its EMA coefficient as
`params.dtSi * 0.25`, a constant from the `DT` *parameter*, which is right only while the host steps at
exactly `DT`; and the BC-4 estimator counts its expiry in ticks, which has the same flaw. In the core
the coefficient is `α = 1 − exp(−dt/τ)` from the measured `dt`, and every horizon is a `Duration`.
`ParameterTypes.DT` is therefore **not** a driver parameter; its role is taken by
`nextEvaluationAfter`.

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
| the six `bc*` switches | They exist to compare the Java model against itself. The core implements one behaviour per decision (§0), not a switch. |
| the eight parameters deleted in Phase 0.5 | Read nowhere — but see Q9: one of them, `DSEARCH`, turns out to have been orphaned by a bug rather than vestigial. |

**The defaults are the production set (Q8, decided).** `DriverParameterKeys` carries the **car**
values of the parameter set the published campaign ran — `T = 1.10 s`, `a = 1.4 m/s²`,
`vGain = 15 km/h`, `fGap = 0.40`, `relaxDamping = 1.00` — resolved through all four override layers and
confirmed against a registered run, not read off constants. The OTS defaults are gone: a standalone
library whose defaults come from a simulator it no longer depends on is an accident waiting to be
inherited. Derivation and evidence: [`default-parameters.md`](default-parameters.md).

**Two conventions follow, both ADR candidates for the new repository.**

> **ADR-A — parameters are positive magnitudes; computed accelerations are signed.**
> A parameter is a bound the driver brings, and a bound has no direction: `b`, `bCrit`, `bMax`,
> `bCoop`, the follower and ego thresholds, `relaxAbortB` are all positive. A sign appears only where
> the model computes: `CarFollowingModel.followingAcceleration`, `TacticalCommand.acceleration`,
> `EgoState.acceleration` are signed, negative to brake. OTS carried both conventions at once —
> `ParameterTypes.B` positive, `MirovaParameters.B_MAX` negative — which is how the same physical
> quantity appeared with two signs in one parameter set. **One judgement call:**
> `EgoState.maxDeceleration` is a *host-reported vehicle limit*, so it follows the parameter
> convention and is a magnitude. It is brought, not computed.

> **ADR-B — one default set, and it is the car set; other classes are named presets.**
> `DriverParameters` describes one driver, so the defaults can only be one vehicle class. Cars are the
> majority class and the one the calibration was steered by. Trucks are `DriverPresets.TRUCK`, seven
> keys that the host applies as an override — `T`, `s0`, `a`, `aMax`, `vGain`, `bCoop`, and
> `cooperate = false`. Which class a driver belongs to is population configuration, and population
> configuration is the host's.

**Every key carries its provenance in the code**, `LITERATURE` / `CALIBRATION` / `ASSUMPTION` /
`OTS_DEFAULT` / `UNKNOWN`, so that a number nobody has ever justified says so where it is read.

After the LMRS check, two of the three unclassified keys are settled and one is not: `socio = 0.25` is
a MiRoVA choice, not the LMRS value (`SOCIO` is 1.0 there, on the unit interval, while MiRoVA drops
that bound), so it is an `ASSUMPTION`; `vGain = 15 / 30 km/h` is **not** the LMRS value either — that
is 69.6 km/h, which is what MiRoVA declares as its default and the campaign overrides — and no source
for 15 / 30 exists in the code or the model reference, so it stays `UNKNOWN`. `tauRelax = 20 s`
likewise: Keane & Gao give 15 s. Two `UNKNOWN` keys remain, and both are load-bearing.

**Distributions: shapes in the core, calibration in the host, entropy in the host.** The spread of the
desired-speed factor across a population is driver heterogeneity and travels with the driver to any
road, so the shape belongs to the core. `carsLimit120_DensityHigh` and its twenty siblings are fits to
a facility and a traffic state, so they belong to the host, which selects and parametrises a core
shape. The `RandomStream` is always the host's, **one per agent** — see §10.

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

The model is **one-parameter**. `mirova_model_reference.md` §6 says so explicitly — a single constant
`τ_relax = 20 s`, with speed-deficit relaxation "not implemented separately" — and that matches the
code. `CLAUDE.md` §5 described a two-parameter model with τ_s ≈ 15 s until Phase 0.5 corrected it; the
15 s figure comes from Keane & Gao, not from anything in this project. Four mechanisms, all in the
core:

| Mechanism | Parameter | Value | Applied |
|---|---|---|---|
| exponential decay of the headway deficit | `tauRelax` | **20 s** | Belief, on the gap before the CF call |
| acceleration damping while relaxed | `relaxDamping`, `relaxDampingOn` | 0.40, on | Reactive, on the CF result |
| lifetime cap | `relaxLifetime` × `tauRelax` | 3 τ = 60 s | Belief |
| fade-out on abort | `relaxFade`, `relaxAbortB` | 1 s, −1.0 m/s² | Reactive |

τ = 20 s is a calibration, not the literature value, and the published results rest on it.

**`desiredGap` is a separate method** rather than an inversion of `followingAcceleration`, because gap
acceptance asks what a gap *should be*, and deriving that by inverting the acceleration would tie
gap acceptance to one model's algebra.

---

## 9. Diagnostics

**A port, not a logger.** The core writes no file, opens no stream and knows no logging framework; it
emits typed events and the host decides what becomes of them. `DefectDiagnostics` in the Java model —
counters behind one system property, a CSV path in another, a shutdown hook to write the report — is
precisely the arrangement this replaces.

Seven events, a closed set so a host can exhaust it in a `when`: `PatternSwitched`, `StateChanged`,
`LaneChange`, `Relaxation`, `UnknownAnswered`, `EvaluationLate`, `LimitReached`.

**`Diagnostics.NONE` is the default** and every method on it is empty, so a JIT that has seen only that
implementation removes the call. Anything expensive must be built inside the implementation, not at the
call site.

**Diagnostics must never change what the model computes.** A run with them on and one with them off
must produce identical trajectories. `isEnabled` exists only to guard work the core would not otherwise
do.

**Two events to watch during integration.** `UnknownAnswered` — a near-field host produces them
legitimately, but a stream of them from a host that ought to know the answer means the adapter is not
wired up. `EvaluationLate` — the host is not honouring the interval the driver asked for, and the
model's time constants are quietly seeing something else. That is a class of bug the Java model had no
way to notice: see the memoisation defect, invisible for years, which needed 136 million instrumented
calls to prove.

---

## 10. Errors and threading

**Fail fast, and do not catch.** Decision A.6: none of the 56 `try`/`catch` blocks in the Java tree
migrates. They exist because OTS declares `ParameterException`, `GtuException`, `NetworkException` and
`OperationalPlanException` as checked exceptions on almost every call, and the catches swallow them,
substitute a default, and continue with a corrupted belief. Kotlin has no checked exceptions, so the
pressure that created them is gone.

- A malformed parameter set throws at **construction**, not at the first tick.
- A `WorldView` contract violation — a negative range, a leader sequence that is not sorted, a `now`
  that goes backwards, `Unknown` from `routeRequirement` — throws `IllegalArgumentException`
  immediately.
- A state the model cannot resolve is **not** an error: no acceptable gap at a ramp end, a required
  deceleration beyond `bMax`. Each has a defined answer and a `LimitReached` diagnostic.
- The core never returns a sentinel acceleration to signal failure. There is no `NaN` path.

**Threading.** An agent is not thread-safe and must be stepped by one thread at a time. Different
agents are independent and may be stepped concurrently — the core holds no shared mutable state
between agents, and the shared objects it does hold (`DriverAgentFactory`, distributions, the
`CarFollowingModel`) are immutable. Two conditions fall on the host:

1. its `WorldView` must be safe for concurrent reads;
2. **each agent has its own `RandomStream`**, derived from the run seed and the `ParticipantId`. A
   single shared stream, however thread-safe, is not reproducible under concurrency: the order of
   draws depends on scheduling, so the same seed yields a different population. Determinism, not
   thread safety, is the property that matters.

Today's runs are single-threaded per JVM, so this is a promise about what the core does not preclude.

**Absent values inside the core.** `Observation` is a port-boundary type. Inside the core an absent
distance is a named sentinel, `Distance.ABSENT`, resolved once in the Belief layer, so the hot path
neither allocates nor unwraps. kotlin-units backs `Distance` with Long micrometres and has no infinity;
the sentinel is a large value with headroom, with **no saturating arithmetic and no change to
kit-ifv/kotlin-units** (decision A.4). The library's own `Distance.MAX` is `Long.MAX_VALUE` and
therefore has *no* headroom, so `ABSENT` must be a distinct, smaller sentinel rather than `MAX`. **Arithmetic on the sentinel is guarded by an assertion, active
under `-ea` in tests and CI and free in a release build** (review decision, Q2). That is what
`POSITIVE_INFINITY` never gave us: a wrong answer that announces itself.

---

## 11. Versioning

**Semantic versioning, with the port and the model versioned together.** A driver model whose
behaviour changes is a different model, and pretending otherwise is how published results lose their
meaning.

The naive rule — *major = anything that changes a trajectory* — is unusable, because traffic
simulations diverge chaotically: reordering two floating-point additions eventually moves every
vehicle. What matters is whether the *distribution* moved.

| Level | Test that must pass |
|---|---|
| **Patch, minor** | unit-level golden tests within tolerance, **and** no significant macroscopic difference against the stated reference run — Van Aerde fundamental diagram, Geistefeldt capacity, cross-day coefficient of variation |
| **Major** | any deliberate model change, or a significant macroscopic difference on those measures |

Minor may add an optional query with a defined fallback, a parameter key whose default reproduces
prior behaviour, or a diagnostic event. *A new event in a sealed hierarchy breaks an exhaustive `when`
at compile time* — hosts should carry an `else` branch.

**Every release states which reference run reproduces it.** For v1 that is the `coreset` variant of
`Phase05ReferenceStudy` (§0), not the Phase 0.5 reference run. **Core v1 is therefore not the model
behind TR-B and HEUREKA**, and it requires its own validation on Freiburg-Nord before anything is
published from it.

The Phase 0.5 rule holds beyond Phase 0.5: behaviour-changing corrections and behaviour-preserving
migration must never be mixed, or a deviation in the equivalence tests cannot be attributed to either.

---

## 12. Decisions taken in review, and what is still open

| Ref | Question | Outcome |
|---|---|---|
| Q1 | `meanSpeed`: keep or drop? | Follows BC-5. Recorded in §0 and §1. |
| Q2 | How much to defend `Distance.ABSENT`? | **Assertion**, active under `-ea` in tests and CI. §10. |
| Q3 | The `_Modified` truck distributions | **Still open — Marvin decides.** |
| Q8 | Defaults | **Decided:** the production car set, ADR-A and ADR-B in §7. |
| Q9 | The θ interpolation | Implemented behind a switch; **open**, and the core reference with it (§0). |
| Q4 | May `routeRequirement` answer `Unknown`? | **No.** Mandatory for every host; a host without routes answers `Absent`. §1. |
| Q5 | What if the host calls back late? | A separate `EvaluationLate` event, not `LimitReached`. §5, §9. |
| Q6 | `mirova_model_reference.md` | **Resolved** — supplied 2026-09-10, now in `docs/decoupling/`. Inventory §C.6 corrected against it. |
| Q7 | `Side` beside `RelativeLane` | Two types confirmed; rationale in `CoreTypes.kt`, to become an ADR in the new repository. |

**Q8 — defaults. Decided: the production set, car values.**
Implemented in `DriverParameterKeys`, with `DriverPresets.TRUCK` for the other class and the two
conventions above as ADR-A and ADR-B. It is a `major` step by §11, taken before v1 is tagged rather
than after. What is *not* settled is the two `UNKNOWN` provenances, `vGain` and `tauRelax`; both are
now known not to come from the sources one would assume.

**Q9 — the θ interpolation is a step function, and `DSEARCH` is why. Now an open core-reference
decision; see §0.**
`MirovaTacticalPlanner:398-402` passes `dFree` (0.365) where the LMRS weighting wants `d_search`
(0.788). Since that is below `dSync` (0.577), the interpolation branch is unreachable and θ_v steps
from 1.0 to 0.0 at `d_mand` instead of tapering. `DSEARCH` was deleted in Phase 0.5 as unread — it was
unread *because of this bug*. `bcDesireInterpolation` is now implemented, default off, `DSEARCH` is
restored, and the campaign runs `bc9_interp` and `coreset-interp`. **The core implements one of the two
behaviours, so until this is decided, so is the core reference.** Adopting it makes `dSearch` the 39th
key. Full detail in [`bc4-and-reference-check.md`](bc4-and-reference-check.md) §2.1.

**Q10 — `Sequence` or an index-based accessor at the port boundary?**
Open until a JMH benchmark of the tick path exists (§2).

---

## 13. Named gaps

Four inventory rows have no contract element, each deliberately. The full mapping is in
[`contract-traceability.md`](contract-traceability.md) §10.

1. **`AnticipateDownstreamMergePattern` stays out of v1.** Reviving it needs one query that does not
   exist: the lane adjacent to a downstream lane drop.
2. **The watchdog has no core presence.** It deletes vehicles, which is a host power the core must not
   have. What follows is worth saying plainly: **ramp deadlocks are a weakness of the model, which OTS
   currently masks by removing the vehicles it produces.** Removal counts are reported in every
   validation. A host without a watchdog exposes the weakness rather than causing it, and the fix is
   in the merge logic, not in the host.
3. **`meanSpeed` may not survive** (Q1).
4. **Wiedemann 99 is host-side by design**, not a gap — inventory §D listed it among the things to
   migrate and has been corrected.
