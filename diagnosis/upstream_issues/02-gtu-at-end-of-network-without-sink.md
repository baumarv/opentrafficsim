# GTU that reaches the end of the network without a sink fails with NullPointerException or IndexOutOfBoundsException

**OTS version:** 1.8.1, `averbraeck/opentrafficsim` `main` at `78f2601dd80aacdfd4950c6bfbe899ee904afee7`
(2026-10-09). Java 17.0.2.

## Summary

When a GTU with the LMRS reaches the end of the last lane of its route and there is no sink to destroy it, its next
plan fails with an exception from perception or from the plan builder, instead of a defined behaviour. Version 1.7.6
handled the same situation in `LaneOperationalPlanBuilder` with the message "GTU … has nowhere to go and no sink
detector either" and destroyed the GTU.

## Minimal reproduction

The `StraightModel` demo of `ots-demo`, with its sink detector removed. One line:

```diff
--- ots-demo/src/main/java/org/opentrafficsim/demo/StraightModel.java
+++ StraightModel without the sink
@@ -137,1 +137,1 @@
-            new SinkDetector(sinkLane, new Length(10.0, METER), DefaultsNl.ROAD_USERS);
+            // sink removed
```

Run it headless for 600 s simulated (`OtsSimulator`, `initialize(Duration.ZERO, Duration.ZERO, Duration.ofSI(600.0),
model, HistoryManagerDevs.noHistory(simulator))`, then `step()` in a loop), with `-XX:-OmitStackTraceInFastThrow`
so that repeated exceptions keep their stack traces. A runner of 30 lines is in `repro/RunRepro.java` next to this
file.

## Observed

`LaneBasedGtu.move` logs `GtuException` and destroys the GTU, 101 times in 600 s:

- 85 times a `NullPointerException` in `LaneStructure.update`:

  ```
  org.opentrafficsim.core.gtu.GtuException: java.lang.RuntimeException: …DirectNeighborsPerception.computeLeaders (170): Unable to get leaders from LaneStructure
      at org.opentrafficsim.core.gtu.Gtu.move(Gtu.java:516)
      at org.opentrafficsim.road.gtu.LaneBasedGtu.move(LaneBasedGtu.java:645)
  Caused by: java.lang.RuntimeException: …DirectNeighborsPerception.computeLeaders (170): Unable to get leaders from LaneStructure
      at org.opentrafficsim.road.gtu.perception.categories.neighbors.DirectNeighborsPerception.computeLeaders(DirectNeighborsPerception.java:170)
      at org.opentrafficsim.road.gtu.perception.categories.neighbors.DirectNeighborsPerception.lambda$getLeaders$3(DirectNeighborsPerception.java:160)
      at org.opentrafficsim.core.gtu.perception.AbstractPerceptionCategory.computeIfAbsent(AbstractPerceptionCategory.java:102)
      at org.opentrafficsim.core.gtu.perception.AbstractPerceptionCategory.computeIfAbsent(AbstractPerceptionCategory.java:131)
      at org.opentrafficsim.road.gtu.perception.categories.neighbors.DirectNeighborsPerception.getLeaders(DirectNeighborsPerception.java:160)
      at org.opentrafficsim.road.gtu.tactical.util.lmrs.LmrsUtil.determinePlan(LmrsUtil.java:117)
      at org.opentrafficsim.road.gtu.tactical.lmrs.Lmrs.generateOperationalPlan(Lmrs.java:87)
      at org.opentrafficsim.core.gtu.Gtu.move(Gtu.java:455)
  Caused by: java.lang.NullPointerException: Cannot invoke "org.opentrafficsim.road.network.LanePosition.lane()" because "position" is null
      at org.opentrafficsim.road.gtu.perception.structure.LaneStructure.update(LaneStructure.java:475)
      at org.opentrafficsim.road.gtu.perception.structure.LaneStructure.getDownstreamGtus(LaneStructure.java:184)
      at org.opentrafficsim.road.gtu.perception.categories.neighbors.DirectNeighborsPerception.lambda$computeLeaders$4(DirectNeighborsPerception.java:172)
      at org.djutils.exceptions.Try.assign(Try.java:82)
      at org.opentrafficsim.road.gtu.perception.categories.neighbors.DirectNeighborsPerception.computeLeaders(DirectNeighborsPerception.java:170)
  ```

- 16 times an `IndexOutOfBoundsException` in `LaneOperationalPlanBuilder.getCenterLinePath`:

  ```
  org.opentrafficsim.core.gtu.GtuException: java.lang.IndexOutOfBoundsException: Index -2 out of bounds for length 0
      at org.opentrafficsim.core.gtu.Gtu.move(Gtu.java:516)
      at org.opentrafficsim.road.gtu.LaneBasedGtu.move(LaneBasedGtu.java:645)
  Caused by: java.lang.IndexOutOfBoundsException: Index -2 out of bounds for length 0
      at java.base/java.util.ArrayList.get(ArrayList.java:427)
      at org.opentrafficsim.road.gtu.operational.LaneOperationalPlanBuilder.getCenterLinePath(LaneOperationalPlanBuilder.java:268)
      at org.opentrafficsim.road.gtu.operational.LaneOperationalPlanBuilder.getPath(LaneOperationalPlanBuilder.java:189)
      at org.opentrafficsim.road.gtu.operational.LaneOperationalPlanBuilder.buildPlanFromSimplePlan(LaneOperationalPlanBuilder.java:158)
      at org.opentrafficsim.road.gtu.tactical.lmrs.Lmrs.generateOperationalPlan(Lmrs.java:137)
      at org.opentrafficsim.core.gtu.Gtu.move(Gtu.java:455)
  ```

Without `-XX:-OmitStackTraceInFastThrow` most of the `NullPointerException`s arrive without message and stack trace,
because HotSpot stops filling them in after many throws from the same place.

The same `NullPointerException` occurs in a larger scenario where GTUs reach the end of the network because a sink
was missed (see issue "SinkDetector not triggered after a lane change …"): 49 of 55 removals in a 9-hour run.

## Expected

A defined behaviour for a GTU that has no lane ahead on its route and no sink: destroy it with a clear message, as
1.7.6 did, or throw one exception that says what happened. Not a `NullPointerException` from perception or an
index error from the plan builder.

## Suspected location

- `LaneStructure.update`, `LaneStructure.java:475`: `LanePosition position = this.egoGtu.getPosition();` is
  dereferenced although `LaneBasedGtu.getPosition()` returns `null` for a roaming GTU (one that is on no lane).
- `LaneOperationalPlanBuilder.getCenterLinePath`, `LaneOperationalPlanBuilder.java:268`:
  `points.get(points.size() - 2)` on an empty point list ("Index -2 out of bounds for length 0"). Presumably the
  loop over lanes ends because `gtu.getNextLaneForRoute(lane)` is empty before any point was added; I did not step
  through it.
- In 1.7.6 the corresponding case was handled in `LaneOperationalPlanBuilder.createPathAlongCenterLine` ("has
  nowhere to go and no sink detector either", followed by `gtu.destroy()`).
