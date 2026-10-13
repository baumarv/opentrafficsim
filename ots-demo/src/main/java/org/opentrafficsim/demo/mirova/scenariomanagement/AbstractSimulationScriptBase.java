package org.opentrafficsim.demo.mirova.scenariomanagement;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.djunits.value.vdouble.scalar.Duration;
import org.djutils.event.Event;
import org.djutils.event.EventListener;
import org.djutils.exceptions.Throw;
import org.opentrafficsim.core.dsol.AbstractOtsModel;
import org.opentrafficsim.core.dsol.OtsAnimator;
import org.opentrafficsim.core.dsol.OtsSimulator;
import org.opentrafficsim.core.dsol.OtsSimulatorInterface;
import org.opentrafficsim.core.perception.HistoryManagerDevs;
import org.opentrafficsim.road.network.RoadNetwork;
import org.opentrafficsim.swing.gui.OtsSimulationApplication;
import org.opentrafficsim.swing.gui.OtsSimulationPanel;
import org.opentrafficsim.swing.gui.OtsSimulationPanelDecorator;

import nl.tudelft.simulation.dsol.SimRuntimeException;
import nl.tudelft.simulation.dsol.experiment.Replication;
import nl.tudelft.simulation.jstats.streams.MersenneTwister;
import nl.tudelft.simulation.jstats.streams.StreamInterface;

/**
 * Base class for programmatic OTS simulations without command-line bindings.
 * <p>
 * It follows upstream's {@code AbstractSimulationScript}, but is configured through setters instead of command-line options,
 * so that a batch of runs with different seeds and parameters can be built in one process, and it steps a headless run on the
 * calling thread.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public abstract class AbstractSimulationScriptBase implements EventListener
{
    /** Interval of the history manager's clean-up. */
    private static final Duration HISTORY_CLEANUP_INTERVAL = Duration.ofSI(10.0);

    /** Simulated time between two progress reports of a headless run. */
    private static final Duration REPORT_INTERVAL = Duration.ofSI(10.0);

    /** Seed of the run. */
    private long seed = 1L;

    /** Time of day at which the simulation starts. */
    private Duration startTimeOfDay = Duration.ZERO;

    /** Warm-up time. */
    private Duration warmupTime = Duration.ZERO;

    /** Simulation time, including the warm-up time. */
    private Duration simulationTime = Duration.ofSI(600.0);

    /** Time for which the history of GTU states is kept. */
    private Duration historyTime = Duration.ZERO;

    /** Whether the run shows a GUI; false for batch runs. */
    private boolean enableGui = true;

    /** The simulator. */
    private OtsSimulatorInterface simulator;

    /** The network. */
    private RoadNetwork network;

    /** Whether the run was aborted. */
    private boolean aborted = false;

    /** Whether {@link #onSimulationEnd()} has run, so that the outputs are written once. */
    private boolean ended = false;

    /** Name of the run. */
    private final String name;

    /** Description of the run. */
    private final String description;

    /**
     * Constructor.
     * @param name String; name of the run
     * @param description String; description of the run
     */
    protected AbstractSimulationScriptBase(final String name, final String description)
    {
        this.name = name;
        this.description = description;
    }

    /**
     * Sets the seed.
     * @param seed long; the seed
     */
    public void setSeed(final long seed)
    {
        this.seed = seed;
    }

    /**
     * Sets the time of day at which the simulation starts.
     * @param startTimeOfDay Duration; the start time of day
     */
    public void setStartTime(final Duration startTimeOfDay)
    {
        this.startTimeOfDay = startTimeOfDay;
    }

    /**
     * Sets the warm-up time.
     * @param warmupTime Duration; the warm-up time
     */
    public void setWarmupTime(final Duration warmupTime)
    {
        this.warmupTime = warmupTime;
    }

    /**
     * Sets the simulation time, including the warm-up time.
     * @param simulationTime Duration; the simulation time
     */
    public void setSimulationTime(final Duration simulationTime)
    {
        this.simulationTime = simulationTime;
    }

    /**
     * Sets the time for which the history of GTU states is kept.
     * @param historyTime Duration; the history time
     */
    public void setHistoryTime(final Duration historyTime)
    {
        this.historyTime = historyTime;
    }

    /**
     * Sets whether the run shows a GUI.
     * @param enableGui boolean; true for a GUI run, false for a headless run
     */
    public void setGuiEnabled(final boolean enableGui)
    {
        this.enableGui = enableGui;
    }

    /**
     * Returns the description of the run.
     * @return String; the description
     */
    public String getDescription()
    {
        return this.description;
    }

    /**
     * Aborts the run: a headless run stops stepping, and {@link #start()} reports the abort.
     */
    public void abort()
    {
        this.aborted = true;
        try
        {
            if (this.simulator != null)
            {
                this.simulator.stop();
            }
        }
        catch (Exception exception)
        {
            // the simulator may already be stopped, which is what is wanted
        }
    }

    /**
     * Returns whether the run was aborted.
     * @return boolean; whether the run was aborted
     */
    public boolean isAborted()
    {
        return this.aborted;
    }

    /**
     * Starts the simulation. A headless run returns when the simulation has ended; a GUI run returns once the window is up.
     * @throws Exception when the simulation cannot be set up, fails, or was aborted
     */
    public final void start() throws Exception
    {
        Throw.when(this.seed < 0, IllegalArgumentException.class, "Seed must be >= zero");
        Throw.when(this.warmupTime.si < 0.0, IllegalArgumentException.class, "Warmup must be >= 0");
        Throw.when(this.simulationTime.si < this.warmupTime.si, IllegalArgumentException.class,
                "Simulation time must be >= warmup time");
        if (this.enableGui)
        {
            runWithGui();
        }
        else
        {
            runHeadless();
        }
        if (this.aborted)
        {
            throw new SimRuntimeException("Simulation run was aborted (watchdog: no vehicle passed any detector).");
        }
    }

    /**
     * Runs the simulation without a GUI, stepping the simulator on the calling thread.
     * @throws Exception when the simulation cannot be set up or fails
     */
    private void runHeadless() throws Exception
    {
        this.simulator = new OtsSimulator(this.name);
        ScriptModel model = new ScriptModel(this.simulator);
        int replicationId = (int) Thread.currentThread().getId();
        this.simulator.initialize(this.startTimeOfDay, this.warmupTime, this.simulationTime, model,
                new HistoryManagerDevs(this.simulator, this.historyTime, HISTORY_CLEANUP_INTERVAL), replicationId);

        double simEnd = this.simulationTime.si;
        double nextReport = 0.0;
        int lastPercent = -1;
        while (this.simulator.getSimulatorTime().si < simEnd && !this.aborted)
        {
            this.simulator.step();
            double t = this.simulator.getSimulatorTime().si;
            if (t >= nextReport)
            {
                int percent = (int) Math.round((t / simEnd) * 100.0);
                if (percent != lastPercent)
                {
                    // Locale.ROOT: a log line is read by scripts, and must not change with the machine it was written on.
                    System.out.println(String.format(Locale.ROOT, "[SIM %s] %s %3d%%  t=%.0f/%.0f s", this.name,
                            progressBar(percent, 30), percent, t, simEnd));
                    lastPercent = percent;
                }
                nextReport += REPORT_INTERVAL.si;
            }
        }
        endOnce();
    }

    /**
     * Runs the simulation with a GUI.
     * @throws Exception when the simulation or the GUI cannot be set up
     */
    private void runWithGui() throws Exception
    {
        this.simulator = new OtsAnimator(this.name);
        ScriptModel model = new ScriptModel(this.simulator);
        this.simulator.initialize(this.startTimeOfDay, this.warmupTime, this.simulationTime, model,
                new HistoryManagerDevs(this.simulator, this.historyTime, HISTORY_CLEANUP_INTERVAL));
        OtsSimulationPanel simulationPanel = new OtsSimulationPanel(model.getNetwork(), getDecorator());
        OtsSimulationApplication<ScriptModel> application = new OtsSimulationApplication<>(model, simulationPanel);
        application.setExitOnClose(true);
        simulationPanel.enableSimulationControlButtons();
    }

    @Override
    public void notify(final Event event)
    {
        if (event.getType().equals(Replication.END_REPLICATION_EVENT))
        {
            endOnce();
        }
    }

    /**
     * Calls {@link #onSimulationEnd()} unless it has been called already. The end of a headless run is reached both by the
     * stepping loop and by the replication's own end event.
     */
    private void endOnce()
    {
        if (!this.ended)
        {
            this.ended = true;
            onSimulationEnd();
        }
    }

    /**
     * Builds the scenario in the given simulator.
     * @param sim OtsSimulatorInterface; the simulator
     * @return RoadNetwork; the network of the scenario
     * @throws Exception when the scenario cannot be built
     */
    protected abstract RoadNetwork setupSimulation(OtsSimulatorInterface sim) throws Exception;

    /**
     * Called once when the simulation has ended; the place to write output. The default does nothing.
     */
    protected void onSimulationEnd()
    {
        // nothing by default
    }

    /**
     * Returns the decorator of the GUI: colorers, markers, toggles, tabs. The default is upstream's default decorator.
     * @return OtsSimulationPanelDecorator; the decorator
     */
    protected OtsSimulationPanelDecorator getDecorator()
    {
        return new OtsSimulationPanelDecorator()
        {
        };
    }

    /**
     * Renders a textual progress bar.
     * @param percent int; the progress in percent
     * @param width int; the width of the bar in characters
     * @return String; the bar
     */
    private static String progressBar(final int percent, final int width)
    {
        int filled = Math.max(0, Math.min(width, (percent * width) / 100));
        char[] bar = new char[width];
        Arrays.fill(bar, 0, filled, '#');
        Arrays.fill(bar, filled, width, '-');
        return "[" + new String(bar) + "]";
    }

    /**
     * The model that builds the scenario when the simulator is initialised.
     */
    private class ScriptModel extends AbstractOtsModel
    {
        /**
         * Constructor.
         * @param sim OtsSimulatorInterface; the simulator
         */
        ScriptModel(final OtsSimulatorInterface sim)
        {
            super(sim);
            AbstractSimulationScriptBase.this.simulator = sim;
        }

        @Override
        public void constructModel() throws SimRuntimeException
        {
            // The same two streams, seeded the same way, as upstream's AbstractSimulationScript and as the runs so far.
            Map<String, StreamInterface> streams = new LinkedHashMap<>();
            streams.put("generation", new MersenneTwister(AbstractSimulationScriptBase.this.seed));
            streams.put("default", new MersenneTwister(AbstractSimulationScriptBase.this.seed + 1));
            this.simulator.getModel().getStreams().putAll(streams);
            try
            {
                AbstractSimulationScriptBase.this.network = setupSimulation(this.simulator);
            }
            catch (Throwable throwable)
            {
                throwable.printStackTrace();
                throw new RuntimeException("Exception during setupSimulation(): " + throwable.getMessage(), throwable);
            }
            this.simulator.addListener(AbstractSimulationScriptBase.this, Replication.END_REPLICATION_EVENT);
        }

        @Override
        public RoadNetwork getNetwork()
        {
            return AbstractSimulationScriptBase.this.network;
        }
    }
}
