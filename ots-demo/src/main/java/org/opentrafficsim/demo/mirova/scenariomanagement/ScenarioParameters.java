package org.opentrafficsim.demo.mirova.scenariomanagement;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.djunits.value.vdouble.scalar.Duration;
import org.djunits.value.vdouble.scalar.Frequency;

/**
 * The configurable parameters of one scenario run.
 * <p>
 * Parameters are stored in a map from key to value and are read back with their type. A scenario hands its defaults to the
 * {@link ScenarioManager}, a study puts its variations on top, and the result is what a run is built from. Typed accessors
 * exist for the parameters most scenarios share; anything else is set and read by key.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class ScenarioParameters
{
    /** Key of the total demand, a {@link Frequency}. */
    public static final String KEY_DEMAND = "demand";

    /** Key of the share of trucks in the demand, a fraction. */
    public static final String KEY_TRUCK_SHARE = "truckShare";

    /** Key of the seed of the run. */
    public static final String KEY_SEED = "seed";

    /** Key of the desired-speed distribution. */
    public static final String KEY_DESIRED_SPEED_DISTRIBUTION = "desiredSpeedDistribution";

    /**
     * Shift of the car desired-speed distribution [km/h]; 0.0 leaves it as measured.
     * <p>
     * A number rather than a replacement table, so that a run manifest states what was changed and by how much, and the
     * empirical distribution stays traceable to its measurement.
     * </p>
     */
    public static final String KEY_DESIRED_SPEED_SHIFT_CAR = "desiredSpeedShiftCarKmh";

    /**
     * Lowest desired speed the car population may contain [km/h]; 0.0 leaves the distribution whole.
     * <p>
     * Truncation with renormalisation, not a floor: the population has fewer slow drivers rather than a spike of them at one
     * speed. Exclusive of {@link #KEY_DESIRED_SPEED_SHIFT_CAR} in practice - a cell that set both would move the distribution
     * twice.
     * </p>
     */
    public static final String KEY_DESIRED_SPEED_MIN_CAR = "desiredSpeedMinCarKmh";

    /** Speed the car desired-speed distribution is compressed towards [km/h]; needs the factor. */
    public static final String KEY_DESIRED_SPEED_PIVOT_CAR = "desiredSpeedPivotCarKmh";

    /** Compression factor of the car desired-speed distribution; 1.0 leaves it as measured. */
    public static final String KEY_DESIRED_SPEED_COMPRESSION_CAR = "desiredSpeedCompressionCar";

    /** Key of the network name. */
    public static final String KEY_NETWORK_NAME = "networkName";

    /** Key of the random stream. */
    public static final String KEY_RANDOM_STREAM = "randomStream";

    /** Key of the share of the demand that enters from the on-ramp, a fraction. */
    public static final String KEY_MERGE_SHARE = "mergeShare";

    /** Key of the time of day at which the simulation starts, a {@link Duration}. */
    public static final String KEY_START_TIME = "startTime";

    /** Key of the warm-up time, a {@link Duration}. */
    public static final String KEY_WARMUP_TIME = "warmupTime";

    /** Key of the simulation time, a {@link Duration}. */
    public static final String KEY_SIMULATION_TIME = "simulationTime";

    /** Internal storage of parameters. */
    private final Map<String, Object> params = new HashMap<>();

    /**
     * Constructor of an empty parameter set.
     */
    public ScenarioParameters()
    {
        // empty
    }

    /**
     * Constructor with initial values.
     * @param initialValues Map&lt;String, Object&gt;; key to value; the map is copied
     */
    public ScenarioParameters(final Map<String, Object> initialValues)
    {
        this.params.putAll(initialValues);
    }

    /**
     * Sets a parameter to a given value.
     * @param key String; the parameter key
     * @param value Object; the parameter value
     * @return ScenarioParameters; this instance for fluent chaining
     */
    public ScenarioParameters set(final String key, final Object value)
    {
        this.params.put(key, value);
        return this;
    }

    /**
     * Returns the value of a parameter, or null when it is not set.
     * @param key String; the parameter key
     * @param type Class&lt;T&gt;; the expected type of the value
     * @param <T> the expected type of the value
     * @return T; the value, or null when the parameter is not set
     * @throws IllegalArgumentException when the value is not of the expected type
     */
    @SuppressWarnings("unchecked")
    public <T> T get(final String key, final Class<T> type)
    {
        Object value = this.params.get(key);
        if (value == null)
        {
            return null;
        }
        if (!type.isInstance(value))
        {
            throw new IllegalArgumentException("Parameter '" + key + "' expected type " + type.getSimpleName() + " but found "
                    + value.getClass().getSimpleName());
        }
        return (T) value;
    }

    /**
     * Returns the value of a parameter, or a default when it is not set.
     * @param key String; the parameter key
     * @param defaultValue T; the value returned when the parameter is not set
     * @param type Class&lt;T&gt;; the expected type of the value
     * @param <T> the expected type of the value
     * @return T; the value, or the default
     * @throws IllegalArgumentException when the value is not of the expected type
     */
    public <T> T getOrDefault(final String key, final T defaultValue, final Class<T> type)
    {
        T value = get(key, type);
        return value != null ? value : defaultValue;
    }

    /**
     * Returns all parameter entries in an unmodifiable map.
     * @return Map&lt;String, Object&gt;; key to value, unmodifiable
     */
    public Map<String, Object> asUnmodifiableMap()
    {
        return Collections.unmodifiableMap(this.params);
    }

    /**
     * Applies all parameters of the given set onto this instance. Only parameters present in the given set are overwritten.
     * @param overrides ScenarioParameters; the values to apply; may be null
     * @return ScenarioParameters; this instance for fluent chaining
     */
    public ScenarioParameters applyOverridesFrom(final ScenarioParameters overrides)
    {
        if (overrides == null)
        {
            return this;
        }
        for (Map.Entry<String, Object> entry : overrides.asUnmodifiableMap().entrySet())
        {
            this.params.put(entry.getKey(), entry.getValue());
        }
        return this;
    }

    /**
     * Sets the time of day at which the simulation starts.
     * @param startTime Duration; the time of day at which the simulation starts
     * @return ScenarioParameters; this instance for fluent chaining
     */
    public ScenarioParameters setStartTime(final Duration startTime)
    {
        return set(KEY_START_TIME, startTime);
    }

    /**
     * Returns the time of day at which the simulation starts.
     * @return Duration; the time of day at which the simulation starts, or null if not set
     */
    public Duration getStartTime()
    {
        return get(KEY_START_TIME, Duration.class);
    }

    /**
     * Sets the warm-up time of the simulation.
     * @param warmupTime Duration; the warm-up time
     * @return ScenarioParameters; this instance for fluent chaining
     */
    public ScenarioParameters setWarmupTime(final Duration warmupTime)
    {
        return set(KEY_WARMUP_TIME, warmupTime);
    }

    /**
     * Returns the warm-up time.
     * @return Duration; the warm-up time, or null if not set
     */
    public Duration getWarmupTime()
    {
        return get(KEY_WARMUP_TIME, Duration.class);
    }

    /**
     * Sets the simulation time, including the warm-up time.
     * @param simulationTime Duration; the simulation time
     * @return ScenarioParameters; this instance for fluent chaining
     */
    public ScenarioParameters setSimulationTime(final Duration simulationTime)
    {
        return set(KEY_SIMULATION_TIME, simulationTime);
    }

    /**
     * Returns the simulation time.
     * @return Duration; the simulation time, or null if not set
     */
    public Duration getSimulationTime()
    {
        return get(KEY_SIMULATION_TIME, Duration.class);
    }

    /**
     * Sets the total demand.
     * @param demand Frequency; the total demand
     * @return ScenarioParameters; this instance for fluent chaining
     */
    public ScenarioParameters setDemand(final Frequency demand)
    {
        return set(KEY_DEMAND, demand);
    }

    /**
     * Returns the total demand.
     * @return Frequency; the total demand, or null if not set
     */
    public Frequency getDemand()
    {
        return get(KEY_DEMAND, Frequency.class);
    }

    /**
     * Sets the share of trucks in the demand.
     * @param share double; the share of trucks, a fraction between 0 and 1
     * @return ScenarioParameters; this instance for fluent chaining
     */
    public ScenarioParameters setTruckShare(final double share)
    {
        return set(KEY_TRUCK_SHARE, share);
    }

    /**
     * Returns the share of trucks in the demand.
     * @return Double; the share of trucks, or null if not set
     */
    public Double getTruckShare()
    {
        return get(KEY_TRUCK_SHARE, Double.class);
    }

    /**
     * Sets the seed of the run.
     * @param seed long; the seed
     * @return ScenarioParameters; this instance for fluent chaining
     */
    public ScenarioParameters setSeed(final long seed)
    {
        return set(KEY_SEED, seed);
    }

    /**
     * Returns the seed of the run.
     * @return Long; the seed, or null if not set
     */
    public Long getSeed()
    {
        return get(KEY_SEED, Long.class);
    }

    /**
     * Sets the share of the demand that enters from the on-ramp.
     * @param share double; the merge share, a fraction between 0 and 1
     * @return ScenarioParameters; this instance for fluent chaining
     */
    public ScenarioParameters setMergeShare(final double share)
    {
        return set(KEY_MERGE_SHARE, share);
    }

    /**
     * Returns the share of the demand that enters from the on-ramp.
     * @return Double; the merge share, or null if not set
     */
    public Double getMergeShare()
    {
        return get(KEY_MERGE_SHARE, Double.class);
    }

    /**
     * Sets the desired-speed distribution.
     * @param dist Object; the desired-speed distribution
     * @return ScenarioParameters; this instance for fluent chaining
     */
    public ScenarioParameters setDesiredSpeedDistribution(final Object dist)
    {
        return set(KEY_DESIRED_SPEED_DISTRIBUTION, dist);
    }

    /**
     * Returns the desired-speed distribution.
     * @return Object; the desired-speed distribution, or null if not set
     */
    public Object getDesiredSpeedDistribution()
    {
        return get(KEY_DESIRED_SPEED_DISTRIBUTION, Object.class);
    }

    /**
     * Sets the network name.
     * @param name String; the network name
     * @return ScenarioParameters; this instance for fluent chaining
     */
    public ScenarioParameters setNetworkName(final String name)
    {
        return set(KEY_NETWORK_NAME, name);
    }

    /**
     * Returns the network name.
     * @return String; the network name, or null if not set
     */
    public String getNetworkName()
    {
        return get(KEY_NETWORK_NAME, String.class);
    }

    /**
     * Returns a copy of this parameter set. The values themselves are shared, not copied.
     * @return ScenarioParameters; the copy
     */
    public ScenarioParameters copy()
    {
        return new ScenarioParameters(new HashMap<>(this.params));
    }

    @Override
    public String toString()
    {
        return "ScenarioParameters" + this.params.toString();
    }
}
