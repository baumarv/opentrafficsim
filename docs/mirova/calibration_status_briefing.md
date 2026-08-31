# Calibration status — briefing for a report author

Written to be read on its own. It states where the Freiburg-Nord calibration of MiRoVA stands, how
it got there, what the numbers are, and what is not settled. Everything here is measured; where a
claim rests on something weaker, that is said.

Sources: the campaign log in [`cluster/README.md`](../../cluster/README.md), the pipeline
description in [`python_pipeline.md`](python_pipeline.md), and the parameter rationale in the
javadoc of `FreiburgStudyParameters`.

---

## 1. The site and the data

The A5 interchange **Freiburg-Nord**, a two-lane carriageway with an on-ramp. Instrumentation is
loop detectors only — there are no vehicle trajectories from this site, which constrains what can
be validated (see §7).

| detector | position |
|:---|:---|
| `det_L3a` | mainline, upstream of the merge — the bottleneck cross-section |
| `det_L7a` | the on-ramp |
| `det_L5a` | mainline, downstream of the merge |

Detector data is aggregated to **5-minute intervals**. Nine study dates, each simulated
13:00–22:00 with a 45-minute warm-up excluded from every metric:

```
2025-09-22  2025-09-23  2025-10-01  2025-10-07  2025-10-08
2025-10-13  2025-10-21  2025-10-27  2025-10-29
```

**Three of them were designated calibration dates before any of this work**, on criteria unrelated
to how well the model fits them: an identified empirical breakdown, and coverage of the observed
capacity range from low to high. Those are `2025-10-27`, `2025-09-22` and `2025-10-07`. The other
six have never been calibrated on and serve as validation. This split matters for how results may
be reported — see §8.

---

## 2. Definitions the numbers depend on

A report has to state these, because several of them changed during this work and the earlier
numbers in the campaign log are not comparable across the change.

**Congestion threshold** `v_crit` = one study-wide value, fitted once by a two-component Gaussian
mixture on the pooled empirical speed distribution, and used for simulation and field alike. On the
full study set it is 86.2 km/h.

**Breakdown episode** = at least **three consecutive 5-minute intervals** below `v_crit`, entered
out of free flow with a speed drop of at least 5 km/h. Fifteen minutes of sustained congestion is
the usual requirement; two intervals was used earlier and was too weak.

**Breakdown capacity** of a run = the **highest** flow in the interval preceding any of its
episodes. A run with several episodes yields several observations.

**Bottleneck flow** = `det_L3a` + `det_L7a`, i.e. mainline plus ramp, since the bottleneck serves
both. Where a figure is mainline-only it is labelled as such.

Metrics reported per breakdown event: pre-breakdown flow over the last interval (`q_pre`) and over
the last 15 minutes (`q_pre_window`), onset time, jam duration, mean jam speed, queue discharge
(mean flow over the jam), and capacity drop (discharge against pre-breakdown flow).

---

## 3. The empirical reference

Over the days with a persistent breakdown, mean with a 95 % Student-t interval:

| quantity | value | interval |
|:---|:---|:---|
| queue discharge | 3115 ± 127 veh/h | 2988 – 3242 |
| pre-breakdown flow, 15 min | 3376 ± 160 veh/h | 3216 – 3535 |
| pre-breakdown flow, 5 min | 3456 ± 310 veh/h | 3146 – 3766 |
| capacity drop | 7.6 ± 3.4 % | 4 – 11 |
| jam speed | 44.6 ± 4.6 km/h | 40 – 49 |
| jam duration | 78.1 ± 18.8 min | 59 – 97 |
| onset | 190.0 ± 24.7 min | 165 – 215 |

**n = 8, not 9.** One breakdown per day is one observation. `2025-09-22` has a single congestion
episode lasting two intervals, which the fifteen-minute rule discards; it therefore contributes no
event. That day is used differently, as the specificity test in §6.

Per-day empirical pre-breakdown flows, for context on how much the site varies:

```
2025-09-23  2988   2025-10-01  3744   2025-10-07  4044   2025-10-08  2940
2025-10-13  3564   2025-10-21  3564   2025-10-27  3300   2025-10-29  3504
```

> A note in `cluster/dates_calibration.txt` says `2025-09-23` and `2025-10-08` have no identified
> breakdown. That predates the current detection and no longer holds — both yield one. The
> reasoning that selected the calibration subset is unaffected, the claim is stale.

---

## 4. The calibrated parameter set

All values are per vehicle class where the class matters. Defined in `FreiburgStudyParameters`,
with the evidence for each in its javadoc.

| parameter | car | truck | evidence |
|:---|:---|:---|:---|
| desired headway `T` [s] | 0.90 | 1.20 | best of three combinations over 9 dates (§6) |
| car-following acceleration `a` [m/s²] | 1.40 | 1.25 | factorials, §5 |
| comfortable deceleration `b` [m/s²] | 2.00 | 2.00 | after Kesting et al.; **never varied** |
| stopping distance `s0` [m] | 2.0 | 4.0 | car sweep and truck factorial |
| relaxation damping | 0.80 | 0.80 | fourth merge grid |
| lane-change safety distance factor | 0.40 | 0.40 | fourth merge grid |
| follower deceleration thresholds [m/s²] | −2.0 / −4.0 | study default | behaviour factorial |

Relaxation follows Keane & Gao with τ_s ≈ 15 s and τ_v ≈ 5 s.

### Two parameter findings worth reporting in their own right

**IDM's `a` is a ceiling, not an observed acceleration.** Truck acceleration had been set to the
field median of 0.7 m/s², which looks correct and is not. The free term scales `a` down with speed
and the interaction term reduces it further, so at a parameter of 0.7 the simulated trucks actually
accelerated at a **median of 0.28 m/s²**, and 0.51 below 10 km/h — far below the field. The field
figures are themselves averages over a process that starts higher, and were measured pulling away
from a ramp meter rather than at an interchange.

At `a` = 1.25 the trucks show a **median of 0.71 m/s² in congestion**, which is inside the field
range of 0.60–0.87 for that same situation. The transfer ratio is consistent across classes and
levels: the realised median is about 57 % of the parameter. This is a usable rule for anyone
setting IDM accelerations from field observations, and it is measurable only because the trajectory
output now records the GTU type — classifying vehicles by their maximum speed instead put 54 % of
the fleet in the truck class where the demand holds 20 %.

**Ramp vehicles deliberately exceed the car-following ceiling.** 1.66 % of car samples accelerate
above the IDM parameter, all of them on the merge link, 77 % in `SynchroniseMergeSpeedState` and
20 % in `SolveParallelVehicleState`. That is `rampAcceleration()` commanding the physical capability
rather than the comfort acceleration, by design. It is worth stating so the acceleration
distributions are not misread.

---

## 5. How the parameters were established

Six cluster campaigns, each 10 seeds per cell. Only the last three matter for the result; the
earlier ones are what made it necessary to fix the evaluation (§9).

| campaign | design | outcome |
|:---|:---|:---|
| `mergegrid` v1–v4 | damping × safety distance × 3 dates | fixed damping 0.80, safety distance 0.40 |
| `behaviour_v1` | follower thresholds × truck `a` × truck `T` × truck `s0`, 1 date, 36 cells | truck parameters and follower thresholds |
| `carparams_v1` | car `a` × car `s0`, 3 dates, 6 cells | car parameters |
| `validation_v1` | 3 headway combinations × **all 9 dates** | headways; the validation result of §6 |

Main effects from `behaviour_v1`, averaged over 359 runs — the design is factorial, so each level
pools ~120 runs:

| axis | jam speed, right lane | jam duration | ramp standstills |
|:---|:---|:---|:---|
| truck `a` 0.7 → 1.3 | **+11.9 km/h** | **−11.7 min** | **340 → 244** |
| truck `T` 1.2 → 1.0 | +5.4 | −10.4 | no effect |
| truck `s0` 3.0 → 4.0 | +2.0 | −7.8 | 324 → 261 |
| follower −2.0 → −2.5 | +0.4 | −1.9 | **265 → 340** |

Truck acceleration is by a wide margin the strongest axis and monotone across all three levels.
Notably the queue discharge barely moves with it — it governs the speed level and the jam duration,
not the throughput, which is what published sweeps of this parameter also report.

### One result that depends on the operating point

The follower deceleration thresholds gave **opposite answers in two tests**. On a heavily congested
hour (damping 0.70, safety distance 0.45, jams of two to three hours) loosening them to −2.5 / −5.0
reduced ramp standstills from 37.4 to 29.9 per run and rescued a run that had been collapsing, from
84 down to 28. On a full day at the calibrated operating point, where jams last ten to twenty
minutes, the factorial over 108 runs found the reverse: −2.5 raised standstills by 29 %.

The calibration targets ordinary days, so the tighter pair is used. This is an operating-point
choice, not a settled value, and it should be reported as one.

The threshold is an admissibility criterion in the gap assessment, not a commanded deceleration.
Measured over followers within 150 m of a merge, the median deceleration is 1.7 m/s² and the 5 %
quantile is 3.5 regardless of the setting; the share braking harder than 5 m/s² moves from 0.75 %
to 1.06 %. The effect comes from usable gaps no longer being discarded, not from anyone braking
that hard.

---

## 6. Validation result

`validation_v1`: three headway combinations over all nine dates, 10 seeds, 268 of 270 runs
completed (two failed in one cell, `2025-10-01 / tightest`, consecutive seeds — a killed task).

Averaged over all nine dates, mainline plus ramp:

| combination | discharge | pre 15 min | pre 5 min | onset | jam | jam speed | drop |
|:---|:---|:---|:---|:---|:---|:---|:---|
| tightest 0.8 / 1.1 | **3193** ✓ | 3006 | **3210** ✓ | **170** ✓ | 51 | 39.3 | −8.3 |
| **tighter 0.9 / 1.2** | **3105** ✓ | 3098 | **3280** ✓ | **181** ✓ | **64** ✓ | 35.0 | −1.1 |
| standard 1.0 / 1.3 | 2921 | 2990 | **3170** ✓ | **167** ✓ | 126 | 25.6 | +1.3 |
| **field (8 days)** | 2988–3242 | 3216–3535 | 3146–3766 | 165–215 | 59–97 | 40–49 | 4–11 % |

`tighter` wins on four of seven metrics. **The headway axis is exhausted, not open**: tightening
further lowers the fifteen-minute pre-breakdown flow, shortens jams below the field interval and
worsens the capacity drop.

### The pre-breakdown deficit is about duration, not level

This is the most reportable of the remaining discrepancies. The flow in the **last five-minute
interval** before breakdown is inside the field interval for all three combinations. Only the
**fifteen-minute mean** leading into it is short. The model reaches the right breakdown flow and
fails to hold it for a quarter of an hour. That is a different fault from insufficient capacity,
and it was invisible under the earlier capacity measurement.

### The ensemble against the realisation

Comparing means answers the smaller half of the question. A field day is one draw from a stochastic
process; the ten seeds of a cell are ten draws from the model's. A per-day deviation is evidence of
misfit only when it exceeds what the model produces on its own.

Standard deviations, and the ratio that matters:

| quantity | sim within a day | sim between days | field between days | within / field |
|:---|:---|:---|:---|:---|
| queue discharge | 182.9 | 120.4 | 152.1 | 1.20 |
| pre-breakdown flow, 15 min | 236.4 | 172.3 | 190.8 | 1.24 |
| jam duration | 37.2 | 26.7 | 22.5 | 1.65 |
| jam speed | 10.8 | 7.7 | 5.5 | **1.96** |

**The model's own run-to-run scatter matches or exceeds the site's day-to-day scatter everywhere,
and doubles it for jam speed.** A single day therefore carries little information about the
parameter set — the 5.2 km/h jam-speed error of §6 sits well inside what the seed alone produces.

Where each empirical day falls in its ensemble:

| quantity | inside the 10–90 % band | ranks |
|:---|:---|:---|
| queue discharge | **7 of 8** | 2, 5, 7, 4, 1, 8, 3, 5 |
| pre-breakdown flow, 15 min | 4 of 8 | 3, 6, 8, 3, 4, 8, 3, 6 |
| jam speed | 3 of 8 | 1, 6, 8, 5, 1, 8, 1, 8 |

Queue discharge is calibrated in the ensemble sense — seven of eight days inside, ranks spread
evenly, which is what a correct ensemble looks like. This is a stronger statement than a matching
mean and worth making in those terms. The pre-breakdown ranks pile up at the top, which is the
systematic shortfall. The jam-speed ranks pile up at *both* ends, the signature of an ensemble too
narrow for the phenomenon it is asked to reproduce.

For the speed time series, the empirical curve lies inside the 10–90 % band in 37.8 % of intervals
at a median band width of 8.0 km/h. A narrow band that misses is a systematic offset rather than a
dispersion problem — here most likely the 3–5 km/h free-branch deficit.

### Per-day errors in the winning cell

| quantity | median error | median absolute error | range |
|:---|:---|:---|:---|
| queue discharge | −54 veh/h | 106 | −218 … +166 |
| pre-breakdown flow | **−292 veh/h** | 292 | −500 … −91 |
| jam duration | −11 min | 28 | −60 … +43 |
| jam speed | −9 km/h | 13 | −20 … +15 |

Only the pre-breakdown flow is a systematic bias — it is negative on **every** day. The rest is
scatter around zero.

**The jam metrics fail in a specific way that should be named — and it is not a lack of variation.**
On days the field saw a fast jam (47–51 km/h) the model is 15–20 km/h low; on days it saw a slow one
(37–39) the model is 6–16 high. That is not scatter: the simulated and empirical day values
correlate at **r = −0.81 (p = 0.014)**. The model responds to the days *against* the site rather than
with it.

The simulated day-to-day spread is in fact larger than the field's (7.7 against 5.5 km/h), so the
model is not producing the same jam every time. It varies, in the wrong direction. Empirically the
high-demand days have the *higher* jam speed; in the model more demand deepens the jam, as one would
expect of a car-following model. The likely reading is that the site's busy days produce a moving
queue where the model produces stop-and-go — see §11.

### The specificity test

Breakdown rate over ten runs per day, against whether the site broke down that day:

| combination | hit rate on the 8 breakdown days | false rate on 2025-09-22 |
|:---|:---|:---|
| standard | 82 % | 60 % |
| **tighter** | **61 %** | **30 %** |
| tightest | 34 % | 10 % |

A sensitivity/specificity trade-off with `tighter` in the middle. The ordering is right — the day
that did not break down gets the lowest rate, and the three highest-demand days get the highest —
but the separation is thin, 30 % against 40 % for the nearest real day.

> A correct model should **not** give 100 % on a day that broke down. The field day is one draw from
> a probability, so 60–90 % on days that did break down and 30 % on a day that did not are not in
> themselves a contradiction. A report should make this point explicitly, or the hit rates read as
> failures.

---

## 7. Lane changes — the unvalidated half

The entire calibration above rests on detector quantities. The lane-change layer, which is what
MiRoVA is actually about, has **not** been validated against data.

The obstacle is structural: Freiburg-Nord has loop detectors only. The comparison would have to be
against trajectory datasets from other ramps — A43, exiD and Automatum, for which evaluations of
merge position already exist in the `diss_mvb` repository under
`scripts/evaluation/fielddata/trajectories/analysis/merging/`.

What the simulation produces, over the nine dates in the winning cell:

| | p10 | p25 | median | p75 | p90 |
|:---|:---|:---|:---|:---|:---|
| merge position, % of acceleration lane | 2 | 8 | **28** | 55 | 81 |
| cars | 2 | 9 | 28 | 54 | 80 |
| trucks | 2 | 3 | 11 | 62 | 87 |

The acceleration lane is 202 m; the median merge is at 56 m, the p90 at 164 m. Median merge speed
68 km/h, trucks 65.

**One number needs checking before it is reported**: only 76 % of ramp vehicles are counted as
merging. Whether the remaining 24 % genuinely fail to merge or the counting loses transitions at
link boundaries has not been established. Measured ramp standstills are around 5 %, so 24 % would
be surprising.

---

## 8. How to frame this in a publication

The temptation is to show the days that are hit well. That would be cherry-picking, and it would
also misrepresent the model: failing to reproduce day-to-day variation is a finding about the
model, not a presentation problem.

What is available instead is stronger. The **calibration/validation split was fixed in advance** —
three calibration dates chosen on criteria unrelated to fit (identified breakdown, coverage of the
capacity range), six validation dates never used. Within that frame a reduction is legitimate:
figures for two or three representative days, the full table for all nine. What matters is that the
selection precedes the result and that every day is reported.

The honest summary is a good one. Discharge is off by a median of 54 veh/h, onset is inside the
field interval on every day, jam duration is right in the mean. The systematic 3.7 % pre-breakdown
shortfall is a result to report — with the observation that it concerns duration rather than level,
which is a sharper statement than "well calibrated" and more useful to a reader.

---

## 9. Corrections to the evaluation — read before using older numbers

Four defects were found and fixed in the evaluation itself. **Any capacity figure produced before
these does not survive**, including those in earlier sections of the campaign log. This is worth a
paragraph in a report, because the corrections changed the headline conclusion of three campaigns.

**Detector rows counted twice.** Each detector interval carries three rows — a cross-class total
plus one per GTU type, the type rows already contained in the total. The loader kept all three, and
merging lanes into a cross-section then paired one lane's truck flow with the other's car flow.
Station flows read roughly double. Corrected, the queue discharge of the third campaign moves from
2512–2840 to 2925–3305 veh/h against a field 3095 ± 118: **the capacity deficit that drove three
campaigns was largely a counting error.**

**Breakdown capacity measured against a threshold refitted per run.** Roughly a hundred intervals
put the per-run GMM threshold anywhere between 65 and 90 km/h, so the threshold was itself a random
variable and a run whose fit landed high registered early crossings as breakdowns. The simulated
capacity read 1544 ± 514 veh/h. With one threshold, whichever it is, it reads 2064 ± 68 against a
field 2052. The *level* of the threshold barely matters — measuring against the simulation's own
pooled GMM gives 2070 — it is the refitting that did the damage.

**Capacity taken from the first of several episodes.** An empirical day yields one episode, a
simulated run one to three; taking the first compared the field's only draw against the smallest of
several, so the more often a model broke down the lower its measured capacity came out. Over the
fourth campaign the first episode sat at 55–82 % of the run's own maximum flow, with up to 28 later
intervals carrying more traffic than the moment of supposed failure. Empirically the same figure is
100 % with nothing higher afterwards.

**Van Aerde fitted on the residual in `q` alone**, although both coordinates carry error, which made
the estimate follow the scatter in `q` — and that scatter grows as the aggregation interval shrinks.
Fitting the same runs at 60 s instead of 300 s moved `v_f` by +12.6 % and `v_c` by −14.5 %; against
the nearest point of the curve the same comparison gives +1.5 % and −1.0 %. The jam density bound
was also a per-lane figure applied to cross-section totals, so both campaigns' fits sat exactly on
it and the reported `k_j` was a boundary artefact.

After all four, the independent estimates agree: breakdown capacity 2064 ± 68 against a field 2052
on the mainline, 3150 ± 178 against 3144 including the ramp, where the fitted fundamental diagram
gives 2138 and 2107.

---

## 10. Model changes made during this work

Relevant if the report describes the model rather than only its calibration.

**`AnticipateDownstreamMergePattern` deactivated.** Its activation could not distinguish a lane drop
from the end of the modelled network, and on the last link every lane ends. It held the downstream
cross-section at exactly `VCONG` = 60 km/h in 59 % of intervals. With it off the same detector runs
at 89 and 107 km/h. Every capacity figure measured at that cross-section before this is unusable.

**The physical net in `MirovaCarFollowingUtil` scoped to relaxed perception.** It was written to
catch the discontinuity after a discarded relaxation but ran on every car-following call, and its
kinematic form yields a negative value whenever the ego closes on a leader at all — 200 m of gap at
5 m/s of closing speed already gives −0.06 m/s². Free acceleration was capped for anyone catching up
with a slower vehicle, costing 7–8 km/h across the whole free branch at every flow level. Since
breakdown is detected against a speed threshold, a free branch held low crosses it at a lower flow,
which was a large part of the pre-breakdown deficit.

**`MirovaIdmPlus` braking bounds corrected**, and the merge FSM reworked with a kinematic gate,
discretionary desire and ramp acceleration — worth +18 merges/h and a merge speed deficit reduced
from 40 to 4 km/h.

**GTU type added to the trajectory output**, which is what made the per-class acceleration analysis
of §4 possible at all.

---

## 11. The congested branch is too deep

The day-to-day anti-correlation of §6 has a concrete cause, and it is measurable. Distribution of
5-minute speeds during congestion at `det_L3a`, over the three highest-demand days:

| | min | p10 | p25 | median | p75 |
|:---|:---|:---|:---|:---|:---|
| field | 14.2 | 27.2 | 34.0 | 43.4 | 53.1 |
| simulation | 10.1 | 17.1 | 20.1 | 26.4 | 40.5 |

**The whole distribution is shifted down by 15–17 km/h, not just its tail.** The site's congestion
is a queue that keeps moving at 34–53 km/h; the model's crawls at 20–40. That is what produces the
anti-correlation: on the busy days, where the field's queue moves fastest, the model's is deepest.

From the trajectories, over congested mainline vehicles on those days (n ≈ 30 000):

| | |
|:---|:---|
| at least one stop-and-go cycle | **13.9 %** of vehicles |
| cycles per vehicle | 0.15 mean, 3 maximum |
| time below 5 km/h | 3.4 % mean, 0 % median |
| minimum speed per vehicle | 23.4 km/h median |
| reaching standstill | 11.9 % |

So genuine stop-and-go affects about one congested vehicle in seven, while the typical congested
vehicle slows to some 23 km/h without stopping. Both parts matter: the oscillation drags the tail
down, the general depth shifts the whole distribution.

### Why, and what could be done

The mechanism is standard. Car-following models of this family are string-unstable at short desired
headways, and the calibration settled on `T` = 0.90 s for cars precisely because that reproduces the
capacity. Short headways plus an **identical** driver population is the configuration that grows
small disturbances into stop-and-go waves.

That population is the lever, and it is largely unused. Desired speed is drawn per vehicle from a
distribution; the car-following parameters — `T`, `a`, `b`, `s0` — are scalars applied to every
vehicle of a class. A heterogeneous platoon damps waves because its members do not all react alike,
which is why distributed parameters are standard practice in this model family. Distributing `T` and
`a` addresses the stop-and-go and the missing day-to-day variation with the same change.

Two other switches exist and neither is the answer here. The capacity-drop addon
(`CAPACITY_DROP_ENABLED`, off) *adds* headway below 40 km/h, which would deepen the queue further
even as it produces the capacity drop the model lacks — it should be tested, but not expected to
help this. `farAnticipationEnabled` gates only `AnticipateDownstreamMergePattern`, which is
deactivated anyway, and has nothing to do with car-following anticipation.

## 12. Open points

| | status |
|:---|:---|
| pre-breakdown flow 3.7 % short | systematic on every day; concerns duration, not level |
| jam speed and duration | mean is right, day-to-day variation is not reproduced |
| capacity drop −1.1 % against a field 4–11 % | still the wrong sign |
| lane-change validation | not done; requires the A43/exiD/Automatum datasets |
| 76 % merge rate | unexplained — real, or a counting artefact at link boundaries |
| decelerations reaching −8.0 m/s² | `GtuTemplate`'s `maximumDeceleration` bypasses MiRoVA's `B_MAX` of −6.0; never traced |
| `b` = 2.0 m/s² | carried untested; 4 % from the OTS default, judged noise |
| specificity | 30 % false breakdown rate against 40 % on the nearest real day |
| `ParameterTypes.A` transfer ratio | realised acceleration is ~57 % of the parameter; useful, but established on one site |
