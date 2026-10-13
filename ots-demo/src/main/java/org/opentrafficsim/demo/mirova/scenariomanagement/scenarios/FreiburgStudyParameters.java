package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import org.djunits.value.vdouble.scalar.Acceleration;
import org.djunits.value.vdouble.scalar.Duration;
import org.djunits.value.vdouble.scalar.Length;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioGenerator;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;

/**
 * The parameters shared by every study of the Freiburg-Nord facility: the simulated period, the demand wiring, and the
 * scenario-level overrides of standard driver parameters.
 * <p>
 * The overrides are <b>not</b> part of a run by default. A driver model runs on its own defaults unless a run selects the
 * scenario parameter set with {@code --lmrsParameters=scenario}; see {@link ScenarioGenerator#KEY_LMRS_PARAMETERS}. The values
 * are those the Freiburg studies used so far for the standard OTS parameters {@code T}, {@code a}, {@code b} and {@code s0}.
 * Everything else those studies set was a parameter of another driver model and is not carried over.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public final class FreiburgStudyParameters
{
    /** Demand aggregation interval in minutes. */
    public static final int AGGREGATION_MIN = 5;

    /** Scenario-level desired time headway of cars. */
    public static final Duration CAR_T = Duration.ofSI(1.10);

    /** Scenario-level desired time headway of trucks. */
    public static final Duration TRUCK_T = Duration.ofSI(1.40);

    /** Scenario-level maximum comfortable acceleration of cars. */
    public static final Acceleration CAR_A = Acceleration.ofSI(1.4);

    /** Scenario-level maximum comfortable acceleration of trucks. */
    public static final Acceleration TRUCK_A = Acceleration.ofSI(1.25);

    /** Scenario-level comfortable deceleration of cars and trucks. */
    public static final Acceleration COMFORTABLE_DECELERATION = Acceleration.ofSI(2.0);

    /** Scenario-level stopping distance of cars. */
    public static final Length CAR_S0 = Length.ofSI(2.0);

    /** Scenario-level stopping distance of trucks. */
    public static final Length TRUCK_S0 = Length.ofSI(4.0);

    /** Start of the simulated period, as time of day. */
    public static final String START_TIME_OF_DAY = "13:00:00";

    /** End of the simulated period, as time of day. */
    public static final String END_TIME_OF_DAY = "22:00:00";

    /**
     * Utility class.
     */
    private FreiburgStudyParameters()
    {
        // utility class
    }

    /**
     * Returns the scenario name for one simulated date.
     * @param date String; the simulated date in yyyy-MM-dd form
     * @return String; the scenario name, which is also the output sub-directory name
     */
    public static String scenarioName(final String date)
    {
        return "FreiburgNord_" + date + "_13-00_to_22-00";
    }

    /**
     * Returns the scenario name for one simulated date and one variant of a study.
     * @param date String; the simulated date in yyyy-MM-dd form
     * @param variantLabel String; the label of the variant
     * @return String; the scenario name, which is also the output sub-directory name
     */
    public static String scenarioName(final String date, final String variantLabel)
    {
        return scenarioName(date) + "_" + variantLabel;
    }

    /**
     * Returns the parameters for simulating one date: the behavioural baseline plus the demand wiring for that date.
     * @param date String; the simulated date in yyyy-MM-dd form
     * @param demandCsvPath String; the absolute path of the pre-generated demand CSV for this date
     * @param strict boolean; when true, a missing or unreadable demand CSV is fatal
     * @return ScenarioParameters; the parameters for that date
     */
    public static ScenarioParameters forDate(final String date, final String demandCsvPath, final boolean strict)
    {
        ScenarioParameters varParams = baseBehaviorParams();
        varParams.setSeed(42L);
        varParams.set("enableTrajectoryRecording", true);
        varParams.set("demandStartDate", date + " " + START_TIME_OF_DAY);
        varParams.set("demandEndDate", date + " " + END_TIME_OF_DAY);
        varParams.set("demandAggregation", AGGREGATION_MIN);
        varParams.set("demandSmooth", false);
        varParams.set("demandCsv", demandCsvPath);
        varParams.set(ScenarioGenerator.KEY_SKIP_DEMAND_PREP, true);
        varParams.set(FreiburgNord.KEY_DEMAND_CSV_STRICT, strict);
        return varParams;
    }

    /**
     * Returns the behavioural baseline of the facility, which is empty: every driver model runs on its own defaults.
     * @return ScenarioParameters; a fresh, empty instance
     */
    public static ScenarioParameters baseBehaviorParams()
    {
        return new ScenarioParameters();
    }

    /**
     * Returns the scenario-level overrides of standard OTS driver parameters, applied only when a run selects the scenario
     * parameter set.
     * @return ScenarioParameters; a fresh instance holding the eight overrides
     */
    public static ScenarioParameters scenarioParameterOverrides()
    {
        ScenarioParameters params = new ScenarioParameters();
        params.set("car." + ParameterTypes.T.getId(), CAR_T);
        params.set("car." + ParameterTypes.A.getId(), CAR_A);
        params.set("car." + ParameterTypes.B.getId(), COMFORTABLE_DECELERATION);
        params.set("car." + ParameterTypes.S0.getId(), CAR_S0);
        params.set("truck." + ParameterTypes.T.getId(), TRUCK_T);
        params.set("truck." + ParameterTypes.A.getId(), TRUCK_A);
        params.set("truck." + ParameterTypes.B.getId(), COMFORTABLE_DECELERATION);
        params.set("truck." + ParameterTypes.S0.getId(), TRUCK_S0);
        return params;
    }
}
