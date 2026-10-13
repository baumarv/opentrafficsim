#!/usr/bin/env bash
#
# Open one day of the Freiburg-Nord scenario in the OTS GUI, to watch it.
#
#   cluster/run_gui.sh                       # what the block below says
#   cluster/run_gui.sh --date=2025-10-07     # any setting can also be given on the command line; it wins
#   cluster/run_gui.sh --dry-run             # print the java command and start nothing
#   cluster/run_gui.sh --help                # every option, with the values it takes
#
# This is for looking, not for results: a GUI run writes its output only when the simulation reaches
# its end, and writes no run.properties. Runs that are evaluated go through run_local_parallel.sh or
# run_mirova.sbatch, which take the same --model, --lmrsParameters, --lmrs.* and --car.*/--truck.* options.
#
# Build first, from clean, after every checkout (the classes are taken from ots-*/target/classes):
#   MAVEN_OPTS="-Duser.language=en -Duser.country=US" \
#       mvn clean install -pl ots-demo -am -Dmaven.test.skip=true -Dmaven.javadoc.skip=true -Djacoco.skip=true
#
# Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved.
# BSD-style license. See OpenTrafficSim License.

# =======================================================================================
# SETTINGS - edit here. An empty value means "do not touch": the model keeps what upstream
# OpenTrafficSim sets. With everything below left as it is, this shows the benchmark.
# =======================================================================================

# --- what to simulate ---------------------------------------------------------------
DATE="2025-09-22"          # a date with a demand file in cluster/demand; see cluster/dates.txt
SEED=""                    # empty = 42, the seed of replication 0
SIMULATION_TIME=""         # empty = the whole day, 13:00-22:00; e.g. "3600s" for the first hour
SAMPLER_LINKS=""           # empty = L4a (the merge section); e.g. "L4a,L3a" to record the approach too

# --- driver model -------------------------------------------------------------------
MODEL="lmrs"               # lmrs = the LMRS of upstream OTS
LMRS_PARAMETERS="default"  # default  = the model's own parameter values
                           # scenario = T, a, b, s0 as the Freiburg studies set them (cars 1.1 s, 1.4, 2.0, 2.0 m;
                           #            trucks 1.4 s, 1.25, 2.0, 4.0 m)

# --- how the LMRS is composed (upstream default in brackets; wording as in upstream) -------
SYNCHRONIZATION=""         # [PASSIVE]  PASSIVE | PASSIVE_MOVING | ALIGN_GAP | ALIGN_GAP_MOVING
                           #   PASSIVE:   the leader on the target lane is followed with limited deceleration
                           #   ALIGN_GAP: follow that leader or align with the middle of the gap, whichever allows
                           #              the larger acceleration
                           #   *_MOVING:  the same, but disabled below the creep speed
COOPERATION=""             # [PASSIVE]  NONE | PASSIVE | PASSIVE_MOVING
                           #   PASSIVE:   cooperate with any leader whose lane-change desire is at least dCoop
                           #   *_MOVING:  the same, but disabled below the creep speed
GAP_ACCEPTANCE=""          # [INFORMED] INFORMED | EGO_HEADWAY
                           #   INFORMED:    informed of the model and parameters of the other drivers
                           #   EGO_HEADWAY: the same, but applying the own headway value

# --- lane-change incentives, true | false ------------------------------------------------
INCENTIVE_ROUTE=""                 # [true]  mandatory: follow the route
INCENTIVE_GET_IN_LANE=""           # [false] mandatory: join slow traffic at a split and do not block other traffic
INCENTIVE_SPEED_WITH_COURTESY=""   # [true]  voluntary: speed, with courtesy
INCENTIVE_COURTESY=""              # [false] voluntary: cooperative lane changes
INCENTIVE_QUEUE=""                 # [false] voluntary: join the shortest queue
INCENTIVE_STAY_RIGHT=""            # [false] voluntary: trucks stay in the two rightmost lanes
INCENTIVE_KEEP=""                  # [true]  voluntary: keep to the slow lane

# --- acceleration incentives and social interactions, true | false -----------------------
ACCELERATION_NO_SLOW_LANE_OVERTAKE=""   # [false] do not overtake traffic on the slow lane
ACCELERATION_SPEED_LIMIT_TRANSITION=""  # [false] slow down prior to a lower speed limit
SOCIAL_PRESSURE=""                 # [false] social pressure exerted on the (potential) leader
TAILGATING=""                      # [false] tailgating due to social pressure towards the leader
SOCIO_LANE_CHANGE=""               # [false] lane changes due to social pressure from the (potential) follower
SOCIO_SPEED=""                     # [false] speed increase due to social pressure from the follower

# --- perception -------------------------------------------------------------------------
FULLER_IMPLEMENTATION=""           # [NONE]  NONE | SUMMATIVE | ANTICIPATION_RELIANCE | ATTENTION_MATRIX
                                   #   implementation of Fuller's task-capability model; NONE = perfect perception

# --- single driver parameters, with unit; one "<car|truck>.<parameter>=<value>" per entry -
# Examples:  "car.T=1.1s"  "car.Tmin=0.56s"  "car.a=1.4m/s2"  "car.b=2.0m/s2"  "car.s0=2m"
#            "car.vGain=50km/h"  "car.dFree=0.365"  "car.dSync=0.577"  "car.dCoop=0.788"  "truck.T=1.4s"
PARAMETERS=()

# --- JVM --------------------------------------------------------------------------------
HEAP="4g"

# =======================================================================================
# END OF SETTINGS
# =======================================================================================

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
    --*)       EXTRA_ARGS+=("$arg") ;;
    *) echo "unknown argument: $arg (expected --key=value, --dry-run or --help)" >&2; exit 2 ;;
  esac
done

cd "$REPO" || exit 1

# The settings block as arguments. An empty setting is not passed, so the model keeps its own default.
ARGS=("--date=$DATE" "--demand=$REPO/cluster/demand" "--model=$MODEL" "--lmrsParameters=$LMRS_PARAMETERS")
add() { if [ -n "$2" ]; then ARGS+=("--$1=$2"); fi; }
add seed                                  "$SEED"
add simulationTime                        "$SIMULATION_TIME"
add lmrs.synchronization                  "$SYNCHRONIZATION"
add lmrs.cooperation                      "$COOPERATION"
add lmrs.gapAcceptance                    "$GAP_ACCEPTANCE"
add lmrs.incentiveRoute                   "$INCENTIVE_ROUTE"
add lmrs.incentiveGetInLane               "$INCENTIVE_GET_IN_LANE"
add lmrs.incentiveSpeedWithCourtesy       "$INCENTIVE_SPEED_WITH_COURTESY"
add lmrs.incentiveCourtesy                "$INCENTIVE_COURTESY"
add lmrs.incentiveQueue                   "$INCENTIVE_QUEUE"
add lmrs.incentiveStayRight               "$INCENTIVE_STAY_RIGHT"
add lmrs.incentiveKeep                    "$INCENTIVE_KEEP"
add lmrs.accelerationNoSlowLaneOvertake   "$ACCELERATION_NO_SLOW_LANE_OVERTAKE"
add lmrs.accelerationSpeedLimitTransition "$ACCELERATION_SPEED_LIMIT_TRANSITION"
add lmrs.socialPressure                   "$SOCIAL_PRESSURE"
add lmrs.tailgating                       "$TAILGATING"
add lmrs.socioLaneChange                  "$SOCIO_LANE_CHANGE"
add lmrs.socioSpeed                       "$SOCIO_SPEED"
add lmrs.fullerImplementation             "$FULLER_IMPLEMENTATION"
if [ ${#PARAMETERS[@]} -gt 0 ]; then
  for parameter in "${PARAMETERS[@]}"; do ARGS+=("--$parameter"); done
fi
# Command-line arguments last: the runner keeps the last value given for a key.
if [ ${#EXTRA_ARGS[@]} -gt 0 ]; then ARGS+=("${EXTRA_ARGS[@]}"); fi

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
if [ -n "$SAMPLER_LINKS" ]; then OPTS+=("-Dmirova.samplerLinks=$SAMPLER_LINKS"); fi
MAIN_CLASS="org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.RunFreiburgNordGui"

if [ "$DRY" = 1 ]; then
  printf '[dry run]'
  printf ' %q' java "${OPTS[@]}" -cp "<ots-*/target/classes>$SEP<${CP_CACHE#"$REPO"/}>" "$MAIN_CLASS" "${ARGS[@]}"
  printf '\n'
  exit 0
fi

echo "[gui] ${ARGS[*]}"
exec java "${OPTS[@]}" -cp "$CP" "$MAIN_CLASS" "${ARGS[@]}"
