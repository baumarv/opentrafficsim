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
 * Redistribute the car desired speeds instead of cutting the slow ones away.
 * <p>
 * Registered as {@code --study=tamavcomp}. The third and last of the desired-speed axes, after
 * {@link TamaDesiredSpeedStudy} (move everyone) and {@link TamaSlowDriverStudy} (remove the slowest).
 * Every desired speed is mapped {@code v -> pivot + factor * (v - pivot)}: the population keeps exactly
 * the drivers it had and their order, the slow ones move up a lot, the median a little and the fastest
 * slightly down.
 * </p>
 * <h3>Why a third axis</h3>
 * <p>
 * Truncation works but does so partly by renormalisation, which raises everyone — its p95 of the desired
 * speeds goes <i>up</i>, from 185.5 to 187.6 km/h. A reshaping of the lower branch alone was measured
 * and is too weak: squeezing everything below 120 km/h into [105, 120] touches 29.4 % of the population
 * and buys 4.3 km/h of harmonic mean, against 5.5 km/h for a truncation at 100 that touches 8.3 %. The
 * reason is in the measurement: the model is slower than the field across the <i>whole</i> distribution,
 * so a fix confined to the bottom cannot close it.
 * </p>
 * <p>
 * A compression towards a pivot above the median does both. At {@code pivot = 150, factor = 0.8} the
 * harmonic mean rises by 4.9 km/h — about what a truncation at 100 gives — while the p95 falls from
 * 185.5 to 178.4 and the slowest wish rises from 80 to 94 km/h. That also addresses the second thing
 * the validation comparison asks for: the simulated spread {@code p95 - p50} of the five-minute means is
 * 9.9 km/h against the field's 8.3.
 * </p>
 * <h3>The cells</h3>
 * <ul>
 * <li><b>{@code base}</b> — the distribution as measured.</li>
 * <li><b>{@code c150k08}</b> — pivot 150, factor 0.8. Harmonic +4.9, p95 178.4, spread 41.5.</li>
 * <li><b>{@code c150k07}</b> — pivot 150, factor 0.7. Harmonic +7.1, p95 174.8, spread 36.3.</li>
 * <li><b>{@code c160k08}</b> — pivot 160, factor 0.8. Harmonic +6.9, p95 180.4, spread 41.5. Separates
 * the pivot from the factor: it has almost the same harmonic gain as {@code c150k07} with a much wider
 * upper tail, so the two together say which of the two the measurement responds to.</li>
 * </ul>
 * <p>
 * Cars only, as on the other two axes.
 * </p>
 *
 * <pre>
 *   --study=tamavcomp --output=&lt;dir&gt; --dates=2025-09-17,2025-09-25,2025-10-08 \
 *   --demand=&lt;dir&gt; --replications=3
 * </pre>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class TamaSpeedSpreadStudy implements StudyDefinition
{
    /** Registered study name. */
    public static final String NAME = "tamavcomp";

    /** Label of the cell that leaves the distribution as measured. */
    public static final String BASE_LABEL = "base";

    /** Replications per cell per date. */
    public static final int DEFAULT_REPLICATIONS = 3;

    /** Parameter key naming the varied cell in the run parameters. */
    public static final String KEY_CELL = "vcomp.cell";

    /**
     * One cell: the pivot it compresses towards and by how much.
     * @param pivotKmh double; the pivot [km/h]
     * @param factor double; the compression factor; 1.0 is the identity
     */
    private record Compression(double pivotKmh, double factor)
    {
    }

    /** The cells, by label, in registration order. */
    private static final Map<String, Compression> CELLS = buildCells();

    /**
     * Builds the cells in registration order; the global run index follows it.
     * @return Map&lt;String, Compression&gt;; the cells
     */
    private static Map<String, Compression> buildCells()
    {
        Map<String, Compression> cells = new LinkedHashMap<>();
        cells.put(BASE_LABEL, new Compression(0.0, 1.0));
        cells.put("c150k08", new Compression(150.0, 0.8));
        cells.put("c150k07", new Compression(150.0, 0.7));
        cells.put("c160k08", new Compression(160.0, 0.8));
        return Collections.unmodifiableMap(cells);
    }

    /**
     * The cells of this study, by label, in registration order.
     * @return Map&lt;String, String&gt;; label to a description of what it applies
     */
    public static Map<String, String> cells()
    {
        Map<String, String> all = new LinkedHashMap<>();
        CELLS.forEach((label, compression) -> all.put(label,
                compression.factor() == 1.0 ? "as measured"
                        : "pivot " + compression.pivotKmh() + " factor " + compression.factor()));
        return Collections.unmodifiableMap(all);
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Compression of the car desired-speed distribution towards a pivot, on the frozen "
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
            for (Map.Entry<String, Compression> entry : CELLS.entrySet())
            {
                if (!wanted.contains(entry.getKey()))
                {
                    continue;
                }
                String scenarioName = facility.scenarioName(date, entry.getKey());
                manager.addScenario(scenarioName, facility.getGeneratorClass());

                ScenarioParameters params = TamaHeadwayScreeningStudy.baseline(facility, date, demandCsvPath, strict,
                        TamaHeadwayScreeningStudy.standardHeadway());
                params.set(ScenarioParameters.KEY_DESIRED_SPEED_PIVOT_CAR, entry.getValue().pivotKmh());
                params.set(ScenarioParameters.KEY_DESIRED_SPEED_COMPRESSION_CAR, entry.getValue().factor());
                params.set(KEY_CELL, entry.getKey());
                params.set(ScenarioGenerator.KEY_TACTICAL_PLANNER, "tama");
                manager.addParameterVariation(scenarioName, params);
            }
        }
        manager.setReplications(replications);
    }
}
