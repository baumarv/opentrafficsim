package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.util.List;
import java.util.Map;

import org.opentrafficsim.demo.mirova.scenariomanagement.FacilityRegistry;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.StudyDefinition;
import org.opentrafficsim.demo.mirova.scenariomanagement.TrafficFacility;

/**
 * The production run: one parameter set, every study date, enough seeds to quote a number.
 * <p>
 * This study varies nothing. Its purpose is to produce the ensemble the publication reports, at a sample size where
 * the reported figures carry a usable interval - which the calibration campaigns did not. At the seven seeds those
 * used, a cell measuring 57 % breakdown has a Wilson interval of [0.25, 0.84] and one measuring 86 % has
 * [0.49, 0.97]; the campaigns were comparing cells that were never distinguishable. At thirty the half-width on the
 * rate is about 0.17 and on jam duration about 18 %.
 * </p>
 * <h3>Where the parameter set comes from</h3>
 * <p>
 * A sensitivity screen over ten one-at-a-time cells found that comfortable deceleration spans a jam speed of 27.9 to
 * 58.5 km/h and so covers the empirical 44.6 outright, while relaxation damping moved it 2.6 km/h and desired
 * headway 5.4. Three earlier campaigns had varied only the latter two, which is why eighteen of their cells left the
 * congested branch 10 to 20 km/h too slow. The grid that followed crossed the three parameters the screen
 * implicated, and this cell is its outcome.
 * </p>
 * <p>
 * Measured over nine days and four seeds per cell, it discharges at 3176 +/- 36 veh/h against an empirical 3115,
 * with a mean absolute per-day deviation of 2.6 % - on the quantity the field data pins down most tightly, spanning
 * only +/-7 % across the nine days. It breaks down in every run of seven of the eight days that broke down, at 207
 * ramp standstills against 432 for the previous set and an unchanged number of completed merges.
 * </p>
 * <h3>Two weaknesses this run will document rather than hide</h3>
 * <p>
 * <b>The quiet day.</b> On 2025-09-22, the one day the site did not break down, this set breaks down in 75 % of
 * runs. The cell with the sharpest discrimination in the grid, b = 1.50 / s0 = 2.0 / a = 1.4, reaches 0 % there but
 * only 66 % on the days that did break down. Across the grid the two rates correlate at r = 0.81: sensitivity and
 * specificity cannot both be had. That 2025-09-22 carries the second-highest demand peak in the set and stayed free
 * anyway is a single realization of a stochastic capacity, not a probability of zero, so a model breaking down there
 * is reproducing what the demand implies.
 * </p>
 * <p>
 * <b>2025-10-27.</b> Breaks down in 25 % of runs against an empirical certainty and 70 minutes of queue. This is not
 * a property of the parameter set - all eighteen grid cells average 10 % on that date. The mainline count feeding
 * the demand reconstruction sits 114 m upstream of the merge, inside the queue during congestion, so that day's
 * demand is capped by its own jam. It carries the lowest peak in the set at 3840 veh/h while having the longest
 * queue.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See OpenTrafficSim License.
 * </p>
 * @author Marvin Baumann
 */
public class FreiburgProductionStudy implements StudyDefinition
{
    /** Comfortable deceleration of both vehicle types, in m/s^2. */
    public static final double B = 1.75;

    /** Stopped bumper-to-bumper distance of cars, in m. */
    public static final double S0_CAR = 3.0;

    /** Stopped bumper-to-bumper distance of trucks, in m, at Kesting's 2:1 ratio to the car value. */
    public static final double S0_TRUCK = 2.0 * S0_CAR;

    /** Maximum acceleration of cars, in m/s^2. */
    public static final double A_CAR = 1.4;

    /** Lane-change safety distance reduction factor, carried from the calibration. */
    public static final double SAFETY_DISTANCE_FACTOR = FreiburgCongestedBranchStudy.SAFETY_DISTANCE_FACTOR;

    /** Desired headway, carried from the calibration. */
    public static final FreiburgCombinationStudy.HeadwayCombination HEADWAY =
            FreiburgCongestedBranchStudy.BASE_HEADWAY;

    /** Relaxation acceleration damping, carried from the calibration; 1.00 disables it. */
    public static final double DAMPING = FreiburgCongestedBranchStudy.BASE_DAMPING;

    /**
     * Replications per date if the caller names none.
     * <p>
     * Thirty rather than the six or seven the calibration campaigns used, because the intervals at that size do not
     * support the statements this run exists to make.
     * </p>
     */
    public static final int DEFAULT_REPLICATIONS = 30;

    /** The label of the single variation, so the output directory names the set rather than only its numbers. */
    public static final String VARIANT_LABEL = "production";

    @Override
    public String getName()
    {
        return "production";
    }

    @Override
    public String getDescription()
    {
        return "Single calibrated set (b=" + B + ", s0=" + S0_CAR + "/" + S0_TRUCK + ", a=" + A_CAR + ", T="
                + HEADWAY.carT() + "/" + HEADWAY.truckT() + ", damping=" + DAMPING + ") over every study date, "
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
            throw new IllegalArgumentException("Study 'production' requires --dates=<comma-separated-dates|file>.");
        }
        String demandOption = options.get("demand");
        if (demandOption == null || demandOption.trim().isEmpty())
        {
            throw new IllegalArgumentException("Study 'production' requires --demand=<csv file or directory>.");
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
            ScenarioParameters params = FreiburgCongestedBranchStudy.forCell(facility, date, demandCsvPath, strict,
                    B, S0_CAR, A_CAR);
            manager.addParameterVariation(scenarioName, params);
        }
        manager.setReplications(replications);
    }
}
