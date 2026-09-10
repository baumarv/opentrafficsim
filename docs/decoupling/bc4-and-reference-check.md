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
| 1 | Reference §7: the plan validity period is set by the winning pattern, **~0.2 s parallel and ~0.1 s for critical exclusive patterns** such as the mandatory lane change | **Every plan lasts `DT` = 0.2 s.** `ManeuverPattern` initialises `patternSpecificTimestep` to `params.dtScalar` and *nothing* calls a setter for it; there is no such setter in the tree. | The merge is re-evaluated half as often as the paper says. Two things rested on the shorter step being real: the acceleration a critical manoeuvre can correct within one plan, and the tick-counted BC-4 expiry, which is why that expiry is now elapsed time. **If ~0.1 s was intended, it is unimplemented behaviour, not a documentation slip.** |
| 2 | Reference §4: `DiscretionaryLaneChangePattern` | `SimpleLaneChangePattern` | Naming only; deferred with the library name |
| 3 | Reference §4: `AnticipateAdjacentCongestionPattern` "removed from the current paper scope" | Deleted in Phase 0.5 | None — the two agree |
| 4 | `CLAUDE.md` §5 (before Phase 0.5): two-parameter relaxation, τ_s ≈ 15 s | One buffer, τ = 20 s | Corrected in Phase 0.5; the model reference agrees with the code |
| 5 | Reference §3: θ_v interpolated between `d_mand` and `d_search` | A step at `d_mand`; the interpolation branch is unreachable (§2.1) | Behavioural, and open as Q9 |
| 6 | Reference §6: relaxation "triggered on leader change / cut-in" | Also pre-registered proactively by `LateralExecution.accelerationForLateralMove` for every leader on the lane being entered | The reference understates the mechanism; the code is the richer one |
