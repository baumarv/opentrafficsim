package org.opentrafficsim.demo.mirova.scenariomanagement;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import org.djunits.value.vdouble.scalar.Duration;
import org.junit.jupiter.api.Test;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.FreiburgCongestedBranchStudy;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.FreiburgFacility;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.FreiburgProductionStudy;

/**
 * The extended trajectory columns must arrive filled in the file, under either driver model.
 * <p>
 * This is the measurement the unit tests cannot make. That the core computes the values and the adapter passes
 * them through is evidenced elsewhere; that they reach a trajectory file is a different claim, and it is the one
 * the campaign pilot falsified: a TaMA run produced a file of the right size, with the right columns, in which
 * five of them were {@code none} and {@code NaN} on every row. Nothing failed and nothing warned.
 * </p>
 * <p>
 * Both models run <b>in the same test</b>, on the same scenario, with the same columns asserted, so the
 * comparison is inside the test rather than in whoever reads two test reports side by side. A change that
 * empties the columns for one model and not the other fails here.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class TrajectoryColumnsIT
{
    /** The columns FreiburgNord registers that carry decision state; GtuType is not one of them. */
    private static final List<String> DECISION_COLUMNS = List.of("ActionState", "LaneChangeDesireLeft",
            "LaneChangeDesireRight", "AccelerationDamping", "CurrentCFAcceleration");

    /** The class the TaMA bundle registers; absent unless the build ran with the {@code tama} profile. */
    private static final String TAMA_PROVIDER = "edu.kit.ifv.tama.ots.TamaPlannerProvider";

    /** A date with a demand CSV in the repository. */
    private static final String DATE = "2025-09-22";

    /** Simulated seconds. Long enough for vehicles to be generated and sampled, short enough for a test. */
    private static final double SECONDS = 120.0;

    /** A run records filled decision columns under MiRoVA. */
    @Test
    public void aMirovaRunFillsTheDecisionColumns() throws Exception
    {
        assertColumnsFilled(ScenarioGenerator.MIROVA_PLANNER);
    }

    /**
     * A run records filled decision columns under TaMA, through the adapter.
     * <p>
     * Skipped, loudly, when the bundle is not on the classpath: that is a statement about the build, not about
     * the adapter. Run it with the {@code tama} profile after {@code ./gradlew :tama-ots:publishToMavenLocal}.
     * </p>
     */
    @Test
    public void aTamaRunFillsTheSameColumns() throws Exception
    {
        if (!tamaOnClasspath())
        {
            System.out.println("[TrajectoryColumnsIT] SKIPPED the TaMA half: " + TAMA_PROVIDER
                    + " is not on the classpath, so this build cannot drive TaMA at all. "
                    + "Run with -Ptama after publishing the bundle to mavenLocal.");
            return;
        }
        assertColumnsFilled("tama");
    }

    /**
     * Runs the scenario on one planner and requires every decision column to be non-empty on every sampled row.
     * @param planner String; the planner to drive with
     * @throws Exception when the run or the reading fails
     */
    private static void assertColumnsFilled(final String planner) throws Exception
    {
        File demand = demandCsv();
        File output = Files.createTempDirectory("trajcolumns-" + planner).toFile();
        System.setProperty(BuildProvenance.ALLOW_PROPERTY, "true");
        try
        {
            TrafficFacility facility = FacilityRegistry.resolve(FreiburgFacility.NAME);
            ScenarioParameters params = FreiburgCongestedBranchStudy.forCell(facility, DATE,
                    demand.getAbsolutePath(), false, FreiburgProductionStudy.B, FreiburgProductionStudy.S0_CAR,
                    FreiburgProductionStudy.A_CAR);
            params.set("simulationTime", Duration.instantiateSI(SECONDS));
            params.set("warmupTime", Duration.instantiateSI(0.0));
            params.set("enableTrajectoryRecording", true);
            params.set(ScenarioGenerator.KEY_TACTICAL_PLANNER, planner);

            String scenarioName = facility.scenarioName(DATE, "columns");
            ScenarioManager manager = new ScenarioManager(output);
            manager.addScenario(scenarioName, facility.getGeneratorClass());
            manager.addParameterVariation(scenarioName, params);
            manager.setReplications(1);
            assertTrue(manager.runByGlobalIndex(0), "the " + planner + " run should complete");

            List<String> lines = trajectoryLines(output);
            assertTrue(lines.size() > 1, "no trajectory rows were written for " + planner);
            assertEveryRowCarriesADecision(planner, lines);
        }
        finally
        {
            System.clearProperty(BuildProvenance.ALLOW_PROPERTY);
        }
    }

    /**
     * Requires each decision column to hold a value on at least one row, and never only empties.
     * <p>
     * "Non-empty on every row" is the wrong bound for two of these: {@code ActionState} is legitimately
     * {@code none} for a vehicle doing nothing, and a damping factor is legitimately absent before the first
     * relaxation. What cannot be legitimate is a column that is empty on <b>every</b> row of a two-minute run -
     * that is the pilot's finding, and it is what this asserts.
     * </p>
     * @param planner String; for the message
     * @param lines List&lt;String&gt;; the CSV lines, header first
     */
    private static void assertEveryRowCarriesADecision(final String planner, final List<String> lines)
    {
        String[] header = lines.get(0).split(",");
        for (String column : DECISION_COLUMNS)
        {
            int index = -1;
            for (int i = 0; i < header.length; i++)
            {
                if (header[i].trim().equals(column))
                {
                    index = i;
                }
            }
            if (index < 0)
            {
                fail("column " + column + " is not in the trajectory header: " + lines.get(0));
            }
            int filled = 0;
            for (int row = 1; row < lines.size(); row++)
            {
                String[] fields = lines.get(row).split(",");
                if (index >= fields.length)
                {
                    continue;
                }
                String value = fields[index].trim();
                if (!value.isEmpty() && !"NaN".equals(value) && !"none".equals(value))
                {
                    filled++;
                }
            }
            if ("ActionState".equals(column))
            {
                // A state name carrying an identity hash is a different string for every instance, so it
                // cannot be grouped, counted or mapped to the other model's vocabulary. A real TaMA run
                // wrote `...MandatoryLaneChangePattern$Synchronising@6cdc7c38`, and the "non-empty" bound
                // above accepted it: the column was full and useless at the same time.
                for (int row = 1; row < lines.size(); row++)
                {
                    String[] fields = lines.get(row).split(",");
                    if (index < fields.length && fields[index].contains("@"))
                    {
                        fail(planner + ": state name '" + fields[index].trim() + "' carries an identity hash, "
                                + "so it names an instance rather than a state");
                    }
                }
            }
            assertTrue(filled > 0, planner + ": column " + column + " is empty on all " + (lines.size() - 1)
                    + " rows. The run wrote a file of the right shape carrying no decision state at all, which is "
                    + "exactly what a sampler bound to one planner class produces for every other driver model.");
        }
    }

    /**
     * Returns the demand CSV, which lives beside the cluster scripts rather than in the module.
     * @return File; the CSV
     */
    private static File demandCsv()
    {
        File here = new File("cluster/demand/demand_" + DATE + ".csv");
        File up = new File("../cluster/demand/demand_" + DATE + ".csv");
        File found = here.isFile() ? here : up;
        assertTrue(found.isFile(), "demand CSV not found at " + here.getAbsolutePath() + " or "
                + up.getAbsolutePath() + "; this test needs the repository's own demand data");
        return found;
    }

    /**
     * Reads the trajectory CSV out of the run folder, zipped or plain.
     * @param output File; the study output root
     * @return List&lt;String&gt;; the lines, header first
     * @throws IOException when it cannot be read
     */
    private static List<String> trajectoryLines(final File output) throws IOException
    {
        // The sampler writes `sampler_<name>_[...].csv.zip`; the detector files sit beside it and have a
        // different header entirely. Picking by "any csv.zip" found the detector file first and asserted
        // against its header, which is a test failing for the wrong reason.
        List<File> candidates = new ArrayList<>();
        try (var paths = Files.walk(output.toPath()))
        {
            paths.map(java.nio.file.Path::toFile)
                    .filter(f -> f.getName().startsWith("sampler_") && f.getName().endsWith(".csv.zip"))
                    .forEach(candidates::add);
        }
        for (File candidate : candidates)
        {
            if (candidate.getName().endsWith(".zip"))
            {
                try (ZipFile zip = new ZipFile(candidate))
                {
                    var entry = zip.stream().filter(e -> e.getName().endsWith(".csv")).findFirst().orElse(null);
                    if (entry != null)
                    {
                        try (InputStream in = zip.getInputStream(entry))
                        {
                            return new ArrayList<>(
                                    List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")));
                        }
                    }
                }
            }
            else
            {
                return Files.readAllLines(candidate.toPath(), StandardCharsets.UTF_8);
            }
        }
        return fail("no trajectory file below " + output.getAbsolutePath() + "; the run wrote none");
    }

    /**
     * Whether the TaMA bundle is on this build's classpath.
     * @return boolean; true when the provider class can be loaded
     */
    private static boolean tamaOnClasspath()
    {
        try
        {
            Class.forName(TAMA_PROVIDER);
            return true;
        }
        catch (ClassNotFoundException e)
        {
            return false;
        }
    }
}
