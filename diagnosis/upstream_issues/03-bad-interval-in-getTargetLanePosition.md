# IllegalArgumentException "Bad interval" in LaneOperationalPlanBuilder.getTargetLanePosition at the end of a lane

**OTS version:** 1.8.1, `averbraeck/opentrafficsim` `main` at `78f2601dd80aacdfd4950c6bfbe899ee904afee7`
(2026-10-09). Java 17.0.2. LMRS with default settings.

## Summary

When a GTU is still registered on a lane but its reference position lies beyond the length of that lane, building
its next plan fails in `getTargetLanePosition`, which extracts the lane centre line from the GTU's position to the
lane length and gets an interval whose start is larger than its end.

## Reproduction

Observed in the same scenario as the issue "SinkDetector not triggered by a GTU whose lane change ends just upstream
of it": GTUs that missed the sink reach the end of the last lane. Of 55 such GTUs in a 9-hour run, 6 fail this way;
the other 49 have already left the lane (roaming) when they fail and hit the `NullPointerException` of the issue
"GTU that reaches the end of the network without a sink …". Which of the two happens depends on where the 0.5 s time
step falls relative to the lane end.

The minimal reproduction of that issue (`StraightModel` without its sink) did not produce this exception in 600 s;
it produced the `NullPointerException` and an `IndexOutOfBoundsException` instead. So this one is not reduced to a
minimal model.

Same branch and run as in the sink issue: branch `ots-upstream-benchmark` of `baumarv/opentrafficsim`,
`RunMirovaClusterStudy --study=dates --dates=2025-09-22 --demand=cluster/demand --strict=true --replications=1
--index=0 --model=lmrs`. The six GTUs are registered on `L5a/Lane1` (length 742.39 m) at positions of 743.6 m to
744.7 m when they fail.

## Observed

```
java.lang.IllegalArgumentException: org.djutils.draw.line.PolyLine2d.extract (836): Bad interval (744.674615..742.386706; length of this PolyLine2d is 742.386706)
    at org.djutils.draw.line.PolyLine2d.extract(PolyLine2d.java:836)
    at org.opentrafficsim.base.geometry.OtsLine2d.extract(OtsLine2d.java:204)
    at org.opentrafficsim.base.geometry.OtsLine2d.extract(OtsLine2d.java:191)
    at org.opentrafficsim.road.gtu.operational.LaneOperationalPlanBuilder.getTargetLanePosition(LaneOperationalPlanBuilder.java:570)
    at org.opentrafficsim.road.gtu.operational.LaneOperationalPlanBuilder.getTargetPoint(LaneOperationalPlanBuilder.java:514)
    at org.opentrafficsim.road.gtu.operational.LaneOperationalPlanBuilder.bezierToHorizon(LaneOperationalPlanBuilder.java:204)
    at org.opentrafficsim.road.gtu.operational.LaneOperationalPlanBuilder.getPath(LaneOperationalPlanBuilder.java:187)
    at org.opentrafficsim.road.gtu.operational.LaneOperationalPlanBuilder.buildPlanFromSimplePlan(LaneOperationalPlanBuilder.java:158)
    at org.opentrafficsim.road.gtu.tactical.lmrs.Lmrs.generateOperationalPlan(Lmrs.java:137)
    at org.opentrafficsim.core.gtu.Gtu.move(Gtu.java:455)
    at org.opentrafficsim.road.gtu.LaneBasedGtu.move(LaneBasedGtu.java:645)
```

The same exception, `Bad interval (738.6..738.59)` on a lane of 741.5 m, also occurs 4 times in the same scenario
in version 1.7.6, there from `OtsLine2d.extractFractional`.

## Expected

The start position is clamped to the lane length, or a GTU whose reference position is beyond the lane is treated
as having left it. Either way, no exception from the geometry library.

## Suspected location

`LaneOperationalPlanBuilder.getTargetLanePosition`, `LaneOperationalPlanBuilder.java:570`:

```java
OtsLine2d laneCenter =
        startPosition.lane().getCenterLine().extract(startPosition.position(), startPosition.lane().getLength());
```

`startPosition.position()` can exceed `startPosition.lane().getLength()`.
