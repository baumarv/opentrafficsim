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
            File outputDir =
                    new File("D:\\Mitarbeitende\\gw2128\\repositories\\mirova\\output\\ots\\freiburg_nord\\run_" + (run + 1));
            ScenarioGenerator scenario = new FreiburgNord();
            outputDir.mkdirs();
            scenario.setOutputDirectory(outputDir);

            ScenarioParameters params = scenario.getDefaultParameters().copy();
            params.setSeed(42L + run);

            // Define the demand CSV file directly
            params.set("demandCsv",
                    "D:\\Mitarbeitende\\gw2128\\repositories\\diss_mvb\\scripts\\evaluation\\fielddata\\detectors\\io\\data\\demand_freiburg_20250925_09-12_low_demand.csv");

            // Define parameters directly analogously to RunFreiburgParallel
            params.set("car." + ParameterTypes.T.getId(), 0.8);
            params.set("car." + MirovaParameters.vGain.getId(), 70.0);
            params.set("truck." + ParameterTypes.T.getId(), 1.2);
            params.set("truck." + MirovaParameters.vGain.getId(), 130.0);

            // Optional: override simulation duration if needed (otherwise it reads from demandCsv)
            // params.setSimulationTime(new Duration(6.0, DurationUnit.HOUR));

            ScenarioSimulationScript script = scenario.buildSimulationScript(params);
            script.setGuiEnabled(!Boolean.getBoolean("java.awt.headless"));
            script.setGuiEnabled(false);
            script.start();
            System.out.println("FreiburgNord run " + (run + 1) + " of 1 finished.");
            System.exit(0);
        }
    }
}
