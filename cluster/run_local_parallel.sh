#!/usr/bin/env bash
#
# Run a study locally, N runs at a time, one JVM per run.
#
# The cluster addresses runs by index through RunMirovaClusterStudy; so does this. A run is
# single-threaded, so parallelism here is simply "how many JVMs at once", and the only real
# constraints are cores and memory: SLOTS x heap has to fit in RAM with room for each JVM's
# non-heap footprint (roughly 0.5-1 GB).
#
# Usage:
#   cluster/run_local_parallel.sh --study=dates --output=<dir> [--slots=12] [--heap=6g]
#                                 [--jvm-opt=-Dx=y]... [--dry-run] [-- <study options>]
#
# --jvm-opt is passed to every run's JVM and may be repeated. A scenario switch read from a
# system property - FreiburgNord's mirova.samplerLinks, for one - reaches the run no other way,
# because the runs are separate JVMs this script starts itself.
#
# The driver model and its parameter set are study options, as on the cluster:
#   --model=lmrs (default)   --lmrsParameters=default|scenario
#
# Example - a 15-minute smoke test of one day on the upstream LMRS:
#   cluster/run_local_parallel.sh --study=dates --output=out/smoke --slots=1 -- \
#       --dates=2025-09-16 --demand=cluster/demand --strict=true --replications=1 \
#       --model=lmrs --simulationTime=900s
#
# Build first, from clean, after every checkout:
#   MAVEN_OPTS="-Duser.language=en -Duser.country=US" \
#       mvn clean install -pl ots-demo -am -Dmaven.test.skip=true -Dmaven.javadoc.skip=true -Djacoco.skip=true
#
# Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved.
# BSD-style license. See OpenTrafficSim License.

set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# A failed 'source' does not stop a script, and every later guard would then be "command not found".
# shellcheck source=cluster/guard.sh
source "$REPO/cluster/guard.sh" || { echo "REFUSING TO CONTINUE: cannot source $REPO/cluster/guard.sh" >&2; exit 2; }

SLOTS=12
HEAP=6g
DRY=0
JVM_OPTS=()
STUDY=""
OUTPUT=""
STUDY_ARGS=()

while [ $# -gt 0 ]; do
  case "$1" in
    --study=*)   STUDY="${1#*=}" ;;
    --output=*)  OUTPUT="${1#*=}" ;;
    --slots=*)   SLOTS="${1#*=}" ;;
    --heap=*)    HEAP="${1#*=}" ;;
    --jvm-opt=*) JVM_OPTS+=("${1#*=}") ;;
    --dry-run)   DRY=1 ;;
    --)          shift; STUDY_ARGS=("$@"); break ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

if [ -z "$STUDY" ] || [ -z "$OUTPUT" ]; then
  echo "usage: $0 --study=<name> --output=<dir> [--slots=N] [--heap=6g] [--jvm-opt=-Dx=y]... [--dry-run] -- <study options>" >&2
  exit 2
fi

cd "$REPO" || exit 1
guard "the output directory $OUTPUT/logs can be created" mkdir -p "$OUTPUT/logs"

MAIN_CLASS="org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.RunMirovaClusterStudy"
CP_CACHE="$REPO/cluster/.cp_local.txt"

# The JVM takes its class path with the separator of the platform it runs on.
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
  *)                    SEP=':' ;;
esac

# --- classpath -------------------------------------------------------------
# Module classes first, so a rebuilt class wins over anything in a jar, then the resolved runtime
# dependencies. The dependency list is cached: resolving it costs a Maven round trip and changes
# only when the poms do.
if [ ! -s "$CP_CACHE" ] || [ "$REPO/pom.xml" -nt "$CP_CACHE" ] || [ "$REPO/ots-demo/pom.xml" -nt "$CP_CACHE" ]; then
  echo "[cp] resolving runtime dependencies (cached in ${CP_CACHE#"$REPO"/})"
  guard "Maven resolves the runtime classpath of ots-demo" \
      mvn -q -pl ots-demo dependency:build-classpath -Dmdep.outputFile="$CP_CACHE" -Dmdep.includeScope=runtime
fi
guard_nonempty "the cached classpath ${CP_CACHE#"$REPO"/} has content" "$(cat "$CP_CACHE" 2>/dev/null)"

# An IDE compiles into the same target/classes and, when it sees a transiently inconsistent tree,
# writes classes whose method bodies are `throw new Error("Unresolved compilation problem")`. Those
# load and link, and fail only when the method is first called - which is how a whole campaign can
# die at t=0 after a green Maven build. So: snapshot the classes, refuse to start if any carries a
# stub, and run the campaign against the snapshot, where the IDE cannot reach it.
# Outside the working tree on purpose, so that no Git-aware tool reports thousands of untracked files.
SNAPSHOT="${TMPDIR:-${TEMP:-/tmp}}/mirova-classes-$$"
trap 'rm -rf "$SNAPSHOT"' EXIT
guard "the class snapshot directory $SNAPSHOT can be created" mkdir -p "$SNAPSHOT"
MODULE_DIRS="$(ls -d "$REPO"/ots-*/target/classes 2>/dev/null)"
guard_nonempty "the modules are built (ots-*/target/classes exist); build first, see the header" "$MODULE_DIRS"
guard "ots-demo is built" test -d "$REPO/ots-demo/target/classes"
for d in $MODULE_DIRS; do
  m="$(basename "$(dirname "$(dirname "$d")")")"
  guard "the classes of $m can be copied into the snapshot" cp -r "$d" "$SNAPSHOT/$m"
done
guard_empty "no class file carries an Eclipse 'Unresolved compilation problem' stub; rebuild from clean" \
    "$(grep -rl "Unresolved compilation" "$SNAPSHOT" 2>/dev/null | head -10)"
echo "[cp] snapshot verified clean: no compilation stubs"

MODULES="$SNAPSHOT/ots-demo$SEP"
for d in "$SNAPSHOT"/*; do
  if [ -d "$d" ] && [ "$d" != "$SNAPSHOT/ots-demo" ]; then MODULES="$MODULES$d$SEP"; fi
done
CP="$MODULES$(cat "$CP_CACHE")"

# --- which code this is ----------------------------------------------------
# Handed to every run for its run.properties. The classes run from a snapshot outside the checkout,
# so a run could not ask git itself.
BUILD_COMMIT="$(git -C "$REPO" rev-parse HEAD)"
guard_nonempty "the checkout has a HEAD commit to record" "$BUILD_COMMIT"
if [ -n "$(git -C "$REPO" status --porcelain --untracked-files=no)" ]; then
  echo "[build] WARNING: uncommitted changes to tracked files; recorded as ${BUILD_COMMIT}-dirty" >&2
  BUILD_COMMIT="${BUILD_COMMIT}-dirty"
fi
UPSTREAM_COMMIT="$(grep -v '^#' "$REPO/cluster/UPSTREAM_COMMIT" | grep -v '^[[:space:]]*$' | head -1 | tr -d '[:space:]')"
guard_nonempty "cluster/UPSTREAM_COMMIT names the upstream OTS commit this branch is based on" "$UPSTREAM_COMMIT"

# -XX:ActiveProcessorCount=1: a run is single-threaded, and without it each JVM sizes its GC and
# common pool for every core, so twelve of them oversubscribe the machine several times over.
# -Duser.language/-Duser.country/-Dfile.encoding: the same locale and encoding the cluster runs under,
# so a local run reads its input and writes its output identically. DJUnits parses numbers through the
# default FORMAT locale - on a German machine "2.3 mm" in a network XML would read as 23 mm - and the
# platform charset decides how the one non-ASCII character of the trajectory header is written.
OPTS=(-Xmx"$HEAP" -XX:ActiveProcessorCount=1 -Djava.awt.headless=true
      -Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8
      -Dmirova.gitCommit="$BUILD_COMMIT" -Dmirova.upstreamCommit="$UPSTREAM_COMMIT")
if [ ${#JVM_OPTS[@]} -gt 0 ]; then OPTS+=("${JVM_OPTS[@]}"); fi

# --- how many runs ---------------------------------------------------------
TOTAL="$(java "${OPTS[@]}" -cp "$CP" "$MAIN_CLASS" --study="$STUDY" --output="$OUTPUT" --count "${STUDY_ARGS[@]}" 2>/dev/null | tail -1 | tr -d '[:space:]')"
case "$TOTAL" in
  ''|*[!0-9]*) echo "could not determine the run count; this is what the study said:" >&2
               java "${OPTS[@]}" -cp "$CP" "$MAIN_CLASS" --study="$STUDY" --output="$OUTPUT" --count "${STUDY_ARGS[@]}"
               exit 1 ;;
esac

echo "[plan] study=$STUDY runs=$TOTAL slots=$SLOTS heap=$HEAP"
echo "[plan] output=$OUTPUT"
echo "[plan] build=$BUILD_COMMIT on upstream OTS $UPSTREAM_COMMIT"
if [ "$DRY" = 1 ]; then
  for i in $(seq 0 $((TOTAL - 1))); do
    printf '[dry run] run %s:' "$i"
    printf ' %q' java "${OPTS[@]}" -cp "<snapshot of ots-*/target/classes>$SEP<${CP_CACHE#"$REPO"/}>" "$MAIN_CLASS" \
        --study="$STUDY" --output="$OUTPUT" --index="$i" "${STUDY_ARGS[@]}"
    printf '\n'
  done
  echo "[plan] dry run, nothing executed"
  exit 0
fi

java "${OPTS[@]}" -cp "$CP" "$MAIN_CLASS" --study="$STUDY" --output="$OUTPUT" \
     --manifest="$OUTPUT/manifest.txt" --count "${STUDY_ARGS[@]}" >/dev/null 2>&1

# --- execute ---------------------------------------------------------------
START=$(date +%s)
running=0
: > "$OUTPUT/logs/.status"

for i in $(seq 0 $((TOTAL - 1))); do
  (
    if java "${OPTS[@]}" -cp "$CP" "$MAIN_CLASS" \
         --study="$STUDY" --output="$OUTPUT" --index="$i" "${STUDY_ARGS[@]}" \
         > "$OUTPUT/logs/run_${i}.log" 2>&1; then
      echo "ok $i" >> "$OUTPUT/logs/.status"
    else
      echo "FAIL $i" >> "$OUTPUT/logs/.status"
    fi
  ) &
  running=$((running + 1))
  if [ "$running" -ge "$SLOTS" ]; then
    wait -n 2>/dev/null || wait
    running=$((running - 1))
    done_n=$(wc -l < "$OUTPUT/logs/.status" 2>/dev/null | tr -d " ")
    now=$(date +%s)
    echo "[progress] $done_n/$TOTAL finished after $(( (now - START) / 60 )) min"
  fi
done
wait

END=$(date +%s)
OK=$(grep -c '^ok ' "$OUTPUT/logs/.status" 2>/dev/null); OK=${OK:-0}
FAILED=$(grep -c '^FAIL ' "$OUTPUT/logs/.status" 2>/dev/null); FAILED=${FAILED:-0}
echo "[done] $OK ok, $FAILED failed, $(( END - START )) s wall clock"

# The exit status is the result: a campaign with a failed run is a failed campaign.
[ "$FAILED" -eq 0 ] || exit 1
[ "$OK" -eq "$TOTAL" ] || { echo "[done] only $OK of $TOTAL runs reported; treating as failure" >&2; exit 1; }
