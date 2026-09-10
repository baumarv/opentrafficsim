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
| — | cache bounded at 16 entries, oldest evicted; observations expire after 50 ticks | not documented |

### The defect: the expiry is counted in ticks

```java
/** Ticks after which an observation is discarded, about 10 s at the configured step. */
private static final long UNOBSTRUCTED_EXPIRY_TICKS = 50;
```

Fifty ticks is ten seconds only while the planner is called every 0.2 s. OTS is event-driven and the
active pattern sets the plan duration — 0.1 s for the mandatory lane change — so under a critical
pattern the memory is five seconds, and under a host with a different step it is anything at all.

**This is the same class of defect as BC-1**, which I wrote a switch for: a time constant expressed in
units of the configured step rather than in elapsed time. I introduced it in the BC-4 commit, in a
mechanism whose entire purpose is to be portable to a host whose step the core does not control.

Not corrected here, because BC-4 is in the pending campaign and changing it now would make the
comparison measure two things. **In the core it is `Duration`, not a tick count** —
`contract.md` §6 already requires elapsed time for every time-dependent quantity, and this is one.
For the OTS run it is a candidate for the next round of switches; it only matters where a vehicle is
under a 0.1 s pattern *and* has a follower it saw running free between five and ten seconds ago.

### One further note

`currentTick()` returns `-1` when the context manager is unavailable. An entry stored with tick `-1`
is `tick - (-1) > 50` from tick 50 onwards, so it expires; an entry read at tick `-1` compares
against a real tick and expires immediately. Both are safe failure modes, but the sentinel is doing
work that `0` would do better. Left alone for the same reason.

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

**Recommendation: a switch `bcDesireInterpolation`, default false, that passes `dSearch` as `dCoop`
and restores `DSEARCH = 0.788`.** It belongs in the pending campaign, because evaluating it later
costs a second campaign. It is not in this commit set: the review asked for one specific study
variant and did not ask for a new switch, and reviving a deleted parameter on my own judgement is
exactly the kind of decision the brief reserves for you. **This has to be decided before the campaign
starts, not after.**

### 2.2 Parameter values: the reference is a calibration, the code defaults are OTS's

| Symbol | Reference (car / truck) | Code default | Freiburg study value |
|---|---|---|---|
| `T` | 0.9 s / 1.2 s | 1.2 s (OTS) | 1.10 s / 1.40 s |
| `a` | 1.2 m/s² | 1.25 m/s² (OTS) | 1.4 / 1.25 m/s² |
| `v_gain` | 15 km/h / 30 km/h | 69.6 km/h (OTS LMRS) | set per study |
| `b_coop` | −2.0 / −0.5 m/s² | −3.0 m/s² | −3.0 / −1.0 m/s² |
| `s_0` | — | 3.0 m (OTS) | 2.0 m / 4.0 m |

Three different value sets, and none of them is wrong: the reference reports the paper's calibration,
the code default is whatever OTS ships, and the study is what actually ran. **The defaults in
`DriverParameterKeys` are currently the code defaults**, which means a host that sets nothing gets
neither the published calibration nor anything the group has validated.

**Open, for you:** should the core's defaults be the paper's calibrated values rather than OTS's?
I lean yes — a standalone library whose defaults come from a simulator it no longer depends on is an
accident waiting to be inherited — but that changes what "default" means for every downstream study,
so it is your call. Recorded as Q8 in `contract.md`.

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

### 2.4 Names that no longer match

The reference calls the discretionary pattern `DiscretionaryLaneChangePattern`; the code has
`SimpleLaneChangePattern`. `AnticipateAdjacentCongestionPattern` was deleted in Phase 0.5, which
matches the reference's own note that it left the paper's scope. Cosmetic, but the standalone
repository is the moment to align them — the naming decision is deferred with the library name.
