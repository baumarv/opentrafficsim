# SinkDetector not triggered by a GTU whose lane change ends just upstream of it

**OTS version:** 1.8.1, `averbraeck/opentrafficsim` `main` at `78f2601dd80aacdfd4950c6bfbe899ee904afee7`
(2026-10-09). Java 17.0.2. LMRS with default settings, time step 0.5 s.

## Summary

A GTU that completes a lane change a few metres before a `SinkDetector` is not destroyed by the sink on its new
lane. It drives on to the end of the lane and then fails as described in the issue "GTU that reaches the end of the
network without a sink …".

## Reproduction

Not reduced to a minimal model yet: it needs two lanes, a sink on each, and lane changes ending just before the
sinks, which a small demo does not produce reliably. It reproduces deterministically in this scenario:

- Branch `ots-upstream-benchmark` of `baumarv/opentrafficsim`, which is upstream `78f2601dd` plus a scenario in
  `ots-demo` and no change to the simulation code in `ots-road`, `ots-core` or `ots-base` that affects behaviour.
- Network `ots-demo/src/main/resources/mirova/FreiburgNord.xml`. The last link `L5a` has two lanes of 742.39 m and
  742.36 m, each with `<ots:Sink … Position="END-50m" Type="NL.ROAD_USERS"/>`, which the XML parser creates with
  `SinkDetector.DESTINATION`. The route of the affected GTUs ends at the end node of `L5a`.
- Run: `RunMirovaClusterStudy --study=dates --dates=2025-09-22 --demand=cluster/demand --strict=true
  --replications=1 --index=0 --model=lmrs` with `-Dmirova.samplerLinks=L5a` to record trajectories on `L5a`;
  9 hours simulated, about 4 minutes.

## Observed

19 092 GTUs drive on `L5a`. 55 of them pass the sink at 692.4 m and run off the end of the lane 50 m later. All 55:

- have the end node of `L5a` as their route destination, so `DESTINATION` should accept them;
- are recorded on both lanes for the last time between 2.6 m and 8.9 m upstream of the sink (median 2.8 m), i.e.
  their lane change ends in the plan step in which they reach the sink;
- end on `Lane1`; 45 came from `Lane2` and 10 changed `Lane1` → `Lane2` → `Lane1` on the link;
- drive at 27.6 to 42.1 m/s, mostly in free flow.

No GTU without that pattern passes a sink. A typical case, GTU 438 (reference point, sampled per plan step):

| t [s] | lane | x [m] |
|---|---|---|
| 633.6 | Lane2 | 671.0 |
| 634.1 | Lane2 and Lane1 | 689.2 / 689.7 |
| 634.6 | Lane1 | 708.0 — past the sink at 692.39 |
| 635.6 | Lane1 | 744.9 — past the end of the lane |

Loop detectors are not measurably affected: on `L5a`, 19 090 trajectories cross the detector position (371 m) and
the detectors count 19 080 GTUs.

In version 1.7.6 the same scenario with the LMRS of that version has 706 GTUs reaching the end of `L5a` past the
sink; 1.7.6 destroyed them in `LaneOperationalPlanBuilder` ("has nowhere to go and no sink detector either"). The
defect is therefore older than 1.8.1.

## Expected

The sink on the lane the GTU is on at the moment its front reaches the sink position destroys it, whether or not a
lane change ended shortly before.

## Stack trace

There is none for the miss itself; nothing is thrown when the sink is not triggered. The GTU fails 50 m later; see
the issue "GTU that reaches the end of the network without a sink …" for those traces.

## Suspected location

`LaneBasedGtu.findDetectorTriggers` and `LaneBasedGtu.findDetectorTriggersInEpisode` (`LaneBasedGtu.java`, lines 957
to 1075 at `78f2601dd`), which collect the detectors a plan will reach, lane by lane ("episodes"), split at the
moment a pending lane is entered. A detector on the target lane that lies just beyond the point where the lane
change ends seems not to be found in either episode. I have not identified the statement.
