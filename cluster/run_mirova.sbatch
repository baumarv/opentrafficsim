#!/usr/bin/env bash
#
# SLURM job array for MiRoVA cluster studies (bwUniCluster 3.0).
#
# ONE ARRAY TASK = TWO SIMULATION RUNS, ONE PER CORE, RUN CONCURRENTLY.
# Array task T executes the study's global run indices 2*T and 2*T+1.
#
# Why two, when a run is single-threaded and the design elsewhere favours one run per task:
# on the cpu partition SLURM allocates two logical CPUs even for --cpus-per-task=1
# (AllocTRES=cpu=2 observed on jobs 6366033, 6366103 and 6366286; the partition reports
# MaxCPUsPerNode=192 over 80 nodes, i.e. 96 physical cores x 2 SMT threads, so a physical
# core appears to be the smallest allocatable unit). The completed canary run 6366286 shows
# the consequence: CPU Utilized 00:18:25 against a wall-clock of 00:18:29, i.e. 49.82% CPU
# efficiency — one core busy, the second one allocated and idle for the whole run.
# Since the allocation costs two cores either way, the second one carries a second run.
# Each run still gets exactly one core's worth of JVM parallelism.
#
# MIROVA_CLUSTER_DIR must be exported before submitting: sbatch copies this script's
# *content* into a spool file and runs that copy, so the script cannot locate its own
# directory and therefore cannot find mirova_env.sh next to it.
#
# Submit from the workspace so that the relative log paths below land there:
#     export MIROVA_WORKSPACE=<workspace name>
#     export MIROVA_CLUSTER_DIR="$PWD/cluster"          # from the repository root
#     sbatch --chdir="$(ws_find "$MIROVA_WORKSPACE")" cluster/run_mirova.sbatch
#
# ---------------------------------------------------------------------------------------
# CALIBRATE BEFORE THE FIRST PRODUCTION SUBMISSION
# ---------------------------------------------------------------------------------------
# --array        must be 0-<ceil(N/2)-1>, because each task runs TWO runs. N comes from:
#                    RunMirovaClusterStudy --study=<study> --output=<dir> ... --count
#                9 dates x 6 replications  =  54 runs -> 27 tasks -> --array=0-26
#                32 dates x 6 replications = 192 runs -> 96 tasks -> --array=0-95
#                An odd N is fine: the last task skips its second index (see below).
# --mem-per-cpu  PLACEHOLDER, per core, so a task's memory limit is 2 x this value while
#                TWO JVMs live inside it. MIROVA_JAVA_HEAP is likewise per run, so the
#                constraint to satisfy when calibrating is
#
#                    2 x MIROVA_JAVA_HEAP  <  2 x --mem-per-cpu  -  (2 x off-heap per JVM)
#
#                i.e. per run: JAVA_HEAP < mem-per-cpu - off-heap. The headroom must cover
#                TWO JVMs' non-heap footprint (metaspace, code cache, GC structures, thread
#                stacks, direct buffers), not one's - roughly 0.5-1 GB each, so with
#                mem-per-cpu=8G a JAVA_HEAP of 6g leaves ~2 GB per run for that.
#                Measure the peak RSS of a single run first, then set both accordingly:
#                exceeding the limit gets the whole task OOM-killed, taking down the healthy
#                run alongside the greedy one.
# --time         03:00:00 gives ~50% headroom over the measured 90-120 min per run. The two
#                runs are concurrent, so this stays a per-run budget, not a sum.
# --partition    choose per bwUniCluster 3.0 queue documentation.
# ---------------------------------------------------------------------------------------
#
#SBATCH --job-name=mirova-study
#SBATCH --array=0-26
#SBATCH --partition=cpu
#SBATCH --nodes=1
#SBATCH --ntasks=1
#SBATCH --cpus-per-task=2
#SBATCH --mem-per-cpu=8G
#SBATCH --time=03:00:00
#SBATCH --output=logs/mirova_%A_%a.out
#SBATCH --error=logs/mirova_%A_%a.err
#
# --cpus-per-task=2 is requested explicitly rather than relying on SLURM rounding 1 up to a
# full physical core: it makes the two cores the task actually gets an intentional part of
# the request, and it keeps the arithmetic honest if that rounding ever changes.
#
# Optional self-throttling during shared-cluster congestion: limit how many array tasks run
# concurrently by appending %N to the range. Not needed by default — 96 two-core tasks stay
# well under the 1920-core-per-user cap.
# #SBATCH --array=0-95%25
#
# Copyright (c) 2026 Marvin Baumann / KIT.

set -euo pipefail

# --------------------------------------------------------------------------------------
# Locate the cluster scripts
# --------------------------------------------------------------------------------------
# Deliberately no self-location from ${BASH_SOURCE[0]}: sbatch copies the submitted
# script's content into /var/spool/slurmd/job<id>/slurm_script and executes that copy, so
# BASH_SOURCE always points at the spool directory, never at the repository. Guessing here
# would break only under sbatch while appearing to work when the script is run directly.
if [ -z "${MIROVA_CLUSTER_DIR:-}" ]; then
    echo "ERROR: MIROVA_CLUSTER_DIR is not set." >&2
    echo "       sbatch runs a copy of this script from the job spool directory, so it" >&2
    echo "       cannot find mirova_env.sh next to itself. Export the path of the" >&2
    echo "       repository's cluster/ directory before submitting, e.g.:" >&2
    echo "           export MIROVA_CLUSTER_DIR=\"\$PWD/cluster\"   # from the repository root" >&2
    echo "           sbatch --chdir=\"\$(ws_find \"\$MIROVA_WORKSPACE\")\" cluster/run_mirova.sbatch" >&2
    exit 2
fi

CLUSTER_DIR="${MIROVA_CLUSTER_DIR}"

if [ ! -f "${CLUSTER_DIR}/mirova_env.sh" ]; then
    echo "ERROR: no mirova_env.sh in MIROVA_CLUSTER_DIR (${CLUSTER_DIR})." >&2
    echo "       It must point at the cluster/ directory of the repository checkout." >&2
    exit 2
fi

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

# Split into an array honouring quotes, so that options whose value contains a space work -
# '--start="2025-09-22 13:00:00"' would otherwise be split into two broken arguments. eval is
# safe enough here: MIROVA_STUDY_OPTS is set by the submitter for their own job.
declare -a STUDY_OPTS_ARR
eval "STUDY_OPTS_ARR=(${STUDY_OPTS})"

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

# Each run is single-threaded (AbstractSimulationScriptBase.runHeadless steps the simulator
# on the calling thread), and two of them share the task's two cores. Pin each JVM's own
# parallel machinery - GC and JIT compiler threads - to one core's worth, so the two
# processes do not oversubscribe the allocation. Deliberately 1, not SLURM_CPUS_PER_TASK.
JAVA_OPTS="-XX:ActiveProcessorCount=1"

# Number of runs bundled into one array task; see the header for why this is 2.
RUNS_PER_TASK="${MIROVA_RUNS_PER_TASK:-2}"

# --------------------------------------------------------------------------------------
# Resolve this array task's runs
# --------------------------------------------------------------------------------------
if [ ! -f "${CP_FILE}" ]; then
    echo "Classpath file not found: ${CP_FILE} - run cluster/build_for_cluster.sh first." >&2
    exit 2
fi

TASK_ID="${SLURM_ARRAY_TASK_ID:-0}"
CLASSPATH_VALUE="$(cat "${CP_FILE}")"

mkdir -p "${OUTPUT_ROOT}" "${WORKSPACE}/logs"

# Total number of runs in this study, asked of the study itself rather than assumed, so that
# an odd total does not make the last task launch a nonexistent index. Costs one JVM start
# (seconds) against a 90-120 minute task. Set MIROVA_TOTAL_RUNS to skip it.
TOTAL_RUNS="${MIROVA_TOTAL_RUNS:-}"
if [ -z "${TOTAL_RUNS}" ]; then
    TOTAL_RUNS="$(java -cp "${CLASSPATH_VALUE}" "${MAIN_CLASS}" \
        --study="${STUDY}" --output="${OUTPUT_ROOT}" "${STUDY_OPTS_ARR[@]}" --count 2>/dev/null \
        | tail -1 | tr -d '[:space:]')"
fi
if ! [[ "${TOTAL_RUNS}" =~ ^[0-9]+$ ]] || [ "${TOTAL_RUNS}" -lt 1 ]; then
    echo "ERROR: could not determine the study's total run count (got '${TOTAL_RUNS}')." >&2
    echo "       Run RunMirovaClusterStudy --count by hand to see why, or set MIROVA_TOTAL_RUNS." >&2
    exit 2
fi

# Which physical CPUs this task actually holds, taken from the process affinity mask so the
# pinning respects the cgroup SLURM put us in rather than guessing CPU 0 and 1.
CPU_LIST=()
if command -v taskset >/dev/null 2>&1; then
    affinity="$(taskset -cp $$ 2>/dev/null | awk -F': ' '{print $2}')"
    IFS=',' read -ra affinity_parts <<< "${affinity}"
    for part in "${affinity_parts[@]}"; do
        if [[ "${part}" =~ ^([0-9]+)-([0-9]+)$ ]]; then
            for ((c = BASH_REMATCH[1]; c <= BASH_REMATCH[2]; c++)); do CPU_LIST+=("${c}"); done
        elif [[ "${part}" =~ ^[0-9]+$ ]]; then
            CPU_LIST+=("${part}")
        fi
    done
fi

echo "=========================================================="
echo "Array task ${TASK_ID} on $(hostname)"
echo "Workspace:   ${WORKSPACE}"
echo "Study:       ${STUDY} (${TOTAL_RUNS} runs total)"
echo "Study opts:  ${STUDY_OPTS}"
echo "Cores:       ${SLURM_CPUS_PER_TASK:-?} (affinity: ${CPU_LIST[*]:-unknown})"
echo "Output:      ${OUTPUT_ROOT}"
echo "=========================================================="

# Direct java launch (not exec:java) - avoids the GlassFish JAXB ClassLoader problems
# documented in docs/mirova/troubleshooting_and_compilation.md.
declare -a RUN_PIDS=() RUN_INDICES=() RUN_LOGS=()

for (( slot = 0; slot < RUNS_PER_TASK; slot++ )); do
    index=$(( TASK_ID * RUNS_PER_TASK + slot ))

    if [ "${index}" -ge "${TOTAL_RUNS}" ]; then
        echo "[slot ${slot}] no run at global index ${index} (study has ${TOTAL_RUNS}); nothing to launch."
        continue
    fi

    log="${WORKSPACE}/logs/mirova_${SLURM_ARRAY_JOB_ID:-local}_${TASK_ID}_run${index}.log"
    RUN_LOGS+=("${log}")
    RUN_INDICES+=("${index}")

    # Own log per run: two concurrent JVMs writing to one stream interleave unreadably.
    if [ "${#CPU_LIST[@]}" -gt "${slot}" ]; then
        cpu="${CPU_LIST[${slot}]}"
        echo "[slot ${slot}] run ${index} pinned to CPU ${cpu} -> ${log}"
        # shellcheck disable=SC2086  # JAVA_OPTS is intentionally word-split
        taskset -c "${cpu}" java -Xmx"${JAVA_HEAP}" ${JAVA_OPTS} -cp "${CLASSPATH_VALUE}" "${MAIN_CLASS}" \
            --study="${STUDY}" --output="${OUTPUT_ROOT}" --index="${index}" "${STUDY_OPTS_ARR[@]}" \
            > "${log}" 2>&1 &
    else
        # No taskset, or fewer CPUs in the mask than slots: leave placement to the OS
        # scheduler, which spreads two runnable single-threaded processes across the
        # allocated cores by itself.
        echo "[slot ${slot}] run ${index} unpinned (OS scheduler) -> ${log}"
        # shellcheck disable=SC2086  # JAVA_OPTS is intentionally word-split
        java -Xmx"${JAVA_HEAP}" ${JAVA_OPTS} -cp "${CLASSPATH_VALUE}" "${MAIN_CLASS}" \
            --study="${STUDY}" --output="${OUTPUT_ROOT}" --index="${index}" "${STUDY_OPTS_ARR[@]}" \
            > "${log}" 2>&1 &
    fi
    RUN_PIDS+=("$!")
done

if [ "${#RUN_PIDS[@]}" -eq 0 ]; then
    echo "ERROR: array task ${TASK_ID} has no runs to execute; the --array range is larger than" >&2
    echo "       ceil(${TOTAL_RUNS}/${RUNS_PER_TASK}) - 1. Shrink it to avoid empty tasks." >&2
    exit 2
fi

# Wait for every run and keep the worst outcome: one run failing must not be masked by the
# other succeeding, or a failed simulation would show up as a successful task in sacct.
EXIT_CODE=0
for i in "${!RUN_PIDS[@]}"; do
    run_status=0
    wait "${RUN_PIDS[$i]}" || run_status=$?
    if [ "${run_status}" -ne 0 ]; then
        echo "Run ${RUN_INDICES[$i]} FAILED with exit code ${run_status} (log: ${RUN_LOGS[$i]})." >&2
        # Plain 'if', not '[ ] && ...': under set -e a false test in a standalone AND-list
        # would terminate the task before the remaining runs are reaped.
        if [ "${run_status}" -gt "${EXIT_CODE}" ]; then
            EXIT_CODE="${run_status}"
        fi
    else
        echo "Run ${RUN_INDICES[$i]} finished successfully (log: ${RUN_LOGS[$i]})."
    fi
done

echo "Array task ${TASK_ID} finished with exit code ${EXIT_CODE} (runs: ${RUN_INDICES[*]})."
exit "${EXIT_CODE}"
