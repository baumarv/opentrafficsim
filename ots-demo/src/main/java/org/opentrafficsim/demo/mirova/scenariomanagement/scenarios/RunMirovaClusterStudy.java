package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.djunits.value.vdouble.scalar.Duration;
import org.opentrafficsim.demo.mirova.scenariomanagement.DriverModels;
import org.opentrafficsim.demo.mirova.scenariomanagement.RunMetadata;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioGenerator;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.StudyDefinition;
import org.opentrafficsim.demo.mirova.scenariomanagement.StudyRegistry;
import org.opentrafficsim.demo.mirova.scenariomanagement.models.LmrsDriverModelProvider;

/**
 * Generic single-run cluster entry point: one process executes exactly one run of a selected study.
 * <p>
 * This is the primary cluster entry point for every study. One SLURM array task corresponds to one run on one core: a run
 * takes far longer than JVM startup, so nothing is gained by running several in one JVM.
 * </p>
 * <p>
 * A run is addressed by its <b>global index</b> into the study's deterministic enumeration — for each registered scenario,
 * for each of its parameter variations, for each replication. This is the same enumeration
 * {@link ScenarioManager#runAll(int, boolean, boolean)} executes as a batch, so the addressing generalises from date sweeps
 * (one variation per scenario) to parameter grids (many variations per scenario) without any change here.
 * </p>
 * <p>
 * Usage: {@code RunMirovaClusterStudy --study=<name|class> --output=<dir> (--count | --manifest=<file> | --index=<n>)
 * [--model=<key>] [--preset=<name|file>] [--key=value ...]}
 * </p>
 * <p>
 * Any further {@code --key=value} arguments are passed to the study definition, which documents the keys it honours (e.g.
 * {@code --demand=}, {@code --dates=}, {@code --replications=}, {@code --strict=true}).
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public final class RunMirovaClusterStudy
{
    /** Utility class; not to be instantiated. */
    private RunMirovaClusterStudy()
    {
        // utility class
    }

    /** Command-line option selecting the driver model; the scenario parameter it sets is the same word. */
    public static final String OPTION_MODEL = ScenarioGenerator.KEY_DRIVER_MODEL;

    /** Former name of {@link #OPTION_MODEL}, still accepted so that existing submit scripts keep working. */
    public static final String OPTION_MODEL_LEGACY = "tacticalPlanner";

    /**
     * Puts the driver model the command line asked for on every parameter variation of the study.
     * <p>
     * A study that varies the driver model itself cannot also be given a global one: the override would flatten the
     * distinction into a single model, and the output of such a run is indistinguishable from a correct one. That is refused.
     * </p>
     * @param model String; the model key from the command line
     * @param scenarioManager ScenarioManager; the manager the study registered its runs with
     * @param study StudyDefinition; the study, for messages
     * @throws IllegalArgumentException when the key is empty or names no driver model
     * @throws IllegalStateException when the study varies the model itself, or the key reached no variation
     */
    static void applyDriverModel(final String model, final ScenarioManager scenarioManager, final StudyDefinition study)
    {
        String requested = model.trim();
        if (requested.isEmpty())
        {
            throw new IllegalArgumentException("--" + OPTION_MODEL + " needs a value; available: "
                    + DriverModels.availableKeys() + ".");
        }
        // Fails here, in seconds and before any run folder exists, when the key names no model.
        DriverModels.resolve(requested);
        Set<Object> already = scenarioManager.distinctVariationValues(ScenarioGenerator.KEY_DRIVER_MODEL);
        if (already.size() > 1)
        {
            throw new IllegalStateException("--" + OPTION_MODEL + "=" + requested + " would overwrite the driver model that "
                    + "study '" + study.getName() + "' sets per cell (it registered " + already + "). A study that varies "
                    + "the driver model is run without this option.");
        }
        int changed = scenarioManager.setOnAllVariations(ScenarioGenerator.KEY_DRIVER_MODEL, requested);
        if (changed == 0)
        {
            throw new IllegalStateException("--" + OPTION_MODEL + "=" + requested + " reached no parameter variation of "
                    + "study '" + study.getName() + "'.");
        }
        System.out.println("Driver model:     " + requested + " (set on " + changed + " parameter variation(s))");
    }

    /** Command-line option naming a preset: a file of driver model options, or the name of one in cluster/presets. */
    public static final String OPTION_PRESET = ScenarioManager.KEY_PRESET;

    /** Directory of the presets, relative to the repository root. */
    public static final String PRESET_DIRECTORY = "cluster/presets";

    /** File name extension of a preset. */
    public static final String PRESET_EXTENSION = ".preset";

    /**
     * Reads a preset: a named combination of driver model options, kept in a file so that it can be selected explicitly and
     * recorded by name.
     * <p>
     * A preset holds one {@code key=value} per line, with the same keys the command line takes for the driver model:
     * {@code <model key>.<setting>}, {@code car.<parameter>} and {@code truck.<parameter>}. Empty lines and lines starting
     * with {@code #} are ignored. Any other key is refused: a preset changes the driver model and nothing else.
     * </p>
     * @param selector String; the path of a preset file, or the name of a preset in {@value #PRESET_DIRECTORY} (found from the
     *            working directory upwards), without its extension
     * @return Map&lt;String, String&gt;; the options of the preset, in file order
     * @throws IOException when the file cannot be read
     * @throws IllegalArgumentException when no such preset exists, or a line is not a driver model option
     */
    static Map<String, String> readPreset(final String selector) throws IOException
    {
        File file = new File(selector.trim());
        if (!file.isFile())
        {
            file = null;
            for (File directory = new File("").getAbsoluteFile(); directory != null && file == null; directory =
                    directory.getParentFile())
            {
                File candidate = new File(new File(directory, PRESET_DIRECTORY), selector.trim() + PRESET_EXTENSION);
                if (candidate.isFile())
                {
                    file = candidate;
                }
            }
        }
        if (file == null)
        {
            throw new IllegalArgumentException("No preset '" + selector + "': it is neither a file nor the name of a file in "
                    + PRESET_DIRECTORY + " (" + selector.trim() + PRESET_EXTENSION + ") above the working directory.");
        }
        Map<String, String> lines = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8))
        {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#"))
            {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator <= 0)
            {
                throw new IllegalArgumentException("Preset " + file + ": line '" + trimmed + "' is not key=value.");
            }
            lines.put(trimmed.substring(0, separator).trim(), trimmed.substring(separator + 1).trim());
        }
        Map<String, String> options = driverModelOptions(lines);
        if (options.size() != lines.size())
        {
            lines.keySet().removeAll(options.keySet());
            throw new IllegalArgumentException("Preset " + file + " holds keys that are not driver model options: "
                    + lines.keySet() + ". A preset takes <model key>.<setting>, car.<parameter> and truck.<parameter>.");
        }
        return options;
    }

    /**
     * Selects, from the command-line options, those addressed to the driver model: the settings of a model
     * ({@code <model key>.<setting>}) and the parameters of a vehicle class ({@code car.<parameter>},
     * {@code truck.<parameter>}). They become scenario parameters of the same name.
     * @param options Map&lt;String, String&gt;; the command-line options, key to value
     * @return Map&lt;String, String&gt;; the options addressed to the driver model, in the order given
     */
    static Map<String, String> driverModelOptions(final Map<String, String> options)
    {
        Map<String, String> selected = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : options.entrySet())
        {
            int dot = entry.getKey().indexOf('.');
            if (dot > 0)
            {
                String prefix = entry.getKey().substring(0, dot);
                if ("car".equals(prefix) || "truck".equals(prefix) || DriverModels.availableKeys().contains(prefix))
                {
                    selected.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return selected;
    }

    /**
     * Sets the simulated duration on every parameter variation, for smoke tests that must not simulate a whole day.
     * @param simulationTime String; the duration with unit, for example {@code 900s} or {@code 15min}
     * @param scenarioManager ScenarioManager; the manager the study registered its runs with
     */
    static void applySimulationTime(final String simulationTime, final ScenarioManager scenarioManager)
    {
        Duration duration = Duration.valueOf(simulationTime.trim());
        scenarioManager.setOnAllVariations(ScenarioParameters.KEY_SIMULATION_TIME, duration);
        System.out.println("Simulation time:  " + duration + " (overrides the demand window)");
    }

    /**
     * Runs one run of a study, or prints the number of runs or a manifest of them.
     * @param args String[]; the command-line arguments, see the class description
     */
    public static void main(final String[] args)
    {
        try
        {
            Map<String, String> options = parseOptions(args);

            String studySelector = options.get("study");
            String outputOption = options.get("output");
            boolean countMode = options.containsKey("count");
            String manifestPath = options.get("manifest");
            String indexOption = options.get("index");

            if (studySelector == null || outputOption == null
                    || (!countMode && manifestPath == null && indexOption == null))
            {
                printUsage();
                System.exit(2);
            }

            StudyDefinition study = StudyRegistry.resolve(studySelector);
            File outputDirectory = new File(outputOption);

            ScenarioManager scenarioManager = new ScenarioManager(outputDirectory);
            study.register(scenarioManager, options);
            String model = options.getOrDefault(OPTION_MODEL, options.getOrDefault(OPTION_MODEL_LEGACY, DriverModels.LMRS));
            applyDriverModel(model, scenarioManager, study);
            if (options.containsKey("lmrsParameters"))
            {
                throw new IllegalArgumentException("--lmrsParameters no longer exists. The driver model runs on its own "
                        + "parameters; change them with --car.<parameter>=<value>, --truck.<parameter>=<value> or a preset.");
            }
            if (options.containsKey(ScenarioParameters.KEY_SIMULATION_TIME))
            {
                applySimulationTime(options.get(ScenarioParameters.KEY_SIMULATION_TIME), scenarioManager);
            }
            // A preset first, explicit options after it, so that an option given on the command line wins.
            Map<String, String> driverModel = new LinkedHashMap<>();
            if (options.containsKey(OPTION_PRESET))
            {
                String preset = options.get(OPTION_PRESET);
                driverModel.putAll(readPreset(preset));
                String presetName = new File(preset.trim()).getName();
                scenarioManager.setOnAllVariations(ScenarioManager.KEY_PRESET, presetName.endsWith(PRESET_EXTENSION)
                        ? presetName.substring(0, presetName.length() - PRESET_EXTENSION.length()) : presetName);
                System.out.println("Preset:           " + preset);
            }
            driverModel.putAll(driverModelOptions(options));
            for (Map.Entry<String, String> entry : driverModel.entrySet())
            {
                scenarioManager.setOnAllVariations(entry.getKey(), entry.getValue());
                System.out.println("Changed:          " + entry.getKey() + "=" + entry.getValue());
            }
            int totalRuns = scenarioManager.countRuns();

            if (totalRuns == 0)
            {
                System.err.println("Study '" + study.getName() + "' registered no runs; nothing to do.");
                System.exit(2);
            }

            if (manifestPath != null)
            {
                writeManifest(new File(manifestPath), study, scenarioManager);
            }

            if (countMode || indexOption == null)
            {
                // --count prints the number used for '#SBATCH --array=0-<N-1>' and runs nothing.
                System.out.println(totalRuns);
                System.exit(0);
                return;
            }

            int globalIndex = Integer.parseInt(indexOption.trim());
            if (globalIndex < 0 || globalIndex >= totalRuns)
            {
                System.err.println("Run index " + globalIndex + " is out of range; study '" + study.getName() + "' has "
                        + totalRuns + " runs (valid indices 0.." + (totalRuns - 1) + ").");
                System.exit(2);
            }

            // Suppress verbose logging and warn/error prints from background threads
            ScenarioManager.silenceBackgroundThreads();

            // Fail in seconds, before any run folder exists, and put the code that runs at the top of the log.
            RunMetadata.require();
            System.out.println("Build:            " + RunMetadata.describeBuild());
            System.out.println("Study:            " + study.getName() + " (" + study.getDescription() + ")");
            System.out.println("Run index:        " + globalIndex + " of " + totalRuns);
            System.out.println("Output directory: " + outputDirectory.getAbsolutePath());

            boolean success = scenarioManager.runByGlobalIndex(globalIndex);
            System.out.println("Execution finished. Shutting down.");
            System.exit(success ? 0 : 1);
        }
        catch (Exception exception)
        {
            System.err.println("An error occurred during the cluster study run:");
            exception.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Writes the informational run manifest: one line per global index, describing which scenario, variation, replication and
     * seed it addresses. Execution never reads this file; the authoritative mapping is the deterministic enumeration in
     * {@link ScenarioManager}.
     * @param manifestFile File; the file to write
     * @param study StudyDefinition; the study being described
     * @param manager ScenarioManager; the manager holding the registered study
     * @throws Exception when the manifest cannot be written or the run descriptions cannot be derived
     */
    private static void writeManifest(final File manifestFile, final StudyDefinition study, final ScenarioManager manager)
            throws Exception
    {
        File parent = manifestFile.getAbsoluteFile().getParentFile();
        if (parent != null)
        {
            parent.mkdirs();
        }

        List<String> descriptions = manager.describeRuns();
        try (PrintWriter writer = new PrintWriter(manifestFile, StandardCharsets.UTF_8))
        {
            writer.println("# MiRoVA run manifest — informational only; execution addresses runs by global index.");
            writer.println("# Study: " + study.getName() + " — " + study.getDescription());
            writer.println("# Total runs: " + descriptions.size());
            writer.println("# index\tscenario\tvariation\treplication\tseed\tparameters");
            for (String description : descriptions)
            {
                writer.println(description);
            }
        }
        System.err.println("Wrote manifest with " + descriptions.size() + " runs to " + manifestFile.getAbsolutePath());
    }

    /**
     * Parses the {@code --key=value} and {@code --flag} command line arguments into a map. A flag without a value is stored
     * with an empty string.
     * @param args String[]; the raw command line arguments
     * @return Map&lt;String, String&gt;; the parsed options
     */
    private static Map<String, String> parseOptions(final String[] args)
    {
        Map<String, String> options = new LinkedHashMap<>();
        for (String arg : args)
        {
            String trimmed = arg.trim();
            if (!trimmed.startsWith("--"))
            {
                throw new IllegalArgumentException("Unexpected argument '" + trimmed + "'; expected --key=value or --flag.");
            }
            String body = trimmed.substring(2);
            int separator = body.indexOf('=');
            if (separator < 0)
            {
                options.put(body, "");
            }
            else
            {
                options.put(body.substring(0, separator), body.substring(separator + 1));
            }
        }
        return options;
    }

    /** Prints the usage message to standard error. */
    private static void printUsage()
    {
        System.err.println("Usage: RunMirovaClusterStudy --study=<name|class> --output=<dir> "
                + "(--count | --manifest=<file> | --index=<n>) [--key=value ...]");
        System.err.println("  --study      study to run; known studies: " + StudyRegistry.knownStudies()
                + ", or a fully qualified StudyDefinition class name");
        System.err.println("  --output     root output directory");
        System.err.println("  --count      print the total number of runs and exit (use for '--array=0-<N-1>')");
        System.err.println("  --manifest   write an informational index->run mapping to the given file");
        System.err.println("  --index      the 0-based global index of the single run to execute");
        System.err.println("  --model      driver model every vehicle drives; default '" + DriverModels.LMRS + "'; available: "
                + DriverModels.availableKeys() + " (--" + OPTION_MODEL_LEGACY + " is accepted as an alias)");
        System.err.println("  --preset     a named combination of the driver model options below: a file, or the name of "
                + "one in " + PRESET_DIRECTORY + "; options given explicitly win over it");
        System.err.println("  --simulationTime  simulated duration with unit, e.g. 900s; for smoke tests only");
        System.err.println("  --lmrs.<setting>=<value>  compose the LMRS differently from upstream: "
                + LmrsDriverModelProvider.SETTINGS_HELP);
        System.err.println("  --car.<parameter>=<value>, --truck.<parameter>=<value>  a driver parameter with its unit, "
                + "e.g. --car.T=1.1s");
        System.err.println("  Study options, e.g.: --demand=<file|dir> --dates=<list|file> --replications=<n> --strict=true");
    }
}
