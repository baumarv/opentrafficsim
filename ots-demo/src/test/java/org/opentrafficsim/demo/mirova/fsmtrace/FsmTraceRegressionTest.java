package org.opentrafficsim.demo.mirova.fsmtrace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Pins the tactical decisions of the MiRoVA Layer 3 state machine against recorded reference traces.
 * <p>
 * This is the regression net for the re-engineering described in {@code docs/mirova/fsm_reengineering_plan.md}. Every stage of
 * that plan but one is meant to leave the model behaviour untouched, and "untouched" is defined here: the trace of a
 * fixed-seed run is byte-identical to the committed reference. A restructuring that changes a single acceleration, a single
 * state name or a single tick fails this test.
 * </p>
 * <p>
 * The test is disabled unless {@code -Dmirova.fsmtrace=true} is passed, because each case runs a full headless simulation and
 * is far too slow for the ordinary build. Record the references with {@link FsmTraceHarness#main(String[])} and copy them to
 * {@code src/test/resources/mirova/fsmtrace/}.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
@EnabledIfSystemProperty(named = "mirova.fsmtrace", matches = "true")
public class FsmTraceRegressionTest
{

    /** Location of the committed reference traces, relative to the module root. */
    private static final String REFERENCE_DIR = "src/test/resources/mirova/fsmtrace";

    /**
     * Checks the Freiburg case: the real network under measured demand, and the only case that enters the congested branch.
     * @throws Exception if the run or the comparison fails
     */
    @Test
    public void freiburgMergeTraceIsUnchanged() throws Exception
    {
        assertTraceUnchanged(FsmTraceHarness.Case.FREIBURG_MERGE);
    }

    /**
     * Checks the merge case, which sweeps the on-ramp demand through the onset of congestion.
     * @throws Exception if the run or the comparison fails
     */
    @Test
    public void mergeTraceIsUnchanged() throws Exception
    {
        assertTraceUnchanged(FsmTraceHarness.Case.MERGE);
    }

    /**
     * Checks the highway case, which exercises free driving and discretionary lane changes.
     * @throws Exception if the run or the comparison fails
     */
    @Test
    public void highwayTraceIsUnchanged() throws Exception
    {
        assertTraceUnchanged(FsmTraceHarness.Case.HIGHWAY);
    }

    /**
     * Runs one case and compares its trace against the committed reference.
     * @param traceCase the case to check
     * @throws Exception if the run or the comparison fails
     */
    private static void assertTraceUnchanged(final FsmTraceHarness.Case traceCase) throws Exception
    {
        Path reference = Paths.get(REFERENCE_DIR, traceCase.getTraceFileName());
        assumeTrue(Files.exists(reference), "No reference trace for case '" + traceCase.getId()
                + "'. Record one with FsmTraceHarness and copy it to " + REFERENCE_DIR + ".");

        File outputDirectory = Files.createTempDirectory("fsm-trace-" + traceCase.getId()).toFile();
        Path actual = FsmTraceHarness.record(traceCase, outputDirectory);

        List<String> expectedLines = readTrace(reference);
        List<String> actualLines = readTrace(actual);

        // Report the first divergence rather than a length mismatch: once the machine takes a different branch every
        // later row differs too, and only the first one says where.
        int common = Math.min(expectedLines.size(), actualLines.size());
        for (int i = 0; i < common; i++)
        {
            if (!expectedLines.get(i).equals(actualLines.get(i)))
            {
                fail(divergenceReport(traceCase, actual, i, expectedLines, actualLines));
            }
        }
        assertEquals(expectedLines.size(), actualLines.size(), "Case '" + traceCase.getId()
                + "': traces agree on the first " + common + " rows but differ in length. Recorded trace: " + actual);
    }

    /**
     * Reads a trace, transparently decompressing it when it is gzipped.
     * @param trace the trace file
     * @return its lines
     * @throws Exception if the file cannot be read
     */
    private static List<String> readTrace(final Path trace) throws Exception
    {
        List<String> lines = new ArrayList<>();
        InputStream in = Files.newInputStream(trace);
        if (trace.getFileName().toString().endsWith(".gz"))
        {
            in = new GZIPInputStream(in);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                lines.add(line);
            }
        }
        return lines;
    }

    /**
     * Builds a failure message showing the first differing row in its context.
     * @param traceCase the case that diverged
     * @param actual the recorded trace, kept on disk for inspection
     * @param index the zero-based index of the first differing line
     * @param expectedLines the reference lines
     * @param actualLines the recorded lines
     * @return the failure message
     */
    private static String divergenceReport(final FsmTraceHarness.Case traceCase, final Path actual, final int index,
            final List<String> expectedLines, final List<String> actualLines)
    {
        StringBuilder message = new StringBuilder();
        message.append("Case '").append(traceCase.getId()).append("': the FSM took a different decision at trace line ")
                .append(index + 1).append(".\n");
        message.append("  expected: ").append(expectedLines.get(index)).append('\n');
        message.append("  actual  : ").append(actualLines.get(index)).append('\n');
        message.append("  header  : ").append(expectedLines.get(0)).append('\n');
        int from = Math.max(1, index - 3);
        message.append("  preceding rows (identical in both):\n");
        for (int i = from; i < index; i++)
        {
            message.append("    ").append(expectedLines.get(i)).append('\n');
        }
        message.append("  recorded trace kept at: ").append(actual.toAbsolutePath());
        return message.toString();
    }
}
