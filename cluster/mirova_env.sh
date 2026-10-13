#!/usr/bin/env bash
#
# Shared environment for the MiRoVA cluster scripts (bwUniCluster 3.0).
# Sourced by build_for_cluster.sh and run_mirova.sbatch — not meant to be executed directly.
#
# This is the SINGLE definition of the workspace location and of the Java/Maven toolchain.
# There is deliberately no 'module load' anywhere: bwUniCluster 3.0 provides NO Java and NO
# Maven modules ('module spider' lists only CAE/simulation software plus Python/R/Julia/
# Matlab), so the toolchain is provisioned into the workspace instead.
#
# Copyright (c) 2026 Marvin Baumann / KIT.

# --------------------------------------------------------------------------------------
# Download endpoints — both verified by hand on the login node.
# --------------------------------------------------------------------------------------
# Adoptium's API redirects to the correct versioned asset. The naive GitHub
# "releases/latest/download/<generic-filename>" URL 404s, because GitHub requires the
# literal versioned asset filename.
MIROVA_JDK_URL="${MIROVA_JDK_URL:-https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse}"

# archive.apache.org, NOT dlcdn.apache.org: dlcdn only mirrors currently-supported releases
# and answers with a ~200-byte error page for 3.9.9.
MIROVA_MAVEN_VERSION="${MIROVA_MAVEN_VERSION:-3.9.9}"
MIROVA_MAVEN_URL="${MIROVA_MAVEN_URL:-https://archive.apache.org/dist/maven/maven-3/${MIROVA_MAVEN_VERSION}/binaries/apache-maven-${MIROVA_MAVEN_VERSION}-bin.tar.gz}"

# Sanity thresholds for the downloads (bytes). Both endpoints answer with small HTML/text
# error pages on failure, which is exactly what these catch.
MIROVA_JDK_MIN_BYTES=$((100 * 1024 * 1024))   # real archive is ~185 MB
MIROVA_MAVEN_MIN_BYTES=$((4 * 1024 * 1024))   # real archive is ~8.7 MB

# Resolves the bwHPC workspace named by $MIROVA_WORKSPACE and echoes its path.
#
# Deliberately fails instead of falling back to $HOME: $HOME is small, slow for
# simulation I/O and subject to quotas, so silently landing there would either fill it
# up or cripple the runs. Allocate a workspace first:
#
#     ws_allocate <name> <days>
#     export MIROVA_WORKSPACE=<name>
#
resolve_workspace() {
    if [ -n "${MIROVA_WORKSPACE_PATH:-}" ]; then
        # Escape hatch for testing off-cluster, where ws_find does not exist.
        echo "${MIROVA_WORKSPACE_PATH}"
        return 0
    fi

    if [ -z "${MIROVA_WORKSPACE:-}" ]; then
        echo "ERROR: MIROVA_WORKSPACE is not set." >&2
        echo "       Allocate a workspace and export its name, e.g.:" >&2
        echo "           ws_allocate mirova <days>" >&2
        echo "           export MIROVA_WORKSPACE=mirova" >&2
        echo "       (choose the lifetime yourself; see 'ws_allocate --help')" >&2
        return 1
    fi

    if ! command -v ws_find >/dev/null 2>&1; then
        echo "ERROR: 'ws_find' not found — are you on a bwUniCluster login/compute node?" >&2
        return 1
    fi

    local workspace_path
    workspace_path="$(ws_find "${MIROVA_WORKSPACE}" 2>/dev/null || true)"

    if [ -z "${workspace_path}" ] || [ ! -d "${workspace_path}" ]; then
        echo "ERROR: workspace '${MIROVA_WORKSPACE}' could not be resolved via ws_find." >&2
        echo "       Existing workspaces:" >&2
        ws_list >&2 2>/dev/null || echo "       (ws_list unavailable)" >&2
        echo "       Create it with:  ws_allocate ${MIROVA_WORKSPACE} <days>" >&2
        return 1
    fi

    echo "${workspace_path}"
}

# Warns if the given path lies under $HOME, which is not intended for simulation I/O.
warn_if_in_home() {
    local path="$1"
    local label="$2"
    case "${path}" in
        "${HOME}"/*|"${HOME}")
            echo "WARNING: ${label} is inside \$HOME (${path})." >&2
            echo "         \$HOME is small and not intended for simulation I/O; prefer the workspace." >&2
            ;;
    esac
}

# Echoes the repository root: the directory containing this script's parent, validated to be
# the reactor root. Derived from the script location, so it is correct no matter where the
# repository was cloned or how deeply it is nested inside the workspace.
resolve_repo_root() {
    local cluster_dir="$1"
    local repo_root
    repo_root="$(cd "${cluster_dir}/.." && pwd)"

    if [ ! -f "${repo_root}/pom.xml" ] || ! grep -q "<module>ots-demo</module>" "${repo_root}/pom.xml" 2>/dev/null; then
        echo "ERROR: ${repo_root} does not look like the OpenTrafficSim reactor root" >&2
        echo "       (no pom.xml declaring the ots-demo module was found there)." >&2
        echo "       This script must stay in <repository>/cluster/ so it can locate the project." >&2
        echo "       If you cloned without the trailing '.', the repository is one level deeper," >&2
        echo "       e.g. \$WORKSPACE/opentrafficsim — run that copy's cluster/ scripts instead." >&2
        return 1
    fi

    echo "${repo_root}"
}

# Downloads a URL and refuses to accept anything that is not a real gzip archive.
#
# Both endpoints answer failures with a small HTML/text page and curl still exits 0, so a
# naive download leaves a file that only fails later, inside tar, with a cryptic
# "gzip: stdin: not in gzip format". This catches it at the source, with context.
#
#   $1 url, $2 destination file, $3 minimum bytes, $4 label, $5 host hint
download_and_verify() {
    local url="$1" dest="$2" min_bytes="$3" label="$4" host="$5"

    echo "  Downloading ${label} from ${url}"
    if ! curl -fL --retry 3 --retry-delay 5 -o "${dest}" "${url}"; then
        echo "ERROR: ${label} download failed — curl could not fetch ${url}." >&2
        echo "       Check network/proxy access to ${host} from this node." >&2
        rm -f "${dest}"
        return 1
    fi

    local size
    size="$(wc -c < "${dest}" | tr -d '[:space:]')"
    if [ "${size}" -lt "${min_bytes}" ]; then
        echo "ERROR: ${label} download failed — got ${size} bytes, expected at least ${min_bytes}." >&2
        echo "       This is the signature of an error page returned with HTTP 200." >&2
        echo "       Check network/proxy access to ${host}, and that the URL still serves this version." >&2
        echo "       First bytes of what was received:" >&2
        head -c 200 "${dest}" >&2 || true
        echo >&2
        rm -f "${dest}"
        return 1
    fi

    if ! tar tzf "${dest}" >/dev/null 2>&1; then
        echo "ERROR: ${label} download failed — ${dest} (${size} bytes) is not a readable gzip archive." >&2
        echo "       Check network/proxy access to ${host}." >&2
        rm -f "${dest}"
        return 1
    fi

    echo "  OK: ${label} archive verified (${size} bytes)."
}

# Provisions Java 17 and Maven into <workspace>/tools, idempotently: an existing, working
# install is reused and nothing is downloaded.
#   $1 workspace path
provision_toolchain() {
    local workspace="$1"
    local tools_dir="${workspace}/tools"
    mkdir -p "${tools_dir}"

    # --- Java ---
    if activate_toolchain "${workspace}" quiet && java -version >/dev/null 2>&1; then
        echo "Toolchain already present: $(java -version 2>&1 | head -1)"
    else
        if [ ! -f "$(_mirova_find_java_home "${tools_dir}")/bin/java" ]; then
            echo "Provisioning Java 17 into ${tools_dir}"
            local jdk_archive="${tools_dir}/jdk17.tar.gz"
            download_and_verify "${MIROVA_JDK_URL}" "${jdk_archive}" "${MIROVA_JDK_MIN_BYTES}" \
                "Java 17" "api.adoptium.net" || return 1
            tar xzf "${jdk_archive}" -C "${tools_dir}"
            rm -f "${jdk_archive}"
        fi

        if [ ! -f "${tools_dir}/apache-maven-${MIROVA_MAVEN_VERSION}/bin/mvn" ]; then
            echo "Provisioning Maven ${MIROVA_MAVEN_VERSION} into ${tools_dir}"
            local maven_archive="${tools_dir}/maven.tar.gz"
            download_and_verify "${MIROVA_MAVEN_URL}" "${maven_archive}" "${MIROVA_MAVEN_MIN_BYTES}" \
                "Maven ${MIROVA_MAVEN_VERSION}" "archive.apache.org" || return 1
            tar xzf "${maven_archive}" -C "${tools_dir}"
            rm -f "${maven_archive}"
        fi

        activate_toolchain "${workspace}" || return 1
    fi
}

# Echoes the JDK directory inside the given tools directory, if any.
_mirova_find_java_home() {
    local tools_dir="$1"
    if [ -n "${MIROVA_JAVA_HOME:-}" ]; then
        echo "${MIROVA_JAVA_HOME}"
        return 0
    fi
    # The extracted directory carries the exact version, e.g. jdk-17.0.13+11
    local candidate
    candidate="$(find "${tools_dir}" -maxdepth 1 -type d -name 'jdk*' 2>/dev/null | sort | tail -1)"
    echo "${candidate}"
}

# Exports JAVA_HOME and prepends the Java and Maven bin directories to PATH.
# This is the single definition used by the build script, the batch script and the README.
#   $1 workspace path, $2 optional "quiet" to suppress the error message
activate_toolchain() {
    local workspace="$1"
    local quiet="${2:-}"
    local tools_dir="${workspace}/tools"

    local java_home maven_home
    java_home="$(_mirova_find_java_home "${tools_dir}")"
    maven_home="${MIROVA_MAVEN_HOME:-${tools_dir}/apache-maven-${MIROVA_MAVEN_VERSION}}"

    # Presence, not the executable bit: some filesystems do not report it reliably, and a
    # false negative here would send a correctly provisioned run back to the download path.
    if [ -z "${java_home}" ] || [ ! -f "${java_home}/bin/java" ] || [ ! -f "${maven_home}/bin/mvn" ]; then
        if [ "${quiet}" != "quiet" ]; then
            echo "ERROR: no usable Java/Maven toolchain in ${tools_dir}." >&2
            echo "       Run cluster/build_for_cluster.sh first; it provisions both into the workspace." >&2
            echo "       (bwUniCluster 3.0 has no Java or Maven modules, so there is nothing to 'module load'.)" >&2
        fi
        return 1
    fi

    export JAVA_HOME="${java_home}"
    export PATH="${java_home}/bin:${maven_home}/bin:${PATH}"
}
