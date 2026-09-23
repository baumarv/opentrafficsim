# Why the free-flow speed differs from the field — what has been measured

**Status: findings; every question raised here has been measured except one, named in §5.** Written
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

## 3. Without the fit, the picture is sharper and different

`free_flow_speed.py` compares the speeds actually measured in intervals below 1500 veh/h, per day,
with no fit in between. Over all sixteen days:

* the model's **median is 5 to 8 km/h below** the field on 15 of 16 days,
* its **p95 is above** the field on 15 of 16 days,
* its p85 is within ±3 km/h of the field.

So the model's free-flow speed distribution is **too wide and centred too low** — a statement about
the desired-speed distribution, not about merging. That is the finding to act on.

### What was ruled out before concluding that

* **Flow is the same quantity on both sides.** Simulated cross-section median 1740 veh/h against a
  field median of 1776; the comparison is not one lane against two.
* **The lane aggregation is correct.** `_combine_lane_frames` builds the station speed as the
  flow-weighted harmonic mean `q_total / Σ(q_i/v_i)`, not an unweighted average of the lanes — which
  would have depressed the model by roughly the observed amount, since Lane 1 carries 480 veh/h and
  Lane 2 carries 1200.
* **Trucks are in both.** The field's `v_kmh` is `v_kfz_gesamt`, all motor vehicles.

### The one input that was not checked here

**The two speeds may not be the same kind of mean.** OTS creates its loop detector with
`LoopDetector.HARMONIC_MEAN_SPEED`, which is at or below the arithmetic mean by an amount that grows
with the spread of the speeds. §5 measures how much that is worth here.

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

## 5. Part of the remaining difference is a definition, and it is now measured

OTS creates its loop detector with `LoopDetector.HARMONIC_MEAN_SPEED`. Computed from the vehicles
actually crossing that position in the probe, the arithmetic mean exceeds the harmonic one by
**2.39 km/h** in uncongested intervals (Lane 1 3.47, Lane 2 1.88; 2.56 km/h over all intervals).

So **if** the field's `v_kfz_gesamt` is an arithmetic mean of individual vehicle speeds — which is
what German loop detectors usually report, and what the database's own definition has to confirm —
then roughly 2 to 2.5 km/h of the 5–8 km/h median difference in §3 is a definition rather than a
behaviour, and 3 to 6 km/h remain. That is the one input still unchecked; everything else in §3 was
verified.

## What to do next, in order

1. **Establish how the field aggregates its speeds.** It is one question to the database schema or
   to whoever maintains `fetch_fielddata_detectors`, and it decides how much of §3 is left to
   explain. Until it is answered, treat the median difference as 3–6 km/h, not 5–8.
2. **Then go at the desired-speed distribution**, which is what §3 points to once §4 and §5 are
   taken out: the median too low *and* the p95 too high is a distribution that is too wide and
   centred too low. `fSpeed` and its spread are the candidates, and they are cheap to screen.
3. **Stop reading `v_f` as the target.** §2 shows it is not determined well enough on the field side
   to calibrate against; §1 shows chasing it through merging parameters costs runs and moves
   nothing. The low-flow speed distribution of §3 is the quantity with an answer.
4. `pThreshold` stays at its default of 1.0, where it reproduces the published linear form exactly.
   Whether to keep the parameter at all is Marvin's call; nothing depends on it.

<!-- Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. BSD-style license. -->
