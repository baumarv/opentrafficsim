package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;

import org.djunits.unit.DurationUnit;
import org.djunits.value.vdouble.scalar.Duration;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioGenerator;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioSimulationScript;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;

/**
 * Simple runner for FreiburgNord scenario with custom parameter configurations.
 */
public class RunFreiburgNord
{
    /**
     * Main entry point.
     * @param args command line arguments
     * @throws Exception on simulation errors
     */
    public static void main(final String[] args) throws Exception
    {
        for (int run = 0; run < 1; run++)
        {
            System.out.println("Starting FreiburgNord run " + (run + 1) + " of 1...");
            File outputDir = new File("D:\\Mitarbeitende\\gw2128\\repositories\\mirova\\output\\ots\\freiburg_gui_test");
            ScenarioGenerator scenario = new FreiburgNord();
            outputDir.mkdirs();
            scenario.setOutputDirectory(outputDir);

            ScenarioParameters params = scenario.getDefaultParameters().copy();
            params.setSeed(42L);

            // Set demand date range and 5-minute aggregation interval without demand smoothing
            params.set("demandStartDate", "2025-10-13 16:00:00");
            params.set("demandEndDate", "2025-10-13 18:00:00");
            params.set("demandAggregation", 5);
            params.set("demandSmooth", false);

            // Behavioral parameters matching the study
            params.set("car." + ParameterTypes.T.getId(), 1.00);
            params.set("truck." + ParameterTypes.T.getId(), 1.30);
            params.set("car." + MirovaParameters.vGain.getId(), 15.0);
            params.set("truck." + MirovaParameters.vGain.getId(), 30.0);

            ScenarioSimulationScript script = scenario.buildSimulationScript(params);
            script.setGuiEnabled(true);
            script.start();
            // System.out.println("FreiburgNord run " + (run + 1) + " of 1 finished.");
            // System.exit(0);
        }
    }
}
