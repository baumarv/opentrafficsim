package org.opentrafficsim.demo.mirova.scenariomanagement.sampling;

import java.util.Optional;

import org.opentrafficsim.kpi.interfaces.GtuData;
import org.opentrafficsim.kpi.sampling.data.ExtendedDataString;

/**
 * Trajectory column {@code ActionState}: the manoeuvre state the tactical planner is executing.
 * <p>
 * Read from the {@link DriverStateReporter} of the vehicle's tactical planner; {@link DriverStateReporter#NO_STATE} when the
 * planner does not report. The column name and description are those of the trajectory files written so far, so existing
 * post-processing reads the output unchanged.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class ExtendedDataActionState extends ExtendedDataString<GtuData>
{
    /** Column description, kept as written so far. */
    private static final String DESCRIPTION = "Current MiRoVA ActionState";

    /**
     * Constructor.
     */
    public ExtendedDataActionState()
    {
        super("ActionState", DESCRIPTION);
    }

    @Override
    public Optional<String> getValue(final GtuData gtu)
    {
        return Optional.of(DriverStateColumns.reporterOf(gtu).map(DriverStateReporter::manoeuvreStateName)
                .orElse(DriverStateReporter.NO_STATE));
    }

    @Override
    public String toString()
    {
        return DESCRIPTION;
    }
}
