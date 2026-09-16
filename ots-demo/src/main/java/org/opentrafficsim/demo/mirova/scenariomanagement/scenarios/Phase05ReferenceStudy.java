package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.util.ArrayList;
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
 * The Phase 0.5 reference run, one variant per behaviour switch, and the set the decoupled core embodies.
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
 * <li><b>One variant per switch.</b> Each of the next six turns on exactly one behaviour change and nothing else,
 * so its effect can be read on its own rather than inferred from a combination. They are deliberately not crossed:
 * the question at this stage is what each correction does, not how they interact.</li>
 * <li><b>The core set.</b> Two further variants, {@code coreset} and {@code coreset-interp}, turn on the
 * combination the decoupled core reproduces by construction, without and with BC-9. One of them is the second
 * baseline: the migration is compared against it, not against {@code reference}.</li>
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
 * <li><b>bc9_interp</b> -- the discretionary weight is interpolated between DMAND and DSEARCH instead of
 * stepping from 1 to 0 at DMAND. The default call site passes DFREE as the upper threshold, which lies below the
 * lower one, so the interpolation branch is unreachable. Affects every vehicle whose mandatory and discretionary
 * desires conflict while the mandatory one exceeds 0.577 -- on the ramp, the common case. Expect a real effect.</li>
 * <li><b>bc10_induceddecel</b> -- the two induced-deceleration quantities cache under keys of their own instead of
 * sharing one per GTU. Measured on one production cell, every cache hit on either path was serving the other
 * overload's number, and the merge router's follower branch decided the opposite way in 10 % of its evaluations.
 * The lane-change gate did not flip there at all, so expect the effect on the ramp rather than on the mainline.</li>
 * <li><b>bc11_headwaykey</b> -- the car-following cache key carries the headway factor the call asked for, so a
 * yielding call and a plain one for the same leader no longer answer each other. Few hits are contaminated -- 23 on
 * the congested day, 109 on the free-flow one -- but a wrong acceleration propagates, and the commanded plan differs
 * on 26 % and 42 % of ticks respectively. <b>Not in the core set</b>: the core reproduces the leader-only key
 * deliberately (ADR-014), so adopting this would make the core set something the core does not embody.</li>
 * <li><b>bc12_decelthreshold</b> -- the two deceleration-threshold cache keys cover NONE instead of folding it
 * onto the RIGHT key, so a call for an undecided direction no longer answers, or poisons, a genuine RIGHT query in
 * the same tick. Measured inert on both production cells: of 18 407 and 18 849 genuine RIGHT reads, 3 and 15 were
 * served a NONE-written entry, and every one of them equalled what the reader's own direction would have computed,
 * because the condition producing NONE also drives the right desire below {@code dMand} where the interpolation
 * clamps to the same minimum. It is registered because that agreement is circumstance and not construction, and the
 * campaign moves the desires. <b>In the core set</b>: the core has no such memo and no NONE at all.</li>
 * <li><b>bc13_desirecap</b> -- the lane-change desire is capped at 1 above and left open below, which is what
 * OTS's own LMRS {@code Desire} does at construction. MiRoVA reproduced the unbounded
 * {@code a_gain * (v_adj - v_cur) / v_gain} of {@code IncentiveSpeedWithCourtesy} without OTS's cap, so the combined
 * desire exceeds 1 in 17.4 % and 14.6 % of evaluations and reaches 7.9 and 9.5, although every deceleration
 * threshold interpolates on a fraction of it clamped to {@code [0, 1]}. This is the largest single switch in the
 * campaign: it moves the desire itself, on which the thresholds, the pattern gates and the LMRS weighting all
 * depend.</li>
 * <li><b>coreset</b> -- the first of two variants that are not a single switch. It turns on BC-1, BC-2, BC-4, BC-6,
 * BC-8, BC-10 and BC-12 together, because that combination is what the decoupled core reproduces by construction: BC-2 and
 * BC-4 remove fields no driver can observe, BC-6 bounds the merge scan to what one can see, BC-1 puts every time
 * constant on elapsed time, BC-8 fixes the context update order, and BC-10 follows from the core having no
 * induced-deceleration memo at all -- it computes each quantity where it is needed, and BC-12 from the same absence
 * on the deceleration thresholds, which the core takes a desire for rather than a direction. BC-5 is left out because it is still undecided. This is
 * the run the migration is measured against -- the {@code reference} variant is what the publications rest on, and
 * the two are not the same model. See {@code docs/decoupling/contract.md} section 0.</li>
 * <li><b>coreset-interp</b> -- the core set plus BC-9. Which of the two is the core reference depends on whether
 * BC-9 is adopted, and that is decided on this campaign; both are run so that the decision does not need a second
 * one.</li>
 * </ul>
 * <h3>Running it</h3>
 * <pre>
 *   --study=phase05 --dates=cluster/dates.txt --demand=cluster/demand --replications=30
 * </pre>
 * <p>
 * With {@code --variants=reference} only the baseline is registered, and
 * {@code --variants=reference,coreset,coreset-interp} runs the three baselines the migration needs; the default
 * registers all fourteen. Enabling the defect counters is orthogonal and done through the JVM:
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

    /** Label of the variant the decoupled core reproduces: every switch the contract embodies, together. */
    public static final String CORE_SET_LABEL = "coreset";

    /** Label of the core set with BC-9 added, the second candidate core reference. */
    public static final String CORE_SET_INTERP_LABEL = "coreset-interp";

    /**
     * Label of the variant that carries the parameters of {@code final_v1}, the reference standard.
     * <p>
     * Every switch at its default, and the cell of {@link FreiburgFinalStudy#cellFor} with the speed gain {@code final_v1}
     * ran -- 15 m/s for cars, 30 m/s for trucks -- rather than this study's production cell. Parameters, not model: see
     * {@link FreiburgFinalStudy#LEGACY_LABEL} and the tag {@code campaign-final-v1}.
     * </p>
     * <p>
     * <b>Not in the default variant set</b>, so the campaign's run count is unchanged; select it with
     * {@code --variants=...,legacy}.
     * </p>
     */
    public static final String LEGACY_LABEL = "legacy";

    /**
     * The behaviour switches, by variant label, in registration order.
     * <p>
     * An empty list means the baseline: no switch is set, so every parameter keeps its declared default. Every other
     * entry but {@link #CORE_SET_LABEL} holds exactly one switch, so that its effect can be read on its own.
     * </p>
     */
    private static final Map<String, List<String>> VARIANTS = new LinkedHashMap<>();

    static
    {
        VARIANTS.put(REFERENCE_LABEL, List.of());
        VARIANTS.put("bc1_ema", List.of(MirovaParameters.EMA_ALPHA_FROM_ACTUAL_DT.getId()));
        VARIANTS.put("bc2_leadermodel", List.of(MirovaParameters.LEADER_HEADWAY_FROM_OWN_MODEL.getId()));
        VARIANTS.put("bc4_followerdesired", List.of(MirovaParameters.FOLLOWER_DESIRED_SPEED_ESTIMATED.getId()));
        VARIANTS.put("bc5_meanspeed", List.of(MirovaParameters.MEAN_SPEED_FROM_PERCEIVED_LEADERS.getId()));
        VARIANTS.put("bc6_mergerange", List.of(MirovaParameters.MERGE_REFERENCE_RANGE_LIMITED.getId()));
        VARIANTS.put("bc8_ctxorder", List.of(MirovaParameters.CONTEXT_UPDATE_ORDER_FIXED.getId()));
        VARIANTS.put("bc9_interp", List.of(MirovaParameters.DESIRE_INTERPOLATION_FIXED.getId()));
        VARIANTS.put("bc10_induceddecel", List.of(MirovaParameters.INDUCED_DECEL_KEY_DISTINCT.getId()));
        VARIANTS.put("bc11_headwaykey", List.of(MirovaParameters.HEADWAY_FACTOR_KEY_DISTINCT.getId()));
        VARIANTS.put("bc12_decelthreshold", List.of(MirovaParameters.DECEL_THRESHOLD_KEY_DISTINCT.getId()));
        VARIANTS.put("bc13_desirecap", List.of(MirovaParameters.DESIRE_CAPPED.getId()));
        VARIANTS.put(CORE_SET_LABEL,
                List.of(MirovaParameters.EMA_ALPHA_FROM_ACTUAL_DT.getId(),
                        MirovaParameters.LEADER_HEADWAY_FROM_OWN_MODEL.getId(),
                        MirovaParameters.FOLLOWER_DESIRED_SPEED_ESTIMATED.getId(),
                        MirovaParameters.MERGE_REFERENCE_RANGE_LIMITED.getId(),
                        MirovaParameters.CONTEXT_UPDATE_ORDER_FIXED.getId(),
                        MirovaParameters.INDUCED_DECEL_KEY_DISTINCT.getId(),
                        MirovaParameters.DECEL_THRESHOLD_KEY_DISTINCT.getId()));
        VARIANTS.put(LEGACY_LABEL, List.of());
        VARIANTS.put(CORE_SET_INTERP_LABEL,
                List.of(MirovaParameters.EMA_ALPHA_FROM_ACTUAL_DT.getId(),
                        MirovaParameters.LEADER_HEADWAY_FROM_OWN_MODEL.getId(),
                        MirovaParameters.FOLLOWER_DESIRED_SPEED_ESTIMATED.getId(),
                        MirovaParameters.MERGE_REFERENCE_RANGE_LIMITED.getId(),
                        MirovaParameters.CONTEXT_UPDATE_ORDER_FIXED.getId(),
                        MirovaParameters.INDUCED_DECEL_KEY_DISTINCT.getId(),
                        MirovaParameters.DECEL_THRESHOLD_KEY_DISTINCT.getId(),
                        MirovaParameters.DESIRE_INTERPOLATION_FIXED.getId()));
    }

    /** Parameter key recording which switches a cell had on, for {@code runParams.txt}. */
    public static final String KEY_SWITCH = "phase05.switch";

    @Override
    public String getName()
    {
        return "phase05";
    }

    @Override
    public String getDescription()
    {
        return "Phase 0.5 golden reference, one variant per behaviour switch, and the core set: " + VARIANTS.size()
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
        // omitting it registers every variant but 'legacy', which has to be asked for by name.
        String variantOption = options.get("variants");
        List<String> wanted;
        if (variantOption == null || variantOption.trim().isEmpty())
        {
            wanted = new ArrayList<>(VARIANTS.keySet());
            wanted.remove(LEGACY_LABEL);
        }
        else
        {
            wanted = List.of(variantOption.trim().split("\\s*,\\s*"));
        }
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
                List<String> switchIds = VARIANTS.get(label);
                String scenarioName = facility.scenarioName(date, label);
                manager.addScenario(scenarioName, facility.getGeneratorClass());

                ScenarioParameters params = LEGACY_LABEL.equals(label)
                        ? FreiburgFinalStudy.cellFor(facility, date, demandCsvPath, strict)
                        : FreiburgCongestedBranchStudy.forCell(facility, date, demandCsvPath, strict,
                                FreiburgProductionStudy.B, FreiburgProductionStudy.S0_CAR, FreiburgProductionStudy.A_CAR);
                if (LEGACY_LABEL.equals(label))
                {
                    FreiburgProductionStudy.applyPublishedSpeedGain(params);
                }
                for (String switchId : switchIds)
                {
                    // Both vehicle types, so a switch is one change and not two.
                    params.set("car." + switchId, Boolean.TRUE);
                    params.set("truck." + switchId, Boolean.TRUE);
                }
                params.set(KEY_SWITCH, switchIds.isEmpty() ? (LEGACY_LABEL.equals(label) ? "published-vgain" : "none")
                        : String.join("+", switchIds));
                manager.addParameterVariation(scenarioName, params);
            }
        }
        manager.setReplications(replications);
    }
}
