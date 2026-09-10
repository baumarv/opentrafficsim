package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opentrafficsim.demo.mirova.scenariomanagement.FacilityRegistry;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.StudyDefinition;
import org.opentrafficsim.demo.mirova.scenariomanagement.TrafficFacility;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;

/**
 * The Phase 0.5 reference run, and one variant per behaviour switch.
 * <p>
 * Phase 0.5 repaired defects and deleted dead code without changing behaviour, and put every behaviour change behind
 * a switch whose default reproduces the model the published results were produced with. This study produces the two
 * things that follow from that arrangement:
 * </p>
 * <ol>
 * <li><b>The golden reference.</b> The {@code reference} variant runs the production parameter set with every switch
 * at its default. It is the baseline every later migration stage is compared against: once the model starts moving
 * into {@code mirova-core}, a deviation is only interpretable against a run of the code as it stood when the moving
 * began.</li>
 * <li><b>One variant per switch.</b> Each remaining variant turns on exactly one behaviour change and nothing else,
 * so its effect can be read on its own rather than inferred from a combination. They are deliberately not crossed:
 * the question at this stage is what each correction does, not how they interact.</li>
 * </ol>
 * <p>
 * The parameter set is {@link FreiburgProductionStudy}'s, unchanged, so the reference variant is comparable with the
 * ensemble the publication reports rather than being a new baseline of its own.
 * </p>
 * <h3>What each variant changes</h3>
 * <ul>
 * <li><b>bc1_ema</b> -- the merge anticipation filter takes its coefficient from the interval actually elapsed and a
 * 4 s time constant, as {@code 1 - exp(-dt/tau)}, recomputed per call. At the configured 0.2 s step this is very
 * nearly the current filter, so this variant is expected to move little; it is in the campaign to establish that,
 * because the default form is the one that breaks if a host ever chooses a different step.</li>
 * <li><b>bc2_leadermodel</b> -- a cooperating vehicle judges whether its own front leader could open the gap using
 * its own car-following model rather than reading the leader's. Affects the merge only, through GapOpenerPattern,
 * and only where the ego and its leader differ in type or in drawn parameters. Expect a small shift in how often
 * cooperation is deferred to the vehicle ahead, and therefore in ramp standstills.</li>
 * <li><b>bc4_followerdesired</b> -- a follower's desired speed is estimated from the speed it was last seen
 * holding while unobstructed, instead of being read off the perceived vehicle. Affects the social interaction
 * incentive only. Expect the clearest effect where ego and follower differ in desired speed -- a car behind a
 * truck -- and little elsewhere, since for an unobstructed follower the estimate is exact.</li>
 * <li><b>bc5_meanspeed</b> -- the mean speed of a neighbouring lane comes from the leaders this vehicle perceives
 * rather than from OTS AnticipationTrafficPerception. This is the variant with the widest reach: the quantity feeds
 * the merge reference speed cascade, PreventUndercuttingPattern and the keep-right incentive. Expect the largest
 * deviation of the six.</li>
 * <li><b>bc6_mergerange</b> -- the merge reference speed scan is bounded by the ego's own look-ahead instead of
 * reading every vehicle on a lane up to a kilometre downstream. Where the target lane carries traffic within
 * 295 m nothing changes; where it does not, the cascade falls through to the speed-limit fallback and mergers
 * synchronise to a higher speed than they do now. Expect an effect concentrated on the early ramp.</li>
 * <li><b>bc8_ctxorder</b> -- the contexts update in dependency order, so the relaxation housekeeping runs before the
 * detection that opens new relaxations. The only difference is a relaxation opened on a space deficit below 0.1 m,
 * which currently dies in the tick it was born in. Expect almost nothing; the variant exists to confirm that.</li>
 * </ul>
 * <h3>Running it</h3>
 * <pre>
 *   --study=phase05 --dates=cluster/dates.txt --demand=cluster/demand --replications=30
 * </pre>
 * <p>
 * With {@code --variants=reference} only the baseline is registered, which is what the golden reference run should
 * use; the default registers all six. Enabling the defect counters is orthogonal and done through the JVM:
 * {@code -Dmirova.defectDiag=true -Dmirova.defectDiagFile=<out>/defects.csv}.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class Phase05ReferenceStudy implements StudyDefinition
{
    /** Label of the baseline variant: the production set with every switch at its default. */
    public static final String REFERENCE_LABEL = "reference";

    /** Replications per cell, matching the production study so the reference is comparable with it. */
    public static final int DEFAULT_REPLICATIONS = FreiburgProductionStudy.DEFAULT_REPLICATIONS;

    /**
     * The behaviour switches, by variant label, in registration order.
     * <p>
     * A {@code null} value means the baseline: no switch is set, so every parameter keeps its declared default.
     * </p>
     */
    private static final Map<String, String> VARIANTS = new LinkedHashMap<>();

    static
    {
        VARIANTS.put(REFERENCE_LABEL, null);
        VARIANTS.put("bc1_ema", MirovaParameters.EMA_ALPHA_FROM_ACTUAL_DT.getId());
        VARIANTS.put("bc2_leadermodel", MirovaParameters.LEADER_HEADWAY_FROM_OWN_MODEL.getId());
        VARIANTS.put("bc4_followerdesired", MirovaParameters.FOLLOWER_DESIRED_SPEED_ESTIMATED.getId());
        VARIANTS.put("bc5_meanspeed", MirovaParameters.MEAN_SPEED_FROM_PERCEIVED_LEADERS.getId());
        VARIANTS.put("bc6_mergerange", MirovaParameters.MERGE_REFERENCE_RANGE_LIMITED.getId());
        VARIANTS.put("bc8_ctxorder", MirovaParameters.CONTEXT_UPDATE_ORDER_FIXED.getId());
    }

    /** Parameter key recording which switch a cell had on, for {@code runParams.txt}. */
    public static final String KEY_SWITCH = "phase05.switch";

    @Override
    public String getName()
    {
        return "phase05";
    }

    @Override
    public String getDescription()
    {
        return "Phase 0.5 golden reference plus one variant per behaviour switch: " + VARIANTS.size()
                + " variations per date, on the production parameter set.";
    }

    /** {@inheritDoc} */
    @Override
    public void register(final ScenarioManager manager, final Map<String, String> options) throws Exception
    {
        TrafficFacility facility = FacilityRegistry.resolve(FreiburgFacility.NAME);

        List<String> dates = DateStudy.resolveDates(options.get("dates"));
        if (dates.isEmpty())
        {
            throw new IllegalArgumentException("Study 'phase05' requires --dates=<comma-separated-dates|file>.");
        }
        String demandOption = options.get("demand");
        if (demandOption == null || demandOption.trim().isEmpty())
        {
            throw new IllegalArgumentException("Study 'phase05' requires --demand=<csv file or directory>.");
        }
        File demandLocation = new File(demandOption.trim());
        String pattern = options.getOrDefault("pattern", DateStudy.DEFAULT_CSV_PATTERN);
        boolean strict = Boolean.parseBoolean(options.getOrDefault("strict", "false"));
        int replications =
                Integer.parseInt(options.getOrDefault("replications", String.valueOf(DEFAULT_REPLICATIONS)));

        // --variants=reference restricts the campaign to the baseline, which is what the golden reference run wants;
        // omitting it registers all six.
        String variantOption = options.get("variants");
        List<String> wanted = variantOption == null || variantOption.trim().isEmpty() ? List.copyOf(VARIANTS.keySet())
                : List.of(variantOption.trim().split("\\s*,\\s*"));
        for (String label : wanted)
        {
            if (!VARIANTS.containsKey(label))
            {
                throw new IllegalArgumentException(
                        "Study 'phase05' has no variant '" + label + "'; known: " + VARIANTS.keySet());
            }
        }

        Map<String, File> demandPerDate = DateStudy.resolveDemandCsvs(dates, demandLocation, pattern, strict);

        for (String date : dates)
        {
            String demandCsvPath = demandPerDate.get(date).getAbsolutePath();
            for (String label : wanted)
            {
                String switchId = VARIANTS.get(label);
                String scenarioName = facility.scenarioName(date, label);
                manager.addScenario(scenarioName, facility.getGeneratorClass());

                ScenarioParameters params = FreiburgCongestedBranchStudy.forCell(facility, date, demandCsvPath, strict,
                        FreiburgProductionStudy.B, FreiburgProductionStudy.S0_CAR, FreiburgProductionStudy.A_CAR);
                if (switchId != null)
                {
                    // Both vehicle types, so a variant is one change and not two.
                    params.set("car." + switchId, Boolean.TRUE);
                    params.set("truck." + switchId, Boolean.TRUE);
                }
                params.set(KEY_SWITCH, switchId == null ? "none" : switchId);
                manager.addParameterVariation(scenarioName, params);
            }
        }
        manager.setReplications(replications);
    }
}
