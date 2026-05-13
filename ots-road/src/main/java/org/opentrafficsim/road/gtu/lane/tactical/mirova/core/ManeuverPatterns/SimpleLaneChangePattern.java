package org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ManeuverPatterns;

import org.djunits.value.vdouble.scalar.Acceleration;
import org.djunits.value.vdouble.scalar.Speed;
import org.opentrafficsim.base.parameters.ParameterException;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.base.parameters.Parameters;
import org.opentrafficsim.core.gtu.GtuException;
import org.opentrafficsim.core.network.LateralDirectionality;
import org.opentrafficsim.core.network.NetworkException;
import org.opentrafficsim.road.gtu.lane.perception.headway.HeadwayGtu;
import org.opentrafficsim.road.gtu.lane.plan.operational.SimpleOperationalPlan;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.MirovaTacticalPlanner;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ActionState;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.Desire;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ManeuverPattern;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.context.EgoContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.context.InfrastructureContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.context.NeighborsContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.following.MirovaCarFollowingUtil;
import org.opentrafficsim.road.network.lane.Lane;

/**
 * A dedicated Maneuver Pattern for executing a simple, direct lane change.
 * <p>
 * This pattern represents a Finite State Machine (FSM) in <b>Layer 4 (Procedure & Action)</b>. It is typically invoked when a
 * lane change decision has been finalized and safety has been verified. It manages the physical transition between lanes,
 * including speed adaptation to target leaders.
 * </p>
 * <p>
 * Copyright (c) 2025 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class SimpleLaneChangePattern extends ManeuverPattern
{
    /** The target direction for the lane change. */
    private LateralDirectionality targetDirection = LateralDirectionality.NONE;

    /**
     * Constructs a new SimpleLaneChangePattern.
     * @param vehicle the tactical planner associated with the ego vehicle
     */
    public SimpleLaneChangePattern(final MirovaTacticalPlanner vehicle)
    {
        super(PatternType.EXCLUSIVE, vehicle);
        this.targetDirection = this.vehicle.getLaneChangeDesire().dominantDirection();
        this.initialActionState = () -> new PerformLaneChangeState(this);
        this.requiredContextKeys.add("Ego");
        this.requiredContextKeys.add("Neighbors");
        this.requiredContextKeys.add("Infrastructure");
    }

    /**
     * Prepares the pattern for a specific direction.
     * @param direction LateralDirectionality (LEFT or RIGHT)
     */
    public void setLaneChangeDirection(final LateralDirectionality direction)
    {
        this.targetDirection = direction;
    }

    @Override
    public boolean checkContext() throws ParameterException
    {
        try
        {
            // Trigger if discretionary desire exceeds the threshold
            return this.vehicle.getLaneChangeDesire().magnitude() >= this.vehicle.getParameters()
                    .getParameter(MirovaParameters.DFREE);
        }
        catch (ParameterException exception)
        {
            return false;
        }
    }

    @Override
    public boolean checkAbility() throws ParameterException
    {
        this.targetDirection = this.vehicle.getLaneChangeDesire().dominantDirection();
        EgoContext ego = this.vehicle.getContext(EgoContext.class);
        NeighborsContext neigh = this.vehicle.getContext(NeighborsContext.class);

        try
        {
            // Discretionary LCs require the vehicle to be physically mobile. When stuck in congestion
            // (near-zero speed AND no positive acceleration out of the jam), suppress the pattern so
            // cooperative parallel patterns (e.g. GapOpener) can operate without being locked out.
            boolean canMove = ego.getEgoSpeed().gt(Speed.instantiateSI(1.0))
                    || ego.getCurrentCarFollowingAcceleration().gt(Acceleration.instantiateSI(0.0));

            return canMove
                    && (this.targetDirection.isLeft() || this.targetDirection.isRight())
                    && neigh.getIfLaneChangePossible(this.targetDirection);
        }
        catch (GtuException | NetworkException exception)
        {
            return false;
        }
    }

    @Override
    public boolean isLaneChangePattern()
    {
        return true;
    }

    @Override
    public double getDesire() throws ParameterException
    {
        return this.vehicle.getLaneChangeDesire().magnitude();
    }

    /*
     * ========================================================================================= STATE: PERFORM_LANE_CHANGE
     * =========================================================================================
     */

    /**
     * Action state responsible for the actual lateral movement and longitudinal synchronization.
     */
    public static class PerformLaneChangeState extends ActionState
    {
        /** Target direction of the lane change. */
        private final LateralDirectionality direction;

        /** Origin lane used to detect when the vehicle has fully crossed over. */
        private final Lane originLane;

        /** Flag to prevent starting the move if speed is too low or gaps closed in the last micro-tick. */
        private Boolean startCondition = true;

        /** Flag to indicate if the lane change is cooperative. */
        private boolean isCooperative = false;

        /**
         * Constructor using the dominant desire direction.
         * @param p the parent maneuver pattern
         */
        public PerformLaneChangeState(final ManeuverPattern p)
        {
            this(p, p.getMirovaTacticalPlanner().getLaneChangeDesire().dominantDirection());
        }

        /**
         * Constructor for a specific direction.
         * @param p the parent maneuver pattern
         * @param direction the lateral direction
         */
        public PerformLaneChangeState(final ManeuverPattern p, final LateralDirectionality direction)
        {
            super(p);
            this.direction = direction;
            this.originLane = this.vehicle.getGtu().getLane();

        }

        /**
         * Constructor for a specific direction and cooperative flag.
         * @param p the parent maneuver pattern
         * @param direction the lateral direction
         * @param isCooperative flag to indicate if the lane change is cooperative
         */
        public PerformLaneChangeState(final ManeuverPattern p, final LateralDirectionality direction,
                final boolean isCooperative)
        {
            super(p);
            this.direction = direction;
            this.originLane = this.vehicle.getGtu().getLane();
            this.isCooperative = isCooperative;

        }

        @Override
        public SimpleOperationalPlan executeControl() throws ParameterException, GtuException, NetworkException
        {
            this.maneuverPattern.setRunning(true);

            NeighborsContext neighborsCtx = this.vehicle.getContext(NeighborsContext.class);
            EgoContext egoCtx = this.vehicle.getContext(EgoContext.class);

            Speed egoSpeed = egoCtx.getEgoSpeed();

            // Base acceleration from current lane car-following
            Acceleration minAcc = egoCtx.getCurrentCarFollowingAcceleration();
            if (!this.vehicle.getLaneChange().isChangingLane())
            {
                egoCtx.triggerRelaxation(neighborsCtx.getLeader(LateralDirectionality.NONE));
            }

            // Synchronize with leader on the target lane
            if (this.vehicle.getGtu().getLane().equals(this.originLane))
            {
                Iterable<HeadwayGtu> leaders = neighborsCtx.getLeaders(this.direction);
                for (HeadwayGtu leader : leaders)
                {
                    if (!this.vehicle.getLaneChange().isChangingLane())
                    {
                        egoCtx.triggerRelaxation(leader);
                    }
                    Acceleration aTarget = MirovaCarFollowingUtil.followSingleLeader(this.vehicle, leader);
                    minAcc = Acceleration.min(minAcc, aTarget);
                }
            }

            // Evaluate lateral feasibility before committing. Only update startCondition when not
            // yet in a lateral move — once the physical change has begun we must complete it.
            if (!this.vehicle.getLaneChange().isChangingLane())
            {
                Speed resultingSpeed = egoSpeed.plus(minAcc.times(this.maneuverPattern.getPatternSpecificTimestep()));
                this.startCondition =
                        resultingSpeed.gt(Speed.instantiateSI(5.0)) && neighborsCtx.getIfLaneChangePossible(this.direction);
            }

            if (!this.startCondition)
            {
                // Conditions not met — return a longitudinal-only plan WITHOUT setting the action lock.
                // This is critical: committing here would block cooperative parallel patterns from
                // running (e.g. GapOpener) every tick the vehicle is stuck waiting for a gap.
                SimpleOperationalPlan waitPlan = new SimpleOperationalPlan(minAcc,
                        this.maneuverPattern.getPatternSpecificTimestep(), LateralDirectionality.NONE);
                if (this.direction.isLeft())
                {
                    waitPlan.setIndicatorIntentLeft();
                }
                else if (this.direction.isRight())
                {
                    waitPlan.setIndicatorIntentRight();
                }
                return waitPlan;
            }

            // Conditions confirmed — commit and initiate the lateral move.
            this.vehicle.commitToAction(this);
            SimpleOperationalPlan plan =
                    new SimpleOperationalPlan(minAcc, this.maneuverPattern.getPatternSpecificTimestep(), this.direction);
            if (this.direction.isLeft())
            {
                plan.setIndicatorIntentLeft();
            }
            else if (this.direction.isRight())
            {
                plan.setIndicatorIntentRight();
            }
            return plan;
        }

        @Override
        public SimpleOperationalPlan next()
                throws ParameterException, NullPointerException, IllegalArgumentException, GtuException, NetworkException
        {
            // Pattern completes when the vehicle is no longer laterally moving and has reached a new lane
            boolean finished =
                    !this.vehicle.getLaneChange().isChangingLane() && !this.originLane.equals(this.vehicle.getGtu().getLane());

            if (finished)
            {

                return finishManeuver();
            }
            return null;
        }

        @Override
        public SimpleOperationalPlan abort() throws ParameterException, GtuException, NetworkException
        {
            // If the start condition failed before the move began, terminate the pattern
            if (!this.startCondition)
            {
                //
                return finishManeuver();
            }
            return null;
        }

        @Override
        public double getUtility()
        {
            try
            {
                EgoContext ego = this.vehicle.getContext(EgoContext.class);
                // A vehicle that cannot physically move has no utility for a discretionary LC.
                boolean canMove = ego.getEgoSpeed().gt(Speed.instantiateSI(1.0))
                        || ego.getCurrentCarFollowingAcceleration().gt(Acceleration.instantiateSI(0.0));
                if (!canMove)
                {
                    return 0.0;
                }
            }
            catch (Exception e)
            {
                return 0.0;
            }

            Desire desire = this.maneuverPattern.getMirovaTacticalPlanner().getLaneChangeDesire();
            double baseUtility = desire.getDirectionalDesire(this.direction);

            if (this.isCooperative)
            {
                try
                {
                    double dFree = this.vehicle.getParameters().getParameter(MirovaParameters.DFREE);
                    // Cooperative LCs get a floor at D_FREE so they are preferred over non-cooperative
                    // ones when desire is similar.
                    baseUtility = Math.max(baseUtility, dFree);
                }
                catch (ParameterException e)
                {
                    // proceed with base utility
                }
            }

            return baseUtility;
        }

        @Override
        public String toString()
        {
            return "PerformLaneChangeState[" + this.direction + ", isCooperative=" + this.isCooperative + "]";
        }
    }
}
