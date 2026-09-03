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

### Two ways this goes wrong that have nothing to do with the model

**The change was never in the artifact.** The test resolves `ots-road` from the local Maven
repository, so it tests whatever was installed there last. `-DskipTests` does not skip test
execution in this build, and a failing test in `ots-road` -- `InjectionsTest.testIdorder` fails on
its own -- aborts the install, leaving the previous jar in place. The test then compares the *old*
model against the references and reports a pass or a failure that means nothing. Install with

    mvn -o -pl ots-demo -am install -Dmaven.test.failure.ignore=true

and check that the change actually arrived before trusting a result:

    python -c "import zipfile; print(b'onEntry' in zipfile.ZipFile(r'<m2>/org/opentrafficsim/ots-road/1.7.6/ots-road-1.7.6.jar').read('org/opentrafficsim/road/gtu/lane/tactical/mirova/core/IntentionLayer/ActionState.class'))"

**JAXB cannot see the generated bindings.** A `NoClassDefFoundError: ColorType`, or
`Error occured while invoking reflection on target classes ... XmlJavaTypeAdapter`, is an `ots-xml`
artifact problem rather than a trace difference. Rebuild that module **on its own**:

    mvn -o -pl ots-xml install -Dmaven.test.failure.ignore=true

Two traps here. `-Dmaven.test.skip=true` must not be used: it also skips the XSD code generation and
installs a jar with a quarter of the classes. And building `ots-xml` as part of the full reactor
(`-pl ots-demo -am`) produces a *different, larger* jar that JAXB then fails on -- 544 KB against the
530 KB of the standalone build. After a reactor install, reinstall `ots-xml` alone.
