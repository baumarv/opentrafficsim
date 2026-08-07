package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;

import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;

/**
 * Parallel test runner for evaluating the impact of Dynamic Cooperative Deceleration Threshold
 * (distance-dependent linear interpolation vs. static/constant cooperation threshold).
 * <p>
 * Scenario: FreiburgNord demand date 2025-10-01 (13:00:00 to 22:00:00).<br>
 * Evaluates Dynamic Cooperation ON vs. OFF across 2 headway settings (T=0.9/1.2 and T=1.0/1.3) x 6 replications.
 * Total: 24 parallel simulation runs.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author Marvin Baumann
 */
public class RunDynamicCooperationTest20251001
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
            final String TARGET_DATE = "2025-10-01";
            final String START_DATE = TARGET_DATE + " 13:00:00";
            final String END_DATE = TARGET_DATE + " 22:00:00";
            final double RED_FAC = 0.60;
            final double ACC_DAMPING = 0.80;
            final int AGGREGATION_MIN = 5;

            // 2 Headway T combinations: (0.9/1.2) and (1.0/1.3)
            double[][] headways = new double[][] {
                {0.90, 1.20},
                {1.00, 1.30}
            };

            // Dynamic Cooperation toggle (true = distance-dependent interpolation, false = static threshold)
            boolean[] dynamicToggles = new boolean[] {true, false};

            int numberOfReplications = 6;
            int parallelThreads = 24;

            File outputDirectory = new File("D:\\Mitarbeitende\\gw2128\\repositories\\mirova\\output\\ots"
                    + "\\study_dynamic_cooperation_20251001");
            // --- END CONFIGURATION ---

            // Pre-warm JAXBContext on the main thread (with exec:java classloader)
            org.opentrafficsim.road.network.factory.xml.parser.XmlParser.warmUpJAXBContext();

            ScenarioManager scenarioManager = new ScenarioManager(outputDirectory);
            String scenarioName = "FreiburgNord_" + TARGET_DATE + "_13-00_to_22-00";

            scenarioManager.addScenario(scenarioName, FreiburgNord.class);

            for (double[] h : headways)
            {
                double carT = h[0];
                double truckT = h[1];

                for (boolean dynamicCoop : dynamicToggles)
                {
                    ScenarioParameters varParams = new ScenarioParameters();
                    varParams.setSeed(42L);
                    varParams.set("enableTrajectoryRecording", false);

                    // Demand period
                    varParams.set("demandStartDate", START_DATE);
                    varParams.set("demandEndDate", END_DATE);

                    // 5-minute aggregation + disabled demand smoothing
                    varParams.set("demandAggregation", AGGREGATION_MIN);
                    varParams.set("demandSmooth", false);

                    // Car parameters
                    varParams.set("car." + ParameterTypes.T.getId(), carT);
                    varParams.set("car." + MirovaParameters.vGain.getId(), 15.0);
                    varParams.set("car." + MirovaParameters.A_MAX.getId(), 3.5);
                    varParams.set("car." + MirovaParameters.cooperativeDecelerationThreshold.getId(), -2.0);
                    varParams.set("car." + MirovaParameters.farAnticipationEnabled.getId(), false);
                    varParams.set("car." + MirovaParameters.safetyDistanceReductionFactorLaneChange.getId(), RED_FAC);
                    varParams.set("car." + MirovaParameters.CAPACITY_DROP_ENABLED.getId(), false);
                    varParams.set("car." + MirovaParameters.RELAXATION_ACC_DAMPING_FACTOR.getId(), ACC_DAMPING);
                    varParams.set("car." + MirovaParameters.enableDynamicCooperativeThreshold.getId(), dynamicCoop);

                    // Truck parameters
                    varParams.set("truck." + ParameterTypes.T.getId(), truckT);
                    varParams.set("truck." + MirovaParameters.vGain.getId(), 30.0);
                    varParams.set("truck." + MirovaParameters.A_MAX.getId(), 1.3);
                    varParams.set("truck." + MirovaParameters.cooperativeDecelerationThreshold.getId(), -0.5);
                    varParams.set("truck." + MirovaParameters.cooperativeLaneChangesEnabled.getId(), false);
                    varParams.set("truck." + MirovaParameters.farAnticipationEnabled.getId(), false);
                    varParams.set("truck." + MirovaParameters.safetyDistanceReductionFactorLaneChange.getId(), RED_FAC);
                    varParams.set("truck." + MirovaParameters.CAPACITY_DROP_ENABLED.getId(), false);
                    varParams.set("truck." + MirovaParameters.RELAXATION_ACC_DAMPING_FACTOR.getId(), ACC_DAMPING);
                    varParams.set("truck." + MirovaParameters.enableDynamicCooperativeThreshold.getId(), dynamicCoop);

                    scenarioManager.addParameterVariation(scenarioName, varParams);
                }
            }

            scenarioManager.setReplications(numberOfReplications);

            System.out.println("================================================================================");
            System.out.println("🚀 LAUNCHING DYNAMIC COOPERATION TEST STUDY (FreiburgNord " + TARGET_DATE + ")");
            System.out.println("   Variations: 4 (Dynamic Coop ON/OFF x Headway Pairs 0.9/1.2 & 1.0/1.3)");
            System.out.println("   Replications: " + numberOfReplications + " seeds per variation (Total " + (4 * numberOfReplications) + " runs)");
            System.out.println("   Threads: " + parallelThreads);
            System.out.println("   Output: " + outputDirectory.getAbsolutePath());
            System.out.println("================================================================================\n");

            boolean success = scenarioManager.runAll(parallelThreads, false);

            System.out.println("\n================================================================================");
            System.out.println(success ? "✅ DYNAMIC COOPERATION TEST STUDY COMPLETED SUCCESSFULLY!" : "❌ STUDY FAILED WITH ERRORS!");
            System.out.println("================================================================================");
            System.exit(success ? 0 : 1);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }
}
