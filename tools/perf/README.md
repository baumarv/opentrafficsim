# Performance measurements

Tools for the three measurements left open by the ledger in
[`docs/mirova/performance_improvements.md`](../../docs/mirova/performance_improvements.md). Results go into that
ledger. Raw output stays outside the repository.

All of them measure a **commit**, never a working tree. Build the commit in its own worktree first:

```bash
git worktree add --detach ../ots-perf-<name> <ref>
cd ../ots-perf-<name> && mvn -o -q -pl ots-demo -am compile -DskipTests
```

Nothing else CPU-heavy may run on the machine while a timing measurement is going. JIT, GC and a second JVM on
the same memory bus all show up in the wall clock.

## 1. What each defect cost — `time_cells.py`

Answers questions 1–3: DJUnits hashing, parameter access, position cache. The protocol is P6's own:
`RunFreiburgMergeWatch`, seed 42, 2025-10-13 13:00, 60 min, one warm-up and five measured runs per cell, order
rotated, one run at a time, simulation loop only (first `[SIM ` line to `[OUTPUT] Writing sampler output`).

The cells cross the code state (`pre` = `826a70ae8~1`, `s1` = `826a70ae8`, `s2` = `aa830ed4d`,
`post` = `8df187cf3`) with `CACHING` and the DJUnits build. A cell with patched DJUnits gets the August build
`5.2.1-mirova-patched` from the local `~/.m2` swapped into its classpath in place of the stock jar. Neither the
pom nor the installation changes. All cells must produce byte-identical traffic, and the script refuses the result
otherwise. Model changes made later on main therefore cannot enter it.

```bash
for t in pre=826a70ae8~1 s1=826a70ae8 s2=aa830ed4d post=8df187cf3; do
  git worktree add --detach ../ots-perf-${t%%=*} ${t#*=}      # then build each
done
python tools/perf/time_cells.py --out ../ots-perf-results/<date>_attribution \
       --tree pre=../ots-perf-pre --tree s1=../ots-perf-s1 --tree s2=../ots-perf-s2 --tree post=../ots-perf-post
```

Writes `timings.csv` (every run) and `summary.md` (per cell, identity check, one contrast per question). About an
hour.

## 2. TaMA against MiRoVA, and where the difference sits — `planners_live.sh`

Answers question 4. It drives TaMA's own runner (`:tama-ots:runFreiburgNordLive`) against an OTS worktree, with
the window of the stage 11 comparison: 2025-09-23 15:00–16:30, 90 min including the breakdown and the jam,
coreset cell, replication 0. It runs three rounds of one TaMA and one MiRoVA run, alternating the order. Then it
takes one JFR recording per planner and analyses each with `diss_mvb/scripts/simulation/ots/analyze_jfr_profile.py`.
The owner mapping there knows TaMA's packages (`edu.kit.ifv.tama`, `edu.kit.ifv.units`, `kotlin.`); before that,
it booked them as JDK.

The two planners do not drive identical traffic. Each run therefore reports its vehicles and congested
lane-minutes at `det_L3a` beside its time.

```bash
git worktree add --detach ../ots-perf-main main      # build; then restore the generated sources the build
                                                     # rewrote with CRLF: git checkout -- ots-xml ots-opendrive
bash tools/perf/planners_live.sh ../tactical-maneuver-architecture ../ots-perf-main \
     ../ots-perf-results/<date>_planners 3
```

About 30 minutes. The run times of the two profile runs are with JFR on and are not a timing result.

## 3. Walltime on the cluster — `cluster/walltime_report.sh`

Reads `sacct` for a finished array job: task states, elapsed time (the slower of the two runs of a task), MaxRSS
of the batch step, core-hours charged. It also reads the per-run `[timing] run <i> wall_s=<n>` lines, which
`run_mirova.sbatch` appends to each run log for jobs submitted after that line was added.

```bash
# on the login node, in the workspace the job was submitted from
cluster/walltime_report.sh <array-job-id> > walltime_<job>.txt
```

It works for any job `sacct` still remembers, so the campaigns that have already run (`tamascreen`, `tamafinal`,
`tamarampcal`) can be read out without running anything new.
