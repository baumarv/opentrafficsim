# Q8 — candidate defaults for the core, with provenance

The production parameter set of the published campaign, resolved to the values a run actually carries,
offered as the core's defaults. **Nothing is changed in `DriverParameterKeys` — this is the paper you
decide on.**

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

---

## 1. Keys the production campaign sets

| Key | Car | Truck | Prov. | Evidence |
|---|---|---|---|---|
| `T` desired headway | **1.10 s** | **1.40 s** | C | Headway-against-damping grid. At 0.90 s the model broke down in one run in ten on 2025-10-27, a date on which the site does break down; 0.90 leaves too much capacity for this site. |
| `a` desired acceleration | **1.4 m/s²** | **1.25 m/s²** | L + C | Cars after Kesting et al. for motorway traffic. Trucks by a factorial over 0.7 / 1.0 / 1.3, monotone on every measure: ramp standstills 340 → 244 per run, right-hand lane +11.9 km/h in congestion, jam 11.7 min shorter. Deliberately **not** the field median of 0.60–0.87, because IDM reads the parameter as a ceiling. |
| `b` comfortable deceleration | **1.75 m/s²** | **1.75 m/s²** | C | `FreiburgProductionStudy.B`, overriding the Kesting value of 2.0 the base set carries. |
| `s0` standstill gap | **3.0 m** | **6.0 m** | L + C | Kesting et al.; trucks at his 2:1 ratio. The car value is the production override of the base set's 2.0. |
| `vGain` speed-difference scale | **15 km/h** | **30 km/h** | ? | The paper's table gives exactly these, and the scenario sets them, but no source is named in either. **Not** the OTS/LMRS default of 69.6 km/h. |
| `aMax` driver's acceleration ceiling | **3.5 m/s²** | **1.3 m/s²** | L | The reference's `f(v)` curve, trucks scaled 1.3/3.5. |
| `bCoop` cooperative deceleration | **−3.0 m/s²** | **−1.0 m/s²** | C | Strengthening it was tried in both congestion regimes and is clearly worse in each: a gap opener braking harder holds up the column behind it and creates the disturbance that blocks the merge. |
| `cooperate` cooperation enabled | true | **false** | ? | Trucks do not cooperate. No rationale is recorded in the code. |
| `fGap` safety-distance reduction | **0.40** | **0.40** | C | `FreiburgCarStudy.SAFETY_DISTANCE_FACTOR`, carried through the calibration; overrides the base set's 0.60. Interacts multiplicatively with `s0`, so grid values are not comparable across a change of `s0`. |
| `bFollowerMin` | **−2.0 m/s²** | −2.0 m/s² | C | The pair is an operating-point decision and the two tests disagree. On a heavily congested hour, −2.5 / −5.0 was the single effective lever against ramp standstills (37.4 → 29.9 per run). On a full day at the calibrated point, a 108-run factorial found the opposite: −2.5 raised standstills 29 % (265 → 340). The calibration targets ordinary days, so the tighter pair stands. **First parameter to revisit if heavy congestion becomes the target.** |
| `bFollowerMax` | **−4.0 m/s²** | −4.0 m/s² | C | as above; the two bound one interpolation ramp and neither moves alone |
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
| `socio` socio-speed sensitivity | 0.25 | ? | The paper's table does not list it; the LMRS default is different. No source found. |
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

**Counted:** of 38 keys, **12 carry a Freiburg-Nord calibration**, 9 a literature source, 13 an
assumption, 1 an OTS default nobody chose, and 3 I could not classify (`vGain`, `socio`, `tauRelax`).
The three unclassified are not minor — `vGain` scales every discretionary desire and `tauRelax` is the
relaxation.

---

## 3. Three things to decide with the values

**Sign convention.** OTS carries `B` and `BCRIT` as positive magnitudes and MiRoVA's own deceleration
parameters as negative numbers, so the same physical quantity appears with both signs in one parameter
set. The contract makes every deceleration a negative `Acceleration`. Adopting the production values
means translating `B = 1.75` to `−1.75 m/s²`, which is mechanical but is exactly the kind of step where
a sign is lost.

**One parameter set per driver, not per class.** `DriverParameters` describes one driver. Car and truck
differ in eleven of the keys above, and in the core that is the **host's** population configuration: it
creates agents with different overrides, or draws from different distributions. Whichever set becomes
the default can only be one of the two. **I would make the car set the default** — it is the majority
class and the one the calibration was steered by — and ship the truck set as a named override in the
adapter.

**The default would then not be the code's current behaviour.** Eleven values change, one of them by a
factor of nearly five (`vGain`: 69.6 → 15 km/h) and one from a no-op to a no-op with a different
meaning (`relaxDamping` 0.40 → 1.00, i.e. damping off). This is a `major` version step by §11 of the
contract, and it should happen before v1 is tagged rather than after.

## 4. What I would do

Adopt the production set, with the car values as defaults, and carry the provenance in the code rather
than only here — `ParameterKey` now has a `provenance` field, populated for every key, so that a value
nobody has ever justified says so at the point of use. Then close the three `?` entries: `vGain` and
`socio` by finding the source or admitting there is none, and `tauRelax` by recording why it is 20 s
and not Keane & Gao's 15 s, because that one number is load-bearing for the merge behaviour of every
published result.
