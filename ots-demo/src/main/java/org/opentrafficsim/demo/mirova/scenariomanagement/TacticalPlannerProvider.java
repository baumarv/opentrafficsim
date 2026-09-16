package org.opentrafficsim.demo.mirova.scenariomanagement;

import org.opentrafficsim.road.gtu.lane.tactical.LaneBasedTacticalPlanner;
import org.opentrafficsim.road.gtu.lane.tactical.LaneBasedTacticalPlannerFactory;

/**
 * A tactical planner that a scenario can select by name, found on the classpath at run time.
 * <p>
 * The scenario parameter {@link ScenarioGenerator#KEY_TACTICAL_PLANNER} names the planner every vehicle of a scenario drives
 * with. {@code "mirova"} -- the default -- is the MiRoVA planner the scenario builds itself and needs no provider. Any other
 * name is looked up among the implementations of this interface registered through {@link java.util.ServiceLoader}, so a
 * planner that lives in another code base -- TaMA, whose OTS adapter is built against these very artefacts and can therefore
 * not be a compile-time dependency of them -- is selected by putting its jar on the classpath and naming it.
 * </p>
 * <p>
 * A provider receives the factory the scenario built, fully configured with the {@code car.} and {@code truck.} parameter
 * overrides, and returns the factory to use. It is expected to take from it what belongs to the host -- the parameters and
 * the perception -- and nothing that is model.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public interface TacticalPlannerProvider
{
    /**
     * Returns the name a scenario selects this provider by.
     * @return String; the name, compared exactly with the {@link ScenarioGenerator#KEY_TACTICAL_PLANNER} parameter
     */
    String name();

    /**
     * Returns the tactical planner factory to use for one vehicle class.
     * @param vehicleClass String; {@code "car"} or {@code "truck"}
     * @param built LaneBasedTacticalPlannerFactory&lt;? extends LaneBasedTacticalPlanner&gt;; the factory the scenario built,
     *            fully configured
     * @return LaneBasedTacticalPlannerFactory&lt;? extends LaneBasedTacticalPlanner&gt;; the factory every vehicle of the
     *         class is created with
     */
    LaneBasedTacticalPlannerFactory<? extends LaneBasedTacticalPlanner> apply(String vehicleClass,
            LaneBasedTacticalPlannerFactory<? extends LaneBasedTacticalPlanner> built);
}
