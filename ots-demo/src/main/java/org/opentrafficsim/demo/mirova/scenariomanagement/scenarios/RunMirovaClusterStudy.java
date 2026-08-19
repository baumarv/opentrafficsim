package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.StudyDefinition;
import org.opentrafficsim.demo.mirova.scenariomanagement.StudyRegistry;
import org.opentrafficsim.road.network.factory.xml.parser.XmlParser;

/**
 * Generic single-run cluster entry point: one process executes exactly one run of a selected study.
 * <p>
 * This is the primary cluster entry point for every study. One SLURM array task corresponds to one run on one core: a run
 * takes 90-120 minutes while JVM startup costs seconds, so bundling buys nothing measurable, whereas single-core tasks let
 * SLURM backfill any free core anywhere.
 * </p>
 * <p>
 * A run is addressed by its <b>global index</b> into the study's deterministic enumeration — for each registered scenario,
 * for each of its parameter variations, for each replication. This is the same enumeration
 * {@link ScenarioManager#runAll(int, boolean, boolean)} executes as a batch, so the addressing generalises from date sweeps
 * (one variation per scenario) to parameter grids (many variations per scenario) without any change here.
 * </p>
 * <p>
 * Usage: {@code RunMirovaClusterStudy --study=<name|class> --output=<dir> (--count | --manifest=<file> | --index=<n>)
 * [--key=value ...]}
 * </p>
 * <p>
 * Any further {@code --key=value} arguments are passed to the study definition, which documents the keys it honours (e.g.
 * {@code --demand=}, {@code --dates=}, {@code --replications=}, {@code --strict=true}).
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author Marvin Baumann
 */
public final class RunMirovaClusterStudy
{
    /** Utility class; not to be instantiated. */
    private RunMirovaClusterStudy()
    {
        // utility class
    }

    /**
     * Main execution method.
     * @param args String[]; the command line arguments, all of the form {@code --key=value} or {@code --flag}; see the class
     *            documentation
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

            // Pre-warm JAXBContext on the main thread
            XmlParser.warmUpJAXBContext();

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
        System.err.println("  Study options, e.g.: --demand=<file|dir> --dates=<list|file> --replications=<n> --strict=true");
    }
}
