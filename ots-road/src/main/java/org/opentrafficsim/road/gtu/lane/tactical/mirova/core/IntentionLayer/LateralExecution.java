package org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer;

import org.djunits.value.vdouble.scalar.Acceleration;
import org.opentrafficsim.base.parameters.ParameterException;
import org.opentrafficsim.core.gtu.GtuException;
import org.opentrafficsim.core.network.LateralDirectionality;
import org.opentrafficsim.core.network.NetworkException;
import org.opentrafficsim.road.gtu.lane.perception.headway.HeadwayGtu;
import org.opentrafficsim.road.gtu.lane.plan.operational.SimpleOperationalPlan;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.MirovaTacticalPlanner;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.EgoContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.NeighborsContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ReactiveLayer.MirovaCarFollowingUtil;
import org.opentrafficsim.road.network.lane.Lane;

/**
 * The mechanics every lateral movement shares, irrespective of why it is being made.
 * <p>
 * Performing a lane change is the same physical act whether it is mandatory, discretionary or cooperative: the ego has to
 * respect the leader it is following now and every leader on the lane it is entering, and it has to know when the movement is
 * over. Deciding <i>whether</i> to start, whether to take the action lock, when to give up and what the manoeuvre is worth are
 * separate questions, and they genuinely differ between the patterns.
 * </p>
 * <p>
 * Before this class existed, the two answers to the first question were two copies of the same code -- one in
 * {@code SimpleLaneChangePattern.PerformLaneChangeState}, which three patterns transition into, and one in
 * {@code MandatoryLaneChangePattern.ExecuteLaneChangeState}. Copies of a mechanism drift; policies that differ on purpose do
 * not. So the mechanism lives here and the policies stay where they are, rather than being merged into one state that would
 * have to carry a flag for each of them.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public final class LateralExecution
{

    /** Utility class, not instantiated. */
    private LateralExecution()
    {
        //
    }

    /**
     * Returns the acceleration a vehicle must not exceed while moving into the given lane.
     * <p>
     * It is the minimum of the ego's plain car-following acceleration and its response to every leader on the target lane. A
     * relaxation with a reduced safety distance is triggered for each of those leaders, and for the current leader, so that the
     * ego may accept the tighter headway a merge produces instead of braking for a gap it is deliberately taking. That is done
     * only while the lateral movement has not yet begun: once the vehicle is physically crossing, the headways are what they
     * are and re-triggering would keep resetting the relaxation that is meant to be decaying.
     * </p>
     * @param vehicle the ego vehicle
     * @param direction the lateral direction being moved into
     * @return the acceleration for this tick
     * @throws ParameterException if a parameter lookup fails
     * @throws GtuException if a GTU query fails
     * @throws NetworkException if a network query fails
     */
    public static Acceleration accelerationForLateralMove(final MirovaTacticalPlanner vehicle,
            final LateralDirectionality direction) throws ParameterException, GtuException, NetworkException
    {
        NeighborsContext neighborsCtx = vehicle.getContext(NeighborsContext.class);
        EgoContext egoCtx = vehicle.getContext(EgoContext.class);

        HeadwayGtu targetLeader = neighborsCtx.getLeader(LateralDirectionality.NONE);
        if (targetLeader != null && !vehicle.getLaneChange().isChangingLane())
        {
            egoCtx.triggerRelaxationWithReducedSafetyDistance(targetLeader);
        }

        Acceleration minAcc = egoCtx.getCurrentCarFollowingAcceleration();

        Iterable<HeadwayGtu> leaders = neighborsCtx.getLeaders(direction);
        for (HeadwayGtu leader : leaders)
        {
            if (!vehicle.getLaneChange().isChangingLane())
            {
                egoCtx.triggerRelaxationWithReducedSafetyDistance(leader);
            }
            Acceleration aTarget = MirovaCarFollowingUtil.followSingleLeader(vehicle, leader);
            minAcc = Acceleration.min(minAcc, aTarget);
        }
        return minAcc;
    }

    /**
     * Sets the turn indicator intent matching a lateral direction. A plan with no lateral direction is left untouched.
     * @param plan the plan to annotate
     * @param direction the lateral direction being indicated
     */
    public static void setIndicators(final SimpleOperationalPlan plan, final LateralDirectionality direction)
    {
        if (direction.isLeft())
        {
            plan.setIndicatorIntentLeft();
        }
        else if (direction.isRight())
        {
            plan.setIndicatorIntentRight();
        }
    }

    /**
     * Returns whether a lateral movement has completed.
     * <p>
     * Both conditions are needed. A vehicle that has left its origin lane but is still crossing must finish the movement, and a
     * vehicle that is not crossing but is still on its origin lane has not started one.
     * </p>
     * @param vehicle the ego vehicle
     * @param originLane the lane the vehicle was on when the movement began
     * @return {@code true} once the vehicle has stopped moving laterally and is on a different lane
     */
    public static boolean lateralMoveFinished(final MirovaTacticalPlanner vehicle, final Lane originLane)
    {
        return !vehicle.getLaneChange().isChangingLane() && !originLane.equals(vehicle.getGtu().getLane());
    }
}
