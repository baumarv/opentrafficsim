package org.opentrafficsim.demo.mirova.scenariomanagement.sampling;

import org.djunits.unit.DurationUnit;
import org.djunits.value.vfloat.scalar.FloatDuration;
import org.djunits.value.vfloat.vector.FloatDurationVector;
import org.opentrafficsim.kpi.interfaces.GtuData;
import org.opentrafficsim.kpi.sampling.data.ExtendedDataFloat;

/**
 * Extended data type for a dimensionless quantity, stored as a duration.
 * <p>
 * The quantities recorded with it are dimensionless. The trajectory output of OpenTrafficSim writes an empty unit for a
 * dimensionless value, which breaks CSV parsing downstream, so the value is carried in a {@link FloatDuration} instead. The
 * unit in the file is therefore a container and not a statement about the quantity.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 * @param <G> the GTU data type
 */
public abstract class ExtendedDataDesire<G extends GtuData>
        extends ExtendedDataFloat<DurationUnit, FloatDuration, FloatDurationVector, G>
{
    /**
     * Constructor.
     * @param id String; the unique identifier of this data type, which is the column name
     * @param description String; a description of the recorded data
     */
    public ExtendedDataDesire(final String id, final String description)
    {
        super(id, description, FloatDuration.class);
    }

    @Override
    protected FloatDuration convertValue(final float value)
    {
        return new FloatDuration(value, DurationUnit.SI);
    }

    @Override
    public FloatDuration parseValue(final String string)
    {
        return new FloatDuration(Float.parseFloat(string), DurationUnit.SI);
    }

    @Override
    protected FloatDurationVector convert(final float[] storage)
    {
        return new FloatDurationVector(storage, DurationUnit.SI);
    }

    @Override
    public FloatDuration interpolate(final FloatDuration value0, final FloatDuration value1, final double f)
    {
        return FloatDuration.interpolate(value0, value1, (float) f);
    }
}
