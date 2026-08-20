#!/usr/bin/env bash
#
# Single-job wrapper for the 2x2 profiling matrix (bwUniCluster 3.0).
#
# NOT an array job, unlike run_mirova.sbatch: the four cells must run one after another on the
# same node under the same conditions, because they are being compared against each other. An
# array would scatter them across nodes and make the comparison meaningless.
#
# --exclusive for the same reason. Every earlier measurement in this series was taken on a
# contended machine, and the resulting caveat ("relative attribution holds, absolute numbers do
# not") is precisely what this run exists to remove.
#
# MIROVA_CLUSTER_DIR must be exported before submitting: sbatch copies this script's *content*
# into a spool file and runs that copy, so it cannot find mirova_env.sh next to itself.
#
#     export MIROVA_WORKSPACE=<workspace name>
#     export MIROVA_CLUSTER_DIR="$PWD/cluster"          # from the repository root
#     sbatch --chdir="$(ws_find "$MIROVA_WORKSPACE")" cluster/profile_matrix.sbatch
#
# Before submitting, once:
#   - install the patched djunits artifact (see docs/mirova/djunits_patch_experiment.md)
#   - run cluster/build_for_cluster.sh, so the toolchain is provisioned and the local .m2 is
#     warm; the rebuilds inside this job are then incremental and need no network
#
# --time: four cells, each a 25-minute simulation that takes a few minutes of wall clock on an
# idle core, plus two Maven builds. One hour is generous; raise it if the builds are cold.
#
#SBATCH --job-name=mirova-profile-matrix
#SBATCH --partition=cpu
#SBATCH --nodes=1
#SBATCH --ntasks=1
#SBATCH --cpus-per-task=2
#SBATCH --exclusive
#SBATCH --mem=16G
#SBATCH --time=01:00:00
#SBATCH --output=logs/profile_matrix_%j.out
#SBATCH --error=logs/profile_matrix_%j.err
#
# Copyright (c) 2026 Marvin Baumann / KIT.

set -euo pipefail

if [ -z "${MIROVA_CLUSTER_DIR:-}" ]; then
    echo "ERROR: MIROVA_CLUSTER_DIR is not set." >&2
    echo "       sbatch runs a copy of this script from the job spool directory, so it cannot" >&2
    echo "       find mirova_env.sh next to itself. Export the repository's cluster/ path:" >&2
    echo "           export MIROVA_CLUSTER_DIR=\"\$PWD/cluster\"   # from the repository root" >&2
    exit 2
fi

if [ ! -f "${MIROVA_CLUSTER_DIR}/profile_matrix.sh" ]; then
    echo "ERROR: no profile_matrix.sh in MIROVA_CLUSTER_DIR (${MIROVA_CLUSTER_DIR})." >&2
    exit 2
fi

mkdir -p logs

echo "=========================================================="
echo "Profiling matrix on $(hostname), job ${SLURM_JOB_ID:-local}"
echo "Started: $(date)"
echo "=========================================================="

# The script restores stock djunits from its own EXIT trap, so its exit status is passed
# through rather than intercepted here.
"${MIROVA_CLUSTER_DIR}/profile_matrix.sh"
