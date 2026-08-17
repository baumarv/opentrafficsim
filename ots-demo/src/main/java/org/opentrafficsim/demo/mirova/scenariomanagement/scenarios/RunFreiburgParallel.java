package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;

import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;

/**
 * Parallel runner for the multi-day evaluation study (32 unique dates across 2025/2026).
 * <p>
 * Parameters: car.T=1.00 s, truck.T=1.30 s, RedFac=0.60, 5-min demand aggregation, Capacity Drop = disabled.<br>
 * 32 days x 6 replications = 192 runs total on 24 parallel threads.
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
                        final double RED_FAC = 0.60;
                        final int AGGREGATION_MIN = 5;

                        // Target date for test (2025-09-23, 13:00:00 to 22:00:00)
                        String[] dates = new String[] {"2025-09-23"};

                        // Standard Headway T: (1.00 / 1.30)
                        double[][] headways = new double[][] {{1.00, 1.30}};

                        int numberOfReplications = 6;
                        int parallelThreads = 6;

                        File outputDirectory = new File("D:\\Mitarbeitende\\gw2128\\repositories\\mirova\\output\\ots"
                                        + "\\freiburg_20250923_L4a_minFollowerDecel_test");
                        // --- END CONFIGURATION ---

                        // Pre-warm JAXBContext on the main thread (with exec:java classloader)
                        org.opentrafficsim.road.network.factory.xml.parser.XmlParser.warmUpJAXBContext();

                        ScenarioManager scenarioManager = new ScenarioManager(outputDirectory);

                        for (String date : dates)
                        {
                                String startDate = date + " 13:00:00";
                                String endDate = date + " 22:00:00";
                                String scenarioName = "FreiburgNord_" + date + "_13-00_to_22-00";

                                scenarioManager.addScenario(scenarioName, FreiburgNord.class);

                                for (double[] h : headways)
                                {
                                        double carT = h[0];
                                        double truckT = h[1];

                                        ScenarioParameters varParams = new ScenarioParameters();
                                        varParams.setSeed(42L);
                                        varParams.set("enableTrajectoryRecording", true);

                                        // Demand period
                                        varParams.set("demandStartDate", startDate);
                                        varParams.set("demandEndDate", endDate);

                                        // 5-minute aggregation + disabled demand smoothing
                                        varParams.set("demandAggregation", AGGREGATION_MIN);
                                        varParams.set("demandSmooth", false);

                                        // Car parameters
                                        varParams.set("car." + ParameterTypes.T.getId(), carT);
                                        varParams.set("car." + MirovaParameters.vGain.getId(), 15.0);
                                        varParams.set("car." + MirovaParameters.A_MAX.getId(), 3.5);
                                        varParams.set("car." + MirovaParameters.cooperativeDecelerationThreshold.getId(), -2.0);
                                        varParams.set("car." + MirovaParameters.farAnticipationEnabled.getId(), false);
                                        varParams.set("car." + MirovaParameters.safetyDistanceReductionFactorLaneChange.getId(),
                                                        RED_FAC);
                                        varParams.set("car." + MirovaParameters.CAPACITY_DROP_ENABLED.getId(), false);
                                        varParams.set("car." + MirovaParameters.minFollowerDecelerationThreshold.getId(), -1.0);

                                        // Truck parameters
                                        varParams.set("truck." + ParameterTypes.T.getId(), truckT);
                                        varParams.set("truck." + MirovaParameters.vGain.getId(), 30.0);
                                        varParams.set("truck." + MirovaParameters.A_MAX.getId(), 1.3);
                                        varParams.set("truck." + MirovaParameters.cooperativeDecelerationThreshold.getId(),
                                                        -0.5);
                                        varParams.set("truck." + MirovaParameters.cooperativeLaneChangesEnabled.getId(), false);
                                        varParams.set("truck." + MirovaParameters.farAnticipationEnabled.getId(), false);
                                        varParams.set("truck."
                                                        + MirovaParameters.safetyDistanceReductionFactorLaneChange.getId(),
                                                        RED_FAC);
                                        varParams.set("truck." + MirovaParameters.CAPACITY_DROP_ENABLED.getId(), false);
                                        varParams.set("truck." + MirovaParameters.minFollowerDecelerationThreshold.getId(),
                                                        -1.0);

                                        scenarioManager.addParameterVariation(scenarioName, varParams);
                                }
                        }

                        scenarioManager.setReplications(numberOfReplications);

                        int totalRuns = dates.length * headways.length * numberOfReplications;
                        System.out.println("Registered " + dates.length + " simulation day (2025-09-23) with "
                                        + numberOfReplications + " replications.");
                        System.out.println("Total runs: " + totalRuns + " on " + parallelThreads + " parallel threads.");

                        boolean success = scenarioManager.runAll(parallelThreads, false);
                        System.out.println("Execution finished. Shutting down.");
                        System.exit(success ? 0 : 1);
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
