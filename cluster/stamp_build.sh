#!/usr/bin/env bash
#
# Records which commit a build was made from, inside the build itself.
#
# Writes mirova-build.properties into a class directory that is on the run classpath. Every study run reads
# it (BuildProvenance) and copies it into its run folder as build.txt, and refuses to start when it is missing.
# That is the only way a campaign can say afterwards what it ran on: neither the output nor runParams.txt did,
# and two campaigns had to be dated from file timestamps and chat history (docs/fork-merge-plan.md, section D).
#
# FAILS when the working tree is dirty, because a commit hash is a claim about the sources and a dirty tree
# makes it false. Dirty means:
#   - any modified, added, deleted or renamed tracked file, wherever it is;
#   - any untracked file under a module's src/, any untracked pom.xml, or any untracked file in cluster/demand/
#     (the inputs a run reads).
# Other untracked files (build output, notes, caches) cannot change a run; they are counted in the stamp.
#
# Usage:  cluster/stamp_build.sh <repository root> <class directory> <builder label>
#         cluster/stamp_build.sh <repository root> --check-only <builder label>
# --check-only runs the dirty check and writes nothing: the build scripts call it before compiling as well as
# after, so that a tree edited during the build is caught too.
#
# Copyright (c) 2026 Marvin Baumann / KIT.

set -euo pipefail

REPO="${1:?usage: stamp_build.sh <repository root> <class directory> <builder label>}"
CLASSES="${2:?usage: stamp_build.sh <repository root> <class directory> <builder label>}"
BUILDER="${3:?usage: stamp_build.sh <repository root> <class directory> <builder label>}"

if ! git -C "${REPO}" rev-parse --verify HEAD >/dev/null 2>&1; then
    echo "ERROR: ${REPO} is not a git checkout; a build that cannot name its commit cannot be run." >&2
    exit 3
fi
if [ "${CLASSES}" != "--check-only" ] && [ ! -d "${CLASSES}" ]; then
    echo "ERROR: class directory ${CLASSES} does not exist; build first." >&2
    exit 3
fi

STATUS="$(git -C "${REPO}" status --porcelain=v1 --untracked-files=all)"
TRACKED_CHANGES="$(printf '%s\n' "${STATUS}" | grep -v '^??' | grep -v '^$' || true)"
UNTRACKED_INPUTS="$(printf '%s\n' "${STATUS}" | grep -E '^\?\? (ots-[^/]+/src/|(.*/)?pom\.xml$|cluster/demand/)' || true)"
# '|| true' inside the braces: grep exits 1 on no match, which pipefail would turn into the end of the script -
# on a clean tree, and on a tree whose only untracked files are inputs.
UNTRACKED_OTHER="$(printf '%s\n' "${STATUS}" | { grep '^??' || true; } \
    | { grep -vE '^\?\? (ots-[^/]+/src/|(.*/)?pom\.xml$|cluster/demand/)' || true; } | wc -l | tr -d '[:space:]')"

if [ -n "${TRACKED_CHANGES}" ] || [ -n "${UNTRACKED_INPUTS}" ]; then
    echo "ERROR: the working tree is dirty; refusing to stamp a build whose commit would not describe its sources." >&2
    printf '%s\n' "${TRACKED_CHANGES}" "${UNTRACKED_INPUTS}" | grep -v '^$' | head -20 >&2
    echo "       Commit or stash, then rebuild. There is deliberately no override." >&2
    exit 3
fi

if [ "${CLASSES}" = "--check-only" ]; then
    echo "Working tree clean at $(git -C "${REPO}" describe --tags --always --long --dirty)."
    exit 0
fi

STAMP="${CLASSES}/mirova-build.properties"
{
    echo "# Written by cluster/stamp_build.sh. Read by BuildProvenance; copied into every run folder as build.txt."
    echo "commit=$(git -C "${REPO}" rev-parse HEAD)"
    echo "describe=$(git -C "${REPO}" describe --tags --always --long --dirty)"
    echo "branch=$(git -C "${REPO}" rev-parse --abbrev-ref HEAD)"
    echo "committedAt=$(git -C "${REPO}" log -1 --format=%cI HEAD)"
    echo "builtAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "builtBy=${BUILDER}"
    echo "host=$(hostname)"
    echo "untrackedFilesOutsideInputs=${UNTRACKED_OTHER}"
} > "${STAMP}.tmp"
mv "${STAMP}.tmp" "${STAMP}"
echo "Build stamped: $(grep '^describe=' "${STAMP}" | cut -d= -f2) -> ${STAMP}"
