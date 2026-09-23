# The parameter set for the validation run

Frozen on 2026-09-23. Every value below is read from the manifest the built study class writes
(`--study=tamascreen2 --cells=standard_base`), not from memory or from a design document. Values marked
*(default)* are not set by the study and take the model default; they are listed because a parameter set that
only names what was changed is not reproducible.

The set is `standard_base` of the second screen: the settled production set at the **standard** headway, with
the relaxation damping off.

## 1. Set explicitly by the study

| Parameter | id | car | truck |
|---|---|---|---|
| Desired headway | `T` | **1.00 s** | **1.30 s** |
| Maximum acceleration | `a` | 1.40 m/s² | 1.25 m/s² |
| Comfortable deceleration | `b` | 1.75 m/s² | 1.75 m/s² |
| Stopped distance | `s0` | 3.00 m | 6.00 m |
| Speed gain (LMRS) | `VGAIN` | 15.0 km/h | 30.0 km/h |
| MiRoVA acceleration cap | `aMaxMirova` | 3.50 m/s² | 1.30 m/s² |
| Lane-change safety-distance factor | `SAFETY_DISTANCE_REDUCTION_FACTOR_LANE_CHANGE` | 0.40 | 0.40 |
| Relaxation acceleration damping | `aRelaxDamping` | **1.0** (off) | **1.0** (off) |
| Damping mechanism enabled | `aRelaxDampingEnabled` | true | true |
| Cooperative deceleration threshold | `COOPERATIVE_DECELERATION_THRESHOLD` | −3.0 m/s² | −1.0 m/s² |
| Cooperative lane changes | `COOPERATIVE_LANE_CHANGES_ENABLED` | true *(default)* | **false** |
| Follower deceleration, lower bound | `MIN_FOLLOWER_DECELERATION_THRESHOLD` | −2.0 m/s² | −2.0 m/s² *(default)* |
| Follower deceleration, upper bound | `MAX_FOLLOWER_DECELERATION_THRESHOLD` | −4.0 m/s² | −4.0 m/s² *(default)* |
| Far anticipation | `FAR_ANTICIPATION_ENABLED` | **false** | **false** |
| Capacity-drop addon | `capDropEnabled` | false | false |

Three asymmetries are deliberate and were inherited from the production set rather than chosen here: the truck
speed gain is twice the car's, the truck stopped distance twice the car's (Kesting's 2:1 ratio), and trucks do
not cooperate while cars do.

## 2. At the model default

Identical for both vehicle classes unless stated.

| Parameter | id | value |
|---|---|---|
| Relaxation time constant | `tau_relax_s` | 20.0 s |
| Relaxation lifetime cap | `relaxMaxLifetime` | 3.0 · τ |
| Relaxation fade-out on abort | `tRelaxFade` | 1.0 s |
| Relaxation abort deceleration | `aRelaxAbort` | −1.0 m/s² |
| Social speed sensitivity | `socioSpeedSensitivity` | 0.25 |
| Gap-opening look-ahead | `considerGapOpeningLookaheadDistance` | 100 m |
| Undercutting time headway | `UNDERCUTTING_TIME_HEADWAY` | 5.0 s |
| Free lane-change threshold | `DFREE` | 0.365 |
| Mandatory lane-change threshold | `DMAND` | 0.577 |
| Extended look-ahead | `extendedLookAheadDistance` | 1000 m |

`DSEARCH` (0.788) exists but has no reader anywhere and is inert. `extendedLookAheadDistance` is very likely
inert as well: the perception query it raises is memoised per vehicle per tick and the desires ask first. Both
are listed so that a reader who finds them in the code knows they were considered.

## 3. Scenario settings

Demand aggregation 5 min, no demand smoothing, simulated 13:00–22:00 with a 45-minute warm-up excluded from
every metric, trajectory recording on and restricted to link L4a.

## 4. Why this set, and what it costs

Chosen from the second screen against five criteria. No cell dominated; this one is the balanced point.

| Criterion | value | field | note |
|---|---|---|---|
| Jam speed, 2025-10-07 | −0.0 km/h | 47.3 | best of the study, on 9 runs |
| Jam speed, 2025-09-23 | +4.3 km/h | 39.1 | on 2 runs |
| Capacity q_c05 / q_c50 (Kaplan-Meier, GMM threshold) | 3192 / 3792 | 3456 / 4044 | ≈250 veh/h low, on 36 events |
| Queue discharge | +300 / +133 veh/h | 3010 / 3184 | the weakest point |
| Ramp standstills | 2.25 % | — | no field reference; all cells 1.99–2.63 % |
| Van Aerde free-flow speed | 124.8 km/h | 128.6 / 134.1 | measured per headway, not per cell |

**What it does not do.** The breakdown of 2025-09-23 is reproduced in 20 % of runs against the field's single
event. That day's field capacity, 2988 veh/h, lies below the 5th percentile of the model's capacity in every
cell of the screen. The field's day-to-day capacity range (2988–4044, about 1056 veh/h) is wider than the
model's stochastic capacity range within one cell (about 600 veh/h). No parameter set from this screen closes
that, because the axes screened move the distribution rather than widen it. This is a property of the model to
be reported, not a tuning failure to be hidden.

**The alternative that was not taken.** `standard` with truck `a` = 0.7 m/s² reaches the field discharge much
more closely (+170 / +24) and the lowest standstills (2.02 %), at the price of a jam 11 km/h too slow on
2025-10-07, a false-breakdown rate of 70 % and the lowest capacity of the study. If the discharge is weighted
above the jam metrics, that is the set to take instead.
