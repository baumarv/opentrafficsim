package org.opentrafficsim.demo.mirova.scenariomanagement.models;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.opentrafficsim.base.parameters.ParameterType;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.demo.mirova.scenariomanagement.DriverModelProvider;
import org.opentrafficsim.demo.mirova.scenariomanagement.ParameterOverrides;
import org.opentrafficsim.road.gtu.strategical.LaneBasedStrategicalPlannerFactory;
import org.opentrafficsim.road.gtu.strategical.LaneBasedStrategicalRoutePlannerFactory;
import org.opentrafficsim.road.gtu.tactical.lmrs.Lmrs;
import org.opentrafficsim.road.gtu.tactical.lmrs.LmrsFactory;
import org.opentrafficsim.road.gtu.tactical.util.lmrs.LmrsParameters;

/**
 * The LMRS of upstream OpenTrafficSim, as a selectable driver model.
 * <p>
 * The factory is upstream's {@link LmrsFactory} with no setting changed: car-following model, incentives, perception,
 * synchronisation, cooperation and gap acceptance are whatever upstream defaults to. Without parameter overrides in the
 * request every parameter keeps its upstream default as well, which is the benchmark configuration. Overrides, when a run asks
 * for them, are set through the factory's own parameter factory and reported by {@link #describe()}.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class LmrsDriverModelProvider implements DriverModelProvider
{
    /** Key this model is selected by. */
    public static final String KEY = "lmrs";

    /** The parameter types an override may address: the standard OTS ones and those of the LMRS. */
    private static final Map<String, ParameterType<?>> PARAMETER_TYPES =
            ParameterOverrides.parameterTypesOf(ParameterTypes.class, LmrsParameters.class);

    /** What was built, one line per applied override; empty when the model runs on upstream defaults. */
    private final List<String> applied = new ArrayList<>();

    /** Vehicle class of the request this provider served. */
    private String vehicleClass = null;

    /**
     * Constructor.
     */
    public LmrsDriverModelProvider()
    {
        // nothing to initialise; a provider is created per request
    }

    @Override
    public String key()
    {
        return KEY;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public LaneBasedStrategicalPlannerFactory<?> createStrategicalPlannerFactory(final Request request)
    {
        this.vehicleClass = request.vehicleClass();
        LmrsFactory<Lmrs> factory = new LmrsFactory<>(List.of(request.gtuType()), Lmrs::new).setStream(request.stream());
        for (Map.Entry<String, Object> override : request.parameterOverrides().entrySet())
        {
            ParameterType<?> parameterType = PARAMETER_TYPES.get(override.getKey());
            if (parameterType == null)
            {
                throw new IllegalArgumentException("Parameter override '" + request.vehicleClass() + "." + override.getKey()
                        + "' names no parameter of OTS or the LMRS. Refusing to run: an override that is silently ignored "
                        + "leaves a run labelled with a parameter it never used.");
            }
            Object value = ParameterOverrides.convert(parameterType, override.getValue());
            factory.addParameter(request.gtuType(), (ParameterType) parameterType, value);
            this.applied.add(request.vehicleClass() + "." + parameterType.getId() + "=" + value);
        }
        // The LMRS factory is its own parameter factory: it is what applies the overrides above to each vehicle.
        return new LaneBasedStrategicalRoutePlannerFactory(factory, factory);
    }

    @Override
    public String describe()
    {
        StringBuilder text = new StringBuilder();
        text.append(this.vehicleClass).append(".factory=").append(LmrsFactory.class.getName())
                .append(" (upstream settings unchanged)\n");
        if (this.applied.isEmpty())
        {
            text.append(this.vehicleClass).append(".parameterOverrides=none (upstream defaults)\n");
        }
        for (String line : this.applied)
        {
            text.append("override.").append(line).append('\n');
        }
        return text.toString();
    }
}
