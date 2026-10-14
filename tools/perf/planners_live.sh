#!/usr/bin/env bash
#
# TaMA against MiRoVA's tactical layer on the same OTS: wall clock, and one JFR profile per planner.
#
# Question 4 of docs/mirova/performance_improvements.md: how much does TaMA save, and does the saving sit in
# the tactical layer itself (a lean implementation) or elsewhere? The timing gives the direction and size, the
# profile says where it comes from.
#
# Uses TaMA's own runner, :tama-ots:runFreiburgNordLive, with the window of the stage 11 comparison (P7 in the
# ledger) so the two are directly comparable: 2025-09-23 15:00-16:30, 90 simulated minutes containing the
# breakdown and the jam, coreset cell, replication 0, no trajectories, not instrumented. The runner reports the
# simulation's own wall clock (JVM start and Gradle excluded) in performance.properties.
#
#   timing:  REPS rounds, each one TaMA and one MiRoVA run, the order alternating per round, one run at a time.
#   profile: one run per planner with JFR on (JAVA_TOOL_OPTIONS, recording into a directory so every JVM of the
#            Gradle invocation gets its own file; the largest is the simulation), then the diss_mvb analysis.
#
# The two planners drive slightly different traffic; each run's vehicle count and congested lane-minutes at
# det_L3a are reported beside its time, because a heavier jam costs time on its own.
#
# Usage:
#   tools/perf/planners_live.sh <tama-repo> <ots-worktree> <out-dir> [reps=3] [--no-profile]
#
# Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved.
# BSD-style license. See OpenTrafficSim License.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GUARD_LIB="${HERE}/cluster/guard.sh"
[ -f "${GUARD_LIB}" ] || { echo "REFUSING TO START: ${GUARD_LIB} not found" >&2; exit 2; }
# shellcheck source=../../cluster/guard.sh
source "${GUARD_LIB}"
declare -F guard > /dev/null || { echo "REFUSING TO START: no guard functions" >&2; exit 2; }

TAMA="${1:-}"
OTS="${2:-}"
OUT="${3:-}"
REPS="${4:-3}"
PROFILE=1
[ "${5:-}" = "--no-profile" ] && PROFILE=0

guard_nonempty "the TaMA repository as the first argument" "${TAMA}"
guard_nonempty "an OTS worktree as the second argument" "${OTS}"
guard_nonempty "an output directory as the third argument" "${OUT}"
guard "the TaMA repository has a Gradle wrapper" test -x "${TAMA}/gradlew"
TAMA="$(cd "${TAMA}" && pwd)"
OTS="$(cd "${OTS}" && pwd)"
guard "the OTS worktree is a git checkout" git -C "${OTS}" rev-parse --verify HEAD
guard_empty "no local changes to tracked files in ${OTS}" "$(git -C "${OTS}" status --porcelain --untracked-files=no)"
guard "ots-demo is compiled in ${OTS}" test -d "${OTS}/ots-demo/target/classes"
guard "the repetition count is a positive number" test "${REPS}" -ge 1

ANALYZER="${MIROVA_JFR_ANALYZER:-${HERE}/../diss_mvb/scripts/simulation/ots/analyze_jfr_profile.py}"
if [ "${PROFILE}" -eq 1 ]; then
    guard "the JFR analyser exists" test -f "${ANALYZER}"
fi

mkdir -p "${OUT}"
OUT="$(cd "${OUT}" && pwd)"
# Gradle under Git Bash needs the Windows profile to find ~/.m2 and its own caches.
export HOME="${USERPROFILE_POSIX:-/c/Users/${USERNAME:-$USER}}"
COMMON=(--console=plain -Ptama.ots.repo="${OTS}" -Ptama.live.date=2025-09-23 -Ptama.live.from=15:00:00
        -Ptama.live.to=16:30:00 -Ptama.live.minutes=90 -Ptama.live.replication=0
        -Ptama.live.trajectories=false -Ptama.live.instrument=false)

{
    echo "ots=$(git -C "${OTS}" rev-parse HEAD)"
    echo "tama=$(git -C "${TAMA}" rev-parse HEAD)"
    echo "tamaDirty=$(git -C "${TAMA}" status --porcelain | tr '\n' ';')"
    echo "window=2025-09-23 15:00-16:30, 90 min, coreset, replication 0"
} > "${OUT}/provenance.txt"

# A path as the Windows programs (python, java) read it. Git Bash converts paths in command arguments on its own,
# but not inside an environment variable or a string of Python source.
win() { cygpath -w "$1" 2>/dev/null || printf '%s' "$1"; }

# Vehicles past det_L3a and congested lane-minutes there, from a run's detector output.
traffic() {
    python -c "import sys; sys.path.insert(0, r'$(win "${HERE}/tools/perf")'); from time_cells import traffic; from pathlib import Path; v, c = traffic(Path(r'$(win "$1")')); print(f'vehicles={v} congestedLaneMin={c}')"
}

run() { # name planner [extra env assignment]
    local name=$1 planner=$2
    local dir="${OUT}/${name}"
    guard_empty "no earlier run at ${dir}" "$(ls -A "${dir}" 2>/dev/null)"
    echo "[planners] ${name} start $(date +%H:%M:%S)"
    ( cd "${TAMA}" && ./gradlew :tama-ots:runFreiburgNordLive "${COMMON[@]}" "${@:3}" \
        -Ptama.live.planner="${planner}" -Ptama.live.out="${dir}" ) > "${OUT}/${name}.log" 2>&1
    guard "the run ${name} exited 0 (log: ${OUT}/${name}.log)" test $? -eq 0
    guard "the run ${name} reported its wall clock" test -s "${dir}/performance.properties"
    local line tr_out tr_rc=0
    tr_out="$(traffic "${dir}")" || tr_rc=$?
    guard "the traffic of ${name} was read from its detector output" test "${tr_rc}" -eq 0
    guard_nonempty "a traffic figure for ${name}" "$(printf '%s' "${tr_out}" | grep '^vehicles=[0-9]')"
    line="${name} $(tr '\n' ' ' < "${dir}/performance.properties") ${tr_out}"
    echo "[planners] ${line}"
    echo "${line}" >> "${OUT}/timings.txt"
}

for (( i = 1; i <= REPS; i++ )); do
    if (( i % 2 )); then run "tama-${i}" tama; run "mirova-${i}" mirova
    else run "mirova-${i}" mirova; run "tama-${i}" tama; fi
done

SUM_RC=0
python - "$(win "${OUT}/timings.txt")" > "${OUT}/summary.md" <<'EOF' || SUM_RC=$?
import re, statistics, sys
rows = [l.split() for l in open(sys.argv[1]) if l.strip()]
by = {}
for r in rows:
    kv = dict(x.split("=", 1) for x in r[1:] if "=" in x)
    by.setdefault(r[0].rsplit("-", 1)[0], []).append((float(kv["wallSeconds"]), kv.get("vehicles"), kv.get("congestedLaneMin")))
print("# TaMA vs MiRoVA, 2025-09-23 15:00-16:30\n")
print("| planner | n | min (s) | median (s) | max (s) | vehicles det_L3a | congested lane-min |")
print("|---|---|---|---|---|---|---|")
for p, v in by.items():
    w = [x[0] for x in v]
    print(f"| {p} | {len(w)} | {min(w):.1f} | {statistics.median(w):.1f} | {max(w):.1f} | "
          f"{', '.join(sorted({x[1] for x in v}))} | {', '.join(sorted({x[2] for x in v}))} |")
t, m = [x[0] for x in by["tama"]], [x[0] for x in by["mirova"]]
r = statistics.median(t) / statistics.median(m)
print(f"\nTaMA needs {100 * r:.1f} % of MiRoVA's wall clock ({100 * (1 - r):.1f} % less, x{1 / r:.2f}); "
      f"{'no overlap' if max(t) < min(m) else 'ranges OVERLAP'}.")
EOF
guard "the timing summary was written" test "${SUM_RC}" -eq 0
cat "${OUT}/summary.md"

if [ "${PROFILE}" -eq 1 ]; then
    for planner in tama mirova; do
        JFR_DIR="${OUT}/jfr-${planner}"
        mkdir -p "${JFR_DIR}"
        # --no-daemon: the recording flags reach every JVM of this invocation, and a daemon would outlive it
        # still recording. A directory as filename gives each JVM its own file.
        JAVA_TOOL_OPTIONS="-XX:StartFlightRecording=filename=$(win "${JFR_DIR}"),settings=profile,dumponexit=true -XX:FlightRecorderOptions=stackdepth=128" \
            run "profile-${planner}" "${planner}" --no-daemon
        JFR="$(ls -S "${JFR_DIR}"/*.jfr 2>/dev/null | head -1)"
        guard_nonempty "a recording in ${JFR_DIR}" "${JFR}"
        guard "the ${planner} profile was analysed" python "$(win "${ANALYZER}")" "$(win "${JFR}")" \
            --out "$(win "${OUT}/profile_${planner}.md")" \
            --scenario-note "runFreiburgNordLive, 2025-09-23 15:00-16:30, coreset, replication 0, planner ${planner}, JFR on"
    done
fi
echo "[planners] done: ${OUT}"
