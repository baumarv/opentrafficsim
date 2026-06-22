package org.opentrafficsim.road.gtu.lane.tactical.mirova.core.DesireLayer;

import org.djunits.value.vdouble.scalar.Length;
import org.djunits.value.vdouble.scalar.Speed;
import org.opentrafficsim.base.parameters.ParameterException;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.core.gtu.GtuException;
import org.opentrafficsim.core.gtu.plan.operational.OperationalPlanException;
import org.opentrafficsim.core.network.LateralDirectionality;
import org.opentrafficsim.core.network.NetworkException;
import org.opentrafficsim.road.gtu.lane.perception.RelativeLane;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.MirovaTacticalPlanner;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.InfrastructureContext;

/**
 * KnowledgeChunk representing the mandatory keep-right rule (Rechtsfahrgebot) on motorways.
 * <p>
 * This component forms part of <b>Layer 2 (Cognition / Motivation)</b> in the MiRoVA architecture. It adds a constant desire
 * toward the right lane whenever the right lane is free, faster than the congestion threshold, and provides sufficient
 * lookahead distance. This implements the German Autobahn keep-right obligation (§ 2 StVO).
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class KeepRightIncentive extends DesireIncentive
{

    /**
     * Constructs a new KeepRightIncentive.
     * @param vehicle the tactical planner governing the ego agent
     * @throws OperationalPlanException if the planner cannot be initialized
     */
    public KeepRightIncentive(final MirovaTacticalPlanner vehicle) throws OperationalPlanException
    {
        super(vehicle);
    }

    /**
     * Determines if the keep-right incentive is applicable.
     * <p>
     * Applicable whenever the right lane exists and a legal lane change to the right is possible.
     * </p>
     * @return {@code true} if a right lane is present and reachable
     * @throws ParameterException if parameter evaluation fails
     */
    @Override
    public boolean isApplicable() throws ParameterException
    {
        double rightDist = getInfrastructurePerception().getLegalLaneChangePossibility(RelativeLane.CURRENT,
                LateralDirectionality.RIGHT).si;
        return rightDist > 0.0 && getInfrastructurePerception().getCrossSection().contains(RelativeLane.RIGHT);
    }

    /**
     * Computes the desire to move right in order to comply with the keep-right rule.
     * <p>
     * A constant desire of {@link MirovaParameters#DFREE} is added to the right direction when all three conditions hold:
     * <ol>
     * <li>The anticipated speed in the right lane is at least the ego desired speed.</li>
     * <li>The legal lane-change distance to the right exceeds {@link ParameterTypes#LOOKAHEAD}.</li>
     * <li>The right lane is not congested (speed above {@link ParameterTypes#VCONG}).</li>
     * </ol>
     * </p>
     * @return a {@link Desire} object with a positive right component when keep-right conditions are met, zero otherwise
     * @throws ParameterException if required parameters for the calculation are missing
     * @throws GtuException if GTU state cannot be accessed
     * @throws NetworkException if network state cannot be accessed
     */
    @Override
    public Desire computeDesire() throws ParameterException, GtuException, NetworkException
    {
        double rightDist = getInfrastructurePerception().getLegalLaneChangePossibility(RelativeLane.CURRENT,
                LateralDirectionality.RIGHT).si;

        if (rightDist <= 0.0 || !getInfrastructurePerception().getCrossSection().contains(RelativeLane.RIGHT))
        {
            this.desire = Desire.zero();
            return this.desire;
        }

        InfrastructureContext infrastructureContext = getMirovaTacticalPlanner().getContext(InfrastructureContext.class);
        Speed vCong = getParameters().getParameter(ParameterTypes.VCONG);
        Speed rightSpeed = infrastructureContext.getAnticipatedSpeed(RelativeLane.RIGHT);
        Length lookahead = getParameters().getParameter(ParameterTypes.LOOKAHEAD);

        double dRight = 0.0;
        if (rightSpeed.ge(getMirovaTacticalPlanner().getGtu().getDesiredSpeed())
                && Length.instantiateSI(rightDist).ge(lookahead) && rightSpeed.gt(vCong))
        {
            dRight = getMirovaTacticalPlanner().getParameters().getParameter(MirovaParameters.DFREE);
        }

        this.desire = new Desire(0.0, dRight, false);
        return this.desire;
    }
}
