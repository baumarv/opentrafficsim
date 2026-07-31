package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioGenerator;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioSimulationScript;

/**
 * Test runner to check simulation behavior with zero traffic demand.
 */
public class RunFreiburgNordNoDemand
{
    /**
     * Main method.
     * @param args arguments
     * @throws Exception on errors
     */
    public static void main(final String[] args) throws Exception
    {
        System.out.println("Starting FreiburgNordNoDemand run (headless)...");
        File outputDir = new File("D:\\Mitarbeitende\\gw2128\\repositories\\mirova\\output\\ots\\freiburg_nord_nodemand");
        outputDir.mkdirs();

        ScenarioGenerator scenario = new FreiburgNord();
        scenario.setOutputDirectory(outputDir);

        ScenarioParameters params = scenario.getDefaultParameters().copy();
        params.setSeed(46L);
        params.set("demandStartDate", null);
        params.set("demandEndDate", null);
        params.set("demandCsv", "C:\\Users\\gw2128\\.gemini\\antigravity-ide\\brain\\aacf1dfb-4f08-4427-96e1-e6b7edcc27d3\\scratch\\no_demand.csv");
        params.set("enableWatchdog", false);

        ScenarioSimulationScript script = scenario.buildSimulationScript(params);
        script.setGuiEnabled(false); // Run headless
        script.setAutorun(true);
        
        System.out.println("Starting simulation script...");
        try
        {
            script.start();
            System.out.println("Simulation script finished successfully.");
            System.exit(0);
        }
        catch (Exception e)
        {
            System.err.println("Simulation script failed: " + e.getMessage());
            System.exit(1);
        }
    }
}
