package org.opentrafficsim.demo.mirova.scenariomanagement;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Writes the metadata file of a run: what was run, with which model and parameters, and from which code.
 * <p>
 * Every run folder gets a {@value #FILE} before anything is simulated, so that a result can always say what produced it, and
 * the file is completed when the run has finished. It records the two commits a result depends on: the commit of this
 * repository, and the commit of upstream OpenTrafficSim the branch is based on.
 * </p>
 * <p>
 * The commit of this repository is taken from the system property {@value #PROPERTY_GIT_COMMIT} when the launcher passes it
 * (the cluster build records the commit it built, and the batch script hands it on). Otherwise it is asked from git in the
 * checkout the classes were loaded from, which is correct as long as the classes were built from the checked-out commit. The
 * upstream commit is read from the tracked file {@value #UPSTREAM_FILE}, or from {@value #PROPERTY_UPSTREAM_COMMIT}.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public final class RunMetadata
{
    /** Name of the metadata file in a run folder. */
    public static final String FILE = "run.properties";

    /** System property carrying the git commit of this repository the classes were built from. */
    public static final String PROPERTY_GIT_COMMIT = "mirova.gitCommit";

    /** System property carrying the upstream OpenTrafficSim commit the branch is based on. */
    public static final String PROPERTY_UPSTREAM_COMMIT = "mirova.upstreamCommit";

    /** System property that allows a run whose commits cannot be determined; its metadata then says {@value #UNKNOWN}. */
    public static final String PROPERTY_ALLOW_UNRECORDED = "mirova.allowUnrecordedBuild";

    /** Path, relative to the repository root, of the tracked file holding the upstream commit. */
    public static final String UPSTREAM_FILE = "cluster/UPSTREAM_COMMIT";

    /** What is recorded for a commit that could not be determined. */
    public static final String UNKNOWN = "unknown";

    /** Suffix a launcher appends to the commit when the classes were built from a tree with uncommitted changes. */
    public static final String DIRTY_SUFFIX = "-dirty";

    /** Seconds a git query may take. */
    private static final long GIT_TIMEOUT_SECONDS = 20L;

    /** The repository root, resolved once; {@code null} when the classes were not loaded from a checkout. */
    private static final File REPOSITORY_ROOT = findRepositoryRoot();

    /**
     * Utility class.
     */
    private RunMetadata()
    {
        // utility class
    }

    /**
     * Returns the git commit of this repository the run is attributed to.
     * @return String; the full commit hash, or {@value #UNKNOWN}
     */
    public static String gitCommit()
    {
        String given = System.getProperty(PROPERTY_GIT_COMMIT);
        if (given != null && !given.isBlank())
        {
            return given.trim();
        }
        String resolved = git("rev-parse", "HEAD");
        return resolved == null || resolved.isBlank() ? UNKNOWN : resolved.trim();
    }

    /**
     * Returns whether the checkout has uncommitted changes to tracked files, in which case the commit does not describe the
     * code that ran.
     * @return String; {@code "true"}, {@code "false"}, or {@value #UNKNOWN} when git could not be asked
     */
    public static String gitDirty()
    {
        String given = System.getProperty(PROPERTY_GIT_COMMIT);
        if (given != null && !given.isBlank())
        {
            // A launcher that records the commit marks a build from a changed tree by this suffix.
            return String.valueOf(given.trim().endsWith(DIRTY_SUFFIX));
        }
        String status = git("status", "--porcelain", "--untracked-files=no");
        return status == null ? UNKNOWN : String.valueOf(!status.isBlank());
    }

    /**
     * Returns the commit of upstream OpenTrafficSim the branch is based on.
     * @return String; the full commit hash, or {@value #UNKNOWN}
     */
    public static String upstreamCommit()
    {
        String given = System.getProperty(PROPERTY_UPSTREAM_COMMIT);
        if (given != null && !given.isBlank())
        {
            return given.trim();
        }
        if (REPOSITORY_ROOT != null)
        {
            File file = new File(REPOSITORY_ROOT, UPSTREAM_FILE);
            if (file.isFile())
            {
                try
                {
                    for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8))
                    {
                        if (!line.isBlank() && !line.trim().startsWith("#"))
                        {
                            return line.trim();
                        }
                    }
                }
                catch (IOException exception)
                {
                    return UNKNOWN;
                }
            }
        }
        return UNKNOWN;
    }

    /**
     * Refuses to continue when either commit cannot be determined, unless {@value #PROPERTY_ALLOW_UNRECORDED} is set.
     * @throws IllegalStateException when a commit is unknown and unrecorded runs are not allowed
     */
    public static void require()
    {
        boolean unknown = UNKNOWN.equals(gitCommit()) || UNKNOWN.equals(upstreamCommit());
        if (unknown && !Boolean.getBoolean(PROPERTY_ALLOW_UNRECORDED))
        {
            throw new IllegalStateException("The commits this run is built from cannot be determined (git commit: "
                    + gitCommit() + ", upstream commit: " + upstreamCommit() + "). Pass -D" + PROPERTY_GIT_COMMIT + " and -D"
                    + PROPERTY_UPSTREAM_COMMIT + ", run from a git checkout that tracks " + UPSTREAM_FILE + ", or allow an "
                    + "unrecorded run explicitly with -D" + PROPERTY_ALLOW_UNRECORDED + "=true.");
        }
    }

    /**
     * Returns a one-line description of the code that runs, for the top of a log.
     * @return String; the git commit, whether the checkout is dirty, and the upstream commit
     */
    public static String describeBuild()
    {
        return "git " + gitCommit() + " (dirty: " + gitDirty() + "), upstream OTS " + upstreamCommit();
    }

    /**
     * Writes the metadata file into a run folder, replacing an existing one atomically.
     * @param runFolder File; the folder of the run
     * @param entries Map&lt;String, String&gt;; key to value, written in iteration order
     * @throws IllegalStateException when the file cannot be written
     */
    public static void write(final File runFolder, final Map<String, String> entries)
    {
        StringBuilder text = new StringBuilder();
        text.append("# Run metadata, written by ").append(RunMetadata.class.getSimpleName()).append(".\n");
        for (Map.Entry<String, String> entry : entries.entrySet())
        {
            String value = entry.getValue() == null ? "" : entry.getValue().replace("\r", " ").replace("\n", " ");
            text.append(entry.getKey()).append('=').append(value).append('\n');
        }
        try
        {
            File temp = File.createTempFile("run", ".tmp", runFolder);
            Files.writeString(temp.toPath(), text.toString(), StandardCharsets.UTF_8);
            Files.move(temp.toPath(), new File(runFolder, FILE).toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException exception)
        {
            // A result that cannot say what produced it is the failure this file exists to prevent.
            throw new IllegalStateException("Cannot write " + FILE + " into " + runFolder, exception);
        }
    }

    /**
     * Runs a git command in the repository the classes were loaded from.
     * @param arguments String...; the arguments after {@code git -C <repository>}
     * @return String; the standard output, or {@code null} when there is no repository or git failed
     */
    private static String git(final String... arguments)
    {
        if (REPOSITORY_ROOT == null)
        {
            return null;
        }
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(REPOSITORY_ROOT.getAbsolutePath());
        command.addAll(List.of(arguments));
        try
        {
            Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)))
            {
                String line;
                while ((line = reader.readLine()) != null)
                {
                    output.append(line).append('\n');
                }
            }
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            {
                process.destroyForcibly();
                return null;
            }
            return process.exitValue() == 0 ? output.toString() : null;
        }
        catch (IOException exception)
        {
            return null;
        }
        catch (InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Finds the root of the git checkout the classes of this module were loaded from.
     * @return File; the repository root, or {@code null} when the classes do not lie inside a checkout
     */
    private static File findRepositoryRoot()
    {
        try
        {
            File location = new File(RunMetadata.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            for (File directory = location; directory != null; directory = directory.getParentFile())
            {
                // In a linked worktree '.git' is a file, in a plain checkout a directory.
                if (new File(directory, ".git").exists())
                {
                    return directory;
                }
            }
        }
        catch (URISyntaxException | SecurityException | NullPointerException exception)
        {
            return null;
        }
        return null;
    }
}
