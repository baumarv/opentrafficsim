# Why the simulated queue is too slow — analysis and proposal, for review

A request for an independent check. The question is why the congested branch of the Freiburg-Nord
model runs 15–17 km/h below the field, what the data rules out, and whether the proposed
intervention follows from what is actually established.

Written so that measurement and inference can be attacked separately. Section 2 is measurement and
should be checkable against the data; sections 4 and 5 are argument and are where the reasoning may
fail. Section 6 lists what is *not* established, including two hypotheses that were already
refuted — one of them mine, twice.

---

## 1. The discrepancy

The model is otherwise well calibrated. Over nine dates with the settled parameter set, queue
discharge is within 1.6 % of the field (3065 against 3115 veh/h), breakdown onset is inside the
field interval on every day, and seven of eight empirical days fall inside the simulated 10–90 %
band for discharge with evenly spread ranks.

The congested branch is not. Distribution of 5-minute speeds during congestion at the bottleneck
cross-section, over the three highest-demand days:

| | min | p10 | p25 | median | p75 |
|:---|:---|:---|:---|:---|:---|
| field | 14.2 | 27.2 | 34.0 | **43.4** | 53.1 |
| simulation | 10.1 | 17.1 | 20.1 | **26.4** | 40.5 |

The **whole distribution** is shifted down, not just its tail. The field's queue keeps moving at
34–53 km/h; the model's crawls at 20–40.

This also produces a second symptom: simulated and empirical day values for jam speed correlate at
**r = −0.81 (p = 0.014)**. On the busy days, where the field's queue moves fastest, the model's is
deepest.

---

## 2. What is measured

### 2.1 Throughput does not account for it

Across 99 cells from five campaigns (736 runs with a breakdown), queue discharge and mean congested
speed correlate positively — Pearson **r = +0.427** (p = 1·10⁻⁵), Spearman +0.377; across all 736
individual runs +0.390; empirically over 8 days **+0.780** (p = 0.022). The relationship is real and
monotone, with a slope of **+1.4 km/h per 100 veh/h** of discharge.

But the magnitude does not carry the deficit. In the validation campaign the discharge is only
**50 veh/h** short while the congested speed is **5.4 km/h** short. At that slope the discharge
deficit accounts for 0.7 km/h — about **13 %**.

### 2.2 The queue is not dense — it is sparser than equilibrium

Measured net gaps between consecutive vehicles in the congested merge section, against the IDM
equilibrium `s = s₀ + v·T` at the driven speed (car s₀ = 2.0 m, T = 0.90 s; truck 4.0 / 1.20):

| speed [km/h] | measured gap | IDM equilibrium | ratio |
|:---|:---|:---|:---|
| 0–10 | 17.5 m | 4.0 m | **4.06** |
| 10–20 | 16.9 | 6.5 | 2.54 |
| 20–30 | 19.6 | 8.4 | 2.37 |
| 30–40 | 19.9 | 10.6 | 1.41 |
| 40–60 | 18.6 | 14.5 | 1.21 |

Median ratio 2.44; only 19.2 % of pairs sit below equilibrium. The equilibrium speed for the
*measured* gap is 55.1 km/h where 16.5 km/h is driven.

**Vehicles are slow despite having space.** A density explanation — too little getting through, hence
a denser and therefore slower queue — predicts the opposite sign and is not supported.

The platoon is also almost never in equilibrium: **8.3 %** of congested samples are quasi-steady
(|a| < 0.01 m/s²), 59 % accelerating, 33 % braking.

### 2.3 Stop-and-go accounts for at most 40 %

| population | share of vehicles | median v | mean v |
|:---|:---|:---|:---|
| with a stop-and-go cycle | 18.1 % | 5.8 | 9.0 |
| without | 81.9 % | 32.1 | 33.4 |
| all | | 23.9 | 25.5 |

Removing the oscillating vehicles raises the mean by 7.9 to 33.4 km/h, leaving **11.3 km/h** to the
empirical median of 43.4. String instability could therefore explain at most about 40 % of the shift
even if it were fully eliminated; the majority comes from the non-oscillating population.

### 2.4 No structural mismatch at corridor scale

Empirically the speed drop occurs at L1a, L2a and L3a within the **same 5-minute interval**, over
2.4 km. A wave propagating at −15 km/h would show a one-interval lag between adjacent detectors
(1.2 km per 5 min = 14.4 km/h). The observed lag is zero — consistent with a spatially extended,
simultaneous transition rather than a moving jam.

In the simulation the cross-section downstream of the merge stays at 91–93 km/h while the bottleneck
is at 26–41. The front is pinned at the merge there too.

So the mismatch is not "moving jams versus a stationary front" at corridor scale. It is inside the
queue.

### 2.5 Acceleration in the queue is far below the parameter

Vehicles that *are* accelerating do so weakly: at 0–10 km/h the median is **0.41 m/s²** and the p90
0.90, against a car parameter of 1.40. At a gap four times equilibrium, IDM would give roughly 1.3.
The relaxation acceleration damping is not the cause — its factor has a median of **1.000** in the
queue.

### 2.6 Cooperation is present but its episodes are short

`OpenGapState` covers **14.7 %** of all mainline samples and **24.4 %** of congested ones. Median
speed while in it is 13.2 km/h against 73.6 km/h otherwise — but the episodes are brief: median
**1.2 s**, p90 7.4 s, only 1.5 % exceed 30 s. 58.7 % of episodes start above 60 km/h and last about
a second.

**The speed association is confounded by location** — cooperation happens in the merge zone, which is
slow anyway. These numbers do not establish that cooperation causes the deficit.

### 2.7 Limits of the measurement

- Trajectories are recorded **only on the 202 m merge link**. Sections 2.2, 2.3, 2.5 and 2.6
  therefore describe the merge zone, not the queue upstream of it. Whether the platoon is equally far
  from equilibrium further upstream cannot be said with the present output.
- The empirical `det_L5a` series is **identical to `det_L3a`**, so the downstream comparison on the
  field side is unusable. The simulation side of 2.4 stands on its own.
- The empirical detector resolution (5 min, 1.2 km) cannot distinguish a stationary front from a
  wave faster than about 15 km/h.

---

## 3. The control law, as implemented

`GapOpenerPattern` locks onto a candidate on the adjacent lane showing a turn indicator toward the
ego, within a lookahead distance, and provided the candidate lies within the ego's own front gap and
the ego's leader cannot cooperate instead. In `OpenGapState`:

```java
aCooperation = MirovaCarFollowingUtil.followSingleLeader(vehicle, candidate);
aCooperation = aCooperation.gt(decelThreshold) ? aCooperation : decelThreshold;
finalAcceleration = Acceleration.min(aCooperation, aDirectLeader);
```

The ego **follows the candidate as if it were its own leader**, bounded below by a distance-dependent
deceleration threshold. The state ends when the candidate merges, drops the indicator, or the ego's
leader takes over the cooperation.

Two further properties: `getUtility()` returns a constant, so the arbitration never weighs how useful
a given cooperation is; and cooperation is explicitly permitted from within a standing queue (the
branch for ego below 5 km/h with a front gap under 15 m). 21.1 % of episodes start below 20 km/h and
reach a median minimum of 0.0 km/h.

---

## 4. The argument

This is inference from the control law, not measurement. It is the part most in need of checking.

**Following a vehicle means adopting its speed.** A car-following target is an equilibrium at
`s₀ + v·T` behind the followed vehicle, and equilibrium behind a vehicle means the same speed as that
vehicle. Since the followed vehicle here is on the ramp, the ego's terminal state is *ramp speed*.

**And the ego's own front gap then grows without a target.** Its mainline leader is faster, so the gap
ahead of the ego increases for as long as the cooperation lasts. `min(aCooperation, aDirectLeader)`
lets the cooperation term govern precisely because it is the more restrictive one. Nothing in the
state stops this at a sufficient gap; it stops when the candidate's indicator goes out.

So the speed reduction lasts **as long as the cooperation**, not as long as the gap-opening.

The alternative would be to enlarge the ego's **desired headway to its own leader** — `T → T + ΔT` —
rather than to hand its speed over to the candidate. Then:

| | as implemented | enlarged desired headway |
|:---|:---|:---|
| deceleration to open the gap | yes | yes, the same amount |
| terminal speed | **ramp speed** | mainline speed |
| duration of the speed loss | duration of the cooperation | duration of the opening |
| target for the gap | none | `ΔT` |

The deceleration itself is unavoidable and identical in both: the integral of the speed difference
*is* the gap increment, and no formulation avoids that. Opening 15 m at a 2 m/s deficit takes 7.5 s.
What differs is the state the vehicle settles into afterwards.

**Why the enlarged gap cannot come from the car-following model itself.** IDM closes any gap above
`s₀ + v·T` — at 20 km/h that is about 7 m. Holding a 20 m gap open for a merger is by construction
what the model works against. Tolerating a larger-than-equilibrium gap is therefore necessarily a
pattern behaviour, not a car-following one.

**Why it must decay rather than end.** A hard termination returns the vehicle to its equilibrium gap,
which it then closes — giving the gap back exactly while the merger is positioning, and producing an
open/release/close oscillation. A `ΔT` decaying over a time constant avoids that.

The model already contains the mirror image of this mechanism: the Keane & Gao relaxation lets a
vehicle tolerate a *smaller* gap than equilibrium after a cut-in, with virtual buffers decaying over
τ_s ≈ 15 s and τ_v ≈ 5 s. The proposal is the same construction with the opposite sign, on the
cooperative side.

---

## 5. Proposal

Replace "follow the candidate" with "temporarily enlarge the desired headway to one's own leader
while a merge is pending, decaying over a time constant".

Not proposed: suppressing cooperation, adding a hard termination, or a sufficiency cut-off. Each was
considered and each fails — the first costs merges, the second and third return the gap.

---

## 6. What is not established

Please attack these first.

- **Causation.** §2.6 shows association, not cause. Cooperation happens where traffic is slow. The
  1.2 s median episode makes a sustained-braking story hard to sustain, and I earlier claimed the
  congested branch is "governed by the cooperation logic" on this evidence — that claim was too
  strong and is withdrawn.
- **Coverage.** The gap and cooperation measurements come from a 202 m merge section. The congested
  speeds of §1 come from a detector at that bottleneck. Whether the same holds along the queue is
  untested.
- **Two hypotheses already refuted**, both mine: that low throughput makes the queue dense and hence
  slow (§2.2 shows gaps *larger* than equilibrium), and that distributed driver parameters would damp
  the oscillation (Ehrhardt & Tordeux 2024 derive the opposite for scaled heterogeneity — `⟨1/a⟩ > 1`
  by Jensen — and §2.3 shows the oscillating population is the minority anyway).
- **Magnitude.** Nothing here predicts how much of the 15–17 km/h the proposal would recover. The
  argument establishes a mechanism and a direction, not a size.
- **The alternative I have not excluded**: that the deficit is simply what this car-following
  parameterisation produces at the merge, and that the cooperation logic is a minor contributor. §2.5
  is the awkward observation for my own argument — vehicles accelerate at 0.41 m/s² where IDM alone
  would give 1.3, and I have not identified what suppresses that. If the answer is not cooperation,
  the proposal misses the target.

---

## 7. What would settle it

A paired comparison on the jam-capable local setup (2025-10-27, 15:00–19:00, seven seeds per
variant), current behaviour against the enlarged-headway variant, measuring all four together:

| | must |
|:---|:---|
| congested speed distribution at the bottleneck | rise |
| merges per run | not fall |
| ramp standstills | not rise |
| merge speed | not fall |

The last two are the constraint: weaker cooperation costs there first, and a standstill increase is
a hard exclusion criterion for this project.

Before that, one cheaper measurement would sharpen §6: extend the trajectory sampler to the approach
link, so the gap-versus-equilibrium comparison can be made along the queue rather than only in the
merge section. If the platoon upstream sits at its own equilibrium while the merge section does not,
that localises the cause and the proposal follows. If it is equally far from equilibrium upstream,
where no cooperation is active, the proposal is aimed at the wrong mechanism.

---

## 8. Results of the measurements requested in review

All five items are in. Two of them change the conclusions above; the corrections are stated where
they belong rather than only here.

### 8.1 The decisive test: the deviation is localized to the merge

Gap against IDM equilibrium, now measured separately on the approach link — where no cooperation is
active — and on the merge link (4 runs, 43 620 congested following pairs):

| link | cooperation | gap/s_eq overall | 0–10 km/h | 10–20 km/h |
|:---|:---|:---|:---|:---|
| **L3a, approach** | **0.0 %** | **1.18** | 1.18 | 1.16 |
| **L4a, merge** | **20.0 %** | **1.45** | 2.27 | 2.45 |

**Localized to the merge.** On the approach the platoon sits at its own car-following equilibrium
across every speed band; on the merge section it does not.

**This also limits §2.2.** That section refuted a density explanation using gaps measured on the
merge link only. On the approach the queue *is* dense and slow and follows the model — and the
detector that measures the congested-speed deficit sits on that link. The two explanations are
therefore not alternatives: if the merge section throttles the outflow, the approach densifies and
slows with its car-following behaviour entirely intact. §2.2 as written overstates its reach.

### 8.2 The acceleration deficit, measured rather than inferred

The car-following acceleration is now recorded alongside the executed one, so the difference can be
attributed. Over 845 590 congested mainline samples:

| intervening state | share of affected samples | share of total loss | median loss |
|:---|:---|:---|:---|
| **`OpenGapState`** | **84.0 %** | **87.9 %** | 0.93 m/s² |
| `PerformLaneChangeState` | 14.0 % | 10.1 % | 0.39 |
| `ExecuteLaneChange` | 2.0 % | 1.9 % | 1.09 |

**Cooperation accounts for 88 % of the acceleration suppressed in congestion.** The relaxation
damping accounts for none — the median loss is 0.00 whether or not it is active. Of the acceleration
the model asks for, **95 % is realized on the approach and 80 % on the merge section**.

**§2.5 was overstated and is corrected.** It claimed the model would ask for about 1.3 m/s² where
0.41 is driven, a factor of three. Measured, the car-following model asks for a median of 0.72 on the
merge link and 0.58 is executed — a 20 % loss, not a factor of three. The 1.3 was a hand calculation
that ignored the speed term. Only 10.3 % of congested samples lose anything at all, though those lose
0.89 m/s² at the median.

So cooperation is confirmed as the dominant suppressor, and the quantity it suppresses is smaller
than §2.5 claimed.

### 8.3 Congestion structure: the simulation propagates upstream

With the approach link sampled the corridor spans 433 m, enough to resolve a wave at −15 km/h.
Cross-correlating speed at two points 300 m apart, over the congested period:

| run | lag | r | propagation |
|:---|:---|:---|:---|
| seed 42 | 70 s | 0.18 | −15 km/h |
| seed 46 | 130 s | 0.55 | −8 km/h |
| seed 47 | 190 s | 0.46 | −6 km/h |

All three show a positive lag: the downstream point is disturbed first. **The simulation produces
upstream-propagating structures at −6 to −15 km/h, not a stationary front.** The correlations are
moderate, and three runs are few, but the sign is consistent.

§2.4's conclusion of "no structural mismatch" does not survive on the simulation side. On the field
side the question is not open but unanswerable: see 8.5.

### 8.4 Sampler extension and its cost

`FreiburgNord.SAMPLED_LINK_IDS` now takes a comma-separated list via `-Dmirova.samplerLinks`,
defaulting to the merge link so campaigns are unchanged.

| | trajectory file |
|:---|:---|
| 30 min, merge only | 1.5 MB |
| 30 min, merge + approach | 2.5 MB |
| 9 h campaign run, merge only | 26.1 MB |
| 9 h campaign run, extrapolated with approach | ≈ 43 MB |

A factor of 1.67, not a multiplication. A 270-run campaign would grow from roughly 7 to 12 GB, which
the workspace tolerates but which should be a deliberate choice rather than a default.

### 8.5 The `det_L5a` duplication: a preprocessing error, and §2.4 is void because of it

`empirical.py` mapped only `det_L3a` and `det_L7a` to measurement positions and fell back to
`"Hauptfahrbahn"` for everything else. The database holds exactly three positions at this site — one
mainline cross-section, the on-ramp, the off-ramp. **There is no empirical detector chain**; the
seven `det_LXa` names are simulation detectors, and five of them have no field counterpart.

Reach: the evaluation pipeline requests only `det_L3a` and `det_L7a`, both correctly mapped, so no
campaign result is affected. Two ad-hoc analyses are void — a speed profile along the corridor that
compared the simulated downstream cross-section against the upstream empirical one, and §2.4's
empirical propagation analysis, which cross-correlated the mainline series with itself and duly found
a lag of zero at r = 0.99.

**§2.4 is therefore not merely unresolved on the field side. It is unanswerable**: the site has one
mainline cross-section, so no propagation speed can be estimated from it at any resolution. The
unused off-ramp position has been added, and an unknown id now raises rather than returning a
plausible frame.

### 8.6 Where this leaves the proposal

Supported, with its scope narrowed. Cooperation is measurably the dominant suppressor of
acceleration and the only place the model departs from its own car-following behaviour. What is not
shown is the link from that suppression to the congested speed measured on the approach — the
approach queue is at equilibrium, so if the deficit reaches it, it does so through density, and that
chain is inferred rather than measured.

The magnitude therefore remains unknown, as §6 said. The paired comparison of §7 is still what would
settle it, and the four criteria stand unchanged.
