# FSM reference traces

Recorded decision traces of the MiRoVA Layer 3 state machine, used by `FsmTraceRegressionTest` to
prove that a restructuring of the machine leaves the model behaviour untouched. See
`docs/mirova/fsm_reengineering_plan.md`.

Regenerate with:

    mvn -o -q -pl ots-demo exec:java \
        -Dexec.mainClass=org.opentrafficsim.demo.mirova.fsmtrace.FsmTraceHarness \
        -Dexec.args=<output directory>

Named cases can be appended to `-Dexec.args` (`<dir> merge freiburg-merge`) to record only those;
with none, every case runs. Then copy the `.trace.csv.gz` files here. Do that **only** for a change whose effect on the model is
intended and understood -- overwriting a reference is how a regression net stops being one.

| File | Case | Rows |
|:--|:--|--:|
| `freiburg-merge.trace.csv.gz` | `FreiburgNord`, 20 min from 2025-10-13 13:00, seed 42, merge-watch calibration | 318 436 |
| `merge.trace.csv.gz` | `MergeScenario`, 600 s, seed 42, demand ramp 1000..6500 veh/h | 289 395 |

`freiburg-merge` is the primary case: it is the real network under measured demand, and it takes its
parameters from `RunFreiburgMergeWatch.watchParameters()` rather than from a copy, so a change to
that calibration reaches the regression net instead of leaving it behind. It is the only case that
enters the congested branch -- `CongestedFollowLeaderState` (311 rows) and `CongestedCreepState`
(40) -- which is exactly the part of the machine stage 5 restructures.

No reference exists for the `highway` case: `SimpleHighwayScenario` currently aborts on the deadlock
watchdog. The test skips a case whose reference is missing.

## What is in a row

The `vehicle` column is not the GTU id. Ids come from a counter that outlives a single simulation, so
the same scenario run twice in one JVM produces the same decisions under different ids; the trace
would then depend on what else that JVM had run. The column is the vehicle's rank of first
appearance within the trace instead.

## Running the test

    mvn -o -pl ots-demo test -Dtest=FsmTraceRegressionTest -Dmirova.fsmtrace=true

If this fails with `NoClassDefFoundError: ColorType` (or another `org.opentrafficsim.xml.bindings`
type) rather than a trace difference, the `ots-xml` artifact in the local Maven repository is stale
relative to the working tree. Rebuild it with

    mvn -o -pl ots-xml install -Dmaven.test.failure.ignore=true

and note that `-Dmaven.test.skip=true` must **not** be used there: it also skips the XSD code
generation and installs a jar with a quarter of the classes.
