package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import org.djunits.value.vdouble.scalar.Acceleration;
import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.demo.mirova.scenariomanagement.FacilityRegistry;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioGenerator;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.StudyDefinition;
import org.opentrafficsim.demo.mirova.scenariomanagement.TrafficFacility;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;

/**
 * The second screen: the car-following triple, the follower band and truck acceleration, each at two headways.
 * <p>
 * Registered as {@code --study=tamascreen2}. Follows {@code tamascreen}, whose result was that none of the axes it
 * screened -- {@code vGain}, {@code tau_relax_s}, {@code fGap}, cooperation -- improves the model's discrimination
 * between a day the site broke down on and a day it did not.
 * </p>
 * <h3>Why these axes</h3>
 * <p>
 * The defect the first screen measured is not on those axes. On both congested days the pre-breakdown flow is
 * 390-500 veh/h short of the field while the discharge is right, which is the documented "reaches the right
 * breakdown flow and fails to hold it" fault. The parameters that govern how densely vehicles run at high flow are
 * the headway and the car-following triple, and those are what this screens.
 * </p>
 * <ul>
 * <li><b>{@code T}</b> at {@code standard} (1.00/1.30) and {@code tighter} (0.90/1.20). The first screen ran at the
 * settled 1.10/1.40, and its false-breakdown rate of ~100 % extends the measured monotone series of
 * {@code validation_v1} (tightest 10 %, tighter 30 %, standard 60 %) by a fourth point.</li>
 * <li><b>{@code b}, {@code s0}, {@code a}</b> -- gridded <i>together</i> in the original calibration and therefore
 * jointly fitted. Moving two of them at a corrected {@code vGain} and a new headway while holding the third would
 * measure them at a point the third no longer suits.</li>
 * <li><b>The follower band</b> {@code bFollowerMin}/{@code bFollowerMax}, moved as a pair because it is one
 * interpolation ramp over the desire above {@code dMand} and neither bound is meaningful alone.</li>
 * <li><b>Truck acceleration.</b> The share of heavy vehicles is a capacity lever at a merge, and the fault is a
 * capacity fault. 0.7 m/s2 is the value measured in a jam; 1.0 is the midpoint to the 1.25 in use.</li>
 * </ul>
 * <h3>Why every axis runs at both headways</h3>
 * <p>
 * This is not one-at-a-time with a headway study beside it. Each axis is measured at <b>both</b> headways, which
 * costs the same and additionally answers whether an axis behaves differently at the tighter one. That coupling is
 * documented for {@code T} and is the reason the first screen could not see it.
 * </p>
 * <h3>Held fixed, on measurements from the first screen</h3>
 * <p>
 * {@code vGain} 15/30 (the only monotone axis there: ramp standstills 2.2 -&gt; 5.0 % over 15/30/54/69.6, so the
 * corrected value is also the best of the four), {@code tau_relax_s} 20 (flat on every metric -- that axis is
 * spent), {@code fGap} 0.40 (every increase worsened both standstills and free-flow capacity), cooperation at its
 * defaults, and the relaxation damping at 1.0 by decision.
 * </p>
 * <pre>
 *   --study=tamascreen2 --output=&lt;dir&gt; --dates=2025-09-22,2025-09-23,2025-10-07 \
 *   --demand=&lt;dir&gt; --replications=10 --count
 * </pre>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class TamaHeadwayScreeningStudy implements StudyDefinition
{
    /** Registered study name. */
    public static final String NAME = "tamascreen2";

    /** Label of the MiRoVA reference cell, which exists once per headway. */
    public static final String REFERENCE_LABEL = "mirovaref";

    /** Label of the unchanged cell of a headway. */
    public static final String BASE_LABEL = "base";

    /** Replications per cell per day. */
    public static final int DEFAULT_REPLICATIONS = 10;

    /** The two headways, taken from the study that defines them rather than restated. */
    private static final List<FreiburgCombinationStudy.HeadwayCombination> HEADWAYS =
            FreiburgCombinationStudy.COMBINATIONS;

    /** Label of the headway combination the validation parameter set was frozen at. */
    public static final String STANDARD_HEADWAY_LABEL = "standard";

    /** Relaxation damping, held at 1.0: the damping axis is not screened here. */
    private static final double DAMPING = 1.00;

    /** Lane-change safety distance, held at the production value. */
    private static final double FGAP = FreiburgCarStudy.SAFETY_DISTANCE_FACTOR;

    /** Comfortable deceleration of the baseline, and the two grid points below it. */
    private static final double B_BASE = FreiburgProductionStudy.B;

    /** Stopped distance of cars in the baseline. */
    private static final double S0_BASE = FreiburgProductionStudy.S0_CAR;

    /** Maximum acceleration of cars in the baseline. */
    private static final double A_BASE = FreiburgProductionStudy.A_CAR;

    /** Car accelerations, bracketing the baseline; the values {@code FreiburgCarStudy} used. */
    private static final double[] A_CAR = {1.25, 1.70};

    /**
     * Comfortable decelerations. Both below the baseline of 1.75, which sits at the top of the range the original
     * grid covered (1.25 / 1.50 / 1.75) -- there is no measured ground above it and all of it below.
     */
    private static final double[] B = {1.25, 1.50};

    /** Car stopped distances. Below the baseline of 3.0 for the same reason; trucks follow at the 2:1 ratio. */
    private static final double[] S0_CAR = {2.0, 2.5};

    /** Follower deceleration bands, as {@code FreiburgBehaviourStudy} defines them; the baseline is the loosest. */
    private static final double[][] FOLLOWER_BANDS = {{-2.25, -4.5}, {-2.5, -5.0}};

    /** Truck accelerations: the value measured in a jam, and the midpoint to the 1.25 in use. */
    private static final double[] A_TRUCK = {0.7, 1.0};

    /** Parameter key naming the cell. */
    public static final String KEY_CELL = "screen2.cell";

    /** Parameter key naming the headway combination, so the evaluation can split on it without parsing labels. */
    public static final String KEY_HEADWAY = "screen2.headway";

    /** Parameter key naming the axis a cell varies. */
    public static final String KEY_AXIS = "screen2.axis";

    /**
     * One cell: what it is called, which axis it moves, and what it sets beyond the headway.
     * @param label String; the cell label, without the headway prefix
     * @param axis String; the axis this cell moves, or "none" for the unchanged cell
     * @param planner String; the driver model this cell runs on
     * @param body Consumer&lt;ScenarioParameters&gt;; what the cell sets on top of the headway baseline
     */
    private record Cell(String label, String axis, String planner, Consumer<ScenarioParameters> body)
    {
    }

    /** The per-headway cells, in registration order. */
    private static final List<Cell> CELLS = buildCells();

    /**
     * Builds the cells one headway carries.
     * @return List&lt;Cell&gt;; the cells, in registration order
     */
    private static List<Cell> buildCells()
    {
        java.util.List<Cell> cells = new java.util.ArrayList<>();
        cells.add(new Cell(BASE_LABEL, "none", "tama", params -> { }));

        for (double a : A_CAR)
        {
            final double value = a;
            cells.add(new Cell("acar" + label(value), "a", "tama",
                    params -> params.set("car." + ParameterTypes.A.getId(), Acceleration.instantiateSI(value))));
        }

        // Applied to both vehicle types, as the sensitivity screen did: splitting it would double the cell count
        // for a distinction the field data cannot resolve.
        for (double b : B)
        {
            final double value = b;
            cells.add(new Cell("b" + label(value), "b", "tama", params ->
            {
                params.set("car." + ParameterTypes.B.getId(), Acceleration.instantiateSI(value));
                params.set("truck." + ParameterTypes.B.getId(), Acceleration.instantiateSI(value));
            }));
        }

        // The truck value follows Kesting's 2:1 ratio rather than being held fixed, so the cell varies one
        // physical quantity instead of also changing the ratio between the two types.
        for (double s0 : S0_CAR)
        {
            final double value = s0;
            cells.add(new Cell("s0car" + label(value), "s0", "tama", params ->
            {
                params.set("car." + ParameterTypes.S0.getId(),
                        org.djunits.value.vdouble.scalar.Length.instantiateSI(value));
                params.set("truck." + ParameterTypes.S0.getId(),
                        org.djunits.value.vdouble.scalar.Length.instantiateSI(2.0 * value));
            }));
        }

        for (double[] band : FOLLOWER_BANDS)
        {
            final double min = band[0];
            final double max = band[1];
            cells.add(new Cell("follower" + label(Math.abs(min)), "follower", "tama", params ->
            {
                params.set("car." + MirovaParameters.minFollowerDecelerationThreshold.getId(),
                        Acceleration.instantiateSI(min));
                params.set("car." + MirovaParameters.maxFollowerDecelerationThreshold.getId(),
                        Acceleration.instantiateSI(max));
            }));
        }

        for (double a : A_TRUCK)
        {
            final double value = a;
            cells.add(new Cell("trucka" + label(value), "truckA", "tama",
                    params -> params.set("truck." + ParameterTypes.A.getId(), Acceleration.instantiateSI(value))));
        }

        cells.add(new Cell(REFERENCE_LABEL, "planner", ScenarioGenerator.MIROVA_PLANNER, params -> { }));
        return Collections.unmodifiableList(cells);
    }

    /**
     * The baseline one headway carries, before any cell moves a member of the triple.
     *
     * Extracted from {@code register} so that a study which needs the same starting point -- the validation set is
     * this method at {@link #standardHeadway()} with the unchanged cell -- takes it from
     * here instead of restating it. A restatement is what drifts; this cannot.
     * @param facility TrafficFacility; the facility
     * @param date String; the date
     * @param demandCsvPath String; the demand file
     * @param strict boolean; whether demand resolution is strict
     * @param headway FreiburgCombinationStudy.HeadwayCombination; the headway combination
     * @return ScenarioParameters; the baseline parameters
     */
    public static ScenarioParameters baseline(final TrafficFacility facility, final String date,
            final String demandCsvPath, final boolean strict,
            final FreiburgCombinationStudy.HeadwayCombination headway)
    {
        ScenarioParameters params =
                FreiburgCombinationStudy.forCombination(facility, date, demandCsvPath, strict, headway, DAMPING, FGAP);
        // The baseline of the triple, which each cell then moves one member of.
        params.set("car." + ParameterTypes.B.getId(), Acceleration.instantiateSI(B_BASE));
        params.set("truck." + ParameterTypes.B.getId(), Acceleration.instantiateSI(B_BASE));
        params.set("car." + ParameterTypes.S0.getId(),
                org.djunits.value.vdouble.scalar.Length.instantiateSI(S0_BASE));
        params.set("truck." + ParameterTypes.S0.getId(),
                org.djunits.value.vdouble.scalar.Length.instantiateSI(2.0 * S0_BASE));
        params.set("car." + ParameterTypes.A.getId(), Acceleration.instantiateSI(A_BASE));
        return params;
    }

    /**
     * The headway combination the validation set was frozen at, found by name rather than by position.
     * @return FreiburgCombinationStudy.HeadwayCombination; the standard combination
     * @throws IllegalStateException when no combination carries that label, rather than silently taking another
     */
    public static FreiburgCombinationStudy.HeadwayCombination standardHeadway()
    {
        for (FreiburgCombinationStudy.HeadwayCombination headway : HEADWAYS)
        {
            if (STANDARD_HEADWAY_LABEL.equals(headway.label()))
            {
                return headway;
            }
        }
        throw new IllegalStateException("no headway combination labelled '" + STANDARD_HEADWAY_LABEL
                + "'; the validation set was frozen at it. Known: " + HEADWAYS.stream()
                        .map(FreiburgCombinationStudy.HeadwayCombination::label).toList());
    }

    /**
     * The cells of this study, by qualified label, in registration order.
     * @return Map&lt;String, String&gt;; label to the axis it moves
     */
    public static Map<String, String> cells()
    {
        Map<String, String> all = new LinkedHashMap<>();
        for (FreiburgCombinationStudy.HeadwayCombination headway : HEADWAYS)
        {
            for (Cell cell : CELLS)
            {
                all.put(headway.label() + "_" + cell.label(), cell.axis());
            }
        }
        return Collections.unmodifiableMap(all);
    }

    /**
     * Formats a value for a label, without a decimal point where there is nothing after it.
     * @param value double; the value
     * @return String; the label fragment
     */
    private static String label(final double value)
    {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value).replace('.', 'p');
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "The car-following triple, the follower band and truck acceleration, each at both headways: "
                + cells().size() + " cells per date.";
    }

    /** {@inheritDoc} */
    @Override
    public void register(final ScenarioManager manager, final Map<String, String> options) throws Exception
    {
        TrafficFacility facility = FacilityRegistry.resolve(FreiburgFacility.NAME);

        List<String> dates = DateStudy.resolveDates(options.get("dates"));
        if (dates.isEmpty())
        {
            throw new IllegalArgumentException("Study '" + NAME + "' requires --dates=<comma-separated-dates|file>.");
        }
        String demandOption = options.get("demand");
        if (demandOption == null || demandOption.trim().isEmpty())
        {
            throw new IllegalArgumentException("Study '" + NAME + "' requires --demand=<csv file or directory>.");
        }
        File demandLocation = new File(demandOption.trim());
        String pattern = options.getOrDefault("pattern", DateStudy.DEFAULT_CSV_PATTERN);
        boolean strict = Boolean.parseBoolean(options.getOrDefault("strict", "false"));
        int replications =
                Integer.parseInt(options.getOrDefault("replications", String.valueOf(DEFAULT_REPLICATIONS)));

        Map<String, String> known = cells();
        String cellOption = options.get("cells");
        List<String> wanted = cellOption == null || cellOption.trim().isEmpty() ? List.copyOf(known.keySet())
                : List.of(cellOption.trim().split("\\s*,\\s*"));
        for (String label : wanted)
        {
            if (!known.containsKey(label))
            {
                throw new IllegalArgumentException(
                        "Study '" + NAME + "' has no cell '" + label + "'; known: " + known.keySet());
            }
        }

        Map<String, File> demandPerDate = DateStudy.resolveDemandCsvs(dates, demandLocation, pattern, strict);

        // Registration order is date-major, then headway, then cell, which the global run index follows.
        for (String date : dates)
        {
            String demandCsvPath = demandPerDate.get(date).getAbsolutePath();
            for (FreiburgCombinationStudy.HeadwayCombination headway : HEADWAYS)
            {
                for (Cell cell : CELLS)
                {
                    String label = headway.label() + "_" + cell.label();
                    if (!wanted.contains(label))
                    {
                        continue;
                    }
                    String scenarioName = facility.scenarioName(date, label);
                    manager.addScenario(scenarioName, facility.getGeneratorClass());

                    ScenarioParameters params =
                            baseline(facility, date, demandCsvPath, strict, headway);

                    cell.body().accept(params);
                    params.set(KEY_CELL, label);
                    params.set(KEY_HEADWAY, headway.label());
                    params.set(KEY_AXIS, cell.axis());
                    params.set(ScenarioGenerator.KEY_TACTICAL_PLANNER, cell.planner());
                    manager.addParameterVariation(scenarioName, params);
                }
            }
        }
        manager.setReplications(replications);
    }
}
