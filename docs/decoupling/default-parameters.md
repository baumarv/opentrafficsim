# Q8 — candidate defaults for the core, with provenance

The production parameter set of the published campaign, resolved to the values a run actually carries.
**Decided: these are the core's defaults** — the car column in `DriverParameterKeys`, the truck column
as `DriverPresets.TRUCK`, decelerations as positive magnitudes (contract §7, ADR-A and ADR-B).

## 0. Confirmed against the registered runs, not read off constants

The four layers below are easy to read wrong, so the values were taken from the study registration
itself: `Phase05ReferenceStudy.register` and `FreiburgProductionStudy.register` were both run against
the built classes, and the resolved `car.*` / `truck.*` entries of each variant were compared key by
key.

| Variant | Result |
|---|---|
| `reference` | **identical to the production set in all 27 behavioural keys** |
| `coreset` | the same 27, plus the five BC booleans on both vehicle classes, and nothing else |
| `coreset-interp` | the same, plus `bcDesireInterpolation` |

So the campaign's `reference` run *is* the production run, and the two core-reference variants differ
from it by switches alone. The `phase05.switch` record carries the switch ids joined by `+`, which is
what `runParams.txt` will show.

The 27 keys are the fourteen car and thirteen truck entries in §1. Anything not among them takes the
value the code declares, listed in §2.

## How the values were resolved

The production set is built in four layers, each overriding the last, so a constant read from
`FreiburgStudyParameters` is not necessarily what runs:

```
FreiburgStudyParameters.applyTo        base for both vehicle classes
  └─ FreiburgCombinationStudy.forCombination   T, relaxation damping, safety-distance factor
       └─ FreiburgCongestedBranchStudy.forCell  B, s0, a (cars)
            └─ FreiburgProductionStudy           B = 1.75, s0Car = 3.0, aCar = 1.4
```

Three values in `FreiburgStudyParameters` are therefore **not** what the campaign ran:
`RED_FAC = 0.60` (overridden to 0.40), `CAR_S0 = 2.0` (to 3.0), `COMFORTABLE_DECELERATION = 2.0`
(to 1.75). The table below gives the resolved values.

## Provenance, as classified

| Tag | Meaning |
|---|---|
| **L** | literature — a published source is named in the code or the paper |
| **C** | Freiburg-Nord calibration — chosen by a grid or factorial on this site, with the evidence recorded |
| **A** | assumption — a plausible value nobody has tested or sourced |
| **O** | OTS default — never set by anything; the value is whatever OpenTrafficSim ships |
| **?** | I cannot tell from the code, and did not guess |
| **I** | intended value, adopted by decision, not yet recalibrated |

---

## 1. Keys the production campaign sets

| Key | Car | Truck | Prov. | Evidence |
|---|---|---|---|---|
| `T` desired headway | **1.10 s** | **1.40 s** | C | Headway-against-damping grid. At 0.90 s the model broke down in one run in ten on 2025-10-27, a date on which the site does break down; 0.90 leaves too much capacity for this site. |
| `a` desired acceleration | **1.4 m/s²** | **1.25 m/s²** | L + C | Cars after Kesting et al. for motorway traffic. Trucks by a factorial over 0.7 / 1.0 / 1.3, monotone on every measure: ramp standstills 340 → 244 per run, right-hand lane +11.9 km/h in congestion, jam 11.7 min shorter. Deliberately **not** the field median of 0.60–0.87, because IDM reads the parameter as a ceiling. |
| `b` comfortable deceleration | **1.75 m/s²** | **1.75 m/s²** | C | `FreiburgProductionStudy.B`, overriding the Kesting value of 2.0 the base set carries. |
| `s0` standstill gap | **3.0 m** | **6.0 m** | L + C | Kesting et al.; trucks at his 2:1 ratio. The car value is the production override of the base set's 2.0. |
| `vGain` speed-difference scale | **15 km/h** | **30 km/h** | **I** | *Intended value (car 15 km/h, truck 30 km/h); not yet recalibrated jointly with the other parameters; the published results ran with 15 / 30 m/s due to an unintended unit conversion (see tag `published-model`).* Decided in Phase 1; see §6. |
| `aMax` driver's acceleration ceiling | **3.5 m/s²** | **1.3 m/s²** | L | The reference's `f(v)` curve, trucks scaled 1.3/3.5. |
| `bCoop` cooperative deceleration | **−3.0 m/s²** | **−1.0 m/s²** | C | Strengthening it was tried in both congestion regimes and is clearly worse in each: a gap opener braking harder holds up the column behind it and creates the disturbance that blocks the merge. |
| `cooperate` cooperation enabled | *not set* → true | **false** | ? | Trucks do not cooperate. No rationale is recorded in the code. |
| `fGap` safety-distance reduction | **0.40** | **0.40** | C | `FreiburgCarStudy.SAFETY_DISTANCE_FACTOR`, carried through the calibration; overrides the base set's 0.60. Interacts multiplicatively with `s0`, so grid values are not comparable across a change of `s0`. |
| `bFollowerMin` | **−2.0 m/s²** | *not set* → −2.0 | C | The pair is an operating-point decision and the two tests disagree. On a heavily congested hour, −2.5 / −5.0 was the single effective lever against ramp standstills (37.4 → 29.9 per run). On a full day at the calibrated point, a 108-run factorial found the opposite: −2.5 raised standstills 29 % (265 → 340). The calibration targets ordinary days, so the tighter pair stands. **First parameter to revisit if heavy congestion becomes the target.** |
| `bFollowerMax` | **−4.0 m/s²** | *not set* → −4.0 | C | as above; the two bound one interpolation ramp and neither moves alone |
| `relaxDamping` | **1.00** | **1.00** | C | **Damping is off in the published campaign.** A 120-run grid found it monotone in every headway row: at 1.10/1.40 it takes vehicles through a stop-and-go cycle from 30.8 % to 11.0 % and ramp standstills from 1237 to 340. It cannot be set alone — removing the damping raises discharge enough to prevent breakdowns, which is why the headway was lengthened at the same time. |
| `relaxDampingOn` | true | true | C | On, with a factor of 1.00, which is a no-op. Kept true so the mechanism stays reachable. |
| `capDropOn` | false | false | A | Off in every campaign. |
| `farAnticipation` *(not a core key)* | false | false | — | Set by the study, read only inside an unregistered pattern. Not in the contract. |

## 2. Keys the campaign never sets — the value is whatever the code declares

| Key | Value | Prov. | Note |
|---|---|---|---|
| `dFree` | 0.365 | L | LMRS (Schakel et al.), and the paper's table |
| `dMand` | 0.577 | L | as above |
| `dSearch` | 0.788 | L | as above. Restored in Phase 1; unread today because of the θ bug (Q9). |
| `socio` socio-speed sensitivity | 0.25 | **A** | Checked against LMRS (§5): `SOCIO` is 1.0 there, constrained to the unit interval. MiRoVA declares 0.25 and drops the bound, and the demo scenarios set 0.75. A MiRoVA choice with no recorded source. |
| `vCong` congestion speed | 60 km/h | L | Paper table, and the LMRS default |
| `t0` route time horizon | 43 s | O | The OTS `T0` default, untouched |
| `lcDuration` | 3.0 s | O | The OTS `LCDUR` default. The reference cites Berghaus & Oeser's τ_LC = 6 s for mergers — **not what runs.** |
| `lcDurationCongested` | 1.5 s | A | Read only from commented-out code; revived by the contract |
| `bCrit` | −3.5 m/s² | A | MiRoVA's own default |
| `bMax` | −6.0 m/s² | A | MiRoVA's own default |
| `cfMaxLeaders` | 2 | A | |
| `fSpeed` desired-speed factor | drawn | L | Never a scalar: drawn per vehicle from `DesiredSpeedLibrary`. The distribution shape is the core's, the calibrated variant the host's (`parameters.md` §4). |
| `tauRelax` | 20 s | ? | Keane & Gao give **15 s**. Where 20 s comes from is recorded nowhere I can find; the published results rest on it. |
| `relaxLifetime` | 3.0 × τ | A | |
| `relaxAbortB` | −1.0 m/s² | A | |
| `relaxFade` | 1.0 s | A | |
| `bEgoMin` / `bEgoMax` | −2.0 / −4.0 m/s² | A | Same shape as the follower pair, never tested separately |
| `dCoop` cooperation range | 100 m | L | Paper table `x_coop` |
| `tUndercut` | 5 s | L | Paper table `t_undercut` |
| `tDischargeAddon` | 0.5 s | A | Capacity drop is off |
| `vCritDischarge` | 40 km/h | A | as above |
| `tDischargeFraction` | 0.0 | A | as above |
| `vCritDischargeFraction` | 0.0 | A | as above |

**Counted:** of 38 keys, **12 carry a Freiburg-Nord calibration**, 9 a literature source, 14 an
assumption (`socio` moved here after the LMRS check), 1 an OTS default nobody chose, and **2 remain
unclassified: `vGain` and `tauRelax`**. Neither is minor — `vGain` scales every discretionary desire
and `tauRelax` *is* the relaxation.

---

## 3. Three things that came with the values, all decided

**Sign convention (ADR-A).** OTS carried `B` and `BCRIT` as positive magnitudes and MiRoVA's own
deceleration parameters as negative numbers — the same physical quantity with two signs in one
parameter set. **Decided: every parameter is a positive magnitude, and only computed accelerations are
signed.** A parameter is a bound the driver brings and a bound has no direction; the sign appears where
the model applies it. `EgoState.maxDeceleration` follows the parameter convention, because a vehicle
limit is brought rather than computed. In this document the deceleration columns still carry the
signs the OTS code uses, so `bCoop = −3.0` here is `3.0.metersPerSecondSquared` in the contract.

**One default set, and it is the car set (ADR-B).** `DriverParameters` describes one driver, so the
defaults can only be one class. Cars are the majority and the class the calibration was steered by;
trucks are `DriverPresets.TRUCK`, seven keys applied as an override. Which class a driver belongs to is
population configuration, and that is the host's.

**The default is no longer the code's current behaviour.** Eleven values change, one of them by a
factor of nearly five (`vGain`: 69.6 → 15 km/h) and one from damping at 0.40 to no damping at all
(`relaxDamping` 1.00). A `major` step by contract §11 — taken now, before v1 is tagged.

## 4. What is left

Two provenances, and both matter more than their size suggests. **`vGain`** runs at 15 / 30 km/h with
no source anywhere: not LMRS (§5), not a paper the code cites, only the model reference's table. It
scales every discretionary lane-change desire. **`tauRelax`** is 20 s where Keane & Gao give 15 s, with
no note saying why, and it is the relaxation itself. Both belong in the systematic paper-against-code
check, and until they are closed the core ships two defaults nobody can defend.

---

## 5. The LMRS check on `vGain` and `socio`

Both were tagged `?` on the assumption that they came from Schakel et al. (2012), the LMRS source.
They do not. Checked against OTS's own LMRS implementation, which is Schakel's
(`ots-road/.../tactical/util/lmrs/LmrsParameters.java`, his name on the file):

| | LMRS | MiRoVA declares | Campaign runs |
|---|---|---|---|
| `vGain` | `69.6 km/h`, positive | `69.6 km/h` — the LMRS value, adopted | **15 km/h car / 30 km/h truck** |
| `socio` | `1.0`, **constrained to [0, 1]**, "sensitivity level for speed of others" | `0.25`, constrained only to positive — **the unit-interval bound is dropped** | 0.25 (never set) |

Two consequences.

**`vGain` is settled by decision, not by a source.** The declared default was LMRS's 69.6 km/h; what
ran was 54 / 108 km/h, the table's numbers read in the wrong unit; what the model now carries is the
intended 15 / 30 km/h. That is a decision about intent, not a finding about provenance — the table
still cites nothing, and the value has not been recalibrated.

**`socio` becomes `ASSUMPTION`.** It is not the LMRS value, nothing else claims it, and MiRoVA has
loosened the constraint so that values above 1 are legal — which the demo scenarios do not use but
could. Worth a look when the systematic paper check runs: LMRS's σ and MiRoVA's socio-speed sensitivity
enter their respective formulas differently, so the numbers may not even be comparable.

`tauRelax` is the third of this kind and remains open: Keane & Gao give 15 s, the code has 20 s, and no
note anywhere says why.

---

## 6. `vGain` runs at 54 km/h, not 15

Found while preparing the sensitivity study, and it changes what "the production value" means.

`FreiburgStudyParameters.baseBehaviorParams` writes bare numbers:

```java
params.set("car." + MirovaParameters.vGain.getId(), 15.0);
params.set("truck." + MirovaParameters.vGain.getId(), 30.0);
```

`MirovaParameters.vGain` is a `ParameterTypeSpeed`, and `ScenarioGenerator.applyParameter` converts a
bare `Double` for a speed with `Speed.instantiateSI(value)` — **SI, so metres per second.** Pushed
through that exact code path:

```
study sets car.VGAIN   = 15.0  ->  15.0 m/s = 54.0 km/h
study sets truck.VGAIN = 30.0  ->  30.0 m/s = 108.0 km/h
declared default               =  19.33 m/s = 69.6 km/h
model reference table          =  15 km/h car / 30 km/h truck
```

**Every published run used 54 km/h for cars and 108 km/h for trucks.** The factor between table and
run is exactly 3.6.

Two readings, and the code cannot distinguish them:

1. **The table is right and the setter is wrong.** 15 km/h was intended; the runs use 3.6× that, and
   the calibration was carried out on a model whose lane-change desire saturates much later than
   documented.
2. **The setter is right and the table's unit is wrong.** 15 m/s was intended, and the table's "km/h"
   is a transcription slip. 54 km/h sits plausibly beside LMRS's 69.6; 108 km/h for trucks does not
   sit plausibly beside anything — a truck would need a 108 km/h speed advantage for a full desire.

**I have changed nothing.** The campaign is starting, every variant shares this value, and the
comparison is unaffected. But it decides what the core's `vGain` default should be, and the value
adopted for now is what ran: `15.0.metersPerSecond`.

Two further consequences worth naming:

- **Every other speed-typed parameter set as a bare double has the same exposure.** In the production
  path `vGain` is the only one, but the conversion is generic and the next `ParameterTypeSpeed`
  someone sets this way will land in m/s too. A typed setter, or a unit suffix in the key, would make
  this unrepresentable — which is the same argument the contract makes for typed keys (§7).
- **The sensitivity study prepared for `vGain` therefore converts explicitly**, so that its
  `{15, 30, 50, 69.6} km/h` really are km/h. Its baseline cell is the production point, 54 / 108 km/h,
  which is not among the four.
