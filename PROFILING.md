# Profiling branch — MiRoVA backend

This branch exists for one purpose: profiling and optimising the simulation backend. It carries a
single fixed run, the demand data that run needs, and nothing else. It is **not** a calibration
branch, and no result produced here says anything about traffic quality.

Everything about the run is pinned so that two profiles taken a week apart measure the same work.
If you change the seed, the date, the simulated span or a behavioural parameter, earlier
measurements stop being comparable — which is the whole point of pinning them.

## The run

[`RunFreiburgProfiling`](ots-demo/src/main/java/org/opentrafficsim/demo/mirova/scenariomanagement/scenarios/RunFreiburgProfiling.java)
simulates the Freiburg-Nord on-ramp on **2025-10-27 with seed 46**, 330 minutes from 13:00.

That combination was picked because it contains both traffic regimes, and they stress different
code. Free flow spends its time in perception and car-following; congestion adds gap search,
cooperation and lane-change decisions, and multiplies the number of interacting neighbours per
vehicle. A run that never breaks down leaves half the model unmeasured.

Measured at `det_L3a`, this run is **86.9 % free flow and 13.1 % congested**, with the jam running
from 3.58 h to 4.92 h after the start of the demand window and the cross-section speed dropping to
17.3 km/h. Of the ten seeds of its cell it is the only one that breaks down at all. The 330 minutes
reach 18:30, past the end of the jam, so the recovery is inside the measurement.

Switched off, because they are output rather than model and would crowd out what is being looked
for:

| | why |
|:---|:---|
| GUI | rendering would dominate the profile, and AWT threads keep the JVM alive after the run |
| trajectory recording | the sampler writes a row per vehicle per 0.2 s and then compresses it |

Both come back with `-Dmirova.gui=true` and `-Dmirova.trajectories=true` when the question is about
them specifically.

## Earlier optimisations are disabled here

The performance investigation that preceded this branch produced two changes. The run undoes the
one that shipped, so that the profile shows the backend as OTS ships it rather than one with the
known hot spot already removed:

- **GTU position cache.** MiRoVA disables `LaneBasedGtu.CACHING` because the cache cost more than
  it saved. This run sets `-Dmirova.gtuPositionCaching=true` by default, restoring OTS's cache.
  Pass `false` explicitly to profile the optimised state instead. The console line at startup says
  which one is active — check it before trusting a measurement.
- **DJUnits hash caching.** Never merged. It lives on `perf/djunits-hash-cache-experiment`;
  `djunits.version` here is stock `5.2.1`, so there is nothing to undo.

Background on both is in
[`docs/mirova/performance_investigation_synthesis.md`](docs/mirova/performance_investigation_synthesis.md).

## Building

```bash
mvn -o -pl ots-base,ots-core,ots-xml,ots-road,ots-demo compile
mvn -o -pl ots-demo dependency:build-classpath \
    -Dmdep.outputFile=target/profiling-cp.txt -DincludeScope=runtime
```

The second command writes the dependency classpath to a file so the run command below stays short.
It only has to be repeated when a dependency changes.

## Running

From the **repository root** — the demand path is relative to it:

```bash
CP="ots-xml/target/classes;ots-road/target/classes;ots-demo/target/classes;ots-demo/src/main/resources;$(cat ots-demo/target/profiling-cp.txt)"

java -Xmx4g -cp "$CP" -Djava.awt.headless=true \
     org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.RunFreiburgProfiling
```

On Linux use `:` instead of `;` as the classpath separator.

> [!IMPORTANT]
> `ots-xml/target/classes` must come **first**, and it must be the build output rather than the
> installed `ots-xml` jar. The jar carries a `META-INF/MANIFEST.MF` where the build output carries
> the `META-INF/sun-jaxb.episode` that JAXB needs; with the jar in front, parsing the network fails
> with `NoClassDefFoundError: ColorType` — an unqualified class name, which is the tell. This costs
> half an hour every time it is rediscovered.

Useful overrides, all optional:

| property | default | |
|:---|:---|:---|
| `mirova.minutes` | `330.0` | shorter spans truncate rather than re-draw the run, so 60 gives exactly the first hour of it. Below ~215 the breakdown is cut off and the profile is free-flow only |
| `mirova.seed` | `46` | changing it changes which traffic is simulated — see above |
| `mirova.gtuPositionCaching` | `true` | `false` profiles the optimised state |
| `mirova.gui` | `false` | |
| `mirova.trajectories` | `false` | |
| `mirova.outputDir` | `target/freiburg-profiling` | |
| `mirova.demandCsv` | `cluster/demand/demand_2025-10-27.csv` | needed when running from elsewhere |

The run prints its configuration on startup and its wall clock at the end. The wall clock is a
coarse check that a change did anything at all; it is not a measurement — the JIT, the machine's
other load and the run-to-run variance all sit inside it.

## Profiling

JDK Flight Recorder needs no extra tooling:

```bash
java -Xmx4g -cp "$CP" -Djava.awt.headless=true \
     -XX:StartFlightRecording=duration=600s,filename=target/profile.jfr,settings=profile \
     org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.RunFreiburgProfiling
```

Open the recording in JDK Mission Control. For allocation-heavy questions, `settings=profile`
already records allocation samples; for wall-clock questions rather than CPU, async-profiler in
`wall` mode is the better instrument.

Two things worth knowing before reading a profile of this model:

- **The demand ramps up.** The first 45 minutes are warm-up with far fewer vehicles on the network,
  so a profile over the whole run under-weights the loaded state. Recording a window that starts
  after the warm-up gives a sharper picture of the state that matters.
- **The jam is a small fraction of the run.** 13 % of the intervals are congested, and congestion
  is where the manoeuvre patterns do their work. If the question is about those, record the window
  around 3.5–5 h rather than the whole run.

## The demand data

`cluster/demand/demand_2025-10-27.csv` is committed on this branch only. The path is in
`.gitignore` on the main branches, where demand files are treated as generated inputs the cluster
workflow produces; here it is committed deliberately so the branch is runnable on its own without
access to the workspace.
