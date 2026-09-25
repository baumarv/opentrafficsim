package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.djunits.value.vdouble.scalar.Acceleration;
import org.djunits.value.vdouble.scalar.Duration;
import org.djunits.value.vdouble.scalar.Length;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.demo.mirova.scenariomanagement.FacilityRegistry;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioGenerator;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.StudyDefinition;
import org.opentrafficsim.demo.mirova.scenariomanagement.TrafficFacility;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;

/**
 * Recalibrating the driving parameters now that the ramp end no longer deadlocks.
 * <p>
 * Registered as {@code --study=tamarampcal}. Every cell runs the <b>new behaviour</b> - the parallel-vehicle
 * anticipation, the last-resort merge with road declared behind the lane end, and the dominance fix - and
 * differs from {@code base} in the driving parameters only. One arm, because the behaviour is no longer the
 * question: the watched runs showed the deadlock gone, and what remains is whether the parameters fitted
 * around the old behaviour are still the right ones.
 * </p>
 * <h3>The levels come from the last complete campaign, not from the defaults</h3>
 * <p>
 * Read out of {@code final_v2}'s own {@code runParams.txt} rather than from {@link MirovaParameters}, because
 * three of them differ and reasoning from the defaults would have varied the wrong way:
 * </p>
 * <table>
 * <caption>What the 496-run campaign actually ran</caption>
 * <tr><th>parameter</th><th>campaign</th><th>class default</th></tr>
 * <tr><td>{@code aRelaxDamping}</td><td><b>1.00</b></td><td>0.40</td></tr>
 * <tr><td>{@code T} car / truck</td><td>1.00 s / 1.30 s</td><td>-</td></tr>
 * <tr><td>follower thresholds, car</td><td>-2.0 / -4.0</td><td>same</td></tr>
 * <tr><td>ego thresholds</td><td><b>not set</b>, so -2.0 / -4.0</td><td>same</td></tr>
 * <tr><td>{@code COOPERATIVE_DECELERATION_THRESHOLD}</td><td>car -3.0, <b>truck -1.0</b></td><td>-3.0</td></tr>
 * </table>
 * <p>
 * The damping is the clearest case: the campaign runs it at 1.00, which is no damping at all, so trying it
 * means going <i>below</i> that rather than towards the class default.
 * </p>
 * <h3>The axes</h3>
 * <ul>
 * <li><b>{@code t110}, {@code t120}</b> -- a larger desired headway: car/truck at 1.10/1.40 and 1.20/1.50 s
 * against 1.00/1.30.</li>
 * <li><b>{@code dec15}, {@code dec10}</b> -- less aggressive deceleration thresholds, <b>follower and ego
 * moved together</b>: (-1.5, -3.0) and (-1.0, -2.5) against (-2.0, -4.0).</li>
 * <li><b>{@code coopSoft}, {@code coopHard}</b> -- the cooperative threshold at (car -2.0, truck -0.5) and
 * (car -4.0, truck -1.5) against (-3.0, -1.0).</li>
 * <li><b>{@code damp60}, {@code damp40}</b> -- relaxation damping at 0.60 and 0.40 against 1.00.</li>
 * <li><b>{@code dec15t110} … {@code dec10t120}</b> -- the thresholds and the headway together, which is the
 * combination worth having: a gentler threshold leaves a merger accepting less, and a larger headway gives
 * it more room to do so. Either alone may cost capacity where the pair does not.</li>
 * </ul>
 * <p>
 * The <b>desired speeds are settled</b> and are not an axis: pivot 150 km/h, compression 0.7, in every cell.
 * </p>
 * <h3>Size</h3>
 * <p>
 * Thirteen cells, three days, ten seeds: <b>390 runs</b>. Days that differ in what they stress - one that
 * breaks down, one that does not - because a design read only on congested days says nothing about the
 * free-flow speed three of these axes exist to move.
 * </p>
 * <h3>What is watched, in order</h3>
 * <ol>
 * <li><b>{@code diffused_vehicles.csv}</b> and the completed-run count. The behaviour these parameters sit
 * on top of lost 12 of 150 runs on the cluster before the deadlock was fixed; a cell that loses runs has not
 * been calibrated, it has failed.</li>
 * <li>The free-flow speed, about 6 % below the field in the last campaign.</li>
 * <li>Capacity, breakdown count and ramp standstills, none of which may get materially worse.</li>
 * <li>The merge positions and the deceleration a merge costs the follower.</li>
 * </ol>
 *
 * <pre>
 *   --study=tamarampcal --output=&lt;dir&gt; --dates=2025-09-22,2025-10-07,2025-10-27 \
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

    /** Label of the cell that moves no driving parameter. */
    public static final String BASE_LABEL = "base";

    /** Replications per cell per date. Thirteen cells over three days is 390 runs. */
    public static final int DEFAULT_REPLICATIONS = 10;

    /** Parameter key naming the cell. */
    public static final String KEY_CELL = "rampcal.cell";

    /** Parameter key naming the axis a cell moves, so the evaluation need not parse labels. */
    public static final String KEY_AXIS = "rampcal.axis";

    /** Declared road behind the lane end [m], the same length {@link TamaRampEndStudy} uses. */
    public static final double ROAD_BEHIND_LANE_END_M = TamaRampEndStudy.ROAD_BEHIND_LANE_END_M;

    /** The campaign's desired headway for cars [s]; the level the headway axis moves away from. */
    public static final double T_CAR_BASE = 1.00;

    /** The campaign's desired headway for trucks [s]. */
    public static final double T_TRUCK_BASE = 1.30;

    /** The campaign's gentler deceleration threshold [m/s^2], follower and ego alike. */
    public static final double THRESHOLD_MIN_BASE = -2.0;

    /** The campaign's harder deceleration threshold [m/s^2]. */
    public static final double THRESHOLD_MAX_BASE = -4.0;

    /** The campaign's relaxation damping factor: 1.00, which is no damping. */
    public static final double DAMPING_BASE = 1.00;

    /** The cells, by label, in registration order. */
    private static final Map<String, Cell> CELLS = buildCells();

    /**
     * One cell: the axis it moves, and what it sets on top of the new behaviour.
     * @param axis String; the axis this cell moves, or "none"
     * @param body Consumer&lt;ScenarioParameters&gt;; what it sets
     */
    private record Cell(String axis, Consumer<ScenarioParameters> body)
    {
    }

    /**
     * Builds the cells in registration order; the global run index follows it.
     * <p>
     * Single axes first and the combinations last, so a campaign that is cut short still holds every
     * one-at-a-time result. A combination is only readable once both of its singles are.
     * </p>
     * @return Map&lt;String, Cell&gt;; the cells
     */
    private static Map<String, Cell> buildCells()
    {
        Map<String, Cell> cells = new LinkedHashMap<>();
        cells.put(BASE_LABEL, new Cell("none", params -> { }));
        cells.put("t110", new Cell("headway", params -> setHeadway(params, 1.10, 1.40)));
        cells.put("t120", new Cell("headway", params -> setHeadway(params, 1.20, 1.50)));
        cells.put("dec15", new Cell("thresholds", params -> setThresholds(params, -1.5, -3.0)));
        cells.put("dec10", new Cell("thresholds", params -> setThresholds(params, -1.0, -2.5)));
        cells.put("coopSoft", new Cell("cooperation", params -> setCooperation(params, -2.0, -0.5)));
        cells.put("coopHard", new Cell("cooperation", params -> setCooperation(params, -4.0, -1.5)));
        cells.put("damp60", new Cell("damping", params -> setDamping(params, 0.60)));
        cells.put("damp40", new Cell("damping", params -> setDamping(params, 0.40)));
        addPair(cells, "dec15t110", -1.5, -3.0, 1.10, 1.40);
        addPair(cells, "dec15t120", -1.5, -3.0, 1.20, 1.50);
        addPair(cells, "dec10t110", -1.0, -2.5, 1.10, 1.40);
        addPair(cells, "dec10t120", -1.0, -2.5, 1.20, 1.50);
        return Collections.unmodifiableMap(cells);
    }

    /**
     * Registers one cell that moves the thresholds and the headway together.
     * @param cells Map&lt;String, Cell&gt;; the cells being built
     * @param label String; the cell label
     * @param min double; the gentler threshold [m/s^2]
     * @param max double; the harder threshold [m/s^2]
     * @param carT double; the car headway [s]
     * @param truckT double; the truck headway [s]
     */
    private static void addPair(final Map<String, Cell> cells, final String label, final double min,
            final double max, final double carT, final double truckT)
    {
        cells.put(label, new Cell("thresholds+headway", params ->
        {
            setThresholds(params, min, max);
            setHeadway(params, carT, truckT);
        }));
    }

    /**
     * The behaviour every cell runs: both ramp-end switches, the road they need, and the dominance fix.
     * <p>
     * Not an axis and not optional. The deadlock that cost the previous campaign 12 of 150 runs was traced
     * to the dominance defect and to road being granted where there is none; with those settled the
     * parameters can be read, and without them the runs cannot.
     * </p>
     * @param params ScenarioParameters; the parameters to write
     */
    private static void setNewBehaviour(final ScenarioParameters params)
    {
        Length room = Length.instantiateSI(ROAD_BEHIND_LANE_END_M);
        for (String who : new String[] {"car.", "truck."})
        {
            params.set(who + MirovaParameters.solveParallelAnticipation.getId(), Boolean.TRUE);
            params.set(who + MirovaParameters.lastResortMerge.getId(), Boolean.TRUE);
            params.set(who + MirovaParameters.roadBehindLaneEnd.getId(), room);
            params.set(who + MirovaParameters.dominantSideMustBeWanted.getId(), Boolean.TRUE);
        }
    }

    /**
     * Sets the desired headway, against the campaign's 1.00 / 1.30 s.
     * @param params ScenarioParameters; the parameters to write
     * @param car double; the car headway [s]
     * @param truck double; the truck headway [s]
     */
    private static void setHeadway(final ScenarioParameters params, final double car, final double truck)
    {
        params.set("car." + ParameterTypes.T.getId(), Duration.instantiateSI(car));
        params.set("truck." + ParameterTypes.T.getId(), Duration.instantiateSI(truck));
    }

    /**
     * Sets the deceleration thresholds, follower and ego together, on cars and trucks alike.
     * <p>
     * All four, which is the point of this axis: the follower threshold is what a merger will impose on the
     * vehicle behind it and the ego threshold what it will accept itself - one behaviour seen from two
     * sides. The earlier screening moved only the follower's lower endpoint, which left the ego threshold
     * capping the braking in {@code solveParallel} exactly where it had been.
     * </p>
     * @param params ScenarioParameters; the parameters to write
     * @param min double; the gentler endpoint [m/s^2], negative
     * @param max double; the harder endpoint [m/s^2], negative
     */
    private static void setThresholds(final ScenarioParameters params, final double min, final double max)
    {
        Acceleration gentle = Acceleration.instantiateSI(min);
        Acceleration hard = Acceleration.instantiateSI(max);
        for (String who : new String[] {"car.", "truck."})
        {
            params.set(who + MirovaParameters.minFollowerDecelerationThreshold.getId(), gentle);
            params.set(who + MirovaParameters.maxFollowerDecelerationThreshold.getId(), hard);
            params.set(who + MirovaParameters.minEgoDecelerationThreshold.getId(), gentle);
            params.set(who + MirovaParameters.maxEgoDecelerationThreshold.getId(), hard);
        }
    }

    /**
     * Sets the cooperative deceleration, against the campaign's car -3.0 / truck -1.0.
     * @param params ScenarioParameters; the parameters to write
     * @param car double; the car threshold [m/s^2], negative
     * @param truck double; the truck threshold [m/s^2], negative
     */
    private static void setCooperation(final ScenarioParameters params, final double car, final double truck)
    {
        params.set("car." + MirovaParameters.cooperativeDecelerationThreshold.getId(),
                Acceleration.instantiateSI(car));
        params.set("truck." + MirovaParameters.cooperativeDecelerationThreshold.getId(),
                Acceleration.instantiateSI(truck));
    }

    /**
     * Sets the relaxation damping factor, against the campaign's 1.00 - which is no damping at all.
     * @param params ScenarioParameters; the parameters to write
     * @param factor double; the damping factor
     */
    private static void setDamping(final ScenarioParameters params, final double factor)
    {
        params.set("car." + MirovaParameters.RELAXATION_ACC_DAMPING_FACTOR.getId(), factor);
        params.set("truck." + MirovaParameters.RELAXATION_ACC_DAMPING_FACTOR.getId(), factor);
    }

    /**
     * Applies the new behaviour and one cell on top of it, for the campaign and for a watched run alike.
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
        setNewBehaviour(params);
        cell.body().accept(params);
        params.set(KEY_CELL, label);
        params.set(KEY_AXIS, cell.axis());
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

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Driving parameters on top of the fixed ramp-end behaviour: " + CELLS.size()
                + " cells per date, four axes and the pairwise combination of two of them.";
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
