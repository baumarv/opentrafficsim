package org.opentrafficsim.demo.mirova.scenariomanagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.FreiburgFacility;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.TamaFinalValidationStudy;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.TamaHeadwayScreeningStudy;

/**
 * What the final campaign runs with, checked without running it.
 * <p>
 * This is the run the paper rests on, so the two things that would ruin it silently are pinned here:
 * that its parameter set is the frozen one plus exactly the one intended change, and that the change
 * itself carries the values the investigation settled on. A campaign that ran a different compression
 * than its own documentation states would produce results nobody could attribute, and the manifest
 * would agree with the run rather than with the intention.
 * </p>
 * <p>
 * The frozen set is compared entry by entry against the study that defines it rather than restated,
 * so this cannot pass by agreeing with a copy of itself.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class TamaFinalValidationStudyTest
{
    /** A date and a demand path; neither is read by the parameter construction. */
    private static final String DATE = "2025-09-22";

    /** A demand path that is never opened here. */
    private static final String DEMAND = "demand_2025-09-22.csv";

    /**
     * The campaign's parameters for one date.
     * @return ScenarioParameters; the parameters
     */
    private static ScenarioParameters campaign()
    {
        return TamaFinalValidationStudy.parameters(FacilityRegistry.resolve(FreiburgFacility.NAME), DATE,
                DEMAND, true);
    }

    /**
     * Everything the frozen set fixes is still fixed, with the same value.
     * <p>
     * Taken from {@link TamaHeadwayScreeningStudy#baseline}, so a change there is either inherited here
     * or fails here; a campaign silently diverging from the set it claims to run is what this prevents.
     * </p>
     */
    @Test
    public void theFrozenSetIsInherited()
    {
        ScenarioParameters frozen = TamaHeadwayScreeningStudy.baseline(
                FacilityRegistry.resolve(FreiburgFacility.NAME), DATE, DEMAND, true,
                TamaHeadwayScreeningStudy.standardHeadway());
        Map<String, Object> campaign = campaign().asUnmodifiableMap();
        for (Map.Entry<String, Object> entry : frozen.asUnmodifiableMap().entrySet())
        {
            assertEquals(entry.getValue(), campaign.get(entry.getKey()),
                    "the campaign changed '" + entry.getKey() + "' of the frozen validation set");
        }
    }

    /** The one intended change is present, with the values the investigation settled on. */
    @Test
    public void theCompressionIsTheOneChange()
    {
        Map<String, Object> params = campaign().asUnmodifiableMap();
        assertEquals(TamaFinalValidationStudy.PIVOT_KMH,
                (Double) params.get(ScenarioParameters.KEY_DESIRED_SPEED_PIVOT_CAR), 0.0,
                "the campaign does not compress towards the pivot its documentation states");
        assertEquals(TamaFinalValidationStudy.COMPRESSION,
                (Double) params.get(ScenarioParameters.KEY_DESIRED_SPEED_COMPRESSION_CAR), 0.0,
                "the campaign does not use the compression factor its documentation states");
    }

    /**
     * No other reshaping of the desired-speed distribution is set.
     * <p>
     * {@code FreiburgNord} refuses a run with more than one, so this would fail at t=0 of every run
     * rather than silently — but a campaign of five hundred runs should not discover that on the
     * cluster.
     * </p>
     */
    @Test
    public void noOtherReshapingIsSet()
    {
        Map<String, Object> params = campaign().asUnmodifiableMap();
        Object shift = params.get(ScenarioParameters.KEY_DESIRED_SPEED_SHIFT_CAR);
        Object minimum = params.get(ScenarioParameters.KEY_DESIRED_SPEED_MIN_CAR);
        assertTrue(shift == null || ((Number) shift).doubleValue() == 0.0,
                "the campaign also shifts the distribution: " + shift);
        assertTrue(minimum == null || ((Number) minimum).doubleValue() == 0.0,
                "the campaign also truncates the distribution: " + minimum);
    }

    /** It runs on TaMA, which is what the whole campaign is about. */
    @Test
    public void itRunsOnTama()
    {
        assertEquals("tama", campaign().asUnmodifiableMap().get(ScenarioGenerator.KEY_TACTICAL_PLANNER),
                "the final campaign is not on the driver model it is meant to validate");
    }

    /** Sixteen days at the default seed count is the run count the campaign was planned at. */
    @Test
    public void theDefaultSeedCountGivesThePlannedSize()
    {
        assertEquals(496, 16 * TamaFinalValidationStudy.DEFAULT_REPLICATIONS,
                "the default no longer gives the planned campaign size of 496 runs over sixteen days");
    }
}
