#!/usr/bin/env bash
#
# Run a MiRoVA study locally, N runs at a time, one JVM per run.
#
# The cluster addresses runs by index through RunMirovaClusterStudy; so does this. A run is
# single-threaded, so parallelism here is simply "how many JVMs at once", and the only real
# constraints are cores and memory: SLOTS x JAVA_HEAP has to fit in RAM with room for each JVM's
# non-heap footprint (roughly 0.5-1 GB).
#
# Each run gets its own log and, when the defect diagnostics are on, its own CSV -- the counters
# are process-wide and written by a shutdown hook, so two runs sharing one file would overwrite
# each other.
#
# Usage:
#   cluster/run_local_parallel.sh --study=phase05 --output=<dir> [--slots=12] [--heap=6g]
#                                 [--diag] [--dry-run] [-- <study options>]
#
# Example -- the Phase 0.5 instrumentation run:
#   cluster/run_local_parallel.sh --study=phase05 --output=out/phase05-instr --slots=12 --diag -- \
#       --variants=reference --dates=2025-10-27,2025-09-22,2025-10-07 \
#       --demand=cluster/demand --replications=5
#
# Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved.
# BSD-style license. See OpenTrafficSim License.

set -u

SLOTS=12
HEAP=6g
DIAG=0
DRY=0
STUDY=""
OUTPUT=""
STUDY_ARGS=()

while [ $# -gt 0 ]; do
  case "$1" in
    --study=*)  STUDY="${1#*=}" ;;
    --output=*) OUTPUT="${1#*=}" ;;
    --slots=*)  SLOTS="${1#*=}" ;;
    --heap=*)   HEAP="${1#*=}" ;;
    --diag)     DIAG=1 ;;
    --dry-run)  DRY=1 ;;
    --)         shift; STUDY_ARGS=("$@"); break ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

if [ -z "$STUDY" ] || [ -z "$OUTPUT" ]; then
  echo "usage: $0 --study=<name> --output=<dir> [--slots=N] [--heap=6g] [--diag] [--dry-run] -- <study options>" >&2
  exit 2
fi

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO" || exit 1
mkdir -p "$OUTPUT/logs" || exit 1

MAIN_CLASS="org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.RunMirovaClusterStudy"
CP_CACHE="$REPO/cluster/.cp_local.txt"

# --- classpath -------------------------------------------------------------
# Module classes first, so a rebuilt class wins over anything in a jar, then the resolved runtime
# dependencies. The dependency list is cached: resolving it costs a Maven round trip and changes
# only when the poms do.
if [ ! -s "$CP_CACHE" ] || [ "$REPO/pom.xml" -nt "$CP_CACHE" ]; then
  echo "[cp] resolving runtime dependencies (cached in ${CP_CACHE#$REPO/})"
  mvn -o -q -pl ots-demo dependency:build-classpath \
      -Dmdep.outputFile="$CP_CACHE" -Dmdep.includeScope=runtime || exit 1
fi
# The IDE compiles into the same target/classes and, when it sees a transiently inconsistent tree,
# writes classes whose method bodies are `throw new Error("Unresolved compilation problem")`. Those
# compile-and-run, and fail only when the method is first called -- which is how a whole campaign can
# die at t=0 after a green Maven build. So: snapshot the classes, refuse to start if any carries a
# stub, and run the campaign against the snapshot, where the IDE cannot reach it.
# Outside the working tree on purpose. Putting it under the output directory left 2500 class
# files inside the repository, which every Git-aware tool then reports as untracked changes.
SNAPSHOT="${TMPDIR:-${TEMP:-/tmp}}/mirova-classes-$$"
trap 'rm -rf "$SNAPSHOT"' EXIT
rm -rf "$SNAPSHOT" && mkdir -p "$SNAPSHOT" || exit 1
MODULE_DIRS=$(ls -d "$REPO"/ots-*/target/classes 2>/dev/null)
[ -n "$MODULE_DIRS" ] || { echo "[cp] no ots-*/target/classes -- run mvn compile first" >&2; exit 1; }
for d in $MODULE_DIRS; do
  m=$(basename "$(dirname "$(dirname "$d")")")
  cp -r "$d" "$SNAPSHOT/$m" || exit 1
done
STUBS=$(grep -rl "Unresolved compilation" "$SNAPSHOT" 2>/dev/null | wc -l)
if [ "$STUBS" -gt 0 ]; then
  echo "[cp] REFUSING TO RUN: $STUBS class file(s) contain an Eclipse 'Unresolved compilation problem' stub." >&2
  grep -rl "Unresolved compilation" "$SNAPSHOT" 2>/dev/null | sed "s|$SNAPSHOT/||" | head -10 >&2
  echo "[cp] rebuild with the recipe in docs/mirova/troubleshooting_and_compilation.md:" >&2
  echo "[cp]   mvn clean install -pl ots-demo -am -Dmaven.test.skip=true -Dmaven.javadoc.skip=true -Djacoco.skip=true" >&2
  exit 1
fi
echo "[cp] snapshot verified clean: no compilation stubs"
MODULES=""
for d in "$SNAPSHOT"/*; do
  [ -d "$d" ] && MODULES="$MODULES$d;"
done
CP="$MODULES$(cat "$CP_CACHE")"


# --- how many runs ---------------------------------------------------------
TOTAL="$(java -cp "$CP" "$MAIN_CLASS" --study="$STUDY" --output="$OUTPUT" --count "${STUDY_ARGS[@]}" 2>/dev/null | tail -1)"
case "$TOTAL" in
  ''|*[!0-9]*) echo "could not determine the run count; re-run without --count suppression:" >&2
               java -cp "$CP" "$MAIN_CLASS" --study="$STUDY" --output="$OUTPUT" --count "${STUDY_ARGS[@]}"
               exit 1 ;;
esac

java -cp "$CP" "$MAIN_CLASS" --study="$STUDY" --output="$OUTPUT" \
     --manifest="$OUTPUT/manifest.txt" --count "${STUDY_ARGS[@]}" >/dev/null 2>&1

echo "[plan] study=$STUDY runs=$TOTAL slots=$SLOTS heap=$HEAP diag=$DIAG"
echo "[plan] output=$OUTPUT"
[ "$DIAG" = 1 ] && echo "[plan] defect counters on; one CSV per run in $OUTPUT/defects/"
if [ "$DRY" = 1 ]; then echo "[plan] dry run, nothing executed"; exit 0; fi
[ "$DIAG" = 1 ] && mkdir -p "$OUTPUT/defects"

# --- execute ---------------------------------------------------------------
START=$(date +%s)
FAILED=0
running=0

for i in $(seq 0 $((TOTAL - 1))); do
  # -XX:ActiveProcessorCount=1 as cluster/README.md prescribes for the local loop: a run is
  # single-threaded, and without it each JVM sizes its GC and common pool for all 32 cores,
  # so twelve of them oversubscribe the machine several times over.
  OPTS=(-Xmx"$HEAP" -XX:ActiveProcessorCount=1 -Djava.awt.headless=true)
  if [ "$DIAG" = 1 ]; then
    OPTS+=(-Dmirova.defectDiag=true -Dmirova.defectDiagFile="$OUTPUT/defects/run_${i}.csv")
  fi
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
echo "[done] $OK ok, $FAILED failed, $(( (END - START) / 60 )) min wall clock"

if [ "$DIAG" = 1 ]; then
  # One table over all runs: the counters are per process, so the campaign total is their sum.
  python "$REPO/cluster/sum_defects.py" "$OUTPUT/defects" > "$OUTPUT/defects_summary.txt" 2>/dev/null \
    && { echo; cat "$OUTPUT/defects_summary.txt"; } \
    || echo "[warn] could not summarise $OUTPUT/defects"
fi

[ "$FAILED" -eq 0 ] || exit 1
