#!/usr/bin/env bash
#
# Watch one cell of the tamarampend study in the OTS animation.
#
# One JVM, one window, the TaMA driver. The parameters come from the study itself
# (TamaRampEndStudy.applyCell on TamaFinalValidationStudy.parameters), so what is on screen is the cell
# the campaign runs and not a second spelling of it.
#
# Usage:
#   cluster/run_gui.sh [--cell=lastresort] [--date=2025-09-22] [--from=16:00:00] [--to=18:00:00]
#                      [--seed=42] [--heap=4g] [--output=out/rampend-gui] [--dry-run]
#
# Cells: base, antic, lastresort, both.
#
# The window is shortened on purpose - the campaign runs 13:00 to 22:00 - so this is for looking and
# never for a number: a different window is a different warm-up and a different demand profile.
#
# Unlike run_local_parallel.sh this reads ots-*/target/classes directly rather than snapshotting them,
# because there is one run and nothing to protect it from except the IDE, which the stub guard below
# catches. It does need the same 'tama' classpath: without the bundle the run dies at t=0 with
# "matches 0 tactical planner provider(s)".
#
# Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved.
# BSD-style license. See OpenTrafficSim License.

set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

GUARD_LIB="$REPO/cluster/guard.sh"
[ -f "$GUARD_LIB" ] || { echo "REFUSING TO START: $GUARD_LIB not found" >&2; exit 2; }
# shellcheck source=guard.sh
source "$GUARD_LIB"
declare -F guard > /dev/null || { echo "REFUSING TO START: no guard functions" >&2; exit 2; }

HEAP=4g
DRY=0
RUN_ARGS=()

while [ $# -gt 0 ]; do
  case "$1" in
    --heap=*)    HEAP="${1#*=}" ;;
    --dry-run)   DRY=1 ;;
    --cell=*|--date=*|--demand=*|--from=*|--to=*|--seed=*|--output=*|--gui=*|--study=*|--route-room=*) RUN_ARGS+=("$1") ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

CP_CACHE="$REPO/cluster/.cp_local-tama.txt"
if [ ! -s "$CP_CACHE" ] || [ "$REPO/pom.xml" -nt "$CP_CACHE" ]; then
  echo "[cp] resolving runtime dependencies (cached in ${CP_CACHE#"$REPO/"})"
  mvn -o -q -pl ots-demo -Ptama dependency:build-classpath \
      -Dmdep.outputFile="$CP_CACHE" -Dmdep.includeScope=runtime || exit 1
fi
guard_nonempty "the tama classpath was resolved" "$(cat "$CP_CACHE")"
guard "the resolved classpath holds the TaMA bundle" grep -q "tama-ots-bundle" "$CP_CACHE"

MODULE_DIRS=$(ls -d "$REPO"/ots-*/target/classes 2>/dev/null)
guard_nonempty "some ots-*/target/classes exist -- run 'mvn compile -pl ots-demo -am' first" "$MODULE_DIRS"
# An Eclipse stub compiles and then throws at the line that matters, which in a GUI run reads as the
# model misbehaving. A condition, not a warning.
guard_empty "no class file carries an Eclipse 'Unresolved compilation' stub" \
    "$(grep -rl "Unresolved compilation" $MODULE_DIRS 2>/dev/null | head -3)"

# Windows paths, not the shell's. The JVM here is the Windows JDK and cannot read /d/... - the parallel
# runner never hit this only because its snapshot lives under %TEMP%, which is already Windows-rooted.
CP=""
for d in $MODULE_DIRS; do CP="$CP$(cygpath -w "$d");"; done
CP="$CP$(cat "$CP_CACHE")"

MAIN=org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.RunTamaRampEndGui

if [ "$DRY" -eq 1 ]; then
  echo "[gui] would run: java -Xmx$HEAP -cp <${#CP} chars> $MAIN ${RUN_ARGS[*]-}"
  exit 0
fi

echo "[gui] starting the animation; close the window to end the run"
exec java -Xmx"$HEAP" -cp "$CP" "$MAIN" ${RUN_ARGS[@]+"${RUN_ARGS[@]}"}
