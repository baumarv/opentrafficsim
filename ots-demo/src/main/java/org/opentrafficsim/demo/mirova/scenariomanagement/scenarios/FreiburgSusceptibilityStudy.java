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
 * Shorter headways crossed with slower relaxation, against the susceptibility that limits this model.
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
 * <h3>Axis 2: relaxation time constants</h3>
 * <p>
 * After a cut-in the follower carries a virtual space buffer that decays as
 * {@code initialDeficit * exp(-t / tau_s)}. At the framework default of 20 s the buffer is down to 37 % after twenty
 * seconds and 5 % after a minute: the follower demands its full gap back within about a minute, decelerates to get
 * it, and that deceleration is what travels upstream. A longer time constant means the follower tolerates the short
 * gap for longer, which is the most direct handle on susceptibility the model has, and it has never been varied.
 * </p>
 * <p>
 * Both constants scale together, keeping the ratio Keane &amp; Gao report rather than making the spatial and speed
 * relaxation independent axes - which would double the grid for a distinction the field data cannot resolve.
 * </p>
 * <p>
 * Lengthening them is safe by construction and does not weaken the physical net: the relaxation only ever makes a
 * follower <i>more</i> tolerant of a gap it already has, {@code MirovaCarFollowingUtil} aborts it outright when the
 * leader brakes hard, and the physical net acts on the unmodified perception and can only strengthen a deceleration.
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
     * 1.00 / 1.30 is the reference row and reproduces the headway study's cell of the same name. The two shorter
     * pairs extend below everything tested so far; 0.90 / 1.20 is the shortest the campaigns have used.
     * </p>
     */
    public static final List<HeadwayCombination> HEADWAY_COMBINATIONS =
            List.of(new HeadwayCombination("T070", 0.70, 1.00), new HeadwayCombination("T080", 0.80, 1.10),
                    new HeadwayCombination("T100", 1.00, 1.30));

    /**
     * Multipliers on both relaxation time constants, whose defaults are 20 s spatial and 8 s speed.
     * <p>
     * 1.0 is the reference row. 2.0 and 3.0 give a spatial constant of 40 and 60 s, so the follower still tolerates
     * roughly two thirds of the gap deficit after 20 and 25 seconds respectively, where at the default it is down to
     * a third. Field estimates of relaxation put it in the tens of seconds, so neither level leaves the range the
     * phenomenon is reported in.
     * </p>
     */
    public static final List<Double> TAU_FACTORS = List.of(1.0, 2.0, 3.0);

    /** Framework default of the spatial relaxation time constant, in seconds. */
    public static final double TAU_SPACE_BASE = 20.0;

    /** Framework default of the speed relaxation time constant, in seconds. */
    public static final double TAU_SPEED_BASE = 8.0;

    /** Replications per date if the caller names none. */
    public static final int DEFAULT_REPLICATIONS = FreiburgCapacityStudy.DEFAULT_REPLICATIONS;

    /** Parameter key recording the headway pair in {@code runParams.txt}. */
    public static final String KEY_HEADWAY = "studyHeadway";

    /** Parameter key recording the relaxation multiplier. */
    public static final String KEY_TAU = "studyTauFactor";

    @Override
    public String getName()
    {
        return "susceptibility";
    }

    @Override
    public String getDescription()
    {
        return "Headway pairs " + HEADWAY_COMBINATIONS.size() + " x relaxation time constants " + TAU_FACTORS
                + " x (" + TAU_SPACE_BASE + " s, " + TAU_SPEED_BASE + " s): "
                + (HEADWAY_COMBINATIONS.size() * TAU_FACTORS.size()) + " variations per date, "
                + DEFAULT_REPLICATIONS + " replications by default.";
    }

    /**
     * Returns the label identifying one cell, used as the suffix of the scenario name.
     * @param combination HeadwayCombination; the headway pair
     * @param tauFactor double; the multiplier on both relaxation time constants
     * @return String; the variant label
     */
    public static String variantLabel(final HeadwayCombination combination, final double tauFactor)
    {
        return String.format(Locale.ROOT, "%s_tau%.1f", combination.label(), tauFactor);
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
                for (double tauFactor : TAU_FACTORS)
                {
                    String scenarioName = facility.scenarioName(date, variantLabel(combination, tauFactor));
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
                        params.set(type + MirovaParameters.RELAXATION_TAU_SPACE.getId(),
                                Duration.instantiateSI(tauFactor * TAU_SPACE_BASE));
                        params.set(type + MirovaParameters.RELAXATION_TAU_SPEED.getId(),
                                Duration.instantiateSI(tauFactor * TAU_SPEED_BASE));
                    }
                    params.set(KEY_HEADWAY, combination.label());
                    params.set(KEY_TAU, tauFactor);
                    manager.addParameterVariation(scenarioName, params);
                }
            }
        }
        manager.setReplications(replications);
    }
}
