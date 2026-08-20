package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.demo.mirova.scenariomanagement.FacilityRegistry;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.StudyDefinition;
import org.opentrafficsim.demo.mirova.scenariomanagement.TrafficFacility;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;

/**
 * Crosses named headway combinations with relaxation acceleration damping factors and lane-change safety distance
 * reduction factors, on every simulated date: N combinations x D damping x S safety distance x M dates x R
 * replications.
 * <p>
 * This is the shape neither existing study covers. {@link DateStudy} applies one fixed parameter set per date, and
 * {@link FreiburgParameterStudy} sweeps one dimension at a time around a baseline on a single date. Here the full
 * combination x factor grid is run on every date, so cells can be compared both against each other and across the same
 * days of field data.
 * </p>
 * <p>
 * Each variation starts from {@link FreiburgStudyParameters#forDate(String, String, boolean)} - hence from the shared
 * {@link FreiburgStudyParameters#baseBehaviorParams()} baseline plus the usual demand wiring - and overrides only the car
 * and truck desired headway {@code T}, the relaxation acceleration damping factor and the lane-change safety distance
 * reduction factor. Both factors are deliberately the same for cars and trucks, so a cell of the grid is described by
 * three numbers rather than six, and a variation differs from the evaluation study in exactly the swept parameters.
 * </p>
 * <p>
 * Options honoured by {@link #register(ScenarioManager, Map)}, identical to {@link DateStudy}'s:
 * </p>
 * <ul>
 * <li>{@code dates} — a comma-separated list of dates, or the path of a file with one date per line. Required.</li>
 * <li>{@code demand} — the demand CSV file, or a directory holding one pre-generated CSV per date. Required.</li>
 * <li>{@code pattern} — the per-date file name pattern used when {@code demand} is a directory. Defaults to
 * {@value DateStudy#DEFAULT_CSV_PATTERN}.</li>
 * <li>{@code replications} — the number of replications per date and combination. Defaults to
 * {@value DateStudy#DEFAULT_REPLICATIONS}.</li>
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
     * The headway combinations run by this study, matching the pairs of the local RunFreiburgParallel runner. Adding a third
     * combination is a matter of adding one entry here; nothing else in this class depends on the list's length.
     */
    public static final List<HeadwayCombination> COMBINATIONS =
            List.of(new HeadwayCombination("standard", 1.00, 1.30), new HeadwayCombination("tighter", 0.90, 1.20));

    /**
     * The relaxation acceleration damping factors crossed with every headway combination. One value per entry, applied to
     * cars and trucks alike; 0.80 is the baseline value, so that combination reproduces the evaluation study's setting.
     */
    public static final List<Double> ACC_DAMPING_FACTORS = List.of(0.60, 0.80);

    /**
     * The lane-change safety distance reduction factors crossed with the other two dimensions, applied to cars and
     * trucks alike. These are the two values the study has actually used historically: 0.50 until 2026-07-31 and 0.60
     * since 2026-08-06, the latter being the facility baseline {@link FreiburgStudyParameters#RED_FAC}.
     */
    public static final List<Double> SAFETY_DISTANCE_FACTORS = List.of(0.50, 0.60);

    /** Scenario parameter key recording which headway combination produced a variation, for downstream post-processing. */
    public static final String KEY_COMBINATION = "headwayCombination";

    /** Scenario parameter key recording the relaxation acceleration damping factor of a variation. */
    public static final String KEY_ACC_DAMPING_FACTOR = "accDampingFactor";

    /** Scenario parameter key recording the lane-change safety distance reduction factor of a variation. */
    public static final String KEY_SAFETY_DISTANCE_FACTOR = "safetyDistanceFactor";

    @Override
    public String getName()
    {
        return "combos";
    }

    @Override
    public String getDescription()
    {
        return "Headway combinations x damping factors x safety distance factors x dates: " + COMBINATIONS.size()
                + " x " + ACC_DAMPING_FACTORS.size() + " x " + SAFETY_DISTANCE_FACTORS.size()
                + " variations per date.";
    }

    @Override
    public void register(final ScenarioManager manager, final Map<String, String> options) throws Exception
    {
        // Content stays Freiburg-specific, but the generator class and baseline come through the same mechanism the
        // facility-agnostic date study uses.
        TrafficFacility facility = FacilityRegistry.resolve(FreiburgFacility.NAME);

        List<String> dates = DateStudy.resolveDates(options.get("dates"));
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
        String pattern = options.getOrDefault("pattern", DateStudy.DEFAULT_CSV_PATTERN);
        boolean strict = Boolean.parseBoolean(options.getOrDefault("strict", "false"));
        int replications = Integer.parseInt(
                options.getOrDefault("replications", String.valueOf(DateStudy.DEFAULT_REPLICATIONS)));

        // Same up-front check as the date study: a missing CSV aborts before any simulation starts.
        Map<String, File> demandPerDate = DateStudy.resolveDemandCsvs(dates, demandLocation, pattern, strict);

        // One scenario per (date, headway combination, damping factor, safety distance factor), so the output folder
        // names all four. Registration order is date-major, then combination, then damping, then safety distance,
        // which the global run index follows.
        for (String date : dates)
        {
            String demandCsvPath = demandPerDate.get(date).getAbsolutePath();
            for (HeadwayCombination combination : COMBINATIONS)
            {
                for (double accDampingFactor : ACC_DAMPING_FACTORS)
                {
                    for (double safetyDistanceFactor : SAFETY_DISTANCE_FACTORS)
                    {
                        String scenarioName = facility.scenarioName(date,
                                variantLabel(combination, accDampingFactor, safetyDistanceFactor));
                        manager.addScenario(scenarioName, facility.getGeneratorClass());
                        manager.addParameterVariation(scenarioName, forCombination(facility, date, demandCsvPath,
                                strict, combination, accDampingFactor, safetyDistanceFactor));
                    }
                }
            }
        }

        manager.setReplications(replications);
    }

    /**
     * Builds the parameters of one grid cell: the multi-day evaluation study's configuration with only the car and truck
     * headway, the relaxation acceleration damping factor and the lane-change safety distance reduction factor replaced.
     * @param facility TrafficFacility; the facility supplying the baseline and demand wiring
     * @param date String; the simulated date in yyyy-MM-dd form
     * @param demandCsvPath String; the absolute path of the pre-generated demand CSV for this date
     * @param strict boolean; when true, a missing or unreadable demand CSV is fatal
     * @param combination HeadwayCombination; the headway combination to apply
     * @param accDampingFactor double; the relaxation acceleration damping factor, applied to cars and trucks alike
     * @param safetyDistanceFactor double; the safety distance reduction factor, applied to cars and trucks alike
     * @return ScenarioParameters; the parameter variation for this grid cell
     */
    public static ScenarioParameters forCombination(final TrafficFacility facility, final String date,
            final String demandCsvPath, final boolean strict, final HeadwayCombination combination,
            final double accDampingFactor, final double safetyDistanceFactor)
    {
        ScenarioParameters params = facility.forDate(date, demandCsvPath, strict);
        params.set("car." + ParameterTypes.T.getId(), combination.carT());
        params.set("truck." + ParameterTypes.T.getId(), combination.truckT());

        // Both swept factors apply to cars and trucks alike, so a grid cell is described by three numbers rather
        // than six.
        params.set("car." + MirovaParameters.RELAXATION_ACC_DAMPING_FACTOR.getId(), accDampingFactor);
        params.set("truck." + MirovaParameters.RELAXATION_ACC_DAMPING_FACTOR.getId(), accDampingFactor);
        params.set("car." + MirovaParameters.safetyDistanceReductionFactorLaneChange.getId(), safetyDistanceFactor);
        params.set("truck." + MirovaParameters.safetyDistanceReductionFactorLaneChange.getId(), safetyDistanceFactor);

        // Recorded so runParams.txt names the variation rather than only carrying its numeric values.
        params.set(KEY_COMBINATION, combination.label());
        params.set(KEY_ACC_DAMPING_FACTOR, accDampingFactor);
        params.set(KEY_SAFETY_DISTANCE_FACTOR, safetyDistanceFactor);
        return params;
    }

    /**
     * Returns the label identifying one cell of the combination grid, used as the suffix of the scenario name.
     * <p>
     * Formatted with {@link Locale#ROOT} so the decimal separator is a dot on every machine: the label ends up in directory
     * names that post-processing matches on, and those must not depend on the format locale of whichever node ran the job.
     * </p>
     * @param combination HeadwayCombination; the headway combination
     * @param accDampingFactor double; the relaxation acceleration damping factor
     * @param safetyDistanceFactor double; the lane-change safety distance reduction factor
     * @return String; the label, e.g. {@code standard_adamp0.60_sdr0.50}
     */
    public static String variantLabel(final HeadwayCombination combination, final double accDampingFactor,
            final double safetyDistanceFactor)
    {
        return combination.label() + String.format(Locale.ROOT, "_adamp%.2f_sdr%.2f", accDampingFactor,
                safetyDistanceFactor);
    }
}
