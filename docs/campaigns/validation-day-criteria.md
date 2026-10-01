# Choosing a further out-of-sample day: the criteria, fixed before the query

Step 1 of C.2 (owner, "Harness-Lock: meine Eskalation war falsch", part C). This file is committed **before** the
query in `validation-day-query.sql` exists and before anyone has run it; the order of the two commits on this branch
is the record of that. Until a day chosen by these rules has been run, option 1 holds: out-of-sample is claimed only
for the TaMA validation run's frozen set, on six clean days, 2025-09-25 qualified
(TaMA `docs/manual-sources/04-scenarios-and-studies.md`, "The reserved days").

The rules below decide which days qualify. They do not choose among qualifying days by how a day looks; a fixed
rule does that (section 4). No model result enters anywhere: not a simulated metric, not a fit, not a comparison
with a run. Every threshold is a number written here, and the query reads no other.

## 1. The population

All days of the field table `detektoren_autobahn_freiburg` with rows for `knotenpunkt = 'AS Freiburg-Nord'`,
`fahrtrichtung = 'Karlsruhe'`, the cross-section and direction every study so far has used
(`diss_mvb/scripts/simulation/ots/io/empirical.py`). The table holds one row per minute and position, with
per-lane flow `q_kfz_fsN` (veh/h), speed `v_kfz_fsN` (km/h) and `pruefziffer_fsN`, the number of source minutes
in the row (0 for an outage, 1 normally) - written by `diss_mvb/.../detectors/io/import_autobahn.py` at one-minute
aggregation.

## 2. Exclusion: every day that appears in a date list

A day is excluded if it appears in any date list - and, more widely than that, if its date appears anywhere in the
content of any commit of the fork (all branches), `diss_mvb` or TaMA, in the working trees of the fork's main
checkout and of `tama-workspace`, or in any commit of the earlier `mirova` and `mirova_main` repositories. Searched
on 2026-10-01 for every `2025-MM-DD`. Wider than "date list" on purpose: a day that was ever written down may have
been looked at, and excluding one too many costs a candidate, while excluding one too few costs the claim.

From the fork, `diss_mvb`, TaMA and the working trees (36; the sixteen study days among them):

```
2025-01-27 2025-05-26 2025-06-11 2025-07-09 2025-09-15 2025-09-16 2025-09-17 2025-09-18 2025-09-19 2025-09-20
2025-09-21 2025-09-22 2025-09-23 2025-09-24 2025-09-25 2025-09-26 2025-10-01 2025-10-02 2025-10-07 2025-10-08
2025-10-09 2025-10-10 2025-10-13 2025-10-14 2025-10-15 2025-10-16 2025-10-17 2025-10-21 2025-10-22 2025-10-23
2025-10-24 2025-10-26 2025-10-27 2025-10-29 2025-10-30 2025-10-31
```

From `mirova` and `mirova_main` only (121; another project's dates, kept because the rule is mechanical):

```
2025-01-02 2025-01-03 2025-01-06 2025-01-07 2025-01-08 2025-01-09 2025-01-10 2025-01-12 2025-01-13 2025-01-14
2025-01-15 2025-01-16 2025-01-17 2025-01-20 2025-01-21 2025-01-22 2025-01-23 2025-01-24 2025-01-25 2025-01-28
2025-01-29 2025-01-30 2025-01-31 2025-02-01 2025-02-02 2025-02-03 2025-02-04 2025-02-05 2025-02-06 2025-02-07
2025-02-08 2025-02-09 2025-02-10 2025-02-11 2025-02-12 2025-02-13 2025-02-14 2025-02-15 2025-02-17 2025-02-18
2025-02-19 2025-02-20 2025-02-21 2025-02-23 2025-02-24 2025-02-25 2025-02-26 2025-02-27 2025-02-28 2025-03-02
2025-03-03 2025-03-04 2025-03-05 2025-03-06 2025-03-07 2025-03-08 2025-03-09 2025-03-10 2025-03-11 2025-03-12
2025-03-13 2025-03-14 2025-03-15 2025-03-16 2025-03-17 2025-03-18 2025-03-19 2025-03-20 2025-03-21 2025-03-23
2025-03-24 2025-03-25 2025-03-26 2025-03-27 2025-03-28 2025-03-29 2025-03-30 2025-03-31 2025-04-01 2025-04-02
2025-04-03 2025-04-04 2025-04-05 2025-04-06 2025-04-07 2025-04-08 2025-04-09 2025-04-10 2025-04-11 2025-04-13
2025-04-14 2025-04-15 2025-04-16 2025-04-17 2025-04-18 2025-04-22 2025-04-23 2025-04-24 2025-04-25 2025-04-27
2025-04-28 2025-04-29 2025-05-02 2025-05-04 2025-05-05 2025-05-06 2025-05-07 2025-05-08 2025-05-09 2025-05-10
2025-05-13 2025-05-15 2025-05-16 2025-05-17 2025-05-19 2025-05-20 2025-05-21 2025-05-22 2025-05-23 2025-05-24
2025-05-25
```

**Not covered**, and to be checked by the owner before running: date lists that exist only in the cluster
workspace, or only on another machine, and were never committed. A day found there joins this list - by a commit,
before the query runs.

## 3. The criteria, each a threshold

The window is 13:00 to 22:00 as stored in `datum`, the window every simulation of this site runs
(`empirical.py`): 540 minutes, 108 five-minute intervals, 13:00 to 21:55. A *lane* of a position is a lane with
at least one measured minute (`pruefziffer_fsN >= 1`) anywhere in the table at that position, so a lane missing
for a whole day counts against the day rather than disappearing from it.

| | Criterion | Threshold |
|---|---|---|
| K0 | **Working day**: Monday to Friday, not a public holiday in Baden-Württemberg (calendar 2024 to 2026 in the query; a day outside it fails) | must hold |
| K1 | **Completeness**, at the Hauptfahrbahn and at the Einfahrt separately: a five-minute interval is complete when every lane of the position has at least one measured minute in it | **0** incomplete intervals of 108, at each position |
| K2 | **Coverage**, at each position separately: measured lane-minutes in the window over (lanes x 540) | **>= 0.95** at each position |
| K3 | **Breakdown** at the Hauptfahrbahn, as the evaluation detects it (`diss_mvb/.../analytics/breakdown_events.py`, `_find_episodes`), on five-minute aggregates formed as `fetch.aggregate_detectors` forms them | **>= 1** episode |

K3 in full, so that the query can be checked against it:
- Five-minute flow per lane: the sum of the lane's flow over its measured minutes, divided by the number of measured
  minutes. Five-minute speed per lane: flow-weighted over the minutes with flow above zero and a speed. Speed of the
  cross-section, `v_kfz_gesamt`: the lane speeds weighted by lane flow.
- Intervals from **13:45** (the evaluation's 45-minute warm-up, `WARMUP_SECONDS = 2700`) to 21:55, in time order.
- `v_crit` = **86.2 km/h**, the study-wide threshold (`docs/mirova/calibration_status_briefing.md` section 2), fixed
  here. It is not refitted: a threshold fitted on the candidate days would move with the days it is asked to judge.
- An episode is a maximal run of at least **3** consecutive intervals below `v_crit`, whose preceding interval is at
  or above `v_crit` and at least **5 km/h** faster than the run's first interval.
- Checked against the field snapshot (`diss_mvb/data/field_snapshot`, 2026-09-23) before this was committed: the
  definition finds no episode on 2025-09-22 and at least one on each of the fifteen other study days, as
  `docs/mirova/parameter_sensitivity.md` section 2 records for the nine. Only excluded days were looked at.

**Two of these are choices, and the owner's to strike before the query runs** - a struck criterion is struck by a
commit, before running, like any other change here:
- **K0 goes beyond the three criteria the brief named.** It is proposed because all sixteen study days are working
  days, and a weekend or a holiday is another demand.
- **K3 requires a breakdown.** Fifteen of the sixteen days have one, and the measures the validation reports most
  (pre-breakdown flow, discharge, capacity drop) exist only on such a day. A day without one would test the other
  branch, as 2025-09-22 does; requiring one is a choice of what the further day is for.

## 4. From the set to a day

The query returns **every** day of the population with its exclusion flag and each criterion's value and verdict,
not only the days that qualify, so that the set can be audited. The qualifying set is the days that are not
excluded and meet K0 to K3.

**If one day is wanted, it is the chronologically first day of the set; if n are wanted, the n first.** No other
ordering is admissible: not by flow, by severity of the breakdown, by resemblance to the study days, nor by anything
a model produced. If the set is empty, that is the result, and it is reported; the thresholds are not loosened to
fill it.

## 5. What would undo this

- Changing a threshold, the window, the exclusion list or the rule of section 4 after the query has been run. A
  change after a run is committed with its reason, the first run's result is reported alongside the second, and the
  day it produces is out-of-sample only if the change cannot have been informed by the first result.
- Running the query, or any query on these days, before the commit that holds this file.
- Reading a model result for a candidate day before the choice is made.
