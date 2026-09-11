package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.djunits.unit.SpeedUnit;
import org.djunits.value.vdouble.scalar.Speed;
import org.opentrafficsim.demo.mirova.scenariomanagement.FacilityRegistry;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.StudyDefinition;
import org.opentrafficsim.demo.mirova.scenariomanagement.TrafficFacility;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;

/**
 * The speed gain on its own: three car values crossed with three truck values, and everything else held at the production
 * set.
 * <p>
 * {@code vgainscreen} compared the two ends -- the published pair and the intended pair -- and varied the safety-distance
 * factor alongside. This grid fills in between and separates the two vehicle classes, so that a difference between the ends
 * can be attributed to the car value, to the truck value or to both, and read for whether it is monotone in either.
 * </p>
 * <h3>The cells</h3>
 * <p>
 * Car speed gain in {15, 30, 54} km/h crossed with truck speed gain in {30, 60, 108} km/h: nine cells. Every cell sets both
 * values explicitly and sets nothing else.
 * </p>
 * <ul>
 * <li><b>intended</b> -- 15 / 30 km/h, taken from {@link FreiburgStudyParameters#CAR_V_GAIN} and
 * {@link FreiburgStudyParameters#TRUCK_V_GAIN}. This cell is the production set.</li>
 * <li><b>published</b> -- 54 / 108 km/h, stated as 15 / 30 m/s exactly as
 * {@link FreiburgProductionStudy#applyPublishedSpeedGain} states them. This cell is the production study's
 * {@code legacy} variant.</li>
 * <li>The other seven are labelled by their values, {@code car<km/h>_truck<km/h>}, e.g. {@code car30_truck60}.</li>
 * </ul>
 * <p>
 * The middle values are twice the intended ones. The published values keep the SI form the legacy variant uses, in the
 * mixed cells too, so that one axis value is one number wherever it occurs.
 * </p>
 * <h3>Days and replications</h3>
 * <p>
 * The two days of {@code vgainscreen} -- {@value VGainScreeningStudy#CONGESTED_DATE}, the congested one, and
 * {@value VGainScreeningStudy#FREE_FLOW_DATE}, the free-flow one -- with {@value #DEFAULT_REPLICATIONS} replications per cell
 * and day: 2 x 9 x 5 = 90 runs. The caution stated there applies here as well: ten runs per cell can show a large effect and
 * rule out a very large one; they cannot establish a small one.
 * </p>
 * <pre>
 *   --study=vgaingrid --output=&lt;dir&gt; --demand=cluster/demand --count
 * </pre>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class VGainGridStudy implements StudyDefinition
{
    /** Replications per cell and day, as in {@code vgainscreen}. */
    public static final int DEFAULT_REPLICATIONS = 5;

    /** Label of the cell carrying the intended speed gain, 15 km/h for cars and 30 km/h for trucks. */
    public static final String INTENDED_LABEL = "intended";

    /** Label of the cell carrying the published speed gain, 15 m/s (54 km/h) for cars and 30 m/s (108 km/h) for trucks. */
    public static final String PUBLISHED_LABEL = "published";

    /** Parameter key naming the cell in {@code runParams.txt}. */
    public static final String KEY_CELL = "grid.cell";

    /** Parameter key recording the car speed gain of the cell, in km/h rounded to an integer. */
    public static final String KEY_CAR_VGAIN_KMH = "grid.carVGainKmh";

    /** Parameter key recording the truck speed gain of the cell, in km/h rounded to an integer. */
    public static final String KEY_TRUCK_VGAIN_KMH = "grid.truckVGainKmh";

    /** The car speed gains in axis order: intended, twice the intended, published. */
    private static final Speed[] CAR_V_GAINS = {FreiburgStudyParameters.CAR_V_GAIN, new Speed(30.0, SpeedUnit.KM_PER_HOUR),
            Speed.instantiateSI(15.0)};

    /** The truck speed gains in axis order: intended, twice the intended, published. */
    private static final Speed[] TRUCK_V_GAINS = {FreiburgStudyParameters.TRUCK_V_GAIN,
            new Speed(60.0, SpeedUnit.KM_PER_HOUR), Speed.instantiateSI(30.0)};

    /** The cells by label, in registration order: car value outer, truck value inner. Each holds {car, truck}. */
    private static final Map<String, Speed[]> CELLS = new LinkedHashMap<>();

    static
    {
        int last = CAR_V_GAINS.length - 1;
        for (int car = 0; car < CAR_V_GAINS.length; car++)
        {
            for (int truck = 0; truck < TRUCK_V_GAINS.length; truck++)
            {
                String label;
                if (car == 0 && truck == 0)
                {
                    label = INTENDED_LABEL;
                }
                else if (car == last && truck == last)
                {
                    label = PUBLISHED_LABEL;
                }
                else
                {
                    label = "car" + kmh(CAR_V_GAINS[car]) + "_truck" + kmh(TRUCK_V_GAINS[truck]);
                }
                CELLS.put(label, new Speed[] {CAR_V_GAINS[car], TRUCK_V_GAINS[truck]});
            }
        }
    }

    /**
     * Returns a speed in whole km/h, for labels and the recorded cell values.
     * @param speed Speed; the speed
     * @return long; the speed in km/h, rounded
     */
    private static long kmh(final Speed speed)
    {
        return Math.round(speed.getInUnit(SpeedUnit.KM_PER_HOUR));
    }

    @Override
    public String getName()
    {
        return "vgaingrid";
    }

    @Override
    public String getDescription()
    {
        return "Speed gain grid: car {15, 30, 54} km/h x truck {30, 60, 108} km/h, " + CELLS.size()
                + " cells on the two vgainscreen days, production set otherwise.";
    }

    /** {@inheritDoc} */
    @Override
    public void register(final ScenarioManager manager, final Map<String, String> options) throws Exception
    {
        TrafficFacility facility = FacilityRegistry.resolve(FreiburgFacility.NAME);

        // The two days are those of vgainscreen and are not an option, but the demand has to be found.
        List<String> dates = List.of(VGainScreeningStudy.CONGESTED_DATE, VGainScreeningStudy.FREE_FLOW_DATE);

        String demandOption = options.get("demand");
        if (demandOption == null || demandOption.trim().isEmpty())
        {
            throw new IllegalArgumentException("Study 'vgaingrid' requires --demand=<csv file or directory>.");
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
                        "Study 'vgaingrid' has no cell '" + label + "'; known: " + CELLS.keySet());
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

                // The production set, built exactly as FreiburgProductionStudy builds it.
                ScenarioParameters params = FreiburgCongestedBranchStudy.forCell(facility, date, demandCsvPath, strict,
                        FreiburgProductionStudy.B, FreiburgProductionStudy.S0_CAR, FreiburgProductionStudy.A_CAR);

                Speed[] speedGain = CELLS.get(label);
                params.set("car." + MirovaParameters.vGain.getId(), speedGain[0]);
                params.set("truck." + MirovaParameters.vGain.getId(), speedGain[1]);
                params.set(KEY_CAR_VGAIN_KMH, (double) kmh(speedGain[0]));
                params.set(KEY_TRUCK_VGAIN_KMH, (double) kmh(speedGain[1]));
                params.set(KEY_CELL, label);
                manager.addParameterVariation(scenarioName, params);
            }
        }
        manager.setReplications(replications);
    }
}
