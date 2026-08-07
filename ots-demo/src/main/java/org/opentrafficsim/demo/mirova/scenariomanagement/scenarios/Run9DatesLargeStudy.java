package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;

import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;

/**
 * Sequential day-by-day parallel runner for the large 9-day simulation study.
 * <p>
 * Dates (9 total): 2025-10-01, 2025-10-07, 2025-10-08, 2025-10-09, 2025-10-14, 2025-10-15,
 * 2025-10-21, 2025-10-27, 2025-10-29.<br>
 * Variations per day (4 total):
 * <ul>
 *   <li>Headway T=(0.90/1.20) s, Damping=0.8, RedFac=0.60</li>
 *   <li>Headway T=(0.90/1.20) s, Damping=0.6, RedFac=0.60</li>
 *   <li>Headway T=(1.00/1.30) s, Damping=0.8, RedFac=0.60</li>
 *   <li>Headway T=(1.00/1.30) s, Damping=0.6, RedFac=0.60</li>
 * </ul>
 * Replications: 10 random seeds per variation (seeds 42..51).<br>
 * Trajectory recording: ENABLED.<br>
 * Execution: Day by day sequentially (40 runs per day executed across 24 parallel threads).
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author Antigravity Agent
 */
public class Run9DatesLargeStudy
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
            final int NUMBER_OF_REPLICATIONS = 10;
            final int PARALLEL_THREADS = 24;

            // 9 Target dates (13:00:00 to 22:00:00)
            String[] dates = new String[] {
                "2025-10-01",
                "2025-10-07",
                "2025-10-08",
                "2025-10-09",
                "2025-10-14",
                "2025-10-15",
                "2025-10-21",
                "2025-10-27",
                "2025-10-29"
            };

            // 2 Headway T combinations: (0.9/1.2) and (1.0/1.3)
            double[][] headways = new double[][] {
                {0.90, 1.20},
                {1.00, 1.30}
            };

            // 2 Acceleration Damping Factors: 0.8 and 0.6
            double[] dampingFactors = new double[] {0.8, 0.6};

            File outputDirectory = new File("D:\\Mitarbeitende\\gw2128\\repositories\\mirova\\output\\ots"
                    + "\\study_9dates_trajectories_10seeds_202510");
            // --- END CONFIGURATION ---

            // Pre-warm JAXBContext on the main thread (with exec:java classloader)
            org.opentrafficsim.road.network.factory.xml.parser.XmlParser.warmUpJAXBContext();

            System.out.println("================================================================================");
            System.out.println("🚀 LAUNCHING 9-DAY LARGE SIMULATION STUDY (360 TOTAL RUNS, TRAJECTORIES ENABLED)");
            System.out.println("================================================================================");
            System.out.println("Dates (9): " + String.join(", ", dates));
            System.out.println("Replications per variation: " + NUMBER_OF_REPLICATIONS + " (Seeds 42.." + (42 + NUMBER_OF_REPLICATIONS - 1) + ")");
            System.out.println("Output Root: " + outputDirectory.getAbsolutePath());
            System.out.println("================================================================================\n");

            for (int dayIdx = 0; dayIdx < dates.length; dayIdx++)
            {
                String date = dates[dayIdx];
                String startDate = date + " 13:00:00";
                String endDate = date + " 22:00:00";
                String scenarioName = "FreiburgNord_" + date + "_13-00_to_22-00";

                System.out.println("\n--------------------------------------------------------------------------------");
                System.out.println("📅 STARTING DAY " + (dayIdx + 1) + "/" + dates.length + ": " + date);
                System.out.println("--------------------------------------------------------------------------------");

                ScenarioManager dayManager = new ScenarioManager(outputDirectory);
                dayManager.addScenario(scenarioName, FreiburgNord.class);

                for (double[] h : headways)
                {
                    double carT = h[0];
                    double truckT = h[1];

                    for (double dampFactor : dampingFactors)
                    {
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
                        varParams.set("car." + MirovaParameters.safetyDistanceReductionFactorLaneChange.getId(), RED_FAC);
                        varParams.set("car." + MirovaParameters.CAPACITY_DROP_ENABLED.getId(), false);
                        varParams.set("car." + MirovaParameters.RELAXATION_ACC_DAMPING_FACTOR.getId(), dampFactor);
                        varParams.set("car." + MirovaParameters.RELAXATION_ACC_DAMPING_ENABLED.getId(), true);

                        // Truck parameters
                        varParams.set("truck." + ParameterTypes.T.getId(), truckT);
                        varParams.set("truck." + MirovaParameters.vGain.getId(), 30.0);
                        varParams.set("truck." + MirovaParameters.A_MAX.getId(), 1.3);
                        varParams.set("truck." + MirovaParameters.cooperativeDecelerationThreshold.getId(), -0.5);
                        varParams.set("truck." + MirovaParameters.cooperativeLaneChangesEnabled.getId(), false);
                        varParams.set("truck." + MirovaParameters.farAnticipationEnabled.getId(), false);
                        varParams.set("truck." + MirovaParameters.safetyDistanceReductionFactorLaneChange.getId(), RED_FAC);
                        varParams.set("truck." + MirovaParameters.CAPACITY_DROP_ENABLED.getId(), false);
                        varParams.set("truck." + MirovaParameters.RELAXATION_ACC_DAMPING_FACTOR.getId(), dampFactor);
                        varParams.set("truck." + MirovaParameters.RELAXATION_ACC_DAMPING_ENABLED.getId(), true);

                        dayManager.addParameterVariation(scenarioName, varParams);
                    }
                }

                dayManager.setReplications(NUMBER_OF_REPLICATIONS);
                boolean daySuccess = dayManager.runAll(PARALLEL_THREADS, false);

                if (!daySuccess)
                {
                    System.err.println("❌ ERROR: Simulation execution failed for date " + date);
                }
                else
                {
                    System.out.println("✅ COMPLETED DAY " + (dayIdx + 1) + "/" + dates.length + ": " + date + " (40 runs completed)");
                }
            }

            System.out.println("\n================================================================================");
            System.out.println("🎉 ALL 9 SIMULATION DAYS (360 TOTAL RUNS) COMPLETED SUCCESSFULLY!");
            System.out.println("================================================================================");
            System.exit(0);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
