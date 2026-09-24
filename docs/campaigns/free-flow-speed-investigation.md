# Why the free-flow speed differs from the field — what has been measured

**Status: findings. Everything raised here has been measured; the one remaining input is a
question to the data provider, not to this code (§5).** Written
on 2026-09-23 while Marvin was away, measured part separated from hypothesis throughout.

## The question as it was posed

The validation run's Van Aerde fits put the model's free-flow speed below the field's on several
days, and the working hypothesis was that merging behaviour depresses it — a merger accepting a
tight gap makes its follower brake, and the mainline never reaches the speed the field shows.

## 1. Merging is not the cause

`tamacurve`, 40 runs, five cells on the frozen validation set, two days, four seeds each. The cells
bend the deceleration-threshold interpolation (`pThreshold` 2 and 3) and lower its lower endpoint
(`bFollowerMin` −1.0), separately and together.

| cell | v_f [km/h] | difference to `base` | share of follower decelerations < −3.0 |
|---|---|---|---|
| `base` | 121.4 | — | 24.78 % |
| `bmin1` | 120.1 | −1.35 ± 1.92 | 24.42 % |
| `p2` | 119.6 | −1.80 ± 1.75 | 24.05 % |
| `p3` | 119.9 | −1.56 ± 1.90 | 24.49 % |
| `p3bmin1` | 119.9 | −1.55 ± 1.82 | 23.45 % |

The spread *within* `base` (SD 4.77 km/h over eight runs) exceeds every difference *between* cells,
all point estimates go the wrong way, and even the favourable end of the interval is about
+2.3 km/h against a field gap of 9–14 km/h. The parameter does reach the driver —
`ScenarioParameterKeysResolveTest` pins that `car.THRESHOLD_CURVATURE` resolves, and a key that does
not resolve is dropped in silence.

**Why the effect is so small:** `follower_max_decel` has hard plateaus — p05 and p10 are exactly
−3.50 m/s² in *every* cell, which is `bCrit`. The quantity measures the saturated car-following
response, not gap acceptance. The −3.5 m/s² at p25 that started this investigation is very likely
that bound rather than an accepted threshold.

## 2. The comparison quantity is itself unstable

Van Aerde's `v_f` is an asymptote, not a speed anyone drove. Across the sixteen validation days the
**field** estimate ranges from **101.4 to 136.0 km/h** — a 35 km/h spread in what should be close to
a site constant — and its extreme values sit on the worst fit residuals (RMSE 450–548 against
210–345 elsewhere). The gap to the model is not systematic: it runs from **+16.4 km/h**
(2025-10-15) to **−24.0 km/h** (2025-09-22).

## 3. Without the fit: the model is slower across the whole distribution

`free_flow_speed.py` compares the speeds actually measured in intervals below 1500 veh/h, per day,
with no fit in between.

**This section was wrong once and is corrected here.** The first version compared the simulation's
**60-second** detector records against the field's **five-minute** ones — the OTS loop detector
writes 60 s and the detector cache does not aggregate in time, while the field table arrives at
`aggregation=5`. A one-minute sample is more dispersed than a five-minute one for no behavioural
reason, and that alone produced the original reading of "median too low, p95 too high", i.e. a
distribution that looked too wide. Both sides are now brought to five minutes before any percentile
is taken.

With that corrected, over the sixteen validation days:

| | mean difference | negative on |
|---|---|---|
| median | **−5.54 km/h** | 16 of 16 days |
| p85 | **−4.48 km/h** | 15 of 16 days |
| p95 | **−3.96 km/h** | 14 of 16 days |

The model is slower **everywhere in the distribution**, not too dispersed: the spread `p95 − p50`
is 9.9 km/h simulated against 8.3 km/h in the field, a difference of 1.6 km/h rather than the
several the artefact suggested. This is a **level** shift, which points at the central value of the
desired-speed distribution rather than at its shape.

### What was ruled out before concluding that

* **Flow is the same quantity on both sides.** Simulated cross-section median 1740 veh/h against a
  field median of 1776; the comparison is not one lane against two.
* **The lane aggregation is correct.** `_combine_lane_frames` builds the station speed as the
  flow-weighted harmonic mean `q_total / Σ(q_i/v_i)`, not an unweighted average of the lanes — which
  would have depressed the model by roughly the observed amount, since Lane 1 carries 480 veh/h and
  Lane 2 carries 1200.
* **Trucks are in both.** The field's `v_kmh` is `v_kfz_gesamt`, all motor vehicles.
* **The interval length now matches**, and the type of mean is the same on both sides — see §5.

## 4. The exit does not depress the mainline — measured, not inferred

The off-ramp leaves from **L2a**, whose exit lane the sampler reports as `Ramp`, and L2a feeds
**L3a**, where the detector sits at half that link's length. Braking for the exit would depress the
measured speed *upstream of any merging at all*, which made this the better hypothesis.

A 15-run probe settles it: five days with the validation parameters, trajectories on L2a, L3a and
L4a, recorded with `--jvm-opt=-Dmirova.samplerLinks=L2a,L3a,L4a`. No campaign had ever recorded the
approach; the switch existed on `FreiburgNord.SAMPLED_LINK_IDS` but the local runner could not pass
it. 15 ok, 0 failed, 15 minutes, 660 MB.

**Speed along L2a in uncongested periods [km/h]:**

| from the link start | Lane1 | Lane2 | Ramp (exit) |
|---|---|---|---|
| 0 m | 101.9 | 110.8 | 101.3 |
| 50 m | 101.6 | 110.7 | 82.2 |
| 125 m | 102.8 | 110.5 | 98.7 |
| 200 m | 103.5 | 110.3 | 98.4 |
| 225 m | 106.8 | 111.9 | 100.6 |

The through lanes are **flat**: Lane 2 holds 110.3–111.9 km/h across the whole 229 m and Lane 1
101.6–106.8. There is no deceleration wave ahead of the gore, and the speeds continue at that level
into L3a (Lane 1 ≈ 105, Lane 2 107–112). Only the exit lane itself is slow, and it carries 1.7 % of
the samples.

**The exit flow is right, so this is not a case of the model simply having no exit traffic.**
Counted as vehicles that appear on L2a and never on L3a — the exit-lane sample count undercounts,
because a vehicle is on that lane only briefly — the model exits **325 veh/h, 16.9 %** of the L2a
traffic, against the field's **336 veh/h** at the *Ausfahrt* position, about 16 %. The manoeuvre
happens, at the right volume, and it does not slow the through lanes down.

## 5. How much of it is a definition: less than first measured

Two different questions were conflated in the first version of this section, and the numbers differ
by a factor of five.

**Aggregating individual vehicles** to an interval mean: the harmonic mean is the right estimator,
because only the space-mean speed satisfies `q = k·v`. Measured from the vehicles crossing the L3a
detector position in the probe, the arithmetic mean of individual speeds exceeds the harmonic one by
**2.39 km/h** in uncongested intervals (Lane 1 3.47, Lane 2 1.88).

**Aggregating interval means to longer intervals** is a different operation, and the correct rule
*preserves the type*:

```
five-minute space-mean:   v_s = Σ n_j / Σ (n_j / v_j)      harmonic, count-weighted
five-minute time-mean:    v_t = Σ (n_j · v_j) / Σ n_j      arithmetic, count-weighted
```

An arithmetic combination of harmonic minute values is neither of the two and lies above the
harmonic one — but only slightly, because the minute-to-minute variation of a mean speed is far
smaller than the vehicle-to-vehicle variation within a minute. Measured on the validation study, the
two rules applied to the same simulated minutes differ by **0.46 km/h**, not 2.39.

The field pipeline (`scripts/evaluation/fielddata/detectors/io/fetch.py`) builds its five-minute
value as `Σ(q_i·v_i) / mean(q) / aggregation`, which reduces to `Σ(q_i·v_i)/Σ(q_i)` — the
flow-weighted arithmetic rule. So:

* if the field's **per-minute** value is a time-mean (arithmetic) speed of individual vehicles, which
  is what TLS/MARZ detectors normally report, then the full **2.4 km/h** vehicle-level difference
  applies and about **3.1 km/h** of the 5.5 remains behavioural;
* if the per-minute value is already a harmonic mean, only **0.5 km/h** is definitional and about
  **5.1 km/h** remains.

Either way the model is genuinely 3 to 5 km/h slower, uniformly across the distribution. The
per-minute definition is the one input this repository cannot answer, and it changes the size of the
correction but not the conclusion.

## 6. The desired-speed distribution is the lever, and it works

Two axes, 27 runs each, three days with the largest median deficits, three seeds, on the frozen
validation set. `tamavdes` moves the whole car distribution; `tamavmin` removes its lower tail and
renormalises. Both sides of the comparison at five minutes and the same kind of mean.

| cell | vehicles changed | Δp50 | Δp85 | Δp95 |
|---|---|---|---|---|
| `base` | — | **−8.66** | −5.24 | −5.25 |
| `plus5` | 100 % | −5.03 | −2.09 | −1.72 |
| `plus10` | 100 % | −1.12 | +2.86 | +3.23 |
| `min100` | 8.3 % | **−3.68** | **−0.73** | **+0.29** |
| `min110` | 15.6 % | +0.04 | +2.71 | +2.92 |

**A prediction of mine was refuted.** §4's probe suggested desired speed rarely binds — the
per-vehicle maximum over the approach has a median of 104 km/h against the distribution's 134 — and I
predicted little effect. The transfer is about **75 %**: a shift of 5 km/h raises the measured median
by 3.6, a shift of 10 by 7.6. The per-vehicle maximum over 460 m of approach was a bad estimator of
desired speed, which was stated as a weakness and then relied on anyway.

**A second claim of mine was also wrong.** Having seen the deficit grow towards the median
(−8.66 against −5.25 at p95), I argued that truncation would lift the bottom more than the top.
It does so in the *desired* distribution but not in the measurement: `min100` lifts p50 by +4.98,
p85 by +4.51 and p95 by +5.54 — nearly uniformly. The percentiles here are over five-minute interval
means, not over vehicles, and removing slow drivers raises every interval.

**What remains true is the efficiency and the side effect.** `min100` buys +5.0 km/h by changing
8.3 % of the population; `plus5` buys +3.6 by changing all of it. And truncation creates no fast
drivers: the upper end of the desired distribution stays at 200 km/h, where `plus10` moves it to 210
and widens the speed differences that lane changes are made of.

**`min100` is the best fit across the distribution** — −3.7 / −0.7 / +0.3 — and something near
`min105` would likely land all three.

### What the other metrics say, and what they cannot say

| cell | v_f | queue discharge | jam speed | follower p25 | runs with a breakdown |
|---|---|---|---|---|---|
| `base` | 120.8 | 3269 | 36.1 | −2.90 | 4 of 27 |
| `min100` | 124.7 | 3282 | 47.5 | −3.13 | 5 |
| `min110` | 127.6 | 3278 | 42.9 | −3.17 | 4 |
| `plus5` | 124.4 | 3304 | 47.8 | −2.96 | 6 |
| `plus10` | 132.9 | 3303 | 55.0 | −3.10 | 8 |

Queue discharge moves by at most 1 %, and the follower deceleration by at most 0.3 m/s². Nothing
here looks like a cell buying the target metric with something else — **but four to eight breakdowns
per cell cannot show that it does not.** The jam speeds spread over 19 km/h on that sample, which is
noise, not a finding. Read these as "no alarm raised", not as "checked".

## 7. Three ways of reshaping the distribution, and what each does

117 runs over three axes, all on the frozen validation set, three days, three seeds. `tamavdes` moves
the whole car distribution, `tamavmin` removes its lower tail and renormalises, `tamavcomp` compresses
it towards a pivot: `v -> pivot + factor * (v - pivot)`, which keeps every driver and their order.

| cell | what it does | Δp50 | Δp85 | Δp95 | mean |Δ| | spread p95−p50 |
|---|---|---|---|---|---|---|
| `base` | as measured | −8.66 | −5.24 | −5.25 | 6.38 | 9.86 |
| `plus5` | all +5 km/h | −5.03 | −2.09 | −1.72 | 2.95 | 9.76 |
| `plus10` | all +10 km/h | −1.12 | +2.86 | +3.23 | 2.40 | 10.81 |
| `min100` | no wish below 100 | −3.68 | −0.73 | +0.29 | 1.57 | 10.43 |
| `min110` | no wish below 110 | +0.04 | +2.71 | +2.92 | 1.89 | 9.33 |
| `c150k08` | pivot 150, factor 0.8 | −3.20 | −1.06 | +0.40 | 1.55 | 10.05 |
| `c160k08` | pivot 160, factor 0.8 | −1.80 | +1.43 | +1.66 | 1.63 | 9.92 |
| **`c150k07`** | **pivot 150, factor 0.7** | **−0.32** | +1.66 | +2.10 | **1.36** | **8.88** |

The field's spread on these three days is **6.43 km/h**.

**Only compression narrows.** Truncation and a uniform shift both widen the measured distribution
against the baseline's 9.86; `c150k07` is the single cell that moves it towards the field. That was
predicted from the desired distribution — truncation raises its p95 from 185.5 to 187.6 by
renormalising, compression lowers it to 174.8 — and it carries through to the measurement.

**The factor does the work, not the pivot.** `c150k07` and `c160k08` were chosen to separate them and
have almost the same gain in the desired harmonic mean (+7.09 against +6.91). Measured, `c150k07`
gives +8.35 km/h on the median at a spread of 8.88, `c160k08` only +6.86 at 9.92. What buys the
measured speed is the narrowing, not where the distribution is pinned.

**A reshaping confined to the lower branch cannot work**, and was ruled out before it was built:
squeezing everything below 120 km/h into [105, 120] touches 29.4 % of the population for 4.3 km/h of
desired harmonic mean, against 5.5 km/h for a truncation at 100 touching 8.3 %. The model is slower
than the field across the whole distribution, so a fix confined to the bottom leaves the top exactly
as short as it was.

### The other metrics

| cell | v_f | queue discharge | follower p25 | runs with a breakdown |
|---|---|---|---|---|
| `base` | 120.8 | 3269 | −2.90 | 4 of 36 |
| `c150k08` | 124.9 | 3270 | −3.11 | 5 |
| `c160k08` | 126.9 | 3306 | −3.12 | 6 |
| `c150k07` | 128.9 | 3308 | −3.18 | 4 |

Queue discharge moves by at most 1.2 % and the follower deceleration by at most 0.29 m/s². I
predicted before running that narrowing would *support* capacity, because smaller speed differences
mean less overtaking pressure; the discharge does rise slightly in that direction. **That is
consistent, not established** — four to six breakdowns per cell cannot carry a capacity claim either
way.

### The candidate

`c150k07` fits best on both counts: the median is on the field value and it is the only cell that
narrows. `c150k08` and `min100` are the conservative alternatives, each about 3 km/h short at the
median. The choice between them is Marvin's, and it needs the days and seeds of a campaign before
anything is frozen — not because the level is in doubt, but because capacity is.

## What to do next, in order

1. **Confirm the per-minute field speed.** The five-minute aggregation is known to be a
   flow-weighted arithmetic mean (§5); what remains is the device's own per-minute definition, which
   is a question to the data provider and not to this code. It decides whether 3.1 or 5.1 km/h is
   left to explain.
2. **Confirm `c150k07` on a campaign's sample.** §7 has the axis, the working range and the reason
   to prefer compression over the other two; what it does not have is enough congested runs to see
   whether capacity pays for it. Three days and three seeds gave four to six breakdowns per cell.
3. **Stop reading `v_f` as the target.** §2 shows it is not determined well enough on the field side
   to calibrate against; §1 shows chasing it through merging parameters costs runs and moves
   nothing. The low-flow speed distribution of §3 is the quantity with an answer.
4. `pThreshold` stays at its default of 1.0, where it reproduces the published linear form exactly.
   Whether to keep the parameter at all is Marvin's call; nothing depends on it.

<!-- Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. BSD-style license. -->
