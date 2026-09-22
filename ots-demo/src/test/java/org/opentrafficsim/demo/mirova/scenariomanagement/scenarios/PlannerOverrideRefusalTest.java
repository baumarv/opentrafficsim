package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioGenerator;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.StudyDefinition;

/**
 * A global {@code --tacticalPlanner} must not flatten a study that varies the planner itself.
 * <p>
 * The failure this guards is silent: the override would turn the MiRoVA reference arm of the screening study into
 * another TaMA cell, and the resulting output would be indistinguishable from a correct run of a study that never
 * had a reference arm. So the rule is a condition that ends the run, and these cases pin it -- including the case
 * that must still be allowed, since a check that refuses everything is not a check.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class PlannerOverrideRefusalTest
{
    /** A study definition that registers nothing; only its name is used in the message. */
    private static final StudyDefinition NAMED = new StudyDefinition()
    {
        @Override
        public String getName()
        {
            return "teststudy";
        }

        @Override
        public String getDescription()
        {
            return "a study that exists only to be named in an error message";
        }

        @Override
        public void register(final ScenarioManager manager, final java.util.Map<String, String> options)
        {
            // nothing
        }
    };

    /**
     * Builds a manager with one variation per given planner value; a null entry registers a variation without one.
     * @param planners String...; the planner each variation carries
     * @return ScenarioManager; the manager
     */
    private static ScenarioManager managerWith(final String... planners)
    {
        ScenarioManager manager = new ScenarioManager(new File("target/planner-refusal-test"));
        int index = 0;
        for (String planner : planners)
        {
            String name = "scenario" + index++;
            manager.addScenario(name, ScenarioGenerator.class);
            ScenarioParameters params = new ScenarioParameters();
            if (planner != null)
            {
                params.set(ScenarioGenerator.KEY_TACTICAL_PLANNER, planner);
            }
            manager.addParameterVariation(name, params);
        }
        return manager;
    }

    /** A study whose cells disagree about the planner refuses the override, and names the values it found. */
    @Test
    public void aStudyThatVariesThePlannerRefusesTheOverride()
    {
        ScenarioManager manager = managerWith("tama", "tama", ScenarioGenerator.MIROVA_PLANNER);
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> RunMirovaClusterStudy.applyTacticalPlanner("tama", manager, NAMED));
        assertTrue(thrown.getMessage().contains("teststudy"), "the message does not name the study");
        assertTrue(thrown.getMessage().contains(ScenarioGenerator.MIROVA_PLANNER),
                "the message does not name the values that would be flattened");
    }

    /** The refusal happens before anything is written: the reference arm still carries its own planner. */
    @Test
    public void theRefusalChangesNothing()
    {
        ScenarioManager manager = managerWith("tama", ScenarioGenerator.MIROVA_PLANNER);
        assertThrows(IllegalStateException.class,
                () -> RunMirovaClusterStudy.applyTacticalPlanner("tama", manager, NAMED));
        assertEquals(2, manager.distinctVariationValues(ScenarioGenerator.KEY_TACTICAL_PLANNER).size(),
                "the refused override wrote anyway");
    }

    /** A study that sets no planner at all is still overridable: that is what the option exists for. */
    @Test
    public void aStudyWithoutAPlannerIsStillOverridable()
    {
        ScenarioManager manager = managerWith(null, null);
        RunMirovaClusterStudy.applyTacticalPlanner("tama", manager, NAMED);
        assertEquals(java.util.Set.of("tama"), manager.distinctVariationValues(ScenarioGenerator.KEY_TACTICAL_PLANNER),
                "the override did not reach every variation");
    }

    /** A study that sets one planner uniformly is overridable too; only a disagreement is refused. */
    @Test
    public void aUniformPlannerIsStillOverridable()
    {
        ScenarioManager manager = managerWith("tama", "tama");
        RunMirovaClusterStudy.applyTacticalPlanner(ScenarioGenerator.MIROVA_PLANNER, manager, NAMED);
        assertEquals(java.util.Set.of(ScenarioGenerator.MIROVA_PLANNER),
                manager.distinctVariationValues(ScenarioGenerator.KEY_TACTICAL_PLANNER),
                "a uniform study should still follow the flag");
    }

    /** The screening study is the real case: its own cells disagree, so it must refuse the flag. */
    @Test
    public void theScreeningStudysOwnCellsDisagree()
    {
        java.util.Set<Object> planners = new java.util.LinkedHashSet<>();
        for (java.util.function.Consumer<ScenarioParameters> cell : TamaScreeningStudy.cells().values())
        {
            ScenarioParameters params = new ScenarioParameters();
            cell.accept(params);
            planners.add(params.asUnmodifiableMap().get(ScenarioGenerator.KEY_TACTICAL_PLANNER));
        }
        assertEquals(2, planners.size(), "the screening study no longer varies the planner across its cells");
    }
}
