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
 * Extends the relaxation acceleration damping axis of {@link FreiburgCombinationStudy} beyond its upper end, on the one
 * grid cell that the combination campaign identified as the best: headway combination {@code tighter} (T = 0.90 / 1.20)
 * with safety distance reduction factor 0.50.
 * <p>
 * The combination campaign evaluated damping 0.60 and 0.80 and found the calibration improving monotonically towards
 * 0.80 on every metric - capacity deficit, critical speed deficit and aligned speed RMSE - with no sign of flattening.
 * Since {@code aRelaxDamping} is the <b>minimum</b> factor applied to positive acceleration during active headway
 * relaxation, a larger value means <b>weaker</b> damping, so the trend says the damping currently applied is too strong.
 * This study establishes where that trend ends by adding 0.90 and 1.00 to the axis.
 * </p>
 * <p>
 * 1.00 is the limit case and needs no separate "damping off" cell: the factor is computed as
 * {@code f = 1 - (1 - aRelaxDamping) * ratio}, which for {@code aRelaxDamping = 1.00} is 1.00 for every ratio, i.e.
 * exactly what {@code aRelaxDampingEnabled = false} produces. Spending a third of the campaign on the boolean would
 * re-measure the same cell.
 * </p>
 * <p>
 * The point is deliberately a bound rather than a search: it answers how much of the roughly 312 veh/h capacity deficit
 * the damping can account for <b>at all</b>. If the limit case recovers only a fraction of it, the remainder sits
 * elsewhere - in the congested branch or in the merge disruption - and no further search along this axis is worth
 * running.
 * </p>
 * <p>
 * Cells reuse {@link FreiburgCombinationStudy#forCombination} and {@link FreiburgCombinationStudy#variantLabel}, so the
 * scenario folder names carry the identical {@code combination_adamp_sdr} shape and the existing Python evaluation
 * pipeline reads this campaign without a single change. The two new cells sit next to the four already simulated ones
 * on the same nine dates, which makes the damping axis four points long: 0.60, 0.80, 0.90, 1.00.
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
public class FreiburgDampingStudy implements StudyDefinition
{
    /** The label of the headway combination this study fixes, resolved from the combination study's own list. */
    public static final String HEADWAY_LABEL = "tighter";

    /**
     * The lane-change safety distance reduction factor this study fixes. 0.50 outperformed 0.60 on every cell of the
     * combination campaign.
     */
    public static final double SAFETY_DISTANCE_FACTOR = 0.50;

    /**
     * The relaxation acceleration damping factors added to the axis. 0.60 and 0.80 are already simulated by
     * {@link FreiburgCombinationStudy}; 1.00 is the limit case of no damping at all.
     */
    public static final List<Double> ACC_DAMPING_FACTORS = List.of(0.90, 1.00);

    @Override
    public String getName()
    {
        return "damping";
    }

    @Override
    public String getDescription()
    {
        return "Relaxation acceleration damping " + ACC_DAMPING_FACTORS + " on the best combination cell ("
                + HEADWAY_LABEL + ", sdr " + SAFETY_DISTANCE_FACTOR + ") x dates: " + ACC_DAMPING_FACTORS.size()
                + " variations per date.";
    }

    @Override
    public void register(final ScenarioManager manager, final Map<String, String> options) throws Exception
    {
        TrafficFacility facility = FacilityRegistry.resolve(FreiburgFacility.NAME);
        HeadwayCombination combination = resolveHeadwayCombination();

        List<String> dates = DateStudy.resolveDates(options.get("dates"));
        if (dates.isEmpty())
        {
            throw new IllegalArgumentException("Study 'damping' requires --dates=<comma-separated-dates|file>.");
        }

        String demandOption = options.get("demand");
        if (demandOption == null || demandOption.trim().isEmpty())
        {
            throw new IllegalArgumentException("Study 'damping' requires --demand=<csv file or directory>.");
        }
        File demandLocation = new File(demandOption.trim());
        String pattern = options.getOrDefault("pattern", DateStudy.DEFAULT_CSV_PATTERN);
        boolean strict = Boolean.parseBoolean(options.getOrDefault("strict", "false"));
        int replications = Integer.parseInt(
                options.getOrDefault("replications", String.valueOf(DateStudy.DEFAULT_REPLICATIONS)));

        // Same up-front check as the date study: a missing CSV aborts before any simulation starts.
        Map<String, File> demandPerDate = DateStudy.resolveDemandCsvs(dates, demandLocation, pattern, strict);

        // Registration order is date-major, then damping factor, which the global run index follows.
        for (String date : dates)
        {
            String demandCsvPath = demandPerDate.get(date).getAbsolutePath();
            for (double accDampingFactor : ACC_DAMPING_FACTORS)
            {
                String scenarioName = facility.scenarioName(date,
                        FreiburgCombinationStudy.variantLabel(combination, accDampingFactor, SAFETY_DISTANCE_FACTOR));
                manager.addScenario(scenarioName, facility.getGeneratorClass());
                manager.addParameterVariation(scenarioName, FreiburgCombinationStudy.forCombination(facility, date,
                        demandCsvPath, strict, combination, accDampingFactor, SAFETY_DISTANCE_FACTOR));
            }
        }

        manager.setReplications(replications);
    }

    /**
     * Returns the headway combination this study fixes, looked up by label in the combination study's own list rather
     * than redeclared here, so the two campaigns cannot drift apart in their headway values.
     * @return HeadwayCombination; the combination labelled {@code tighter}
     * @throws IllegalStateException when the combination study no longer defines that label
     */
    public static HeadwayCombination resolveHeadwayCombination()
    {
        return FreiburgCombinationStudy.COMBINATIONS.stream().filter(c -> HEADWAY_LABEL.equals(c.label())).findFirst()
                .orElseThrow(() -> new IllegalStateException("FreiburgCombinationStudy no longer defines the headway "
                        + "combination 'tighter'; the damping study fixes that cell and cannot run without it."));
    }
}
