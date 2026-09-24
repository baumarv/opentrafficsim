package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.djunits.value.vdouble.scalar.Acceleration;
import org.opentrafficsim.demo.mirova.scenariomanagement.FacilityRegistry;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioGenerator;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.StudyDefinition;
import org.opentrafficsim.demo.mirova.scenariomanagement.TrafficFacility;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;

/**
 * Does bending the deceleration-threshold interpolation raise the free-flow speed without costing the rest?
 * <p>
 * Registered as {@code --study=tamacurve}. Every cell starts from the <b>frozen validation set</b>, taken from
 * {@link TamaHeadwayScreeningStudy#baseline} at {@link TamaHeadwayScreeningStudy#standardHeadway()} rather than
 * restated, so the baseline cell here is the same driver the 800-run validation was measured on.
 * </p>
 * <h3>The question</h3>
 * <p>
 * Measured on the validation run: the Freiburg on-ramp is 200 m against a route-urgency horizon of about 1433 m, so
 * the mandatory desire on it runs 0.88 to 0.98 and never enters the lower part of {@code [dMand, 1]}. Under the
 * published linear interpolation a merging driver therefore accepts -3.45 m/s&sup2; of follower deceleration at the
 * ramp's first metre and -3.92 m/s&sup2; at its last -- nearly its maximum, from the start. The suspicion under test
 * is that this depresses the mainline speed and with it the Van Aerde free-flow speed, which the validation put at
 * 124.8 km/h against 128.6 / 134.1 in the field.
 * </p>
 * <h3>The cells</h3>
 * <ul>
 * <li><b>{@code base}</b> -- the frozen set unchanged. Not a control in name only: it is what every other cell is
 * read against, and it must reproduce the validation numbers or the comparison is worthless.</li>
 * <li><b>{@code p2}, {@code p3}</b> -- the exponent {@code pThreshold} at 2.0 and 3.0. On the measured ramp desires
 * the accepted follower deceleration then runs -3.05 to -3.84 and -2.76 to -3.77, against -3.45 to -3.92.</li>
 * <li><b>{@code bmin1}</b> -- {@code bFollowerMin} at -1.0 with the interpolation left linear. This is the cell that
 * makes the exponent falsifiable: linearly, the ramp never leaves the top third of the interval, so lowering the
 * lower endpoint should move the ramp entry only from -3.45 to -3.17. A cell that moves as much as {@code p3} would
 * say the effect is not the curvature.</li>
 * <li><b>{@code p3bmin1}</b> -- both. Bending is what makes the lower endpoint reachable at all; together they put
 * the ramp entry at -2.14 while the merge point stays at -3.65.</li>
 * </ul>
 * <p>
 * The exponent is set on cars and trucks alike. It is a shape, not a level, and splitting it would double the cells
 * for a distinction this data cannot resolve. {@code bFollowerMin} is set on cars only, following the frozen set,
 * which leaves the truck value at its default.
 * </p>
 * <h3>What is being watched</h3>
 * <p>
 * The free-flow speed is the target, but it is not the criterion on its own: capacity, queue discharge, jam speed and
 * ramp standstills all have to hold up. A cell that raises the free-flow speed by making the merge disorderly has not
 * answered the question.
 * </p>
 *
 * <pre>
 *   --study=tamacurve --output=&lt;dir&gt; --dates=2025-09-22,2025-09-23 \
 *   --demand=&lt;dir&gt; --replications=4
 * </pre>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class TamaCurvatureStudy implements StudyDefinition
{
    /** Registered study name. */
    public static final String NAME = "tamacurve";

    /** Label of the cell that leaves the frozen set alone. */
    public static final String BASE_LABEL = "base";

    /** Replications per cell per date; a local run, so fewer than a campaign. */
    public static final int DEFAULT_REPLICATIONS = 4;

    /** Parameter key naming the varied cell in the run parameters. */
    public static final String KEY_CELL = "curve.cell";

    /** Parameter key naming the axis a cell moves, so the evaluation need not parse labels. */
    public static final String KEY_AXIS = "curve.axis";

    /** The exponents tried, beyond the linear default of 1.0. */
    private static final double[] EXPONENTS = {2.0, 3.0};

    /** The lowered value of the follower threshold's lower endpoint [m/s^2], against the frozen -2.0. */
    private static final double B_FOLLOWER_MIN_LOW = -1.0;

    /** The cells, by label, in registration order. */
    private static final Map<String, Cell> CELLS = buildCells();

    /**
     * One cell: which axis it moves, and what it sets on top of the frozen set.
     * @param axis String; the axis this cell moves, or "none" for the unchanged cell
     * @param body Consumer&lt;ScenarioParameters&gt;; what it sets
     */
    private record Cell(String axis, Consumer<ScenarioParameters> body)
    {
    }

    /**
     * Builds the cells in registration order; the global run index follows it.
     * @return Map&lt;String, Cell&gt;; the cells
     */
    private static Map<String, Cell> buildCells()
    {
        Map<String, Cell> cells = new LinkedHashMap<>();
        cells.put(BASE_LABEL, new Cell("none", params -> { }));
        for (double exponent : EXPONENTS)
        {
            final double value = exponent;
            cells.put("p" + label(value), new Cell("curvature", params -> setCurvature(params, value)));
        }
        cells.put("bmin" + label(Math.abs(B_FOLLOWER_MIN_LOW)),
                new Cell("bfollowermin", params -> setFollowerMin(params, B_FOLLOWER_MIN_LOW)));
        cells.put("p" + label(EXPONENTS[EXPONENTS.length - 1]) + "bmin" + label(Math.abs(B_FOLLOWER_MIN_LOW)),
                new Cell("both", params ->
                {
                    setCurvature(params, EXPONENTS[EXPONENTS.length - 1]);
                    setFollowerMin(params, B_FOLLOWER_MIN_LOW);
                }));
        return Collections.unmodifiableMap(cells);
    }

    /**
     * Sets the threshold curvature on cars and trucks alike.
     * @param params ScenarioParameters; the parameters to write
     * @param exponent double; the exponent
     */
    private static void setCurvature(final ScenarioParameters params, final double exponent)
    {
        params.set("car." + MirovaParameters.thresholdCurvature.getId(), exponent);
        params.set("truck." + MirovaParameters.thresholdCurvature.getId(), exponent);
    }

    /**
     * Sets the follower threshold's lower endpoint on cars, as the frozen set does.
     * @param params ScenarioParameters; the parameters to write
     * @param value double; the value [m/s^2], negative
     */
    private static void setFollowerMin(final ScenarioParameters params, final double value)
    {
        params.set("car." + MirovaParameters.minFollowerDecelerationThreshold.getId(),
                Acceleration.instantiateSI(value));
    }

    /**
     * The cells of this study, by label, in registration order.
     * @return Map&lt;String, String&gt;; label to the axis it moves
     */
    public static Map<String, String> cells()
    {
        Map<String, String> all = new LinkedHashMap<>();
        CELLS.forEach((label, cell) -> all.put(label, cell.axis()));
        return Collections.unmodifiableMap(all);
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
        return "Threshold-interpolation curvature and the follower threshold's lower endpoint, on the frozen "
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
            for (Map.Entry<String, Cell> entry : CELLS.entrySet())
            {
                if (!wanted.contains(entry.getKey()))
                {
                    continue;
                }
                String scenarioName = facility.scenarioName(date, entry.getKey());
                manager.addScenario(scenarioName, facility.getGeneratorClass());

                ScenarioParameters params = TamaHeadwayScreeningStudy.baseline(facility, date, demandCsvPath, strict,
                        TamaHeadwayScreeningStudy.standardHeadway());
                entry.getValue().body().accept(params);
                params.set(KEY_CELL, entry.getKey());
                params.set(KEY_AXIS, entry.getValue().axis());
                params.set(ScenarioGenerator.KEY_TACTICAL_PLANNER, "tama");
                manager.addParameterVariation(scenarioName, params);
            }
        }
        manager.setReplications(replications);
    }
}
