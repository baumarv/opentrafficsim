#!/usr/bin/env bash
#
# 2x2 profiling matrix: stock vs patched djunits, crossed with LaneBasedGtu.CACHING on/off.
#
#                       CACHING=true              CACHING=false
#   stock djunits       (A) baseline              (B) position-cache experiment alone
#   patched djunits     (C) patch alone           (D) both combined
#
# All four cells must be measured together on one node. The djunits patch removes most of the
# hashing cost that made the position cache expensive in the first place, so measuring the two
# independently invites the wrong conclusion about either: CACHING=false can look like a large
# win in isolation while being a wash, or a loss, once the hash it protects against is cheap.
#
# Only TWO builds are needed, not four. djunits is a build-time choice (the artifact on the
# classpath) while CACHING is a runtime one (a static field set by RunProfileMatrix), so the
# matrix is one build per djunits version crossed with two runs each. That also means the
# "which jar is actually resolved" check runs once per classpath rather than once per cell.
#
# Usage (login node, or via cluster/profile_matrix.sbatch):
#
#     export MIROVA_WORKSPACE=<workspace name>
#     ./cluster/profile_matrix.sh
#
# Prerequisites, both checked before anything is built:
#   - the patched artifact installed in this machine's .m2 (see docs/mirova/djunits_patch_experiment.md)
#   - a working toolchain, i.e. cluster/build_for_cluster.sh has been run at least once
#
# Copyright (c) 2026 Marvin Baumann / KIT.

set -euo pipefail

CLUSTER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# shellcheck source=cluster/mirova_env.sh
source "${CLUSTER_DIR}/mirova_env.sh"

WORKSPACE="$(resolve_workspace)"
REPO_ROOT="$(resolve_repo_root "${CLUSTER_DIR}")"

STOCK_VERSION="${MIROVA_DJUNITS_STOCK:-5.2.1}"
PATCHED_VERSION="${MIROVA_DJUNITS_PATCHED:-5.2.1-mirova-patched}"

RESULT_DIR="${MIROVA_PROFILE_DIR:-${WORKSPACE}/profiling/matrix_$(date +%Y%m%d_%H%M%S)}"
MAIN_CLASS="org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.RunProfileMatrix"
JAVA_HEAP="${MIROVA_JAVA_HEAP:-6g}"

# One core's worth of JVM parallelism, matching run_mirova.sbatch: the simulation is
# single-threaded, and letting GC and JIT threads spread across an exclusive node would add
# noise to exactly the measurement being taken.
JAVA_OPTS="-XX:ActiveProcessorCount=1"

# Same JFR settings as every measurement in this series. stackdepth=128 is not optional: the
# default truncates the perception call chains well above the frames that explain the cost.
JFR_SETTINGS="settings=profile"
JFR_STACKDEPTH=128

mkdir -p "${RESULT_DIR}"

# --------------------------------------------------------------------------------------
# Unconditional restore
# --------------------------------------------------------------------------------------
# Registered before the first modification and fired on ANY exit, including a failed build,
# a failed run, or Ctrl-C. The failure mode this guards against is specific and quiet:
# cp.txt is written by build_for_cluster.sh and is what run_mirova.sbatch launches against,
# so restoring pom.xml WITHOUT rebuilding would leave a classpath still pointing at the
# patched jar, and the next production submission would silently run on it.
RESTORED=0
restore_stock() {
    local exit_code=$?
    if [ "${RESTORED}" -eq 1 ]; then
        exit "${exit_code}"
    fi
    RESTORED=1

    echo
    echo "=========================================================="
    echo "Restoring stock djunits ${STOCK_VERSION} and rebuilding"
    echo "=========================================================="
    cd "${REPO_ROOT}"
    git checkout -- pom.xml || sed -i \
        "s|<djunits.version>${PATCHED_VERSION}</djunits.version>|<djunits.version>${STOCK_VERSION}</djunits.version>|" \
        pom.xml

    if "${CLUSTER_DIR}/build_for_cluster.sh" > "${RESULT_DIR}/restore_build.log" 2>&1; then
        local resolved
        resolved="$(grep -o "djunits[^:]*\.jar" "${WORKSPACE}/cp.txt" | head -1 || true)"
        echo "  pom.xml:  $(grep -o '<djunits.version>[^<]*' pom.xml | head -1 | cut -d'>' -f2)"
        echo "  cp.txt:   ${resolved}"
        case "${resolved}" in
            *"${PATCHED_VERSION}"*)
                echo "  ERROR: cp.txt still resolves the PATCHED jar after the restore rebuild." >&2
                echo "         Do not submit anything until this is fixed by hand." >&2
                exit 1
                ;;
            *)
                echo "  OK — the tree is back on stock djunits."
                ;;
        esac
    else
        echo "  ERROR: the restore rebuild FAILED (log: ${RESULT_DIR}/restore_build.log)." >&2
        echo "         pom.xml was reverted, but cp.txt may still point at the patched jar." >&2
        echo "         Run cluster/build_for_cluster.sh by hand before submitting anything." >&2
        exit 1
    fi

    exit "${exit_code}"
}
trap restore_stock EXIT INT TERM

# --------------------------------------------------------------------------------------
# Preconditions — fail here, not halfway through the matrix
# --------------------------------------------------------------------------------------
echo "=========================================================="
echo "MiRoVA profiling matrix"
echo "Workspace:  ${WORKSPACE}"
echo "Repository: ${REPO_ROOT}"
echo "Results:    ${RESULT_DIR}"
echo "=========================================================="
echo
echo "[0/3] Preconditions"

activate_toolchain "${WORKSPACE}"
echo "  JAVA_HOME=${JAVA_HOME}"

M2_REPO="${MIROVA_M2_REPO:-${HOME}/.m2/repository}"
PATCHED_JAR="${M2_REPO}/org/djunits/djunits/${PATCHED_VERSION}/djunits-${PATCHED_VERSION}.jar"
if [ ! -f "${PATCHED_JAR}" ]; then
    echo "ERROR: the patched djunits artifact is not installed on this machine." >&2
    echo "       Expected: ${PATCHED_JAR}" >&2
    echo >&2
    echo "       Copy it over and install it first (see docs/mirova/djunits_patch_experiment.md):" >&2
    echo "         scp <workstation>:~/.m2/repository/org/djunits/djunits/${PATCHED_VERSION}/djunits-${PATCHED_VERSION}{.jar,-sources.jar,.pom} /tmp/" >&2
    echo "         mvn install:install-file \\\\" >&2
    echo "           -Dfile=/tmp/djunits-${PATCHED_VERSION}.jar \\\\" >&2
    echo "           -DpomFile=/tmp/djunits-${PATCHED_VERSION}.pom \\\\" >&2
    echo "           -Dsources=/tmp/djunits-${PATCHED_VERSION}-sources.jar" >&2
    echo >&2
    echo "       -DpomFile rather than -DgroupId/-DartifactId/-Dversion: the real pom carries" >&2
    echo "       djunits' own dependency on djutils-base, and a synthesised one would drop it." >&2
    exit 2
fi
echo "  patched artifact: ${PATCHED_JAR}"

cd "${REPO_ROOT}"
if ! grep -q "<djunits.version>" pom.xml; then
    echo "ERROR: no <djunits.version> property in ${REPO_ROOT}/pom.xml." >&2
    exit 2
fi
CURRENT_VERSION="$(grep -o '<djunits.version>[^<]*' pom.xml | head -1 | cut -d'>' -f2)"
if [ "${CURRENT_VERSION}" != "${STOCK_VERSION}" ]; then
    echo "ERROR: pom.xml already declares djunits ${CURRENT_VERSION}, expected ${STOCK_VERSION}." >&2
    echo "       Start from a clean tree so the restore at the end is meaningful." >&2
    exit 2
fi
echo "  pom.xml starts on stock ${STOCK_VERSION}"

# --------------------------------------------------------------------------------------
# Build once per djunits version, verifying what actually landed on the classpath
# --------------------------------------------------------------------------------------
# build_for_cluster.sh writes cp.txt into the workspace, so each build's classpath is copied
# aside immediately; otherwise the second build would overwrite the first one's.
build_variant() {
    local version="$1" label="$2" cp_out="$3"

    echo
    echo "[1/3] Building against djunits ${version} (${label})"
    cd "${REPO_ROOT}"
    sed -i "s|<djunits.version>[^<]*</djunits.version>|<djunits.version>${version}</djunits.version>|" pom.xml

    if ! "${CLUSTER_DIR}/build_for_cluster.sh" > "${RESULT_DIR}/build_${label}.log" 2>&1; then
        echo "  ERROR: build failed (log: ${RESULT_DIR}/build_${label}.log)." >&2
        tail -20 "${RESULT_DIR}/build_${label}.log" >&2
        return 1
    fi

    # Verify rather than assume. A build that silently resolved the wrong artifact would
    # produce four plausible-looking recordings measuring two identical configurations.
    local resolved
    resolved="$(grep -o "djunits[^:]*\.jar" "${WORKSPACE}/cp.txt" | head -1 || true)"
    echo "  cp.txt resolves: ${resolved:-<nothing>}"
    case "${resolved}" in
        *"djunits-${version}.jar")
            echo "  OK — matches the requested version."
            ;;
        *)
            echo "  ERROR: expected djunits-${version}.jar on the classpath, found '${resolved}'." >&2
            echo "         Refusing to profile: the recordings would not measure what they claim to." >&2
            return 1
            ;;
    esac

    cp "${WORKSPACE}/cp.txt" "${cp_out}"
}

CP_STOCK="${RESULT_DIR}/cp_stock.txt"
CP_PATCHED="${RESULT_DIR}/cp_patched.txt"

build_variant "${STOCK_VERSION}" "stock" "${CP_STOCK}"
build_variant "${PATCHED_VERSION}" "patched" "${CP_PATCHED}"

# --------------------------------------------------------------------------------------
# The four runs
# --------------------------------------------------------------------------------------
run_cell() {
    local cell="$1" cp_file="$2" caching="$3" description="$4"

    local out_dir="${RESULT_DIR}/${cell}"
    local jfr_file="${RESULT_DIR}/${cell}.jfr"
    mkdir -p "${out_dir}"

    echo
    echo "----------------------------------------------------------"
    echo "[2/3] Cell ${cell}: ${description}"
    echo "----------------------------------------------------------"

    # shellcheck disable=SC2086  # JAVA_OPTS is intentionally word-split
    java -Xmx"${JAVA_HEAP}" ${JAVA_OPTS} \
        "-XX:StartFlightRecording=filename=${jfr_file},${JFR_SETTINGS},dumponexit=true" \
        "-XX:FlightRecorderOptions=stackdepth=${JFR_STACKDEPTH}" \
        -Dmirova.profileOut="${out_dir}" \
        -Dmirova.gtuPositionCaching="${caching}" \
        -cp "$(cat "${cp_file}")" "${MAIN_CLASS}" \
        > "${RESULT_DIR}/${cell}.log" 2>&1

    if [ ! -s "${jfr_file}" ]; then
        echo "  ERROR: no recording was written for cell ${cell}." >&2
        return 1
    fi
    echo "  recording: ${jfr_file} ($(wc -c < "${jfr_file}") bytes)"

    # Text dumps alongside the recording, so the results are readable without the jfr tool
    # being available wherever they are eventually analysed.
    jfr print --stack-depth "${JFR_STACKDEPTH}" --events jdk.ExecutionSample "${jfr_file}" \
        > "${RESULT_DIR}/${cell}_exec.txt" 2>/dev/null || true
    jfr print --stack-depth "${JFR_STACKDEPTH}" --events jdk.ObjectAllocationSample "${jfr_file}" \
        > "${RESULT_DIR}/${cell}_alloc.txt" 2>/dev/null || true
    echo "  samples: $(grep -c '^jdk.ExecutionSample' "${RESULT_DIR}/${cell}_exec.txt" 2>/dev/null || echo 0) CPU, $(grep -c '^jdk.ObjectAllocationSample' "${RESULT_DIR}/${cell}_alloc.txt" 2>/dev/null || echo 0) allocation"
}

run_cell A "${CP_STOCK}"   true  "stock djunits, position cache ON  (baseline)"
run_cell B "${CP_STOCK}"   false "stock djunits, position cache OFF"
run_cell C "${CP_PATCHED}" true  "patched djunits, position cache ON"
run_cell D "${CP_PATCHED}" false "patched djunits, position cache OFF"

# --------------------------------------------------------------------------------------
# Correctness across all four cells
# --------------------------------------------------------------------------------------
# Both variables under test are pure memoisation, so all four cells must produce identical
# output. Comparing every cell against A covers B and D, whose correctness has not been
# established anywhere else, and re-confirms C at no extra cost.
echo
echo "[3/3] Correctness — every cell must match cell A byte for byte"
CORRECTNESS_OK=1
for cell in B C D; do
    for f in detector_periodic.csv.zip detector_positions.csv.zip diffused_vehicles.csv; do
        a="${RESULT_DIR}/A/${f}"
        b="${RESULT_DIR}/${cell}/${f}"
        if [ ! -f "${a}" ] || [ ! -f "${b}" ]; then
            echo "  ${cell}/${f}: MISSING — cannot compare"
            CORRECTNESS_OK=0
            continue
        fi
        # The zips are compared by content rather than by bytes: the archive carries a
        # timestamp, so identical simulations still yield different zip bytes.
        if [ "${f%.zip}" != "${f}" ]; then
            ha="$(unzip -p "${a}" | sha256sum | cut -d' ' -f1)"
            hb="$(unzip -p "${b}" | sha256sum | cut -d' ' -f1)"
        else
            ha="$(sha256sum < "${a}" | cut -d' ' -f1)"
            hb="$(sha256sum < "${b}" | cut -d' ' -f1)"
        fi
        if [ "${ha}" = "${hb}" ]; then
            echo "  ${cell}/${f}: IDENTICAL  (${ha:0:16})"
        else
            echo "  ${cell}/${f}: DIFFERS    A=${ha:0:16} ${cell}=${hb:0:16}"
            CORRECTNESS_OK=0
        fi
    done
done

if [ "${CORRECTNESS_OK}" -ne 1 ]; then
    echo
    echo "  WARNING: not all cells produced identical output." >&2
    echo "           Both variables under test are supposed to be pure memoisation, so a" >&2
    echo "           difference is a correctness bug, not a tuning trade-off. Do not report" >&2
    echo "           the performance numbers below as a result until this is understood." >&2
fi

# --------------------------------------------------------------------------------------
# Consolidated table
# --------------------------------------------------------------------------------------
echo
echo "=========================================================="
echo "Consolidated comparison"
echo "=========================================================="
PYTHON_BIN="${MIROVA_PYTHON:-$(command -v python3 || command -v python || true)}"
if [ -n "${PYTHON_BIN}" ]; then
    "${PYTHON_BIN}" "${CLUSTER_DIR}/profile_matrix_report.py" "${RESULT_DIR}" \
        | tee "${RESULT_DIR}/comparison.txt"
else
    echo "No python found — the text dumps are in ${RESULT_DIR}."
    echo "Run the report elsewhere:  python3 cluster/profile_matrix_report.py <dir>"
fi

echo
echo "All results: ${RESULT_DIR}"
# The EXIT trap restores stock djunits and rebuilds from here.
