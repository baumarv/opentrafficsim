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
 * Does raising the car desired-speed distribution raise the measured free-flow speed?
 * <p>
 * Registered as {@code --study=tamavdes}. Every cell starts from the frozen validation set, taken from
 * {@link TamaHeadwayScreeningStudy#baseline} at {@link TamaHeadwayScreeningStudy#standardHeadway()}, and
 * moves the whole car desired-speed distribution by a stated offset in km/h.
 * </p>
 * <h3>The question</h3>
 * <p>
 * Measured on the validation run, with both sides brought to the same five-minute interval and the same
 * kind of mean: in intervals below 1500 veh/h the model's median speed is <b>5.5 km/h below</b> the
 * field on 16 of 16 days, its p85 4.5 km/h below on 15 of 16 and its p95 4.0 km/h below on 14 of 16,
 * while the spread {@code p95 − p50} is 9.9 km/h against the field's 8.3. That is a level shift, not a
 * shape difference, so the location of the desired-speed distribution is the candidate. The speed limit
 * is not: it is deliberately set high and the distribution does the work.
 * </p>
 * <h3>The prediction, stated before the run</h3>
 * <p>
 * It may well do very little. On the approach recorded by the exit probe, the per-vehicle maximum speed
 * has a median of 104 km/h against the distribution's intended 134, and only 5.1 % of vehicles ever
 * exceed 140 km/h where the distribution intends 40.7 %. Desired speed rarely binds in this traffic, so
 * a distribution that is moved up may simply be cut off at the same place by car following. If the
 * cells come out equal, that is the answer -- and it would move the question to the car-following side,
 * to {@code T} and {@code a}, rather than to the distribution.
 * </p>
 * <h3>The cells</h3>
 * <ul>
 * <li><b>{@code base}</b> — the frozen set, distribution as measured. The reference every other cell is
 * read against.</li>
 * <li><b>{@code plus5}, {@code plus10}</b> — the whole distribution moved up by 5 and 10 km/h. The
 * measured gap is 4 to 5.5 km/h, so a distribution that transfers one-to-one would be corrected by
 * {@code plus5}; {@code plus10} is there because the transfer is certainly damped, and two levels show
 * the slope rather than a single point.</li>
 * </ul>
 * <p>
 * The shift is on <b>cars only</b>. Trucks are governed by their own distribution and by the truck speed
 * limit, the measured gap is a mainline-speed gap, and a cell that moved both would vary two things.
 * </p>
 *
 * <pre>
 *   --study=tamavdes --output=&lt;dir&gt; --dates=2025-09-17,2025-10-08,2025-10-16 \
 *   --demand=&lt;dir&gt; --replications=3
 * </pre>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class TamaDesiredSpeedStudy implements StudyDefinition
{
    /** Registered study name. */
    public static final String NAME = "tamavdes";

    /** Label of the cell that leaves the distribution as measured. */
    public static final String BASE_LABEL = "base";

    /** Replications per cell per date. */
    public static final int DEFAULT_REPLICATIONS = 3;

    /** Parameter key naming the varied cell in the run parameters. */
    public static final String KEY_CELL = "vdes.cell";

    /** The shifts tried [km/h], beyond the unshifted distribution. */
    private static final double[] SHIFTS_KMH = {5.0, 10.0};

    /** The cells, by label, in registration order; the value is the shift [km/h]. */
    private static final Map<String, Double> CELLS = buildCells();

    /**
     * Builds the cells in registration order; the global run index follows it.
     * @return Map&lt;String, Double&gt;; label to shift [km/h]
     */
    private static Map<String, Double> buildCells()
    {
        Map<String, Double> cells = new LinkedHashMap<>();
        cells.put(BASE_LABEL, 0.0);
        for (double shift : SHIFTS_KMH)
        {
            cells.put("plus" + label(shift), shift);
        }
        return Collections.unmodifiableMap(cells);
    }

    /**
     * The cells of this study, by label, in registration order.
     * @return Map&lt;String, Double&gt;; label to the shift it applies [km/h]
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
        return "Shift of the car desired-speed distribution on the frozen validation set: " + CELLS.size()
                + " cells per date.";
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
                // Set on every cell, the baseline included, so the manifest of the reference states the
                // value it ran with rather than leaving a reader to assume the default.
                params.set(ScenarioParameters.KEY_DESIRED_SPEED_SHIFT_CAR, entry.getValue());
                params.set(KEY_CELL, entry.getKey());
                params.set(ScenarioGenerator.KEY_TACTICAL_PLANNER, "tama");
                manager.addParameterVariation(scenarioName, params);
            }
        }
        manager.setReplications(replications);
    }
}
