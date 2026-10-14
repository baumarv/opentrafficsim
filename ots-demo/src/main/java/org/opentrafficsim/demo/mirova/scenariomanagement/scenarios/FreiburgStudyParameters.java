package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioGenerator;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;

/**
 * The parameters shared by every study of the Freiburg-Nord facility: the simulated period, the demand wiring and the car
 * desired-speed distribution.
 * <p>
 * It sets no driver parameter; each driver model starts from its own defaults (for the LMRS see {@code LmrsDefaults}), and a
 * run changes them with {@code car.<parameter>} or {@code truck.<parameter>} options or with a preset.
 * </p>
 * <p>
 * The car desired-speed distribution is that of the MiRoVA campaign final_v2: the measured distribution compressed towards
 * {@value #DESIRED_SPEED_PIVOT_CAR_KMH} km/h by a factor of {@value #DESIRED_SPEED_COMPRESSION_CAR}, v -&gt; pivot + factor
 * (v - pivot), which moves 80..200 km/h to 101..185 km/h. It is a property of the population of drivers, not of a driver
 * model, so every model runs with it.
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

    /** Start of the simulated period, as time of day. */
    public static final String START_TIME_OF_DAY = "13:00:00";

    /** End of the simulated period, as time of day. */
    public static final String END_TIME_OF_DAY = "22:00:00";

    /** Speed the car desired-speed distribution is compressed towards [km/h], as in final_v2. */
    public static final double DESIRED_SPEED_PIVOT_CAR_KMH = 150.0;

    /** Compression factor of the car desired-speed distribution, as in final_v2. */
    public static final double DESIRED_SPEED_COMPRESSION_CAR = 0.7;

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
     * Returns the behavioural baseline of the facility: the car desired-speed distribution. Driver parameters are left to the
     * driver model.
     * @return ScenarioParameters; a fresh instance with the baseline
     */
    public static ScenarioParameters baseBehaviorParams()
    {
        ScenarioParameters params = new ScenarioParameters();
        params.set(ScenarioParameters.KEY_DESIRED_SPEED_PIVOT_CAR, DESIRED_SPEED_PIVOT_CAR_KMH);
        params.set(ScenarioParameters.KEY_DESIRED_SPEED_COMPRESSION_CAR, DESIRED_SPEED_COMPRESSION_CAR);
        return params;
    }
}
