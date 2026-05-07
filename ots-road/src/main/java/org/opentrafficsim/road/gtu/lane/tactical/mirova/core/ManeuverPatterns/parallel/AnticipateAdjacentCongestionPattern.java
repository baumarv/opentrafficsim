package org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ManeuverPatterns.parallel;

import java.io.Serializable;
import java.util.ArrayList;

import org.djunits.value.vdouble.scalar.Acceleration;
import org.djunits.value.vdouble.scalar.Speed;
import org.opentrafficsim.base.parameters.ParameterException;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.core.gtu.GtuException;
import org.opentrafficsim.core.gtu.plan.operational.OperationalPlanException;
import org.opentrafficsim.core.network.LateralDirectionality;
import org.opentrafficsim.core.network.NetworkException;
import org.opentrafficsim.road.gtu.lane.perception.RelativeLane;
import org.opentrafficsim.road.gtu.lane.plan.operational.SimpleOperationalPlan;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.MirovaTacticalPlanner;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ActionState;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ManeuverPattern;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.context.EgoContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.context.MacroTrafficContext;

/**
 * Detects congestion on adjacent lanes and applies gentle preemptive deceleration.
 * <p>
 * This pattern activates when the average speed on any adjacent lane falls below the congestion threshold ({@code VCONG})
 * while the ego vehicle is still at free-flow speed. It applies a soft deceleration bounded by
 * {@link MirovaParameters#cooperativeDecelerationThreshold} to gradually adapt speed without abrupt braking, improving
 * longitudinal coordination before the congestion front propagates to the ego lane.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 *
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class AnticipateAdjacentCongestionPattern extends ManeuverPattern implements Serializable
{
    /** Serial version UID. */
    private static final long serialVersionUID = 20260507L;

    /** Adjacent lanes currently identified as congested. */
    protected ArrayList<LateralDirectionality> congestedLanes = new ArrayList<>();

    /**
     * Constructs a new AnticipateAdjacentCongestionPattern.
     *
     * @param vehicle the tactical planner executing this pattern
     */
    public AnticipateAdjacentCongestionPattern(final MirovaTacticalPlanner vehicle)
    {
        super(PatternType.PARALLEL, vehicle);
        this.initialActionState = () -> new AdjacentCongestionState(this);
        this.requiredContextKeys.add("Ego");
        this.requiredContextKeys.add("MacroTraffic");
    }

    /**
     * Returns {@code true} unconditionally; ability is implicitly guaranteed when context holds.
     *
     * @return {@code true}
     */
    @Override
    public boolean checkAbility()
    {
        return true;
    }

    /**
     * Checks whether any adjacent lane is congested while the ego vehicle is still at free-flow speed.
     * <p>
     * Congestion is identified as an adjacent lane average speed below {@code VCONG} and at most 3 m/s above ego speed (to
     * avoid reacting to a marginally slower but non-critical adjacent flow). The ego must itself be above {@code VCONG}; if ego
     * is already in congestion, no additional preemptive adaptation is necessary.
     * </p>
     *
     * @return {@code true} if at least one adjacent lane is congested and ego is above congestion speed
     * @throws ParameterException if parameter retrieval fails
     */
    @Override
    public boolean checkContext() throws ParameterException
    {
        this.congestedLanes.clear();

        MacroTrafficContext macro = this.vehicle.getContextManager().getCategory("MacroTraffic", MacroTrafficContext.class);
        EgoContext ego = this.vehicle.getContextManager().getCategory("Ego", EgoContext.class);

        Speed egoSpeed = ego.getEgoSpeed();
        Speed vCong = this.vehicle.getParameters().getParameter(ParameterTypes.VCONG);

        if (!egoSpeed.gt(vCong))
        {
            return false;
        }

        try
        {
            Speed rightLaneSpeed = macro.getAverageSpeed(RelativeLane.RIGHT);
            if (rightLaneSpeed.lt(vCong) && rightLaneSpeed.si < egoSpeed.si + 3.0)
            {
                this.congestedLanes.add(LateralDirectionality.RIGHT);
            }
        }
        catch (OperationalPlanException | ParameterException exception)
        {
            // no right lane data available
        }

        try
        {
            Speed leftLaneSpeed = macro.getAverageSpeed(RelativeLane.LEFT);
            if (leftLaneSpeed.lt(vCong) && leftLaneSpeed.si < egoSpeed.si + 3.0)
            {
                this.congestedLanes.add(LateralDirectionality.LEFT);
            }
        }
        catch (OperationalPlanException | ParameterException exception)
        {
            // no left lane data available
        }

        return !this.congestedLanes.isEmpty();
    }

    /*
     * =========================================================================================
     * STATE: AdjacentCongestionState
     * =========================================================================================
     */

    /**
     * Applies bounded preemptive deceleration in response to adjacent lane congestion.
     * <p>
     * The resulting acceleration is the minimum of the direct car-following acceleration and the preemptive deceleration,
     * itself bounded below by {@link MirovaParameters#cooperativeDecelerationThreshold} to prevent unsafe braking.
     * </p>
     */
    public static class AdjacentCongestionState extends ActionState
    {
        /** The parent pattern. */
        private final AnticipateAdjacentCongestionPattern maneuverPattern;

        /**
         * Constructor.
         *
         * @param pattern the parent maneuver pattern
         */
        public AdjacentCongestionState(final AnticipateAdjacentCongestionPattern pattern)
        {
            super(pattern);
            this.maneuverPattern = pattern;
        }

        @Override
        public SimpleOperationalPlan next()
        {
            return null;
        }

        @Override
        public SimpleOperationalPlan executeControl()
                throws ParameterException, OperationalPlanException, GtuException, NetworkException
        {
            this.maneuverPattern.setRunning(false);

            EgoContext ego = this.vehicle.getContextManager().getCategory("Ego", EgoContext.class);
            Acceleration aDirectLeader = ego.getCurrentCarFollowingAcceleration();
            Acceleration aPreemptive =
                    this.vehicle.getParameters().getParameter(MirovaParameters.preemptiveCooperativeDeceleration);
            Acceleration accCoop =
                    this.vehicle.getParameters().getParameter(MirovaParameters.cooperativeDecelerationThreshold);

            Acceleration finalAcceleration = Acceleration.min(aDirectLeader, Acceleration.max(aPreemptive, accCoop));
            return new SimpleOperationalPlan(finalAcceleration,
                    this.vehicle.getParameters().getParameter(ParameterTypes.DT));
        }

        @Override
        public SimpleOperationalPlan abort() throws ParameterException, GtuException, NetworkException
        {
            return null;
        }

        @Override
        public double getUtility()
        {
            return 0.3;
        }

        @Override
        public String toString()
        {
            return "AdjacentCongestionState";
        }
    }
}
