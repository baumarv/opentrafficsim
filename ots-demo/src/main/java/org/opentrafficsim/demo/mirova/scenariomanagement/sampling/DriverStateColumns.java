package org.opentrafficsim.demo.mirova.scenariomanagement.sampling;

import java.util.Optional;

import org.opentrafficsim.kpi.interfaces.GtuData;
import org.opentrafficsim.road.network.sampling.GtuDataRoad;

/**
 * Shared access of the extended trajectory columns to the {@link DriverStateReporter} of a sampled vehicle.
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
final class DriverStateColumns
{
    /**
     * Utility class.
     */
    private DriverStateColumns()
    {
        // utility class
    }

    /**
     * Returns the reporter of a sampled vehicle, if its tactical planner is one.
     * @param gtu GtuData; the sampled vehicle
     * @return Optional&lt;DriverStateReporter&gt;; the planner as reporter, empty when it does not report
     */
    static Optional<DriverStateReporter> reporterOf(final GtuData gtu)
    {
        if (gtu instanceof GtuDataRoad road && road.getGtu().getTacticalPlanner() instanceof DriverStateReporter reporter)
        {
            return Optional.of(reporter);
        }
        return Optional.empty();
    }
}
