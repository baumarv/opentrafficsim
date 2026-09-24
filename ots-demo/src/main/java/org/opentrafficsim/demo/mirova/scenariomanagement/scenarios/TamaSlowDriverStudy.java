package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opentrafficsim.demo.mirova.scenariomanagement.FacilityRegistry;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioGenerator;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.StudyDefinition;
import org.opentrafficsim.demo.mirova.scenariomanagement.TrafficFacility;

/**
 * Does removing the slowest car drivers raise the measured free-flow speed?
 * <p>
 * Registered as {@code --study=tamavmin}. The companion of {@link TamaDesiredSpeedStudy}, which moves
 * the whole distribution; this one removes its lower tail and renormalises, so the population contains
 * fewer slow drivers rather than the same drivers going faster.
 * </p>
 * <h3>Why the tail and not the whole distribution</h3>
 * <p>
 * The detector reports a harmonic mean, in which a vehicle at 85 km/h weighs as much as two at 170. The
 * lower tail therefore has far more leverage on the measured speed than its share of vehicles. On
 * {@code carsLimit140_DensityLow}, <b>15.6 %</b> of cars want to drive slower than 110 km/h, and removing
 * them raises the harmonic mean of the desired speeds by <b>9.0 km/h</b> — against 5.2 km/h for a uniform
 * shift of 5 km/h that touches every vehicle. Per vehicle changed it is about four times as effective,
 * and it narrows the distribution rather than carrying its width along, which is the second thing the
 * validation comparison asks for: the simulated spread {@code p95 − p50} is 9.9 km/h against the field's
 * 8.3.
 * </p>
 * <h3>The cells</h3>
 * <ul>
 * <li><b>{@code base}</b> — the distribution as measured.</li>
 * <li><b>{@code min100}</b> — no car wants less than 100 km/h; 8.3 % of the population changes, desired
 * harmonic mean +5.5 km/h.</li>
 * <li><b>{@code min110}</b> — no car wants less than 110 km/h; 15.6 % changes, +9.0 km/h.</li>
 * </ul>
 * <p>
 * Cars only, for the same reason as the companion study: trucks have their own distribution and their
 * own limit, and a cell that moved both would vary two things.
 * </p>
 * <h3>What must be watched besides the target</h3>
 * <p>
 * Removing slow cars removes moving bottlenecks, so it should raise the free-flow speed; but it also
 * changes how often a car meets a slower vehicle, which is what lane changes and therefore the merge
 * section are made of. Capacity, queue discharge and the ramp standstills have to hold up, or the cell
 * has bought the target metric with something else.
 * </p>
 *
 * <pre>
 *   --study=tamavmin --output=&lt;dir&gt; --dates=2025-09-17,2025-09-25,2025-10-08 \
 *   --demand=&lt;dir&gt; --replications=3
 * </pre>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class TamaSlowDriverStudy implements StudyDefinition
{
    /** Registered study name. */
    public static final String NAME = "tamavmin";

    /** Label of the cell that leaves the distribution whole. */
    public static final String BASE_LABEL = "base";

    /** Replications per cell per date. */
    public static final int DEFAULT_REPLICATIONS = 3;

    /** Parameter key naming the varied cell in the run parameters. */
    public static final String KEY_CELL = "vmin.cell";

    /** The lower bounds tried [km/h]; 0.0 leaves the distribution whole. */
    private static final double[] MINIMA_KMH = {100.0, 110.0};

    /** The cells, by label, in registration order; the value is the minimum [km/h]. */
    private static final Map<String, Double> CELLS = buildCells();

    /**
     * Builds the cells in registration order; the global run index follows it.
     * @return Map&lt;String, Double&gt;; label to minimum [km/h]
     */
    private static Map<String, Double> buildCells()
    {
        Map<String, Double> cells = new LinkedHashMap<>();
        cells.put(BASE_LABEL, 0.0);
        for (double minimum : MINIMA_KMH)
        {
            cells.put("min" + label(minimum), minimum);
        }
        return Collections.unmodifiableMap(cells);
    }

    /**
     * The cells of this study, by label, in registration order.
     * @return Map&lt;String, Double&gt;; label to the minimum it applies [km/h]
     */
    public static Map<String, Double> cells()
    {
        return CELLS;
    }

    /**
     * Formats a value for a label, without a decimal point where there is nothing after it.
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
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Removal of the slowest car drivers from the desired-speed distribution, on the frozen "
                + "validation set: " + CELLS.size() + " cells per date.";
    }

    /** {@inheritDoc} */
    @Override
    public void register(final ScenarioManager manager, final Map<String, String> options) throws Exception
    {
        TrafficFacility facility = FacilityRegistry.resolve(FreiburgFacility.NAME);

        List<String> dates = DateStudy.resolveDates(options.get("dates"));
        if (dates.isEmpty())
        {
            throw new IllegalArgumentException("Study '" + NAME + "' requires --dates=<comma-separated-dates|file>.");
        }
        String demandOption = options.get("demand");
        if (demandOption == null || demandOption.trim().isEmpty())
        {
            throw new IllegalArgumentException("Study '" + NAME + "' requires --demand=<csv file or directory>.");
        }
        File demandLocation = new File(demandOption.trim());
        String pattern = options.getOrDefault("pattern", DateStudy.DEFAULT_CSV_PATTERN);
        boolean strict = Boolean.parseBoolean(options.getOrDefault("strict", "false"));
        int replications =
                Integer.parseInt(options.getOrDefault("replications", String.valueOf(DEFAULT_REPLICATIONS)));

        String cellOption = options.get("cells");
        List<String> wanted = cellOption == null || cellOption.trim().isEmpty() ? new ArrayList<>(CELLS.keySet())
                : List.of(cellOption.trim().split("\\s*,\\s*"));
        for (String cellLabel : wanted)
        {
            if (!CELLS.containsKey(cellLabel))
            {
                throw new IllegalArgumentException(
                        "Study '" + NAME + "' has no cell '" + cellLabel + "'; known: " + CELLS.keySet());
            }
        }

        Map<String, File> demandPerDate = DateStudy.resolveDemandCsvs(dates, demandLocation, pattern, strict);

        // Registration order is date-major, then cell, which the global run index follows.
        for (String date : dates)
        {
            String demandCsvPath = demandPerDate.get(date).getAbsolutePath();
            for (Map.Entry<String, Double> entry : CELLS.entrySet())
            {
                if (!wanted.contains(entry.getKey()))
                {
                    continue;
                }
                String scenarioName = facility.scenarioName(date, entry.getKey());
                manager.addScenario(scenarioName, facility.getGeneratorClass());

                ScenarioParameters params = TamaHeadwayScreeningStudy.baseline(facility, date, demandCsvPath, strict,
                        TamaHeadwayScreeningStudy.standardHeadway());
                params.set(ScenarioParameters.KEY_DESIRED_SPEED_MIN_CAR, entry.getValue());
                params.set(KEY_CELL, entry.getKey());
                params.set(ScenarioGenerator.KEY_TACTICAL_PLANNER, "tama");
                manager.addParameterVariation(scenarioName, params);
            }
        }
        manager.setReplications(replications);
    }
}
