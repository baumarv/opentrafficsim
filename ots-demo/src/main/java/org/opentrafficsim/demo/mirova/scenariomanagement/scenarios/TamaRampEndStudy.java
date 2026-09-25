package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.djunits.value.vdouble.scalar.Length;
import org.opentrafficsim.demo.mirova.scenariomanagement.FacilityRegistry;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioGenerator;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.StudyDefinition;
import org.opentrafficsim.demo.mirova.scenariomanagement.TrafficFacility;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;

/**
 * Two ways of not braking to a stop for the end of the acceleration lane, against the final validation set.
 * <p>
 * Registered as {@code --study=tamarampend}. Every cell starts from {@link TamaFinalValidationStudy#parameters},
 * which is the set the 496-run final validation was measured on, so the {@code base} cell here is that campaign's
 * driver and the other cells differ from it in one switch each.
 * </p>
 * <h3>The question</h3>
 * <p>
 * The merge plots of the final validation show vehicles braking hard well before the end of the ramp, and the cause
 * is not the car-following model: {@code SolveParallelVehicleState} commands {@code stopBefore(laneEnd)} whenever it
 * cannot overtake the vehicle alongside, floored only by the ego deceleration threshold - which on a 200 m ramp is
 * near its maximum from the first metre, because the mandatory desire saturates there
 * ({@code docs/observations/ramp-desire-saturation.md}). A driver that merely has to wait a moment therefore brakes
 * as if it had to stop.
 * </p>
 * <h3>The cells</h3>
 * <ul>
 * <li><b>{@code base}</b> -- the final validation set unchanged. What every other cell is read against; it must
 * reproduce the campaign's numbers or the comparison says nothing.</li>
 * <li><b>{@code antic}</b> -- {@code solveParallelAnticipation}. The driver asks whether holding speed already
 * lets the blocker draw clear by the time it reaches the end of the usable lane, and if not, brakes by the least
 * that closes the deficit rather than by what it takes to stop. Where even the threshold does not suffice, nothing
 * here intervenes and the emergency stop still handles it.</li>
 * <li><b>{@code lastresort}</b> -- {@code lastResortMerge} with {@code ROAD_BEHIND_LANE_END} at
 * {@value #ROAD_BEHIND_LANE_END_M} m. A driver that can no longer stop at the deadline stops trying to and takes
 * the first gap nobody is standing in. The length is the hard shoulder behind the acceleration lane, which this
 * network models as a {@code Shoulder} rather than as a lane, so the model cannot find it by itself and the
 * scenario declares it.</li>
 * <li><b>{@code both}</b> -- both switches. They act at different moments - one before the deadline, one after -
 * so this cell is not redundant, but it is the least interpretable and is read last.</li>
 * </ul>
 * <h3>What is being watched</h3>
 * <p>
 * The primary measurement is <b>{@code diffused_vehicles.csv}</b>, which counts every vehicle the watchdog removed
 * at a lane end. It stands at <b>zero</b> across the final validation, and the emergency stop is what keeps it
 * there. A cell that merges more smoothly while losing vehicles has not improved the model; it has moved the
 * failure somewhere that is easier to overlook. After that: the merge positions and the deceleration the merge
 * costs the follower, then capacity, breakdowns and standstills, none of which may get materially worse.
 * </p>
 *
 * <pre>
 *   --study=tamarampend --output=&lt;dir&gt; --dates=2025-09-22 \
 *   --demand=&lt;dir&gt; --replications=4
 * </pre>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class TamaRampEndStudy implements StudyDefinition
{
    /** Registered study name. */
    public static final String NAME = "tamarampend";

    /** Label of the cell that leaves the final validation set alone. */
    public static final String BASE_LABEL = "base";

    /** Replications per cell per date; a local run, so fewer than a campaign. */
    public static final int DEFAULT_REPLICATIONS = 4;

    /** Parameter key naming the varied cell in the run parameters. */
    public static final String KEY_CELL = "rampend.cell";

    /** Parameter key naming the axis a cell moves, so the evaluation need not parse labels. */
    public static final String KEY_AXIS = "rampend.axis";

    /**
     * Driveable road declared behind the end of the acceleration lane [m].
     *
     * The hard shoulder, which the Freiburg-Nord network carries as a {@code Shoulder} of its own rather than as a
     * continuation of the lane, so nothing in the model can measure it. 100 m is deliberately short of the
     * shoulder's full length: it is enough road for a late merge and not so much that a driver could postpone one
     * indefinitely, and a cell that depends on the exact figure would be telling us the figure matters more than
     * the mechanism.
     */
    public static final double ROAD_BEHIND_LANE_END_M = 100.0;

    /** The cells, by label, in registration order. */
    private static final Map<String, Cell> CELLS = buildCells();

    /**
     * One cell: which axis it moves, and what it sets on top of the final validation set.
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
        cells.put("antic", new Cell("anticipation", TamaRampEndStudy::setAnticipation));
        cells.put("lastresort", new Cell("lastresort", TamaRampEndStudy::setLastResort));
        cells.put("both", new Cell("both", params ->
        {
            setAnticipation(params);
            setLastResort(params);
        }));
        return Collections.unmodifiableMap(cells);
    }

    /**
     * Turns on the parallel-vehicle anticipation, on cars and trucks alike.
     * @param params ScenarioParameters; the parameters to write
     */
    private static void setAnticipation(final ScenarioParameters params)
    {
        params.set("car." + MirovaParameters.solveParallelAnticipation.getId(), Boolean.TRUE);
        params.set("truck." + MirovaParameters.solveParallelAnticipation.getId(), Boolean.TRUE);
    }

    /**
     * Turns on the last-resort merge and declares the road it needs behind the lane end.
     * <p>
     * Both, because the switch alone changes nothing: with no road declared the driver still enters the emergency
     * stop, which is what stops the switch from inventing tarmac. A cell that set only one of the two would look
     * like a cell that had no effect.
     * </p>
     * @param params ScenarioParameters; the parameters to write
     */
    private static void setLastResort(final ScenarioParameters params)
    {
        Length room = Length.instantiateSI(ROAD_BEHIND_LANE_END_M);
        params.set("car." + MirovaParameters.lastResortMerge.getId(), Boolean.TRUE);
        params.set("truck." + MirovaParameters.lastResortMerge.getId(), Boolean.TRUE);
        params.set("car." + MirovaParameters.roadBehindLaneEnd.getId(), room);
        params.set("truck." + MirovaParameters.roadBehindLaneEnd.getId(), room);
    }

    /**
     * Applies one cell's switches to a parameter set built from the final validation baseline.
     * <p>
     * Public so that the GUI runner shows <b>this</b> cell rather than a hand-written imitation of it. A second
     * spelling of a cell is a second thing to keep in step, and the one that gets looked at is the one nobody
     * checks against the campaign.
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
        return "Anticipating a parallel vehicle and merging as a last resort, on the final validation set: "
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
