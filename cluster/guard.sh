#!/usr/bin/env bash
#
# Checks that cannot be written as bare output.
#
# Source this, then state every precondition with `guard`, `guard_empty` or `guard_nonempty`. Each one
# either passes or ends the script. There is deliberately no variant that returns a value for the caller
# to inspect: that is the shape this file exists to remove.
#
# ## Why
#
# Three incidents in one working session, each the same shape - a line that looks like a check, prints its
# result, and does not gate anything:
#
#   1. `git show "$c" | head -5` taking the exit code of `head`, not of `git show`, so a failed command
#      read as success.
#   2. `rm -rf "$P"; echo "REMOVED $w"` - the echo ran regardless of whether the rm had failed with
#      "Device or resource busy". The report said removed; the directory was still there.
#   3. `other=$(git status --porcelain | grep -v kotlin-units); echo "further changes: $other"` followed by
#      the removal - the value was printed and never tested. It was non-empty. The removal ran anyway.
#
# The third was harmless only because the untracked files happened to be IDE output. Three of a kind is not
# coincidence: writing a check as an echo is easy to do and impossible to see afterwards, because the
# output looks exactly like a check that passed. So the form goes away.
#
# ## Use
#
#   source "${CLUSTER_DIR}/guard.sh"
#
#   guard "the tree is a git checkout"        git -C "$repo" rev-parse --verify HEAD
#   guard_empty "no tracked changes"          "$(git -C "$repo" status --porcelain | grep -v '^??')"
#   guard_nonempty "the classpath was written" "$(cat "$CP_FILE")"
#
# On failure each prints what was expected, what it found, and exits ${GUARD_EXIT:-2}. On success `guard`
# is silent unless GUARD_VERBOSE=1, so a passing script stays readable and a failing one says why.
#
# Copyright (c) 2026 Marvin Baumann / KIT.

# Exit status used when a guard fails. 2 throughout the cluster scripts: "refused to start", as distinct
# from a run that started and failed.
: "${GUARD_EXIT:=2}"

# Prints the line a guard failed on, when the caller ran under `set -x`-less conditions and needs the site.
_guard_site() {
    # BASH_SOURCE[2]/BASH_LINENO[1]: the caller of the guard function, not the guard itself.
    printf '%s:%s' "${BASH_SOURCE[2]:-?}" "${BASH_LINENO[1]:-?}"
}

# Fails unless the command succeeds.
#
#   guard "<what must be true>" <command> [args...]
#
# The command's own output is not suppressed - a guard that hides why it failed is barely better than one
# that never ran.
guard() {
    local what="$1"
    shift
    if "$@"; then
        [ "${GUARD_VERBOSE:-0}" = "1" ] && echo "guard ok: ${what}"
        return 0
    fi
    echo "REFUSING TO CONTINUE: ${what}" >&2
    echo "  the check failed at $(_guard_site): $*" >&2
    exit "${GUARD_EXIT}"
}

# Fails unless the value is empty. For "nothing changed", "nothing left over", "no offending file".
#
#   guard_empty "<what must be absent>" "<value>"
guard_empty() {
    local what="$1" value="$2"
    if [ -z "${value}" ]; then
        [ "${GUARD_VERBOSE:-0}" = "1" ] && echo "guard ok: ${what}"
        return 0
    fi
    echo "REFUSING TO CONTINUE: ${what}" >&2
    echo "  expected nothing, found (at $(_guard_site)):" >&2
    printf '%s\n' "${value}" | head -20 | sed 's/^/    /' >&2
    exit "${GUARD_EXIT}"
}

# Fails unless the value is non-empty. For "the file has content", "the query returned something".
#
#   guard_nonempty "<what must be present>" "<value>"
guard_nonempty() {
    local what="$1" value="$2"
    if [ -n "${value}" ]; then
        [ "${GUARD_VERBOSE:-0}" = "1" ] && echo "guard ok: ${what}"
        return 0
    fi
    echo "REFUSING TO CONTINUE: ${what}" >&2
    echo "  expected a value, found nothing (at $(_guard_site))" >&2
    exit "${GUARD_EXIT}"
}
