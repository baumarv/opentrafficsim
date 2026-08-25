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
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.ContextCategory;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.EgoContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.InfrastructureContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.MacroTrafficContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.NeighborsContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.InfrastructureContext.ScanDirection;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ActionState;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ManeuverPattern;
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
 *   <li><b>AnticipateMergeState</b> (Anticipation Phase): The target lane is not yet physically alongside, so nothing can
 *       be perceived on it. The ego estimates the speed of the traffic it will join by scanning the target lane ahead and
 *       adjusts its own speed towards it, smoothed by an Exponential Moving Average filter. When the target lane becomes
 *       available it transitions to <i>SynchroniseMergeSpeedState</i> -- a change of phase, not a decision to merge.</li>
 *   <li><b>SynchroniseMergeSpeedState</b> (Synchronisation Phase): The ego is on the acceleration lane with the target
 *       lane alongside. It accelerates towards the reference speed of that lane and resolves whatever prevents the merge.
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
 *       to <i>SynchroniseMergeSpeedState</i>. If a parallel blocker appears, it transitions to <i>SolveParallelVehicleState</i>.</li>
 *   <li><b>SolveParallelVehicleState</b> (Parallel Conflict Resolution): Resolves situations where a vehicle is driving
 *       parallel on the adjacent lane. If there is enough remaining ramp distance (>200m) and own lane headway, it accelerates
 *       maximally to overtake and merge ahead (Overtake Strategy). Otherwise, it decelerates to drop behind the blocker.</li>
 *   <li><b>CongestedMergeState</b> (Congested Flow Dispatcher): Activated under congested conditions (speed < 15 km/h).
 *       Acts as a pure routing dispatcher, transitioning to <i>CongestedCreepState</i> when a parallel vehicle is blocking,
 *       or <i>CongestedFollowLeaderState</i> when the target lane leader is clear but the lane change is not yet physically possible.
 *       If speed recovers above 30 km/h, it transitions back to <i>SynchroniseMergeSpeedState</i>.</li>
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

    /** Buffer distance before the end of the lane where emergency braking is enforced. */
    public static final Length RAMP_END_BUFFER = Length.instantiateSI(10.0);

    /** Distance threshold to transition from anticipation to active matching. */
    private static final Length ANTICIPATION_THRESHOLD = Length.instantiateSI(400.0);

    /**
     * Upper bound for the merge reference speed when it has to be derived from the legal speed limit instead of measured
     * traffic. A merger cannot realistically synchronise to a 130 km/h limit from an acceleration lane, so an unmeasured
     * target lane is assumed to flow at no more than this speed.
     */
    private static final Speed MAX_UNMEASURED_REFERENCE_SPEED = new Speed(100.0, SpeedUnit.KM_PER_HOUR);

    /** Number of vehicles sampled when estimating the target lane speed from perceived traffic. */
    private static final int REFERENCE_SPEED_SAMPLE_SIZE = 3;

    /**
     * Car-following acceleration below which the ego is considered to be held back by a vehicle ahead of it on the
     * acceleration lane rather than free to build up speed.
     */
    private static final double OBSTRUCTION_ACCELERATION_THRESHOLD = 0.2;

    /** Lower bound on the assumed acceleration capability, guarding the kinematic distance estimate against division by zero. */
    private static final double MIN_ASSUMED_ACCELERATION = 0.1;

    /** Reference speed below which the target lane counts as congested, so that speed synchronisation is meaningless. */
    private static final double CONGESTED_TARGET_SPEED = 11.11;

    /**
     * Speed difference tolerated once the ego has run out of time to close it [m/s].
     * <p>
     * {@link #MAX_SPEED_DELTA} applies while there is still time to build up speed; this is the value it widens to as
     * that time runs out. A constant bound is what produced the vicious cycle the measurements showed: a vehicle that
     * had braked for a parallel blocker lost some 15 km/h, and the unchanged 20 km/h bound then refused it the merge
     * for the rest of the ramp - 68 % of the samples between 100 and 150 m were blocked by the speed criterion alone,
     * at a median speed of 46 km/h against a 75 km/h main stream, until they reached the end and stopped.
     * </p>
     */
    private static final double MAX_SPEED_DELTA_OUT_OF_TIME = 40.0 / 3.6;

    /**
     * Time still available on the ramp below which the speed tolerance starts widening [s].
     * <p>
     * Expressed as time rather than distance so the criterion does not encode the length of one particular weaving
     * section, and set to the order of a lane change duration: once what remains is no more than the manoeuvre itself
     * takes, insisting on further speed build-up asks for something that can no longer happen.
     * </p>
     */
    private static final double SPEED_GATE_TIME_HORIZON = 6.0;

    /** Maximum accepted speed difference to the reference speed while the ego can still close it [m/s]. */
    private static final double MAX_SPEED_DELTA = 20.0 / 3.6;

    /**
     * Maximum accepted speed difference for a ego held back on the ramp [m/s]. Wider than {@link #MAX_SPEED_DELTA},
     * because such a vehicle cannot improve its situation by waiting, but still bounded so that it does not enter fast
     * traffic at an arbitrary speed difference.
     */
    private static final double MAX_OBSTRUCTED_DELTA = 30.0 / 3.6;

    /**
     * Share of its free acceleration that the car-following model must still be returning for the ramp boost to apply.
     * <p>
     * Below this the model is reacting to the vehicle ahead rather than to the desired speed, and overriding it would
     * close that gap. Above it the leader is far enough that the only thing holding the ego back is the comfort
     * parameter {@code a}, which is not a safety constraint.
     * </p>
     */
    private static final double UNRESTRICTED_CAR_FOLLOWING_SHARE = 0.80;

    /** Distance to the ramp end within which merging takes precedence over merging comfortably [m]. */
    private static final double RAMP_FINAL_APPROACH_DISTANCE = 20.0;

    /** Length of the segment scanned on the target lane when it is not yet physically adjacent. */
    private static final Length REFERENCE_SPEED_SCAN_LENGTH = Length.instantiateSI(150.0);

    /**
     * Cache keys under which the merge reference speed is stored for the duration of one simulation tick,
     * indexed by {@code LateralDirectionality.ordinal()} so that no key is concatenated per access.
     */
    private static final String[] REFERENCE_SPEED_CACHE_KEYS =
            ContextCategory.directionKeys("mergeReferenceSpeed_");

    /**
     * Determines the speed the ego vehicle has to synchronise with in order to merge into the target lane.
     * <p>
     * This is the single reference speed for the whole pattern: the control states steer towards it and the transition
     * criteria are evaluated against it. It is derived from a cascade of sources, ordered by how directly they observe the
     * traffic the ego will actually merge into. The cascade is necessary because the available information changes over the
     * course of the manoeuvre -- while the ego is still on the acceleration lane the target lane is not yet a perceivable
     * neighbouring lane, so only the long-range lane scan yields data.
     * </p>
     * <ol>
     * <li><b>Perceived upstream traffic on the target lane.</b> The followers on the target lane are exactly the vehicles the
     * ego has to merge in between, and perception resolves them across lane boundaries. Available only once the target lane is
     * physically adjacent.</li>
     * <li><b>Local macroscopic perception</b> ({@code TrafficPerception}) of the adjacent lane. Same availability
     * restriction, but tolerates the case where no individual follower is perceived.</li>
     * <li><b>Long-range scan of the target lane</b> ahead of the merge point. This is the only source available during early
     * anticipation. It is scanned {@code BACK_TO_FRONT}, i.e. starting at the upstream end of the segment: the vehicles
     * closest to the merge point from upstream determine the speed the ego must match, whereas vehicles further downstream
     * are already moving away and may reflect a state the ego will never encounter (for instance when a shockwave travels
     * upstream towards the merge point).</li>
     * <li><b>Legal speed limit</b>, capped at {@link #MAX_UNMEASURED_REFERENCE_SPEED}, when the target lane is empty.</li>
     * </ol>
     * <p>
     * The result is finally capped at the ego's own desired speed, so that a vehicle which structurally cannot reach the
     * target lane flow (a truck with {@code v_wunsch} = 80 km/h merging into 120 km/h traffic) is evaluated against the speed
     * it can actually achieve.
     * </p>
     * @param vehicle the tactical planner of the ego vehicle
     * @param dir the lateral direction of the target lane
     * @return the merge reference speed, never null and never NaN
     */
    public static Speed getMergeReferenceSpeed(final MirovaTacticalPlanner vehicle, final LateralDirectionality dir)
    {
        // Cached for the duration of the tick: the control path and the transition criteria both ask for this value
        // every tick, and the cascade below iterates perceived neighbours and may scan a whole lane. The cache is
        // cleared by the context update, so the value can never outlive the perception it was derived from.
        EgoContext cache = null;
        String cacheKey = REFERENCE_SPEED_CACHE_KEYS[dir.ordinal()];
        try
        {
            cache = vehicle.getContext(EgoContext.class);
            Speed cached = cache.getCachedValue(cacheKey, Speed.class);
            if (cached != null)
            {
                return cached;
            }
        }
        catch (Exception exception)
        {
            cache = null; // no context available, compute without caching
        }

        Speed reference = null;

        // 1. Perceived upstream traffic on the target lane.
        try
        {
            NeighborsContext neigh = vehicle.getContext(NeighborsContext.class);
            Iterable<HeadwayGtu> followers = neigh.getFollowers(dir);
            if (followers != null)
            {
                double sum = 0.0;
                int count = 0;
                for (HeadwayGtu follower : followers)
                {
                    if (count >= REFERENCE_SPEED_SAMPLE_SIZE)
                    {
                        break;
                    }
                    if (follower.getDistance() != null && follower.getDistance().si >= 0.0)
                    {
                        sum += follower.getSpeed().si;
                        count++;
                    }
                }
                if (count > 0)
                {
                    reference = Speed.instantiateSI(sum / count);
                }
            }
        }
        catch (Exception exception)
        {
            reference = null; // target lane not perceivable yet, fall through
        }

        // 2. Local macroscopic perception of the adjacent lane.
        if (!isUsableReference(reference))
        {
            try
            {
                MacroTrafficContext macro = vehicle.getContext(MacroTrafficContext.class);
                reference = macro.getAverageSpeed(dir.isLeft() ? RelativeLane.LEFT : RelativeLane.RIGHT);
            }
            catch (Exception exception)
            {
                reference = null;
            }
        }

        // 3. Long-range scan of the target lane, from its upstream end towards the merge point.
        if (!isUsableReference(reference))
        {
            try
            {
                InfrastructureContext infra = vehicle.getContext(InfrastructureContext.class);
                Lane targetLane = infra.getDownstreamAdjacentLane(dir);
                if (targetLane != null)
                {
                    reference = infra.getLaneAverageSpeed(targetLane, Length.ZERO, REFERENCE_SPEED_SCAN_LENGTH,
                            REFERENCE_SPEED_SAMPLE_SIZE, ScanDirection.BACK_TO_FRONT);
                }
            }
            catch (Exception exception)
            {
                reference = null;
            }
        }

        // 4. Empty target lane: assume it flows at the legal speed limit, bounded to a speed a merger can reach.
        if (!isUsableReference(reference))
        {
            Speed speedLimit = null;
            try
            {
                speedLimit = vehicle.getContext(InfrastructureContext.class).getLegalSpeedLimit();
            }
            catch (Exception exception)
            {
                speedLimit = null;
            }
            reference = isUsableReference(speedLimit) ? Speed.min(speedLimit, MAX_UNMEASURED_REFERENCE_SPEED)
                    : MAX_UNMEASURED_REFERENCE_SPEED;
        }

        // Cap at the ego's own desired speed.
        try
        {
            Speed desiredSpeed = vehicle.getContext(EgoContext.class).getCurrentDesiredSpeed();
            if (isUsableReference(desiredSpeed))
            {
                reference = Speed.min(reference, desiredSpeed);
            }
        }
        catch (Exception exception)
        {
            // keep the uncapped reference
        }

        if (cache != null)
        {
            cache.cacheValue(cacheKey, reference, true);
        }
        return reference;
    }

    /**
     * Checks whether a measured speed can serve as a merge reference.
     * @param speed the speed to check, may be null
     * @return true if the speed is present, finite and strictly positive
     */
    private static boolean isUsableReference(final Speed speed)
    {
        return speed != null && !Double.isNaN(speed.si) && !Double.isInfinite(speed.si) && speed.si > 0.0;
    }

    /**
     * Decides whether the ego is in a state in which executing the lane change is appropriate.
     * <p>
     * This is a <i>precondition of the execution</i>, not a property of any single state. Whether the manoeuvre may be
     * carried out depends on the ego's speed relative to the traffic it is joining and on how much acceleration lane is
     * left -- never on which state the vehicle happens to have reached the decision from. Evaluating it here, and requiring
     * it in {@link MandatoryLaneChangeState#checkCommonTransitions}, has two consequences that the previous formulation as
     * a transition condition on a single edge could not provide:
     * </p>
     * <ul>
     * <li>Every path to {@link ExecuteLaneChangeState} is covered. The state that resolves merge conflicts is reachable
     * from five different predecessors, of which only one used to test these criteria; a vehicle entering it from any of
     * the other four could execute the change without ever being checked.</li>
     * <li>The preparatory states become freely reachable. A vehicle that still has to resolve a parallel blocker or drop
     * behind a leader may enter those states at any time, because doing so no longer implies permission to merge.</li>
     * </ul>
     * <p>
     * The vehicle is considered ready when any of the following holds:
     * </p>
     * <ul>
     * <li><b>It has run out of acceleration lane.</b> Within the final approach distance the question is no longer whether
     * merging is comfortable but whether it happens at all.</li>
     * <li><b>It is synchronised with the target lane.</b> It is within the tolerated difference of the speed the
     * remaining lane length still allows it to reach. The tolerance is {@link #MAX_SPEED_DELTA} while there is time to
     * build up speed and widens towards {@link #MAX_SPEED_DELTA_OUT_OF_TIME} as that time runs out.</li>
     * <li><b>It cannot accelerate any further.</b> Held back by a vehicle ahead on the ramp, the ego will not become
     * faster by waiting, so a wider speed difference is accepted.</li>
     * <li><b>The target lane is congested.</b> There is no speed to synchronise with, so speed criteria are meaningless.</li>
     * </ul>
     * @param vehicle the tactical planner of the ego vehicle
     * @param dir the lateral direction of the target lane
     * @return true if the lane change may be executed now
     * @throws ParameterException if a parameter lookup fails
     * @throws GtuException if GTU limits fail
     * @throws NetworkException if network queries fail
     */
    public static boolean mayExecuteLaneChange(final MirovaTacticalPlanner vehicle, final LateralDirectionality dir)
            throws ParameterException, GtuException, NetworkException
    {
        EgoContext ego = vehicle.getContext(EgoContext.class);
        InfrastructureContext infra = vehicle.getContext(InfrastructureContext.class);
        Speed egoSpeed = ego.getEgoSpeed();
        Length distToLaneEnd = infra.getPhysicalDistanceToLaneEnd();
        double dist = (distToLaneEnd != null) ? distToLaneEnd.si : Double.MAX_VALUE;

        // Reference speed of the traffic being joined, already capped at the ego's own desired speed: a truck that
        // structurally cannot reach the target lane flow must be judged against the speed it can achieve.
        double effectiveTargetSpeedSI = getMergeReferenceSpeed(vehicle, dir).si;

        // Congested target lane: there is no flow speed to synchronise with, so the speed criteria do not apply.
        boolean isCongestedTarget = effectiveTargetSpeedSI < CONGESTED_TARGET_SPEED;

        double speedDeficitSI = effectiveTargetSpeedSI - egoSpeed.si;
        boolean canAccelerate = ego.getCurrentCarFollowingAcceleration().si > OBSTRUCTION_ACCELERATION_THRESHOLD;
        double achievableAcceleration = Math.max(ego.getMaxPhysicalAcceleration().si, MIN_ASSUMED_ACCELERATION);

        // Speed the ego can still reach before the change has to be complete, from the remaining lane length less the
        // stretch the manoeuvre itself needs, capped at the speed of the traffic being joined. This is the quantity the
        // readiness question is actually about: a driver builds up speed while doing so still buys something, and
        // merges once it no longer does.
        //
        // Formulating it kinematically rather than as a fixed fraction of the target speed is what keeps the criterion
        // usable on any geometry. The predecessor demanded 66 % of the target speed and dropped its own delta bound as
        // soon as the remaining lane was too short to close the gap - on a 184 m weaving section that happened within
        // the first few metres, after which nothing but the 66 % remained and vehicles merged some 40 km/h below the
        // stream they joined. It also means a stricter gate cannot create ramp queueing: the demand shrinks with the
        // distance left, so the ego is never held for a speed the lane cannot deliver.
        double usableDistance = Math.max(0.0, dist - RAMP_FINAL_APPROACH_DISTANCE);
        double achievableSpeedSI = Math.min(effectiveTargetSpeedSI,
                Math.sqrt(egoSpeed.si * egoSpeed.si + 2.0 * achievableAcceleration * usableDistance));

        // The ego is judged against the speed it can still reach, not against the speed of the target lane, and the
        // tolerance around it widens as the time left on the ramp runs out. Both halves matter: measuring against the
        // achievable speed keeps the criterion honest on a short acceleration lane, and widening the tolerance is what
        // lets a vehicle that has already lost speed get back in instead of being refused until the ramp ends.
        //
        // A fraction of the achievable speed was tried first and turned out to decide nothing: every sample it
        // admitted was already admitted by the 20 km/h bound, so the bound was the only criterion actually in force.
        double timeLeft = usableDistance / Math.max(egoSpeed.si, 1.0);
        double outOfTime = Math.min(1.0, Math.max(0.0, 1.0 - timeLeft / SPEED_GATE_TIME_HORIZON));
        double allowedDelta = MAX_SPEED_DELTA + outOfTime * (MAX_SPEED_DELTA_OUT_OF_TIME - MAX_SPEED_DELTA);

        boolean isSpeedSynchronized =
                effectiveTargetSpeedSI > 0.0 && egoSpeed.si >= achievableSpeedSI - allowedDelta;

        boolean isObstructedOnRamp = !canAccelerate
                && (isCongestedTarget || (effectiveTargetSpeedSI > 0.0 && speedDeficitSI <= MAX_OBSTRUCTED_DELTA));

        boolean isAtRampEnd = dist <= RAMP_FINAL_APPROACH_DISTANCE;

        return isAtRampEnd || isSpeedSynchronized || isObstructedOnRamp || isCongestedTarget;
    }


    /**
     * Acceleration for building up speed on the ramp, using the vehicle's physical capability rather than the
     * car-following comfort parameter, while leaving the car-following model its veto.
     * <p>
     * The states of this pattern accelerated through {@link MirovaCarFollowingUtil#approachTargetSpeed}, which
     * evaluates the car-following model and is therefore bounded by {@link ParameterTypes#A} - 1.25 m/s by default,
     * and the same value for cars and trucks. That is a comfort parameter for following a leader, not a limit on what
     * a vehicle can do when the road ahead is clear, and on a short acceleration lane it is the difference between
     * reaching the speed of the traffic being joined and not reaching it: measured over a full run, no vehicle ever
     * exceeded 1.25 m/s while {@code aMaxMirova} was set to 3.5.
     * </p>
     * <p>
     * The boost applies only while the car-following model is <i>not</i> the binding constraint. Once it returns less
     * than {@link #UNRESTRICTED_CAR_FOLLOWING_SHARE} of its free acceleration it is responding to the vehicle ahead,
     * and its value is returned unchanged - so a closing gap still brakes the ego and a rear-end conflict is not
     * traded away for merge speed. The boost also tapers with the fourth power of the speed ratio, the same shape the
     * IDM free term uses, so it fades out at the target speed instead of overshooting it.
     * </p>
     * @param vehicle MirovaTacticalPlanner; the ego vehicle
     * @param targetSpeed Speed; the speed being built up to
     * @param approachDistance Length; the distance over which the target speed is approached
     * @return Acceleration; the acceleration to command
     * @throws ParameterException if a parameter lookup fails
     * @throws GtuException if GTU state cannot be accessed
     * @throws NetworkException if a network query fails
     */
    private static Acceleration rampAcceleration(final MirovaTacticalPlanner vehicle, final Speed targetSpeed,
            final Length approachDistance) throws ParameterException, GtuException, NetworkException
    {
        EgoContext ego = vehicle.getContext(EgoContext.class);
        Acceleration aApproach = MirovaCarFollowingUtil.approachTargetSpeed(vehicle, approachDistance, targetSpeed);

        Speed vEgo = ego.getEgoSpeed();
        if (targetSpeed == null || targetSpeed.si <= 0.0 || vEgo.ge(targetSpeed))
        {
            return aApproach;
        }

        double aFree = vehicle.getParameters().getParameter(ParameterTypes.A).si;
        if (aFree <= 0.0 || ego.getCurrentCarFollowingAcceleration().si < UNRESTRICTED_CAR_FOLLOWING_SHARE * aFree)
        {
            return aApproach;
        }

        double ratio = Math.min(1.0, vEgo.si / targetSpeed.si);
        double boost = ego.getMaxPhysicalAcceleration().si * (1.0 - Math.pow(ratio, 4.0));
        return Acceleration.instantiateSI(Math.max(aApproach.si, boost));
    }

    /**
     * Constructs a new MandatoryLaneChangePattern.
     * @param vehicle the tactical planner associated with the ego vehicle
     */
    public MandatoryLaneChangePattern(final MirovaTacticalPlanner vehicle)
    {
        super(vehicle);
        // Start in the early anticipation state
        this.initialActionState = () -> new AnticipateMergeState(this);
        this.targetDirection = this.vehicle.getLaneChangeDesire().dominantDirection();
    }

    /**
     * Gets the lateral direction of the target lane.
     * @return the target direction
     */
    public LateralDirectionality getTargetDirection()
    {
        return this.vehicle.getLaneChangeDesire().dominantDirection();
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
            // Two independent questions, deliberately kept apart: whether the gap physically permits the manoeuvre,
            // and whether the ego is in a fit state to perform it. The second is a property of the vehicle, not of the
            // state it is in, so it is enforced here -- on every path to the execution -- rather than on a single edge.
            if (neigh.getIfLaneChangePossible(dir) && mayExecuteLaneChange(this.vehicle, dir))
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
         * Decides what to do about the merge this tick.
         * <p>
         * The branching mirrors the two questions a merging driver actually faces, and keeps them apart:
         * </p>
         * <ul>
         * <li><b>The gap is open.</b> Then nothing needs resolving, and the only remaining question is whether the ego
         * itself is ready -- which {@link #checkCommonTransitions} has already answered. If it is not ready it simply
         * keeps accelerating; there is no obstacle to react to.</li>
         * <li><b>The gap is not open.</b> Then the reason matters, and the vehicle is routed to the state that
         * addresses it: congested traffic, a physically overlapping vehicle, or a leader it has to align with.</li>
         * </ul>
         * <p>
         * Because this lives in the base class rather than in one state, any state may consult it. That is what makes
         * the preparatory states reachable at all times: previously they could only be entered from a single state, so
         * a vehicle that had not yet been released could not resolve a blocker even when one was plainly in the way.
         * </p>
         * <p>
         * The open-gap branch also skips the follower and reachability computations below, which are the expensive part
         * of this method and are pointless when nothing is blocking the manoeuvre.
         * </p>
         * @param neigh the neighbors context
         * @param dir the target direction
         * @return plan if a transition was taken, null to remain in the current state
         * @throws ParameterException if a parameter lookup fails
         * @throws OperationalPlanException if plan construction fails
         * @throws GtuException if GTU limits fail
         * @throws NetworkException if network queries fail
         */
        protected SimpleOperationalPlan checkMergeTransitions(final NeighborsContext neigh,
                final LateralDirectionality dir)
                throws ParameterException, OperationalPlanException, GtuException, NetworkException
        {
            SimpleOperationalPlan commonTransition = checkCommonTransitions(neigh, dir);
            if (commonTransition != null)
            {
                return commonTransition;
            }

            if (neigh.getIfLaneChangePossible(dir))
            {
                // The gap permits the manoeuvre; checkCommonTransitions already established that the ego is not yet
                // ready for it. There is nothing to resolve, so keep accelerating rather than paying for the
                // obstacle analysis below. The value is cached for the tick, so this test is free.
                return null;
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
            return null; // no resolvable obstacle found; wait for the gap to open
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
     *   <li>Transitions to <i>SynchroniseMergeSpeedState</i> as soon as the target lane becomes physically available and
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
                    Speed actualSpeed = getMergeReferenceSpeed(this.vehicle, this.pattern.getTargetDirection());

                    // The reference speed is low-pass filtered here, but not in next(): this is a control signal that
                    // is fed into an acceleration request every tick, so unfiltered jitter would translate directly
                    // into jerk. The transition criteria in next() are discrete decisions and use the raw value.
                    if (!isUsableReference(this.smoothedMergeSpeed))
                    {
                        this.smoothedMergeSpeed = actualSpeed;
                    }
                    else
                    {
                        this.smoothedMergeSpeed =
                                Speed.instantiateSI((1.0 - this.SPEED_SMOOTHING_FACTOR) * this.smoothedMergeSpeed.si
                                        + this.SPEED_SMOOTHING_FACTOR * actualSpeed.si);
                    }

                    // Floor: never decelerate below this on the acceleration lane, even towards a congested target
                    // lane -- the ego still has to cover the remaining ramp. Deck: obey the legal speed limit.
                    Speed targetSpeed = Speed.max(this.smoothedMergeSpeed, new Speed(20.0, SpeedUnit.KM_PER_HOUR));
                    targetSpeed = Speed.min(targetSpeed, speedLimit);
                    if (ego.getEgoSpeed().gt(targetSpeed))
                    {
                        Acceleration aToTarget = MirovaCarFollowingUtil.approachTargetSpeed(this.vehicle,
                                Length.instantiateSI(10.0), targetSpeed);
                        Acceleration egoDecelThreshold = ego.getEgoDecelerationThreshold(this.pattern.getTargetDirection());
                        aToTarget = Acceleration.max(aToTarget, egoDecelThreshold);

                        return new SimpleOperationalPlan(aToTarget, this.pattern.getPatternSpecificTimestep());
                    }

                    // Below the reference speed the ego should already be gaining speed on the ramp rather than
                    // waiting for the acceleration lane to begin, so the same boost applies here.
                    return new SimpleOperationalPlan(rampAcceleration(this.vehicle, targetSpeed,
                            Length.instantiateSI(10.0)), this.pattern.getPatternSpecificTimestep());
                }
            }

            return new SimpleOperationalPlan(aCf, this.pattern.getPatternSpecificTimestep());

        }

        @Override
        public SimpleOperationalPlan next() throws ParameterException, OperationalPlanException, NetworkException, GtuException
        {
            InfrastructureContext infra = this.vehicle.getContext(InfrastructureContext.class);
            if (!infra.getIfLaneAvailable(this.pattern.getTargetDirection()))
            {
                // The target lane is not yet physically alongside, so nothing can be perceived on it and no merge
                // conflict can exist yet. The only thing that can happen in this phase is the safety net.
                Length routeDistToEnd = infra.getRouteDistanceToLaneEnd();
                if (routeDistToEnd != null)
                {
                    Acceleration requiredStopAccel =
                            MirovaCarFollowingUtil.stop(this.vehicle, routeDistToEnd.minus(RAMP_END_BUFFER));
                    if (requiredStopAccel.si < -5.0)
                    {
                        return transitionTo(new EmergencyStopState(this.maneuverPattern));
                    }
                }
                return null;
            }

            // The acceleration lane has been reached. This is a change of phase, not a decision to merge: the ego now
            // accelerates towards the speed of the traffic it is joining and resolves any merge conflicts, while
            // permission to actually execute the change rests with mayExecuteLaneChange() on the execution path.
            return transitionTo(new SynchroniseMergeSpeedState(this.maneuverPattern));
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
     * ========================================================================================= STATE 1: SYNCHRONISE MERGE SPEED
     * =========================================================================================
     */

    /**
     * <b>State 2: Synchronisation Phase (SynchroniseMergeSpeedState)</b>
     * <p>
     * The phase in which the ego travels along the acceleration lane with the target lane alongside. Its task is to reach
     * the speed of the traffic it intends to join -- a real driver builds up speed on the ramp largely irrespective of
     * whether a gap happens to be available early, because merging far below the speed of the target lane forces the
     * traffic there to brake.
     * </p>
     * <p>
     * Deciding <i>whether</i> to merge is deliberately not this state's job. That question is answered by
     * {@link MandatoryLaneChangePattern#mayExecuteLaneChange} wherever the execution is actually triggered, so that the
     * criteria hold on every path to {@link ExecuteLaneChangeState} rather than on the one edge leading into this state.
     * </p>
     *
     * <h4>Functional Behavior:</h4>
     * <ul>
     *   <li>Accelerates towards the reference speed of the target lane, bounded by the legal speed limit.</li>
     *   <li>Delegates all transition decisions to {@link MandatoryLaneChangeState#checkMergeTransitions}, which routes to
     *       the state addressing whatever prevents the merge.</li>
     * </ul>
     *
     * <h4>Transitions:</h4>
     * <ul>
     *   <li>To <i>ExecuteLaneChangeState</i> when the gap permits the manoeuvre and the ego is ready to perform it.</li>
     *   <li>To <i>CongestedMergeState</i> when the traffic is too slow for speed synchronisation to be meaningful.</li>
     *   <li>To <i>MatchLeaderSpeedState</i> when the ego has to align with the target lane leader first.</li>
     *   <li>To <i>SolveParallelVehicleState</i> when a physically overlapping vehicle blocks access to the gap.</li>
     * </ul>
     */
    public static class SynchroniseMergeSpeedState extends MandatoryLaneChangeState
    {
        /** Time horizon in seconds to evaluate overtaking maneuvers. */
        private static final double TIME_HORIZON_S = 3.0;

        /**
         * Constructor for the evaluation state.
         * @param p the parent maneuver pattern
         */
        public SynchroniseMergeSpeedState(final ManeuverPattern p)
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
                    // Same reference speed as the anticipation phase; the cascade also guards against the NaN that
                    // TrafficPerception returns when the target lane is not (yet) a perceivable neighbouring lane.
                    Speed targetLaneSpeed = getMergeReferenceSpeed(this.vehicle, this.pattern.getTargetDirection());

                    Speed targetSpeed = Speed.min(targetLaneSpeed, speedLimit);
                    // This is the phase whose entire purpose is to build up merge speed, so it uses the physical
                    // capability rather than the car-following comfort acceleration; see rampAcceleration.
                    Acceleration aToTarget = rampAcceleration(this.vehicle, targetSpeed, Length.instantiateSI(10.0));
                    plan = new SimpleOperationalPlan(aToTarget, this.pattern.getPatternSpecificTimestep());

                }
            }
            if (plan == null)
            {
                plan = new SimpleOperationalPlan(aCf, this.pattern.getPatternSpecificTimestep());
            }

            setIndicators(plan, this.pattern.getTargetDirection());

            return plan;

        }

        @Override
        public SimpleOperationalPlan next() throws ParameterException, OperationalPlanException, NetworkException, GtuException
        {
            return checkMergeTransitions(this.vehicle.getContext(NeighborsContext.class),
                    this.pattern.getTargetDirection());
        }

        @Override
        public String toString()
        {
            return "SynchroniseMergeSpeedState";
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
     *   <li>Transitions to <i>SynchroniseMergeSpeedState</i> if the downstream gap becomes kinematically unreachable,
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

            SimpleOperationalPlan plan = new SimpleOperationalPlan(finalAcc, this.pattern.getPatternSpecificTimestep());

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
            // to SynchroniseMergeSpeedState so the vehicle can wait for an upstream gap instead.
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
                    return transitionTo(new SynchroniseMergeSpeedState(this.maneuverPattern));
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
     *   <li>Transitions to <i>SynchroniseMergeSpeedState</i> once the parallel block is resolved and target lane is clear.</li>
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
            SimpleOperationalPlan plan = new SimpleOperationalPlan(targetAcc, this.pattern.getPatternSpecificTimestep());

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
                return transitionTo(new SynchroniseMergeSpeedState(this.maneuverPattern));
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
     *   <li>Transitions to <i>SynchroniseMergeSpeedState</i> if speed recovers above 30 km/h, returning to normal evaluation.</li>
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
            SimpleOperationalPlan plan = new SimpleOperationalPlan(aCf, this.pattern.getPatternSpecificTimestep());
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
                return transitionTo(new SynchroniseMergeSpeedState(this.maneuverPattern));
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
            SimpleOperationalPlan plan = new SimpleOperationalPlan(finalAcc, this.pattern.getPatternSpecificTimestep());
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
     *   <li>Transitions to <i>SynchroniseMergeSpeedState</i> if traffic speed recovers above 30 km/h.</li>
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
            SimpleOperationalPlan plan = new SimpleOperationalPlan(finalAcc, this.pattern.getPatternSpecificTimestep());
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
                return transitionTo(new SynchroniseMergeSpeedState(this.maneuverPattern));
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
                            ego.getEgoDecelerationThreshold(dir))
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

            SimpleOperationalPlan plan = new SimpleOperationalPlan(finalAcc, this.pattern.getPatternSpecificTimestep());
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
                    new SimpleOperationalPlan(minAcc, this.pattern.getPatternSpecificTimestep(), this.direction);

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
