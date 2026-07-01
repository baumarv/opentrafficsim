package org.opentrafficsim.demo.mirova.scenariomanagement;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

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
    /** Helper class representing a single parameter sweep dimension. */
    public static class ParameterDimension
    {
        private final List<Object> values;
        private final BiConsumer<ScenarioParameters, Object> setter;

        public ParameterDimension(final List<Object> values, final BiConsumer<ScenarioParameters, Object> setter)
        {
            this.values = values;
            this.setter = setter;
        }

        public List<Object> getValues()
        {
            return this.values;
        }

        public void apply(final ScenarioParameters params, final Object value)
        {
            this.setter.accept(params, value);
        }
    }

    /** The base scenario parameters to copy from. */
    private final ScenarioParameters baseParameters;

    /** List of sweep dimensions. */
    private final List<ParameterDimension> dimensions = new ArrayList<>();

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
     * <p>
     * Example:
     * <pre>
     * builder.addDimension("car.T", 1.0, 1.2, 1.4);
     * </pre>
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
        this.dimensions.add(new ParameterDimension(valList, (params, val) -> params.set(key, val)));
        return this;
    }

    /**
     * Adds a dynamic parameter dimension using a custom setter lambda and values.
     * <p>
     * Example:
     * <pre>
     * builder.addDimension((params, val) -&gt; {
     *     double v = ((Number) val).doubleValue();
     *     params.set("car.T", v);
     *     params.set("truck.T", v + 0.3);
     * }, 1.0, 1.2, 1.4);
     * </pre>
     * @param setter BiConsumer&lt;ScenarioParameters, Object&gt;; functional interface to apply the value dynamically
     * @param values Object[]; the array of values to sweep through
     * @return ParameterGridBuilder; this builder instance for fluent chaining
     */
    public ParameterGridBuilder addDimension(final BiConsumer<ScenarioParameters, Object> setter, final Object... values)
    {
        List<Object> valList = new ArrayList<>();
        for (Object v : values)
        {
            valList.add(v);
        }
        this.dimensions.add(new ParameterDimension(valList, setter));
        return this;
    }

    /**
     * Adds a coupled dimension mapping multiple keys to value tuples.
     * <p>
     * Example:
     * <pre>
     * builder.addDimension(
     *     new String[]{"car.T", "truck.T"},
     *     new Object[]{1.0, 1.4},
     *     new Object[]{1.2, 1.7},
     *     new Object[]{1.4, 2.0}
     * );
     * </pre>
     * @param keys String[]; the array of parameter keys to set simultaneously
     * @param valueTuples Object[]...; the array of value tuples corresponding to the keys
     * @return ParameterGridBuilder; this builder instance for fluent chaining
     */
    public ParameterGridBuilder addDimension(final String[] keys, final Object[]... valueTuples)
    {
        List<Object> valList = new ArrayList<>();
        for (Object[] tuple : valueTuples)
        {
            valList.add(tuple);
        }
        this.dimensions.add(new ParameterDimension(valList, (params, val) -> {
            Object[] tuple = (Object[]) val;
            for (int i = 0; i < keys.length; i++)
            {
                if (i < tuple.length)
                {
                    params.set(keys[i], tuple[i]);
                }
            }
        }));
        return this;
    }

    /**
     * Adds a coupled dimension mapping multiple keys to values, changed in parallel.
     * <p>
     * The same value is set on all keys for each step.
     * Automatically handles min/max deceleration parameter scaling if MIN_FOLLOWER or MIN_EGO keys are matched.
     * <p>
     * Example:
     * <pre>
     * builder.addDimensionParallel(
     *     new String[]{"coopDecel", "car.cooperativeDecelerationThreshold", "truck.cooperativeDecelerationThreshold"},
     *     -2.0, -3.0, -4.0
     * );
     * </pre>
     * @param keys String[]; the array of parameter keys to set simultaneously
     * @param values Object[]; the array of base values to sweep through in parallel
     * @return ParameterGridBuilder; this builder instance for fluent chaining
     */
    public ParameterGridBuilder addDimensionParallel(final String[] keys, final Object... values)
    {
        List<Object> valList = new ArrayList<>();
        for (Object v : values)
        {
            valList.add(v);
        }
        this.dimensions.add(new ParameterDimension(valList, (params, val) -> {
            for (String key : keys)
            {
                params.set(key, val);

                // Automatically handle coupled min/max deceleration logic (case-insensitive)
                String upperKey = key.toUpperCase();
                if (upperKey.endsWith("MIN_FOLLOWER_DECELERATION_THRESHOLD"))
                {
                    String maxKey = key.substring(0, key.length() - "MIN_FOLLOWER_DECELERATION_THRESHOLD".length())
                            + "MAX_FOLLOWER_DECELERATION_THRESHOLD";
                    if (val instanceof Number)
                    {
                        params.set(maxKey, ((Number) val).doubleValue() * 2.0);
                    }
                }
                else if (upperKey.endsWith("MIN_EGO_DECELERATION_THRESHOLD"))
                {
                    String maxKey = key.substring(0, key.length() - "MIN_EGO_DECELERATION_THRESHOLD".length())
                            + "MAX_EGO_DECELERATION_THRESHOLD";
                    if (val instanceof Number)
                    {
                        params.set(maxKey, ((Number) val).doubleValue() * 2.0);
                    }
                }
            }
        }));
        return this;
    }

    /**
     * Adds a coupled dimension mapping multiple key groups to value tuples, changed in parallel.
     * <p>
     * For each value tuple, the j-th element is set on all keys in the j-th key group.
     * <p>
     * Example:
     * <pre>
     * builder.addDimensionParallel(
     *     new String[][] {
     *         {"followerMinDecel", "car.minFollowerDecel", "truck.minFollowerDecel"},
     *         {"car.maxFollowerDecel", "truck.maxFollowerDecel"}
     *     },
     *     new Object[]{-1.0, -2.0},  // min=-1.0, max=-2.0
     *     new Object[]{-1.5, -3.0},  // min=-1.5, max=-3.0
     *     new Object[]{-2.0, -4.0}   // min=-2.0, max=-4.0
     * );
     * </pre>
     * @param keyGroups String[][]; the array of key groups
     * @param valueTuples Object[]...; the array of value tuples corresponding to the key groups
     * @return ParameterGridBuilder; this builder instance for fluent chaining
     */
    public ParameterGridBuilder addDimensionParallel(final String[][] keyGroups, final Object[]... valueTuples)
    {
        List<Object> valList = new ArrayList<>();
        for (Object[] tuple : valueTuples)
        {
            valList.add(tuple);
        }
        this.dimensions.add(new ParameterDimension(valList, (params, val) -> {
            Object[] tuple = (Object[]) val;
            for (int j = 0; j < keyGroups.length; j++)
            {
                if (j < tuple.length)
                {
                    Object elementValue = tuple[j];
                    for (String key : keyGroups[j])
                    {
                        params.set(key, elementValue);
                    }
                }
            }
        }));
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
        for (ParameterDimension dim : this.dimensions)
        {
            List<Object> values = dim.getValues();
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
                    dim.apply(varCopy, value);
                    newResults.add(varCopy);
                }
            }
            results = newResults;
        }

        return results;
    }

    /**
     * Builds and returns a list of ScenarioParameters variations where each parameter value
     * in each dimension is tested one-at-a-time against the base parameters.
     * This avoids a Cartesian product: only one parameter (or group of parallel parameters)
     * is changed from the base set at any time.
     * <p>
     * The first element of the returned list is always a copy of the base parameters.
     * Subsequent elements are variations where exactly one value of one dimension is applied,
     * while keeping all other dimensions at their base values.
     * Duplicates (configurations that result in parameters identical to the base parameter set
     * or to other generated variations) are automatically skipped.
     * 
     * @return List&lt;ScenarioParameters&gt;; the list of isolated parameter variations
     */
    public List<ScenarioParameters> buildIsolated()
    {
        List<ScenarioParameters> results = new ArrayList<>();
        ScenarioParameters baseCopy = this.baseParameters.copy();
        results.add(baseCopy);

        for (ParameterDimension dim : this.dimensions)
        {
            for (Object value : dim.getValues())
            {
                ScenarioParameters varCopy = this.baseParameters.copy();
                dim.apply(varCopy, value);
                
                // Check if this configuration is already in the results list
                boolean isDuplicate = false;
                for (ScenarioParameters existing : results)
                {
                    if (existing.asUnmodifiableMap().equals(varCopy.asUnmodifiableMap()))
                    {
                        isDuplicate = true;
                        break;
                    }
                }
                
                if (!isDuplicate)
                {
                    results.add(varCopy);
                }
            }
        }
        return results;
    }
}
