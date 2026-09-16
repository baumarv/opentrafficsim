package org.opentrafficsim.demo.mirova.scenariomanagement;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * The commit a run was built from, recorded in the run's own output.
 * <p>
 * No campaign before this class recorded its build: neither the detector output nor {@code runParams.txt} names a commit,
 * and the production ensemble and {@code final_v1} could afterwards only be bracketed by file timestamps (see
 * {@code docs/fork-merge-plan.md}, section D). This closes that for every run that goes through {@link ScenarioManager}.
 * </p>
 * <p>
 * The build scripts ({@code cluster/build_for_cluster.sh}, {@code cluster/run_local_parallel.sh}) write
 * {@value #RESOURCE} into the classes on the run classpath via {@code cluster/stamp_build.sh}, which refuses a dirty working
 * tree. {@link #recordInto(File)} copies it into each run folder as {@value #RUN_FILE}. A run without a stamp does not
 * start.
 * </p>
 * <p>
 * The one exception is explicit and leaves a trace: {@code -D}{@value #ALLOW_PROPERTY}{@code =true}, for a run from an IDE
 * that will never produce a result. The run folder then receives a {@value #RUN_FILE} saying {@code recorded=false}, so that
 * an evaluation script can refuse it rather than guess.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public final class BuildProvenance
{
    /** Classpath resource written by {@code cluster/stamp_build.sh}. */
    public static final String RESOURCE = "mirova-build.properties";

    /** File name of the copy in each run folder. */
    public static final String RUN_FILE = "build.txt";

    /** System property that lets an unstamped build run, recording that it was unstamped. */
    public static final String ALLOW_PROPERTY = "mirova.allowUnrecordedBuild";

    /** Utility class. */
    private BuildProvenance()
    {
        // utility class
    }

    /**
     * Returns the stamp's text, or fails when there is none and unstamped runs are not explicitly allowed.
     * @return String; the content of {@value #RESOURCE}, or a {@code recorded=false} record when explicitly allowed
     * @throws IllegalStateException when no stamp is on the classpath and {@value #ALLOW_PROPERTY} is not {@code true}
     */
    public static String require()
    {
        String stamp = readStamp();
        if (stamp != null)
        {
            return stamp;
        }
        if (Boolean.getBoolean(ALLOW_PROPERTY))
        {
            return "# No build stamp on the classpath; run explicitly allowed with -D" + ALLOW_PROPERTY + "=true.\n"
                    + "recorded=false\n";
        }
        throw new IllegalStateException("No " + RESOURCE + " on the classpath: this build does not record the commit it was "
                + "built from, so its output could not be attributed. Build with cluster/build_for_cluster.sh or run through "
                + "cluster/run_local_parallel.sh (both stamp the build and refuse a dirty tree). For a throwaway run from an "
                + "IDE only: -D" + ALLOW_PROPERTY + "=true, which marks the output recorded=false.");
    }

    /**
     * Returns the one-line description of the build for logs: the {@code describe} entry, or {@code "unrecorded"}.
     * @return String; e.g. {@code vgain-grid-1-14-g2ccc1b362}
     */
    public static String describe()
    {
        String stamp = readStamp();
        if (stamp == null)
        {
            return "unrecorded";
        }
        for (String line : stamp.split("\\R"))
        {
            if (line.startsWith("describe="))
            {
                return line.substring("describe=".length()).trim();
            }
        }
        return "stamp without describe";
    }

    /**
     * Writes the build record into a run folder, atomically.
     * @param runFolder File; the run's output folder
     * @throws IllegalStateException when there is no stamp and unstamped runs are not allowed, or the file cannot be written
     */
    public static void recordInto(final File runFolder)
    {
        String record = require();
        try
        {
            File temp = File.createTempFile("build", ".tmp", runFolder);
            Files.writeString(temp.toPath(), record, StandardCharsets.UTF_8);
            Files.move(temp.toPath(), new File(runFolder, RUN_FILE).toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException exception)
        {
            // Not a warning: a result that cannot say what it ran on is the failure this class exists to prevent.
            throw new IllegalStateException("Cannot write " + RUN_FILE + " into " + runFolder, exception);
        }
    }

    /**
     * Reads the stamp from the classpath.
     * @return String; its content, or {@code null} when absent
     */
    private static String readStamp()
    {
        try (InputStream in = BuildProvenance.class.getClassLoader().getResourceAsStream(RESOURCE))
        {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException exception)
        {
            throw new IllegalStateException("Cannot read " + RESOURCE + " from the classpath", exception);
        }
    }
}
