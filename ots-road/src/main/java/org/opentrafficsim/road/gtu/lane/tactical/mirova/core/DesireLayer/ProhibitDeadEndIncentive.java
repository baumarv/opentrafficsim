package org.opentrafficsim.road.gtu.lane.tactical.mirova.core.DesireLayer;

import org.djunits.value.vdouble.scalar.Length;
import org.opentrafficsim.base.parameters.ParameterException;
import org.opentrafficsim.base.parameters.ParameterType;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.core.gtu.GtuType;
import org.opentrafficsim.core.gtu.plan.operational.OperationalPlanException;
import org.opentrafficsim.core.network.LateralDirectionality;
import org.opentrafficsim.road.gtu.lane.perception.RelativeLane;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.MirovaTacticalPlanner;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.InfrastructureContext;
import org.opentrafficsim.road.network.lane.Lane;

/**
 * DesireIncentive that suppresses lane changes onto a merging lane.
 * <p>
 * This component forms part of <b>Layer 2 (Cognition / Motivation)</b> in the MiRoVA architecture. It is responsible for
 * detecting adjacent merging lanes and discouraging the ego vehicle from changing into them.
 * </p>
 * <p>
 * When a parallel merge is detected on a given side, this incentive applies a negative desire for changing in that direction,
 * thereby preventing the ego vehicle from moving onto a lane that is about to end or is occupied by merging traffic.
 * </p>
 * <p>
 * Copyright (c) 2025 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class ProhibitDeadEndIncentive extends DesireIncentive
{

    private boolean prohibitLeft = false;  // Flag to suppress left lane changes
    private boolean prohibitRight = false; // Flag to suppress right lane changes

    /**
     * Constructs a new MergeCooperationChunk.
     * @param vehicle the tactical planner governing the ego agent
     * @throws OperationalPlanException if chunk instantiation fails
     */
    public ProhibitDeadEndIncentive(final MirovaTacticalPlanner vehicle) throws OperationalPlanException
    {
        super(vehicle);
    }

    /**
     * Determines if the merge cooperation logic is applicable.
     * <p>
     * This chunk is always applicable in principle, as merge situations can occur dynamically.
     * </p>
     * @return {@code true} as cooperation checks must always run
     * @throws ParameterException if parameter evaluation fails
     */
    @Override
    public boolean isApplicable() throws ParameterException
    {
        InfrastructureContext infraCtx = this.vehicle.getContext(InfrastructureContext.class);
        
        this.prohibitLeft = infraCtx.getParallelMerge(LateralDirectionality.LEFT)
                || isDeadEndForRoute(RelativeLane.LEFT, RelativeLane.CURRENT);
        this.prohibitRight = infraCtx.getParallelMerge(LateralDirectionality.RIGHT)
                || isDeadEndForRoute(RelativeLane.RIGHT, RelativeLane.CURRENT);

        return this.prohibitLeft || this.prohibitRight;
    }

    /**
     * Computes the desire to suppress a lane change towards a merging lane, exit lane, or dead end.
     * <p>
     * If a parallel merge or exit/dead-end lane is detected on either side, a negative desire is applied in that direction to
     * discourage the ego vehicle from changing onto it.
     * </p>
     * @return a {@link Desire} object with a negative value in the prohibited directions, or 0.0 if not prohibited
     * @throws ParameterException if required parameters are missing
     */
    @Override
    public Desire computeDesire() throws ParameterException
    {
        double dLeft = this.prohibitLeft ? -this.vehicle.getParameters().getParameter(MirovaParameters.DMAND) : 0.0;
        double dRight = this.prohibitRight ? -this.vehicle.getParameters().getParameter(MirovaParameters.DMAND) : 0.0;

        this.desire = new Desire(dLeft, dRight, false);
        return this.desire;
    }
}
