package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;

import org.djunits.unit.DurationUnit;
import org.djunits.value.vdouble.scalar.Duration;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioGenerator;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioSimulationScript;

/**
 * Short interactive Freiburg-Nord run for visually inspecting merge behaviour on the on-ramp.
 * <p>
 * The point of this runner is to watch <i>how fast</i> vehicles are when they merge, and how that depends on the traffic state
 * on the main lanes. Slow merges are legitimate when the target lane is congested -- the merger simply has nothing to
 * synchronise with. They are not legitimate when the main lanes are flowing, because a real driver accelerates on the ramp
 * towards the speed of the traffic they are joining, largely irrespective of whether a gap happens to be available early. The
 * evening peak is therefore simulated: it passes through both regimes, so both cases can be observed in one run.
 * </p>
 * <p>
 * The behavioural parameters are taken verbatim from {@link FreiburgStudyParameters#baseBehaviorParams()} via
 * {@code getDefaultParameters()}, so what is observed here is the same driver behaviour the studies measure.
 * </p>
 * <p>
 * Configurable via system properties, so the same build can be pointed at a different part of the day without editing:
 * </p>
 * <ul>
 * <li>{@code -Dmerge.start} / {@code -Dmerge.end} -- demand window, {@code yyyy-MM-dd HH:mm:ss}. Changing these changes the
 * demand cache key and may trigger a fresh demand preparation, which takes noticeably longer than a cached window.</li>
 * <li>{@code -Dmerge.minutes} -- simulated duration in minutes, counted from the start of the demand window.</li>
 * <li>{@code -Dmerge.seed} -- random seed.</li>
 * </ul>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See OpenTrafficSim License.
 * </p>
 * @author Marvin Baumann
 */
public final class RunFreiburgMergeWatch
{
    /** Utility class, not instantiated. */
    private RunFreiburgMergeWatch()
    {
    }

    /**
     * Starts the interactive run.
     * @param args command line arguments, unused
     * @throws Exception on simulation errors
     */
    public static void main(final String[] args) throws Exception
    {
        String demandStart = System.getProperty("merge.start", "2025-10-13 13:00:00");
        String demandEnd = System.getProperty("merge.end", "2025-10-13 14:00:00");
        double minutes = Double.parseDouble(System.getProperty("merge.minutes", "45"));
        long seed = Long.parseLong(System.getProperty("merge.seed", "42"));

        File outputDir = new File(System.getProperty("merge.outDir", "target/freiburg-merge-watch2"));
        outputDir.mkdirs();

        ScenarioGenerator scenario = new FreiburgNord();
        scenario.setOutputDirectory(outputDir);

        ScenarioParameters params = scenario.getDefaultParameters().copy();
        params.setSeed(seed);

        // Demand window. Aggregation and smoothing are kept identical to RunFreiburgNord so that an already prepared
        // demand file for the same window is reused instead of regenerated.
        params.set("demandStartDate", demandStart);
        params.set("demandEndDate", demandEnd);
        params.set("demandAggregation", 5);
        params.set("demandSmooth", false);

        // Only a slice of the demand window is simulated -- this run is for looking, not for producing statistics.
        params.setSimulationTime(new Duration(minutes, DurationUnit.MINUTE));

        // Trajectory recording is pure overhead for an interactive run and slows the animation down.
        params.set("enableTrajectoryRecording", false);

        System.out.println("[MergeWatch] demand window " + demandStart + " .. " + demandEnd + ", simulating " + minutes
                + " min, seed " + seed);

        ScenarioSimulationScript script = scenario.buildSimulationScript(params);
        script.setGuiEnabled(true);
        script.start();
    }
}
