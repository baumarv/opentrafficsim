package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.road.network.factory.xml.parser.XmlParser;

/**
 * Cluster runner executing a batch of dates per invocation, with local multi-core parallelism.
 * <p>
 * <b>This is the secondary cluster entry point.</b> For the Freiburg-Nord study, prefer
 * {@link RunMirovaClusterStudy}, which runs exactly one simulation per SLURM array task: a run takes 90-120 minutes
 * while JVM startup costs seconds, so bundling saves nothing measurable and only makes the tasks harder for SLURM to
 * schedule. This batched variant remains available for scenarios whose per-run overhead is not negligible, or for running
 * several dates in one interactive session.
 * </p>
 * <p>
 * Unlike {@link RunFreiburgParallel}, this variant takes the simulated dates, output directory, thread count, demand CSV
 * location and replication count as command-line arguments, so that a SLURM job array can assign each array task its own
 * subset of dates (distribution across nodes, in addition to the existing multi-core parallelism within
 * {@link ScenarioManager#runAll(int, boolean, boolean)}).
 * </p>
 * <p>
 * The demand CSV argument may be either a single CSV file (used for every date) or a directory holding one pre-generated CSV
 * per date. In the directory case, the file name is derived from the pattern given by {@code --pattern=...}, where the
 * placeholder <code>{date}</code> is replaced by the date of the respective run; the pattern defaults to
 * {@value #DEFAULT_CSV_PATTERN}.
 * </p>
 * <p>
 * Because pre-generated demand CSVs are supplied directly, the Python-based demand preparation is disabled for all runs (see
 * {@link FreiburgStudyParameters}), and the post-run Python plotting step is skipped — neither the virtual environment nor
 * the evaluation scripts exist on the cluster.
 * </p>
 * <p>
 * Usage: {@code RunFreiburgParallelCluster <outputDir> <parallelThreads> <replications> <comma-separated-dates>
 * <demandCsvFileOrDir> [--strict] [--pattern=demand_{date}.csv]}
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author Marvin Baumann
 */
public final class RunFreiburgParallelCluster
{
    /** Default file name pattern used to resolve a per-date demand CSV inside a demand directory. */
    public static final String DEFAULT_CSV_PATTERN = "demand_{date}.csv";

    /** Utility class; not to be instantiated. */
    private RunFreiburgParallelCluster()
    {
        // utility class
    }

    /**
     * Main execution method.
     * @param args String[]; [0]=output directory, [1]=parallel threads, [2]=replications, [3]=comma-separated dates
     *            (yyyy-MM-dd), [4]=demand CSV file or directory, followed by the optional flags {@code --strict} (fail
     *            instead of falling back to synthetic demand when a CSV is missing) and {@code --pattern=<pattern>} (per-date
     *            file name pattern used when [4] is a directory)
     */
    public static void main(final String[] args)
    {
        try
        {
            if (args.length < 5)
            {
                System.err.println("Usage: RunFreiburgParallelCluster <outputDir> <parallelThreads> <replications> "
                        + "<comma-separated-dates> <demandCsvFileOrDir> [--strict] [--pattern=" + DEFAULT_CSV_PATTERN + "]");
                System.exit(2);
            }

            // Suppress verbose logging and warn/error prints from background threads
            ScenarioManager.silenceBackgroundThreads();

            File outputDirectory = new File(args[0]);
            int parallelThreads = Integer.parseInt(args[1].trim());
            int numberOfReplications = Integer.parseInt(args[2].trim());
            List<String> dates = parseDates(args[3]);
            File demandCsvLocation = new File(args[4]);

            boolean strict = false;
            String csvPattern = DEFAULT_CSV_PATTERN;
            for (int i = 5; i < args.length; i++)
            {
                String flag = args[i].trim();
                if ("--strict".equals(flag))
                {
                    strict = true;
                }
                else if (flag.startsWith("--pattern="))
                {
                    csvPattern = flag.substring("--pattern=".length());
                }
                else
                {
                    System.err.println("Unknown argument: " + flag);
                    System.exit(2);
                }
            }

            if (dates.isEmpty())
            {
                System.err.println("No dates given; nothing to run.");
                System.exit(2);
            }

            // Resolve the demand CSV per date up-front, so a misconfigured path fails before any simulation starts
            Map<String, File> demandPerDate = resolveDemandFiles(dates, demandCsvLocation, csvPattern);
            List<String> missing = new ArrayList<>();
            for (Map.Entry<String, File> entry : demandPerDate.entrySet())
            {
                if (!entry.getValue().isFile())
                {
                    missing.add(entry.getKey() + " -> " + entry.getValue().getAbsolutePath());
                }
            }
            if (!missing.isEmpty())
            {
                System.err.println("Demand CSV files missing for " + missing.size() + " of " + dates.size() + " date(s):");
                for (String entry : missing)
                {
                    System.err.println("  " + entry);
                }
                if (strict)
                {
                    System.err.println("Strict mode is enabled; aborting instead of falling back to synthetic demand.");
                    System.exit(2);
                }
                System.err.println("WARNING: these runs will fall back to synthetic demand. Use --strict to forbid this.");
            }

            // Pre-warm JAXBContext on the main thread
            XmlParser.warmUpJAXBContext();

            ScenarioManager scenarioManager = new ScenarioManager(outputDirectory);

            for (String date : dates)
            {
                String scenarioName = FreiburgStudyParameters.scenarioName(date);
                scenarioManager.addScenario(scenarioName, FreiburgNord.class);
                scenarioManager.addParameterVariation(scenarioName,
                        FreiburgStudyParameters.forDate(date, demandPerDate.get(date).getAbsolutePath(), strict));
            }

            scenarioManager.setReplications(numberOfReplications);

            int totalVariations = dates.size();
            int totalRuns = totalVariations * numberOfReplications;
            System.out.println("Registered " + dates.size() + " simulation days.");
            System.out.println("Output directory: " + outputDirectory.getAbsolutePath());
            System.out.println("Strict demand mode: " + strict);
            System.out.println("Total variations: " + totalVariations + " | Total runs: " + totalRuns + " on "
                    + parallelThreads + " parallel threads.");

            boolean success = scenarioManager.runAll(parallelThreads, false, true);
            System.out.println("Execution finished. Shutting down.");
            System.exit(success ? 0 : 1);
        }
        catch (Exception exception)
        {
            System.err.println("An error occurred during the parallel scenario execution:");
            exception.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Splits the comma-separated date argument into a list of trimmed, non-empty dates.
     * @param datesArgument String; the raw comma-separated argument value
     * @return List&lt;String&gt;; the parsed dates in the given order
     */
    private static List<String> parseDates(final String datesArgument)
    {
        List<String> dates = new ArrayList<>();
        for (String date : datesArgument.split(","))
        {
            String trimmed = date.trim();
            if (!trimmed.isEmpty())
            {
                dates.add(trimmed);
            }
        }
        return dates;
    }

    /**
     * Maps every date to the demand CSV file it should use. When the given location is a directory, the file name is derived
     * from the pattern by replacing the <code>{date}</code> placeholder; otherwise the location itself is used for all dates.
     * @param dates List&lt;String&gt;; the dates to resolve
     * @param location File; the demand CSV file or the directory holding per-date CSV files
     * @param pattern String; the per-date file name pattern, containing the placeholder <code>{date}</code>
     * @return Map&lt;String, File&gt;; the resolved demand CSV file per date
     */
    private static Map<String, File> resolveDemandFiles(final List<String> dates, final File location, final String pattern)
    {
        Map<String, File> resolved = new LinkedHashMap<>();
        boolean perDate = location.isDirectory();
        for (String date : dates)
        {
            resolved.put(date, perDate ? new File(location, pattern.replace("{date}", date)) : location);
        }
        return resolved;
    }
}
