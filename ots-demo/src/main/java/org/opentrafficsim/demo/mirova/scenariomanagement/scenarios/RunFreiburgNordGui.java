package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import org.djunits.value.vdouble.scalar.Duration;
import org.opentrafficsim.demo.mirova.scenariomanagement.DriverModels;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioGenerator;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.models.LmrsDriverModelProvider;

/**
 * Opens one day of the Freiburg-Nord scenario in the OTS GUI, to watch it.
 * <p>
 * The run is configured like a run of the date study: same network, same demand of the day, same seed handling. The driver
 * model, its parameter set, its composition and single driver parameters can be chosen on the command line. With no option
 * beyond the date it shows what the benchmark runs: the upstream LMRS, untouched.
 * </p>
 * <p>
 * Usage: {@code RunFreiburgNordGui [--date=2025-09-22] [--demand=<dir>] [--seed=42] [--model=lmrs]
 * [--lmrsParameters=default|scenario] [--simulationTime=<duration>] [--output=<dir>] [--lmrs.<setting>=<value> ...]
 * [--car.<parameter>=<value> ...] [--truck.<parameter>=<value> ...]}
 * </p>
 * <p>
 * This is for looking, not for results: a GUI run writes its output only when the simulation reaches its end, and it writes no
 * run metadata. Use {@link RunMirovaClusterStudy} for runs that are evaluated.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public final class RunFreiburgNordGui
{
    /** Date shown when none is given: the first of the calibration dates. */
    public static final String DEFAULT_DATE = "2025-09-22";

    /** Directory of the demand CSVs when none is given, relative to the repository root. */
    public static final String DEFAULT_DEMAND_DIRECTORY = "cluster/demand";

    /** Output directory when none is given, relative to the working directory. */
    public static final String DEFAULT_OUTPUT_DIRECTORY = "out/gui";

    /**
     * Utility class.
     */
    private RunFreiburgNordGui()
    {
        // utility class
    }

    /**
     * Starts the GUI.
     * @param args String[]; the command-line arguments, see the class description
     */
    public static void main(final String[] args)
    {
        try
        {
            Map<String, String> options = parseOptions(args);
            if (options.containsKey("help"))
            {
                printUsage();
                return;
            }
            String date = options.getOrDefault("date", DEFAULT_DATE);
            File demandDirectory = new File(options.getOrDefault("demand", DEFAULT_DEMAND_DIRECTORY));
            File demandCsv = DateStudy.resolveDemandCsv(demandDirectory, DateStudy.DEFAULT_CSV_PATTERN, date);
            if (!demandCsv.isFile())
            {
                throw new IllegalArgumentException("No demand CSV for " + date + " at " + demandCsv.getAbsolutePath()
                        + ". Give the directory with --demand=<dir>; the dates with demand are in cluster/demand.");
            }
            File output = new File(options.getOrDefault("output", DEFAULT_OUTPUT_DIRECTORY));
            output.mkdirs();

            FreiburgNord scenario = new FreiburgNord();
            ScenarioParameters parameters = scenario.getDefaultParameters().copy()
                    .applyOverridesFrom(FreiburgStudyParameters.forDate(date, demandCsv.getAbsolutePath(), true));
            parameters.set(ScenarioGenerator.KEY_DRIVER_MODEL, options.getOrDefault(ScenarioGenerator.KEY_DRIVER_MODEL,
                    DriverModels.LMRS));
            parameters.set(ScenarioGenerator.KEY_LMRS_PARAMETERS, options.getOrDefault(ScenarioGenerator.KEY_LMRS_PARAMETERS,
                    ScenarioGenerator.PARAMETER_SET_DEFAULT));
            if (options.containsKey(ScenarioParameters.KEY_SEED))
            {
                parameters.setSeed(Long.parseLong(options.get(ScenarioParameters.KEY_SEED).trim()));
            }
            if (options.containsKey(ScenarioParameters.KEY_SIMULATION_TIME))
            {
                parameters.setSimulationTime(Duration.valueOf(options.get(ScenarioParameters.KEY_SIMULATION_TIME).trim()));
            }
            Map<String, String> passedOn = RunMirovaClusterStudy.driverModelOptions(options);
            for (Map.Entry<String, String> entry : passedOn.entrySet())
            {
                parameters.set(entry.getKey(), entry.getValue());
            }

            System.out.println("Freiburg-Nord, " + date + ", seed " + parameters.getSeed());
            System.out.println("Driver model:  " + parameters.get(ScenarioGenerator.KEY_DRIVER_MODEL, String.class)
                    + ", parameter set " + parameters.get(ScenarioGenerator.KEY_LMRS_PARAMETERS, String.class));
            System.out.println("Changed:       " + (passedOn.isEmpty() ? "nothing (the model as upstream builds it)" : passedOn));
            System.out.println("Output (written when the simulation reaches its end): " + output.getAbsolutePath());

            scenario.setOutputDirectory(output);
            ScenarioGenerator.resetAppliedModel();
            scenario.startSimpleSimulation(parameters, output);
            System.out.println("Model built:   " + ScenarioGenerator.getAppliedModelDescription().replace("\n", "; "));
        }
        catch (Exception exception)
        {
            System.err.println("The GUI could not be started: " + exception.getMessage());
            exception.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Parses {@code --key=value} and {@code --flag} arguments.
     * @param args String[]; the command-line arguments
     * @return Map&lt;String, String&gt;; key to value, a flag having the empty string as value
     */
    private static Map<String, String> parseOptions(final String[] args)
    {
        Map<String, String> options = new LinkedHashMap<>();
        for (String arg : args)
        {
            String trimmed = arg.trim();
            if (!trimmed.startsWith("--"))
            {
                throw new IllegalArgumentException("Unexpected argument '" + trimmed + "'; expected --key=value.");
            }
            String body = trimmed.substring(2);
            int separator = body.indexOf('=');
            options.put(separator < 0 ? body : body.substring(0, separator), separator < 0 ? "" : body.substring(separator + 1));
        }
        return options;
    }

    /**
     * Prints the usage message.
     */
    private static void printUsage()
    {
        System.out.println("Usage: RunFreiburgNordGui [--date=" + DEFAULT_DATE + "] [--demand=<dir>] [--seed=42] [--model="
                + DriverModels.LMRS + "] [--lmrsParameters=default|scenario] [--simulationTime=<duration>] [--output=<dir>]");
        System.out.println("  --model           one of " + DriverModels.availableKeys());
        System.out.println("  --lmrsParameters  default: the model's own parameters; scenario: the scenario-level overrides "
                + "of T, a, b and s0 as a set");
        System.out.println("  --lmrs.<setting>=<value>   compose the LMRS differently: " + LmrsDriverModelProvider.SETTINGS_HELP);
        System.out.println("  --car.<parameter>=<value>, --truck.<parameter>=<value>   a driver parameter, with its unit, "
                + "e.g. --car.T=1.1s --car.vGain=50km/h");
    }
}
