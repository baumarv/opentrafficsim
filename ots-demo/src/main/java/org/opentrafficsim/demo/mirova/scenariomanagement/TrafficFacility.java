package org.opentrafficsim.demo.mirova.scenariomanagement;

/**
 * A modelled traffic facility: the scenario generator that builds it, the behavioural baseline its studies are measured
 * against, and the naming its output folders use.
 * <p>
 * This bundles everything a study needs to know about <i>where</i> it simulates, so that a study can describe <i>what</i> it
 * varies without naming a facility. {@link org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.DateStudy} is fully
 * facility-agnostic on this basis; the parameter and combination studies keep facility-specific content but obtain the
 * generator class and baseline through the same mechanism.
 * </p>
 * <p>
 * Adding a facility means implementing this interface and, optionally, registering a short name in
 * {@link FacilityRegistry} — the study layer, the cluster entry point and the batch script need no changes.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author Marvin Baumann
 */
public interface TrafficFacility
{
    /**
     * Returns the short name by which this facility is selected on the command line.
     * @return String; the facility name
     */
    String getName();

    /**
     * Returns the scenario generator that builds this facility's network, demand and output configuration.
     * @return Class&lt;? extends ScenarioGenerator&gt;; the generator class
     */
    Class<? extends ScenarioGenerator> getGeneratorClass();

    /**
     * Returns this facility's behavioural baseline: the driving-behaviour parameters every study of it is measured against,
     * independent of period or demand. A study overrides only what it actually varies.
     * @return ScenarioParameters; a fresh instance holding the baseline
     */
    ScenarioParameters baseBehaviorParams();

    /**
     * Returns the scenario name for one simulated date, optionally qualified by the labels of a study's swept values.
     * <p>
     * The parts are appended so that an output folder identifies its run without resolving a numeric index against the
     * study definition. Calling this without parts yields the plain per-date name.
     * </p>
     * @param date String; the simulated date in yyyy-MM-dd form
     * @param suffixParts String...; optional labels appended in order, e.g. a parameter combination name
     * @return String; the scenario name, which is also the output sub-directory name
     */
    String scenarioName(String date, String... suffixParts);

    /**
     * Returns the parameters for simulating one date at this facility: the behavioural baseline plus the demand wiring for
     * that date - period, aggregation, the pre-generated CSV and the strictness flag.
     * @param date String; the simulated date in yyyy-MM-dd form
     * @param demandCsvPath String; the absolute path of the pre-generated demand CSV for this date
     * @param strict boolean; when true, a missing or unreadable demand CSV is fatal instead of falling back to synthetic
     *            demand
     * @return ScenarioParameters; the parameters for that date
     */
    ScenarioParameters forDate(String date, String demandCsvPath, boolean strict);
}
