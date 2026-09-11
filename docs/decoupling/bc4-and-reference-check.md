# BC-4 implementation check, and what the model reference turned up

Two things, because the second was found while doing the first: the review's request to verify that
`bcFollowerDesiredSpeedEstimated` implements what `contract.md` §3 describes, and a comparison of
`mirova_model_reference.md` — supplied on 2026-09-10 — against the code.

---

## 1. BC-4: code against §3

**Verdict: the code implements what §3 describes, and §3 is incomplete rather than wrong.** Three
things the code does that the document does not mention, and one defect. `contract.md` §3 and
`phase05-report.md` have been amended; the code is unchanged.

Sites: [`NeighborsContext.java`](../../ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/BeliefLayer/NeighborsContext.java) —
`observeUnobstructedFollower`, `expireUnobstructedObservations`, `estimatedFollowerDesiredSpeed`;
consumer [`SocialInteractionsIncentives.java:164-170`](../../ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/DesireLayer/SocialInteractionsIncentives.java#L164-L170).

| §3 says | Code does | Verdict |
|---|---|---|
| "the speed the vehicle was last seen holding while unobstructed" | `unobstructedSpeed[id] = (speedSi, tick)`, overwritten on each new observation | matches |
| "falling back to the legal limit scaled by the mean factor this driver has observed" | `limit × observedFactorSum / observedFactorCount`, factor 1.0 before any observation | matches |
| — | **the criterion for "unobstructed"**: net gap > `s0 + v_follower · T`, using the **ego's** `s0` and `T` because the follower's are not observable, **and** the follower's acceleration ≥ −0.1 m/s² | not documented |
| — | **only the current-lane follower is ever judged**, because only there is the ego the follower's leader. `followerSocialPressure` is called with `CURRENT` (line 120) and `LEFT` (line 95); the `LEFT` call therefore always takes the fallback. | not documented |
| — | **the estimate is floored at the follower's current speed** — a vehicle travelling at *v* wants at least *v* | not documented |
| — | cache bounded at 16 entries, oldest evicted; observations expire after ten seconds (fifty ticks when this was written) | not documented |

### The defect: the expiry was counted in ticks — since corrected

```java
/** Ticks after which an observation is discarded, about 10 s at the configured step. */
private static final long UNOBSTRUCTED_EXPIRY_TICKS = 50;
```

Fifty ticks is ten seconds only while the planner is called every 0.2 s — a property of the
configuration, not of the model. **This is the same class of defect as BC-1**, a time constant
expressed in units of the configured step, and I introduced it in the BC-4 commit, in a mechanism whose
entire purpose is to be portable to a host whose step the core does not control.

**Corrected in `d6ad9521c`:** the expiry is now `UNOBSTRUCTED_EXPIRY_SECONDS = 10.0` against the
simulator clock. The change is behaviour-preserving at the configured step, and that was verified
rather than assumed: `ManeuverPattern` initialises `patternSpecificTimestep` to `params.dtScalar` and
nothing in the tree calls a setter for it, so every plan lasts 0.2 s and fifty ticks is exactly ten
seconds. **The model reference's ~0.1 s for critical patterns is not implemented** — see §2.4, entry 1.

The `-1` tick sentinel went with it. An unreachable simulator now yields `NaN` and the observation is
skipped, rather than being stored against a fake time.

---

## 2. The model reference against the code

`mirova_model_reference.md` is now in `docs/decoupling/`. Its own caveat is that it is partly out of
date, and where it and the code disagree the code wins — but two disagreements are not the document
being out of date.

### 2.1 The θ interpolation is a step function, and `d_search` is why

The reference (§3) describes the LMRS weighting exactly as Schakel et al. do:

> `θ_v,j ∈ [0,1]`: 1 if mandatory/discretionary agree or mandatory is weak (`≤ d_mand`); 0 if they
> conflict and mandatory is strong (`≥ d_search`); linearly interpolated in between.

The code implements that formula correctly in
[`Desire.computeDiscLcWeight`](../../ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/DesireLayer/Desire.java#L285-L301):

```java
if (product >= 0.0 || absMand <= dSync)  { return 1.0; }
if (absMand >= dCoop)                    { return 0.0; }
return (dCoop - absMand) / (dCoop - dSync);
```

and then calls it with the wrong upper threshold
([`MirovaTacticalPlanner.java:398-402`](../../ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/MirovaTacticalPlanner.java#L398-L402)):

```java
double dSync = this.getDMand();   // 0.577
double dCoop = this.getDFree();   // 0.365   <-- the paper's d_search is 0.788
```

Because `dCoop` (0.365) is **below** `dSync` (0.577), any `absMand` that fails the first test
(`> 0.577`) necessarily passes the second (`≥ 0.365`). **The interpolation branch is unreachable.**
θ_v is a step: 1.0 up to a mandatory desire of 0.577, then 0.0.

So the model does not taper the discretionary contribution as route pressure grows; it switches it
off in one step at `d_mand`. That is a knife edge of the kind the merge hysteresis work removed
elsewhere, and it sits in the aggregation every vehicle runs every tick.

It also explains a Phase 0.5 finding I recorded without understanding: `DSEARCH` (0.788) was deleted
as never read. It is never read because this call site passes `dFree` where the model wants
`d_search`. **The parameter was not vestigial — it was orphaned by a bug.**

**Implemented in `3b3a5ad43`** as `bcDesireInterpolation`, default off, with `DSEARCH = 0.788`
restored; the campaign runs it alone as `bc9_interp` and in combination as `coreset-interp`. Which
behaviour the core takes is Q9, and until it is answered the core reference is open with it — see
`contract.md` §0.

### 2.2 Parameter values: the reference is a calibration, the code defaults are OTS's

| Symbol | Reference (car / truck) | Code default | Freiburg study value |
|---|---|---|---|
| `T` | 0.9 s / 1.2 s | 1.2 s (OTS) | 1.10 s / 1.40 s |
| `a` | 1.2 m/s² | 1.25 m/s² (OTS) | 1.4 / 1.25 m/s² |
| `v_gain` | 15 km/h / 30 km/h | 69.6 km/h (OTS LMRS) | set per study |
| `b_coop` | −2.0 / −0.5 m/s² | −3.0 m/s² | −3.0 / −1.0 m/s² |
| `s_0` | — | 3.0 m (OTS) | 2.0 m / 4.0 m |

Three different value sets, and none of them is wrong: the reference reports the paper's table, the
code default is whatever OTS ships, and the study column is what actually ran.

**Settled (Q8).** `DriverParameterKeys` now carries the study column — the car values of the production
set — with `DriverPresets.TRUCK` for the other class. Note that the reference's own table is *not* what
ran either: it gives `T = 0.9 / 1.2 s` and `b_coop = −2.0 / −0.5`, where the campaign used 1.10 / 1.40
and −3.0 / −1.0. The derivation is in [`default-parameters.md`](default-parameters.md).

### 2.3 Two agreements worth recording

**The relaxation.** The reference §6 describes exactly one time constant, τ = 20 s, and says
explicitly that speed-deficit relaxation is not implemented separately. **That matches the code**, and
it settles inventory §C.6, which said "neither document matches the code exactly" on the strength of
`CLAUDE.md` and the ITSC paper. The reference matches. §C.6 is corrected accordingly, and the claim in
`contract.md` §8 that the ITSC paper describes a two-parameter model is removed — per the review, that
statement came from `CLAUDE.md`, not from the paper.

**The acceleration curve.** The reference §6 gives the piece-wise `f(v)` — 3.5 falling to zero at
250 km/h — as a *vehicle* property, scaled `1.3/3.5` for trucks. That is what `EgoState.maxAccelerationAt`
in the contract asks the host for, and it is the reason it is a function of speed rather than a
constant.

### 2.4 Documentation-against-code mismatches

Running list, to be extended by the systematic paper-against-code check once the TR-B and HEUREKA
sources are available.

| # | Documented | Implemented | Consequence |
|---|---|---|---|
| 1 | Reference §7: the plan validity period is set by the winning pattern, **~0.2 s parallel and ~0.1 s for critical exclusive patterns** such as the mandatory lane change | **Every plan lasts `DT` = 0.2 s.** `ManeuverPattern` initialises `patternSpecificTimestep` to `params.dtScalar` and *nothing* calls a setter for it; there is no such setter in the tree. | The merge is re-evaluated half as often as the paper says. Two things rested on the shorter step being real: the acceleration a critical manoeuvre can correct within one plan, and the tick-counted BC-4 expiry, which is why that expiry is now elapsed time. **See the note below: the model as run has one plan duration, and v1 is specified that way.** |
| 2 | Reference §4: `DiscretionaryLaneChangePattern` | `SimpleLaneChangePattern` | Naming only; deferred with the library name |
| 3 | Reference §4: `AnticipateAdjacentCongestionPattern` "removed from the current paper scope" | Deleted in Phase 0.5 | None — the two agree |
| 4 | `CLAUDE.md` §5 (before Phase 0.5): two-parameter relaxation, τ_s ≈ 15 s | One buffer, τ = 20 s | Corrected in Phase 0.5; the model reference agrees with the code |
| 5 | Reference §3: θ_v interpolated between `d_mand` and `d_search` | A step at `d_mand`; the interpolation branch is unreachable (§2.1) | Behavioural, and open as Q9 |
| 6 | Reference §6: relaxation "triggered on leader change / cut-in" | Also pre-registered proactively by `LateralExecution.accelerationForLateralMove` for every leader on the lane being entered | The reference understates the mechanism; the code is the richer one |
| 7 | Reference §8: `a` = 1.2 m/s² | **1.4 car, 1.25 truck** | +17 % for cars. See §3. |
| 8 | Reference §8: `T` = 0.9 s car, 1.2 s truck | **1.10 s, 1.40 s** | +22 % and +17 %. See §3. |
| 9 | Reference §8: `b_coop` = −2.0 m/s² car, −0.5 truck | **−3.0, −1.0** | 1.5× and 2× stronger. See §3. |
| 10 | Reference §8: `f_LC` = 0.5 | **0.40** | −20 %. See §3. |
| 11 | Reference §8: `x_ext` = 1000 m, "extended anticipation look-ahead" | The value stands, the mechanism does not: the look-ahead mutation it fed was measured inert and deleted in Phase 0.5. The parameter now bounds the merge-lane path projection only. | The symbol survives with a different meaning |
| 12 | Reference §8: `v_gain` = 15 km/h car, 30 truck | **RESOLVED by decision.** The model now carries 15 / 30 km/h; the published 15 / 30 m/s survives as the `legacy` variant and the tag `published-model`. | See the history below. |
| 13 | Reference §4 describes `AnticipateDownstreamMergePattern` as part of the pattern library, with two states, a two-threshold activation and priorities 0.15 / 0.25 | **Not registered.** Commented out in `MirovaTacticalPlannerFactory:178` since `acb1ba544` (2026-08-26, "deactivate the downstream merge anticipation"). | The paper describes a pattern no published run contained. The reason is recorded in the code: its activation cannot tell a lane drop from the end of the modelled network, so every vehicle on the final link was pinned at `VCONG`. A paired ten-seed comparison moved speed at `det_L5a` by +29 and +47 km/h with nothing else significant, and one seed in ten collapsed the facility. |
| 14 | Reference §3 lists four incentives; the code contains five | **`SocialInteractionsIncentives` has never been registered** — it does not appear in `setDesireLayer` in any commit of that file, on any branch, since it was written in `b894e7ffd` (2026-06-22). | The reference and the *registration* agree; the **code** carries a fifth incentive that never runs. Its parameters `socio` and the social half of `vGain` are therefore inert, and so is **BC-4**, whose only consumer it is. See §5. |
| 15 | — | Checked and agreeing: the arbitration hysteresis is `HYSTERESIS_MULTIPLIER = 1.10` (`HybridPlanArbitrator:37`), as reference §5 states. | no action |
| 16 | The parameter set a vehicle is built with | **Two sets exist per vehicle**, differing in the `FSPEED` draw; MiRoVA reads one and `LaneBasedGtu.getDesiredSpeed()` the other. | See §6. |
| 17 | Reference §6 and Keane & Gao: a relaxation is "triggered on leader change / cut-in", i.e. by an event that leaves a real headway deficit | **A vehicle seeds a relaxation on its own first tactical tick**, having no remembered leader. `NeighborsContext:1574` treats the first leader ever seen as an ID change -- the comment there says so: *"Check for ID change, EVEN IF lastLeaderId was null"* -- and with no previous leader the reference speed falls back to the ego's own (`:1584`). Any leader slower than the ego then yields `gammaV > 0`. | **9 of 11 relaxations in the TaMA sample.** The buffer is seeded with `max(targetHeadway × fGap, gammaS)` (`EgoContext:278`), which at 1.10 s, 110 km/h and `fGap` = 0.40 is ≈ 13 m and does not depend on the actual gap; with τ = 20 s and the 3τ cap it runs up to 60 s. Every vehicle therefore enters the network relaxed. |
| 18 | As above | **A receding leader can seed a relaxation.** The guard at `:1577` admits a leader that is accelerating and not much slower, and the branch taken depends on `gammaV` -- the *previous* leader's speed minus the new one's -- not on the gap. When `gammaV > 0` the buffer is seeded even though `gammaS` is zero, i.e. although the gap already exceeds the desired headway. | **2 of 11 in the same sample.** A driver tolerating a headway deficit it does not have. |

### The plan duration in v1

**Recorded as a decision, not only as a mismatch: plan validity is `DT` = 0.2 s for every pattern in
v1, because that is the model as run** — every published result was produced with one plan duration,
and specifying the core to a duration that has never executed would make the equivalence test
meaningless.

The ~0.1 s intent is not lost, it is relocated. `TacticalCommand.nextEvaluationAfter` is exactly the
mechanism for it: the active pattern names the interval it wants, per manoeuvre, and an event-driven
host honours it. What the Java model lacked was not the idea but a way for a pattern to say so — the
field it would have used is initialised once and never written. So the core carries the *capability*
from v1 and the *value* stays at 0.2 s until a campaign says otherwise. A host that then honours a
shorter interval is running a different model, and `EvaluationLate` makes the converse visible.

---

## 3. The symbol table, row by row

`mirova_model_reference.md` §8 stands in for the paper's `tab:parameters` until the TR-B source is in.
Every row against the value the production run actually resolves — taken from the registered run, not
from the constants (see `default-parameters.md` §0). Sixteen rows: **nine agree, five deviate
numerically, two agree in value but not in meaning.**

| Symbol | Reference (car / truck) | Runs (car / truck) | |
|---|---|---|---|
| `d_free` | 0.365 | 0.365 / same | ✅ |
| `d_mand` | 0.577 | 0.577 / same | ✅ |
| `d_search` | 0.788 | 0.788 / same, **but never read** | ⚠️ value agrees, the θ call site passes `d_free` instead (§2.1) |
| `v_gain` | 15 km/h / 30 km/h | **54 / 108 km/h** | ❌ the study's bare `15.0` / `30.0` are read as SI, so m/s; factor 3.6 |
| `v_cong` | 60 km/h | 60 / same | ✅ |
| `a` | 1.2 m/s² | **1.4 / 1.25** | ❌ **+17 % for cars** |
| `a_max` | 3.5 / 1.3 m/s² | 3.5 / 1.3 | ✅ |
| `T` | 0.9 s / 1.2 s | **1.10 / 1.40** | ❌ **+22 % and +17 %** |
| `b_coop` | −2.0 / −0.5 m/s² | **−3.0 / −1.0** | ❌ **1.5× and 2× stronger** |
| `b_pre` | −1.0 m/s² | −1.0 / same | ✅ |
| `f_LC` | 0.5 | **0.40** | ❌ **−20 %** |
| `τ_relax` | 20 s | 20 / same | ✅ (and unsourced in both — Keane & Gao give 15 s) |
| `x_stop` | 5 m | 5 / same | ✅ |
| `x_ext` | 1000 m | 1000 / same, **different meaning** | ⚠️ the look-ahead mutation it fed was measured inert and deleted; it now bounds the merge-lane projection only |
| `x_coop` | 100 m | 100 / same | ✅ |
| `t_undercut` | 5 s | 5 / same | ✅ |

### The five numeric deviations

**They are not errors, they are the calibration** — but the paper's table is what a reader will
reproduce from, and none of the four is small enough to be rounding.

**`T` (0.9 → 1.10 s car, 1.2 → 1.40 truck).** The largest single change, and the best documented: the
headway-against-damping grid raised it because at 0.90 s the model broke down in one run in ten on
2025-10-27, a date on which the site does break down. It cannot be read alone — the damping was
switched off in the same step, and the two axes work against each other on the discharge rate. A
reader who takes 0.9 s from the table *and* the damping from the code gets a model that breaks down.

**`b_coop` (−2.0 → −3.0 car, −0.5 → −1.0 truck).** The largest *relative* change, and the one flagged:
trucks cooperate twice as hard as the table says, cars half again. This is the parameter the
calibration notes say is clearly worse when strengthened — "a gap opener braking harder holds up the
column behind it" — and yet the values that run are the stronger ones, because the note concerns
strengthening them *beyond* the calibrated pair. Worth stating plainly, because table and note read as
contradicting each other unless the baseline is known.

**`a` (1.2 → 1.4 car).** Cars accelerate 17 % harder than the table. The code cites Kesting for 1.4,
the reference gives 1.2 without a source; trucks are 1.25 by a factorial that found this the strongest
axis of every one tested.

**`f_LC` (0.5 → 0.40).** The lane-change safety-distance reduction is 20 % tighter than documented,
and it interacts multiplicatively with `s0` — so this row and the `s0` row cannot be read
independently of each other.

**`v_gain` (15 → 54 km/h car, 30 → 108 truck) — resolved, and the resolution is a decision.** Not a
calibration at all but a unit: the study wrote a bare `15.0` and the generator read bare numbers for a
speed as SI. Marvin has decided that **the table is right and the setter was wrong**: 15 and 30 km/h
were intended and are now the model values.

Three things follow, and all three are in place:

1. **The published results are preserved.** They were produced at 15 / 30 m/s. The tag
   `published-model` marks the last commit with that parameterisation, and the `legacy` variant of
   both studies reproduces it explicitly, so no checkout is needed to run it.
2. **The trap is closed** so that this cannot recur: a bare number for a unit-typed parameter is now
   refused, naming the key and the unit it would have assumed.
3. **The model is uncalibrated at the new value.** The headway, the damping and the safety-distance
   factor were calibrated against a speed gain 3.6× larger, and some of them may have been
   compensating for it. `recalibration-inventory.md` records what would have to be redone.

### The two that agree in value only

**`d_search` = 0.788** is in the code and in the table, and is never read, because the θ call site
passes `d_free` (§2.1). A reader reproducing the model from the table would implement the taper the
table describes and get different behaviour from the code — arguably *more* correct behaviour, which
is what Q9 has to decide.

**`x_ext` = 1000 m** survives as a number while the mechanism behind it changed twice: the look-ahead
mutation it fed was measured inert (100.0000 % of 136.5 M calls served from a stale memo) and deleted,
and BC-6 bounds what remains to the driver's own perception. The symbol in the table no longer names
what the code does with it.

---

## 5. What the production configuration actually registers

Evidence for the whole section:
[`MirovaTacticalPlannerFactory.java:140-180`](../../ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/MirovaTacticalPlannerFactory.java#L140-L180).
Identical at the tag `published-model`, verified with `git show published-model:…` — so this is what every
published run contained.

### Desire layer — four of five

| Incentive | Registered | Evidence |
|---|---|---|
| `CruisingSpeedIncentive` | **yes** | `:141` |
| `KeepRightIncentive` | **yes** | `:142` |
| `RouteIncentive` | **yes** | `:143` |
| `ProhibitDeadEndIncentive` | **yes** | `:144` |
| `SocialInteractionsIncentives` | **no — never** | absent from `setDesireLayer`; `git log -S` over that file on all branches returns nothing since the class was written |
| `CongestionIncentive` | n/a | deleted in Phase 0.5, unregistered before that |

### Intention layer — four of five

| Pattern | Registered | Evidence |
|---|---|---|
| `SimpleLaneChangePattern` (the reference's *Discretionary*) | **yes**, exclusive | `:155` |
| `PreventUndercuttingPattern` | **yes**, parallel | `:158` |
| `MandatoryLaneChangePattern` | **yes**, exclusive | `:159` |
| `GapOpenerPattern` | **yes**, parallel | `:160` |
| `AnticipateDownstreamMergePattern` | **no** | `:178`, commented out with the ten-seed evidence, since `acb1ba544` (2026-08-26) |
| `AnticipateAdjacentCongestionPattern` | n/a | deleted in Phase 0.5; the reference notes it left the paper's scope too |

### Correcting one attribution

**`GapOpenerPattern` is registered and live.** The ten-seed comparison in the factory comment belongs to
`AnticipateDownstreamMergePattern`, which sits directly below it. `GapOpenerPattern` was registered in
`44d04e6c1` and has never been removed; its `checkAbility()` returns `true` unconditionally and its
`checkContext()` fires whenever a neighbour with an active indicator is within `x_coop` = 100 m. The only
gate inside it is `cooperativeLaneChangesEnabled`, and that guards one branch only — the *evasive lane
change* in `evasiveChangePossible` (`:433`). The production set switches that off for trucks, so **trucks
open gaps but do not dodge sideways**; cars do both.

### What this does to BC-2 and BC-4

**BC-2 is live.** Its site is `GapOpenerPattern.leaderCanCooperate`, reached from `findNewCandidate`
(`:234`) and from the running state (`:522`). The `coreset` variant measures a real change.

**BC-4 is inert.** Its only consumer is `SocialInteractionsIncentives.followerSocialPressure`, in the
incentive that is never registered. **`bcFollowerDesiredSpeedEstimated` therefore changes nothing in any
run — including `coreset` and `bc4_followerdesired`.** The campaign will measure exactly zero there, and
that is a property of the registration, not a null result about the estimator.

Three consequences follow, and they are not cosmetic:

1. **`contract.md` §0 overstates the core's reference model.** `coreset` differs from `reference` by BC-1,
   BC-2, BC-6 and BC-8 — four switches, not five.
2. **The contract's `PerceivedVehicle` argument for dropping `desiredSpeed` still holds**, but the
   *justification changes*: the field is not observable, and in this configuration nothing reads it either.
3. **If the social incentive is meant to run, it is a behaviour change to register it** — a large one, since
   `socio` and the social half of `vGain` would come alive at once. That is a decision, not a fix.

---

## 6. Two parameter sets per vehicle

**Every production vehicle is built with two `Parameters` objects, and MiRoVA reads the one the GTU does
not.**

The order in
[`LaneBasedStrategicalRoutePlannerFactory.create`](../../ots-road/src/main/java/org/opentrafficsim/road/gtu/strategical/LaneBasedStrategicalRoutePlannerFactory.java#L126-L133):

```java
LaneBasedStrategicalRoutePlanner strategicalPlanner = new LaneBasedStrategicalRoutePlanner(
        this.tacticalPlannerFactory.create(gtu), route, gtu, origin, destination, this.routeGenerator);
gtu.setParameters(nextParameters(gtu.getType()));   // <-- after the planner exists
```

and inside `MirovaTacticalPlannerFactory.create` (`:66-79`):

```java
gtu.setParameters(getParameters());                 // set A
MirovaTacticalPlanner planner = new MirovaTacticalPlanner(...);   // captures A
setDesireLayer(planner);                            // the incentives capture A
```

`nextParameters` returns the *peeked* set — a second, independent call to
`MirovaTacticalPlannerFactory.getParameters()` — so the GTU ends up holding **set B** while the planner,
its snapshot and its incentives hold **set A**.

### What differs between A and B

**Exactly one value: `FSPEED`.** Both sets come from the same `getParameters()`, which applies the same
deterministic `car.*` / `truck.*` overrides; the strategical factory's `ParameterFactory` in this path is
`ParameterFactoryDefault`, whose `setValues` is empty. The one non-deterministic element is
`AbstractIdmFactory.getParameters`, which draws `FSPEED` from `N(123.7/120, 0.1)` **on every call**.

### Who reads which

| Reader | Set | Evidence |
|---|---|---|
| `MirovaCarFollowingUtil`, `LongitudinalControl` — every car-following call | **A** | `vehicle.getParameters()` where `vehicle` is the planner (`MirovaTacticalPlanner:606`) |
| `MirovaParameterSnapshot` — every constant parameter | **A** | `MirovaTacticalPlanner:154-155` |
| The desire layer | **A** | `DesireIncentive:80` captures `gtu.getParameters()` **at construction**, which is inside `create`, before B is installed |
| `LaneBasedGtu.getDesiredSpeed()` → `EgoContext.getDesiredSpeed()` → the whole belief layer | **B** | `LaneBasedGtu:1463` passes `getParameters()`; `EgoContext:425` calls `gtu.getDesiredSpeed()` |
| `LaneBasedGtu.getCarFollowingAcceleration()` (OTS-internal, and the skip-this-tick path) | **B** | same |

So the desired speed the belief layer believes in is evaluated with a different `FSPEED` than the
car-following that acts on it. That is precisely the reported symptom.

### Are the published results affected?

**In principle yes, in practice only in the tail — and this is an estimate from the distributions, not a
measurement.**

Desired speed is `min(limit × FSPEED, maxVehicleSpeed)`. The two sets can differ only where the `FSPEED`
term binds:

- **Cars.** Limit 200 km/h, so `200 × FSPEED ~ N(206, 20)` km/h, against a drawn maximum speed of
  80–200 km/h with a median near 133. The term binds only for cars drawn near the top of that
  distribution — about 6 % of cars exceed 180 km/h — and then only when the draw falls low. A rough
  product of the two gives **on the order of 1–2 % of cars**, with a difference of a few km/h where it
  occurs.
- **Trucks.** Limit 120 km/h, `120 × FSPEED ~ N(123.7, 12)`, against a maximum speed drawn uniformly over
  79–100 km/h. `P(120 × FSPEED < 100) ≈ 2.4 %`.

It affects the free-flow speed choice of the fastest few percent of vehicles, which does feed capacity,
but not by a mechanism that would move a calibration target. **It cannot be dismissed without measuring,
and it should not be corrected before the campaign** — correcting it changes behaviour, and the campaign
is running.

### Does the resolved-parameter comparison reflect what vehicles used?

**For every key it compares, yes.** All 27 behavioural keys are set deterministically through the
`car.*` / `truck.*` overrides and are identical in A and B; the comparison resolves them through the same
`applyParameter` path the generator uses.

**`FSPEED` is outside it, by construction.** No study sets it, so it never appeared in the comparison, and
it is the one value that differs between the two sets. A comparison over resolved *overrides* cannot see a
parameter that is drawn rather than set — which is worth remembering the next time such a comparison is
used as evidence.

### For the core

`DriverParameters` is resolved once at construction and there is exactly one set per agent, so this defect
is unrepresentable there — which is the argument for the design, not an accident of it. The host draws
`fSpeed` once and passes it in.

---

## 7. The two relaxation entries in detail

Observed by the TaMA recording (11 relaxations in the sample, 9 + 2 as above); the *mechanism* below is
traced in the code here, the *counts* are theirs and are not re-measured.

Both follow from one line and one branch.

**The trigger is an identity change, and the first identity counts as a change.**

```java
// NeighborsContext:1573-1574
// BUGFIX: Check for ID change, EVEN IF lastLeaderId was null.
if (!currentId.equals(this.lastLeaderId))
```

`lastLeaderId` is `null` until a leader is seen, so the first leader a vehicle ever perceives is an
"identity change". The reference speed then falls back to the ego's own speed (`:1584`), so a leader any
slower than the ego produces `gammaV > 0`. A vehicle generated behind slower traffic is relaxed from its
first tick.

**The seeded buffer does not depend on the gap.**

```java
// EgoContext:275-280
if (gammaV.si > 0.0)
{
    triggerRelaxation(newLeader.getId(), Length.max(targetHeadway.times(safetyDistanceReductionFactor), gammaS), ...);
}
```

The speed-deficit branch seeds `max(T·v·fGap, gammaS)` — a *fraction of the desired headway*, not the
measured deficit. When the gap already exceeds the desired headway, `gammaS` is zero and the buffer is
still `T·v·fGap`. That is the receding-leader case, and it is also why the first-tick buffer is ≈ 13 m
whatever the vehicle is actually following.

**Is this a defect?** It is at least a divergence from what both the reference and Keane & Gao describe,
where relaxation is the decay of a deficit that exists. Seeding a deficit that does not exist is a
different model. But `docs/decoupling/inventory.md` §C.6 already records the deliberate part of this: the
speed deficit is routed into the headway buffer *because tolerating it directly produced collisions*, and
the seeding constant is the calibrated `fGap`. **Whether the first-tick case is intended is a question for
the author, not a finding**: it makes every vehicle enter relaxed, which raises the discharge rate the
calibration was fitted against — so changing it is a behaviour change of the campaign-sized kind, not a
tidy-up.

For the core contract: `contract.md` §8 specifies relaxation as the decay of a headway deficit opened by a
cut-in. **If the first-tick seeding is kept, the contract understates the model** and §8 needs a sentence
saying that a relaxation is also opened when a driver first acquires a leader. Recorded here rather than
changed, for the same reason.
