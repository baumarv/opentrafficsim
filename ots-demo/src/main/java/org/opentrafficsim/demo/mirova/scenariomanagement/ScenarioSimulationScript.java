package org.opentrafficsim.demo.mirova.scenariomanagement;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

import org.djunits.value.vdouble.scalar.Duration;
import org.djutils.event.Event;
import org.djutils.event.EventListener;
import org.opentrafficsim.core.dsol.OtsSimulatorInterface;
import org.opentrafficsim.demo.mirova.scenariomanagement.sampling.GtuDeletionDiagnostics;
import org.opentrafficsim.road.network.RoadNetwork;
import org.opentrafficsim.road.network.object.detector.LoopDetector;

import nl.tudelft.simulation.dsol.SimRuntimeException;

/**
 * The simulation script the {@link ScenarioManager} runs a scenario with.
 * <p>
 * It applies the {@link ScenarioParameters} of the run (seed, timings), lets the {@link ScenarioGenerator} build the scenario,
 * installs a watchdog that aborts a run in which traffic has stopped altogether, and writes the outputs of the
 * {@link ScenarioOutputConfiguration} when the simulation ends.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class ScenarioSimulationScript extends AbstractSimulationScriptBase
{
    /** Interval between two watchdog checks. */
    private static final Duration WATCHDOG_INTERVAL = Duration.ofSI(10.0);

    /** Time after the start of the run during which the watchdog does not act, so that the network can fill. */
    private static final Duration WATCHDOG_GRACE_PERIOD = Duration.ofSI(300.0);

    /** Time without any detector passage after which the watchdog aborts the run. */
    private static final Duration WATCHDOG_TIMEOUT = Duration.ofSI(180.0);

    /** Scenario generator. */
    private final ScenarioGenerator scenario;

    /** Parameters of the run. */
    private final ScenarioParameters parameters;

    /** Output configuration; known once the scenario is built. */
    private ScenarioOutputConfiguration outputConfig;

    /**
     * Constructor.
     * @param scenario ScenarioGenerator; the scenario to run
     * @param params ScenarioParameters; the parameters of the run
     */
    public ScenarioSimulationScript(final ScenarioGenerator scenario, final ScenarioParameters params)
    {
        super("Scenario-" + scenario.scenarioName + " (seed " + params.getSeed() + ")", "Scenario simulation runner");
        this.scenario = scenario;
        this.parameters = params;
        this.outputConfig = null;
        applyParameters(params);
    }

    /**
     * Applies the seed and the timings of the given parameters to this script.
     * @param p ScenarioParameters; the parameters of the run
     */
    private void applyParameters(final ScenarioParameters p)
    {
        if (p == null)
        {
            return;
        }
        if (p.getSeed() != null)
        {
            setSeed(p.getSeed());
        }
        if (p.getStartTime() != null)
        {
            setStartTime(p.getStartTime());
        }
        if (p.getWarmupTime() != null)
        {
            setWarmupTime(p.getWarmupTime());
        }
        if (p.getSimulationTime() != null)
        {
            setSimulationTime(p.getSimulationTime());
        }
    }

    @Override
    protected RoadNetwork setupSimulation(final OtsSimulatorInterface sim) throws Exception
    {
        RoadNetwork network = this.scenario.setupSimulation(sim, this.parameters);
        this.outputConfig = this.scenario.getOutputConfiguration();
        // The end of setup: the network and the vehicle templates exist, so the driver model has been selected - and nothing
        // has been simulated yet. A scenario that never selected one is refused here, where it costs seconds, not a run.
        ScenarioGenerator.requireSelectionHappened(this.scenario.getScenarioName(),
                this.parameters.getOrDefault(ScenarioGenerator.KEY_DRIVER_MODEL, DriverModels.LMRS, String.class));
        setupWatchdog(sim);
        return network;
    }

    /**
     * Sets the output directory of this run.
     * @param outputDirectory String; the output directory
     */
    public void setOutputDirectory(final String outputDirectory)
    {
        if (this.outputConfig != null)
        {
            this.outputConfig.setOutputDirectory(outputDirectory);
        }
        else
        {
            System.out.println("Warning: output configuration is null, cannot set output directory");
        }
    }

    @Override
    protected void onSimulationEnd()
    {
        if (this.outputConfig != null)
        {
            this.outputConfig.writeAllOutputs();
            if (this.outputConfig.getOutputDirectory() != null)
            {
                try
                {
                    GtuDeletionDiagnostics.write(new File(this.outputConfig.getOutputDirectory()));
                }
                catch (IOException exception)
                {
                    System.err.println("[ERROR] Failed to write the removed-vehicle records: " + exception.getMessage());
                }
            }
        }
        // The vehicles are gone by now and nothing else says why any of them was removed.
        GtuDeletionDiagnostics.report();
    }

    /**
     * Installs the watchdog: it listens to every loop detector of the scenario and aborts the run when none of them has seen
     * a vehicle for {@link #WATCHDOG_TIMEOUT}. Disabled with the scenario parameter {@code enableWatchdog=false}.
     * @param sim OtsSimulatorInterface; the simulator
     */
    private void setupWatchdog(final OtsSimulatorInterface sim)
    {
        Boolean enableWatchdog = this.parameters.getOrDefault("enableWatchdog", true, Boolean.class);
        if (!enableWatchdog)
        {
            System.out.println("[Watchdog] Watchdog disabled via parameters.");
            return;
        }
        List<LoopDetector> detectors = this.scenario.getLoopDetectors();
        if (detectors.isEmpty())
        {
            System.out.println("[Watchdog] No detectors found. Watchdog disabled.");
            return;
        }
        System.out.println("[Watchdog] Registered " + detectors.size() + " detectors for deadlock detection.");

        final Duration[] lastVehiclePassTime = new Duration[] {Duration.ZERO};
        EventListener listener = new EventListener()
        {
            @Override
            public void notify(final Event event)
            {
                lastVehiclePassTime[0] = sim.getSimulatorTime();
            }
        };
        for (LoopDetector detector : detectors)
        {
            detector.addListener(listener, LoopDetector.LOOP_DETECTOR_TRIGGERED);
        }
        scheduleWatchdogCheck(sim, lastVehiclePassTime);
    }

    /**
     * Schedules the next watchdog check.
     * @param sim OtsSimulatorInterface; the simulator
     * @param lastVehiclePassTime Duration[]; one-element holder of the time a vehicle last passed any detector
     */
    private void scheduleWatchdogCheck(final OtsSimulatorInterface sim, final Duration[] lastVehiclePassTime)
    {
        try
        {
            sim.scheduleEventRel(WATCHDOG_INTERVAL, this, "checkDeadlock", new Object[] {sim, lastVehiclePassTime});
        }
        catch (SimRuntimeException exception)
        {
            System.err.println("[Watchdog] Failed to schedule deadlock check event.");
            exception.printStackTrace();
        }
    }

    /**
     * Aborts the run when no vehicle has passed any detector for {@link #WATCHDOG_TIMEOUT}; otherwise schedules the next check.
     * Called by the simulator through reflection.
     * @param sim OtsSimulatorInterface; the simulator
     * @param lastVehiclePassTime Duration[]; one-element holder of the time a vehicle last passed any detector
     */
    @SuppressWarnings("unused")
    private void checkDeadlock(final OtsSimulatorInterface sim, final Duration[] lastVehiclePassTime)
    {
        Duration now = sim.getSimulatorTime();
        if (now.ge(WATCHDOG_GRACE_PERIOD))
        {
            Duration sinceLastVehicle = now.minus(lastVehiclePassTime[0]);
            if (sinceLastVehicle.gt(WATCHDOG_TIMEOUT))
            {
                System.out.println(String.format(Locale.ROOT,
                        "[WATCHDOG] DEADLOCK DETECTED! No vehicles measured on any detectors for %.1f seconds "
                                + "(current time: %.1f s). Aborting simulation run.",
                        sinceLastVehicle.si, now.si));
                abort();
                return;
            }
        }
        scheduleWatchdogCheck(sim, lastVehiclePassTime);
    }
}
