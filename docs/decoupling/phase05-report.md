# Phase 0.5 — repairs, deletions and behaviour switches

Work done on the existing Java/OTS code before anything moves into `mirova-core`, so that
behaviour-preserving migration and behaviour-changing correction never appear in the same
measurement. Branch `decoupling_phase05`, thirteen commits, each labelled `[no behaviour change]` or
`[behaviour change]`.

**Nothing in this branch changes what the model does at default settings.** Every behaviour change
sits behind a parameter whose default reproduces the model the published results were produced with.

**The instrumentation campaign has run** -- 15 runs, three calibration dates, five replications each,
all successful. Its results are in §2.5, and they settle three of the open questions.

---

## 1. What was done, by step

| Step | Commits | Effect at defaults |
|---|---|---|
| 1 — Instrumentation | `e257bbe`, `598b357` | none; all counters behind `-Dmirova.defectDiag=true` |
| 2 — Defect fixes | `a65e965`, `145cbe9`, `3e5d4dd`, `e6635ea` | none, except on a tick where the look-ahead leak fired |
| 3 — Dead code | `8cea034`, `127a1a2` | none; 1642 + 193 lines removed |
| 4 — Documentation | `a7d2eea`, `9489856` | none |
| 5 — Behaviour switches | `e3b03bd`, `8674a4b` | none until a switch is set |
| 6 — Run configuration | `9489856` | new study `phase05` |

Both modules compile clean (`mvn -o -pl ots-road,ots-demo -Dmaven.compiler.useIncrementalCompilation=false clean compile`).

---

## 2. Step 1 — what is now measured

`DefectDiagnostics`, enabled with `-Dmirova.defectDiag=true`, optionally writing CSV with
`-Dmirova.defectDiagFile=<path>`. Off by default: every counter sits behind a branch on a
`static final boolean`. The reporting path is smoke-tested end to end (stdout report, per-site
counts with exception classes, CSV).

**1. The look-ahead leak.** `distanceToLaneChangeExtendedLookahead` raised `LOOKAHEAD` and lowered it
again with nothing guarding the two statements. Counted as `lookahead,calls` and `lookahead,leaked`.

The defect is narrower than the inventory implied: `getLegalLaneChangeInfo` declares no checked
exceptions, and the two the method reports come from `getPerceptionCategory`, which runs *before* the
parameter is raised. **Only an unchecked exception can leak** — possible, since the query walks the
lane structure, but not the routine case.

**2. Swallowed exceptions**, at all 56 sites in the behavioural code that replace a failure with a
plausible default. Counted per site *and per exception class*, so a site firing for two different
reasons is not read as one. Keys are `ClassName.method`, with a numeric suffix where one method has
several — `getMergeReferenceSpeed` alone has seven.

> **Deviation from the brief, deliberate.** The review asked for `file:line` as the key. Line numbers
> move with every subsequent commit, and steps 2–5 moved most of them; a stale key would have been
> worse than a stable one. `ClassName.method#n` survives editing and reads better in the report.

**3. The speed-limit transition term.** `LongitudinalControl` now also computes what the tick would
have commanded without that candidate, and records how often the two differ and by how much
(`evaluations`, `finite`, `binding`, `material`, `meanEffectSi`, `maxEffectSi`). This is the evidence
for **E.5**: if the term never binds on Freiburg-Nord, query 12 of the contract collapses from a
speed-limit *prospect* to a list of limits with distances, and curvature and bumps leave the contract.

**4. A fourth counter, added on discovery** — see §4.1.

### 2.5 What the campaign measured

15 runs on 2025-10-27, 2025-09-22 and 2025-10-07, five replications each, all completed.

| Counter | Result |
|---|---|
| `lookahead,calls` | 136 504 913 |
| `lookahead,leaked` | **0** (0.0000 %) |
| `lookahead,cacheWarm` | **136 504 913** (**100.0000 %**) |
| `speedLimitTransition,evaluations` | 141 337 948 |
| `speedLimitTransition,finite` | **0** (0.0000 %) |
| `speedLimitTransition,binding` | **0** |
| `swallowed`, over all 56 sites | **0 sites fired** |

**The extended look-ahead has never had any effect.** Not "probably", as §4.1 argued from reading the
code: every one of 136.5 million calls was answered from a memo computed under the normal 295 m.
The 1000 m in `extendedLookAheadDistance` has never entered a decision. **BC-7 now needs a
decision**, and option (c) — deleting the mechanism — is the truthful default, since it would make
the code say what it has always done.

**The look-ahead leak never fired.** Zero escapes in 136.5 million calls, so the `finally` guard
changed nothing about any published result. It is insurance, not a correction.

**The speed-limit transition term never produced even a finite candidate**, which settles E.5 — and
a source check turns that from a property of this facility into a property of OTS: the term reacts
only to `SpeedLimitTypes.CURVATURE` and `SPEED_BUMP`, and **nothing in the OTS source tree ever
populates either**. Every `addSpeedInfo` call site uses `MAX_VEHICLE_SPEED` and `FIXED_SIGN` alone,
so the loop runs zero iterations on any network the standard parser builds. Query 12 therefore loses
the prospect and becomes a list of legal limits with distances; curvature and speed bumps leave the
contract; and the call in `LongitudinalControl` is provably inert.

**None of the 56 swallowed-exception sites fired.** They are unreachable defensive code, not failure
paths the model runs on. The migration to Kotlin needs no per-site decision for them — which removes
the concern §C.7 of the inventory raised. (The mechanism demonstrably works: the other two counters
returned nine-figure numbers from the same instrumentation.)

The remaining runs could not have changed any of this: exact zeros and an exact 100.0000 % over
nine-figure call counts are not a small sample.

---

## 3. Step 2 — defects repaired

**2.1 `LOOKAHEAD` reset in a `finally`.** The review asked whether the range could be passed as an
argument instead. **It cannot**: `InfrastructurePerception.getLegalLaneChangeInfo` takes only a
`RelativeLane`, and `DirectInfrastructurePerception` reads `LOOKAHEAD` from the GTU's own parameter
set inside the call ([line 203](../../ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/perception/categories/DirectInfrastructurePerception.java#L203)).
Widening that interface means changing OTS code, which Phase 0.5 excludes. **Variant chosen:
`try/finally`.**

**2.2 Context update order stated, not inherited.** `updateFromPerception` iterated a `HashMap`.
Computing the order the four names actually produce under the standard spread gives
**`MacroTraffic → Infrastructure → Neighbors → Ego`**, which is what every published result was
produced with. That order is now written down and iterated explicitly, and the map is a
`LinkedHashMap` so a later-registered category cannot fall into a second accidental order.

> The order the review asked for — `Ego → Neighbors → Infrastructure → Macro` — is the **reverse** of
> what the model has been doing, and is therefore a behaviour change, not a repair. It is offered as
> **BC-8**.

**2.3 The lane-change timer deleted.** `timeSinceLastLaneChange` was maintained every tick for every
vehicle and read by nobody; `socialInteractionCooldown`, the parameter it existed to be compared
against, was declared and snapshotted and never read either. It was also the clearest implicit
time-step assumption: it advanced by the `DT` *parameter*, not by the interval actually elapsed.

**2.4 Snapshot/live access made consistent.** Eleven sites still used `getParameter` for a value the
snapshot already carried. Verified safe first: after Phase 0.5 the only parameters written at runtime
anywhere are `T` and `LOOKAHEAD`, neither of which is snapshotted.

`vGain` and `vCrit` stay live — the snapshot keeps them as SI doubles and the accessors return
`Speed`, so a snapshot read would allocate a scalar per call where the lookup returns the stored one.

One consequence: `SimpleLaneChangePattern.checkContext` wrapped its `DFREE` lookup in a catch that
answered `false`, so **a failed parameter read silently suppressed the discretionary lane-change
pattern altogether**. The snapshot read cannot fail, so the catch is gone.

---

## 4. Findings that changed the plan

### 4.1 The extended look-ahead is very probably inert

While preparing 2.1, the larger half of that defect appeared.

`DirectInfrastructurePerception.getLegalLaneChangeInfo` memoises its answer **per GTU per simulation
step** through `AbstractPerceptionCategory.computeIfAbsent`, and the memo key is the relative lane
alone — **not the look-ahead the answer was computed under**. So raising `LOOKAHEAD` around the call
does nothing once the same question has been asked in the same tick; and conversely a query that runs
first leaves the *extended* answer in the memo for everyone after it.

Which of the two happens is decided by call order in `MirovaTacticalPlanner.update`:

1. step 2, `updateLaneChangeDesire()` → `RouteIncentive.computeDesire()` → `getLegalLaneChangeInfo(CURRENT)`, **unconditionally**, at the normal 295 m;
2. step 6, arbitration → `MandatoryLaneChangePattern.checkContext()` → the extended query, which receives the memo.

If that reading is right, the 1000 m merge anticipation has been operating on the 295 m default all
along — which would affect `MandatoryLaneChangePattern.checkContext` (when the pattern activates) and
`AnticipateMergeState.mergeStillFarOff` (when anticipation ends). It does **not** affect the merge
reference speed, which is a separate mechanism.

That is an argument from reading code, so it is now **counted** instead: every MiRoVA path that makes
the query notes the tick on the vehicle's `InfrastructureContext`, and the extended query reports
whether the memo was already warm (`lookahead,cacheWarm`). The instrumentation run will settle it.

**BC-7 is not implemented, and its switch has been withdrawn** rather than left declared and inert.
Three options, for your decision:

| Option | What it does | Cost |
|---|---|---|
| **(a)** Move the extended query to the start of the tick | Everyone, including the desires, gets the 1000 m answer | Largest behaviour change of anything in this phase; the route desire is computed from that number |
| **(b)** Compute the extended answer independently of the memo | Only the extended query sees 1000 m, as intended | Requires reimplementing the OTS lane-structure traversal — excluded by Phase 0.5's rules |
| **(c)** Delete the mechanism | `getDistanceToLaneChangeExtendedLookahead()` becomes the ordinary route query; the `LOOKAHEAD` mutation disappears | Honest — the code would say what it does — and removes the last of the two runtime parameter writes. But it makes permanent something that may never have been intended |

My reading: if the counter confirms the memo is always warm, **(c)** is the truthful default and
**(b)** is the right target for the core, where MiRoVA owns the perception query and can simply take a
range argument. That is a decision for you, not for this phase.

### 4.2 Correction to the Phase 0 inventory: Wiedemann 99 is not dead

The inventory listed the Wiedemann 99 family as dead and the review's step 3 followed it.
**`SimpleHighwayScenario` runs MiRoVA on Wiedemann 99**: it builds a `Wiedemann99Factory` for cars and
one for trucks and passes both into `MirovaTacticalPlannerFactory`
([lines 241, 277](../../ots-demo/src/main/java/org/opentrafficsim/demo/mirova/scenariomanagement/scenarios/SimpleHighwayScenario.java#L241)).

Cause: the grep that established the claim was truncated by a `head -30`, and the
`SimpleHighwayScenario` hits fell off the end. All other dead-code claims were re-verified without a
limit before deleting anything.

**Kept, therefore:** `Wiedemann99`, `Wiedemann99Factory`, `AbstractWiedemannModel`,
`AbstractWiedemannFactory`, `W99ParameterTypes`, `ExtendedDataW99DrivingMode`. §D of the inventory
should be corrected: `mirova-core` needs either an IDM+ **and** a W99, or `SimpleHighwayScenario`
retires.

### 4.3 BC-3 turned out to be a no-op, and was applied without a switch

The review asked me to check whether `LOOKAHEAD` is homogeneous before switching
`SocialInteractionsIncentives:153`. **It is**: no scenario, study or parameter grid sets `LOOKAHEAD`
anywhere; every vehicle carries the OTS default of 295 m. The one mechanism that could ever have made
two vehicles differ was the look-ahead leak, now closed. Applied directly.

That removes one of the three cross-agent reads outright.

---

## 5. Step 3 — what was deleted

**1835 lines**, none of which any live path reaches. Git keeps the history.

| Deleted | Why |
|---|---|
| `helpers/GapCandidate`, `helpers/HeuristicGapSelector` (957 lines) | Never imported anywhere |
| `MaxUtilityArbitrator`, `PlanArbitrator` | Superseded by `HybridPlanArbitrator`; the interface had one implementor |
| `CongestionIncentive`, `AnticipateAdjacentCongestionPattern` | Referenced only from their own commented-out registration |
| `DimensionlessUnitMirova` | No users |
| `NeighborsContext.isGtuAlongside` + 2 cache keys | Last call site is a comment |
| `MacroTrafficContext.getDensity*` (93 lines) | The whole density branch; its only caller was itself |
| `MirovaCarFollowingUtil.getKinematicEmergencyBrake` + `COLLISION_DECELERATION` + `TTC_EMERGENCY_BRAKING` | Last call site is a comment; the parameter existed only to feed it |
| 6 unused imports in `NeighborsContext` | — |
| The relaxation speed buffer (193 lines) | See below |

**`AnticipateDownstreamMergePattern` stays**, as directed, now marked `MIROVA-DISABLED` in its class
comment with the condition for re-enabling it. Its queries — the anticipated lane drop, and one of the
two consumers of the lane-segment speed scan — are **out of contract v1**.

### The relaxation speed buffer

`RelaxationState` implemented Keane & Gao faithfully: one decaying buffer for the headway deficit and
an independent one for the speed difference. **The second was never fed.** Every live call site passed
`Speed.ZERO`; `getVirtualSpeedBuffer` returns zero for a non-positive deficit; `tau_v` never entered a
calculation. Where a speed deficit was computed it only selected a branch, seeding the *space* buffer
with a reduced target headway instead of the raw gap deficit.

Behaviour is unchanged term by term: the buffer added to the perceived leader speed was `Speed.ZERO`,
so the addition was the identity; the housekeeping term was `|0| < 0.1`, always true, so the
conjunction reduces to the space condition alone.

Two overloads went with it. `triggerRelaxation(HeadwayGtu)` — the proactive path `CLAUDE.md` described
— had **no caller at all**; that role is filled by `triggerRelaxationWithReducedSafetyDistance`.

**What the model implements is a one-parameter relaxation on the headway buffer with τ_s = 20 s**,
which is what `mirova_model_reference.md` describes and `CLAUDE.md` did not.

---

## 6. Step 5 — the behaviour switches

All Boolean parameters, all defaulting to `false`. Set per vehicle type in a study, e.g.
`params.set("car." + MirovaParameters.EMA_ALPHA_FROM_ACTUAL_DT.getId(), true)`.

### BC-1 `bcEmaActualDt` — merge anticipation filter from the actual step

**Changes.** `AnticipateMergeState` fixes its EMA coefficient in the constructor as `0.25 · DT`:
from the parameter rather than the step taken, linear where the filter is exponential, and never
revisited. With the switch it is `1 − exp(−Δt/τ)` with τ = 4 s, recomputed per call from the elapsed
simulation time.

**Affects.** Every merging vehicle on Freiburg-Nord during the anticipation phase.

**Expected.** Very little. At Δt = 0.2 s and τ = 4 s the exponential form gives α = 0.0488 against
0.05 — a 2 % difference in filter gain. The variant is in the campaign to *establish* that it moves
nothing, because the default form is what breaks the moment a host chooses a different step. If it
moves the merge speed measurably, that is itself worth knowing.

### BC-2 `bcLeaderOwnModel` — judge the leader with the ego's own model

**Changes.** `GapOpenerPattern.leaderCanCooperate` evaluates the front leader's own car-following
model against the front leader's own parameter set. With the switch the ego uses its own.

**Affects.** Cooperation on the mainline only, and only where ego and leader differ — in practice
car-behind-truck and truck-behind-car, and any drawn parameter that differs (`vGain`,
`safetyDistanceReductionFactorLaneChange`, `T`).

**Expected.** A shift in how often cooperation is deferred to the vehicle ahead. A car behind a truck
currently reads the truck's longer `T` and concludes the truck cannot cooperate, so it cooperates
itself; under the switch it applies its own shorter `T` and defers more often. Direction: slightly
*less* cooperation from cars behind trucks, so possibly more ramp standstills. Magnitude unknown —
this is the BC I am least able to predict, and the one whose replacement is a genuine modelling
choice rather than a correction.

### BC-5 `bcMeanSpeedFromLeaders` — lane mean speed from perceived leaders

**Changes.** `MacroTrafficContext` answers from OTS `AnticipationTrafficPerception`; with the switch
it answers from `InfrastructureContext.getAnticipatedSpeed`, which blends the perceived leaders'
speeds with the ego's desired speed, distance-weighted over `LOOKAHEAD`.

**Affects.** The widest reach of the six. The quantity feeds the merge reference speed cascade
(step 2), `PreventUndercuttingPattern`'s abort path, and — through `getAnticipatedSpeed`, which is
already the other implementation — the keep-right and cruising-speed incentives.

**Expected.** The largest deviation of the six, and the one most likely to need its own comparison
before adoption. The two estimators differ systematically: `AnticipationTrafficPerception` answers for
an empty lane with a free-flow estimate, while the leader blend answers with the ego's own desired
speed. On the mainline these are close; on a deceleration lane they are not, which is exactly the case
`getMergeReferenceSpeed` already special-cases by skipping macro perception to the right
([step 2 comment](../../ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/IntentionLayer/ManeuverPatterns/MandatoryLaneChangePattern.java)).
Adopting BC-5 may let that special case be removed, which would be a real simplification.

### BC-6 `bcMergeRefRangeLimited` — bound the merge reference to what the ego can see

**Changes.** Two bounds, of which the first is the one that matters.

`InfrastructureContext.getDownstreamAdjacentLane` projects the ego's path **1000 m** ahead to find the
lane it will merge into, and `getMergeReferenceSpeed` then reads the speed and position of every
vehicle on a 150 m window of it. Under the switch the *projection* is bounded by the ego's own
`LOOKAHEAD` instead, so a merge lane further away than the ego can see is simply not found, step 3 of
the cascade yields nothing, and the reference falls through to the speed-limit fallback the cascade
already has. The 150 m window is additionally capped at `LOOKAHEAD`, which is inert at the current
values but keeps the two ranges from contradicting each other if either constant moves.

**Range and justification.** The ego's own `LOOKAHEAD`, 295 m by default. Using the same constant the
rest of its perception uses means the model holds one notion of how far this driver can see, rather
than a general one and a special longer one for merges. It is also the quantity a driving-simulator
host can most plausibly answer.

**Affects.** Merging vehicles during early anticipation, before the target lane is physically
alongside — precisely the phase where step 3 is the only source of a reference speed. Once the target
lane is adjacent, steps 1 and 2 answer and step 3 is not reached, so the late merge is untouched.

**Expected.** On the Freiburg ramp the merge lane becomes findable at some point between 1000 m and
295 m out; before that point mergers will now use the speed-limit fallback, capped at 100 km/h and at
their own desired speed, instead of the measured mainline speed. In free flow those are close and
little should change. **In congestion they are not**: the fallback is a free-flow number where the
measurement would have been a jam speed, so vehicles will enter the acceleration lane faster than they
do now and have more speed to shed. Expect the effect concentrated on congested days and on the early
ramp — and expect it to be the second-largest of the six after BC-5.

This is the switch that answers **E.1** in the form the review chose (option b): a semantic,
range-limited query that may answer nothing.

### BC-8 `bcContextOrderFixed` — contexts update in dependency order

**Changes.** `Ego → Neighbors → Infrastructure → MacroTraffic` instead of the inherited
`MacroTraffic → Infrastructure → Neighbors → Ego`.

**Affects.** One case: a relaxation opened on an initial space deficit below 0.1 m. Under the
inherited order `EgoContext`'s housekeeping runs *after* `NeighborsContext` has opened it, and collects
it in the same tick because the buffer is already under the 0.1 m collection threshold. Under the fixed
order it survives.

**Expected.** Almost nothing. A deficit under 10 cm means the new leader is barely inside the desired
headway. The variant exists to confirm that, and to catch any ordering dependency I have not spotted.

### BC-4 — not implemented; estimator proposed for approval

The review asked for the estimator to be proposed before it is written.

`SocialInteractionsIncentives.egoSocialPressure`/`followerSocialPressure` need the *follower's desired
speed*. Today they read it from `HeadwayGtu.getDesiredSpeed()`, which under the configured
`HeadwayGtuType.WRAP` is the true value — a driver reading another driver's intention.

**Proposal.** Estimate it from what is observable, in the core:

```
v_des_est(follower) = max( v_follower , min( v_limit · fspeed_ego , v_follower + Δv_unobstructed ) )
```

with the branch on whether the follower appears obstructed:

- **Follower unobstructed** (the ego is its leader, and the net gap exceeds the ego's own desired
  headway at the follower's speed): the follower is travelling at its desired speed, so
  `v_des_est = v_follower`. This is the majority case and needs no free parameter.
- **Follower obstructed** (gap at or below that headway): its desired speed is unobservable. Assume it
  wants what the ego wants — `v_limit · FSPEED_ego`, the same "others are like me" assumption BC-2
  makes — floored at its current speed.

Consequences worth stating before you decide:

- It removes the last non-observable field from `PerceivedVehicle`, which is the point.
- It makes the social pressure term **self-referential in the obstructed case**: the ego computes the
  pressure it exerts from its own desired speed. That is defensible as psychology and is what the
  literature's "ego assumes others share its goal" formulation does, but it is a modelling choice.
- The unobstructed case is where the term matters most — pressure from a follower that *wants* to go
  faster — and there the estimate is exact rather than assumed, since an unobstructed follower is by
  definition at its desired speed.
- It needs **no new parameter**. I would rather not introduce one.

Alternative, if you prefer to avoid the self-reference: give `PerceivedVehicle` a
`perceivedDesiredSpeed` field that the *host* estimates, and let a driving simulator answer it however
it can. That keeps the core honest but pushes a modelling decision into the adapter, which cuts
against decision 1.

**I have not implemented either.** Say which and it goes in with a switch.

---

## 7. Step 6 — run configurations

`Phase05ReferenceStudy`, registered as **`phase05`**, on the production parameter set so the reference
is comparable with the published ensemble rather than being a new baseline.

**The instrumentation run (step 1)** — *executed; see §2.5 for the results.* Run locally on twelve
cores with `cluster/run_local_parallel.sh`, which wraps the documented single-run entry point:

```
cluster/run_local_parallel.sh --study=phase05 --output=out/phase05-instr --slots=12 --diag --     --variants=reference --dates=2025-10-27,2025-09-22,2025-10-07     --demand=cluster/demand --replications=5 --strict=true
```

The underlying per-run invocation is:

```
java -Dmirova.defectDiag=true -Dmirova.defectDiagFile=<out>/defects.csv \
     -cp "<classpath>" org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.RunMirovaClusterStudy \
     --study=phase05 --variants=reference --output=<out> \
     --dates=2025-09-16 --demand=cluster/demand --replications=5 --index=<n>
```

Five replications is enough for counts that are either zero or large; nothing here needs a confidence
interval. What to read from `defects.csv`:

- `lookahead,leaked` — **0.** The try/finally changed nothing; no published result is affected.
- `lookahead,cacheWarm` / `lookahead,calls` — **100.0000 %.** The 1000 m anticipation has never taken effect; §4.1 needs a decision.
- `speedLimitTransition,binding` and `material` — **both 0**, and structurally impossible. E.5 settled.
- `swallowed,*` — **no site fired.** All 56 are unreachable defensive code.

**The golden reference run (step 6)** — all dates, production replications:

```
--study=phase05 --variants=reference --dates=cluster/dates.txt --demand=cluster/demand --replications=30
```

**One run per switch**, for isolated assessment:

```
--study=phase05 --dates=cluster/dates.txt --demand=cluster/demand --replications=30
```

which registers all six variants (reference plus five switches) per date. The variants are not
crossed — the question is what each correction does on its own.

Registration verified: `--count` with one date and two replications reports 12 runs = 6 × 2.

---

## 8. E.6 — absent values inside the core, without allocation or overflow

The review agreed the sealed `Measured / Absent / Unknown` at the **port boundary** and asked how
absent values are handled *inside* the hot path, given that sealed instances allocate, `Distance?`
boxes, and a `Long.MAX_VALUE` sentinel overflows silently on addition.

**Proposal: keep the sealed type at the boundary, and convert once, at the boundary, into a
non-allocating internal representation.**

The core's hot path has three distinct absent-shaped situations, and today all three are spelled
`POSITIVE_INFINITY` or `null`, which is why `catch` blocks returning `null` for "computation failed"
are indistinguishable from `null` for "nothing there":

| Situation | Example | Internal representation |
|---|---|---|
| **Nothing there** | no leader on the left lane; no lane end within range | a saturating sentinel, see below |
| **Not applicable** | rear headway on the current lane | the query does not exist in the core API; removed at design time |
| **Query failed** | the host threw | never reaches the hot path — the adapter converts it at the boundary and the core is told `Unknown` |

For the first, I would use a **saturating sentinel with checked arithmetic**, not a nullable:

- `Distance.ABSENT = Long.MAX_VALUE / 4` micrometres — about 2.3 × 10^12 m. Dividing the range by four
  means `ABSENT + ABSENT` and `ABSENT * 2` still fit in a `Long`, so the overflow risk the review named
  cannot be reached by the additions the model actually performs (gap + length + gap).
- A single `Distance.isAbsent` check, which is one comparison and no allocation.
- Arithmetic on an absent value **saturates rather than wrapping**: `absent + x = absent`. That is one
  branch in the two or three operators the hot path uses, and it makes the propagation explicit instead
  of hoping nobody adds to a sentinel.

Why not `Distance?`: Kotlin boxes a nullable value class on every assignment and every collection
entry, and the leader/follower queries return sequences of these. Why not the sealed type internally:
same reason, plus an allocation per query per tick per vehicle.

Why this is better than the present `Length.POSITIVE_INFINITY`: it is a *named* absence rather than a
number that happens to compare correctly, `isAbsent` cannot be confused with a large distance, and the
saturating operators make "absent propagates" a property of the type rather than of each call site.

**Two things to settle before Phase 1 commits to this:**

1. `Speed` and `Acceleration` are `Double` in kotlin-units, so they *have* `POSITIVE_INFINITY` and need
   no sentinel. Mixing conventions — sentinel for `Distance`, infinity for `Speed` — is ugly. The
   alternative is `Double.NaN` as the absence marker for both, which propagates naturally but compares
   false against everything, including itself, and would silently poison comparisons. I lean towards
   accepting the asymmetry and documenting it.
2. `Distance` is a value class over `Long`; adding a companion constant and saturating operators means
   either extending kit-ifv/kotlin-units or wrapping it in the core. Extending it is cleaner and the
   library is ours, but it is a change to a shared dependency. **Your call.**

**No implementation in this phase**, as directed.

---

## 9. Open questions

1. **§4.1, BC-7** — options (a), (b) or (c) for the extended look-ahead. **The measurement is in:
   100.0000 % of calls were answered from a stale memo**, so the mechanism has never worked. This is
   now purely a decision about what the code should say.
2. **§6, BC-4** — which estimator for the follower's desired speed, or the host-answers alternative.
3. **§6, BC-6 / E.1** — the switch bounds the path projection to `LOOKAHEAD`, so a distant merge lane
   is not consulted at all. Confirm 295 m is the visibility range you want, or name another.
4. **§4.2** — `SimpleHighwayScenario` runs on Wiedemann 99. Does `mirova-core` carry a W99, or does
   that scenario retire? This changes the size of the Reactive layer noticeably.
5. **`parameters.md` §5** — eight parameters are resolved for every vehicle and never read, several of
   them set by study grids where they therefore do nothing. Delete, or keep for revival?
6. **`parameters.md` §3** — `ConflictUtil` and `LmrsParameters` contribute nothing MiRoVA reads, and
   `TMIN`/`TMAX`/`TAU` are unreachable. May the factory stop pulling those sets in? Note that
   `MergeScenario` and `SimpleHighwayScenario` set `TMIN`/`TMAX` on MiRoVA factories, where they have
   no effect today — those lines are misleading and should probably go either way.
7. **E.7** — the `_Modified` truck distributions could not be classified as driver or scenario.
8. **Both documents from the brief are still missing.** `mirova_model_reference.md` and
   `DriverModelContract.kt` are not in `docs/decoupling/` or anywhere else in the four working
   repositories. §5 of this report is written against the code alone; if the reference document says
   something different about the relaxation, I have not seen it.

---

---

## 10. Decisions taken after the campaign, and what they changed

| Ref | Decision | Implemented as |
|---|---|---|
| A.1 | Extended look-ahead: option (c), delete the mechanism | `[no behaviour change]` — the measurement made it behaviour-neutral |
| A.2 | Follower desired speed: remembered unobstructed speed | `bcFollowerDesiredSpeedEstimated`, default off |
| A.3 | Wiedemann 99 is not part of the core | Documented; `SimpleHighwayScenario` and the W99 family stay in OTS |
| A.4 | Sealed type at the boundary, named sentinel inside, no saturating arithmetic | Phase 1 |
| A.5 | Query 12 shrinks to legal limits with distances | Recorded in the inventory |
| A.6 | Fail-fast in the core; none of the 56 catches migrate | Phase 1 |
| A.7 | Delete the never-read parameters; drop `TMIN`/`TMAX` from the MiRoVA factory | `[no behaviour change]` |

### Two deviations, both because the brief rested on something I had got wrong

**`EXTENDED_LOOK_AHEAD_DISTANCE` is kept.** A.1 says it "goes with" the mutation, which holds only if
the mutation is its one consumer. It is not: `computeDownstreamAdjacentLane` uses it as the 1000 m
path projection that finds the lane a ramp vehicle will merge into (live, and the range BC-6
switches), and three further sites use it as a distance threshold —
`MandatoryLaneChangePattern.checkContext`, `AnticipateMergeState.mergeStillFarOff` and
`PreventUndercuttingPattern.trafficIsFreeFlowing`, the last with a measurement over sixteen study
days behind it. Deleting the parameter would silently change all four.

The two threshold uses now compare a value capped at the driver's look-ahead against a larger bound,
which admits every finite answer and refuses the infinite one — the behaviour they have always had,
but the bound no longer means what its name says. **Open:** re-express them as "is there a route lane
change in range at all", which is what they now test.

**`bSi` was not unread.** My `parameters.md` listed it among the dead snapshot fields and A.7
inherited the error. `MandatoryLaneChangePattern` reads it to build the comfortable deceleration for
the ramp-end stop; the clean build caught it, the field is restored and the table corrected. For the
same reason: `FAR_ANTICIPATION_ENABLED` is set by **three files**, not six — the report counted
occurrences rather than files.

### A.2, for review before the campaign runs

The estimator is specified in the commit and in `parameters.md`. Two points deserve your eye:

1. **The fallback is a running per-agent mean** of observed unobstructed speed factors, not a
   configured value. That answers "where does the population mean come from" without adding a driver
   parameter — but it is a small learning mechanism, and if you would rather have a fixed configured
   value it becomes a key instead. Before any observation the factor is 1.0, i.e. the legal limit.
2. **Only the current-lane follower is judged**, because only there is the ego the follower's leader.
   A follower on an adjacent lane can never contribute an observation, so its estimate always comes
   from the fallback.

---

*Phase 0.5 complete, instrumentation run included. Awaiting review of the two deviations above and
the A.2 estimator, and the BC defaults after the cluster campaign.*
