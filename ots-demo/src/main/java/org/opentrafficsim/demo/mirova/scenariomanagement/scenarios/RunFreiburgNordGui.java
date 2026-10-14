package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;

import org.djunits.value.vdouble.scalar.Duration;
import org.opentrafficsim.base.parameters.ParameterType;
import org.opentrafficsim.demo.mirova.scenariomanagement.DriverModels;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioGenerator;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.models.LmrsDefaults;
import org.opentrafficsim.demo.mirova.scenariomanagement.models.LmrsDriverModelProvider;
import org.opentrafficsim.demo.mirova.scenariomanagement.models.LmrsSettings;
import org.opentrafficsim.road.gtu.tactical.lmrs.LmrsFactory.Setting;

/**
 * Opens one day of the Freiburg-Nord scenario in the OTS GUI, to watch it. Run this class; it takes no arguments.
 * <p>
 * What is shown is set in the code at the top of the class, with the types of OpenTrafficSim itself: the day and the seed as
 * constants, the configuration the LMRS starts from as {@link #DEFAULTS}, changes to its composition in
 * {@link #lmrsSettings()} as {@link Setting}s of upstream's factory with the strategy objects and switches the LMRS uses, and
 * single driver parameters in {@link #driverParameters(ScenarioParameters)} as {@link ParameterType}s with DJUnits values.
 * </p>
 * <p>
 * As committed, the LMRS starts from {@link LmrsDefaults#FINAL_V2} and nothing is changed on top, so the GUI shows what the
 * benchmark runs; the values of that configuration are listed in {@link LmrsDefaults}. On start the class prints the
 * composition and the parameters read back from the factories that were built.
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
    // =====================================================================================================================
    // SETTINGS - edit here, then run.
    // =====================================================================================================================

    /** The day; one with a demand file in cluster/demand, see cluster/dates.txt. */
    private static final LocalDate DATE = LocalDate.of(2025, 9, 22);

    /** The seed. 42 is the seed of replication 0; replication k of a study has 42 + k * 1000003. */
    private static final long SEED = 42L;

    /** The simulated duration, for example {@code Optional.of(Duration.ofSI(3600.0))}; empty = the whole day, 13:00 to 22:00. */
    private static final Optional<Duration> SIMULATION_TIME = Optional.empty();

    /** The driver model; {@link DriverModels#LMRS} is the LMRS of upstream OpenTrafficSim. */
    private static final String MODEL = DriverModels.LMRS;

    /**
     * The parameter set, the switch between the configurations of the benchmark:
     * <ul>
     * <li>{@link LmrsDefaults#FINAL_V2}: ShortMerge composition, courtesy for cars, driver parameters of final_v2. The
     * benchmark default, the cluster variant {@code final-v2}.</li>
     * <li>{@link LmrsDefaults#SHORTMERGE}: ShortMerge composition, courtesy for all, upstream's parameter values. The cluster
     * variant {@code shortmerge}.</li>
     * <li>{@link LmrsDefaults#UPSTREAM}: upstream's factory as it is.</li>
     * </ul>
     */
    private static final LmrsDefaults DEFAULTS = LmrsDefaults.FINAL_V2;

    /**
     * Changes to the composition of the LMRS, for cars and trucks alike, on top of {@link #DEFAULTS}. Empty as committed. Each
     * line sets one {@link Setting} of upstream's LmrsFactory, for example:
     * <pre>
     * .set(Setting.SYNCHRONIZATION, Synchronization.PASSIVE)       // PASSIVE, PASSIVE_MOVING, ALIGN_GAP, ALIGN_GAP_MOVING
     * .set(Setting.COOPERATION, Cooperation.PASSIVE)               // NONE, PASSIVE, PASSIVE_MOVING
     * .set(Setting.GAP_ACCEPTANCE, GapAcceptance.EGO_HEADWAY)      // INFORMED, EGO_HEADWAY
     * .set(Setting.INCENTIVE_COURTESY, true)                       // and the other INCENTIVE_* switches
     * .set(Setting.FULLER_IMPLEMENTATION, FullerImplementation.NONE)
     * </pre>
     * @return LmrsSettings; the changes
     */
    private static LmrsSettings lmrsSettings()
    {
        return new LmrsSettings();
    }

    /**
     * Changes to the composition of the LMRS for one vehicle class only, applied after {@link #lmrsSettings()}. Empty as
     * committed; for example {@code CAR.equals(vehicleClass) ? new LmrsSettings().set(Setting.INCENTIVE_QUEUE, true) : ...}.
     * @param vehicleClass String; {@link #CAR} or {@link #TRUCK}
     * @return LmrsSettings; the changes for that class
     */
    private static LmrsSettings lmrsSettings(final String vehicleClass)
    {
        return new LmrsSettings();
    }

    /**
     * Single driver parameters, on top of {@link #DEFAULTS}. Empty as committed. Add lines with
     * {@link #set(ScenarioParameters, String, ParameterType, Object)}, for example
     * {@code set(parameters, CAR, ParameterTypes.T, Duration.ofSI(1.1));} or
     * {@code set(parameters, TRUCK, LmrsParameters.VGAIN, new Speed(50.0, SpeedUnit.KM_PER_HOUR));}. Any parameter of
     * {@code ParameterTypes} or {@code LmrsParameters} can be set.
     * @param parameters ScenarioParameters; the parameters of the run, to add to
     */
    private static void driverParameters(final ScenarioParameters parameters)
    {
        // no driver parameter is changed beyond the defaults
    }

    // =====================================================================================================================
    // END OF SETTINGS
    // =====================================================================================================================

    /** Vehicle class of cars, as driver parameters are addressed. */
    public static final String CAR = "car";

    /** Vehicle class of trucks, as driver parameters are addressed. */
    public static final String TRUCK = "truck";

    /** Directory of the demand CSVs, relative to the repository root. */
    public static final String DEMAND_DIRECTORY = "cluster/demand";

    /** Output directory, relative to the working directory. */
    public static final String OUTPUT_DIRECTORY = "out/gui";

    /**
     * Utility class.
     */
    private RunFreiburgNordGui()
    {
        // utility class
    }

    /**
     * Sets a driver parameter of one vehicle class, with the value type the parameter has.
     * @param parameters ScenarioParameters; the parameters of the run
     * @param vehicleClass String; {@link #CAR} or {@link #TRUCK}
     * @param parameterType ParameterType&lt;V&gt;; the parameter
     * @param value V; its value
     * @param <V> the value type of the parameter
     */
    @SuppressWarnings("unused")
    private static <V> void set(final ScenarioParameters parameters, final String vehicleClass,
            final ParameterType<V> parameterType, final V value)
    {
        parameters.set(vehicleClass + "." + parameterType.getId(), value);
    }

    /**
     * Starts the GUI.
     * @param args String[]; not used; the settings are in the code at the top of the class
     */
    public static void main(final String[] args)
    {
        // The locale of the evaluated runs, so that what is watched is read the way it is run. Started from an IDE there
        // is no launcher that could pass -Duser.language, hence here, before anything is parsed.
        Locale.setDefault(Locale.US);
        try
        {
            if (args.length > 0)
            {
                throw new IllegalArgumentException("This class takes no arguments; its settings are in the code at the top "
                        + "of the class. RunMirovaClusterStudy takes options.");
            }
            String date = DATE.toString();
            File demandCsv = DateStudy.resolveDemandCsv(findDemandDirectory(), DateStudy.DEFAULT_CSV_PATTERN, date);
            if (!demandCsv.isFile())
            {
                throw new IllegalArgumentException("No demand CSV for " + date + " at " + demandCsv.getAbsolutePath()
                        + ". The dates with demand are in " + DEMAND_DIRECTORY + ".");
            }
            File output = new File(OUTPUT_DIRECTORY);
            output.mkdirs();

            FreiburgNord scenario = new FreiburgNord();
            ScenarioParameters parameters = scenario.getDefaultParameters().copy()
                    .applyOverridesFrom(FreiburgStudyParameters.forDate(date, demandCsv.getAbsolutePath(), true));
            parameters.setSeed(SEED);
            SIMULATION_TIME.ifPresent(parameters::setSimulationTime);
            parameters.set(ScenarioGenerator.KEY_DRIVER_MODEL, MODEL);
            if (DriverModels.LMRS.equals(MODEL))
            {
                String prefix = LmrsDriverModelProvider.KEY + ".";
                parameters.set(prefix + LmrsDriverModelProvider.KEY_DEFAULTS, DEFAULTS.id());
                parameters.set(prefix + LmrsDriverModelProvider.KEY_SETTINGS, lmrsSettings());
                for (String vehicleClass : LmrsDriverModelProvider.VEHICLE_CLASSES)
                {
                    parameters.set(prefix + vehicleClass + "." + LmrsDriverModelProvider.KEY_SETTINGS,
                            lmrsSettings(vehicleClass));
                }
            }
            driverParameters(parameters);

            System.out.println("Freiburg-Nord, " + date + ", seed " + SEED + ", driver model " + MODEL);
            System.out.println("Output (written when the simulation reaches its end): " + output.getAbsolutePath());

            scenario.setOutputDirectory(output);
            ScenarioGenerator.resetAppliedModel();
            scenario.startSimpleSimulation(parameters, output);

            // Read back from the factories that were built, so this is what runs and not what was asked for.
            System.out.println("Driver model as built:");
            for (String line : ScenarioGenerator.getAppliedModelDescription().split("\\R"))
            {
                System.out.println("  " + line);
            }
        }
        catch (Exception exception)
        {
            System.err.println("The GUI could not be started: " + exception.getMessage());
            exception.printStackTrace();
            System.exit(1);
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
                + " or any of its parents. Start the class from inside the repository.");
    }
}
