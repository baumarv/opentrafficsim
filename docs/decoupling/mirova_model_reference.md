# MiRoVA Model Reference

> **Provenance.** Pasted into the session by Marvin Baumann on 2026-09-10, after the Phase 1 draft
> was written; it was not present in any working repository before that (see inventory §E.8).
> **The author's own caveat: "teilweise nicht mehr aktuell".** Distilled from the TRB Part B paper
> draft (`mirova_trb_expose.tex`).
>
> **The mathematical notation below is mangled by the paste** — subscripts and fractions are
> duplicated inline. The text is kept verbatim rather than reconstructed, because guessing at a
> formula is worse than an ugly one. Replace this file with the original when it is available.
>
> **Where this document and the code disagree, the code wins** (project rule), but every divergence
> found so far is recorded in `docs/decoupling/bc4-and-reference-check.md` §3.

---

Condensed reference distilled from the current TRB Part B paper draft (mirova_trb_expose.tex). Reflects the current state of the architecture, pattern names, and symbols — supersedes any older project documentation (README.md, layer*.md) where they conflict.

## 1. What MiRoVA Is

MiRoVA is a cognitive tactical driver behavior architecture for microscopic traffic flow simulation, implemented as an alternative TacticalPlanner inside OpenTrafficSim (OTS). It replaces purely reactive stimulus–response car-following/lane-change models with explicit, FSM-based tactical maneuver representations (ManeuverPatterns), while retaining an exchangeable car-following model at the reactive base.

Core thesis: conventional microsimulation lacks an explicit tactical layer; ADS behavioral-planning frameworks have the right architectural vocabulary (invocation/commitment conditions, arbitration) but aren't built for calibration against traffic data. MiRoVA bridges this gap.

## 2. Five-Layer Architecture

Conceptually inspired by BDI, executed sequentially every simulation tick:

- **Belief Layer** — perception + world model. Caches per-tick perceptual values (headways, speeds, route distances) lazily (computed on first access, invalidated at tick start). Also manages relaxation state initiation/advancement.
- **Desire Layer** — quantifies motivations only, never actions. Produces two directional scalars: Lane Change Desire (LCD) `d_L, d_R ∈ [−1, 1]`. Positive = motivated toward that direction; negative = actively discouraged (veto).
- **Intention Layer** — ManeuverPattern (FSM shell) + ActionState (atomic unit). Each ActionState has: (a) operational execution logic → OperationalPlan, (b) transition conditions (indexed, first-match wins), (c) dynamic priority, (d) abortion condition.
- **Arbitration Layer** — selects/combines competing OperationalPlans (see §5).
- **Reactive Layer** — exchangeable car-following model (baseline: IDM+) plus headway relaxation. Patterns don't override the CF model; they reparameterize its target speed or the considered leading vehicle.

Pattern classification: **exclusive** (sole control, e.g. lane changes) vs. **parallel** (contributes only a longitudinal suggestion, e.g. cooperation).

Anticipation and Cooperation are not separate layers — they emerge from ordinary patterns conditioning on perceived intent (e.g. an active indicator), plus two baseline mechanisms: early leader-reclassification on cut-in (Belief Layer) and accepting temporary headway deficits (Reactive Layer, relaxation).

## 3. Desire Layer: Incentives

Aggregation (LMRS Eq. 11-style, per direction `j`):

```
D_r,j = Σ_{k ∈ I_mand} d_k,j        D_v,j = Σ_{k ∈ I_disc} d_k,j
d_j   = D_r,j + θ_v,j · D_v,j
```

`θ_v,j ∈ [0,1]`: 1 if mandatory/discretionary agree or mandatory is weak (`≤ d_mand`); 0 if they conflict and mandatory is strong (`≥ d_search`); linearly interpolated in between.

Incentives implemented:

| Incentive | Type | Purpose |
|---|---|---|
| RouteIncentive | mandatory | Desire-to-leave per lane vs. route distance; vetoes lane changes worsening route compliance |
| CruisingSpeedIncentive | discretionary | Speed-gain desire from anticipated adjacent-lane speeds, scaled by `v_gain`; damped by `a_gain` near `a_max`; right-side gain capped at 0 in free flow |
| KeepRightIncentive | discretionary | `+d_free` to the right iff right lane is not slower, not congested, sufficiently long, not a dead end |
| ProhibitDeadEndIncentive | discretionary (veto) | `−d_mand` toward any lane with a detected parallel merge/dead end |

## 4. Pattern Library (Intention Layer)

### MandatoryLaneChangePattern (exclusive)

Route-forced lane changes (merges, exits). Merge scenario (faster target, accelerate + align) vs. exit scenario (slower target, decelerate + align). States: AnticipateMergeState (initial) → EvaluateTargetGapState (hub) → MatchLeaderSpeedState / SolveParallelVehicleState (overtake vs. yield strategy) / CongestedMergeState (dispatcher) → CongestedCreepState / CongestedFollowLeaderState → EmergencyStopState (kinematic last-minute overtake check) → ExecuteLaneChangeState. Priority = mandatory LCD magnitude (grows continuously). Full transition table in the paper.

### DiscretionaryLaneChangePattern (exclusive)

Voluntary lane changes (speed gain, keep-right) — no preparation, only executes when a gap is already feasible. Also used (with a cooperative flag) as the execution vehicle for other patterns' cooperative lane changes. Single state PerformLaneChangeState: waits (no lock) if conditions unmet so parallel patterns can still act; commits lock + executes once feasible. Priority = directional LCD; floored at `d_free` if cooperative.

### GapOpenerPattern (parallel)

Opens a gap for a merging neighbor with an active indicator. Defers if own front leader can cooperate more effectively. Single state OpenGapState: two-leader CF rule (candidate as virtual leader), bounded by `b_coop`. May trigger a cooperative DiscretionaryLaneChangePattern. Priority fixed at `d_free`.

### AnticipateDownstreamMergePattern (parallel)

Proactive speed adaptation ahead of an adjacent lane drop, before any specific neighbor signals intent. Two-threshold activation (30 s TTC free-flow / 250 m distance congested). States: FarAnticipationState (no lock, re-evaluated each tick, samples downstream speed) → NearAnticipationState (macro-traffic-based preemptive deceleration). Priorities: 0.15 (far) / 0.25 (near) — both below `d_free`.

### PreventUndercuttingPattern (parallel)

Keeps right-side-overtaking compliant (German §5 StVO) via speed-shadowing rather than hard braking. Activates above `v_cong` when TTC to a slower left-lane vehicle drops below `t_undercut`. States: ShadowingState (initial, speed-matches left neighbor) → PrepareLaneChangeState (assertive-but-comfortable deceleration to open a gap) → cooperative lane change. Priority fixed at `0.1` (low).

*(Note: AnticipateAdjacentCongestionPattern existed in an earlier draft but was removed from the current paper scope — confirm before reintroducing.)*

## 5. Arbitration (Three-Step Scheme + Hysteresis)

1. **Lock check** — a running exclusive lane-change pattern's plan is executed unconditionally until it signals completion.
2. **Winner-takes-all** — if any pattern's priority `≥ d_free`, the highest-priority plan wins and (if a lane change) commits the lock.
3. **Min-acceleration voting** — otherwise, longitudinal accel = `min_i a_i` across all active parallel proposals; lateral direction = highest-priority lateral proposal, if any.

**Hysteresis:** the previous tick's active pattern gets its priority multiplied by `1.10`, preventing oscillation between similarly-prioritized patterns.

## 6. Reactive Layer

- **Car-following model:** IDM+ (Schakel et al. 2012 variant of IDM; min instead of sum of free-flow/interaction terms).
- **`a` vs. `a_max`:** `a` = comfortable desired acceleration (enters IDM+ directly). `a_max` = physical acceleration cap from standstill, speed-dependent, applied to all computed accelerations (car-following and pattern-originated alike):

```
          ⎧ 3.5 − (2.5/100)·v          v < 100
  f(v) =  ⎨ 1.0 − (1/150)·(v − 100)    100 ≤ v < 250
          ⎩ 0                          v ≥ 250

  a_max(v) = f(v) · a_max,0 / 3.5
```

(`v` in km/h; reference curve defined for cars, `a_max,0 = 3.5 m/s²`; trucks scale by `1.3/3.5`.)

- **Relaxation (Keane & Gao 2021):** virtual headway buffer triggered on leader change / cut-in; decays over single constant `τ_relax = 20 s`. Speed-deficit relaxation is not implemented separately — a speed deficit instead triggers the same headway-buffer mechanism (targeting a reduced desired headway) to avoid collisions under large speed differentials.

## 7. OTS Integration

- MiRoVA = an alternative TacticalPlanner (default in OTS is LMRS).
- OTS is event-driven, not fixed-timestep; OperationalPlan validity period is set dynamically by the winning pattern (~0.2 s parallel, ~0.1 s for critical exclusive patterns like MandatoryLaneChangePattern).
- **Update Cycle** (every time the current plan expires):
  1. Belief Layer: clear/lazily-recompute cache; advance/initiate relaxation.
  2. Desire Layer: evaluate applicable incentives → aggregate LCD.
  3. Intention Layer: check `checkContext`/`checkAbility` per pattern; execute active states (abort/transition/control); consult Reactive Layer as needed.
  4. Arbitration Layer: produce the final OperationalPlan.

## 8. Key Symbols & Thresholds

| Symbol | Meaning | Car | Truck |
|---|---|---|---|
| `d_free` | discretionary LC threshold | 0.365 | — |
| `d_mand` | mandatory LC threshold | 0.577 | — |
| `d_search` | active gap-search threshold | 0.788 | — |
| `v_gain` | speed differential → desire 1.0 | 15 km/h | 30 km/h |
| `v_cong` | congestion speed threshold | 60 km/h | — |
| `a` | desired CF acceleration | 1.2 m/s² | — |
| `a_max` | standstill physical accel cap | 3.5 m/s² | 1.3 m/s² |
| `T` | minimum desired headway | 0.9 s | 1.2 s |
| `b_coop` | cooperative decel threshold | −2.0 m/s² | −0.5 m/s² |
| `b_pre` | preemptive/cautious decel | −1.0 m/s² | — |
| `f_LC` | headway reduction factor during LC | 0.5 | — |
| `τ_relax` | relaxation time constant | 20 s | — |
| `x_stop` | emergency-stop buffer distance | 5 m | — |
| `x_ext` | extended anticipation look-ahead | 1000 m | — |
| `x_coop` | cooperation candidate scan range | 100 m | — |
| `t_undercut` | undercutting TTC threshold | 5 s | — |

Full table with sources in the paper (`tab:parameters`).

## 9. Conventions

- **b vs. a for decelerations:** all deceleration thresholds use symbol `b` (e.g. `b_ego`, `b_follower`, `b_coop`, `b_pre`); `a` is reserved for accelerations.
- **x vs. d for distances/desires:** `x_•` = distances/look-aheads; `d_•` = desires/thresholds on the `[−1, 1]` desire scale.
- DVA = driver-vehicle agent. LCD = Lane Change Desire.
- **Section-3/Section-4 pairing:** Section 3 = conceptual/generic definition, Section 4 = concrete parametrization/instantiation — avoid restating Section 3 content in Section 4; use forward/backward `\ref{}` instead.
