package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;

import org.djunits.unit.DurationUnit;
import org.djunits.value.vdouble.scalar.Duration;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioGenerator;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioSimulationScript;

/**
 * One fixed, reproducible Freiburg-Nord run for profiling the simulation backend.
 * <p>
 * This is not a calibration or evaluation entry point. It exists so that a profiler always sees the same work: one
 * date, one seed, one parameter set, no GUI, and no trajectory recording. Nothing here is meant to be tuned for a
 * better traffic result - if a parameter changes, the profile is no longer comparable with earlier measurements, which
 * defeats the purpose.
 * </p>
 *
 * <h2>Why this particular run</h2>
 * <p>
 * A profile is only useful if the work it measures resembles the work the model actually does, and the two traffic
 * regimes stress entirely different code. Free flow spends its time in perception and car-following; congestion adds
 * gap search, cooperation and lane-change decisions, and it multiplies the number of interacting neighbours per
 * vehicle. A run that never breaks down would leave that half unmeasured.
 * </p>
 * <p>
 * Seed 46 on 2025-10-27 was chosen because it contains both, and in a realistic proportion: of the ten seeds of that
 * cell it is the only one that breaks down at all. Measured at det_L3a, 86.9 % of its 5-minute intervals are in free
 * flow and 13.1 % congested, with the jam running from 3.58 h to 4.92 h after the start of the demand window and the
 * cross-section speed reaching a minimum of 17.3 km/h. The simulated span below covers free flow, the breakdown, the
 * jam and the recovery.
 * </p>
 *
 * <h2>What is deliberately switched off</h2>
 * <ul>
 * <li><b>The GUI</b>, which would otherwise dominate the profile with rendering work and, worse, keep the JVM alive
 * after the run through its AWT threads.</li>
 * <li><b>Trajectory recording</b>. The sampler writes a row per vehicle per 0.2 s and then compresses the result; that
 * is real work, but it is output, not model, and it would crowd out what is being looked for.</li>
 * </ul>
 * <p>
 * Both can be turned on with {@code -Dmirova.gui=true} and {@code -Dmirova.trajectories=true} when the question is
 * about them specifically.
 * </p>
 *
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See OpenTrafficSim License.
 * </p>
 * @author Marvin Baumann
 */
public final class RunFreiburgProfiling
{
    /** The simulated date. One of the three calibration dates; the lightest of them, and the only one needed here. */
    public static final String DATE = "2025-10-27";

    /** Start of the demand window. Fixed: the seed reproduces a run only together with its window. */
    public static final String DEMAND_START = DATE + " 13:00:00";

    /** End of the demand window. */
    public static final String DEMAND_END = DATE + " 22:00:00";

    /**
     * Simulated duration in minutes, counted from the start of the demand window.
     * <p>
     * 330 minutes reach 18:30, which is past the end of this seed's jam at 17:55 and leaves the recovery inside the
     * measurement. Shortening it below roughly 215 minutes cuts the breakdown off and leaves a free-flow-only profile;
     * the run is truncated, not re-drawn, so a shorter span reproduces exactly the beginning of a longer one.
     * </p>
     */
    public static final double SIMULATED_MINUTES =
            Double.parseDouble(System.getProperty("mirova.minutes", "330.0"));

    /** The seed. Changing it changes which traffic is simulated - see the class comment. */
    public static final long SEED = Long.getLong("mirova.seed", 46L);

    /** Relaxation acceleration damping factor, fixed at the best cell of the fourth merge grid campaign. */
    public static final double ACC_DAMPING_FACTOR = FreiburgCarStudy.ACC_DAMPING_FACTOR;

    /** Lane-change safety distance reduction factor, fixed at the best cell of the fourth merge grid campaign. */
    public static final double SAFETY_DISTANCE_FACTOR = FreiburgCarStudy.SAFETY_DISTANCE_FACTOR;

    /** Path of the demand CSV, relative to the repository root. Committed on this branch so the run is self-contained. */
    public static final String DEMAND_CSV =
            System.getProperty("mirova.demandCsv", "cluster/demand/demand_" + "2025-10-27" + ".csv");

    /** Output directory. Nothing but the demand copy is written unless trajectory recording is switched on. */
    public static final String OUTPUT_DIR =
            System.getProperty("mirova.outputDir", "target/freiburg-profiling");

    /** Whether to show the animation. Off: rendering would dominate the profile and the JVM would not exit. */
    public static final boolean SHOW_GUI = Boolean.parseBoolean(System.getProperty("mirova.gui", "false"));

    /** Whether to record trajectories. Off: sampling and compression are output work, not model work. */
    public static final boolean RECORD_TRAJECTORIES =
            Boolean.parseBoolean(System.getProperty("mirova.trajectories", "false"));

    /** Utility class, not instantiated. */
    private RunFreiburgProfiling()
    {
    }

    /**
     * Starts the profiling run.
     * @param args command line arguments, unused - configure through the system properties named above
     * @throws Exception on simulation errors
     */
    public static void main(final String[] args) throws Exception
    {
        File demandCsv = new File(DEMAND_CSV);
        if (!demandCsv.isFile())
        {
            throw new IllegalStateException("Demand CSV not found at '" + demandCsv.getAbsolutePath()
                    + "'. Run from the repository root, or point -Dmirova.demandCsv at the file.");
        }

        // The GTU position cache is switched back on, i.e. MiRoVA's own optimisation is switched off. Profiling a
        // configuration that already has the known hot spot removed would hide it, and the point of this branch is to
        // measure the backend as OTS ships it. Set -Dmirova.gtuPositionCaching=false to profile the optimised state
        // instead. The other optimisation of that investigation, a DJUnits build caching scalar hash codes, was never
        // merged - it lives on perf/djunits-hash-cache-experiment and djunits.version here is stock 5.2.1 - so there
        // is nothing to undo for it.
        if (System.getProperty(ScenarioGenerator.PROPERTY_GTU_POSITION_CACHING) == null)
        {
            System.setProperty(ScenarioGenerator.PROPERTY_GTU_POSITION_CACHING, "true");
        }

        File outputDir = new File(OUTPUT_DIR);
        outputDir.mkdirs();

        ScenarioGenerator scenario = new FreiburgNord();
        scenario.setOutputDirectory(outputDir);

        // The scenario defaults carry the network and demand wiring - merge share, generator positions and the like -
        // which the study parameter sets do not, so they have to be the base rather than the other way round.
        ScenarioParameters params = scenario.getDefaultParameters().copy();

        // Built the same way the cluster study builds a cell, so that what is profiled is what the campaigns run
        // rather than a second parameter set that would drift away from it.
        params.applyOverridesFrom(FreiburgCombinationStudy.forCombination(new FreiburgFacility(), DATE,
                demandCsv.getAbsolutePath(), true, FreiburgDampingStudy.resolveHeadwayCombination(),
                ACC_DAMPING_FACTOR, SAFETY_DISTANCE_FACTOR));

        params.setSeed(SEED);
        params.set("demandStartDate", DEMAND_START);
        params.set("demandEndDate", DEMAND_END);
        params.setSimulationTime(new Duration(SIMULATED_MINUTES, DurationUnit.MINUTE));
        params.set("enableTrajectoryRecording", RECORD_TRAJECTORIES);

        System.out.println("[Profiling] " + DATE + " seed " + SEED + ", " + SIMULATED_MINUTES
                + " min simulated, gui=" + SHOW_GUI + ", trajectories=" + RECORD_TRAJECTORIES);
        System.out.println("[Profiling] damping=" + ACC_DAMPING_FACTOR + ", safetyDistance=" + SAFETY_DISTANCE_FACTOR
                + ", demand=" + demandCsv.getPath());

        long t0 = System.nanoTime();
        ScenarioSimulationScript script = scenario.buildSimulationScript(params);
        script.setGuiEnabled(SHOW_GUI);
        script.start();
        System.out.println("[Profiling] wall clock: " + ((System.nanoTime() - t0) / 1_000_000_000L) + " s");
    }
}
