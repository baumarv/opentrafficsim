package org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ManeuverPatterns;

import java.util.Iterator;

import org.djunits.unit.SpeedUnit;
import org.djunits.value.vdouble.scalar.Acceleration;
import org.djunits.value.vdouble.scalar.Duration;
import org.djunits.value.vdouble.scalar.Length;
import org.djunits.value.vdouble.scalar.Speed;
import org.opentrafficsim.base.parameters.ParameterException;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.base.parameters.Parameters;
import org.opentrafficsim.core.gtu.GtuException;
import org.opentrafficsim.core.gtu.plan.operational.OperationalPlanException;
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
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.InfrastructureContext.ScanDirection;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ActionState;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ManeuverPattern;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ManeuverPatterns.helpers.GapCandidate;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ReactiveLayer.MirovaCarFollowingUtil;
import org.opentrafficsim.road.network.lane.Lane;

/**
 * Mandatory lane change pattern with long-range anticipation for merge scenarios.
 * <p>
 * This pattern extends the traditional gap search by adding an early anticipation phase. It actively looks up to
 * extendedLookAheadDistance ahead to determine the average speed in the merge area without globally increasing the continuous
 * car-following look-ahead, thereby preserving simulation performance. It implements a state machine transitioning from early
 * anticipation to active gap searching and execution.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class MandatoryLaneChangePattern extends ManeuverPattern
{
    /** The intended lateral direction for the maneuver. */
    private LateralDirectionality targetDirection;

    /** The currently targeted gap on the adjacent lane. */
    private GapCandidate activeGap;

    /** Buffer distance before the end of the lane where emergency braking is enforced. */
    public static final Length RAMP_END_BUFFER = Length.instantiateSI(10.0);

    /** Specific simulation time step for the execution of this maneuver. */
    private final Duration patternSpecificTimestep = Duration.instantiateSI(0.1);

    /** Distance threshold to transition from anticipation to active matching. */
    private static final Length ANTICIPATION_THRESHOLD = Length.instantiateSI(400.0);

    /**
     * Constructs a new MandatoryLaneChangePattern.
     * @param vehicle the tactical planner associated with the ego vehicle
     */
    public MandatoryLaneChangePattern(final MirovaTacticalPlanner vehicle)
    {
        super(PatternType.EXCLUSIVE, vehicle);
        // Start in the early anticipation state
        this.initialActionState = () -> new AnticipateMergeState(this);
        this.targetDirection = this.vehicle.getLaneChangeDesire().dominantDirection();
        this.requiredContextKeys.add("Ego");
        this.requiredContextKeys.add("Neighbors");
        this.requiredContextKeys.add("Infrastructure");
        this.requiredContextKeys.add("MacroTraffic");
    }

    /**
     * Gets the lateral direction of the target lane.
     * @return the target direction
     */
    public LateralDirectionality getTargetDirection()
    {
        return this.vehicle.getLaneChangeDesire().dominantDirection();
    }

    /**
     * Gets the currently active gap candidate.
     * @return the active gap
     */
    public GapCandidate getActiveGap()
    {
        return this.activeGap;
    }

    /**
     * Sets the currently active gap candidate.
     * @param gap the gap to target
     */
    public void setActiveGap(final GapCandidate gap)
    {
        this.activeGap = gap;
    }

    @Override
    public boolean checkContext()
    {
        try
        {
            // Activate earlier than the old GapSearchPattern!
            // E.g., trigger if there is a known merge ahead within the extended lookahead
            InfrastructureContext infra = this.vehicle.getContext(InfrastructureContext.class);
            Length distToMerge = infra.getDistanceToLaneChangeExtendedLookahead();

            // Trigger if within 1000m OR if standard desire is high
            boolean isApproachingMerge = distToMerge.si > 0 && distToMerge.si < this.vehicle.getParameters()
                    .getParameter(MirovaParameters.extendedLookAheadDistance).si;
            boolean isDesireHigh = this.vehicle.getLaneChangeDesire().magnitude() >= this.vehicle.getParameters()
                    .getParameter(MirovaParameters.DMAND);
            // Lowered threshold for early
            // activation

            return isApproachingMerge || isDesireHigh; // || isDesireHigh;
        }
        catch (Exception exception)
        {
            return false;
        }
    }

    @Override
    public boolean checkAbility()
    {
        return true; // Assume the vehicle is always able to perform the maneuver if the context is right
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

    /**
     * Shared helper: returns {@code true} if there is a parallel-blocking vehicle on the target lane.
     * <p>
     * A vehicle is considered a parallel block when it is physically overlapping the ego vehicle (isParallel) OR when it is
     * within the reduced safety distance AND driving at nearly the same speed (delta &lt; 1.0 m/s). This matches the conditions
     * used in {@code DownstreamMergeState} (line ~616).
     * </p>
     * @param neigh the neighbors context
     * @param dir target lateral direction
     * @param ego the ego context
     * @param params vehicle parameters
     * @return whether a parallel-blocking vehicle exists on the target lane
     * @throws ParameterException if the safety-reduction-factor parameter is missing
     */
    static boolean detectParallelBlock(final NeighborsContext neigh, final LateralDirectionality dir, final EgoContext ego,
            final org.opentrafficsim.base.parameters.Parameters params) throws ParameterException
    {
        HeadwayGtu leader = neigh.getLeader(dir);
        HeadwayGtu follower = neigh.getFollower(dir);
        Length safe = ego.getDesiredFrontHeadway(dir);
        double factor = params.getParameter(MirovaParameters.safetyDistanceReductionFactorLaneChange);
        if (leader != null && (leader.isParallel()
                || (leader.getDistance().si < safe.si * factor && Math.abs(leader.getSpeed().si - ego.getEgoSpeed().si) < 1.0)))
        {
            return true;
        }
        return follower != null && (follower.isParallel() || (follower.getDistance().si < safe.si * factor
                && Math.abs(follower.getSpeed().si - ego.getEgoSpeed().si) < 1.0));
    }

    /*
     * ========================================================================================= 1) STATE: ANTICIPATE_MERGE
     * =========================================================================================
     */

    /**
     * Early state where the vehicle looks far ahead to determine the speed at the merge bottleneck and softly adapts its speed,
     * without actively forcing a gap search yet.
     */
    public static class AnticipateMergeState extends ActionState
    {
        /** Reference to the parent pattern for accessing shared data and parameters. */
        private final MandatoryLaneChangePattern pattern;

        /** Smoothed anticipated speed to prevent high frequency oscillations (low-pass filter). */
        private Speed smoothedMergeSpeed = null;

        /** Smoothing factor (alpha) for the Exponential Moving Average (EMA). 0.0 < alpha <= 1.0 */
        private double SPEED_SMOOTHING_FACTOR = 0.1;

        /**
         * Constructor for the anticipation state.
         * @param p the parent maneuver pattern
         */
        public AnticipateMergeState(final MandatoryLaneChangePattern p)
        {
            super(p);
            this.pattern = (MandatoryLaneChangePattern) p;
            this.active = true;
            this.maneuverPattern.setRunning(true);
            try
            {
                this.SPEED_SMOOTHING_FACTOR = this.vehicle.getParameters().getParameter(ParameterTypes.DT).si * 0.25;
            }
            catch (ParameterException exception)
            {
                exception.printStackTrace();
            }
        }

        @Override
        public SimpleOperationalPlan executeControl() throws ParameterException, GtuException, NetworkException
        {
            Acceleration criticalDecelThreshold = Acceleration.instantiateSI(-2.0); // Ggf. aus Parametern holen
            EgoContext ego = this.vehicle.getContext(EgoContext.class);
            InfrastructureContext infra = this.vehicle.getContext(InfrastructureContext.class);
            Acceleration aCf = ego.getCurrentCarFollowingAcceleration();

            if (aCf.gt(criticalDecelThreshold))
            {
                Speed vEgo = ego.getEgoSpeed();
                Speed speedLimit = infra.getLegalSpeedLimit();
                if (vEgo.lt(speedLimit))
                {
                    Lane targetLane = infra.getDownstreamAdjacentLane(this.pattern.getTargetDirection());

                    if (targetLane != null)
                    {
                        Speed targetLaneSpeed = infra.getLaneAverageSpeed(targetLane, Length.instantiateSI(0.0),
                                Length.instantiateSI(150.0), 3, ScanDirection.FRONT_TO_BACK);
                        Speed targetSpeed = Speed.max(targetLaneSpeed, new Speed(20.0, SpeedUnit.KM_PER_HOUR));
                        targetSpeed = Speed.min(targetSpeed, speedLimit);
                        if (ego.getEgoSpeed().gt(targetSpeed))
                        {
                            Acceleration aToTarget = MirovaCarFollowingUtil.approachTargetSpeed(this.vehicle,
                                    Length.instantiateSI(10.0), targetSpeed);
                            Acceleration egoDecelThreshold = ego.getEgoDecelerationThreshold(this.pattern.getTargetDirection());
                            aToTarget = Acceleration.max(aToTarget, egoDecelThreshold);

                            return new SimpleOperationalPlan(aToTarget, this.pattern.patternSpecificTimestep);
                        }

                    }
                }
            }

            return new SimpleOperationalPlan(aCf, this.pattern.patternSpecificTimestep);

        }

        @Override
        public SimpleOperationalPlan next() throws ParameterException, OperationalPlanException, NetworkException, GtuException
        {
            InfrastructureContext infra = this.vehicle.getContext(InfrastructureContext.class);
            // 1. FIX: Den vergessenen ANTICIPATION_THRESHOLD anwenden!
            boolean isLaneAvailable = infra.getIfLaneAvailable(this.pattern.getTargetDirection());
            if (isLaneAvailable)
            {
                return transitionTo(new EvaluateTargetGapState(this.maneuverPattern));
            }

            return null; // Bleibe in der Antizipation, wenn noch weit weg
        }

        @Override
        public SimpleOperationalPlan abort() throws ParameterException, GtuException, NetworkException
        {
            try
            {
                InfrastructureContext infra = this.vehicle.getContext(InfrastructureContext.class);
                if (infra.getDistanceToLaneChangeExtendedLookahead().si >= this.vehicle.getParameters()
                        .getParameter(MirovaParameters.extendedLookAheadDistance).si)
                {
                    return finishManeuver();
                }
            }
            catch (Exception exception)
            {
                return finishManeuver();
            }
            return null;
        }

        @Override
        public double getUtility()
        {
            return this.vehicle.getMandatoryLaneChangeDesire().magnitude();
        }

        @Override
        public String toString()
        {
            return "AnticipateMergeState";
        }
    }

    /*
     * ========================================================================================= STATE 1: EVALUATE TARGET GAP
     * =========================================================================================
     */

    /**
     * State that evaluates the target gap using a safety-first heuristic hierarchy.
     * <p>
     * It strictly evaluates kinematic constraints (Ego and Follower induced decelerations) before resolving spatial conflicts
     * (parallel vehicles). If decelerations are critical, it immediately routes the finite state machine to escape or brake,
     * rendering the parallel vehicle secondary until the speeds are synchronized.
     * </p>
     * <p>
     * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
     * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
     * </p>
     * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
     */
    public static class EvaluateTargetGapState extends ActionState
    {
        /** The parent mandatory lane change pattern. */
        private final MandatoryLaneChangePattern pattern;

        /** Time horizon in seconds to evaluate overtaking maneuvers. */
        private static final double TIME_HORIZON_S = 3.0;

        /**
         * Constructor for the evaluation state.
         * @param p the parent maneuver pattern
         */
        public EvaluateTargetGapState(final ManeuverPattern p)
        {
            super(p);
            this.pattern = (MandatoryLaneChangePattern) p;
            this.active = true;
        }

        @Override
        public SimpleOperationalPlan executeControl() throws ParameterException, GtuException, NetworkException
        {
            Acceleration criticalDecelThreshold = Acceleration.instantiateSI(-2.0); // Ggf. aus Parametern holen
            EgoContext ego = this.vehicle.getContext(EgoContext.class);
            InfrastructureContext infra = this.vehicle.getContext(InfrastructureContext.class);
            Acceleration aCf = ego.getCurrentCarFollowingAcceleration();
            SimpleOperationalPlan plan = null;

            if (aCf.gt(criticalDecelThreshold))
            {
                Speed vEgo = ego.getEgoSpeed();
                Speed speedLimit = infra.getLegalSpeedLimit();
                if (vEgo.lt(speedLimit))
                {
                    MacroTrafficContext macro = this.vehicle.getContext(MacroTrafficContext.class);
                    RelativeLane targetRelativeLane =
                            (this.pattern.getTargetDirection().isLeft()) ? RelativeLane.LEFT : RelativeLane.RIGHT;
                    Speed targetLaneSpeed = macro.getAverageSpeed(targetRelativeLane);

                    Speed targetSpeed = Speed.min(targetLaneSpeed, speedLimit);
                    Acceleration aToTarget =
                            MirovaCarFollowingUtil.approachTargetSpeed(this.vehicle, Length.instantiateSI(10.0), targetSpeed);
                    plan = new SimpleOperationalPlan(aToTarget, this.pattern.patternSpecificTimestep);

                }
            }
            if (plan == null)
            {
                plan = new SimpleOperationalPlan(aCf, this.pattern.patternSpecificTimestep);
            }

            if (this.pattern.getTargetDirection().isLeft())
            {
                plan.setIndicatorIntentLeft();
            }
            else if (this.pattern.getTargetDirection().isRight())
            {
                plan.setIndicatorIntentRight();
            }

            return plan;

        }

        @Override
        public SimpleOperationalPlan next() throws ParameterException, OperationalPlanException, NetworkException, GtuException
        {
            NeighborsContext neigh = this.vehicle.getContext(NeighborsContext.class);
            LateralDirectionality dir = this.pattern.getTargetDirection();

            // 0. Physical execution check: If the gap is perfectly clear, execute immediately
            if (neigh.getIfLaneChangePossible(dir))
            {
                return transitionTo(new ExecuteLaneChangeState(this.maneuverPattern, dir));
            }

            Length distToLaneEnd = this.vehicle.getContext(InfrastructureContext.class).getRouteDistanceToLaneEnd();
            // Notbremse, falls das Ende der Rampe unweigerlich näher rückt
            if (distToLaneEnd != null)
            {
                Acceleration requiredStopAccel =
                        MirovaCarFollowingUtil.stop(this.vehicle, distToLaneEnd.minus(RAMP_END_BUFFER));
                if (requiredStopAccel.si < -5.0)
                {
                    return transitionTo(new DecelEndOfRampState(this.maneuverPattern));
                }
            }

            // --> NEU: Übergang in den Congested Merge State bei zähfließendem Verkehr (< 15 km/h)
            Speed egoSpeed = this.vehicle.getContext(EgoContext.class).getEgoSpeed();
            if (egoSpeed.lt(new Speed(15.0, org.djunits.unit.SpeedUnit.KM_PER_HOUR)))
            {
                return transitionTo(new CongestedMergeState(this.maneuverPattern));
            }

            // Check for parallel vehicle (physically overlapping)
            HeadwayGtu parallel = null;
            HeadwayGtu leader = neigh.getLeader(dir);
            if (leader != null && (leader.isParallel() || leader.getDistance().si < 0.0))
            {
                parallel = leader;
            }
            else
            {
                HeadwayGtu follower = neigh.getFollower(dir);
                if (follower != null && (follower.isParallel() || follower.getDistance().si < 0.0))
                {
                    parallel = follower;
                }
            }

            if (parallel != null)
            {
                return transitionTo(new SolveParallelVehicleState(this.maneuverPattern));
            }

            // Get the actual follower behind the gap (must not be parallel/overlapping)
            HeadwayGtu actualFollower = null;
            Iterable<HeadwayGtu> followers = neigh.getFollowers(dir);
            if (followers != null)
            {
                for (HeadwayGtu gtu : followers)
                {
                    if (gtu.getDistance().si >= 0.0)
                    {
                        actualFollower = gtu;
                        break; // Only the closest follower behind the gap matters for safety
                    }
                }
            }

            EgoContext ego = this.vehicle.getContext(EgoContext.class);

            // === Kinematic reachability check for the downstream gap ===
            // The downstream gap is only reachable if the ego can accelerate to the effective
            // target speed (bounded by its desired speed v_wunsch) within the distance that
            // remains until the lane change must be completed.
            //
            // Variables used:
            // v_ego – current ego speed
            // v_wunsch – ego free-flow desired speed (ParameterTypes.V0)
            // v_leader – current speed of the target lane leader
            // a_max_current – speed-dependent max physical acceleration (decreases with speed)
            // d_available – remaining ramp distance minus one lane-change safety buffer
            //
            // Kinematic check: d_required = (v_target² - v_ego²) / (2 · a_max) ≤ d_available
            boolean downstreamGapReachable = true;
            HeadwayGtu targetLeader = neigh.getLeader(dir);
            if (targetLeader != null && !targetLeader.isParallel() && targetLeader.getDistance().si >= 0.0
                    && distToLaneEnd != null)
            {
                double vEgo = egoSpeed.si;
                double vLeader = targetLeader.getSpeed().si;

                // Ego desired speed (v_wunsch) from the car-following model
                // (accounts for speed-limit scaling, vehicle type, etc.)
                Speed desiredSpeedResult = ego.getCurrentDesiredSpeed();
                double vWunsch = desiredSpeedResult.si;

                // Maximum physical acceleration evaluated at the TARGET speed (v_overtake) – not at
                // the current ego speed. The ego must sustain this acceleration until it reaches
                // v_overtake, so the relevant operating point is the higher (more limiting) speed.
                // getMaxPhysicalAccelerationAt() uses the same piece-wise linear model as
                // getMaxPhysicalAcceleration() but for an arbitrary speed, avoiding caching.
                //
                // To merge AHEAD of the leader, the ego must be going faster than the leader.
                // Add an overtake margin so the reachability check accounts for this requirement.
                final double OVERTAKE_MARGIN_MS = 3.0; // ~11 km/h above leader speed
                double vTarget = vLeader + OVERTAKE_MARGIN_MS;

                double aMaxCurrent = Math.max(ego.getMaxPhysicalAccelerationAt(Speed.instantiateSI(vWunsch)).si, 0.01);

                if (vTarget > vWunsch)
                {
                    // Even with the overtake margin, the ego would need to exceed its desired speed
                    // → downstream gap is structurally unreachable.
                    downstreamGapReachable = false;
                }
                else
                {
                    if (vTarget > vEgo)
                    {
                        // Ego needs to accelerate to the overtake speed. Subtract a lane-change
                        // safety buffer so there is still room to execute the lane change afterwards.
                        // Buffer ≈ v_ego * LCDUR_default (4 s conservative estimate)
                        final double LANE_CHANGE_DURATION_BUFFER_S = 4.0;
                        double dBuffer = Math.min(vEgo * LANE_CHANGE_DURATION_BUFFER_S, distToLaneEnd.si * 0.25);
                        double dAvailable = Math.max(0.0, distToLaneEnd.si - dBuffer);

                        // SUVAT: d_required = (v_target² − v_ego²) / (2 · a_max)
                        double dRequired = (vTarget * vTarget - vEgo * vEgo) / (2.0 * aMaxCurrent);

                        if (dRequired > dAvailable)
                        {
                            downstreamGapReachable = false;
                        }
                    }
                    // else: ego is already faster than the overtake target → gap is reachable
                }
            }

            if (actualFollower == null)
            {
                // No follower: follower decel is implicitly safe.
                // Still require kinematic reachability of the downstream gap.
                if (downstreamGapReachable)
                {
                    return transitionTo(new DownstreamMergeState(this.maneuverPattern));
                }
                // Leader is kinematically unreachable: wait – upstream gap will open as leader pulls away.
                return null;
            }
            else
            {
                Acceleration followerInducedDecel = neigh.getGtuDeceleration(actualFollower);
                Acceleration followerDecelThreshold = ego.getFollowerDecelerationThreshold(this.pattern.getTargetDirection());

                if (followerInducedDecel.si > followerDecelThreshold.si && downstreamGapReachable)
                {
                    // Follower decel acceptable AND downstream gap kinematically reachable → merge ahead
                    return transitionTo(new DownstreamMergeState(this.maneuverPattern));
                }
            }
            return null; // Boundaries safe, no parallel vehicle, waiting for LaneChangePossible
        }

        @Override
        public SimpleOperationalPlan abort()
        {
            try
            {
                if (this.vehicle.getLaneChangeDesire().magnitude() < this.vehicle.getParameters()
                        .getParameter(MirovaParameters.DMAND))
                {
                    return finishManeuver();
                }
            }
            catch (ParameterException | GtuException | NetworkException exception)
            {
                exception.printStackTrace();
            }
            return null;
        }

        @Override
        public double getUtility()
        {
            return this.vehicle.getMandatoryLaneChangeDesire().magnitude();
        }

        @Override
        public String toString()
        {
            return "EvaluateTargetGapState";
        }
    }

    /*
     * ========================================================================================= STATE 2: DOWNSTREAM MERGE STATE
     * (BRAKING) =========================================================================================
     */

    /**
     * State for resolving a downstream merge conflict.
     * <p>
     * This state is triggered when the ego vehicle is too fast for the target gap (EgoDecelerationThreshold is violated). It
     * overrides the standard car-following acceleration with a hard braking maneuver until the target leader can be safely
     * followed.
     * </p>
     * <p>
     * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
     * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
     * </p>
     * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
     */
    public static class DownstreamMergeState extends ActionState
    {
        /** The parent mandatory lane change pattern. */
        private final MandatoryLaneChangePattern pattern;

        /**
         * Constructor for the downstream merge state.
         * @param p the parent maneuver pattern
         */
        public DownstreamMergeState(final ManeuverPattern p)
        {
            super(p);
            this.pattern = (MandatoryLaneChangePattern) p;
            this.active = true;
        }

        @Override
        public SimpleOperationalPlan executeControl() throws ParameterException, GtuException, NetworkException
        {
            EgoContext ego = this.vehicle.getContext(EgoContext.class);
            NeighborsContext neigh = this.vehicle.getContext(NeighborsContext.class);
            Acceleration aCf = ego.getCurrentCarFollowingAcceleration();

            // Apply hard braking, but if the car-following model demands even harder braking
            // (e.g., to avoid crashing into the ego-lane leader), we must respect that.
            Acceleration egoDecelThreshold = ego.getEgoDecelerationThreshold(this.pattern.getTargetDirection());
            Acceleration inducedDecel = Acceleration.NEGATIVE_INFINITY;
            HeadwayGtu adjacentLeader = neigh.getLeader(this.pattern.getTargetDirection());
            if (adjacentLeader != null)
            {
                Acceleration putativeLeaderAccel = MirovaCarFollowingUtil.followSingleLeader(this.vehicle, adjacentLeader);
                inducedDecel = Acceleration.max(inducedDecel, putativeLeaderAccel);
            }
            inducedDecel = Acceleration.max(inducedDecel, egoDecelThreshold);

            // (B) Distance-dependent speed cap in congested conditions: when the target lane is
            // congested (macro speed < vCong) and the ramp end is within 200 m, limit the allowed
            // acceleration so the vehicle approaches a linearly-decreasing target speed (15 → 5 km/h).
            // This prevents aggressive acceleration into an opening gap near the end of the ramp.
            try
            {
                MacroTrafficContext macro = this.vehicle.getContext(MacroTrafficContext.class);
                RelativeLane targetRelativeLane =
                        this.pattern.getTargetDirection().isLeft() ? RelativeLane.LEFT : RelativeLane.RIGHT;
                Speed macroSpeed = macro.getAverageSpeed(targetRelativeLane);
                Speed vCong = this.vehicle.getParameters().getParameter(ParameterTypes.VCONG);
                if (macroSpeed.lt(vCong) && inducedDecel.si > 0)
                {
                    InfrastructureContext infra = this.vehicle.getContext(InfrastructureContext.class);
                    Length distToLaneEnd = infra.getRouteDistanceToLaneEnd();
                    if (distToLaneEnd != null && distToLaneEnd.si < 200.0)
                    {
                        double distFraction = Math.min(1.0, distToLaneEnd.si / 200.0);
                        Speed dynamicTargetSpeed =
                                Speed.max(new Speed(5.0, SpeedUnit.KM_PER_HOUR), Speed.instantiateSI(vCong.si * distFraction));
                        Acceleration aApproach = MirovaCarFollowingUtil.approachTargetSpeed(this.vehicle,
                                Length.instantiateSI(10.0), dynamicTargetSpeed);
                        inducedDecel = Acceleration.min(inducedDecel, aApproach);
                    }
                }
            }
            catch (Exception e)
            {
                // Macro speed unavailable – keep existing inducedDecel
            }

            Acceleration finalAcc = Acceleration.min(aCf, inducedDecel);

            SimpleOperationalPlan plan = new SimpleOperationalPlan(finalAcc, this.pattern.patternSpecificTimestep);

            if (this.pattern.getTargetDirection().isLeft())
            {
                plan.setIndicatorIntentLeft();
            }
            else if (this.pattern.getTargetDirection().isRight())
            {
                plan.setIndicatorIntentRight();
            }

            return plan;
        }

        @Override
        public SimpleOperationalPlan next() throws ParameterException, OperationalPlanException, NetworkException, GtuException
        {
            NeighborsContext neigh = this.vehicle.getContext(NeighborsContext.class);
            LateralDirectionality dir = this.pattern.getTargetDirection();

            // 1. If the gap suddenly becomes perfectly clear, execute immediately
            if (neigh.getIfLaneChangePossible(dir))
            {
                return transitionTo(new ExecuteLaneChangeState(this.maneuverPattern, dir));
            }

            Length distToLaneEnd = this.vehicle.getContext(InfrastructureContext.class).getRouteDistanceToLaneEnd();
            // Notbremse, falls das Ende der Rampe unweigerlich näher rückt
            if (distToLaneEnd != null)
            {
                Acceleration requiredStopAccel =
                        MirovaCarFollowingUtil.stop(this.vehicle, distToLaneEnd.minus(RAMP_END_BUFFER));
                if (requiredStopAccel.si < -5.0)
                {
                    return transitionTo(new DecelEndOfRampState(this.maneuverPattern));
                }
            }

            // --> NEU: Übergang in den Congested Merge State bei zähfließendem Verkehr (< 15 km/h)
            Speed egoSpeed = this.vehicle.getContext(EgoContext.class).getEgoSpeed();
            if (egoSpeed.lt(new Speed(15.0, org.djunits.unit.SpeedUnit.KM_PER_HOUR)))
            {
                return transitionTo(new CongestedMergeState(this.maneuverPattern));
            }

            HeadwayGtu parallel = null;
            HeadwayGtu leader = neigh.getLeader(dir);
            EgoContext ego = this.vehicle.getContext(EgoContext.class);
            Length safeDistance = ego.getDesiredFrontHeadway(dir);
            Double safetyReductionFactor =
                    this.vehicle.getParameters().getParameter(MirovaParameters.safetyDistanceReductionFactorLaneChange);

            // Continuous kinematic re-evaluation: if the target leader has pulled away and the
            // downstream gap is no longer reachable within the remaining ramp distance, exit
            // to EvaluateTargetGapState so the vehicle can wait for an upstream gap instead.
            if (leader != null && !leader.isParallel() && leader.getDistance().si >= 0.0 && distToLaneEnd != null)
            {
                double vEgo = egoSpeed.si;
                double vLeader = leader.getSpeed().si;
                Speed desiredSpeedResult = ego.getCurrentDesiredSpeed();
                double vWunsch = (desiredSpeedResult != null && !Double.isNaN(desiredSpeedResult.si)) ? desiredSpeedResult.si
                        : this.vehicle.getContext(InfrastructureContext.class).getLegalSpeedLimit().si;
                // To merge AHEAD of the leader, the ego must go faster than the leader.
                // Add an overtake margin; evaluate acceleration at this higher (more limiting) speed.
                final double OVERTAKE_MARGIN_MS = 3.0; // ~11 km/h above leader speed
                double vTarget = vLeader + OVERTAKE_MARGIN_MS;

                // Acceleration at the overtake target speed – the most limiting (highest) speed
                // the ego must reach to successfully execute the downstream merge.
                double aMaxCurrent = Math.max(ego.getMaxPhysicalAccelerationAt(Speed.instantiateSI(vTarget)).si, 0.01);

                boolean reachable = true;
                if (vTarget > vWunsch)
                {
                    // Overtake speed exceeds desired speed → leader kinematically unreachable
                    reachable = false;
                }
                else if (vTarget > vEgo)
                {
                    final double LANE_CHANGE_DURATION_BUFFER_S = 4.0;
                    double dBuffer = Math.min(vEgo * LANE_CHANGE_DURATION_BUFFER_S, distToLaneEnd.si * 0.25);
                    double dAvailable = Math.max(0.0, distToLaneEnd.si - dBuffer);
                    double dRequired = (vTarget * vTarget - vEgo * vEgo) / (2.0 * aMaxCurrent);
                    if (dRequired > dAvailable)
                    {
                        reachable = false;
                    }
                }
                // else: already faster than overtake target → reachable

                if (!reachable)
                {
                    // Leader is no longer reachable – abandon downstream merge, wait for upstream gap
                    return transitionTo(new EvaluateTargetGapState(this.maneuverPattern));
                }
            }

            if (leader != null && (leader.isParallel() || (leader.getDistance().si < safeDistance.si * safetyReductionFactor
                    && Math.abs(leader.getSpeed().si - ego.getEgoSpeed().si) < 1.0)))
            {
                parallel = leader;
            }
            else
            {
                HeadwayGtu follower = neigh.getFollower(dir);
                if (follower != null
                        && (follower.isParallel() || (follower.getDistance().si < safeDistance.si * safetyReductionFactor
                                && Math.abs(follower.getSpeed().si - ego.getEgoSpeed().si) < 1.0)))
                {
                    parallel = follower;
                }
            }

            // --> NEU: Wenn ein paralleles Fahrzeug existiert, in den neuen State wechseln
            if (parallel != null)
            {
                return transitionTo(new SolveParallelVehicleState(this.maneuverPattern));
            }

            return null; // Keep braking
        }

        @Override
        public SimpleOperationalPlan abort()
        {
            try
            {
                if (this.vehicle.getLaneChangeDesire().magnitude() < this.vehicle.getParameters()
                        .getParameter(MirovaParameters.DMAND))
                {
                    return finishManeuver();
                }
            }
            catch (ParameterException | GtuException | NetworkException exception)
            {
                exception.printStackTrace();
            }
            return null;
        }

        @Override
        public double getUtility()
        {
            return this.vehicle.getMandatoryLaneChangeDesire().magnitude();
        }

        @Override
        public String toString()
        {
            return "DownstreamMergeState";
        }
    }

    /*
     * ========================================================================================= STATE: SOLVE PARALLEL VEHICLE
     * =========================================================================================
     */

    /**
     * State to resolve conflicts with a parallel vehicle on the target lane.
     * <p>
     * If a vehicle is driving parallel on the target lane, the ego vehicle typically decelerates slightly (-1.0 m/s&sup2;) to
     * let the parallel vehicle pass. However, if there is sufficient distance to the end of the ramp and the car-following
     * model allows for strong acceleration (&gt; 1.0 m/s&sup2;), the ego vehicle will accelerate maximally to merge ahead of
     * the parallel vehicle.
     * </p>
     * <p>
     * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
     * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
     * </p>
     * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
     */
    public static class SolveParallelVehicleState extends ActionState
    {
        /** The parent mandatory lane change pattern. */
        private final MandatoryLaneChangePattern pattern;

        /** Threshold for sufficient distance to lane end to attempt accelerating ahead [m]. */
        private static final double SUFFICIENT_DISTANCE_THRESHOLD = 200.0;

        private HeadwayGtu parallelVehicle = null;

        /**
         * Constructor for the solve parallel vehicle state.
         * @param p the parent maneuver pattern
         */
        public SolveParallelVehicleState(final ManeuverPattern p)
        {
            super(p);
            this.pattern = (MandatoryLaneChangePattern) p;
            this.active = true;
        }

        @Override
        public SimpleOperationalPlan executeControl() throws ParameterException, GtuException, NetworkException
        {
            EgoContext ego = this.vehicle.getContext(EgoContext.class);
            InfrastructureContext infra = this.vehicle.getContext(InfrastructureContext.class);

            Acceleration aCf = ego.getCurrentCarFollowingAcceleration();
            Length distToLaneEnd = infra.getRouteDistanceToLaneEnd();

            Acceleration targetAcc = aCf; // Default to car-following acceleration if no parallel vehicle or no room to maneuver

            if (distToLaneEnd != null)
            {
                // Strategy: Check if we have enough room and momentum to overtake the parallel vehicle
                if (distToLaneEnd != null && distToLaneEnd.si > SUFFICIENT_DISTANCE_THRESHOLD && aCf.si > 1.0
                        && !parallelVehicle.isAhead())
                {
                    // Accelerate maximally to merge ahead
                    targetAcc = ego.getMaxPhysicalAcceleration();
                }
                else
                {
                    // Default strategy: Decelerate slightly to drop behind the parallel vehicle.
                    // We use Acceleration.min() with the Car-Following acceleration to ensure
                    // we don't crash into a leader on our CURRENT lane while braking.
                    Acceleration aStop = MirovaCarFollowingUtil.stop(this.vehicle, distToLaneEnd.minus(RAMP_END_BUFFER));
                    if (distToLaneEnd.si < 100.0)
                    {
                        // If we are very close to the end, be more conservative with braking to avoid unnecessary hard stops
                        aStop = Acceleration.min(aStop, ego.getEgoDecelerationThreshold(this.pattern.getTargetDirection()));
                    }
                    else
                    {
                        // If we have more room, we can afford a stronger deceleration to ensure we drop back in time
                        aStop = Acceleration.min(aStop, Acceleration.instantiateSI(-1.0));
                    }
                    targetAcc = Acceleration.min(aCf, aStop);
                }
            }
            SimpleOperationalPlan plan = new SimpleOperationalPlan(targetAcc, this.pattern.patternSpecificTimestep);

            // Keep the blinkers running
            if (this.pattern.getTargetDirection().isLeft())
            {
                plan.setIndicatorIntentLeft();
            }
            else if (this.pattern.getTargetDirection().isRight())
            {
                plan.setIndicatorIntentRight();
            }

            return plan;
        }

        @Override
        public SimpleOperationalPlan next() throws ParameterException, OperationalPlanException, NetworkException, GtuException
        {
            NeighborsContext neigh = this.vehicle.getContext(NeighborsContext.class);
            LateralDirectionality dir = this.pattern.getTargetDirection();

            // 1. Physical execution check: If the gap becomes perfectly clear, execute immediately
            if (neigh.getIfLaneChangePossible(dir))
            {
                return transitionTo(new ExecuteLaneChangeState(this.maneuverPattern, dir));
            }

            // 2. Emergency brake check: If the end of the ramp gets critically close
            Length distToLaneEnd = this.vehicle.getContext(InfrastructureContext.class).getRouteDistanceToLaneEnd();
            if (distToLaneEnd != null)
            {
                Acceleration requiredStopAccel =
                        MirovaCarFollowingUtil.stop(this.vehicle, distToLaneEnd.minus(RAMP_END_BUFFER));
                if (requiredStopAccel.si < -5.0)
                {
                    return transitionTo(new DecelEndOfRampState(this.maneuverPattern));
                }
            }

            // --> NEU: Übergang in den Congested Merge State bei zähfließendem Verkehr (< 15 km/h)
            Speed egoSpeed = this.vehicle.getContext(EgoContext.class).getEgoSpeed();
            if (egoSpeed.lt(new Speed(15.0, org.djunits.unit.SpeedUnit.KM_PER_HOUR)))
            {
                return transitionTo(new CongestedMergeState(this.maneuverPattern));
            }

            // 3. Check if the parallel vehicle is still blocking us
            boolean hasParallel = false;
            HeadwayGtu putativeLeader = neigh.getLeader(dir);
            Length safeDistance = this.vehicle.getContext(EgoContext.class).getDesiredFrontHeadway(dir);
            Double safetyReductionFactor =
                    this.vehicle.getParameters().getParameter(MirovaParameters.safetyDistanceReductionFactorLaneChange);
            if (putativeLeader != null && (putativeLeader.isParallel()
                    || putativeLeader.getDistance().si < safeDistance.si * safetyReductionFactor))
            {
                hasParallel = true;
                parallelVehicle = putativeLeader;
            }
            else
            {
                HeadwayGtu putativeFollower = neigh.getFollower(dir);
                if (putativeFollower != null && (putativeFollower.isParallel()
                        || putativeFollower.getDistance().si < safeDistance.si * safetyReductionFactor))
                {
                    hasParallel = true;
                    parallelVehicle = putativeFollower;
                }
            }

            // 4. If the parallel vehicle is gone (passed us or we passed it), transition appropriately
            if (!hasParallel)
            {
                HeadwayGtu targetLeader = neigh.getLeader(dir);
                if (targetLeader != null && targetLeader.getDistance().si > 0.0)
                {
                    // The vehicle is now ahead of us. Transition to DownstreamMergeState to follow it.
                    return transitionTo(new DownstreamMergeState(this.maneuverPattern));
                }
                return transitionTo(new EvaluateTargetGapState(this.maneuverPattern));
            }

            return null; // Stay in this state and continue resolving the conflict
        }

        @Override
        public SimpleOperationalPlan abort()
        {
            try
            {
                if (this.vehicle.getLaneChangeDesire().magnitude() < this.vehicle.getParameters()
                        .getParameter(MirovaParameters.DMAND))
                {
                    return finishManeuver();
                }
            }
            catch (ParameterException | GtuException | NetworkException exception)
            {
                exception.printStackTrace();
            }
            return null;
        }

        @Override
        public double getUtility()
        {
            return this.vehicle.getMandatoryLaneChangeDesire().magnitude();
        }

        @Override
        public String toString()
        {
            return "SolveParallelVehicleState";
        }
    }

    /*
     * ========================================================================================= STATE: CONGESTED MERGE
     * =========================================================================================
     */

    /**
     * Routing-only entry state for congested merge situations (ego speed &lt; 15 km/h).
     * <p>
     * This state does not implement longitudinal control itself; it acts as a pure dispatcher that evaluates the current
     * traffic situation every tick and transitions immediately to the appropriate sub-state:
     * <ul>
     * <li>{@code CongestedCreepState} – when a parallel-blocking vehicle is detected on the target lane</li>
     * <li>{@code CongestedFollowLeaderState} – when the target gap is open but no lane-change is yet possible</li>
     * </ul>
     * It also handles the shared escape conditions (lane-change possible, end-of-ramp emergency, speed recovery).
     * </p>
     * <p>
     * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
     * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
     * </p>
     * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
     */
    public static class CongestedMergeState extends ActionState
    {
        /** The parent mandatory lane change pattern. */
        private final MandatoryLaneChangePattern pattern;

        /** Speed threshold above which the vehicle returns to normal gap evaluation. */
        static final Speed RECOVERY_SPEED_THRESHOLD = new Speed(30.0, org.djunits.unit.SpeedUnit.KM_PER_HOUR);

        /**
         * Constructor for the congested merge state.
         * @param p the parent maneuver pattern
         */
        public CongestedMergeState(final ManeuverPattern p)
        {
            super(p);
            this.pattern = (MandatoryLaneChangePattern) p;
            this.active = true;
        }

        @Override
        public SimpleOperationalPlan executeControl() throws ParameterException, GtuException, NetworkException
        {
            // Pure routing state: return neutral car-following acceleration for this tick.
            // next() will dispatch to the appropriate sub-state on the same or next tick.
            Acceleration aCf = this.vehicle.getContext(EgoContext.class).getCurrentCarFollowingAcceleration();
            SimpleOperationalPlan plan = new SimpleOperationalPlan(aCf, this.pattern.patternSpecificTimestep);
            if (this.pattern.getTargetDirection().isLeft())
            {
                plan.setIndicatorIntentLeft();
            }
            else if (this.pattern.getTargetDirection().isRight())
            {
                plan.setIndicatorIntentRight();
            }
            return plan;
        }

        @Override
        public SimpleOperationalPlan next() throws ParameterException, OperationalPlanException, NetworkException, GtuException
        {
            NeighborsContext neigh = this.vehicle.getContext(NeighborsContext.class);
            LateralDirectionality dir = this.pattern.getTargetDirection();

            // 1. Lane-change physically possible → execute immediately
            if (neigh.getIfLaneChangePossible(dir))
            {
                return transitionTo(new ExecuteLaneChangeState(this.maneuverPattern, dir));
            }

            // 2. Emergency brake: end of ramp critically close
            Length distToLaneEnd = this.vehicle.getContext(InfrastructureContext.class).getRouteDistanceToLaneEnd();
            if (distToLaneEnd != null)
            {
                Acceleration requiredStopAccel =
                        MirovaCarFollowingUtil.stop(this.vehicle, distToLaneEnd.minus(RAMP_END_BUFFER));
                if (requiredStopAccel.si < -5.0)
                {
                    return transitionTo(new DecelEndOfRampState(this.maneuverPattern));
                }
            }

            // 3. Speed recovered: return to normal gap evaluation
            Speed egoSpeed = this.vehicle.getContext(EgoContext.class).getEgoSpeed();
            if (egoSpeed.gt(RECOVERY_SPEED_THRESHOLD))
            {
                return transitionTo(new EvaluateTargetGapState(this.maneuverPattern));
            }

            // 4. Parallel block present → creep alongside
            if (detectParallelBlock(neigh, dir, this.vehicle.getContext(EgoContext.class), this.vehicle.getParameters()))
            {
                return transitionTo(new CongestedCreepState(this.maneuverPattern));
            }

            // 5. No parallel block → follow the putative leader at reduced target speed
            return transitionTo(new CongestedFollowLeaderState(this.maneuverPattern));
        }

        @Override
        public SimpleOperationalPlan abort()
        {
            try
            {
                if (this.vehicle.getLaneChangeDesire().magnitude() < this.vehicle.getParameters()
                        .getParameter(MirovaParameters.DMAND))
                {
                    return finishManeuver();
                }
            }
            catch (ParameterException | GtuException | NetworkException exception)
            {
                exception.printStackTrace();
            }
            return null;
        }

        @Override
        public double getUtility()
        {
            return this.vehicle.getMandatoryLaneChangeDesire().magnitude();
        }

        @Override
        public String toString()
        {
            return "CongestedMergeState";
        }
    }

    /*
     * ========================================================================================= STATE: CONGESTED CREEP
     * =========================================================================================
     */

    /**
     * Sub-state of the congested merge scenario: a parallel-blocking vehicle is present on the target lane.
     * <p>
     * The ego vehicle creeps gently toward 3 km/h (max acceleration 0.3 m/s&sup2;) while the parallel vehicle is blocking the
     * gap. This keeps the ego positioned just behind the blocker without accelerating alongside it. As soon as the parallel
     * block is resolved, control returns to {@code CongestedMergeState} for re-dispatch.
     * </p>
     * <p>
     * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
     * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
     * </p>
     * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
     */
    public static class CongestedCreepState extends ActionState
    {
        /** The parent mandatory lane change pattern. */
        private final MandatoryLaneChangePattern pattern;

        /**
         * Constructor for the congested creep state.
         * @param p the parent maneuver pattern
         */
        public CongestedCreepState(final ManeuverPattern p)
        {
            super(p);
            this.pattern = (MandatoryLaneChangePattern) p;
            this.active = true;
        }

        @Override
        public SimpleOperationalPlan executeControl() throws ParameterException, GtuException, NetworkException
        {
            EgoContext ego = this.vehicle.getContext(EgoContext.class);
            Acceleration aCf = ego.getCurrentCarFollowingAcceleration();

            // Creep gently toward 3 km/h – do NOT match the parallel vehicle's full acceleration.
            // This keeps the ego positioned for the gap without driving alongside the blocker.
            Acceleration aCreep = MirovaCarFollowingUtil.approachTargetSpeed(this.vehicle, Length.instantiateSI(5.0),
                    new Speed(3.0, SpeedUnit.KM_PER_HOUR));
            aCreep = Acceleration.min(aCreep, Acceleration.instantiateSI(0.3));

            // Hard floor: never worse than own-lane car-following.
            Acceleration finalAcc = Acceleration.min(aCf, aCreep);
            SimpleOperationalPlan plan = new SimpleOperationalPlan(finalAcc, this.pattern.patternSpecificTimestep);
            if (this.pattern.getTargetDirection().isLeft())
            {
                plan.setIndicatorIntentLeft();
            }
            else if (this.pattern.getTargetDirection().isRight())
            {
                plan.setIndicatorIntentRight();
            }
            return plan;
        }

        @Override
        public SimpleOperationalPlan next() throws ParameterException, OperationalPlanException, NetworkException, GtuException
        {
            NeighborsContext neigh = this.vehicle.getContext(NeighborsContext.class);
            LateralDirectionality dir = this.pattern.getTargetDirection();

            // 1. Lane-change physically possible → execute immediately
            if (neigh.getIfLaneChangePossible(dir))
            {
                return transitionTo(new ExecuteLaneChangeState(this.maneuverPattern, dir));
            }

            // 2. Emergency brake: end of ramp critically close
            Length distToLaneEnd = this.vehicle.getContext(InfrastructureContext.class).getRouteDistanceToLaneEnd();
            if (distToLaneEnd != null)
            {
                Acceleration requiredStopAccel =
                        MirovaCarFollowingUtil.stop(this.vehicle, distToLaneEnd.minus(RAMP_END_BUFFER));
                if (requiredStopAccel.si < -5.0)
                {
                    return transitionTo(new DecelEndOfRampState(this.maneuverPattern));
                }
            }

            // 3. Parallel block resolved → return to dispatcher
            if (!detectParallelBlock(neigh, dir, this.vehicle.getContext(EgoContext.class), this.vehicle.getParameters()))
            {
                return transitionTo(new CongestedMergeState(this.maneuverPattern));
            }

            return null; // Stay: parallel vehicle still blocking
        }

        @Override
        public SimpleOperationalPlan abort()
        {
            try
            {
                if (this.vehicle.getLaneChangeDesire().magnitude() < this.vehicle.getParameters()
                        .getParameter(MirovaParameters.DMAND))
                {
                    return finishManeuver();
                }
            }
            catch (ParameterException | GtuException | NetworkException exception)
            {
                exception.printStackTrace();
            }
            return null;
        }

        @Override
        public double getUtility()
        {
            return this.vehicle.getMandatoryLaneChangeDesire().magnitude();
        }

        @Override
        public String toString()
        {
            return "CongestedCreepState";
        }
    }

    /*
     * ========================================================================================= STATE: CONGESTED FOLLOW LEADER
     * =========================================================================================
     */

    /**
     * Sub-state of the congested merge scenario: no parallel block, but the target gap is not yet clear.
     * <p>
     * The ego vehicle follows the putative leader on the target lane using a distance-dependent target speed that scales from
     * the congestion threshold (15 km/h) down to 5 km/h as the ramp end approaches within a 200 m reference window. The
     * resulting acceleration is bounded by the own-lane car-following acceleration to prevent rear-end collisions.
     * </p>
     * <p>
     * If a parallel block appears during this state, control returns to {@code CongestedMergeState} for re-dispatch to
     * {@code CongestedCreepState}. When the ego speed recovers above the recovery threshold, the state machine exits the
     * congested branch and returns to {@code EvaluateTargetGapState}.
     * </p>
     * <p>
     * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
     * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
     * </p>
     * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
     */
    public static class CongestedFollowLeaderState extends ActionState
    {
        /** The parent mandatory lane change pattern. */
        private final MandatoryLaneChangePattern pattern;

        /** Speed threshold below which the vehicle remains in the congested branch. */
        private static final Speed CONGESTION_SPEED_THRESHOLD = new Speed(15.0, org.djunits.unit.SpeedUnit.KM_PER_HOUR);

        /**
         * Constructor for the congested follow-leader state.
         * @param p the parent maneuver pattern
         */
        public CongestedFollowLeaderState(final ManeuverPattern p)
        {
            super(p);
            this.pattern = (MandatoryLaneChangePattern) p;
            this.active = true;
        }

        @Override
        public SimpleOperationalPlan executeControl() throws ParameterException, GtuException, NetworkException
        {
            EgoContext ego = this.vehicle.getContext(EgoContext.class);
            NeighborsContext neigh = this.vehicle.getContext(NeighborsContext.class);
            InfrastructureContext infra = this.vehicle.getContext(InfrastructureContext.class);
            LateralDirectionality dir = this.pattern.getTargetDirection();

            // 1. Own-lane car-following (safety floor)
            Acceleration aCf = ego.getCurrentCarFollowingAcceleration();

            // 2. Distance-dependent target speed: scale from 15 km/h down to 5 km/h
            // within a 200 m reference window before the ramp end.
            Length distToLaneEnd = infra.getRouteDistanceToLaneEnd();
            double distSI = distToLaneEnd != null ? Math.max(0.0, distToLaneEnd.si) : 200.0;
            double distFraction = Math.min(1.0, distSI / 200.0);
            Speed dynamicTargetSpeed = Speed.max(new Speed(5.0, SpeedUnit.KM_PER_HOUR),
                    Speed.instantiateSI(CONGESTION_SPEED_THRESHOLD.si * distFraction));

            // 3. Approach the dynamic target speed
            Acceleration aApproach =
                    MirovaCarFollowingUtil.approachTargetSpeed(this.vehicle, Length.instantiateSI(10.0), dynamicTargetSpeed);
            aApproach = Acceleration.min(aApproach, ego.getMaxPhysicalAcceleration());

            // 4. Follow the putative leader on the target lane if one exists
            HeadwayGtu putativeLeader = neigh.getLeader(dir);
            if (putativeLeader != null)
            {
                aApproach =
                        Acceleration.max(aApproach, MirovaCarFollowingUtil.followSingleLeader(this.vehicle, putativeLeader));
            }

            // 5. Hard floor: never worse than own-lane car-following.
            Acceleration finalAcc = Acceleration.min(aCf, aApproach);
            SimpleOperationalPlan plan = new SimpleOperationalPlan(finalAcc, this.pattern.patternSpecificTimestep);
            if (dir.isLeft())
            {
                plan.setIndicatorIntentLeft();
            }
            else if (dir.isRight())
            {
                plan.setIndicatorIntentRight();
            }
            return plan;
        }

        @Override
        public SimpleOperationalPlan next() throws ParameterException, OperationalPlanException, NetworkException, GtuException
        {
            NeighborsContext neigh = this.vehicle.getContext(NeighborsContext.class);
            LateralDirectionality dir = this.pattern.getTargetDirection();

            // 1. Lane-change physically possible → execute immediately
            if (neigh.getIfLaneChangePossible(dir))
            {
                return transitionTo(new ExecuteLaneChangeState(this.maneuverPattern, dir));
            }

            // 2. Emergency brake: end of ramp critically close
            Length distToLaneEnd = this.vehicle.getContext(InfrastructureContext.class).getRouteDistanceToLaneEnd();
            if (distToLaneEnd != null)
            {
                Acceleration requiredStopAccel =
                        MirovaCarFollowingUtil.stop(this.vehicle, distToLaneEnd.minus(RAMP_END_BUFFER));
                if (requiredStopAccel.si < -5.0)
                {
                    return transitionTo(new DecelEndOfRampState(this.maneuverPattern));
                }
            }

            // 3. Speed recovered → exit congested branch
            Speed egoSpeed = this.vehicle.getContext(EgoContext.class).getEgoSpeed();
            if (egoSpeed.gt(CongestedMergeState.RECOVERY_SPEED_THRESHOLD))
            {
                return transitionTo(new EvaluateTargetGapState(this.maneuverPattern));
            }

            // 4. Parallel block appeared → back to dispatcher (will route to CongestedCreepState)
            if (detectParallelBlock(neigh, dir, this.vehicle.getContext(EgoContext.class), this.vehicle.getParameters()))
            {
                return transitionTo(new CongestedMergeState(this.maneuverPattern));
            }

            return null; // Stay: still congested, no parallel block
        }

        @Override
        public SimpleOperationalPlan abort()
        {
            try
            {
                if (this.vehicle.getLaneChangeDesire().magnitude() < this.vehicle.getParameters()
                        .getParameter(MirovaParameters.DMAND))
                {
                    return finishManeuver();
                }
            }
            catch (ParameterException | GtuException | NetworkException exception)
            {
                exception.printStackTrace();
            }
            return null;
        }

        @Override
        public double getUtility()
        {
            return this.vehicle.getMandatoryLaneChangeDesire().magnitude();
        }

        @Override
        public String toString()
        {
            return "CongestedFollowLeaderState";
        }
    }

    /*
     * ========================================================================================= 5) STATE: BREAKING_END_OF_RAMP
     * =========================================================================================
     */

    /**
     * Emergency state to prevent driving off the end of the lane if no gap was found.
     */
    public static class DecelEndOfRampState extends ActionState
    {
        private final MandatoryLaneChangePattern pattern;

        /**
         * Constructor.
         * @param p the parent maneuver pattern
         */
        public DecelEndOfRampState(final ManeuverPattern p)
        {
            super(p);
            this.pattern = (MandatoryLaneChangePattern) p;
            this.active = true;
        }

        @Override
        public SimpleOperationalPlan executeControl() throws ParameterException, GtuException, NetworkException
        {
            EgoContext ego = this.vehicle.getContext(EgoContext.class);
            InfrastructureContext infra = this.vehicle.getContext(InfrastructureContext.class);
            NeighborsContext neigh = this.vehicle.getContext(NeighborsContext.class);

            // Stop before ramp end
            Length distToLaneEnd = infra.getRouteDistanceToLaneEnd();
            Acceleration aStop = distToLaneEnd != null
                    ? MirovaCarFollowingUtil.stop(this.vehicle, Length.max(distToLaneEnd.minus(RAMP_END_BUFFER), Length.ZERO))
                    : ego.getCurrentCarFollowingAcceleration();

            // Safety: don't rear-end current-lane leader
            Acceleration aCf = ego.getCurrentCarFollowingAcceleration();

            // Adapt to target-lane leader; floor at comfort deceleration threshold
            HeadwayGtu putativeLeader = neigh.getLeader(this.pattern.getTargetDirection());
            Acceleration aLeader = putativeLeader != null
                    ? Acceleration.max(MirovaCarFollowingUtil.followSingleLeader(this.vehicle, putativeLeader),
                            this.vehicle.getParameters().getParameter(MirovaParameters.egoDecelerationThreshold))
                    : Acceleration.POSITIVE_INFINITY;

            Acceleration finalAcc = Acceleration.min(aStop, Acceleration.min(aCf, aLeader));

            SimpleOperationalPlan plan = new SimpleOperationalPlan(finalAcc, this.pattern.patternSpecificTimestep);
            if (this.pattern.getTargetDirection().isLeft())
                plan.setIndicatorIntentLeft();
            else if (this.pattern.getTargetDirection().isRight())
                plan.setIndicatorIntentRight();

            return plan;
        }

        @Override
        public SimpleOperationalPlan next() throws ParameterException, OperationalPlanException, NetworkException, GtuException
        {
            NeighborsContext neigh = this.vehicle.getContext(NeighborsContext.class);
            if (neigh.getIfLaneChangePossible(this.pattern.getTargetDirection()))
            {
                return transitionTo(new ExecuteLaneChangeState(this.maneuverPattern, this.pattern.getTargetDirection()));
            }
            return null;
        }

        @Override
        public SimpleOperationalPlan abort()
        {
            try
            {
                if (this.vehicle.getLaneChangeDesire().magnitude() < this.vehicle.getParameters()
                        .getParameter(MirovaParameters.DMAND))
                {
                    return finishManeuver();
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
            return null;
        }

        @Override
        public double getUtility()
        {
            return this.vehicle.getMandatoryLaneChangeDesire().magnitude();
        }

        @Override
        public String toString()
        {
            return "DecelEndOfRampState";
        }
    }

    /*
     * ========================================================================================= 6) STATE: EXECUTE_LANE_CHANGE
     * =========================================================================================
     */

    /**
     * Final state where the actual lateral move is executed.
     */
    public static class ExecuteLaneChangeState extends ActionState
    {
        private final LateralDirectionality direction;

        private final Lane originLane;

        private final MandatoryLaneChangePattern pattern;

        private boolean slowLaneChange = false;

        /**
         * Constructor.
         * @param p parent pattern
         * @param direction lateral direction
         * @throws ParameterException if parameter missing
         */
        public ExecuteLaneChangeState(final ManeuverPattern p, final LateralDirectionality direction) throws ParameterException
        {
            super(p);
            this.direction = direction;
            this.pattern = (MandatoryLaneChangePattern) p;
            this.originLane = this.vehicle.getGtu().getLane();

            // if (this.vehicle.getContext(EgoContext.class).getEgoSpeed().si < 7.0)
            // {
            // this.slowLaneChange = true;
            // this.vehicle.getParameters().setParameterResettable(ParameterTypes.LCDUR,
            // this.vehicle.getParameters().getParameter(MirovaParameters.congestedLaneChangeDuration));
            // }
        }

        @Override
        public SimpleOperationalPlan executeControl() throws ParameterException, GtuException, NetworkException
        {
            this.vehicle.commitToAction(this);
            NeighborsContext neighborsCtx = this.vehicle.getContext(NeighborsContext.class);
            EgoContext egoCtx = this.vehicle.getContext(EgoContext.class);

            HeadwayGtu targetLeader = neighborsCtx.getLeader(LateralDirectionality.NONE);
            if (targetLeader != null && !this.vehicle.getLaneChange().isChangingLane())
            {
                egoCtx.triggerRelaxationWithReducedSafetyDistance(targetLeader);
            }

            Acceleration minAcc = egoCtx.getCurrentCarFollowingAcceleration();

            Iterable<HeadwayGtu> leaders = neighborsCtx.getLeaders(this.direction);
            for (HeadwayGtu leader : leaders)
            {
                if (!this.vehicle.getLaneChange().isChangingLane())
                {
                    egoCtx.triggerRelaxationWithReducedSafetyDistance(leader);
                }
                Acceleration aTarget = MirovaCarFollowingUtil.followSingleLeader(this.vehicle, leader);
                minAcc = Acceleration.min(minAcc, aTarget);
            }

            SimpleOperationalPlan plan =
                    new SimpleOperationalPlan(minAcc, this.pattern.patternSpecificTimestep, this.direction);

            if (this.direction == LateralDirectionality.LEFT)
            {
                plan.setIndicatorIntentLeft();
            }
            else if (this.direction == LateralDirectionality.RIGHT)
            {
                plan.setIndicatorIntentRight();
            }

            return plan;
        }

        @Override
        public SimpleOperationalPlan next()
                throws ParameterException, NullPointerException, IllegalArgumentException, GtuException, NetworkException
        {
            boolean finished =
                    !this.vehicle.getLaneChange().isChangingLane() && !this.originLane.equals(this.vehicle.getGtu().getLane());

            if (finished)
            {
                // if (this.slowLaneChange)
                // {
                // this.vehicle.getParameters().resetParameter(ParameterTypes.LCDUR);
                // }
                this.vehicle.releaseActionLock();
                return finishManeuver();
            }
            return null;
        }

        @Override
        public SimpleOperationalPlan abort() throws ParameterException, OperationalPlanException
        {
            if (this.vehicle.getLaneChange().isChangingLane())
            {
                return null;
            }

            try
            {
                if (this.vehicle.getLaneChangeDesire().magnitude() < this.vehicle.getParameters()
                        .getParameter(MirovaParameters.DMAND))
                {
                    // if (this.slowLaneChange)
                    // {
                    // this.vehicle.getParameters().resetParameter(ParameterTypes.LCDUR);
                    // }

                    this.vehicle.releaseActionLock(); // HIER EINFÜGEN
                    return finishManeuver();
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
            return null;
        }

        @Override
        public double getUtility()
        {
            return this.vehicle.getMandatoryLaneChangeDesire().magnitude();
        }

        @Override
        public String toString()
        {
            return "ExecuteLaneChange[" + this.direction + "]";
        }
    }
}
