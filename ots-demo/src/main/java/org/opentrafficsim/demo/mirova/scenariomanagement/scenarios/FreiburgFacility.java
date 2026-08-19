package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioGenerator;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.TrafficFacility;

/**
 * The Freiburg-Nord motorway section: the A5 merge modelled by {@link FreiburgNord}, with the behavioural baseline and
 * naming of the multi-day evaluation study.
 * <p>
 * This is deliberately a thin adapter over {@link FreiburgStudyParameters} rather than a new home for those values. The
 * baseline is what an empirical dataset is currently being collected against, so moving the numbers would risk changing
 * them; keeping one definition and adapting it to the {@link TrafficFacility} contract cannot.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author Marvin Baumann
 */
public class FreiburgFacility implements TrafficFacility
{
    /** The short name by which this facility is selected. */
    public static final String NAME = "freiburg";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public Class<? extends ScenarioGenerator> getGeneratorClass()
    {
        return FreiburgNord.class;
    }

    @Override
    public ScenarioParameters baseBehaviorParams()
    {
        return FreiburgStudyParameters.baseBehaviorParams();
    }

    @Override
    public String scenarioName(final String date, final String... suffixParts)
    {
        String name = FreiburgStudyParameters.scenarioName(date);
        for (String part : suffixParts)
        {
            name = name + "_" + part;
        }
        return name;
    }

    @Override
    public ScenarioParameters forDate(final String date, final String demandCsvPath, final boolean strict)
    {
        return FreiburgStudyParameters.forDate(date, demandCsvPath, strict);
    }
}
