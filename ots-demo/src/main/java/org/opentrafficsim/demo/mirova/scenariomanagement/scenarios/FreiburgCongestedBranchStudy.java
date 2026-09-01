package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.demo.mirova.scenariomanagement.FacilityRegistry;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.StudyDefinition;
import org.opentrafficsim.demo.mirova.scenariomanagement.TrafficFacility;

/**
 * Grid over the three parameters the sensitivity screen found to move the congested branch.
 * <p>
 * The screen (study {@code sensitivity}, 360 runs over nine days) settled the direction question the
 * headway-and-damping campaigns could not: comfortable deceleration spans a jam speed of 27.9 to 58.5 km/h and so
 * covers the empirical 44.6 outright, while relaxation damping moved it by 2.6 km/h and desired headway by 5.4. The
 * quantity that eighteen earlier cells failed to reach turns out to be governed by the one parameter none of them
 * varied.
 * </p>
 * <p>
 * <b>Comfortable deceleration</b> is the primary axis. Interpolating the screen, the empirical jam speed sits near
 * {@code b = 1.35}, so the levels bracket that rather than continuing downward from the old default of 2.0 - at 1.5
 * the jam already runs at 39.3 km/h and at 2.0 at 28.7, so a grid starting at 2.0 would sit below the target
 * throughout. Lower values also halve ramp standstills (432 to 193 per run) and lift merge speed from 53 to 58 km/h,
 * against the concern that weaker braking would strand mergers on the ramp: in IDM {@code b} sits under the root of
 * the interaction term, so a smaller value <i>widens</i> the desired gap on approach. The vehicle reacts earlier and
 * more gently rather than late and hard, which is what a merger needs.
 * </p>
 * <p>
 * <b>Stopped distance</b> is the counterweight. Lowering {@code b} costs breakdown frequency - the screen fell to
 * 58 % against an empirical 8 days in 9 - and a larger {@code s0} brings it back, hitting 89 % exactly on its own.
 * It cannot be used alone: at that setting the model also broke down on all four runs of the one day the site stayed
 * free. Only the smaller direction is excluded outright, since {@code s0 = 1.0} tripled standstills to 1320.
 * </p>
 * <p>
 * <b>Maximum acceleration of cars</b> is the third axis, weaker than the other two but pulling the same way as a
 * lower {@code b}: 2.0 m/s&sup2; raised jam speed to 39.3 km/h at a discharge of 3129 against the empirical 3115,
 * with no false breakdown on the quiet day at all.
 * </p>
 * <p>
 * Everything else is held at the settled values, headway 1.10 / 1.40 and damping off, because the screen confirmed
 * both to be near-inert on the quantity this grid is chasing.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See OpenTrafficSim License.
 * </p>
 * @author Marvin Baumann
 */
public class FreiburgCongestedBranchStudy implements StudyDefinition
{
    /** Lane-change safety distance reduction factor, fixed at the settled value. */
    public static final double SAFETY_DISTANCE_FACTOR = FreiburgCarStudy.SAFETY_DISTANCE_FACTOR;

    /** Desired headway, held at the settled pair. */
    public static final FreiburgCombinationStudy.HeadwayCombination BASE_HEADWAY =
            FreiburgSensitivityStudy.BASE_HEADWAY;

    /** Relaxation acceleration damping, held at the settled value. */
    public static final double BASE_DAMPING = FreiburgSensitivityStudy.BASE_DAMPING;

    /**
     * Comfortable deceleration levels, in m/s&sup2;, applied to cars and trucks alike.
     * <p>
     * Bracket the interpolated optimum of about 1.35 rather than descending from the old default of 2.0, which the
     * screen showed to sit well below the empirical jam speed.
     * </p>
     */
    public static final List<Double> B_LEVELS = List.of(1.25, 1.50, 1.75);

    /** Stopped distance of cars, in m. The truck value follows at Kesting's 2:1 ratio. */
    public static final List<Double> S0_CAR_LEVELS = List.of(2.0, 2.5, 3.0);

    /** Maximum acceleration of cars, in m/s&sup2;. */
    public static final List<Double> A_CAR_LEVELS = List.of(1.4, 2.0);

    /** Parameter keys recording the cell in {@code runParams.txt}. */
    public static final String KEY_B = "studyB";

    /** Parameter key for the stopped distance of cars. */
    public static final String KEY_S0 = "studyS0Car";

    /** Parameter key for the maximum acceleration of cars. */
    public static final String KEY_A = "studyACar";

    @Override
    public String getName()
    {
        return "congested";
    }

    @Override
    public String getDescription()
    {
        return "Comfortable deceleration " + B_LEVELS + " x car stopped distance " + S0_CAR_LEVELS
                + " x car maximum acceleration " + A_CAR_LEVELS + ": "
                + (B_LEVELS.size() * S0_CAR_LEVELS.size() * A_CAR_LEVELS.size()) + " variations per date.";
    }

    /**
     * Returns the label identifying one cell, used as the suffix of the scenario name.
     * <p>
     * Formatted with {@link Locale#ROOT} so the decimal separator is a dot on every machine: the label ends up in
     * directory names that post-processing matches on.
     * </p>
     * @param b double; the comfortable deceleration
     * @param s0Car double; the stopped distance of cars
     * @param aCar double; the maximum acceleration of cars
     * @return String; the variant label
     */
    public static String variantLabel(final double b, final double s0Car, final double aCar)
    {
        return String.format(Locale.ROOT, "b%.2f_s0%.1f_a%.1f", b, s0Car, aCar);
    }

    /** {@inheritDoc} */
    @Override
    public void register(final ScenarioManager manager, final Map<String, String> options) throws Exception
    {
        TrafficFacility facility = FacilityRegistry.resolve(FreiburgFacility.NAME);

        List<String> dates = DateStudy.resolveDates(options.get("dates"));
        if (dates.isEmpty())
        {
            throw new IllegalArgumentException("Study 'congested' requires --dates=<comma-separated-dates|file>.");
        }
        String demandOption = options.get("demand");
        if (demandOption == null || demandOption.trim().isEmpty())
        {
            throw new IllegalArgumentException("Study 'congested' requires --demand=<csv file or directory>.");
        }
        File demandLocation = new File(demandOption.trim());
        String pattern = options.getOrDefault("pattern", DateStudy.DEFAULT_CSV_PATTERN);
        boolean strict = Boolean.parseBoolean(options.getOrDefault("strict", "false"));
        int replications = Integer.parseInt(
                options.getOrDefault("replications", String.valueOf(DateStudy.DEFAULT_REPLICATIONS)));

        Map<String, File> demandPerDate = DateStudy.resolveDemandCsvs(dates, demandLocation, pattern, strict);

        // Registration order is date-major, then b, then s0, then a, which the global run index follows.
        for (String date : dates)
        {
            String demandCsvPath = demandPerDate.get(date).getAbsolutePath();
            for (double b : B_LEVELS)
            {
                for (double s0Car : S0_CAR_LEVELS)
                {
                    for (double aCar : A_CAR_LEVELS)
                    {
                        String scenarioName = facility.scenarioName(date, variantLabel(b, s0Car, aCar));
                        manager.addScenario(scenarioName, facility.getGeneratorClass());
                        manager.addParameterVariation(scenarioName,
                                forCell(facility, date, demandCsvPath, strict, b, s0Car, aCar));
                    }
                }
            }
        }
        manager.setReplications(replications);
    }

    /**
     * Builds the parameter set of one cell.
     * @param facility TrafficFacility; the facility the study runs on
     * @param date String; the simulated date
     * @param demandCsvPath String; absolute path of the demand CSV for that date
     * @param strict boolean; whether a missing demand CSV aborts the run
     * @param b double; the comfortable deceleration of both vehicle types
     * @param s0Car double; the stopped distance of cars
     * @param aCar double; the maximum acceleration of cars
     * @return ScenarioParameters; the parameters of this cell
     */
    public static ScenarioParameters forCell(final TrafficFacility facility, final String date,
            final String demandCsvPath, final boolean strict, final double b, final double s0Car, final double aCar)
    {
        ScenarioParameters params = FreiburgCombinationStudy.forCombination(facility, date, demandCsvPath, strict,
                BASE_HEADWAY, BASE_DAMPING, SAFETY_DISTANCE_FACTOR);
        params.set("car." + ParameterTypes.B.getId(), b);
        params.set("truck." + ParameterTypes.B.getId(), b);
        params.set("car." + ParameterTypes.S0.getId(), s0Car);
        params.set("truck." + ParameterTypes.S0.getId(), 2.0 * s0Car);
        params.set("car." + ParameterTypes.A.getId(), aCar);

        params.set(KEY_B, b);
        params.set(KEY_S0, s0Car);
        params.set(KEY_A, aCar);
        return params;
    }
}
