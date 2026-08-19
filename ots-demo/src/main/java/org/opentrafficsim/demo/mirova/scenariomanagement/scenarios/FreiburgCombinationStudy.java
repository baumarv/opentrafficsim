package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.util.List;
import java.util.Map;

import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.StudyDefinition;

/**
 * Crosses a small list of named headway combinations with every simulated date: N combinations x M dates x R replications.
 * <p>
 * This is the shape neither existing study covers. {@link FreiburgDateStudy} applies one fixed parameter set per date, and
 * {@link FreiburgParameterStudy} sweeps one dimension at a time around a baseline on a single date. Here every named
 * combination is run on every date, so combinations can be compared across the same days of field data.
 * </p>
 * <p>
 * Each variation starts from {@link FreiburgStudyParameters#forDate(String, String, boolean)} - hence from the shared
 * {@link FreiburgStudyParameters#baseBehaviorParams()} baseline plus the usual demand wiring - and overrides only the car
 * and truck desired headway {@code T}. A combination therefore differs from the multi-day evaluation study in exactly two
 * parameters, by construction rather than by convention.
 * </p>
 * <p>
 * Options honoured by {@link #register(ScenarioManager, Map)}, identical to {@link FreiburgDateStudy}'s:
 * </p>
 * <ul>
 * <li>{@code dates} — a comma-separated list of dates, or the path of a file with one date per line. Required.</li>
 * <li>{@code demand} — the demand CSV file, or a directory holding one pre-generated CSV per date. Required.</li>
 * <li>{@code pattern} — the per-date file name pattern used when {@code demand} is a directory. Defaults to
 * {@value FreiburgDateStudy#DEFAULT_CSV_PATTERN}.</li>
 * <li>{@code replications} — the number of replications per date and combination. Defaults to
 * {@value FreiburgDateStudy#DEFAULT_REPLICATIONS}.</li>
 * <li>{@code strict} — when {@code true}, a missing demand CSV is fatal instead of falling back to synthetic demand.
 * Defaults to {@code false}.</li>
 * </ul>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author Marvin Baumann
 */
public class FreiburgCombinationStudy implements StudyDefinition
{
    /**
     * One named combination of desired headways.
     * @param label String; the name of the combination, used in the output folder name
     * @param carT double; the desired headway T of cars, in seconds
     * @param truckT double; the desired headway T of trucks, in seconds
     */
    public record HeadwayCombination(String label, double carT, double truckT)
    {
    }

    /**
     * The combinations run by this study, matching the pairs of the local RunFreiburgParallel runner. Adding a fourth
     * combination is a matter of adding one entry here; nothing else in this class depends on the list's length.
     */
    public static final List<HeadwayCombination> COMBINATIONS =
            List.of(new HeadwayCombination("standard", 1.00, 1.30), new HeadwayCombination("tighter", 0.90, 1.20));

    /** Scenario parameter key recording which combination produced a variation, for downstream post-processing. */
    public static final String KEY_COMBINATION = "headwayCombination";

    @Override
    public String getName()
    {
        return "combos";
    }

    @Override
    public String getDescription()
    {
        return "Named headway combinations crossed with every date: " + COMBINATIONS.size() + " combinations x dates.";
    }

    @Override
    public void register(final ScenarioManager manager, final Map<String, String> options) throws Exception
    {
        List<String> dates = FreiburgDateStudy.resolveDates(options.get("dates"));
        if (dates.isEmpty())
        {
            throw new IllegalArgumentException("Study 'combos' requires --dates=<comma-separated-dates|file>.");
        }

        String demandOption = options.get("demand");
        if (demandOption == null || demandOption.trim().isEmpty())
        {
            throw new IllegalArgumentException("Study 'combos' requires --demand=<csv file or directory>.");
        }
        File demandLocation = new File(demandOption.trim());
        String pattern = options.getOrDefault("pattern", FreiburgDateStudy.DEFAULT_CSV_PATTERN);
        boolean strict = Boolean.parseBoolean(options.getOrDefault("strict", "false"));
        int replications = Integer.parseInt(
                options.getOrDefault("replications", String.valueOf(FreiburgDateStudy.DEFAULT_REPLICATIONS)));

        // Same up-front check as the date study: a missing CSV aborts before any simulation starts.
        Map<String, File> demandPerDate = FreiburgDateStudy.resolveDemandCsvs(dates, demandLocation, pattern, strict);

        // One scenario per (date, combination), so the output folder names both. Registration order is
        // date-major, which the global run index follows: index = ((dateIndex * combinations) + comboIndex) * replications.
        for (String date : dates)
        {
            String demandCsvPath = demandPerDate.get(date).getAbsolutePath();
            for (HeadwayCombination combination : COMBINATIONS)
            {
                String scenarioName = FreiburgStudyParameters.scenarioName(date, combination.label());
                manager.addScenario(scenarioName, FreiburgNord.class);
                manager.addParameterVariation(scenarioName, forCombination(date, demandCsvPath, strict, combination));
            }
        }

        manager.setReplications(replications);
    }

    /**
     * Builds the parameters of one date and combination: the multi-day evaluation study's configuration with only the car
     * and truck headway replaced.
     * @param date String; the simulated date in yyyy-MM-dd form
     * @param demandCsvPath String; the absolute path of the pre-generated demand CSV for this date
     * @param strict boolean; when true, a missing or unreadable demand CSV is fatal
     * @param combination HeadwayCombination; the headway combination to apply
     * @return ScenarioParameters; the parameter variation for this date and combination
     */
    public static ScenarioParameters forCombination(final String date, final String demandCsvPath, final boolean strict,
            final HeadwayCombination combination)
    {
        ScenarioParameters params = FreiburgStudyParameters.forDate(date, demandCsvPath, strict);
        params.set("car." + ParameterTypes.T.getId(), combination.carT());
        params.set("truck." + ParameterTypes.T.getId(), combination.truckT());
        // Recorded so runParams.txt names the combination rather than only its headway values.
        params.set(KEY_COMBINATION, combination.label());
        return params;
    }
}
