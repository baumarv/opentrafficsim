package org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ReactiveLayer;

import org.djunits.unit.SpeedUnit;
import org.djunits.value.vdouble.scalar.Acceleration;
import org.djunits.value.vdouble.scalar.Duration;
import org.djunits.value.vdouble.scalar.Length;
import org.djunits.value.vdouble.scalar.Speed;
import org.djunits.value.vdouble.scalar.Time;
import org.opentrafficsim.base.parameters.ParameterException;
import org.opentrafficsim.base.parameters.ParameterType;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.base.parameters.Parameters;
import org.opentrafficsim.core.gtu.GtuException;
import org.opentrafficsim.road.gtu.lane.perception.headway.HeadwayGtu;
import org.opentrafficsim.road.gtu.lane.tactical.following.CarFollowingModel;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.MirovaTacticalPlanner;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.EgoContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.RelaxationDiagnostics;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.InfrastructureContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.RelaxationState;
import org.opentrafficsim.road.gtu.lane.tactical.util.CarFollowingUtil;
import org.opentrafficsim.road.network.speed.SpeedLimitInfo;

/**
 * Utility class for MiRoVA specific car-following calculations.
 * <p>
 * This class acts as a transparent wrapper around the standard OTS {@code CarFollowingUtil}. It automatically injects the Keane
 * and Gao (2021) 2-parameter relaxation buffers for specific leader GTUs. Additionally, it simplifies the method signatures for
 * MiRoVA by extracting repetitive parameters (model, speed, limits) directly from the tactical planner.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public final class MirovaCarFollowingUtil
{

    /** Leader speed below which relaxation against a new leader is not applied. */
    private static final Speed RELAXATION_MIN_LEADER_SPEED = new Speed(10.0, SpeedUnit.KM_PER_HOUR);

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private MirovaCarFollowingUtil()
    {
        // Utility class
    }

    /*
     * ========================================================================================= 1) RELAXED METHODS (Applicable
     * for physical GTUs with IDs) =========================================================================================
     */

    /**
     * Calculates the acceleration towards a single leader, transparently applying ID-based relaxation.
     * <p>
     * This method utilizes a single-tick cache stored within the {@code EgoContext} to prevent redundant car-following
     * evaluations of the same leader GTU within the same simulation step.
     * </p>
     * @param vehicle the tactical planner of the ego vehicle containing all contexts
     * @param leader the actual, unmanipulated perception of the leader GTU
     * @return the acceleration calculated by the car-following model
     * @throws ParameterException if a required parameter is missing
     * @throws GtuException if GTU state cannot be accessed
     */
    public static Acceleration followSingleLeader(final MirovaTacticalPlanner vehicle, final HeadwayGtu leader)
            throws ParameterException, GtuException
    {
        if (leader == null)
        {
            return freeAcceleration(vehicle);
        }

        EgoContext ego = vehicle.getContext(EgoContext.class);
        String leaderId = leader.getId();

        // 1. Check Cache (Early Exit for Performance)
        if (leaderId != null)
        {
            Acceleration cachedAcc = ego.getCachedAcceleration(leaderId);
            if (cachedAcc != null)
            {
                return cachedAcc; // Spart den kompletten Berechnungsbaum!
            }
        }

        Duration now = vehicle.getGtu().getSimulator().getSimulatorTime();
        Length perceivedDistance = leader.getDistance();

        // A cooperative headway reserve makes the ego perceive its own leader as closer than it is, so the model
        // settles at a correspondingly larger real gap - the gap a merger needs. Applied here rather than by handing
        // the ego's speed over to the candidate, which is what ends it at ramp speed.
        Length reserve = ego.getCooperativeGapReserve();
        if (reserve.si > 0.0 && perceivedDistance != null)
        {
            perceivedDistance = Length.instantiateSI(Math.max(perceivedDistance.si - reserve.si, 0.5));
        }
        Speed perceivedLeaderSpeed = leader.getSpeed();

        // 2. Check for and apply ID-based relaxation buffers
        boolean perceptionRelaxed = false;
        RelaxationState activeRelaxation = ego.getActiveRelaxationForLeader(leaderId);
        if (RelaxationDiagnostics.ENABLED)
        {
            RelaxationDiagnostics.carFollowingCall(activeRelaxation != null);
        }
        if (activeRelaxation != null)
        {
            boolean abortNow = leader.getAcceleration().lt(vehicle.getParams().relaxationAbortDecelerationScalar)
                    || perceivedLeaderSpeed.si < RELAXATION_MIN_LEADER_SPEED.si;
            if (abortNow && !activeRelaxation.isFading())
            {
                if (RelaxationDiagnostics.ENABLED)
                {
                    double life = now.si - activeRelaxation.getStartTime().si;
                    RelaxationDiagnostics.discarded(activeRelaxation.getVirtualSpaceBuffer(now).si);
                    if (leader.getAcceleration().lt(vehicle.getParams().relaxationAbortDecelerationScalar))
                    {
                        RelaxationDiagnostics.abortedByDeceleration(life);
                    }
                    else
                    {
                        RelaxationDiagnostics.abortedBySpeed(life);
                    }
                }
                // Fade the buffer out rather than dropping it, so the perceived distance does not jump. With a
                // non-positive fade duration this ends the relaxation at once, as it used to.
                activeRelaxation.beginFade(now, vehicle.getParams().relaxationFadeDurationScalar);
            }
            if (!activeRelaxation.isFadedOut(now))
            {
                perceivedDistance = perceivedDistance.plus(activeRelaxation.getVirtualSpaceBuffer(now));
                perceivedLeaderSpeed = perceivedLeaderSpeed.plus(activeRelaxation.getVirtualSpeedBuffer(now));
                perceptionRelaxed = true;
            }
            else
            {
                // The fade has run its course: nothing is left to apply, so drop the state.
                ego.clearRelaxationForLeader(leaderId);
            }
        }
        // 3. Perform the heavy physical calculation
        Acceleration result = CarFollowingUtil.followSingleLeader(vehicle.getCarFollowingModel(), vehicle.getParameters(),
                ego.getEgoSpeed(), vehicle.getContext(InfrastructureContext.class).getCurrentSpeedLimit(), perceivedDistance,
                perceivedLeaderSpeed);

        if (result.si > 0.0 && leaderId != null)
        {
            double fRelaxAcc = ego.getRelaxationAccelerationFactor(leaderId);
            if (fRelaxAcc < 1.0)
            {
                result = Acceleration.instantiateSI(result.si * fRelaxAcc);
            }
        }

        // Physical net, evaluated on the unmodified perception.
        //
        // Everything above this line may have been computed from an enlarged gap and a reduced speed difference: the
        // relaxation adds its virtual buffers before the car-following model is called, and the acceleration damping
        // scales the answer afterwards. That is the point of both mechanisms - a driver who has just accepted a short
        // headway tolerates it rather than braking hard - but tolerating a headway must not extend to ignoring the
        // physics of the gap that is actually there.
        //
        // The kinematic bounding inside MirovaIdmPlus cannot do this: it only ever sees the synthetic leader carrying
        // the buffers, so it reasons about a gap that does not exist. Here both are available, and the rule is one
        // way only - the result may never be milder than what the real gap and the real speed difference require.
        // Only vehicles whose perception was actually enlarged need it. For everyone else the model already reasoned
        // about the real gap, so re-imposing the real gap on top adds nothing - except that the kinematic form yields a
        // small negative value for any vehicle closing on a leader at all, however distant, which then acts as a ceiling
        // on free acceleration. Scoped this way the net guards the discontinuity it was written for and leaves
        // unrelaxed traffic to the car-following model.
        if (perceptionRelaxed)
        {
            result = Acceleration.min(result, requiredDeceleration(vehicle, leader));
        }

        // 4. Store the result in the cache for subsequent calls in this tick
        if (leaderId != null)
        {
            ego.cacheAcceleration(leaderId, result);
        }

        return result;
    }


    /**
     * Returns the deceleration the real gap demands, or {@link Acceleration#POSITIVE_INFINITY} when it demands nothing.
     * <p>
     * Uses the perceived leader as it is, without the relaxation's virtual buffers, and answers a single question: at
     * the present closing speed, what deceleration is needed to avoid running into the leader before the gap is used
     * up. Below {@code B_CRIT} the answer is not binding - a driver may brake harder than physics require, and often
     * does - so the bound only ever tightens a result that would leave the ego short.
     * </p>
     * @param vehicle MirovaTacticalPlanner; the ego vehicle
     * @param leader HeadwayGtu; the perceived leader, unmodified
     * @return Acceleration; the required deceleration, bounded by {@code B_MAX}, or positive infinity when none is
     *         required
     * @throws ParameterException if a required parameter is missing
     */
    private static Acceleration requiredDeceleration(final MirovaTacticalPlanner vehicle, final HeadwayGtu leader)
            throws ParameterException
    {
        EgoContext ego = vehicle.getContext(EgoContext.class);
        Length distance = leader.getDistance();
        if (distance == null)
        {
            return Acceleration.POSITIVE_INFINITY;
        }
        double deltaV = ego.getEgoSpeed().si - leader.getSpeed().si;
        if (deltaV <= 0.0)
        {
            return Acceleration.POSITIVE_INFINITY; // not closing in
        }

        Length s0 = vehicle.getParams().s0Scalar;
        Acceleration bMax = vehicle.getParams().bMaxScalar;
        double usable = distance.si - s0.si;
        if (usable <= 0.0)
        {
            return bMax; // already inside the standstill distance and still closing
        }
        double required = -(deltaV * deltaV) / (2.0 * usable);
        return Acceleration.instantiateSI(Math.max(required, bMax.si));
    }

    /**
     * Calculates the most restrictive acceleration towards a set of multiple leaders, evaluating ID-based relaxation for each
     * leader individually.
     * @param vehicle the tactical planner of the ego vehicle containing all contexts
     * @param leaders the iterable collection of leaders (e.g., from multiple lanes)
     * @return the most restrictive (minimum) acceleration among all evaluated leaders
     * @throws ParameterException if a required parameter is missing
     * @throws GtuException if GTU state cannot be accessed
     */
    public static Acceleration followMultipleLeaders(final MirovaTacticalPlanner vehicle, final Iterable<HeadwayGtu> leaders)
            throws ParameterException, GtuException
    {
        Acceleration minAcceleration = null;

        for (HeadwayGtu leader : leaders)
        {
            Acceleration acc = followSingleLeader(vehicle, leader);

            if (minAcceleration == null || acc.lt(minAcceleration))
            {
                minAcceleration = acc;
            }
        }

        if (minAcceleration == null)
        {
            return freeAcceleration(vehicle);
        }

        return minAcceleration;
    }

    /**
     * ========================================================================================= 2) STANDARD METHODS (No
     * Relaxation - Target is virtual or lacks an ID)
     * =========================================================================================
     */

    /**
     * Follows a leader while tolerating a shorter headway than the ego would otherwise want.
     * <p>
     * A vehicle yielding to one alongside it is not following that vehicle in the ordinary sense: it accepts a
     * closer gap for the duration of the manoeuvre. The car-following model reads the desired headway from the
     * parameter set, so expressing that means overriding {@code T} for the one call.
     * </p>
     * <p>
     * The override lives here rather than in the tactical states that need it. It was written out twice in
     * {@code PreventUndercuttingPattern}, in both cases with several statements between setting the parameter and
     * resetting it, including the car-following call itself: anything thrown in between left the ego with a
     * permanently reduced headway. The reset is in a {@code finally} block here, and the states no longer touch
     * the parameter at all, which the project rules forbid them to do.
     * </p>
     * @param vehicle MirovaTacticalPlanner; the tactical planner of the ego vehicle
     * @param leader HeadwayGtu; the vehicle being yielded to
     * @param headwayFactor double; the fraction of the desired time headway to require, in (0, 1]
     * @return Acceleration; the acceleration from the car-following model under the reduced headway
     * @throws ParameterException if a required parameter is missing
     * @throws GtuException if GTU state cannot be accessed
     */
    public static Acceleration followWithReducedHeadway(final MirovaTacticalPlanner vehicle, final HeadwayGtu leader,
            final double headwayFactor) throws ParameterException, GtuException
    {
        Parameters parameters = vehicle.getParameters();
        parameters.setParameterResettable(ParameterTypes.T,
                parameters.getParameter(ParameterTypes.T).times(headwayFactor));
        try
        {
            return followSingleLeader(vehicle, leader);
        }
        finally
        {
            parameters.resetParameter(ParameterTypes.T);
        }
    }

    /**
     * The same for a distance and speed that belong to no perceived vehicle.
     * @param vehicle MirovaTacticalPlanner; the tactical planner of the ego vehicle
     * @param distance Length; the distance to follow
     * @param leaderSpeed Speed; the speed to follow
     * @param headwayFactor double; the fraction of the desired time headway to require, in (0, 1]
     * @return Acceleration; the acceleration from the car-following model under the reduced headway
     * @throws ParameterException if a required parameter is missing
     * @throws GtuException if GTU state cannot be accessed
     */
    public static Acceleration followDistanceAndSpeedWithReducedHeadway(final MirovaTacticalPlanner vehicle,
            final Length distance, final Speed leaderSpeed, final double headwayFactor)
            throws ParameterException, GtuException
    {
        Parameters parameters = vehicle.getParameters();
        parameters.setParameterResettable(ParameterTypes.T,
                parameters.getParameter(ParameterTypes.T).times(headwayFactor));
        try
        {
            return followDistanceAndSpeed(vehicle, distance, leaderSpeed);
        }
        finally
        {
            parameters.resetParameter(ParameterTypes.T);
        }
    }

    /**
     * Follows an arbitrary distance and speed without a specific GTU ID.
     * <p>
     * Note: No relaxation is applied here, as relaxation requires tracking a specific vehicle ID over time.
     * </p>
     * @param vehicle the tactical planner of the ego vehicle
     * @param distance the distance to follow
     * @param leaderSpeed the speed to follow
     * @return acceleration for following the virtual leader
     * @throws ParameterException if a parameter is missing
     * @throws GtuException if GTU state cannot be accessed
     */
    public static Acceleration followDistanceAndSpeed(final MirovaTacticalPlanner vehicle, final Length distance,
            final Speed leaderSpeed) throws ParameterException, GtuException
    {
        return CarFollowingUtil.followSingleLeader(vehicle.getCarFollowingModel(), vehicle.getParameters(),
                vehicle.getContext(EgoContext.class).getEgoSpeed(),
                vehicle.getContext(InfrastructureContext.class).getCurrentSpeedLimit(), distance, leaderSpeed);
    }

    /**
     * Stop within given distance.
     * <p>
     * Note: Stopping is usually done for static infrastructure (e.g., traffic lights). Therefore, the Keane and Gao relaxation
     * phenomenon is physically not applicable here.
     * </p>
     * @param vehicle the tactical planner of the ego vehicle
     * @param distance distance to stop over
     * @return acceleration to stop over distance
     * @throws ParameterException if a parameter is missing
     * @throws GtuException if GTU state cannot be accessed
     */
    public static Acceleration stop(final MirovaTacticalPlanner vehicle, final Length distance)
            throws ParameterException, GtuException
    {
        return CarFollowingUtil.stop(vehicle.getCarFollowingModel(), vehicle.getParameters(),
                vehicle.getContext(EgoContext.class).getEgoSpeed(),
                vehicle.getContext(InfrastructureContext.class).getCurrentSpeedLimit(), distance);
    }

    /**
     * Return constant acceleration in order to stop in specified distance.
     * @param vehicle the tactical planner of the ego vehicle
     * @param distance distance to stop over
     * @return constant acceleration in order to stop in specified distance
     * @throws ParameterException on missing parameter
     * @throws GtuException if GTU state cannot be accessed
     */
    public static Acceleration constantAccelerationStop(final MirovaTacticalPlanner vehicle, final Length distance)
            throws ParameterException, GtuException
    {
        return CarFollowingUtil.constantAccelerationStop(vehicle.getCarFollowingModel(), vehicle.getParameters(),
                vehicle.getContext(EgoContext.class).getEgoSpeed(), distance);
    }

    /**
     * Calculate free acceleration.
     * @param vehicle the tactical planner of the ego vehicle
     * @return acceleration free acceleration
     * @throws ParameterException if a parameter is missing
     * @throws GtuException if GTU state cannot be accessed
     */
    public static Acceleration freeAcceleration(final MirovaTacticalPlanner vehicle) throws ParameterException, GtuException
    {
        return CarFollowingUtil.freeAcceleration(vehicle.getCarFollowingModel(), vehicle.getParameters(),
                vehicle.getContext(EgoContext.class).getEgoSpeed(),
                vehicle.getContext(InfrastructureContext.class).getCurrentSpeedLimit());
    }

    /**
     * Returns an acceleration based on the car-following model in order to adjust the speed to a given value at some location
     * ahead (e.g., for early anticipation).
     * <p>
     * Note: This method operates on a virtual stationary vehicle internally. Relaxation cannot and should not be applied here.
     * </p>
     * @param vehicle the tactical planner of the ego vehicle
     * @param distance distance to the location of the target speed
     * @param targetSpeed target speed
     * @return acceleration based on the car-following model in order to adjust the speed
     * @throws ParameterException if parameter exception occurs
     * @throws GtuException if GTU state cannot be accessed
     */
    public static Acceleration approachTargetSpeed(final MirovaTacticalPlanner vehicle, final Length distance,
            final Speed targetSpeed) throws ParameterException, GtuException
    {
        return CarFollowingUtil.approachTargetSpeed(vehicle.getCarFollowingModel(), vehicle.getParameters(),
                vehicle.getContext(EgoContext.class).getEgoSpeed(),
                vehicle.getContext(InfrastructureContext.class).getCurrentSpeedLimit(), distance, targetSpeed);
    }
}
