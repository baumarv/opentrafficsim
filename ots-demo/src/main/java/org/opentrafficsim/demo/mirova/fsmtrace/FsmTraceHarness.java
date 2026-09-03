package org.opentrafficsim.demo.mirova.fsmtrace;

import java.io.File;
import java.nio.file.Path;

import org.djunits.unit.DurationUnit;
import org.djunits.value.vdouble.scalar.Duration;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioGenerator;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioSimulationScript;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.MergeScenario;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.SimpleHighwayScenario;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging.FsmTraceRecorder;

/**
 * Runs short, fixed-seed scenarios with the {@link FsmTraceRecorder} enabled, so that the tactical decisions of the Layer 3
 * state machine can be compared before and after a restructuring.
 * <p>
 * The two cases were chosen to reach every pattern between them. {@link Case#MERGE} sweeps the on-ramp demand from 1000 to
 * 6500 veh/h within its run time, so a single run passes through free flow, the onset of congestion and the congested merge
 * branch. {@link Case#HIGHWAY} exercises the free-driving and discretionary behaviour that the merge network barely reaches.
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
        /** Merge network, demand ramp through congestion. */
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
        ScenarioGenerator scenario = traceCase == Case.MERGE ? new MergeScenario() : new SimpleHighwayScenario();

        outputDirectory.mkdirs();
        scenario.setOutputDirectory(outputDirectory);

        // Every input of the recorded run is stated here rather than inherited from the scenario's defaults: a reference
        // trace is only meaningful together with the configuration it was recorded under, and a default that drifts later
        // would silently invalidate it.
        ScenarioParameters params = new ScenarioParameters();
        params.setSeed(SEED);
        params.setSimulationTime(RUN_TIME);
        params.setDemand(DEMAND_VEH_PER_HOUR);
        params.setTruckShare(TRUCK_SHARE);
        params.setMergeShare(MERGE_SHARE);

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
     * Records both cases into the given directory.
     * @param args optional single argument: the output directory; defaults to {@code build/fsm-trace}
     * @throws Exception if a run fails
     */
    public static void main(final String[] args) throws Exception
    {
        File out = new File(args.length > 0 ? args[0] : "build/fsm-trace");
        for (Case traceCase : Case.values())
        {
            Path trace = record(traceCase, out);
            System.out.println("[FsmTraceHarness] wrote " + trace.toAbsolutePath());
        }
    }
}
