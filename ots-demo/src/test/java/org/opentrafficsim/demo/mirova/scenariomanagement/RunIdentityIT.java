package org.opentrafficsim.demo.mirova.scenariomanagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import org.djunits.value.vdouble.scalar.Duration;
import org.junit.jupiter.api.Test;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.SimpleHighwayScenario;

/**
 * Drives a short scenario through the real runner and checks what the run folder says about it.
 * <p>
 * This exists because the unit tests could not have caught the mistake it was written after. They check the rule --
 * "refuse when the planner that drove is not the one requested" -- with values handed to it, and they were green while
 * the rule was asked at a moment when the answer did not exist yet: the planner is selected when the GTU templates are
 * built, during simulation setup, and a check placed before that reported {@code mirova} for a run that went on to
 * drive on something else. When a check is asked is not something a unit test can see; only a run through the real
 * path can.
 * </p>
 * <p>
 * Named {@code *IT} so the ordinary unit-test run does not pay for it; surefire excludes it and it is run deliberately.
 * A few simulated seconds are enough - the question is which planner the setup resolves and what the run records, not
 * what the traffic does.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class RunIdentityIT
{

    /**
     * Tests that a run on the scenario's own planner records that fact, in both files, written by the run.
     * @throws Exception when the run fails
     */
    @Test
    public void aRunRecordsThePlannerThatDroveIt() throws Exception
    {
        File output = Files.createTempDirectory("runidentity").toFile();
        System.setProperty(BuildProvenance.ALLOW_PROPERTY, "true");
        try
        {
            ScenarioManager manager = new ScenarioManager(output);
            manager.addScenario("short", SimpleHighwayScenario.class);
            manager.addParameterVariation("short", shortRun());
            manager.setReplications(1);
            assertEquals(1, manager.countRuns(), "one cell, one seed");

            assertTrue(manager.runByGlobalIndex(0), "the run should complete");

            File runFolder = findRunFolder(output);
            String runProperties = Files.readString(new File(runFolder, "run.properties").toPath());
            String buildText = Files.readString(new File(runFolder, "build.txt").toPath());

            // Written by the run, not by the build: this is the only statement about what actually steered.
            assertTrue(runProperties.contains("tacticalPlanner=" + ScenarioGenerator.MIROVA_PLANNER), runProperties);
            assertTrue(runProperties.contains("scenario="), runProperties);
            assertTrue(runProperties.contains("seed="), runProperties);
            assertTrue(buildText.contains("# Drove"), buildText);
            assertTrue(buildText.contains("run.tacticalPlanner=" + ScenarioGenerator.MIROVA_PLANNER), buildText);
        }
        finally
        {
            System.clearProperty(BuildProvenance.ALLOW_PROPERTY);
        }
    }

    /**
     * Tests that a run asking for a planner the scenario cannot build is refused during setup, before it is simulated.
     * <p>
     * The message must name both, and the refusal must arrive from the selection rather than from the check after the
     * run: a misconfigured campaign would otherwise compute every task in full before saying so, burning exactly the
     * allocation the check exists to protect.
     * </p>
     * @throws Exception when something other than the expected refusal goes wrong
     */
    @Test
    public void aRunAskingForAnAbsentPlannerIsRefusedBeforeItIsSimulated() throws Exception
    {
        File output = Files.createTempDirectory("runidentity-refused").toFile();
        System.setProperty(BuildProvenance.ALLOW_PROPERTY, "true");
        try
        {
            ScenarioManager manager = new ScenarioManager(output);
            manager.addScenario("short", SimpleHighwayScenario.class);
            manager.addParameterVariation("short", shortRun());
            manager.setReplications(1);
            // "tama" is not on this test's classpath, so the selection cannot produce it. What is asserted below is not
            // which guard speaks, but when: the run must end during setup, with nothing simulated.
            manager.setOnAllVariations(ScenarioGenerator.KEY_TACTICAL_PLANNER, "tama");

            // runByGlobalIndex reports a failed run by its return value rather than by throwing: it catches, prints the
            // root cause and answers false, which is what makes the cluster task exit non-zero.
            boolean completed = manager.runByGlobalIndex(0);

            assertTrue(!completed, "a run that cannot drive what it asked for must not report success");

            // The point of refusing early: no simulation output exists. Refusing after the run would leave these here
            // and would have cost the run's compute - across a campaign, the whole allocation the check protects.
            File runFolder = findRunFolderOrNull(output);
            if (runFolder != null)
            {
                for (String produced : new String[] {"detector_periodic.csv.zip", "run.properties"})
                {
                    assertTrue(!new File(runFolder, produced).exists(),
                            produced + " exists, so the run was simulated before it was refused");
                }
            }
        }
        finally
        {
            System.clearProperty(BuildProvenance.ALLOW_PROPERTY);
        }
    }

    /**
     * Returns parameters for a run of a few simulated seconds.
     * @return ScenarioParameters; the parameters
     */
    private static ScenarioParameters shortRun()
    {
        return new ScenarioParameters().set("simulationTime", Duration.instantiateSI(5.0))
                .set("warmupTime", Duration.instantiateSI(0.0)).set("enableTrajectoryRecording", false);
    }

    /**
     * Returns the single run folder below a study output root.
     * @param output File; the output root
     * @return File; the run folder
     * @throws Exception when it cannot be found
     */
    private static File findRunFolder(final File output) throws Exception
    {
        try (var paths = Files.walk(output.toPath()))
        {
            List<File> folders = paths.map(java.nio.file.Path::toFile)
                    .filter(f -> f.isDirectory() && f.getName().startsWith("run_seed_")).toList();
            assertEquals(1, folders.size(), "expected exactly one run folder under " + output);
            return folders.get(0);
        }
    }

    /**
     * Returns the single run folder below a study output root, or {@code null} when none was created.
     * @param output File; the output root
     * @return File; the run folder or {@code null}
     * @throws Exception when the tree cannot be walked
     */
    private static File findRunFolderOrNull(final File output) throws Exception
    {
        try (var paths = Files.walk(output.toPath()))
        {
            return paths.map(java.nio.file.Path::toFile)
                    .filter(f -> f.isDirectory() && f.getName().startsWith("run_seed_")).findFirst().orElse(null);
        }
    }

}
