package org.opentrafficsim.demo.mirova.scenariomanagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.djunits.value.vdouble.scalar.Duration;
import org.junit.jupiter.api.Test;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.core.definitions.DefaultsNl;
import org.opentrafficsim.demo.mirova.scenariomanagement.models.LmrsDriverModelProvider;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.FreiburgNord;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.FreiburgStudyParameters;

import nl.tudelft.simulation.jstats.streams.MersenneTwister;

/**
 * Pins what the driver model selection promises: a key selects exactly one model, the default configuration applies no
 * parameter override, the scenario-level overrides come as one set and only on request, and an override that cannot be
 * applied stops the run instead of being ignored.
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class DriverModelSelectionTest
{
    /** Seed of the streams used here; no test depends on its value. */
    private static final long SEED = 42L;

    /**
     * Constructor.
     */
    public DriverModelSelectionTest()
    {
        // nothing to initialise
    }

    /**
     * The default key resolves to the LMRS provider, and an unknown key is refused with the available keys in the message.
     */
    @Test
    public void keyResolvesToExactlyOneModel()
    {
        assertEquals("lmrs", DriverModels.LMRS);
        assertEquals(LmrsDriverModelProvider.class, DriverModels.resolve(DriverModels.LMRS).getClass());
        assertTrue(DriverModels.availableKeys().contains(DriverModels.LMRS));
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> DriverModels.resolve("no-such-model"));
        assertTrue(thrown.getMessage().contains(DriverModels.LMRS), "the message does not name what is available");
    }

    /**
     * Without overrides the LMRS provider builds a factory and reports that it applied none.
     * @throws Exception when the factory cannot be built
     */
    @Test
    public void lmrsWithoutOverridesReportsUpstreamDefaults() throws Exception
    {
        LmrsDriverModelProvider provider = new LmrsDriverModelProvider();
        assertNotNull(provider.createStrategicalPlannerFactory(
                new DriverModelProvider.Request("car", DefaultsNl.CAR, new MersenneTwister(SEED), Map.of(), Map.of())));
        assertTrue(provider.describe().contains("car.parameterOverrides=none"), provider.describe());
        assertFalse(provider.describe().contains("override."), provider.describe());
    }

    /**
     * An override is applied and reported; one that names no parameter, or gives a bare number for a quantity with a unit,
     * is refused.
     * @throws Exception when the factory cannot be built
     */
    @Test
    public void overridesAreAppliedReportedOrRefused() throws Exception
    {
        Map<String, Object> headway = new LinkedHashMap<>();
        headway.put("t", Duration.ofSI(1.1));
        LmrsDriverModelProvider provider = new LmrsDriverModelProvider();
        provider.createStrategicalPlannerFactory(
                new DriverModelProvider.Request("car", DefaultsNl.CAR, new MersenneTwister(SEED), headway, Map.of()));
        assertTrue(provider.describe().contains("override.car." + ParameterTypes.T.getId() + "="), provider.describe());

        Map<String, Object> unknown = Map.of("nosuchparameter", 1.0);
        assertThrows(IllegalArgumentException.class, () -> new LmrsDriverModelProvider().createStrategicalPlannerFactory(
                new DriverModelProvider.Request("car", DefaultsNl.CAR, new MersenneTwister(SEED), unknown, Map.of())));

        Map<String, Object> bare = Map.of("t", 1.1);
        assertThrows(IllegalArgumentException.class, () -> new LmrsDriverModelProvider().createStrategicalPlannerFactory(
                new DriverModelProvider.Request("car", DefaultsNl.CAR, new MersenneTwister(SEED), bare, Map.of())));
    }

    /**
     * The parameters of a date carry no driver parameter override; the scenario set adds its eight as one set; a key the run
     * sets itself is not replaced by the set; any other value of the switch is refused.
     */
    @Test
    public void scenarioOverridesAreASetAndOffByDefault()
    {
        FreiburgNord scenario = new FreiburgNord();
        ScenarioParameters run = FreiburgStudyParameters.forDate("2025-09-22", "demand.csv", true);
        assertTrue(ParameterOverrides.forVehicleClass(run, "car").isEmpty());
        assertTrue(ParameterOverrides.forVehicleClass(run, "truck").isEmpty());
        assertTrue(ParameterOverrides.forVehicleClass(scenario.driverModelParameters(run), "car").isEmpty());

        run.set(ScenarioGenerator.KEY_LMRS_PARAMETERS, ScenarioGenerator.PARAMETER_SET_SCENARIO);
        ScenarioParameters withSet = scenario.driverModelParameters(run);
        assertEquals(4, ParameterOverrides.forVehicleClass(withSet, "car").size());
        assertEquals(4, ParameterOverrides.forVehicleClass(withSet, "truck").size());
        assertEquals(FreiburgStudyParameters.CAR_T, ParameterOverrides.forVehicleClass(withSet, "car").get("t"));
        assertTrue(ParameterOverrides.forVehicleClass(run, "car").isEmpty(), "the parameters of the run were modified");

        Duration own = Duration.ofSI(0.9);
        run.set("car." + ParameterTypes.T.getId(), own);
        assertEquals(own, ParameterOverrides.forVehicleClass(scenario.driverModelParameters(run), "car").get("t"));

        run.set(ScenarioGenerator.KEY_LMRS_PARAMETERS, "something-else");
        assertThrows(IllegalArgumentException.class, () -> scenario.driverModelParameters(run));
    }

    /**
     * A model setting is applied and reported, for every strategy the provider names; an unknown setting, an unknown
     * strategy and a switch that is neither true nor false are refused; a parameter given as text with its unit is read.
     * @throws Exception when the factory cannot be built
     */
    @Test
    public void modelSettingsAreAppliedReportedOrRefused() throws Exception
    {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put(LmrsDriverModelProvider.SYNCHRONIZATION, "ALIGN_GAP");
        settings.put(LmrsDriverModelProvider.COOPERATION, "PASSIVE_MOVING");
        settings.put(LmrsDriverModelProvider.GAP_ACCEPTANCE, "EGO_HEADWAY");
        settings.put("incentiveKeep", "false");
        settings.put("socialPressure", "true");
        LmrsDriverModelProvider provider = new LmrsDriverModelProvider();
        assertNotNull(provider.createStrategicalPlannerFactory(new DriverModelProvider.Request("car", DefaultsNl.CAR,
                new MersenneTwister(SEED), Map.of("t", "1.1 s"), settings)));
        String description = provider.describe();
        assertTrue(description.contains("setting.car.synchronization=ALIGN_GAP"), description);
        assertTrue(description.contains("setting.car.incentiveKeep=false"), description);
        assertTrue(description.contains("car.settings=changed"), description);
        assertTrue(description.contains("override.car.T=1.1"), description);

        for (Map<String, String> refused : List.of(Map.of("noSuchSetting", "true"),
                Map.of(LmrsDriverModelProvider.SYNCHRONIZATION, "NO_SUCH_STRATEGY"), Map.of("incentiveKeep", "maybe")))
        {
            assertThrows(Exception.class, () -> new LmrsDriverModelProvider().createStrategicalPlannerFactory(
                    new DriverModelProvider.Request("car", DefaultsNl.CAR, new MersenneTwister(SEED), Map.of(), refused)),
                    "accepted: " + refused);
        }
    }
}
