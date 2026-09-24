package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.util.List;
import java.util.Map;

import org.opentrafficsim.demo.mirova.scenariomanagement.FacilityRegistry;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioGenerator;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.StudyDefinition;
import org.opentrafficsim.demo.mirova.scenariomanagement.TrafficFacility;

/**
 * The final validation campaign: one parameter set, all sixteen days, nothing varied.
 * <p>
 * Registered as {@code --study=tamafinal}. A study with a single cell rather than a cell selected from
 * a screening study on the command line: this run is the paper's basis, and what it ran with belongs in
 * the class and the manifest, not in the shell history of whoever submitted it.
 * </p>
 * <h3>The parameter set</h3>
 * <p>
 * The frozen validation set, taken from {@link TamaHeadwayScreeningStudy#baseline} at
 * {@link TamaHeadwayScreeningStudy#standardHeadway()} rather than restated, plus one change: the car
 * desired-speed distribution is compressed towards {@value #PIVOT_KMH} km/h by a factor of
 * {@value #COMPRESSION}.
 * </p>
 * <p>
 * That change comes from {@code docs/campaigns/free-flow-speed-investigation.md}. Measured over 117
 * runs on three axes, it is the only reshaping that corrects the free-flow speed <i>and</i> the spread:
 * it puts the median 0.3 km/h from the field against the previous set's 8.7, and narrows the spread of
 * the five-minute means from 9.9 to 8.9 km/h where the field has 6.4, while moving a uniform shift or a
 * truncation the wrong way. Every driver and their order is kept; the map is
 * {@code v -> pivot + factor * (v - pivot)}.
 * </p>
 * <h3>What this campaign can and cannot answer</h3>
 * <p>
 * It measures the model as it now stands against sixteen days of field data. It is <b>not</b> a
 * comparison against the earlier 800-run validation, which ran on {@code mirova-reference/1} and an
 * older build of this repository; a difference between the two would mix the distribution with
 * everything else that changed, and no reader could attribute it. Where a same-build reference is
 * wanted, {@code --study=tamavcomp --cells=base,c150k07} provides one.
 * </p>
 * <p>
 * What the three-day screening could not settle is capacity: four to six runs per cell reached a
 * breakdown, which carries no capacity claim in either direction. Sixteen days at this many seeds is
 * what settles it, and it is the first thing to read out of the result.
 * </p>
 *
 * <pre>
 *   --study=tamafinal --output=&lt;dir&gt; --dates=&lt;cluster/dates.txt&gt; \
 *   --demand=&lt;dir&gt; --strict=true --replications=31
 * </pre>
 * <p>
 * Sixteen days times {@value #DEFAULT_REPLICATIONS} seeds is 496 runs.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class TamaFinalValidationStudy implements StudyDefinition
{
    /** Registered study name. */
    public static final String NAME = "tamafinal";

    /** The cell label; one cell, named for what it is. */
    public static final String CELL_LABEL = "final_v2";

    /** Seeds per day; sixteen days at this many is 496 runs. */
    public static final int DEFAULT_REPLICATIONS = 31;

    /** The speed the car desired-speed distribution is compressed towards [km/h]. */
    public static final double PIVOT_KMH = 150.0;

    /** The compression factor of the car desired-speed distribution. */
    public static final double COMPRESSION = 0.7;

    /** Parameter key naming the cell in the run parameters, as the screening studies do. */
    public static final String KEY_CELL = "final.cell";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Final validation: the frozen set with the car desired-speed distribution compressed "
                + "towards " + PIVOT_KMH + " km/h by " + COMPRESSION + ", one cell, every date given.";
    }

    /**
     * Applies this campaign's parameter set to a fresh set for one date.
     * <p>
     * Public so that a test can read what the campaign runs with, without running it.
     * </p>
     * @param facility TrafficFacility; the facility
     * @param date String; the date
     * @param demandCsvPath String; the demand file
     * @param strict boolean; whether demand resolution is strict
     * @return ScenarioParameters; the parameters of this campaign's only cell
     */
    public static ScenarioParameters parameters(final TrafficFacility facility, final String date,
            final String demandCsvPath, final boolean strict)
    {
        ScenarioParameters params = TamaHeadwayScreeningStudy.baseline(facility, date, demandCsvPath, strict,
                TamaHeadwayScreeningStudy.standardHeadway());
        params.set(ScenarioParameters.KEY_DESIRED_SPEED_PIVOT_CAR, PIVOT_KMH);
        params.set(ScenarioParameters.KEY_DESIRED_SPEED_COMPRESSION_CAR, COMPRESSION);
        params.set(KEY_CELL, CELL_LABEL);
        params.set(ScenarioGenerator.KEY_TACTICAL_PLANNER, "tama");
        return params;
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
        // This campaign is the paper's basis, so a date whose demand file is missing must end the
        // submission rather than quietly shrink it. The option still exists; the default is the safe one.
        boolean strict = Boolean.parseBoolean(options.getOrDefault("strict", "true"));
        File demandLocation = new File(demandOption.trim());
        String pattern = options.getOrDefault("pattern", DateStudy.DEFAULT_CSV_PATTERN);
        int replications =
                Integer.parseInt(options.getOrDefault("replications", String.valueOf(DEFAULT_REPLICATIONS)));

        if (options.get("cells") != null && !options.get("cells").trim().isEmpty()
                && !options.get("cells").trim().equals(CELL_LABEL))
        {
            throw new IllegalArgumentException("Study '" + NAME + "' has the single cell '" + CELL_LABEL
                    + "'; --cells=" + options.get("cells") + " asks for something it does not have. A campaign "
                    + "that silently ran a different cell than the one asked for would be unattributable.");
        }

        Map<String, File> demandPerDate = DateStudy.resolveDemandCsvs(dates, demandLocation, pattern, strict);

        // Registration order is date-major, which the global run index follows.
        for (String date : dates)
        {
            String scenarioName = facility.scenarioName(date, CELL_LABEL);
            manager.addScenario(scenarioName, facility.getGeneratorClass());
            manager.addParameterVariation(scenarioName,
                    parameters(facility, date, demandPerDate.get(date).getAbsolutePath(), strict));
        }
        manager.setReplications(replications);
    }
}
