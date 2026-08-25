package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.util.List;
import java.util.Map;

import org.opentrafficsim.demo.mirova.scenariomanagement.FacilityRegistry;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.StudyDefinition;
import org.opentrafficsim.demo.mirova.scenariomanagement.TrafficFacility;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.FreiburgCombinationStudy.HeadwayCombination;

/**
 * Searches the relaxation acceleration damping and lane-change safety distance axes on a fine grid, over few days
 * rather than all of them: 3 damping x 3 safety distance x D dates x R replications.
 * <p>
 * The two earlier campaigns established where the remaining calibration error sits. {@link FreiburgCombinationStudy}
 * swept damping 0.60/0.80 and safety distance 0.50/0.60 on nine dates, and {@link FreiburgDampingStudy} extended the
 * damping axis to 0.90 and 1.00. Measured on the bottleneck flow - mainline plus ramp, since the merge serves both -
 * the outcome was:
 * </p>
 * <ul>
 * <li>Capacity saturates on the damping axis above 0.80: 2811, 2766, 2787 veh/h at 0.80, 0.90, 1.00 against 3456 veh/h
 * empirically. Damping accounts for roughly a fifth of the deficit and nothing beyond that.</li>
 * <li>The capacity drop moves the other way and is reproduced at 0.60 (10.1 % against 9.8 % empirically), turning
 * negative from 0.90 upwards - the bottleneck would discharge more after breaking down than before.</li>
 * <li>Safety distance is the steeper lever and moves both quantities in the same direction: 0.60 to 0.50 gained 151
 * veh/h at damping 0.60 and 216 veh/h at 0.80, and raised the capacity drop in both cases.</li>
 * </ul>
 * <p>
 * Hence this grid: damping between the value that reproduces the capacity drop and the value that maximises capacity,
 * crossed with safety distances below the best one measured so far. Values under 0.40 are deliberately not included -
 * accepting gaps below 40 % of the safe distance buys capacity with implausible behaviour rather than with better
 * modelling, and that trade should be a decision, not a side effect of a sweep.
 * </p>
 * <p>
 * Two cells of the grid - damping 0.60 and 0.80 at safety distance 0.50 - are already simulated by the combination
 * campaign on all nine dates. They are deliberately re-run here as a consistency check: if they reproduce on the three
 * dates of this study, the rest of the grid can be read against the earlier results.
 * </p>
 * <p>
 * The dates are supplied through {@code --dates} as usual, but the intended set is the three days of
 * {@code cluster/dates_calibration.txt}, chosen for an identified breakdown and a spread of demand rather than for
 * fitting well already. Calibrating on the days a model happens to reproduce makes the metric look better without
 * making the model better; the remaining six dates stay untouched as validation.
 * </p>
 * <p>
 * Options honoured by {@link #register(ScenarioManager, Map)} are identical to {@link FreiburgCombinationStudy}'s:
 * {@code dates}, {@code demand}, {@code pattern}, {@code replications} and {@code strict}.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author Marvin Baumann
 */
public class FreiburgMergeGridStudy implements StudyDefinition
{
    /** The label of the headway combination this study fixes, resolved from the combination study's own list. */
    public static final String HEADWAY_LABEL = "tighter";

    /**
     * The relaxation acceleration damping factors of the grid. 0.60 reproduces the empirical capacity drop, 0.80 is
     * where capacity saturates, and 0.70 resolves the trade-off between the two.
     */
    public static final List<Double> ACC_DAMPING_FACTORS = List.of(0.60, 0.70, 0.80);

    /**
     * The lane-change safety distance reduction factors of the grid. 0.50 is the best value measured so far and is
     * included as the upper anchor; 0.45 and 0.40 extend the axis in the direction its gradient points.
     */
    public static final List<Double> SAFETY_DISTANCE_FACTORS = List.of(0.40, 0.45, 0.50);

    @Override
    public String getName()
    {
        return "mergegrid";
    }

    @Override
    public String getDescription()
    {
        return "Damping " + ACC_DAMPING_FACTORS + " x safety distance " + SAFETY_DISTANCE_FACTORS + " on the "
                + HEADWAY_LABEL + " headway combination x dates: "
                + (ACC_DAMPING_FACTORS.size() * SAFETY_DISTANCE_FACTORS.size()) + " variations per date.";
    }

    @Override
    public void register(final ScenarioManager manager, final Map<String, String> options) throws Exception
    {
        TrafficFacility facility = FacilityRegistry.resolve(FreiburgFacility.NAME);
        HeadwayCombination combination = FreiburgDampingStudy.resolveHeadwayCombination();

        List<String> dates = DateStudy.resolveDates(options.get("dates"));
        if (dates.isEmpty())
        {
            throw new IllegalArgumentException("Study 'mergegrid' requires --dates=<comma-separated-dates|file>.");
        }

        String demandOption = options.get("demand");
        if (demandOption == null || demandOption.trim().isEmpty())
        {
            throw new IllegalArgumentException("Study 'mergegrid' requires --demand=<csv file or directory>.");
        }
        File demandLocation = new File(demandOption.trim());
        String pattern = options.getOrDefault("pattern", DateStudy.DEFAULT_CSV_PATTERN);
        boolean strict = Boolean.parseBoolean(options.getOrDefault("strict", "false"));
        int replications = Integer.parseInt(
                options.getOrDefault("replications", String.valueOf(DateStudy.DEFAULT_REPLICATIONS)));

        // Same up-front check as the date study: a missing CSV aborts before any simulation starts.
        Map<String, File> demandPerDate = DateStudy.resolveDemandCsvs(dates, demandLocation, pattern, strict);

        // Registration order is date-major, then damping factor, then safety distance factor, which the global run
        // index follows.
        for (String date : dates)
        {
            String demandCsvPath = demandPerDate.get(date).getAbsolutePath();
            for (double accDampingFactor : ACC_DAMPING_FACTORS)
            {
                for (double safetyDistanceFactor : SAFETY_DISTANCE_FACTORS)
                {
                    String scenarioName = facility.scenarioName(date,
                            FreiburgCombinationStudy.variantLabel(combination, accDampingFactor,
                                    safetyDistanceFactor));
                    manager.addScenario(scenarioName, facility.getGeneratorClass());
                    manager.addParameterVariation(scenarioName, FreiburgCombinationStudy.forCombination(facility,
                            date, demandCsvPath, strict, combination, accDampingFactor, safetyDistanceFactor));
                }
            }
        }

        manager.setReplications(replications);
    }
}
