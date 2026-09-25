package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.djunits.value.vdouble.scalar.Length;
import org.opentrafficsim.demo.mirova.scenariomanagement.FacilityRegistry;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioGenerator;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioSimulationScript;
import org.opentrafficsim.demo.mirova.scenariomanagement.TrafficFacility;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;

/**
 * Watch one cell of {@link TamaRampEndStudy} in the animation, to see what the ramp end actually looks like.
 * <p>
 * The numbers say a merger brakes hard a long way before the end of the acceleration lane, and two switches are
 * meant to stop it. Whether the result <i>looks</i> like driving is not something the aggregates answer, and it is
 * the one question that has to be answered by looking.
 * </p>
 * <h3>Why this exists rather than another hand-written runner</h3>
 * <p>
 * {@link RunFreiburgNord} sets its own handful of parameters, and those have drifted from the validated set - a
 * local run that quietly uses stale values is worse than no local run, because what is watched is then not what was
 * measured. This builds its parameters from {@link TamaFinalValidationStudy#parameters} and applies the cell through
 * {@link TamaRampEndStudy#applyCell}, which is the same call the campaign makes. There is no second spelling of a
 * cell here to keep in step.
 * </p>
 * <h3>Arguments</h3>
 * <ul>
 * <li>{@code --study=} {@code tamarampend} (the default) or {@code tamarampcal}.</li>
 * <li>{@code --cell=} a cell of that study. For {@code tamarampend}: {@code base}, {@code antic},
 * {@code lastresort}, {@code both}; default {@code lastresort}, because that is the one whose behaviour is
 * new. For {@code tamarampcal}: {@code base_off} ... {@code road200_new}.</li>
 * <li>{@code --date=} the demand date, default {@value #DEFAULT_DATE}.</li>
 * <li>{@code --demand=} the demand CSV or the directory holding one per date, default {@value #DEFAULT_DEMAND}.</li>
 * <li>{@code --from=}, {@code --to=} clock times on that date, default {@value #DEFAULT_FROM} to
 * {@value #DEFAULT_TO}. The campaign runs 13:00 to 22:00; that is nine hours of watching, so the default here is a
 * window rather than the whole day. <b>This makes it not the campaign's run</b> - a shorter window is a different
 * warm-up and a different demand profile - so it is for looking, never for a number.</li>
 * <li>{@code --seed=} the replication seed, default {@value #DEFAULT_SEED}.</li>
 * <li>{@code --output=} where the run writes, default {@value #DEFAULT_OUTPUT}.</li>
 * <li>{@code --wanted-only=true} sets {@code dominantSideMustBeWanted}.</li>
 * <li>{@code --route-room=250} sets {@code minRouteRoomForLaneChange} in metres, for trying the rule out
 * before it has a permanent home. Omitted, nothing is set and the behaviour is unchanged.</li>
 * <li>{@code --gui=false} runs the same configuration headless, so the sampler output belongs to the run that
 * was watched rather than to a similar one.</li>
 * </ul>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public final class RunTamaRampEndGui
{
    /** Default cell: the one whose behaviour is new. */
    public static final String DEFAULT_CELL = "lastresort";

    /** Default demand date; a day the final validation broke down on, so the ramp is under load. */
    public static final String DEFAULT_DATE = "2025-09-22";

    /** Default demand location. */
    public static final String DEFAULT_DEMAND = "cluster/demand";

    /** Default start of the watched window. */
    public static final String DEFAULT_FROM = "16:00:00";

    /** Default end of the watched window. */
    public static final String DEFAULT_TO = "18:00:00";

    /** Default seed, the first of the campaign's. */
    public static final long DEFAULT_SEED = 42L;

    /** Default output directory. */
    public static final String DEFAULT_OUTPUT = "out/rampend-gui";

    /** Utility class. */
    private RunTamaRampEndGui()
    {
        // not instantiated
    }

    /**
     * Starts the animation.
     * @param args String[]; see the class documentation
     * @throws Exception on simulation errors
     */
    public static void main(final String[] args) throws Exception
    {
        Map<String, String> options = parse(args);
        String cell = options.getOrDefault("cell", DEFAULT_CELL);
        String study = options.getOrDefault("study", TamaRampEndStudy.NAME);
        String date = options.getOrDefault("date", DEFAULT_DATE);
        String demand = options.getOrDefault("demand", DEFAULT_DEMAND);
        String from = options.getOrDefault("from", DEFAULT_FROM);
        String to = options.getOrDefault("to", DEFAULT_TO);
        long seed = Long.parseLong(options.getOrDefault("seed", String.valueOf(DEFAULT_SEED)));
        File output = new File(options.getOrDefault("output", DEFAULT_OUTPUT));

        TrafficFacility facility = FacilityRegistry.resolve(FreiburgFacility.NAME);
        // Resolved the way the study resolves it, so a missing file fails here with the study's own message
        // rather than at t=0 with an empty demand.
        Map<String, File> demandPerDate =
                DateStudy.resolveDemandCsvs(List.of(date), new File(demand), DateStudy.DEFAULT_CSV_PATTERN, true);
        String demandCsvPath = demandPerDate.get(date).getAbsolutePath();

        ScenarioGenerator scenario = facility.getGeneratorClass().getDeclaredConstructor().newInstance();
        output.mkdirs();
        scenario.setOutputDirectory(output);

        ScenarioParameters params = TamaFinalValidationStudy.parameters(facility, date, demandCsvPath, true);
        // Either study's cell, because the cells worth watching are in both: the two switches on their own
        // in `tamarampend`, and the calibration arms - including the ones whose cluster runs died - in
        // `tamarampcal`. Routed through each study's own applyCell so there is no third spelling.
        if (TamaRampEndCalibrationStudy.NAME.equals(study))
        {
            TamaRampEndCalibrationStudy.applyCell(cell, params);
        }
        else if (TamaRampEndStudy.NAME.equals(study))
        {
            TamaRampEndStudy.applyCell(cell, params);
        }
        else
        {
            throw new IllegalArgumentException("unknown --study=" + study + "; expected "
                    + TamaRampEndStudy.NAME + " or " + TamaRampEndCalibrationStudy.NAME);
        }
        params.setSeed(seed);
        // Narrowed after the cell, not before: the cell must see the set it was defined against.
        params.set("demandStartDate", date + " " + from);
        params.set("demandEndDate", date + " " + to);
        // A knob for trying a rule out before it has a home, deliberately not a study axis: the route room
        // a lane must still give before a discretionary change into it. Zero, the default, changes nothing.
        // The dominance fix, as a knob for the run that has to show whether it removes the deadlock.
        if (Boolean.parseBoolean(options.getOrDefault("wanted-only", "false")))
        {
            params.set("car." + MirovaParameters.dominantSideMustBeWanted.getId(), Boolean.TRUE);
            params.set("truck." + MirovaParameters.dominantSideMustBeWanted.getId(), Boolean.TRUE);
            System.out.println("[gui] dominantSideMustBeWanted = true");
        }
        String routeRoom = options.get("route-room");
        if (routeRoom != null)
        {
            Length room = Length.instantiateSI(Double.parseDouble(routeRoom));
            params.set("car." + MirovaParameters.minRouteRoomForLaneChange.getId(), room);
            params.set("truck." + MirovaParameters.minRouteRoomForLaneChange.getId(), room);
            System.out.println("[gui] minRouteRoomForLaneChange = " + room);
        }

        System.out.println("[gui] study=" + study + " cell=" + cell + " date=" + date + " window=" + from
                + ".." + to + " seed=" + seed);
        System.out.println("[gui] demand=" + demandCsvPath);
        System.out.println("[gui] a shortened window: for watching, not for a number.");

        // The three things ScenarioManager.prepareRun does before it builds a script, and which a runner that
        // skips them does not reproduce. A study's parameter set is an *override* set: it carries what the study
        // varies and nothing else, so on its own getMergeShare() and its like are null and setup dies. And the
        // planner has to be announced before the script is built, because FreiburgNord makes its vehicle
        // factories during simulation setup - announcing it afterwards left runs that asked for TaMA recording
        // MiRoVA.
        ScenarioParameters effective = scenario.getDefaultParameters().copy().applyOverridesFrom(params);
        ScenarioGenerator.resetAppliedPlanner();
        ScenarioGenerator.setRequestedPlanner(effective.getOrDefault(ScenarioGenerator.KEY_TACTICAL_PLANNER,
                ScenarioGenerator.MIROVA_PLANNER, String.class));

        ScenarioSimulationScript script = scenario.buildSimulationScript(effective);
        // --gui=false runs the same configuration without a window, which is how the run that is watched
        // and the run that is measured are made to be the same run. Same cell, same seed, same window:
        // the trajectories then explain what was on screen rather than resembling it.
        script.setGuiEnabled(Boolean.parseBoolean(options.getOrDefault("gui", "true")));
        script.start();
    }

    /**
     * Parses {@code --key=value} arguments.
     * @param args String[]; the arguments
     * @return Map&lt;String, String&gt;; key to value
     * @throws IllegalArgumentException on an argument that is not in that form
     */
    private static Map<String, String> parse(final String[] args)
    {
        Map<String, String> options = new HashMap<>();
        for (String arg : args)
        {
            if (!arg.startsWith("--") || !arg.contains("="))
            {
                throw new IllegalArgumentException("expected --key=value, got '" + arg + "'");
            }
            int split = arg.indexOf('=');
            options.put(arg.substring(2, split), arg.substring(split + 1));
        }
        return options;
    }
}
