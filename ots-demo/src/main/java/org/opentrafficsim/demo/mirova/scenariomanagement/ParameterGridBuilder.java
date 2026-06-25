package org.opentrafficsim.demo.mirova.scenariomanagement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opentrafficsim.base.parameters.ParameterType;

/**
 * ParameterGridBuilder — builds a Cartesian product grid of parameter variations
 * starting from a base ScenarioParameters instance.
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author Antigravity Agent
 */
public class ParameterGridBuilder
{
    /** The base scenario parameters to copy from. */
    private final ScenarioParameters baseParameters;

    /** Map storing the parameter key and its corresponding list of variation values. */
    private final Map<String, List<Object>> dimensions = new LinkedHashMap<>();

    /**
     * Constructor.
     * @param baseParameters ScenarioParameters; base parameters to copy and apply variations on
     */
    public ParameterGridBuilder(final ScenarioParameters baseParameters)
    {
        this.baseParameters = baseParameters;
    }

    /**
     * Adds a parameter dimension with a list of values to vary.
     * @param key String; the parameter key (e.g. "car.T", "truck.vGain")
     * @param values Object[]; the array of values to sweep through
     * @return ParameterGridBuilder; this builder instance for fluent chaining
     */
    public ParameterGridBuilder addDimension(final String key, final Object... values)
    {
        List<Object> valList = new ArrayList<>();
        for (Object v : values)
        {
            valList.add(v);
        }
        this.dimensions.put(key, valList);
        return this;
    }

    /**
     * Adds a parameter dimension using a prefix and a ParameterType.
     * @param prefix String; the prefix (e.g. "car." or "truck.")
     * @param parameterType ParameterType&lt;?&gt;; the parameter type to vary
     * @param values Object[]; the array of values to sweep through
     * @return ParameterGridBuilder; this builder instance for fluent chaining
     */
    public ParameterGridBuilder addDimension(final String prefix, final ParameterType<?> parameterType, final Object... values)
    {
        return addDimension(prefix + parameterType.getId(), values);
    }

    /**
     * Convenience method to add a car parameter dimension.
     * @param parameterType ParameterType&lt;?&gt;; the car parameter type to vary
     * @param values Object[]; the array of values to sweep through
     * @return ParameterGridBuilder; this builder instance for fluent chaining
     */
    public ParameterGridBuilder addCarDimension(final ParameterType<?> parameterType, final Object... values)
    {
        return addDimension("car.", parameterType, values);
    }

    /**
     * Convenience method to add a truck parameter dimension.
     * @param parameterType ParameterType&lt;?&gt;; the truck parameter type to vary
     * @param values Object[]; the array of values to sweep through
     * @return ParameterGridBuilder; this builder instance for fluent chaining
     */
    public ParameterGridBuilder addTruckDimension(final ParameterType<?> parameterType, final Object... values)
    {
        return addDimension("truck.", parameterType, values);
    }

    /**
     * Builds and returns the list of all ScenarioParameters variations representing the Cartesian product of the dimensions.
     * @return List&lt;ScenarioParameters&gt;; the list of all parameter variations
     */
    public List<ScenarioParameters> build()
    {
        List<ScenarioParameters> results = new ArrayList<>();
        // Start with a single copy of the base parameters
        results.add(this.baseParameters.copy());

        // Iteratively compute the Cartesian product across all dimensions
        for (Map.Entry<String, List<Object>> entry : this.dimensions.entrySet())
        {
            String key = entry.getKey();
            List<Object> values = entry.getValue();

            if (values.isEmpty())
            {
                continue;
            }

            List<ScenarioParameters> newResults = new ArrayList<>();
            for (ScenarioParameters baseCopy : results)
            {
                for (Object value : values)
                {
                    ScenarioParameters varCopy = baseCopy.copy();
                    varCopy.set(key, value);
                    newResults.add(varCopy);
                }
            }
            results = newResults;
        }

        return results;
    }
}
