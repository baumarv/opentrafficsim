# Why the free-flow speed differs from the field — what has been measured

**Status: findings, with one probe still running.** Written on 2026-09-23 while Marvin was away, so
that the measured part is separated from the part that is still a hypothesis.

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

### What is not yet ruled out

**The two speeds may not be the same kind of mean.** OTS creates its loop detector with
`LoopDetector.HARMONIC_MEAN_SPEED`. A harmonic mean is at or below the arithmetic one and the gap
grows with the spread of the speeds, so if the field figure is an arithmetic mean, part of the
median difference is a definition rather than a behaviour. `approach_profile.py` computes both from
the vehicles crossing the detector position, so the size of that part will be known rather than
assumed.

## 4. The exit hypothesis — plausible, probe running

The off-ramp leaves from **L2a**, whose third lane `FORWARD3` is the exit zone, and L2a feeds **L3a**
where the detector sits, at half that link's length. Vehicles braking for the exit would therefore
depress the measured speed *upstream of any merging at all*.

The field data says the perturbation is real: the **Ausfahrt** measurement position carries
**336 veh/h at a median of 48.5 km/h**, about 19 % of the mainline's 1776 veh/h, decelerating from
around 110 km/h.

No campaign has ever recorded trajectories on the approach — the sampler has always been restricted
to the merge section L4a. `--jvm-opt=-Dmirova.samplerLinks=L2a,L3a,L4a` now passes the switch that
`FreiburgNord.SAMPLED_LINK_IDS` has always offered, and a 15-run probe is recording L2a, L3a and L4a
on five days with the validation parameters: the four with the largest positive gap
(2025-10-15, 2025-10-29, 2025-10-07, 2025-09-17) and 2025-09-22 as the control with the largest
negative one.

`approach_profile.py` reads them: mean speed by link, lane and position bin over uncongested periods
only. If the exit is the cause, the exit lane is slower than the through lanes and the effect fades
with distance from the gore.

## What to do next, in order

1. **Read the probe.** It settles the exit question and the harmonic/arithmetic one at once.
2. **Take the desired-speed distribution seriously as the lever**, since §3 points at it directly:
   the median is too low and the tail too high, which is a distribution shape, not a merging
   parameter. `fSpeed` and its spread are the candidates.
3. **Stop reading `v_f` as the target.** §2 shows it is not determined well enough on the field side
   to calibrate against. The low-flow speed distribution of §3 is the quantity with an answer.
4. `pThreshold` stays at its default of 1.0, where it reproduces the published linear form exactly.
   Whether to keep the parameter at all is Marvin's call; nothing depends on it.

<!-- Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. BSD-style license. -->
