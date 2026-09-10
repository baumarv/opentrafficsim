package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import org.djunits.unit.SpeedUnit;
import org.djunits.value.vdouble.scalar.Speed;
import org.djunits.value.vdouble.scalar.Duration;
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
 * Registered as {@code --study=vgaintau}.
 * </p>
 * <h3>Why these two</h3>
 * <p>
 * Of the parameters the core will carry, {@code vGain} and {@code tau_relax_s} are the two whose provenance could not
 * be established at all (see {@code docs/decoupling/default-parameters.md}). {@code vGain} scales every discretionary
 * lane-change desire; {@code tau_relax_s} is the relaxation. Both are load-bearing and both are, at present,
 * unexplained numbers.
 * </p>
 * <ul>
 * <li><b>{@code vGain}</b>: the model now carries the intended 15 km/h for cars and 30 for trucks. The grid spans
 * that, the 54 km/h the published results ran with, and the LMRS value of 69.6 km/h. Since the model is uncalibrated
 * at the intended value, this axis is the first thing a recalibration needs.</li>
 * <li><b>{@code tau_relax_s}</b>: Keane &amp; Gao give 15 s, the code has 20 s, and no note anywhere says why. The
 * grid brackets both.</li>
 * </ul>
 * <h3>Design</h3>
 * <p>
 * One at a time around the production set: <b>eight cells</b>, being the baseline plus three {@code vGain} values plus
 * four {@code tau_relax_s} values. The axes as specified are {15, 30, 54, 69.6} km/h and {10, 15, 20, 25, 30} s; the
 * two grid points that coincide with the production set -- 15 km/h and 20 s -- <i>are</i> the baseline cell and are
 * not registered a second time. Trucks are scaled by the production ratio of two, so a grid cell remains one number
 * rather than two.
 * </p>
 * <p>
 * The 54 km/h cell is the value the published results ran with, and is labelled {@code vgain54published} for that
 * reason. Every value is stated in km/h and converted explicitly: a bare number for a speed would be read as SI, which
 * is how 15 km/h became 54 in the first place.
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

    /** The production speed gain for cars [km/h], which is the centre of the grid. */
    private static final double BASELINE_VGAIN_KMH = 15.0;

    /** The production relaxation time constant [s], which is the centre of the grid. */
    private static final double BASELINE_TAU_S = 20.0;

    /** The speed gain the published results ran with [km/h]; its cell is labelled accordingly. */
    private static final double PUBLISHED_VGAIN_KMH = 54.0;

    /**
     * Speed-gain values of the grid [km/h], excluding the baseline of 15 km/h, which is the production value.
     * <p>
     * 54 km/h is what the published results ran with; see {@code FreiburgProductionStudy.LEGACY_LABEL}.
     * </p>
     */
    private static final double[] VGAIN_KMH = {30.0, 54.0, 69.6};

    /** Relaxation time constants of the grid [s], excluding the baseline of 20 s, which is the production value. */
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
            params.set(KEY_VGAIN_KMH, BASELINE_VGAIN_KMH);
            params.set(KEY_TAU_RELAX, BASELINE_TAU_S);
        });

        for (double kmh : VGAIN_KMH)
        {
            final double carKmh = kmh;
            String suffix = kmh == PUBLISHED_VGAIN_KMH ? "published" : "";
            CELLS.put("vgain" + label(kmh) + suffix, params ->
            {
                params.set("car." + MirovaParameters.vGain.getId(), new Speed(carKmh, SpeedUnit.KM_PER_HOUR));
                params.set("truck." + MirovaParameters.vGain.getId(),
                        new Speed(carKmh * TRUCK_VGAIN_RATIO, SpeedUnit.KM_PER_HOUR));
                params.set(KEY_VGAIN_KMH, carKmh);
                params.set(KEY_TAU_RELAX, BASELINE_TAU_S);
            });
        }

        for (double tau : TAU_RELAX_S)
        {
            CELLS.put("tau" + label(tau), params ->
            {
                params.set("car." + MirovaParameters.RELAXATION_TAU_SPACE.getId(), Duration.instantiateSI(tau));
                params.set("truck." + MirovaParameters.RELAXATION_TAU_SPACE.getId(), Duration.instantiateSI(tau));
                params.set(KEY_VGAIN_KMH, BASELINE_VGAIN_KMH);
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
