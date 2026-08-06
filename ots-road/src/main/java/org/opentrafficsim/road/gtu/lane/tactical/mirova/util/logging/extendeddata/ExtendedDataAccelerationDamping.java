package org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging.extendeddata;

import org.djunits.value.vfloat.scalar.FloatDuration;
import org.opentrafficsim.kpi.interfaces.GtuData;
import org.opentrafficsim.kpi.sampling.data.ExtendedDataDuration;
import org.opentrafficsim.road.gtu.lane.LaneBasedGtu;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.MirovaTacticalPlanner;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.EgoContext;
import org.opentrafficsim.road.network.sampling.GtuDataRoad;

/**
 * Extended data type for logging the current acceleration damping factor during headway relaxation.
 * <p>
 * This metric indicates the scaling factor applied to positive acceleration (a value between aRelaxDamping, e.g. 0.4, and 1.0).
 * Stored using a Duration container to bypass limitations in the trajectory output framework regarding dimensionless units.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 *
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class ExtendedDataAccelerationDamping extends ExtendedDataDuration<GtuData>
{

    /** Singleton instance for convenient sampler registration. */
    public static final ExtendedDataAccelerationDamping INSTANCE = new ExtendedDataAccelerationDamping();

    /**
     * Constructs a new extended data type for logging acceleration damping factor.
     */
    public ExtendedDataAccelerationDamping()
    {
        super("AccelerationDamping", "Current positive acceleration damping factor [-, stored as Duration]");
    }

    /**
     * Retrieves the current acceleration damping factor for a specific GTU.
     *
     * @param gtu the GTU data from the sampler
     * @return the damping factor as a float [aRelaxDamping, 1.0], or NaN if unavailable
     */
    @Override
    public FloatDuration getValue(final GtuData gtu)
    {
        if (gtu instanceof GtuDataRoad road)
        {
            LaneBasedGtu lgtu = road.getGtu();
            if (lgtu.getTacticalPlanner() instanceof MirovaTacticalPlanner p)
            {
                EgoContext ego = p.getContext(EgoContext.class);
                if (ego != null)
                {
                    double factor = ego.getPrimaryRelaxationAccelerationFactor();
                    return FloatDuration.instantiateSI((float) factor);
                }
            }
        }
        return FloatDuration.instantiateSI(Float.NaN);
    }
}
