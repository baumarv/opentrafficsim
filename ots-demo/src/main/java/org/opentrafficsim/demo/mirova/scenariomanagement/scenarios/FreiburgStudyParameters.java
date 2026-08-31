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

    /**
     * Desired headway T of cars, in seconds.
     * <p>
     * Raised from 0.90 - the value the campaigns had been using - by the headway-against-damping grid. At 0.90 the
     * model broke down in one run out of ten on 2025-10-27, a date on which the site does break down, and above a
     * damping of 0.95 it did not break down at all. That headway leaves too much capacity for this site.
     * </p>
     */
    public static final double CAR_T = 1.10;

    /** Desired headway T of trucks, in seconds. Raised alongside {@link #CAR_T}; the grid varied the pair. */
    public static final double TRUCK_T = 1.40;

    /**
     * Car-following acceleration of cars [m/s^2], after Kesting et al. for motorway traffic.
     * <p>
     * OTS defaults {@link ParameterTypes#A} to 1.25 m/s^2 for every vehicle class. That single value is both too slow
     * for cars and too brisk for trucks, and because nothing in the MiRoVA setup ever set it, both classes ran on it.
     * </p>
     */
    public static final double CAR_A = 1.4;

    /**
     * Car-following acceleration of trucks [m/s^2].
     * <p>
     * Not the field median of 0.60 to 0.87, although that is what this held. IDM reads the parameter as a ceiling: the
     * free term scales it down with speed and the interaction term reduces it further, so at 0.7 the trucks actually
     * accelerated at a median of 0.28 m/s^2, and 0.51 below 10 km/h. The field figures also average a process that
     * starts higher, and were measured pulling away from a ramp meter rather than at an interchange.
     * </p>
     * <p>
     * A factorial over 0.7, 1.0 and 1.3 found this the strongest of every axis tested and monotone across all three:
     * ramp standstills fell from 340 to 244 per run, the right-hand mainline lane gained 11.9 km/h in congestion and
     * the jam shortened by 11.7 minutes. Notably the queue discharge barely moved, which is what published sweeps of
     * this parameter also report - it governs the speed level and the duration, not the throughput.
     * </p>
     */
    public static final double TRUCK_A = 1.25;

    /** Comfortable car-following deceleration [m/s^2], after Kesting et al.; identical for both classes. */
    public static final double COMFORTABLE_DECELERATION = 2.0;

    /**
     * Stopping distance of cars [m], after Kesting et al.
     * <p>
     * Interacts with {@link MirovaParameters#safetyDistanceReductionFactorLaneChange}, which scales the target headway
     * when a relaxation is triggered: a smaller {@code s0} therefore acts multiplicatively in the merge situation, not
     * additively. Values of the {@code sdr} grid axis are not comparable across a change of this constant.
     * </p>
     */
    public static final double CAR_S0 = 2.0;

    /** Stopping distance of trucks [m], after Kesting et al. */
    public static final double TRUCK_S0 = 4.0;

    /**
     * Deceleration a merging car expects the follower on the target lane to accept at the lowest mandatory desire
     * [m/s^2].
     * <p>
     * {@code EgoContext.computeFollowerDecelerationThreshold} interpolates linearly between this value and
     * {@link #CAR_FOLLOWER_DECELERATION_MAX} over the lane-change desire above {@code DMAND}, so both ends bound the
     * same ramp and neither can be moved on its own.
     * </p>
     * <p>
     * This pair depends on the operating point, and the two tests of it disagree. On a heavily congested hour - damping
     * 0.70, safety distance 0.45, jams of two to three hours - loosening it to -2.5 / -5.0 was the single effective
     * lever against ramp standstills, which fell from 37.4 to 29.9 per run while the one run that had been collapsing
     * came back from 84 to 28. On a full day at the calibrated operating point, where jams last ten to twenty minutes,
     * a factorial over 108 runs found the opposite: -2.5 raised standstills by 29 % against -2.0, from 265 to 340 per
     * run, and it accounts for much of the near-doubling seen between the third and the fourth campaign.
     * </p>
     * <p>
     * The calibration targets ordinary days, where the light regime dominates, so the value stays at the tighter pair.
     * Should heavy congestion become the target, this is the first parameter to revisit rather than a settled one.
     * Strengthening the cooperative deceleration was tried alongside in both tests and is clearly worse in each - a gap
     * opener braking harder holds up the column behind it and creates the very disturbance that blocks the merge.
     * </p>
     * <p>
     * The threshold is an admissibility criterion in the gap assessment, not a commanded deceleration. Measured over
     * the followers within 150 m of a merge, the median deceleration is 1.7 m/s^2 and the 5 % quantile sits at 3.5
     * regardless of this setting; the share braking harder than 5 m/s^2 rises from 0.75 % to 1.06 %. The effect comes
     * from usable gaps no longer being discarded, not from anyone actually braking that hard.
     * </p>
     */
    public static final double CAR_FOLLOWER_DECELERATION_MIN = -2.0;

    /** Deceleration a merging car expects the follower to accept at full desire [m/s^2]. See the minimum for context. */
    public static final double CAR_FOLLOWER_DECELERATION_MAX = -4.0;

    /**
     * Relaxation acceleration damping factor: the lower bound of the scaling applied to positive accelerations while a
     * relaxation is active, so 1.00 leaves accelerations untouched and smaller values damp harder.
     * <p>
     * Now 1.00, i.e. off. The damping applies precisely in the moments after an insertion, to the merging vehicle and
     * to the follower it cut in front of, and it is therefore the parameter governing how smoothly traffic re-sorts
     * itself. A 120-run grid found it monotone in every headway row: at 1.10 / 1.40 it takes vehicles going through a
     * stop-and-go cycle from 30.8 % to 11.0 % and ramp standstills from 1237 to 340 per run.
     * </p>
     * <p>
     * It cannot be set alone. Removing the damping raises the discharge enough to prevent breakdowns - at the previous
     * headway of 0.90 / 1.20 it removed them entirely - which is why the headway was lengthened at the same time. The
     * two axes work against each other on the same quantity and only the pair is meaningful.
     * </p>
     */
    public static final double RELAXATION_DAMPING = 1.00;

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
        params.set("car." + ParameterTypes.A.getId(), CAR_A);
        params.set("car." + ParameterTypes.B.getId(), COMFORTABLE_DECELERATION);
        params.set("car." + ParameterTypes.S0.getId(), CAR_S0);
        params.set("car." + MirovaParameters.vGain.getId(), 15.0);
        params.set("car." + MirovaParameters.A_MAX.getId(), 3.5);
        params.set("car." + MirovaParameters.cooperativeDecelerationThreshold.getId(), CAR_COOPERATIVE_DECELERATION_THRESHOLD);
        params.set("car." + MirovaParameters.farAnticipationEnabled.getId(), false);
        params.set("car." + MirovaParameters.safetyDistanceReductionFactorLaneChange.getId(), RED_FAC);
        params.set("car." + MirovaParameters.minFollowerDecelerationThreshold.getId(), CAR_FOLLOWER_DECELERATION_MIN);
        params.set("car." + MirovaParameters.maxFollowerDecelerationThreshold.getId(), CAR_FOLLOWER_DECELERATION_MAX);
        params.set("car." + MirovaParameters.CAPACITY_DROP_ENABLED.getId(), false);
        params.set("car." + MirovaParameters.RELAXATION_ACC_DAMPING_FACTOR.getId(), RELAXATION_DAMPING);
        params.set("car." + MirovaParameters.RELAXATION_ACC_DAMPING_ENABLED.getId(), true);

        // Truck parameters
        params.set("truck." + ParameterTypes.T.getId(), TRUCK_T);
        params.set("truck." + ParameterTypes.A.getId(), TRUCK_A);
        params.set("truck." + ParameterTypes.B.getId(), COMFORTABLE_DECELERATION);
        params.set("truck." + ParameterTypes.S0.getId(), TRUCK_S0);
        params.set("truck." + MirovaParameters.vGain.getId(), 30.0);
        params.set("truck." + MirovaParameters.A_MAX.getId(), 1.3);
        params.set("truck." + MirovaParameters.cooperativeDecelerationThreshold.getId(),
                TRUCK_COOPERATIVE_DECELERATION_THRESHOLD);
        params.set("truck." + MirovaParameters.cooperativeLaneChangesEnabled.getId(), false);
        params.set("truck." + MirovaParameters.farAnticipationEnabled.getId(), false);
        params.set("truck." + MirovaParameters.safetyDistanceReductionFactorLaneChange.getId(), RED_FAC);
        params.set("truck." + MirovaParameters.CAPACITY_DROP_ENABLED.getId(), false);
        params.set("truck." + MirovaParameters.RELAXATION_ACC_DAMPING_FACTOR.getId(), RELAXATION_DAMPING);
        params.set("truck." + MirovaParameters.RELAXATION_ACC_DAMPING_ENABLED.getId(), true);

        return params;
    }
}
