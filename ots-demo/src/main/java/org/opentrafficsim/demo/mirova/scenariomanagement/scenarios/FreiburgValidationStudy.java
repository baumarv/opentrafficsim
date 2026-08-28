package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.util.List;
import java.util.Map;

import org.opentrafficsim.demo.mirova.scenariomanagement.FacilityRegistry;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.StudyDefinition;
import org.opentrafficsim.demo.mirova.scenariomanagement.TrafficFacility;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.FreiburgCombinationStudy.HeadwayCombination;

/**
 * The settled parameter set over all nine study dates, with the desired headways as the only open axis.
 * <p>
 * Everything the preceding campaigns established is held fixed here: relaxation damping at 0.80 and the lane-change
 * safety distance at 0.40 from the fourth merge grid, truck acceleration 1.25, truck stopping distance 4.0 m and the
 * follower deceleration thresholds at -2.0 / -4.0 from the behaviour factorial, and car acceleration 1.40 with a
 * stopping distance of 2.0 m from the car sweep. That set already puts queue discharge inside the field interval on
 * four of six cells and the breakdown onset inside on all of them.
 * </p>
 * <p>
 * Two questions remain, and this study is aimed at both.
 * </p>
 * <p>
 * <b>Does it generalise?</b> Six of these nine dates have never been calibrated on. Three of them have no empirical
 * breakdown at all, which makes them the sharper test: a model tuned to break down at the right flow must also manage
 * <i>not</i> to break down on a day the site did not. A calibration measured only on days that do break down cannot
 * see that failure mode.
 * </p>
 * <p>
 * <b>Does the headway close the remaining gap?</b> The pre-breakdown flow is still 4 to 11 % short at 3008 to 3164
 * against a field 3251 to 3543 veh/h - down from 10 to 19 %, but not closed. The desired headway is the parameter with
 * the strongest reported influence on bottleneck discharge, considerably stronger than the acceleration, so it is the
 * natural candidate for what is left. The third combination extends the axis in the direction the gap points; the
 * first two are the pairs the earlier campaigns already used, which keeps this comparable with them.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See OpenTrafficSim License.
 * </p>
 * @author Marvin Baumann
 */
public class FreiburgValidationStudy implements StudyDefinition
{
    /** Relaxation acceleration damping factor, fixed at the best cell of the fourth merge grid campaign. */
    public static final double ACC_DAMPING_FACTOR = FreiburgCarStudy.ACC_DAMPING_FACTOR;

    /** Lane-change safety distance reduction factor, fixed at the best cell of the fourth merge grid campaign. */
    public static final double SAFETY_DISTANCE_FACTOR = FreiburgCarStudy.SAFETY_DISTANCE_FACTOR;

    /**
     * The desired headway combinations, as (car, truck) pairs in seconds.
     * <p>
     * Held here rather than taken from {@link FreiburgCombinationStudy#COMBINATIONS} so that extending the axis for
     * this study does not enlarge that one, whose cells are crossed with two further dimensions.
     * </p>
     */
    public static final List<HeadwayCombination> HEADWAY_COMBINATIONS =
            List.of(new HeadwayCombination("standard", 1.00, 1.30),
                    new HeadwayCombination("tighter", 0.90, 1.20),
                    new HeadwayCombination("tightest", 0.80, 1.10));

    @Override
    public String getName()
    {
        return "validation";
    }

    @Override
    public String getDescription()
    {
        return "Settled parameter set x headway combinations " + HEADWAY_COMBINATIONS.size() + " over every study date, "
                + "at damping " + ACC_DAMPING_FACTOR + " and safety distance " + SAFETY_DISTANCE_FACTOR + ".";
    }

    /** {@inheritDoc} */
    @Override
    public void register(final ScenarioManager manager, final Map<String, String> options) throws Exception
    {
        TrafficFacility facility = FacilityRegistry.resolve(FreiburgFacility.NAME);

        List<String> dates = DateStudy.resolveDates(options.get("dates"));
        if (dates.isEmpty())
        {
            throw new IllegalArgumentException("Study 'validation' requires --dates=<comma-separated-dates|file>.");
        }

        String demandOption = options.get("demand");
        if (demandOption == null || demandOption.trim().isEmpty())
        {
            throw new IllegalArgumentException("Study 'validation' requires --demand=<csv file or directory>.");
        }
        File demandLocation = new File(demandOption.trim());
        String pattern = options.getOrDefault("pattern", DateStudy.DEFAULT_CSV_PATTERN);
        boolean strict = Boolean.parseBoolean(options.getOrDefault("strict", "false"));
        int replications = Integer.parseInt(
                options.getOrDefault("replications", String.valueOf(DateStudy.DEFAULT_REPLICATIONS)));

        Map<String, File> demandPerDate = DateStudy.resolveDemandCsvs(dates, demandLocation, pattern, strict);

        // Registration order is date-major, then headway combination, which the global run index follows.
        for (String date : dates)
        {
            String demandCsvPath = demandPerDate.get(date).getAbsolutePath();
            for (HeadwayCombination combination : HEADWAY_COMBINATIONS)
            {
                String scenarioName = facility.scenarioName(date, combination.label());
                manager.addScenario(scenarioName, facility.getGeneratorClass());
                manager.addParameterVariation(scenarioName, forCell(facility, date, demandCsvPath, strict, combination));
            }
        }

        manager.setReplications(replications);
    }

    /**
     * Builds the parameter set of one cell: the settled values with one headway combination applied.
     * @param facility TrafficFacility; the facility the study runs on
     * @param date String; the simulated date
     * @param demandCsvPath String; absolute path of the demand CSV for that date
     * @param strict boolean; whether a missing demand CSV aborts the run
     * @param combination HeadwayCombination; the headway pair of this cell
     * @return ScenarioParameters; the parameters of this cell
     */
    public static ScenarioParameters forCell(final TrafficFacility facility, final String date,
            final String demandCsvPath, final boolean strict, final HeadwayCombination combination)
    {
        // Everything but the headways comes from the study baseline, so a value settled since the last campaign
        // reaches this study without being restated here and drifting away from it.
        return FreiburgCombinationStudy.forCombination(facility, date, demandCsvPath, strict, combination,
                ACC_DAMPING_FACTOR, SAFETY_DISTANCE_FACTOR);
    }
}
