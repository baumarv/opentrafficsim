# Recalibration inventory

What the production calibration actually did, what would be needed to do it again, and which
parameters are most likely to have been absorbing the speed gain that was 3.6× too large.

**Report only. No code was changed for this document.**

**Provenance of the reconstruction.** Commit history (`ots-demo/src`, `cluster/`, `docs/mirova/`),
the class documentation of the seventeen `Freiburg*Study` classes, `parameter_sensitivity.md`, and
`python_pipeline.md`. Where the record does not say something, the entry is marked **[inferred]** and
says what it was inferred from. Nothing here is taken from memory of a conversation.

---

## 1. What was calibrated, in what order, and what was tied to what

### 1.1 The sequence

Reconstructed from commit dates and the class documentation. Each step's *purpose* is quoted from the
class it produced; the *ordering* is the commit order, which is evidence of sequence but not of
intent.

| # | When | Step | Parameters moved | Evidence |
|---|---|---|---|---|
| 0 | 2026-05-13 | Headway retargeted after the relaxation was switched off | `T` | commit "changed parameter settings to T since time headway relaxation is deactivated" |
| 1 | 2026-06-30 | Forced a breakdown on the left | `T`, lane-change aggressiveness | commit "higher desired headways and more aggressive lane changing to achieve breakdown on left" |
| 2 | 2026-08-06 | Relaxation acceleration damping introduced and swept over several days | `aRelaxDamping` | `FreiburgDampingStudy`, commit "implement relaxation acceleration damping factor and parameter flag" |
| 3 | 2026-08-19 | **Headway crossed with damping**, then crossed again with the lane-change safety-distance factor | `T` × `aRelaxDamping` × `fGap` | `FreiburgCombinationStudy`: "Crosses named headway combinations with relaxation acceleration damping factors and lane-change safety distance…" |
| 4 | 2026-08-19+ | Fine grid on the merge axes, few days | `aRelaxDamping` × `fGap` | `FreiburgMergeGridStudy` |
| 5 | — | Headway × damping on one date, for smoothness | `T` × `aRelaxDamping` | `FreiburgSmoothnessStudy` |
| 6 | — | The set "as it stands after the headway-against-damping grid", over all nine dates | — (confirmation) | `FreiburgSettledStudy` |
| 7 | — | Settled set with the headways as the only open axis | `T` | `FreiburgValidationStudy` |
| 8 | — | **OAT sensitivity screen** around the settled set, ten cells | screening `b`, `s0`, `a`; `T` and damping as reference rows | `FreiburgSensitivityStudy`, `parameter_sensitivity.md` §3 |
| 9 | 2026-09-01 | **Grid over the three parameters the screen found to move the congested branch** | `b`, `s0`, `a` | `FreiburgCongestedBranchStudy` |
| 10 | 2026-09-01 | Production set fixed | all of the above | `FreiburgProductionStudy` |
| 11 | 2026-09-02 | Shorter headways to close the breakdown-capacity deficit | `T` | `FreiburgCapacityStudy` |
| 12 | 2026-09-02 | Capacity-drop addon tested against the too-short queue | `capDrop*` | `FreiburgCapacityDropStudy` — **not adopted**, off in production |
| 13 | 2026-09-03 | Shorter headways crossed with a longer-lived relaxation | `T` × relaxation lifetime | `FreiburgSusceptibilityStudy` |
| 14 | 2026-09-04 | Final ensemble, fifty seeds, at `T` = 1.00 / 1.30 | `T` | `FreiburgFinalStudy` |
| 15 | 2026-09-07 | Seven out-of-sample dates added | — | commit; `dates_calibration.txt` (3) and `dates_extension.txt` (7) |
| 16 | 2026-09-09/10 | Merge mechanism: eleven changes measured, four kept | structural, not parameters | `parameter_sensitivity.md` §9 |

**Two headways are in play, and the record explains why.** Step 14 settles on `T` = 1.00 / 1.30 s
while the production study resolves **1.10 / 1.40**. `FreiburgFinalStudy` documents this as a
deliberate trade with the measurements behind it:

| quantity | field | 1.00 / 1.30 | 1.10 / 1.40 |
|---|---|---|---|
| capacity at the GMM threshold | 3500 ± 326 | 3210 (−8.3 %) | 3085 (−11.8 %) |
| Van Aerde capacity | 3465 | 3500 (+1.0 %) | 3371 (−2.7 %) |
| jam speed | 44.6 | 47.0 (+5 %) | 37.2 (−17 %) |
| queue discharge | 3115 | 3299 (+5.9 %) | 3186 (+2.3 %) |
| jam duration | 78 min | 33 (−58 %) | 64 (−18 %) |
| breakdown on the eight jam days | 8 of 8 | 72 % | 90 % |

"1.00 / 1.30 wins on both capacity measures and on jam speed, 1.10 / 1.40 on the jam event — whether
it happens, how long it lasts, at what discharge. This set follows the capacities, which is the
decision taken."

**[resolved 2026-09-16]** The reference standard is `final_v1`, **1.00 / 1.30**. The former
`published-model` tag and `legacy` variant pinned 1.10 / 1.40 and were on the wrong set: the tag is deleted,
`campaign-final-v1` marks `fbce85dbe`, where `final_v1` ran (re-run byte-identical), and `legacy` now resolves
to `final_v1`'s parameters (`production-v1` keeps 1.10 / 1.40). See `docs/fork-merge-plan.md`, section D.

Note also that the two candidates differ in *which* target they satisfy, and the recalibration will
face the same trade at a corrected `vGain`: more discretionary lane changing lowers capacity and
makes the jam event more likely, which is exactly the axis these two sets trade along.

### 1.2 What was calibrated jointly

Three couplings are documented, and they are couplings in the strong sense: the values are not
separately meaningful.

1. **`T` with the relaxation damping.** The most important one. `FreiburgStudyParameters` states it
   outright: removing the damping raises the discharge enough to prevent breakdowns, "which is why
   the headway was lengthened at the same time. The two axes work against each other on the same
   quantity and only the pair is meaningful." Steps 3, 5 and 6 are all this pair.
2. **`fGap` with `s0`.** "Interacts multiplicatively with `s0`, so grid values are not comparable
   across a change of this constant" — the lane-change safety distance scales the target headway, so
   a smaller `s0` acts multiplicatively in the merge rather than additively.
3. **The follower deceleration pair.** `bFollowerMin` and `bFollowerMax` bound one interpolation
   ramp over the desire above `dMand`; neither can be moved alone.

**`b`, `s0` and `a` were gridded together** in step 9 — the three the screen found to move the
congested branch — so they are jointly fitted even where no pairwise interaction is documented.

### 1.3 What was never calibrated at all

`vGain`, `socio`, `dFree`, `dMand`, `dSearch`, `tauRelax`, the cooperation range, the undercutting
threshold. **No study in the repository varies any of them.** For `vGain` that is the point of this
document: it was never fitted, and everything else was fitted with it held at a value 3.6× larger
than intended.

---

## 2. Procedure, targets and data: what exists, and what a rerun needs

### 2.1 Targets

`parameter_sensitivity.md` §2 gives the empirical target table: nine study days, breakdown detection
at `v_crit` = 86.2 km/h on 5-minute aggregates of the **combined mainline-plus-ramp cross-section**
(the merge serves both, so the mainline detector alone is not the facility).

| Quantity | Field value | Usable as a target? |
|---|---|---|
| Queue discharge | **3115 veh/h**, span 2878–3314 (±7 %) | **Yes — the stable one.** Documented as "the most reliable single calibration target available here". |
| Jam duration | 78 min | Yes |
| Jam speed | 44.6 km/h | Yes |
| Pre-breakdown flow | 3456 veh/h, span 2940–4044 (±19 %) | **Weak.** Stochastic capacity: the flow that triggers the transition is not a well-defined property. |
| Breakdown frequency | 8 of 9 days | **Weak.** Wilson interval [0.57, 0.98] — calibrating against "89 %" implies a precision the data lacks. |

Secondary metrics from trajectories on the merge link L4a: ramp standstills, standstill time, merge
count, merge speed, and the share of vehicles passing through a stop-and-go cycle.

### 2.2 Method

- **Design**: one-at-a-time around a baseline for screening, factorial or grid only on the axes the
  screen finds to matter. Ten cells in the screen, "at a tenth of the runs of a 3⁵ grid".
- **Pooling is essential**: nine days × 4 replications = 36 runs per cell. "Per day it is 4, and at
  that size nothing is resolvable."
- **Reference rows are part of the design**: `T` and damping were carried through the screen although
  they were expected to move nothing, so that a null result on the candidates could be distinguished
  from a procedure that cannot detect anything.
- **Statistics**: Student-t for means, Wilson for proportions.

### 2.3 Data and tooling in the repository

| What | Where | State |
|---|---|---|
| Study dates | `cluster/dates.txt` (16), `dates_calibration.txt` (3), `dates_extension.txt` (7) | present |
| Demand | `cluster/demand/demand_<date>.csv`, generated from the detector database by `generate_demand_csvs.py`/`.ps1` | CSVs present; **regeneration needs the detector PostgreSQL database, which is reachable only from the local Windows machine** |
| Runner | `RunMirovaClusterStudy`, `cluster/run_mirova.sbatch`, `run_local_parallel.sh` | present |
| Evaluation | `diss_mvb/scripts/simulation/ots/plot_scenario_results.py`, `calibration_analysis_extensions.py`, `run_calibration_extensions_fast.py` | present, **in the other repository** |
| Fundamental diagram, breakdown capacity | Van Aerde fit; breakdown episodes ≥ 3 consecutive 5-min intervals below `v_crit`, entered out of free flow with `dv ≥ dv_min`; capacity = highest pre-breakdown flow of the run | documented in `python_pipeline.md` §1–2 |

**What a rerun needs that is not in this repository:** the evaluation pipeline (`diss_mvb`), and,
for any change to the demand, the detector database. Everything else — dates, demand CSVs, runner,
studies — is here.

**What a rerun needs that does not exist yet:** nothing structural. The `vgaintau` study is
registered and its baseline is the new production point.

---

## 3. What is most likely to have compensated the too-large `vGain`

### 3.1 The direction of the error

The social pressure and speed-gain terms both saturate through

```
rho = 1 - exp(-(dv / vGain) * (1 - headway / lookahead))
```

`vGain` is in the denominator: **a larger `vGain` produces a smaller desire for the same speed
difference.** Running at 54 km/h instead of 15 made every discretionary desire roughly 3.6× less
sensitive to a speed advantage. The corrected value therefore makes the model **more eager to change
lane discretionarily, more eager to keep right, and more responsive to a faster follower** — at
constant thresholds.

### 3.2 The candidates, in order of likelihood

**1. `fGap` = 0.40, the lane-change safety-distance reduction. [inferred]**
Calibrated down from 0.60 across steps 3 and 4, on the merge axes, against ramp standstills. With
discretionary lane changing suppressed by a too-large `vGain`, the lane changes the model had to get
through were disproportionately the *mandatory* ones at the ramp; a tighter gap acceptance is exactly
what makes those succeed. At the corrected `vGain` the mainline will produce many more voluntary lane
changes, each now also accepting 40 %-reduced gaps. **This is the parameter I would expect to be too
aggressive now.**

**2. `T` = 1.10 / 1.40 with damping = 1.00, the coupled pair. [inferred]**
They were fitted against discharge and breakdown frequency. More discretionary lane changing near the
bottleneck lowers capacity, so at the corrected `vGain` the model should break down *more* readily
than the calibration intended — which pushes the pair back towards a shorter headway, i.e. undoes
part of step 11's correction. The pair moves together or not at all.

**3. `bCoop` = −3.0 / −1.0, cooperative deceleration.**
Documented as clearly worse when strengthened beyond these values. Cooperation exists to serve
merging vehicles; with more mainline lane changing there is more competition for the same gaps.
Whether this needs to move is genuinely open — the note argues against strengthening, and nothing
argues for weakening.

**4. The follower deceleration pair, −2.0 / −4.0.**
The one parameter set whose two documented tests disagree, depending on the congestion regime. If the
corrected `vGain` shifts how often the model is in the heavy regime, the pair's setting is decided by
a different operating point than the one it was chosen for. Revisit it *after* the pair above, not
before.

**5. `dFree` = 0.365 — the threshold, not the scale.**
Never varied, taken from LMRS. It and `vGain` are the two halves of the same decision: `vGain` sets
how fast desire grows with a speed advantage, `dFree` how much desire is needed to act. Correcting
one and not the other is a decision, and it should be a deliberate one. **Not a compensator, but the
parameter whose meaning changed most when `vGain` did.**

### 3.3 What almost certainly did *not* compensate

`b`, `s0` and `a` were fitted in step 9 against the *congested branch* — jam speed, discharge, queue
depth — through car following, not through lane changing. The mechanism `vGain` acts on is upstream
of them. They should be re-checked, not re-fitted first.

---

## 4. Suggested order for the recalibration

Not a decision, a proposal, and it follows the order the original calibration used rather than
inventing a new one.

1. **Measure first.** The `vgaintau` study, baseline plus the `vGain` axis (1920 runs at 16 dates ×
   30 replications; 640 at 10 replications). It contains the published 54 km/h cell, so the size of
   the behavioural change is measured against the calibration's own operating point rather than
   asserted.
2. **Re-screen, do not re-fit.** An OAT screen at the new `vGain` over `fGap`, the `T`/damping pair
   and `bCoop`, with `T` and damping again as reference rows. If the screen moves nothing, the
   calibration survives and the published set stands with a corrected parameter — which is the
   outcome worth hoping for and the one nobody should assume.
3. **Grid only what the screen moves**, as step 9 did.
4. **Validate out of sample** on the seven extension dates, which were added for exactly this.
5. **Regenerate `parametertable.tex`** and tag the recalibrated campaign. The `legacy` variant and the
   `campaign-final-v1` tag keep the old results reproducible throughout.

**A defect found after this document was written, and it belongs in the list.** `bcInducedDecelKey`
(BC-10) gives the two induced-deceleration quantities cache keys of their own. They share one today, so
whichever runs first in a tick decides what the other reads. Measured on one twenty-minute production cell:
286 cache hits, **every one** of them serving the other overload's number, differing by more than 5 m/s²
in 18.5 % of cases, and the merge router's follower branch deciding the opposite way in **28 of 281**
evaluations. The published model keeps the collision, so nothing already measured moves — but a
recalibration run with the switch on is calibrating a different merge router, so step 2 should screen at a
decided value of this switch rather than across it.

**A second cache defect, and this one is larger.** `bcHeadwayFactorKey` (BC-11) keys the per-tick
car-following cache by the headway factor a call asked for, not by the leader alone. Few reads are
contaminated -- 23 on a congested production cell, 109 on a free-flow one -- but on the free-flow day 93 of
those 109 are a *plain* car-following call served the *yielding* number, differing by a mean of 2.5 m/s², and
the commanded plan then differs on 26 % and 42 % of ticks. The published model keeps the defect. Unlike BC-10
this one is **not** in the core set, because the core reproduces the leader-only key by decision (ADR-014), so
a recalibration has to decide the switch *and* whether the core follows.

**A fourth instance, currently inert, and it is on the list for that reason.** `bcDecelThresholdKey`
(BC-12) stops a lane-change direction of NONE from folding onto the RIGHT key of the two
deceleration-threshold memos, where it both reads and overwrites a genuine RIGHT answer. On both
production cells the correction changes nothing — 3 and 15 poisoned reads, all equal to what the reader
would have computed itself, and the recording with the switch on is byte-identical to the one with it off.
That equality holds only because the desires that produce NONE are near zero and therefore below `dMand`,
where the interpolation clamps both to the same minimum. A recalibration that moves `dMand`, or that
raises the desires, breaks that coincidence and the switch starts to matter. It is in the core set, so
the core reference already has it on; what needs deciding is the published model, and that decision
cannot be read off today's numbers.

**The lane-change desire is not bounded, and the threshold interpolation assumes it is.**
`computeEgoDecelerationThreshold` and `computeFollowerDecelerationThreshold` map the directional desire
onto `(desire − dMand) / (1 − dMand)` and clamp the result to `[0, 1]`, which is the LMRS form and takes
the desire to lie in `[0, 1]`. It does not: measured over every threshold computation on two production
cells, the directional desire reaches **5.70** on 2025-10-27 and **7.02** on 2025-09-22, and `Desire`
clamps nowhere. Separately from that — and not caused by it, see the correction below — the fraction's own
clamps do most of the work: **10.5 %** and **8.5 %** of ego computations sit pinned at
`maxEgoDecelerationThreshold`, where every desire from 1.0 upward is the same input.

| fraction | ego, 2025-10-27 | ego, 2025-09-22 |
|---|---|---|
| at the lower clamp (`minEgoDecelerationThreshold`) | 82.5 % | 84.1 % |
| interpolating strictly between | 7.0 % | 7.4 % |
| at the upper clamp (`maxEgoDecelerationThreshold`) | 10.5 % | 8.5 % |

**This contradicts the model's own specification.** `mirova_model_reference.md` fixes the scale twice — §2
gives the desire layer's output as `d_L, d_R ∈ [−1, 1]`, and the symbol conventions call it "the `[−1, 1]`
desire scale" on which every `d_•` threshold is defined. The specification is not merely silent about the
excess: §3 prescribes the aggregation as an unweighted sum over incentives, `D_r,j = Σ_k d_k,j` and
`d_j = D_r,j + θ_v,j · D_v,j`, with `θ_v,j ∈ [0, 1]`. A sum whose result is asserted to lie in `[−1, 1]`
holds only if the summands are individually small or never co-occur, and the specification says neither.
So the inconsistency is in the model description as well as in the code, which is why it cannot be settled
by reading the code alone.

It is also the same question as the `socio` bound restored in `f6d06fe7f`, and for the same stated reason:
that bound was put back because a `socio` above 1, multiplied by a pressure in `[0, 1)`, would let *one
incentive alone* put the desire outside the `[−1, 1]` scale the thresholds live on. The bound closed that
path for one incentive; the measurement above says the desire leaves the scale regardless, so the question
is only where a bound belongs — at the incentive or at the combination — not whether the scale is being
left.

**Whose defect the resolution loss is — corrected.** An earlier version of this section attributed the
pile-up at the upper clamp to the missing cap on the desire. That is wrong, and the correction matters for
which parameter to screen. The interpolation clamps its *fraction* to `[0, 1]`, so any desire at or above
1 gives a fraction of exactly 1 whether or not the desire itself is capped: a desire of 1.0 and a desire
of 7 are the same input to the threshold either way. The resolution loss therefore belongs to the `[0, 1]`
clamp on the fraction, not to the unbounded desire, and capping the desire (BC-13) does not recover any of
it — proven, not argued: with `bcDesireCapped` on, the deceleration thresholds are unchanged.

**`dMand` remains the lever for it**, and that is what makes it actionable. The fraction is
`(desire − dMand) / (1 − dMand)`, so `dMand` alone decides where both clamps fall and therefore how much
of the desire range the thresholds can still resolve — at the measured value, 82–84 % of evaluations sit
at the lower clamp and 8–10 % at the upper, leaving about 7 % where the two bounds do anything at all.

**Ordering for step 2.** Screen `dMand` **before** `minEgoDecelerationThreshold` and
`maxEgoDecelerationThreshold`, not alongside them. The two bounds have a different meaning at each
`dMand` — they act only on the fraction of evaluations `dMand` leaves strictly between the clamps — so a
design that varies all three together cannot separate them.

**A saving in the same design: BC-13 is separable from those two bounds.** Since the cap does not reach
the threshold interpolation at all (above), `bcDesireCapped` and the two deceleration-threshold bounds do
not interact through that path, and BC-13 need not be crossed with them. It acts on the *other* consumers
of the desire — the comparisons against `dFree`, `dSync` and `dCoop`, the LMRS weighting, the pattern
gates and `dominantDirection()` — so it belongs in a screening block with those, not in the threshold
block. That removes a two-way crossing from the design at no cost in information.

Whether the desires ought to be bounded at 1 is a modelling decision and is Marvin's to take; nothing is
clamped by default. It is recorded because it changes what a recalibration of these parameters can mean,
and because it is invisible in the published outputs.

**Where the excess comes from: one incentive, not the combination rule.** Measured over every desire
evaluation on the two cells, with the probe again inert (both recordings reproduce their references).

| | 2025-10-27 | 2025-09-22 |
|---|---|---|
| desire evaluations | 337 568 | 353 570 |
| `CruisingSpeedIncentive` alone above 1 | **58 826 (17.4 %)**, max **7.88** | **49 255 (13.9 %)**, max **9.51** |
| `RouteIncentive` alone above 1 | 271 (0.08 %), max 1.03 | 231 (0.07 %), max 1.03 |
| `KeepRightIncentive` | never, max 0.365 (`dFree`) | never, max 0.365 |
| `ProhibitDeadEndIncentive` | never, max 0.577 (`dMand`) | never, max 0.577 |
| combined desire above 1 | 58 906 (17.4 %) | 51 783 (14.6 %) |
| …of which **one incentive alone** exceeds 1 | **51 490 (87.4 %)** | **43 973 (84.9 %)** |
| …of which only the **sum** exceeds 1 | 7 416 (12.6 %) | 7 810 (15.1 %) |
| largest contributor `CruisingSpeedIncentive` | 53 909 (91.5 %) | 46 020 (88.9 %) |
| max combined desire, left / right | 5.70 / 6.85 | 7.02 / 7.91 |

`KeepRightIncentive` and `ProhibitDeadEndIncentive` are structurally bounded — they emit `dFree` and
`−dMand`, both parameters below 1 — and `RouteIncentive` only just crosses. The excess is
`CruisingSpeedIncentive`, whose desire is `a_gain · (v_adj − v_cur) / v_gain`, an unbounded ratio: nothing
limits the speed difference against `v_gain`, so the desire is whatever that quotient happens to be.
(`SocialInteractionsIncentives` never contributed on either cell — it is a fifth class in the package that
never runs, and the specification's table lists only the other four.)

**The bound belongs at the `Desire` type, and that is where MiRoVA dropped it.** OTS's own LMRS has the
*identical* unbounded expression in `IncentiveSpeedWithCourtesy` — this is inherited, not a MiRoVA
divergence in the formula. It is harmless in OTS because `org.opentrafficsim.road.gtu.lane.tactical.util.lmrs.Desire`
is a record whose canonical constructor caps the value: `this.left = left <= 1 ? left : 1;`, documented as
"Values above 1 are not valid and should be limited to 1", with `LmrsUtil` limiting again at the point of
use. Every OTS desire, single-incentive or combined, is capped at construction. MiRoVA's own `Desire`
class reproduced the formula without the cap.

So this is the `socio` case a second time — OTS bounds, MiRoVA does not — and it decides where a bound
would go. Capping in MiRoVA's `Desire` constructor covers both findings at once, because `add` and
`combine` build their results through it: it bounds the single incentive that causes 85–87 % of the excess
and the summation that causes the rest. Bounding only `CruisingSpeedIncentive` would leave the 12–15 % of
exceedances that come from summing sub-1 discretionary terms. Note one asymmetry before deciding: OTS caps
above at 1 and deliberately leaves negative values unbounded ("Values below 0 are allowed"), whereas the
MiRoVA specification states a symmetric `[−1, 1]`.

**The cap is now a switch: `bcDesireCapped` (BC-13), default off.** It caps the desire totals at 1 above
and leaves them open below, in `Desire`'s constructors, which is where OTS caps and therefore covers both
the single incentive behind 85–87 % of the excess and the summation behind the rest. This is the largest
switch in the campaign by what it touches — the desire is read by the thresholds, the pattern gates, the
LMRS weighting and `dominantDirection()` — and it moves **18 484 of 337 568** ticks on the congested cell
(5.5 %) and **1 323 of 353 570** on the free-flow one (0.37 %), with the switch off byte-identical to both
references.

Two things a recalibration should know before screening it.

*The cap does not touch the deceleration thresholds.* They interpolate on `(desire − dMand) / (1 − dMand)`
clamped to `[0, 1]`, so a desire of 1 and a desire of 7 already give a fraction of exactly 1. The
thresholds have been absorbing the excess silently all along, and the resolution loss recorded above is a
property of that clamp, not of the missing cap. What the cap moves is every *other* consumer: the
comparisons against `dFree`, `dSync` and `dCoop`, the LMRS weighting, the pattern gates, and
`dominantDirection()`, which returns NONE when the two sides are within `1e-3` — capping both sides at 1
turns unequal large desires into ties. So BC-13 and the two threshold bounds are close to separable in a
screening design, which is convenient and was not obvious.

*A prediction, and its refutation — recorded as such because the prediction was made in writing before
the measurement.* When BC-12 was found inert, the reason given was that the desires producing NONE are
near zero and therefore below `dMand`, where the interpolation clamps both directions to the same
minimum; and it was stated explicitly that this is "circumstance, not construction", since NONE also
arises when the two desires are close *above* `dMand`, where the values would differ. BC-13 creates
exactly that case: capping both sides at 1 turns unequal large desires into ties, so it was predicted to
make BC-12's collision reachable at last.

**It does not.** Running `bcDesireCapped` with and without `bcDecelThresholdKey` gives byte-identical
recordings on both cells — 0 of 337 486 ticks on 2025-10-27 and 0 of 353 581 on 2025-09-22. BC-12 stays
inert under the very condition predicted to break it.

That strengthens rather than weakens the BC-12 decision, and changes its basis: it is in the core set
because the core has no such memo and no NONE to alias — a structural argument that holds whatever the
desires do — and not because the collision happens to be harmless at the current parameters. The
recalibration therefore does not need to re-measure BC-12 against each candidate parameter set; it needs
only to keep the structural claim true.

**Before touching any direction-keyed cache, re-read the audit.** Six per-tick caches are keyed with
`dir == LEFT ? … : …`, which sends `NONE` to the RIGHT entry. One pair is a real collision and sits behind
BC-12; the other four are harmless *today*, and only because of where NONE currently arrives — three never
receive it (measured: 0 calls on both cells), one is unreachable because its single caller loops over an
explicit `{LEFT, RIGHT}` array (reasoned from the call site), and one, `getIfLaneAvailable`, does receive
it 16 997 and 15 177 times but is measured inert for two independent reasons. **This is a property of the
key convention, not four separate cases.** A recalibration that changes `dominantDirection()` — its `1e-3`
tie window is the obvious candidate, and BC-13 already widens the set of ties — or that gives any of these
getters a new caller, can make five latent aliases live in one change, with no test failing to say so.
The full table, with numbers and with each verdict marked measured or reasoned, is in
[`cache-audit.md`](cache-audit.md) §2; pointers sit at each getter and at `dominantDirection()` in the
code.

**Also on the list: `getLaneAverageSpeed` keys the lane by id without the link.** All five value-bearing
arguments are in the key, so this is not a collision in the audit's sense, but the lane is identified as
`lane.getId()` where the rest of the tree uses `link.getId() + "/" + lane.getId()`. Uniqueness is
therefore an assumption about the *network* rather than a property of the key: it may hold on
Freiburg-Nord and need not hold on another facility, and the model travels. Reasoned from the code, not
measured — no collision was observed, and none would be visible if it happened.

**Checked and cleared, so it is not re-opened:** `PreventUndercuttingPattern` asks for the LEFT
deceleration threshold unconditionally at its shadowing site. That is not a wrong argument — the pattern
is left-only by construction, triggered by `getRightSideOvertakingAhead()`, shadowing the left leader and
exiting by a lane change to the left. Measured anyway, because the pattern does run while the dominant
desire points right (676 of 1133 ticks on the congested cell, 656 of 1859 on the free-flow one): the LEFT
threshold equalled the one the dominant direction calls for in **100 %** of those ticks, and the
commanded plan changed in **none** of them, with either the dominant or the RIGHT threshold substituted.
The threshold clamp binds at that site in 65 and 166 ticks, so this is not a site where nothing happens —
the argument simply does not matter there, because both directional desires are below `dMand` in
free-flowing traffic, which is the only traffic this pattern runs in. No switch.

**One thing to decide before any of it:** whether `dFree` moves with `vGain` (§3.2, point 5), and
whether the `T` discrepancy of §1.1 — 1.00 / 1.30 against 1.10 / 1.40 — means the published ensemble
is not the one the production study resolves.
