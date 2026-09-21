#!/usr/bin/env bash
#
# Tests cluster/guard.sh. A guard that does not abort is the defect it exists to prevent, so the cases that
# matter are the failing ones: each must end the subshell with GUARD_EXIT and say what it expected.
#
# Usage:  cluster/test_guard.sh        exit 0 when every case passes
#
# Copyright (c) 2026 Marvin Baumann / KIT.

set -uo pipefail

GUARD="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/guard.sh"
PASS=0
FAIL=0

# Runs a snippet in a subshell with guard.sh sourced; echoes "rc=<exit> <output>".
run() {
    local body="$1" out rc
    out="$(bash -c "source '${GUARD}'; ${body}" 2>&1)"
    rc=$?
    printf 'rc=%s %s' "${rc}" "${out}"
}

check() {
    local name="$1" want_rc="$2" want_text="$3" body="$4" result rc
    result="$(run "${body}")"
    rc="${result#rc=}"; rc="${rc%% *}"
    if [ "${rc}" != "${want_rc}" ]; then
        echo "FAIL ${name}: exit ${rc}, expected ${want_rc}"; echo "     ${result}"; FAIL=$((FAIL + 1)); return
    fi
    if [ -n "${want_text}" ] && ! printf '%s' "${result}" | grep -q "${want_text}"; then
        echo "FAIL ${name}: output does not mention '${want_text}'"; echo "     ${result}"; FAIL=$((FAIL + 1)); return
    fi
    echo "pass ${name}"; PASS=$((PASS + 1))
}

# --- guard -------------------------------------------------------------------------------------------
check "guard passes a succeeding command"  0 ""                    'guard "true holds" true; echo reached'
check "guard aborts on a failing command"  2 "REFUSING TO CONTINUE" 'guard "false holds" false; echo reached'
check "guard aborts before the next line"  2 "false holds"          'guard "false holds" false; echo REACHED-ANYWAY'

# The point of the whole file: the line after a failed guard must not run.
check "nothing after a failed guard runs"  2 ""                     'guard "x" false; echo REACHED-ANYWAY'
result="$(run 'guard "x" false; echo REACHED-ANYWAY')"
if printf '%s' "${result}" | grep -q "REACHED-ANYWAY"; then
    echo "FAIL the statement after a failed guard ran"; FAIL=$((FAIL + 1))
else
    echo "pass the statement after a failed guard did not run"; PASS=$((PASS + 1))
fi

# --- guard_empty / guard_nonempty --------------------------------------------------------------------
check "guard_empty passes on empty"        0 ""                    'guard_empty "no changes" ""; echo reached'
check "guard_empty aborts on a value"      2 "expected nothing"    'guard_empty "no changes" " M file.kt"; echo reached'
check "guard_empty shows what it found"    2 "M file.kt"           'guard_empty "no changes" " M file.kt"'
check "guard_nonempty passes on a value"   0 ""                    'guard_nonempty "cp written" "a:b"; echo reached'
check "guard_nonempty aborts on empty"     2 "expected a value"    'guard_nonempty "cp written" ""; echo reached'

# --- the exit status is configurable, because the cluster scripts distinguish 2 from a failed run ------
check "GUARD_EXIT is honoured"             3 "REFUSING"            'GUARD_EXIT=3; guard "x" false'

# --- a passing guard is silent unless asked ----------------------------------------------------------
result="$(run 'guard "quiet by default" true')"
if [ "${result}" = "rc=0 " ]; then
    echo "pass a passing guard prints nothing"; PASS=$((PASS + 1))
else
    echo "FAIL a passing guard printed: ${result}"; FAIL=$((FAIL + 1))
fi
check "GUARD_VERBOSE reports passes"       0 "guard ok"            'GUARD_VERBOSE=1; guard "loud" true'

# --- the historical incident, reproduced -------------------------------------------------------------
# Incident 3: a check written as an echo. With guard_empty the same code cannot continue.
check "the incident that motivated this"   2 "build-logic/bin"     'other="?? build-logic/bin/"; guard_empty "no further changes" "$other"; echo REMOVED-ANYWAY'

echo "${PASS} passed, ${FAIL} failed"
[ "${FAIL}" -eq 0 ]
