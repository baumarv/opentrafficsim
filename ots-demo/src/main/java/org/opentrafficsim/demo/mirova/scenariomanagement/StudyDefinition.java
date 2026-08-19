package org.opentrafficsim.demo.mirova.scenariomanagement;

import java.util.Map;

/**
 * A complete, self-contained definition of a simulation study: it registers its scenarios, parameter variations and
 * replication count into a {@link ScenarioManager}.
 * <p>
 * Registration must be <b>deterministic</b>: every process that registers the same study with the same options must produce
 * exactly the same scenarios, in the same order, each with the same variations in the same order. This is what makes a global
 * run index a stable address for one specific run across independently started processes, which is how the cluster executes a
 * study as a SLURM job array of one run per task.
 * </p>
 * <p>
 * Adding a new study therefore requires only a new implementation of this interface plus its registration in
 * {@link StudyRegistry} — no changes to the cluster entry point or to the batch script.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author Marvin Baumann
 */
public interface StudyDefinition
{
    /**
     * Returns the short name by which this study is selected on the command line.
     * @return String; the study name
     */
    String getName();

    /**
     * Returns a one-line description of what this study varies, for logging and manifests.
     * @return String; the description
     */
    String getDescription();

    /**
     * Registers all scenarios, parameter variations and the replication count of this study into the given manager.
     * @param manager ScenarioManager; the manager to register into
     * @param options Map&lt;String, String&gt;; the options passed on the command line as {@code --key=value}, e.g. the demand
     *            CSV location; implementations document which keys they honour and must apply sensible defaults for absent
     *            ones
     * @throws Exception when the study cannot be registered, e.g. because a required option is missing or invalid
     */
    void register(ScenarioManager manager, Map<String, String> options) throws Exception;
}
