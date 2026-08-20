package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioGenerator;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;

/**
 * Shared parameter configuration of the Freiburg-Nord multi-day evaluation study.
 * <p>
 * Every study that runs this facility builds on this single definition - {@link DateStudy} directly, and
 * {@link FreiburgCombinationStudy} and {@link FreiburgParameterStudy} with their swept parameters layered on top - so that
 * a run is configured identically no matter which study or execution path produced it.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author Marvin Baumann
 */
public final class FreiburgStudyParameters
{
    /** Safety distance reduction factor for lane changes. */
    public static final double RED_FAC = 0.60;

    /** Demand aggregation interval in minutes. */
    public static final int AGGREGATION_MIN = 5;

    /** Desired headway T of cars, in seconds. */
    public static final double CAR_T = 1.00;

    /** Desired headway T of trucks, in seconds. */
    public static final double TRUCK_T = 1.30;

    /** Deceleration a car accepts in order to cooperate with a merging vehicle, in m/s^2. */
    public static final double CAR_COOPERATIVE_DECELERATION_THRESHOLD = -3.0;

    /** Deceleration a truck accepts in order to cooperate with a merging vehicle, in m/s^2. */
    public static final double TRUCK_COOPERATIVE_DECELERATION_THRESHOLD = -1.0;

    /** Start time of day of every simulated date. */
    public static final String START_TIME_OF_DAY = "13:00:00";

    /** End time of day of every simulated date. */
    public static final String END_TIME_OF_DAY = "22:00:00";

    /** Utility class; not to be instantiated. */
    private FreiburgStudyParameters()
    {
        // utility class
    }

    /**
     * Returns the scenario name used for the given date.
     * @param date String; the simulated date in yyyy-MM-dd form
     * @return String; the scenario name, which is also the output sub-directory name
     */
    public static String scenarioName(final String date)
    {
        return "FreiburgNord_" + date + "_13-00_to_22-00";
    }

    /**
     * Returns the scenario name used for the given date and named parameter variant. Keeping the label in the scenario name
     * makes an output folder identifiable by date and variant on its own, without resolving a numeric variation index
     * against the study definition.
     * @param date String; the simulated date in yyyy-MM-dd form
     * @param variantLabel String; the label of the parameter variant, e.g. a headway combination name
     * @return String; the scenario name, which is also the output sub-directory name
     */
    public static String scenarioName(final String date, final String variantLabel)
    {
        return scenarioName(date) + "_" + variantLabel;
    }

    /**
     * Builds the parameter variation of the study for one simulated date.
     * <p>
     * The seed set here is irrelevant for the actual run: both execution paths derive the effective seed from the scenario
     * generator's default parameters plus the replication index, and overwrite this value.
     * </p>
     * @param date String; the simulated date in yyyy-MM-dd form
     * @param demandCsvPath String; the absolute path of the pre-generated demand CSV for this date
     * @param strict boolean; when true, a missing or unreadable demand CSV is fatal instead of falling back to synthetic
     *            demand
     * @return ScenarioParameters; the parameter variation for this date
     */
    public static ScenarioParameters forDate(final String date, final String demandCsvPath, final boolean strict)
    {
        ScenarioParameters varParams = baseBehaviorParams();
        varParams.setSeed(42L);
        varParams.set("enableTrajectoryRecording", true);

        // Demand period
        varParams.set("demandStartDate", date + " " + START_TIME_OF_DAY);
        varParams.set("demandEndDate", date + " " + END_TIME_OF_DAY);

        // 5-minute aggregation + disabled demand smoothing
        varParams.set("demandAggregation", AGGREGATION_MIN);
        varParams.set("demandSmooth", false);

        // Pre-generated demand: use the CSV as-is and never invoke the Python preparation pipeline
        varParams.set("demandCsv", demandCsvPath);
        varParams.set(ScenarioGenerator.KEY_SKIP_DEMAND_PREP, true);
        varParams.set(FreiburgNord.KEY_DEMAND_CSV_STRICT, strict);

        return varParams;
    }

    /**
     * Builds the behavioural baseline shared by every Freiburg-Nord study: the car and truck parameters that define how the
     * modelled drivers behave, independent of which period is simulated or where its demand comes from.
     * <p>
     * This is the single source of truth for these values. Studies layer their own seed, demand wiring and, for parameter
     * studies, their swept dimensions on top, so that a sweep is always measured against the same baseline the multi-day
     * evaluation study runs — the two cannot drift apart the way the previously duplicated blocks did.
     * </p>
     * <p>
     * Deliberately <i>not</i> included, because they configure the run rather than the driving behaviour: the seed, output
     * switches such as {@code enableTrajectoryRecording}, and all demand wiring ({@code demandStartDate},
     * {@code demandEndDate}, {@code demandAggregation}, {@code demandSmooth}, {@code demandCsv} and the strictness flags).
     * </p>
     * @return ScenarioParameters; a fresh instance holding only the behavioural baseline
     */
    public static ScenarioParameters baseBehaviorParams()
    {
        ScenarioParameters params = new ScenarioParameters();

        // Car parameters
        params.set("car." + ParameterTypes.T.getId(), CAR_T);
        params.set("car." + MirovaParameters.vGain.getId(), 15.0);
        params.set("car." + MirovaParameters.A_MAX.getId(), 3.5);
        params.set("car." + MirovaParameters.cooperativeDecelerationThreshold.getId(), CAR_COOPERATIVE_DECELERATION_THRESHOLD);
        params.set("car." + MirovaParameters.farAnticipationEnabled.getId(), false);
        params.set("car." + MirovaParameters.safetyDistanceReductionFactorLaneChange.getId(), RED_FAC);
        params.set("car." + MirovaParameters.CAPACITY_DROP_ENABLED.getId(), false);
        params.set("car." + MirovaParameters.RELAXATION_ACC_DAMPING_FACTOR.getId(), 0.8);
        params.set("car." + MirovaParameters.RELAXATION_ACC_DAMPING_ENABLED.getId(), true);

        // Truck parameters
        params.set("truck." + ParameterTypes.T.getId(), TRUCK_T);
        params.set("truck." + MirovaParameters.vGain.getId(), 30.0);
        params.set("truck." + MirovaParameters.A_MAX.getId(), 1.3);
        params.set("truck." + MirovaParameters.cooperativeDecelerationThreshold.getId(),
                TRUCK_COOPERATIVE_DECELERATION_THRESHOLD);
        params.set("truck." + MirovaParameters.cooperativeLaneChangesEnabled.getId(), false);
        params.set("truck." + MirovaParameters.farAnticipationEnabled.getId(), false);
        params.set("truck." + MirovaParameters.safetyDistanceReductionFactorLaneChange.getId(), RED_FAC);
        params.set("truck." + MirovaParameters.CAPACITY_DROP_ENABLED.getId(), false);
        params.set("truck." + MirovaParameters.RELAXATION_ACC_DAMPING_FACTOR.getId(), 0.8);
        params.set("truck." + MirovaParameters.RELAXATION_ACC_DAMPING_ENABLED.getId(), true);

        return params;
    }
}
