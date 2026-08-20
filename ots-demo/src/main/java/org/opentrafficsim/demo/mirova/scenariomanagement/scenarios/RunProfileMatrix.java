package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;

import org.djunits.unit.DurationUnit;
import org.djunits.value.vdouble.scalar.Duration;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioGenerator;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioSimulationScript;
import org.opentrafficsim.road.gtu.lane.LaneBasedGtu;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;

/**
 * One cell of the profiling matrix: a single fixed FreiburgNord run, headless, for comparing build and
 * runtime variants against each other.
 * <p>
 * Everything that defines the scenario is fixed in this class rather than configurable, because the whole
 * point of the matrix is that only the variable under test differs between runs. The two things that do
 * vary are passed as system properties by {@code cluster/profile_matrix.sh}:
 * </p>
 * <ul>
 * <li>{@code -Dmirova.profileOut=<dir>} — where the simulation output goes, one directory per cell, so the
 * outputs can be compared afterwards.</li>
 * <li>{@code -Dmirova.gtuPositionCaching=true|false} — sets {@link LaneBasedGtu#CACHING}.</li>
 * </ul>
 *
 * <h3>Why this class exists at all</h3>
 * <p>
 * {@link LaneBasedGtu#CACHING} is a plain {@code public static boolean} with no property binding, so there
 * is no way to vary it from the command line. Rather than patching OTS to add one, this runner sets the
 * field before the simulation is built. That keeps the switch confined to a profiling entry point: nothing
 * on a production path can reach it, and no OTS source is touched.
 * </p>
 * <p>
 * The field is written before {@code buildSimulationScript}, hence before any GTU exists, so no vehicle can
 * observe the flag changing underneath it.
 * </p>
 * <p>
 * It also calls {@link System#exit} when finished. The simulation completes but the JVM does not terminate
 * on its own, and a matrix script running four of these in sequence would otherwise accumulate live JVMs
 * competing for the very CPU it is trying to measure.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See OpenTrafficSim License.
 * </p>
 * @author Marvin Baumann
 */
public final class RunProfileMatrix
{
    /** Start of the demand window — the same window every measurement in this series used. */
    private static final String DEMAND_START = "2025-10-13 13:00:00";

    /** End of the demand window. */
    private static final String DEMAND_END = "2025-10-13 14:00:00";

    /** Simulated duration in minutes. */
    private static final double SIMULATED_MINUTES = 25.0;

    /** Random seed — fixed, so that any output difference between cells is a real difference. */
    private static final long SEED = 42L;

    /** Utility class, not instantiated. */
    private RunProfileMatrix()
    {
    }

    /**
     * Runs one cell of the matrix.
     * @param args command line arguments, unused — configured via system properties
     * @throws Exception on simulation errors
     */
    public static void main(final String[] args) throws Exception
    {
        String outDir = System.getProperty("mirova.profileOut");
        if (outDir == null || outDir.isBlank())
        {
            System.err.println("ERROR: -Dmirova.profileOut=<directory> is required.");
            System.exit(2);
        }

        // Set before anything builds a GTU, so the flag cannot change under a running vehicle.
        boolean positionCaching = Boolean.parseBoolean(
                System.getProperty("mirova.gtuPositionCaching", "true"));
        LaneBasedGtu.CACHING = positionCaching;

        File outputDir = new File(outDir);
        outputDir.mkdirs();

        ScenarioGenerator scenario = new FreiburgNord();
        scenario.setOutputDirectory(outputDir);

        ScenarioParameters params = scenario.getDefaultParameters().copy();
        FreiburgStudyParameters.baseBehaviorParams().asUnmodifiableMap().forEach(params::set);
        params.setSeed(SEED);

        params.set("demandStartDate", DEMAND_START);
        params.set("demandEndDate", DEMAND_END);
        params.set("demandAggregation", FreiburgStudyParameters.AGGREGATION_MIN);
        params.set("demandSmooth", false);
        params.setSimulationTime(new Duration(SIMULATED_MINUTES, DurationUnit.MINUTE));

        // Off: the sampler is overhead that would sit on top of everything being compared, and it is
        // not part of the cost under test.
        params.set("enableTrajectoryRecording", false);

        System.out.println("[ProfileMatrix] djunits=" + djunitsVersion()
                + "  LaneBasedGtu.CACHING=" + LaneBasedGtu.CACHING
                + "  window=" + DEMAND_START + ".." + DEMAND_END
                + "  minutes=" + SIMULATED_MINUTES + "  seed=" + SEED);
        System.out.println("[ProfileMatrix] car T=" + params.get("car." + ParameterTypes.T.getId(), Object.class)
                + "  safetyDistanceFactor="
                + params.get("car." + MirovaParameters.safetyDistanceReductionFactorLaneChange.getId(), Object.class));
        System.out.println("[ProfileMatrix] output -> " + outputDir.getAbsolutePath());

        ScenarioSimulationScript script = scenario.buildSimulationScript(params);
        script.setGuiEnabled(false);
        script.start();

        System.out.println("[ProfileMatrix] finished.");
        System.exit(0);
    }

    /**
     * Reports which djunits build is actually on the classpath, read from the jar's own location.
     * <p>
     * Printed into the run log so that a recording can be traced back to the artifact it was produced
     * with, independently of what the build script believed it was doing.
     * </p>
     * @return the path of the djunits code source, or a marker if it cannot be determined
     */
    private static String djunitsVersion()
    {
        try
        {
            return org.djunits.unit.LengthUnit.class.getProtectionDomain().getCodeSource().getLocation().toString();
        }
        catch (Exception exception)
        {
            return "(could not determine)";
        }
    }
}
