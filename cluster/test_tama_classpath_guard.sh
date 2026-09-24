#!/usr/bin/env bash
#
# Tests the two conditions that refuse a TaMA run whose classpath holds no bundle.
#
# The failure they exist to prevent is expensive and quiet: every run builds its network, dies at its
# first GTU with "matches 0 tactical planner provider(s) ... available: []", and the array reports
# finished tasks. On 2026-09-24 that cost an 800-run submission. So the cases that matter are the
# ones where the guard must ABORT - a guard that only passes is indistinguishable from no guard.
#
# The conditions live in build_for_cluster.sh and run_mirova.sbatch. Their logic is restated here and
# checked in both directions; the restatement is deliberate, so that a change to either script that
# drops the guard leaves this test passing on its own copy and the grep below catches it instead.
#
# Usage:  cluster/test_tama_classpath_guard.sh      exit 0 when every case passes
#
# Copyright (c) 2026 Marvin Baumann / KIT.
# BSD-style license. See the OpenTrafficSim License.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT
PASS=0
FAIL=0

check() {
    local what="$1" expected="$2" actual="$3"
    if [ "${expected}" = "${actual}" ]; then
        PASS=$((PASS + 1))
    else
        FAIL=$((FAIL + 1))
        echo "FAIL: ${what}: expected '${expected}', got '${actual}'" >&2
    fi
}

# --- the build_for_cluster.sh condition ----------------------------------------------------------
# MIROVA_TAMA=1 and no bundle in the generated classpath must abort.
build_guard() {
    local tama="$1" cp_file="$2"
    bash -c '
        set -u
        MIROVA_TAMA="$1"; CP_FILE="$2"
        if [ "${MIROVA_TAMA:-0}" = "1" ] && ! grep -q "tama-ots-bundle" "${CP_FILE}"; then
            exit 1
        fi
        exit 0' _ "${tama}" "${cp_file}"
    echo "$?"
}

echo "org/other/thing.jar:/another/lib.jar" > "${TMP}/cp-without.txt"
echo "org/other/thing.jar:/m2/edu/kit/ifv/tama/tama-ots-bundle/0.1.0-SNAPSHOT/tama-ots-bundle-0.1.0-SNAPSHOT.jar" \
    > "${TMP}/cp-with.txt"

check "build: TaMA asked for, bundle missing -> abort" "1" "$(build_guard 1 "${TMP}/cp-without.txt")"
check "build: TaMA asked for, bundle present -> pass" "0" "$(build_guard 1 "${TMP}/cp-with.txt")"
check "build: TaMA not asked for, bundle missing -> pass" "0" "$(build_guard 0 "${TMP}/cp-without.txt")"

# --- the run_mirova.sbatch condition -------------------------------------------------------------
# The study's own manifest decides whether the bundle is needed, not the study's name.
run_guard() {
    local manifest="$1" cp_file="$2"
    bash -c '
        set -u
        PLANNER_PROBE="$1"; CP_FILE="$2"
        if [ ! -s "${PLANNER_PROBE}" ]; then exit 2; fi
        if grep -q "tacticalPlanner=tama" "${PLANNER_PROBE}" && ! grep -q "tama-ots-bundle" "${CP_FILE}"; then
            exit 2
        fi
        exit 0' _ "${manifest}" "${cp_file}"
    echo "$?"
}

echo "ScenarioParameters{seed=42, tacticalPlanner=tama, car.T=1.0 s}" > "${TMP}/manifest-tama.txt"
echo "ScenarioParameters{seed=42, tacticalPlanner=mirova, car.T=1.0 s}" > "${TMP}/manifest-mirova.txt"
: > "${TMP}/manifest-empty.txt"

check "run: study wants tama, bundle missing -> abort" "2" \
    "$(run_guard "${TMP}/manifest-tama.txt" "${TMP}/cp-without.txt")"
check "run: study wants tama, bundle present -> pass" "0" \
    "$(run_guard "${TMP}/manifest-tama.txt" "${TMP}/cp-with.txt")"
check "run: study wants mirova, bundle missing -> pass" "0" \
    "$(run_guard "${TMP}/manifest-mirova.txt" "${TMP}/cp-without.txt")"
check "run: manifest could not be written -> abort" "2" \
    "$(run_guard "${TMP}/manifest-empty.txt" "${TMP}/cp-with.txt")"
check "run: manifest absent -> abort" "2" \
    "$(run_guard "${TMP}/does-not-exist.txt" "${TMP}/cp-with.txt")"

# --- the guards are still in the scripts this test restates --------------------------------------
for script in build_for_cluster.sh run_mirova.sbatch; do
    if grep -q "tama-ots-bundle" "${HERE}/${script}"; then
        PASS=$((PASS + 1))
    else
        FAIL=$((FAIL + 1))
        echo "FAIL: ${script} no longer mentions tama-ots-bundle; the guard this test restates is gone" >&2
    fi
done

echo "passed ${PASS}, failed ${FAIL}"
[ "${FAIL}" -eq 0 ]
