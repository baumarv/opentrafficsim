# Performance Improvements — One Ledger

Every performance measure taken on the MiRoVA/OTS stack since August 2026, recorded in one format:
what it changed, how it was measured, what it saved, and how far the number can be trusted. The
detailed reasoning stays in the reports linked from each entry; this file does not replace them, it
makes them comparable.

Scope: the OTS repository (MiRoVA layer, OTS's own classes, the cluster workflow), plus the one
measured comparison against the TaMA planner that replaced MiRoVA's tactical layer.

## Summary

| # | Measure | Commit(s) | Date | Metric | Result | Confidence | Status |
|---|---|---|---|---|---|---|---|
| P1 | Two runs per cluster array task | `fcc878c2d` | 2026-08-19 | CPU efficiency of a SLURM task | **49.8 % → 95.4 %** (throughput per allocation ≈ ×1.9) | measured, `seff` | adopted |
| P2 | Trajectories on L4a only | `0d46c777b` | 2026-08-20 | — | **not measured** | — | adopted |
| P3 | Pre-built context cache keys | `44a7f4561` | 2026-08-20 | CPU share of the `ContextCategory` cache | 2.65 % → 2.51 % — **no measurable speed-up** | profile, inside noise | adopted |
| P4 | `LaneBasedGtu.CACHING = false` | `b0dcff791` | 2026-08-21 | CPU samples, full production day | **100 % → 84.3 %** (−15.7 %) | single run per cell | adopted |
| P5 | Patched DJUnits (cached scalar hashes) | tag `archive-perf-djunits-hash-cache-experiment` | 2026-08-21 | CPU samples, full production day | alone 90.3 %; on top of P4 84.8 % (≈ P4 alone) | single run per cell | **rejected** |
| P6 | Parameter & units rework (3 stages) | `826a70ae8` … `8df187cf3` | 2026-09-02 | wall clock of the simulation loop, 60 min | **62.01 s → 49.12 s median (−20.8 %)** | n = 5 per variant, interleaved, no overlap | adopted |
| P7 | TaMA planner instead of MiRoVA's tactical layer | TaMA `8a4a456`, OTS `d0a0a53aa` | 2026-09 | wall clock, 90 congested min | **170.3 s → 140.5 s median (−17.5 %)**; peak heap unchanged | n = 3 per model, interleaved, no overlap | adopted (TaMA) |
| P8 | TaMA-internal caches | TaMA `measure/cache-probe` `d79cb2e` | 2026-09 | wall clock with one cache switched off | at most `leaderCache` (3–10 %) and `adapterMemo` (1–12 %); the rest not measurable | 2 rounds, 7 % round-to-round noise | kept |
| — | P4 + P6 together, and the share of each defect | worktrees at `826a70ae8~1` … `8df187cf3` | 2026-09-28 | wall clock, 60 min, 9 cells | **70.47 s → 47.05 s (−33.2 %, ×1.50)**; see next section | n = 5 per cell, 54 runs byte-identical | measured |
| — | P7 re-measured on current main, with profiles | OTS `119b05887`, TaMA `5757034` | 2026-09-28 | wall clock, 90 congested min | **170.3 s → 138.8 s (−18.5 %, ×1.23)** | n = 3 per planner, no overlap | measured |

P4 and P6 are what "the performance work" refers to elsewhere in the docs. The figures above were
measured separately, with different metrics and against different baselines. Their combined effect
and the share of each defect were measured together on 2026-09-28 (next section). That section is
the one to quote.

## The four questions — measured 2026-09-28

Two experiments, both strictly one run at a time on an idle 16-core workstation, cell order rotated
every repetition. Raw output is in `ots-perf-results/2026-09-28_*` next to the repository. The tools
and how to rerun them are in [`tools/perf/README.md`](../../tools/perf/README.md).

**Attribution (questions 1–3).** `RunFreiburgMergeWatch`, seed 42, 2025-10-13 13:00, 60 simulated
minutes, the protocol of P6. The timer covers the simulation loop only. Each cell has one warm-up
and five measured runs. The nine cells cross four code states (`826a70ae8~1` before P6, then after
each of its stages: `826a70ae8`, `aa830ed4d`, `8df187cf3`) with `CACHING` on/off and stock/patched
DJUnits (the August jar, swapped into the classpath). **All 54 runs produced byte-identical detector
output**, so only the code under test differs. The model changes made later on main cannot enter the
comparison.

| cell | median (s) | min–max (s) |
|---|---|---|
| original: before P6, `CACHING` on, stock DJUnits | **70.47** | 70.29–79.23 |
| original + patched DJUnits | 56.42 | 53.45–58.24 |
| before P6, `CACHING` off | 58.91 | 55.46–61.08 |
| before P6, `CACHING` off, patched DJUnits | 52.17 | 48.30–55.32 |
| after stage 1 (`ParameterType` hash cached), `CACHING` off | 48.38 | 45.57–50.94 |
| after stage 2 (snapshot), `CACHING` off | 47.05 | 44.91–50.95 |
| **today: after stage 3, `CACHING` off** | **47.05** | 45.68–49.86 |
| today, `CACHING` switched back on | 63.86 | 62.23–71.65 |
| today + patched DJUnits | 47.03 | 45.12–49.16 |

**Original → today: 70.47 s → 47.05 s, 33.2 % less time, ×1.50**, ranges not overlapping. This
replaces the naive product 0.843 × 0.792 that this ledger quoted before. The two agree.

**The three defects are not independent.** The missing hash cache in DJUnits is the *cause*.
`RelativePosition.hashCode` (the position cache) and `ParameterType.hashCode` (parameter access) are
the two paths through which it cost time: both end in `Quantity.hashCode`. Whichever fix is credited
first takes the shared part. The matrix contains both orders:

| order | step | saved (s) | share of the 23.4 s |
|---|---|---|---|
| as it was done | position cache off (P4) | 11.6 | 49 % |
| | parameter access, stage 1 | 10.5 | 45 % |
| | parameter access, stage 2 (snapshot) | 1.3 | 6 % (inside noise) |
| | DJUnits allocations, stage 3 | 0.0 | 0 % |
| cause first | DJUnits hash cache (patch) | 14.1 | 60 % |
| | then position cache off | 4.3 | 18 % |
| | then parameter access (all of P6) | 5.1 | 22 % |

**1. What did the missing hash caching in DJUnits cost?** On the original code **about 20 % of the
run time** (70.5 s → 56.4 s with the patched jar, ×1.25). It was the largest single cause, and it is
the answer to "what was the problem". **Today it costs nothing:** the patched jar on top of today's
code changes the run time by 0.0 %. P4 and P6 stage 1 removed both paths through which the hash was
reached. This is why rejecting the patch (P5) cost nothing.

**2. What did the poor parameter access cost?** **About 20 %** of the run time with the position
cache already off (58.9 s → 47.1 s). Nearly all of it is stage 1, the `ParameterType` hash computed
once instead of on every lookup (17.9 %). The snapshot adds 2.8 %, inside the noise. Roughly **half
of this was DJUnits hashing** reached through the parameter's default value. With the patched jar,
the same rework still saves 9.9 % (52.2 s → 47.0 s). That remainder is the lookup itself: the map
probe and the call overhead at 171 read sites. The DJUnits allocation work of stage 3 saved nothing
measurable. The class-by-class call paths are in
[`performance_call_paths.md`](performance_call_paths.md).

**3. What did the vehicle position cache cost?** **16.4 %** of the run time on the original code
(70.5 s → 58.9 s). Even with cheap hashes (patched jar) the cache still costs 7.5 % (56.4 s →
52.2 s). So the cache is a net loss for MiRoVA independent of DJUnits: its bookkeeping costs more
than recomputing positions. Switched back on today, it would cost 26 % (47.1 s → 63.9 s). That is
more in absolute terms than on the original code (16.8 s against 11.6 s), an interaction the
matrix shows but does not explain.

**4. How much does TaMA contribute, and is it the lean Kotlin implementation?**
`:tama-ots:runFreiburgNordLive` on current main (`119b05887`) and TaMA `5757034`, 2025-09-23
15:00–16:30 (90 min with breakdown and jam), coreset cell, three runs each:
**TaMA 138.8 s, MiRoVA 170.3 s median — 18.5 % less wall clock (×1.23)**, ranges not overlapping.
This reproduces P7 (17.5 %). Traffic: 3 211 against 3 208 vehicles past `det_L3a`, 47 against 52
congested lane-minutes. MiRoVA's slightly heavier jam accounts for a small part of its time.

Where it comes from, from one JFR profile per planner, simulation samples of the main thread split
by whether a tactical-layer frame is on the stack:

| | MiRoVA | TaMA | difference |
|---|---|---|---|
| samples under the tactical layer | 3 227 | 1 786 | **−45 %** |
| … innermost frame in JDK (hash maps, iteration) | 1 607 | 836 | −771 |
| … innermost frame in generic OTS (perception, positions) | 990 | 342 | −648 |
| … innermost frame in DJUnits | 304 | 192 | −112 |
| … innermost frame in djutils | 164 | 118 | −46 |
| … the tactical layer's own code (TaMA: core, adapter, Kotlin stdlib, kotlin-units) | 162 | 297 | **+135** |
| samples outside the tactical layer (vehicle movement, OTS history) | 253 | 282 | ≈ same |

**The saving is not faster own code, it is asking OTS for less.** TaMA's own computation costs
*more* samples than MiRoVA's. What disappears is the work MiRoVA triggered inside OTS and the JDK
collections: perception iteration, route and speed look-ups, hash-map probes. In MiRoVA the largest
triggers are `RouteIncentive.computeDesire` (16 % of simulation samples) and
`MacroTrafficContext.computeAverageSpeed` (15 %). TaMA answers the same questions through a
memoised world view (`OtsWorldView`, keyed by `QueryKey`), whose largest entry is `meanSpeed`
(19 %). The Kotlin units library plays no role (0.2 %). DJUnits under TaMA is still 9 %, because
the OTS calls in the adapter use it. So the contribution is real (×1.23), and it belongs to the
interface design (a narrow, memoised query surface), not to Kotlin.

**Overall.** Original MiRoVA to today's MiRoVA is ×1.50 (60 min free flow). TaMA on top is ×1.23
(90 min with a jam). The two were measured on different windows, so their product (≈ ×1.85) is a
guide, not a measurement.

**Limits.** The attribution window is free flow (0 congested lane-minutes). The August profiles
showed DJUnits hashing to be more expensive under congestion, so the shares of questions 1–3 are
probably lower bounds for a congested run. The TaMA split rests on one profile per planner with
JFR on. It shows direction and mechanism, not a second decimal.

## Conventions used in the entries

- **Metric.** *CPU* means JFR sample counts of the simulation thread, compared as a ratio; *wall
  clock* means the simulation loop only (JVM start, network build and demand preparation excluded)
  unless the entry says otherwise. The two are not interchangeable.
- **Output identity.** Every adopted measure except P7 was checked to produce byte-identical
  detector and trajectory output. A measure that changes the traffic is not a performance result,
  because a heavier jam costs time on its own (see P8, `tickCache`).
- **Confidence.** A single run per arm has no error bar; anything below ~2 percentage points in it
  is noise. "No overlap" means the slowest run of the faster variant beat the fastest run of the
  slower one.

---

## P1 — Two simulation runs per cluster array task

- **What.** `run_mirova.sbatch` runs global indices `2T` and `2T+1` as two concurrent JVMs per array
  task, pinned to distinct CPUs where `taskset` exists. The Java run path is unchanged.
- **Why.** The `cpu` partition allocates two logical CPUs even for `--cpus-per-task=1`; one of them
  sat idle for the whole run.
- **Measurement.** `seff` on the cluster: canary job `6366286` at **49.82 %** CPU efficiency with one
  run per task, **95.4 %** with two.
- **Result.** Roughly twice the runs per allocated core-hour. It does not make a single run faster.
- **Behaviour.** None — packaging only.
- **Source.** [`cluster/README.md`](../../cluster/README.md), "Why two runs per task" and "Running
  several runs in one process"; commit `fcc878c2d`.

## P2 — Record trajectories on the merge section L4a only

- **What.** The road sampler registered every lane of L1a–L4a; it now registers L4a only (both
  mainline lanes and the acceleration lane). Detectors unchanged.
- **Measurement.** None. Checked only that the output contains L4a lanes exclusively.
- **Result.** Unknown. The expected gain is smaller trajectory files and less sampler work, not
  simulation speed.
- **Behaviour.** No effect on the traffic; the trajectory output is narrower by construction.
- **Source.** Commit `0d46c777b`.

## P3 — Pre-built context cache keys (profiling candidate 5)

- **What.** `ContextCategory` cache keys were concatenated strings, rebuilt and re-hashed on every
  access. They are now built once into static tables and looked up by ordinal.
- **Measurement.** JFR profile on FreiburgNord 2025-10-13 13:00–14:00, seed 42, before and after.
- **Result.** The whole cache went from 2.65 % to 2.51 % of CPU — 0.14 points against ~0.35 points
  of sampling noise. **Not a measurable speed-up.** The 2.6 % in the original report was the cost of
  the entire cache, not the available saving; key construction is only a small part of it.
- **Behaviour.** Byte-identical output.
- **Source.** [`performance_profile_2026-08-20.md`](performance_profile_2026-08-20.md), "Candidate 5";
  commit `44a7f4561`.

## P4 — Switch off OTS's GTU position cache (`LaneBasedGtu.CACHING = false`)

- **What.** OTS memoises `position()` in a `MultiKeyMap` keyed by a `RelativePosition` whose hash
  walks DJUnits scalars. For MiRoVA, populating the cache cost more than the recomputation it saved.
  Set in `ScenarioGenerator.buildSimulationScript` for MiRoVA scenarios only;
  `-Dmirova.gtuPositionCaching=true` restores it.
- **Measurement.** 2×2 matrix (DJUnits stock/patched × CACHING on/off), one full 9-hour production
  day per cell, JFR CPU samples.
- **Result.** **84.3 %** of the baseline CPU (−15.7 %). The position hash alone was 8.9 % of CPU with
  the cache on, 0.0 % with it off.
- **Behaviour.** Byte-identical, verified through the production path (`RunMirovaClusterStudy`,
  `dates` study, 2025-10-07), including the 188 MB trajectory CSV.
- **Source.** [`performance_investigation_synthesis.md`](performance_investigation_synthesis.md),
  [`performance_profile_2026-08-21_full_day.md`](performance_profile_2026-08-21_full_day.md);
  commit `b0dcff791`.
- **Replaces.** Candidate 1 of the first profile (a MiRoVA-side, tick-scoped position cache,
  predicted at ~25 % of CPU). Not implemented: MiRoVA has only four direct call sites, reachable
  saving 0.9 % of CPU, and positions are not tick-stable on time alone.

## P5 — Patched DJUnits with cached scalar hash codes (rejected)

- **What.** A DJUnits 5.2.1 build that caches `Quantity`/`Unit` hash codes.
- **Measurement.** Two experiments. (a) A 25-minute window, one run per arm: main-thread CPU
  samples **−45 %**, `ParameterSet` share of `DoubleScalar.hashCode` 25.2 % → 3.5 %. (b) The full-day
  2×2 matrix of P4: patched alone **90.3 %**, patched on top of P4 **84.8 %**.
- **Result.** The full-day figure governs. On top of P4 the patch adds nothing outside noise (84.8 %
  vs. 84.3 %): it makes cheap exactly the hash the position cache was paying for. The −45 % of (a) is
  a short window with no error bar and is superseded.
- **Why rejected.** A hand-installed jar in every environment's `.m2`, invisible to CI, silently
  falling back to stock where forgotten. `djunits.version` stays stock 5.2.1. Most of the durable
  part of its benefit (the `ParameterSet` path) was later obtained inside OTS by P6.
- **Source.** [`djunits_hashcode_finding.md`](djunits_hashcode_finding.md),
  [`djunits_patch_experiment.md`](djunits_patch_experiment.md); tag
  `archive-perf-djunits-hash-cache-experiment` (`575cc3ce6`).

## P6 — Parameter access and DJUnits allocation rework

- **What.** Three stages, measured together:
  1. `ots-base`: `ParameterType.hashCode()` cached in the constructor (it recomputed a four-part hash
     reaching the DJUnits `Quantity.hashCode` chain through the default value on every lookup), and
     `ParameterSet.getParameter` uses a plain `if` instead of `Throw.when` (`826a70ae8`). The latter
     is cosmetic: djutils 2.3.1 binds that call to a fixed-arity overload, so no varargs array was
     ever allocated (see [`performance_call_paths.md`](performance_call_paths.md) §8).
  2. MiRoVA reads constant parameters from the per-vehicle `MirovaParameterSnapshot`: 171 call sites
     became 74 (`2d251a9e4`, `0ea6de14c`, `0b964e1a6`, `aa830ed4d`).
  3. No per-tick DJUnits allocation for intermediate arithmetic; ~30 literals hoisted into named
     constants (`1d0069b1d`, `8df187cf3`).
- **Measurement.** 60-minute Freiburg-Nord run, `RunFreiburgMergeWatch`, seed 42, headless.
  `826a70ae8~1` against `8df187cf3`, five repetitions each, interleaved, each preceded by a discarded
  warm-up run. Timed from the first `[SIM ` line to `[OUTPUT] Writing sampler output`.
- **Result.** **Median 62.01 s → 49.12 s, 20.8 % faster.** Ranges 59.88–64.11 s and 47.77–49.87 s do
  not overlap; the spread halved (4.2 s → 2.1 s), consistent with less GC jitter.
- **Limits.** Wall clock, not a profile — what now tops the profile is unknown. Not attributed
  between the three stages. One seed, one scenario, one parameterisation.
- **Behaviour.** Byte-identical trajectories (104 188 lines), detector and demand output.
- **Source.** [`parameter_access_and_units.md`](parameter_access_and_units.md), "How much it saved".

## P7 — TaMA planner against MiRoVA's tactical layer

- **What.** Not an optimisation of OTS but the replacement of MiRoVA's tactical layer by the TaMA
  bundle, timed as a side result of the stage 11 comparison.
- **Measurement.** 2025-09-23 15:00–16:30 (90 simulated minutes, including breakdown and jam),
  `coreset` cell, seed 42, no trajectories. Strictly one run at a time on an idle 32-core
  workstation, T/M interleaved, three each.
- **Result.** **TaMA 140.5 s, MiRoVA 170.3 s median — 17.5 % less wall clock**, no overlap (pairwise
  10.7 %–21.8 %). **Peak heap: no difference** (the predicted third less did not materialise).
- **Behaviour.** Different models, so not byte-identical; they move the same traffic to within 0.1 %
  past det_L3a, and MiRoVA's jam is slightly heavier, which would cost it some of that time.
- **Correction to the traffic figures.** Stage 11 reports 6 422 vehicles and 131 against 145
  congested lane-minutes. Those sums include the all-types row *and* the per-type rows (car,
  truck) of `detector_periodic.csv`, so every vehicle is counted twice and a congested interval up
  to three times. Counted over the all-types rows only, the same runs carry 3 211 against 3 208
  vehicles and 47 against 52 lane-minutes. The time comparison is unaffected: both sides were
  counted the same way.
- **Re-measured 2026-09-28** on current main with one profile per planner: 18.5 %. See "The four
  questions" above.
- **Source.** `tactical-maneuver-architecture/docs/checkpoints/stage11.md`, "Performance and cache
  switch-off".

## P8 — TaMA's internal caches (switch-off measurement)

- **What.** Each core cache switched off singly, against the probe build with nothing off.
- **Measurement.** Same 90-minute run as P7, two interleaved rounds; nothing-off gave 133.7 s and
  143.4 s (7 % between rounds, larger than most effects).
- **Result.** Measurable only for `leaderCache` (+3–10 % when off) and `adapterMemo` (+1–12 %).
  `followerCache`, `routeDistances`, `limits` and the low-hit-rate caches: not measurable.
  `tickCache` shadow (pays the evaluation, returns the cached answer): not measurable — the cache
  saves no visible time. `tickCache` off changes behaviour (BC-11 disappears, 16 % more congested
  lane-minutes), so its +10–14 % is a traffic effect, not a cache cost.
- **Source.** `tactical-maneuver-architecture/docs/checkpoints/stage11.md`,
  `tactical-maneuver-architecture/docs/cache-audit-core.md` §5.

---

## Per-run durations quoted around the repository

These figures are often read as "the speed-up", and they are not comparable with each other:

| Figure | Where | What it is |
|---|---|---|
| 90–120 min per run | handover document, older cluster sizing | cluster, before P4 and P6; origin not re-measured |
| 18 min 29 s | canary job `6366286` | cluster, one run, 2026-08-19 — before P4 and P6, study and window not recorded here |
| "well under an hour" | `cluster/README.md` since `edefa9805` | qualitative, after the performance work |
| ~7 min per nine-hour day | local rehearsal, `cluster/README.md` "What could not be reconciled", item 4 | workstation; model and commit not stated there |
| 398–460 s per run | `tactical-maneuver-architecture/docs/campaigns/tama-parameter-study.md` | workstation, TaMA, one day |

The gap between the handover's 90–120 min and the workstation's 7 min is far larger than P4 and P6
can explain, so the two describe different things. **No cluster run has been timed after the
performance work** in a way that is written down; until one is, the cluster sizing in
`cluster/README.md` stays an upper bound.

## Not done, and where the next saving would be

- **Time a cluster run.** `cluster/walltime_report.sh <job-id>` reads walltime, MaxRSS and
  core-hours out of `sacct` for any campaign it still remembers (`tamafinal`, `tamarampcal`).
  `run_mirova.sbatch` now also appends a `[timing]` line to each run log, for the duration per run.
  This needs someone on the cluster.
- **The attribution under congestion.** The 2026-09-28 matrix is free flow. Repeating it on a
  window with a breakdown would give the upper end of the shares.
- **Where the next saving would be.** In both planners, what dominates is the tactical layer's
  queries into OTS, not its own code. Under MiRoVA that is `RouteIncentive` and
  `MacroTrafficContext.computeAverageSpeed`. Under TaMA it is `OtsWorldView.meanSpeed`. The
  remaining DJUnits cost (`ValueUtil.expressAsUnit`, `Duration.instantiateSI`) is unit conversion,
  not hashing, and no measure so far has touched it.
