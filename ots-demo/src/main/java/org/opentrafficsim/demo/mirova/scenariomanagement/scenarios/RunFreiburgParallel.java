package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;

import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;

/**
 * Runner class to test the effect of demand aggregation interval on the
 * "dip-fill + proportional peak trim" smoothing strategy.
 * <p>
 * Fixed parameters: car.T=0.90 s, truck.T=1.20 s, RedFac=0.50<br>
 * Tested aggregation intervals: 5, 10, 15, 20 minutes<br>
 * 6 replications x 4 configs = 24 runs on 24 threads (01.10.2025 only)
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author Antigravity Agent
 */
public class RunFreiburgParallel
{
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

                        // --- CONFIGURATION ---
                        // Fixed simulation time period: 01.10.2025 afternoon peak
                        String startDate = "2025-10-01 13:00:00";
                        String endDate   = "2025-10-01 22:00:00";

                        // Fixed behavioural parameters (best-performing combo from calibration study)
                        final double CAR_T   = 0.90;
                        final double TRUCK_T = 1.20;
                        final double RED_FAC = 0.50;

                        // Demand aggregation intervals to test [minutes]: 5, 10, 15, 20
                        int[] aggregationIntervals = new int[] {5, 10, 15, 20};

                        // 6 replications per config x 4 configs = 24 runs total
                        int numberOfReplications = 6;
                        int parallelThreads      = 24;

                        File outputDirectory = new File(
                                        "D:\\Mitarbeitende\\gw2128\\repositories\\mirova\\output\\ots"
                                        + "\\freiburg_smoothingIntervalStudy_2025-10-01");
                        // --- END CONFIGURATION ---

                        ScenarioManager scenarioManager = new ScenarioManager(outputDirectory);

                        String scenarioName = "FreiburgNord_2025-10-01_13-00_to_22-00";
                        scenarioManager.addScenario(scenarioName, FreiburgNord.class);

                        for (int agg : aggregationIntervals)
                        {
                                ScenarioParameters varParams = new ScenarioParameters();
                                varParams.setSeed(42L);
                                varParams.set("enableTrajectoryRecording", false);

                                // Demand period
                                varParams.set("demandStartDate", startDate);
                                varParams.set("demandEndDate",   endDate);

                                // Aggregation interval -- this is the variable under study
                                varParams.set("demandAggregation", agg);

                                // Explicitly enable the "dip-fill + proportional peak trim" smoothing strategy
                                // (also the default, but stated here for clarity)
                                varParams.set("demandSmooth", true);

                                // Car parameters
                                varParams.set("car." + ParameterTypes.T.getId(), CAR_T);
                                varParams.set("car." + MirovaParameters.vGain.getId(), 15.0);
                                varParams.set("car." + MirovaParameters.A_MAX.getId(), 3.5);
                                varParams.set("car." + MirovaParameters.cooperativeDecelerationThreshold.getId(), -2.0);
                                varParams.set("car." + MirovaParameters.farAnticipationEnabled.getId(), false);
                                varParams.set("car." + MirovaParameters.safetyDistanceReductionFactorLaneChange.getId(),
                                                RED_FAC);

                                // Truck parameters
                                varParams.set("truck." + ParameterTypes.T.getId(), TRUCK_T);
                                varParams.set("truck." + MirovaParameters.vGain.getId(), 30.0);
                                varParams.set("truck." + MirovaParameters.A_MAX.getId(), 1.3);
                                varParams.set("truck." + MirovaParameters.cooperativeDecelerationThreshold.getId(), -0.5);
                                varParams.set("truck." + MirovaParameters.cooperativeLaneChangesEnabled.getId(), false);
                                varParams.set("truck." + MirovaParameters.farAnticipationEnabled.getId(), false);
                                varParams.set("truck." + MirovaParameters.safetyDistanceReductionFactorLaneChange.getId(),
                                                RED_FAC);

                                scenarioManager.addParameterVariation(scenarioName, varParams);
                                System.out.println("Registered: aggregation=" + agg + " min"
                                                + " | carT=" + CAR_T + " | truckT=" + TRUCK_T
                                                + " | RedFac=" + RED_FAC);
                        }

                        scenarioManager.setReplications(numberOfReplications);

                        System.out.println("Starting smoothing aggregation study: "
                                        + aggregationIntervals.length + " configs x "
                                        + numberOfReplications + " runs = "
                                        + (aggregationIntervals.length * numberOfReplications)
                                        + " total runs on " + parallelThreads + " threads...");

                        boolean success = scenarioManager.runAll(parallelThreads, false);
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
