#!/usr/bin/env bash
#
# SLURM job array for MiRoVA cluster studies (bwUniCluster 3.0).
#
# ONE ARRAY TASK = ONE SIMULATION RUN = ONE CORE.
# SLURM_ARRAY_TASK_ID is used directly as the study's global run index, so the array range
# is simply 0..N-1 where N is what RunMirovaClusterStudy --count reports for that study.
# A single run takes 90-120 min while JVM startup costs seconds, so there is nothing to gain
# from bundling runs — and single-core tasks let SLURM backfill any free core anywhere.
#
# Submit from the workspace so that the relative log paths below land there:
#     export MIROVA_WORKSPACE=<workspace name>
#     sbatch --chdir="$(ws_find "$MIROVA_WORKSPACE")" cluster/run_mirova.sbatch
#
# ---------------------------------------------------------------------------------------
# CALIBRATE BEFORE THE FIRST PRODUCTION SUBMISSION
# ---------------------------------------------------------------------------------------
# --array        must be 0-<N-1> with N from:
#                    RunMirovaClusterStudy --study=<study> --output=<dir> ... --count
#                For the date study with 32 dates x 6 replications: --array=0-191.
# --mem-per-cpu  PLACEHOLDER for ONE run (not a bundle). Measure the peak RSS of a single
#                run locally, add JVM overhead, then set this and JAVA_HEAP.
# --time         03:00:00 gives ~50% headroom over the measured 90-120 min per run.
# --partition    choose per bwUniCluster 3.0 queue documentation.
# ---------------------------------------------------------------------------------------
#
#SBATCH --job-name=mirova-study
#SBATCH --array=0-53
#SBATCH --partition=cpu
#SBATCH --nodes=1
#SBATCH --ntasks=1
#SBATCH --cpus-per-task=1
#SBATCH --mem-per-cpu=8G
#SBATCH --time=03:00:00
#SBATCH --output=logs/mirova_%A_%a.out
#SBATCH --error=logs/mirova_%A_%a.err
#
# Optional self-throttling during shared-cluster congestion: limit how many array tasks run
# concurrently by appending %N to the range. Not needed by default — 192 single-core tasks
# stay well under the 1920-core-per-user cap.
# #SBATCH --array=0-191%50
#
# Copyright (c) 2026 Marvin Baumann / KIT.

set -euo pipefail

# --------------------------------------------------------------------------------------
# Workspace — all simulation I/O lives here, never in $HOME
# --------------------------------------------------------------------------------------
CLUSTER_DIR="${MIROVA_CLUSTER_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}"

# shellcheck source=cluster/mirova_env.sh
source "${CLUSTER_DIR}/mirova_env.sh"

WORKSPACE="$(resolve_workspace)"

# Java/Maven come from the workspace toolchain provisioned by build_for_cluster.sh.
# There is no 'module load' here: bwUniCluster 3.0 provides no Java or Maven modules.
activate_toolchain "${WORKSPACE}"

# --------------------------------------------------------------------------------------
# Study selection and configuration
# --------------------------------------------------------------------------------------
# Which study to run: a short name known to StudyRegistry ('dates', 'paramgrid') or the
# fully qualified class name of any StudyDefinition. Adding a study needs no change here.
STUDY="${MIROVA_STUDY:-dates}"

# Directory holding the pre-generated per-date demand CSVs (one file per simulated date).
DEMAND_DIR="${MIROVA_DEMAND_DIR:-${WORKSPACE}/demand}"

# Root output directory of this study.
OUTPUT_ROOT="${MIROVA_OUTPUT_ROOT:-${WORKSPACE}/output/${STUDY}}"

# Study-specific options passed straight through, e.g. --replications=6 or --start=...
# The date study reads its date list from this file; every array task must see the same one.
DATES_FILE="${MIROVA_DATES_FILE:-${CLUSTER_DIR}/dates.txt}"
STUDY_OPTS="${MIROVA_STUDY_OPTS:---dates=${DATES_FILE} --demand=${DEMAND_DIR} --strict=true}"

# Heap for a single run. Keep below --mem-per-cpu to leave room for JVM off-heap memory.
JAVA_HEAP="${MIROVA_JAVA_HEAP:-6g}"

MAIN_CLASS="org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.RunMirovaClusterStudy"
CP_FILE="${MIROVA_CP_FILE:-${WORKSPACE}/cp.txt}"

# --------------------------------------------------------------------------------------
# Environment
# --------------------------------------------------------------------------------------
# No Python virtual environment or evaluation scripts exist on the cluster: skip both the
# Python demand preparation and the post-run plotting step. The studies also set this
# themselves, so this is a belt-and-braces safeguard for manual invocations.
export MIROVA_SKIP_POSTPROCESSING=1
export MIROVA_SKIP_DEMAND_PREP=1

# The simulation loop itself is single-threaded (AbstractSimulationScriptBase.runHeadless
# steps the simulator on the calling thread). Confine the JVM's own parallel machinery — GC
# and JIT compiler threads — to the single allocated core as well, so the process does not
# oversubscribe what SLURM granted it.
JAVA_OPTS="-XX:ActiveProcessorCount=${SLURM_CPUS_PER_TASK:-1}"

# --------------------------------------------------------------------------------------
# Resolve this array task's run
# --------------------------------------------------------------------------------------
if [ ! -f "${CP_FILE}" ]; then
    echo "Classpath file not found: ${CP_FILE} — run cluster/build_for_cluster.sh first." >&2
    exit 2
fi

# SLURM_ARRAY_TASK_ID *is* the global run index; no lookup table is involved.
GLOBAL_INDEX="${SLURM_ARRAY_TASK_ID:-0}"

mkdir -p "${OUTPUT_ROOT}" "${WORKSPACE}/logs"

echo "=========================================================="
echo "Array task ${GLOBAL_INDEX} on $(hostname)"
echo "Workspace:   ${WORKSPACE}"
echo "Study:       ${STUDY}"
echo "Study opts:  ${STUDY_OPTS}"
echo "Cores:       ${SLURM_CPUS_PER_TASK:-1}"
echo "Output:      ${OUTPUT_ROOT}"
echo "=========================================================="

# Direct java launch (not exec:java) — avoids the GlassFish JAXB ClassLoader problems
# documented in docs/mirova/troubleshooting_and_compilation.md.
EXIT_CODE=0
# shellcheck disable=SC2086  # STUDY_OPTS is intentionally word-split into separate options
java -Xmx"${JAVA_HEAP}" ${JAVA_OPTS} -cp "$(cat "${CP_FILE}")" "${MAIN_CLASS}" \
    --study="${STUDY}" \
    --output="${OUTPUT_ROOT}" \
    --index="${GLOBAL_INDEX}" \
    ${STUDY_OPTS} || EXIT_CODE=$?

echo "Array task ${GLOBAL_INDEX} finished with exit code ${EXIT_CODE}."
exit "${EXIT_CODE}"
