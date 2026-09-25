package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * The desired headway against the relaxation damping, as a grid, on days that actually break down.
 * <p>
 * Registered as {@code --study=tamatdamp}. {@code tamarampcal} screened four axes one at a time and
 * answered which two matter: the headway and the damping move the speed level and the breakdown
 * frequency, the deceleration thresholds and the cooperative threshold move only the stability and only
 * slightly. Those two are therefore <b>fixed here at the values chosen for realism</b> - {@code dec15} and
 * {@code coopSoft} - and the two that matter are crossed.
 * </p>
 * <h3>The grid</h3>
 * <p>
 * {@code T} at {@value #T_LOW} to {@value #T_HIGH} s for cars, the truck value held {@value #TRUCK_OFFSET} s
 * above it as every campaign so far has; damping at {@value #DAMPING_LOW} to {@value #DAMPING_HIGH}. Four
 * headways by three damping factors, plus one reference cell at the previous campaign's own values
 * ({@code T} 1.00/1.30, damping 1.00), which is outside the grid because 1.00 is no damping at all and the
 * grid is about how much damping to have.
 * </p>
 * <p>
 * Cartesian and not one-at-a-time, because the screening already showed the two interacting: {@code dec15}
 * alone produced 8 breakdowns in 30 runs and {@code t120} alone 35, while the pair produced 41 with a
 * markedly better discharge. A grid is the only design that separates "each helps" from "they help
 * together".
 * </p>
 * <h3>The days are chosen, not inherited</h3>
 * <p>
 * The previous campaign's three days were a poor set, and that is visible in its own output: on
 * <b>2025-09-22 the field shows no breakdown at all</b> ({@code emp_breakdown=false}) and on 2025-10-27 the
 * simulation never breaks down ({@code sim_bd_prob=0.0}), so of three days only 2025-10-07 could produce a
 * discharge or a jam duration. Most cells' figures rested on a single day.
 * </p>
 * <p>
 * The six days below are selected against `final_v2`'s per-day summary on three conditions: the field
 * breaks down, the day is not one of the two held out of the evaluation (2025-10-14, 2025-09-26), and the
 * simulation broke down often enough there to measure anything - {@code sim_bd_prob} between 0.68 and 0.92.
 * Their empirical discharge spans 3003 to 3315 veh/h, which is the range the calibration is aiming at; the
 * days with 1594 and 2280 are left out as outliers rather than quietly averaged in.
 * </p>
 * <h3>Size</h3>
 * <p>
 * Thirteen cells over six days at ten seeds is <b>780 runs</b>. Above the 600 the previous campaign was
 * held to, which is the price of six days instead of three; {@code --replications=8} brings it to 624 if
 * that matters more than the seed count.
 * </p>
 * <h3>What is watched</h3>
 * <ol>
 * <li>The <b>queue discharge</b> against the field's, which is the number the grid exists to hit. The
 * previous campaign's reference cell overshot it by 392 veh/h.</li>
 * <li>The <b>jam duration</b>, where the same cell gave 25 min against 62.5 measured.</li>
 * <li>The <b>breakdown probability</b>, where it gave 4 of 30 runs on days the field breaks down on.</li>
 * <li>The free-flow speed and the capacity distribution, neither of which may be sacrificed for the
 * above.</li>
 * <li>{@code diffused_vehicles.csv} and the completed-run count: 390 of 390 and zero last time, and a cell
 * that loses runs has not been calibrated.</li>
 * </ol>
 *
 * <pre>
 *   --study=tamatdamp --output=&lt;dir&gt; --demand=&lt;dir&gt; --replications=10
 * </pre>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class TamaHeadwayDampingStudy implements StudyDefinition
{
    /** Registered study name. */
    public static final String NAME = "tamatdamp";

    /** Label of the cell that keeps the previous campaign's own headway and damping. */
    public static final String BASE_LABEL = "ref";

    /** Replications per cell per date. Thirteen cells over six days is 780 runs. */
    public static final int DEFAULT_REPLICATIONS = 10;

    /** Parameter key naming the cell. */
    public static final String KEY_CELL = "tdamp.cell";

    /** Parameter key naming the car headway, so the evaluation need not parse labels. */
    public static final String KEY_T = "tdamp.carT";

    /** Parameter key naming the damping factor. */
    public static final String KEY_DAMPING = "tdamp.damping";

    /** The lowest car headway in the grid [s]. */
    public static final double T_LOW = 0.90;

    /** The highest car headway in the grid [s]. */
    public static final double T_HIGH = 1.20;

    /** How far above the car headway the truck's is held [s], as every campaign so far has. */
    public static final double TRUCK_OFFSET = 0.30;

    /** The lowest damping factor in the grid. */
    public static final double DAMPING_LOW = 0.40;

    /** The highest damping factor in the grid. */
    public static final double DAMPING_HIGH = 0.60;

    /** The car headways tried [s]. */
    private static final double[] HEADWAYS = {T_LOW, 1.00, 1.10, T_HIGH};

    /** The damping factors tried. */
    private static final double[] DAMPINGS = {DAMPING_LOW, 0.50, DAMPING_HIGH};

    /** Declared road behind the lane end [m], as the ramp-end behaviour needs it. */
    public static final double ROAD_BEHIND_LANE_END_M = TamaRampEndStudy.ROAD_BEHIND_LANE_END_M;

    /** The deceleration thresholds this study holds fixed, the gentler endpoint [m/s^2]. */
    public static final double THRESHOLD_MIN = -1.5;

    /** The deceleration thresholds this study holds fixed, the harder endpoint [m/s^2]. */
    public static final double THRESHOLD_MAX = -3.0;

    /** The cooperative threshold this study holds fixed, cars [m/s^2]. */
    public static final double COOPERATIVE_CAR = -2.0;

    /** The cooperative threshold this study holds fixed, trucks [m/s^2]. */
    public static final double COOPERATIVE_TRUCK = -0.5;

    /**
     * The days this study runs on, chosen against `final_v2`'s per-day summary.
     * <p>
     * Every one of them breaks down in the field, none is held out of the evaluation, and the simulation
     * broke down on each often enough to produce a discharge and a jam duration. Spelled out here rather
     * than taken from a dates file, because the choice is part of the design and a file that is edited
     * later would silently change what this study means.
     * </p>
     */
    public static final List<String> DAYS = List.of(
            "2025-09-16", "2025-09-17", "2025-10-01", "2025-10-07", "2025-10-21", "2025-10-29");

    /** The cells, by label, in registration order. */
    private static final Map<String, double[]> CELLS = buildCells();

    /**
     * Builds the grid in registration order: the reference first, then headway-major.
     * <p>
     * Headway-major so that a campaign cut short holds complete damping sweeps for the headways it reached,
     * which is readable; the other way round it would hold three partial sweeps and answer nothing.
     * </p>
     * @return Map&lt;String, double[]&gt;; label to {car headway, damping}
     */
    private static Map<String, double[]> buildCells()
    {
        Map<String, double[]> cells = new LinkedHashMap<>();
        cells.put(BASE_LABEL, new double[] {1.00, 1.00});
        for (double t : HEADWAYS)
        {
            for (double damping : DAMPINGS)
            {
                cells.put(label(t, damping), new double[] {t, damping});
            }
        }
        return Collections.unmodifiableMap(cells);
    }

    /**
     * The label of one grid cell, as {@code t090d40}.
     * @param t double; the car headway [s]
     * @param damping double; the damping factor
     * @return String; the label
     */
    private static String label(final double t, final double damping)
    {
        return String.format("t%03dd%02d", Math.round(t * 100), Math.round(damping * 100));
    }

    /**
     * Applies one cell: the fixed behaviour, the fixed thresholds, and the cell's headway and damping.
     * @param cellLabel String; the cell label
     * @param params ScenarioParameters; the parameters to write
     * @throws IllegalArgumentException when no cell carries that label
     */
    public static void applyCell(final String cellLabel, final ScenarioParameters params)
    {
        double[] cell = CELLS.get(cellLabel);
        if (cell == null)
        {
            throw new IllegalArgumentException(
                    "Study '" + NAME + "' has no cell '" + cellLabel + "'; known: " + CELLS.keySet());
        }
        Length room = Length.instantiateSI(ROAD_BEHIND_LANE_END_M);
        Acceleration gentle = Acceleration.instantiateSI(THRESHOLD_MIN);
        Acceleration hard = Acceleration.instantiateSI(THRESHOLD_MAX);
        for (String who : new String[] {"car.", "truck."})
        {
            // The ramp-end behaviour, fixed: this study is not about it any more.
            params.set(who + MirovaParameters.solveParallelAnticipation.getId(), Boolean.TRUE);
            params.set(who + MirovaParameters.lastResortMerge.getId(), Boolean.TRUE);
            params.set(who + MirovaParameters.roadBehindLaneEnd.getId(), room);
            params.set(who + MirovaParameters.dominantSideMustBeWanted.getId(), Boolean.TRUE);
            // The thresholds `tamarampcal` settled on, follower and ego together.
            params.set(who + MirovaParameters.minFollowerDecelerationThreshold.getId(), gentle);
            params.set(who + MirovaParameters.maxFollowerDecelerationThreshold.getId(), hard);
            params.set(who + MirovaParameters.minEgoDecelerationThreshold.getId(), gentle);
            params.set(who + MirovaParameters.maxEgoDecelerationThreshold.getId(), hard);
            params.set(who + MirovaParameters.RELAXATION_ACC_DAMPING_FACTOR.getId(), cell[1]);
        }
        params.set("car." + MirovaParameters.cooperativeDecelerationThreshold.getId(),
                Acceleration.instantiateSI(COOPERATIVE_CAR));
        params.set("truck." + MirovaParameters.cooperativeDecelerationThreshold.getId(),
                Acceleration.instantiateSI(COOPERATIVE_TRUCK));
        params.set("car." + ParameterTypes.T.getId(), Duration.instantiateSI(cell[0]));
        params.set("truck." + ParameterTypes.T.getId(), Duration.instantiateSI(cell[0] + TRUCK_OFFSET));
        params.set(KEY_CELL, cellLabel);
        params.set(KEY_T, cell[0]);
        params.set(KEY_DAMPING, cell[1]);
    }

    /**
     * The cells of this study, by label, in registration order.
     * @return Map&lt;String, String&gt;; label to "T / damping"
     */
    public static Map<String, String> cells()
    {
        Map<String, String> all = new LinkedHashMap<>();
        CELLS.forEach((cellLabel, values) ->
                all.put(cellLabel, String.format("T=%.2f d=%.2f", values[0], values[1])));
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
        return "Headway against relaxation damping as a grid on " + DAYS.size()
                + " days that break down: " + CELLS.size() + " cells per date.";
    }

    /** {@inheritDoc} */
    @Override
    public void register(final ScenarioManager manager, final Map<String, String> options) throws Exception
    {
        TrafficFacility facility = FacilityRegistry.resolve(FreiburgFacility.NAME);

        // The days are the design's, not the caller's - but an explicit --dates still wins, so one day can
        // be re-run without editing the study.
        List<String> dates = DateStudy.resolveDates(options.get("dates"));
        if (dates.isEmpty())
        {
            dates = new ArrayList<>(DAYS);
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
            for (String cellLabel : CELLS.keySet())
            {
                if (!wanted.contains(cellLabel))
                {
                    continue;
                }
                String scenarioName = facility.scenarioName(date, cellLabel);
                manager.addScenario(scenarioName, facility.getGeneratorClass());

                ScenarioParameters params =
                        TamaFinalValidationStudy.parameters(facility, date, demandCsvPath, strict);
                applyCell(cellLabel, params);
                params.set(ScenarioGenerator.KEY_TACTICAL_PLANNER, "tama");
                manager.addParameterVariation(scenarioName, params);
            }
        }
        manager.setReplications(replications);
    }
}
