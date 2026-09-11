package org.opentrafficsim.road.gtu.lane.tactical.mirova.core.DesireLayer;

import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;

import org.djunits.value.vdouble.scalar.Length;
import org.djunits.value.vdouble.scalar.Speed;
import org.opentrafficsim.base.parameters.ParameterException;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.base.parameters.Parameters;
import org.opentrafficsim.core.gtu.plan.operational.OperationalPlanException;
import org.opentrafficsim.core.network.LateralDirectionality;
import org.opentrafficsim.road.gtu.lane.perception.LanePerception;
import org.opentrafficsim.road.gtu.lane.perception.RelativeLane;
import org.opentrafficsim.road.gtu.lane.perception.categories.InfrastructurePerception;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.MirovaTacticalPlanner;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.EgoContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.InfrastructureContext;
import org.opentrafficsim.road.network.LaneChangeInfo;
import org.opentrafficsim.road.network.speed.SpeedLimitInfo;

/**
 * KnowledgeChunk representing a mandatory lane change for route following.
 * <p>
 * Forms a critical part of <b>Layer 2 (Cognition / Motivation)</b> in the MiRoVA architecture. This chunk monitors the route
 * and the network infrastructure. It produces a {@link Desire} to leave a lane if it ends or diverges from the route.
 * Crucially, it also produces negative desires for adjacent lanes that are invalid (e.g., dead ends), preventing discretionary
 * lane changes into unsafe areas.
 * </p>
 * <p>
 * Copyright (c) 2025 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class RouteIncentive extends DesireIncentive
{

    /**
     * Constructs a new MandatoryLaneChangeChunk.
     * @param vehicle the tactical planner governing the ego agent
     * @throws OperationalPlanException if chunk instantiation fails
     */
    public RouteIncentive(final MirovaTacticalPlanner vehicle) throws OperationalPlanException
    {
        super(vehicle);
    }

    /**
     * Determines if the mandatory lane change logic is applicable.
     * <p>
     * This chunk is always applicable as it must constantly monitor the validity of current and adjacent lanes to provide veto
     * desires. Even if the current lane is fine, it must prevent changing into a lane that ends by returning negative desires.
     * </p>
     * @return {@code true} as this chunk must always evaluate lane validity
     * @throws ParameterException if parameter evaluation fails
     */
    @Override
    public boolean isApplicable() throws ParameterException
    {
        return true;
    }

    /**
     * Computes the LMRS-based mandatory lane change desire.
     * <p>
     * Step 1: Compute the desire-to-leave for each relevant lane (LEFT, CURRENT, RIGHT).<br>
     * Step 2: Derive directional desires (to LEFT / to RIGHT) by comparing the desire-to-leave values.
     * </p>
     * @return a {@link Desire} object containing mandatory desire values for left and right directions
     * @throws ParameterException if required parameters are missing
     */
    @Override
    public Desire computeDesire() throws ParameterException
    {
        // --- Access Contexts ----------------------------------------------------
        EgoContext egoCtx = this.getMirovaTacticalPlanner().getContext(EgoContext.class);
        InfrastructureContext infraCtx = this.getMirovaTacticalPlanner().getContext(InfrastructureContext.class);
        LanePerception perception = this.getMirovaTacticalPlanner().getPerception();
        InfrastructurePerception infraPerc = getInfrastructurePerception();

        Parameters p = getParameters();
        double v = egoCtx.getEgoSpeed().si;

        // --- Step 1: Desire-to-leave per lane (d_r,k) ---------------------------
        Map<RelativeLane, Double> dLeave = new HashMap<>();

        // Define relevant lanes to check
        RelativeLane[] lanesToCheck = new RelativeLane[] {RelativeLane.LEFT, RelativeLane.CURRENT, RelativeLane.RIGHT};
        double lookAheadParam = p.getParameter(ParameterTypes.LOOKAHEAD).si;
        double t0Param = getMirovaTacticalPlanner().getParams().t0Si;

        for (RelativeLane lane : lanesToCheck)
        {
            double d_r = 0.0;
            // Check if lane exists in cross-section. The speed limit has to be read inside this test, not before it:
            // the prospect is computed from the root record of the relative lane, and for a lane that is not in the
            // cross-section there is none, so the query dereferences null. Both values it produces are used only
            // here, and a lane that does not exist carries no desire to leave it, which is the zero already set
            // above. On the right shoulder of L5a this killed 12 vehicles per 300-minute run, all within the first
            // four metres, where the lane structure has no lane to the right.
            if (perception.getLaneStructure().exists(lane))
            {
                SpeedLimitInfo speedLimitInfo = infraPerc.getSpeedLimitProspect(lane).getSpeedLimitInfo(Length.ZERO);
                Speed desiredSpeed = this.vehicle.getCarFollowingModel().desiredSpeed(p, speedLimitInfo);

                // Calculate desire based on remaining distance
                SortedSet<LaneChangeInfo> info = infraPerc.getLegalLaneChangeInfo(lane);

                if (info != null && !info.isEmpty())
                {
                    for (LaneChangeInfo lcInfo : info)
                    {
                        double d = 0.0;
                        Length x = lcInfo.remainingDistance();
                        int n = lcInfo.numberOfLaneChanges();

                        // Prevent division by zero if n=0 (should not happen for mandatory info, but safety first)
                        n = n == 0 ? 1 : n;

                        double d1 = 1.0 - x.si / (n * lookAheadParam);
                        // CHANGE: Use desired speed instead of current speed to avoid bias of low speed from congestion
                        double d2 = 1.0 - (x.si / desiredSpeed.si) / (n * t0Param);
                        d = Math.max(d1, d2);

                        d_r = Math.max(d_r, Math.max(0.0, d)); // clamp to [0,1]
                    }
                }
            }
            dLeave.put(lane, d_r);
        }

        // --- Step 2: Compute Directional Desires --------------------------------

        // Get constraints for changing FROM current lane
        SortedSet<LaneChangeInfo> currentInfo = infraPerc.getLegalLaneChangeInfo(RelativeLane.CURRENT);
        Length currentReqDist = (currentInfo == null || currentInfo.isEmpty() || currentInfo.first().numberOfLaneChanges() == 0)
                ? Length.POSITIVE_INFINITY : currentInfo.first().remainingDistance();

        Double dCurr = dLeave.getOrDefault(RelativeLane.CURRENT, 0.0);
        double dLeft = 0.0;
        double dRight = 0.0;

        // Check Left Validity
        if (perception.getLaneStructure().exists(RelativeLane.LEFT) && infraCtx.getIfLaneAvailable(LateralDirectionality.LEFT))
        {
            if (infraPerc.getLegalLaneChangePossibility(RelativeLane.CURRENT, LateralDirectionality.LEFT).neg()
                    .lt(currentReqDist))
            {
                double dLeaveLeft = dLeave.getOrDefault(RelativeLane.LEFT, 0.0);
                // Schakel formula: desire to change = (DesireLeaveTarget < DesireLeaveCurrent) ? DesireLeaveCurrent :
                // -DesireLeaveTarget
                dLeft = dLeaveLeft < dCurr ? dCurr : -dLeaveLeft;
            }
        }

        // Check Right Validity
        if (perception.getLaneStructure().exists(RelativeLane.RIGHT)
                && infraCtx.getIfLaneAvailable(LateralDirectionality.RIGHT))
        {
            if (infraPerc.getLegalLaneChangePossibility(RelativeLane.CURRENT, LateralDirectionality.RIGHT).neg()
                    .lt(currentReqDist))
            {
                double dLeaveRight = dLeave.getOrDefault(RelativeLane.RIGHT, 0.0);
                dRight = dLeaveRight < dCurr ? dCurr : -dLeaveRight;
            }
        }

        // --- Build Desire vector ------------------------------------------------

        // Return desire with the "mandatory" flag set to true, and cache it like every other incentive does.
        // The planner aggregates the return value, not the field, so the field was dead and stayed at Desire.zero()
        // for the life of every vehicle -- invisible to the model, wrong for anything that reads the incentive.
        this.desire = new Desire(dLeft, dRight, true);
        return this.desire;
    }
}
