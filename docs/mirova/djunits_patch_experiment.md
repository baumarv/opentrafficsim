# DJUnits hash-caching patch — built, verified, measured

Follow-up to `djunits_hashcode_finding.md`. The proposed patch was built and measured. **The working
tree is back to stock `djunits 5.2.1`**; see "State afterwards" at the end.

## Result in one line

Byte-identical simulation output, and **main-thread CPU time dropped by ~45 %** on an otherwise idle
machine. `Quantity.hashCode` disappears from the profile entirely.

## 1. What was built

| | |
|---|---|
| Source | `github.com/averbraeck/djunits`, tag `v5.2.1`, commit `41744a39f0a5b70560437538ac38e6e9f82bfacf` |
| Cross-check | cloned `SIDimensions.java` and `Quantity.java` are **byte-identical** to `djunits-5.2.1-sources.jar` |
| Patch applied | 4a (`SIDimensions`, unconditional lazy cache) + 4b (`Quantity`, lazy cache with invalidation) |
| `Unit.java` | **untouched**, per option A |
| Artifact | `org.djunits:djunits:5.2.1-mirova-patched`, installed into the local `.m2` with its sources jar |
| Verified in bytecode | `javap` finds 6 `cachedHashCode` references in `Quantity.class` |

The diffs applied cleanly with no adjustment — the sketched patch matched the real source, line
numbers included. Total change: **27 added lines across 2 files**, no deletions.

```
 src/main/java/org/djunits/quantity/Quantity.java    | 15 +++++++++++++++
 src/main/java/org/djunits/unit/si/SIDimensions.java | 12 ++++++++++++
```

## 2. Correctness — byte-identical, verified before any performance claim

`FreiburgNord`, demand `2025-10-13` 13:00–14:00, seed 42, 25 simulated minutes, headless. Once
against stock `5.2.1`, once against `5.2.1-mirova-patched`, everything else held constant.

```
  diffused_vehicles.csv        IDENTICAL  sha=6b1713836e5f57e3
  simulation_demand.csv        IDENTICAL  sha=04d6160b1df21060
  simulation_demand_wide.csv   IDENTICAL  sha=63a4228936df4285
  detector_periodic.csv        IDENTICAL  sha256 stock=c9abd26319583243 patched=c9abd26319583243 (18006 bytes)
  detector_periodic.csvm       IDENTICAL  sha256 stock=c5a27b7d12c658ab patched=c5a27b7d12c658ab (410 bytes)
  detector_positions.csv       IDENTICAL  sha256 stock=62c285a260b5a02c patched=62c285a260b5a02c (245 bytes)
  detector_positions.csvm      IDENTICAL  sha256 stock=a899950afef3cacd patched=a899950afef3cacd (322 bytes)

  ==> BYTE-IDENTICAL
```

`detector_periodic.csv` carries the same SHA-256 (`c9abd263…`) as every earlier verification run in
this series, so the scenario is deterministic and the patch changed nothing about it — which is what
a pure memoisation must do.

## 3. Measured effect

Same scenario and seed, JFR `settings=profile`, `stackdepth=128`, main thread. Both runs on a machine
that had become **essentially idle** (~18 % load; the foreign job that contaminated every earlier
measurement in this series had finished).

| Metric | stock 5.2.1 | 5.2.1-mirova-patched | change |
|---|---|---|---|
| CPU samples (≈ main-thread CPU time) | 1041 | 571 | **−45 %** |
| `DoubleScalar.hashCode` on stack | 28.53 % | 3.85 % | **−24.7 pt** |
| … via `ParameterType` (`ParameterSet.getParameter`) | 25.17 % | 3.50 % | −21.7 pt |
| … via `RelativePosition` (position cache) | 2.69 % | 0.00 % | −2.7 pt |
| `Quantity.hashCode` on stack | 26.32 % | **0.00 %** | gone |
| `SIDimensions.hashCode` on stack | 1.73 % | **0.00 %** | gone |
| `LinkedKeyIterator` share of allocation | 18.60 % | 9.03 % | −9.6 pt |
| sampled allocation | 41 288 MB | 37 060 MB | −10 % |

### Did the predicted percentages materialise?

**The `ParameterSet` path: yes, and larger than predicted.** The report projected ~15 % of CPU; the
measurement shows **25.17 % → 3.50 %**, a 21.7-point reduction. This is the durable benefit, the one
unaffected by whatever happens with `LaneBasedGtu.CACHING`.

**The position path: not confirmed, because it was barely present in this run.** Only 2.69 % of stock
CPU came through `RelativePosition`, against 61.7 % of `DoubleScalar.hashCode` in the original
contended profile. The split between the two sub-chains varies substantially with run length and
machine conditions — the earlier long, contended run spent far more of its time in perception
traversal. **The ~25 % attributed to the position path in the original report should therefore be
treated as run-specific rather than as a stable figure.** The `ParameterType` share is the one that
reproduced, and it reproduced larger.

**Allocation: the mechanism was right, the predicted share was not.** The finding report claimed this
patch would eliminate most of the `LinkedKeyIterator` churn. It removes a bit over half:

```
stock   (7680 MB):  59.1% Quantity.hashCode   28.6% Unit.hashCode   4.1% LaneStructure.nextLateral  …
patched (3347 MB):   ---                      67.9% Unit.hashCode  10.1% LaneStructure.nextLateral  …
```

The `Quantity`-driven share is gone completely. What remains is **`Unit.hashCode` iterating its
`abbreviations` set** — which this patch deliberately does not touch, because `Unit` was left uncached
under option A. So roughly 56 % of that churn disappears, not "most".

That gives option B (caching `Unit`, with `Quantity` invalidating its registered units) a measured
justification it did not have before: it would address the remaining 67.9 %. Whether the extra
invalidation complexity is worth ~9 % of allocation is a judgement call — but it is now an informed
one rather than a guess.

### Caveats on the numbers

- **Single run per arm.** The −45 % CPU figure compares sample counts across two runs and has no error
  bar. The composition changes are a different matter: `Quantity.hashCode` going to exactly 0.00 % is
  structural, not statistical.
- **Low sample counts** (1041 / 571). On an idle machine the 25-minute simulation finishes in one to
  two minutes of wall time, so there is simply less to sample. Adequate for the large effects reported
  here; anything below ~2 % in these two profiles is noise.
- Not measured on the cluster — see section 5.

## 4. Getting the patched artifact onto the cluster

The cluster provisions its own Java/Maven into the workspace (`cluster/mirova_env.sh`) and its `.m2`
is not reachable from a workstation, so this has to be done there. Nothing below is automated; it is
meant to be pasted.

**Step 1 — copy the two jars and the pom across.** From this workstation:

```bash
scp ~/.m2/repository/org/djunits/djunits/5.2.1-mirova-patched/djunits-5.2.1-mirova-patched.jar \
    ~/.m2/repository/org/djunits/djunits/5.2.1-mirova-patched/djunits-5.2.1-mirova-patched-sources.jar \
    ~/.m2/repository/org/djunits/djunits/5.2.1-mirova-patched/djunits-5.2.1-mirova-patched.pom \
    <user>@<login-node>:/tmp/
```

The sources jar is optional but worth taking: it makes the patched code readable from an IDE attached
to the cluster build, which matters if anything needs debugging there.

**Step 2 — install into the cluster's local `.m2`.** On the login node, with the workspace toolchain
active:

```bash
export MIROVA_WORKSPACE=<workspace name>
source <repo>/cluster/mirova_env.sh
activate_toolchain "$(ws_find $MIROVA_WORKSPACE)"

mvn install:install-file \
  -Dfile=/tmp/djunits-5.2.1-mirova-patched.jar \
  -DpomFile=/tmp/djunits-5.2.1-mirova-patched.pom \
  -Dsources=/tmp/djunits-5.2.1-mirova-patched-sources.jar
```

`-DpomFile` rather than `-DgroupId/-DartifactId/-Dversion`: the real pom carries djunits' own
dependency on `djutils-base 2.3.1`, and a synthesised pom would drop it and break the build in a
confusing way.

**Step 3 — point the build at it, for the test build only.**

```bash
cd <repo>
sed -i 's|<djunits.version>5.2.1</djunits.version>|<djunits.version>5.2.1-mirova-patched</djunits.version>|' pom.xml
./cluster/build_for_cluster.sh
grep -o 'djunits[^:]*jar' "$(ws_find $MIROVA_WORKSPACE)/cp.txt" | head -1   # must show the patched jar
```

**Step 4 — revert before anything real is submitted.**

```bash
git checkout -- pom.xml
./cluster/build_for_cluster.sh    # rebuild against stock, regenerating cp.txt
```

That last step matters more than it looks. `cp.txt` is written by the build and is what
`run_mirova.sbatch` launches against, so reverting `pom.xml` *without* rebuilding leaves a classpath
still pointing at the patched jar — and the next submission would silently use it.

## 5. What was not done, and why

**The comparison was not run on an idle or exclusive cluster node.** There was no prior cluster
profiling task in this series and no node reservation to reuse — all earlier profiling in these
reports was local — and the cluster is not reachable from this workstation. The measurement in
section 3 is local, on a machine that happened to become idle. That is a genuine improvement over the
contended runs behind the earlier reports, but it is not the cluster.

That bundle — stock vs patched djunits crossed with `LaneBasedGtu.CACHING` on and off — is now
prepared as a single script; see section 6. The two interact, which is why they belong in one
matrix: this patch removes most of the hashing cost that made the position cache expensive in the
first place, so with the patch applied `CACHING=false` may no longer be a win at all. Measuring
them separately would invite the wrong conclusion about either.

## State afterwards

- Branch `perf/djunits-hash-cache-experiment`, created from `laneChangeIncentive_Reengineering` at
  `18fc06115`. **The production branch was not touched.**
- `pom.xml` is byte-identical to `HEAD`; `djunits.version` is back to `5.2.1`.
- `RunFreiburgMergeWatch.java` restored to its committed state.
- `5.2.1-mirova-patched` remains installed in the **local** `.m2` only. It is inert — nothing resolves
  it unless `djunits.version` is changed — and it is *not* on the cluster.
- The next build on any branch resolves stock `5.2.1`.

## 6. The 2x2 matrix, prepared for one cluster session

`cluster/profile_matrix.sh` runs all four combinations unattended, submitted through
`cluster/profile_matrix.sbatch`:

```
                  CACHING=true          CACHING=false
stock djunits     (A) baseline          (B) position cache alone
patched djunits   (C) patch alone       (D) both combined
```

**Production-length cells.** Each cell runs the real `dates` study window, 13:00-22:00, taken from
`FreiburgStudyParameters.forDate` rather than configured in the runner. That covers free flow, the
build-up into congestion and its dissipation, instead of a one-hour snapshot of whichever regime
happened to fall inside it — and it is what the 32-date campaign will actually spend its time on.
The simulated duration follows the demand window automatically (32 400 s, confirmed), so the runner
cannot drift out of step with the study by hardcoding a duration of its own.

The parameters are composed exactly as `ScenarioManager.prepareRun` composes a real run:
`getDefaultParameters().copy().applyOverridesFrom(forDate(...))`. `forDate()` alone is not enough —
it carries the behaviour baseline and the demand wiring but not the scenario defaults such as
`mergeShare`, and `FreiburgNord.buildGtuTemplates` fails outright on a null one. Demand is loaded in
strict mode: a cell quietly falling back to synthetic demand would make the cells incomparable while
still producing four plausible recordings.

**Two builds, two run pairs.** djunits is a build-time choice and `CACHING` a runtime one, so the
matrix is one build per djunits version crossed with two runs each. The two `CACHING` variants of a
build run concurrently, one per allocated core, following `run_mirova.sbatch`'s bundling pattern —
background processes, `wait` on both before judging either, one log per run, affinity read from the
task's own mask rather than assumed to be CPUs 0 and 1. Wall clock is therefore two runs plus two
builds, not four runs. The "which jar actually got resolved" check runs once per classpath, and the
script refuses to profile on a mismatch: four plausible recordings that silently measure two
identical configurations would be worse than a failed build.

**Non-exclusive allocation, deliberately.** `--exclusive` was dropped to avoid the queue wait, so
the job takes a standard two-core share of a node and accepts neighbour noise — which on this
cluster has been seen to slow a run five- to sixfold. The consequence is specific rather than fatal:
relative attribution *within* a cell still holds, because JFR samples in proportion to the CPU the
thread actually received, but wall-clock and CPU-sample counts become incomparable *between* cells
if the neighbours change while the matrix runs. Lean on the composition percentages, treat the
sample-count ratios as indicative, and **state in any write-up that the run was non-exclusive**.

`--time` is 6 hours: 90-120 minutes per run from `run_mirova.sbatch`'s calibration, two runs'
worth of wall clock because the pairs are concurrent, two Maven builds, and roughly 50 % headroom.
A matrix that dies at the wall clock loses all four cells, since the comparison is only meaningful
complete.

**`LaneBasedGtu.CACHING` had no switch, so one had to be made.** It is a plain
`public static boolean` with no property binding. `RunProfileMatrix` sets it before the simulation
is built — hence before any GTU exists — which keeps the switch on a profiling entry point instead
of patching OTS to add a property. That class also calls `System.exit(0)`: the simulation finishes
but the JVM does not terminate by itself, and accumulating JVMs would compete for the CPU being
measured. Every earlier run in this series left one alive.

**The restore is an `EXIT` trap, not a final step.** It fires on failure and on interrupt as well as
on the clean path, and it refuses to finish quietly if `cp.txt` still resolves the patched jar
afterwards.

### Correctness

Checked by the script itself: all four cells must produce output identical to cell A, compared by
SHA-256 over the zip contents rather than the zip bytes, since the archives carry a timestamp.

Note the scope of what has been verified so far, because it is not the same window. The
stock-vs-patched comparison in section 2 and the `CACHING=false` check were both done on the short
one-hour window. **The 9-hour window has not been correctness-verified yet** — that happens in the
matrix run itself, which is the first thing to read in its output. Pure memoisation should not
behave differently over a longer run, but that is a "should", and the matrix is where it gets
confirmed for the window it will actually report on.

### Prerequisites, all checked before anything is built

- the patched artifact installed in the node's `.m2` (section 4)
- `cluster/build_for_cluster.sh` run once, so the toolchain is provisioned and the local `.m2` warm
- the per-date demand CSVs in `<workspace>/demand`; `demand_2025-10-13.csv` covers the full day
  (00:00-23:55, 288 intervals — verified), so the 13:00-22:00 window fits inside it
