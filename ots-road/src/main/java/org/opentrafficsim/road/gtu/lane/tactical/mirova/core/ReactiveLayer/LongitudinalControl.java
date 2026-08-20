package org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ReactiveLayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.djunits.value.vdouble.scalar.Acceleration;
import org.djunits.value.vdouble.scalar.Length;
import org.djunits.value.vdouble.scalar.Speed;
import org.opentrafficsim.base.parameters.ParameterException;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.base.parameters.Parameters;
import org.opentrafficsim.core.gtu.GtuException;
import org.opentrafficsim.core.network.LateralDirectionality;
import org.opentrafficsim.core.network.NetworkException;
import org.opentrafficsim.road.gtu.lane.perception.RelativeLane;
import org.opentrafficsim.road.gtu.lane.perception.categories.InfrastructurePerception;
import org.opentrafficsim.road.gtu.lane.perception.headway.HeadwayGtu;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.MirovaTacticalPlanner;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.EgoContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.InfrastructureContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.NeighborsContext;
import org.opentrafficsim.road.gtu.lane.tactical.util.SpeedLimitUtil;
import org.opentrafficsim.road.network.speed.SpeedLimitInfo;

/**
 * Combines the longitudinal control components into the acceleration the vehicle actually executes.
 * <p>
 * Part of <b>Layer 4 (Reactive Control)</b> in the MiRoVA architecture. Several influences each demand an acceleration at the
 * same time -- following the leaders ahead, a speed-limit transition, an upcoming lower limit -- and the vehicle can only obey
 * the most restrictive of them. This class evaluates the candidates and takes their minimum.
 * </p>
 * <p>
 * The components are:
 * </p>
 * <ul>
 * <li><b>Leader following</b>, through {@link MirovaCarFollowingUtil} so that the Keane and Gao (2021) relaxation is applied.
 * Skipped once a lane change has passed its halfway point, where the leaders of the origin lane no longer govern.</li>
 * <li><b>Speed-limit transitions</b>, such as curvature or bumps.</li>
 * <li><b>An upcoming lower speed limit</b>, approached over the lookahead distance.</li>
 * </ul>
 * <p>
 * If no candidate applies, the fallback is plain car-following on the current leader, or free acceleration when the vehicle is
 * more than halfway through a lane change.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public final class LongitudinalControl
{
    /** Utility class, not instantiated. */
    private LongitudinalControl()
    {
        // Utility class
    }

    /**
     * Computes the longitudinal acceleration for the current time step as the most restrictive of all applicable components.
     * @param vehicle the tactical planner of the ego vehicle
     * @return the final longitudinal acceleration
     * @throws ParameterException if parameter retrieval fails
     * @throws NetworkException if the network structure cannot be queried
     * @throws GtuException if GTU state errors occur
     */
    public static Acceleration computeAcceleration(final MirovaTacticalPlanner vehicle)
            throws ParameterException, GtuException, NetworkException
    {
        // 1. Retrieve tightly coupled contexts and parameters
        EgoContext ego = vehicle.getContext(EgoContext.class);
        InfrastructureContext infra = vehicle.getContext(InfrastructureContext.class);
        NeighborsContext neighbors = vehicle.getContext(NeighborsContext.class);
        Parameters parameters = vehicle.getParameters();

        // List of candidate accelerations
        List<Acceleration> candidates = new ArrayList<>();

        // 2. Leader-following (incorporating automatic 2-parameter relaxation via our Utility)
        // We do not need that when the current LC has already entered the traget lane
        if (!vehicle.getLaneChange().isChangingLane() || vehicle.getLaneChange().getFraction() <= 0.5)
        {

            Iterable<HeadwayGtu> currentLeaders = neighbors.getLeaders(LateralDirectionality.NONE);
            double maxLeadersToConsider = vehicle.getParameters().getParameter(MirovaParameters.CF_MAX_LEADERS);
            List<HeadwayGtu> limitedLeaders = new ArrayList<>();
            int leaderCount = 0;
            for (HeadwayGtu leader : currentLeaders)
            {
                if (leaderCount >= maxLeadersToConsider)
                {
                    break;
                }
                limitedLeaders.add(leader);
                leaderCount++;
            }
            currentLeaders = limitedLeaders;
            Acceleration aCf = MirovaCarFollowingUtil.followMultipleLeaders(vehicle, currentLeaders);
            candidates.add(aCf);
        }

        // 3. Transition deceleration (e.g., curvature or bumps)
        Acceleration aTrans = SpeedLimitUtil.considerSpeedLimitTransitions(parameters, ego.getEgoSpeed(), vehicle.getPerception()
                .getPerceptionCategory(InfrastructurePerception.class).getSpeedLimitProspect(RelativeLane.CURRENT),
                vehicle.getCarFollowingModel());

        if (aTrans != null && aTrans.lt(Acceleration.POSITIVE_INFINITY))
        {
            candidates.add(aTrans);
        }

        // 4. Upcoming lower speed limit ahead
        SpeedLimitInfo nextLimit = infra.getNextSpeedLimit();
        Speed currentLegalLimit = infra.getLegalSpeedLimit();

        // Null-Safety: Prüfe, ob sowohl das nächste als auch das aktuelle SpeedLimit bekannt sind
        if (nextLimit != null && currentLegalLimit != null)
        {
            Speed nextLegal = SpeedLimitUtil.getLegalSpeedLimit(nextLimit);

            if (nextLegal.lt(currentLegalLimit))
            {
                // Nutze den OTS-Parameter anstelle der harten 200 Meter
                Length distanceToLimit = parameters.getParameter(ParameterTypes.LOOKAHEAD);
                Acceleration aLimit = MirovaCarFollowingUtil.approachTargetSpeed(vehicle, distanceToLimit, nextLegal);

                if (aLimit != null)
                {
                    candidates.add(aLimit);
                }
            }
        }

        // 5. Compute most restrictive acceleration safely
        Acceleration fallbackAcc = (vehicle.getLaneChange().isChangingLane() && vehicle.getLaneChange().getFraction() > 0.5)
                ? MirovaCarFollowingUtil.freeAcceleration(vehicle)
                : MirovaCarFollowingUtil.followSingleLeader(vehicle, neighbors.getLeader(LateralDirectionality.NONE));
        return candidates.stream().filter(Objects::nonNull).min(Acceleration::compareTo)
                .orElse(fallbackAcc);
    }
}
