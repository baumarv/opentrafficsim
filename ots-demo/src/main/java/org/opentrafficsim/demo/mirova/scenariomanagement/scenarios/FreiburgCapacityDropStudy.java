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
 * The capacity-drop addon, on the headway that breaks down at the right flow.
 * <p>
 * The capacity study left one quantity clearly wrong and identified why. At {@code T} = 1.00 / 1.30 the model breaks
 * down at roughly the right flow but recovers far too quickly: 35 minutes of queue against an empirical 78. Across
 * the headway levels, discharge and duration are tightly and non-linearly coupled - 3191 veh/h gives 63 min, 3282
 * gives 35, 3398 gives 25 - because after the peak the demand sits only just above capacity, so a few percent of
 * extra discharge drains the queue disproportionately fast.
 * </p>
 * <p>
 * Lengthening the queue therefore means <b>lowering the discharge without lowering the breakdown capacity</b>. That
 * is the capacity drop, and it is the one quantity of the whole calibration whose sign is wrong: the field drops
 * from 3456 before breakdown to 3115 in discharge, -9.9 %, while every cell measured so far <i>gains</i> between 4.8
 * and 8.4 %.
 * </p>
 * <h3>Why not damping</h3>
 * <p>
 * Relaxation damping scales positive accelerations after a cut-in. It does not lower the equilibrium discharge rate;
 * it delays individual vehicles in resuming speed. The queue does lengthen, but through disturbance rather than
 * through capacity, and the disturbance appears as stop-and-go and ramp standstills - the quantity under the
 * standing constraint. Measured as a control row in the capacity study, damping 0.90 was worse than 1.00 in every
 * pairing: lower pre-breakdown flow, worse specificity, lower jam speed.
 * </p>
 * <h3>Both axes are relative</h3>
 * <p>
 * The mechanism applies {@code T_eff = T * (1 + f_T * alpha)} with
 * {@code alpha = max(0, (f_v * v_desired - v) / (f_v * v_desired))}, in
 * {@code MirovaIdmPlus.combineInteractionTerm} - the only place the desired speed the threshold is defined against
 * is available, the desired-headway model being handed no more than the parameters and the current speed.
 * </p>
 * <p>
 * The threshold has to be an axis rather than a constant either way: the framework default of the absolute
 * {@code V_CRIT_DISCHARGE} is 40 km/h and the jam at {@code T} = 1.00 / 1.30 runs at 48.3, so at the default the
 * mechanism would be inert and this study would measure nothing.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See OpenTrafficSim License.
 * </p>
 * @author Marvin Baumann
 */
public class FreiburgCapacityDropStudy implements StudyDefinition
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
     * Desired headway, fixed at the pair the capacity study settled on.
     * <p>
     * 1.00 / 1.30 breaks down at a pre-breakdown flow of 3158 against an empirical 3456 and at a jam speed of 48.3
     * against 44.6, both closer than the production 1.10 / 1.40 managed. It is the duration that this study is for.
     * </p>
     */
    public static final HeadwayCombination HEADWAY = new HeadwayCombination("T100", 1.00, 1.30);

    /** Relaxation acceleration damping, off, as every measurement so far has preferred. */
    public static final double DAMPING = 1.00;

    /**
     * Additional desired headway during congested discharge, as a fraction of the vehicle's own {@code T}.
     * <p>
     * Relative rather than a value in seconds: 0.4 s is a 40 % increase on a car at {@code T} = 1.00 and 31 % on a
     * truck at 1.30, so an absolute addon would produce a capacity drop that differs by vehicle type without anyone
     * having chosen that. As a fraction the level states what it does - 0.2 widens the queue headway by a fifth.
     * </p>
     * <p>
     * Zero is the reference row and reproduces the capacity study's T100 cell exactly, so the effect is measured
     * against a cell whose other numbers are already known rather than against an assumption.
     * </p>
     */
    public static final List<Double> ADDON_FRACTIONS = List.of(0.0, 0.2, 0.4);

    /**
     * Speed below which the addon ramps in, as a fraction of the vehicle's own desired speed.
     * <p>
     * A car wanting 130 km/h reaches the ramp at 65 or 91 km/h, a truck wanting 80 at 40 or 56 - which is the point.
     * An absolute threshold would treat both as being in the same traffic state at the same speed, and would also
     * sit awkwardly against the measurement, since a detector's jam speed is a harmonic mean over the cross-section
     * with individual vehicles above and below it.
     * </p>
     * <p>
     * Both levels put the 48.3 km/h jam this configuration produces inside the ramp for cars; the higher one also
     * catches the faster jams the lighter days produce.
     * </p>
     */
    public static final List<Double> V_CRIT_FRACTIONS = List.of(0.5, 0.7);

    /** Replications per date if the caller names none; matches the capacity study. */
    public static final int DEFAULT_REPLICATIONS = FreiburgCapacityStudy.DEFAULT_REPLICATIONS;

    /** Parameter key recording the addon in {@code runParams.txt}. */
    public static final String KEY_ADDON = "studyAddon";

    /** Parameter key recording the ramp threshold. */
    public static final String KEY_VCRIT = "studyVCrit";

    @Override
    public String getName()
    {
        return "capdrop";
    }

    @Override
    public String getDescription()
    {
        return "Capacity-drop addon " + ADDON_FRACTIONS + " x T, ramp threshold " + V_CRIT_FRACTIONS
                + " x desired speed, on T="
                + HEADWAY.carT() + "/" + HEADWAY.truckT() + ": "
                + (ADDON_FRACTIONS.size() * V_CRIT_FRACTIONS.size()) + " variations per date, "
                + DEFAULT_REPLICATIONS + " replications by default.";
    }

    /**
     * Returns the label identifying one cell, used as the suffix of the scenario name.
     * @param addon double; the additional headway as a fraction of T
     * @param vCrit double; the ramp threshold as a fraction of the desired speed
     * @return String; the variant label
     */
    public static String variantLabel(final double addon, final double vCrit)
    {
        return String.format(Locale.ROOT, "fT%.1f_fV%.1f", addon, vCrit);
    }

    /** {@inheritDoc} */
    @Override
    public void register(final ScenarioManager manager, final Map<String, String> options) throws Exception
    {
        TrafficFacility facility = FacilityRegistry.resolve(FreiburgFacility.NAME);

        List<String> dates = DateStudy.resolveDates(options.get("dates"));
        if (dates.isEmpty())
        {
            throw new IllegalArgumentException("Study 'capdrop' requires --dates=<comma-separated-dates|file>.");
        }
        String demandOption = options.get("demand");
        if (demandOption == null || demandOption.trim().isEmpty())
        {
            throw new IllegalArgumentException("Study 'capdrop' requires --demand=<csv file or directory>.");
        }
        File demandLocation = new File(demandOption.trim());
        String pattern = options.getOrDefault("pattern", DateStudy.DEFAULT_CSV_PATTERN);
        boolean strict = Boolean.parseBoolean(options.getOrDefault("strict", "false"));
        int replications = Integer.parseInt(
                options.getOrDefault("replications", String.valueOf(DEFAULT_REPLICATIONS)));

        Map<String, File> demandPerDate = DateStudy.resolveDemandCsvs(dates, demandLocation, pattern, strict);

        for (String date : dates)
        {
            String demandCsvPath = demandPerDate.get(date).getAbsolutePath();
            for (double addon : ADDON_FRACTIONS)
            {
                for (double vCrit : V_CRIT_FRACTIONS)
                {
                    String scenarioName = facility.scenarioName(date, variantLabel(addon, vCrit));
                    manager.addScenario(scenarioName, facility.getGeneratorClass());

                    ScenarioParameters params = FreiburgCombinationStudy.forCombination(facility, date,
                            demandCsvPath, strict, HEADWAY, DAMPING, SAFETY_DISTANCE_FACTOR);
                    params.set("car." + ParameterTypes.B.getId(), B);
                    params.set("truck." + ParameterTypes.B.getId(), B);
                    params.set("car." + ParameterTypes.S0.getId(), S0_CAR);
                    params.set("truck." + ParameterTypes.S0.getId(), 2.0 * S0_CAR);
                    params.set("car." + ParameterTypes.A.getId(), A_CAR);

                    // The zero row stays with the mechanism switched off rather than enabled at zero addon, so it
                    // reproduces the capacity study's T100 cell bit for bit and can be checked against it.
                    boolean enabled = addon > 0.0;
                    for (String type : new String[] {"car.", "truck."})
                    {
                        params.set(type + MirovaParameters.CAPACITY_DROP_ENABLED.getId(), enabled);
                        params.set(type + MirovaParameters.T_DISCHARGE_FRACTION.getId(), addon);
                        params.set(type + MirovaParameters.V_CRIT_DISCHARGE_FRACTION.getId(), vCrit);
                    }
                    params.set(KEY_ADDON, addon);
                    params.set(KEY_VCRIT, vCrit);
                    manager.addParameterVariation(scenarioName, params);
                }
            }
        }
        manager.setReplications(replications);
    }
}
