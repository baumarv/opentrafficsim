package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.opentrafficsim.demo.mirova.scenariomanagement.FacilityRegistry;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.StudyDefinition;
import org.opentrafficsim.demo.mirova.scenariomanagement.TrafficFacility;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;

/**
 * One-at-a-time sensitivity of the two parameters whose values nothing in the project justifies.
 * <p>
 * <b>Not registered.</b> {@code StudyRegistry} does not know this class, so it can only be selected by its fully
 * qualified name. Registering it is one line -- {@code STUDIES.put("vgaintau", VGainRelaxationSensitivityStudy.class)}
 * -- and is deliberately not taken here.
 * </p>
 * <h3>Why these two</h3>
 * <p>
 * Of the parameters the core will carry, {@code vGain} and {@code tau_relax_s} are the two whose provenance could not
 * be established at all (see {@code docs/decoupling/default-parameters.md}). {@code vGain} scales every discretionary
 * lane-change desire; {@code tau_relax_s} is the relaxation. Both are load-bearing and both are, at present,
 * unexplained numbers.
 * </p>
 * <ul>
 * <li><b>{@code vGain}</b>: the model reference's table says 15 km/h for cars and 30 for trucks, the declared default
 * is the LMRS value of 69.6 km/h, and Keane &amp; Gao are silent on it. The grid spans all three.</li>
 * <li><b>{@code tau_relax_s}</b>: Keane &amp; Gao give 15 s, the code has 20 s, and no note anywhere says why. The
 * grid brackets both.</li>
 * </ul>
 * <h3>The unit trap this study steps around</h3>
 * <p>
 * The production set writes {@code params.set("car.VGAIN", 15.0)}, and {@code ScenarioGenerator.applyParameter}
 * converts a bare number for a {@code ParameterTypeSpeed} with {@code Speed.instantiateSI} -- metres per second. The
 * production run therefore uses <b>54 km/h for cars and 108 km/h for trucks</b>, not 15 and 30. This study states its
 * grid in km/h and converts explicitly, so that a cell labelled {@code vgain15} really is 15 km/h. Its baseline cell
 * is the production point, 54 / 108 km/h, which is <i>not</i> one of the four grid values.
 * </p>
 * <h3>Design</h3>
 * <p>
 * One at a time around the production set: nine cells, being the production baseline plus four {@code vGain} values
 * plus four {@code tau_relax_s} values (20 s is the baseline and is not repeated). Trucks are scaled by the
 * production ratio of two, so a grid cell remains one number rather than two.
 * </p>
 * <pre>
 *   --study=org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.VGainRelaxationSensitivityStudy \
 *   --output=&lt;dir&gt; --dates=cluster/dates.txt --demand=cluster/demand --replications=30 --count
 * </pre>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class VGainRelaxationSensitivityStudy implements StudyDefinition
{
    /** Label of the baseline cell: the production set, unchanged. */
    public static final String BASELINE_LABEL = "baseline";

    /** Replications per cell, matching the production study so the baseline is comparable with it. */
    public static final int DEFAULT_REPLICATIONS = FreiburgProductionStudy.DEFAULT_REPLICATIONS;

    /** Ratio of the truck speed-gain to the car value, as the production set has it. */
    private static final double TRUCK_VGAIN_RATIO = 2.0;

    /** Speed-gain values of the grid [km/h]; the production point of 54 km/h is deliberately not among them. */
    private static final double[] VGAIN_KMH = {15.0, 30.0, 50.0, 69.6};

    /** Relaxation time constants of the grid [s]; 20 s is the production value and is the baseline cell. */
    private static final double[] TAU_RELAX_S = {10.0, 15.0, 25.0, 30.0};

    /** Parameter key naming the varied cell in {@code runParams.txt}. */
    public static final String KEY_CELL = "sensitivity.cell";

    /** Parameter key recording the car speed-gain of a cell in km/h, since the raw value is in m/s. */
    public static final String KEY_VGAIN_KMH = "sensitivity.vGainKmh";

    /** Parameter key recording the relaxation time constant of a cell in seconds. */
    public static final String KEY_TAU_RELAX = "sensitivity.tauRelaxS";

    /** The cells, by label, in registration order. */
    private static final Map<String, Consumer<ScenarioParameters>> CELLS = new LinkedHashMap<>();

    static
    {
        CELLS.put(BASELINE_LABEL, params ->
        {
            // Nothing to set: the production values are already in place. Recorded so every cell carries the pair.
            params.set(KEY_VGAIN_KMH, 54.0);
            params.set(KEY_TAU_RELAX, 20.0);
        });

        for (double kmh : VGAIN_KMH)
        {
            final double carSi = kmh / 3.6;
            CELLS.put("vgain" + label(kmh), params ->
            {
                params.set("car." + MirovaParameters.vGain.getId(), carSi);
                params.set("truck." + MirovaParameters.vGain.getId(), carSi * TRUCK_VGAIN_RATIO);
                params.set(KEY_VGAIN_KMH, kmh);
                params.set(KEY_TAU_RELAX, 20.0);
            });
        }

        for (double tau : TAU_RELAX_S)
        {
            CELLS.put("tau" + label(tau), params ->
            {
                params.set("car." + MirovaParameters.RELAXATION_TAU_SPACE.getId(), tau);
                params.set("truck." + MirovaParameters.RELAXATION_TAU_SPACE.getId(), tau);
                params.set(KEY_VGAIN_KMH, 54.0);
                params.set(KEY_TAU_RELAX, tau);
            });
        }
    }

    /**
     * Formats a grid value for use in a cell label, without a decimal point where there is nothing after it.
     * @param value double; the value
     * @return String; the label fragment
     */
    private static String label(final double value)
    {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value).replace('.', 'p');
    }

    @Override
    public String getName()
    {
        return "vgaintau";
    }

    @Override
    public String getDescription()
    {
        return "One-at-a-time sensitivity of vGain and the relaxation time constant around the production set: "
                + CELLS.size() + " cells per date.";
    }

    /** {@inheritDoc} */
    @Override
    public void register(final ScenarioManager manager, final Map<String, String> options) throws Exception
    {
        TrafficFacility facility = FacilityRegistry.resolve(FreiburgFacility.NAME);

        List<String> dates = DateStudy.resolveDates(options.get("dates"));
        if (dates.isEmpty())
        {
            throw new IllegalArgumentException("Study 'vgaintau' requires --dates=<comma-separated-dates|file>.");
        }
        String demandOption = options.get("demand");
        if (demandOption == null || demandOption.trim().isEmpty())
        {
            throw new IllegalArgumentException("Study 'vgaintau' requires --demand=<csv file or directory>.");
        }
        File demandLocation = new File(demandOption.trim());
        String pattern = options.getOrDefault("pattern", DateStudy.DEFAULT_CSV_PATTERN);
        boolean strict = Boolean.parseBoolean(options.getOrDefault("strict", "false"));
        int replications =
                Integer.parseInt(options.getOrDefault("replications", String.valueOf(DEFAULT_REPLICATIONS)));

        // --cells=baseline,vgain15 restricts the campaign; omitting it registers all nine.
        String cellOption = options.get("cells");
        List<String> wanted = cellOption == null || cellOption.trim().isEmpty() ? List.copyOf(CELLS.keySet())
                : List.of(cellOption.trim().split("\\s*,\\s*"));
        for (String label : wanted)
        {
            if (!CELLS.containsKey(label))
            {
                throw new IllegalArgumentException(
                        "Study 'vgaintau' has no cell '" + label + "'; known: " + CELLS.keySet());
            }
        }

        Map<String, File> demandPerDate = DateStudy.resolveDemandCsvs(dates, demandLocation, pattern, strict);

        // Registration order is date-major, then cell, which the global run index follows.
        for (String date : dates)
        {
            String demandCsvPath = demandPerDate.get(date).getAbsolutePath();
            for (String label : wanted)
            {
                String scenarioName = facility.scenarioName(date, label);
                manager.addScenario(scenarioName, facility.getGeneratorClass());

                ScenarioParameters params = FreiburgCongestedBranchStudy.forCell(facility, date, demandCsvPath, strict,
                        FreiburgProductionStudy.B, FreiburgProductionStudy.S0_CAR, FreiburgProductionStudy.A_CAR);
                CELLS.get(label).accept(params);
                params.set(KEY_CELL, label);
                manager.addParameterVariation(scenarioName, params);
            }
        }
        manager.setReplications(replications);
    }
}
