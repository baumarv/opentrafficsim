package org.opentrafficsim.demo.mirova.scenariomanagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.TamaRampEndCalibrationStudy;

/**
 * The calibration study's design is a condition, not a comment.
 * <p>
 * Its whole value rests on being a 2 x 4: every calibration axis run in both behaviour arms, so that a
 * number which moves can be attributed to the axis rather than to the arm. An axis that lost one of its
 * arms - to a rename, to a copied line, to a cell removed while trimming the campaign - leaves a study
 * that still runs, still produces output, and can no longer answer the question it was built for. Nothing
 * downstream would notice: the missing arm is simply a column that is not there.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class TamaRampEndCalibrationStudyTest
{
    /** The label of the one cell that is deliberately unpaired. */
    private static final String SENSITIVITY = "road200_new";

    /**
     * Every calibration axis exists in both arms, and only the sensitivity cell stands alone.
     */
    @Test
    public void everyAxisIsRunInBothArms()
    {
        Map<String, Set<String>> armsPerAxis = new TreeMap<>();
        for (Map.Entry<String, String> cell : TamaRampEndCalibrationStudy.cells().entrySet())
        {
            if (SENSITIVITY.equals(cell.getKey()))
            {
                continue;
            }
            String[] parts = cell.getValue().split("/");
            assertEquals(2, parts.length, "cell '" + cell.getKey() + "' does not report arm/axis");
            armsPerAxis.computeIfAbsent(parts[1], key -> new LinkedHashSet<>()).add(parts[0]);
        }
        assertTrue(armsPerAxis.size() >= 2, "there is only one axis, so there is nothing to compare: " + armsPerAxis);
        for (Map.Entry<String, Set<String>> axis : armsPerAxis.entrySet())
        {
            assertEquals(Set.of("off", "new"), axis.getValue(),
                    "axis '" + axis.getKey() + "' is not run in both behaviour arms but in " + axis.getValue()
                            + ". The design is a 2 x 4 and a half-populated axis cannot be read: a number that "
                            + "moves could be the axis or the arm, and the study would run to completion without "
                            + "saying which.");
        }
    }

    /**
     * The baseline cell is in the published arm, and is named as the study's base.
     * <p>
     * It is what every other cell is read against, so a base that quietly carried the new behaviour would
     * make the whole campaign a comparison of the new behaviour with itself.
     * </p>
     */
    @Test
    public void theBaselineIsThePublishedBehaviour()
    {
        String base = TamaRampEndCalibrationStudy.cells().get(TamaRampEndCalibrationStudy.BASE_LABEL);
        assertEquals("off/none", base,
                "the baseline cell '" + TamaRampEndCalibrationStudy.BASE_LABEL + "' is '" + base
                        + "', not the published behaviour with nothing varied");
    }

    /** Both arms of an axis sit next to each other, so a campaign cut short still holds whole pairs. */
    @Test
    public void theArmsOfAnAxisAreAdjacent()
    {
        String previousAxis = null;
        String previousArm = null;
        for (Map.Entry<String, String> cell : TamaRampEndCalibrationStudy.cells().entrySet())
        {
            if (SENSITIVITY.equals(cell.getKey()))
            {
                continue;
            }
            String[] parts = cell.getValue().split("/");
            String arm = parts[0];
            String axis = parts[1];
            if ("new".equals(arm))
            {
                assertEquals(axis, previousAxis,
                        "cell '" + cell.getKey() + "' does not follow its own axis's other arm, so a campaign "
                                + "that is cut short can end on an unpaired cell");
                assertEquals("off", previousArm, "the arms of axis '" + axis + "' are not adjacent");
            }
            previousAxis = axis;
            previousArm = arm;
        }
    }
}
