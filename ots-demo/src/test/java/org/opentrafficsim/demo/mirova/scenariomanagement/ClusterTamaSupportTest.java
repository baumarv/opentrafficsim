package org.opentrafficsim.demo.mirova.scenariomanagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.junit.jupiter.api.Test;

/**
 * What a cluster run needs in order to drive on TaMA and to say afterwards what it drove on.
 * <p>
 * Two rules are pinned here. A run states its driver model through a scenario parameter set on every variation, so that
 * the same study body can be run on either model with one flag; and a run that drives on TaMA records the TaMA commit
 * next to the OTS one, refusing a bundle built from a dirty tree. Before this, {@code build.txt} named the OTS commit
 * alone while the bundle - resolved from a local Maven repository and carrying an empty manifest - could have come from
 * any TaMA tree at all.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class ClusterTamaSupportTest
{
    /** A stamp as the TaMA bundle carries it. */
    private static final String CLEAN_STAMP = "tama.describe=1dda593\ntama.kotlinUnits=commit=f2ed2e8 patch=applied\n";

    /** Tests that the planner parameter reaches every variation of every scenario. */
    @Test
    public void setsThePlannerOnEveryVariation()
    {
        ScenarioManager manager = new ScenarioManager(new File("target/test-output"));
        manager.addScenario("a", null);
        manager.addScenario("b", null);
        manager.addParameterVariation("a", new ScenarioParameters());
        manager.addParameterVariation("a", new ScenarioParameters().set("car.T", 1.2));
        manager.addParameterVariation("b", new ScenarioParameters());

        int changed = manager.setOnAllVariations(ScenarioGenerator.KEY_TACTICAL_PLANNER, "tama");

        assertEquals(3, changed, "every variation of every scenario, so no run silently keeps the study's planner");
    }

    /** Tests that the planner parameter overwrites what a study put there, since the run's choice wins. */
    @Test
    public void thePlannerOverwritesWhatTheStudySet()
    {
        ScenarioManager manager = new ScenarioManager(new File("target/test-output"));
        manager.addScenario("a", null);
        ScenarioParameters params = new ScenarioParameters().set(ScenarioGenerator.KEY_TACTICAL_PLANNER, "mirova");
        manager.addParameterVariation("a", params);

        manager.setOnAllVariations(ScenarioGenerator.KEY_TACTICAL_PLANNER, "tama");

        assertEquals("tama", params.get(ScenarioGenerator.KEY_TACTICAL_PLANNER, String.class));
    }

    /** Tests that a study registering nothing is reported rather than silently accepted. */
    @Test
    public void aPlannerThatReachesNothingIsVisibleToTheCaller()
    {
        ScenarioManager manager = new ScenarioManager(new File("target/test-output"));
        manager.addScenario("a", null);

        assertEquals(0, manager.setOnAllVariations(ScenarioGenerator.KEY_TACTICAL_PLANNER, "tama"),
                "a scenario without variations produces no runs, and the caller must be able to tell");
    }

    /** Tests that a MiRoVA-only classpath adds nothing: the OTS stamp then describes the whole model. */
    @Test
    public void withoutTheBundleTheRecordIsUnchanged()
    {
        assertEquals("", BuildProvenance.driverModelRecord(null, null));
    }

    /** Tests that the TaMA commit and the requested composition are recorded. */
    @Test
    public void theTamaCommitAndCompositionAreRecorded()
    {
        String record = BuildProvenance.driverModelRecord(CLEAN_STAMP, "mirova-reference/1");

        assertTrue(record.startsWith("# TaMA\n"), record);
        assertTrue(record.contains("tama.describe=1dda593"), record);
        assertTrue(record.contains("tama.composition=mirova-reference/1"), record);
    }

    /** Tests that a run which named no composition records that fact rather than leaving the line out. */
    @Test
    public void anUnnamedCompositionIsRecordedAsTheDefault()
    {
        assertTrue(BuildProvenance.driverModelRecord(CLEAN_STAMP, null).contains("tama.composition=(provider default)"));
        assertTrue(BuildProvenance.driverModelRecord(CLEAN_STAMP, "  ").contains("tama.composition=(provider default)"));
    }

    /** Tests that a bundle built from a dirty tree is refused, as a dirty OTS tree is refused by stamp_build.sh. */
    @Test
    public void aDirtyTamaBuildIsRefused()
    {
        String dirty = "tama.describe=1dda593-dirty\n";

        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> BuildProvenance.driverModelRecord(dirty, null));

        assertTrue(failure.getMessage().contains("dirty"), failure.getMessage());
    }
}
