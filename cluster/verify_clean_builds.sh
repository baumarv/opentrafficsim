#!/usr/bin/env bash
#
# Builds twice with build_for_cluster.sh and fails unless the tree is clean before and after each build.
#
# The generated sources of ots-xml and ots-opendrive are tracked, so a build that generates them differently from
# the committed files leaves the tree dirty, and the next build is recorded as <commit>-dirty. This checks that it
# does not, on the machine it runs on - which is the point: the order of the schemas used to depend on the file
# system, so a clean tree on Windows said nothing about Linux.
#
# Usage, on the cluster, from the checkout ($WS/ots-benchmark):
#   export MIROVA_WORKSPACE=mirova-ots
#   export MIROVA_CP_FILE="$(ws_find mirova-ots)/cp_benchmark.txt"   # required: the workspace is shared
#   cluster/verify_clean_builds.sh
#
# Copyright (c) 2026 Marvin Baumann / KIT.

set -u
CLUSTER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=cluster/guard.sh
source "${CLUSTER_DIR}/guard.sh" || { echo "REFUSING TO CONTINUE: cannot source ${CLUSTER_DIR}/guard.sh" >&2; exit 2; }
REPO_ROOT="$(cd "${CLUSTER_DIR}/.." && pwd)"

guard_nonempty "MIROVA_CP_FILE is set, so the build does not overwrite the MiRoVA checkout's cp.txt" \
    "${MIROVA_CP_FILE:-}"
guard_empty "the tree is clean before the first build" "$(git -C "${REPO_ROOT}" status --porcelain)"

for build in 1 2; do
    echo "=== build ${build} of 2"
    guard "build ${build} succeeds" bash "${CLUSTER_DIR}/build_for_cluster.sh"
    guard_empty "the tree is clean after build ${build}" "$(git -C "${REPO_ROOT}" status --porcelain)"
done
echo "OK: two consecutive builds left the tree clean at $(git -C "${REPO_ROOT}" rev-parse --short HEAD)."
