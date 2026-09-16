# Fork merge plan

Status: **report, awaiting decision.** Nothing was merged, tagged, rebased, deleted or force-pushed. Written
2026-09-16 on `agent/fork-tidy-plan`. Measurements were run in throwaway worktrees under
`tama-workspace/scratch/`; the parameter resolver and its outputs are archived in
`tama-workspace/provenance/`.

Sections: A the answer in one page · B inventory · C what `decoupling_phase05` mixes · D campaign provenance
and tags · E evidence per group · F resolved-parameter comparison · G proposal · H items to keep ·
I IDE files in version control · J branches after the merge · K command sequence. Task 2 (ots-demo tidy) is
appended at the end.

---

## A. The answer in one page

1. **`main` (`4859cdaff`, 2026-06-03) is a strict ancestor of `decoupling_phase05` (`2ccc1b362`).** 304
   commits ahead, 0 behind. Technically a fast-forward; nothing on `main` needs reconciling.
2. **The published model is not where the tag says.** `published-model` (`9eb7ab856`, local only, never
   pushed) sits after eight unlabelled model changes that `final_v1` did not have. `final_v1` — the reference
   standard — ran at **`fbce85dbe`**: one cell re-run there reproduces the archived output exactly (§D.1).
3. **"Behaviour changes to the default" is not only vGain.** Between `final_v1` and the Phase 0.5 work there
   are **eight unlabelled model changes** (`e958ed365` … `42bb55c66`, §D.4), and they are ancestors of every
   later commit. They cannot be kept out of `main` without rewriting the history everything since is built on.
4. **Proposal (§G):** fast-forward `main` to the tip; make each campaign reachable *and identified* by an
   annotated tag on its commit (`campaign-production-v1`, `campaign-final-v1`, `campaign-final-v3`); retire
   `published-model`; keep vGain as the separately labelled commit it already is and document what the default
   of `main` then is. Then merge the four short agent branches with `--no-ff`.
5. **Decisions needed from you**, most important first: the tag commits and annotations (§D.5); whether `main`
   may carry the eight fixes and vGain as its default (§G, options); the IDE-file removal (§I).

---

## B. Inventory

### B.1 Branches

Counts are `git rev-list --left-right --count X...branch` (behind/ahead), measured 2026-09-16 after
`git fetch`. "fbce" / "prod" = whether the `final_v1` commit `fbce85dbe` / the production study commit
`5b996a05a` is an ancestor. Authors are over `main..branch`; "Baumann" (23 commits) and "Marvin Baumann"
are two identities of the same person.

| branch | tip | date | vs main | vs d05 | fbce / prod | what it is | referenced by |
|---|---|---|---|---|---|---|---|
| `main` / `origin/main` | `4859cdaff` | 06-03 | 0/0 | 304/0 | n / n | PR #3 merge (merging_relaxation) | — |
| `decoupling_phase05` / origin | `2ccc1b362` | 09-15 | 0/304 | 0/0 | y / y | the whole line: model work, final ensembles, Phase 0.5, TaMA hooks | phase05-report, TaMA docs |
| `laneChangeIncentive_Reengineering` | `edefa9805` | 09-10 | 0/231 | 73/0 | y / y | the model line up to Phase 0.5; **local 1 ahead of origin** | cluster campaigns (§D) |
| `origin/laneChangeIncentive_Reengineering` | `efaccb06b` | 09-10 | 0/230 | 74/0 | y / y | as above, one commit shorter | **final_v3 ran here (inferred, §D.3)** |
| `tama-fixtures` | `b287fef1d` | 09-10 | 0/279 | 25/0 | y / y | vGain, legacy variant, parameter table; **local 8 ahead of origin**, all 8 in d05 | the tag `published-model` sits on it |
| `origin/tama-fixtures` | `3c36e0c02` | 09-10 | 0/271 | 33/0 | y / y | stage-2 fixture harness | TaMA stage 2 |
| `agent/phase1-hygiene` | `6a103e509` | 09-11 | 0/284 | 20/0 | y / y | contained in d05 | proof commit in phase05 docs |
| `agent/tama-contract-s8` | `7803b3f27` | 09-11 | 0/285 | 19/0 | y / y | contained in d05 | TaMA contract docs |
| `agent/observer-proposals` / origin | `aa393158f` | 09-11 | 0/291 | 13/0 | y / y | contained in d05 | `vgain-grid-1` run doc |
| `agent/bc6-range` / origin | `2ccc1b362` | 09-15 | 0/304 | 0/0 | y / y | identical to d05 | — |
| `agent/tama-planner-selection` / origin | `c56066531` | 09-16 | 0/306 | 0/2 | y / y | **new**: `tacticalPlanner` parameter + ServiceLoader, Maven profile `tama` | TaMA stage 11, snapshot `c56066531` |
| `agent/run-provenance` / origin | `a3f5713e2` | 09-16 | 0/306 | 0/2 | y / y | **new**: build stamp, `build.txt` per run (§D.6) | — |
| `agent/legacy-final-v1` / origin | `a12f18d2a` | 09-16 | 0/305 | 0/1 | y / y | **new**: legacy = final_v1 parameters, `production-v1` (§F) | this plan |
| `agent/fork-tidy-plan` (local) | `3871c7921` | 09-16 | 0/305 | 0/1 | y / y | **new**: this plan, phase05-report correction | — |
| `backup_before_reset_to_0708` (**local only**) | `01b6a88a1` | 08-17 | 0/82 | 241/**19** | n / n | 19 commits not in d05 (08-10…08-17): merge guards in `MandatoryLaneChangePattern`, follower-deceleration restriction on the ramp, docs | nothing found |
| `perf/djunits-hash-cache-experiment` | `575cc3ce6` | 08-21 | 0/117 | 197/**10** | n / n | DJUnits hash-cache patch, 2×2 profiling matrix; **4 local-only doc commits** (`12ba45a8f`…`575cc3ce6`) | `docs/mirova/djunits_*` (on d05) |
| `origin/perf/djunits-hash-cache-experiment` | `09c009c40` | 08-21 | 0/113 | 197/6 | n / n | as above without the 4 | — |
| `profiling/backend-hotspots` / origin | `c7393c94a` | 08-28 | 0/136 | 169/**1** | n / n | 1 commit not in d05: pin a profiling run | performance docs |
| `origin/pse_maneuver_editor` | `55b264c4a` | 05-04 | 26/0 | 330/0 | n / n | fully contained in `main` | — |

**Commits reachable from no branch that will survive a merge:** 19 (`backup_before_reset_to_0708`), 10
(`perf/…`, 4 of them local only), 1 (`profiling/…`). None of them is an ancestor of any campaign commit.

### B.2 Tags

| tag | points at | pushed | claim | true? |
|---|---|---|---|---|
| `published-model` | `9eb7ab856` (annotated) | **no** | "The parameterisation behind the published results." | **No** — see §D.2 |
| `vgain-grid-1` | `abe0b4095` (annotated) | yes | cluster test of the speed gain | consistent with its run doc |
| `v1.6.0` … `v1.7.6` | upstream releases | — | — | — |

### B.3 Worktrees

Registered: the main worktree (`decoupling_phase05`); `.claude/worktrees/{agent-phase1, bc6-range,
observer-proposals, tama-contract}` on their agent branches; `.claude/worktrees/tama-ots-{2544c76,3502ca5d}`
(detached; the OTS commits TaMA recordings replay-20…22 were taken from); `ots-tama-selection`,
`ots-run-provenance`, `ots-legacy`, `ots-fork-plan` (this work); `ots_A`…`ots_D` (detached, older
performance A/B builds); `D:/otsA`, `otsbase`, `otspatch`, `otsref`, `otsrun` — **prunable** (directories
gone). Scratch worktrees of this analysis: `tama-workspace/scratch/fp-{main, published-model, d05, edefa, fbce,
efac}`.

---

## C. What `decoupling_phase05` mixes

`main..2ccc1b362`, 304 commits, by range:

| range | commits | labelled | kind |
|---|---|---|---|
| `main..5b996a05a` | 157 | 0 | model development before any campaign on record |
| `5b996a05a..fbce85dbe` | 44 | 0 | production study → final study (`production_v1` ran at the start) |
| `fbce85dbe..efaccb06b` | 29 | 0 | **8 model changes** (§D.4), refactors, logging, cluster, docs |
| `efaccb06b..edefa9805` | 1 | 0 | docs |
| `edefa9805..9eb7ab856` | 44 | 44 | Phase 0.5: instrumentation, defect fixes, dead code, switches BC-1…BC-9, legacy variant |
| `9eb7ab856..abe9125bd` | 1 | 1 | **vGain 15/30 m/s → 15/30 km/h** `[behaviour change]` |
| `abe9125bd..2ccc1b362` | 28 | 27 | parameter table, vGain studies, TaMA hooks, BC-10…BC-13 (switched off), docs |

Kind (1), no behaviour change, and (2), switches default off, are established only from `edefa9805` on (§E).
Kind (3), default changes, is `abe9125bd` **and** the eight commits of §D.4. Before `fbce85dbe` the
classification does not apply: that is the model's own development, and `final_v1` is its result.

---

## D. Campaign provenance

No campaign recorded the commit it ran on: the output holds detector files, trajectories and a
`runParams.txt` that contains the parameter variation only — no commit, no build time, no resolved parameters.
What follows is reconstructed; each bound says where it comes from.

### D.1 `final_v1` — reference standard — **`fbce85dbe`**

Output: `mirova/output/ots/final_v1/`, 16 dates × 50 seeds = 800 runs.

| bound | evidence | status |
|---|---|---|
| not before `fbce85dbe` (09-04 08:25:31) | the study `final` is added in that commit (`git log --diff-filter=A -- '*FreiburgFinalStudy.java'`); the run folders are named `_final` | established |
| nine dates (09-22 … 10-29) not after `fbce85dbe` | run directories 09-04 08:31–08:48, samplers 08:55–09:05; `origin` held `fbce85dbe` from 08:27:06 until `cd8fead83`/`ec9fcb761` at 09-07 11:25 (remote reflog); no commit exists on any branch between 09-03 19:38 and 09-07 11:25 | established that no other *committed* state was available; **inferred** that the cluster checkout was clean and was pulled from `origin` (cluster README: `git clone`) |
| seven dates (09-16 … 10-16) at `ec9fcb761` or `6e21c6139` | run directories 09-07 11:49–11:50, samplers 12:00–12:06; `6e21c6139` pushed 11:41:30; the seven dates and their demand CSVs are added by `ec9fcb761` | **inferred**; immaterial to the model: `git diff fbce85dbe 6e21c6139` touches no `.java`/`.xml`/`pom.xml`, only `cluster/` and `.gitignore` |
| the model is `fbce85dbe`'s | cell 2025-10-27, seed 42, re-run at `fbce85dbe` on this machine: unzipped trajectories **106 590 153 bytes, md5 `a99d7755…`, identical**; detector output md5 `5265e3c6…`, identical; positions identical | **established for this cell** |
| — differences in that rerun | header line 16 `m/s²`: UTF-8 on the cluster, cp1252 here; `diffused_vehicles.csv` (header only) LF vs CRLF; `runParams.txt`: demand path and `demandCsvStrict` (command-line option) | platform / invocation artefacts, not model |
| parameters | `final|legacy` on `agent/legacy-final-v1` vs `final|final` resolved at `fbce85dbe`: 134 shared resolved parameters, **0 values differ** (§F.3) | established |

Nothing in the output files identifies the build: no timestamp inside the data, no commit in any header, and
the header of the sampler is identical across campaigns.

### D.2 `published-model` (`9eb7ab856`) reproduces neither campaign

`9eb7ab856` descends from `fbce85dbe` through the eight model changes of §D.4, so it is not `final_v1`'s
model. Its `legacy` variant pinned T = 1.10 / 1.40 s — the production study's headway, which is
`production_v1`'s — while `final_v1` ran 1.00 / 1.30 s; and `production_v1` ran at `5b996a05a`, 44 + 29 + 45
commits earlier. The annotation "The parameterisation behind the published results" is therefore not true of
either.

### D.3 Other campaigns, as far as cheap

- **`production_v1`** (270 runs, 9 dates × 30): run directories 09-01 18:25–19:00; the study was added in
  `5b996a05a` (18:01:22, pushed 18:09:29); the next push was `c71b3827c` at 09-02 10:28. **Inferred commit
  `5b996a05a`.** Its `runParams.txt` matches `production|production-v1` on `agent/legacy-final-v1` in every
  variation key (§F.2). Not re-run: no longer load-bearing.
- **`final_v3`** (16 dates × 20): samplers 09-10 12:39–13:04; `origin` held `efaccb06b` from 12:23:13.
  **Commit `efaccb06b`** (inferred from timestamps; the model established by re-run, §E.2), which is Java-identical to `edefa9805` (`git diff` touches only
  `cluster/README.md` and `run_mirova.sbatch`). Its `runParams.txt` equals `final_v1`'s. So `final_v3` is
  `final_v1`'s parameters on the model *after* the eight changes. Re-run: §E.2.
- **`final_v2`**: summary files only, no `runParams.txt`, no samplers; cannot be placed.
- **`vgain-grid-1`**: tagged at `abe0b4095`, pushed; consistent with `docs/cluster/vgain-grid-1.md`.
- **TaMA recordings** replay-20/21/22: taken from OTS snapshots `2544c76ac` / `3502ca5d6`, both on
  `decoupling_phase05`; named in the TaMA checkpoint docs.

### D.4 The eight unlabelled model changes between `final_v1` and Phase 0.5

`e958ed365` (09-08, undercutting on the acceleration lane), `f6b13ca37` (09-08, undercutting congestion
test), `1de363b14` (09-09, route incentive null dereference — 12 vehicle deletions per run), `802593157`
(09-09, room to overtake), `74a0a9021` (09-09, blocker pulling away), `c155bfb46` (09-10, lane end under
control), `c7929297a` (09-10, empty lane to the right), `42bb55c66` (09-10, congested-branch hysteresis).
Each commit message reports a measured effect at defaults; details and the other 21 commits of the range in
the correction at the top of `docs/decoupling/phase05-report.md`.

**Count:** the brief said seven. `1de363b14` is the eighth on the same test — it changes trajectories at
defaults — although its aggregate metrics barely move. Your call whether it belongs in the list.

### D.5 Proposed tags — **not created**

```
campaign-final-v1   -> fbce85dbe
campaign-final-v3   -> efaccb06b
campaign-production-v1 -> 5b996a05a
```

Annotation `campaign-final-v1`:

```
final_v1: the reference standard. 16 dates x 50 seeds, study 'final', T = 1.00 / 1.30 s,
vGain 15 / 30 m/s (bare numbers read as SI), corrected relaxation.

Established: the study exists from this commit on; the nine original dates ran 2026-09-04
08:31-09:05 while origin held this commit and no later commit existed; cell 2025-10-27 seed 42
re-run at this commit reproduces the archived trajectories and detector output byte for byte
(md5 a99d7755..., 5265e3c6...), up to platform encoding of one header line.
Inferred: the cluster checkout was clean and pulled from origin. The seven dates added on
2026-09-07 ran at ec9fcb761 or 6e21c6139, whose Java, XML and POM files are identical to this
commit's.
Not recorded by the run itself; reconstructed 2026-09-16 (docs/fork-merge-plan.md, section D).
```

Annotation `campaign-final-v3`:

```
final_v3: final_v1's parameters on the model after the eight unlabelled changes of
2026-09-08..10. 16 dates x 20 seeds.
Established: cell 2025-10-27 seed 42 re-run at this commit reproduces the archived
trajectories and detector output byte for byte (md5 ff576ffa..., f73bae9d...); so does
final|legacy at a12f18d2a (agent/legacy-final-v1).
Inferred: the commit itself, from timestamps - samplers 2026-09-10 12:39-13:04, origin held
this commit from 12:23:13; edefa9805 has the same Java and would produce the same output.
```

Annotation `campaign-production-v1`:

```
production_v1: the 270-run production ensemble, 9 dates x 30 seeds, T = 1.10 / 1.40 s,
vGain 15 / 30 m/s. Inferred from timestamps only: runs 2026-09-01 18:25-19:00, the study was
added in this commit (pushed 18:09), the next push was 2026-09-02 10:28. Not re-run.
runParams match the production-v1 variant of agent/legacy-final-v1 in every variation key.
```

**`published-model`:** recommend **retiring the name** (delete the local tag) rather than re-pointing it. It
was never pushed, so nobody else holds it; a name that has carried a false claim is better replaced by one that
says what it identifies. `campaign-final-v1` takes its role. Re-pointing would keep the name and silently change
what it meant to anyone who has read it in a doc: `FreiburgProductionStudy`, `Phase05ReferenceStudy` and
`FreiburgStudyParameters` Javadoc mention it (the first two already corrected on `agent/legacy-final-v1`; the
third is not — listed in §K).

### D.6 Making it impossible to recur — built, on `agent/run-provenance`

`cluster/stamp_build.sh` writes `mirova-build.properties` (commit, `git describe`, branch, commit and build
time, host) into the classes a run loads and **fails on a dirty tree** (any tracked change; untracked files
under `ots-*/src/`, `pom.xml`, `cluster/demand/`). `build_for_cluster.sh` checks before and stamps after its
clean build; `run_local_parallel.sh` stamps its class snapshot and refuses a `target/classes` older than HEAD.
`ScenarioManager.prepareRun` copies the stamp into every run folder as `build.txt` before simulating, and a run
without a stamp refuses to start (`-Dmirova.allowUnrecordedBuild=true` for IDE runs, recorded as
`recorded=false`). Tested: clean / modified tracked / untracked source / untracked note; unstamped study run
refuses in seconds; stamped run writes `build.txt` into `run_seed_42`. TaMA's `snapshotOts` and live run do the
same on their side (TaMA `stage11-adapter`).

**Open point:** the stamp records neither the resolved parameters nor the JDK; `runParams.txt` still records
only the variation. Adding a resolved-parameter dump per variation would make §F unnecessary next time.

---

## E. Evidence per group

### E.1 Existing proofs

| range | proof | what it measures | covers |
|---|---|---|---|
| `edefa9805` = `9eb7ab856` = `2ccc1b362` | `RunFreiburgMergeWatch`, 60 min, seed 42, 2025-10-13 13:00–16:00, T 1.0/1.3, vGain 15/30 m/s (hard-coded in the harness) — trajectories md5 `0e4e3faf…`, 99 114 lines at all three (re-hashed by me from the output files) | every trajectory row of one window | every labelled commit **at this configuration**; not vGain (harness fixes it) |
| individual Phase 0.5 commits | production-cell recordings, 20 min, `e8dcc434…` (10-27) / `303125a4…` (09-22), named in commit messages of `6a103e509`, `a587e6fd5`, `2544c76ac`, `f04106196`, `a23cf3385`, `413d4337d`, `8434308f7`, `3502ca5d6`, `feb41f69e`, `7d6becdfa`, `ccb14ec3b` | planner-observer recording | each commit against its parent; recorder harness not committed |
| `148062f6d`, `e411f7f1b` | 45-min run byte-identical (message) / gate diagnostics byte-identical (message) | as stated | those two refactors |
| `fbce85dbe` = `final_v1` | §D.1 | full 9 h production cell | the final_v1 commit |

**Gaps:** the logging and cluster commits in `fbce85dbe..efaccb06b` have no proof of their own (they are
bundled with the eight model changes, so no end-to-end comparison can isolate them); the recordings of the
Phase 0.5 proofs are not in the repository.

### E.2 Closing the largest gap — two reruns of the production cell

Prediction, stated before the runs: (a) `final|final` at `efaccb06b` and (b) `final|legacy` at
`agent/legacy-final-v1` (tip of the labelled range + vGain + the variant correction) both reproduce the
archived `final_v3` cell 2025-10-27 seed 42 byte for byte, up to the platform artefacts of §D.1. A
difference in (b) but not (a) would put a default change in a labelled commit.

**Result — prediction confirmed.** Unzipped outputs of cell 2025-10-27, seed 42 (9 simulated hours):

| source | trajectories md5 | bytes | detector_periodic md5 | detector_positions md5 |
|---|---|---|---|---|
| `final_v3` archive (cluster) | `ff576ffa6540…` | 104 335 955 | `f73bae9dc3bb…` | `0550a24efebc…` |
| re-run at `efaccb06b` | `ff576ffa6540…` | 104 335 955 | `f73bae9dc3bb…` | `0550a24efebc…` |
| re-run `final|legacy` at `agent/legacy-final-v1` (`a12f18d2a`) | `ff576ffa6540…` | 104 335 955 | `f73bae9dc3bb…` | `0550a24efebc…` |
| control: `final_v1` archive | `a99d77551b31…` | 106 590 153 | `5265e3c6bbb0…` | `0550a24efebc…` |

What this establishes:
- `final_v3` ran on a model identical in output to `efaccb06b` (for this cell) — the inferred commit holds.
- **Every commit from `efaccb06b` to `2ccc1b362`, including all Phase 0.5 work, the switches at default, the vGain
  commit and the legacy correction, leaves the full production cell unchanged at `final_v1`'s parameters.** This
  covers the logging/cluster commits of §E.1 that sit after `efaccb06b`, and it covers vGain in the only sense that
  matters here: `legacy` still runs the old vGain, byte for byte.
- The control differs, so the comparison can see a model change: the eight commits of §D.4 move this cell
  (trajectory file 2.1 % shorter).

What it does not establish: other dates and seeds; `reference`/`final` at the new vGain (a deliberate change);
the logging and cluster commits *between* `fbce85dbe` and `efaccb06b` separately from the eight model changes.
Outputs: `tama-workspace/scratch/fv3-efac`, `fv3-legacy`, `fp-final-fbce`.

---

## F. Resolved-parameter comparison

Tool: `ParamDump` (archived in `tama-workspace/provenance/`) registers every study in `StudyRegistry` with its
default variant set and each named label, for date 2025-10-27, merges each cell onto the generator defaults as
`ScenarioManager.prepareRun` does, and resolves car and truck parameters through
`buildStrategicalPlannerFactoryCar/Truck()` → `getParameters()` — the path a run takes. Out of scope: per-vehicle
draws (`FSPEED`).

### F.1 Across commits

- `main`: no `StudyRegistry`; only `MergeScenario`/`SimpleHighwayScenario` defaults (322 lines). Main predates
  the model: declared defaults differ widely (extended look-ahead 400 → 1000 m, follower thresholds, several
  parameters added and removed).
- `9eb7ab856` → `2ccc1b362`, over the 301 cells both have: only `car.VGAIN` 15.0 → 4.1667 and `truck.VGAIN`
  30.0 → 8.3333 SI in every cell inheriting the production set except `legacy`; four new switches, `false`
  everywhere except `coreset`/`coreset-interp`; new studies (`vgaintau`, `vgainscreen`, `vgaingrid`) and variants
  (bc10…bc13).
- `fbce85dbe` → `agent/legacy-final-v1`, cell `final`: 134 shared resolved parameters; 7 per vehicle type exist
  only at `fbce85dbe` (removed later as dead: `MANDATORY_LANE_CHANGE_LOOK_AHEAD_DISTANCE`,
  `SOCIAL_INTERACTION_COOLDOWN`, `STANDSTILL_SPEED_THRESHOLD`, `TTC_EMERGENCY_BRAKING`, `VCRIT`, `aScale`,
  `tau_relax_v`); 11 `bc*` switches only on the branch, all `false`; values: only VGAIN in `final|final`, none in
  `final|legacy`.

### F.2 The legacy correction (`agent/legacy-final-v1` vs `decoupling_phase05`)

Prediction stated before the run, confirmed exactly:

- new cells: `final|legacy`, `production|production-v1`;
- changed: `production|legacy` and `phase05|legacy` only — resolved `car.T` 1.1 → 1.0, `truck.T` 1.4 → 1.3; raw
  `headwayCombination` settled → T100, `tRelaxFade`/`relaxMaxLifetime`/`aRelaxAbort` added, `studyACar`,
  `studyB`, `studyS0Car` removed;
- 397 other cells unchanged (apart from the demand path, which names the worktree);
- `production|production-v1` resolves identically to the former `production|legacy`.

Against the campaigns' own `runParams.txt` (a different source: written by the cluster at run time):

| campaign | variant | differing keys |
|---|---|---|
| `final_v1` | `final|legacy`, `production|legacy` | `demand`, `mergeShare`, `truckShare` (generator defaults, absent from runParams), `demandCsvStrict` (command-line option) |
| `final_v1` | `phase05|legacy` | the same four + `phase05.switch=published-vgain` (the study's own marker) |
| `production_v1` | `production|production-v1` | the same four |
| `final_v1` | `final|final` (default) | the same four + VGAIN 15 → 4.17, 30 → 8.33 |

Which variant resolves to what: **`legacy` (final, production, phase05) = `final_v1`'s parameters;
`production-v1` = `production_v1`'s.** Parameters only — on any commit after `fbce85dbe` the model is not
`final_v1`'s (§D.4).

### F.3 What `campaign-final-v1` and `legacy` point at afterwards

`campaign-final-v1` → `fbce85dbe`, reproduces `final_v1` (§D.1). `legacy` on the tip → `final_v1`'s parameters on
the current model, and that reproduces **`final_v3`** byte for byte (§E.2), not `final_v1`.

---

## G. Proposal

### G.1 The constraint

History is linear. The eight model changes and vGain are ancestors of everything after them — the Phase 0.5
switches, the TaMA hooks, the provenance tooling. The agent's trial revert of `abe9125bd` in a throwaway
worktree conflicted in `docs/decoupling/bc4-and-reference-check.md` and, with the docs dropped, did not compile
(`VGainGridStudy`, `Phase05ReferenceStudy` use `CAR_V_GAIN`/`TRUCK_V_GAIN`). Reverting eight model fixes would
be worse. Keeping kind (3) out of `main` therefore means either rewriting history or leaving `main` at a point
that has none of the later work.

### G.2 Options

| | what `main` becomes | cost | risk |
|---|---|---|---|
| **1 (recommended)** | fast-forward to the tip, then `--no-ff` merges of the four agent branches; campaign commits tagged; default = eight fixes + vGain in km/h, stated in `docs/mirova/README.md` and the merge commit | one session; no rewritten history | `main`'s default is uncalibrated (vGain) — visible, labelled, and `legacy` runs the reference parameters |
| 2 | fast-forward to `edefa9805` only (no vGain, no Phase 0.5) | small | `main` lacks the switches, TaMA selection and provenance; everything later stays on branches |
| 3 | fast-forward to the tip plus one labelled commit on `main` that sets vGain back to 15/30 m/s until recalibration | as 1 + one commit | reverses a decision you took; two commits that cancel each other in the history of a parameter |
| 4 | rebuild `main` by cherry-picking kinds (1)+(2) onto `fbce85dbe` | days | every proof in §E is against the original commits and would have to be redone; rejected |

Why 1 over your stated preference ("(3) stays on a branch until recalibration"): kind (3) is not one commit on
a branch but nine commits in the middle of the line, and they are already individually visible — vGain carries
`[behaviour change]`, the eight are now named in the phase05-report correction. What "not silently" needs is that
`main`'s default is declared and each campaign's commit is identified, which the tags and the README entry do.
If you want `main` to stay on the calibrated vGain until recalibration, option 3 is the honest form of it.

---

## H. Items to keep on the list

1. **`RunFreiburgMergeWatch` labels vGain "[km/h]" and sets 15 / 30 m/s** (`CAR_V_GAIN = 15.0`, `TRUCK_V_GAIN =
   30.0`, `Speed.instantiateSI`, lines ~160 / ~250 / ~374 / ~392 at `2ccc1b362`). The `freiburg-merge` FSM
   reference trace takes its parameters from `watchParameters` there, so it pins the old value. Whoever moves
   vGain must know — and the MergeWatch identity proof of §E.1 is blind to vGain for the same reason.
2. **The FSM trace references are stale since `b071a17c4` and cannot match HEAD.** Measured by reading, not run:
   both references (`freiburg-merge`, `merge`) were last recorded in `b071a17c4` (09-03 18:22); after it,
   `cbcfa6d77` (09-03 18:32) changed `FsmTraceRecorder` (+42 lines) and `ActionState`, `c3477979c` and `41d473cba`
   changed the harness parameters, and the eight model changes followed. The test "passes" because it does not
   run: `@EnabledIfSystemProperty(named = "mirova.fsmtrace", matches = "true")` skips it in every normal build, and
   `highway` has no reference at all, so `assumeTrue` skips it even when enabled. Prediction if enabled: both
   recorded cases fail at an early row. Not verified by running (a full headless simulation per case).

---

## I. IDE files in version control — proposal, not executed

**The blocker:** the main worktree has ` M ots-demo/.settings/org.eclipse.core.resources.prefs` (Eclipse removed
`encoding//src/test/resources=UTF-8`), so `stamp_build.sh` refuses to stamp a build there.

**Measured:** **184** IDE files are tracked: `.project`, `.classpath` and 9 `.settings/*.prefs` for each of 16
modules, 4 root `.settings`/`.project` files, and `.vscode/{extensions,launch,settings}.json` (the three `.vscode`
files added by the fork in `5c036888f` / `f919f6298`). A further 16 `.checkstyle` files are tracked. `.gitignore`
already lists `.classpath`, `.project`, `.settings/`, `.vscode/` — the files were committed before the ignore
rules and stay tracked regardless of them. **Upstream tracks them too:** 181 at the fork base `81241d09f`, 170 at
upstream `main` (`9b2ab14a3`).

**Proposal (removes the cause):** untrack all of them, keep them on disk, and rely on the existing ignore rules.

```
cd D:/Mitarbeitende/gw2128/repositories/opentrafficsim
git rm -r --cached --quiet -- $(git ls-files | grep -E '(^|/)\.settings/|(^|/)\.project$|(^|/)\.classpath$|(^|/)\.vscode/')
git status --short | grep -v '^D ' | head          # expect nothing: only deletions from the index
git commit -m "chore: stop tracking IDE project files; .gitignore already excludes them [no behaviour change]"
```

`.checkstyle` is not in the ignore rules and is read by the Eclipse Checkstyle plugin only; leave it unless you
want it gone too (then add `.checkstyle` to `.gitignore` in the same commit).

**Costs to weigh:**
- **Upstream updates:** upstream keeps tracking these files, so a future merge or rebase onto upstream produces a
  modify/delete conflict for each file upstream changed (11 of them changed between base and upstream head).
  Resolved mechanically with `git rm`, but it is noise in exactly the operation §3 of the upstream plan calls
  expensive.
- **Eclipse users of a fresh clone** lose the shared JDT compiler settings, per-project UTF-8 encoding and save
  actions. Maven builds are unaffected (`project.build.sourceEncoding` is UTF-8 in the root POM).
- **Fallback if you prefer not to diverge from upstream:** keep them tracked and let `stamp_build.sh` ignore
  tracked changes under `.settings/`, `.project`, `.classpath`, `.vscode/`. That narrows the check to what can
  change a Maven build — but it is a loosening of the check, and you asked for the cause to be removed, so it is
  not what I recommend.

---

## J. Branches after the merge

| branch | recommendation |
|---|---|
| `agent/bc6-range` | identical to d05 → delete local and remote after the merge |
| `agent/phase1-hygiene`, `agent/tama-contract-s8`, `agent/observer-proposals` | fully contained → delete after the merge; worktrees under `.claude/worktrees/` removed first |
| `agent/tama-planner-selection`, `agent/run-provenance`, `agent/legacy-final-v1`, `agent/fork-tidy-plan` | merged with `--no-ff` → keep the remote branches **30 days** as backup, then delete |
| `decoupling_phase05`, `laneChangeIncentive_Reengineering`, `tama-fixtures` | contained → keep 30 days, then delete; push the local 1 / 8 commits first only if you want the remote to show them (they are in `main` either way) |
| `backup_before_reset_to_0708` | 19 unique commits, local only, superseded by the model line → push to `origin` as `archive/backup-before-reset-0708` (so they exist somewhere but the clone), then delete the local branch; or tag `archive/…` |
| `perf/djunits-hash-cache-experiment` | 10 unique (4 local only) → push the 4, keep as an archived experiment (`archive/` prefix) |
| `profiling/backend-hotspots` | 1 unique → keep as archive or cherry-pick if the pin is still wanted |
| `origin/pse_maneuver_editor` | contained in `main` → delete remote |
| prunable worktrees `D:/otsA`, `otsbase`, `otspatch`, `otsref`, `otsrun` | `git worktree prune` |
| scratch worktrees `tama-workspace/scratch/fp-*` | `git worktree remove` after you have read this plan |

---

## K. Command sequence (option 1) — to be run by you, nothing executed

```
cd D:/Mitarbeitende/gw2128/repositories/opentrafficsim
git fetch origin
git status --short                      # main worktree clean first (§I)

# 1. Tags on the campaign commits (after you approve §D.5 and §E.2)
git tag -a campaign-final-v1      fbce85dbe -F <annotation file>
git tag -a campaign-final-v3      efaccb06b -F <annotation file>
git tag -a campaign-production-v1 5b996a05a -F <annotation file>
git tag -d published-model             # local only, never pushed
git push origin campaign-final-v1 campaign-final-v3 campaign-production-v1

# 2. main: fast-forward to the line
git switch main
git merge --ff-only decoupling_phase05
git merge-base --is-ancestor fbce85dbe main && git merge-base --is-ancestor 5b996a05a main && echo campaigns-reachable

# 3. The four new branches, each as a visible merge
git merge --no-ff agent/fork-tidy-plan         # docs: this plan, phase05-report correction
git merge --no-ff agent/legacy-final-v1        # legacy = final_v1 parameters  [behaviour change, legacy only]
git merge --no-ff agent/run-provenance         # build stamp, build.txt per run
git merge --no-ff agent/tama-planner-selection # tacticalPlanner=tama
#    expected conflicts: none. Measured with `git merge-tree --write-tree` for all six pairs: rc=0 each;
#    the branches touch disjoint files (1 / 3 / 6 / 3 files, no file in two branches)

# 4. Verify on main before pushing
mvn clean install -pl ots-demo -am -Dmaven.test.skip=true -Dmaven.javadoc.skip=true -Djacoco.skip=true
bash cluster/stamp_build.sh "$PWD" ots-demo/target/classes manual   # must succeed on a clean tree
#    resolver: ParamDump against main; expect exactly the F.2 result relative to 2ccc1b362
#    one production cell, --study=final --variants=legacy, compare to final_v3 (as E.2)

# 5. Push (no force)
git push origin main

# 6. Afterwards (section J), not before main is pushed and verified
```

Remaining edits before step 1, on a branch: `FreiburgStudyParameters` Javadoc still says "Use the legacy variant, or
the tag `published-model`"; `docs/mirova/README.md` should state `main`'s default (eight fixes + vGain km/h,
uncalibrated) and name the campaign tags.

**Open check not yet run:** enabling the FSM trace test to confirm item H.2.

---

## Task 2 — Tidying the MiRoVA part of ots-demo

**Audit only. Nothing here has been deleted or edited.** The format follows
[`decoupling/cache-audit.md`](decoupling/cache-audit.md): each entry says what the thing is, what refers
to it (grep evidence), and a recommendation. **Marvin decides.**

Read at `agent/fork-tidy-plan` = `decoupling_phase05` tip `2ccc1b362`. Also searched: `diss_mvb` at
`a70da43` (tracked files, `git grep`) and `tama-stage11` at `5d065f4`.

**How sure each "unused" is:**

- **[verified unused]**: no caller on any path, and the thing is not reachable by reflection or by string
  lookup. Either nothing mentions it, or the only mentions are comments or docs.
- **[no grep reference]**: grep finds no caller. It could still be reached through `StudyRegistry.resolve`
  (`Class.forName` on a fully qualified name, `StudyRegistry.java:92`), an IDE launch config, or a command
  line typed by hand.
- **[verified set, not read]**: a value is written into `ScenarioParameters` or a `Parameters` set, and I
  followed every reader.

Scale of what is audited: 55 Java files, 13 747 lines, under `ots-demo/.../demo/mirova`. The 88 files under
`ots-road/.../tactical/mirova` are only covered where the demo code reaches into them.

---

### 2.1 Summary: the entries that matter most

| # | Entry | Kind | Recommendation |
|---|---|---|---|
| T2-1 | `RunFreiburgMergeWatch` runs speed gain 15/30 **m/s** while its Javadoc says km/h and "study baseline 15.0". The `freiburg-merge` FSM reference trace inherits that value. | stale hand copy | Fix the copy, or label it `legacy` on purpose; decide what the trace should pin |
| T2-2 | `MergeScenario` ignores `demand` and `mergeShare` from the run parameters, so `RunParallelMergeScenarios` and `FsmTraceHarness` set values that nothing reads | set, not read | Read `params`, or stop setting the values; note that the MERGE trace depends on it |
| T2-3 | `farAnticipationEnabled` is set in 3 files, 6 call sites, but its only reader, `AnticipateDownstreamMergePattern`, is not registered | set, not read | Drop the sets, or keep them deliberately for the day the pattern returns |
| T2-4 | `ScenarioGenerator` silently drops `car.`/`truck.` keys it cannot resolve | trap | Fail loudly on an unknown key |
| T2-5 | The production parameter set is spread over 6 classes, 4 of them historical calibration campaigns | scattered defaults | Put it in one class, then mark the campaigns historical |
| T2-6 | `docs/decoupling/parameters.md` is stale in five places, including the TMIN/TMAX example this task started from | stale doc | Correct it |
| T2-7 | `SimpleHighwayScenario` + the Wiedemann 99 family: about 1 460 lines, no publication, no cluster use, no Python use | retire candidate | Retire; costs one skipped test case plus doc/ADR amendments in OTS and TaMA |
| T2-8 | `--array` in `run_mirova.sbatch` is hand-set, and a too-small range drops runs without an error | hand-maintained count | Guard with `SLURM_ARRAY_TASK_MAX`, or submit through a wrapper |
| T2-9 | 21 of 27 desired-speed distributions are selected by no scenario | unused library entries | Keep the file, mark which entries are live |
| T2-10 | Local runners with hard-coded `D:\` paths and legacy parameter copies (`RunFreiburgParallel`, `RunFreiburgNord`, `RunMerge`, …) | superseded | Retire, or replace with one parameterised runner |

---

### 2.2 Parameters that are set but that nothing reads

#### T2-0 — The known example (TMIN/TMAX on MiRoVA factories) is already fixed

- **What:** `docs/decoupling/parameters.md:128-131` says that `MergeScenario` and `SimpleHighwayScenario`
  set `ParameterTypes.TMIN`/`TMAX` on MiRoVA planner factories.
- **Evidence:** commit `3b73c7ab7` ("delete the parameters nothing reads") removed those calls from
  `SimpleHighwayScenario`. In `MergeScenario` they are commented out on the MiRoVA factories
  (`MergeScenario.java:236-237`, `:289-290`). The live calls sit on LMRS factories (`:257-258`, `:322-323`).
  The LMRS builders that contain them, `buildLmrsStrategicalPlannerFactoryCar/Truck` (`:252`, `:317`), are
  **[verified unused]**: their only call sites are the commented lines `:187` and `:189`.
- **Recommendation:** fix the paragraph in `parameters.md` (see T2-6). In `MergeScenario`, remove the two
  dead LMRS builders, the commented W99 blocks (`:216-226`, `:271-280`), and the imports that only those
  blocks use (`:67-71`, `:89`). About 60 lines, `[no behaviour change]`.

#### T2-1 — `RunFreiburgMergeWatch` sets the published speed gain but says it sets the intended one

- **What:** `RunFreiburgMergeWatch.java:160-161` declares `CAR_V_GAIN = 15.0` with the Javadoc
  "[km/h]. Study baseline: 15.0", and `:251` declares `TRUCK_V_GAIN = 30.0`. Both are applied as
  `Speed.instantiateSI(...)` (`:374`, `:392`), which gives 54 and 108 km/h. Since `abe9125bd` the study
  baseline is 15 and 30 **km/h** (`FreiburgStudyParameters.CAR_V_GAIN`/`TRUCK_V_GAIN`). The class header
  (`:32-36`) still promises that the defaults "reproduce the calibration the studies use".
- **Referenced by:**
  - `FsmTraceHarness.parametersFor` → `RunFreiburgMergeWatch.watchParameters` (`FsmTraceHarness.java:226`).
    The committed `freiburg-merge.trace.csv.gz` therefore pins the **legacy** speed gain. Its README
    (`src/test/resources/mirova/fsmtrace/README.md:22-24`) presents this as "a change to that calibration
    reaches the regression net". That stopped being true when `abe9125bd` changed the calibration and
    left this copy alone.
  - `tools/run_merge_gui.sh`, `cluster/README.md:811`, and four docs under `docs/mirova`.
- **Same pattern elsewhere:** `RunFreiburgNord.java:46-47` and `RunFreiburgParallel.java:88`, `:100` also
  write `Speed.instantiateSI(15.0 / 30.0)`.
- **Recommendation:** decide what the merge-watch is for.
  - If it is "the production model, editable": take the speed gain from `FreiburgStudyParameters` as the
    other constants already do. This changes behaviour and needs a re-recorded trace.
  - If it should stay on the published model: rename the constants to say so and fix the Javadoc.
  - Either way, most of its 26 constants copy values from `FreiburgStudyParameters`, and the copy has now
    drifted once. Deriving every default from the production class, and keeping only the
    `System.getProperty` overrides here, removes that risk.

#### T2-2 — `MergeScenario` ignores `demand` and `mergeShare` from the run parameters

- **What, [verified set, not read]:**
  - `demand`: the only reader is `createVehiclesFromGenerator` (`MergeScenario.java:168`), which is not
    called; its call is commented out at `:160`. The OD path hard-codes a ramp from 1000 to 6500 veh/h
    (`:392-394`, with `// params.getDemand()` left in the comment).
  - `mergeShare`: read from `this.defaultParameters` rather than from `params`, both for routes
    (`:191`, `:193`) and for OD demand (`:407-410`). The default is 0.2 (`:493`).
  - The comment at `:491` says "5% trucks" next to `0.1`.
- **Referenced by (writes with no effect):**
  - `RunParallelMergeScenarios.java:45`: `setMergeShare(0.1)`, which silently runs at 0.2.
  - `RunMerge.java:28`: `setMergeShare(0.2)`, the same value as the default, so no visible difference.
  - `FsmTraceHarness.java:236`, `:238`: `setDemand(4000)`, `setMergeShare(0.2)` for the MERGE case.
    `setDemand` has an effect only for HIGHWAY.
  - `FreiburgNord.java:560, 563`: default `demand` 4500 and `mergeShare` 0.2. They are read only by the
    synthetic-demand fallback (`:355-390`), which strict mode (`KEY_DEMAND_CSV_STRICT`, set by every study)
    makes unreachable.
- **Recommendation:** let `MergeScenario` read `params`. This is a `[behaviour change]` only for
  callers passing a non-default value. The MERGE trace passes the default and should stay byte-identical;
  verify with `FsmTraceRegressionTest`. Otherwise remove the ineffective `set` calls. Also retire
  `createVehiclesFromGenerator` in `MergeScenario` (**[verified unused]**).

#### T2-3 — `farAnticipationEnabled`

- **What:** `MirovaParameters.farAnticipationEnabled` (`FAR_ANTICIPATION_ENABLED`, default `true`) is
  snapshotted (`MirovaParameterSnapshot.java:365`) and read only by `AnticipateDownstreamMergePattern`.
  That pattern's registration is commented out at `MirovaTacticalPlannerFactory.java:213`, with a
  measured reason (`:197-212`). The same holds for `preemptiveCooperativeDeceleration`, but only the
  pattern's own read of it is dead: `GapOpenerPattern` also reads it and is live.
- **Set by, [verified set, not read]:** `FreiburgStudyParameters.java:261`, `:278`;
  `RunFreiburgMergeWatch.java:377`, `:396`; `RunFreiburgParallel.java:91`, `:104`. That is 3 files and 6
  calls. The count is also the correction to `parameters.md:174`, which says "six studies".
- **Recommendation:** keep the parameter, since the pattern is retained by decision. Either remove the six
  `false` writes (`[no behaviour change]`), or mark them in the code as deliberate insurance for when the
  pattern is re-registered. At present they suggest to a reader that the far anticipation is switched off
  by configuration. In fact it is switched off by the missing registration.

#### T2-4 — Unknown `car.`/`truck.` keys are dropped without a word

- **What:** `ScenarioGenerator.buildStrategicalPlannerFactoryCar/Truck` look each key up in
  `PARAMETER_TYPES` and apply it only `if (pt != null)` (`ScenarioGenerator.java:1238`, `:1277`). A typo,
  or the id of a parameter that has since been deleted (for example the five removed in `3b73c7ab7`),
  runs as the model default with no warning.
- **Evidence it has not bitten yet:** every `car.`/`truck.` key in the demo tree is built from
  `<Type>.<FIELD>.getId()`. That is 20 distinct parameters, all of which resolve today. No study uses a
  literal id string.
- **Recommendation:** throw on an unresolved key, `[no behaviour change]` for every current study. This is
  the same class of trap as the SI/km/h speed gain that `fe5275866` closed.

#### T2-5 — Other scenario-parameter keys without readers

- `ScenarioParameters.KEY_RANDOM_STREAM` (`ScenarioParameters.java:119`): **[verified unused]**, no
  setter, getter or use.
- `setNetworkName`/`getNetworkName` and `setDesiredSpeedDistribution`/`getDesiredSpeedDistribution`
  (`:219-233`): **[verified unused]**, no caller of any of the four.
- `RELAXATION_ACC_DAMPING_ENABLED = true` together with `RELAXATION_DAMPING = 1.00`
  (`FreiburgStudyParameters.java:138`, `:266-267`, `:281-282`): not dead, but a no-op pair. With factor 1.00, "enabled"
  is identical to disabled; `FreiburgDampingStudy`'s Javadoc proves this. Worth a comment, not a change.
- **Recommendation:** remove the three unused accessors and the constant, `[no behaviour change]`.

---

### 2.3 Studies and parameter grids

#### T2-6a — What each registered study is for, and what depends on it

All 22 studies are registered in `StudyRegistry.java:48-69`. None is named in `cluster/`, except `dates`
(the default in `run_mirova.sbatch:329`) and the campaign log in `cluster/README.md`. The `diss_mvb`
pipeline reads output folders generically and names no study. The one study-specific trace in it is a
hard-coded `_final` run path in `scripts/presentation/merge_slide/build_episodes.py:36`. TaMA imports
`Phase05ReferenceStudy` (`RecordFreiburgNord.kt`, `RunFreiburgNordLive.kt`), which reaches
`FreiburgCongestedBranchStudy.forCell` and `FreiburgProductionStudy`.

| Short name | Class (lines) | Role today | Depended on by other classes | Recommendation |
|---|---|---|---|---|
| `final` | `FreiburgFinalStudy` (177) | The published ensemble (ITSC/TR-B) | `RunFreiburgMergeWatch` constants | **keep** |
| `production` | `FreiburgProductionStudy` (190) | Production set + `legacy` variant | Phase05, VGain×3, Capacity, CapDrop, Relaxation, Susceptibility, Final | **keep**; see T2-5b |
| `phase05` | `Phase05ReferenceStudy` (281) | Golden reference and core set; **used by TaMA** | TaMA recorder | **keep** |
| `vgaintau`, `vgainscreen`, `vgaingrid` | 217 / 191 / 194 | Recalibration inputs (`recalibration-inventory.md`) | each other | **keep** |
| `dates` | `DateStudy` (187) | Generic date×set study; `resolveDates`/`resolveDemandCsvs` used by 20 studies | all | **keep** |
| `congested` | `FreiburgCongestedBranchStudy` (194) | Historical campaign, but its `forCell` builds the production cell | Production, Phase05, VGain×3 | **keep until T2-5b**, then historical |
| `combos` | `FreiburgCombinationStudy` (220) | Historical campaign; `forCombination` is part of the production chain | 16 studies | same as above |
| `carparams`, `sensitivity` | 186 / 190 | Historical; export `SAFETY_DISTANCE_FACTOR`, `BASE_HEADWAY`, `BASE_DAMPING` into the production chain | Congested, Settled, Smoothness, Validation, VGainScreen | same as above |
| `damping` | 138 | Historical (campaign 2); exports `resolveHeadwayCombination` | Behaviour, Car, MergeGrid | historical |
| `paramgrid`, `mergegrid`, `behaviour`, `validation`, `smoothness`, `settled`, `capacity`, `capdrop`, `susceptibility`, `relaxation` | 135–246 each, 1 755 total | Calibration campaigns 1–13 of `recalibration-inventory.md:27-39` | a few static constants | **mark as historical** |

**Why "historical" and not "retire":** `docs/decoupling/recalibration-inventory.md` uses these classes as
the record of the calibration path (their Javadoc holds the measured findings), and states at `:142` that a
rerun "needs nothing structural". Deleting them would lose that record and the ability to rerun a campaign
at a pinned commit.

**Recommendation:** move the historical campaigns into a `scenarios.historical` package, or list them in a
separate `StudyRegistry` block so that `knownStudies()` shows them as historical. Add a one-line
"historical: campaign N, superseded by …" to each Javadoc. Output folder names come from
`FreiburgStudyParameters.scenarioName` plus labels, not from the package, so this is
`[no behaviour change]`, but re-check `StudyRegistry.resolve` callers that pass fully qualified names.

#### T2-5b — The production parameter set lives in six places

- **What:** the cell that `production`, `final`, `phase05` and the three `vgain*` studies resolve is
  assembled from:
  1. `FreiburgStudyParameters.baseBehaviorParams` (T, a, b, s0, vGain, and more);
  2. `FreiburgCombinationStudy.forCombination` (T, damping, safety factor);
  3. `FreiburgCarStudy.SAFETY_DISTANCE_FACTOR = 0.40` (`FreiburgCarStudy.java:67`), re-exported by
     Sensitivity `:54` and CongestedBranch `:59`;
  4. `FreiburgSensitivityStudy.BASE_HEADWAY`/`BASE_DAMPING = 1.00` (`:57`, `:61`);
  5. `FreiburgCongestedBranchStudy.forCell` (b, s0, a) (`:178-193`);
  6. `FreiburgProductionStudy.B = 1.75`, `A_CAR = 1.4` (`:63`, `:72`).

  TaMA's `docs/checkpoints/stage8b.md:63-65` had to trace this chain by hand to find out that production
  damping is 1.00.
- **Duplication inside the chain:** `FreiburgBehaviourStudy.java:69,72` and `FreiburgCarStudy.java:64,67`
  both declare `ACC_DAMPING_FACTOR = 0.80` and `SAFETY_DISTANCE_FACTOR = 0.40` independently.
- **Recommendation:** add one `FreiburgProductionParameters` (or extend `FreiburgStudyParameters`) that
  states the whole cell. `forCell`/`forCombination` would then delegate to it, and the historical campaigns
  would reference it rather than being referenced by it. Once that is done, T2-6a's "keep until T2-5b"
  rows become freely retirable. `ParameterTableGenerator` already resolves the set through the real code
  path, so a byte-compare of `parametertable.tex` before and after proves the refactor is
  `[no behaviour change]`. Also note `recalibration-inventory.md:44`: `production` resolves T = 1.10/1.40
  and `final` resolves 1.00/1.30. A single class would make that fork visible rather than implicit.

#### T2-6c — `ParameterGridBuilder` (356 lines)

- **What:** a generic Cartesian-product builder.
- **Referenced by:** `FreiburgParameterStudy` only (`paramgrid`), plus two docs. It carries
  `@author Antigravity Agent`, as does `RunFreiburgParallel`, against the KIT header standard. Every later
  grid study hand-rolls nested loops instead, and `FreiburgCombinationStudy.java` Javadoc (lines 60-66)
  explicitly defers "a `List<Dimension>` walked by a generic cartesian product".
- **Recommendation:** either make it the single grid mechanism (a merge, when a study next needs a new
  axis), or retire it together with `paramgrid`. Keeping a generic builder that one historical study uses
  is the worst of both.

---

### 2.4 Distribution libraries

#### T2-9 — `DesiredSpeedLibrary` (755 lines, 27 public factory methods)

| Entry | Selected by | Status |
|---|---|---|
| `carsLimit140_DensityLow` (`:284`) | `FreiburgNord.java:220` | **live, production** |
| `trucksLimit100_DensityClass1_Modified` (`:698`) | `FreiburgNord.java:227` | **live, production** |
| `hoogendoornCars` (`:345`), `hoogendoornTrucks` (`:387`) | `MergeScenario.java:198,205`, `SimpleHighwayScenario.java:179,186` | live (MERGE trace); becomes MERGE-only if T2-7 is done |
| `carsUnrestricted` (`:110`), `trucks` (`:441`) | `SimpleSimulation.java:252,255` only | dies with `SimpleSimulation` (T2-10) |
| `cars100kmh`, `cars120kmh`, `cars130kmh` | — | **[no grep reference]** |
| 8 other `carsLimit{80,100,120,140}_Density*` | — | **[no grep reference]** |
| 10 other `trucksLimit{80,100}_DensityClass*` (5 plain, 5 `_Modified`) | — | **[no grep reference]** |

- Nothing in TaMA or `diss_mvb` calls the library. TaMA's ADR-008 names `carsLimit120_DensityHigh` only as
  a classification example.
- The entries are static methods called from Java. Selection by string (`KEY_DESIRED_SPEED_DISTRIBUTION`)
  exists as a parameter key, but it has no reader (T2-5), so no hidden reflective use is possible.
- **Correction:** `parameters.md:141` says "28 named distributions plus `trucks`". There are 27 methods
  including `trucks`.
- **Recommendation:** keep the file. The unused fits are cheap and document the source data, and the
  open decision `contract.md` Q3 on the `_Modified` variants concerns exactly these entries. Add a header
  table marking the two production entries. Retiring the 21 unused methods (about 560 lines) is fine too,
  but only after Q3 is settled.
- **One observation, not a finding:** FreiburgNord takes the *low-density, 140 km/h* car fit for every
  time of day. Whether that was intended is outside this audit.

#### `HeadwayDistributionLibrary` (80 lines, 1 method)

`shiftedExponential` is used by `FreiburgNord.java:474`. **Keep.** A one-method "library" could be folded
into `FreiburgNord`, but the saving is trivial.

---

### 2.5 T2-7 — `SimpleHighwayScenario` and the Wiedemann 99 family

**What it is:** a synthetic 3-lane motorway scenario that runs MiRoVA on `Wiedemann99Factory` for cars and
trucks (`SimpleHighwayScenario.java:242-290`, W99 data logger at `:362`). No result from it is in any
publication.

**What would be removed:**

| File | Lines |
|---|---|
| `ots-demo/.../scenarios/SimpleHighwayScenario.java` | 576 |
| `ots-demo/.../scenarios/RunSimpleHighwayScenarios.java` (hard-coded `D:\` path, 11 commented-out code lines) | 51 |
| `ots-road/.../ReactiveLayer/Wiedemann99.java` | 323 |
| `ots-road/.../ReactiveLayer/AbstractWiedemannModel.java` | 261 |
| `ots-road/.../ReactiveLayer/W99ParameterTypes.java` | 94 |
| `ots-road/.../ReactiveLayer/AbstractWiedemannFactory.java` | 78 |
| `ots-road/.../ReactiveLayer/Wiedemann99Factory.java` | 29 |
| `ots-road/.../util/logging/extendeddata/ExtendedDataW99DrivingMode.java` | 49 |
| W99 imports and commented W99 blocks in `MergeScenario.java` (`:67-71`, `:89`, `:216-226`, `:271-280`) | about 27 |
| **Total** | **about 1 490** |

It would also remove the one `ParameterSet`-as-state wart that `parameter_access_and_units.md:272-274`
records (`CURRENT_DRIVING_MODE` written by `Wiedemann99`).

**What it would cost, meaning everything that depends on it:**

| Dependant | Evidence | Cost |
|---|---|---|
| Tests | `FsmTraceHarness.Case.HIGHWAY` (`FsmTraceHarness.java:66`, `:202`); `FsmTraceRegressionTest.highwayTraceIsUnchanged` (`:72-76`) | **None in coverage.** No reference trace exists, because the scenario "currently aborts on the deadlock watchdog" (`fsmtrace/README.md:28`), so the test always skips. Remove the enum constant and the test method. No `ots-road` test references W99 (`grep` over `ots-road/src/test`). |
| GUI / IDE | `.vscode/launch.json` has no entry for it; `RunSimpleHighwayScenarios` has no references | none |
| Cluster | no reference in `cluster/` | none |
| Python (`diss_mvb`) | no reference to `SimpleHighway`, `W99` or `Wiedemann99`. The Wiedemann hits there are *field-data* estimation of Wiedemann-74 thresholds (`estimate_car_following.py`, `run_behavioral_analysis.py`), unrelated to the OTS class | none |
| TaMA | ADR-011 (`docs/adr/011-idm-plus-only-in-v1.md:11-21`) and `tama-api/.../CarFollowingModel.kt:25-26` justify "W99 stays in OTS" by `SimpleHighwayScenario` being alive | Amend the ADR and the KDoc sentence. No code dependency. |
| OTS decoupling docs | `contract.md:399-400`, `:596`; `contract/CarFollowing.kt:19-20`; `contract-traceability.md:171`, `:238-239`; `inventory.md:167-168`, `:391`, `:453`; `phase05-report.md:180-193`, `:675`, `:699` (decision A.3) | Add a follow-up note to A.3 ("retired in …"). Do not rewrite the history. |
| OTS model docs | `layer4_reactive_control.md:88-94`; `scenarios_and_simulations.md`; `scenariomanagement_architecture.md`; `fsm_reengineering_plan.md`; `parameter_access_and_units.md:189`, `:234`, `:272-274` | Remove or annotate the sections |
| `DesiredSpeedLibrary.hoogendoorn*` | also used by `MergeScenario` | stays |

**Recommendation: retire, in one `[no behaviour change]` commit.** The live model never instantiates W99;
only a scenario that cannot currently complete a run does. The commit would amend Phase 0.5 decision A.3
and TaMA ADR-011 rather than contradict them: their premise was "alive", and "alive" meant only that the
class is instantiated. If Marvin wants a W99 comparison for the thesis later, a pinned tag
(`pre-w99-retirement`) keeps it reproducible at no maintenance cost.

---

### 2.6 T2-10 — Local runners and prototypes that are superseded

| Class | Lines | References | What is wrong | Recommendation |
|---|---|---|---|---|
| `demo/mirova/SimpleSimulation.java` | 359 | `.vscode/launch.json:62-64`; `troubleshooting_and_compilation.md` (about a *different* upstream tutorial class, `docs/08-tutorials` likewise) | Pre-scenariomanagement prototype. Uses OTS `IdmPlusFactory`, not `MirovaIdmPlus` (`:261`, `:263`), so no relaxation. Hard-coded `D:\` output path (`:354`); 31 commented-out code lines (the most in the tree). | **retire** (with its launch entry) |
| `RunMerge.java` | 34 | `.vscode/launch.json:69-71` | Output path `D:\...\bodegraven\lmrs\run_N` (`:20`) though it runs MiRoVA; loop `run < 1` printed as "of 10"; `setMergeShare` ineffective (T2-2) | **retire** or fold into one merge runner |
| `RunParallelMergeScenarios.java` | 71 | `launch.json:76-78`; `scenarios_and_simulations.md` | `setMergeShare(0.1)` silently ignored (T2-2); hard-coded path `:30` | **retire** or fix with T2-2 |
| `RunFreiburgParallel.java` | 134 | `FreiburgCombinationStudy.java:75` (Javadoc); `scenariomanagement_architecture.md:92`, `:241`; `README.md:33`; `mirova_parameters_documentation.md`; `scenarios_and_simulations.md`; `troubleshooting_and_compilation.md` | A hand-kept copy of an old baseline: T 1.00/1.30 and 0.90/1.20, damping 0.8, `RED_FAC` 0.60, speed gain in SI, cooperative deceleration −2.0/−0.5 against the study's −3.0/−1.0 (`:90`, `:102`). Output folder `freiburg_20250923_AnticipateMergeFix`; `@author Antigravity Agent`. `scenariomanagement_architecture.md:241` still recommends it as *the* way to run a scenario locally. `diss_mvb/.../dashboard_comparison.py:10` hard-codes a `freiburg_parallel` output of it. | **retire**; point the doc at `cluster/run_local_parallel.sh --study=…` or `RunFreiburgMergeWatch` |
| `RunFreiburgNord.java` | 56 | `launch.json:83-85`; `scenarios_and_simulations.md` | T 1.00/1.30 and speed gain 15/30 SI (`:44-47`), nothing else of the production set; hard-coded path `:29` | **retire**, or turn into "production cell with GUI" delegating to T2-5b |
| `RunFreiburgNordNoDemand.java` | 51 | `scenarios_and_simulations.md` only | Hard-coded path `:21` | **[no grep reference]** in code → **retire** |
| `TestReflection.java` | 204 | `troubleshooting_and_compilation.md`, `scenariomanagement_architecture.md` | A live, useful JAXB diagnostic, but named like a unit test and filed under `scenarios` | **keep; move and rename** (e.g. `tools/JaxbClassLoadingCheck`) |

Retiring the first six removes about 705 lines and the hard-coded `D:\` output paths in the runners.
`RunFreiburgMergeWatch` is **kept**: it is the documented interactive tool and feeds the FSM trace (but
see T2-1).

---

### 2.7 Hand-maintained things that tooling should own

Precedents: the campaign count went stale twice, and the OTS snapshot was hand-copied until a
`snapshotOts` task existed.

#### T2-8 — The SLURM array range

- **What:** `run_mirova.sbatch:60` hard-codes `#SBATCH --array=0-159`. The header table (`:32-35`) marks
  one row "<- current" by hand. `cluster/README.md` repeats per-campaign `--array=` values at `:185-190`,
  `:257`, `:267`, `:282`, `:549`, `:616`, `:673`, `:726`, `:835`, `:997`.
- **Why it matters:** the script *does* ask the study for `TOTAL_RUNS` (`:418-428`) and refuses a task
  whose indices are all past the end (`:491-495`). It does **not** notice a range that is too *small*:
  runs above `2·(max task + 1)` are never launched, and nothing reports it.
- **Recommendation:** in the batch script, compare `SLURM_ARRAY_TASK_MAX` against
  `ceil(TOTAL_RUNS / RUNS_PER_TASK) − 1` and fail task 0 on a mismatch. Or add
  `cluster/submit_study.sh`, which runs `--count` and passes `--array` on the command line; the `#SBATCH`
  line then becomes a documented placeholder. Either way, drop the "<- current" marker.

#### T2-8b — Date lists

- `cluster/dates.txt` (16 dates) and `dates_extension.txt` (7) restate the same seven dates by hand.
  `dates_calibration.txt` (3) is a subset.
- `cluster/README.md:41` still calls `dates.txt` a "**template — swap in the real 32 dates**", and `:190`
  sizes a 32-date campaign. The file holds 16 real dates. `cluster/demand/` holds 32 files, which are
  16 dates × {plain, `_wide`}, which is probably where "32" came from.
- **Recommendation:** fix the two README lines now. Longer term, one annotated date file with tags
  (`calibration`, `extension`) and a `--dates=cluster/dates.txt#extension` selector in
  `DateStudy.resolveDates` would remove the copies. That is optional; the lists change rarely.

#### T2-8c — Hard-coded workstation paths in code that runs on the cluster path

| Location | Path | Guarded? |
|---|---|---|
| `ScenarioManager.java:260`, `:262` | `mirova\venv\Scripts\python.exe`, `diss_mvb\scripts\simulation\ots\plot…` | skipped by `MIROVA_SKIP_POSTPROCESSING` |
| `ScenarioGenerator.java:869`, `:871` | same venv; `diss_mvb\scripts\evaluation\…` | skipped by `skipDemandPrep` / `MIROVA_SKIP_DEMAND_PREP` |
| `FreiburgNord.java:304` | `diss_mvb\scripts\evaluation\fielddata\…` | — |
| `cluster/generate_demand_csvs.ps1:30`, `generate_demand_csvs.py:22` | venv, `diss_mvb` root | local tool |
| `tools/run_merge_gui.sh:19` | `D:/otsrun` default, env-overridable | OK |
| six runners in T2-10 | `D:\Mitarbeitende\…\mirova\output\…` | — |

**Recommendation:** one `MIROVA_PYTHON` / `MIROVA_DISS_MVB` pair (system property or environment variable),
read in one place, with the current paths as the fallback. `[no behaviour change]` on this workstation.

#### T2-8d — Parameter documentation that duplicates Java defaults

- `docs/mirova/parametertable.tex` is **tool-owned** (`ParameterTableGenerator`, resolved from the real
  code path). This is the model to follow.
- `docs/mirova/mirova_parameters_documentation.md` (70 lines, last touched 2026-07-31) is a hand-written
  default table and is already wrong:
  - it lists `DSEARCH` as an LMRS threshold, although it was deleted and then restored for BC-9;
  - it names `followerDecelerationThreshold`/`egoDecelerationThreshold`, although the code has min/max
    pairs;
  - it lists a `TR` reaction time that MiRoVA does not read.

  **Recommendation: retire** in favour of `docs/decoupling/parameters.md` plus `parametertable.tex`, or
  generate it from `MirovaParameters` the way the `.tex` is generated.
- `docs/decoupling/parameters.md` and `default-parameters.md` carry default values by hand. They are
  records of a phase and acceptable as such, but see T2-6 for the places where they have already drifted.
- `FsmTraceHarness` HIGHWAY/MERGE inputs (`:113-119`) are stated explicitly on purpose (README). Fine.

---

### 2.8 Things a reader would take for current that are not

#### T2-6 — `docs/decoupling/parameters.md`

| Line | Claims | Actually |
|---|---|---|
| 106 | `bSi` — **unread** | read by `MandatoryLaneChangePattern.java:1585`; §5 of the same file corrects it, but the table was not updated |
| 107 | `bCritSi` exists, unread | deleted in `3b73c7ab7` |
| 128-131 | MergeScenario and SimpleHighwayScenario set TMIN/TMAX on MiRoVA factories | fixed in `3b73c7ab7` (T2-0) |
| 141 | 28 named distributions plus `trucks` | 27 methods including `trucks` |
| 162-176 (§5 "declared but never read") | lists DSEARCH, aScale, STANDSTILL_SPEED_THRESHOLD, MANDATORY_…LOOK_AHEAD, VCRIT, bCritSi as current | all deleted (listed again at 177-188) |
| 174 | FAR_ANTICIPATION_ENABLED "set by six studies" | 3 files (T2-3) |
| 180 | DSEARCH deleted | restored by `3b3a5ad43` for BC-9 and read via `MirovaTacticalPlanner.java:468` |
| 44, 171 | CONGESTED_LANE_CHANGE_DURATION is read from commented code in `ExecuteLaneChangeState` | the commented read is in `MandatoryLaneChangePattern.java:2524-2528` |

**Recommendation:** one docs commit that brings §3/§5 to the post-A.7, post-BC-9 state.

#### Model docs describing deleted classes as live

| Doc | Stale content | Code state |
|---|---|---|
| `docs/mirova/layer2_desire.md:123-126` | §5 `CongestionIncentive` "Currently Disabled", links the source file | class deleted (`8cea03479`) |
| `docs/mirova/layer3_decision_intention.md:104` | `CongestedMergeState` "Dispatcher below 15 km/h" | deleted in `148062f6d` (2026-09-08); doc last touched 2026-08-27 |
| `docs/mirova/mandatory_lane_change_pattern.md:79-109`, `:126`, `:220`, `:305`, `:386` | state diagram and table built around `CongestedMergeState`, incl. `RECOVERY_SPEED_THRESHOLD` | same |
| `docs/mirova/README.md:24` | lists `CompositeActionState` among key classes | does not exist; it is a *planned* name in `fsm_reengineering_plan.md` |
| `docs/mirova/scenariomanagement_architecture.md:6` | `ContextManager`, `KnowledgeChunk` | no such classes; the planner has `getKnowledgeChunks()` returning `DesireIncentive` |
| `docs/mirova/scenariomanagement_architecture.md:241` | "run a single scenario locally: edit and run `RunFreiburgParallel`" | superseded (T2-10) |
| `docs/mirova/scenarios_and_simulations.md` (604 lines, 2026-08-20) | module tree without `StudyRegistry`, `DateStudy`, any `*Study`, `fsmtrace`; calls `MergeScenario` "abstract" | 22 studies exist; `MergeScenario` is concrete |
| `docs/mirova/layer4_reactive_control.md:88` | W99 "implemented and calibrated" | see T2-7 |
| `CLAUDE.md:47-48` | layer table names `ContextManager`, `KnowledgeChunk` | as above |
| `CLAUDE.md:104-109` (§6) | `checkContext`/`AnticipateMergeState` call `getDistanceToLaneChangeExtendedLookahead()` and the raise "is very likely inert" | method deleted under decision A.1 (`MandatoryLaneChangePattern.java:674` comment; `phase05-report.md:697`) |

`GapSearchPattern`/`AccelToGap` appear only in `CLAUDE.md:14`, already flagged there as historical. No doc
or code uses them. **Nothing to do.**

**Recommendation:** update `layer2`, `layer3`, `mandatory_lane_change_pattern.md` and `README.md` to the
post-`148062f6d` state, and correct `CLAUDE.md` §3 and §6 (Marvin's file). Retire or rewrite
`scenarios_and_simulations.md` against `scenariomanagement_architecture.md`, which is newer (2026-09-11)
and covers the same ground.

#### Point-in-time reports without a "historical" marker

`docs/mirova/`: `performance_profile_2026-08-20.md` (437), `performance_profile_2026-08-21_full_day.md` (684),
`djunits_hashcode_finding.md` (341), `djunits_patch_experiment.md` (271), `calibration_status_briefing.md`
(463), `congested_branch_review_request.md` (361). They are superseded respectively by
`performance_investigation_synthesis.md`, and by `parameter_sensitivity.md` and
`recalibration-inventory.md`. Two of them cite the now-deleted `ValueUtil` and `MirovaPositionUtil`.
**Recommendation: mark as historical** (a dated banner, or a `docs/mirova/archive/` folder) and keep them.
They are the evidence behind decisions.

`cluster/README.md` (1 022 lines) is about 20 % runbook and 80 % campaign log (from about `:380` on).
**Recommendation:** split it into `README.md` (runbook) and `CAMPAIGNS.md` (history), so the runbook's
stale lines (T2-8b) are not buried.

#### Commented-out code

A heuristic count (comment lines ending in `;`, `{` or `}`) finds 81 lines in `demo/mirova` and 34 in
`road/.../mirova`. Most sit in `SimpleSimulation` (31), `MergeScenario` (29) and
`RunSimpleHighwayScenarios` (11), all covered above. Several in `ots-road` are *documented* retentions:
`MirovaTacticalPlannerFactory.java:213` (with its measured reason) and the
`congestedLaneChangeDuration` block kept by decision. **Recommendation:** remove the blocks in the three
demo files together with T2-0/T2-7/T2-10. Leave the documented `ots-road` ones.

#### Stray files at the repository root

| File | Size | Last commit | Recommendation |
|---|---|---|---|
| `temp_import_fix.txt` | 99 KB, UTF-16, 1 105 lines of a Java source dump | `e38752f48`, 2026-05-13 | **[verified unused]** → retire |
| `parallelization_feasibility_report.md` | 24 KB | `54d8efe2b`, 2026-08-06 | superseded by `performance_investigation_synthesis.md` → move to `docs/mirova/archive/` |

---

### 2.9 Suggested order

1. **Docs only:** T2-6, the stale model docs, `cluster/README.md:41/190`, and the CLAUDE.md notes.
   No risk.
2. **`[no behaviour change]` code:** T2-4 (fail on unknown key), T2-5 (dead accessors), T2-0 (LMRS/W99
   leftovers in `MergeScenario`), T2-3 (drop the `farAnticipationEnabled` writes), the root stray files.
3. **T2-5b:** consolidate the production cell, proven by a byte-identical `parametertable.tex` and the
   Phase 0.5 reference recording. Then mark the campaigns historical (T2-6a).
4. **T2-7 and T2-10:** retire the W99 family, `SimpleHighwayScenario`, and the superseded runners. Amend
   A.3 and TaMA ADR-011.
5. **Decisions with a behaviour or trace consequence:** T2-1 (merge-watch speed gain → re-record
   `freiburg-merge` or relabel) and T2-2 (MergeScenario reads `params`). Each gets its own commit, labelled
   `[behaviour change]` if a trace moves.
6. **Tooling:** T2-8 (array guard or submit wrapper) and T2-8c (path configuration).
