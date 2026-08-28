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
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.FreiburgCombinationStudy.HeadwayCombination;

/**
 * Sweep of the two car-following parameters of the car class that have never been varied, over every calibration date.
 * <p>
 * Three car parameters changed at once when the Kesting values were adopted - acceleration 1.25 to 1.4, comfortable
 * deceleration 2.09 to 2.0 and stopping distance 3.0 to 2.0 m - and none of them was ever tested on its own. The truck
 * side has since been resolved by a factorial, but cars carry 80 % of the traffic here, so the larger gap was left
 * open. This study closes it.
 * </p>
 * <p>
 * The truck and follower values it holds fixed are the ones that factorial settled, each measured over 108 to 359 runs:
 * </p>
 * <ul>
 * <li>{@code a} of trucks at 1.25 rather than the field median of 0.7 - the strongest axis of all and monotone across
 * three levels, worth 96 fewer ramp standstills per run and 11.9 km/h in the right-hand lane. IDM reads the parameter
 * as a ceiling, and at 0.7 the trucks actually accelerated at a median of 0.28 m/s^2.</li>
 * <li>{@code s0} of trucks at the Kesting value of 4.0 m, which is one of the few places the Kesting set clearly
 * helped: 19 % fewer standstills than at 3.0.</li>
 * <li>{@code T} of trucks at the combination's 1.20 s. The factorial found no effect on standstills and only a weak
 * one on speeds, so there is no reason to break comparability with the earlier campaigns over it.</li>
 * <li>Follower deceleration thresholds back at -2.0 / -4.0. Loosening them to -2.5 / -5.0 raised standstills by 29 %
 * at this operating point and accounts for much of their near-doubling between the third and the fourth campaign. The
 * opposite held under heavy congestion, which is recorded where the constants are defined.</li>
 * </ul>
 * <p>
 * Comfortable deceleration stays at 2.0 rather than being swept: against the OTS default of 2.09 that is a four percent
 * difference, which is noise beside the other effects. It therefore rides along untested, deliberately.
 * </p>
 * <p>
 * The third level of the car acceleration is not a bracket around Kesting's 1.4 but an opening upwards, because the
 * truck result showed how far the parameter sits above the acceleration it produces. Whether 1.4 suffices for cars at
 * a bottleneck is open, and the extra level costs two cells.
 * </p>
 * <p>
 * One cell is the control: car acceleration 1.25 with a stopping distance of 3.0 m restores the OTS defaults both
 * carried before the Kesting set, so the campaign contains its own answer to whether everything else changed since the
 * third campaign has been worth it.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See OpenTrafficSim License.
 * </p>
 * @author Marvin Baumann
 */
public class FreiburgCarStudy implements StudyDefinition
{
    /** The relaxation acceleration damping factor, fixed at the best cell of the fourth campaign. */
    public static final double ACC_DAMPING_FACTOR = 0.80;

    /** The lane-change safety distance reduction factor, fixed at the best cell of the fourth campaign. */
    public static final double SAFETY_DISTANCE_FACTOR = 0.40;

    /**
     * Car-following acceleration of cars [m/s^2]: the OTS default the model ran on until the fourth campaign, the
     * Kesting value it runs on now, and one step beyond it.
     */
    public static final List<Double> CAR_ACCELERATIONS = List.of(1.25, 1.40, 1.70);

    /** Stopping distance of cars [m]: the Kesting value and the OTS default. */
    public static final List<Double> CAR_STOPPING_DISTANCES = List.of(2.0, 3.0);

    /** Parameter key recording the car acceleration in {@code runParams.txt}. */
    public static final String KEY_CAR_A = "studyCarA";

    /** Parameter key recording the car stopping distance in {@code runParams.txt}. */
    public static final String KEY_CAR_S0 = "studyCarS0";

    @Override
    public String getName()
    {
        return "carparams";
    }

    @Override
    public String getDescription()
    {
        return "Car acceleration " + CAR_ACCELERATIONS + " x car stopping distance " + CAR_STOPPING_DISTANCES
                + " at damping " + ACC_DAMPING_FACTOR + " and safety distance " + SAFETY_DISTANCE_FACTOR + ": "
                + (CAR_ACCELERATIONS.size() * CAR_STOPPING_DISTANCES.size()) + " variations per date.";
    }

    /**
     * Returns the label identifying one cell, used as the suffix of the scenario name.
     * <p>
     * Formatted with {@link Locale#ROOT} so the decimal separator is a dot on every machine: the label ends up in
     * directory names that post-processing matches on, and those must not depend on the format locale of whichever
     * node ran the job.
     * </p>
     * @param carA double; the car car-following acceleration
     * @param carS0 double; the car stopping distance
     * @return String; the variant label
     */
    public static String variantLabel(final double carA, final double carS0)
    {
        return String.format(Locale.ROOT, "ca%.2f_cs%.1f", carA, carS0);
    }

    /** {@inheritDoc} */
    @Override
    public void register(final ScenarioManager manager, final Map<String, String> options) throws Exception
    {
        TrafficFacility facility = FacilityRegistry.resolve(FreiburgFacility.NAME);
        HeadwayCombination combination = FreiburgDampingStudy.resolveHeadwayCombination();

        List<String> dates = DateStudy.resolveDates(options.get("dates"));
        if (dates.isEmpty())
        {
            throw new IllegalArgumentException("Study 'carparams' requires --dates=<comma-separated-dates|file>.");
        }

        String demandOption = options.get("demand");
        if (demandOption == null || demandOption.trim().isEmpty())
        {
            throw new IllegalArgumentException("Study 'carparams' requires --demand=<csv file or directory>.");
        }
        File demandLocation = new File(demandOption.trim());
        String pattern = options.getOrDefault("pattern", DateStudy.DEFAULT_CSV_PATTERN);
        boolean strict = Boolean.parseBoolean(options.getOrDefault("strict", "false"));
        int replications = Integer.parseInt(
                options.getOrDefault("replications", String.valueOf(DateStudy.DEFAULT_REPLICATIONS)));

        Map<String, File> demandPerDate = DateStudy.resolveDemandCsvs(dates, demandLocation, pattern, strict);

        // Registration order is date-major, then acceleration, then stopping distance, which the global run index
        // follows.
        for (String date : dates)
        {
            String demandCsvPath = demandPerDate.get(date).getAbsolutePath();
            for (double carA : CAR_ACCELERATIONS)
            {
                for (double carS0 : CAR_STOPPING_DISTANCES)
                {
                    String scenarioName = facility.scenarioName(date, variantLabel(carA, carS0));
                    manager.addScenario(scenarioName, facility.getGeneratorClass());
                    manager.addParameterVariation(scenarioName,
                            forCell(facility, date, demandCsvPath, strict, combination, carA, carS0));
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
     * @param combination HeadwayCombination; the fixed headway combination
     * @param carA double; the car car-following acceleration
     * @param carS0 double; the car stopping distance
     * @return ScenarioParameters; the parameters of this cell
     */
    public static ScenarioParameters forCell(final TrafficFacility facility, final String date,
            final String demandCsvPath, final boolean strict, final HeadwayCombination combination,
            final double carA, final double carS0)
    {
        ScenarioParameters params = FreiburgCombinationStudy.forCombination(facility, date, demandCsvPath, strict,
                combination, ACC_DAMPING_FACTOR, SAFETY_DISTANCE_FACTOR);

        params.set("car." + ParameterTypes.A.getId(), carA);
        params.set("car." + ParameterTypes.S0.getId(), carS0);

        // Recorded so runParams.txt names the cell rather than only carrying the values it derives from.
        params.set(KEY_CAR_A, carA);
        params.set(KEY_CAR_S0, carS0);
        return params;
    }
}
