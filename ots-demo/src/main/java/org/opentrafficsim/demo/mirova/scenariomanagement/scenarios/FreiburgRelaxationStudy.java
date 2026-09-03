package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 * A relaxation that actually runs, against the one that did not.
 * <p>
 * Instrumenting the mechanism showed that it had never been doing what it was written to do. Counted inside the model
 * over a congested hour, two thirds of all relaxations ended because the leader fell below 10 km/h, after a mean of
 * 0.28 s, and were then recreated on the next tick and killed again - 118 000 creations in one hour to sustain
 * relaxation on a tenth of the car-following calls. A further defect made the survivors linger: the housekeeping
 * collected a state only once its buffer fell below an absolute 0.1 m, so a 10 m deficit ran for 92 s under a
 * mechanism declared with a 20 s time constant.
 * </p>
 * <p>
 * Both are corrected. Ending a relaxation now fades its buffer to zero over {@code RELAXATION_FADE_DURATION} rather
 * than discarding it between two ticks - which used to drop the perceived distance by a mean of 2.2 m in congestion
 * and 9.2 m in free flow, in over 99 % of aborts, a braking impulse the model inflicted on itself - and the lifetime
 * is bounded at {@code RELAXATION_MAX_LIFETIME_FACTOR} times tau_s.
 * </p>
 * <p>
 * Measured on one congested hour, that takes creations from 118 000 to 21 000, the share of endings that are natural
 * from 8 % to 56 %, the natural lifetime from 60 s to 21 s, and the share of car-following calls running with an
 * active relaxation from 9.9 % to 32.8 %. What it does to breakdown behaviour is what this study asks, since a single
 * run says nothing about that.
 * </p>
 * <h3>The rows</h3>
 * <p>
 * {@code legacy} sets the fade to zero and the lifetime bound out of reach, reproducing the previous behaviour
 * exactly, so the comparison is against a measured baseline rather than against remembered numbers. The other rows
 * vary the fade, which is the correction expected to matter: the lifetime bound mostly removes a long tail that was
 * already down to 5 % of its deficit.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See OpenTrafficSim License.
 * </p>
 * @author Marvin Baumann
 */
public class FreiburgRelaxationStudy implements StudyDefinition
{
    /** Comfortable deceleration, from the production set. */
    public static final double B = FreiburgProductionStudy.B;

    /** Stopped distance of cars, from the production set. */
    public static final double S0_CAR = FreiburgProductionStudy.S0_CAR;

    /** Maximum acceleration of cars, from the production set. */
    public static final double A_CAR = FreiburgProductionStudy.A_CAR;

    /** Lane-change safety distance reduction factor, from the production set. */
    public static final double SAFETY_DISTANCE_FACTOR = FreiburgProductionStudy.SAFETY_DISTANCE_FACTOR;

    /** Relaxation acceleration damping, off. */
    public static final double DAMPING = 1.00;

    /** Desired headway, held at the pair the capacity study settled on. */
    public static final HeadwayCombination HEADWAY = new HeadwayCombination("T100", 1.00, 1.30);

    /** Lifetime bound reproducing the unbounded behaviour, in multiples of tau_s. */
    public static final double LIFETIME_LEGACY = 1000.0;

    /** Lifetime bound of the correction, in multiples of tau_s. */
    public static final double LIFETIME_BOUNDED = 3.0;

    /**
     * The rows: a label, the fade duration in seconds, and the lifetime bound.
     * <p>
     * A fade of zero with an unreachable lifetime bound is the previous behaviour exactly.
     * </p>
     */
    public static final List<double[]> ROWS =
            List.of(new double[] {0.0, LIFETIME_LEGACY}, new double[] {0.0, LIFETIME_BOUNDED},
                    new double[] {1.0, LIFETIME_BOUNDED}, new double[] {2.0, LIFETIME_BOUNDED});

    /** Labels matching {@link #ROWS}. */
    public static final List<String> ROW_LABELS = List.of("legacy", "bounded", "fade1.0", "fade2.0");

    /** Leader decelerations at which a relaxation is abandoned, in m/s^2. */
    public static final List<Double> ABORT_DECELERATIONS = List.of(-1.0, -2.0);

    /** Replications per date if the caller names none. */
    public static final int DEFAULT_REPLICATIONS = FreiburgCapacityStudy.DEFAULT_REPLICATIONS;

    /** Parameter key recording the row. */
    public static final String KEY_ROW = "studyRelaxRow";

    /** Parameter key recording the abort deceleration. */
    public static final String KEY_ABORT = "studyAbortDecel";

    @Override
    public String getName()
    {
        return "relaxation";
    }

    @Override
    public String getDescription()
    {
        return "Relaxation ending " + ROW_LABELS + " x abort deceleration " + ABORT_DECELERATIONS + " m/s2 on T="
                + HEADWAY.carT() + "/" + HEADWAY.truckT() + ": " + (ROWS.size() * ABORT_DECELERATIONS.size())
                + " variations per date, " + DEFAULT_REPLICATIONS + " replications by default.";
    }

    /**
     * Returns the label identifying one cell, used as the suffix of the scenario name.
     * @param row String; the relaxation-ending row
     * @param abortDeceleration double; the leader deceleration abandoning a relaxation
     * @return String; the variant label
     */
    public static String variantLabel(final String row, final double abortDeceleration)
    {
        return String.format(Locale.ROOT, "%s_abort%.1f", row, abortDeceleration);
    }

    /** {@inheritDoc} */
    @Override
    public void register(final ScenarioManager manager, final Map<String, String> options) throws Exception
    {
        TrafficFacility facility = FacilityRegistry.resolve(FreiburgFacility.NAME);

        List<String> dates = DateStudy.resolveDates(options.get("dates"));
        if (dates.isEmpty())
        {
            throw new IllegalArgumentException("Study 'relaxation' requires --dates=<comma-separated-dates|file>.");
        }
        String demandOption = options.get("demand");
        if (demandOption == null || demandOption.trim().isEmpty())
        {
            throw new IllegalArgumentException("Study 'relaxation' requires --demand=<csv file or directory>.");
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
            for (int i = 0; i < ROWS.size(); i++)
            {
                double fadeSeconds = ROWS.get(i)[0];
                double lifetime = ROWS.get(i)[1];
                String rowLabel = ROW_LABELS.get(i);
                for (double abortDeceleration : ABORT_DECELERATIONS)
                {
                    String scenarioName = facility.scenarioName(date, variantLabel(rowLabel, abortDeceleration));
                    manager.addScenario(scenarioName, facility.getGeneratorClass());

                    ScenarioParameters params = FreiburgCombinationStudy.forCombination(facility, date,
                            demandCsvPath, strict, HEADWAY, DAMPING, SAFETY_DISTANCE_FACTOR);
                    params.set("car." + ParameterTypes.B.getId(), B);
                    params.set("truck." + ParameterTypes.B.getId(), B);
                    params.set("car." + ParameterTypes.S0.getId(), S0_CAR);
                    params.set("truck." + ParameterTypes.S0.getId(), 2.0 * S0_CAR);
                    params.set("car." + ParameterTypes.A.getId(), A_CAR);
                    for (String type : new String[] {"car.", "truck."})
                    {
                        params.set(type + MirovaParameters.RELAXATION_FADE_DURATION.getId(),
                                Duration.instantiateSI(fadeSeconds));
                        params.set(type + MirovaParameters.RELAXATION_MAX_LIFETIME_FACTOR.getId(), lifetime);
                        params.set(type + MirovaParameters.RELAXATION_ABORT_DECELERATION.getId(),
                                org.djunits.value.vdouble.scalar.Acceleration.instantiateSI(abortDeceleration));
                    }
                    params.set(KEY_ROW, rowLabel);
                    params.set(KEY_ABORT, abortDeceleration);
                    manager.addParameterVariation(scenarioName, params);
                }
            }
        }
        manager.setReplications(replications);
    }
}
