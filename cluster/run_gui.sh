#!/usr/bin/env bash
#
# Open one day of the Freiburg-Nord scenario in the OTS GUI from a shell.
#
# There is nothing to set here. The settings - day, seed, driver model, how the LMRS is composed, single
# driver parameters - are code at the top of
#   ots-demo/src/main/java/org/opentrafficsim/demo/mirova/scenariomanagement/scenarios/RunFreiburgNordGui.java
# which is normally run from an IDE. This script only spares building the class path by hand.
#
#   cluster/run_gui.sh             # start the GUI
#   cluster/run_gui.sh --dry-run   # print the java command and start nothing
#
# Build first, after every change to the class (the classes are taken from ots-*/target/classes):
#   MAVEN_OPTS="-Duser.language=en -Duser.country=US" #       mvn install -pl ots-demo -am -Dmaven.test.skip=true -Dmaven.javadoc.skip=true -Djacoco.skip=true
#
# Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved.
# BSD-style license. See OpenTrafficSim License.

HEAP="4g"

set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Under Git Bash the JVM is a Windows program: it needs "D:/..." paths, and a class path joined with ';' is not
# translated for it the way a single "/d/..." argument is.
if command -v cygpath >/dev/null 2>&1; then REPO="$(cygpath -m "$REPO")"; fi
# A failed 'source' does not stop a script, and every later guard would then be "command not found".
# shellcheck source=cluster/guard.sh
source "$REPO/cluster/guard.sh" || { echo "REFUSING TO CONTINUE: cannot source $REPO/cluster/guard.sh" >&2; exit 2; }

DRY=0
EXTRA_ARGS=()
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY=1 ;;
    *) echo "unknown argument: $arg (the only option is --dry-run; the settings are in RunFreiburgNordGui.java)" >&2; exit 2 ;;
  esac
done

cd "$REPO" || exit 1

ARGS=()
if [ ${#EXTRA_ARGS[@]} -gt 0 ]; then ARGS=("${EXTRA_ARGS[@]}"); fi

# The JVM takes its class path with the separator of the platform it runs on.
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
  *)                    SEP=':' ;;
esac

# Module classes first, so a rebuilt class wins over anything in a jar, then the resolved dependencies.
CP_CACHE="$REPO/cluster/.cp_local.txt"
if [ ! -s "$CP_CACHE" ] || [ "$REPO/pom.xml" -nt "$CP_CACHE" ] || [ "$REPO/ots-demo/pom.xml" -nt "$CP_CACHE" ]; then
  echo "[cp] resolving runtime dependencies (cached in ${CP_CACHE#"$REPO"/})"
  guard "Maven resolves the runtime classpath of ots-demo" \
      mvn -q -pl ots-demo dependency:build-classpath -Dmdep.outputFile="$CP_CACHE" -Dmdep.includeScope=runtime
fi
guard_nonempty "the cached classpath ${CP_CACHE#"$REPO"/} has content" "$(cat "$CP_CACHE" 2>/dev/null)"
guard "ots-demo is built (see the header for the build command)" test -d "$REPO/ots-demo/target/classes"
guard_empty "no class file carries an Eclipse 'Unresolved compilation problem' stub; rebuild from clean" \
    "$(grep -rl "Unresolved compilation" "$REPO"/ots-*/target/classes 2>/dev/null | head -10)"

CP="$REPO/ots-demo/target/classes"
for d in "$REPO"/ots-*/target/classes; do
  if [ "$d" != "$REPO/ots-demo/target/classes" ]; then CP="$CP$SEP$d"; fi
done
CP="$CP$SEP$(cat "$CP_CACHE")"

# The same locale and encoding as the evaluated runs, so that what is watched is what is run.
OPTS=(-Xmx"$HEAP" -Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8)
MAIN_CLASS="org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.RunFreiburgNordGui"

if [ "$DRY" = 1 ]; then
  printf '[dry run]'
  printf ' %q' java "${OPTS[@]}" -cp "<ots-*/target/classes>$SEP<${CP_CACHE#"$REPO"/}>" "$MAIN_CLASS" ${ARGS[@]+"${ARGS[@]}"}
  printf '\n'
  exit 0
fi

if [ ${#ARGS[@]} -gt 0 ]; then
  exec java "${OPTS[@]}" -cp "$CP" "$MAIN_CLASS" "${ARGS[@]}"
fi
exec java "${OPTS[@]}" -cp "$CP" "$MAIN_CLASS"
