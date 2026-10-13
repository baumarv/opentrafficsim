package org.opentrafficsim.demo.mirova.scenariomanagement;

import java.util.Map;

import org.opentrafficsim.core.gtu.GtuType;
import org.opentrafficsim.road.gtu.strategical.LaneBasedStrategicalPlannerFactory;

import nl.tudelft.simulation.jstats.streams.StreamInterface;

/**
 * A driver model that a scenario can select by key.
 * <p>
 * This is the model-selection point of the scenario framework. A scenario never names a driver model: it asks
 * {@link DriverModels#resolve(String)} for the provider whose key is the value of the scenario parameter
 * {@link ScenarioGenerator#KEY_DRIVER_MODEL} and lets that provider build the planner factory of each vehicle class. Adding a
 * model therefore needs no change to any scenario class: a provider is either registered as a built-in in
 * {@link DriverModels}, or it lives in another code base and is found through {@link java.util.ServiceLoader} once its jar is
 * on the classpath.
 * </p>
 * <p>
 * A provider builds the complete strategical planner factory rather than wrapping one the scenario built, so that no model is
 * ever constructed merely to be replaced, and so that a model is free to bring its own parameter factory.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public interface DriverModelProvider
{
    /**
     * Returns the key a run selects this model by.
     * @return String; the key, compared exactly with the {@link ScenarioGenerator#KEY_DRIVER_MODEL} parameter
     */
    String key();

    /**
     * Builds the strategical planner factory every vehicle of one vehicle class is created with.
     * @param request Request; what the scenario provides to the model
     * @return LaneBasedStrategicalPlannerFactory&lt;?&gt;; the factory for the vehicle class of the request
     * @throws Exception when the model cannot be built from the request, for example on an override it does not know
     */
    LaneBasedStrategicalPlannerFactory<?> createStrategicalPlannerFactory(Request request) throws Exception;

    /**
     * Returns what this provider actually built, for the run to record beside its results.
     * <p>
     * A key is a label: it says which provider was asked, not what that provider built. A provider whose behaviour depends on
     * anything the key does not carry is expected to return that here, resolved rather than as it was requested. Called after
     * {@link #createStrategicalPlannerFactory(Request)} and written into the run metadata.
     * </p>
     * @return String; one or more lines of the form {@code name=value}; the plain key when there is nothing further to say
     */
    default String describe()
    {
        return key();
    }

    /**
     * What a scenario hands to a driver model when it asks for the planner factory of one vehicle class.
     * <p>
     * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
     * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
     * </p>
     * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
     * @param vehicleClass String; {@code "car"} or {@code "truck"}, the prefix parameter overrides are addressed with
     * @param gtuType GtuType; the GTU type of the vehicle class
     * @param stream StreamInterface; the random stream for driver population draws of this run
     * @param parameterOverrides Map&lt;String, Object&gt;; parameter id (lower case, without the vehicle class prefix) to value,
     *            for this vehicle class only; empty when the model is to run on its own defaults
     * @param modelSettings Map&lt;String, Object&gt;; settings addressed to this model, name to value: the scenario parameters
     *            whose key starts with the model key and a dot, without that prefix; empty when the model is to be composed
     *            as it is by default. A value is text when it comes from a command line and may be an object of the model's
     *            own types when a program sets it. What the names mean is the model's own business
     */
    record Request(String vehicleClass, GtuType gtuType, StreamInterface stream, Map<String, Object> parameterOverrides,
            Map<String, Object> modelSettings)
    {
    }
}
