package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.djunits.value.vdouble.scalar.Acceleration;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.demo.mirova.scenariomanagement.FacilityRegistry;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.StudyDefinition;
import org.opentrafficsim.demo.mirova.scenariomanagement.TrafficFacility;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.FreiburgCombinationStudy.HeadwayCombination;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;

/**
 * Shorter headways crossed with a longer-lived relaxation, against the susceptibility that limits this model.
 * <p>
 * The calibration established that the breakdown is not a capacity exceedance. Demand is <i>still rising</i> when the
 * model collapses - by 7.1 % over the following half hour, in 93 % of runs - while in the field it is already falling
 * by 4.2 %. The site breaks at 94 % of the highest free flow it reaches, the model at 90 %, and at a flow the model
 * demonstrably sustains, since it sustains exactly that flow in the queue afterwards. Both break down through
 * merging; what differs is how loaded the mainline has to be before a merge succeeds in overturning it.
 * </p>
 * <p>
 * That reframes what is worth varying. Capacity is close - the model reaches 3504 veh/h against the site's 3676 - so
 * the target is not more capacity but a mainline that absorbs a merge at flows where it currently does not.
 * </p>
 * <h3>Axis 1: headway, below the range tested so far</h3>
 * <p>
 * The headway study measured a saturating effect on breakdown capacity: 1.10 to 1.00 gained 5.0 %, and 1.00 to 0.90 a
 * further 0.5 %, against the 22 % that a capacity scaling inversely with headway would give. That is what established
 * capacity as not headway-limited. Whether the saturation continues below 0.90 is untested, and there is a second
 * reason to look: raising capacity moves the collapse later along the demand profile, towards the peak where the
 * field's collapse sits. The headway study saw exactly that as a side effect - jam duration fell from 63 to 25 min
 * as the headway shortened - without anyone having asked for it.
 * </p>
 * <h3>Axis 2: the deceleration at which a relaxation is abandoned</h3>
 * <p>
 * The obvious candidate was the relaxation time constant, and measurement ruled it out before a single run. The
 * buffer decays as {@code exp(-t / tau_s)} with {@code tau_s} = 20 s, but most relaxations are discarded long
 * before it runs out. Counted inside the model over one congested hour, 26 % end because the leader braked past the
 * threshold, after a mean of 2.0 s, and 66 % because the leader fell below 10 km/h, after well under a second. Only
 * the 8 % that neither condition touches live their full 60 s. Lengthening {@code tau_s} would extend a curve that
 * almost nothing reaches, so that axis would have measured nothing.
 * </p>
 * <p>
 * The threshold governs the lifetime, so the threshold is the axis. A deceleration of one metre per second squared
 * is ordinary car-following rather than hard braking, and the value was acting as a permanent switch-off instead of
 * the safety abort it was written to be. The levels run to {@code B_CRIT}, the comfort limit the kinematic bounding
 * already uses, so none of them lets a relaxation survive braking the model itself considers uncomfortable.
 * </p>
 * <p>
 * Raising it cannot weaken safety: the relaxation only ever makes a follower <i>more</i> tolerant of a gap it
 * already has, and the physical net acts on the unmodified perception, so it can strengthen a deceleration but never
 * weaken one.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See OpenTrafficSim License.
 * </p>
 * @author Marvin Baumann
 */
public class FreiburgSusceptibilityStudy implements StudyDefinition
{
    /** Comfortable deceleration, from the production set. */
    public static final double B = FreiburgProductionStudy.B;

    /** Stopped distance of cars, from the production set. */
    public static final double S0_CAR = FreiburgProductionStudy.S0_CAR;

    /** Maximum acceleration of cars, from the production set. */
    public static final double A_CAR = FreiburgProductionStudy.A_CAR;

    /** Lane-change safety distance reduction factor, from the production set. */
    public static final double SAFETY_DISTANCE_FACTOR = FreiburgProductionStudy.SAFETY_DISTANCE_FACTOR;

    /** Relaxation acceleration damping, off, as every measurement so far has preferred. */
    public static final double DAMPING = 1.00;

    /** The capacity-drop addon stays off: it lengthened the queue but never turned the sign of the drop. */
    public static final boolean CAPACITY_DROP = false;

    /**
     * Desired headway pairs, truck kept 0.30 s above car.
     * <p>
     * 1.00 / 1.30 is the reference row and reproduces the headway study's cell of the same name; 0.90 / 1.20 is the
     * shortest the campaigns have used so far, and 0.80 / 1.10 extends below all of them.
     * </p>
     */
    public static final List<HeadwayCombination> HEADWAY_COMBINATIONS =
            List.of(new HeadwayCombination("T080", 0.80, 1.10), new HeadwayCombination("T090", 0.90, 1.20),
                    new HeadwayCombination("T100", 1.00, 1.30));

    /**
     * Leader decelerations at which an active relaxation is abandoned, in m/s^2.
     * <p>
     * -1.0 is the framework value and the reference row. -2.0 and -3.5 lengthen the relaxation by letting it survive
     * braking it currently does not; -3.5 is {@code B_CRIT}, the comfort limit the kinematic bounding already uses.
     * </p>
     */
    public static final List<Double> ABORT_DECELERATIONS = List.of(-1.0, -2.0, -3.5);

    /** Replications per date if the caller names none. */
    public static final int DEFAULT_REPLICATIONS = FreiburgCapacityStudy.DEFAULT_REPLICATIONS;

    /** Parameter key recording the headway pair in {@code runParams.txt}. */
    public static final String KEY_HEADWAY = "studyHeadway";

    /** Parameter key recording the abort deceleration. */
    public static final String KEY_ABORT = "studyAbortDecel";

    @Override
    public String getName()
    {
        return "susceptibility";
    }

    @Override
    public String getDescription()
    {
        return "Headway pairs " + HEADWAY_COMBINATIONS.size() + " x relaxation abort deceleration "
                + ABORT_DECELERATIONS + " m/s2: "
                + (HEADWAY_COMBINATIONS.size() * ABORT_DECELERATIONS.size()) + " variations per date, "
                + DEFAULT_REPLICATIONS + " replications by default.";
    }

    /**
     * Returns the label identifying one cell, used as the suffix of the scenario name.
     * @param combination HeadwayCombination; the headway pair
     * @param abortDeceleration double; the leader deceleration abandoning a relaxation
     * @return String; the variant label
     */
    public static String variantLabel(final HeadwayCombination combination, final double abortDeceleration)
    {
        return String.format(Locale.ROOT, "%s_abort%.1f", combination.label(), abortDeceleration);
    }

    /** {@inheritDoc} */
    @Override
    public void register(final ScenarioManager manager, final Map<String, String> options) throws Exception
    {
        TrafficFacility facility = FacilityRegistry.resolve(FreiburgFacility.NAME);

        List<String> dates = DateStudy.resolveDates(options.get("dates"));
        if (dates.isEmpty())
        {
            throw new IllegalArgumentException("Study 'susceptibility' requires --dates=<comma-separated-dates|file>.");
        }
        String demandOption = options.get("demand");
        if (demandOption == null || demandOption.trim().isEmpty())
        {
            throw new IllegalArgumentException("Study 'susceptibility' requires --demand=<csv file or directory>.");
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
            for (HeadwayCombination combination : HEADWAY_COMBINATIONS)
            {
                for (double abortDeceleration : ABORT_DECELERATIONS)
                {
                    String scenarioName = facility.scenarioName(date, variantLabel(combination, abortDeceleration));
                    manager.addScenario(scenarioName, facility.getGeneratorClass());

                    ScenarioParameters params = FreiburgCombinationStudy.forCombination(facility, date,
                            demandCsvPath, strict, combination, DAMPING, SAFETY_DISTANCE_FACTOR);
                    params.set("car." + ParameterTypes.B.getId(), B);
                    params.set("truck." + ParameterTypes.B.getId(), B);
                    params.set("car." + ParameterTypes.S0.getId(), S0_CAR);
                    params.set("truck." + ParameterTypes.S0.getId(), 2.0 * S0_CAR);
                    params.set("car." + ParameterTypes.A.getId(), A_CAR);
                    for (String type : new String[] {"car.", "truck."})
                    {
                        params.set(type + MirovaParameters.CAPACITY_DROP_ENABLED.getId(), CAPACITY_DROP);
                        params.set(type + MirovaParameters.RELAXATION_ABORT_DECELERATION.getId(),
                                Acceleration.instantiateSI(abortDeceleration));
                    }
                    params.set(KEY_HEADWAY, combination.label());
                    params.set(KEY_ABORT, abortDeceleration);
                    manager.addParameterVariation(scenarioName, params);
                }
            }
        }
        manager.setReplications(replications);
    }
}
