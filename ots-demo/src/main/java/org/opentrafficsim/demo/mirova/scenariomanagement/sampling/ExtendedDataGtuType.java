package org.opentrafficsim.demo.mirova.scenariomanagement.sampling;

import java.util.Optional;

import org.opentrafficsim.kpi.interfaces.GtuData;
import org.opentrafficsim.kpi.sampling.data.ExtendedDataString;

/**
 * Trajectory column {@code GtuType}: the GTU type of the sampled vehicle.
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class ExtendedDataGtuType extends ExtendedDataString<GtuData>
{
    /** Column description. */
    private static final String DESCRIPTION = "GTU type of the sampled vehicle";

    /**
     * Constructor.
     */
    public ExtendedDataGtuType()
    {
        super("GtuType", DESCRIPTION);
    }

    @Override
    public Optional<String> getValue(final GtuData gtu)
    {
        String type = gtu == null ? null : gtu.getGtuTypeId();
        return Optional.of(type == null || type.isEmpty() ? "unknown" : type);
    }

    @Override
    public String toString()
    {
        return DESCRIPTION;
    }
}
