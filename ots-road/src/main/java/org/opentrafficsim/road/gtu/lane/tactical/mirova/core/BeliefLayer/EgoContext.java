package org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.djunits.unit.SpeedUnit;
import org.djunits.value.vdouble.scalar.Acceleration;
import org.djunits.value.vdouble.scalar.Duration;
import org.djunits.value.vdouble.scalar.Length;
import org.djunits.value.vdouble.scalar.Speed;
import org.opentrafficsim.road.gtu.lane.tactical.following.CarFollowingModel;
import org.opentrafficsim.base.parameters.ParameterException;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.base.parameters.Parameters;
import org.opentrafficsim.core.gtu.GtuException;
import org.opentrafficsim.core.gtu.perception.EgoPerception;
import org.opentrafficsim.core.network.LateralDirectionality;
import org.opentrafficsim.core.network.NetworkException;
import org.opentrafficsim.road.gtu.lane.perception.headway.HeadwayGtu;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.MirovaTacticalPlanner;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ReactiveLayer.DynamicHeadwayProvider;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ReactiveLayer.LongitudinalControl;

/**
 * Context category representing ego-vehicle-related state variables.
 * <p>
 * Forms a central part of <b>Layer 1 (Perception & Context)</b> in the MiRoVA architecture. Provides direct access to low-level
 * vehicle states such as speed, accelerations, and deceleration thresholds, which are frequently required by tactical and
 * longitudinal control logic.
 * </p>
 * <p>
 * The values are lazily updated once per simulation tick and cached within the {@link VehicleContextManager} to optimize
 * performance and ensure intra-tick consistency. Furthermore, it tracks ID-based relaxation states for specific leader vehicles
 * and provides a highly efficient single-tick cache for longitudinal acceleration evaluations.
 * </p>
 * <p>
 * Copyright (c) 2025 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class EgoContext extends ContextCategory implements UpdatableContext
{

    /** Cache key for ego speed. */
    public static final String EGO_SPEED = "egoSpeed";

    /** Cache key for current car-following acceleration. */
    public static final String CURRENT_CF_ACCELERATION = "currentCarFollowingAcceleration";

    /** Cache key for current desired speed. */
    public static final String CURRENT_DESIRED_SPEED = "currentDesiredSpeed";

    /** Cache key for desired front headway in current lane. */
    public static final String DESIRED_FRONT_HEADWAY_CURRENT = "desiredFrontHeadwayCurrent";

    /** Cache key for desired front headway in left lane. */
    public static final String DESIRED_FRONT_HEADWAY_LEFT = "desiredFrontHeadwayLeft";

    /** Cache key for desired front headway in right lane. */
    public static final String DESIRED_FRONT_HEADWAY_RIGHT = "desiredFrontHeadwayRight";

    /** Cache key for desired rear headway in left lane. */
    public static final String DESIRED_REAR_HEADWAY_LEFT = "desiredRearHeadwayLeft";

    /** Cache key for desired rear headway in right lane. */
    public static final String DESIRED_REAR_HEADWAY_RIGHT = "desiredRearHeadwayRight";

    /** Cache key for desired rear headway in current lane. */
    public static final String DESIRED_REAR_HEADWAY_CURRENT = "desiredRearHeadwayCurrent";

    /** Cache key for ego deceleration threshold (left). */
    public static final String EGO_DECELERATION_THRESHOLD_LEFT = "egoDecelerationThresholdLeft";

    /** Cache key for ego deceleration threshold (right). */
    public static final String EGO_DECELERATION_THRESHOLD_RIGHT = "egoDecelerationThresholdRight";

    /** Cache key for follower deceleration threshold (left). */
    public static final String FOLLOWER_DECELERATION_THRESHOLD_LEFT = "followerDecelerationThresholdLeft";

    /** Cache key for follower deceleration threshold (right). */
    public static final String FOLLOWER_DECELERATION_THRESHOLD_RIGHT = "followerDecelerationThresholdRight";

    /** Cache key for maximum physical acceleration. */
    public static final String MAX_PHYSICAL_ACCELERATION = "maxPhysicalAcceleration";

    // =========================================================================================
    // FIELDS: RELAXATION & CACHING
    // =========================================================================================

    /**
     * * Map of active relaxation states, tracked by the GTU ID of the respective leader. Handles the Keane and Gao (2021)
     * 2-parameter relaxation phenomenon.
     */
    private final Map<String, RelaxationState> activeRelaxations = new HashMap<>();

    /**
     * * Temporary cache for longitudinal accelerations evaluated during the current time step. Key is the GTU ID of the leader.
     * This cache is cleared at the start of every perception update.
     */
    private final Map<String, Acceleration> tickAccelerationCache = new HashMap<>();

    /**
     * Extra headway the ego holds to its own leader while a merge is pending, in seconds of headway.
     * <p>
     * The mirror of the Keane &amp; Gao relaxation this class already carries. That one lets a vehicle tolerate a
     * <i>smaller</i> gap than equilibrium after a cut-in; this one lets it tolerate a <i>larger</i> one while it is
     * cooperating, so that a gap opened for a merger is not immediately closed again by the car-following model. Both
     * decay exponentially, for the same reason: a hard end returns the vehicle to its equilibrium gap in one step.
     * </p>
     * <p>
     * Zero disables it, which is the default and reproduces the behaviour in which a cooperating vehicle instead
     * follows the merge candidate as though it were its own leader - and thereby ends at the candidate's speed.
     * </p>
     */
    private Duration cooperativeHeadwayReserve = Duration.ZERO;

    /** Simulation time at which the reserve was last refreshed. */
    private Duration reserveRefreshedAt = null;

    /** Decay constant of the reserve. */
    private Duration reserveTau = Duration.instantiateSI(5.0);

    /** Follower speed below which a rear headway constraint is treated as effectively absent. */
    private static final Speed SLOW_FOLLOWER_SPEED = new Speed(15.0, SpeedUnit.KM_PER_HOUR);

    /** Rear headway assumed behind a follower that is barely moving. */
    private static final Length SLOW_FOLLOWER_REAR_HEADWAY = Length.instantiateSI(1.5);

    /** Leader deceleration above which a cut-in is still considered safe enough to relax against. */
    private static final Acceleration RELAXATION_MIN_LEADER_ACCELERATION = Acceleration.instantiateSI(-1.0);

    /** Leader speed below which relaxation against a new leader is not applied. */
    private static final Speed RELAXATION_MIN_LEADER_SPEED = new Speed(10.0, SpeedUnit.KM_PER_HOUR);

    /** Vehicle length assumed when the GTU cannot be queried. */
    private static final Length FALLBACK_VEHICLE_LENGTH = Length.instantiateSI(4.5);

    /**
     * Refreshes the cooperative headway reserve, to be called while cooperation is active.
     * @param reserve Duration; the extra headway to hold, in seconds
     * @param tau Duration; the decay constant applied once refreshing stops
     */
    public void reserveHeadwayForCooperation(final Duration reserve, final Duration tau)
    {
        this.cooperativeHeadwayReserve = reserve;
        this.reserveTau = tau;
        this.reserveRefreshedAt = this.vehicle.getGtu().getSimulator().getSimulatorTime();
        this.tickAccelerationCache.clear();
    }

    /**
     * Returns the extra distance the ego currently holds to its own leader, decayed since the last refresh.
     * @return Length; the reserve in metres, zero when none is active
     */
    public Length getCooperativeGapReserve()
    {
        if (this.reserveRefreshedAt == null || this.cooperativeHeadwayReserve.si <= 0.0)
        {
            return Length.ZERO;
        }
        Duration now = this.vehicle.getGtu().getSimulator().getSimulatorTime();
        double age = now.si - this.reserveRefreshedAt.si;
        double factor = Math.exp(-age / Math.max(this.reserveTau.si, 1e-6));
        if (factor < 0.02)
        {
            return Length.ZERO;
        }
        return Length.instantiateSI(this.cooperativeHeadwayReserve.si * factor * getEgoSpeed().si);
    }

    // ----------------------------------------------------------------------
    // Construction
    // ----------------------------------------------------------------------

    /**
     * Constructs a new {@code EgoContext}.
     * @param vehicle the ego vehicle associated with this context
     */
    public EgoContext(final MirovaTacticalPlanner vehicle)
    {
        super("Ego", vehicle);
    }

    // =========================================================================================
    // METHODS: SINGLE-TICK ACCELERATION CACHE
    // =========================================================================================

    /**
     * Retrieves a cached acceleration for a specific leader ID evaluated in the current tick.
     * @param leaderId the GTU ID of the leader
     * @return the cached acceleration, or null if not yet evaluated in this tick
     */
    public Acceleration getCachedAcceleration(final String leaderId)
    {
        return this.tickAccelerationCache.get(leaderId);
    }

    /**
     * Caches a computed acceleration for a specific leader ID for the duration of the current tick.
     * @param leaderId the GTU ID of the leader
     * @param acceleration the computed acceleration
     */
    public void cacheAcceleration(final String leaderId, final Acceleration acceleration)
    {
        if (leaderId != null && acceleration != null)
        {
            this.tickAccelerationCache.put(leaderId, acceleration);
        }
    }

    /**
     * Provides access to the entire tick acceleration cache, which can be useful for debugging or advanced maneuver logic that
     * needs to
     * @return the current tick acceleration cache map
     */
    public Map<String, Acceleration> getCurrentTickAccelerationCache()
    {
        return this.tickAccelerationCache;
    }

    /**
     * Returns the map of active relaxation states for all leaders. This can be used by maneuver patterns or tactical logic to
     * @return the map of active relaxations, keyed by leader GTU ID
     */
    public Map<String, RelaxationState> getActiveRelaxations()
    {
        return this.activeRelaxations;
    }

    // =========================================================================================
    // METHODS: RELAXATION MANAGEMENT
    // =========================================================================================

    /**
     * Evaluates a new cut-in situation and triggers the 2-parameter relaxation if the new leader violates the dynamic desired
     * headway or has a significant speed difference.
     * <p>
     * This method is typically called by the {@code NeighborsContext} when a change in the leader ID is detected (edge
     * trigger). It leverages the {@link DynamicHeadwayProvider} to accurately assess the required spatial gap.
     * </p>
     * @param newLeader HeadwayGtu; the new headway object that just cut in
     * @param oldLeaderSpeed Speed; the speed of the previous leader at the time of the cut-in (can be null)
     * @throws ParameterException if a required parameter is missing
     * @throws GtuException if GTU state cannot be accessed
     */
    public void evaluateAndTriggerRelaxation(final HeadwayGtu newLeader, final Speed oldLeaderSpeed)
            throws ParameterException, GtuException
    {
        if (newLeader == null)
        {
            return;
        }

        Parameters params = this.vehicle.getParameters();
        CarFollowingModel cfModel = this.vehicle.getCarFollowingModel();
        Speed egoSpeed = this.getEgoSpeed();

        // 1. Compute static equilibrium headway
        Length targetHeadway = cfModel.desiredHeadway(params, egoSpeed);

        // 3. Calculate spatial deficit (gamma_s)
        Length gammaS = Length.ZERO;
        if (newLeader.getDistance().lt(targetHeadway))
        {
            gammaS = targetHeadway.minus(newLeader.getDistance());
        }

        // 4. Calculate speed deficit (gamma_v)
        Speed gammaV = oldLeaderSpeed != null ? oldLeaderSpeed.minus(newLeader.getSpeed()) : Speed.ZERO;

        // 5. Trigger relaxation if there is ANY deficit (space OR speed)
        // Speed relaxation is dangerous: if there is a speed deficit, we target a lower headway instead of relaxing the speed
        // buffer, which would cause unwanted crashes.
        Duration tauSpace = this.vehicle.getParams().relaxationTauSpaceScalar;
        Duration tauSpeed = this.vehicle.getParams().relaxationTauSpeedScalar;
        Double safetyDistanceReductionFactor = (this.vehicle.getParams().safetyDistanceReductionFactorLaneChange);
        if (gammaV.si > 0.0)
        {
            triggerRelaxation(newLeader.getId(), Length.max(targetHeadway.times(safetyDistanceReductionFactor), gammaS),
                    Speed.ZERO, tauSpace, tauSpeed, false);
        }
        else if (gammaS.si > 0.0)
        {
            triggerRelaxation(newLeader.getId(), gammaS, Speed.ZERO, tauSpace, tauSpeed, false);
        }

    }

    /**
     * Explicitly registers a relaxation state for a specific target vehicle without overwriting an active state.
     * <p>
     * This is a legacy/convenience wrapper that defaults to {@code forceOverwrite = false}.
     * </p>
     * @param leaderId String; the ID of the target leader GTU
     * @param initialSpaceDeficit Length; the initial space headway deficit [m]
     * @param initialSpeedDeficit Speed; the speed difference (oldLeaderSpeed - newLeaderSpeed) [m/s]
     * @param tauSpace Duration; the spatial relaxation time constant [s]
     * @param tauSpeed Duration; the speed relaxation time constant [s]
     */
    public void triggerRelaxation(final String leaderId, final Length initialSpaceDeficit, final Speed initialSpeedDeficit,
            final Duration tauSpace, final Duration tauSpeed)
    {
        triggerRelaxation(leaderId, initialSpaceDeficit, initialSpeedDeficit, tauSpace, tauSpeed, false);
    }

    /**
     * We trigger a relaxation with a reduced safety distance (instead of a speed buffer) for proactive lane changes, where we
     * want to safely accept closer gaps before the physical lane change starts. This method can be called by maneuver patterns
     * when they iniate a proactive lane change and want to preemptively relax towards the target leader.
     * @param leader the target leader GTU to relax towards
     * @throws ParameterException if required relaxation parameters are missing
     */
    public void triggerRelaxationWithReducedSafetyDistance(HeadwayGtu leader) throws ParameterException
    {
        if (leader == null)
        {
            return;
        }

        Parameters params = this.vehicle.getParameters();
        CarFollowingModel cfModel = this.vehicle.getCarFollowingModel();
        Speed egoSpeed = this.getEgoSpeed();

        Length targetHeadway = cfModel.desiredHeadway(params, egoSpeed);

        Double safetyDistanceReductionFactor = (this.vehicle.getParams().safetyDistanceReductionFactorLaneChange);
        Length gammaS = Length.ZERO;
        if (leader.getDistance().lt(targetHeadway))
        {
            gammaS = targetHeadway.minus(leader.getDistance());
        }
        Length reducedHeadway = Length.max(targetHeadway.times(safetyDistanceReductionFactor), gammaS);

        Duration tauSpace = this.vehicle.getParams().relaxationTauSpaceScalar;
        Duration tauSpeed = this.vehicle.getParams().relaxationTauSpeedScalar;

        triggerRelaxation(leader.getId(), reducedHeadway, Speed.ZERO, tauSpace, tauSpeed);
    }

    /**
     * Explicitly registers or updates a relaxation state for a specific target vehicle.
     * <p>
     * If {@code forceOverwrite} is true, an ongoing relaxation is reset. This freezes the buffer at 100% while the maneuver is
     * being prepared but not yet physically executed.
     * </p>
     * @param leaderId String; the ID of the target leader GTU
     * @param initialSpaceDeficit Length; the initial space headway deficit [m]
     * @param initialSpeedDeficit Speed; the speed difference (oldLeaderSpeed - newLeaderSpeed) [m/s]
     * @param tauSpace Duration; the spatial relaxation time constant [s]
     * @param tauSpeed Duration; the speed relaxation time constant [s]
     * @param forceOverwrite boolean; if true, any active relaxation state for this leader is overwritten
     */
    public void triggerRelaxation(final String leaderId, final Length initialSpaceDeficit, final Speed initialSpeedDeficit,
            final Duration tauSpace, final Duration tauSpeed, final boolean forceOverwrite)
    {
        if (forceOverwrite || !this.activeRelaxations.containsKey(leaderId))
        {
            // Verify there is actually a deficit to relax
            if ((initialSpaceDeficit != null && initialSpaceDeficit.si > 0.0)
                    || (initialSpeedDeficit != null && initialSpeedDeficit.si > 0.0))
            {
                Duration now = this.vehicle.getGtu().getSimulator().getSimulatorTime();
                this.activeRelaxations.put(leaderId,
                        new RelaxationState(now, initialSpaceDeficit, initialSpeedDeficit, tauSpace, tauSpeed));
                if (RelaxationDiagnostics.ENABLED)
                {
                    RelaxationDiagnostics.created();
                }

                // ARCHITECTURE-UPDATE: Targeted cache invalidation ensures the IDM immediately recalculates
                this.tickAccelerationCache.remove(leaderId);
            }
        }
    }

    /**
     * Proactively calculates deficits and triggers relaxation for a specific target leader.
     * <p>
     * This method is designed for maneuver patterns to safely accept gaps on adjacent lanes. It leverages the
     * {@link DynamicHeadwayProvider} and deliberately <b>overwrites</b> existing states to keep the buffer fresh while waiting
     * for the physical lane change to start.
     * </p>
     * @param targetLeader HeadwayGtu; the target leader GTU to relax towards
     * @throws ParameterException if required relaxation parameters are missing
     */
    public void triggerRelaxation(final HeadwayGtu targetLeader) throws ParameterException
    {
        // NOTE: We do NOT check !this.activeRelaxations.containsKey anymore, because we want to overwrite!
        if (targetLeader == null)
        {
            return;
        }

        if (targetLeader.getAcceleration().ge(RELAXATION_MIN_LEADER_ACCELERATION)
                && targetLeader.getSpeed().si >= RELAXATION_MIN_LEADER_SPEED.si)
        {
            // Only trigger proactive relaxation if the target leader is not braking hard and has a reasonable speed.
            // This prevents dangerous relaxation

            Parameters params = this.vehicle.getParameters();
            CarFollowingModel cfModel = this.vehicle.getCarFollowingModel();
            Speed egoSpeed = this.getEgoSpeed();

            Length targetHeadway = cfModel.desiredHeadway(params, egoSpeed);

            Length spaceDeficit = Length.ZERO;
            if (targetLeader.getDistance().lt(targetHeadway))
            {
                spaceDeficit = targetHeadway.minus(targetLeader.getDistance());
            }

            // For proactive lane changes, speed deficit is Ego Speed minus Target Leader Speed
            Speed speedDeficit = egoSpeed.minus(targetLeader.getSpeed());

            Duration tauSpace = this.vehicle.getParams().relaxationTauSpaceScalar;
            Duration tauSpeed = this.vehicle.getParams().relaxationTauSpeedScalar;
            Double safetyDistanceReductionFactor =
                    (this.vehicle.getParams().safetyDistanceReductionFactorLaneChange);
            if (speedDeficit.si > 0.0)
            {
                triggerRelaxation(targetLeader.getId(),
                        Length.max(targetHeadway.times(safetyDistanceReductionFactor), spaceDeficit), Speed.ZERO, tauSpace,
                        tauSpeed, false);
            }
            else if (spaceDeficit.si > 0.0)
            {
                triggerRelaxation(targetLeader.getId(), spaceDeficit, Speed.ZERO, tauSpace, tauSpeed, false);
            }
            // if (spaceDeficit.si > 0.0 || speedDeficit.si > 0.0)
            // {
            // Duration tauSpace = this.vehicle.getParams().relaxationTauSpaceScalar;
            // Duration tauSpeed = this.vehicle.getParams().relaxationTauSpeedScalar;

            // // Force overwrite = true! Buffer will not decay until the trigger stops (i.e. physical LC starts).
            // triggerRelaxation(targetLeader.getId(), spaceDeficit, speedDeficit, tauSpace, tauSpeed, false);
            // }
        }
    }

    /**
     * Retrieves the active relaxation state for a specific leader.
     * @param leaderId String; the ID of the leader GTU
     * @return RelaxationState; the active relaxation state, or null if no relaxation is active for this leader
     */
    public RelaxationState getActiveRelaxationForLeader(final String leaderId)
    {
        return this.activeRelaxations.get(leaderId);
    }

    /**
     * Clears the active relaxation state for a specific leader.
     * @param leaderId String; the ID of the leader GTU
     */
    public void clearRelaxationForLeader(final String leaderId)
    {
        this.activeRelaxations.remove(leaderId);
    }

    // ----------------------------------------------------------------------
    // Lazy Accessors
    // ----------------------------------------------------------------------

    /**
     * Returns the current ego-vehicle speed as perceived in the last simulation tick.
     * <p>
     * This method uses lazy evaluation: the speed is only retrieved once per tick from {@link EgoPerception} and then cached.
     * </p>
     * @return current ego speed
     */
    public Speed getEgoSpeed()
    {
        Speed cached = getCachedValue(EGO_SPEED, Speed.class);
        if (cached != null)
        {
            return cached;
        }

        Speed result = computeEgoSpeed();
        cacheValue(EGO_SPEED, result, true);
        return result;
    }

    /**
     * Returns the current baseline car-following acceleration of the ego vehicle. Uses lazy evaluation to cache the result per
     * tick.
     * @return current car-following acceleration
     * @throws ParameterException if a required parameter is missing
     * @throws GtuException if GTU state cannot be accessed
     * @throws NetworkException if network state cannot be accessed
     */
    public Acceleration getCurrentCarFollowingAcceleration() throws ParameterException, GtuException, NetworkException
    {
        Acceleration cached = getCachedValue(CURRENT_CF_ACCELERATION, Acceleration.class);
        if (cached != null)
        {
            return cached;
        }

        Acceleration result = LongitudinalControl.computeAcceleration(this.vehicle);
        cacheValue(CURRENT_CF_ACCELERATION, result, true);
        return result;
    }

    /**
     * Returns the currently desired speed of the ego vehicle. Uses lazy evaluation to cache the result per tick.
     * @return the current desired speed
     * @throws ParameterException if parameter resolution fails
     * @throws GtuException if GTU state is invalid
     * @throws NetworkException if network state is invalid
     */
    public Speed getCurrentDesiredSpeed() throws ParameterException, GtuException, NetworkException
    {
        Speed cached = getCachedValue(CURRENT_DESIRED_SPEED, Speed.class);
        if (cached != null)
        {
            return cached;
        }
        Speed result = this.vehicle.getGtu().getDesiredSpeed();
        cacheValue(CURRENT_DESIRED_SPEED, result, true);
        return result;
    }

    /**
     * Computes and returns the deceleration threshold for the ego vehicle for a specific lane change direction.
     * @param dir the lateral direction to consider (LEFT or RIGHT)
     * @return the acceptable deceleration threshold for the ego vehicle
     * @throws ParameterException if threshold parameters are missing
     */
    public Acceleration getEgoDecelerationThreshold(final LateralDirectionality dir) throws ParameterException
    {
        String key = (dir == LateralDirectionality.LEFT) ? EGO_DECELERATION_THRESHOLD_LEFT : EGO_DECELERATION_THRESHOLD_RIGHT;

        Acceleration cached = getCachedValue(key, Acceleration.class);
        if (cached != null)
        {
            return cached;
        }

        Acceleration result = computeEgoDecelerationThreshold(dir);
        cacheValue(key, result, true);
        return result;
    }

    /**
     * Computes and returns the expected deceleration threshold for the follower in a target lane.
     * @param dir the lateral direction to consider (LEFT or RIGHT)
     * @return the expected deceleration threshold for the follower
     * @throws ParameterException if threshold parameters are missing
     */
    public Acceleration getFollowerDecelerationThreshold(final LateralDirectionality dir) throws ParameterException
    {
        String key = (dir == LateralDirectionality.LEFT) ? FOLLOWER_DECELERATION_THRESHOLD_LEFT
                : FOLLOWER_DECELERATION_THRESHOLD_RIGHT;

        Acceleration cached = getCachedValue(key, Acceleration.class);
        if (cached != null)
        {
            return cached;
        }

        Acceleration result = computeFollowerDecelerationThreshold(dir);
        cacheValue(key, result, true);
        return result;
    }

    /**
     * Returns the physical length of the ego vehicle.
     * @return Length; the ego vehicle's length, or 4.5 m when the GTU cannot be queried
     */
    public Length getEgoLength()
    {
        try
        {
            return this.vehicle.getGtu().getLength();
        }
        catch (Exception e)
        {
            return FALLBACK_VEHICLE_LENGTH;
        }
    }

    /**
     * Calculates the desired front headway distance for a given direction.
     * @param dir the lateral direction (NONE for current lane)
     * @return the desired front headway distance
     */
    public Length getDesiredFrontHeadway(final LateralDirectionality dir)
    {
        String key;
        if (dir == LateralDirectionality.LEFT)
        {
            key = DESIRED_FRONT_HEADWAY_LEFT;
        }
        else if (dir == LateralDirectionality.RIGHT)
        {
            key = DESIRED_FRONT_HEADWAY_RIGHT;
        }
        else
        {
            key = DESIRED_FRONT_HEADWAY_CURRENT;
        }

        Length cached = getCachedValue(key, Length.class);
        if (cached != null)
        {
            return cached;
        }

        Length result = computeDesiredFrontHeadway();
        cacheValue(key, result, true);
        return result;
    }

    /**
     * Calculates the desired rear headway distance for a given direction based on the follower.
     * @param dir the lateral direction (NONE for current lane)
     * @return the desired rear headway distance
     */
    public Length getDesiredRearHeadway(final LateralDirectionality dir)
    {
        String key;
        if (dir == LateralDirectionality.LEFT)
        {
            key = DESIRED_REAR_HEADWAY_LEFT;
        }
        else if (dir == LateralDirectionality.RIGHT)
        {
            key = DESIRED_REAR_HEADWAY_RIGHT;
        }
        else
        {
            key = DESIRED_REAR_HEADWAY_CURRENT;
        }

        Length cached = getCachedValue(key, Length.class);
        if (cached != null)
        {
            return cached;
        }

        Length result = computeDesiredRearHeadway(dir);
        cacheValue(key, result, true);
        return result;
    }

    /**
     * Calculates the maximum physical acceleration currently possible based on the vehicle's speed.
     * <p>
     * Uses lazy evaluation to cache the result per tick. The calculation is based on an empirical piece-wise linear function
     * representing a typical combustion engine vehicle's performance.
     * </p>
     * @return Acceleration; the dynamically calculated maximum physical acceleration
     */
    public Acceleration getMaxPhysicalAcceleration()
    {
        Acceleration cached = getCachedValue(MAX_PHYSICAL_ACCELERATION, Acceleration.class);
        if (cached != null)
        {
            return cached;
        }

        Acceleration result = computeMaxPhysicalAcceleration();
        cacheValue(MAX_PHYSICAL_ACCELERATION, result, true);
        return result;
    }

    /**
     * Calculates the maximum physical acceleration at an <em>arbitrary</em> speed (not necessarily the current ego speed).
     * <p>
     * This method is <b>not cached</b> and should be used only for look-ahead calculations such as kinematic reachability
     * checks in lane-change state machines. For the current ego speed use {@link #getMaxPhysicalAcceleration()} instead.
     * </p>
     * @param speed Speed; the speed at which to evaluate the acceleration capability
     * @return Acceleration; the estimated maximum physical acceleration at the given speed
     */
    public Acceleration getMaxPhysicalAccelerationAt(final org.djunits.value.vdouble.scalar.Speed speed)
    {
        double speedKmh = speed.getInUnit(SpeedUnit.KM_PER_HOUR);
        // The 3.5 m/s2 fallback that stood here is gone with the lookup that could fail: the snapshot resolved A_MAX
        // when the vehicle was built, so this can no longer silently substitute a value the configuration did not ask for.
        double aMaxScaleSI = this.vehicle.getParams().aMaxSi;
        return Acceleration.instantiateSI(computeMaxPhysicalAccelerationAt(speedKmh, aMaxScaleSI));
    }
    // ----------------------------------------------------------------------
    // Safe computation wrappers
    // ----------------------------------------------------------------------

    /**
     * Safely computes the ego-vehicle speed from {@link EgoPerception}. Returns zero speed in case of missing perception data
     * or errors.
     * @return ego speed or {@link Speed#ZERO} on error
     */
    private Speed computeEgoSpeed()
    {
        try
        {
            return this.vehicle.getPerception().getPerceptionCategory(EgoPerception.class).getSpeed();
        }
        catch (Exception e)
        {
            return Speed.ZERO;
        }
    }

    /**
     * Computes the dynamically desired front headway based on current speed and relaxed headway.
     * @return the computed desired front headway distance
     */
    private Length computeDesiredFrontHeadway()
    {
        Length desiredFrontHeadway = Length.NaN;
        try
        {
            // v * T + s0, computed in SI so the intermediate Length from times() is never built.
            desiredFrontHeadway = Length.instantiateSI(
                    getEgoSpeed().si * this.vehicle.getParameters().getParameter(ParameterTypes.T).si
                            + this.vehicle.getParams().s0Si);
        }
        catch (ParameterException exception)
        {
            exception.printStackTrace();
        }
        return desiredFrontHeadway;
    }

    /**
     * Computes the dynamically desired rear headway based on the target lane follower's speed. * @param dir the lateral
     * direction to inspect
     * @return the computed desired rear headway distance
     */
    private Length computeDesiredRearHeadway(final LateralDirectionality dir)
    {
        Length desiredRearHeadway = Length.NaN;
        try
        {
            HeadwayGtu follower =
                    this.vehicle.getContextManager().getCategory("Neighbors", NeighborsContext.class).getFollower(dir);
            if (follower == null)
            {
                // No follower, so no rear headway constraint
                desiredRearHeadway = Length.NEGATIVE_INFINITY;
            }
            else
            {
                Speed followerSpeed = follower.getSpeed();
                if (followerSpeed.si < SLOW_FOLLOWER_SPEED.si)
                {
                    // If follower is very slow, assume it can be very close without safety issues
                    desiredRearHeadway = SLOW_FOLLOWER_REAR_HEADWAY;
                }
                else
                {
                    desiredRearHeadway = Length.instantiateSI(
                            followerSpeed.si * this.vehicle.getParameters().getParameter(ParameterTypes.T).si
                                    + this.vehicle.getParams().s0Si);
                }
            }
        }
        catch (ParameterException exception)
        {
            exception.printStackTrace();
        }
        return desiredRearHeadway;
    }

    /**
     * Interpolates the acceptable follower deceleration threshold based on current lane change desire.
     * <p>
     * The calculation returns the minimum threshold if the current desire is below the mandatory lane change threshold
     * ({@code DMAND}), the maximum threshold if the desire exceeds {@code 1.0}, and linearly interpolates between the two for
     * intermediate desire values. Clamping is applied strictly to the interpolation fraction to ensure mathematical robustness
     * with negative acceleration values.
     * </p>
     * * @param dir LateralDirectionality; the lateral direction for which the desire is evaluated
     * @return Acceleration; the computed acceleration threshold for the following vehicle (typically a negative value)
     * @throws ParameterException if a required parameter is missing in the vehicle's parameter set
     */
    private Acceleration computeFollowerDecelerationThreshold(final LateralDirectionality dir) throws ParameterException
    {
        Acceleration minThreshold =
                this.vehicle.getParams().minFollowerDecelerationThresholdScalar;
        Acceleration maxThreshold =
                this.vehicle.getParams().maxFollowerDecelerationThresholdScalar;

        // Use primitive double to avoid unnecessary autoboxing/unboxing overhead in the simulation loop
        double currentDirectionDesire = this.vehicle.getLaneChangeDesire().getDirectionalDesire(dir);
        double mandatoryDesireThreshold = this.vehicle.getParams().dMand;

        // Calculate the interpolation fraction based on current desire
        double fraction = (currentDirectionDesire - mandatoryDesireThreshold) / (1.0 - mandatoryDesireThreshold);

        // Clamp the fraction strictly between 0.0 (min limit) and 1.0 (max limit)
        fraction = Math.max(0.0, Math.min(1.0, fraction));

        // Interpolate using the clamped fraction
        double currentThresholdSi = minThreshold.si + fraction * (maxThreshold.si - minThreshold.si);

        return Acceleration.instantiateSI(currentThresholdSi);
    }

    /**
     * Interpolates the acceptable ego deceleration threshold based on current lane change desire.
     * <p>
     * The calculation returns the minimum threshold if the current desire is below the mandatory lane change threshold
     * ({@code DMAND}), the maximum threshold if the desire exceeds {@code 1.0}, and linearly interpolates between the two for
     * intermediate desire values.
     * </p>
     * * @param dir LateralDirectionality; the lateral direction for which the desire is evaluated
     * @return Acceleration; the computed acceleration threshold (typically a negative value for deceleration)
     * @throws ParameterException if a required parameter is missing in the vehicle's parameter set
     */
    private Acceleration computeEgoDecelerationThreshold(final LateralDirectionality dir) throws ParameterException
    {
        Acceleration minThreshold = this.vehicle.getParams().minEgoDecelerationThresholdScalar;
        Acceleration maxThreshold = this.vehicle.getParams().maxEgoDecelerationThresholdScalar;
        double currentDirectionDesire = this.vehicle.getLaneChangeDesire().getDirectionalDesire(dir);
        double mandatoryDesireThreshold = this.vehicle.getParams().dMand;

        // Calculate the interpolation fraction based on current desire
        double fraction = (currentDirectionDesire - mandatoryDesireThreshold) / (1.0 - mandatoryDesireThreshold);

        // Clamp the fraction to strictly bind it between 0.0 (min limit) and 1.0 (max limit)
        // This makes the logic mathematically robust, even if maxThreshold is numerically smaller than minThreshold (negative
        // accelerations)
        fraction = Math.max(0.0, Math.min(1.0, fraction));

        // Interpolate using the clamped fraction
        double currentThresholdSi = minThreshold.si + fraction * (maxThreshold.si - minThreshold.si);

        return Acceleration.instantiateSI(currentThresholdSi);
    }

    // ----------------------------------------------------------------------
    // Update handling
    // ----------------------------------------------------------------------

    /**
     * Marks the cached values as valid for the current simulation tick, clears the single-tick acceleration cache, and performs
     * housekeeping on active relaxation states.
     * @param vehicle the ego vehicle executing the update
     */
    @Override
    public void updateFromPerception(final MirovaTacticalPlanner vehicle)
    {
        // 1. CRITICAL: Clear the tick cache. Acceleration values from the previous tick are invalid!
        this.tickAccelerationCache.clear();

        // 2. Housekeeping for active relaxations
        try
        {
            Duration now = vehicle.getGtu().getSimulator().getSimulatorTime();
            Iterator<Map.Entry<String, RelaxationState>> iterator = this.activeRelaxations.entrySet().iterator();

            while (iterator.hasNext())
            {
                RelaxationState state = iterator.next().getValue();

                // If both the space buffer (< 10cm) and speed buffer (< 0.1 m/s) have decayed,
                // the relaxation process is finished. We remove it to free memory.
                if (state.getVirtualSpaceBuffer(now).si < 0.1 && Math.abs(state.getVirtualSpeedBuffer(now).si) < 0.1)
                {
                    if (RelaxationDiagnostics.ENABLED)
                    {
                        RelaxationDiagnostics.expired(now.si - state.getStartTime().si);
                    }
                    iterator.remove();
                }
            }
        }
        catch (Exception e)
        {
            // Failsafe if simulator time is temporarily unavailable
        }

        // 3. Mark the context properties cache as valid (Lazy evaluation trigger)
        markCacheValid();
    }

    /**
     * Calculates the positive acceleration scaling factor in [aRelaxDamping, 1.0]
     * based on the active headway relaxation state for a specific leader GTU.
     * @param leaderId String; the GTU ID of the leader
     * @return double; acceleration scaling factor in [aRelaxDamping, 1.0]
     */
    public double getRelaxationAccelerationFactor(final String leaderId)
    {
        if (leaderId == null || !this.activeRelaxations.containsKey(leaderId))
        {
            return 1.0;
        }

        RelaxationState state = this.activeRelaxations.get(leaderId);
        if (state == null)
        {
            return 1.0;
        }

        try
        {
            Duration now = this.vehicle.getGtu().getSimulator().getSimulatorTime();
            Length spaceBuffer = state.getVirtualSpaceBuffer(now);
            Length initialDeficit = state.getInitialSpaceDeficit();

            if (spaceBuffer == null || initialDeficit == null || spaceBuffer.si <= 0.0 || initialDeficit.si <= 0.0)
            {
                return 1.0;
            }

            boolean enabled = this.vehicle.getParams().relaxationAccDampingEnabled;
            if (!enabled)
            {
                return 1.0;
            }

            double minFactor = this.vehicle.getParams().relaxationAccDampingFactor;
            double ratio = Math.min(1.0, Math.max(0.0, spaceBuffer.si / initialDeficit.si));

            // Linear / exponential recovery from minFactor (0.40) towards 1.0 as spaceBuffer decays to 0:
            // f(t) = 1.0 - (1.0 - minFactor) * ratio
            double factor = 1.0 - (1.0 - minFactor) * ratio;
            return Math.min(1.0, Math.max(minFactor, factor));
        }
        catch (Exception e)
        {
            return 1.0;
        }
    }

    /**
     * Calculates the minimum active positive acceleration scaling factor in [aRelaxDamping, 1.0]
     * across all active headway relaxations, or 1.0 if no relaxation is active.
     * @return double; acceleration scaling factor in [aRelaxDamping, 1.0]
     */
    public double getPrimaryRelaxationAccelerationFactor()
    {
        if (this.activeRelaxations.isEmpty())
        {
            return 1.0;
        }

        double minFactor = 1.0;
        for (String leaderId : this.activeRelaxations.keySet())
        {
            double factor = getRelaxationAccelerationFactor(leaderId);
            if (factor < minFactor)
            {
                minFactor = factor;
            }
        }
        return minFactor;
    }

    /**
     * Computes the maximum physical acceleration based on an empirical piece-wise linear model.
     * <p>
     * The model evaluates the current speed in km/h to apply the following constraints:
     * <ul>
     * <li>0 to 100 km/h: linear decrease from 3.5 m/s&sup2; to 1.0 m/s&sup2;</li>
     * <li>100 to 250 km/h: linear decrease from 1.0 m/s&sup2; to 0.0 m/s&sup2;</li>
     * <li>Above 250 km/h: 0.0 m/s&sup2;</li>
     * </ul>
     * </p>
     * @return Acceleration; the computed maximum physical acceleration
     */
    private Acceleration computeMaxPhysicalAcceleration()
    {
        double speedKmh = getEgoSpeed().getInUnit(SpeedUnit.KM_PER_HOUR);
        // See getMaxPhysicalAccelerationAt: the snapshot cannot fail, so the silent 3.5 m/s2 fallback is gone.
        double aMaxScaleSI = this.vehicle.getParams().aMaxSi;
        return Acceleration.instantiateSI(computeMaxPhysicalAccelerationAt(speedKmh, aMaxScaleSI));
    }

    /**
     * Core piece-wise linear acceleration model at a given speed and scale.
     * <p>
     * This static helper is shared by {@link #computeMaxPhysicalAcceleration()} and
     * {@link #getMaxPhysicalAccelerationAt(org.djunits.value.vdouble.scalar.Speed)} to avoid duplication.
     * </p>
     * @param speedKmh speed in km/h at which to evaluate
     * @param aMaxScaleSI the vehicle-specific maximum acceleration reference value [m/s&sup2;]; used as a scaling
     *            numerator against the 3.5 m/s&sup2; reference
     * @return the estimated maximum physical acceleration [m/s&sup2;]
     */
    private static double computeMaxPhysicalAccelerationAt(final double speedKmh, final double aMaxScaleSI)
    {
        double maxAccSi;
        if (speedKmh < 100.0)
        {
            maxAccSi = 3.5 - (2.5 / 100.0) * speedKmh;
        }
        else if (speedKmh < 250.0)
        {
            maxAccSi = 1.0 - (1.0 / 150.0) * (speedKmh - 100.0);
        }
        else
        {
            maxAccSi = 0.0;
        }
        // Apply vehicle-specific scaling factor
        maxAccSi *= (aMaxScaleSI / 3.5);
        return maxAccSi;
    }

    /**
     * Returns a compact textual summary of the currently cached ego parameters.
     * @return summary string
     */
    @Override
    public String toString()
    {
        return "EgoContext[" + "egoSpeed=" + getCachedValue(EGO_SPEED, Speed.class) + "]";
    }
}
