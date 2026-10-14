#!/usr/bin/env bash
#
# Walltime and memory of a finished campaign, read from SLURM's accounting and from the run logs.
#
# The cluster sizing in cluster/README.md (walltime, --mem-per-cpu, MIROVA_JAVA_HEAP, core-hours) rests on
# a per-run duration that has never been measured on the partition since the performance work. This script
# turns one array job into that measurement:
#
#   - from sacct, per array task: state, elapsed time, time limit, and MaxRSS of the batch step. A task runs
#     two simulations concurrently, so its elapsed time is the SLOWER of its two runs, and its MaxRSS covers
#     both JVMs (the batch step is one process tree). Works for any job sacct still remembers, including
#     campaigns that ran before this script existed.
#   - from the run logs, per run: the "[timing] run <i> wall_s=<n>" line that run_mirova.sbatch appends since
#     it was added. Absent for older jobs; then only the task figures are reported.
#
# Usage (on the cluster login node, from the workspace the job was submitted from):
#
#   cluster/walltime_report.sh <array-job-id> [<logs-dir>]
#
#   <logs-dir> defaults to ./logs, where run_mirova.sbatch writes mirova_<job>_<task>_run<i>.log.
#
# Output is plain text on stdout; redirect it into the campaign's folder to keep it with the results.
#
# Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved.
# BSD-style license. See OpenTrafficSim License.

set -uo pipefail

CLUSTER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GUARD_LIB="${CLUSTER_DIR}/guard.sh"
[ -f "${GUARD_LIB}" ] || { echo "REFUSING TO START: ${GUARD_LIB} not found" >&2; exit 2; }
# shellcheck source=guard.sh
source "${GUARD_LIB}"
declare -F guard > /dev/null || { echo "REFUSING TO START: no guard functions" >&2; exit 2; }

JOB="${1:-}"
LOGS="${2:-logs}"
guard_nonempty "an array job id as the first argument" "${JOB}"
guard "the job id is a number" test -n "$(printf '%s' "${JOB}" | grep -E '^[0-9]+$')"
guard "sacct is available" bash -c "command -v sacct > /dev/null"

SACCT_RC=0
SACCT_OUT="$(sacct -j "${JOB}" -n -P --units=M \
    --format=JobID,State,Elapsed,ElapsedRaw,Timelimit,MaxRSS,ReqMem,NodeList)" || SACCT_RC=$?
guard "sacct answered for job ${JOB}" test "${SACCT_RC}" -eq 0
guard_nonempty "sacct still has records of job ${JOB}" "${SACCT_OUT}"

echo "Campaign walltime report - array job ${JOB}"
echo "Generated $(date -Iseconds) on $(hostname)"
echo

# Per task: the main row (<job>_<task>) carries state and elapsed; the .batch step carries MaxRSS.
printf '%s\n' "${SACCT_OUT}" | awk -F'|' '
    function median(a, n,   i, j, t) {
        for (i = 2; i <= n; i++) { t = a[i]; for (j = i - 1; j >= 1 && a[j] > t; j--) a[j + 1] = a[j]; a[j + 1] = t }
        return (n % 2) ? a[(n + 1) / 2] : (a[n / 2] + a[n / 2 + 1]) / 2
    }
    function hm(s) { return sprintf("%d:%02d", int(s / 3600), int((s % 3600) / 60)) }
    $1 ~ /^[0-9]+_[0-9]+$/ {
        split($1, p, "_"); t = p[2]
        state[t] = $2; el[t] = $4; limit = $5; tasks++
        split($2, s, " "); states[s[1]]++
    }
    $1 ~ /^[0-9]+_[0-9]+\.batch$/ {
        split($1, p, "[_.]"); rss = $6; sub(/M$/, "", rss); mem[p[2]] = rss + 0
    }
    END {
        print "Tasks: " tasks "   time limit: " limit
        printf "States:"; for (k in states) printf "  %s=%d", k, states[k]; print ""
        n = 0; m = 0
        for (t in el) if (state[t] == "COMPLETED") { n++; e[n] = el[t]; if (t in mem) { m++; r[m] = mem[t] } }
        if (n == 0) { print "No COMPLETED task - nothing to summarise."; exit }
        emin = e[1]; emax = e[1]; for (i = 1; i <= n; i++) { if (e[i] < emin) emin = e[i]; if (e[i] > emax) emax = e[i] }
        print ""
        print "COMPLETED tasks (" n "), elapsed = the slower of the two runs of a task:"
        printf "  min %s   median %s   max %s   (h:mm)\n", hm(emin), hm(median(e, n)), hm(emax)
        if (m > 0) {
            rmax = r[1]; for (i = 1; i <= m; i++) if (r[i] > rmax) rmax = r[i]
            printf "  MaxRSS of the batch step (both JVMs): median %.0f MB   max %.0f MB\n", median(r, m), rmax
        }
        for (t in el) all += el[t]
        printf "  core-hours charged overall (all states, 2 cores per task): %.1f\n", 2 * all / 3600
    }'

# Tasks that did not complete, listed, because a TIMEOUT or OUT_OF_MEMORY decides the next sizing.
FAILED="$(printf '%s\n' "${SACCT_OUT}" | awk -F'|' '$1 ~ /^[0-9]+_[0-9]+$/ && $2 != "COMPLETED" { print "  " $1 "  " $2 "  " $3 }')"
if [ -n "${FAILED}" ]; then
    echo
    echo "Tasks not COMPLETED:"
    printf '%s\n' "${FAILED}"
fi

echo
# Per run, from the [timing] lines. Absent for jobs submitted before run_mirova.sbatch wrote them.
shopt -s nullglob
RUN_LOGS=( "${LOGS}"/mirova_"${JOB}"_*_run*.log )
if [ "${#RUN_LOGS[@]}" -eq 0 ]; then
    echo "Per run: no run logs for job ${JOB} under ${LOGS}/ - task figures only."
    exit 0
fi
TIMING="$(grep -h '^\[timing\] run ' "${RUN_LOGS[@]}")"
if [ -z "${TIMING}" ]; then
    echo "Per run: ${#RUN_LOGS[@]} run logs, none with a [timing] line (job predates it) - task figures only."
    exit 0
fi
printf '%s\n' "${TIMING}" | awk '
    function median(a, n,   i, j, t) {
        for (i = 2; i <= n; i++) { t = a[i]; for (j = i - 1; j >= 1 && a[j] > t; j--) a[j + 1] = a[j]; a[j + 1] = t }
        return (n % 2) ? a[(n + 1) / 2] : (a[n / 2] + a[n / 2 + 1]) / 2
    }
    {
        for (i = 1; i <= NF; i++) { if ($i ~ /^wall_s=/) { sub(/wall_s=/, "", $i); w = $i + 0 } if ($i ~ /^exit=/) { sub(/exit=/, "", $i); x = $i } }
        if (x == "0") { n++; v[n] = w; if (n == 1 || w < lo) lo = w; if (n == 1 || w > hi) hi = w } else bad++
    }
    END {
        printf "Per run, exit 0 (%d runs, %d others): min %.1f min   median %.1f min   max %.1f min\n",
            n, bad, lo / 60, median(v, n) / 60, hi / 60
    }'
