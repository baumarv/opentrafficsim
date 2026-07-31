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
 * Runner class to execute parallel fine parameter study simulations of the FreiburgNord scenario.
 * Evaluates the sweet spot region across both 01.10.2025 and 07.10.2025 with 6 replications per variation.
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
                        // 1. Define the simulation time periods (01.10.2025 & 07.10.2025)
                        java.util.List<TimePeriod> periods = java.util.List.of(
                                        new TimePeriod("2025-10-01 13:00:00", "2025-10-01 22:00:00"),
                                        new TimePeriod("2025-10-07 13:00:00", "2025-10-07 22:00:00")
                        );

                        // 2. Set the number of replications (6 seeds/runs to run per parameter variation)
                        int numberOfReplications = 6;

                        // 3. Number of parallel execution threads
                        int parallelThreads = 24;

                        // 4. Define the root output directory for the fine parameter study results
                        File outputDirectory = new File(
                                        "D:\\Mitarbeitende\\gw2128\\repositories\\mirova\\output\\ots\\freiburg_fineParameterStudy_2025-10-01_and_2025-10-07");
                        // --- CONFIGURATION END ---

                        // Define fine parameter combinations in sweet spot region: {RedFac, car.T, truck.T}
                        double[][] paramCombinations = new double[][] {
                                {0.50, 0.90, 1.20}, // Var 1: RedFac=0.50, car.T=0.90, truck.T=1.20
                                {0.45, 0.90, 1.20}, // Var 2: RedFac=0.45, car.T=0.90, truck.T=1.20
                                {0.40, 0.90, 1.20}, // Var 3: RedFac=0.40, car.T=0.90, truck.T=1.20
                                {0.40, 0.85, 1.15}, // Var 4: RedFac=0.40, car.T=0.85, truck.T=1.15
                                {0.40, 0.95, 1.25}, // Var 5: RedFac=0.40, car.T=0.95, truck.T=1.25
                                {0.35, 0.85, 1.15}, // Var 6: RedFac=0.35, car.T=0.85, truck.T=1.15
                                {0.35, 0.90, 1.20}, // Var 7: RedFac=0.35, car.T=0.90, truck.T=1.20
                                {0.45, 0.85, 1.15}  // Var 8: RedFac=0.45, car.T=0.85, truck.T=1.15
                        };

                        // Initialize the ScenarioManager
                        ScenarioManager scenarioManager = new ScenarioManager(outputDirectory);

                        for (TimePeriod period : periods)
                        {
                                String specificScenarioName = "FreiburgNord_" + formatPeriodName(period);
                                scenarioManager.addScenario(specificScenarioName, FreiburgNord.class);

                                for (double[] combo : paramCombinations)
                                {
                                        double redFac = combo[0];
                                        double carT = combo[1];
                                        double truckT = combo[2];

                                        ScenarioParameters varParams = new ScenarioParameters();
                                        varParams.setSeed(42L); // Base seed
                                        varParams.set("enableTrajectoryRecording", false);

                                        // Set demand date range and aggregation interval for database loading
                                        varParams.set("demandStartDate", period.startDate());
                                        varParams.set("demandEndDate", period.endDate());
                                        varParams.set("demandAggregation", 2);

                                        // Car parameters
                                        varParams.set("car." + ParameterTypes.T.getId(), carT);
                                        varParams.set("car." + MirovaParameters.vGain.getId(), 15.0);
                                        varParams.set("car." + MirovaParameters.A_MAX.getId(), 3.5);
                                        varParams.set("car." + MirovaParameters.cooperativeDecelerationThreshold.getId(), -2.0);
                                        varParams.set("car." + MirovaParameters.farAnticipationEnabled.getId(), false);
                                        varParams.set("car." + MirovaParameters.safetyDistanceReductionFactorLaneChange.getId(), redFac);

                                        // Truck parameters
                                        varParams.set("truck." + ParameterTypes.T.getId(), truckT);
                                        varParams.set("truck." + MirovaParameters.vGain.getId(), 30.0);
                                        varParams.set("truck." + MirovaParameters.A_MAX.getId(), 1.3);
                                        varParams.set("truck." + MirovaParameters.cooperativeDecelerationThreshold.getId(), -0.5);
                                        varParams.set("truck." + MirovaParameters.cooperativeLaneChangesEnabled.getId(), false);
                                        varParams.set("truck." + MirovaParameters.farAnticipationEnabled.getId(), false);
                                        varParams.set("truck." + MirovaParameters.safetyDistanceReductionFactorLaneChange.getId(), redFac);

                                        // Register variation
                                        scenarioManager.addParameterVariation(specificScenarioName, varParams);
                                }
                        }

                        // Set the number of replications (seeds to run per variation)
                        scenarioManager.setReplications(numberOfReplications);

                        boolean enableGUI = false;

                        System.out.println("Starting parallel fine parameter study of FreiburgNord (96 runs across 01.10 & 07.10)...");
                        boolean success = scenarioManager.runAll(parallelThreads, enableGUI);

                        System.out.println("Execution finished. Shutting down.");
                        System.exit(success ? 0 : 1);
                }
                catch (Exception exception)
                {
                        System.err.println("An error occurred during the parallel scenario execution:");
                        exception.printStackTrace();
                }
        }
}
