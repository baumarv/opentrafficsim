#!/usr/bin/env bash
# Watch the on-ramp merge in the GUI, at a time of day when the queue forms.
#
#   bash tools/run_merge_gui.sh
#
# The run uses the campaign parameters: RunFreiburgMergeWatch derives its defaults from
# FreiburgFinalStudy, so what is on screen is what the cluster computes. Override the seed, the
# length or the window from the environment:
#
#   MIROVA_SEED=1005 MIROVA_MINUTES=120 bash tools/run_merge_gui.sh
#
# It runs from a separate worktree rather than from this checkout, because the IDE's Java language
# server rewrites target/classes underneath a running build and the run then dies on a class format
# error. Point MIROVA_WORKTREE somewhere else to watch a different build - the two can be compared
# by pointing it at a worktree checked out at another commit.
set -euo pipefail
cd "$(dirname "$0")/.."

WORKTREE="${MIROVA_WORKTREE:-D:/otsrun}"
SEED="${MIROVA_SEED:-4242}"
MINUTES="${MIROVA_MINUTES:-90}"
START="${MIROVA_START:-2025-10-13 14:00:00}"
END="${MIROVA_END:-2025-10-13 22:00:00}"

if [ ! -f "$WORKTREE/cp.txt" ]; then
  echo "No classpath for $WORKTREE. Build it once with:" >&2
  echo "  (cd $WORKTREE && mvn -o -pl ots-demo -am compile \\" >&2
  echo "     && mvn -o -pl ots-demo dependency:build-classpath \\" >&2
  echo "            -Dmdep.outputFile=$WORKTREE/cp.txt -Dmdep.includeScope=runtime)" >&2
  exit 1
fi

MODULES="demo road core base draw swing animation parser-xml kpi trafficcontrol"
CP=""
for m in $MODULES; do CP="$CP$WORKTREE/ots-$m/target/classes;"; done
CP="$CP$(cat "$WORKTREE/cp.txt")"

echo "Worktree $WORKTREE, seed $SEED, $MINUTES min, window $START .. $END"

# No -Dmirova.samplerLinks and no -Dmirova.gateDiag: neither is needed to look at the run, and both
# cost time and disk. With the GUI on, the process deliberately stays alive after the run ends.
java -Xmx8g -cp "$CP" \
  -Dmirova.gui=true \
  -Dmirova.seed="$SEED" \
  -Dmirova.minutes="$MINUTES" \
  -Dmirova.demandStart="$START" \
  -Dmirova.demandEnd="$END" \
  -Dmirova.outputDir="target/merge-gui" \
  org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.RunFreiburgMergeWatch
