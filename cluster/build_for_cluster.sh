#!/usr/bin/env bash
#
# Idempotent build helper for running MiRoVA studies on bwUniCluster 3.0.
#
# Provisions Java 17 and Maven into the workspace (bwUniCluster 3.0 has no Java or Maven
# modules), builds all modules required by ots-demo, and writes the runtime classpath and
# directory layout the batch script expects.
#
# Safe to re-run: an existing working toolchain is reused without downloading, and Maven
# rebuilds only what changed.
#
# Usage:  export MIROVA_WORKSPACE=<workspace name>
#         ./cluster/build_for_cluster.sh
#
# Copyright (c) 2026 Marvin Baumann / KIT.

set -euo pipefail

CLUSTER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# shellcheck source=cluster/mirova_env.sh
source "${CLUSTER_DIR}/mirova_env.sh"

WORKSPACE="$(resolve_workspace)"
REPO_ROOT="$(resolve_repo_root "${CLUSTER_DIR}")"
CP_FILE="${MIROVA_CP_FILE:-${WORKSPACE}/cp.txt}"

echo "Workspace:  ${WORKSPACE}"
echo "Repository: ${REPO_ROOT}"
warn_if_in_home "${REPO_ROOT}" "the repository"

echo
echo "[1/4] Toolchain"
provision_toolchain "${WORKSPACE}"
echo "  JAVA_HOME=${JAVA_HOME}"
echo "  $(mvn -version 2>/dev/null | head -1)"

cd "${REPO_ROOT}"

echo
echo "[2/4] Building and installing ots-demo and its module dependencies"
# 'install' (not 'package'): ots-demo resolves ots-road/ots-xml from the local .m2
# repository, so changes there only take effect once installed.
# All three skip flags are required — see docs/mirova/troubleshooting_and_compilation.md.
# -Dmaven.javadoc.skip=true in particular is what a plain -DskipTests build is missing:
# the javadoc plugin fails on pre-existing Javadoc issues in ots-road.
mvn install -pl ots-demo -am -Dmaven.test.skip=true -Dmaven.javadoc.skip=true -Djacoco.skip=true

echo
echo "[3/4] Generating runtime classpath -> ${CP_FILE}"
mkdir -p "$(dirname "${CP_FILE}")"
mvn -pl ots-demo dependency:build-classpath -Dmdep.outputFile="${CP_FILE}" \
    -Dmaven.test.skip=true -Dmaven.javadoc.skip=true -Djacoco.skip=true

# Prepend the reactor module output directories. Running the simulation via a direct java
# launch (rather than exec:java) avoids the GlassFish JAXB ClassLoader problems documented
# in docs/mirova/troubleshooting_and_compilation.md.
MODULE_CLASSES="${REPO_ROOT}/ots-demo/target/classes"
for module in ots-xml ots-road ots-core ots-base ots-kpi ots-animation ots-draw; do
    if [ -d "${REPO_ROOT}/${module}/target/classes" ]; then
        MODULE_CLASSES="${MODULE_CLASSES}:${REPO_ROOT}/${module}/target/classes"
    fi
done

printf '%s:%s' "${MODULE_CLASSES}" "$(cat "${CP_FILE}")" > "${CP_FILE}.tmp"
mv "${CP_FILE}.tmp" "${CP_FILE}"

echo
echo "[4/4] Preparing workspace directory layout"
mkdir -p "${WORKSPACE}/demand" "${WORKSPACE}/output" "${WORKSPACE}/logs"

echo
echo "Done. Classpath written to ${CP_FILE} ($(wc -c < "${CP_FILE}") bytes)."
echo "Put the pre-generated demand CSVs into: ${WORKSPACE}/demand"
echo
echo "Next: determine the array size for the study you want to run, e.g."
echo "  java -cp \"\$(cat ${CP_FILE})\" \\"
echo "    org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.RunMirovaClusterStudy \\"
echo "    --study=dates --output=${WORKSPACE}/output/dates \\"
echo "    --dates=${CLUSTER_DIR}/dates.txt --demand=${WORKSPACE}/demand --count"
echo "then set '#SBATCH --array=0-<N-1>' in cluster/run_mirova.sbatch and submit:"
echo "  export MIROVA_CLUSTER_DIR=${CLUSTER_DIR}"
echo "  sbatch --chdir=${WORKSPACE} ${CLUSTER_DIR}/run_mirova.sbatch"
echo
echo "(The export is required: sbatch runs a copy of the script from the job spool"
echo " directory, so it cannot find mirova_env.sh next to itself.)"
