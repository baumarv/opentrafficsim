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
 * The parameter set as it stands after the headway-against-damping grid, over all nine study dates.
 * <p>
 * That grid changed two values at once, and had to: the relaxation damping governs how smoothly traffic re-sorts
 * itself after a merge, and removing it is worth a great deal on exactly the failure this model has - at 1.10 / 1.40
 * it takes vehicles going through a stop-and-go cycle from 30.8 % to 11.0 % and ramp standstills from 1237 to 340 per
 * run, monotonically and in every headway row tested. But removing it also raises the discharge enough to prevent
 * breakdowns, and at the previous headway of 0.90 / 1.20 it removed them entirely on a date where the site does break
 * down. The headway had to lengthen to give the capacity back.
 * </p>
 * <p>
 * The combination carried forward, 1.10 / 1.40 with damping off, was the only cell of twelve that achieved both at
 * once: a breakdown in five runs of ten, at the smoothest stop-and-go share the grid produced.
 * </p>
 * <p>
 * Both axes are extended rather than fixed, because the grid saw one date and the two values it settled on sit at the
 * edge of what it tested. What the nine dates have to decide is whether that edge holds where the grid could not look.
 * </p>
 * <p>
 * Six of the nine dates have never been calibrated on, and one of them, 2025-09-22, has no persistent breakdown at
 * all. With the damping off that date becomes the sharper test rather than a formality: a configuration that has been
 * made harder to break should be checked first on the day the site did not break.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See OpenTrafficSim License.
 * </p>
 * @author Marvin Baumann
 */
public class FreiburgSettledStudy implements StudyDefinition
{
    /** Lane-change safety distance reduction factor, fixed at the settled value. */
    public static final double SAFETY_DISTANCE_FACTOR = FreiburgCarStudy.SAFETY_DISTANCE_FACTOR;

    /**
     * The headway pair the grid settled on, and one step longer again.
     * <p>
     * The grid stopped at 1.10 / 1.40 because that was its longest level, not because the gradient had flattened: at
     * damping 1.00 it gave a breakdown in five runs of ten where 0.90 / 1.20 gave none, and it cost almost nothing in
     * smoothness, 11.0 % against 10.2 %. Whether one step further buys a breakdown rate closer to the site's own -
     * which on this date is one out of one - at a similarly small cost is the open question the grid left, and this
     * campaign is long enough to answer it.
     * </p>
     */
    public static final List<HeadwayCombination> HEADWAY_COMBINATIONS =
            List.of(new HeadwayCombination("settled", FreiburgStudyParameters.CAR_T, FreiburgStudyParameters.TRUCK_T),
                    new HeadwayCombination("longer", 1.20, 1.50));

    /**
     * Damping off, and two steps below it.
     * <p>
     * Switching a mechanism entirely off is a stronger statement than moving a parameter, and one date is thin ground
     * for it. If the nine dates show 1.00 and 0.95 behaving alike, the simpler setting stands; if 1.00 over-stabilises
     * on the higher-demand days the grid could not see, the fallbacks are already in this campaign rather than a
     * campaign away.
     * </p>
     */
    public static final List<Double> ACC_DAMPING_FACTORS = List.of(1.00, 0.95, 0.90);

    /** Parameter key recording the damping factor in {@code runParams.txt}. */
    public static final String KEY_DAMPING = FreiburgSmoothnessStudy.KEY_DAMPING;

    @Override
    public String getName()
    {
        return "settled";
    }

    @Override
    public String getDescription()
    {
        return "Headways " + HEADWAY_COMBINATIONS.size() + " x damping " + ACC_DAMPING_FACTORS
                + " at safety distance " + SAFETY_DISTANCE_FACTOR + " over every study date: "
                + (HEADWAY_COMBINATIONS.size() * ACC_DAMPING_FACTORS.size()) + " variations per date.";
    }

    /**
     * Returns the label identifying one cell, used as the suffix of the scenario name.
     * @param combination HeadwayCombination; the headway pair of this cell
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
            throw new IllegalArgumentException("Study 'settled' requires --dates=<comma-separated-dates|file>.");
        }
        String demandOption = options.get("demand");
        if (demandOption == null || demandOption.trim().isEmpty())
        {
            throw new IllegalArgumentException("Study 'settled' requires --demand=<csv file or directory>.");
        }
        File demandLocation = new File(demandOption.trim());
        String pattern = options.getOrDefault("pattern", DateStudy.DEFAULT_CSV_PATTERN);
        boolean strict = Boolean.parseBoolean(options.getOrDefault("strict", "false"));
        int replications = Integer.parseInt(
                options.getOrDefault("replications", String.valueOf(DateStudy.DEFAULT_REPLICATIONS)));

        Map<String, File> demandPerDate = DateStudy.resolveDemandCsvs(dates, demandLocation, pattern, strict);

        for (String date : dates)
        {
            String demandCsvPath = demandPerDate.get(date).getAbsolutePath();
            for (HeadwayCombination combination : HEADWAY_COMBINATIONS)
            {
                for (double damping : ACC_DAMPING_FACTORS)
                {
                    String scenarioName = facility.scenarioName(date, variantLabel(combination, damping));
                    manager.addScenario(scenarioName, facility.getGeneratorClass());
                    ScenarioParameters params = FreiburgCombinationStudy.forCombination(facility, date, demandCsvPath,
                            strict, combination, damping, SAFETY_DISTANCE_FACTOR);
                    params.set(KEY_DAMPING, damping);
                    manager.addParameterVariation(scenarioName, params);
                }
            }
        }
        manager.setReplications(replications);
    }
}
