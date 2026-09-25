package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.djunits.value.vdouble.scalar.Acceleration;
import org.djunits.value.vdouble.scalar.Length;
import org.opentrafficsim.demo.mirova.scenariomanagement.FacilityRegistry;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioGenerator;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.StudyDefinition;
import org.opentrafficsim.demo.mirova.scenariomanagement.TrafficFacility;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;

/**
 * Does the ramp-end behaviour move the calibration, and if so, which way?
 * <p>
 * Registered as {@code --study=tamarampcal}. A <b>2 x 4 design plus one sensitivity</b>: each of four
 * calibration points is run with the new ramp-end behaviour off and on, so the study measures not whether
 * each parameter matters - that is known - but whether the new behaviour <i>changes what its best value
 * is</i>. That question cannot be answered by a one-at-a-time sweep on top of the new behaviour, because a
 * single arm cannot distinguish "this axis moved" from "everything moved".
 * </p>
 * <h3>Why the question arises</h3>
 * <p>
 * Three of the parameters below were moved in order to chase one number: the Van Aerde free-flow speed,
 * which the 496-run validation put about 6 % below the field. The reason it was low is now understood
 * differently. A merger used to brake for the end of the acceleration lane in five different states,
 * including a congested speed cap that ramps it down to 5 km/h as it approaches a place it does not have to
 * stop at, and the follower thresholds were widened to absorb the consequences. If the behaviour no longer
 * produces those consequences, the widening is calibration against a defect, and it should come back.
 * </p>
 * <h3>The cells</h3>
 * <p>
 * {@code _off} is the published behaviour, {@code _new} has both ramp-end switches on with
 * {@value TamaRampEndStudy#ROAD_BEHIND_LANE_END_M} m of road declared behind the lane end.
 * </p>
 * <ul>
 * <li><b>{@code base_off}</b> / <b>{@code base_new}</b> -- the final validation set. {@code base_off} must
 * reproduce that campaign's numbers or nothing else here can be read against anything.</li>
 * <li><b>{@code vcomp_off}</b> / <b>{@code vcomp_new}</b> -- the desired-speed compression factor at
 * {@value #COMPRESSION_RELAXED} instead of {@value TamaFinalValidationStudy#COMPRESSION}, which is
 * <i>less</i> reshaping of the distribution. The prediction worth stating in advance: if the new behaviour
 * raises the free-flow speed on its own, then less compression is needed, and the relaxed factor should
 * cost less under {@code _new} than under {@code _off}.</li>
 * <li><b>{@code p2_off}</b> / <b>{@code p2_new}</b> -- the threshold-interpolation exponent at
 * {@value #THRESHOLD_EXPONENT}, which lowers the deceleration a merger accepts over most of a 200 m
 * ramp.</li>
 * <li><b>{@code bfmin_off}</b> / <b>{@code bfmin_new}</b> -- the follower threshold's lower endpoint at
 * {@value #B_FOLLOWER_MIN} m/s&sup2;, likewise.</li>
 * <li><b>{@code road200_new}</b> -- the declared road behind the lane end at
 * {@value #ROAD_BEHIND_LONG} m instead of {@value TamaRampEndStudy#ROAD_BEHIND_LANE_END_M}. Not a
 * calibration axis but a sensitivity: if the results depend strongly on it, the mechanism is being carried
 * by a number nobody measured, and that has to be known before any of the rest is trusted.</li>
 * </ul>
 * <p>
 * The car-following headway {@code T} is deliberately <b>not</b> an axis. It sets capacity rather than
 * free-flow speed and the ramp-end behaviour does not touch it; adding it would double the campaign for a
 * question this design cannot answer anyway.
 * </p>
 * <h3>What is watched, in order</h3>
 * <ol>
 * <li><b>{@code diffused_vehicles.csv}</b>. It is zero across the final validation and the emergency stop is
 * what keeps it there. A cell that improves every other number while losing vehicles has moved the failure
 * somewhere harder to see, not removed it.</li>
 * <li>The free-flow speed, which is what the three calibration axes exist for.</li>
 * <li>Capacity, breakdown count and ramp standstills, none of which may get materially worse.</li>
 * <li>The merge positions and the deceleration a merge costs the follower - the mechanism itself.</li>
 * </ol>
 * <h3>Run it on a few days first</h3>
 * <p>
 * Nine cells over all sixteen days at ten seeds is 1440 runs, and this design's own weakness is that it
 * cannot tell a real interaction from day-to-day variation until it has enough days. So the first pass is
 * <b>two or three days</b> - about 270 runs - which is enough to see whether any pair separates at all and
 * whether the base arm still reproduces the validation. Only a pair that separates there is worth sixteen
 * days.
 * </p>
 * <p>
 * Pick days that differ in what they stress: one that breaks down and one that does not. A design read only
 * on congested days says nothing about the free-flow speed it was built to chase.
 * </p>
 *
 * <pre>
 *   # first pass, three days, about 270 runs
 *   --study=tamarampcal --output=&lt;dir&gt; --dates=2025-09-22,2025-10-07,2025-10-27  *   --demand=&lt;dir&gt; --replications=10
 *
 *   --study=tamarampcal --output=&lt;dir&gt; --dates=cluster/dates.txt \
 *   --demand=&lt;dir&gt; --replications=10
 * </pre>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class TamaRampEndCalibrationStudy implements StudyDefinition
{
    /** Registered study name. */
    public static final String NAME = "tamarampcal";

    /** Label of the cell that is the final validation set with the published behaviour. */
    public static final String BASE_LABEL = "base_off";

    /** Replications per cell per date. Nine cells over sixteen days, so this is 1440 runs. */
    public static final int DEFAULT_REPLICATIONS = 10;

    /** Parameter key naming the cell. */
    public static final String KEY_CELL = "rampcal.cell";

    /** Parameter key naming the calibration axis a cell moves, independently of the behaviour arm. */
    public static final String KEY_AXIS = "rampcal.axis";

    /** Parameter key naming the behaviour arm, so the evaluation need not parse labels. */
    public static final String KEY_ARM = "rampcal.arm";

    /** The relaxed desired-speed compression factor: less reshaping than the validation's. */
    public static final double COMPRESSION_RELAXED = 0.85;

    /** The threshold-interpolation exponent tried, against the published linear 1.0. */
    public static final double THRESHOLD_EXPONENT = 2.0;

    /** The follower threshold's lower endpoint tried [m/s^2], against the frozen -2.0. */
    public static final double B_FOLLOWER_MIN = -1.0;

    /** The longer declared road behind the lane end [m], for the sensitivity cell. */
    public static final double ROAD_BEHIND_LONG = 200.0;

    /** The cells, by label, in registration order. */
    private static final Map<String, Cell> CELLS = buildCells();

    /**
     * One cell: its behaviour arm, its calibration axis, and what it sets.
     * @param arm String; "off" for the published behaviour, "new" for the ramp-end switches
     * @param axis String; the calibration axis this cell moves, or "none"
     * @param body Consumer&lt;ScenarioParameters&gt;; what it sets on top of the final validation set
     */
    private record Cell(String arm, String axis, Consumer<ScenarioParameters> body)
    {
    }

    /**
     * Builds the cells in registration order; the global run index follows it.
     * <p>
     * Axis-major within each arm, and both arms of an axis adjacent, so that a partial campaign - one that
     * is cut short, or a subset selected with {@code --cells} - still holds complete pairs. A design whose
     * first half is all one arm answers nothing until it finishes.
     * </p>
     * @return Map&lt;String, Cell&gt;; the cells
     */
    private static Map<String, Cell> buildCells()
    {
        Map<String, Cell> cells = new LinkedHashMap<>();
        addPair(cells, "base", "none", params -> { });
        addPair(cells, "vcomp", "compression", TamaRampEndCalibrationStudy::setRelaxedCompression);
        addPair(cells, "p2", "curvature", TamaRampEndCalibrationStudy::setThresholdExponent);
        addPair(cells, "bfmin", "bfollowermin", TamaRampEndCalibrationStudy::setFollowerMin);
        cells.put("road200_new", new Cell("new", "roadlength", params ->
        {
            setNewBehaviour(params);
            setRoadBehind(params, ROAD_BEHIND_LONG);
        }));
        return Collections.unmodifiableMap(cells);
    }

    /**
     * Registers both arms of one calibration axis.
     * @param cells Map&lt;String, Cell&gt;; the cells being built
     * @param label String; the axis label
     * @param axis String; the axis name recorded in the run parameters
     * @param body Consumer&lt;ScenarioParameters&gt;; what the axis sets, applied in both arms
     */
    private static void addPair(final Map<String, Cell> cells, final String label, final String axis,
            final Consumer<ScenarioParameters> body)
    {
        cells.put(label + "_off", new Cell("off", axis, body));
        cells.put(label + "_new", new Cell("new", axis, params ->
        {
            body.accept(params);
            setNewBehaviour(params);
        }));
    }

    /**
     * Turns on both ramp-end switches and declares the road the last-resort merge needs.
     * <p>
     * Both switches together rather than one cell each: this study asks what the new behaviour does to the
     * calibration, and the two switches are two halves of one behaviour - one acts before the conforming
     * deadline and one after. {@code tamarampend} is the study that separates them.
     * </p>
     * @param params ScenarioParameters; the parameters to write
     */
    private static void setNewBehaviour(final ScenarioParameters params)
    {
        params.set("car." + MirovaParameters.solveParallelAnticipation.getId(), Boolean.TRUE);
        params.set("truck." + MirovaParameters.solveParallelAnticipation.getId(), Boolean.TRUE);
        params.set("car." + MirovaParameters.lastResortMerge.getId(), Boolean.TRUE);
        params.set("truck." + MirovaParameters.lastResortMerge.getId(), Boolean.TRUE);
        setRoadBehind(params, TamaRampEndStudy.ROAD_BEHIND_LANE_END_M);
    }

    /**
     * Declares the driveable road behind the end of a lane that ends.
     * @param params ScenarioParameters; the parameters to write
     * @param metres double; the length [m]
     */
    private static void setRoadBehind(final ScenarioParameters params, final double metres)
    {
        Length road = Length.instantiateSI(metres);
        params.set("car." + MirovaParameters.roadBehindLaneEnd.getId(), road);
        params.set("truck." + MirovaParameters.roadBehindLaneEnd.getId(), road);
    }

    /**
     * Relaxes the desired-speed compression, leaving the pivot where the validation set it.
     * @param params ScenarioParameters; the parameters to write
     */
    private static void setRelaxedCompression(final ScenarioParameters params)
    {
        params.set(ScenarioParameters.KEY_DESIRED_SPEED_COMPRESSION_CAR, COMPRESSION_RELAXED);
    }

    /**
     * Bends the deceleration-threshold interpolation, on cars and trucks alike.
     * @param params ScenarioParameters; the parameters to write
     */
    private static void setThresholdExponent(final ScenarioParameters params)
    {
        params.set("car." + MirovaParameters.thresholdCurvature.getId(), THRESHOLD_EXPONENT);
        params.set("truck." + MirovaParameters.thresholdCurvature.getId(), THRESHOLD_EXPONENT);
    }

    /**
     * Lowers the follower threshold's lower endpoint on cars, as the frozen set does.
     * @param params ScenarioParameters; the parameters to write
     */
    private static void setFollowerMin(final ScenarioParameters params)
    {
        params.set("car." + MirovaParameters.minFollowerDecelerationThreshold.getId(),
                Acceleration.instantiateSI(B_FOLLOWER_MIN));
    }

    /**
     * Applies one cell's settings to a parameter set built from the final validation baseline.
     * <p>
     * Public for the same reason {@link TamaRampEndStudy#applyCell} is: the GUI runner has to be able to
     * show <b>this</b> cell, above all a cell whose runs died on the cluster, rather than a hand-written
     * approximation of it. A second spelling of a cell is a second thing to keep in step.
     * </p>
     * @param label String; the cell label
     * @param params ScenarioParameters; the parameters to write, already carrying the baseline
     * @throws IllegalArgumentException when no cell carries that label
     */
    public static void applyCell(final String label, final ScenarioParameters params)
    {
        Cell cell = CELLS.get(label);
        if (cell == null)
        {
            throw new IllegalArgumentException(
                    "Study '" + NAME + "' has no cell '" + label + "'; known: " + CELLS.keySet());
        }
        cell.body().accept(params);
        params.set(KEY_CELL, label);
        params.set(KEY_AXIS, cell.axis());
        params.set(KEY_ARM, cell.arm());
    }

    /**
     * The cells of this study, by label, in registration order.
     * @return Map&lt;String, String&gt;; label to "arm/axis"
     */
    public static Map<String, String> cells()
    {
        Map<String, String> all = new LinkedHashMap<>();
        CELLS.forEach((label, cell) -> all.put(label, cell.arm() + "/" + cell.axis()));
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
        return "Does the ramp-end behaviour move the calibration: " + CELLS.size()
                + " cells per date, four axes in two behaviour arms plus a road-length sensitivity.";
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
        boolean strict = Boolean.parseBoolean(options.getOrDefault("strict", "true"));
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

                ScenarioParameters params =
                        TamaFinalValidationStudy.parameters(facility, date, demandCsvPath, strict);
                applyCell(entry.getKey(), params);
                params.set(ScenarioGenerator.KEY_TACTICAL_PLANNER, "tama");
                manager.addParameterVariation(scenarioName, params);
            }
        }
        manager.setReplications(replications);
    }
}
