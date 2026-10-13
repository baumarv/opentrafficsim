package org.opentrafficsim.demo.mirova.scenariomanagement.sampling;

import java.util.Optional;

import org.djunits.value.vfloat.scalar.FloatDuration;
import org.opentrafficsim.kpi.interfaces.GtuData;

/**
 * Trajectory column {@code LaneChangeDesireLeft}: the desire to change lane to the left.
 * <p>
 * Read from the {@link DriverStateReporter} of the vehicle's tactical planner; {@code NaN} when the planner does not report.
 * The column name and description are those of the trajectory files written so far, so existing post-processing reads the
 * output unchanged.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class ExtendedDataLaneChangeDesireLeft extends ExtendedDataDesire<GtuData>
{
    /** Column description, kept as written so far. */
    private static final String DESCRIPTION = "Desire for lane change to the left [-, stored as Duration]";

    /**
     * Constructor.
     */
    public ExtendedDataLaneChangeDesireLeft()
    {
        super("LaneChangeDesireLeft", DESCRIPTION);
    }

    @Override
    public final Optional<FloatDuration> getValue(final GtuData gtu)
    {
        return Optional.of(FloatDuration.ofSI(DriverStateColumns.reporterOf(gtu).map((r) -> (float) r.laneChangeDesireLeft())
                .orElse(Float.NaN)));
    }

    @Override
    public final String toString()
    {
        return DESCRIPTION;
    }
}
