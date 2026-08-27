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
 * Factorial sweep of the behavioural parameters that changed between the third and the fourth merge grid campaign, on a
 * single date with the grid cell held fixed.
 * <p>
 * The preceding campaigns varied relaxation damping and the lane-change safety distance while everything else moved in
 * step with whatever the model looked like that week. That made every regression a matter of guessing which of several
 * simultaneous changes carried it, and two such guesses - the car stopping distance, then the truck acceleration - were
 * refuted by measurement after the fact. This study fixes damping at 0.80 and the safety distance at 0.40, where the
 * fourth campaign put the best cell, and spends the freed dimensions on the behavioural axes instead.
 * </p>
 * <p>
 * What is being chased is a collapse of the right-hand mainline lane. Between the two campaigns its jam speed fell from
 * 41.8 to 14.8 km/h for cars and from 43.5 to 12.5 for trucks, while the left lane lost far less - so the disturbance
 * belongs to the lane next to the on-ramp rather than to a vehicle class. Ramp standstills nearly doubled at the same
 * time, from 148 to 279 per run, against only 8 % more merges.
 * </p>
 * <p>
 * The axes are chosen accordingly:
 * </p>
 * <ul>
 * <li><b>Follower deceleration thresholds</b> - the main suspect. They govern what a merging vehicle may impose on the
 * follower it cuts in front of, which is by construction a vehicle in the right lane. Three levels, because this axis
 * carries the hypothesis and a two-point line cannot show curvature.</li>
 * <li><b>Truck acceleration</b> - three levels spanning the Kesting value, the OTS default the model ran on before, and
 * the midpoint. Field medians of 0.60 to 0.87 m/s^2 describe an average over a process that starts higher, whereas IDM
 * reads this parameter as a ceiling, so the empirical figure is a lower bound on what belongs here rather than the
 * value itself.</li>
 * <li><b>Truck desired headway</b> - included because published sweeps find the headway to move bottleneck discharge
 * where the acceleration barely does, and because the two are varied together in the literature's own jam-resolution
 * strategy.</li>
 * <li><b>Truck stopping distance</b> - the second truck parameter that moved when the Kesting values were adopted. Both
 * moved in the direction of a slower queue, so without this axis their effects cannot be told apart.</li>
 * </ul>
 * <p>
 * One date keeps the design affordable at 36 variations. It is deliberately not a substitute for the multi-date grid:
 * what this study can establish is which parameter carries an effect, not whether the calibrated value generalises.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See OpenTrafficSim License.
 * </p>
 * @author Marvin Baumann
 */
public class FreiburgBehaviourStudy implements StudyDefinition
{
    /** The headway combination this study fixes. */
    public static final String HEADWAY_LABEL = FreiburgMergeGridStudy.HEADWAY_LABEL;

    /** The relaxation acceleration damping factor, fixed at the best cell of the fourth campaign. */
    public static final double ACC_DAMPING_FACTOR = 0.80;

    /** The lane-change safety distance reduction factor, fixed at the best cell of the fourth campaign. */
    public static final double SAFETY_DISTANCE_FACTOR = 0.40;

    /**
     * Follower deceleration thresholds as (minimum, maximum) pairs [m/s^2].
     * <p>
     * Both ends bound the same interpolation over lane-change desire, so neither can be moved on its own. The first
     * pair is what the model carried before the fourth campaign, the last is what it carries now, and the middle pair
     * splits them.
     * </p>
     */
    public static final List<double[]> FOLLOWER_THRESHOLDS =
            List.of(new double[] {-2.0, -4.0}, new double[] {-2.25, -4.5}, new double[] {-2.5, -5.0});

    /** Car-following acceleration of trucks [m/s^2]: the Kesting value, the midpoint, the OTS default. */
    public static final List<Double> TRUCK_ACCELERATIONS = List.of(0.7, 1.0, 1.3);

    /** Desired headway of trucks [s]: the value of the fixed combination, and a distinctly shorter one. */
    public static final List<Double> TRUCK_HEADWAYS = List.of(1.20, 1.00);

    /** Stopping distance of trucks [m]: the Kesting value, and the OTS default the model ran on before. */
    public static final List<Double> TRUCK_STOPPING_DISTANCES = List.of(4.0, 3.0);

    /** Parameter key recording the follower minimum in {@code runParams.txt}. */
    public static final String KEY_FOLLOWER_MIN = "studyFollowerMin";

    /** Parameter key recording the follower maximum in {@code runParams.txt}. */
    public static final String KEY_FOLLOWER_MAX = "studyFollowerMax";

    /** Parameter key recording the truck acceleration in {@code runParams.txt}. */
    public static final String KEY_TRUCK_A = "studyTruckA";

    /** Parameter key recording the truck headway in {@code runParams.txt}. */
    public static final String KEY_TRUCK_T = "studyTruckT";

    /** Parameter key recording the truck stopping distance in {@code runParams.txt}. */
    public static final String KEY_TRUCK_S0 = "studyTruckS0";

    @Override
    public String getName()
    {
        return "behaviour";
    }

    @Override
    public String getDescription()
    {
        return "Follower thresholds " + FOLLOWER_THRESHOLDS.size() + " x truck acceleration "
                + TRUCK_ACCELERATIONS + " x truck headway " + TRUCK_HEADWAYS + " x truck stopping distance "
                + TRUCK_STOPPING_DISTANCES + " at damping " + ACC_DAMPING_FACTOR + " and safety distance "
                + SAFETY_DISTANCE_FACTOR + ": " + variationCount() + " variations per date.";
    }

    /**
     * Returns the number of variations the factorial spans.
     * @return int; the number of variations per date
     */
    public static int variationCount()
    {
        return FOLLOWER_THRESHOLDS.size() * TRUCK_ACCELERATIONS.size() * TRUCK_HEADWAYS.size()
                * TRUCK_STOPPING_DISTANCES.size();
    }

    /**
     * Returns the label identifying one cell of the factorial, used as the suffix of the scenario name.
     * <p>
     * Formatted with {@link Locale#ROOT} so the decimal separator is a dot on every machine: the label ends up in
     * directory names that post-processing matches on, and those must not depend on the format locale of whichever
     * node ran the job.
     * </p>
     * @param follower double[]; the follower threshold pair
     * @param truckA double; the truck car-following acceleration
     * @param truckT double; the truck desired headway
     * @param truckS0 double; the truck stopping distance
     * @return String; the variant label
     */
    public static String variantLabel(final double[] follower, final double truckA, final double truckT,
            final double truckS0)
    {
        return String.format(Locale.ROOT, "fol%.2f-%.2f_ta%.2f_tT%.2f_ts%.1f", follower[0], follower[1], truckA,
                truckT, truckS0);
    }

    /** {@inheritDoc} */
    @Override
    public void register(final ScenarioManager manager, final Map<String, String> options) throws Exception
    {
        TrafficFacility facility = FacilityRegistry.resolve(FreiburgFacility.NAME);
        HeadwayCombination combination = FreiburgDampingStudy.resolveHeadwayCombination();

        List<String> dates = DateStudy.resolveDates(options.get("dates"));
        if (dates.isEmpty())
        {
            throw new IllegalArgumentException("Study 'behaviour' requires --dates=<comma-separated-dates|file>.");
        }

        String demandOption = options.get("demand");
        if (demandOption == null || demandOption.trim().isEmpty())
        {
            throw new IllegalArgumentException("Study 'behaviour' requires --demand=<csv file or directory>.");
        }
        File demandLocation = new File(demandOption.trim());
        String pattern = options.getOrDefault("pattern", DateStudy.DEFAULT_CSV_PATTERN);
        boolean strict = Boolean.parseBoolean(options.getOrDefault("strict", "false"));
        int replications = Integer.parseInt(
                options.getOrDefault("replications", String.valueOf(DateStudy.DEFAULT_REPLICATIONS)));

        Map<String, File> demandPerDate = DateStudy.resolveDemandCsvs(dates, demandLocation, pattern, strict);

        // Registration order is date-major, then follower, truck acceleration, truck headway, truck stopping
        // distance, which the global run index follows.
        for (String date : dates)
        {
            String demandCsvPath = demandPerDate.get(date).getAbsolutePath();
            for (double[] follower : FOLLOWER_THRESHOLDS)
            {
                for (double truckA : TRUCK_ACCELERATIONS)
                {
                    for (double truckT : TRUCK_HEADWAYS)
                    {
                        for (double truckS0 : TRUCK_STOPPING_DISTANCES)
                        {
                            String scenarioName = facility.scenarioName(date,
                                    variantLabel(follower, truckA, truckT, truckS0));
                            manager.addScenario(scenarioName, facility.getGeneratorClass());
                            manager.addParameterVariation(scenarioName, forCell(facility, date, demandCsvPath, strict,
                                    combination, follower, truckA, truckT, truckS0));
                        }
                    }
                }
            }
        }

        manager.setReplications(replications);
    }

    /**
     * Builds the parameter set of one cell of the factorial.
     * @param facility TrafficFacility; the facility the study runs on
     * @param date String; the simulated date
     * @param demandCsvPath String; absolute path of the demand CSV for that date
     * @param strict boolean; whether a missing demand CSV aborts the run
     * @param combination HeadwayCombination; the fixed headway combination
     * @param follower double[]; the follower threshold pair
     * @param truckA double; the truck car-following acceleration
     * @param truckT double; the truck desired headway
     * @param truckS0 double; the truck stopping distance
     * @return ScenarioParameters; the parameters of this cell
     */
    public static ScenarioParameters forCell(final TrafficFacility facility, final String date,
            final String demandCsvPath, final boolean strict,
            final HeadwayCombination combination, final double[] follower,
            final double truckA, final double truckT, final double truckS0)
    {
        ScenarioParameters params = FreiburgCombinationStudy.forCombination(facility, date, demandCsvPath, strict,
                combination, ACC_DAMPING_FACTOR, SAFETY_DISTANCE_FACTOR);

        // The truck headway of the fixed combination is overridden here, since this study sweeps it.
        params.set("truck." + ParameterTypes.T.getId(), truckT);
        params.set("truck." + ParameterTypes.A.getId(), truckA);
        params.set("truck." + ParameterTypes.S0.getId(), truckS0);

        // Only cars merge onto the mainline in any number here, so the follower thresholds are swept for cars; the
        // truck values stay at the study baseline rather than acquiring a setting the sweep never justified.
        params.set("car." + MirovaParameters.minFollowerDecelerationThreshold.getId(), follower[0]);
        params.set("car." + MirovaParameters.maxFollowerDecelerationThreshold.getId(), follower[1]);

        // Recorded so runParams.txt names the cell rather than only carrying the values it derives from.
        params.set(KEY_FOLLOWER_MIN, follower[0]);
        params.set(KEY_FOLLOWER_MAX, follower[1]);
        params.set(KEY_TRUCK_A, truckA);
        params.set(KEY_TRUCK_T, truckT);
        params.set(KEY_TRUCK_S0, truckS0);
        return params;
    }
}
