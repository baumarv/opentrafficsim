package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;

import org.djunits.unit.DurationUnit;
import org.djunits.value.vdouble.scalar.Duration;
import org.opentrafficsim.base.parameters.ParameterType;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.ParameterGridBuilder;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;

/**
 * Runner class to execute parallel simulations of the FreiburgNord scenario with custom parameter configurations.
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author Antigravity Agent
 */
public class RunFreiburgParallel
{
    /**
     * Helper record to represent a simulation time period.
     */
    public record TimePeriod(String startDate, String endDate)
    {
    }

    /**
     * Formats a TimePeriod into a filename-friendly string.
     * @param period the time period
     * @return a formatted string
     */
    private static String formatPeriodName(final TimePeriod period)
    {
        String start = period.startDate().replace(" ", "_").replace(":", "-");
        String end = period.endDate().replace(" ", "_").replace(":", "-");
        return start + "_to_" + end;
    }

    /**
     * Main execution method.
     * @param args String[]; command line arguments
     */
    public static void main(final String[] args)
    {
        try
        {
            // Suppress verbose logging and warn/error prints from background threads
            ScenarioManager.silenceBackgroundThreads();

            // --- CONFIGURATION START ---
            // 1. Define the simulation time periods to run
            java.util.List<TimePeriod> periods = java.util.List.of(
                    // new TimePeriod("2025-09-17 07:00:00", "2025-09-17 20:00:00"),
                    // new TimePeriod("2025-09-18 12:00:00", "2025-09-18 20:00:00"),
                    // new TimePeriod("2025-09-20 06:00:00", "2025-09-20 14:00:00"),
                    // new TimePeriod("2025-09-15 08:00:00", "2025-09-21 20:00:00"),
                    new TimePeriod("2025-09-25 13:00:00", "2025-09-25 16:00:00"));

            // 2. Set the number of replications (seeds/runs to run per time period)
            int numberOfReplications = 10;

            // 3. Number of parallel execution threads
            int parallelThreads = 16;

            // 4. Define the root output directory for the simulation results
            File outputDirectory = new File("D:\\Mitarbeitende\\gw2128\\repositories\\mirova\\output\\ots\\freiburg_parallel");
            // --- CONFIGURATION END ---

            // Initialize the ScenarioManager
            ScenarioManager scenarioManager = new ScenarioManager(outputDirectory);

            // Define and register each time period as a separate scenario to output to different directories
            for (TimePeriod period : periods)
            {
                String specificScenarioName = "FreiburgNord_Calibration_MergeAggression_" + formatPeriodName(period);
                scenarioManager.addScenario(specificScenarioName, FreiburgNord.class);

                ScenarioParameters baseParams = new ScenarioParameters();
                baseParams.setSeed(42L); // Base seed

                // Set demand date range and aggregation interval for database loading
                baseParams.set("demandStartDate", period.startDate());
                baseParams.set("demandEndDate", period.endDate());
                baseParams.set("demandAggregation", 5); // 1-minute aggregation for minute-by-minute demand

                // Define parameters directly analogously to RunFreiburgNord
                baseParams.set("car." + ParameterTypes.T.getId(), 1.4);
                baseParams.set("car." + MirovaParameters.vGain.getId(), 15.0);
                baseParams.set("car." + MirovaParameters.A_MAX.getId(), 3.5);
                baseParams.set("truck." + ParameterTypes.T.getId(), 2.0);
                baseParams.set("truck." + MirovaParameters.vGain.getId(), 30.0);
                baseParams.set("truck." + MirovaParameters.A_MAX.getId(), 2.5);

                // Build variations grid using ParameterGridBuilder with custom coupled parameter dimensions (Tuple-based)
                java.util.List<ScenarioParameters> variations =
                        new ParameterGridBuilder(baseParams)
                                .addDimensionParallel(
                                        new String[] {"coopDecel",
                                                "car." + MirovaParameters.cooperativeDecelerationThreshold.getId(),
                                                "truck." + MirovaParameters.cooperativeDecelerationThreshold.getId()},
                                        -3.0, -2.5)
                                .addDimensionParallel(new String[][] {
                                        {"followerMinDecel", "car." + MirovaParameters.minFollowerDecelerationThreshold.getId(),
                                                "truck." + MirovaParameters.minFollowerDecelerationThreshold.getId()},
                                        {"car." + MirovaParameters.maxFollowerDecelerationThreshold.getId(),
                                                "truck." + MirovaParameters.maxFollowerDecelerationThreshold.getId()}},
                                        new Object[] {-1.5, -3.0}, new Object[] {-2.0, -4.0})
                                .addDimensionParallel(
                                        new String[][] {
                                                {"egoMinDecel", "car." + MirovaParameters.minEgoDecelerationThreshold.getId(),
                                                        "truck." + MirovaParameters.minEgoDecelerationThreshold.getId()},
                                                {"car." + MirovaParameters.maxEgoDecelerationThreshold.getId(),
                                                        "truck." + MirovaParameters.maxEgoDecelerationThreshold.getId()}},
                                        new Object[] {-1.5, -3.0}, new Object[] {-1.0, -2.5})
                                .addDimensionParallel(
                                        new String[] {"safetyDistanceReductionFactorLaneChange",
                                                "car." + MirovaParameters.safetyDistanceReductionFactorLaneChange.getId(),
                                                "truck." + MirovaParameters.safetyDistanceReductionFactorLaneChange.getId()},
                                        0.5, 0.4)
                                .build();

                for (ScenarioParameters varParams : variations)
                {
                    scenarioManager.addParameterVariation(specificScenarioName, varParams);
                }
            }

            // Set the number of replications (seeds to run per variation)
            scenarioManager.setReplications(numberOfReplications);

            boolean enableGUI = false;

            System.out.println("Starting parallel execution of FreiburgNord scenarios...");
            scenarioManager.runAll(parallelThreads, enableGUI);

            System.out.println("Execution finished. Shutting down.");
            System.exit(0);
        }
        catch (Exception exception)
        {
            System.err.println("An error occurred during the parallel scenario execution:");
            exception.printStackTrace();
        }
    }
}
