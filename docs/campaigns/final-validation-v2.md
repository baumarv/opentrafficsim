# The final validation campaign

**496 runs — sixteen days, 31 seeds, one parameter set.** `--study=tamafinal`, cell `final_v2`.

This is the run the paper rests on. It measures the model as it now stands against sixteen days of
field data. It is deliberately **not** a comparison against the earlier 800-run validation: that ran
on `mirova-reference/1` and an older build of this repository, so any difference would mix the
parameter change with everything else that moved, and no reader could attribute it.

## What it runs

The frozen validation set, taken from `TamaHeadwayScreeningStudy.baseline` at its standard headway
rather than restated, plus **one** change:

| | value |
|---|---|
| car desired-speed distribution | compressed towards **150 km/h** by a factor of **0.7** |
| everything else | the frozen set, unchanged |

The map is `v -> pivot + factor * (v - pivot)`: every driver and their order is kept, the slow ones
move up a lot, the median a little, the fastest slightly down. On the distribution
`carsLimit140_DensityLow` it moves the slowest wish from 80 to 101 km/h, the share below 110 km/h
from 15.6 % to 4.9 %, the p95 from 185.5 to 174.8 and the harmonic mean from 130.4 to 137.5 km/h.

`TamaFinalValidationStudyTest` compares the campaign's parameters entry by entry against the study
that defines the frozen set, so a divergence fails here rather than in the results, and checks that no
second reshaping of the distribution is set.

## Why this change and no other

From `free-flow-speed-investigation.md`, 117 runs over three axes. Against the field, with both sides
at five-minute intervals and the same kind of mean:

| cell | Δp50 | Δp85 | Δp95 | spread p95−p50 |
|---|---|---|---|---|
| frozen set | −8.66 | −5.24 | −5.25 | 9.86 |
| all +10 km/h | −1.12 | +2.86 | +3.23 | 10.81 |
| no wish below 110 | +0.04 | +2.71 | +2.92 | 9.33 |
| **pivot 150, factor 0.7** | **−0.32** | +1.66 | +2.10 | **8.88** |

The field's spread on the screening days is 6.43 km/h. Compression is the only reshaping that
corrects the level *and* moves the spread towards the field; a uniform shift and a truncation both
widen it. Between the two cells built to separate the pivot from the factor, the factor is what buys
the measured speed.

## What to read out of it first

**Capacity.** The three-day screening reached a breakdown in four to six runs per cell, which carries
no capacity claim in either direction. Sixteen days at 31 seeds is what settles whether the narrowed
speed distribution costs anything at the bottleneck. The prediction, stated before the run: smaller
speed differences mean less overtaking pressure, so discharge should hold or rise slightly — the
screening's discharge moved +1.2 %, consistent but not established.

**Then** the free-flow speed itself, the jam speed, and the ramp standstills.

**The merge tactics are available for the first time.** With the sampler wired to
`DriverStateObservable` on both sides, this campaign's trajectories carry the `ActionState` and desire
columns for TaMA. Every previous TaMA run labelled all merges `Direct Merge`, and the evaluation
reported the strategy split as unavailable.

## Submitting it

The build order is four steps, because `MirovaParameters.thresholdCurvature` is a shared interface:
install the fork, publish TaMA, build the fork, run. `tama-ots`'s `checkInstalledOtsInterface`
refuses a stale install by name.

```
--study=tamafinal --output=<dir> --dates=<cluster/dates.txt> \
--demand=<workspace>/demand --strict=true --replications=31
```

`--strict` defaults to `true` here rather than to `false`: a date whose demand file is missing must
end the submission rather than quietly shrink a campaign of this size. A `--cells` that names anything
but `final_v2` is refused for the same reason.

The sampler stays on its default of `L4a`. Recording the approach as well, as the exit probe did,
would take this from roughly 13 GB to 55 GB.

<!-- Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. BSD-style license. -->
