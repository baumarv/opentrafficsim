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
import org.djunits.value.vdouble.scalar.Length;
import org.djunits.value.vdouble.scalar.Speed;
import org.junit.jupiter.api.Test;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.core.definitions.DefaultsNl;
import org.opentrafficsim.core.gtu.GtuType;
import org.opentrafficsim.demo.mirova.scenariomanagement.models.LmrsDefaults;
import org.opentrafficsim.demo.mirova.scenariomanagement.models.LmrsDriverModelProvider;
import org.opentrafficsim.demo.mirova.scenariomanagement.models.LmrsSettings;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.FreiburgStudyParameters;
import org.opentrafficsim.road.gtu.strategical.LaneBasedStrategicalPlannerFactory;
import org.opentrafficsim.road.gtu.tactical.lmrs.LmrsFactory.Setting;
import org.opentrafficsim.road.gtu.tactical.util.lmrs.Cooperation;
import org.opentrafficsim.road.gtu.tactical.util.lmrs.GapAcceptance;
import org.opentrafficsim.road.gtu.tactical.util.lmrs.Synchronization;

import nl.tudelft.simulation.jstats.streams.MersenneTwister;

/**
 * Pins what the driver model selection promises: a key selects exactly one model, the LMRS starts from the configuration
 * final-v2 unless a run asks for upstream's factory as it is, which then applies no parameter and no setting, and an
 * override or setting that cannot be applied stops the run instead of being ignored.
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

    /** The model setting that starts the LMRS from upstream's factory as it is. */
    private static final Map<String, Object> UPSTREAM =
            Map.of(LmrsDriverModelProvider.KEY_DEFAULTS, LmrsDefaults.UPSTREAM.id());

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
     * With the defaults upstream and without overrides the LMRS provider builds a factory and reports that it applied none.
     * @throws Exception when the factory cannot be built
     */
    @Test
    public void lmrsWithUpstreamDefaultsTouchesNothing() throws Exception
    {
        LmrsDriverModelProvider provider = new LmrsDriverModelProvider();
        assertNotNull(provider.createStrategicalPlannerFactory(
                new DriverModelProvider.Request("car", DefaultsNl.CAR, new MersenneTwister(SEED), Map.of(), UPSTREAM)));
        assertTrue(provider.describe().contains("car.defaults=upstream"), provider.describe());
        assertTrue(provider.describe().contains("car.parameterOverrides=none"), provider.describe());
        assertTrue(provider.describe().contains("car.settings=upstream defaults"), provider.describe());
        assertTrue(provider.describe().contains("setting.car.SYNCHRONIZATION=PASSIVE"), provider.describe());
        assertFalse(hasLine(provider.describe(), "override."), provider.describe());
        assertFalse(hasLine(provider.describe(), "default."), provider.describe());
    }

    /**
     * Without any setting the LMRS starts from final-v2: the ShortMerge composition, courtesy for cars only, and the driver
     * parameters of final_v2, which reach the factory: the desired headway at 30 m/s is s0 + 30 T. An override replaces a
     * parameter of the defaults.
     * @throws Exception when the factory cannot be built
     */
    @Test
    public void lmrsStartsFromFinalV2() throws Exception
    {
        assertEquals(LmrsDefaults.FINAL_V2, LmrsDefaults.DEFAULT);
        for (String vehicleClass : List.of("car", "truck"))
        {
            boolean car = "car".equals(vehicleClass);
            GtuType gtuType = car ? DefaultsNl.CAR : DefaultsNl.TRUCK;
            LmrsDriverModelProvider provider = new LmrsDriverModelProvider();
            LaneBasedStrategicalPlannerFactory<?> factory = provider.createStrategicalPlannerFactory(
                    new DriverModelProvider.Request(vehicleClass, gtuType, new MersenneTwister(SEED), Map.of(), Map.of()));
            String description = provider.describe();
            assertTrue(description.contains(vehicleClass + ".defaults=final-v2"), description);
            assertTrue(description.contains("setting." + vehicleClass + ".SYNCHRONIZATION=ALIGN_GAP_MOVING"), description);
            assertTrue(description.contains("setting." + vehicleClass + ".COOPERATION=PASSIVE_MOVING"), description);
            assertTrue(description.contains("setting." + vehicleClass + ".GAP_ACCEPTANCE=INFORMED"), description);
            assertTrue(description.contains("setting." + vehicleClass + ".INCENTIVE_COURTESY=" + car), description);
            assertTrue(description.contains("default." + vehicleClass + ".Tmax="), description);
            assertFalse(hasLine(description, "override."), description);
            double expected = car ? 3.0 + 30.0 * 1.00 : 6.0 + 30.0 * 1.30;
            Length headway = factory.peekDesiredHeadway(gtuType, Speed.ofSI(30.0)).orElseThrow();
            assertEquals(expected, headway.si, 1e-9, vehicleClass);
        }

        LmrsDriverModelProvider overridden = new LmrsDriverModelProvider();
        overridden.createStrategicalPlannerFactory(new DriverModelProvider.Request("car", DefaultsNl.CAR,
                new MersenneTwister(SEED), Map.of("t", Duration.ofSI(1.1)), Map.of()));
        assertTrue(overridden.describe().matches("(?s).*override[.]car[.]T=1[.,]1.*"), overridden.describe());
        assertFalse(overridden.describe().contains("default.car.T="), overridden.describe());
        assertTrue(overridden.describe().contains("default.car.Tmax="), overridden.describe());
        assertThrows(IllegalArgumentException.class, () -> new LmrsDriverModelProvider().createStrategicalPlannerFactory(
                new DriverModelProvider.Request("car", DefaultsNl.CAR, new MersenneTwister(SEED), Map.of(),
                        Map.of(LmrsDriverModelProvider.KEY_DEFAULTS, "no-such-defaults"))));
    }

    /**
     * The defaults shortmerge build the same factory as the preset shortmerge (upstream's defaults and four settings), for
     * cars and trucks, and set no parameter.
     * @throws Exception when the factory cannot be built
     */
    @Test
    public void shortmergeDefaultsEqualThePreset() throws Exception
    {
        Map<String, Object> preset = new LinkedHashMap<>(UPSTREAM);
        preset.put(LmrsDriverModelProvider.SYNCHRONIZATION, "ALIGN_GAP_MOVING");
        preset.put(LmrsDriverModelProvider.COOPERATION, "PASSIVE_MOVING");
        preset.put(LmrsDriverModelProvider.GAP_ACCEPTANCE, "INFORMED");
        preset.put("incentiveCourtesy", "true");
        for (String vehicleClass : List.of("car", "truck"))
        {
            GtuType gtuType = "car".equals(vehicleClass) ? DefaultsNl.CAR : DefaultsNl.TRUCK;
            LmrsDriverModelProvider fromPreset = new LmrsDriverModelProvider();
            fromPreset.createStrategicalPlannerFactory(
                    new DriverModelProvider.Request(vehicleClass, gtuType, new MersenneTwister(SEED), Map.of(), preset));
            LmrsDriverModelProvider fromDefaults = new LmrsDriverModelProvider();
            fromDefaults.createStrategicalPlannerFactory(new DriverModelProvider.Request(vehicleClass, gtuType,
                    new MersenneTwister(SEED), Map.of(),
                    Map.of(LmrsDriverModelProvider.KEY_DEFAULTS, LmrsDefaults.SHORTMERGE.id())));
            assertEquals(fromPreset.describe().replace(".defaults=upstream", ".defaults=shortmerge"), fromDefaults.describe());
            assertTrue(fromDefaults.describe().contains(vehicleClass + ".parameterOverrides=none"), fromDefaults.describe());
        }
    }

    /**
     * A setting addressed to one vehicle class applies to that class only, after the settings for all classes; a prefix that
     * is no vehicle class is refused.
     * @throws Exception when the factory cannot be built
     */
    @Test
    public void settingsForOneVehicleClass() throws Exception
    {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("incentiveQueue", "false");
        settings.put("truck.incentiveQueue", "true");
        settings.put("truck.incentiveCourtesy", "true");
        LmrsDriverModelProvider truck = new LmrsDriverModelProvider();
        truck.createStrategicalPlannerFactory(
                new DriverModelProvider.Request("truck", DefaultsNl.TRUCK, new MersenneTwister(SEED), Map.of(), settings));
        assertTrue(truck.describe().contains("setting.truck.INCENTIVE_QUEUE=true"), truck.describe());
        assertTrue(truck.describe().contains("setting.truck.INCENTIVE_COURTESY=true"), truck.describe());
        LmrsDriverModelProvider car = new LmrsDriverModelProvider();
        car.createStrategicalPlannerFactory(
                new DriverModelProvider.Request("car", DefaultsNl.CAR, new MersenneTwister(SEED), Map.of(), settings));
        assertTrue(car.describe().contains("setting.car.INCENTIVE_QUEUE=false"), car.describe());

        assertThrows(IllegalArgumentException.class, () -> new LmrsDriverModelProvider().createStrategicalPlannerFactory(
                new DriverModelProvider.Request("car", DefaultsNl.CAR, new MersenneTwister(SEED), Map.of(),
                        Map.of("cra.incentiveQueue", "true"))));
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
     * The parameters of a date carry no driver parameter override and no model setting, so every driver model runs on its own
     * defaults; they do carry final_v2's car desired-speed distribution, which belongs to the scenario.
     */
    @Test
    public void dateParametersLeaveTheDriverModelAlone()
    {
        ScenarioParameters run = FreiburgStudyParameters.forDate("2025-09-22", "demand.csv", true);
        assertTrue(ParameterOverrides.forVehicleClass(run, "car").isEmpty());
        assertTrue(ParameterOverrides.forVehicleClass(run, "truck").isEmpty());
        assertTrue(ParameterOverrides.forModel(run, DriverModels.LMRS).isEmpty());
        assertEquals(150.0, run.get(ScenarioParameters.KEY_DESIRED_SPEED_PIVOT_CAR, Double.class));
        assertEquals(0.7, run.get(ScenarioParameters.KEY_DESIRED_SPEED_COMPRESSION_CAR, Double.class));
    }

    /**
     * A model setting is applied and reported, for every strategy the provider names; an unknown setting, an unknown
     * strategy and a switch that is neither true nor false are refused; a parameter given as text with its unit is read.
     * @throws Exception when the factory cannot be built
     */
    @Test
    public void modelSettingsAreAppliedReportedOrRefused() throws Exception
    {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put(LmrsDriverModelProvider.SYNCHRONIZATION, "ALIGN_GAP");
        settings.put(LmrsDriverModelProvider.COOPERATION, "PASSIVE_MOVING");
        settings.put(LmrsDriverModelProvider.GAP_ACCEPTANCE, "EGO_HEADWAY");
        settings.put("incentiveKeep", "false");
        settings.put("socialPressure", "true");
        LmrsDriverModelProvider provider = new LmrsDriverModelProvider();
        assertNotNull(provider.createStrategicalPlannerFactory(new DriverModelProvider.Request("car", DefaultsNl.CAR,
                new MersenneTwister(SEED), Map.of("t", "1.1 s"), settings)));
        String description = provider.describe();
        assertTrue(description.contains("setting.car.SYNCHRONIZATION=ALIGN_GAP"), description);
        assertTrue(description.contains("setting.car.INCENTIVE_KEEP=false"), description);
        assertTrue(description.contains("car.settings=changed: "), description);
        for (String changed : List.of("SYNCHRONIZATION", "COOPERATION", "GAP_ACCEPTANCE", "INCENTIVE_KEEP", "SOCIO_PRESSURE"))
        {
            assertTrue(description.split("car.settings=changed: ")[1].split("\\R")[0].contains(changed), description);
        }
        assertTrue(description.matches("(?s).*override[.]car[.]T=1[.,]1.*"), description);

        for (Map<String, Object> refused : List.<Map<String, Object>>of(Map.of("noSuchSetting", "true"),
                Map.of(LmrsDriverModelProvider.SYNCHRONIZATION, "NO_SUCH_STRATEGY"), Map.of("incentiveKeep", "maybe"),
                Map.of(LmrsDriverModelProvider.KEY_SETTINGS, "not a settings object")))
        {
            assertThrows(Exception.class, () -> new LmrsDriverModelProvider().createStrategicalPlannerFactory(
                    new DriverModelProvider.Request("car", DefaultsNl.CAR, new MersenneTwister(SEED), Map.of(), refused)),
                    "accepted: " + refused);
        }
    }

    /**
     * Settings stated with the types of the LMRS give the same factory as the same settings given as text; on upstream's
     * defaults, settings equal to the values of upstream are reported as upstream defaults and an empty settings object
     * changes nothing.
     * @throws Exception when the factory cannot be built
     */
    @Test
    public void typedSettingsEqualTextSettings() throws Exception
    {
        LmrsSettings typed = new LmrsSettings().set(Setting.SYNCHRONIZATION, Synchronization.ALIGN_GAP)
                .set(Setting.COOPERATION, Cooperation.NONE).set(Setting.INCENTIVE_KEEP, false);
        LmrsDriverModelProvider fromObject = new LmrsDriverModelProvider();
        Map<String, Object> object = new LinkedHashMap<>(UPSTREAM);
        object.put(LmrsDriverModelProvider.KEY_SETTINGS, typed);
        fromObject.createStrategicalPlannerFactory(
                new DriverModelProvider.Request("car", DefaultsNl.CAR, new MersenneTwister(SEED), Map.of(), object));
        Map<String, Object> text = new LinkedHashMap<>(UPSTREAM);
        text.put(LmrsDriverModelProvider.SYNCHRONIZATION, "ALIGN_GAP");
        text.put(LmrsDriverModelProvider.COOPERATION, "NONE");
        text.put("incentiveKeep", "false");
        LmrsDriverModelProvider fromText = new LmrsDriverModelProvider();
        fromText.createStrategicalPlannerFactory(
                new DriverModelProvider.Request("car", DefaultsNl.CAR, new MersenneTwister(SEED), Map.of(), text));
        assertEquals(fromText.describe(), fromObject.describe());
        assertTrue(fromObject.describe().contains("car.settings=changed: SYNCHRONIZATION, COOPERATION, INCENTIVE_KEEP"),
                fromObject.describe());

        LmrsSettings asUpstream = new LmrsSettings().set(Setting.SYNCHRONIZATION, Synchronization.PASSIVE)
                .set(Setting.GAP_ACCEPTANCE, GapAcceptance.INFORMED).set(Setting.INCENTIVE_KEEP, true);
        for (LmrsSettings unchanged : List.of(asUpstream, new LmrsSettings()))
        {
            LmrsDriverModelProvider provider = new LmrsDriverModelProvider();
            Map<String, Object> settings = new LinkedHashMap<>(UPSTREAM);
            settings.put(LmrsDriverModelProvider.KEY_SETTINGS, unchanged);
            provider.createStrategicalPlannerFactory(
                    new DriverModelProvider.Request("car", DefaultsNl.CAR, new MersenneTwister(SEED), Map.of(), settings));
            assertTrue(provider.describe().contains("car.settings=upstream defaults"), provider.describe());
        }
    }

    /**
     * Returns whether a description has a line starting with the given text. A substring test would also find the text in
     * the summary line {@code parameterOverrides=applied, see default.* and override.*}.
     * @param description String; the description of a provider
     * @param start String; the start of the line
     * @return boolean; whether such a line exists
     */
    private static boolean hasLine(final String description, final String start)
    {
        return description.lines().anyMatch((line) -> line.startsWith(start));
    }
}
