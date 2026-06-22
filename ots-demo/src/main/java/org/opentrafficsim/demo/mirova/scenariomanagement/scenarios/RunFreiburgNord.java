package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;

import org.djunits.unit.DurationUnit;
import org.djunits.value.vdouble.scalar.Duration;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioGenerator;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioSimulationScript;

/**
 * Simple runner for FreiburgNord scenario.
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
            File outputDir =
                    new File("D:\\Mitarbeitende\\gw2128\\repositories\\mirova\\output\\ots\\freiburg_nord\\run_" + (run + 1));
            ScenarioGenerator scenario = new FreiburgNord();
            outputDir.mkdirs();
            scenario.setOutputDirectory(outputDir);

            ScenarioParameters params = new ScenarioParameters();
            params.setSeed(42 + run);
            params.setSimulationTime(new Duration(2.0, DurationUnit.HOUR));
            params.setTruckShare(0.1);
            params.setMergeShare(0.2);

            ScenarioSimulationScript script = scenario.buildSimulationScript(params);
            script.setGuiEnabled(true);
            script.start();
        }
    }
}
