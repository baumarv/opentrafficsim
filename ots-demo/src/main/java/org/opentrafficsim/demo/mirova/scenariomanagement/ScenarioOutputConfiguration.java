package org.opentrafficsim.demo.mirova.scenariomanagement;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.djunits.value.vdouble.scalar.Duration;
import org.djutils.data.Table;
import org.djutils.data.csv.CsvData;
import org.djutils.data.serialization.TextSerializationException;
import org.djutils.io.CompressedFileWriter;
import org.opentrafficsim.core.definitions.DefaultsNl;
import org.opentrafficsim.core.gtu.GtuType;
import org.opentrafficsim.kpi.sampling.SamplerData;
import org.opentrafficsim.road.network.RoadNetwork;
import org.opentrafficsim.road.network.sampling.RoadSampler;
import org.opentrafficsim.road.network.Lane;
import org.opentrafficsim.road.network.object.detector.LoopDetector;

/**
 * What a scenario records and where it writes it: the road samplers (trajectories), the loop detectors, optional custom
 * writers, and the output directory. A {@link ScenarioGenerator} fills an instance of this class and the simulation script
 * writes all of it when the simulation has ended.
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class ScenarioOutputConfiguration
{

    /** Name of the file listing vehicles removed to dissolve a deadlock. */
    public static final String DIFFUSED_VEHICLES_FILE = "diffused_vehicles.csv";

    /** Header of {@link #DIFFUSED_VEHICLES_FILE}. */
    public static final String DIFFUSED_VEHICLES_HEADER =
            "gtu_id,sim_time_sec,lane_id,dist_to_lane_end_m,active_lane_change,reason";

    /** Collects RoadSamplers configured for this scenario. */
    protected final List<RoadSampler> samplers = new ArrayList<>();

    /** Loop detectors of this scenario. */
    protected final List<LoopDetector> detectors = new ArrayList<>();

    /** Optional custom writers (e.g., KPI summaries). */
    protected final List<Runnable> customWriters = new ArrayList<>();

    /** List of extended data types to register in the sampler. */
    private final List<Object> extendedDataTypes = new ArrayList<>();

    /** List of lanes for which the scenario wants to record KPIs. */
    private final List<Lane> monitoredLanes = new ArrayList<>();

    /** Actions to configure the sampler after creation. */
    private final List<Consumer<RoadSampler>> samplerPostProcessors = new ArrayList<>();

    /** Output directory for generated files. */
    private String outputDirectory = null;

    /** Optional override for CSV output filename. */
    private String outputFilename = "trajectory_data.csv";

    /** Time at which sampling starts (default: 0s). */
    private Duration recordingStartTime = Duration.ZERO;

    /** Time at which sampling stops (default: entire simulation). */
    private Duration recordingEndTime = null;

    /** Reference to the road network (set during scenario setup). */
    private RoadNetwork roadNetwork = null;

    // ----------------------------------------------------------------------
    // Configuration API
    // ----------------------------------------------------------------------

    /**
     * Register an extended data type (e.g. ExtendedDataActionState).
     * @param extendedData instance of the extended data type to register
     * @return this ScenarioOutputConfiguration for chaining
     */
    public ScenarioOutputConfiguration addExtendedDataType(final Object extendedData)
    {
        this.extendedDataTypes.add(extendedData);
        return this;
    }

    /**
     * register multiple road samplers
     * @param samplers list of RoadSamplers to add
     * @return this ScenarioOutputConfiguration for chaining
     */
    public ScenarioOutputConfiguration addRoadSamplers(final List<RoadSampler> samplers)
    {
        this.samplers.addAll(samplers);
        return this;
    }

    /**
     * Register a lane whose vehicle trajectories should be sampled.
     * @param lane lane to monitor
     * @return this ScenarioOutputConfiguration for chaining
     */
    public ScenarioOutputConfiguration addMonitoredLane(final Lane lane)
    {
        this.monitoredLanes.add(lane);
        return this;
    }

    /**
     * Register multiple lanes whose vehicle trajectories should be sampled.
     * @param lanes list of lanes to monitor
     * @return this ScenarioOutputConfiguration for chaining
     */
    public ScenarioOutputConfiguration addMonitoredLanes(final List<Lane> lanes)
    {
        this.monitoredLanes.addAll(lanes);
        return this;
    }

    /**
     * Add a custom sampler post-processor. Allows configuring start/stop recording per lane or adding GraphPath objects.
     * @param config consumer that receives the RoadSampler for configuration
     * @return this ScenarioOutputConfiguration for chaining
     */
    public ScenarioOutputConfiguration modifySampler(final Consumer<RoadSampler> config)
    {
        this.samplerPostProcessors.add(config);
        return this;
    }

    /**
     * Set output directory for file export.
     * @param dir output directory path
     * @return this ScenarioOutputConfiguration for chaining
     */
    public ScenarioOutputConfiguration setOutputDirectory(final String dir)
    {
        this.outputDirectory = dir;
        return this;
    }

    /**
     * Set output file name.
     * @param filename output file name
     * @return this ScenarioOutputConfiguration for chaining
     */
    public ScenarioOutputConfiguration setOutputFilename(final String filename)
    {
        this.outputFilename = filename;
        return this;
    }

    /**
     * Configure start time for KPI recording.
     * @param start start time
     * @return this ScenarioOutputConfiguration for chaining
     */
    public ScenarioOutputConfiguration setRecordingStart(final Duration start)
    {
        this.recordingStartTime = start;
        return this;
    }

    /**
     * Configure end time for KPI recording.
     * @param end end time
     * @return this ScenarioOutputConfiguration for chaining
     */
    public ScenarioOutputConfiguration setRecordingEnd(final Duration end)
    {
        this.recordingEndTime = end;
        return this;
    }

    // ----------------------------------------------------------------------
    // Accessors for ScenarioManager
    // ----------------------------------------------------------------------

    // -------------------------------------------------------------

    /**
     * Returns the list of extended data types to register.
     * @return list of extended data types
     */
    public List<Object> getExtendedDataTypes()
    {
        return this.extendedDataTypes;
    }

    /**
     * Returns the list of lanes to monitor.
     * @return list of monitored lanes
     */
    public List<Lane> getMonitoredLanes()
    {
        return this.monitoredLanes;
    }

    /**
     * Returns the list of sampler post-processors.
     * @return list of sampler post-processors
     */
    public List<Consumer<RoadSampler>> getSamplerPostProcessors()
    {
        return this.samplerPostProcessors;
    }

    /**
     * Returns the output directory.
     * @return output directory
     */
    public String getOutputDirectory()
    {
        return this.outputDirectory;
    }

    /**
     * Returns the output filename.
     * @return output filename
     */
    public String getOutputFilename()
    {
        return this.outputFilename;
    }

    /**
     * Returns the recording start time.
     * @return recording start time
     */
    public Duration getRecordingStartTime()
    {
        return this.recordingStartTime;
    }

    /**
     * Returns the recording end time.
     * @return recording end time
     */
    public Duration getRecordingEndTime()
    {
        return this.recordingEndTime;
    }

    // -------------------------------------------------------------
    // Fluent builder-like API
    // -------------------------------------------------------------

    /**
     * sets the road network.
     * @param roadNetwork road network
     * @return road network
     */
    public ScenarioOutputConfiguration setRoadNetwork(final RoadNetwork roadNetwork)
    {
        this.roadNetwork = roadNetwork;
        return this;
    }

    /**
     * Returns the road network.
     * @return road network
     */
    public RoadNetwork getRoadNetwork()
    {
        return this.roadNetwork;
    }

    /**
     * Adds a RoadSampler to the configuration.
     * @param sampler RoadSampler instance
     * @return this ScenarioOutputConfiguration for chaining
     */
    public ScenarioOutputConfiguration addSampler(final RoadSampler sampler)
    {
        this.samplers.add(sampler);
        return this;
    }

    /**
     * Adds a LoopDetector to the configuration.
     * @param detector LoopDetector instance
     * @return this ScenarioOutputConfiguration for chaining
     */
    public ScenarioOutputConfiguration addLoopDetector(final LoopDetector detector)
    {
        this.detectors.add(detector);
        return this;
    }

    /**
     * Adds multiple LoopDetectors to the configuration.
     * @param detectors list of LoopDetector instances
     * @return this ScenarioOutputConfiguration for chaining
     */
    public ScenarioOutputConfiguration addLoopDetectors(final List<LoopDetector> detectors)
    {
        this.detectors.addAll(detectors);
        return this;
    }

    /**
     * Adds a custom output writer to the configuration.
     * @param writer Runnable that performs custom output writing
     * @return this ScenarioOutputConfiguration for chaining
     */
    public ScenarioOutputConfiguration addCustomWriter(final Runnable writer)
    {
        this.customWriters.add(writer);
        return this;
    }

    /**
     * Returns the list of configured RoadSamplers.
     * @return list of RoadSamplers
     */
    public List<RoadSampler> getSamplers()
    {
        return this.samplers;
    }

    /**
     * Returns the list of configured LoopDetectors.
     * @return list of LoopDetectors
     */
    public List<LoopDetector> getDetectors()
    {
        return this.detectors;
    }

    // -------------------------------------------------------------
    // Central method to write all outputs of this scenario
    // -------------------------------------------------------------

    /**
     * Writes all configured outputs to files. This includes sampler data and any custom writers.
     */
    public void writeAllOutputs()
    {
        if (this.outputDirectory == null)
        {
            System.err.println("[WARN] No output directory configured — skipping output writing.");
            return;
        }

        File outDir = new File(this.outputDirectory);
        if (!outDir.exists())
        {
            boolean ok = outDir.mkdirs();
            if (!ok)
            {
                System.err.println("[ERROR] Could not create output directory: " + this.outputDirectory);
                return;
            }
        }

        System.out.println("[OUTPUT] Writing sampler output to: " + outDir.getAbsolutePath());

        // ---------------------------------------------------------
        // Write RoadSampler output
        // ---------------------------------------------------------
        for (RoadSampler sampler : this.samplers)
        {
            try
            {
                SamplerData<?> data = sampler.getSamplerData();

                String fileName = "sampler_" + sampler.toString().replace(" ", "_") + ".csv";
                File file = new File(outDir, fileName);

                data.writeToFile(file.getAbsolutePath());

                System.out.println("[OUTPUT]   -> wrote sampler data: " + file.getName());
            }
            catch (Exception ex)
            {
                System.err.println("[ERROR] Failed to write sampler output: " + ex.getMessage());
                ex.printStackTrace();
            }
        }
        // ---------------------------------------------------------
        // Write LoopDetector output (periodic: flow + mean speed)
        // ---------------------------------------------------------
        if (this.roadNetwork != null)
        {
            try
            {
                // Query all detectors registered in the network directly — avoids relying on the detectors list copy
                Table periodicData = LoopDetector.asTablePeriodicData(this.roadNetwork, DefaultsNl.CAR, DefaultsNl.TRUCK, null);
                Table positionData = LoopDetector.asTablePositions(this.roadNetwork);

                if (!periodicData.iterator().hasNext())
                {
                    System.out.println("[OUTPUT]   -> no detectors registered in network, skipping detector output.");
                }
                else
                {
                    File periodicFile = new File(outDir, "detector_periodic.csv.zip");
                    CsvData.writeZippedData(new CompressedFileWriter(periodicFile.getAbsolutePath()),
                            "detector_periodic.csv", "detector_periodic.csvm", periodicData);
                    System.out.println("[OUTPUT]   -> wrote detector periodic data: " + periodicFile.getName());

                    File posFile = new File(outDir, "detector_positions.csv.zip");
                    CsvData.writeZippedData(new CompressedFileWriter(posFile.getAbsolutePath()),
                            "detector_positions.csv", "detector_positions.csvm", positionData);
                    System.out.println("[OUTPUT]   -> wrote detector positions: " + posFile.getName());
                }
            }
            catch (IOException | TextSerializationException ex)
            {
                System.err.println("[ERROR] Failed to write loop detector output: " + ex.getMessage());
                ex.printStackTrace();
            }
        }

        // The driver models on this branch remove no vehicle to dissolve a deadlock, so the file carries its header and no
        // rows. It is still written, with the header it always had, so that post-processing finds the file it expects.
        File diffusionFile = new File(outDir, DIFFUSED_VEHICLES_FILE);
        try
        {
            Files.writeString(diffusionFile.toPath(), DIFFUSED_VEHICLES_HEADER + "\n", StandardCharsets.UTF_8);
        }
        catch (IOException ex)
        {
            System.err.println("[ERROR] Failed to write " + DIFFUSED_VEHICLES_FILE + ": " + ex.getMessage());
        }

        // ---------------------------------------------------------
        // Write additional scenario-level custom exports (optional)
        // ---------------------------------------------------------
        for (Runnable r : this.customWriters)
        {
            try
            {
                r.run();
            }
            catch (Exception e)
            {
                System.err.println("[ERROR] Custom output writer failed: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    // ----------------------------------------------------------------------
    // Helper
    // ----------------------------------------------------------------------

    @Override
    public String toString()
    {
        return "ScenarioOutputConfiguration[" + "extendedDataTypes=" + this.extendedDataTypes.size() + ", monitoredLanes="
                + this.monitoredLanes.size() + ", outputDirectory=" + this.outputDirectory + ", filename=" + this.outputFilename
                + "]";
    }
}
