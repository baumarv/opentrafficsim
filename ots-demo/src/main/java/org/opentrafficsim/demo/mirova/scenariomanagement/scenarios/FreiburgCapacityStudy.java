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
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;

/**
 * Shorter desired headways on the calibrated congested-branch set, to close the breakdown-capacity deficit.
 * <p>
 * The production run settled which part of the capacity behaviour the calibration reproduces and which it does not.
 * Measured over 270 runs on nine days, three capacity notions disagree:
 * </p>
 * <table>
 * <caption>Production run against the field</caption>
 * <tr><th>quantity</th><th>simulation</th><th>field</th><th>deviation</th></tr>
 * <tr><td>flow before breakdown</td><td>2973 +/- 36</td><td>3456</td><td>-14.0 %</td></tr>
 * <tr><td>95th percentile of free-flow intervals</td><td>3050 +/- 13</td><td>3336</td><td>-8.6 %</td></tr>
 * <tr><td>queue discharge</td><td>3186 +/- 11</td><td>3115</td><td>+2.3 %</td></tr>
 * <tr><td>capacity drop</td><td>-8.2 %</td><td>+9.1 %</td><td>sign inverted</td></tr>
 * </table>
 * <p>
 * The discharge matches, the breakdown capacity does not, and the capacity drop has the wrong sign: the model flows
 * <i>more</i> during the jam than before it, where a bottleneck must do the opposite. The agreement on discharge is
 * partly an artefact of the other two - the breakdown triggers at too low a flow, and the demand still rising
 * afterwards lifts the discharge into the right range.
 * </p>
 * <p>
 * <b>Desired headway is the direct lever on the two quantities that are short.</b> The sensitivity screen also found
 * a shorter headway to raise jam speed rather than lower it - {@code T} = 1.00 / 1.30 gave 34.1 km/h against 28.7 at
 * 1.10 / 1.40 - so it moves both open quantities the same way.
 * </p>
 * <p>
 * <b>What this cannot fix, and the study should not be read as attempting:</b> the false breakdown on 2025-09-22.
 * That date carries the second-highest demand peak of the nine, 4584 veh/h against 4596 on the highest, and higher
 * than seven of the eight dates that did break down. No capacity level separates it: raising capacity far enough to
 * keep it free removes the breakdown from nearly every other date too. Its staying free is a realization of a
 * stochastic capacity, not a threshold the model is missing.
 * </p>
 * <p>
 * <b>Damping is carried as a control, not as a candidate.</b> The screen measured 0.90 against 1.00: ramp standstills
 * 717 against 432, stop-and-go 19.6 % against 13.2 %, jam speed unchanged. It degrades the quantity under the
 * standing constraint without improving any target. It is in the grid so that its interaction with a shorter headway
 * is on record, since the two were never varied together.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See OpenTrafficSim License.
 * </p>
 * @author Marvin Baumann
 */
public class FreiburgCapacityStudy implements StudyDefinition
{
    /** Comfortable deceleration, from the production set. */
    public static final double B = FreiburgProductionStudy.B;

    /** Stopped distance of cars, from the production set. */
    public static final double S0_CAR = FreiburgProductionStudy.S0_CAR;

    /** Maximum acceleration of cars, from the production set. */
    public static final double A_CAR = FreiburgProductionStudy.A_CAR;

    /** Lane-change safety distance reduction factor, from the production set. */
    public static final double SAFETY_DISTANCE_FACTOR = FreiburgProductionStudy.SAFETY_DISTANCE_FACTOR;

    /**
     * Desired headway pairs, truck kept 0.30 s above car as in every campaign so far.
     * <p>
     * 1.10 / 1.40 is the production set and serves as the reference row; the two shorter pairs bracket the deficit,
     * which at roughly 9 to 14 % of capacity corresponds to a headway some 0.1 to 0.15 s shorter if capacity scaled
     * inversely with headway alone. It does not scale that cleanly, which is why two levels are tested rather than
     * one computed.
     * </p>
     */
    public static final List<HeadwayCombination> HEADWAY_COMBINATIONS =
            List.of(new HeadwayCombination("T090", 0.90, 1.20), new HeadwayCombination("T100", 1.00, 1.30),
                    new HeadwayCombination("T110", 1.10, 1.40));

    /** Relaxation acceleration damping: 1.00 disables it, 0.90 is the control row. */
    public static final List<Double> ACC_DAMPING_FACTORS = List.of(0.90, 1.00);

    /** Parameter key recording the headway pair in {@code runParams.txt}. */
    public static final String KEY_HEADWAY = "studyHeadway";

    /** Parameter key recording the damping factor. */
    public static final String KEY_DAMPING = "studyDamping";

    @Override
    public String getName()
    {
        return "capacity";
    }

    @Override
    public String getDescription()
    {
        return "Headway pairs " + HEADWAY_COMBINATIONS.size() + " x damping " + ACC_DAMPING_FACTORS
                + " on the production congested-branch set (b=" + B + ", s0=" + S0_CAR + ", a=" + A_CAR + "): "
                + (HEADWAY_COMBINATIONS.size() * ACC_DAMPING_FACTORS.size()) + " variations per date.";
    }

    /**
     * Returns the label identifying one cell, used as the suffix of the scenario name.
     * @param combination HeadwayCombination; the headway pair
     * @param damping double; the relaxation acceleration damping factor
     * @return String; the variant label
     */
    public static String variantLabel(final HeadwayCombination combination, final double damping)
    {
        return String.format(Locale.ROOT, "%s_adamp%.2f", combination.label(), damping);
    }

    /** {@inheritDoc} */
    @Override
    public void register(final ScenarioManager manager, final Map<String, String> options) throws Exception
    {
        TrafficFacility facility = FacilityRegistry.resolve(FreiburgFacility.NAME);

        List<String> dates = DateStudy.resolveDates(options.get("dates"));
        if (dates.isEmpty())
        {
            throw new IllegalArgumentException("Study 'capacity' requires --dates=<comma-separated-dates|file>.");
        }
        String demandOption = options.get("demand");
        if (demandOption == null || demandOption.trim().isEmpty())
        {
            throw new IllegalArgumentException("Study 'capacity' requires --demand=<csv file or directory>.");
        }
        File demandLocation = new File(demandOption.trim());
        String pattern = options.getOrDefault("pattern", DateStudy.DEFAULT_CSV_PATTERN);
        boolean strict = Boolean.parseBoolean(options.getOrDefault("strict", "false"));
        int replications = Integer.parseInt(
                options.getOrDefault("replications", String.valueOf(DateStudy.DEFAULT_REPLICATIONS)));

        Map<String, File> demandPerDate = DateStudy.resolveDemandCsvs(dates, demandLocation, pattern, strict);

        for (String date : dates)
        {
            String demandCsvPath = demandPerDate.get(date).getAbsolutePath();
            for (HeadwayCombination combination : HEADWAY_COMBINATIONS)
            {
                for (double damping : ACC_DAMPING_FACTORS)
                {
                    String scenarioName = facility.scenarioName(date, variantLabel(combination, damping));
                    manager.addScenario(scenarioName, facility.getGeneratorClass());

                    ScenarioParameters params = FreiburgCombinationStudy.forCombination(facility, date,
                            demandCsvPath, strict, combination, damping, SAFETY_DISTANCE_FACTOR);
                    params.set("car." + ParameterTypes.B.getId(), B);
                    params.set("truck." + ParameterTypes.B.getId(), B);
                    params.set("car." + ParameterTypes.S0.getId(), S0_CAR);
                    params.set("truck." + ParameterTypes.S0.getId(), 2.0 * S0_CAR);
                    params.set("car." + ParameterTypes.A.getId(), A_CAR);
                    params.set("car." + MirovaParameters.RELAXATION_ACC_DAMPING_FACTOR.getId(), damping);
                    params.set("truck." + MirovaParameters.RELAXATION_ACC_DAMPING_FACTOR.getId(), damping);
                    params.set(KEY_HEADWAY, combination.label());
                    params.set(KEY_DAMPING, damping);
                    manager.addParameterVariation(scenarioName, params);
                }
            }
        }
        manager.setReplications(replications);
    }
}
