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
     * Main execution method.
     * @param args String[]; command line arguments
     */
    public static void main(final String[] args)
    {
        try
        {
            // 1. Define the root output directory for the simulation results
            File outputDirectory = new File("D:\\Mitarbeitende\\gw2128\\repositories\\mirova\\output\\ots\\freiburg_parallel");

            // 2. Initialize the ScenarioManager
            ScenarioManager scenarioManager = new ScenarioManager(outputDirectory);

            // 3. Register the FreiburgNord class under a unique name
            String scenarioName = "FreiburgNord_Parallel";
            scenarioManager.addScenario(scenarioName, FreiburgNord.class);

            // 4. Define the base parameters
            ScenarioParameters baseParameters = new ScenarioParameters();
            baseParameters.setSeed(42L); // Base seed
            // baseParameters.setSimulationTime(new Duration(0.1, DurationUnit.HOUR)); // Commented out to read duration
            // directly from demandCsv
            baseParameters.set("demandCsv",
                    "D:\\Mitarbeitende\\gw2128\\repositories\\diss_mvb\\scripts\\evaluation\\fielddata\\detectors\\io\\data\\demand_freiburg_20250925_09-12_low_demand.csv");

            // 5. Define variations
            // We use ParameterGridBuilder to generate a Cartesian product grid sweep of parameter combinations.
            java.util.List<ScenarioParameters> variations =
                    new ParameterGridBuilder(baseParameters).addCarDimension(ParameterTypes.T, 0.7, 0.8, 0.9, 1.0)
                            .addCarDimension(MirovaParameters.vGain, 30.0, 50.0, 70.0)
                            .addTruckDimension(ParameterTypes.T, 1.0, 1.2, 1.4)
                            .addTruckDimension(MirovaParameters.vGain, 70.0, 100.0, 130.0).build();

            for (ScenarioParameters variation : variations)
            {
                scenarioManager.addParameterVariation(scenarioName, variation);
            }

            // 6. Set the number of replications (seeds to run per variation)
            int numberOfReplications = 1;
            scenarioManager.setReplications(numberOfReplications);

            // 7. Run all variations in parallel (e.g., using 4 parallel threads)
            int parallelThreads = 16;
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
