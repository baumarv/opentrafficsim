# The validation run

The out-of-sample run behind the paper. 350 runs, TaMA only, on the seven days no study has touched.

## What runs

| | |
|---|---|
| Study | `--study=tamascreen2 --cells=standard_base` — no new class; the cell is the frozen set of `final-parameter-set.md` |
| Parameters | `standard_base`: headway 1.00/1.30, damping off, everything else as documented |
| Days | the seven of `cluster/dates_extension.txt` |
| Seeds | 50 per day |
| Runs | **350** — asked of the built class, not computed |
| Tasks | **175** at two runs each → `--array=0-174` |
| Core time | ≈ 41 h (350 × 7 min) |
| Disk | ≈ **5.8 GB** (350 × 16.5 MB) |

Verified from the manifest, not assumed: 350 runs, all `tacticalPlanner=tama`, one cell, exactly those seven
dates. There is no MiRoVA reference arm — the two models were indistinguishable on every metric of the first
screen, and the second screen's difference stayed inside the resolution of ten seeds.

## These seven days are spent by this run

They were reserved in the campaign design with the rule that no study before the final validation may use
them. This is that validation. After it they are no longer out-of-sample: **the parameter set may not be
adjusted on these days afterwards**, or the claim the paper rests on is gone. The set is frozen at commit
`5c6da6d13`.

## Before submitting

1. **`tama-screening-study` must be merged.** The study class `TamaHeadwayScreeningStudy` lives on that branch;
   without it `--study=tamascreen2` is an unknown study and the run stops at once.
2. **Decide whether the trajectories should carry the decision columns.** As things stand they will not:
   `ActionState`, both lane-change desires, the damping factor and the car-following acceleration are empty in
   every TaMA run, because the sampler columns read the MiRoVA planner class. The fix is merged-and-ready on
   `sampler-driver-state` (fork) and `driver-state-observable` (TaMA) but is not in `main`. No metric of this
   run needs those columns — but a merge-tactic analysis for the paper does, and getting them afterwards costs
   these 41 hours again.
3. **Quota**: 5.8 GB, against 8.2 GB for the first screen and about 12 GB for the second.
4. The build order is four steps whenever a shared interface changed; see `cluster/README.md`.

## What the run must show

1. `COMPLETED`, exit 0, no `errors.txt` anywhere under the output root.
2. `--count` printed **350** before submission. Anything else stops it: the study or the cell selection changed.
3. In every run folder: `run.properties` with `tacticalPlanner=tama` and both `planner.car` and `planner.truck`
   carrying `fingerprint=a93e52a7…`.
4. L4a trajectories at roughly 16 MB per run.
5. Walltime per task in `sacct` around 8–10 minutes.

## Submission

```bash
export MIROVA_WORKSPACE=mirova-ots
export WS=$(ws_find mirova-ots)
source $WS/opentrafficsim/cluster/mirova_env.sh
activate_toolchain "$(resolve_workspace)"

cd $WS/opentrafficsim && git checkout main && git pull --ff-only && git rev-parse --short HEAD

# the count, asked of the built classes - must print 350
java -cp "$(cat $WS/cp.txt)" \
  org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.RunMirovaClusterStudy \
  --study=tamascreen2 --cells=standard_base --output=$WS/output/validation \
  --dates=$WS/opentrafficsim/cluster/dates_extension.txt --demand=$WS/demand \
  --replications=50 --count

export MIROVA_CLUSTER_DIR="$WS/opentrafficsim/cluster"
export MIROVA_STUDY=tamascreen2
export MIROVA_STUDY_OPTS="--cells=standard_base --dates=$WS/opentrafficsim/cluster/dates_extension.txt --demand=$WS/demand --replications=50 --strict=true"
export MIROVA_OUTPUT_ROOT="$WS/output/validation"
export MIROVA_TACTICAL_PLANNER=tama
export MIROVA_TAMA_COMPOSITION=mirova-reference/1
sbatch --chdir="$WS" --array=0-174 --time=01:00:00 cluster/run_mirova.sbatch
```

`MIROVA_TACTICAL_PLANNER=tama` is safe here and only here: with one cell the study sets a single planner, so
the global flag agrees with it rather than flattening a distinction. Selecting both cells again would make the
runner refuse it.
