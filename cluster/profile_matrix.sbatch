#!/usr/bin/env bash
#
# Single-job wrapper for the 2x2 profiling matrix (bwUniCluster 3.0).
#
# NOT an array job, unlike run_mirova.sbatch: the four cells are being compared against each
# other, so they must run on the same node under the same conditions. An array would scatter
# them and make the comparison meaningless.
#
# NOT --exclusive either, deliberately. An exclusive allocation would remove neighbour noise,
# but the queue wait for one is not worth it here — so this accepts a standard shared-node
# allocation and the risk that comes with it. That risk is real and has been observed on this
# cluster: a noisy neighbour can slow a run by five- or sixfold. The consequence for the
# results is specific rather than fatal — relative attribution within a cell still holds,
# because JFR samples in proportion to the CPU the thread actually received, but wall-clock
# and CPU-sample counts become incomparable BETWEEN cells if the neighbours differ while the
# matrix runs. profile_matrix_report.py prints the CPU-sample ratios; treat them as indicative
# and lean on the composition percentages, which are far more robust to this.
#
# The report and any write-up must state that this was a non-exclusive run.
#
# --cpus-per-task=2 is what the design needs, no more: the two CACHING variants of a djunits
# build run concurrently, one per core, and each JVM is capped at one core's worth of
# parallelism (-XX:ActiveProcessorCount=1) so the two cannot oversubscribe the allocation.
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
#   - put the per-date demand CSVs into <workspace>/demand — the cells run the study's full
#     13:00-22:00 window and the script refuses to start without demand_<date>.csv
#
# --time: each cell is a full production-length run, the same 9-hour window the dates study
# uses, measured at 90-120 minutes per run in run_mirova.sbatch's calibration. The two cells
# of a build run concurrently, so the wall clock is two runs plus two Maven builds, not four
# runs. 6 hours gives roughly 50% headroom on that — the same margin run_mirova.sbatch
# allows — and covers a moderately noisy neighbour without needing a resubmission. Raise it
# if the node turns out to be badly contended; a matrix that dies at the wall clock loses all
# four cells, since the comparison is only meaningful complete.
#
#SBATCH --job-name=mirova-profile-matrix
#SBATCH --partition=cpu
#SBATCH --nodes=1
#SBATCH --ntasks=1
#SBATCH --cpus-per-task=2
#SBATCH --mem-per-cpu=8G
#SBATCH --time=06:00:00
#SBATCH --output=logs/profile_matrix_%j.out
#SBATCH --error=logs/profile_matrix_%j.err
#
# --mem-per-cpu=8G, i.e. 16G for the task, mirroring run_mirova.sbatch: two JVMs live inside
# this allocation at once, and MIROVA_JAVA_HEAP is per run. The constraint is
# JAVA_HEAP < mem-per-cpu - off-heap, so the default 6g heap leaves ~2 GB per run for
# metaspace, code cache, GC structures and thread stacks. Exceeding it gets the whole task
# OOM-killed, taking down the healthy cell alongside the greedy one.
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
echo "Allocation: ${SLURM_CPUS_PER_TASK:-?} cores, NON-EXCLUSIVE (neighbours may be present)"
echo "Started:    $(date)"
echo "=========================================================="

# The script restores stock djunits from its own EXIT trap, so its exit status is passed
# through rather than intercepted here.
"${MIROVA_CLUSTER_DIR}/profile_matrix.sh"
