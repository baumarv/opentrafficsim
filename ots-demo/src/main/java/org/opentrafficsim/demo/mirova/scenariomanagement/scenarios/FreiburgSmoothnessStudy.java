package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.opentrafficsim.demo.mirova.scenariomanagement.FacilityRegistry;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.StudyDefinition;
import org.opentrafficsim.demo.mirova.scenariomanagement.TrafficFacility;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.FreiburgCombinationStudy.HeadwayCombination;

/**
 * Desired headway crossed with relaxation acceleration damping, on one date.
 * <p>
 * The two axes work against each other on the same quantity, which is why neither can be set alone.
 * </p>
 * <p>
 * <b>Damping</b> scales positive accelerations while a relaxation is active - that is, in the moments right after a
 * merge, for the merging vehicle and for the follower it cut in front of. It therefore governs how smoothly traffic
 * re-sorts itself after an insertion. A paired local comparison over seven seeds found switching it off worth a great
 * deal: vehicles going through a stop-and-go cycle fell from 26.5 % to 14.7 %, ramp standstills from 767 to 304 per
 * run, standstill time roughly halved, and merge speed rose from 32 to 56 km/h. Every metric moved the same way,
 * though none reached significance at seven seeds.
 * </p>
 * <p>
 * But it went too far: with damping off, four of those seven runs produced <b>no breakdown at all</b> on a date where
 * the site does break down, at an empirical pre-breakdown flow of 3300 veh/h and a jam of some 70 minutes. Smoothing
 * the recovery raises the discharge enough to prevent the breakdown rather than to improve it.
 * </p>
 * <p>
 * <b>The headway</b> is the counterweight. It sets capacity directly, so lengthening it brings the breakdown back.
 * The question this study asks is whether there is a combination at which the breakdown still occurs and runs without
 * the stop-and-go cascade - the two conditions the axes trade against each other.
 * </p>
 * <p>
 * The middle headway combination is the one the local comparisons ran on; the tighter one is what the campaigns use.
 * That difference is worth stating, because it means those comparisons already sat on longer headways than the
 * calibrated configuration, and the breakdowns that vanished there would vanish more readily at 0.90 / 1.20.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See OpenTrafficSim License.
 * </p>
 * @author Marvin Baumann
 */
public class FreiburgSmoothnessStudy implements StudyDefinition
{
    /** Lane-change safety distance reduction factor, fixed at the settled value. */
    public static final double SAFETY_DISTANCE_FACTOR = FreiburgCarStudy.SAFETY_DISTANCE_FACTOR;

    /** The desired headway combinations, as (car, truck) pairs in seconds. */
    public static final List<HeadwayCombination> HEADWAY_COMBINATIONS =
            List.of(new HeadwayCombination("tighter", 0.90, 1.20),
                    new HeadwayCombination("standard", 1.00, 1.30),
                    new HeadwayCombination("looser", 1.10, 1.40));

    /**
     * Relaxation acceleration damping factors. The value is the lower bound of the scaling applied to positive
     * accelerations, so 1.00 disables the damping entirely and smaller values damp harder.
     */
    public static final List<Double> ACC_DAMPING_FACTORS = List.of(0.70, 0.85, 0.95, 1.00);

    /** Parameter key recording the damping factor in {@code runParams.txt}. */
    public static final String KEY_DAMPING = "studyDamping";

    @Override
    public String getName()
    {
        return "smoothness";
    }

    @Override
    public String getDescription()
    {
        return "Headway combinations " + HEADWAY_COMBINATIONS.size() + " x relaxation damping "
                + ACC_DAMPING_FACTORS + " at safety distance " + SAFETY_DISTANCE_FACTOR + ": "
                + (HEADWAY_COMBINATIONS.size() * ACC_DAMPING_FACTORS.size()) + " variations per date.";
    }

    /**
     * Returns the label identifying one cell, used as the suffix of the scenario name.
     * <p>
     * Formatted with {@link Locale#ROOT} so the decimal separator is a dot on every machine: the label ends up in
     * directory names that post-processing matches on.
     * </p>
     * @param combination HeadwayCombination; the headway pair
     * @param damping double; the relaxation acceleration damping factor
     * @return String; the variant label
     */
    public static String variantLabel(final HeadwayCombination combination, final double damping)
    {
        return String.format(Locale.ROOT, "%s_adamp%.2f", combination.label(), damping);
    }

    /** {@inheritDoc} */
    @Override
    public void register(final ScenarioManager manager, final Map<String, String> options) throws Exception
    {
        TrafficFacility facility = FacilityRegistry.resolve(FreiburgFacility.NAME);

        List<String> dates = DateStudy.resolveDates(options.get("dates"));
        if (dates.isEmpty())
        {
            throw new IllegalArgumentException("Study 'smoothness' requires --dates=<comma-separated-dates|file>.");
        }

        String demandOption = options.get("demand");
        if (demandOption == null || demandOption.trim().isEmpty())
        {
            throw new IllegalArgumentException("Study 'smoothness' requires --demand=<csv file or directory>.");
        }
        File demandLocation = new File(demandOption.trim());
        String pattern = options.getOrDefault("pattern", DateStudy.DEFAULT_CSV_PATTERN);
        boolean strict = Boolean.parseBoolean(options.getOrDefault("strict", "false"));
        int replications = Integer.parseInt(
                options.getOrDefault("replications", String.valueOf(DateStudy.DEFAULT_REPLICATIONS)));

        Map<String, File> demandPerDate = DateStudy.resolveDemandCsvs(dates, demandLocation, pattern, strict);

        // Registration order is date-major, then headway, then damping, which the global run index follows.
        for (String date : dates)
        {
            String demandCsvPath = demandPerDate.get(date).getAbsolutePath();
            for (HeadwayCombination combination : HEADWAY_COMBINATIONS)
            {
                for (double damping : ACC_DAMPING_FACTORS)
                {
                    String scenarioName = facility.scenarioName(date, variantLabel(combination, damping));
                    manager.addScenario(scenarioName, facility.getGeneratorClass());
                    manager.addParameterVariation(scenarioName,
                            forCell(facility, date, demandCsvPath, strict, combination, damping));
                }
            }
        }

        manager.setReplications(replications);
    }

    /**
     * Builds the parameter set of one cell.
     * @param facility TrafficFacility; the facility the study runs on
     * @param date String; the simulated date
     * @param demandCsvPath String; absolute path of the demand CSV for that date
     * @param strict boolean; whether a missing demand CSV aborts the run
     * @param combination HeadwayCombination; the headway pair of this cell
     * @param damping double; the relaxation acceleration damping factor of this cell
     * @return ScenarioParameters; the parameters of this cell
     */
    public static ScenarioParameters forCell(final TrafficFacility facility, final String date,
            final String demandCsvPath, final boolean strict, final HeadwayCombination combination,
            final double damping)
    {
        ScenarioParameters params = FreiburgCombinationStudy.forCombination(facility, date, demandCsvPath, strict,
                combination, damping, SAFETY_DISTANCE_FACTOR);
        params.set(KEY_DAMPING, damping);
        return params;
    }
}
