package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.util.List;
import java.util.Map;

import org.djunits.value.vdouble.scalar.Acceleration;
import org.djunits.value.vdouble.scalar.Duration;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.demo.mirova.scenariomanagement.FacilityRegistry;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.StudyDefinition;
import org.opentrafficsim.demo.mirova.scenariomanagement.TrafficFacility;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.FreiburgCombinationStudy.HeadwayCombination;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;

/**
 * The ensemble the publication reports: one parameter set, every study date, fifty seeds.
 * <p>
 * This study varies nothing. Its purpose is a sample large enough to quote from - fifty seeds per date give a Wilson
 * half-width of about 0.13 on the breakdown rate and some 14 % on jam duration, against 0.30 and 36 % at the seven
 * seeds the calibration campaigns began with.
 * </p>
 * <h3>Why this headway</h3>
 * <p>
 * The choice between 1.00 / 1.30 and the earlier production 1.10 / 1.40 is a genuine trade, and it turns on which
 * quantities the report leads with. Measured against the field:
 * </p>
 * <table>
 * <caption>The two candidates against the field</caption>
 * <tr><th>quantity</th><th>field</th><th>1.00 / 1.30</th><th>1.10 / 1.40</th></tr>
 * <tr><td>capacity at the GMM threshold</td><td>3500 +/- 326</td><td>3210 (-8.3 %)</td><td>3085 (-11.8 %)</td></tr>
 * <tr><td>Van Aerde capacity</td><td>3465</td><td>3500 (+1.0 %)</td><td>3371 (-2.7 %)</td></tr>
 * <tr><td>jam speed</td><td>44.6</td><td>47.0 (+5 %)</td><td>37.2 (-17 %)</td></tr>
 * <tr><td>queue discharge</td><td>3115</td><td>3299 (+5.9 %)</td><td>3186 (+2.3 %)</td></tr>
 * <tr><td>jam duration</td><td>78 min</td><td>33 (-58 %)</td><td>64 (-18 %)</td></tr>
 * <tr><td>breakdown on the eight jam days</td><td>8 of 8</td><td>72 %</td><td>90 %</td></tr>
 * </table>
 * <p>
 * 1.00 / 1.30 wins on both capacity measures and on jam speed, 1.10 / 1.40 on the jam event - whether it happens,
 * how long it lasts, at what discharge. This set follows the capacities, which is the decision taken.
 * </p>
 * <h3>The relaxation runs corrected</h3>
 * <p>
 * Instrumenting the mechanism showed it had not been running: two thirds of relaxations ended within 0.28 s because
 * the leader fell below 10 km/h and were recreated on the next tick, sustaining relaxation on a tenth of the
 * car-following calls. Reporting results as "with Keane and Gao relaxation" on that basis would not be defensible.
 * With the fade and the bounded lifetime it is a third of calls, and 56 % of relaxations run to their natural end.
 * The correction was measured over 576 runs as neutral on every target quantity, so previous campaigns remain
 * comparable.
 * </p>
 * <h3>What this set does not do</h3>
 * <p>
 * Stated here so it is reported rather than discovered: the jam lasts around a third of the observed duration, the
 * day the site stayed free breaks down in most runs, the breakdown lands at about 90 % of the model's own highest
 * free flow against the field's 94 %, and demand is still rising when it does, where in the field it is already
 * falling.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See OpenTrafficSim License.
 * </p>
 * @author Marvin Baumann
 */
public class FreiburgFinalStudy implements StudyDefinition
{
    /** Desired headway, the pair the capacity measures selected. */
    public static final HeadwayCombination HEADWAY = new HeadwayCombination("T100", 1.00, 1.30);

    /** Comfortable deceleration of both vehicle types, in m/s^2. */
    public static final double B = FreiburgProductionStudy.B;

    /** Stopped distance of cars, in m. */
    public static final double S0_CAR = FreiburgProductionStudy.S0_CAR;

    /** Maximum acceleration of cars, in m/s^2. */
    public static final double A_CAR = FreiburgProductionStudy.A_CAR;

    /** Lane-change safety distance reduction factor. */
    public static final double SAFETY_DISTANCE_FACTOR = FreiburgProductionStudy.SAFETY_DISTANCE_FACTOR;

    /** Relaxation acceleration damping; 1.00 disables it. */
    public static final double DAMPING = 1.00;

    /** The capacity-drop addon stays off: it lengthened the queue but never turned the sign of the drop. */
    public static final boolean CAPACITY_DROP = false;

    /** Fade-out duration when a relaxation is abandoned, in s. */
    public static final double RELAXATION_FADE_SECONDS = 1.0;

    /** Longest a relaxation may run, in multiples of tau_s. */
    public static final double RELAXATION_MAX_LIFETIME = 3.0;

    /** Leader deceleration abandoning a relaxation, in m/s^2. */
    public static final double RELAXATION_ABORT = -1.0;

    /**
     * Replications per date if the caller names none.
     * <p>
     * Fifty rather than the thirty of the first production run: the quantities this ensemble is quoted for include
     * the breakdown rate, and at thirty its Wilson half-width is 0.17, wide enough that the difference from the
     * field's eight days in nine would not be resolvable.
     * </p>
     */
    public static final int DEFAULT_REPLICATIONS = 50;

    /** The label of the single variation. */
    public static final String VARIANT_LABEL = "final";

    @Override
    public String getName()
    {
        return "final";
    }

    @Override
    public String getDescription()
    {
        return "Final ensemble: T=" + HEADWAY.carT() + "/" + HEADWAY.truckT() + ", b=" + B + ", s0=" + S0_CAR
                + ", a=" + A_CAR + ", damping off, corrected relaxation, over every study date, "
                + DEFAULT_REPLICATIONS + " replications by default.";
    }

    /** {@inheritDoc} */
    @Override
    public void register(final ScenarioManager manager, final Map<String, String> options) throws Exception
    {
        TrafficFacility facility = FacilityRegistry.resolve(FreiburgFacility.NAME);

        List<String> dates = DateStudy.resolveDates(options.get("dates"));
        if (dates.isEmpty())
        {
            throw new IllegalArgumentException("Study 'final' requires --dates=<comma-separated-dates|file>.");
        }
        String demandOption = options.get("demand");
        if (demandOption == null || demandOption.trim().isEmpty())
        {
            throw new IllegalArgumentException("Study 'final' requires --demand=<csv file or directory>.");
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
            String scenarioName = facility.scenarioName(date, VARIANT_LABEL);
            manager.addScenario(scenarioName, facility.getGeneratorClass());

            ScenarioParameters params = FreiburgCombinationStudy.forCombination(facility, date, demandCsvPath,
                    strict, HEADWAY, DAMPING, SAFETY_DISTANCE_FACTOR);
            params.set("car." + ParameterTypes.B.getId(), B);
            params.set("truck." + ParameterTypes.B.getId(), B);
            params.set("car." + ParameterTypes.S0.getId(), S0_CAR);
            params.set("truck." + ParameterTypes.S0.getId(), 2.0 * S0_CAR);
            params.set("car." + ParameterTypes.A.getId(), A_CAR);
            for (String type : new String[] {"car.", "truck."})
            {
                params.set(type + MirovaParameters.CAPACITY_DROP_ENABLED.getId(), CAPACITY_DROP);
                params.set(type + MirovaParameters.RELAXATION_FADE_DURATION.getId(),
                        Duration.instantiateSI(RELAXATION_FADE_SECONDS));
                params.set(type + MirovaParameters.RELAXATION_MAX_LIFETIME_FACTOR.getId(),
                        RELAXATION_MAX_LIFETIME);
                params.set(type + MirovaParameters.RELAXATION_ABORT_DECELERATION.getId(),
                        Acceleration.instantiateSI(RELAXATION_ABORT));
            }
            manager.addParameterVariation(scenarioName, params);
        }
        manager.setReplications(replications);
    }
}
