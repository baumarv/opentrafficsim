package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opentrafficsim.demo.mirova.scenariomanagement.FacilityRegistry;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.StudyDefinition;
import org.opentrafficsim.demo.mirova.scenariomanagement.TrafficFacility;

/**
 * The multi-day evaluation study: one scenario per simulated date, one parameter variation each, run with several
 * replications.
 * <p>
 * Facility-agnostic - a date list crossed with one fixed parameter set is a generic concept, so the facility supplies the
 * generator class, the baseline and the scenario naming via {@link TrafficFacility}. It defaults to
 * {@value #DEFAULT_FACILITY}, so an invocation without {@code --facility} behaves exactly as before this was generalised.
 * </p>
 * <p>
 * Options honoured by {@link #register(ScenarioManager, Map)}:
 * </p>
 * <ul>
 * <li>{@code dates} — a comma-separated list of dates, or the path of a file with one date per line ({@code #} comments and
 * blank lines are ignored). Required.</li>
 * <li>{@code demand} — the demand CSV file, or a directory holding one pre-generated CSV per date. Required.</li>
 * <li>{@code pattern} — the per-date file name pattern used when {@code demand} is a directory; the placeholder
 * <code>{date}</code> is replaced by the date. Defaults to {@value #DEFAULT_CSV_PATTERN}.</li>
 * <li>{@code replications} — the number of replications per date. Defaults to {@value #DEFAULT_REPLICATIONS}.</li>
 * <li>{@code strict} — when {@code true}, a missing demand CSV is fatal instead of falling back to synthetic demand.
 * Defaults to {@code false}.</li>
 * <li>{@code facility} — the traffic facility to simulate, by short name or class name. Defaults to
 * {@value #DEFAULT_FACILITY}.</li>
 * </ul>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author Marvin Baumann
 */
public class DateStudy implements StudyDefinition
{
    /** Default file name pattern used to resolve a per-date demand CSV inside a demand directory. */
    public static final String DEFAULT_CSV_PATTERN = "demand_{date}.csv";

    /** Default number of replications per date. */
    public static final int DEFAULT_REPLICATIONS = 6;

    /** Facility simulated when no {@code facility} option is given, keeping existing invocations unchanged. */
    public static final String DEFAULT_FACILITY = FreiburgFacility.NAME;

    @Override
    public String getName()
    {
        return "dates";
    }

    @Override
    public String getDescription()
    {
        return "Multi-day evaluation study: one scenario per date, one variation each.";
    }

    @Override
    public void register(final ScenarioManager manager, final Map<String, String> options) throws Exception
    {
        TrafficFacility facility = FacilityRegistry.resolve(options.getOrDefault("facility", DEFAULT_FACILITY));

        List<String> dates = resolveDates(options.get("dates"));
        if (dates.isEmpty())
        {
            throw new IllegalArgumentException("Study 'dates' requires --dates=<comma-separated-dates|file>.");
        }

        String demandOption = options.get("demand");
        if (demandOption == null || demandOption.trim().isEmpty())
        {
            throw new IllegalArgumentException("Study 'dates' requires --demand=<csv file or directory>.");
        }
        File demandLocation = new File(demandOption.trim());
        String pattern = options.getOrDefault("pattern", DEFAULT_CSV_PATTERN);
        boolean strict = Boolean.parseBoolean(options.getOrDefault("strict", "false"));
        int replications = Integer.parseInt(options.getOrDefault("replications", String.valueOf(DEFAULT_REPLICATIONS)));

        Map<String, File> demandPerDate = resolveDemandCsvs(dates, demandLocation, pattern, strict);

        for (String date : dates)
        {
            String scenarioName = facility.scenarioName(date);
            manager.addScenario(scenarioName, facility.getGeneratorClass());
            manager.addParameterVariation(scenarioName,
                    facility.forDate(date, demandPerDate.get(date).getAbsolutePath(), strict));
        }

        manager.setReplications(replications);
    }

    /**
     * Resolves the demand CSV of every date and reports the ones that are missing, so that a study aborts before any
     * simulation starts rather than discovering a missing input mid-run. Shared by every date-driven study.
     * @param dates List&lt;String&gt;; the dates to resolve
     * @param location File; the demand CSV file or the directory holding per-date CSV files
     * @param pattern String; the per-date file name pattern, containing the placeholder <code>{date}</code>
     * @param strict boolean; when true, a missing demand CSV aborts instead of warning
     * @return Map&lt;String, File&gt;; the resolved demand CSV per date, in the order the dates were given
     * @throws IllegalStateException when a demand CSV is missing and strict mode is enabled
     */
    public static Map<String, File> resolveDemandCsvs(final List<String> dates, final File location, final String pattern,
            final boolean strict)
    {
        Map<String, File> resolved = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();

        for (String date : dates)
        {
            File demandCsv = resolveDemandCsv(location, pattern, date);
            resolved.put(date, demandCsv);
            if (!demandCsv.isFile())
            {
                missing.add(date + " -> " + demandCsv.getAbsolutePath());
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
                throw new IllegalStateException("Strict mode is enabled; refusing to run with missing demand CSV files.");
            }
            System.err.println("WARNING: these runs will fall back to synthetic demand. Use --strict=true to forbid this.");
        }
        return resolved;
    }

    /**
     * Resolves the demand CSV of one date: the location itself when it is a file, otherwise the per-date file inside it.
     * @param location File; the demand CSV file or the directory holding per-date CSV files
     * @param pattern String; the per-date file name pattern, containing the placeholder <code>{date}</code>
     * @param date String; the date in yyyy-MM-dd form
     * @return File; the demand CSV of that date
     */
    public static File resolveDemandCsv(final File location, final String pattern, final String date)
    {
        return location.isDirectory() ? new File(location, pattern.replace("{date}", date)) : location;
    }

    /**
     * Resolves the dates option, which is either a path to a file with one date per line or a comma-separated list.
     * @param datesOption String; the raw option value, may be null
     * @return List&lt;String&gt;; the dates, in the given order
     * @throws IOException when the date file cannot be read
     */
    public static List<String> resolveDates(final String datesOption) throws IOException
    {
        List<String> dates = new ArrayList<>();
        if (datesOption == null || datesOption.trim().isEmpty())
        {
            return dates;
        }

        File asFile = new File(datesOption.trim());
        List<String> rawEntries = asFile.isFile()
                ? Files.readAllLines(asFile.toPath(), StandardCharsets.UTF_8)
                : Arrays.asList(datesOption.split(","));

        for (String entry : rawEntries)
        {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#"))
            {
                dates.add(trimmed);
            }
        }
        return dates;
    }
}
