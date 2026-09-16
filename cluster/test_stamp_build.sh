#!/usr/bin/env bash
#
# Tests cluster/stamp_build.sh against small throwaway repositories.
#
# Above all the case that motivated the hidden-change check: a tracked file with the skip-worktree bit whose content
# differs from the commit. git status reports nothing for it, and the stamp must refuse anyway. Each such case also
# asserts that git status is in fact silent, so the test fails if it ever stops testing the blind spot it is for.
#
# The repositories use core.autocrlf=true and carry one blob committed with LF and one with CRLF, because the
# comparison has to call an unchanged file unchanged under both - a check that refuses every clean Windows checkout
# would be switched off by the next person who meets it.
#
# Usage:  cluster/test_stamp_build.sh        exit 0 when every case passes
#
# Copyright (c) 2026 Marvin Baumann / KIT.

set -uo pipefail

STAMP="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/stamp_build.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT
PASS=0
FAIL=0

# A fresh repository: ots-x/src/Lf.java committed with LF, ots-x/src/Crlf.java committed with CRLF, a class directory.
new_repo() {
    local repo="${WORK}/$1"
    mkdir -p "${repo}/ots-x/src" "${repo}/classes"
    git -C "${repo}" init -q
    git -C "${repo}" config user.name test
    git -C "${repo}" config user.email test@example.invalid
    git -C "${repo}" config core.autocrlf false
    printf 'class Lf {}\n' > "${repo}/ots-x/src/Lf.java"
    printf 'class Crlf {}\r\n' > "${repo}/ots-x/src/Crlf.java"
    git -C "${repo}" add -A ots-x
    git -C "${repo}" commit -q -m init
    git -C "${repo}" config core.autocrlf true
    # Re-check the files out under autocrlf, as a Windows clone has them: LF blob -> CRLF on disk, CRLF blob unchanged.
    rm "${repo}/ots-x/src/Lf.java" "${repo}/ots-x/src/Crlf.java"
    git -C "${repo}" checkout -q -- ots-x
    echo "${repo}"
}

# expect <name> <expected exit> <expected text or empty> <repo>
expect() {
    local name="$1" want="$2" text="$3" repo="$4" out rc
    out="$(bash "${STAMP}" "${repo}" "${repo}/classes" test 2>&1)"
    rc=$?
    if [ "${rc}" -ne "${want}" ]; then
        echo "FAIL ${name}: exit ${rc}, expected ${want}"; echo "${out}" | sed 's/^/     /'; FAIL=$((FAIL + 1)); return
    fi
    if [ -n "${text}" ] && ! grep -qF -- "${text}" <<< "${out}"; then
        echo "FAIL ${name}: output lacks '${text}'"; echo "${out}" | sed 's/^/     /'; FAIL=$((FAIL + 1)); return
    fi
    echo "pass ${name}"
    PASS=$((PASS + 1))
}

# silent <name> <repo>: the premise of a hidden-change case - git status must see nothing.
silent() {
    if [ -n "$(git -C "$2" status --porcelain)" ]; then
        echo "FAIL $1: git status is not silent, so this case does not test a hidden change"; FAIL=$((FAIL + 1))
        return 1
    fi
}

r="$(new_repo clean)"
expect "clean tree is stamped" 0 "Build stamped" "${r}"
[ -f "${r}/classes/mirova-build.properties" ] || { echo "FAIL clean tree: no stamp written"; FAIL=$((FAIL + 1)); }

r="$(new_repo skip-unchanged-lf)"
git -C "${r}" update-index --skip-worktree ots-x/src/Lf.java
expect "skip-worktree, unchanged, LF blob (CRLF on disk) is clean" 0 "Build stamped" "${r}"

r="$(new_repo skip-unchanged-crlf)"
git -C "${r}" update-index --skip-worktree ots-x/src/Crlf.java
expect "skip-worktree, unchanged, CRLF blob is clean" 0 "Build stamped" "${r}"

r="$(new_repo skip-changed)"
git -C "${r}" update-index --skip-worktree ots-x/src/Lf.java
printf '@SuppressWarnings("all") class Lf {}\r\n' > "${r}/ots-x/src/Lf.java"
silent "skip-worktree, changed" "${r}" && expect "skip-worktree, changed, is refused" 3 "S (hidden from git status) ots-x/src/Lf.java" "${r}"
[ -f "${r}/classes/mirova-build.properties" ] && { echo "FAIL skip-worktree, changed: a stamp was written"; FAIL=$((FAIL + 1)); }

r="$(new_repo skip-changed-crlf)"
git -C "${r}" update-index --skip-worktree ots-x/src/Crlf.java
printf 'class Crlf { int x; }\r\n' > "${r}/ots-x/src/Crlf.java"
silent "skip-worktree, changed, CRLF blob" "${r}" && expect "skip-worktree, changed, CRLF blob, is refused" 3 "S (hidden from git status) ots-x/src/Crlf.java" "${r}"

r="$(new_repo skip-missing)"
git -C "${r}" update-index --skip-worktree ots-x/src/Lf.java
rm "${r}/ots-x/src/Lf.java"
silent "skip-worktree, deleted" "${r}" && expect "skip-worktree, deleted, is refused" 3 "S (missing) ots-x/src/Lf.java" "${r}"

r="$(new_repo assume-changed)"
git -C "${r}" update-index --assume-unchanged ots-x/src/Lf.java
printf 'class Lf { int y; }\r\n' > "${r}/ots-x/src/Lf.java"
silent "assume-unchanged, changed" "${r}" && expect "assume-unchanged, changed, is refused" 3 "(hidden from git status) ots-x/src/Lf.java" "${r}"

r="$(new_repo check-only)"
git -C "${r}" update-index --skip-worktree ots-x/src/Lf.java
printf 'class Lf { int z; }\r\n' > "${r}/ots-x/src/Lf.java"
out="$(bash "${STAMP}" "${r}" --check-only test 2>&1)"; rc=$?
if [ "${rc}" -eq 3 ]; then echo "pass --check-only refuses a hidden change too"; PASS=$((PASS + 1)); else echo "FAIL --check-only: exit ${rc}"; FAIL=$((FAIL + 1)); fi

echo "${PASS} passed, ${FAIL} failed"
[ "${FAIL}" -eq 0 ]
