# IdmDesiredSpeedModel discards its computed desired speed when a GTU-type speed limit exists

**OTS version:** 1.8.1, `averbraeck/opentrafficsim` `main` at `78f2601dd80aacdfd4950c6bfbe899ee904afee7`
(2026-10-09). Java 17.0.2.

## Summary

`AbstractIdm.IdmDesiredSpeedModel.desiredSpeed` computes the desired speed from the lane speed limit and the GTU-type
speed limit, but when a GTU-type speed limit is present it does not return that value. It falls through to the
no-speed-limit fallback and returns `min(maxVehicleSpeed, fSpeed × 130 km/h)`. A vehicle type with its own speed limit
(trucks, typically) therefore ignores both its type limit and the lane limit, and drives at up to 130 km/h × fSpeed,
or its maximum vehicle speed if that is lower.

## Reproduction

`repro/DesiredSpeedRepro.java` (in this directory) calls `AbstractIdm.DESIRED_SPEED.desiredSpeed` directly with both
adherence factors (`FSPEED`, `FSPEED_GTU`) at 1.0, a maximum vehicle speed of 200 km/h that does not bind, a lane
limit of 100 km/h and a GTU-type limit of 80 km/h, neither enforced:

```
javac -d out -cp <ots-road classpath> DesiredSpeedRepro.java
java -cp out;<ots-road classpath> repro.DesiredSpeedRepro
```

## Observed

```
lane 100 only              desired  100.0 km/h, expected  100.0 km/h
GTU type 80 only           desired  130.0 km/h, expected   80.0 km/h   <-- WRONG
lane 100 and GTU type 80   desired  130.0 km/h, expected   80.0 km/h   <-- WRONG
none                       desired  130.0 km/h, expected  130.0 km/h
```

With both limits present the result is above the lane limit as well.

## Expected

As the class Javadoc says: "the minimum of fSpeed'·laneSpeedLimit and fSpeedGtu'·gtuTypeSpeedLimit if both exist, or
one of them if one exists. If both do not exist this model returns fSpeed·130km/h", in each case capped by the
maximum vehicle speed. So 80 km/h in the second and third case.

## Location

`ots-road/src/main/java/org/opentrafficsim/road/gtu/tactical/following/AbstractIdm.java`, lines 227–262 at
`78f2601dd`:

```java
            if (speedLimits.gtuTypeSpeedLimit() != null)
            {
                double gtuTypeFactor = parameters.getParameter(FSPEED_GTU);
                ...
                if (speed == null)
                {
                    speed = speedLimits.gtuTypeSpeedLimit().speed().times(gtuTypeFactor);
                }
                else
                {
                    speed = Speed.min(speed, speedLimits.gtuTypeSpeedLimit().speed().times(gtuTypeFactor));
                }
            }
            else if (speed != null)
            {
                return Speed.min(maxVehicleSpeed, speed);
            }
            return Speed.min(maxVehicleSpeed, NO_SPEED_LIMIT_SPEED.times(parameters.getParameter(FSPEED)));
```

The `if` branch assigns `speed` and then leaves the block without returning it; only the `else if` branch returns.

## Suggested fix

Replace the `else if` and the final `return` by:

```java
            }
            if (speed == null)
            {
                speed = NO_SPEED_LIMIT_SPEED.times(parameters.getParameter(FSPEED));
            }
            return Speed.min(maxVehicleSpeed, speed);
```

## Affected callers

Every user of `AbstractIdm.DESIRED_SPEED`: the IDM and IDM+ car-following models of the LMRS, and the generator's
`peekDesiredSpeed` (`LmrsFactory.peekDesiredSpeed`, used by `CfRoomChecker` and `GeneratorPositions`), so a vehicle of
an affected type is also generated at the wrong speed. `SpeedLimitUtil.getDesiredSpeedProxy` is not affected.

## Effect where it was found

None. In the Freiburg-Nord scenario on branch `ots-upstream-benchmark` of `baumarv/opentrafficsim`, the only GTU-type
speed limit is 120 km/h for trucks on the motorway links. Trucks draw a maximum vehicle speed of 79–100 km/h, and the
fallback is at least 130 km/h × 0.8 = 104 km/h (the truncation of the `FSPEED` distribution of the `LmrsFactory`), so
the maximum vehicle speed binds in both the correct and the actual computation and the desired speed is the same. Cars
have no GTU-type limit and are not affected. Observed truck speeds in that scenario do not exceed 100 km/h.
