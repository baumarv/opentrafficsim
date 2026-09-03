package org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ManeuverPatterns;

import org.djunits.value.vdouble.scalar.Acceleration;
import org.djunits.value.vdouble.scalar.Speed;
import org.opentrafficsim.base.parameters.ParameterException;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.base.parameters.Parameters;
import org.opentrafficsim.core.gtu.GtuException;
import org.opentrafficsim.core.network.LateralDirectionality;
import org.opentrafficsim.core.network.NetworkException;
import org.opentrafficsim.road.gtu.lane.plan.operational.SimpleOperationalPlan;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.MirovaTacticalPlanner;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.EgoContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.InfrastructureContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.NeighborsContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.DesireLayer.Desire;
import java.util.List;

import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ActionState;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.Transition;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.LateralExecution;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ManeuverPattern;
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

    /** Speed above which the ego counts as moving rather than standing [m/s]. */
    private static final double MOVING_SPEED_SI = 1.0;

    /** Speed the ego must retain after the manoeuvre for a discretionary lane change to be worth it [m/s]. */
    private static final double LANE_CHANGE_MIN_SPEED_SI = 5.0;
    /** The target direction for the lane change. */
    private LateralDirectionality targetDirection = LateralDirectionality.NONE;

    /**
     * Constructs a new SimpleLaneChangePattern.
     * @param vehicle the tactical planner associated with the ego vehicle
     */
    public SimpleLaneChangePattern(final MirovaTacticalPlanner vehicle)
    {
        super(vehicle);
        this.targetDirection = this.vehicle.getLaneChangeDesire().dominantDirection();
        this.initialActionState = () -> new PerformLaneChangeState(this);
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
            // Trigger on the discretionary desire only. Using the combined desire made this
            // pattern declare itself relevant because of a *mandatory* desire it does not own,
            // so wherever a mandatory lane change is pending - on-ramp, off-ramp, any forced
            // change - the discretionary pattern competed for the manoeuvre as well.
            return this.vehicle.getDiscretionaryLaneChangeDesire().magnitude() >= this.vehicle.getParameters()
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
            boolean canMove = ego.getEgoSpeed().si > MOVING_SPEED_SI
                    || ego.getCurrentCarFollowingAcceleration().gt(Acceleration.ZERO);

            return canMove && (this.targetDirection.isLeft() || this.targetDirection.isRight())
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
            Speed egoSpeed = this.vehicle.getContext(EgoContext.class).getEgoSpeed();

            Acceleration minAcc = LateralExecution.accelerationForLateralMove(this.vehicle, this.direction);

            // Evaluate lateral feasibility before committing. Only update startCondition when not
            // yet in a lateral move — once the physical change has begun we must complete it.
            if (!this.vehicle.getLaneChange().isChangingLane())
            {
                Speed resultingSpeed = egoSpeed.plus(minAcc.times(this.maneuverPattern.getPatternSpecificTimestep()));
                this.startCondition =
                        resultingSpeed.si > LANE_CHANGE_MIN_SPEED_SI && neighborsCtx.getIfLaneChangePossible(this.direction);
            }

            if (!this.startCondition)
            {
                // Conditions not met — return a longitudinal-only plan WITHOUT setting the action lock.
                // This is critical: committing here would block cooperative parallel patterns from
                // running (e.g. GapOpener) every tick the vehicle is stuck waiting for a gap.
                SimpleOperationalPlan waitPlan = new SimpleOperationalPlan(minAcc,
                        this.maneuverPattern.getPatternSpecificTimestep(), LateralDirectionality.NONE);
                LateralExecution.setIndicators(waitPlan, this.direction);
                return waitPlan;
            }

            // Conditions confirmed — commit and initiate the lateral move.
            this.vehicle.commitToAction(this);
            SimpleOperationalPlan plan =
                    new SimpleOperationalPlan(minAcc, this.maneuverPattern.getPatternSpecificTimestep(), this.direction);
            LateralExecution.setIndicators(plan, this.direction);
            return plan;
        }

        @Override
        protected List<Transition> transitions()
        {
            return List.of(
                    new Transition("start conditions lost before the move began", "end", this::startConditionLost),
                    new Transition("lateral move complete", "end", this::moveComplete));
        }

        /**
         * Ends the pattern when the gate in {@code executeControl} closed before the crossing began.
         * @return {@link #FINISHED} if the start condition failed, {@code null} otherwise
         */
        private ActionState startConditionLost()
        {
            return this.startCondition ? null : FINISHED;
        }

        /**
         * Ends the pattern once the vehicle has arrived on the target lane.
         * @return {@link #FINISHED} when the crossing is over, {@code null} while it is not
         */
        private ActionState moveComplete()
        {
            return LateralExecution.lateralMoveFinished(this.vehicle, this.originLane) ? FINISHED : null;
        }

        @Override
        public double getUtility()
        {
            try
            {
                EgoContext ego = this.vehicle.getContext(EgoContext.class);
                // A vehicle that cannot physically move has no utility for a discretionary LC.
                boolean canMove = ego.getEgoSpeed().si > MOVING_SPEED_SI
                        || ego.getCurrentCarFollowingAcceleration().gt(Acceleration.ZERO);
                if (!canMove)
                {
                    return 0.0;
                }
            }
            catch (Exception e)
            {
                return 0.0;
            }

            // Scored on the discretionary desire for the same reason checkContext() is: the
            // combined desire contains the mandatory component, which made this state's utility
            // structurally greater than or equal to that of MandatoryLaneChangeState in the same
            // direction. The arbitration is winner-takes-all on utility, so the discretionary
            // pattern won the merge by construction rather than on its own merit.
            Desire desire = this.maneuverPattern.getMirovaTacticalPlanner().getDiscretionaryLaneChangeDesire();
            double baseUtility = desire.getDirectionalDesire(this.direction);

            if (this.isCooperative)
            {
                // Cooperative LCs get a floor at D_FREE so they are preferred over non-cooperative
                // ones when desire is similar.
                baseUtility = Math.max(baseUtility, this.vehicle.getParams().dFree);
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
