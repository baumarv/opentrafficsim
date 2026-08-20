package org.opentrafficsim.road.gtu.lane.tactical.mirova.util;

import org.djunits.value.vdouble.scalar.Duration;
import org.djunits.value.vdouble.scalar.Length;
import org.djunits.value.vdouble.scalar.Speed;
import org.opentrafficsim.road.gtu.lane.LaneBasedGtu;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.MirovaTacticalPlanner;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.InfrastructureContext;
import org.opentrafficsim.road.gtu.lane.plan.operational.LaneChange;

/**
 * Removes vehicles that have become permanently stuck, so that a single deadlocked vehicle cannot block a whole simulation.
 * <p>
 * The mechanism mirrors the vehicle diffusion of commercial microsimulators: a vehicle that has been standing still for longer
 * than {@code vehicleDiffusionTime} in a position it can no longer resolve is removed from the network and the removal is
 * recorded by {@link VehicleDiffusionLogger}. It is a simulation safeguard rather than driver behaviour, which is why it lives
 * outside the tactical planner.
 * </p>
 * <p>
 * Standing still is not by itself a deadlock -- a vehicle waiting in a mainline queue is behaving perfectly normally. Only two
 * situations count:
 * </p>
 * <ul>
 * <li>The vehicle is standing at the very end of its route lane, where no car-following manoeuvre can resolve the situation.</li>
 * <li>The vehicle is standing in the middle of a lane change close to a critical lane end, straddling two lanes with no room to
 * complete the manoeuvre.</li>
 * </ul>
 * <p>
 * The stoppage timer is reset as soon as the vehicle moves again or leaves those situations, so a vehicle that is merely slow
 * is never removed.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class DeadlockDiffusionWatchdog
{
    /** Speed at or below which the vehicle counts as standing still [m/s]. */
    private static final double STANDSTILL_SPEED = 0.1;

    /** Distance to the end of the route lane within which a lane change in progress counts as critical [m]. */
    private static final double CRITICAL_LANE_END_DISTANCE = 100.0;

    /** The planner whose vehicle is supervised. */
    private final MirovaTacticalPlanner vehicle;

    /** Simulation time at which the current uninterrupted stoppage began, or null while the vehicle is moving. */
    private Duration stoppageStartTime = null;

    /**
     * Creates a watchdog for one vehicle.
     * @param vehicle the tactical planner whose vehicle is supervised
     */
    public DeadlockDiffusionWatchdog(final MirovaTacticalPlanner vehicle)
    {
        this.vehicle = vehicle;
    }

    /**
     * Evaluates the vehicle's situation for this tick and removes it if it is deadlocked.
     * <p>
     * Deliberately fail-safe: any error while evaluating the situation is swallowed and reported as "not deadlocked". A
     * watchdog that throws would take down the very simulation it exists to protect.
     * </p>
     * @return true if the vehicle was removed or is already gone, in which case the caller must not plan for it any more
     */
    public boolean check()
    {
        try
        {
            LaneBasedGtu gtu = this.vehicle.getGtu();
            if (gtu == null || gtu.isDestroyed())
            {
                return true;
            }

            Speed speed = gtu.getSpeed();
            if (speed == null || speed.si > STANDSTILL_SPEED)
            {
                this.stoppageStartTime = null;
                return false;
            }

            LaneChange laneChange = this.vehicle.getLaneChange();
            boolean activeLaneChange = laneChange != null && laneChange.isChangingLane();

            boolean nearEmergencyStoppingPosition = false;
            boolean nearCriticalLaneEnd = false;
            InfrastructureContext infra = this.vehicle.getContext(InfrastructureContext.class);
            Length routeDistToLaneEnd = (infra != null) ? infra.getRouteDistanceToLaneEnd() : null;
            if (routeDistToLaneEnd != null)
            {
                Length emergencyStoppingDist = gtu.getParameters().getParameter(MirovaParameters.emergencyStoppingDistance);
                if (emergencyStoppingDist != null)
                {
                    nearEmergencyStoppingPosition = routeDistToLaneEnd.si < 2.0 * emergencyStoppingDist.si;
                    nearCriticalLaneEnd = routeDistToLaneEnd.si < CRITICAL_LANE_END_DISTANCE;
                }
            }

            boolean deadlockCandidate = nearEmergencyStoppingPosition
                    || (activeLaneChange && nearCriticalLaneEnd);
            if (!deadlockCandidate)
            {
                this.stoppageStartTime = null; // standing in a normal queue, not stuck
                return false;
            }

            Duration currentTime = gtu.getSimulator().getSimulatorTime();
            if (this.stoppageStartTime == null)
            {
                this.stoppageStartTime = currentTime;
            }

            Duration stoppageDuration = currentTime.minus(this.stoppageStartTime);
            Duration maxDiffusionTime = gtu.getParameters().getParameter(MirovaParameters.vehicleDiffusionTime);
            if (stoppageDuration.le(maxDiffusionTime))
            {
                return false;
            }

            diffuse(gtu, currentTime, stoppageDuration, activeLaneChange, routeDistToLaneEnd);
            return true;
        }
        catch (Exception exception)
        {
            return false; // fail-safe: never let the safeguard itself break the run
        }
    }

    /**
     * Records and performs the removal of a deadlocked vehicle.
     * @param gtu the vehicle to remove
     * @param currentTime the current simulation time
     * @param stoppageDuration how long the vehicle has been stuck
     * @param activeLaneChange whether the vehicle was stuck in the middle of a lane change
     * @param routeDistToLaneEnd the remaining distance to the end of the route lane, may be null
     */
    private void diffuse(final LaneBasedGtu gtu, final Duration currentTime, final Duration stoppageDuration,
            final boolean activeLaneChange, final Length routeDistToLaneEnd)
    {
        double distM = (routeDistToLaneEnd != null) ? routeDistToLaneEnd.si : -1.0;

        String laneId = "UNKNOWN";
        try
        {
            if (gtu.getReferencePosition() != null && gtu.getReferencePosition().lane() != null)
            {
                laneId = gtu.getReferencePosition().lane().getId();
            }
        }
        catch (Exception exception)
        {
            laneId = "UNKNOWN"; // position not resolvable; the removal is still recorded
        }

        String reason = activeLaneChange ? "BLOCKED_LANE_CHANGE_DEADLOCK" : "ROUTE_LANE_END_EMERGENCY_STOP";

        System.out.printf(
                "[DIFFUSION] GTU %s deadlocked for %.1fs (activeLaneChange=%b, distToLaneEnd=%.1fm, lane=%s)."
                        + " Removing vehicle from simulation.%n",
                gtu.getId(), stoppageDuration.si, activeLaneChange, distM, laneId);

        VehicleDiffusionLogger.logDiffusion(gtu.getId(), currentTime.si, laneId, distM, activeLaneChange, reason);

        gtu.destroy();
    }
}
