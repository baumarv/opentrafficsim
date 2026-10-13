package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.djunits.value.vdouble.scalar.Duration;
import org.opentrafficsim.demo.mirova.scenariomanagement.DriverModels;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioGenerator;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.models.LmrsDriverModelProvider;

/**
 * Opens one day of the Freiburg-Nord scenario in the OTS GUI, to watch it. Run this class; there is nothing else to start.
 * <p>
 * What is shown is set in the block of constants at the top of the class: the day, the seed, the driver model, how the LMRS is
 * composed, and single driver parameters. A constant left empty is not applied, so the model keeps what upstream OpenTrafficSim
 * sets. With the block as it is committed, the GUI shows what the benchmark runs: the upstream LMRS, untouched.
 * </p>
 * <p>
 * Every setting can also be given as a program argument, which then wins over the block: {@code --date=2025-10-07},
 * {@code --seed=1000045}, {@code --model=lmrs}, {@code --lmrsParameters=scenario}, {@code --simulationTime=3600s},
 * {@code --output=<dir>}, {@code --demand=<dir>}, {@code --lmrs.<setting>=<value>}, {@code --car.<parameter>=<value>},
 * {@code --truck.<parameter>=<value>}; {@code --help} lists them.
 * </p>
 * <p>
 * This is for looking, not for results: a GUI run writes its output only when the simulation reaches its end, and it writes no
 * run metadata. Use {@link RunMirovaClusterStudy} for runs that are evaluated; it takes the same options.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public final class RunFreiburgNordGui
{
    // =====================================================================================================================
    // SETTINGS - edit here, then run. An empty string means "do not touch": the model keeps upstream's value, which is
    // given in brackets. With everything below as committed, this shows the benchmark.
    // =====================================================================================================================

    // ----- what to simulate ------------------------------------------------------------------------------------------

    /** The day; one with a demand file in cluster/demand, see cluster/dates.txt. */
    private static final String DATE = "2025-09-22";

    /** The seed; empty = 42, the seed of replication 0. Replication k of a study has 42 + k * 1000003. */
    private static final String SEED = "";

    /** The simulated duration with unit, e.g. "3600s"; empty = the whole day, 13:00 to 22:00. */
    private static final String SIMULATION_TIME = "";

    // ----- driver model ------------------------------------------------------------------------------------------------

    /** The driver model; "lmrs" is the LMRS of upstream OpenTrafficSim. */
    private static final String MODEL = "lmrs";

    /**
     * The parameter set: "default" = the model's own values; "scenario" = T, a, b and s0 as the Freiburg studies set them
     * (cars 1.1 s, 1.4 m/s2, 2.0 m/s2, 2.0 m; trucks 1.4 s, 1.25 m/s2, 2.0 m/s2, 4.0 m).
     */
    private static final String LMRS_PARAMETERS = "default";

    // ----- how the LMRS is composed; wording of the values as in upstream ----------------------------------------------

    /**
     * [PASSIVE] PASSIVE: the leader on the target lane is followed with limited deceleration. ALIGN_GAP: follow that leader or
     * align with the middle of the gap, whichever allows the larger acceleration. PASSIVE_MOVING, ALIGN_GAP_MOVING: the same,
     * but disabled below the creep speed.
     */
    private static final String SYNCHRONIZATION = "";

    /**
     * [PASSIVE] NONE: no cooperation. PASSIVE: cooperate with any leader whose lane-change desire is at least dCoop.
     * PASSIVE_MOVING: the same, but disabled below the creep speed.
     */
    private static final String COOPERATION = "";

    /**
     * [INFORMED] INFORMED: informed of the model and parameters of the other drivers. EGO_HEADWAY: the same, but applying the
     * own headway value.
     */
    private static final String GAP_ACCEPTANCE = "";

    // ----- lane-change incentives: "true" or "false" -------------------------------------------------------------------

    /** [true] Mandatory incentive: follow the route. */
    private static final String INCENTIVE_ROUTE = "";

    /** [false] Mandatory incentive: join slow traffic at a split and do not block other traffic. */
    private static final String INCENTIVE_GET_IN_LANE = "";

    /** [true] Voluntary incentive: speed, with courtesy. */
    private static final String INCENTIVE_SPEED_WITH_COURTESY = "";

    /** [false] Voluntary incentive: cooperative lane changes. */
    private static final String INCENTIVE_COURTESY = "";

    /** [false] Voluntary incentive: join the shortest queue. */
    private static final String INCENTIVE_QUEUE = "";

    /** [false] Voluntary incentive: trucks stay in the two rightmost lanes. */
    private static final String INCENTIVE_STAY_RIGHT = "";

    /** [true] Voluntary incentive: keep to the slow lane. */
    private static final String INCENTIVE_KEEP = "";

    // ----- acceleration incentives and social interactions: "true" or "false" -------------------------------------------

    /** [false] Do not overtake traffic on the slow lane. */
    private static final String ACCELERATION_NO_SLOW_LANE_OVERTAKE = "";

    /** [false] Slow down prior to a lower speed limit. */
    private static final String ACCELERATION_SPEED_LIMIT_TRANSITION = "";

    /** [false] Social pressure exerted on the (potential) leader. */
    private static final String SOCIAL_PRESSURE = "";

    /** [false] Tailgating due to social pressure towards the leader. */
    private static final String TAILGATING = "";

    /** [false] Lane changes due to social pressure from the (potential) follower. */
    private static final String SOCIO_LANE_CHANGE = "";

    /** [false] Speed increase due to social pressure from the follower. */
    private static final String SOCIO_SPEED = "";

    // ----- perception --------------------------------------------------------------------------------------------------

    /** [NONE] Implementation of Fuller's task-capability model: NONE, SUMMATIVE, ANTICIPATION_RELIANCE or ATTENTION_MATRIX. */
    private static final String FULLER_IMPLEMENTATION = "";

    // ----- single driver parameters ------------------------------------------------------------------------------------

    /**
     * Driver parameters, each as "car.&lt;parameter&gt;=&lt;value with unit&gt;" or "truck.&lt;parameter&gt;=...". Any parameter of
     * ParameterTypes or LmrsParameters, for example: "car.T=1.1s", "car.Tmin=0.56s", "car.a=1.4m/s2", "car.b=2.0m/s2",
     * "car.s0=2m", "car.vGain=50km/h", "car.dFree=0.365", "car.dSync=0.577", "car.dCoop=0.788", "truck.T=1.4s".
     */
    private static final String[] PARAMETERS = {};

    // =====================================================================================================================
    // END OF SETTINGS
    // =====================================================================================================================

    /** Directory of the demand CSVs, relative to the repository root. */
    public static final String DEMAND_DIRECTORY = "cluster/demand";

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
     * @param args String[]; optional arguments that win over the settings block, see the class description
     */
    public static void main(final String[] args)
    {
        // The locale of the evaluated runs, so that what is watched is read the way it is run. Started from an IDE there
        // is no launcher that could pass -Duser.language, hence here, before anything is parsed.
        Locale.setDefault(Locale.US);
        try
        {
            Map<String, String> options = settingsBlock();
            options.putAll(parseOptions(args));
            if (options.containsKey("help"))
            {
                printUsage();
                return;
            }
            String date = options.get("date");
            File demandDirectory = options.containsKey("demand") ? new File(options.get("demand")) : findDemandDirectory();
            File demandCsv = DateStudy.resolveDemandCsv(demandDirectory, DateStudy.DEFAULT_CSV_PATTERN, date);
            if (!demandCsv.isFile())
            {
                throw new IllegalArgumentException("No demand CSV for " + date + " at " + demandCsv.getAbsolutePath()
                        + ". The dates with demand are in cluster/demand; another directory can be given with --demand=<dir>.");
            }
            File output = new File(options.getOrDefault("output", DEFAULT_OUTPUT_DIRECTORY));
            output.mkdirs();

            FreiburgNord scenario = new FreiburgNord();
            ScenarioParameters parameters = scenario.getDefaultParameters().copy()
                    .applyOverridesFrom(FreiburgStudyParameters.forDate(date, demandCsv.getAbsolutePath(), true));
            parameters.set(ScenarioGenerator.KEY_DRIVER_MODEL, options.get(ScenarioGenerator.KEY_DRIVER_MODEL));
            parameters.set(ScenarioGenerator.KEY_LMRS_PARAMETERS, options.get(ScenarioGenerator.KEY_LMRS_PARAMETERS));
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
     * Turns the settings block into options. A setting left empty is not included.
     * @return Map&lt;String, String&gt;; option key to value
     */
    private static Map<String, String> settingsBlock()
    {
        Map<String, String> options = new LinkedHashMap<>();
        put(options, "date", DATE);
        put(options, ScenarioParameters.KEY_SEED, SEED);
        put(options, ScenarioParameters.KEY_SIMULATION_TIME, SIMULATION_TIME);
        put(options, ScenarioGenerator.KEY_DRIVER_MODEL, MODEL);
        put(options, ScenarioGenerator.KEY_LMRS_PARAMETERS, LMRS_PARAMETERS);
        String lmrs = LmrsDriverModelProvider.KEY + ".";
        put(options, lmrs + LmrsDriverModelProvider.SYNCHRONIZATION, SYNCHRONIZATION);
        put(options, lmrs + LmrsDriverModelProvider.COOPERATION, COOPERATION);
        put(options, lmrs + LmrsDriverModelProvider.GAP_ACCEPTANCE, GAP_ACCEPTANCE);
        put(options, lmrs + "incentiveRoute", INCENTIVE_ROUTE);
        put(options, lmrs + "incentiveGetInLane", INCENTIVE_GET_IN_LANE);
        put(options, lmrs + "incentiveSpeedWithCourtesy", INCENTIVE_SPEED_WITH_COURTESY);
        put(options, lmrs + "incentiveCourtesy", INCENTIVE_COURTESY);
        put(options, lmrs + "incentiveQueue", INCENTIVE_QUEUE);
        put(options, lmrs + "incentiveStayRight", INCENTIVE_STAY_RIGHT);
        put(options, lmrs + "incentiveKeep", INCENTIVE_KEEP);
        put(options, lmrs + "accelerationNoSlowLaneOvertake", ACCELERATION_NO_SLOW_LANE_OVERTAKE);
        put(options, lmrs + "accelerationSpeedLimitTransition", ACCELERATION_SPEED_LIMIT_TRANSITION);
        put(options, lmrs + "socialPressure", SOCIAL_PRESSURE);
        put(options, lmrs + "tailgating", TAILGATING);
        put(options, lmrs + "socioLaneChange", SOCIO_LANE_CHANGE);
        put(options, lmrs + "socioSpeed", SOCIO_SPEED);
        put(options, lmrs + LmrsDriverModelProvider.FULLER_IMPLEMENTATION, FULLER_IMPLEMENTATION);
        for (String parameter : PARAMETERS)
        {
            int separator = parameter.indexOf('=');
            if (separator <= 0)
            {
                throw new IllegalArgumentException("PARAMETERS entry '" + parameter + "' is not of the form "
                        + "car.<parameter>=<value>.");
            }
            options.put(parameter.substring(0, separator).trim(), parameter.substring(separator + 1).trim());
        }
        return options;
    }

    /**
     * Adds a setting to the options unless it is empty.
     * @param options Map&lt;String, String&gt;; the options being collected
     * @param key String; the option key
     * @param value String; the value of the setting; empty means that the setting is not applied
     */
    private static void put(final Map<String, String> options, final String key, final String value)
    {
        if (!value.isBlank())
        {
            options.put(key, value.trim());
        }
    }

    /**
     * Finds the directory of the demand CSVs, looking in the working directory and in its parents. An IDE starts a class in
     * the repository root or in the module directory, depending on how the project was imported.
     * @return File; the demand directory
     * @throws IllegalStateException when no such directory is found
     */
    private static File findDemandDirectory()
    {
        for (File directory = new File("").getAbsoluteFile(); directory != null; directory = directory.getParentFile())
        {
            File candidate = new File(directory, DEMAND_DIRECTORY);
            if (candidate.isDirectory())
            {
                return candidate;
            }
        }
        throw new IllegalStateException("No " + DEMAND_DIRECTORY + " directory in " + new File("").getAbsolutePath()
                + " or any of its parents. Start the class from inside the repository, or give --demand=<dir>.");
    }

    /**
     * Parses {@code --key=value} and {@code --flag} arguments.
     * @param args String[]; the program arguments
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
        System.out.println("Run without arguments to use the settings block at the top of the class. Arguments win over it:");
        System.out.println("  --date=<yyyy-MM-dd> --seed=<n> --simulationTime=<duration> --output=<dir> --demand=<dir>");
        System.out.println("  --model           one of " + DriverModels.availableKeys());
        System.out.println("  --lmrsParameters  default: the model's own parameters; scenario: the scenario-level overrides "
                + "of T, a, b and s0 as a set");
        System.out.println("  --lmrs.<setting>=<value>   compose the LMRS differently: " + LmrsDriverModelProvider.SETTINGS_HELP);
        System.out.println("  --car.<parameter>=<value>, --truck.<parameter>=<value>   a driver parameter, with its unit, "
                + "e.g. --car.T=1.1s --car.vGain=50km/h");
    }
}
