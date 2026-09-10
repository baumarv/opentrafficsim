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
import org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging.DefectDiagnostics;

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

    /**
     * How much of the lane-change safety distance reduction the shadowing headway keeps.
     * <p>
     * The reduced headway a yield accepts is the lane-change reduction factor scaled by this: slightly less of a
     * reduction than a lane change itself takes, because the ego stays in its own lane here. It was written as a
     * bare 1.1 at both call sites.
     * </p>
     */
    private static final double SHADOW_HEADWAY_TOLERANCE = 1.1;

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
     * Returns whether a vehicle the ego has decided not to undercut still calls for that.
     * <p>
     * True while it is alongside or ahead within {@link #FAR_AWAY_GAP}; false once it is further ahead than that,
     * at which point the ego has dropped back far enough that it is no longer passing anything. Deliberately not a
     * function of the speed difference: that is the quantity the yield itself changes.
     * </p>
     * @param leftLeader HeadwayGtu; the vehicle being yielded to
     * @return boolean; true while the yield should continue
     */
    private static boolean stillWorthYieldingTo(final HeadwayGtu leftLeader)
    {
        Length distance = leftLeader.getDistance();
        return distance == null || distance.si < FAR_AWAY_GAP.si;
    }

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
    /**
     * Returns whether the traffic around the ego is flowing freely, judged by the lanes rather than by the ego.
     * <p>
     * This used to read the ego's own speed against {@code vCong}, which made the test a function of the very
     * quantity this pattern reduces. Yielding at the comfortable floor of -2.0 m/s^2 takes a vehicle from 75 km/h
     * below the threshold in about two seconds; the pattern then concluded that traffic was congested, where
     * undercutting is tolerated, and finished. The ego accelerated back over the threshold and the pattern
     * retriggered. Counting the exits of the resolution predicate over an hour, 3.28 % of its evaluations ended the
     * pattern that way against 0.95 % for every other reason together, and the resulting episodes had a median
     * length of 1.6 s - the braking time from 75 km/h to the threshold.
     * </p>
     * <p>
     * The macroscopic speed of the lanes is exogenous: one vehicle slowing down does not move it, so the decision
     * can no longer undo its own precondition.
     * </p>
     * @param vehicle MirovaTacticalPlanner; the ego vehicle
     * @return boolean; true while the surrounding traffic is above the congestion threshold
     */
    static boolean trafficIsFreeFlowing(final MirovaTacticalPlanner vehicle)
    {
        Speed threshold = vehicle.getParams().vCongScalar;
        try
        {
            MacroTrafficContext macro = vehicle.getContext(MacroTrafficContext.class);
            Speed here = macro.getAverageSpeedCurrent();
            Speed left = macro.getAverageSpeedLeft();
            Speed reference = here;
            if (left != null && (reference == null || left.gt(reference)))
            {
                reference = left;
            }
            if (reference != null && reference.si > 0.0)
            {
                return reference.gt(threshold);
            }
        }
        catch (Exception exception)
        {
            if (DefectDiagnostics.ENABLED)
            {
                DefectDiagnostics.swallowed("PreventUndercuttingPattern.trafficIsFreeFlowing", exception);
            }
            // No macroscopic estimate available yet; fall back on the ego, as before.
        }
        return vehicle.getContext(EgoContext.class).getEgoSpeed().gt(threshold);
    }

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

        boolean isFreeFlow = trafficIsFreeFlowing(vehicle);

        // Someone else is there now, or traffic has become congested, where undercutting is normal anyway.
        if (!leftLeader.getId().equals(pattern.getShadowingLeftNeighborId()) || !isFreeFlow)
        {
            return ActionState.FINISHED;
        }

        // The neighbour has settled it themselves by being far enough ahead.
        //
        // This used to end the yield on the speed difference as well, once the neighbour was more than
        // PULLING_AWAY_SPEED faster and PULLING_AWAY_GAP ahead. That condition was produced by the yield itself:
        // braking opens the speed difference, the neighbour counts as pulling away, the pattern finishes, the ego
        // accelerates, the difference closes and checkAbility triggers again. Measured over an hour, the result was
        // 836 episodes of a median 0.4 to 1.0 s across 357 vehicles - up to 42 for a single one - each taking
        // 1.3 m/s^2 from a vehicle the car-following model would have accelerated, at 75 to 88 km/h and in 81 % of
        // cases in free-flowing traffic.
        //
        // The distance alone decides now. It moves as the integral of the speed difference rather than with it, so
        // the ego's own response can no longer undo the decision within a tick. Ending later rather than sooner is
        // also the safer direction for what this pattern is for: not passing on the right.
        Length leftGap = neighbors.getFrontGapDistance(LateralDirectionality.LEFT);
        if (leftGap.si > FAR_AWAY_GAP.si)
        {
            return ActionState.FINISHED;
        }
        return null;
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

        // 1. On a lane that is being dropped the rule does not apply at all. A vehicle on an acceleration lane
        // passing a slower one on the mainline beside it is not overtaking on the right, it is merging, and the
        // regulation this pattern implements exempts that case. Without the test the pattern fired on the ramp:
        // 4501 of 3.28 million merges reached the lane change through one of its states, at 27 m instead of 82 m
        // and at 49.7 instead of 65.6 km/h, on every one of sixteen study days.
        InfrastructureContext infra = this.vehicle.getContext(InfrastructureContext.class);
        Length toLaneEnd = infra.getRouteDistanceToLaneEnd();
        if (toLaneEnd != null && toLaneEnd.si < this.vehicle.getParams().extendedLookAheadDistanceSi)
        {
            this.shadowingLeftNeighborId = null;
            setRunning(false);
            return false;
        }

        // 2. Check Traffic State (Undercutting is allowed/tolerated in congestion)
        boolean isFreeFlow = trafficIsFreeFlowing(this.vehicle);

        if (!isFreeFlow)
        {
            this.shadowingLeftNeighborId = null;
            setRunning(false);
            return false;
        }

        HeadwayGtu leftLeader = neighbors.getLeader(LateralDirectionality.LEFT);

        // 3. Already yielding to a particular vehicle: keep doing so until it is no longer one the ego would be
        // passing on the right. The trigger below cannot be used for that, because it is a time to collision -
        // distance over speed difference - and braking is what closes the speed difference. The decision therefore
        // undoes its own condition within a tick or two, which is what produced the observed flutter: episodes of a
        // median 0.4 s, up to 42 per vehicle, each one taking 1.3 m/s^2 away from a vehicle the car-following model
        // would have accelerated, at 75 to 88 km/h and in 81 % of cases in free-flowing traffic.
        //
        // The release criterion is a distance instead. It is monotone in a quantity the ego moves only slowly, and
        // it says what the rule means: the ego stops yielding once it has dropped far enough behind the vehicle to
        // no longer be passing it. Being held behind a slower vehicle on the left is not a failure state - it is
        // the regulation. The way out is the lane change to the left that this pattern already performs.
        if (this.shadowingLeftNeighborId != null)
        {
            if (leftLeader != null && this.shadowingLeftNeighborId.equals(leftLeader.getId())
                    && stillWorthYieldingTo(leftLeader))
            {
                return true;
            }
            this.shadowingLeftNeighborId = null;
        }

        // 4. Check Perception for Undercutting situation
        if (neighbors.getRightSideOvertakingAhead() && leftLeader != null)
        {
            this.shadowingLeftNeighborId = leftLeader.getId();
            return true;
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
                // Yielding accepts a closer gap than ordinary following, which the utility expresses by
                // overriding the desired headway for the one call and restoring it in a finally block.
                double headwayFactor = this.vehicle.getParams().safetyDistanceReductionFactorLaneChange
                        * SHADOW_HEADWAY_TOLERANCE;

                // 1. Acceleration for the left target lane
                Acceleration aShadowLeft = MirovaCarFollowingUtil.followDistanceAndSpeedWithReducedHeadway(this.vehicle,
                        leftDistHeadway.minus(leftLeaderLength), leftLeaderSpeed, headwayFactor);

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
                    new Transition("gap open on the left and wanted", "PerformLaneChangeState", this::gapOpenAndWanted),
                    new Transition("closing on the left leader with room behind it", "PrepareLaneChangeState",
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
            double headwayFactor =
                    this.vehicle.getParams().safetyDistanceReductionFactorLaneChange * SHADOW_HEADWAY_TOLERANCE;

            Acceleration aDecel;

            // If we have a comfortable gap, we can match the left leader's speed.
            // If not, we apply a more assertive deceleration to create space for the lane change.
            // A vehicle the ego has drawn level with reports a non-positive distance, and the car-following
            // model reads that as an imminent crash and answers with B_MAX - correct for a leader ahead, meaningless
            // for one alongside. Over a full run 5 % of the calls here were of that kind and 6 % came back at B_MAX.
            // The comfortable floor below capped every one of them at the value returned directly here, so the
            // answer is unchanged and only its provenance is: an intended yield rather than a division by a
            // negative gap. GapOpenerPattern guards its equivalent call the same way.
            aDecel = leftLeader.getDistance() != null && leftLeader.getDistance().si > 0.0
                    ? MirovaCarFollowingUtil.followWithReducedHeadway(this.vehicle, leftLeader, headwayFactor)
                    : COMFORTABLE_DECELERATION_FLOOR;

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
                    new Transition("gap open on the left", "PerformLaneChangeState", this::gapNowOpen),
                    new Transition("gap behind the left leader lost again", "ShadowingState", this::gapLostAgain));
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
