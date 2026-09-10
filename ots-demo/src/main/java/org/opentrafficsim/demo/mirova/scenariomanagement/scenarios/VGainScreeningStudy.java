package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.opentrafficsim.demo.mirova.scenariomanagement.FacilityRegistry;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.StudyDefinition;
import org.opentrafficsim.demo.mirova.scenariomanagement.TrafficFacility;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;

/**
 * The first measurement after the speed gain was corrected: how far the model moved, and whether the lane-change
 * safety-distance factor was compensating for it.
 * <p>
 * Small and cheap by design. It is a screen, not a calibration: two days, five replications, four cells. Its purpose
 * is to answer two questions before anyone spends a campaign on them -- <i>is the behavioural change large enough to
 * need a recalibration at all</i>, and <i>does {@code fGap} move in the direction that would undo it</i>.
 * </p>
 * <h3>The cells</h3>
 * <ul>
 * <li><b>legacy</b> -- the published parameterisation: speed gain 15 m/s for cars and 30 m/s for trucks, which is 54
 * and 108 km/h. Everything else is the production set. This is the reference the other three are read against.</li>
 * <li><b>new</b> -- the production set as it now stands: the intended speed gain of 15 and 30 km/h, and the
 * calibrated {@code fGap} of 0.40. The difference between this cell and {@code legacy} <i>is</i> the behavioural
 * effect of the correction.</li>
 * <li><b>fgap0p5</b>, <b>fgap0p6</b> -- the new speed gain with the safety-distance factor loosened towards where it
 * came from. The factor was calibrated <i>down</i> from 0.60 to 0.40 against ramp standstills, at a time when a speed
 * gain 3.6 times too large was suppressing discretionary lane changing. If it was compensating, the corrected model
 * should want it back.</li>
 * </ul>
 * <p>
 * The {@code fGap} axis is therefore {0.40, 0.50, 0.60} under the new speed gain; its 0.40 end is the {@code new}
 * cell itself and is not registered twice.
 * </p>
 * <h3>The two days</h3>
 * <p>
 * Both are calibration days, so the screen measures on the data the parameters were fitted to rather than on a fresh
 * sample -- which is what a screen should do before an out-of-sample test is worth running.
 * </p>
 * <ul>
 * <li><b>2025-09-22</b> -- the <i>only</i> one of the nine study days on which the site did <b>not</b> break down.
 * The free-flow case, where more discretionary lane changing shows up as lane distribution and speed rather than as
 * a queue, and where a model that now breaks down would be failing visibly.</li>
 * <li><b>2025-10-27</b> -- the congested case, and the most demanding one: the lowest queue discharge of the nine
 * days at 2878 veh/h, and the day the headway grid singled out, where at {@code T} = 0.90 s the model broke down in
 * one run out of ten. If the correction costs capacity, it costs it here first.</li>
 * </ul>
 * <h3>Reading it</h3>
 * <p>
 * Five replications per cell and day is <b>too few to resolve anything on its own</b> -- {@code
 * parameter_sensitivity.md} §8 is explicit that four runs per cell resolves nothing, which is why the calibration
 * pooled nine days. Ten runs per cell here can show a large effect and can rule out a very large one; it cannot
 * establish a small one. Treat a null result as "no campaign-sized effect visible", not as "no effect".
 * </p>
 * <pre>
 *   --study=vgainscreen --output=&lt;dir&gt; --demand=cluster/demand --count
 * </pre>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class VGainScreeningStudy implements StudyDefinition
{
    /** The free-flow day: the one study day without a breakdown. */
    public static final String FREE_FLOW_DATE = "2025-09-22";

    /** The congested day: the lowest discharge of the nine, and the one the headway grid singled out. */
    public static final String CONGESTED_DATE = "2025-10-27";

    /** Replications per cell. Deliberately small; see the class documentation on what that does and does not buy. */
    public static final int DEFAULT_REPLICATIONS = 5;

    /** The safety-distance factors of the axis, excluding 0.40, which is the {@code new} cell. */
    private static final double[] FGAP = {0.50, 0.60};

    /** Parameter key naming the varied cell in {@code runParams.txt}. */
    public static final String KEY_CELL = "screen.cell";

    /** Parameter key recording the car speed gain of a cell in km/h. */
    public static final String KEY_VGAIN_KMH = "screen.vGainKmh";

    /** Parameter key recording the safety-distance factor of a cell. */
    public static final String KEY_FGAP = "screen.fGap";

    /** The production safety-distance factor, which is the {@code new} cell's value. */
    private static final double PRODUCTION_FGAP = FreiburgCarStudy.SAFETY_DISTANCE_FACTOR;

    /** The cells, by label, in registration order. */
    private static final Map<String, Consumer<ScenarioParameters>> CELLS = new LinkedHashMap<>();

    static
    {
        CELLS.put(FreiburgProductionStudy.LEGACY_LABEL, params ->
        {
            FreiburgProductionStudy.applyPublishedSpeedGain(params);
            params.set(KEY_VGAIN_KMH, 54.0);
            params.set(KEY_FGAP, PRODUCTION_FGAP);
        });

        CELLS.put("new", params ->
        {
            // Nothing to set: the production set already carries the intended speed gain and fGap = 0.40.
            params.set(KEY_VGAIN_KMH, 15.0);
            params.set(KEY_FGAP, PRODUCTION_FGAP);
        });

        for (double fGap : FGAP)
        {
            final double value = fGap;
            CELLS.put("fgap" + String.valueOf(value).replace('.', 'p'), params ->
            {
                params.set("car." + MirovaParameters.safetyDistanceReductionFactorLaneChange.getId(), value);
                params.set("truck." + MirovaParameters.safetyDistanceReductionFactorLaneChange.getId(), value);
                params.set(KEY_VGAIN_KMH, 15.0);
                params.set(KEY_FGAP, value);
            });
        }
    }

    @Override
    public String getName()
    {
        return "vgainscreen";
    }

    @Override
    public String getDescription()
    {
        return "Screen of the corrected speed gain against the lane-change safety distance: " + CELLS.size()
                + " cells on two calibration days.";
    }

    /** {@inheritDoc} */
    @Override
    public void register(final ScenarioManager manager, final Map<String, String> options) throws Exception
    {
        TrafficFacility facility = FacilityRegistry.resolve(FreiburgFacility.NAME);

        // The two days are the point of the design and are not an option, but the demand has to be found.
        List<String> dates = List.of(CONGESTED_DATE, FREE_FLOW_DATE);

        String demandOption = options.get("demand");
        if (demandOption == null || demandOption.trim().isEmpty())
        {
            throw new IllegalArgumentException("Study 'vgainscreen' requires --demand=<csv file or directory>.");
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
                        "Study 'vgainscreen' has no cell '" + label + "'; known: " + CELLS.keySet());
            }
        }

        Map<String, File> demandPerDate = DateStudy.resolveDemandCsvs(dates, demandLocation, pattern, strict);

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
