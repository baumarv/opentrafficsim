package org.opentrafficsim.demo.mirova.scenariomanagement;

import java.util.Map;
import java.util.Set;
import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;

import org.opentrafficsim.base.parameters.ParameterType;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.base.parameters.ParameterException;
import org.opentrafficsim.base.parameters.Parameters;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;
import org.opentrafficsim.core.gtu.GtuType;
import org.opentrafficsim.core.network.NetworkException;
import org.opentrafficsim.core.network.Node;
import org.opentrafficsim.core.network.route.Route;
import org.opentrafficsim.road.gtu.generator.characteristics.LaneBasedGtuTemplate;
import org.opentrafficsim.road.gtu.generator.headway.HeadwayGenerator;
import org.opentrafficsim.road.network.RoadNetwork;
import org.opentrafficsim.road.network.lane.Lane;
import org.opentrafficsim.road.network.lane.LanePosition;
import org.opentrafficsim.road.network.lane.object.detector.LoopDetector;
import org.opentrafficsim.road.network.sampling.RoadSampler;
import org.opentrafficsim.core.dsol.OtsSimulatorInterface;
import org.opentrafficsim.road.od.OdMatrix;
import org.opentrafficsim.road.od.Categorization;
import org.opentrafficsim.road.od.Category;
import org.opentrafficsim.road.od.Interpolation;
import org.djunits.unit.TimeUnit;
import org.djunits.unit.FrequencyUnit;
import org.djunits.value.vdouble.vector.TimeVector;
import org.djunits.value.vdouble.vector.FrequencyVector;
import org.djunits.value.vdouble.vector.data.DoubleVectorData;
import org.djunits.value.storage.StorageType;

import org.opentrafficsim.road.gtu.lane.tactical.mirova.MirovaTacticalPlannerFactory;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ReactiveLayer.MirovaIdmPlusFactory;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.DefaultMirovaPerceptionFactory;
import org.opentrafficsim.road.gtu.strategical.LaneBasedStrategicalPlannerFactory;
import org.opentrafficsim.road.gtu.strategical.LaneBasedStrategicalRoutePlannerFactory;
import org.opentrafficsim.road.gtu.generator.characteristics.LaneBasedGtuCharacteristicsGeneratorOd;
import org.opentrafficsim.road.gtu.generator.characteristics.LaneBasedGtuCharacteristics;
import org.opentrafficsim.road.gtu.lane.LaneBasedGtu;
import org.opentrafficsim.road.gtu.lane.VehicleModel;
import org.opentrafficsim.core.gtu.GtuCharacteristics;
import org.opentrafficsim.core.gtu.GtuException;
import org.djunits.unit.SpeedUnit;
import org.djunits.unit.DurationUnit;
import org.djunits.value.vdouble.scalar.Speed;
import org.djunits.value.vdouble.scalar.Duration;
import nl.tudelft.simulation.jstats.distributions.DistContinuous;
import nl.tudelft.simulation.jstats.distributions.DistUniform;
import nl.tudelft.simulation.jstats.streams.MersenneTwister;
import nl.tudelft.simulation.jstats.streams.StreamInterface;

/**
 * ScenarioGenerator ----------------- Abstract base class that encapsulates all components required to fully define a
 * simulation scenario in OTS/Mirova. A concrete scenario defines: - The road network - GTU templates (car, truck, etc.) -
 * Desired speed distributions per vehicle type - Route definitions - Demand definitions (OD matrix or direct generators) -
 * Traffic sampling configuration - All simulation parameters relevant for this scenario A ScenarioManager will request all
 * scenario components and run multiple replications with different random seeds.
 */
public abstract class ScenarioGenerator
{

    /** Human-readable name of the scenario. */
    protected final String scenarioName;

    /** map as container for routes for this scenario (keyed by route name). */
    protected Map<String, Route> routes = new HashMap<>();

    /** GTU templates for this scenario (keyed by GTU type). */
    protected Map<GtuType, LaneBasedGtuTemplate> gtuTemplates = new HashMap<>();

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
     * @param baseSeed long; the seed of the simulation run
     * @param streamRole long; the role constant identifying which stream is being derived
     * @return long; the derived seed
     */
    public static long deriveSeed(final long baseSeed, final long streamRole)
    {
        long z = baseSeed + streamRole;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
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
     * System property overriding MiRoVA's default for {@link LaneBasedGtu#CACHING}; {@code true} restores OTS's own
     * default. Only the profiling matrix has a reason to set it.
     */
    public static final String PROPERTY_GTU_POSITION_CACHING = "mirova.gtuPositionCaching";

    /** Guards the one-off log line; the flag itself is idempotent and may be written by every run. */
    private static boolean gtuPositionCachingLogged = false;

    /**
     * Constructor.
     * @param name scenario name
     */
    protected ScenarioGenerator(final String name)
    {
        this.scenarioName = name;
        setDefaultParameters();
    }

    /** Returns the scenario name. */
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
     * @param output configuration for output data to be collected
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
     * @return
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
        applyGtuPositionCaching();
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
     * Turns OTS's GTU position cache off for MiRoVA scenarios, before anything builds a GTU.
     * <p>
     * {@link LaneBasedGtu#CACHING} memoises {@code position()} in a {@link org.djutils.multikeymap.MultiKeyMap} keyed by
     * the GTU's {@link org.opentrafficsim.core.gtu.RelativePosition}, whose {@code hashCode} walks the DJUnits scalars it
     * holds. For MiRoVA the bookkeeping costs more than the recomputation it avoids: profiling a full production day
     * measured the cache-off run at <b>84.3 %</b> of the cache-on run's CPU, with the position hash alone accounting for
     * 8.9 % of CPU with the cache on and 0.0 % with it off. The results are unaffected -- the cache is pure memoisation,
     * and cache-on and cache-off runs produce byte-identical detector and trajectory output.
     * </p>
     * <p>
     * Set here rather than as a JVM-wide default because the flag belongs to OTS, not to MiRoVA: other users of the
     * library keep OTS's own default, and only scenarios built through this class are switched.
     * {@link #PROPERTY_GTU_POSITION_CACHING} restores the cache for anyone who wants to measure the difference again.
     * </p>
     * <p>
     * The flag is a JVM-global {@code public static}, so a process that runs MiRoVA scenarios alongside non-MiRoVA OTS
     * scenarios switches the cache off for both. Every MiRoVA entry point funnels through this class and all of them
     * want the same value, so within MiRoVA the write is idempotent and parallel replications cannot race to different
     * values.
     * </p>
     * <p>
     * See {@code docs/mirova/performance_investigation_synthesis.md} for the measurements and the decision.
     * </p>
     */
    protected static void applyGtuPositionCaching()
    {
        boolean caching = Boolean.parseBoolean(System.getProperty(PROPERTY_GTU_POSITION_CACHING, "false"));
        LaneBasedGtu.CACHING = caching;
        if (!gtuPositionCachingLogged)
        {
            gtuPositionCachingLogged = true;
            System.out.println("[INFO] LaneBasedGtu.CACHING = " + caching
                    + (caching ? " (OTS default, restored via -D" + PROPERTY_GTU_POSITION_CACHING + "=true)"
                            : " (MiRoVA default: the position cache costs more than it saves)"));
        }
    }

    /** Cached map of all known ParameterType instances from standard OTS and Mirova. */
    protected static final Map<String, ParameterType<?>> PARAMETER_TYPES = new HashMap<>();
    static
    {
        // 1. Load standard OTS parameters via reflection from ParameterTypes
        for (java.lang.reflect.Field field : ParameterTypes.class.getFields())
        {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    && ParameterType.class.isAssignableFrom(field.getType()))
            {
                try
                {
                    ParameterType<?> pt = (ParameterType<?>) field.get(null);
                    if (pt != null)
                    {
                        PARAMETER_TYPES.put(pt.getId().toLowerCase(), pt);
                    }
                }
                catch (Exception e)
                {
                    // Ignore non-accessible or null fields
                }
            }
        }
        // 2. Load custom Mirova-specific parameters via reflection from MirovaParameters
        for (java.lang.reflect.Field field : MirovaParameters.class.getFields())
        {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    && ParameterType.class.isAssignableFrom(field.getType()))
            {
                try
                {
                    ParameterType<?> pt = (ParameterType<?>) field.get(null);
                    if (pt != null)
                    {
                        PARAMETER_TYPES.put(pt.getId().toLowerCase(), pt);
                    }
                }
                catch (Exception e)
                {
                    // Ignore non-accessible or null fields
                }
            }
        }
    }

    /**
     * Helper method to convert and set parameter values on a Parameters instance. Handles type conversions for Double, Integer,
     * Boolean, String, and DJUnits (Duration, Length, Speed, Acceleration).
     * @param parameters Parameters; the Parameters instance to set the value on
     * @param pt ParameterType<?>; the target ParameterType field
     * @param value Object; the input value (Number, String, Boolean, or target type)
     * @throws ParameterException when setting the parameter fails
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    protected static void applyParameter(Parameters parameters, ParameterType<?> pt, Object value) throws ParameterException
    {
        Class<?> valueClass = pt.getValueClass();
        Object convertedValue = null;
        if (valueClass.isInstance(value))
        {
            convertedValue = value;
        }
        else if (value instanceof Number)
        {
            double doubleVal = ((Number) value).doubleValue();
            if (valueClass == Double.class)
            {
                convertedValue = doubleVal;
            }
            else if (valueClass == Integer.class)
            {
                convertedValue = (int) doubleVal;
            }
            else if (valueClass == org.djunits.value.vdouble.scalar.Duration.class)
            {
                convertedValue = org.djunits.value.vdouble.scalar.Duration.instantiateSI(doubleVal);
            }
            else if (valueClass == org.djunits.value.vdouble.scalar.Length.class)
            {
                convertedValue = org.djunits.value.vdouble.scalar.Length.instantiateSI(doubleVal);
            }
            else if (valueClass == org.djunits.value.vdouble.scalar.Speed.class)
            {
                convertedValue = org.djunits.value.vdouble.scalar.Speed.instantiateSI(doubleVal);
            }
            else if (valueClass == org.djunits.value.vdouble.scalar.Acceleration.class)
            {
                convertedValue = org.djunits.value.vdouble.scalar.Acceleration.instantiateSI(doubleVal);
            }
        }
        else if (value instanceof String)
        {
            String strVal = (String) value;
            if (valueClass == Boolean.class)
            {
                convertedValue = Boolean.parseBoolean(strVal);
            }
            else if (valueClass == String.class)
            {
                convertedValue = strVal;
            }
        }
        else if (value instanceof Boolean)
        {
            if (valueClass == Boolean.class)
            {
                convertedValue = value;
            }
        }

        if (convertedValue != null)
        {
            parameters.setParameter((ParameterType) pt, convertedValue);
        }
        else
        {
            System.err.println("WARNING: Could not convert value of type " + value.getClass().getSimpleName()
                    + " to expected parameter type class " + valueClass.getSimpleName() + " for parameter " + pt.getId());
        }
    }

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

            try (BufferedReader br = new BufferedReader(new java.io.FileReader(csvFile)))
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

            TimeVector timeVector =
                    new TimeVector(DoubleVectorData.instantiate(timeArray, TimeUnit.BASE_SECOND.getScale(), StorageType.DENSE),
                            TimeUnit.BASE_SECOND);

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

                Node originNode = network.getNode(originName);
                Node destNode = network.getNode(destName);

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

    /**
     * Builds the strategical planner factory for cars using the Mirova tactical planner. Applies standard defaults for cars and
     * then dynamically applies any parameter overrides matching the "car.<parameterId>" prefix from ScenarioParameters.
     * @return LaneBasedStrategicalPlannerFactory<?>; the strategical planner factory for cars
     */
    public LaneBasedStrategicalPlannerFactory<?> buildStrategicalPlannerFactoryCar()
    {
        final ScenarioParameters params = this.currentParameters != null ? this.currentParameters : this.defaultParameters;
        MirovaTacticalPlannerFactory mirovaTacticalPlannerFactoryCars =
                new MirovaTacticalPlannerFactory(new MirovaIdmPlusFactory(this.stream), new DefaultMirovaPerceptionFactory())
                {
                    @Override
                    public Parameters getParameters() throws ParameterException
                    {
                        Parameters parameters = getDefaultParameters();

                        // 2. Dynamic overrides: look for keys starting with "car."
                        for (Map.Entry<String, Object> entry : params.asUnmodifiableMap().entrySet())
                        {
                            String key = entry.getKey();
                            if (key.startsWith("car."))
                            {
                                String paramId = key.substring(4).toLowerCase();
                                ParameterType<?> pt = PARAMETER_TYPES.get(paramId);
                                if (pt != null)
                                {
                                    applyParameter(parameters, pt, entry.getValue());
                                }
                            }
                        }

                        return parameters;
                    }
                };

        return new LaneBasedStrategicalRoutePlannerFactory(mirovaTacticalPlannerFactoryCars);
    }

    /**
     * Builds the strategical planner factory for trucks using the Mirova tactical planner. Applies standard defaults for trucks
     * and then dynamically applies any parameter overrides matching the "truck.<parameterId>" prefix from ScenarioParameters.
     * @return LaneBasedStrategicalPlannerFactory<?>; the strategical planner factory for trucks
     */
    public LaneBasedStrategicalPlannerFactory<?> buildStrategicalPlannerFactoryTruck()
    {
        final ScenarioParameters params = this.currentParameters != null ? this.currentParameters : this.defaultParameters;
        MirovaTacticalPlannerFactory mirovaTacticalPlannerFactoryTrucks =
                new MirovaTacticalPlannerFactory(new MirovaIdmPlusFactory(this.stream), new DefaultMirovaPerceptionFactory())
                {
                    @Override
                    public Parameters getParameters() throws ParameterException
                    {
                        Parameters parameters = getDefaultParameters();

                        // 2. Dynamic overrides: look for keys starting with "truck."
                        for (Map.Entry<String, Object> entry : params.asUnmodifiableMap().entrySet())
                        {
                            String key = entry.getKey();
                            if (key.startsWith("truck."))
                            {
                                String paramId = key.substring(6).toLowerCase();
                                ParameterType<?> pt = PARAMETER_TYPES.get(paramId);
                                if (pt != null)
                                {
                                    applyParameter(parameters, pt, entry.getValue());
                                }
                            }
                        }

                        return parameters;
                    }
                };

        return new LaneBasedStrategicalRoutePlannerFactory(mirovaTacticalPlannerFactoryTrucks);
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
                        ScenarioGenerator.this.gtuTemplates.get(gtuType).getStrategicalPlannerFactory();
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
