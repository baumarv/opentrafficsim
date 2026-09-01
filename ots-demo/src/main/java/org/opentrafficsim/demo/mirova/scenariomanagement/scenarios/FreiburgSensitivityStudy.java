package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.demo.mirova.scenariomanagement.FacilityRegistry;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.StudyDefinition;
import org.opentrafficsim.demo.mirova.scenariomanagement.TrafficFacility;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.FreiburgCombinationStudy.HeadwayCombination;

/**
 * One-at-a-time sensitivity screen around the settled parameter set.
 * <p>
 * This study does not calibrate. It answers a single question that the headway-against-damping campaigns left
 * unanswered and cannot answer: <b>which parameter moves the jam speed at all?</b>
 * </p>
 * <p>
 * The nightly campaign measured a jam speed of 18.6 to 50.8 km/h across eighteen cells, median around 28, against an
 * empirical band of 37.3 to 50.8 over the eight days that break down. Neither desired headway nor relaxation damping
 * shifted it in any cell. A grid over those two axes would therefore repeat a known null result at the cost of
 * several hundred runs, which is what this screen exists to avoid.
 * </p>
 * <p>
 * The candidates are the parameters of the congested branch that no campaign has varied. {@code b}, the comfortable
 * deceleration, governs how hard a vehicle brakes when its gap closes and therefore how deep a disturbance cuts
 * before it recovers. {@code s0} sets the jam density directly, and through it the speed the model settles at inside
 * a queue. {@code a} bounds how quickly a vehicle leaves a disturbance again. Desired headway and damping are carried
 * along as reference rows: they are expected to move nothing, and if they do move something here, the screen itself
 * is suspect.
 * </p>
 * <p>
 * Cells vary one parameter at a time from the baseline rather than forming a grid. With ten cells against the
 * 3<sup>5</sup> of a full factorial, the screen costs a tenth of the runs and answers the direction question; the
 * interactions a grid would find are only worth paying for on the axes that turn out to matter.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See OpenTrafficSim License.
 * </p>
 * @author Marvin Baumann
 */
public class FreiburgSensitivityStudy implements StudyDefinition
{
    /** Lane-change safety distance reduction factor, fixed at the settled value. */
    public static final double SAFETY_DISTANCE_FACTOR = FreiburgCarStudy.SAFETY_DISTANCE_FACTOR;

    /** Desired headway of the baseline, the pair the settled campaign carried forward. */
    public static final HeadwayCombination BASE_HEADWAY =
            new HeadwayCombination("settled", FreiburgStudyParameters.CAR_T, FreiburgStudyParameters.TRUCK_T);

    /** Relaxation acceleration damping of the baseline. */
    public static final double BASE_DAMPING = 1.00;

    /** Parameter key naming the varied cell in {@code runParams.txt}. */
    public static final String KEY_CELL = "studyCell";

    /**
     * The cells of the screen: a label and the deviation it applies to the baseline parameters.
     * <p>
     * Ordered so the reference rows sit last, which keeps the interesting cells at the low array indices of a
     * partially completed job.
     * </p>
     */
    public static final Map<String, Consumer<ScenarioParameters>> CELLS = buildCells();

    /**
     * Builds the cell table.
     * @return Map&lt;String, Consumer&lt;ScenarioParameters&gt;&gt;; label to parameter deviation
     */
    private static Map<String, Consumer<ScenarioParameters>> buildCells()
    {
        Map<String, Consumer<ScenarioParameters>> cells = new LinkedHashMap<>();
        cells.put("base", p -> { });

        // Comfortable deceleration, applied to both vehicle types: splitting it would double the cell count for a
        // distinction the field data cannot resolve.
        cells.put("b1.0", p -> setBoth(p, ParameterTypes.B.getId(), 1.0));
        cells.put("b1.5", p -> setBoth(p, ParameterTypes.B.getId(), 1.5));
        cells.put("b3.0", p -> setBoth(p, ParameterTypes.B.getId(), 3.0));

        // Stopped distance. The truck value keeps Kesting's 2:1 ratio to the car value rather than being held fixed,
        // so the cell varies one physical quantity rather than also changing the ratio between the two types.
        cells.put("s0car1.0", p -> setS0(p, 1.0));
        cells.put("s0car3.0", p -> setS0(p, 3.0));

        // Maximum acceleration of cars only: the truck value is a vehicle property rather than a driver one, and was
        // already validated separately at 0.71 m/s^2 measured in a jam.
        cells.put("aCar1.0", p -> p.set("car." + ParameterTypes.A.getId(), 1.0));
        cells.put("aCar2.0", p -> p.set("car." + ParameterTypes.A.getId(), 2.0));

        // Reference rows: expected to move nothing, present so a null result on the candidates can be told apart
        // from a screen that cannot detect anything at all.
        cells.put("T1.00", p ->
        {
            p.set("car." + ParameterTypes.T.getId(), 1.00);
            p.set("truck." + ParameterTypes.T.getId(), 1.30);
        });
        cells.put("damp0.90", p -> setBoth(p,
                org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters
                        .RELAXATION_ACC_DAMPING_FACTOR.getId(), 0.90));
        return cells;
    }

    /**
     * Sets one parameter for cars and trucks alike.
     * @param params ScenarioParameters; the parameters to modify
     * @param parameterId String; the parameter id, without the type prefix
     * @param value double; the value
     */
    private static void setBoth(final ScenarioParameters params, final String parameterId, final double value)
    {
        params.set("car." + parameterId, value);
        params.set("truck." + parameterId, value);
    }

    /**
     * Sets the stopped distance of cars, carrying the truck value along at Kesting's 2:1 ratio.
     * @param params ScenarioParameters; the parameters to modify
     * @param carS0 double; the car value in m
     */
    private static void setS0(final ScenarioParameters params, final double carS0)
    {
        params.set("car." + ParameterTypes.S0.getId(), carS0);
        params.set("truck." + ParameterTypes.S0.getId(), 2.0 * carS0);
    }

    @Override
    public String getName()
    {
        return "sensitivity";
    }

    @Override
    public String getDescription()
    {
        return "One-at-a-time screen over b, s0 and a around the settled set, with headway and damping as "
                + "reference rows: " + CELLS.size() + " variations per date.";
    }

    /** {@inheritDoc} */
    @Override
    public void register(final ScenarioManager manager, final Map<String, String> options) throws Exception
    {
        TrafficFacility facility = FacilityRegistry.resolve(FreiburgFacility.NAME);

        List<String> dates = DateStudy.resolveDates(options.get("dates"));
        if (dates.isEmpty())
        {
            throw new IllegalArgumentException("Study 'sensitivity' requires --dates=<comma-separated-dates|file>.");
        }
        String demandOption = options.get("demand");
        if (demandOption == null || demandOption.trim().isEmpty())
        {
            throw new IllegalArgumentException("Study 'sensitivity' requires --demand=<csv file or directory>.");
        }
        File demandLocation = new File(demandOption.trim());
        String pattern = options.getOrDefault("pattern", DateStudy.DEFAULT_CSV_PATTERN);
        boolean strict = Boolean.parseBoolean(options.getOrDefault("strict", "false"));
        int replications = Integer.parseInt(
                options.getOrDefault("replications", String.valueOf(DateStudy.DEFAULT_REPLICATIONS)));

        Map<String, File> demandPerDate = DateStudy.resolveDemandCsvs(dates, demandLocation, pattern, strict);

        // Registration order is date-major, then cell, which the global run index follows.
        for (String date : dates)
        {
            String demandCsvPath = demandPerDate.get(date).getAbsolutePath();
            for (Map.Entry<String, Consumer<ScenarioParameters>> cell : CELLS.entrySet())
            {
                String scenarioName = facility.scenarioName(date, cell.getKey());
                manager.addScenario(scenarioName, facility.getGeneratorClass());
                ScenarioParameters params = FreiburgCombinationStudy.forCombination(facility, date, demandCsvPath,
                        strict, BASE_HEADWAY, BASE_DAMPING, SAFETY_DISTANCE_FACTOR);
                cell.getValue().accept(params);
                params.set(KEY_CELL, cell.getKey());
                manager.addParameterVariation(scenarioName, params);
            }
        }
        manager.setReplications(replications);
    }
}
