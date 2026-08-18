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
 * This pattern implements a state-machine based lane change process, transitioning from early speed anticipation 
 * to active gap evaluation, conflict resolution, and execution.
 * </p>
 * 
 * <h3>Functional State Machine Flow:</h3>
 * <ol>
 *   <li><b>AnticipateMergeState</b> (Initial Phase): Looks far ahead (up to extendedLookAheadDistance) to estimate average 
 *       downstream speed on the target lane. The speed signal is smoothed using an Exponential Moving Average (EMA) filter. 
 *       The vehicle adjusts its speed smoothly without actively looking for gaps or forcing a lane change. When the target 
 *       lane is within reach, it transitions to <i>EvaluateTargetGapState</i>.</li>
 *   <li><b>EvaluateTargetGapState</b> (Gap Evaluation Phase): Searches for suitable gap candidates on the adjacent lane. 
 *       It evaluates required safety decelerations and coordinates parallel blocks. 
 *       <ul>
 *         <li><b>Slower Target Lane (Exit/Decel Scenario):</b> When changing to a slower lane, the target lane leader speed 
 *             is lower than the ego speed. The safety deceleration threshold is typically violated. The vehicle transitions to 
 *             <i>MatchLeaderSpeedState</i> to brake and drop behind the slower leader.</li>
 *         <li><b>Faster Target Lane (Merge/Accel Scenario):</b> The vehicle accelerates towards target lane speed. If a parallel 
 *             block is detected, it transitions to <i>SolveParallelVehicleState</i> to handle overtaking.</li>
 *       </ul>
 *       If speed drops below 15 km/h, it transitions to <i>CongestedMergeState</i>. If a gap is physically open, 
 *       it transitions to <i>ExecuteLaneChangeState</i>.</li>
 *   <li><b>MatchLeaderSpeedState</b> (Active Braking Phase): Entered when the ego vehicle is too fast/close to the target leader. 
 *       It actively decelerates the ego vehicle to safely match the target leader's speed and fall behind it. 
 *       It kinematically checks if the gap is still reachable within the remaining ramp end distance; if not, it transitions back 
 *       to <i>EvaluateTargetGapState</i>. If a parallel blocker appears, it transitions to <i>SolveParallelVehicleState</i>.</li>
 *   <li><b>SolveParallelVehicleState</b> (Parallel Conflict Resolution): Resolves situations where a vehicle is driving 
 *       parallel on the adjacent lane. If there is enough remaining ramp distance (>200m) and own lane headway, it accelerates 
 *       maximally to overtake and merge ahead (Overtake Strategy). Otherwise, it decelerates to drop behind the blocker.</li>
 *   <li><b>CongestedMergeState</b> (Congested Flow Dispatcher): Activated under congested conditions (speed < 15 km/h). 
 *       Acts as a pure routing dispatcher, transitioning to <i>CongestedCreepState</i> when a parallel vehicle is blocking, 
 *       or <i>CongestedFollowLeaderState</i> when the target lane leader is clear but the lane change is not yet physically possible. 
 *       If speed recovers above 30 km/h, it transitions back to <i>EvaluateTargetGapState</i>.</li>
 *   <li><b>CongestedCreepState</b> (Congested Parallel Blocking): Creeps forward at a very low speed (3 km/h, max 0.3 m/s²) 
 *       without accelerating alongside the blocking vehicle. It returns to <i>CongestedMergeState</i> when the block is resolved.</li>
 *   <li><b>CongestedFollowLeaderState</b> (Congested Target Following): Follows the leader in the target lane at a speed 
 *       scaling down from 15 km/h to 5 km/h as the end of the ramp approaches. If a parallel block appears, it transitions back 
 *       to <i>CongestedMergeState</i>.</li>
 *   <li><b>EmergencyStopState</b> (Emergency Stop & Last-Minute Overtake): Triggered when approaching the end of the lane 
 *       without finding a gap (e.g. at the end of a merge ramp or when approaching a highway exit on the main road). 
 *       It stops the vehicle before the lane end buffer. While decelerating, it continuously checks if a last-minute 
 *       overtake and lane change is safely possible.</li>
 *   <li><b>ExecuteLaneChangeState</b> (Lateral Execution): Performs the physical lateral movement, temporarily triggering 
 *       safety distance relaxation (cooperative gap creation) for adjacent leaders and followers.</li>
 * </ol>
 * 
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
            boolean isDesireHigh = this.vehicle.getLaneChangeDesire().magnitude() >= this.vehicle.getParameters()
                    .getParameter(MirovaParameters.DMAND);
            if (isDesireHigh)
            {
                return true;
            }

            InfrastructureContext infra = this.vehicle.getContext(InfrastructureContext.class);
            Length distToMerge = infra.getDistanceToLaneChangeExtendedLookahead();

            // Trigger if within 1000m
            boolean isApproachingMerge = distToMerge.si > 0 && distToMerge.si < this.vehicle.getParameters()
                    .getParameter(MirovaParameters.extendedLookAheadDistance).si;

            return isApproachingMerge;
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
     * used in {@code MatchLeaderSpeedState}.
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
     * ========================================================================================= BASE STATE CLASS
     * =========================================================================================
     */

    /**
     * Base class for action states within MandatoryLaneChangePattern.
     * Reduces code duplication of utility, abort checks, indicators, and common transitions.
     */
    public abstract static class MandatoryLaneChangeState extends ActionState
    {
        /** The parent mandatory lane change pattern. */
        protected final MandatoryLaneChangePattern pattern;

        /**
         * Constructor.
         * @param p parent pattern
         */
        public MandatoryLaneChangeState(final ManeuverPattern p)
        {
            super(p);
            this.pattern = (MandatoryLaneChangePattern) p;
        }

        @Override
        public double getUtility()
        {
            return this.vehicle.getMandatoryLaneChangeDesire().magnitude();
        }

        @Override
        public SimpleOperationalPlan abort() throws ParameterException, OperationalPlanException, NullPointerException,
                IllegalArgumentException, GtuException, NetworkException
        {
            try
            {
                if (this.vehicle.getLaneChangeDesire().magnitude() < this.vehicle.getParameters()
                        .getParameter(MirovaParameters.DMAND))
                {
                    return finishManeuver();
                }
            }
            catch (Exception exception)
            {
                exception.printStackTrace();
            }
            return null;
        }

        /**
         * Checks transitions to ExecuteLaneChangeState or EmergencyStopState.
         * @param neigh the neighbors context
         * @param dir the target direction
         * @return plan if transitioned, null otherwise
         */
        protected SimpleOperationalPlan checkCommonTransitions(final NeighborsContext neigh, final LateralDirectionality dir)
                throws ParameterException, OperationalPlanException, GtuException, NetworkException
        {
            if (neigh.getIfLaneChangePossible(dir))
            {
                return transitionTo(new ExecuteLaneChangeState(this.maneuverPattern, dir));
            }

            Length distToLaneEnd = this.vehicle.getContext(InfrastructureContext.class).getRouteDistanceToLaneEnd();
            if (distToLaneEnd != null)
            {
                Acceleration requiredStopAccel =
                        MirovaCarFollowingUtil.stop(this.vehicle, distToLaneEnd.minus(RAMP_END_BUFFER));
                if (requiredStopAccel.si < -5.0)
                {
                    return transitionTo(new EmergencyStopState(this.maneuverPattern));
                }
            }
            return null;
        }

        /**
         * Set blinker intent on the plan based on target direction.
         */
        protected void setIndicators(final SimpleOperationalPlan plan, final LateralDirectionality dir)
        {
            if (dir.isLeft())
            {
                plan.setIndicatorIntentLeft();
            }
            else if (dir.isRight())
            {
                plan.setIndicatorIntentRight();
            }
        }

        /**
         * Checks for a parallel blocking vehicle on the target lane.
         */
        protected HeadwayGtu getParallelBlock(final NeighborsContext neigh, final LateralDirectionality dir, final EgoContext ego)
                throws ParameterException
        {
            HeadwayGtu leader = neigh.getLeader(dir);
            HeadwayGtu follower = neigh.getFollower(dir);
            Length safe = ego.getDesiredFrontHeadway(dir);
            double factor = this.vehicle.getParameters().getParameter(MirovaParameters.safetyDistanceReductionFactorLaneChange);
            if (leader != null && (leader.isParallel()
                    || (leader.getDistance().si < safe.si * factor && Math.abs(leader.getSpeed().si - ego.getEgoSpeed().si) < 1.0)))
            {
                return leader;
            }
            if (follower != null && (follower.isParallel() || (follower.getDistance().si < safe.si * factor
                    && Math.abs(follower.getSpeed().si - ego.getEgoSpeed().si) < 1.0)))
            {
                return follower;
            }
            return null;
        }

        /**
         * Checks for a physically overlapping vehicle (isParallel or distance < 0.0) on the target lane.
         */
        protected HeadwayGtu getPhysicallyOverlappingVehicle(final NeighborsContext neigh, final LateralDirectionality dir)
        {
            HeadwayGtu leader = neigh.getLeader(dir);
            if (leader != null && (leader.isParallel() || leader.getDistance().si < 0.0))
            {
                return leader;
            }
            HeadwayGtu follower = neigh.getFollower(dir);
            if (follower != null && (follower.isParallel() || follower.getDistance().si < 0.0))
            {
                return follower;
            }
            return null;
        }

        /**
         * Checks if the parallel block is still present based on safety distance (without speed synchronization check).
         */
        protected HeadwayGtu getParallelBlockWithoutSpeedCheck(final NeighborsContext neigh, final LateralDirectionality dir, final EgoContext ego)
                throws ParameterException
        {
            HeadwayGtu leader = neigh.getLeader(dir);
            HeadwayGtu follower = neigh.getFollower(dir);
            Length safe = ego.getDesiredFrontHeadway(dir);
            double factor = this.vehicle.getParameters().getParameter(MirovaParameters.safetyDistanceReductionFactorLaneChange);
            if (leader != null && (leader.isParallel() || leader.getDistance().si < safe.si * factor))
            {
                return leader;
            }
            if (follower != null && (follower.isParallel() || follower.getDistance().si < safe.si * factor))
            {
                return follower;
            }
            return null;
        }
    }

    /*
     * ========================================================================================= 1) STATE: ANTICIPATE_MERGE
     * =========================================================================================
     */

    /**
     * <b>State 1: Speed Anticipation Phase (AnticipateMergeState)</b>
     * <p>
     * Early state focused on speed synchronization with the target merge area bottleneck before 
     * actively searching for gap candidates or executing lateral actions.
     * </p>
     * 
     * <h4>Functional Behavior:</h4>
     * <ul>
     *   <li>Looks ahead up to <i>extendedLookAheadDistance</i> on the adjacent/main road lane to determine the average traffic speed.</li>
     *   <li>Applies an Exponential Moving Average (EMA) low-pass filter to smooth out short-term speed fluctuations.</li>
     *   <li>Softly adapts the ego vehicle's longitudinal speed to match the smoothed merge speed, ensuring a smooth, 
     *       non-disruptive merge approach.</li>
     * </ul>
     * 
     * <h4>Transitions:</h4>
     * <ul>
     *   <li>Transitions to <i>EvaluateTargetGapState</i> as soon as the target lane becomes physically available and 
     *       within the active lane change matching range.</li>
     * </ul>
     */
    public static class AnticipateMergeState extends MandatoryLaneChangeState
    {
        /** Smoothed anticipated speed to prevent high frequency oscillations (low-pass filter). */
        private Speed smoothedMergeSpeed = null;

        /** Smoothing factor (alpha) for the Exponential Moving Average (EMA). 0.0 < alpha <= 1.0 */
        private double SPEED_SMOOTHING_FACTOR = 0.1;

        /**
         * Constructor for the anticipation state.
         * @param p the parent maneuver pattern
         */
        public AnticipateMergeState(final ManeuverPattern p)
        {
            super(p);
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
                        
                        Speed actualSpeed = Double.isInfinite(targetLaneSpeed.si) ? speedLimit : targetLaneSpeed;
                        if (this.smoothedMergeSpeed == null || Double.isInfinite(this.smoothedMergeSpeed.si))
                        {
                            this.smoothedMergeSpeed = actualSpeed;
                        }
                        else
                        {
                            this.smoothedMergeSpeed = Speed.instantiateSI(
                                    (1.0 - this.SPEED_SMOOTHING_FACTOR) * this.smoothedMergeSpeed.si
                                            + this.SPEED_SMOOTHING_FACTOR * actualSpeed.si);
                        }

                        Speed targetSpeed = Speed.max(this.smoothedMergeSpeed, new Speed(20.0, SpeedUnit.KM_PER_HOUR));
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
            boolean isLaneAvailable = infra.getIfLaneAvailable(this.pattern.getTargetDirection());
            if (!isLaneAvailable)
            {
                return null; // Target lane is not yet physically available
            }

            EgoContext ego = this.vehicle.getContext(EgoContext.class);
            Length distToLaneEnd = infra.getPhysicalDistanceToLaneEnd();
            Speed egoSpeed = ego.getEgoSpeed();

            // 2. Target lane traffic speed evaluation (evaluated first, needed for early congested routing below)
            MacroTrafficContext macro = this.vehicle.getContext(MacroTrafficContext.class);
            RelativeLane targetRelativeLane =
                    (this.pattern.getTargetDirection().isLeft()) ? RelativeLane.LEFT : RelativeLane.RIGHT;
            Speed targetLaneSpeed = macro.getAverageSpeed(targetRelativeLane);
            if (targetLaneSpeed == null || Double.isNaN(targetLaneSpeed.si) || targetLaneSpeed.si <= 0.0)
            {
                targetLaneSpeed = infra.getLegalSpeedLimit();
                if (targetLaneSpeed != null && targetLaneSpeed.gt(new Speed(100.0, SpeedUnit.KM_PER_HOUR)))
                {
                    targetLaneSpeed = new Speed(100.0, SpeedUnit.KM_PER_HOUR);
                }
            }

            // Cap the reference speed at ego's desired speed (v_wunsch).
            // If the ego structurally cannot reach the target lane flow speed (e.g. a truck whose
            // v_wunsch = 80 km/h when target lane flows at 120 km/h), all delta- and fraction-based
            // release criteria must be evaluated against the speed the vehicle can actually achieve,
            // not the absolute target lane flow. Without this cap the vehicle would never be released.
            Speed desiredSpeed = ego.getCurrentDesiredSpeed();
            double effectiveTargetSpeedSI = (targetLaneSpeed != null && !Double.isNaN(targetLaneSpeed.si))
                    ? targetLaneSpeed.si : 0.0;
            if (desiredSpeed != null && !Double.isNaN(desiredSpeed.si) && desiredSpeed.si > 0.0)
            {
                effectiveTargetSpeedSI = Math.min(effectiveTargetSpeedSI, desiredSpeed.si);
            }

            // 5. Congested target lane traffic (< 40 km/h): no high-speed ramp acceleration required
            boolean isCongestedTarget = targetLaneSpeed != null && !Double.isNaN(targetLaneSpeed.si) && targetLaneSpeed.si < 11.11;

            // Fix 1: Early congested routing at pattern entry.
            // If the target lane is already congested when we first reach the active gate zone, bypass
            // speed synchronisation entirely and dispatch straight to CongestedMergeState. Without this,
            // executeControl() would accelerate the vehicle to >= 20 km/h before the congested branch
            // is ever evaluated, causing late-stage emergency stops (observed ~17% standstill rate).
            final double RAMP_GATE_START_DISTANCE = 120.0; // [m] – outer boundary of the active merge zone
            double dist = (distToLaneEnd != null) ? distToLaneEnd.si : Double.MAX_VALUE;
            if (isCongestedTarget && dist <= RAMP_GATE_START_DISTANCE)
            {
                return transitionTo(new CongestedMergeState(this.maneuverPattern));
            }

            // Fix 3: Soft distance-threshold – replace the hard binary 120 m switch with a linearly
            // relaxed speed-fraction requirement. As the vehicle approaches the ramp end, the minimum
            // fraction shrinks from MIN_MERGE_SPEED_FRACTION (at RAMP_GATE_START_DISTANCE) down to
            // MIN_SPEED_FRACTION_FLOOR (at 0 m). This eliminates the spatial clustering artefact that
            // previously appeared as a dense scatter-cloud at Merge-Position ≈ 80 m.
            final double MIN_MERGE_SPEED_FRACTION = 0.66;
            final double MIN_SPEED_FRACTION_FLOOR  = 0.50;
            double relaxedFraction;
            if (dist >= RAMP_GATE_START_DISTANCE)
            {
                relaxedFraction = MIN_MERGE_SPEED_FRACTION; // full fraction required while far from end
            }
            else
            {
                // Linear interpolation: 0.66 at 120 m → 0.50 at 0 m
                double t = Math.max(0.0, dist / RAMP_GATE_START_DISTANCE);
                relaxedFraction = MIN_SPEED_FRACTION_FLOOR + t * (MIN_MERGE_SPEED_FRACTION - MIN_SPEED_FRACTION_FLOOR);
            }

            // Fix 2: Speed proximity check – delta to effective target speed.
            // Instead of an absolute speed floor (which overfits to a specific speed limit),
            // we require that the ego is within MAX_SPEED_DELTA of the effective target speed.
            // effectiveTargetSpeedSI = min(targetLaneFlow, v_wunsch) ensures that vehicles which
            // structurally cannot reach target lane flow (e.g. trucks) are evaluated against their
            // own maximum comfortable speed, not the absolute traffic flow speed.
            // This criterion scales naturally across different speed limits and vehicle types:
            //   - 100 km/h flow, v_wunsch = 130 km/h: effective = 100, release at ego >= 80 km/h
            //   - 120 km/h flow, v_wunsch =  80 km/h: effective =  80, release at ego >= 60 km/h
            final double MAX_SPEED_DELTA_SI = 20.0 / 3.6; // max allowed delta to effective target speed [m/s]

            // 3. Speed synchronization: ego has built up at least relaxedFraction of the effective target
            //    speed AND is within MAX_SPEED_DELTA of it (free-flow regime only).
            boolean isSpeedSynchronized = effectiveTargetSpeedSI > 0.0
                    && egoSpeed.si >= relaxedFraction * effectiveTargetSpeedSI
                    && (isCongestedTarget || (effectiveTargetSpeedSI - egoSpeed.si) <= MAX_SPEED_DELTA_SI);

            // 4. Platoon obstruction on ramp: ego is trapped behind a slower vehicle (a_cf <= 0.2 m/s²).
            // The bare a_cf criterion is intentionally combined with a speed proximity guard: without it,
            // the condition also fires when the ego has reached its desired ramp speed and the CF model
            // simply requests no further acceleration (a_cf ≈ 0), which is not a genuine obstruction.
            // Using a wider delta (30 km/h) than the free-flow case (20 km/h) to allow obstructed vehicles
            // a realistic merge window even if full speed sync was impossible, while still preventing
            // dangerously large speed differences (e.g. 50 km/h ego into 100 km/h traffic).
            // Again referenced against effectiveTargetSpeedSI to handle v_wunsch < targetLaneFlow.
            final double MAX_OBSTRUCTED_DELTA_SI = 30.0 / 3.6; // max allowed speed gap for obstructed merge [m/s]
            boolean isObstructedOnRamp = ego.getCurrentCarFollowingAcceleration().si <= 0.2
                    && (isCongestedTarget || (effectiveTargetSpeedSI > 0.0
                            && (effectiveTargetSpeedSI - egoSpeed.si) <= MAX_OBSTRUCTED_DELTA_SI));

            // 1. Hard distance fallback: transition unconditionally at the very end of the ramp (dist <= 0)
            boolean isAtRampEnd = dist <= 0.0;

            if (isAtRampEnd || isSpeedSynchronized || isObstructedOnRamp || isCongestedTarget)
            {
                return transitionTo(new EvaluateTargetGapState(this.maneuverPattern));
            }

            return null; // Stay in AnticipateMergeState to build up speed on the acceleration lane
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
     * <b>State 2: Gap Evaluation Phase (EvaluateTargetGapState)</b>
     * <p>
     * Active gap selection and evaluation state that uses a safety-first heuristic hierarchy to identify 
     * a feasible adjacent gap candidate.
     * </p>
     * 
     * <h4>Functional Behavior:</h4>
     * <ul>
     *   <li>Searches for adjacent gaps and selects the most promising candidate.</li>
     *   <li>Evaluates kinematic safety constraints: checks if merging requires the ego to brake excessively, 
     *       or forces the target lane follower to brake harder than its comfort deceleration limit.</li>
     *   <li><b>Speed adaptation:</b> In a slower target lane (exit scenario), the ego vehicle must decelerate to align. 
     *       In a faster target lane (merge scenario), it accelerates towards the target flow speed.</li>
     * </ul>
     * 
     * <h4>Transitions:</h4>
     * <ul>
     *   <li>Transitions to <i>ExecuteLaneChangeState</i> immediately if the gap is physically clear (safety constraints met).</li>
     *   <li>Transitions to <i>CongestedMergeState</i> if traffic speed falls below 15 km/h.</li>
     *   <li>Transitions to <i>MatchLeaderSpeedState</i> if the deceleration required to align behind the target leader 
     *       exceeds comfort limits (safety-first heuristic).</li>
     *   <li>Transitions to <i>SolveParallelVehicleState</i> if a parallel vehicle is blocking access to the gap.</li>
     * </ul>
     */
    public static class EvaluateTargetGapState extends MandatoryLaneChangeState
    {
        /** Time horizon in seconds to evaluate overtaking maneuvers. */
        private static final double TIME_HORIZON_S = 3.0;

        /**
         * Constructor for the evaluation state.
         * @param p the parent maneuver pattern
         */
        public EvaluateTargetGapState(final ManeuverPattern p)
        {
            super(p);
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

            setIndicators(plan, this.pattern.getTargetDirection());

            return plan;

        }

        @Override
        public SimpleOperationalPlan next() throws ParameterException, OperationalPlanException, NetworkException, GtuException
        {
            NeighborsContext neigh = this.vehicle.getContext(NeighborsContext.class);
            LateralDirectionality dir = this.pattern.getTargetDirection();

            SimpleOperationalPlan commonTransition = checkCommonTransitions(neigh, dir);
            if (commonTransition != null)
            {
                return commonTransition;
            }

            Length distToLaneEnd = this.vehicle.getContext(InfrastructureContext.class).getRouteDistanceToLaneEnd();

            // --> NEU: Übergang in den Congested Merge State bei zähfließendem Verkehr (< 15 km/h)
            Speed egoSpeed = this.vehicle.getContext(EgoContext.class).getEgoSpeed();
            if (egoSpeed.lt(new Speed(15.0, org.djunits.unit.SpeedUnit.KM_PER_HOUR)))
            {
                return transitionTo(new CongestedMergeState(this.maneuverPattern));
            }

            // Check for parallel vehicle (physically overlapping)
            HeadwayGtu parallel = getPhysicallyOverlappingVehicle(neigh, dir);
            if (parallel != null)
            {
                return transitionTo(new SolveParallelVehicleState(this.maneuverPattern, parallel));
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
                    return transitionTo(new MatchLeaderSpeedState(this.maneuverPattern));
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
                    return transitionTo(new MatchLeaderSpeedState(this.maneuverPattern));
                }
            }
            return null; // Boundaries safe, no parallel vehicle, waiting for LaneChangePossible
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
     * <b>State 3: Match Target Leader Speed (MatchLeaderSpeedState)</b>
     * <p>
     * Active braking/alignment state entered when the ego vehicle is traveling too fast relative to the target lane leader 
     * (EgoDecelerationThreshold is violated). It overrides standard car-following acceleration with a targeted deceleration 
     * maneuver to yield and drop behind the leader. This occurs especially when changing to a slower target lane (exit scenario).
     * </p>
     * 
     * <h4>Functional Behavior:</h4>
     * <ul>
     *   <li>Applies deceleration required to follow the target lane leader safely.</li>
     *   <li>Imposes a distance-dependent speed cap in congested conditions near the end of the ramp (approaching 15 down to 5 km/h) 
     *       to prevent accelerating into tight openings.</li>
     *   <li>Continuously performs a kinematic reachability check: evaluates whether the target leader can be overtaken 
     *       or followed within the remaining ramp distance.</li>
     * </ul>
     * 
     * <h4>Transitions:</h4>
     * <ul>
     *   <li>Transitions to <i>ExecuteLaneChangeState</i> if the lane change becomes physically possible.</li>
     *   <li>Transitions to <i>EmergencyStopState</i> if the end of the lane is critically close (emergency stop condition).</li>
     *   <li>Transitions to <i>CongestedMergeState</i> if speed drops below 15 km/h.</li>
     *   <li>Transitions to <i>EvaluateTargetGapState</i> if the downstream gap becomes kinematically unreachable, 
     *       meaning the vehicle must stop and wait for an upstream gap instead.</li>
     *   <li>Transitions to <i>SolveParallelVehicleState</i> if a parallel blocking vehicle is detected.</li>
     * </ul>
     */
    public static class MatchLeaderSpeedState extends MandatoryLaneChangeState
    {

        /**
         * Constructor for the state.
         * @param p the parent maneuver pattern
         */
        public MatchLeaderSpeedState(final ManeuverPattern p)
        {
            super(p);
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

            setIndicators(plan, this.pattern.getTargetDirection());

            return plan;
        }

        @Override
        public SimpleOperationalPlan next() throws ParameterException, OperationalPlanException, NetworkException, GtuException
        {
            NeighborsContext neigh = this.vehicle.getContext(NeighborsContext.class);
            LateralDirectionality dir = this.pattern.getTargetDirection();

            SimpleOperationalPlan commonTransition = checkCommonTransitions(neigh, dir);
            if (commonTransition != null)
            {
                return commonTransition;
            }

            Length distToLaneEnd = this.vehicle.getContext(InfrastructureContext.class).getRouteDistanceToLaneEnd();

            // --> NEU: Übergang in den Congested Merge State bei zähfließendem Verkehr (< 15 km/h)
            Speed egoSpeed = this.vehicle.getContext(EgoContext.class).getEgoSpeed();
            if (egoSpeed.lt(new Speed(15.0, org.djunits.unit.SpeedUnit.KM_PER_HOUR)))
            {
                return transitionTo(new CongestedMergeState(this.maneuverPattern));
            }

            HeadwayGtu leader = neigh.getLeader(dir);
            EgoContext ego = this.vehicle.getContext(EgoContext.class);

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

            HeadwayGtu parallel = getParallelBlock(neigh, dir, ego);

            // --> NEU: Wenn ein paralleles Fahrzeug existiert, in den neuen State wechseln
            if (parallel != null)
            {
                return transitionTo(new SolveParallelVehicleState(this.maneuverPattern, parallel));
            }

            return null; // Keep braking
        }

        @Override
        public String toString()
        {
            return "MatchLeaderSpeedState";
        }
    }

    /*
     * ========================================================================================= STATE: SOLVE PARALLEL VEHICLE
     * =========================================================================================
     */

    /**
     * <b>State 4: Parallel Conflict Resolution (SolveParallelVehicleState)</b>
     * <p>
     * Active state entered when a vehicle is driving parallel on the target lane, blocking the lane change. 
     * It chooses between accelerating ahead or decelerating behind based on the remaining ramp distance.
     * </p>
     * 
     * <h4>Functional Behavior:</h4>
     * <ul>
     *   <li><b>Overtake Strategy (Merge/Accel Scenario):</b> If the distance to the lane end is greater than 200 m, 
     *       car-following acceleration is positive (&gt; 1 m/s²), and the blocker is not ahead, the ego vehicle 
     *       accelerates maximally to pass the parallel vehicle and merge ahead.</li>
     *   <li><b>Yield Strategy (Exit/Decel Scenario):</b> Otherwise, the vehicle decelerates comfortability (-1.0 m/s²) 
     *       to let the parallel vehicle pass, while ensuring it doesn't crash into leaders in its own lane.</li>
     * </ul>
     * 
     * <h4>Transitions:</h4>
     * <ul>
     *   <li>Transitions to <i>ExecuteLaneChangeState</i> if a gap becomes physically open.</li>
     *   <li>Transitions to <i>EmergencyStopState</i> if the end of the lane is critically close (emergency stop).</li>
     *   <li>Transitions to <i>CongestedMergeState</i> if speed drops below 15 km/h.</li>
     *   <li>Transitions to <i>MatchLeaderSpeedState</i> once the parallel block is resolved, if the target leader is ahead.</li>
     *   <li>Transitions to <i>EvaluateTargetGapState</i> once the parallel block is resolved and target lane is clear.</li>
     * </ul>
     */
    public static class SolveParallelVehicleState extends MandatoryLaneChangeState
    {

        /** Threshold for sufficient distance to lane end to attempt accelerating ahead [m]. */
        private static final double SUFFICIENT_DISTANCE_THRESHOLD = 200.0;

        private HeadwayGtu parallelVehicle = null;

        /**
         * Constructor for the solve parallel vehicle state.
         * @param p the parent maneuver pattern
         * @param parallelVehicle the parallel vehicle blocking the gap
         */
        public SolveParallelVehicleState(final ManeuverPattern p, final HeadwayGtu parallelVehicle)
        {
            super(p);
            this.parallelVehicle = parallelVehicle;
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
                        && parallelVehicle != null && !parallelVehicle.isAhead())
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

            setIndicators(plan, this.pattern.getTargetDirection());

            return plan;
        }

        @Override
        public SimpleOperationalPlan next() throws ParameterException, OperationalPlanException, NetworkException, GtuException
        {
            NeighborsContext neigh = this.vehicle.getContext(NeighborsContext.class);
            LateralDirectionality dir = this.pattern.getTargetDirection();

            SimpleOperationalPlan commonTransition = checkCommonTransitions(neigh, dir);
            if (commonTransition != null)
            {
                return commonTransition;
            }

            // --> NEU: Übergang in den Congested Merge State bei zähfließendem Verkehr (< 15 km/h)
            Speed egoSpeed = this.vehicle.getContext(EgoContext.class).getEgoSpeed();
            if (egoSpeed.lt(new Speed(15.0, org.djunits.unit.SpeedUnit.KM_PER_HOUR)))
            {
                return transitionTo(new CongestedMergeState(this.maneuverPattern));
            }

            // 3. Check if the parallel vehicle is still blocking us
            parallelVehicle = getParallelBlockWithoutSpeedCheck(neigh, dir, this.vehicle.getContext(EgoContext.class));

            // 4. If the parallel vehicle is gone (passed us or we passed it), transition appropriately
            if (parallelVehicle == null)
            {
                HeadwayGtu targetLeader = neigh.getLeader(dir);
                if (targetLeader != null && targetLeader.getDistance().si > 0.0)
                {
                    // The vehicle is now ahead of us. Transition to MatchLeaderSpeedState to follow it.
                    return transitionTo(new MatchLeaderSpeedState(this.maneuverPattern));
                }
                return transitionTo(new EvaluateTargetGapState(this.maneuverPattern));
            }

            return null; // Stay in this state and continue resolving the conflict
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
     * <b>State 5: Congested Flow Dispatcher (CongestedMergeState)</b>
     * <p>
     * Dispatcher state activated in slow or stop-and-go traffic (ego speed &lt; 15 km/h). 
     * It does not control longitudinal behavior itself, but immediately routes control to a specific 
     * congested sub-state based on immediate blocker presence.
     * </p>
     * 
     * <h4>Functional Behavior:</h4>
     * <ul>
     *   <li>Acts as a pure decision dispatcher evaluated on every simulation step.</li>
     *   <li>Returns default own-lane car-following acceleration as a neutral fallback.</li>
     * </ul>
     * 
     * <h4>Transitions:</h4>
     * <ul>
     *   <li>Transitions to <i>ExecuteLaneChangeState</i> if a gap becomes physically open.</li>
     *   <li>Transitions to <i>EmergencyStopState</i> if the end of the lane is critically close (emergency stop).</li>
     *   <li>Transitions to <i>EvaluateTargetGapState</i> if speed recovers above 30 km/h, returning to normal evaluation.</li>
     *   <li>Transitions to <i>CongestedCreepState</i> if a parallel vehicle is blocking the adjacent gap.</li>
     *   <li>Transitions to <i>CongestedFollowLeaderState</i> if no parallel blocker is present but a target leader exists.</li>
     * </ul>
     */
    public static class CongestedMergeState extends MandatoryLaneChangeState
    {
        /** Speed threshold above which the vehicle returns to normal gap evaluation. */
        static final Speed RECOVERY_SPEED_THRESHOLD = new Speed(30.0, org.djunits.unit.SpeedUnit.KM_PER_HOUR);

        /**
         * Constructor for the congested merge state.
         * @param p the parent maneuver pattern
         */
        public CongestedMergeState(final ManeuverPattern p)
        {
            super(p);
            this.active = true;
        }

        @Override
        public SimpleOperationalPlan executeControl() throws ParameterException, GtuException, NetworkException
        {
            // Pure routing state: return neutral car-following acceleration for this tick.
            // next() will dispatch to the appropriate sub-state on the same or next tick.
            Acceleration aCf = this.vehicle.getContext(EgoContext.class).getCurrentCarFollowingAcceleration();
            SimpleOperationalPlan plan = new SimpleOperationalPlan(aCf, this.pattern.patternSpecificTimestep);
            setIndicators(plan, this.pattern.getTargetDirection());
            return plan;
        }

        @Override
        public SimpleOperationalPlan next() throws ParameterException, OperationalPlanException, NetworkException, GtuException
        {
            NeighborsContext neigh = this.vehicle.getContext(NeighborsContext.class);
            LateralDirectionality dir = this.pattern.getTargetDirection();

            SimpleOperationalPlan commonTransition = checkCommonTransitions(neigh, dir);
            if (commonTransition != null)
            {
                return commonTransition;
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
     * <b>State 5a: Congested Creep Sub-state (CongestedCreepState)</b>
     * <p>
     * Sub-state of the congested merge sequence when a parallel-blocking vehicle is present on the target lane.
     * </p>
     * 
     * <h4>Functional Behavior:</h4>
     * <ul>
     *   <li>Creeps gently forward at a target speed of 3 km/h with a low maximum acceleration cap of 0.3 m/s².</li>
     *   <li>This prevents the ego vehicle from driving alongside the blocking vehicle, positioning it to fall 
     *       behind the blocker and wait for the block to clear.</li>
     *   <li>Ensures longitudinal safety by flooring acceleration at own-lane car-following requirements.</li>
     * </ul>
     * 
     * <h4>Transitions:</h4>
     * <ul>
     *   <li>Transitions to <i>ExecuteLaneChangeState</i> if a gap becomes physically open.</li>
     *   <li>Transitions to <i>EmergencyStopState</i> if the end of the lane is critically close (emergency stop).</li>
     *   <li>Transitions back to <i>CongestedMergeState</i> (dispatcher) as soon as the parallel block is resolved.</li>
     * </ul>
     */
    public static class CongestedCreepState extends MandatoryLaneChangeState
    {
        /**
         * Constructor for the congested creep state.
         * @param p the parent maneuver pattern
         */
        public CongestedCreepState(final ManeuverPattern p)
        {
            super(p);
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
            setIndicators(plan, this.pattern.getTargetDirection());
            return plan;
        }

        @Override
        public SimpleOperationalPlan next() throws ParameterException, OperationalPlanException, NetworkException, GtuException
        {
            NeighborsContext neigh = this.vehicle.getContext(NeighborsContext.class);
            LateralDirectionality dir = this.pattern.getTargetDirection();

            SimpleOperationalPlan commonTransition = checkCommonTransitions(neigh, dir);
            if (commonTransition != null)
            {
                return commonTransition;
            }

            // 3. Parallel block resolved → return to dispatcher
            if (!detectParallelBlock(neigh, dir, this.vehicle.getContext(EgoContext.class), this.vehicle.getParameters()))
            {
                return transitionTo(new CongestedMergeState(this.maneuverPattern));
            }

            return null; // Stay: parallel vehicle still blocking
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
     * <b>State 5b: Congested Follow Leader Sub-state (CongestedFollowLeaderState)</b>
     * <p>
     * Sub-state of the congested merge sequence when no parallel blocking vehicle is present, but the target 
     * gap is not yet open.
     * </p>
     * 
     * <h4>Functional Behavior:</h4>
     * <ul>
     *   <li>Follows the putative leader on the target lane.</li>
     *   <li>Enforces a distance-dependent target speed cap that scales linearly from 15 km/h down to 5 km/h 
     *       as the ramp end approaches within a 200 m window. This prevents the ego vehicle from accelerating 
     *       rapidly towards the bottleneck.</li>
     *   <li>Floors acceleration at own-lane car-following requirements to maintain absolute longitudinal safety.</li>
     * </ul>
     * 
     * <h4>Transitions:</h4>
     * <ul>
     *   <li>Transitions to <i>ExecuteLaneChangeState</i> if a gap becomes physically open.</li>
     *   <li>Transitions to <i>EmergencyStopState</i> if the end of the lane is critically close (emergency stop).</li>
     *   <li>Transitions to <i>EvaluateTargetGapState</i> if traffic speed recovers above 30 km/h.</li>
     *   <li>Transitions back to <i>CongestedMergeState</i> (dispatcher) if a parallel block appears.</li>
     * </ul>
     */
    public static class CongestedFollowLeaderState extends MandatoryLaneChangeState
    {
        /** Speed threshold below which the vehicle remains in the congested branch. */
        private static final Speed CONGESTION_SPEED_THRESHOLD = new Speed(15.0, org.djunits.unit.SpeedUnit.KM_PER_HOUR);

        /**
         * Constructor for the congested follow-leader state.
         * @param p the parent maneuver pattern
         */
        public CongestedFollowLeaderState(final ManeuverPattern p)
        {
            super(p);
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
            setIndicators(plan, dir);
            return plan;
        }

        @Override
        public SimpleOperationalPlan next() throws ParameterException, OperationalPlanException, NetworkException, GtuException
        {
            NeighborsContext neigh = this.vehicle.getContext(NeighborsContext.class);
            LateralDirectionality dir = this.pattern.getTargetDirection();

            SimpleOperationalPlan commonTransition = checkCommonTransitions(neigh, dir);
            if (commonTransition != null)
            {
                return commonTransition;
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
     * <b>State 6: Emergency Stop & Last-Minute Overtake (EmergencyStopState)</b>
     * <p>
     * Emergency braking state activated when the remaining lane length (ramp end or exit point) is critically low 
     * and no gap has been found. It ensures the vehicle stops safely before the lane end buffer, while continuously 
     * checking for a last-minute overtake opportunity.
     * </p>
     * 
     * <h4>Functional Behavior:</h4>
     * <ul>
     *   <li>Calculates deceleration to stop completely before the lane end buffer.</li>
     *   <li>If a parallel vehicle is blocking, it solves a quadratic kinematic equation to estimate the time and distance 
     *       required to accelerate maximally, pass the parallel vehicle, and complete the lane change within the remaining distance.</li>
     *   <li>If the last-minute overtake is calculated to be safe and within the available distance, it overrides the stopping constraint 
     *       and accelerates maximally.</li>
     *   <li>Otherwise, it decelerates more aggressively (at least -2.5 m/s²) to drop back and let the blocker pass.</li>
     * </ul>
     * 
     * <h4>Transitions:</h4>
     * <ul>
     *   <li>Transitions to <i>ExecuteLaneChangeState</i> immediately if the lane change becomes physically possible.</li>
     * </ul>
     */
    public static class EmergencyStopState extends MandatoryLaneChangeState
    {
        /**
         * Constructor.
         * @param p the parent maneuver pattern
         */
        public EmergencyStopState(final ManeuverPattern p)
        {
            super(p);
            this.active = true;
        }

        @Override
        public SimpleOperationalPlan executeControl() throws ParameterException, GtuException, NetworkException
        {
            EgoContext ego = this.vehicle.getContext(EgoContext.class);
            InfrastructureContext infra = this.vehicle.getContext(InfrastructureContext.class);
            NeighborsContext neigh = this.vehicle.getContext(NeighborsContext.class);
            LateralDirectionality dir = this.pattern.getTargetDirection();

            // Stop before ramp end
            Length distToLaneEnd = infra.getRouteDistanceToLaneEnd();
            Acceleration aStop = distToLaneEnd != null
                    ? MirovaCarFollowingUtil.stop(this.vehicle, Length.max(distToLaneEnd.minus(RAMP_END_BUFFER), Length.ZERO))
                    : ego.getCurrentCarFollowingAcceleration();

            // Safety: don't rear-end current-lane leader
            Acceleration aCf = ego.getCurrentCarFollowingAcceleration();

            // Adapt to target-lane leader; floor at comfort deceleration threshold
            HeadwayGtu putativeLeader = neigh.getLeader(dir);
            Acceleration aLeader = putativeLeader != null
                    ? Acceleration.max(MirovaCarFollowingUtil.followSingleLeader(this.vehicle, putativeLeader),
                            this.vehicle.getParameters().getParameter(MirovaParameters.egoDecelerationThreshold))
                    : Acceleration.POSITIVE_INFINITY;

            // Detect if we are blocked by a parallel vehicle
            HeadwayGtu parallelGtu = null;
            if (putativeLeader != null && (putativeLeader.isParallel() || putativeLeader.getDistance().si < 0.0))
            {
                parallelGtu = putativeLeader;
            }
            else
            {
                HeadwayGtu putativeFollower = neigh.getFollower(dir);
                if (putativeFollower != null && (putativeFollower.isParallel() || putativeFollower.getDistance().si < 0.0))
                {
                    parallelGtu = putativeFollower;
                }
            }

            boolean attemptOvertake = false;
            if (parallelGtu != null && distToLaneEnd != null)
            {
                double vEgo = ego.getEgoSpeed().si;
                double vPart = parallelGtu.getSpeed().si;
                double aMax = Math.max(ego.getMaxPhysicalAcceleration().si, 0.1);
                double lcdur = this.vehicle.getParameters().getParameter(ParameterTypes.LCDUR).si;

                // 1. Calculate relative distance we need to make up to get fully ahead
                boolean isFollower = (parallelGtu == neigh.getFollower(dir));
                double overlap = (parallelGtu.getDistance().si < 0.0) ? -parallelGtu.getDistance().si : 0.0;
                double safetyBuffer = 5.0; // 5 meters buffer

                double dRel0;
                if (isFollower)
                {
                    // Parallel vehicle is behind/overlapping from behind. We only need to clear the overlap.
                    dRel0 = overlap + safetyBuffer;
                }
                else
                {
                    // Parallel vehicle is ahead. We need to clear the overlap + its length + our length.
                    dRel0 = parallelGtu.getLength().si + this.vehicle.getGtu().getLength().si + overlap + safetyBuffer;
                }

                // 2. Solve quadratic equation for t_overtake:
                // dRel0 = dV * t + 0.5 * a * t^2  ==>  0.5 * a * t^2 + dV * t - dRel0 = 0
                // where dV = vEgo - vPart
                double dV = vEgo - vPart;
                double discriminant = dV * dV + 2.0 * aMax * dRel0;
                if (discriminant >= 0.0)
                {
                    double tOvertake = (-dV + Math.sqrt(discriminant)) / aMax;
                    if (tOvertake > 0.0)
                    {
                        // 3. Compute physical distance traveled during overtake and lane change
                        double vFinal = vEgo + aMax * tOvertake;
                        double dOvertake = vPart * tOvertake + dRel0;
                        double dLaneChange = vFinal * lcdur;
                        double dRequired = dOvertake + dLaneChange;

                        double dAvailable = Math.max(0.0, distToLaneEnd.si - RAMP_END_BUFFER.si);

                        if (dRequired < dAvailable)
                        {
                            // Check if our own lane is clear enough to allow accelerating
                            if (aCf.si > 0.5)
                            {
                                attemptOvertake = true;
                            }
                        }
                    }
                }
            }

            Acceleration finalAcc;
            if (attemptOvertake)
            {
                // We are accelerating to merge ahead of the parallel vehicle, ignoring the ramp-end stop
                // constraint because we expect to change lanes before the end of the ramp.
                // We still respect own-lane leader safety (aCf).
                finalAcc = Acceleration.min(aCf, ego.getMaxPhysicalAcceleration());
            }
            else
            {
                // Standard braking strategy, but if there is a parallel vehicle and we cannot overtake,
                // we actively brake harder to drop behind it and let it pass.
                Acceleration aResolve = aStop;
                if (parallelGtu != null)
                {
                    // Brake at least at -2.5 m/s^2 to drop behind
                    aResolve = Acceleration.min(aStop, Acceleration.instantiateSI(-2.5));
                }
                finalAcc = Acceleration.min(aResolve, Acceleration.min(aCf, aLeader));
            }

            SimpleOperationalPlan plan = new SimpleOperationalPlan(finalAcc, this.pattern.patternSpecificTimestep);
            setIndicators(plan, dir);

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
        public String toString()
        {
            return "EmergencyStopState";
        }
    }

    /*
     * ========================================================================================= 6) STATE: EXECUTE_LANE_CHANGE
     * =========================================================================================
     */

    /**
     * <b>State 7: Lateral Execution Phase (ExecuteLaneChangeState)</b>
     * <p>
     * Final action state in the sequence where the physical lateral lane change is initiated and executed.
     * </p>
     * 
     * <h4>Functional Behavior:</h4>
     * <ul>
     *   <li>Commits the vehicle to the lane-change action lock, preventing interruptions.</li>
     *   <li>Triggers temporary safety-distance relaxation on surrounding vehicles (cooperative gap creation) 
     *       so that target lane vehicles can adjust to accommodate the merge.</li>
     *   <li>Calculates joint longitudinal and lateral trajectories to perform the shift.</li>
     * </ul>
     * 
     * <h4>Transitions:</h4>
     * <ul>
     *   <li>Releases the action lock and finishes the maneuver pattern once the lane change is complete 
     *       and the vehicle is fully established on the target lane.</li>
     * </ul>
     */
    public static class ExecuteLaneChangeState extends MandatoryLaneChangeState
    {
        private final LateralDirectionality direction;

        private final Lane originLane;

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

            setIndicators(plan, this.direction);

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
        public String toString()
        {
            return "ExecuteLaneChange[" + this.direction + "]";
        }
    }
}
