package org.opentrafficsim.demo.mirova.scenariomanagement;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.djunits.unit.DurationUnit;
import org.djunits.unit.FrequencyUnit;
import org.djunits.value.storage.StorageType;
import org.djunits.value.vdouble.scalar.Length;
import org.djunits.value.vdouble.vector.DurationVector;
import org.djunits.value.vdouble.vector.FrequencyVector;
import org.djunits.value.vdouble.vector.data.DoubleVectorData;
import org.opentrafficsim.core.dsol.OtsSimulatorInterface;
import org.opentrafficsim.core.gtu.GtuCharacteristics;
import org.opentrafficsim.core.gtu.GtuException;
import org.opentrafficsim.core.gtu.GtuType;
import org.opentrafficsim.core.network.NetworkException;
import org.opentrafficsim.core.network.Node;
import org.opentrafficsim.core.network.route.Route;
import org.opentrafficsim.road.gtu.VehicleModel;
import org.opentrafficsim.road.gtu.generator.characteristics.LaneBasedGtuCharacteristics;
import org.opentrafficsim.road.gtu.generator.characteristics.LaneBasedGtuCharacteristicsGeneratorOd;
import org.opentrafficsim.road.gtu.generator.characteristics.LaneBasedGtuTemplate;
import org.opentrafficsim.road.gtu.generator.headway.HeadwayGenerator;
import org.opentrafficsim.road.gtu.strategical.LaneBasedStrategicalPlannerFactory;
import org.opentrafficsim.road.network.Lane;
import org.opentrafficsim.road.network.LanePosition;
import org.opentrafficsim.road.network.RoadNetwork;
import org.opentrafficsim.road.network.object.detector.LoopDetector;
import org.opentrafficsim.road.network.sampling.RoadSampler;
import org.opentrafficsim.road.od.Categorization;
import org.opentrafficsim.road.od.Category;
import org.opentrafficsim.road.od.Interpolation;
import org.opentrafficsim.road.od.OdMatrix;

import nl.tudelft.simulation.jstats.streams.MersenneTwister;
import nl.tudelft.simulation.jstats.streams.StreamInterface;

/**
 * Abstract base class that holds everything needed to define one simulation scenario.
 * <p>
 * A concrete scenario defines the road network, the GTU templates (vehicle dimensions and maximum-speed distributions), the
 * routes, the demand, the detectors and samplers, and its default parameters. It does not define a driver model: the planner
 * factory of each vehicle class is built by the {@link DriverModelProvider} that the scenario parameter
 * {@link #KEY_DRIVER_MODEL} selects. A {@link ScenarioManager} asks a scenario for all of this and runs replications with
 * different seeds.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public abstract class ScenarioGenerator
{

    /** Human-readable name of the scenario. */
    protected final String scenarioName;

    /** map as container for routes for this scenario (keyed by route name). */
    protected Map<String, Route> routes = new HashMap<>();

    /** GTU templates for this scenario (keyed by GTU type). */
    protected Map<GtuType, LaneBasedGtuTemplate> gtuTemplates = new HashMap<>();


    /**
     * The strategical planner factory built for each GTU type, kept by the scenario so that the OD characteristics generator
     * can hand it to every vehicle without asking the template for it.
     */
    protected Map<GtuType, LaneBasedStrategicalPlannerFactory<?>> strategicalPlannerFactories = new LinkedHashMap<>();

    /** OD matrix for demand definition. */
    protected OdMatrix odMatrix = null;

    /** Headway generator for direct generation (alternative to OD matrix). */
    protected HeadwayGenerator headwayGenerator = null;

    /** The road network for this scenario. */
    protected RoadNetwork network = null;

    /**
     * Random stream for driver population draws: desired speed, the IDM speed adherence factor, and route choice.
     * <p>
     * This stream must stay disjoint from the {@code "generation"} stream that {@code OdApplier} uses for arrival
     * times. Build it with {@link #newBehaviourStream(long)} rather than from the run seed directly - seeding it with
     * the run seed produces a second generator emitting the very same sequence the arrival process consumes, so the
     * two subsystems draw identical numbers while the code reads as though they were independent.
     * </p>
     */
    protected StreamInterface stream = null;

    /**
     * Role identifier of the behaviour stream, mixed into the run seed by {@link #deriveSeed(long, long)}.
     * <p>
     * The value is the odd 64-bit golden-ratio constant used by SplitMix64; any odd constant works, and using a
     * named one keeps the derivation reproducible across runs and machines.
     * </p>
     */
    public static final long STREAM_ROLE_BEHAVIOUR = 0x9E3779B97F4A7C15L;

    /**
     * Derives a well-separated seed for one random-number role from the seed of the run.
     * <p>
     * Uses the SplitMix64 finalizer, an avalanche mix in which a single bit of input change flips about half the
     * output bits. Two roles derived from the same run seed therefore start Mersenne Twisters at unrelated points,
     * where a small additive offset such as {@code seed + 1} would instead reproduce a neighbouring replication's
     * sequence exactly.
     * </p>
     * <p>
     * This is seed separation, not a formal guarantee of non-overlapping substreams. L'Ecuyer's jump-ahead
     * construction would give that guarantee, but the jstats {@code MersenneTwister} exposes no jump-ahead and
     * introducing a different generator would reach across all of OTS. Given the Mersenne Twister period of
     * 2^19937-1 against at most some 10^7 draws per run, an overlap is not a practical concern once the starting
     * points are unrelated.
     * </p>
     * <p>
     * The result is forced strictly positive: {@code RandomNumberGenerator} rejects a seed of zero or below, and the
     * mix produces negative values for half of all inputs.
     * </p>
     * @param baseSeed long; the seed of the simulation run
     * @param streamRole long; the role constant identifying which stream is being derived
     * @return long; the derived seed, strictly positive
     */
    public static long deriveSeed(final long baseSeed, final long streamRole)
    {
        long z = baseSeed + streamRole;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        long positive = z & Long.MAX_VALUE;
        return positive == 0L ? 1L : positive;
    }

    /**
     * Creates the driver-population stream for a run, seeded so that it is disjoint from the arrival-process stream.
     * @param baseSeed long; the seed of the simulation run
     * @return StreamInterface; the behaviour stream
     */
    protected StreamInterface newBehaviourStream(final long baseSeed)
    {
        return new MersenneTwister(deriveSeed(baseSeed, STREAM_ROLE_BEHAVIOUR));
    }

    /** Initial longitudinal positions for generated GTUs (optional). */
    protected Set<LanePosition> initialLongitudinalPositions = new LinkedHashSet<>();

    /** All lanes in the network (cached after network creation). */
    protected List<Lane> listAllLanes = new ArrayList<>();

    /** Road samplers for data collection in this scenario. */
    protected List<RoadSampler> listRoadSamplers = new ArrayList<>();

    /** Default parameters for this scenario. */
    protected ScenarioParameters defaultParameters = new ScenarioParameters();

    /** Active scenario parameters for the current setup. */
    protected ScenarioParameters currentParameters;

    /** Output configuration for this scenario. */
    protected ScenarioOutputConfiguration outputConfiguration = new ScenarioOutputConfiguration();

    /** Loop detectors in this scenario. */
    protected List<LoopDetector> listLoopDetectors = new ArrayList<>();

    /** Lock object for synchronizing parallel demand preparation. */
    private static final Object DEMAND_LOCK = new Object();

    /**
     * Constructor.
     * @param name scenario name
     */
    protected ScenarioGenerator(final String name)
    {
        this.scenarioName = name;
        setDefaultParameters();
    }

    /**
     * Returns the scenario name.
     * @return String; the scenario name
     */
    public String getScenarioName()
    {
        return this.scenarioName;
    }

    // ----------------------------------------------------------------------
    // Network definition
    // ----------------------------------------------------------------------

    /**
     * Builds the road network for this scenario. Implementations should define all relevant roads, nodes, and lanes required
     * for the simulation.
     * @param sim the OTS simulator instance to use
     * @throws Exception if network creation fails
     */
    public abstract void buildNetwork(OtsSimulatorInterface sim) throws Exception;

    /**
     * Initializes the simulation with the given simulator using default parameters and output configuration.
     * @param sim the OTS simulator instance
     * @return the initialized RoadNetwork object
     * @throws Exception if initialization fails
     */
    public RoadNetwork setupSimulation(final OtsSimulatorInterface sim) throws Exception
    {
        return setupSimulation(sim, this.defaultParameters);
    }

    /**
     * Initializes the simulation with the given simulator, scenario parameters, and output configuration. This method should
     * build the network, configure all relevant components, and prepare the simulation to start.
     * @param sim the OTS simulator instance
     * @param params scenario-specific parameters, e.g., simulation duration or vehicle parameters
     * @return the initialized RoadNetwork object
     * @throws Exception if initialization fails
     */
    public abstract RoadNetwork setupSimulation(OtsSimulatorInterface sim, ScenarioParameters params) throws Exception;

    // ----------------------------------------------------------------------
    // GTU Templates (vehicle types, desired speed distributions, routes)
    // ----------------------------------------------------------------------

    /**
     * Builds the GTU templates (vehicle types) for generation. Implementations should define all required vehicle types, their
     * properties, and distributions.
     * @param sim the OTS simulator instance
     * @throws Exception if template creation fails
     */
    public abstract void buildGtuTemplates(OtsSimulatorInterface sim) throws Exception;

    /**
     * Creates the route definitions for the scenario. This method should define all available routes in the network, used for
     * vehicle generation or OD matrix.
     * @throws Exception if route creation fails
     */
    public abstract void buildRoutes() throws Exception;

    // ----------------------------------------------------------------------
    // Demand definitions
    // ----------------------------------------------------------------------

    /**
     * Defines the demand using an OD matrix. Implementations can create an OD matrix describing traffic flows between origin
     * and destination nodes. If not used, this method can return null.
     * @param sim the OTS simulator instance
     * @throws Exception if OD matrix creation fails
     */
    public void buildOdMatrix(final OtsSimulatorInterface sim) throws Exception
    {
    }

    /**
     * Defines a direct headway generator as an alternative to the OD matrix. Implementations can configure a generator that
     * directly determines vehicle headways.
     */
    public void buildHeadwayGenerator()
    {
    }

    /**
     * Builds the road samplers for data collection in this scenario. Implementations should define all relevant sampling points
     * and metrics to be collected during simulation. Gets added to the listRoadSamplers attribute.
     * @throws NetworkException if sampler creation fails
     */
    public void buildRoadSamplers() throws NetworkException
    {
    }

    /**
     * Returns the origin locations (nodes or lane positions) where vehicles can be generated.
     * @param network the road network in which to search for origins
     * @return list of origin nodes or lane positions
     */
    public abstract List<Node> getOrigins(RoadNetwork network);

    /**
     * Returns the destination locations (nodes) to which vehicles can travel in the scenario.
     * @param network the road network in which to search for destinations
     * @return list of destination nodes
     */
    public abstract List<Node> getDestinations(RoadNetwork network);

    /**
     * Returns all lanes in the network.
     * @return list of all lanes
     */
    public List<Lane> getAllLanes()
    {
        return this.listAllLanes;
    }

    // ----------------------------------------------------------------------
    // Output configuration
    // ----------------------------------------------------------------------

    /**
     * Configures the output data sampling for this scenario. Implementations should define which data to sample, at what
     * frequency, and any custom output writers.
     * @return output configuration for data sampling
     */
    public ScenarioOutputConfiguration buildOutputConfiguration()
    {
        this.outputConfiguration = new ScenarioOutputConfiguration();
        return this.outputConfiguration; // default: empty config
    }

    /**
     * Returns the output configuration of this scenario.
     * @return ScenarioOutputConfiguration; the output configuration
     */
    public ScenarioOutputConfiguration getOutputConfiguration()
    {
        return this.outputConfiguration;
    }
    // ----------------------------------------------------------------------
    // Parameter overrides (scenario-specific global parameters)
    // ----------------------------------------------------------------------

    /**
     * Returns the default parameters for this scenario.
     * @return scenario-specific parameters
     */
    public ScenarioParameters getDefaultParameters()
    {
        return this.defaultParameters;
    }

    /**
     * Sets the output directory for this scenario.
     * @param outputDirectory directory to store output data
     */
    public void setOutputDirectory(final File outputDirectory)
    {
        this.outputConfiguration.setOutputDirectory(outputDirectory.getAbsolutePath());
    }

    /**
     * Returns the output directory for this scenario.
     * @return output directory
     */
    public File getOutputDirectory()
    {
        return new File(this.outputConfiguration.getOutputDirectory());
    }

    /**
     * Sets the default parameters for this scenario. Implementations should define all relevant default parameters here.
     */
    public abstract void setDefaultParameters();

    /**
     * Builds the simulation script for this scenario with given parameters and output configuration. Preparing simulation
     * demand and dynamically reading the simulation duration from the configured demand CSV file if no explicit duration was
     * set.
     * @param params scenario-specific parameters
     * @return simulation script instance
     */
    public ScenarioSimulationScript buildSimulationScript(final ScenarioParameters params)
    {
        prepareSimulationDemand(params);

        String demandCsv = params.get("demandCsv", String.class);
        if (demandCsv != null)
        {
            File csvFile = new File(demandCsv);
            if (csvFile.exists())
            {
                if (params.getSimulationTime() == null)
                {
                    try
                    {
                        String startDateStr = params.get("demandStartDate", String.class);
                        String endDateStr = params.get("demandEndDate", String.class);
                        boolean windowDurationSet = false;
                        if (startDateStr == null || endDateStr == null)
                        {
                            // Silent before: this branch produced no output at all, so a study that failed to set its
                            // demand window looked exactly like one that had none, and the CSV-max fallback below was
                            // the only visible trace.
                            System.out.println("[INFO] No demand window configured (demandStartDate=" + startDateStr
                                    + ", demandEndDate=" + endDateStr + "); deriving the simulation duration from the "
                                    + "largest time value in the CSV instead.");
                        }
                        else
                        {
                            try
                            {
                                // Locale.ROOT: these timestamps are machine-generated and non-localized, so they must
                                // not be parsed with whatever format locale the executing machine happens to have.
                                java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter
                                        .ofPattern("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT);
                                java.time.LocalDateTime sdt = java.time.LocalDateTime.parse(startDateStr.trim(), dtf);
                                java.time.LocalDateTime edt = java.time.LocalDateTime.parse(endDateStr.trim(), dtf);
                                long diffSeconds = java.time.Duration.between(sdt, edt).getSeconds();
                                if (diffSeconds > 0)
                                {
                                    org.djunits.value.vdouble.scalar.Duration duration =
                                            new org.djunits.value.vdouble.scalar.Duration(diffSeconds,
                                                    org.djunits.unit.DurationUnit.SI);
                                    params.setSimulationTime(duration);
                                    windowDurationSet = true;
                                    System.out.println("Set simulation duration from demand date window: " + duration + " ("
                                            + startDateStr + " to " + endDateStr + ")");
                                }
                                else
                                {
                                    System.err.println("WARNING: demand window is empty or inverted (" + startDateStr
                                            + " to " + endDateStr + " = " + diffSeconds + " s); falling back to the "
                                            + "CSV-max duration.");
                                }
                            }
                            catch (Exception e)
                            {
                                System.err.println("WARNING: could not parse demand window (" + startDateStr + " to "
                                        + endDateStr + ") - " + e.getClass().getSimpleName() + ": " + e.getMessage()
                                        + "; falling back to the CSV-max duration.");
                            }
                        }

                        if (!windowDurationSet)
                        {
                            double maxTimeSec = 0.0;
                            try (BufferedReader br = new BufferedReader(new FileReader(csvFile)))
                            {
                                String line;
                                boolean isHeader = true;
                                while ((line = br.readLine()) != null)
                                {
                                    if (isHeader)
                                    {
                                        isHeader = false;
                                        continue;
                                    }
                                    String[] parts = line.split(",");
                                    if (parts.length > 0)
                                    {
                                        try
                                        {
                                            double timeSec = Double.parseDouble(parts[0].trim());
                                            if (timeSec > maxTimeSec)
                                            {
                                                maxTimeSec = timeSec;
                                            }
                                        }
                                        catch (NumberFormatException e)
                                        {
                                            // Ignore header or malformed rows
                                        }
                                    }
                                }
                            }
                            if (maxTimeSec > 0.0)
                            {
                                org.djunits.value.vdouble.scalar.Duration durationFromCsv =
                                        new org.djunits.value.vdouble.scalar.Duration(maxTimeSec, org.djunits.unit.DurationUnit.SI);
                                params.setSimulationTime(durationFromCsv);
                                System.out.println("Dynamically set simulation duration from CSV: " + durationFromCsv + " ("
                                        + csvFile.getName() + ")");
                            }
                        }
                    }
                    catch (Exception e)
                    {
                        System.err.println("Error reading duration from demand CSV: " + e.getMessage());
                    }
                }
                else
                {
                    System.out.println("Using explicitly set simulation duration: " + params.getSimulationTime()
                            + " (ignoring CSV duration)");
                }
            }
        }
        return new ScenarioSimulationScript(this, params);
    }

    /**
     * Builds the simulation script for this scenario with default parameters and output configuration.
     * @return simulation script instance
     */
    public ScenarioSimulationScript buildSimulationScript()
    {
        return buildSimulationScript(this.defaultParameters);
    }

    /**
     * Starts a simple simulation for this scenario with given parameters and output directory.
     * @param params scenario-specific parameters
     * @param outputDirectory directory to store output data
     * @throws Exception if simulation fails to start
     */
    public void startSimpleSimulation(final ScenarioParameters params, final File outputDirectory) throws Exception
    {
        ScenarioSimulationScript script = buildSimulationScript(params);
        script.setOutputDirectory(outputDirectory.getAbsolutePath());
        script.setGuiEnabled(true);

        script.start();

    }

    /**
     * Starts a simple simulation for this scenario with default parameters and given output directory.
     * @param outputDirectory directory to store output data
     * @throws Exception if simulation fails to start
     */
    public void startSimpleSimulation(final File outputDirectory) throws Exception
    {
        startSimpleSimulation(this.defaultParameters, outputDirectory);
    }

    /**
     * Returns the list of loop detectors in this scenario.
     * @return list of loop detectors
     */
    public List<LoopDetector> getLoopDetectors()
    {
        return this.listLoopDetectors;
    }

    /**
     * Adds a loop detector to this scenario.
     * @param detector loop detector to add
     */
    public void addLoopDetector(final LoopDetector detector)
    {
        this.listLoopDetectors.add(detector);
    }

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------

    /**
     * Scenario parameter key that, when set to {@code Boolean.TRUE}, disables the Python-based demand preparation. Takes
     * precedence over {@value #ENV_SKIP_DEMAND_PREP}.
     */
    public static final String KEY_SKIP_DEMAND_PREP = "skipDemandPrep";

    /**
     * Environment variable that, when set to "1" or "true", disables the Python-based demand preparation. Used for cluster
     * execution, where pre-generated demand CSV files are supplied directly.
     */
    public static final String ENV_SKIP_DEMAND_PREP = "MIROVA_SKIP_DEMAND_PREP";

    /** Environment variable overriding the path of the demand preparation script. */
    public static final String ENV_DEMAND_SCRIPT = "MIROVA_DEMAND_SCRIPT";

    /**
     * Returns the value of an environment variable, or the given fallback when the variable is unset or blank.
     * @param name String; the environment variable name
     * @param fallback String; the value to use when the variable is not set
     * @return String; the resolved value
     */
    private static String envOrDefault(final String name, final String fallback)
    {
        String value = System.getenv(name);
        return (value == null || value.trim().isEmpty()) ? fallback : value;
    }

    /**
     * Reusable demand preparation mechanism with local file cache. Generates simulation demand files from database using
     * prepare_simulation_demand.py. If the requested demand was already prepared previously, it copies cached files in <1ms.
     * @param params ScenarioParameters; parameters for this simulation run
     */
    protected void prepareSimulationDemand(final ScenarioParameters params)
    {
        // Cluster mode: no Python environment is available and the demand CSV is supplied pre-generated. Leaving the
        // 'demandCsv' parameter untouched is essential here, since the regular path overwrites it with the generated file.
        Boolean skipParam = params.get(KEY_SKIP_DEMAND_PREP, Boolean.class);
        boolean skip = (skipParam != null) ? skipParam : ScenarioManager.isTruthy(System.getenv(ENV_SKIP_DEMAND_PREP));
        if (skip)
        {
            System.out.println("[INFO] Demand preparation skipped; using the configured 'demandCsv' as-is.");
            return;
        }

        String startDate = params.get("demandStartDate", String.class);
        String endDate = params.get("demandEndDate", String.class);
        Integer aggregation = params.get("demandAggregation", Integer.class);

        if (startDate != null && endDate != null)
        {
            File outputDir = getOutputDirectory();
            if (outputDir != null)
            {
                if (aggregation == null)
                {
                    aggregation = 1;
                }

                File outputDemandFile = new File(outputDir, "simulation_demand.csv");
                File outputDemandWideFile = new File(outputDir, "simulation_demand_wide.csv");
                File outputDemandPngFile = new File(outputDir, "simulation_demand.png");

                // Resolve cache directory
                File cacheDir = null;
                File p1 = outputDir.getParentFile();
                if (p1 != null)
                {
                    File p2 = p1.getParentFile();
                    if (p2 != null)
                    {
                        File p3 = p2.getParentFile();
                        if (p3 != null)
                        {
                            cacheDir = new File(p3, ".demand_cache");
                        }
                    }
                }
                if (cacheDir == null)
                {
                    cacheDir = new File(outputDir, ".demand_cache");
                }

                // Smoothing: read optional 'demandSmooth' param (defaults to TRUE).
                // Smoothing applies the "dip-fill + proportional peak trim" strategy
                // which raises congestion-suppressed intervals to Q_ref while conserving
                // the total demand integral exactly.
                Boolean smoothParam = params.get("demandSmooth", Boolean.class);
                boolean noSmooth = (smoothParam != null) ? !smoothParam : false; // default: smoothing ON

                // Construct clean cache key
                String smoothSuffix = noSmooth ? "_nosmooth" : "_smooth";
                String cleanStart = startDate.replaceAll("[^a-zA-Z0-9]", "_");
                String cleanEnd = endDate.replaceAll("[^a-zA-Z0-9]", "_");
                String cacheKey = "demand_" + cleanStart + "_" + cleanEnd + "_" + aggregation + smoothSuffix;

                File cacheDemandFile = new File(cacheDir, cacheKey + ".csv");
                File cacheDemandWideFile = new File(cacheDir, cacheKey + "_wide.csv");
                File cacheDemandPngFile = new File(cacheDir, cacheKey + ".png");

                synchronized (DEMAND_LOCK)
                {
                    if (cacheDemandFile.exists() && cacheDemandWideFile.exists() && cacheDemandPngFile.exists()
                            && cacheDemandFile.length() > 0)
                    {
                        System.out.println("[INFO] Demand cache HIT for key: " + cacheKey + ". Copying cached files...");
                        try
                        {
                            if (!outputDemandFile.exists() || outputDemandFile.length() == 0)
                            {
                                java.nio.file.Files.copy(cacheDemandFile.toPath(), outputDemandFile.toPath(),
                                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                java.nio.file.Files.copy(cacheDemandWideFile.toPath(), outputDemandWideFile.toPath(),
                                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                java.nio.file.Files.copy(cacheDemandPngFile.toPath(), outputDemandPngFile.toPath(),
                                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            }
                        }
                        catch (Exception e)
                        {
                            System.err.println("[WARNING] Could not copy cached demand files (may already be in use): " + e.getMessage());
                        }
                        params.set("demandCsv", outputDemandFile.getAbsolutePath());
                    }
                    else
                    {
                        System.out.println("[INFO] Demand cache MISS for key: " + cacheKey + ". Running python script...");
                        cacheDir.mkdirs();

                        try
                        {
                            String pythonExe = envOrDefault(ScenarioManager.ENV_PYTHON_EXECUTABLE,
                                    "D:\\Mitarbeitende\\gw2128\\repositories\\mirova\\venv\\Scripts\\python.exe");
                            String scriptPath = envOrDefault(ENV_DEMAND_SCRIPT,
                                    "D:\\Mitarbeitende\\gw2128\\repositories\\diss_mvb\\scripts\\evaluation\\fielddata\\detectors\\io\\prepare_simulation_demand.py");

                            List<String> command = new ArrayList<>();
                            command.add(pythonExe);
                            command.add(scriptPath);
                            command.add("--start-date");
                            command.add(startDate);
                            command.add("--end-date");
                            command.add(endDate);
                            command.add("--aggregation");
                            command.add(String.valueOf(aggregation));
                            command.add("--output-file");
                            command.add(cacheDemandFile.getAbsolutePath());
                            if (noSmooth)
                            {
                                command.add("--no-smooth");
                            }

                            ProcessBuilder pb = new ProcessBuilder(command);
                            pb.redirectErrorStream(true);
                            Process process = pb.start();

                            // Read process output
                            try (BufferedReader reader =
                                    new BufferedReader(new java.io.InputStreamReader(process.getInputStream())))
                            {
                                String line;
                                while ((line = reader.readLine()) != null)
                                {
                                    System.out.println("[Python Demand Prep] " + line);
                                }
                            }

                            int exitCode = process.waitFor();
                            if (exitCode == 0)
                            {
                                System.out.println("[Python Demand Prep] Successfully generated demand in cache at: "
                                        + cacheDemandFile.getAbsolutePath());
                                try
                                {
                                    if (!outputDemandFile.exists() || outputDemandFile.length() == 0)
                                    {
                                        java.nio.file.Files.copy(cacheDemandFile.toPath(), outputDemandFile.toPath(),
                                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                        java.nio.file.Files.copy(cacheDemandWideFile.toPath(), outputDemandWideFile.toPath(),
                                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                        java.nio.file.Files.copy(cacheDemandPngFile.toPath(), outputDemandPngFile.toPath(),
                                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                    }
                                }
                                catch (Exception e)
                                {
                                    System.err.println(
                                            "[WARNING] Could not copy generated demand files to output dir: " + e.getMessage());
                                }
                                params.set("demandCsv", outputDemandFile.getAbsolutePath());
                            }
                            else
                            {
                                System.err.println("[ERROR] prepare_simulation_demand.py failed with exit code: " + exitCode);
                            }
                        }
                        catch (Exception e)
                        {
                            System.err.println("[ERROR] Failed to run prepare_simulation_demand.py: " + e.getMessage());
                            e.printStackTrace();
                        }
                    }
                }
            }
        }
    }

    /**
     * Slices and parses the OD matrix from a CSV file. If 'demandStartDate' and 'demandEndDate' are set in params, only
     * entries within that window are parsed, and relative times are re-based to t=0 at the start of the window.
     * @param csvFile File; the CSV file to parse
     * @param network RoadNetwork; the road network
     * @param categorization Categorization; the categorization of GTU types
     * @param carCat Category; category for cars
     * @param truckCat Category; category for trucks
     * @return OdMatrix; the parsed OD matrix, or null if parsing fails or file does not exist
     */
    protected OdMatrix parseOdMatrixFromCsv(final File csvFile, final RoadNetwork network, final Categorization categorization,
            final Category carCat, final Category truckCat)
    {
        return parseOdMatrixFromCsv(csvFile, network, categorization, carCat, truckCat, this.currentParameters);
    }

    /**
     * Slices and parses the OD matrix from a CSV file. If 'demandStartDate' and 'demandEndDate' are set in params, only
     * entries within that window are parsed, and relative times are re-based to t=0 at the start of the window.
     * @param csvFile File; the CSV file to parse
     * @param network RoadNetwork; the road network
     * @param categorization Categorization; the categorization of GTU types
     * @param carCat Category; category for cars
     * @param truckCat Category; category for trucks
     * @param params ScenarioParameters; parameters containing optional demand window bounds
     * @return OdMatrix; the parsed OD matrix, or null if parsing fails or file does not exist
     */
    protected OdMatrix parseOdMatrixFromCsv(final File csvFile, final RoadNetwork network, final Categorization categorization,
            final Category carCat, final Category truckCat, final ScenarioParameters params)
    {
        if (csvFile == null || !csvFile.exists())
        {
            return null;
        }

        try
        {
            String startStr = (params != null) ? params.get("demandStartDate", String.class)
                    : (this.currentParameters != null ? this.currentParameters.get("demandStartDate", String.class) : null);
            String endStr = (params != null) ? params.get("demandEndDate", String.class)
                    : (this.currentParameters != null ? this.currentParameters.get("demandEndDate", String.class) : null);
            java.time.LocalDateTime windowStart = null;
            java.time.LocalDateTime windowEnd = null;
            // Locale.ROOT: the CSV timestamps are machine-generated and non-localized, so they must not be parsed with
            // whatever format locale the executing machine happens to have.
            java.time.format.DateTimeFormatter dtf =
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT);

            if (startStr == null || endStr == null)
            {
                System.out.println("[INFO] No demand window configured (demandStartDate=" + startStr + ", demandEndDate="
                        + endStr + "); applying the demand CSV " + csvFile.getName() + " in full.");
            }
            else
            {
                try
                {
                    windowStart = java.time.LocalDateTime.parse(startStr.trim(), dtf);
                    windowEnd = java.time.LocalDateTime.parse(endStr.trim(), dtf);
                }
                catch (Exception ex)
                {
                    windowStart = null;
                    windowEnd = null;
                    System.err.println("WARNING: could not parse demand window (" + startStr + " to " + endStr + ") - "
                            + ex.getClass().getSimpleName() + ": " + ex.getMessage() + "; applying the demand CSV "
                            + csvFile.getName() + " in full instead of slicing it.");
                }
            }

            java.util.TreeSet<Double> uniqueTimes = new java.util.TreeSet<>();
            Map<String, Map<Double, Double>> demandMap = new HashMap<>();
            boolean timestampWarningIssued = false;

            try (BufferedReader br = new BufferedReader(new FileReader(csvFile)))
            {
                String line;
                boolean isHeader = true;
                while ((line = br.readLine()) != null)
                {
                    if (isHeader)
                    {
                        isHeader = false;
                        continue;
                    }
                    String[] parts = line.split(",");
                    if (parts.length < 6)
                    {
                        continue;
                    }
                    double timeSec = Double.parseDouble(parts[0].trim());
                    String timestampStr = parts[1].trim();
                    String origin = parts[2].trim();
                    String destination = parts[3].trim();
                    String gtuType = parts[4].trim();
                    double demand = Double.parseDouble(parts[5].trim());

                    if (windowStart != null && windowEnd != null)
                    {
                        try
                        {
                            java.time.LocalDateTime rowTime = java.time.LocalDateTime.parse(timestampStr, dtf);
                            if (rowTime.isBefore(windowStart) || rowTime.isAfter(windowEnd))
                            {
                                continue;
                            }
                            timeSec = java.time.Duration.between(windowStart, rowTime).getSeconds();
                        }
                        catch (Exception ex)
                        {
                            // Keep the original timeSec if the timestamp column is not a standard date. Reported once
                            // per file: an unparseable timestamp column means no row can be sliced, so warning per row
                            // would print thousands of identical lines.
                            if (!timestampWarningIssued)
                            {
                                timestampWarningIssued = true;
                                System.err.println("WARNING: timestamp column of " + csvFile.getName()
                                        + " could not be parsed (first offending value: '" + timestampStr + "') - "
                                        + ex.getClass().getSimpleName() + ": " + ex.getMessage()
                                        + "; rows are kept with their original time_sec and the demand window is not "
                                        + "applied.");
                            }
                        }
                    }

                    uniqueTimes.add(timeSec);
                    String key = origin + ";" + destination + ";" + gtuType;
                    demandMap.computeIfAbsent(key, k -> new HashMap<>()).put(timeSec, demand);
                }
            }

            int n = uniqueTimes.size();
            double[] timeArray = new double[n];
            int idx = 0;
            for (Double t : uniqueTimes)
            {
                timeArray[idx++] = t;
            }

            DurationVector timeVector =
                    new DurationVector(DoubleVectorData.instantiate(timeArray, DurationUnit.SECOND.getScale(), StorageType.DENSE),
                            DurationUnit.SECOND);

            List<Node> origins = getOrigins(network);
            List<Node> destinations = getDestinations(network);
            OdMatrix odMatrix =
                    new OdMatrix("OD_Merge", origins, destinations, categorization, timeVector, Interpolation.STEPWISE);

            for (Map.Entry<String, Map<Double, Double>> entry : demandMap.entrySet())
            {
                String[] keyParts = entry.getKey().split(";");
                String originName = keyParts[0];
                String destName = keyParts[1];
                String gtuTypeStr = keyParts[2];

                Node originNode = network.getNode(originName).orElse(null);
                Node destNode = network.getNode(destName).orElse(null);

                if (originNode == null || destNode == null)
                {
                    System.err.println("WARNING: Node not found in network: " + originName + " or " + destName);
                    continue;
                }

                Category cat;
                if ("CAR".equalsIgnoreCase(gtuTypeStr))
                {
                    cat = carCat;
                }
                else if ("TRUCK".equalsIgnoreCase(gtuTypeStr))
                {
                    cat = truckCat;
                }
                else
                {
                    System.err.println("WARNING: Unknown GTU type in CSV: " + gtuTypeStr);
                    continue;
                }

                double[] demandArray = new double[n];
                Map<Double, Double> timeToDemand = entry.getValue();
                for (int i = 0; i < n; i++)
                {
                    Double t = timeArray[i];
                    demandArray[i] = timeToDemand.getOrDefault(t, 0.0);
                }

                FrequencyVector demandFreq = new FrequencyVector(
                        DoubleVectorData.instantiate(demandArray, FrequencyUnit.PER_HOUR.getScale(), StorageType.DENSE),
                        FrequencyUnit.PER_HOUR);

                odMatrix.putDemandVector(originNode, destNode, cat, demandFreq);
            }
            return odMatrix;
        }
        catch (Exception e)
        {
            System.err.println("Error parsing OD matrix from CSV: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }


    // ----------------------------------------------------------------------
    // Driver model selection
    // ----------------------------------------------------------------------

    /**
     * Scenario parameter naming the driver model every vehicle drives with; the key of a {@link DriverModelProvider}. Set from
     * the command line with {@code --model}.
     */
    public static final String KEY_DRIVER_MODEL = "model";

    /**
     * Scenario parameter selecting the parameter set the driver model runs on: {@value #PARAMETER_SET_DEFAULT} leaves every
     * parameter at the model's own default, {@value #PARAMETER_SET_SCENARIO} applies the overrides the scenario declares in
     * {@link #scenarioParameterOverrides()} as one set. Set from the command line with {@code --lmrsParameters}.
     */
    public static final String KEY_LMRS_PARAMETERS = "lmrsParameters";

    /** Value of {@link #KEY_LMRS_PARAMETERS} that keeps the driver model on its own defaults. */
    public static final String PARAMETER_SET_DEFAULT = "default";

    /** Value of {@link #KEY_LMRS_PARAMETERS} that applies the scenario-level overrides as a set. */
    public static final String PARAMETER_SET_SCENARIO = "scenario";

    /**
     * Scenario parameter holding the distance after its origin within which a generated vehicle may not change lane, as a
     * {@link Length}; absent means no such restriction.
     */
    public static final String KEY_NO_LC_DIST = "noLaneChangeDistance";

    /** The model that was applied since the last reset, or {@code null} when no selection took place. */
    private static volatile String appliedModel = null;

    /** Description lines gathered from every provider that was applied, in order and without repeats. */
    private static final Set<String> APPLIED_DESCRIPTION_LINES = new LinkedHashSet<>();

    /**
     * Forgets the driver model recorded for the previous run. Called before a run is built, so that a value cannot leak from
     * one run to the next when several are executed in one process.
     */
    public static void resetAppliedModel()
    {
        appliedModel = null;
        synchronized (APPLIED_DESCRIPTION_LINES)
        {
            APPLIED_DESCRIPTION_LINES.clear();
        }
    }

    /**
     * Returns the key of the driver model that was applied to the scenario built last.
     * @return String; the model key, or {@code null} when the scenario never selected a driver model
     */
    public static String getAppliedModel()
    {
        return appliedModel;
    }

    /**
     * Returns what the applied providers reported about themselves, resolved rather than as requested.
     * @return String; the description, zero or more lines
     */
    public static String getAppliedModelDescription()
    {
        synchronized (APPLIED_DESCRIPTION_LINES)
        {
            return String.join("\n", APPLIED_DESCRIPTION_LINES);
        }
    }

    /**
     * Refuses, at the end of setup, a scenario that built its vehicles without selecting a driver model.
     * <p>
     * A scenario that constructs its own planner factory ignores {@code --model} entirely, and its output would be labelled
     * with a model that did not produce it. Called once the network and the templates are built and before the first
     * simulation step, so a misconfigured campaign pays seconds per task rather than a full run.
     * </p>
     * @param scenarioName String; the scenario being set up, for the message
     * @param requestedModel String; the model key the run asked for
     * @throws IllegalStateException when no selection took place, or it resolved to another model than requested
     */
    public static void requireSelectionHappened(final String scenarioName, final String requestedModel)
    {
        String applied = appliedModel;
        if (applied == null)
        {
            throw new IllegalStateException("Scenario " + scenarioName + " never selected a driver model: it builds its own "
                    + "planner factory and ignores " + KEY_DRIVER_MODEL + "=" + requestedModel + ". Refusing at the end of "
                    + "setup, before the first simulation step. Build the planner factories with "
                    + "buildStrategicalPlannerFactory.");
        }
        if (!applied.equals(requestedModel))
        {
            throw new IllegalStateException("The run asked for " + KEY_DRIVER_MODEL + "=" + requestedModel + ", but scenario "
                    + scenarioName + " was built with " + applied + ". Refusing during setup: the output would be "
                    + "attributed to a model that did not produce it.");
        }
    }

    /**
     * Returns the overrides of standard driver parameters that belong to this scenario, keyed {@code "car.<id>"} and
     * {@code "truck.<id>"}.
     * <p>
     * They are applied only when a run selects {@link #PARAMETER_SET_SCENARIO}; by default a driver model runs on its own
     * defaults. The default implementation declares none.
     * </p>
     * @return ScenarioParameters; the scenario-level overrides, empty when the scenario declares none
     */
    public ScenarioParameters scenarioParameterOverrides()
    {
        return new ScenarioParameters();
    }

    /**
     * Returns the parameters the driver model is built from: the parameters of the run, plus the scenario-level overrides
     * when the run selects them. A key the run sets itself is never replaced by a scenario-level override.
     * @param params ScenarioParameters; the parameters of the run
     * @return ScenarioParameters; the parameters the driver model is built from
     * @throws IllegalArgumentException when {@link #KEY_LMRS_PARAMETERS} holds an unknown value
     */
    public ScenarioParameters driverModelParameters(final ScenarioParameters params)
    {
        String parameterSet = params.getOrDefault(KEY_LMRS_PARAMETERS, PARAMETER_SET_DEFAULT, String.class);
        if (PARAMETER_SET_DEFAULT.equals(parameterSet))
        {
            return params;
        }
        if (!PARAMETER_SET_SCENARIO.equals(parameterSet))
        {
            throw new IllegalArgumentException(KEY_LMRS_PARAMETERS + "=" + parameterSet + " is not a parameter set; use "
                    + PARAMETER_SET_DEFAULT + " or " + PARAMETER_SET_SCENARIO + ".");
        }
        return scenarioParameterOverrides().copy().applyOverridesFrom(params);
    }

    /**
     * Builds the strategical planner factory of one vehicle class with the driver model the run selected, and remembers it
     * for {@link #buildOdsCharacteristicsGenerator(OtsSimulatorInterface)}.
     * @param vehicleClass String; {@code "car"} or {@code "truck"}, the prefix parameter overrides are addressed with
     * @param gtuType GtuType; the GTU type of the vehicle class
     * @return LaneBasedStrategicalPlannerFactory&lt;?&gt;; the strategical planner factory of the vehicle class
     * @throws IllegalStateException when the selected driver model cannot be built
     */
    public LaneBasedStrategicalPlannerFactory<?> buildStrategicalPlannerFactory(final String vehicleClass,
            final GtuType gtuType)
    {
        ScenarioParameters params = this.currentParameters != null ? this.currentParameters : this.defaultParameters;
        String modelKey = params.getOrDefault(KEY_DRIVER_MODEL, DriverModels.LMRS, String.class);
        DriverModelProvider provider = DriverModels.resolve(modelKey);
        LaneBasedStrategicalPlannerFactory<?> factory;
        try
        {
            factory = provider.createStrategicalPlannerFactory(new DriverModelProvider.Request(vehicleClass, gtuType,
                    this.stream, ParameterOverrides.forVehicleClass(driverModelParameters(params), vehicleClass)));
        }
        catch (Exception exception)
        {
            throw new IllegalStateException("Driver model '" + modelKey + "' could not be built for vehicle class "
                    + vehicleClass + ": " + exception.getMessage(), exception);
        }
        appliedModel = provider.key();
        synchronized (APPLIED_DESCRIPTION_LINES)
        {
            for (String line : provider.describe().split("\\R"))
            {
                if (!line.isBlank() && !line.trim().equals(provider.key()))
                {
                    APPLIED_DESCRIPTION_LINES.add(line.trim());
                }
            }
        }
        this.strategicalPlannerFactories.put(gtuType, factory);
        return factory;
    }

    /**
     * Builds the GTU characteristics generator for the OD matrix. Maps the chosen vehicle template properties onto drawing
     * characteristics.
     * @param sim OtsSimulatorInterface; the OTS simulator
     * @return LaneBasedGtuCharacteristicsGeneratorOd; the characteristics generator
     */
    public LaneBasedGtuCharacteristicsGeneratorOd buildOdsCharacteristicsGenerator(final OtsSimulatorInterface sim)
    {
        return new LaneBasedGtuCharacteristicsGeneratorOd()
        {
            @Override
            public LaneBasedGtuCharacteristics draw(final Node origin, final Node destination, final Category category,
                    final StreamInterface randomStream) throws GtuException
            {
                GtuType gtuType = category.get(GtuType.class);
                Route route = null;
                try
                {
                    route = ScenarioGenerator.this.network.getShortestRouteBetween(gtuType, origin, destination);
                }
                catch (NetworkException exception)
                {
                    exception.printStackTrace();
                }
                GtuCharacteristics gtuCharacteristics = ScenarioGenerator.this.gtuTemplates.get(gtuType).get();
                VehicleModel vehicleModel = VehicleModel.MINMAX;
                LaneBasedStrategicalPlannerFactory<?> strategical =
                        ScenarioGenerator.this.strategicalPlannerFactories.get(gtuType);
                return new LaneBasedGtuCharacteristics(gtuCharacteristics, strategical, route, origin, destination,
                        vehicleModel);
            }
        };
    }

    @Override
    public String toString()
    {
        return "ScenarioGenerator[" + this.scenarioName + "]";
    }
}
