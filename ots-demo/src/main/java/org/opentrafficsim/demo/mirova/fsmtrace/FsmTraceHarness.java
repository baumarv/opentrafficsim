package org.opentrafficsim.demo.mirova.fsmtrace;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.djunits.unit.DurationUnit;
import org.djunits.value.vdouble.scalar.Duration;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioGenerator;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioSimulationScript;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.FreiburgNord;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.MergeScenario;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.RunFreiburgMergeWatch;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.SimpleHighwayScenario;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging.FsmTraceRecorder;

/**
 * Runs short, fixed-seed scenarios with the {@link FsmTraceRecorder} enabled, so that the tactical decisions of the Layer 3
 * state machine can be compared before and after a restructuring.
 * <p>
 * {@link Case#FREIBURG_MERGE} is the primary case: the real Freiburg-Nord network under measured demand, with the
 * calibration {@link RunFreiburgMergeWatch} is used to inspect merge behaviour with. Because it is that runner's own
 * parameter set rather than a copy of it, a change to the calibration cannot silently leave the regression net behind.
 * </p>
 * <p>
 * {@link Case#MERGE} is the synthetic counterpart: it sweeps the on-ramp demand from 1000 to 6500 veh/h within its run time,
 * so one run passes through free flow, the onset of congestion and the congested merge branch, cheaply and without depending
 * on demand data. {@link Case#HIGHWAY} would exercise free driving and discretionary changes, but does not currently run.
 * </p>
 * <p>
 * The run times are short on purpose: the recorder buffers every row in memory, and the point of the trace is coverage of the
 * decision graph, not a statistically meaningful traffic sample.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public final class FsmTraceHarness
{

    /** Utility class. */
    private FsmTraceHarness()
    {
        //
    }

    /**
     * The scenarios the regression net is recorded from.
     */
    public enum Case
    {
        /** Freiburg-Nord on-ramp under measured demand, with the merge-watch calibration. */
        FREIBURG_MERGE("freiburg-merge"),

        /** Synthetic merge network, demand ramp through congestion. */
        MERGE("merge"),

        /** Plain highway, free flow and discretionary lane changes. */
        HIGHWAY("highway");

        /** File-name stem of this case. */
        private final String id;

        /**
         * Creates a case.
         * @param id the file-name stem of this case
         */
        Case(final String id)
        {
            this.id = id;
        }

        /**
         * Returns the file-name stem of this case.
         * @return the file-name stem, e.g. {@code merge}
         */
        public String getId()
        {
            return this.id;
        }

        /**
         * Returns the name of the trace file for this case. Traces are gzipped: an uncompressed run is upwards of ten
         * megabytes, and the references are meant to live in the repository next to the code they pin.
         * @return the file name, e.g. {@code merge.trace.csv.gz}
         */
        public String getTraceFileName()
        {
            return this.id + ".trace.csv.gz";
        }
    }

    /** Simulated time per case. Long enough for the merge demand ramp to reach the congested branch. */
    private static final Duration RUN_TIME = new Duration(600.0, DurationUnit.SECOND);

    /**
     * Simulated time for the Freiburg case. Shorter, because that network carries far more vehicles per second of
     * simulation and the recorder holds every row in memory.
     */
    private static final Duration FREIBURG_RUN_TIME = new Duration(20.0, DurationUnit.MINUTE);

    /** Seed of every recorded run. Fixed, because a trace from a different seed compares against nothing. */
    private static final long SEED = 42L;

    /** Demand. The merge case replaces this with its own ramped profile; the highway case uses it as it stands. */
    private static final double DEMAND_VEH_PER_HOUR = 4000.0;

    /** Share of trucks in the demand. */
    private static final double TRUCK_SHARE = 0.1;

    /** Share of the demand routed over the on-ramp. Ignored by the highway case, which has no ramp. */
    private static final double MERGE_SHARE = 0.2;

    /**
     * Runs one case with the recorder enabled and writes its trace.
     * @param traceCase the case to run
     * @param outputDirectory the directory the trace and the scenario's own output are written to
     * @return the trace file that was written
     * @throws Exception if the simulation or the trace cannot be run or written
     */
    public static Path record(final Case traceCase, final File outputDirectory) throws Exception
    {
        ScenarioGenerator scenario = scenarioFor(traceCase);

        outputDirectory.mkdirs();
        scenario.setOutputDirectory(outputDirectory);

        ScenarioParameters params = parametersFor(traceCase, scenario);

        ScenarioSimulationScript script = scenario.buildSimulationScript(params);
        script.setGuiEnabled(false);
        script.setAutorun(true);

        Path trace = new File(outputDirectory, traceCase.getTraceFileName()).toPath();
        FsmTraceRecorder.start(trace);
        try
        {
            script.start();
        }
        finally
        {
            // Written even when the run aborts: a partial trace still says where the two runs diverged, and leaving the
            // recorder armed would silently poison the next case.
            FsmTraceRecorder.stop();
        }
        return trace;
    }

    /**
     * Returns the scenario a case runs on.
     * @param traceCase the case
     * @return a fresh scenario instance
     */
    private static ScenarioGenerator scenarioFor(final Case traceCase)
    {
        switch (traceCase)
        {
            case FREIBURG_MERGE:
                return new FreiburgNord();
            case MERGE:
                return new MergeScenario();
            default:
                return new SimpleHighwayScenario();
        }
    }

    /**
     * Returns the parameters a case runs with.
     * <p>
     * The Freiburg case takes {@link RunFreiburgMergeWatch}'s own parameter assembly, so that the trace pins the
     * calibration that is actually being worked on; only the run length and the seed are overridden, and the trajectory
     * recording is switched off because the trace, not the trajectories, is what this run is for.
     * </p>
     * <p>
     * The synthetic cases state every input here rather than inheriting the scenario's defaults: a reference trace is only
     * meaningful together with the configuration it was recorded under, and a default that drifts later would silently
     * invalidate it.
     * </p>
     * @param traceCase the case
     * @param scenario the scenario the case runs on
     * @return the parameters
     */
    private static ScenarioParameters parametersFor(final Case traceCase, final ScenarioGenerator scenario)
    {
        if (traceCase == Case.FREIBURG_MERGE)
        {
            ScenarioParameters params = RunFreiburgMergeWatch.watchParameters(scenario);
            params.setSeed(SEED);
            params.setSimulationTime(FREIBURG_RUN_TIME);
            params.set("enableTrajectoryRecording", false);
            return params;
        }

        ScenarioParameters params = new ScenarioParameters();
        params.setSeed(SEED);
        params.setSimulationTime(RUN_TIME);
        params.setDemand(DEMAND_VEH_PER_HOUR);
        params.setTruckShare(TRUCK_SHARE);
        params.setMergeShare(MERGE_SHARE);
        return params;
    }

    /**
     * Records the requested cases into the given directory.
     * <p>
     * A case that fails is reported and the remaining cases still run: one scenario that cannot start is not a reason to
     * lose the traces of the others.
     * </p>
     * @param args output directory (default {@code build/fsm-trace}), optionally followed by case ids to record; with no
     *            ids, every case is recorded
     * @throws Exception if no case could be recorded at all
     */
    public static void main(final String[] args) throws Exception
    {
        File out = new File(args.length > 0 ? args[0] : "build/fsm-trace");

        List<Case> cases = new ArrayList<>();
        for (int i = 1; i < args.length; i++)
        {
            cases.add(byId(args[i]));
        }
        if (cases.isEmpty())
        {
            cases.addAll(Arrays.asList(Case.values()));
        }

        int recorded = 0;
        for (Case traceCase : cases)
        {
            try
            {
                Path trace = record(traceCase, out);
                System.out.println("[FsmTraceHarness] wrote " + trace.toAbsolutePath());
                recorded++;
            }
            catch (Exception exception)
            {
                System.out.println("[FsmTraceHarness] case '" + traceCase.getId() + "' failed: " + exception);
            }
        }
        if (recorded == 0)
        {
            throw new IllegalStateException("No case could be recorded.");
        }
    }

    /**
     * Returns the case with the given id.
     * @param id the file-name stem of a case, e.g. {@code merge}
     * @return the case
     */
    private static Case byId(final String id)
    {
        for (Case traceCase : Case.values())
        {
            if (traceCase.getId().equals(id))
            {
                return traceCase;
            }
        }
        throw new IllegalArgumentException("Unknown case '" + id + "'; known: " + Arrays.toString(Case.values()));
    }
}
