package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.util.List;
import java.util.Map;

import org.opentrafficsim.demo.mirova.scenariomanagement.ParameterGridBuilder;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioGenerator;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.StudyDefinition;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;

/**
 * The one-at-a-time (OAT) parameter study of the FreiburgNord scenario: a baseline variation plus, for every swept
 * dimension, one variation per value, each run with several replications.
 * <p>
 * This is a reusable {@link StudyDefinition}, so that the same study can be executed either locally through
 * {@link ScenarioManager#runAll(int, boolean, boolean)} or on the cluster as one array task per run. It supersedes the
 * former standalone runner it was extracted from, whose hardcoded baseline had drifted from the multi-day evaluation
 * study's.
 * </p>
 * <p>
 * The baseline every dimension is swept against comes from {@link FreiburgStudyParameters#baseBehaviorParams()}, the same
 * definition the multi-day evaluation study runs, so a sweep always measures a deviation from that study's configuration.
 * Only the demand wiring — period, aggregation, smoothing and the CSV — is defined here.
 * </p>
 * <p>
 * Options honoured by {@link #register(ScenarioManager, Map)}:
 * </p>
 * <ul>
 * <li>{@code demand} — the demand CSV file, or a directory holding per-date CSV files. When given, the Python demand
 * preparation is disabled and the CSV is used as-is; when absent, the historical behaviour of preparing demand from the
 * configured period is kept.</li>
 * <li>{@code pattern} — the per-date file name pattern used when {@code demand} is a directory. Defaults to
 * {@value FreiburgDateStudy#DEFAULT_CSV_PATTERN}.</li>
 * <li>{@code start}, {@code end} — the simulated period. Default to {@value #DEFAULT_START} and {@value #DEFAULT_END}.</li>
 * <li>{@code replications} — the number of replications per variation. Defaults to {@value #DEFAULT_REPLICATIONS}.</li>
 * <li>{@code strict} — when {@code true}, a missing demand CSV is fatal instead of falling back to synthetic demand.
 * Defaults to {@code false}.</li>
 * </ul>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author Marvin Baumann
 */
public class FreiburgParameterStudy implements StudyDefinition
{
    /** Default start of the simulated period; the same daily window the multi-day evaluation study uses. */
    public static final String DEFAULT_START = "2025-09-25 " + FreiburgStudyParameters.START_TIME_OF_DAY;

    /** Default end of the simulated period; the same daily window the multi-day evaluation study uses. */
    public static final String DEFAULT_END = "2025-09-25 " + FreiburgStudyParameters.END_TIME_OF_DAY;

    /** Default number of replications per variation. */
    public static final int DEFAULT_REPLICATIONS = 6;

    @Override
    public String getName()
    {
        return "paramgrid";
    }

    @Override
    public String getDescription()
    {
        return "One-at-a-time parameter study: baseline plus four swept dimensions.";
    }

    @Override
    public void register(final ScenarioManager manager, final Map<String, String> options) throws Exception
    {
        String startDate = options.getOrDefault("start", DEFAULT_START);
        String endDate = options.getOrDefault("end", DEFAULT_END);
        boolean strict = Boolean.parseBoolean(options.getOrDefault("strict", "false"));
        int replications = Integer.parseInt(options.getOrDefault("replications", String.valueOf(DEFAULT_REPLICATIONS)));

        String scenarioName = "FreiburgNord_ParameterStudy_" + formatPeriodName(startDate, endDate);
        manager.addScenario(scenarioName, FreiburgNord.class);

        // The behavioural baseline is shared with the multi-day evaluation study, so that every swept dimension is
        // measured against exactly the configuration that study runs.
        ScenarioParameters baseParams = FreiburgStudyParameters.baseBehaviorParams();
        baseParams.setSeed(42L); // Base seed

        // Set demand date range and aggregation interval
        baseParams.set("demandStartDate", startDate);
        baseParams.set("demandEndDate", endDate);
        baseParams.set("demandAggregation", FreiburgStudyParameters.AGGREGATION_MIN);
        baseParams.set("demandSmooth", false);

        // Pre-generated demand (cluster): use the CSV as-is and never invoke the Python preparation pipeline
        String demandOption = options.get("demand");
        if (demandOption != null && !demandOption.trim().isEmpty())
        {
            String datePart = startDate.trim().split(" ")[0];
            File demandCsv = FreiburgDateStudy.resolveDemandCsv(new File(demandOption.trim()),
                    options.getOrDefault("pattern", FreiburgDateStudy.DEFAULT_CSV_PATTERN), datePart);
            if (!demandCsv.isFile())
            {
                String message = "Demand CSV not found for period starting " + datePart + ": " + demandCsv.getAbsolutePath();
                if (strict)
                {
                    throw new IllegalStateException(message + " (strict mode enabled)");
                }
                System.err.println("WARNING: " + message + "; this study will fall back to synthetic demand.");
            }
            baseParams.set("demandCsv", demandCsv.getAbsolutePath());
            baseParams.set(ScenarioGenerator.KEY_SKIP_DEMAND_PREP, true);
            baseParams.set(FreiburgNord.KEY_DEMAND_CSV_STRICT, strict);
        }

        for (ScenarioParameters varParams : buildVariations(baseParams))
        {
            manager.addParameterVariation(scenarioName, varParams);
        }

        manager.setReplications(replications);
    }

    /**
     * Builds the one-at-a-time variations of the study: the baseline plus, per swept dimension, one variation per value.
     * @param baseParams ScenarioParameters; the baseline parameters
     * @return List&lt;ScenarioParameters&gt;; the isolated parameter variations, baseline first
     */
    public static List<ScenarioParameters> buildVariations(final ScenarioParameters baseParams)
    {
        return new ParameterGridBuilder(baseParams)
                .addDimensionParallel(
                        new String[] {"coopDecel", "car." + MirovaParameters.cooperativeDecelerationThreshold.getId(),
                                "truck." + MirovaParameters.cooperativeDecelerationThreshold.getId()},
                        -3.0, -2.5, -2.0, -1.5)
                .addDimensionParallel(
                        new String[][] {
                                {"followerMinDecel", "car." + MirovaParameters.minFollowerDecelerationThreshold.getId(),
                                        "truck." + MirovaParameters.minFollowerDecelerationThreshold.getId()},
                                {"car." + MirovaParameters.maxFollowerDecelerationThreshold.getId(),
                                        "truck." + MirovaParameters.maxFollowerDecelerationThreshold.getId()}},
                        new Object[] {-1.0, -2.5}, new Object[] {-1.5, -3.0}, new Object[] {-2.0, -4.0},
                        new Object[] {-2.5, -5.0})
                .addDimensionParallel(
                        new String[][] {
                                {"egoMinDecel", "car." + MirovaParameters.minEgoDecelerationThreshold.getId(),
                                        "truck." + MirovaParameters.minEgoDecelerationThreshold.getId()},
                                {"car." + MirovaParameters.maxEgoDecelerationThreshold.getId(),
                                        "truck." + MirovaParameters.maxEgoDecelerationThreshold.getId()}},
                        new Object[] {-1.0, -2.5}, new Object[] {-1.5, -3.0}, new Object[] {-2.0, -4.0},
                        new Object[] {-2.5, -5.0})
                .addDimensionParallel(
                        new String[] {"safetyDistanceReductionFactorLaneChange",
                                "car." + MirovaParameters.safetyDistanceReductionFactorLaneChange.getId(),
                                "truck." + MirovaParameters.safetyDistanceReductionFactorLaneChange.getId()},
                        0.4, 0.5, 0.6, 0.7)
                .buildIsolated();
    }

    /**
     * Formats a simulated period into a filename-friendly string.
     * @param startDate String; the start of the period
     * @param endDate String; the end of the period
     * @return String; a formatted string usable as a directory name
     */
    public static String formatPeriodName(final String startDate, final String endDate)
    {
        String start = startDate.replace(" ", "_").replace(":", "-");
        String end = endDate.replace(" ", "_").replace(":", "-");
        return start + "_to_" + end;
    }
}
