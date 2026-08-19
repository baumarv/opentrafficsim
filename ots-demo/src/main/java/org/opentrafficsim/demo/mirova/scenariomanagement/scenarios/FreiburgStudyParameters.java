package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioGenerator;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;

/**
 * Shared parameter configuration of the Freiburg-Nord multi-day evaluation study.
 * <p>
 * Both the {@link FreiburgDateStudy} used by the single-run cluster entry point and the batched
 * {@link RunFreiburgParallelCluster} build their runs from this single definition, so that a run is configured identically
 * regardless of whether it was launched as one array task per run or as part of a batched, multi-threaded task.
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
        ScenarioParameters varParams = new ScenarioParameters();
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

        // Car parameters
        varParams.set("car." + ParameterTypes.T.getId(), CAR_T);
        varParams.set("car." + MirovaParameters.vGain.getId(), 15.0);
        varParams.set("car." + MirovaParameters.A_MAX.getId(), 3.5);
        varParams.set("car." + MirovaParameters.cooperativeDecelerationThreshold.getId(), -2.0);
        varParams.set("car." + MirovaParameters.farAnticipationEnabled.getId(), false);
        varParams.set("car." + MirovaParameters.safetyDistanceReductionFactorLaneChange.getId(), RED_FAC);
        varParams.set("car." + MirovaParameters.CAPACITY_DROP_ENABLED.getId(), false);
        varParams.set("car." + MirovaParameters.RELAXATION_ACC_DAMPING_FACTOR.getId(), 0.8);
        varParams.set("car." + MirovaParameters.RELAXATION_ACC_DAMPING_ENABLED.getId(), true);

        // Truck parameters
        varParams.set("truck." + ParameterTypes.T.getId(), TRUCK_T);
        varParams.set("truck." + MirovaParameters.vGain.getId(), 30.0);
        varParams.set("truck." + MirovaParameters.A_MAX.getId(), 1.3);
        varParams.set("truck." + MirovaParameters.cooperativeDecelerationThreshold.getId(), -0.5);
        varParams.set("truck." + MirovaParameters.cooperativeLaneChangesEnabled.getId(), false);
        varParams.set("truck." + MirovaParameters.farAnticipationEnabled.getId(), false);
        varParams.set("truck." + MirovaParameters.safetyDistanceReductionFactorLaneChange.getId(), RED_FAC);
        varParams.set("truck." + MirovaParameters.CAPACITY_DROP_ENABLED.getId(), false);
        varParams.set("truck." + MirovaParameters.RELAXATION_ACC_DAMPING_FACTOR.getId(), 0.8);
        varParams.set("truck." + MirovaParameters.RELAXATION_ACC_DAMPING_ENABLED.getId(), true);

        return varParams;
    }
}
