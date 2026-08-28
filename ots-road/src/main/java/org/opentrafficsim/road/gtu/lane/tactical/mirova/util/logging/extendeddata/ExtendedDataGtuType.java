package org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging.extendeddata;

import org.opentrafficsim.kpi.interfaces.GtuData;
import org.opentrafficsim.kpi.sampling.data.ExtendedDataString;

/**
 * Extended data type for logging the GTU type of each sampled vehicle.
 * <p>
 * The trajectory file records speed and acceleration per vehicle but carried nothing that said what kind of vehicle it
 * was, while the detector file splits its rows by GTU type. Anything asking how cars and trucks differ - which
 * accelerations a truck actually reaches, whether a jam is held up by one class or by a lane - therefore had to be
 * answered from the detector aggregates, which cannot resolve individual vehicles, or from a proxy.
 * </p>
 * <p>
 * The obvious proxy is the maximum speed a vehicle reached, since trucks run against a lower desired speed. It does not
 * work: a car that spent its time in a queue never reaches a car's speed either, and classifying by that measure put
 * 54 % of the fleet in the truck class where the demand contains some 15 %. Restricting the classification to vehicles
 * that were observed running freely does not repair it, because the vehicles of interest are precisely those that were
 * not.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class ExtendedDataGtuType extends ExtendedDataString<GtuData>
{
    /** Singleton instance for convenient sampler registration. */
    public static final ExtendedDataGtuType INSTANCE = new ExtendedDataGtuType();

    /**
     * Constructs a new extended data type for logging the GTU type.
     */
    public ExtendedDataGtuType()
    {
        super("GtuType", "GTU type of the sampled vehicle");
    }

    /**
     * Retrieves the id of the GTU type, for example {@code NL.CAR} or {@code NL.TRUCK}.
     * @param gtu the GTU data from the sampler
     * @return the GTU type id, or "unknown" if unavailable
     */
    @Override
    public String getValue(final GtuData gtu)
    {
        String type = gtu == null ? null : gtu.getGtuTypeId();
        return type == null || type.isEmpty() ? "unknown" : type;
    }

    @Override
    public String toString()
    {
        return "GTU type of the sampled vehicle";
    }
}
