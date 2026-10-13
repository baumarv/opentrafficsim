#!/usr/bin/env bash
#
# Idempotent build helper for running the scenario studies on bwUniCluster 3.0.
#
# Provisions Java 17 and Maven into the workspace (bwUniCluster 3.0 has no Java or Maven
# modules), builds all modules required by ots-demo, and writes the runtime classpath and
# directory layout the batch script expects.
#
# Safe to re-run: an existing working toolchain is reused without downloading. Maven always
# builds from clean, so a re-run never keeps a stale class (see step 2).
#
# Usage:  export MIROVA_WORKSPACE=<workspace name>
#         ./cluster/build_for_cluster.sh
#
# Copyright (c) 2026 Marvin Baumann / KIT.

set -euo pipefail

CLUSTER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# shellcheck source=cluster/mirova_env.sh
source "${CLUSTER_DIR}/mirova_env.sh"
# A failed 'source' does not stop a script, and every later guard would then be "command not found".
# shellcheck source=cluster/guard.sh
source "${CLUSTER_DIR}/guard.sh" || { echo "REFUSING TO CONTINUE: cannot source ${CLUSTER_DIR}/guard.sh" >&2; exit 2; }

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

# The commit this build is made from, recorded for the run metadata. Taken before compiling, so that it describes the
# sources Maven is about to read. A tree with uncommitted changes to tracked files is built all the same, but says so:
# the commit alone would then name code that did not run.
BUILD_COMMIT="$(git -C "${REPO_ROOT}" rev-parse HEAD)"
guard_nonempty "the checkout has a HEAD commit to record" "${BUILD_COMMIT}"
if [ -n "$(git -C "${REPO_ROOT}" status --porcelain --untracked-files=no)" ]; then
    echo "WARNING: the working tree has uncommitted changes; the build is recorded as ${BUILD_COMMIT}-dirty." >&2
    BUILD_COMMIT="${BUILD_COMMIT}-dirty"
fi
UPSTREAM_COMMIT="$(grep -v '^#' "${CLUSTER_DIR}/UPSTREAM_COMMIT" | grep -v '^[[:space:]]*$' | head -1 | tr -d '[:space:]')"
guard_nonempty "cluster/UPSTREAM_COMMIT names the upstream OTS commit this branch is based on" "${UPSTREAM_COMMIT}"

echo
echo "[2/4] Building and installing ots-demo and its module dependencies"
# 'install' (not 'package'): ots-demo resolves ots-road/ots-xml from the local .m2
# repository, so changes there only take effect once installed.
# Tests, Javadoc and JaCoCo are skipped: none of them is needed to run a study, and each costs minutes.
# 'clean' first. An incremental build keeps every class file already in target/classes: classes
# whose source has since been deleted, and 'Unresolved compilation problem' stubs an IDE wrote
# there. Neither is noticed until a run calls into it. A clean build costs a few minutes per
# checkout; a stale class costs a job array.
# The locale is pinned for the build JVM because ots-xml generates its JAXB classes into the tracked source tree, and
# the generator writes its Javadoc through a localised bundle: a build under a German locale rewrites the generated
# files and leaves the tree dirty.
MAVEN_OPTS="${MAVEN_OPTS:-} -Duser.language=en -Duser.country=US" \
    mvn clean install -pl ots-demo -am \
    -Dmaven.test.skip=true -Dmaven.javadoc.skip=true -Djacoco.skip=true

echo
echo "[3/4] Generating runtime classpath -> ${CP_FILE}"
mkdir -p "$(dirname "${CP_FILE}")"
mvn -pl ots-demo dependency:build-classpath -Dmdep.outputFile="${CP_FILE}" \
    -Dmaven.test.skip=true -Dmaven.javadoc.skip=true -Djacoco.skip=true

# Prepend the reactor module output directories, so that the classes just built win over any jar of the same
# module in the local Maven repository. The simulation is started with a direct java launch, not exec:java.
MODULE_CLASSES="${REPO_ROOT}/ots-demo/target/classes"
for module in ots-xml ots-swing ots-animation-data ots-animation ots-trafficcontrol ots-road ots-kpi ots-core ots-base; do
    if [ -d "${REPO_ROOT}/${module}/target/classes" ]; then
        MODULE_CLASSES="${MODULE_CLASSES}:${REPO_ROOT}/${module}/target/classes"
    fi
done

printf '%s:%s' "${MODULE_CLASSES}" "$(cat "${CP_FILE}")" > "${CP_FILE}.tmp"
mv "${CP_FILE}.tmp" "${CP_FILE}"

# Beside the classpath, because the two describe the same build: the batch script hands these to every run, which
# writes them into its run.properties.
BUILD_INFO_FILE="${CP_FILE%.txt}_build.env"
printf 'MIROVA_BUILD_COMMIT=%s\nMIROVA_UPSTREAM_COMMIT=%s\n' "${BUILD_COMMIT}" "${UPSTREAM_COMMIT}" > "${BUILD_INFO_FILE}"
echo "Build recorded in ${BUILD_INFO_FILE}: ${BUILD_COMMIT} on upstream OTS ${UPSTREAM_COMMIT}"

echo
echo "[4/4] Preparing workspace directory layout"
mkdir -p "${WORKSPACE}/demand" "${WORKSPACE}/output" "${WORKSPACE}/logs"

# The demand CSVs live in the repository, so copy them rather than leaving it to the
# submitter. Leaving it manual cost a whole job array once: seven dates were added to the
# study, the workspace still held the previous nine, and every array task died in about
# twenty seconds on the strict-mode check before a single simulation had started.
# Existing files are overwritten, which is what makes a regenerated demand set take effect;
# anything in the workspace that the repository does not know about is left alone.
if compgen -G "${CLUSTER_DIR}/demand/demand_*.csv" > /dev/null; then
  cp -p "${CLUSTER_DIR}"/demand/demand_*.csv "${WORKSPACE}/demand/"
  echo "Copied $(ls -1 "${CLUSTER_DIR}"/demand/demand_*.csv | wc -l) demand CSV(s) to ${WORKSPACE}/demand"
else
  echo "WARNING: no demand CSVs found in ${CLUSTER_DIR}/demand - the study will abort in strict mode."
fi

echo
echo "Done. Classpath written to ${CP_FILE} ($(wc -c < "${CP_FILE}") bytes)."
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
