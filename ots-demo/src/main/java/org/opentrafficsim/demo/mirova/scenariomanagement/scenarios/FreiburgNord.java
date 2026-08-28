package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Supplier;

import org.djunits.unit.DurationUnit;
import org.djunits.unit.FrequencyUnit;
import org.djunits.unit.SpeedUnit;
import org.djunits.unit.TimeUnit;
import org.djunits.value.storage.StorageType;
import org.djunits.value.vdouble.scalar.Duration;
import org.djunits.value.vdouble.scalar.Frequency;
import org.djunits.value.vdouble.scalar.Length;
import org.djunits.value.vdouble.scalar.Speed;
import org.djunits.value.vdouble.scalar.Time;
import org.djunits.value.vdouble.vector.FrequencyVector;
import org.djunits.value.vdouble.vector.TimeVector;
import org.djunits.value.vdouble.vector.data.DoubleVectorData;
import org.djutils.immutablecollections.ImmutableIterator;
import org.djutils.immutablecollections.ImmutableMap;
import org.djutils.io.URLResource;
import org.opentrafficsim.animation.GraphLaneUtil;
import org.opentrafficsim.base.parameters.ParameterException;
import org.opentrafficsim.base.parameters.ParameterType;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.base.parameters.Parameters;
import org.opentrafficsim.core.definitions.DefaultsNl;
import org.opentrafficsim.core.distributions.ConstantSupplier;
import org.opentrafficsim.core.distributions.FrequencyAndObject;
import org.opentrafficsim.core.dsol.OtsSimulatorInterface;
import org.opentrafficsim.core.gtu.GtuCharacteristics;
import org.opentrafficsim.core.gtu.GtuErrorHandler;
import org.opentrafficsim.core.gtu.GtuException;
import org.opentrafficsim.core.gtu.GtuType;
import org.opentrafficsim.core.network.Link;
import org.opentrafficsim.core.network.NetworkException;
import org.opentrafficsim.core.network.Node;
import org.opentrafficsim.core.network.route.ProbabilisticRouteGenerator;
import org.opentrafficsim.core.network.route.Route;
import org.opentrafficsim.core.object.DetectorType;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioGenerator;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioManager;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioOutputConfiguration;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioSimulationScript;
import org.opentrafficsim.demo.mirova.scenariomanagement.libraries.DesiredSpeedLibrary;
import org.opentrafficsim.draw.graphs.GraphPath;
import org.opentrafficsim.road.gtu.generator.GeneratorPositions;
import org.opentrafficsim.road.gtu.generator.characteristics.LaneBasedGtuCharacteristics;
import org.opentrafficsim.road.gtu.generator.characteristics.LaneBasedGtuCharacteristicsGeneratorOd;
import org.opentrafficsim.road.gtu.generator.characteristics.LaneBasedGtuTemplate;
import org.opentrafficsim.road.gtu.lane.VehicleModel;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.DefaultMirovaPerceptionFactory;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.MirovaTacticalPlannerFactory;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ReactiveLayer.MirovaIdmPlusFactory;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging.extendeddata.ExtendedDataActionState;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging.extendeddata.ExtendedDataGtuType;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging.extendeddata.ExtendedDataLaneChangeDesireLeft;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging.extendeddata.ExtendedDataLaneChangeDesireRight;
import org.opentrafficsim.road.gtu.strategical.LaneBasedStrategicalPlannerFactory;
import org.opentrafficsim.road.gtu.strategical.LaneBasedStrategicalRoutePlannerFactory;
import org.opentrafficsim.road.network.RoadNetwork;
import org.opentrafficsim.road.network.factory.xml.parser.XmlParser;
import org.opentrafficsim.road.network.lane.CrossSectionLink;
import org.opentrafficsim.road.network.lane.Lane;
import org.opentrafficsim.road.network.lane.LanePosition;
import org.opentrafficsim.road.network.lane.object.detector.LoopDetector;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging.extendeddata.ExtendedDataAccelerationDamping;
import org.opentrafficsim.road.network.sampling.LaneDataRoad;
import org.opentrafficsim.road.network.sampling.RoadSampler;
import org.opentrafficsim.road.od.Categorization;
import org.opentrafficsim.road.od.Category;
import org.opentrafficsim.road.od.Interpolation;
import org.opentrafficsim.road.od.OdApplier;
import org.opentrafficsim.road.od.OdMatrix;
import org.opentrafficsim.road.od.OdOptions;

import nl.tudelft.simulation.jstats.distributions.DistContinuous;
import nl.tudelft.simulation.jstats.distributions.DistUniform;
import nl.tudelft.simulation.jstats.streams.MersenneTwister;
import nl.tudelft.simulation.jstats.streams.StreamInterface;

/**
 * FreiburgNord highway scenario generator class.
 * <p>
 * This class builds the network based on the FreiburgNord.xml layout, loads traffic demand from a CSV file (or programmatic
 * step-wise defaults), registers loop detectors and road samplers, and applies dynamic behavior parameter overrides to Cars and
 * Trucks using Java reflection.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 */
public class FreiburgNord extends ScenarioGenerator
{

    /** Link whose lanes are recorded by the road sampler: the merge section downstream of node N3_4. */
    public static final String SAMPLED_LINK_ID = "L4a";

    /**
     * Constructor for FreiburgNord.
     */
    public FreiburgNord()
    {
        super("FreiburgNord");
    }

    /**
     * Parses and builds the road network from FreiburgNord.xml.
     * <p>
     * Note: JAXB unmarshalling is synchronized on FreiburgNord.class because OTS's XML parser relies on static
     * structures/caches that are not thread-safe in parallel execution.
     * </p>
     * @param sim OtsSimulatorInterface; the OTS simulator
     * @throws Exception when unmarshalling or network compilation fails
     */
    @Override
    public void buildNetwork(final OtsSimulatorInterface sim) throws Exception
    {
        URL xmlURL = URLResource.getResource("/resources/mirova/FreiburgNord.xml");
        this.network = new RoadNetwork("FreiburgNord", sim);

        synchronized (FreiburgNord.class)
        {
            new XmlParser(this.network).setUrl(xmlURL).build();
        }

        // Initialize GTU generation start points (main entrance lane and on-ramp lane)
        CrossSectionLink linkMainIn = (CrossSectionLink) this.network.getLink("L1a");
        CrossSectionLink linkRampIn = (CrossSectionLink) this.network.getLink("L7a");

        for (Lane lane : linkMainIn.getLanes())
        {
            this.initialLongitudinalPositions.add(new LanePosition(lane, Length.instantiateSI(2.0)));
        }
        for (Lane lane : linkRampIn.getLanes())
        {
            this.initialLongitudinalPositions.add(new LanePosition(lane, Length.instantiateSI(2.0)));
        }
    }

    /**
     * Initializes and configures the FreiburgNord simulation with the given parameters.
     * @param sim OtsSimulatorInterface; the OTS simulator
     * @param params ScenarioParameters; active parameters and overrides
     * @return RoadNetwork; the built and configured road network
     * @throws Exception when initialization fails
     */
    @Override
    public RoadNetwork setupSimulation(final OtsSimulatorInterface sim, final ScenarioParameters params) throws Exception
    {
        this.currentParameters = params;
        this.stream = new MersenneTwister(params.getSeed());

        buildNetwork(sim);
        getOutputConfiguration().setRoadNetwork(this.network);
        buildRoutes();
        buildGtuTemplates(sim);
        buildRoadSamplers();
        buildOutputConfiguration();
        createVehiclesFromODMatrix(params, sim);
        return this.network;
    }

    /**
     * Defines GTU templates (Cars and Trucks) using their respective strategical planner factories and a probabilistic route
     * generator for OD matrix routing.
     * @param sim OtsSimulatorInterface; the OTS simulator
     * @throws Exception when template creation fails
     */
    @Override
    public void buildGtuTemplates(final OtsSimulatorInterface sim) throws Exception
    {
        ScenarioParameters params = this.currentParameters != null ? this.currentParameters : this.defaultParameters;

        // Build the strategical planner factories for both vehicle types
        LaneBasedStrategicalPlannerFactory<?> strategicalPlannerFactoryCars = buildStrategicalPlannerFactoryCar();
        LaneBasedStrategicalPlannerFactory<?> strategicalPlannerFactoryTrucks = buildStrategicalPlannerFactoryTruck();

        // Define route shares (main highway vs merging on-ramp traffic)
        FrequencyAndObject<Route> routeAE = new FrequencyAndObject<Route>(1.0 - params.getMergeShare(), this.routes.get("A-E"));
        FrequencyAndObject<Route> routeFE = new FrequencyAndObject<Route>(params.getMergeShare(), this.routes.get("F-E"));

        Supplier<Route> routeGenerator = new ProbabilisticRouteGenerator(List.of(routeAE, routeFE), this.stream);

        // Define Car Template: 4m length, max speed distribution limit 140km/h
        LaneBasedGtuTemplate car = new LaneBasedGtuTemplate(DefaultsNl.CAR, new ConstantSupplier<>(Length.instantiateSI(4.0)),
                new ConstantSupplier<>(Length.instantiateSI(2.0)), DesiredSpeedLibrary.carsLimit140_DensityLow(this.stream),
                strategicalPlannerFactoryCars, routeGenerator);
        this.gtuTemplates.put(DefaultsNl.CAR, car);

        // Define Truck Template: 12m length, speed limit 80km/h
        LaneBasedGtuTemplate truck = new LaneBasedGtuTemplate(DefaultsNl.TRUCK,
                new ConstantSupplier<>(Length.instantiateSI(12.0)), new ConstantSupplier<>(Length.instantiateSI(2.5)),
                DesiredSpeedLibrary.trucksLimit100_DensityClass1_Modified(this.stream), strategicalPlannerFactoryTrucks,
                routeGenerator);
        this.gtuTemplates.put(DefaultsNl.TRUCK, truck);
    }



    /**
     * Builds the shortest path route definitions (A-E main highway and F-E ramp merge) for both CAR and TRUCK.
     * <p>
     * Routes must be registered for every GTU type that uses them. OTS computes shortest paths in a GTU-type-specific manner
     * (e.g. restricted lanes differ per type). If a route is only registered for CAR, trucks originating from the on-ramp node
     * R7_1 will have no valid route and will be silently removed, causing the L7a detector to report near-zero truck counts
     * despite non-zero demand.
     * </p>
     * @throws Exception when path calculation fails
     */
    @Override
    public void buildRoutes() throws Exception
    {
        GtuType car = DefaultsNl.CAR;
        GtuType truck = DefaultsNl.TRUCK;

        // Routes for cars
        Route routeAE_car =
                this.network.getShortestRouteBetween(car, this.network.getNode("N1_1"), this.network.getNode("N5_3"));
        Route routeFE_car =
                this.network.getShortestRouteBetween(car, this.network.getNode("R7_1"), this.network.getNode("N5_3"));

        // Routes for trucks — must be registered separately so trucks get a valid path from the on-ramp (L7a)
        Route routeAE_truck =
                this.network.getShortestRouteBetween(truck, this.network.getNode("N1_1"), this.network.getNode("N5_3"));
        Route routeFE_truck =
                this.network.getShortestRouteBetween(truck, this.network.getNode("R7_1"), this.network.getNode("N5_3"));

        this.routes.put("A-E", routeAE_car);
        this.routes.put("F-E", routeFE_car);
        this.routes.put("A-E-truck", routeAE_truck);
        this.routes.put("F-E-truck", routeFE_truck);
    }

    /**
     * Scenario parameter key that, when set to {@code Boolean.TRUE}, turns a missing or unreadable demand CSV into a fatal
     * error instead of a silent fallback to synthetic demand. Takes precedence over {@value #ENV_DEMAND_CSV_STRICT}.
     */
    public static final String KEY_DEMAND_CSV_STRICT = "demandCsvStrict";

    /**
     * Environment variable that, when set to "1" or "true", turns a missing or unreadable demand CSV into a fatal error
     * instead of a silent fallback to synthetic demand.
     */
    public static final String ENV_DEMAND_CSV_STRICT = "MIROVA_STRICT_DEMAND";

    /** Environment variable overriding the demand CSV used when no 'demandCsv' scenario parameter is present. */
    public static final String ENV_DEMAND_CSV = "MIROVA_DEMAND_CSV";

    /**
     * Scenario parameter key overriding the minimum total demand a parsed OD matrix must carry in strict mode, in vehicles
     * per hour summed over all intervals, origins, destinations and GTU types.
     */
    public static final String KEY_DEMAND_MIN_TOTAL = "demandCsvMinTotal";

    /**
     * Minimum total demand a parsed OD matrix must carry in strict mode, in vehicles per hour summed over all intervals,
     * origins, destinations and GTU types.
     * <p>
     * The check is meant to catch a structurally broken input — a truncated CSV, a period filtered to nothing, or unexpected
     * column/node names that make every row be skipped — not to judge whether traffic was light. A real 13:00-22:00 Freiburg
     * day sums to five or six digits here, whereas any of those failure modes sums to exactly zero. The threshold is
     * therefore placed just above zero rather than at a "plausible traffic" level: it must never reject a genuinely quiet
     * measurement period, only an input that carries no usable demand at all. Override via {@value #KEY_DEMAND_MIN_TOTAL}.
     * </p>
     */
    public static final double DEFAULT_DEMAND_MIN_TOTAL = 1.0;

    /** Demand CSV used when neither the 'demandCsv' parameter nor {@value #ENV_DEMAND_CSV} is set (local Windows default). */
    public static final String DEFAULT_DEMAND_CSV =
            "D:\\Mitarbeitende\\gw2128\\repositories\\diss_mvb\\scripts\\evaluation\\fielddata\\detectors\\io\\data"
                    + "\\demand_freiburg_20250925_06-12_low_demand.csv";

    /**
     * Creates the vehicle generators from the OD matrix, which is read from the configured demand CSV file. When the CSV
     * cannot be read, synthetic step-wise demand is used instead, unless strict mode is enabled.
     * @param params ScenarioParameters; the parameters of this simulation run
     * @param sim OtsSimulatorInterface; the simulator
     * @throws Exception when the OD matrix cannot be built or applied, or when strict mode rejects a missing demand CSV
     */
    public void createVehiclesFromODMatrix(final ScenarioParameters params, final OtsSimulatorInterface sim) throws Exception
    {
        String fallbackCsv = System.getenv(ENV_DEMAND_CSV);
        if (fallbackCsv == null || fallbackCsv.trim().isEmpty())
        {
            fallbackCsv = DEFAULT_DEMAND_CSV;
        }
        String demandCsv = params.getOrDefault("demandCsv", fallbackCsv, String.class);
        File csvFile = new File(demandCsv);

        Categorization categorization = new Categorization("MyCategorization", GtuType.class);
        List<Node> origins = getOrigins(this.network);
        List<Node> destinations = getDestinations(this.network);

        Category carCat = new Category(categorization, DefaultsNl.CAR);
        Category truckCat = new Category(categorization, DefaultsNl.TRUCK);

        OdMatrix odMatrix = parseOdMatrixFromCsv(csvFile, this.network, categorization, carCat, truckCat, params);

        // Strict mode (batch/validation runs): fabricated demand must never silently replace field data.
        Boolean strictParam = params.get(KEY_DEMAND_CSV_STRICT, Boolean.class);
        boolean strict =
                (strictParam != null) ? strictParam : ScenarioManager.isTruthy(System.getenv(ENV_DEMAND_CSV_STRICT));

        if (odMatrix != null && strict)
        {
            // A CSV that exists and parses but carries (almost) no demand would otherwise produce a vehicle-free run,
            // detected only later and indirectly by the deadlock watchdog. Reject it here, where the cause is still known.
            double minTotal = params.getOrDefault(KEY_DEMAND_MIN_TOTAL, DEFAULT_DEMAND_MIN_TOTAL, Double.class);
            double totalDemand = totalDemand(odMatrix, origins, destinations, carCat, truckCat);
            if (totalDemand < minTotal)
            {
                String message = "FATAL: demand CSV at " + csvFile.getAbsolutePath() + " parsed to an empty or near-empty"
                        + " OD matrix (total demand " + totalDemand + " veh/h summed over all intervals, origins,"
                        + " destinations and GTU types; minimum required is " + minTotal + " veh/h). The file is most"
                        + " likely truncated, filtered to an empty period, or written with unexpected column names or"
                        + " node identifiers. Refusing to run a demand-free simulation in strict demand mode.";
                System.err.println(message);
                throw new IllegalStateException(message);
            }
        }

        if (odMatrix == null)
        {
            if (strict)
            {
                String message = "FATAL: demand CSV could not be read at " + csvFile.getAbsolutePath()
                        + " and strict demand mode is enabled. Refusing to fall back to synthetic demand.";
                System.err.println(message);
                throw new IllegalStateException(message);
            }

            System.err.println("WARNING: CSV demand file not found at " + csvFile.getAbsolutePath()
                    + ". Falling back to default programmatic demand.");

            double startVolume = 1000.0; // vehicles per hour
            double endVolume = 6500.0; // params.getDemand(); // vehicles per hour
            double volumeStep = 100.0; // vehicles per hour
            double steps = Math.ceil((endVolume - startVolume) / volumeStep) + 1;
            double relativeTimeStep = 1.0 / steps;
            int i = 0;
            double[] time = new double[(int) steps];
            double[] carDemandMain = new double[(int) steps];
            double[] carDemandOnRamp = new double[(int) steps];
            double[] truckDemandMain = new double[(int) steps];
            double[] truckDemandOnRamp = new double[(int) steps];

            for (i = 0; i < steps; i++)
            {
                time[i] = relativeTimeStep * i * params.getSimulationTime().getInUnit(DurationUnit.HOUR);
                carDemandMain[i] =
                        startVolume * (1.0 - params.getTruckShare()) * (1.0 - this.defaultParameters.getMergeShare());
                truckDemandMain[i] = startVolume * params.getTruckShare() * (1.0 - this.defaultParameters.getMergeShare());
                carDemandOnRamp[i] = startVolume * (1.0 - params.getTruckShare()) * this.defaultParameters.getMergeShare();
                truckDemandOnRamp[i] = startVolume * params.getTruckShare() * this.defaultParameters.getMergeShare();

                startVolume += volumeStep;
            }

            TimeVector timeVector = new TimeVector(
                    DoubleVectorData.instantiate(time, TimeUnit.BASE_HOUR.getScale(), StorageType.DENSE), TimeUnit.BASE_HOUR);

            odMatrix = new OdMatrix("OD_Merge", origins, destinations, categorization, timeVector, Interpolation.STEPWISE);

            FrequencyVector carFreqMain = new FrequencyVector(
                    DoubleVectorData.instantiate(carDemandMain, FrequencyUnit.PER_HOUR.getScale(), StorageType.DENSE),
                    FrequencyUnit.PER_HOUR);
            FrequencyVector truckFreqMain = new FrequencyVector(
                    DoubleVectorData.instantiate(truckDemandMain, FrequencyUnit.PER_HOUR.getScale(), StorageType.DENSE),
                    FrequencyUnit.PER_HOUR);
            FrequencyVector carFreqOnRamp = new FrequencyVector(
                    DoubleVectorData.instantiate(carDemandOnRamp, FrequencyUnit.PER_HOUR.getScale(), StorageType.DENSE),
                    FrequencyUnit.PER_HOUR);
            FrequencyVector truckFreqOnRamp = new FrequencyVector(
                    DoubleVectorData.instantiate(truckDemandOnRamp, FrequencyUnit.PER_HOUR.getScale(), StorageType.DENSE),
                    FrequencyUnit.PER_HOUR);

            odMatrix.putDemandVector(this.network.getNode("N1_1"), this.network.getNode("N5_3"), carCat, carFreqMain);
            odMatrix.putDemandVector(this.network.getNode("R7_1"), this.network.getNode("N5_3"), carCat, carFreqOnRamp);
            odMatrix.putDemandVector(this.network.getNode("N1_1"), this.network.getNode("N5_3"), truckCat, truckFreqMain);
            odMatrix.putDemandVector(this.network.getNode("R7_1"), this.network.getNode("N5_3"), truckCat, truckFreqOnRamp);
        }

        // Define GTU characteristics generator for OD
        LaneBasedGtuCharacteristicsGeneratorOd characteristicsGenerator = buildOdsCharacteristicsGenerator(sim);

        OdOptions odOptions = new OdOptions();
        odOptions.set(OdOptions.GTU_TYPE, characteristicsGenerator);
        odOptions.set(OdOptions.ERROR_HANDLER, GtuErrorHandler.DELETE);
        odOptions.set(OdOptions.LANE_BIAS, getLaneBiases());

        System.out.println("Applying OD matrix: \n" + odMatrix);

        OdApplier.applyOd(this.network, odMatrix, odOptions, new DetectorType("NL.VEHICLES"));
    }

    /**
     * Sums the demand of an OD matrix over all intervals, origin-destination pairs and GTU types.
     * <p>
     * The result is a sum of frequencies in vehicles per hour and therefore not a vehicle count; it is used solely to
     * distinguish an OD matrix that carries demand from one that carries none.
     * </p>
     * @param odMatrix OdMatrix; the matrix to sum
     * @param origins List&lt;Node&gt;; the origin nodes to consider
     * @param destinations List&lt;Node&gt;; the destination nodes to consider
     * @param categories Category...; the categories to consider
     * @return double; the summed demand in veh/h, or 0.0 when the matrix carries no demand vectors at all
     */
    public static double totalDemand(final OdMatrix odMatrix, final List<Node> origins, final List<Node> destinations,
            final Category... categories)
    {
        double total = 0.0;
        for (Node origin : origins)
        {
            for (Node destination : destinations)
            {
                for (Category category : categories)
                {
                    FrequencyVector demandVector;
                    try
                    {
                        demandVector = odMatrix.getDemandVector(origin, destination, category);
                    }
                    catch (Exception exception)
                    {
                        // Origin/destination/category combination not present in this matrix
                        continue;
                    }
                    if (demandVector == null)
                    {
                        continue;
                    }
                    for (int i = 0; i < demandVector.size(); i++)
                    {
                        total += Math.abs(demandVector.get(i).getInUnit(FrequencyUnit.PER_HOUR));
                    }
                }
            }
        }
        return total;
    }

    /**
     * Returns the origin nodes where traffic enters the network.
     * @param network RoadNetwork; the road network
     * @return List<Node>; the list of origin nodes
     */
    @Override
    public List<Node> getOrigins(final RoadNetwork network)
    {
        List<Node> origins = new ArrayList<>();
        origins.add(network.getNode("N1_1"));
        origins.add(network.getNode("R7_1"));
        return origins;
    }

    /**
     * Returns the destination nodes where traffic exits the network.
     * @param network RoadNetwork; the road network
     * @return List<Node>; the list of destination nodes
     */
    @Override
    public List<Node> getDestinations(final RoadNetwork network)
    {
        List<Node> destinations = new ArrayList<>();
        destinations.add(network.getNode("N5_3"));
        destinations.add(network.getNode("R6_8"));
        return destinations;
    }

    /**
     * Sets the default parameters for this scenario (demand, truck share, seed, merge share).
     */
    @Override
    public void setDefaultParameters()
    {
        this.defaultParameters.setDemand(4500.0); // vehicles per hour
        this.defaultParameters.setTruckShare(0.1); // 10% trucks
        this.defaultParameters.setSeed(42L); // default random seed
        this.defaultParameters.setMergeShare(0.2); // 20% of overall demand merges from the on-ramp
        this.defaultParameters.set("demandStartDate", "2025-09-25 09:00:00");
        this.defaultParameters.set("demandEndDate", "2025-09-25 10:00:00");
        this.defaultParameters.set("demandAggregation", 1);
    }

    /**
     * Returns the lane biases used by vehicle generators. Slowly travelling vehicles (like trucks) are configured to prefer the
     * right lane.
     * @return GeneratorPositions.LaneBiases; the lane biases
     */
    public GeneratorPositions.LaneBiases getLaneBiases()
    {
        GeneratorPositions.LaneBiases laneBiases = new GeneratorPositions.LaneBiases();
        laneBiases.addBias(DefaultsNl.VEHICLE, GeneratorPositions.LaneBias.bySpeed(150, 80));
        laneBiases.addBias(DefaultsNl.TRUCK,
                new GeneratorPositions.LaneBias(new GeneratorPositions.RoadPosition.ByValue(0.0), 1.0, 1.0));
        return laneBiases;
    }

    /**
     * Returns the registered GTU templates mapped by type.
     * @return Map<GtuType, LaneBasedGtuTemplate>; the GTU templates map
     */
    public Map<GtuType, LaneBasedGtuTemplate> getGtuTemplates()
    {
        return this.gtuTemplates;
    }

    /**
     * Configures and builds road samplers to record lane measurements. Registers specific extended data types for tracking
     * Mirova tactical planner states and sets up loop detectors at selected lane cross-sections.
     * @throws NetworkException when registering or scheduling samplers fails
     */
    @Override
    public void buildRoadSamplers() throws NetworkException
    {
        ScenarioParameters params = this.currentParameters != null ? this.currentParameters : this.defaultParameters;
        Boolean enableSamplers = params.getOrDefault("enableTrajectoryRecording", true, Boolean.class);

        RoadSampler sampler = null;
        if (enableSamplers)
        {
            sampler = RoadSampler.build(this.network).registerExtendedDataType(new ExtendedDataActionState())
                    .registerExtendedDataType(new ExtendedDataLaneChangeDesireLeft())
                    .registerExtendedDataType(new ExtendedDataLaneChangeDesireRight())
                    .registerExtendedDataType(new ExtendedDataAccelerationDamping())
                    .registerExtendedDataType(new ExtendedDataGtuType()).create();
        }
        else
        {
            System.out.println("[Samplers] Trajectory recording (samplers) is disabled via parameters.");
        }

        ImmutableMap<String, Link> linkMap = this.network.getLinkMap();
        ImmutableIterator<Link> links = linkMap.values().iterator();
        this.listAllLanes = new ArrayList<Lane>();
        while (links.hasNext())
        {
            CrossSectionLink link = (CrossSectionLink) links.next();
            for (Lane lane : link.getLanes())
            {
                String linkId = link.getId();
                this.listAllLanes.add(lane);

                // Add loop detectors on specific links (L3a, L7a, L5a)
                if ((linkId.equals("L3a") && lane.getId().startsWith("Lane"))
                        || (linkId.equals("L7a") && lane.getId().startsWith("Lane"))
                        || (linkId.equals("L5a") && lane.getId().startsWith("Lane"))
                        || (linkId.equals("L6a") && lane.getId().startsWith("Lane")))
                {
                    LoopDetector detector = new LoopDetector("det_" + lane.getFullId(),
                            new LanePosition(lane, lane.getLength().times(0.5)), Length.ZERO, DefaultsNl.LOOP_DETECTOR,
                            Time.instantiateSI(0.0), Duration.instantiateSI(60.0), LoopDetector.HARMONIC_MEAN_SPEED);
                    detector.specificDataFor(DefaultsNl.CAR, DefaultsNl.TRUCK);
                    this.listLoopDetectors.add(detector);
                }

                // Record trajectories on the merge section L4a only: it is the segment downstream of the merge
                // node N3_4, where the interaction being evaluated happens. Sampling the upstream mainline as well
                // multiplied the trajectory output without adding to that analysis.
                if (enableSamplers && linkId.equals(SAMPLED_LINK_ID))
                {
                    GraphPath<LaneDataRoad> path = GraphLaneUtil.createPath("path", lane);
                    sampler.scheduleStartRecording(Time.instantiateSI(0), path.get(0).getSource(0));
                }
            }
        }

        if (enableSamplers && sampler != null)
        {
            this.listRoadSamplers.add(sampler);
        }
    }

    /**
     * Returns the built output configuration consisting of loop detectors and road samplers.
     * @return ScenarioOutputConfiguration; the output configuration
     */
    @Override
    public ScenarioOutputConfiguration buildOutputConfiguration()
    {
        this.outputConfiguration.setRoadNetwork(network).addRoadSamplers(this.listRoadSamplers)
                .addLoopDetectors(this.listLoopDetectors);
        return this.outputConfiguration;
    }


}
