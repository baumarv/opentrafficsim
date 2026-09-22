package org.opentrafficsim.demo.mirova.scenariomanagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.TamaScreeningStudy;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.VGainRelaxationSensitivityStudy;

/**
 * Structural checks on the combined screening study.
 * <p>
 * These check the cell set, not the simulation: the run count is asked of the study by the runner, and the metrics
 * are pre-registered in the campaign documents. What can go wrong silently here is the cell set -- a cell that
 * forgets its planner, a reference arm that is not on MiRoVA, an order that shifts the task-to-cell mapping.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class TamaScreeningStudyTest
{
    /**
     * Applies one cell to a fresh parameter set and returns it.
     * @param label String; the cell label
     * @return ScenarioParameters; the parameters the cell produced
     */
    private static ScenarioParameters apply(final String label)
    {
        Consumer<ScenarioParameters> cell = TamaScreeningStudy.cells().get(label);
        assertTrue(cell != null, "no cell '" + label + "'");
        ScenarioParameters params = new ScenarioParameters();
        cell.accept(params);
        return params;
    }

    /** Every cell must name the planner it runs on; a cell that forgets it inherits whatever ran last. */
    @Test
    public void everyCellNamesItsPlanner()
    {
        for (String label : TamaScreeningStudy.cells().keySet())
        {
            Object planner = apply(label).asUnmodifiableMap().get(ScenarioGenerator.KEY_TACTICAL_PLANNER);
            assertTrue(planner != null, "cell '" + label + "' does not name a planner");
        }
    }

    /** Exactly one cell is the reference arm, and it is the only one on MiRoVA. */
    @Test
    public void exactlyOneCellIsTheMirovaReference()
    {
        List<String> onMirova = new ArrayList<>();
        for (String label : TamaScreeningStudy.cells().keySet())
        {
            Object planner = apply(label).asUnmodifiableMap().get(ScenarioGenerator.KEY_TACTICAL_PLANNER);
            if (ScenarioGenerator.MIROVA_PLANNER.equals(planner))
            {
                onMirova.add(label);
            }
        }
        assertEquals(List.of(TamaScreeningStudy.REFERENCE_LABEL), onMirova,
                "the reference arm must be the one and only MiRoVA cell");
    }

    /** The eight vGain/tau cells are the ones vgaintau defines, not a restatement that can drift from them. */
    @Test
    public void theVGainTauCellsAreTakenFromTheStudyThatDefinesThem()
    {
        Set<String> screen = TamaScreeningStudy.cells().keySet();
        for (String label : VGainRelaxationSensitivityStudy.cells().keySet())
        {
            assertTrue(screen.contains(label), "vgaintau cell '" + label + "' is missing from the screen");
        }
    }

    /** A cell taken from vgaintau still sets what vgaintau sets: the wrapper adds, it does not replace. */
    @Test
    public void aTakenCellKeepsItsOwnParameters()
    {
        ScenarioParameters direct = new ScenarioParameters();
        VGainRelaxationSensitivityStudy.cells().get("vgain30").accept(direct);
        ScenarioParameters wrapped = apply("vgain30");
        for (Map.Entry<String, Object> entry : direct.asUnmodifiableMap().entrySet())
        {
            assertEquals(entry.getValue(), wrapped.asUnmodifiableMap().get(entry.getKey()),
                    "the screen changed '" + entry.getKey() + "' of a cell it only meant to label");
        }
    }

    /** Cell labels are unique and the order is stable, since the global run index follows registration order. */
    @Test
    public void theCellOrderIsStableAndFree()
    {
        List<String> first = new ArrayList<>(TamaScreeningStudy.cells().keySet());
        List<String> second = new ArrayList<>(TamaScreeningStudy.cells().keySet());
        assertEquals(first, second, "the cell order is not reproducible between calls");
        assertEquals(first.size(), new LinkedHashSet<>(first).size(), "duplicate cell label");
    }

    /** The bounding cell of the cooperation block comes before the cells it can retire. */
    @Test
    public void theCooperationBlockLeadsWithItsBoundingCell()
    {
        List<String> labels = new ArrayList<>(TamaScreeningStudy.cells().keySet());
        int off = labels.indexOf(TamaScreeningStudy.COOPERATION_OFF_LABEL);
        assertTrue(off >= 0, "the bounding cell is not registered");
        for (String label : labels)
        {
            if ("cooperation".equals(apply(label).asUnmodifiableMap().get(TamaScreeningStudy.KEY_BLOCK))
                    && !TamaScreeningStudy.COOPERATION_OFF_LABEL.equals(label))
            {
                assertTrue(labels.indexOf(label) > off,
                        "cooperation cell '" + label + "' is registered before the bounding cell");
            }
        }
    }

    /** Every cell carries a block, so the evaluation can group cells without parsing their labels. */
    @Test
    public void everyCellNamesItsBlock()
    {
        for (String label : TamaScreeningStudy.cells().keySet())
        {
            Object block = apply(label).asUnmodifiableMap().get(TamaScreeningStudy.KEY_BLOCK);
            assertTrue(block instanceof String && !((String) block).isEmpty(),
                    "cell '" + label + "' does not name a block");
        }
    }

    /**
     * The cooperation cells touch cars only.
     * <p>
     * Found in a real manifest, not here: the production set already carries
     * {@code truck.COOPERATIVE_LANE_CHANGES_ENABLED=false} and an asymmetric truck threshold of -1.0 against the
     * car's -3.0. A cooperation cell that also set the truck values would vary the level <i>and</i> that asymmetry,
     * and a cell that moves two things explains neither.
     * </p>
     */
    @Test
    public void theCooperationCellsTouchCarsOnly()
    {
        for (String label : TamaScreeningStudy.cells().keySet())
        {
            ScenarioParameters params = apply(label);
            if (!"cooperation".equals(params.asUnmodifiableMap().get(TamaScreeningStudy.KEY_BLOCK)))
            {
                continue;
            }
            for (String key : params.asUnmodifiableMap().keySet())
            {
                assertFalse(key.startsWith("truck."),
                        "cooperation cell '" + label + "' sets '" + key + "'; the baseline's car/truck asymmetry "
                                + "means a truck value makes the cell vary two things at once");
            }
        }
    }

    /** The view handed out cannot be written through, so a caller cannot reorder the campaign. */
    @Test
    public void theCellViewIsUnmodifiable()
    {
        boolean refused = false;
        try
        {
            TamaScreeningStudy.cells().put("injected", params -> { });
        }
        catch (UnsupportedOperationException e)
        {
            refused = true;
        }
        assertTrue(refused, "the cell view accepted a write");
        assertFalse(TamaScreeningStudy.cells().containsKey("injected"), "the injected cell survived");
    }
}
