package org.opentrafficsim.demo.mirova.scenariomanagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.TamaRampEndCalibrationStudy;

/**
 * The calibration study's shape is a condition, not a comment.
 * <p>
 * Three properties carry its readability, and each of them can be lost silently - to a rename, a copied
 * line, a cell dropped while trimming the campaign. A study that loses one still runs, still produces
 * output, and can no longer answer what it was built for, and nothing downstream would notice.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class TamaRampEndCalibrationStudyTest
{
    /** The axis label the combination cells carry. */
    private static final String COMBINED = "thresholds+headway";

    /**
     * The baseline moves nothing, so that every other cell is read against the campaign's own set.
     * <p>
     * A base that quietly carried an axis would make the whole campaign a comparison of that axis with
     * itself.
     * </p>
     */
    @Test
    public void theBaselineMovesNothing()
    {
        assertEquals("none", TamaRampEndCalibrationStudy.cells().get(TamaRampEndCalibrationStudy.BASE_LABEL),
                "the baseline cell moves an axis, so nothing in this study has an unvaried reference");
    }

    /**
     * Every axis is tried at more than one level, or it is not an axis.
     * <p>
     * A single level cannot be read against anything but the base, which makes it a guess rather than a
     * direction - and the point of this study is which way each parameter should move.
     * </p>
     */
    @Test
    public void everyAxisHasAtLeastTwoLevels()
    {
        Map<String, Set<String>> cellsPerAxis = new java.util.TreeMap<>();
        TamaRampEndCalibrationStudy.cells().forEach((label, axis) ->
        {
            if (!"none".equals(axis))
            {
                cellsPerAxis.computeIfAbsent(axis, key -> new LinkedHashSet<>()).add(label);
            }
        });
        assertTrue(cellsPerAxis.size() >= 4, "fewer than four axes remain: " + cellsPerAxis.keySet());
        cellsPerAxis.forEach((axis, labels) -> assertTrue(labels.size() >= 2,
                "axis '" + axis + "' has only " + labels + ". One level can be read against the base and "
                        + "against nothing else, which makes it a guess rather than a direction."));
    }

    /**
     * The combinations are registered after the singles they combine.
     * <p>
     * The global run index follows registration order, so a campaign that is cut short keeps whichever cells
     * came first. A combination whose two singles have not run is not interpretable: a number that moved
     * could belong to either half.
     * </p>
     */
    @Test
    public void combinationsComeAfterTheirSingles()
    {
        List<String> axes = new ArrayList<>(TamaRampEndCalibrationStudy.cells().values());
        int firstCombined = axes.indexOf(COMBINED);
        assertTrue(firstCombined > 0, "no combination cell is registered under '" + COMBINED + "'");
        List<String> before = axes.subList(0, firstCombined);
        assertTrue(before.contains("thresholds"),
                "the threshold singles are registered after the combination, so a campaign cut short could "
                        + "hold the pair without either half: " + axes);
        assertTrue(before.contains("headway"),
                "the headway singles are registered after the combination: " + axes);
        for (int i = firstCombined; i < axes.size(); i++)
        {
            assertEquals(COMBINED, axes.get(i),
                    "a single-axis cell is registered after the combinations, which breaks the same property "
                            + "from the other side: " + axes);
        }
    }

    /** Ten seeds over three days must stay inside the 600 runs the campaign is allowed. */
    @Test
    public void theCampaignFitsItsBudget()
    {
        int runs = TamaRampEndCalibrationStudy.cells().size() * 3 * TamaRampEndCalibrationStudy.DEFAULT_REPLICATIONS;
        assertTrue(runs <= 600,
                "three days at " + TamaRampEndCalibrationStudy.DEFAULT_REPLICATIONS + " seeds over "
                        + TamaRampEndCalibrationStudy.cells().size() + " cells is " + runs
                        + " runs, above the 600 this campaign is allowed");
    }
}
