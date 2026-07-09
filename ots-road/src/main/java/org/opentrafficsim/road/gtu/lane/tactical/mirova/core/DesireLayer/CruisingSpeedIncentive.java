package org.opentrafficsim.road.gtu.lane.tactical.mirova.core.DesireLayer;

import org.djunits.unit.DimensionlessUnit;
import org.djunits.value.vdouble.scalar.Acceleration;
import org.djunits.value.vdouble.scalar.Dimensionless;
import org.djunits.value.vdouble.scalar.Speed;
import org.opentrafficsim.base.parameters.ParameterException;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.core.gtu.GtuException;
import org.opentrafficsim.core.gtu.plan.operational.OperationalPlanException;
import org.opentrafficsim.core.network.LateralDirectionality;
import org.opentrafficsim.core.network.NetworkException;
import org.opentrafficsim.road.gtu.lane.perception.RelativeLane;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.MirovaTacticalPlanner;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.EgoContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.InfrastructureContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.MacroTrafficContext;

/**
 * DesireIncentive representing discretionary lane change incentives (speed gain, keep-right).
 * <p>
 * This component forms part of <b>Layer 2 (Cognition / Motivation)</b> in the MiRoVA architecture. It produces desires toward
 * faster or more comfortable lanes based on macroscopic traffic states and ego vehicle dynamics. The logic is largely based on
 * Schakel et al. (2012) – LMRS Equations (6–7) and (10).
 * </p>
 * <p>
 * Copyright (c) 2025 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class CruisingSpeedIncentive extends DesireIncentive
{

    /**
     * Constructs a new DiscretionaryLaneChangeChunk.
     * @param vehicle the tactical planner governing the ego agent
     * @throws OperationalPlanException if the planner cannot be initialized
     * @throws ParameterException if required parameters are missing
     */
    public CruisingSpeedIncentive(final MirovaTacticalPlanner vehicle) throws OperationalPlanException, ParameterException
    {
        super(vehicle);
    }

    /**
     * Determines if the discretionary lane change logic is applicable.
     * <p>
     * Discretionary lane changes for speed gain or keeping right are generally always applicable during normal driving
     * operations.
     * </p>
     * @return {@code true} as this chunk is always applicable
     * @throws ParameterException if parameter evaluation fails
     */
    @Override
    public boolean isApplicable() throws ParameterException
    {
        return true; // Always applicable
    }

    /**
     * Computes the discretionary desire to change lanes for speed gains or to adhere to a keep-right rule.
     * @return a {@link Desire} object containing discretionary desire values for left and right directions
     * @throws ParameterException if required parameters for the calculation are missing
     * @throws GtuException if GTU state cannot be accessed
     * @throws NetworkException if network state cannot be accessed
     */
    @Override
    public Desire computeDesire() throws ParameterException, GtuException, NetworkException
    {
        Speed vGain = getMirovaTacticalPlanner().getVGain();
        InfrastructureContext infrastructureContext = getMirovaTacticalPlanner().getContext(InfrastructureContext.class);
        EgoContext egoContext = getMirovaTacticalPlanner().getContext(EgoContext.class);

        double leftDist = getInfrastructurePerception().getLegalLaneChangePossibility(RelativeLane.CURRENT,
                LateralDirectionality.LEFT).si;
        double rightDist = getInfrastructurePerception().getLegalLaneChangePossibility(RelativeLane.CURRENT,
                LateralDirectionality.RIGHT).si;

        Dimensionless aGain;
        Speed vCur = infrastructureContext.getAnticipatedSpeed(RelativeLane.CURRENT);

        Acceleration aCur = egoContext.getCurrentCarFollowingAcceleration();

        // Desire dampening is only applied in situation where we are not accelerating
        // from standstill (this explains the condition with ego speed higher than 5 m/s)
        if (aCur.si > 0 && egoContext.getEgoSpeed().si > 5.0)
        {
            Acceleration a = getParameters().getParameter(ParameterTypes.A);
            aGain = a.minus(aCur).divide(a);
        }
        else
        {
            aGain = new Dimensionless(1, DimensionlessUnit.SI);
        }
        // ---------------------------------------------------------
        // Left Desire Computation (Speed Gain)
        // ---------------------------------------------------------
        double dLeft;
        if (leftDist > 0.0 && getInfrastructurePerception().getCrossSection().contains(RelativeLane.LEFT)
                && !isDeadEndForRoute(RelativeLane.LEFT, RelativeLane.CURRENT))
        {
            Speed vLeft = infrastructureContext.getAnticipatedSpeed(RelativeLane.LEFT);
            dLeft = aGain.si * (vLeft.si - vCur.si) / vGain.si;
        }
        else
        {
            dLeft = 0.0;
        }

        // ---------------------------------------------------------
        // Right Desire Computation (Speed Gain & Keep Right)
        // ---------------------------------------------------------
        double dRight;
        if (rightDist > 0.0 && getInfrastructurePerception().getCrossSection().contains(RelativeLane.RIGHT)
                && !isDeadEndForRoute(RelativeLane.RIGHT, RelativeLane.CURRENT))
        {
            Speed vRight = infrastructureContext.getAnticipatedSpeed(RelativeLane.RIGHT);
            // no speed gain incentive to the right lane in non-congested situations
            if (vCur.si >= getParameters().getParameter(ParameterTypes.VCONG).si)
            {
                dRight = aGain.si * Math.min(vRight.si - vCur.si, 0) / vGain.si;
            }
            else
            {
                dRight = aGain.si * (vRight.si - vCur.si) / vGain.si;
            }
        }
        else
        {
            dRight = 0.0;
        }

        // Create and return the computed non-mandatory desire
        this.desire = new Desire(dLeft, dRight, false);
        return this.desire;
    }
}
