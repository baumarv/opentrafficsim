package org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ManeuverPatterns;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.djunits.value.vdouble.scalar.Acceleration;
import org.djunits.value.vdouble.scalar.Duration;
import org.djunits.value.vdouble.scalar.Length;
import org.djunits.value.vdouble.scalar.Speed;
import org.opentrafficsim.base.parameters.ParameterException;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.core.gtu.GtuException;
import org.opentrafficsim.core.network.LateralDirectionality;
import org.opentrafficsim.core.network.NetworkException;
import org.opentrafficsim.road.gtu.lane.perception.RelativeLane;
import org.opentrafficsim.road.gtu.lane.perception.headway.HeadwayGtu;
import org.opentrafficsim.road.gtu.lane.plan.operational.SimpleOperationalPlan;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.MirovaTacticalPlanner;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.EgoContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.InfrastructureContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.MacroTrafficContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.NeighborsContext;
import java.util.List;

import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ActionState;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.Transition;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ManeuverPattern;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ReactiveLayer.MirovaCarFollowingUtil;
import org.opentrafficsim.road.network.speed.SpeedLimitInfo;

/**
 * Parallel maneuver pattern that prevents undercutting on the right.
 * <p>
 * Forms part of <b>Layer 4 (Procedure & Action)</b> in the MiRoVA architecture. Ensures compliance with the German "no
 * overtaking on the right" regulation (Rechtsüberholverbot, §5 StVO). This pattern activates when the perception detects a
 * slower vehicle on the immediate left lane while traffic is free flowing (speed > VCONG).
 * </p>
 * <p>
 * Instead of performing a hard brake, it initiates a "Shadowing" state, matching the speed of the left neighbor until a lane
 * change is possible or the situation clears.
 * </p>
 * <p>
 * Copyright (c) 2025 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class PreventUndercuttingPattern extends ManeuverPattern
{
    /** Deceleration beyond which shadowing the left leader is abandoned as physically unreasonable. */
    private static final Acceleration SHADOW_ABORT_DECELERATION = Acceleration.instantiateSI(-6.0);

    /** Distance over which the left lane speed is approached while shadowing. */
    private static final Length SHADOW_APPROACH_DISTANCE = Length.instantiateSI(50.0);

    /** Comfortable deceleration floor applied while opening space for the lane change. */
    private static final Acceleration COMFORTABLE_DECELERATION_FLOOR = Acceleration.instantiateSI(-2.0);


    /** Gap beyond which the left neighbour is simply too far ahead for undercutting to be a concern. */
    private static final Length FAR_AWAY_GAP = Length.instantiateSI(80.0);

    /** Gap beyond which a neighbour that is also faster counts as pulling away rather than staying alongside. */
    private static final Length PULLING_AWAY_GAP = Length.instantiateSI(40.0);

    /** Speed advantage at which the left neighbour counts as pulling away. */
    private static final Speed PULLING_AWAY_SPEED = Speed.instantiateSI(1.0);

    /** Time headway below which the ego counts as running up on the left leader. */
    private static final Duration CLOSING_TIME_HEADWAY = Duration.instantiateSI(1.5);

    /**
     * Decides whether the undercutting situation this pattern reacts to still exists.
     * <p>
     * Both states asked exactly this, in two byte-identical copies. It is a question about the world rather than about the
     * phase the manoeuvre is in, so it is asked in one place and both tables name it first.
     * </p>
     * @param vehicle the ego vehicle
     * @param pattern the pattern, which remembers the neighbour being shadowed
     * @return {@link ActionState#FINISHED} once the situation has resolved, {@code null} while it persists
     * @throws ParameterException if a parameter lookup fails
     * @throws GtuException if a GTU query fails
     * @throws NetworkException if a network query fails
     */
    static ActionState undercuttingResolved(final MirovaTacticalPlanner vehicle, final PreventUndercuttingPattern pattern)
            throws ParameterException, GtuException, NetworkException
    {
        NeighborsContext neighbors = vehicle.getContext(NeighborsContext.class);
        HeadwayGtu leftLeader = neighbors.getLeader(LateralDirectionality.LEFT);

        // The neighbour being shadowed is gone.
        if (leftLeader == null)
        {
            return ActionState.FINISHED;
        }

        EgoContext ego = vehicle.getContext(EgoContext.class);
        boolean isFreeFlow = ego.getEgoSpeed().gt(vehicle.getParams().vCongScalar);

        // Someone else is there now, or traffic has become congested, where undercutting is normal anyway.
        if (!leftLeader.getId().equals(pattern.getShadowingLeftNeighborId()) || !isFreeFlow)
        {
            return ActionState.FINISHED;
        }

        // The neighbour has settled it themselves, by being far ahead or by pulling away.
        Length leftGap = neighbors.getFrontGapDistance(LateralDirectionality.LEFT);
        boolean isFarAway = leftGap.si > FAR_AWAY_GAP.si;
        boolean isPullingAway = leftLeader.getSpeed().si > ego.getEgoSpeed().si + PULLING_AWAY_SPEED.si
                && leftGap.si > PULLING_AWAY_GAP.si;

        return isFarAway || isPullingAway ? ActionState.FINISHED : null;
    }

    /** ID of the vehicle on the left lane that this ego vehicle is currently shadowing. */
    protected String shadowingLeftNeighborId = null;

    /**
     * Constructs a new PreventUndercuttingPattern.
     * @param vehicle the tactical planner associated with the ego vehicle
     * @throws ParameterException if parameter initialization fails
     */
    public PreventUndercuttingPattern(final MirovaTacticalPlanner vehicle) throws ParameterException
    {
        super(vehicle);
        this.initialActionState = () -> new ShadowingState(this);
    }

    /**
     * Determines if this pattern is applicable based on the current context.
     * <p>
     * Logic: 1. Check if traffic is flowing (Speed > VCONG). Undercutting is allowed in congestion. 2. Check if a right-side
     * overtaking situation is detected ahead.
     * </p>
     * @return {@code true} if we are at risk of undercutting and must prevent it, {@code false} otherwise
     */
    @Override
    public boolean checkAbility()
    {
        NeighborsContext neighbors = this.vehicle.getContext(NeighborsContext.class);
        EgoContext ego = this.vehicle.getContext(EgoContext.class);

        // 1. Check Traffic State (Undercutting is allowed/tolerated in congestion)
        Speed congestionThreshold = this.vehicle.getParams().vCongScalar;
        boolean isFreeFlow = ego.getEgoSpeed().gt(congestionThreshold);

        if (isFreeFlow)
        {
            // 2. Check Perception for Undercutting situation
            boolean potentialUndercut = neighbors.getRightSideOvertakingAhead();

            if (potentialUndercut)
            {
                this.shadowingLeftNeighborId = neighbors.getLeader(LateralDirectionality.LEFT).getId();
                return true;
            }
        }

        this.shadowingLeftNeighborId = null;
        setRunning(false);
        return false;
    }

    /**
     * Context check placeholder for parallel execution.
     * @return always {@code true}, as contextual relevance is handled via checkAbility
     */
    @Override
    public boolean checkContext()
    {
        return true;
    }

    /**
     * Returns the ID of the left neighbor currently being shadowed.
     * @return the ID of the left neighbor, or null if no vehicle is being shadowed
     */
    public String getShadowingLeftNeighborId()
    {
        return this.shadowingLeftNeighborId;
    }

    /*
     * ========================================================================================= STATE: SHADOWING
     * =========================================================================================
     */

    /**
     * The active state of this pattern.
     * <p>
     * It calculates an acceleration that matches the left neighbor (Shadowing), while respecting the safety distance to the own
     * leader.
     * </p>
     */
    public static class ShadowingState extends ActionState
    {
        /** The parent maneuver pattern. */
        private final PreventUndercuttingPattern maneuverPattern;

        /**
         * Constructor.
         * @param pattern the parent maneuver pattern
         */
        public ShadowingState(final PreventUndercuttingPattern pattern)
        {
            super(pattern);
            this.maneuverPattern = pattern;
        }

        /**
         * Executes the longitudinal control to shadow the left neighbor.
         * @return the operational plan for the current tick
         * @throws ParameterException if a parameter lookup fails
         * @throws GtuException if GTU state prevents plan generation
         * @throws NetworkException if network topology limits calculation
         */
        @Override
        public SimpleOperationalPlan executeControl() throws ParameterException, GtuException, NetworkException
        {
            this.maneuverPattern.setRunning(true);
            this.maneuverPattern.setCurrentActionState(this);

            EgoContext ego = this.vehicle.getContext(EgoContext.class);
            InfrastructureContext infra = this.vehicle.getContext(InfrastructureContext.class);
            NeighborsContext neighbors = this.vehicle.getContext(NeighborsContext.class);

            LateralDirectionality leftDir = LateralDirectionality.LEFT;

            // We re-verify existence to be safe, though pattern logic checked it
            if (neighbors.getLeader(leftDir) != null)
            {
                HeadwayGtu leftLeader = neighbors.getLeader(leftDir);
                Length leftDistHeadway = neighbors.getFrontGapDistance(leftDir);
                Speed leftLeaderSpeed = leftLeader.getSpeed();
                Length leftLeaderLength = leftLeader.getLength();

                // Calculate acceleration required to stay behind the left vehicle
                Double safetyDistanceReductionFactorLaneChange =
                        this.vehicle.getParams().safetyDistanceReductionFactorLaneChange
                                * 1.1;
                Duration timeHeadwayReduced = this.vehicle.getParameters().getParameter(ParameterTypes.T)
                        .times(safetyDistanceReductionFactorLaneChange);
                this.vehicle.getParameters().setParameterResettable(ParameterTypes.T, timeHeadwayReduced);

                // 1. Berechnung für den linken Zielfahrstreifen
                Acceleration aShadowLeft = MirovaCarFollowingUtil.followDistanceAndSpeed(this.vehicle,
                        leftDistHeadway.minus(leftLeaderLength), leftLeaderSpeed);

                this.vehicle.getParameters().resetParameter(ParameterTypes.T);

                // Emergency break logic für die Ziellücke
                if (aShadowLeft.lt(SHADOW_ABORT_DECELERATION))
                {
                    MacroTrafficContext macroCtx = this.vehicle.getContext(MacroTrafficContext.class);
                    Speed leftLaneSpeed = macroCtx.getAverageSpeed(RelativeLane.LEFT);
                    aShadowLeft =
                            MirovaCarFollowingUtil.approachTargetSpeed(this.vehicle, SHADOW_APPROACH_DISTANCE, leftLaneSpeed);
                }

                // Limit deceleration to a comfortable level for the lane maneuver
                EgoContext egoCtx = this.vehicle.getContext(EgoContext.class);
                Acceleration comfortableEgoDecel = egoCtx.getEgoDecelerationThreshold(LateralDirectionality.LEFT);
                aShadowLeft = Acceleration.max(aShadowLeft, comfortableEgoDecel);
                Acceleration aTarget = Acceleration.min(aShadowLeft, egoCtx.getCurrentCarFollowingAcceleration());

                return new SimpleOperationalPlan(aTarget,
                        this.vehicle.getParams().dtScalar);
            }

            return null; // No left leader, should not happen as pattern should not be active, but safety first
        }

        /**
         * Checks if the vehicle can transition out of the shadowing state.
         * @return transition to lane change preparation, or {@code null} to continue shadowing
         * @throws ParameterException if a parameter lookup fails
         * @throws GtuException if GTU limits fail
         * @throws NetworkException if network topology fails
         */
        @Override
        protected List<Transition> transitions()
        {
            return List.of(
                    new Transition("undercutting situation resolved", "end",
                            () -> undercuttingResolved(this.vehicle, (PreventUndercuttingPattern) this.maneuverPattern)),
                    new Transition("gap open on the left and wanted", "PerformLaneChange", this::gapOpenAndWanted),
                    new Transition("closing on the left leader with room behind it", "PrepareLaneChange",
                            this::worthPreparing));
        }

        /**
         * Goes straight into the lane change when the gap is already there and the ego wants it.
         * @return the lane-change state, or {@code null}
         * @throws ParameterException if a parameter lookup fails
         * @throws GtuException if a GTU query fails
         * @throws NetworkException if a network query fails
         */
        private ActionState gapOpenAndWanted() throws ParameterException, GtuException, NetworkException
        {
            NeighborsContext neighbors = this.vehicle.getContext(NeighborsContext.class);
            if (this.vehicle.getMandatoryLaneChangeDesire().getMandatoryDesire(LateralDirectionality.LEFT) >= 0.0
                    && neighbors.getIfLaneChangePossible(LateralDirectionality.LEFT))
            {
                return new SimpleLaneChangePattern.PerformLaneChangeState(this.maneuverPattern,
                        LateralDirectionality.LEFT, true);
            }
            return null;
        }

        /**
         * Starts preparing once the ego is running up on the left leader and there is room behind it to slot into.
         * @return the preparation state, or {@code null} while shadowing is still the right thing to do
         * @throws ParameterException if a parameter lookup fails
         * @throws GtuException if a GTU query fails
         * @throws NetworkException if a network query fails
         */
        private ActionState worthPreparing() throws ParameterException, GtuException, NetworkException
        {
            NeighborsContext neighbors = this.vehicle.getContext(NeighborsContext.class);
            Duration leftTimeHeadway = neighbors.getFrontGapTimeHeadway(LateralDirectionality.LEFT);

            if (leftTimeHeadway.si < CLOSING_TIME_HEADWAY.si)
            {
                Duration gapLeftLane = getGapBehindLeftLeader(this.vehicle);
                if (gapLeftLane.ge(this.vehicle.getParameters().getParameter(ParameterTypes.T)))
                {
                    return new PrepareLaneChangeState(this.maneuverPattern);
                }
            }
            return null;
        }

        /**
         * * Calculates the time headway to the left leader, considering both front and rear gaps and the length of the left
         * leader. This is used to determine if we have enough gap to safely move behind the left leader. * @param vehicle the
         * tactical planner instance
         * @return Duration representing the time headway to the left leader
         * @throws ParameterException if parameters are missing
         * @throws GtuException if GTU-related errors occur
         * @throws NetworkException if network-related errors occur
         */
        public static Duration getGapBehindLeftLeader(final MirovaTacticalPlanner vehicle)
                throws ParameterException, GtuException, NetworkException
        {
            Duration gapLeftLane;
            NeighborsContext neighbors = vehicle.getContext(NeighborsContext.class);
            HeadwayGtu leftLeader = neighbors.getLeader(LateralDirectionality.LEFT);

            if (leftLeader.isParallel())
            {
                gapLeftLane = neighbors.getRearGapTimeHeadway(LateralDirectionality.LEFT);
            }
            else
            {
                Length gapLength = neighbors.getRearGapDistance(LateralDirectionality.LEFT)
                        .plus(neighbors.getFrontGapDistance(LateralDirectionality.LEFT)).plus(vehicle.getGtu().getLength());
                EgoContext ego = vehicle.getContext(EgoContext.class);
                gapLeftLane = gapLength.divide(ego.getEgoSpeed());
            }
            return gapLeftLane;
        }

        @Override
        public double getUtility()
        {
            return 0.1;
        }

        @Override
        public String toString()
        {
            return "PreventUndercutting:Shadowing";
        }
    }

    /*
     * ========================================================================================= STATE: PREPARE_LANE_CHANGE
     * =========================================================================================
     */

    /**
     * * Prepares for the lane change by ensuring we have a safe gap to the left leader and adjusting speed if necessary. This
     * state is a safety buffer before initiating the lane change, ensuring we do not cut in too closely behind the left leader.
     */
    public static class PrepareLaneChangeState extends ActionState
    {
        private final PreventUndercuttingPattern maneuverPattern;

        /**
         * Constructor.
         * @param pattern the parent maneuver pattern
         */
        public PrepareLaneChangeState(final PreventUndercuttingPattern pattern)
        {
            super(pattern);
            this.maneuverPattern = pattern;
        }

        /**
         * Executes deceleration to ensure a comfortable gap before changing lanes.
         * @return the operational plan
         * @throws ParameterException if a parameter lookup fails
         * @throws GtuException if GTU state prevents plan generation
         * @throws NetworkException if network topology limits calculation
         */
        @Override
        public SimpleOperationalPlan executeControl() throws ParameterException, GtuException, NetworkException
        {
            this.maneuverPattern.setRunning(true);
            this.maneuverPattern.setCurrentActionState(this);

            EgoContext ego = this.vehicle.getContext(EgoContext.class);
            NeighborsContext neighbors = this.vehicle.getContext(NeighborsContext.class);

            LateralDirectionality leftDir = LateralDirectionality.LEFT;
            HeadwayGtu leftLeader = neighbors.getLeader(leftDir);

            if (leftLeader == null)
            {
                // Should not happen as we checked in the previous state, but we add a safety check.
                return null;
            }

            // Calculate acceleration required to stay behind the left vehicle
            Double safetyDistanceReductionFactorLaneChange =
                    this.vehicle.getParams().safetyDistanceReductionFactorLaneChange * 1.1;
            Duration timeHeadwayReduced =
                    this.vehicle.getParameters().getParameter(ParameterTypes.T).times(safetyDistanceReductionFactorLaneChange);
            this.vehicle.getParameters().setParameterResettable(ParameterTypes.T, timeHeadwayReduced);

            Acceleration aDecel;

            // If we have a comfortable gap, we can match the left leader's speed.
            // If not, we apply a more assertive deceleration to create space for the lane change.
            aDecel = MirovaCarFollowingUtil.followSingleLeader(this.vehicle, leftLeader);

            this.vehicle.getParameters().resetParameter(ParameterTypes.T);
            aDecel = Acceleration.max(aDecel, COMFORTABLE_DECELERATION_FLOOR); // Limit deceleration to a comfortable level

            aDecel = Acceleration.min(aDecel, ego.getCurrentCarFollowingAcceleration()); // Do not decelerate more than current
                                                                                         // following accel

            SimpleOperationalPlan plan =
                    new SimpleOperationalPlan(aDecel, this.vehicle.getParams().dtScalar);
            plan.setIndicatorIntentLeft();

            return plan;
        }

        /**
         * Checks if the preparation is complete and the lane change can begin.
         * @return transition to perform lane change, transition back to shadowing if gap lost, or null
         * @throws ParameterException if a parameter lookup fails
         * @throws GtuException if GTU limits fail
         * @throws NetworkException if network topology fails
         */
        @Override
        protected List<Transition> transitions()
        {
            return List.of(
                    new Transition("undercutting situation resolved", "end",
                            () -> undercuttingResolved(this.vehicle, (PreventUndercuttingPattern) this.maneuverPattern)),
                    new Transition("gap open on the left", "PerformLaneChange", this::gapNowOpen),
                    new Transition("gap behind the left leader lost again", "Shadowing", this::gapLostAgain));
        }

        /**
         * Begins the lane change once the gap the preparation was opening has appeared.
         * @return the lane-change state, or {@code null}
         * @throws ParameterException if a parameter lookup fails
         * @throws GtuException if a GTU query fails
         * @throws NetworkException if a network query fails
         */
        private ActionState gapNowOpen() throws ParameterException, GtuException, NetworkException
        {
            NeighborsContext neighbors = this.vehicle.getContext(NeighborsContext.class);
            if (neighbors.getIfLaneChangePossible(LateralDirectionality.LEFT))
            {
                // This used to call finishManeuver() and throw its plan away before transitioning, which marked the
                // pattern finished and then entered a state in it. It was harmless only because the state entered sets
                // the running flag again on its first tick. Naming the target is the same transition without the
                // contradiction.
                return new SimpleLaneChangePattern.PerformLaneChangeState(this.maneuverPattern,
                        LateralDirectionality.LEFT, true);
            }
            return null;
        }

        /**
         * Falls back to shadowing when the gap closes again during the preparation, rather than cutting in too closely.
         * @return the shadowing state, or {@code null} while the preparation still makes sense
         * @throws ParameterException if a parameter lookup fails
         * @throws GtuException if a GTU query fails
         * @throws NetworkException if a network query fails
         */
        private ActionState gapLostAgain() throws ParameterException, GtuException, NetworkException
        {
            return ShadowingState.getGapBehindLeftLeader(this.vehicle).si
                    < this.vehicle.getParameters().getParameter(ParameterTypes.T).si
                            ? new ShadowingState(this.maneuverPattern) : null;
        }

        @Override
        public double getUtility()
        {
            return 0.1;
        }

        @Override
        public String toString()
        {
            return "PreventUndercutting:PrepareLaneChange";
        }
    }
}
