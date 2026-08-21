package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;

import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioGenerator;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioSimulationScript;
import org.opentrafficsim.road.gtu.lane.LaneBasedGtu;

/**
 * One cell of the profiling matrix: a single production-length FreiburgNord run, headless, for comparing
 * build and runtime variants against each other.
 * <p>
 * The scenario is taken from {@link FreiburgStudyParameters#forDate} rather than configured here, so this
 * profiles what the {@code dates} study actually runs: the full 13:00–22:00 window, the study's behaviour
 * baseline, seed 42, and the pre-generated demand CSV used as-is. A snapshot of one hour would only show
 * one traffic regime; the full window covers free flow, the build-up into congestion and its dissipation,
 * which is what the 32-date campaign will spend its time on.
 * </p>
 * <p>
 * Because the window comes from the study definition, the simulated duration follows the demand window
 * automatically — {@code ScenarioGenerator} derives it when no explicit simulation time is set — so this
 * class cannot drift out of step with the study by hardcoding a duration of its own.
 * </p>
 *
 * <h3>What varies between cells</h3>
 * <ul>
 * <li>{@code -Dmirova.profileOut=<dir>} — output directory, one per cell, so the cells can be diffed.</li>
 * <li>{@code -Dmirova.gtuPositionCaching=true|false} — sets {@link LaneBasedGtu#CACHING}.</li>
 * <li>{@code -Dmirova.profileDate=<yyyy-MM-dd>} — the simulated date. The driving script passes the same
 * value it checked the demand CSV for, so the two cannot disagree about which day is being profiled.</li>
 * <li>{@code -Dmirova.demandDir=<dir>} — where {@code demand_<date>.csv} lives. Defaults to the
 * repository's {@code cluster/demand}; on the cluster this is the workspace demand directory.</li>
 * </ul>
 * <p>
 * The djunits variant is not a property: it is whichever artifact the classpath resolves, which is why the
 * run logs the jar it actually loaded rather than what the build intended.
 * </p>
 *
 * <h3>Why this class exists at all</h3>
 * <p>
 * {@link LaneBasedGtu#CACHING} is a plain {@code public static boolean} with no property binding, so there
 * is no way to vary it from the command line. Setting it here, before the simulation is built and therefore
 * before any GTU exists, keeps the switch confined to a profiling entry point: nothing on a production path
 * can reach it, and no OTS source is touched.
 * </p>
 * <p>
 * It also calls {@link System#exit} when finished. The simulation completes but the JVM does not terminate
 * on its own, and a matrix running several of these would otherwise accumulate live JVMs competing for the
 * very CPU it is trying to measure.
 * </p>
 * <p>
 * Demand is loaded in strict mode. A missing CSV must fail loudly rather than fall back to synthetic
 * demand: in a matrix, a silent fallback in one cell would make the cells incomparable while still
 * producing four plausible recordings.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See OpenTrafficSim License.
 * </p>
 * @author Marvin Baumann
 */
public final class RunProfileMatrix
{
    /**
     * Default simulated date. Its demand CSV covers the full day, so the study window fits inside it.
     * Overridable via {@code -Dmirova.profileDate}: the driving script also needs to know which CSV to
     * check for before it spends two builds finding out, and the two must not be able to disagree.
     */
    private static final String DEFAULT_DATE = "2025-10-13";

    /** Utility class, not instantiated. */
    private RunProfileMatrix()
    {
    }

    /**
     * Runs one cell of the matrix.
     * @param args command line arguments, unused — configured via system properties
     * @throws Exception on simulation errors
     */
    public static void main(final String[] args) throws Exception
    {
        String outDir = System.getProperty("mirova.profileOut");
        if (outDir == null || outDir.isBlank())
        {
            System.err.println("ERROR: -Dmirova.profileOut=<directory> is required.");
            System.exit(2);
        }

        String date = System.getProperty("mirova.profileDate", DEFAULT_DATE);
        String demandDir = System.getProperty("mirova.demandDir", "cluster/demand");
        File demandCsv = new File(demandDir, "demand_" + date + ".csv");
        if (!demandCsv.isFile())
        {
            System.err.println("ERROR: no demand CSV at " + demandCsv.getAbsolutePath());
            System.err.println("       Point -Dmirova.demandDir at the directory holding demand_" + date + ".csv");
            System.err.println("       (on the cluster that is <workspace>/demand).");
            System.exit(2);
        }

        // Set before anything builds a GTU, so the flag cannot change under a running vehicle.
        LaneBasedGtu.CACHING = Boolean.parseBoolean(System.getProperty("mirova.gtuPositionCaching", "true"));

        File outputDir = new File(outDir);
        outputDir.mkdirs();

        ScenarioGenerator scenario = new FreiburgNord();
        scenario.setOutputDirectory(outputDir);

        // Composed exactly as ScenarioManager.prepareRun composes a real study run: the scenario's own
        // defaults with the study variation laid over them. forDate() alone is not enough -- it carries the
        // behaviour baseline and the demand wiring, but not the scenario defaults such as mergeShare and
        // truckShare, and FreiburgNord.buildGtuTemplates fails on a null mergeShare.
        // strict = true, so a missing or unusable CSV is fatal rather than silently synthetic: in a matrix,
        // one cell quietly falling back to fabricated demand would make the cells incomparable.
        ScenarioParameters params = scenario.getDefaultParameters().copy()
                .applyOverridesFrom(FreiburgStudyParameters.forDate(date, demandCsv.getAbsolutePath(), true));

        System.out.println("[ProfileMatrix] djunits    = " + djunitsArtifact());
        System.out.println("[ProfileMatrix] CACHING    = " + LaneBasedGtu.CACHING);
        System.out.println("[ProfileMatrix] window     = " + params.get("demandStartDate", String.class)
                + " .. " + params.get("demandEndDate", String.class));
        System.out.println("[ProfileMatrix] demand CSV = " + demandCsv.getAbsolutePath());
        System.out.println("[ProfileMatrix] output     = " + outputDir.getAbsolutePath());

        ScenarioSimulationScript script = scenario.buildSimulationScript(params);
        script.setGuiEnabled(false);
        script.start();

        System.out.println("[ProfileMatrix] finished.");
        System.exit(0);
    }

    /**
     * Reports which djunits build is actually on the classpath, read from the jar's own location.
     * <p>
     * Logged so that a recording can be traced back to the artifact it was produced with, independently of
     * what the build script believed it was doing.
     * </p>
     * @return the path of the djunits code source, or a marker if it cannot be determined
     */
    private static String djunitsArtifact()
    {
        try
        {
            return org.djunits.unit.LengthUnit.class.getProtectionDomain().getCodeSource().getLocation().toString();
        }
        catch (Exception exception)
        {
            return "(could not determine)";
        }
    }
}
