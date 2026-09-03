# Parameter influence on the congested branch — A5 Freiburg-Nord

What each behavioural parameter of the MiRoVA car-following layer actually does to the
simulated traffic state at a motorway merge, measured rather than assumed. Written as a
reference for the calibration chapter, so every claim carries the measurement it rests on
and the uncertainty that measurement has.

**Status:** the sensitivity screen (360 runs), the congested-branch grid (648), the
production ensemble (270), the headway study (432) and the capacity-drop study (432) are
all evaluated. Section 6 carries the finding that reframes several of the others; section 9
lists what is still open.

---

## 1. Why this document exists

Three successive campaigns varied desired headway `T` and the relaxation acceleration
damping factor, on the assumption that these govern capacity and therefore the breakdown
behaviour at the merge. They did move breakdown frequency and smoothness. They did not
move the **structure of the congestion**: across eighteen cells the simulated jam speed
stayed between 18.6 and 50.8 km/h with a median near 28, against an empirical band of
37.3 to 50.8 km/h. The jam was consistently too slow, too long, and discharged too
little, in every cell tested.

That is a null result over a large amount of compute, and it was reached without ever
having varied the parameters that describe the congested branch itself. The screen
documented here fixed that. Its outcome reverses the working assumption: the quantity
that eighteen cells could not reach is governed almost entirely by the one parameter none
of them touched.

---

## 2. Empirical targets

Nine study days, breakdown detection at v_crit = 86.2 km/h on 5-minute aggregates of the
combined mainline-plus-ramp cross-section.

| Day | Breakdown | q before breakdown | Discharge | Duration | Jam speed |
|---|---|---|---|---|---|
| 2025-09-22 | **no** | — | — | — | — |
| 2025-09-23 | yes | 2988 | 3010 | 110 min | 39.1 km/h |
| 2025-10-01 | yes | 3744 | 3314 | 95 min | 50.8 km/h |
| 2025-10-07 | yes | 4044 | 3184 | 55 min | 47.3 km/h |
| 2025-10-08 | yes | 2940 | 3170 | 100 min | 49.4 km/h |
| 2025-10-13 | yes | 3564 | 3065 | 50 min | 37.3 km/h |
| 2025-10-21 | yes | 3564 | 3295 | 85 min | 48.1 km/h |
| 2025-10-27 | yes | 3300 | 2878 | 70 min | 38.0 km/h |
| 2025-10-29 | yes | 3504 | 3002 | 60 min | 46.7 km/h |
| **mean of the 8** | 8 of 9 | 3456 | **3115** | **78 min** | **44.6 km/h** |

Two properties of this target set matter for how it should be used.

**Discharge is the stable quantity, pre-breakdown flow is not.** Discharge spans
2878–3314, that is ±7 % around 3115. The flow at which breakdown occurs spans 2940–4044,
±19 %. This is the ordinary picture of stochastic capacity: the queue discharge rate is a
well-defined property of the bottleneck, the flow that triggers the transition is not.
Discharge is therefore the most reliable single calibration target available here, and
pre-breakdown flow the least.

**The target rate is itself uncertain.** Eight breakdowns in nine days gives a Wilson
interval of [0.57, 0.98]. Calibrating breakdown frequency against "89 %" implies a
precision the field data does not have.

### 2.1 A limitation of the input data

The mainline count that the demand reconstruction is built from sits at 113.9 m along
link L3a, which is **114 m upstream of the merge point** N3_4. A queue from the merge
backs well past that. During the congested phase the cross-section therefore measures the
*discharge*, not the demand, and the reconstructed demand for those intervals is capped.

This is a structural fact of the site, established from the network geometry and the
detector position, not a hypothesis. Its consequences:

- The day ordering is not explicable from demand. 2025-10-27 has the lowest demand peak
  in the set (3840 veh/h) and the longest jam (70 min); 2025-09-22 has 4584 and stayed
  free. A deterministic model cannot reproduce an ordering that is not in its input.
- Jam-phase quantities (duration, discharge, jam speed) are contaminated on the input
  side for the eight congested days.
- **2025-09-22 is the only day with uncontaminated demand throughout**, since nothing
  ever backed up over the detector. It is both the cleanest calibration day and the
  natural specificity control.

The size of the effect cannot be determined without a second cross-section outside the
queue, which the site does not have.

---

## 3. Method

**Design.** One-at-a-time variation around a baseline, ten cells, rather than a factorial.
At a tenth of the runs of a 3⁵ grid it answers the direction question; interactions are
worth paying for only on the axes that turn out to matter.

**Baseline.** `T` = 1.10 s (car) / 1.40 s (truck), damping 1.00 (off), `b` = 2.0 m/s²,
`s0` = 2.0 m (car) / 4.0 m (truck), `a` = 1.4 m/s² (car) / 1.25 (truck), lane-change
safety distance factor 0.55.

**Pooling is essential and deliberate.** Nine days × 4 replications gives 36 runs per
cell. Per day it is 4, and at that size nothing is resolvable — see §8. Every figure
below is pooled across days unless stated otherwise.

**Reference rows.** `T` and damping were carried through the screen although they were
expected to move nothing. Without them, a null result on the candidates could not be told
apart from a measurement procedure that cannot detect anything at all. They behaved as
intended (§4.4, §4.5), which is what licenses believing the positive results.

**Metrics.** Breakdown occurrence per run; mean jam speed, jam duration and queue
discharge over the runs that broke down; ramp standstills, standstill time, merge count
and merge speed from trajectories on the merge link L4a; share of vehicles passing
through a stop-and-go cycle. Confidence intervals are Student-t for means, Wilson for
proportions.

---

## 4. Findings per parameter

### 4.1 `b` — comfortable deceleration — the dominant lever

Applied to cars and trucks alike. Pooled over 9 days × 4 seeds, 36 runs per cell.

| `b` [m/s²] | Jam speed | Duration | Discharge | Breakdown rate |
|---|---|---|---|---|
| 1.0 | **58.5 ± 6.7** | 30 ± 4 | 3309 ± 70 | 58 % [0.42, 0.73] |
| 1.5 | **39.3 ± 5.8** | 48 ± 18 | 3223 ± 90 | 58 % [0.42, 0.73] |
| 2.0 (baseline) | 28.7 ± 4.1 | 105 ± 26 | 2906 ± 83 | 81 % [0.65, 0.90] |
| 3.0 | 27.9 ± 3.4 | 171 ± 23 | 2758 ± 65 | 89 % [0.75, 0.96] |
| *empirical* | *44.6* | *78* | *3115* | *8 of 9 days* |

`b` spans a jam speed of **27.9 to 58.5 km/h** and therefore covers the empirical 44.6
outright. Linear interpolation between the 1.0 and 1.5 levels puts the target near
**b ≈ 1.35**. Every other quantity moves monotonically with it: lower `b` gives a shorter
jam, a higher discharge, and a lower breakdown rate.

The effect saturates upward — 2.0 and 3.0 differ by 0.8 km/h in jam speed, within the
intervals — so the informative range lies below the previous default.

**Effect on the merge** (3 days × 4 seeds, 12 runs per cell):

| `b` | Ramp standstills | Standstill time | Merges | Merge speed | Stop-and-go |
|---|---|---|---|---|---|
| 1.0 | **193** | 3674 s | 3908 | **58.4 km/h** | **5.5 %** |
| 1.5 | **204** | 4605 s | 3923 | **58.2 km/h** | 6.6 % |
| 2.0 (baseline) | 432 | 5525 s | 4056 | 53.2 km/h | 13.2 % |
| 3.0 | **931** | 8494 s | 4255 | 42.9 km/h | 23.3 % |

Lowering `b` **halves ramp standstills** and lifts merge speed by 5 km/h, while the number
of completed merges barely changes (3908 against 4056). Vehicles do not merge less often;
they merge more smoothly. Vehicles deleted for failing to complete a lane change are zero
at `b` = 1.0 and 1.5, and only appear at `b` = 3.0.

**Mechanism.** This is the opposite of the intuitive expectation that weaker braking would
leave a merger unable to decelerate into a gap. In the IDM desired-gap term

> s\*(v, Δv) = s₀ + v·T + v·Δv / (2·√(a·b))

`b` sits *under the root in the denominator*. A smaller `b` therefore **increases** the
desired gap while closing on a slower leader. The vehicle begins to respond earlier and
more gently rather than late and hard. For a merging vehicle that is exactly the right
behaviour: it arrives at the gap under control instead of reaching it late and stopping.
The mechanism is the standard reading of the IDM interaction term; what is measured here
is its consequence, not the mechanism itself.

> **Do not confuse `b` with the deceleration bounds.** `ParameterTypes.B` shapes the
> *desired gap*; it is not a cap on braking. The deceleration MiRoVA actually applies is
> filtered separately in `MirovaIdmPlus.combineInteractionTerm` against `B_CRIT` = −3.5 m/s²
> and `B_MAX` = −6.0 m/s², a comfort filter that touches about 0.4 % of time steps and is
> explicitly not a safety device. Three similarly named quantities therefore act at
> different points, and a chapter that treats them as one will misattribute the effect
> measured here. See [layer4_reactive_control.md](layer4_reactive_control.md).

**Caution.** The gain in smoothness is paid for in breakdown frequency, which falls to
58 % against an empirical 8 in 9. This is the same trade seen when the relaxation damping
was switched off: smoothing the recovery raises discharge enough to prevent the breakdown
rather than to improve it. `b` cannot be set alone.

### 4.2 `s0` — stopped bumper-to-bumper distance — the counterweight

Car value varied, truck value carried at Kesting's 2:1 ratio so that one physical quantity
changes rather than also the ratio between vehicle types.

| `s0` car [m] | Jam speed | Duration | Discharge | Rate | Standstills | Stop-and-go |
|---|---|---|---|---|---|---|
| 1.0 | **16.8 ± 4.0** | 221 ± 27 | 2454 ± 111 | 69 % | **1320** | **38.3 %** |
| 2.0 (baseline) | 28.7 ± 4.1 | 105 ± 26 | 2906 ± 83 | 81 % | 432 | 13.2 % |
| 3.0 | **37.4 ± 4.2** | 69 ± 15 | 3172 ± 29 | **89 %** | 224 | 7.9 % |

Larger `s0` improves everything measured here at once, and `s0` = 3.0 is the only single
cell in the screen that hits the empirical breakdown rate exactly (89 %) while also
landing within a few percent of the empirical duration (69 against 78 min) and discharge
(3172 against 3115).

**But it fails the specificity test outright.** On 2025-09-22, the one day the site did
not break down, `s0` = 3.0 broke down in **all four runs**. Aggregate agreement bought by
breaking down everywhere is not agreement. This is the clearest illustration in the whole
screen of why a pooled score needs a specificity control beside it.

The smaller direction is excluded outright: `s0` = 1.0 triples standstills to 1320 and
drives more than a third of vehicles through a stop-and-go cycle. An earlier informal
argument that smaller accepted distances should make traffic flow *more* freely is
refuted by this measurement.

### 4.3 `a` — maximum acceleration of cars

| `a` car [m/s²] | Jam speed | Duration | Discharge | Rate | Standstills | Merge speed |
|---|---|---|---|---|---|---|
| 1.0 | 22.6 ± 1.9 | 119 ± 17 | 2926 ± 82 | 97 % | 359 | 44.2 km/h |
| 1.4 (baseline) | 28.7 ± 4.1 | 105 ± 26 | 2906 ± 83 | 81 % | 432 | 53.2 km/h |
| 2.0 | **39.3 ± 6.7** | 52 ± 18 | **3129 ± 93** | 50 % | 292 | **59.9 km/h** |

Weaker than `b` but pulling the same way. At 2.0 the discharge is the closest of any cell
to the empirical 3115, and the false-breakdown rate on the quiet day is zero. The cost is
a breakdown rate of 50 %, the lowest in the screen.

Truck acceleration was not varied here: it is a vehicle property rather than a driver one
and had already been validated separately at 0.71 m/s² measured in a jam.

### 4.4 `T` — desired time headway — reference row

`T` = 1.00/1.30 against the baseline 1.10/1.40 moved jam speed from 28.7 to
**34.1 ± 5.1** km/h — a shift of 5.4 km/h whose interval overlaps the baseline's. Duration
improved markedly (105 → 70 min) and discharge rose to 3036.

So `T` is not entirely inert on the congested branch, but against the 30 km/h span of `b`
it is a minor effect, and it was the axis three campaigns spent their compute on. Its real
role is capacity, and there it works as expected: shorter headway, higher discharge,
fewer breakdowns.

The preceding campaign established the other end of the range: `T` = 1.20/1.50 breaks down
in 86–100 % of runs on the day the site stayed free, while simultaneously worsening
smoothness (19.7 % stop-and-go, 614 standstills). Lengthening `T` beyond 1.10/1.40 is
excluded.

### 4.5 Relaxation acceleration damping — reference row

Damping 0.90 against 1.00 moved jam speed from 28.7 to **26.1 ± 3.7** km/h, that is
nothing. It did degrade standstills (432 → 717) and stop-and-go (13.2 → 19.6 %).

This confirms the reference-row expectation and retrospectively explains why the
headway-and-damping grids could not reach the empirical jam speed: **neither of their two
axes acts on it.**

Damping's real role is smoothness, and there its behaviour is not simple. In a
single-date grid it was monotone in every headway row — switching it off took vehicles
through a stop-and-go cycle from 30.8 % to 11.0 % and ramp standstills from 1237 to 340.
Across three days that monotonicity **breaks**: on 2025-09-22 damping 0.95 beat 1.00 on
both smoothness measures, on 2025-10-07 the ordering reversed again. A conclusion drawn
from one date did not survive three.

---

## 5. Interactions and the central trade-off

Every parameter that improves the congested branch also **reduces breakdown frequency**,
and every parameter that restores breakdown frequency degrades the congested branch. The
screen shows this as a consistent antagonism rather than a coincidence:

| Direction | Jam speed | Discharge | Smoothness | Breakdown rate |
|---|---|---|---|---|
| `b` down | ↑↑ | ↑ | ↑↑ | ↓↓ |
| `s0` up | ↑ | ↑ | ↑ | ↑ |
| `a` up | ↑ | ↑ | ↑ | ↓↓ |
| `T` down | ↑ | ↑ | ~ | ↓ |
| damping down | ~ | ~ | ↓ | ↑ |

`s0` is the exception that makes a combined setting possible: it is the only axis that
raises jam speed and breakdown frequency together. That is why the follow-up grid crosses
`b` (which supplies the jam speed but costs the rate) with `s0` (which supplies the rate),
with `a` as a third, weaker axis.

Expressed as single-cell agreement with the four targets, no cell in the screen satisfies
all of them, and the failures are informative:

- `b` = 1.5: jam speed and discharge close, specificity perfect (0 % on the quiet day),
  **breakdown rate far too low** (58 %).
- `s0` = 3.0: rate exact, duration and discharge close, **specificity catastrophic**
  (100 % on the quiet day).
- `a` = 2.0: discharge exact, specificity perfect, **rate far too low** (50 %).

---

### 5.1 How deep the queue is, in distribution rather than in the mean

The mean jam speed used above compresses a discrepancy that an earlier analysis resolved
across the whole distribution. Five-minute speeds during congestion at `det_L3a`, over the
three highest-demand days:

| | min | p10 | p25 | median | p75 |
|---|---|---|---|---|---|
| field | 14.2 | 27.2 | 34.0 | 43.4 | 53.1 |
| simulation | 10.1 | 17.1 | 20.1 | 26.4 | 40.5 |

**The whole distribution is displaced by 15–17 km/h, not merely its lower tail.** The
site's queue keeps moving at 34–53 km/h; the model's crawls at 20–40. From the
trajectories, about one congested vehicle in seven (13.9 %) goes through at least one
stop-and-go cycle and 11.9 % reach standstill, while the typical congested vehicle slows to
some 23 km/h without stopping. Both parts contribute: the oscillation drags the tail down,
the general depth shifts the whole distribution.

This matters for how the chapter frames the problem. It is not a stop-and-go artefact
sitting on an otherwise correct congested branch — the branch itself is in the wrong place.

*Caveat:* these figures were measured at an earlier operating point, `T` = 0.90 s for cars,
against the present baseline of 1.10. The shape of the finding is expected to carry, the
levels are not directly comparable. Source:
[calibration_status_briefing.md](calibration_status_briefing.md) §11.

### 5.2 A non-car-following suppressor: cooperation

The parameters above are not the only thing acting on acceleration in congestion. With the
car-following demand recorded alongside the executed acceleration, over 845 590 congested
mainline samples:

| intervening state | share of affected samples | share of total loss | median loss |
|---|---|---|---|
| **`OpenGapState`** | **84.0 %** | **87.9 %** | 0.93 m/s² |
| `PerformLaneChangeState` | 14.0 % | 10.1 % | 0.39 m/s² |
| `ExecuteLaneChange` | 2.0 % | 1.9 % | 1.09 m/s² |

**Cooperative gap opening accounts for 88 % of the acceleration suppressed in congestion.**
The relaxation damping accounts for none — its median loss is 0.00 whether active or not,
consistent with §4.5. Of the acceleration the model asks for, 95 % is realised on the
approach and 80 % on the merge section, so the loss is a 20 % effect localised at the
merge, not the factor of three an earlier hand calculation claimed.

What is *not* established is the link from this suppression to the congested speed measured
on the approach. The approach queue sits at equilibrium, so if the deficit reaches it, it
does so through density, and that chain is inferred rather than measured. Source:
[congested_branch_review_request.md](congested_branch_review_request.md) §8.2, §8.6.

### 5.3 Two switches that exist and were not used

- **`CAPACITY_DROP_ENABLED`** (default `false`) adds `alpha(v) · T_DISCHARGE_ADDON` to the
  desired headway, ramping from full at standstill to zero at `V_CRIT_DISCHARGE`. It would
  produce the capacity drop the model lacks, but it *adds* headway below 40 km/h and would
  therefore deepen the queue further — the opposite of what §5.1 needs. Worth testing, not
  worth expecting help from.
- **`RELAXATION_ACC_DAMPING_FACTOR`** has a framework default of **0.40**, while every
  campaign reported here ran it at 1.00, that is off. Numbers from this document should not
  be compared against runs at the framework default without noting that.

## 6. Where the breakdown happens, and why no parameter reaches the capacity drop

The capacity drop is the one target of this calibration whose **sign** is wrong, in every cell of
every campaign: the field discharges some 10 % less than it carried before breakdown, the model
discharges 3 to 8 % more. A dedicated study of the capacity-drop mechanism failed to turn it, and in
failing, explained it.

### 6.1 The model collapses on the rising flank

Demand around the moment of breakdown, over 55 runs of the calibrated set against the eight field
days:

| | 15 min before breakdown | 30 min after | |
|---|---|---|---|
| simulation | 3552 | 3805 | **+7.1 %** |
| field | 3767 | 3608 | **−4.2 %** |

In 93 % of runs the demand is **still rising** when the model breaks down. In the field it is already
falling. The site collapses at or after its demand peak; the model collapses on the way up.

That alone accounts for the sign. If demand keeps growing after the collapse, the queue afterwards
discharges more traffic than was flowing at the moment it formed, so the "drop" comes out negative by
construction — no parameter can invert it while the breakdown lands on the rising flank.

### 6.2 It is not a difference of mechanism, it is one of susceptibility

Both the site and the model break down through **merging**. Nothing else happens at an on-ramp, and
demand is the load, not the mechanism. The difference is *how loaded the mainline has to be* before a
merge succeeds in overturning it:

| | breakdown flow | highest free-flow flow reached | ratio |
|---|---|---|---|
| field | 3456 | 3676 | **94 %** |
| simulation | 3158 | 3504 | **90 %** |

The model's peak capability is close to the site's — 3504 against 3676, a 5 % shortfall. What differs
is the point on the flow axis at which a merge disturbance stops being absorbed. In the field a merge
is absorbed until the mainline is nearly saturated. In the model it succeeds earlier, at a flow the
model demonstrably sustains — it sustains exactly that flow in the queue afterwards, at 3282.

**The mainline is too susceptible to merge disturbances, or recovers from them too poorly.** That is
the finding; it is a statement about robustness, not about cause.

### 6.3 What this reframes

Three results documented elsewhere in this file follow from it rather than standing on their own:

*   **Shortening the headway barely raised the breakdown capacity** (§4.4, and a dedicated study
    measuring +5.5 % for an 18 % shorter headway). Capacity was never the binding constraint;
    susceptibility is.
*   **The capacity-drop addon lengthened the queue but did not turn the sign.** Measured against the
    free-flow 95th percentile instead of the transition interval — a reference that cannot be
    contaminated by the mechanism itself — the drop is still −3.1 to −4.7 % against a field +6.9 %.
    The addon lowers the pre-breakdown flow and the discharge together.
*   **The one day the site stayed free breaks down in the model regardless** (§2.1, §5). If breakdown
    depends on merge events rather than on how close demand is to capacity, a day being below capacity
    does not protect it.

### 6.4 Limits of this finding

The comparison rests on 55 runs of one cell against 8 field days, and the field's breakdown flow of
3456 comes from the same cross-section whose limitation §2.1 records — 114 m upstream of the merge,
inside the queue once one forms. The direction is solid; the magnitudes are not accurate to a few
percent. What would settle it is a measure of susceptibility itself rather than of its consequence:
the share of merge events followed by a sustained speed drop, as a function of the flow at which they
occur, in the model and in the trajectory data.

---

## 7. What is *not* a parameter effect

Several discrepancies that looked like calibration problems turned out not to be, and a
chapter based on this material should not attribute them to parameters.

**Detector triple-counting.** The evaluation summed three rows per interval — a total row
plus one per GTU type — inflating station flows by roughly a factor of two. The
"capacity deficit" that motivated three campaigns was largely this artefact.

**Arrival process on the mainline.** OTS defaults every origin to exponential
inter-arrivals. At a mean headway of 1.8 s the drawn distribution puts **24.2 % of gaps
below 0.5 s** — shorter than a car. This looked like a substantial defect and is not:

- Only **6 %** of such gaps appear on the road at the generation cross-section: the
  generator's placement logic already withholds vehicles that do not fit, imposing a de
  facto floor.
- By the approach cross-section 1360 m downstream the share is **0.85 %**. The run-in
  distance dissipates what remains.
- An explicit headway floor changed neither the gap distribution nor the disturbance level
  (acceleration standard deviation, hard-braking share) at any of the three cross-sections
  measured, nor any outcome metric at the merge.

The conclusion is geometric: with 1.4 km of mainline run-in, the boundary arrival pattern
does not reach the merge. **The on-ramp is different** — 310 m from the network boundary
to the merge point — and is the only inflow where arrival structure survives to the
bottleneck. Ramp arrivals released by a signalized intersection are burstier than Poisson
and cyclic rather than random, which no `HeadwayDistribution` can express, since that
interface receives only a random stream and never simulation time.

**Random-number handling.** Two defects, both corrected: the scenario built its driver
population stream from the same seed as the arrival stream (two generators, one identical
sequence), and replication seeds ran as consecutive integers so that replication *k*'s
second stream reproduced replication *k+1*'s arrival stream. Measured before the fix, a
paired comparison differing only in a behaviour parameter diverged in up to 9.9 % of
vehicle type assignments from the onset of congestion. After the fix that fell to 4.1 %
and the onset moved from t = 1050 s to t = 14859 s. The residual is consistent with
generator backlog reordering under congestion, which is physical.

**Deleted vehicles.** GTUs removed via the error handler had already begun a lane change
and simply ran out of ramp before completing it. It is a timing defect against the
remaining ramp length, not a gap-acceptance failure, and the numbers are small (≈0 per run
in the screen).

---

## 8. Methodological findings

These are worth a paragraph in the chapter in their own right, because they invalidate a
naive reading of the earlier campaigns.

**Seven seeds cannot resolve a cell comparison.** Breakdown probability is a share of
runs. At n = 7 a cell measuring 57 % has a Wilson interval of [0.25, 0.84] and one
measuring 86 % has [0.49, 0.97]. Those cells are not distinguishable, and the per-cell
comparisons of the earlier campaigns were treating them as if they were.

**Continuous metrics are worse.** Jam duration has a coefficient of variation near 49 %
across cells. The one apparent clean hit of the earlier campaign — 69 min against an
empirical 70 — carries a 95 % interval of **±60 min** over the four seeds that broke down
at all.

**Required sample sizes**, at a true breakdown rate of 0.6:

| n | Wilson half-width on the rate | Jam-duration accuracy |
|---|---|---|
| 7 | ±0.30 | ±36 % |
| 20 | ±0.20 | ±21 % |
| 30 | ±0.17 | ±18 % |
| 40 | ±0.15 | ±15 % |
| 154 | — | ±10 % |

**Pooling across days is what makes small campaigns work.** Nine days × 4 seeds gives 36
runs per cell — enough to rank cells — while 4 per day resolves nothing. This only holds
if the target is across-day performance, which for a proof of concept it is.

**Variance decomposition.** Within a cell, discharge varies by a coefficient of variation
of 7.7 % while everything tied to the jam event varies by 31–60 %. That is the signature
of a well-defined capacity whose *exceedance* depends on arrival structure — the same
picture the empirical target set shows in §2.

---

## 9. Open questions

- **The grid is not yet run.** `b` × `s0` × `a`, 18 cells over nine days, is defined
  (study `congested`) but unevaluated. Whether a combination reaches all four targets
  simultaneously is the open question this document sets up.
- **Interaction effects are assumed additive.** The screen varied one parameter at a
  time; the expectation that lowering `b` and raising `s0` will combine as their
  individual effects suggest is untested.
- **The specificity control rests on four runs per cell.** `s0` = 3.0 at 100 % has an
  interval of [0.51, 1.00]; `b` = 1.5 at 0 % has [0.00, 0.49].
- **Demand contamination during congestion** cannot be quantified without a second
  cross-section (§2.1).
- **The ramp arrival process.** A 2-minute periodicity is present in ramp counts on
  2025-10-27 and absent on 2025-09-22, robust across three detrendings. At 1-minute
  sampling the Nyquist period is 120 s, so the entire 60–120 s band of plausible signal
  cycles is unresolvable: the finding is consistent with a 120 s cycle and equally with an
  alias of a shorter one.
- **Driver parameters are deterministic, and whether to distribute them is genuinely
  unresolved.** `T`, `a`, `b` and `s0` are identical for every car and every truck; the only
  driver heterogeneity is desired speed. Two positions in this project's own documents
  contradict each other, and the chapter should present the tension rather than pick a side
  silently:

  - [calibration_status_briefing.md](calibration_status_briefing.md) §11 argues *for*
    distributing `T` and `a`: car-following models of this family are string-unstable at
    short desired headways, and short headways plus an identical driver population is
    exactly the configuration that grows small disturbances into stop-and-go waves. A
    heterogeneous platoon damps waves because its members do not all react alike, which is
    why distributed parameters are standard practice in this model family. It also addresses
    the missing day-to-day variation with the same change.
  - Ehrhardt & Tordeux (2024) argue the opposite for *scaled* heterogeneity: because
    ⟨1/a⟩ > 1 by Jensen's inequality, scaling parameters multiplicatively across a
    population destabilises rather than damps.

  These are not necessarily inconsistent — the second concerns a particular way of
  introducing heterogeneity, the first the existence of heterogeneity at all — but which
  applies to a distribution over `T` and `a` here has not been established, and no
  measurement in this project bears on it. An earlier draft of this document asserted the
  Ehrhardt & Tordeux position as settled; that was an overstatement.

---

## 10. Provenance

| Result | Source |
|---|---|
| §2 empirical targets | field database, 9 days, 5-minute aggregates |
| §4 screen, all pooled figures | study `sensitivity`, 360 runs (9 days × 10 cells × 4 seeds) |
| §4 merge and standstill figures | trajectories of 3 of the 9 days, 12 runs per cell |
| §4.4, §4.5 headway and damping context | earlier `settled` campaign, 126 runs (3 days × 18 cells × 7 seeds) |
| §6 arrival process | 9 local runs, 3 cross-sections (L1a, L3a, L4a) |
| §6 random numbers | 126-run re-analysis plus 2 paired runs after the fix |
| §5.1 speed distribution | [calibration_status_briefing.md](calibration_status_briefing.md) §11, measured at `T` = 0.90 |
| §5.2 acceleration attribution | [congested_branch_review_request.md](congested_branch_review_request.md) §8.2, 845 590 samples |
| §6 breakdown position | 55 runs of the calibrated cell against 8 field days, plus the demand profiles |
| §6.3 headway effect | study `capacity`, 432 runs (9 days x 6 cells x 8 seeds) |
| §6.3 capacity-drop addon | study `capdrop`, 432 runs (9 days x 6 cells x 8 seeds) |
| §8 sample sizes | Wilson and Student-t on the measured coefficients of variation |

Raw per-run records: `docs/mirova/results/`.

## 11. Related documents

- [calibration_status_briefing.md](calibration_status_briefing.md) — the calibration as a
  whole: empirical reference, validation result, per-day errors, the specificity test, and
  §9 "Corrections to the evaluation — read before using older numbers", which is required
  reading before quoting any figure predating those corrections.
- [congested_branch_review_request.md](congested_branch_review_request.md) — the analysis of
  why the queue is too slow, including what was measured, what was inferred, and which of
  its own claims were later withdrawn.
- [layer4_reactive_control.md](layer4_reactive_control.md) — the control law itself: the
  IDM+ variant in use, the kinematic bounding of the interaction term, the Keane & Gao
  relaxation and the acceleration damping, and the section distinguishing the several
  similarly named accelerations.
- [mirova_parameters_documentation.md](mirova_parameters_documentation.md) — the parameter
  catalogue: what each identifier means and where it lives.
