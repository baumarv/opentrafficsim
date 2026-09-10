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

**[inferred]** Which of the two the papers report is not stated anywhere I can find. The
`published-model` tag and the `legacy` variant pin **1.10 / 1.40**, because that is what
`FreiburgProductionStudy` resolves today — if TR-B or HEUREKA report the final ensemble instead, the
tag is on the wrong set and I need to be told. **This is the one thing in this document that should
be checked before anything else**, because it decides which parameterisation the recalibration has to
reproduce.

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
5. **Regenerate `parametertable.tex`** and re-tag. The `legacy` variant and the `published-model` tag
   keep the old results reproducible throughout.

**One thing to decide before any of it:** whether `dFree` moves with `vGain` (§3.2, point 5), and
whether the `T` discrepancy of §1.1 — 1.00 / 1.30 against 1.10 / 1.40 — means the published ensemble
is not the one the production study resolves.
