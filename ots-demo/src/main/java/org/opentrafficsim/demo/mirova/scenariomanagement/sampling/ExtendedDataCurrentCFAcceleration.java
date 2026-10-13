package org.opentrafficsim.demo.mirova.scenariomanagement.sampling;

import java.util.Optional;

import org.djunits.value.vdouble.scalar.Acceleration;
import org.djunits.value.vfloat.scalar.FloatAcceleration;
import org.opentrafficsim.kpi.interfaces.GtuData;

/**
 * Trajectory column {@code CurrentCFAcceleration}: the acceleration the car-following model alone asks for.
 * <p>
 * Recorded next to the acceleration that is executed; their difference is what everything between the two takes away. Read
 * from the {@link DriverStateReporter} of the vehicle's tactical planner; {@code NaN} when the planner does not report or has
 * no value this tick. The column name and description are those of the trajectory files written so far.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class ExtendedDataCurrentCFAcceleration extends ExtendedDataAcceleration<GtuData>
{
    /** Column description, kept as written so far. */
    private static final String DESCRIPTION = "Acceleration according to current car-following model [m/s\u00b2]";

    /**
     * Constructor.
     */
    public ExtendedDataCurrentCFAcceleration()
    {
        super("CurrentCFAcceleration", DESCRIPTION);
    }

    @Override
    public Optional<FloatAcceleration> getValue(final GtuData gtu)
    {
        Acceleration acceleration =
                DriverStateColumns.reporterOf(gtu).map(DriverStateReporter::carFollowingAcceleration).orElse(null);
        // Casting double to float carries NaN and the infinities over unchanged.
        return Optional.of(FloatAcceleration.ofSI(acceleration == null ? Float.NaN : (float) acceleration.si));
    }

    @Override
    public final String toString()
    {
        return DESCRIPTION;
    }
}
