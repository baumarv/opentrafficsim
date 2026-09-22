package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import org.djunits.value.vdouble.scalar.Acceleration;
import org.djunits.value.vdouble.scalar.Length;
import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.opentrafficsim.demo.mirova.scenariomanagement.FacilityRegistry;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioGenerator;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.StudyDefinition;
import org.opentrafficsim.demo.mirova.scenariomanagement.TrafficFacility;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;

/**
 * The combined screening study: every open axis at once, on three days, under TaMA, with a MiRoVA reference arm.
 * <p>
 * Registered as {@code --study=tamascreen}. Replaces the earlier two-stage plan of a {@code vgaintau} campaign
 * followed by a wider one; see {@code docs/campaigns/} in the TaMA repository for the design and the pre-registered
 * metrics.
 * </p>
 * <h3>The cells</h3>
 * <ul>
 * <li><b>Eight</b> taken unchanged from {@link VGainRelaxationSensitivityStudy#cells()} -- the baseline, three
 * {@code vGain} levels and four {@code tau_relax_s} levels. Taken rather than restated, so the two studies cannot
 * drift apart.</li>
 * <li><b>Two</b> on the lane-change safety distance {@code fGap}, at the values {@link VGainScreeningStudy} uses.
 * It is the parameter most likely to have compensated the too-large {@code vGain}.</li>
 * <li><b>Two</b> on {@code socioSpeedSensitivity}, halved and doubled. It is the second knob on the same saturation
 * term as {@code vGain} and was never fitted.</li>
 * <li><b>Four</b> on cooperation, led by the bounding cell that switches it off. If cooperation off moves nothing,
 * the three cells that modulate it are answered with it -- a parameter cannot contribute through a mechanism that
 * contributes nothing.</li>
 * <li><b>One</b> reference arm on the MiRoVA planner at the baseline parameters, so the whole screen has a
 * same-configuration comparison against the model the calibration was done on.</li>
 * </ul>
 * <h3>Why the planner is a cell rather than a flag</h3>
 * <p>
 * {@code --tacticalPlanner} sets the planner on <i>every</i> variation, which is right for a study that asks one
 * question of two models and wrong here: it would turn the reference arm into a seventeenth TaMA cell without
 * saying so. This study therefore sets the planner per cell and is run <b>without</b> that option;
 * {@code RunMirovaClusterStudy} refuses the combination rather than letting it pass.
 * </p>
 * <pre>
 *   --study=tamascreen --output=&lt;dir&gt; --dates=2025-09-22,2025-09-23,2025-10-07 \
 *   --demand=&lt;dir&gt; --replications=10 --count
 * </pre>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class TamaScreeningStudy implements StudyDefinition
{
    /** Registered study name. */
    public static final String NAME = "tamascreen";

    /** Label of the MiRoVA reference cell. */
    public static final String REFERENCE_LABEL = "mirovaref";

    /** Label of the cell that switches cooperative lane changes off, which bounds the whole cooperation block. */
    public static final String COOPERATION_OFF_LABEL = "coopoff";

    /** Replications per cell; the campaign runs ten seeds per cell per day. */
    public static final int DEFAULT_REPLICATIONS = 10;

    /** Lane-change safety distance factors, matching {@link VGainScreeningStudy} so the cells are comparable. */
    private static final double[] FGAP = {0.50, 0.60};

    /** Social speed sensitivity values: the default 0.25 halved and doubled. */
    private static final double[] SOCIO = {0.125, 0.50};

    /** Cooperative deceleration thresholds [m/s^2], bracketing the default of -3.0. */
    private static final double[] COOP_DECELERATION = {-2.0, -4.5};

    /** Gap-opening look-ahead distance [m]; the default is 100. */
    private static final double COOP_RANGE_M = 200.0;

    /** Parameter key naming the varied cell in the run parameters. */
    public static final String KEY_CELL = "screen.cell";

    /** Parameter key naming the block a cell belongs to, so the evaluation can group without parsing labels. */
    public static final String KEY_BLOCK = "screen.block";

    /** The cells, by label, in registration order. */
    private static final Map<String, Consumer<ScenarioParameters>> CELLS = new LinkedHashMap<>();

    static
    {
        // Block 1: the eight vGain and tau cells, taken from the study that defines them.
        for (Map.Entry<String, Consumer<ScenarioParameters>> cell : VGainRelaxationSensitivityStudy.cells().entrySet())
        {
            Consumer<ScenarioParameters> body = cell.getValue();
            CELLS.put(cell.getKey(), params ->
            {
                body.accept(params);
                params.set(KEY_BLOCK, "vgaintau");
                params.set(ScenarioGenerator.KEY_TACTICAL_PLANNER, "tama");
            });
        }

        // Block 2: the lane-change safety distance.
        for (double fGap : FGAP)
        {
            final double value = fGap;
            CELLS.put("fgap" + label(value), params ->
            {
                params.set("car." + MirovaParameters.safetyDistanceReductionFactorLaneChange.getId(), value);
                params.set("truck." + MirovaParameters.safetyDistanceReductionFactorLaneChange.getId(), value);
                params.set(KEY_BLOCK, "fgap");
                params.set(ScenarioGenerator.KEY_TACTICAL_PLANNER, "tama");
            });
        }

        // Block 3: the social speed sensitivity.
        for (double socio : SOCIO)
        {
            final double value = socio;
            CELLS.put("socio" + label(value), params ->
            {
                params.set("car." + MirovaParameters.socioSpeedSensitivity.getId(), value);
                params.set("truck." + MirovaParameters.socioSpeedSensitivity.getId(), value);
                params.set(KEY_BLOCK, "socio");
                params.set(ScenarioGenerator.KEY_TACTICAL_PLANNER, "tama");
            });
        }

        // Block 4: cooperation. The bounding cell is registered first, which is also the order it must be read in.
        //
        // Cars only, and that is measured rather than chosen: the production set already carries
        // truck.COOPERATIVE_LANE_CHANGES_ENABLED=false, so trucks do not cooperate at the baseline at all. Setting the
        // truck values too would mean these cells varied two things -- the level, and the car/truck asymmetry the
        // production set has (car -3.0 against truck -1.0) -- and a cell that moves two things explains nothing. The
        // truck threshold is in any case inert while truck cooperation is off.
        CELLS.put(COOPERATION_OFF_LABEL, params ->
        {
            params.set("car." + MirovaParameters.cooperativeLaneChangesEnabled.getId(), Boolean.FALSE);
            params.set(KEY_BLOCK, "cooperation");
            params.set(ScenarioGenerator.KEY_TACTICAL_PLANNER, "tama");
        });

        for (double decel : COOP_DECELERATION)
        {
            final double value = decel;
            CELLS.put("coopdecel" + label(Math.abs(value)), params ->
            {
                params.set("car." + MirovaParameters.cooperativeDecelerationThreshold.getId(),
                        Acceleration.instantiateSI(value));
                params.set(KEY_BLOCK, "cooperation");
                params.set(ScenarioGenerator.KEY_TACTICAL_PLANNER, "tama");
            });
        }

        CELLS.put("cooprange" + label(COOP_RANGE_M), params ->
        {
            params.set("car." + MirovaParameters.considerGapOpeningLookaheadDistance.getId(),
                    Length.instantiateSI(COOP_RANGE_M));
            params.set(KEY_BLOCK, "cooperation");
            params.set(ScenarioGenerator.KEY_TACTICAL_PLANNER, "tama");
        });

        // Block 5: the reference arm. Baseline parameters, the other driver model.
        CELLS.put(REFERENCE_LABEL, params ->
        {
            params.set(KEY_BLOCK, "reference");
            params.set(ScenarioGenerator.KEY_TACTICAL_PLANNER, ScenarioGenerator.MIROVA_PLANNER);
        });
    }

    /**
     * The cells of this study, by label, in registration order.
     * @return Map&lt;String, Consumer&lt;ScenarioParameters&gt;&gt;; an unmodifiable, order-preserving view
     */
    public static Map<String, Consumer<ScenarioParameters>> cells()
    {
        return Collections.unmodifiableMap(new LinkedHashMap<>(CELLS));
    }

    /**
     * Formats a value for use in a cell label, without a decimal point where there is nothing after it.
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
        return "Combined screening of vGain, the relaxation time constant, the lane-change safety distance, the social "
                + "speed sensitivity and cooperation, with a MiRoVA reference arm: " + CELLS.size() + " cells per date.";
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

        String cellOption = options.get("cells");
        List<String> wanted = cellOption == null || cellOption.trim().isEmpty() ? List.copyOf(CELLS.keySet())
                : List.of(cellOption.trim().split("\\s*,\\s*"));
        for (String label : wanted)
        {
            if (!CELLS.containsKey(label))
            {
                throw new IllegalArgumentException(
                        "Study '" + NAME + "' has no cell '" + label + "'; known: " + CELLS.keySet());
            }
        }

        Map<String, File> demandPerDate = DateStudy.resolveDemandCsvs(dates, demandLocation, pattern, strict);

        // Registration order is date-major, then cell, which the global run index follows.
        for (String date : dates)
        {
            String demandCsvPath = demandPerDate.get(date).getAbsolutePath();
            for (String label : wanted)
            {
                String scenarioName = facility.scenarioName(date, label);
                manager.addScenario(scenarioName, facility.getGeneratorClass());

                ScenarioParameters params = FreiburgCongestedBranchStudy.forCell(facility, date, demandCsvPath, strict,
                        FreiburgProductionStudy.B, FreiburgProductionStudy.S0_CAR, FreiburgProductionStudy.A_CAR);
                CELLS.get(label).accept(params);
                params.set(KEY_CELL, label);
                manager.addParameterVariation(scenarioName, params);
            }
        }
        manager.setReplications(replications);
    }
}
