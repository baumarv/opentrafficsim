package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.djunits.unit.DurationUnit;
import org.djunits.unit.FrequencyUnit;
import org.djunits.unit.SpeedUnit;
import org.djunits.value.storage.StorageType;
import org.djunits.value.vdouble.scalar.Duration;
import org.djunits.value.vdouble.scalar.Frequency;
import org.djunits.value.vdouble.scalar.Length;
import org.djunits.value.vdouble.scalar.Speed;
import org.djunits.value.vdouble.vector.DurationVector;
import org.djunits.value.vdouble.vector.FrequencyVector;
import org.djunits.value.vdouble.vector.data.DoubleVectorData;
import org.djutils.immutablecollections.ImmutableIterator;
import org.djutils.immutablecollections.ImmutableMap;
import org.opentrafficsim.animation.data.util.GraphLaneUtil;
import org.opentrafficsim.animation.graphs.GraphPath;
import org.opentrafficsim.core.definitions.DefaultsNl;
import org.opentrafficsim.core.distributions.ConstantSupplier;
import org.opentrafficsim.core.distributions.FrequencyAndObject;
import org.opentrafficsim.core.dsol.OtsSimulatorInterface;
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
import org.opentrafficsim.demo.mirova.scenariomanagement.libraries.DesiredSpeedLibrary;
import org.opentrafficsim.demo.mirova.scenariomanagement.libraries.HeadwayDistributionLibrary;
import org.opentrafficsim.demo.mirova.scenariomanagement.sampling.ExtendedDataAccelerationDamping;
import org.opentrafficsim.demo.mirova.scenariomanagement.sampling.ExtendedDataActionState;
import org.opentrafficsim.demo.mirova.scenariomanagement.sampling.ExtendedDataCurrentCFAcceleration;
import org.opentrafficsim.demo.mirova.scenariomanagement.sampling.ExtendedDataGtuType;
import org.opentrafficsim.demo.mirova.scenariomanagement.sampling.ExtendedDataLaneChangeDesireLeft;
import org.opentrafficsim.demo.mirova.scenariomanagement.sampling.ExtendedDataLaneChangeDesireRight;
import org.opentrafficsim.demo.mirova.scenariomanagement.sampling.GtuDeletionDiagnostics;
import org.opentrafficsim.road.gtu.generator.GeneratorPositions;
import org.opentrafficsim.road.gtu.generator.characteristics.LaneBasedGtuCharacteristicsGeneratorOd;
import org.opentrafficsim.road.gtu.generator.characteristics.LaneBasedGtuTemplate;
import org.opentrafficsim.road.gtu.strategical.LaneBasedStrategicalPlannerFactory;
import org.opentrafficsim.road.network.CrossSectionLink;
import org.opentrafficsim.road.network.Lane;
import org.opentrafficsim.road.network.LanePosition;
import org.opentrafficsim.road.network.RoadNetwork;
import org.opentrafficsim.road.network.factory.xml.parser.XmlParser;
import org.opentrafficsim.road.network.object.detector.LoopDetector;
import org.opentrafficsim.road.network.sampling.LaneDataRoad;
import org.opentrafficsim.road.network.sampling.RoadSampler;
import org.opentrafficsim.road.od.Categorization;
import org.opentrafficsim.road.od.Category;
import org.opentrafficsim.road.od.Interpolation;
import org.opentrafficsim.road.od.OdApplier;
import org.opentrafficsim.road.od.OdMatrix;
import org.opentrafficsim.road.od.OdOptions;

/**
 * Scenario of the Freiburg-Nord interchange (A5 / B31): a two-lane mainline with an exit ramp and an on-ramp.
 * <p>
 * The class builds the network from {@code FreiburgNord.xml}, loads the traffic demand of one day from a CSV file (or
 * synthetic step-wise demand when none is given and strict mode is off), and registers the loop detectors and the trajectory
 * sampler. It names no driver model: the planner factories come from the model the run selects, see
 * {@link ScenarioGenerator#KEY_DRIVER_MODEL}.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class FreiburgNord extends ScenarioGenerator
{

    /** Link whose lanes are recorded by the road sampler: the merge section downstream of node N3_4. */
    public static final String SAMPLED_LINK_ID = "L4a";

    /**
     * Links whose lanes the road sampler records, as a comma-separated list.
     * <p>
     * Defaults to the merge section alone, which is what the campaigns run on: sampling the approach as
     * well multiplies the trajectory output, and the merge is where the interaction under study happens.
     * That default has a cost worth stating, though - every trajectory-based result so far describes
     * 202 m of merge section rather than the queue. A gap measured against car-following equilibrium, or
     * a share of vehicles in a cooperative state, says nothing about the platoon upstream, where no
     * cooperation is active and the comparison would be decisive.
     * </p>
     * <p>
     * Override with {@code -Dmirova.samplerLinks=L4a,L3a} to record the approach as well. Check the
     * resulting file size before using that for a campaign rather than a diagnostic run.
     * </p>
     */
    public static final Set<String> SAMPLED_LINK_IDS =
            Arrays.stream(System.getProperty("mirova.samplerLinks", SAMPLED_LINK_ID).split(",")).map(String::trim)
                    .filter(x -> !x.isEmpty()).collect(Collectors.toCollection(LinkedHashSet::new));

    /** Resource path of the network file. */
    private static final String NETWORK_RESOURCE = "/resources/mirova/FreiburgNord.xml";

    /** Position on their lane at which vehicles are generated. */
    private static final Length GENERATION_POSITION = Length.ofSI(2.0);

    /** Length of a car. */
    private static final Length CAR_LENGTH = Length.ofSI(4.0);

    /** Width of a car. */
    private static final Length CAR_WIDTH = Length.ofSI(2.0);

    /** Length of a truck. */
    private static final Length TRUCK_LENGTH = Length.ofSI(12.0);

    /** Width of a truck. */
    private static final Length TRUCK_WIDTH = Length.ofSI(2.5);

    /** Aggregation period of the loop detectors. */
    private static final Duration DETECTOR_AGGREGATION = Duration.ofSI(60.0);

    /**
     * Interval at which the trajectory sampler records every vehicle on the sampled lanes.
     * <p>
     * Fixed, so that the trajectories do not depend on the driver model: without an interval the sampler writes a row per
     * plan step, which is 0.2 s in MiRoVA and 0.5 s in the LMRS. A sample is evaluated on the vehicle's operational plan at
     * the sample time (position, speed and acceleration of the plan at that instant), not interpolated between plan steps.
     * 0.2 s is the step of the trajectories written so far.
     * </p>
     */
    public static final Duration SAMPLING_INTERVAL = Duration.ofSI(0.2);

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
     * Note: parsing is synchronized on FreiburgNord.class, because the XML parser of OTS switches the default locale of the
     * JVM while it unmarshals, which is not safe when several runs are built in parallel in one process.
     * </p>
     * @param sim OtsSimulatorInterface; the OTS simulator
     * @throws Exception when unmarshalling or network compilation fails
     */
    @Override
    public void buildNetwork(final OtsSimulatorInterface sim) throws Exception
    {
        this.network = new RoadNetwork("FreiburgNord", sim);

        synchronized (FreiburgNord.class)
        {
            new XmlParser(this.network).setResource(NETWORK_RESOURCE).build();
        }

        // Initialize GTU generation start points (main entrance lane and on-ramp lane)
        CrossSectionLink linkMainIn = (CrossSectionLink) this.network.getLink("L1a").orElseThrow();
        CrossSectionLink linkRampIn = (CrossSectionLink) this.network.getLink("L7a").orElseThrow();

        for (Lane lane : linkMainIn.getLanes())
        {
            this.initialLongitudinalPositions.add(new LanePosition(lane, GENERATION_POSITION));
        }
        for (Lane lane : linkRampIn.getLanes())
        {
            this.initialLongitudinalPositions.add(new LanePosition(lane, GENERATION_POSITION));
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
        this.stream = newBehaviourStream(params.getSeed());

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
     * Defines the GTU templates (cars and trucks): dimensions, maximum-speed distribution and route generator. The planner
     * factories are built by the driver model the run selected.
     * @param sim OtsSimulatorInterface; the OTS simulator
     * @throws Exception when template creation fails
     */
    @Override
    public void buildGtuTemplates(final OtsSimulatorInterface sim) throws Exception
    {
        ScenarioParameters params = this.currentParameters != null ? this.currentParameters : this.defaultParameters;

        // Build the strategical planner factories for both vehicle types
        LaneBasedStrategicalPlannerFactory<?> strategicalPlannerFactoryCars =
                buildStrategicalPlannerFactory("car", DefaultsNl.CAR);
        LaneBasedStrategicalPlannerFactory<?> strategicalPlannerFactoryTrucks =
                buildStrategicalPlannerFactory("truck", DefaultsNl.TRUCK);

        // Define route shares (main highway vs merging on-ramp traffic)
        FrequencyAndObject<Route> routeAE = new FrequencyAndObject<Route>(1.0 - params.getMergeShare(), this.routes.get("A-E"));
        FrequencyAndObject<Route> routeFE = new FrequencyAndObject<Route>(params.getMergeShare(), this.routes.get("F-E"));

        Supplier<Route> routeGenerator = new ProbabilisticRouteGenerator(List.of(routeAE, routeFE), this.stream);

        // Define Car Template: 4m length, max speed distribution limit 140km/h. The shift defaults to
        // 0.0, which reproduces the measured distribution; a study that sets it says so in its manifest.
        double desiredSpeedShiftCar = params.getOrDefault(
                ScenarioParameters.KEY_DESIRED_SPEED_SHIFT_CAR, 0.0, Double.class);
        double desiredSpeedMinCar = params.getOrDefault(
                ScenarioParameters.KEY_DESIRED_SPEED_MIN_CAR, 0.0, Double.class);
        double desiredSpeedCompression = params.getOrDefault(
                ScenarioParameters.KEY_DESIRED_SPEED_COMPRESSION_CAR, 1.0, Double.class);
        double desiredSpeedPivot = params.getOrDefault(
                ScenarioParameters.KEY_DESIRED_SPEED_PIVOT_CAR, 0.0, Double.class);
        // Each of the three reshapes the same distribution, so a run with more than one moves it
        // several times and no result could be attributed to any of them. Counted rather than paired,
        // so a fourth axis cannot be added without this refusing it too.
        int active = (desiredSpeedShiftCar != 0.0 ? 1 : 0) + (desiredSpeedMinCar > 0.0 ? 1 : 0)
                + (desiredSpeedCompression != 1.0 ? 1 : 0);
        if (active > 1)
        {
            throw new IllegalStateException("more than one reshaping of the car desired-speed "
                    + "distribution is set: " + ScenarioParameters.KEY_DESIRED_SPEED_SHIFT_CAR + "="
                    + desiredSpeedShiftCar + ", " + ScenarioParameters.KEY_DESIRED_SPEED_MIN_CAR + "="
                    + desiredSpeedMinCar + ", " + ScenarioParameters.KEY_DESIRED_SPEED_COMPRESSION_CAR
                    + "=" + desiredSpeedCompression + ". Each moves the same distribution, so a run "
                    + "with several moves it several times and no result could be attributed to any.");
        }
        if (desiredSpeedCompression != 1.0 && desiredSpeedPivot <= 0.0)
        {
            throw new IllegalStateException(ScenarioParameters.KEY_DESIRED_SPEED_COMPRESSION_CAR + "="
                    + desiredSpeedCompression + " needs a positive "
                    + ScenarioParameters.KEY_DESIRED_SPEED_PIVOT_CAR
                    + "; a compression towards an unstated speed is not defined.");
        }
        var carSpeeds = desiredSpeedCompression != 1.0
                ? DesiredSpeedLibrary.carsLimit140_DensityLowCompressed(this.stream, kmh(desiredSpeedPivot),
                        desiredSpeedCompression)
                : desiredSpeedMinCar > 0.0
                        ? DesiredSpeedLibrary.carsLimit140_DensityLowAbove(this.stream, kmh(desiredSpeedMinCar))
                        : DesiredSpeedLibrary.carsLimit140_DensityLow(this.stream, kmh(desiredSpeedShiftCar));
        LaneBasedGtuTemplate car = new LaneBasedGtuTemplate(DefaultsNl.CAR, new ConstantSupplier<>(CAR_LENGTH),
                new ConstantSupplier<>(CAR_WIDTH), carSpeeds,
                strategicalPlannerFactoryCars, routeGenerator);
        this.gtuTemplates.put(DefaultsNl.CAR, car);

        // Define Truck Template: 12m length, speed limit 80km/h
        LaneBasedGtuTemplate truck = new LaneBasedGtuTemplate(DefaultsNl.TRUCK,
                new ConstantSupplier<>(TRUCK_LENGTH), new ConstantSupplier<>(TRUCK_WIDTH),
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
                this.network.getShortestRouteBetween(car, node("N1_1"), node("N5_3"));
        Route routeFE_car =
                this.network.getShortestRouteBetween(car, node("R7_1"), node("N5_3"));

        // Routes for trucks — must be registered separately so trucks get a valid path from the on-ramp (L7a)
        Route routeAE_truck =
                this.network.getShortestRouteBetween(truck, node("N1_1"), node("N5_3"));
        Route routeFE_truck =
                this.network.getShortestRouteBetween(truck, node("R7_1"), node("N5_3"));

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
    public static final Frequency DEFAULT_DEMAND_MIN_TOTAL = new Frequency(1.0, FrequencyUnit.PER_HOUR);

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
            // detected only later and indirectly by the watchdog. Reject it here, where the cause is still known.
            Frequency minTotal = new Frequency(params.getOrDefault(KEY_DEMAND_MIN_TOTAL,
                    DEFAULT_DEMAND_MIN_TOTAL.getInUnit(FrequencyUnit.PER_HOUR), Double.class), FrequencyUnit.PER_HOUR);
            Frequency totalDemand = totalDemand(odMatrix, origins, destinations, carCat, truckCat);
            if (totalDemand.lt(minTotal))
            {
                String message = "FATAL: demand CSV at " + csvFile.getAbsolutePath() + " parsed to an empty or near-empty"
                        + " OD matrix (total demand " + totalDemand.getInUnit(FrequencyUnit.PER_HOUR)
                        + " veh/h summed over all intervals, origins,"
                        + " destinations and GTU types; minimum required is "
                        + minTotal.getInUnit(FrequencyUnit.PER_HOUR) + " veh/h). The file is most"
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

            DurationVector timeVector = new DurationVector(
                    DoubleVectorData.instantiate(time, DurationUnit.HOUR.getScale(), StorageType.DENSE), DurationUnit.HOUR);

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

            odMatrix.putDemandVector(node("N1_1"), node("N5_3"), carCat, carFreqMain);
            odMatrix.putDemandVector(node("R7_1"), node("N5_3"), carCat, carFreqOnRamp);
            odMatrix.putDemandVector(node("N1_1"), node("N5_3"), truckCat, truckFreqMain);
            odMatrix.putDemandVector(node("R7_1"), node("N5_3"), truckCat, truckFreqOnRamp);
        }

        // Define GTU characteristics generator for OD
        LaneBasedGtuCharacteristicsGeneratorOd characteristicsGenerator = buildOdsCharacteristicsGenerator(sim);

        OdOptions odOptions = new OdOptions();
        odOptions.set(OdOptions.GTU_TYPE, characteristicsGenerator);
        // Same behaviour as GtuErrorHandler.DELETE - the vehicle is destroyed either way - but the exception
        // is recorded instead of discarded, so that the removals can be attributed to a cause rather than to the
        // position they happen to occur at.
        odOptions.set(OdOptions.ERROR_HANDLER, GtuDeletionDiagnostics.INSTANCE);
        odOptions.set(OdOptions.LANE_BIAS, getLaneBiases());
        // Set explicitly, also when it is null: OTS 1.8 changed the default of this option from "no restriction" to 50 m.
        // The scenario was built and calibrated without the restriction, so it states its value instead of inheriting one.
        odOptions.set(OdOptions.NO_LC_DIST, params.get(KEY_NO_LC_DIST, Length.class));
        applyHeadwayDistributions(odOptions);

        System.out.println("Applying OD matrix: \n" + odMatrix);

        OdApplier.applyOd(this.network, odMatrix, odOptions, new DetectorType("NL.VEHICLES"));
    }

    /**
     * Sets the arrival headway distribution separately for the two inflows.
     * <p>
     * The two inflows of this site have opposite arrival characteristics, so a single global distribution is wrong for
     * one of them whichever is chosen.
     * </p>
     * <p>
     * <b>Mainline A5.</b> Above 2000 veh/h per lane the exponential distribution OTS defaults to keeps drawing gaps
     * shorter than a vehicle, and traffic arriving from the upstream network is in any case already platooned rather
     * than independent. A floor under the headway is therefore the physically defensible choice, and
     * {@link HeadwayDistributionLibrary#shiftedExponential(double)} imposes one without touching the mean, so the OD
     * demand is delivered unchanged. The default of 0.0 reproduces the previous behaviour exactly; set
     * {@code -Dmirova.mainlineHeadwayFloor} to enable it.
     * </p>
     * <p>
     * <b>On-ramp B31.</b> The opposite holds. Vehicles released by the signalized intersection travel as a tight
     * platoon and disperse only gradually, with the red phase leaving a long gap between platoons - burstier than
     * Poisson, and cyclic rather than random. Poisson understates the clustering and misrepresents its structure.
     * </p>
     * <p>
     * That structure cannot be expressed here. A {@code HeadwayDistribution} sees only a random stream: it has no
     * access to simulation time, so a process whose intensity varies with the phase of a signal cycle is outside what
     * this interface can represent, whatever distribution is chosen. Reproducing it needs a time-aware arrivals
     * generator - Robertson's dispersion model, a shifted geometric travel-time distribution applied to the flow
     * profile leaving the stop line - which is a new generator rather than a parameter, and is not attempted here.
     * </p>
     * <p>
     * Dispersion does not dispose of the question either. The modelled ramp runs some 310 m from R7_1 to the merge,
     * and field studies still resolve platoon structure at 230, 450 and 700 m downstream of a stop line, so a platoon
     * would arrive at the merge largely intact. The ramp therefore keeps the OTS default for now, as an acknowledged
     * approximation rather than a justified choice.
     * </p>
     * @param odOptions OdOptions; the options to configure
     */
    private void applyHeadwayDistributions(final OdOptions odOptions)
    {
        double floor = Double.parseDouble(System.getProperty("mirova.mainlineHeadwayFloor", "0.0"));
        if (floor > 0.0)
        {
            odOptions.set(node("N1_1"), OdOptions.HEADWAY_DIST,
                    HeadwayDistributionLibrary.shiftedExponential(floor));
            System.out.println("[HEADWAY] mainline N1_1: shifted exponential, floor " + floor
                    + " of the mean headway; on-ramp R7_1: OTS default (exponential)");
        }
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
     * @return Frequency; the summed demand, zero when the matrix carries no demand vectors at all
     */
    public static Frequency totalDemand(final OdMatrix odMatrix, final List<Node> origins, final List<Node> destinations,
            final Category... categories)
    {
        double total = 0.0;
        for (Node origin : origins)
        {
            for (Node destination : destinations)
            {
                for (Category category : categories)
                {
                    FrequencyVector demandVector = odMatrix.getDemandVector(origin, destination, category).orElse(null);
                    if (demandVector == null)
                    {
                        // Origin/destination/category combination not present in this matrix
                        continue;
                    }
                    for (int i = 0; i < demandVector.size(); i++)
                    {
                        total += Math.abs(demandVector.get(i).getInUnit(FrequencyUnit.PER_HOUR));
                    }
                }
            }
        }
        return new Frequency(total, FrequencyUnit.PER_HOUR);
    }

    /**
     * Returns the origin nodes where traffic enters the network.
     * @param network RoadNetwork; the road network
     * @return List&lt;Node&gt;; the list of origin nodes
     */
    @Override
    public List<Node> getOrigins(final RoadNetwork network)
    {
        List<Node> origins = new ArrayList<>();
        origins.add(network.getNode("N1_1").orElseThrow());
        origins.add(network.getNode("R7_1").orElseThrow());
        return origins;
    }

    /**
     * Returns the destination nodes where traffic exits the network.
     * @param network RoadNetwork; the road network
     * @return List&lt;Node&gt;; the list of destination nodes
     */
    @Override
    public List<Node> getDestinations(final RoadNetwork network)
    {
        List<Node> destinations = new ArrayList<>();
        destinations.add(network.getNode("N5_3").orElseThrow());
        destinations.add(network.getNode("R6_8").orElseThrow());
        return destinations;
    }

    /**
     * Sets the default parameters for this scenario (demand, truck share, seed, merge share).
     */
    @Override
    public void setDefaultParameters()
    {
        this.defaultParameters.setDemand(new Frequency(4500.0, FrequencyUnit.PER_HOUR));
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
     * @return Map&lt;GtuType, LaneBasedGtuTemplate&gt;; the GTU templates map
     */
    public Map<GtuType, LaneBasedGtuTemplate> getGtuTemplates()
    {
        return this.gtuTemplates;
    }

    /**
     * Configures and builds the road sampler and the loop detectors. The sampler records six extended columns; five of them
     * are filled by the tactical planner when it reports its state, see
     * {@link org.opentrafficsim.demo.mirova.scenariomanagement.sampling.DriverStateReporter}.
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
            sampler = RoadSampler.build(this.network)
                    .setFrequency(new Frequency(1.0 / SAMPLING_INTERVAL.si, FrequencyUnit.PER_SECOND))
                    .registerExtendedDataType(new ExtendedDataActionState())
                    .registerExtendedDataType(new ExtendedDataLaneChangeDesireLeft())
                    .registerExtendedDataType(new ExtendedDataLaneChangeDesireRight())
                    .registerExtendedDataType(new ExtendedDataAccelerationDamping())
                    .registerExtendedDataType(new ExtendedDataGtuType())
                    // The acceleration the car-following model alone asks for, recorded next to the one that is
                    // executed. Their difference is what everything between the two takes away.
                    .registerExtendedDataType(new ExtendedDataCurrentCFAcceleration()).create();
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
                            Duration.ZERO, DETECTOR_AGGREGATION, LoopDetector.HARMONIC_MEAN_SPEED);
                    detector.specificDataFor(DefaultsNl.CAR, DefaultsNl.TRUCK);
                    this.listLoopDetectors.add(detector);
                }

                // Record trajectories on the merge section L4a only: it is the segment downstream of the merge
                // node N3_4, where the interaction being evaluated happens. Sampling the upstream mainline as well
                // multiplied the trajectory output without adding to that analysis.
                if (enableSamplers && SAMPLED_LINK_IDS.contains(linkId))
                {
                    GraphPath<LaneDataRoad> path = GraphLaneUtil.createPath("path", lane);
                    sampler.scheduleStartRecording(Duration.ZERO, path.get(0).getSource(0));
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
        this.outputConfiguration.setRoadNetwork(this.network).addRoadSamplers(this.listRoadSamplers)
                .addLoopDetectors(this.listLoopDetectors);
        return this.outputConfiguration;
    }

    /**
     * Wraps a value given in km/h, as the desired-speed scenario parameters are, into a speed.
     * @param value double; the value in km/h
     * @return Speed; the speed
     */
    private static Speed kmh(final double value)
    {
        return new Speed(value, SpeedUnit.KM_PER_HOUR);
    }

    /**
     * Returns a node of the network that the scenario relies on.
     * @param id String; the node id
     * @return Node; the node
     * @throws java.util.NoSuchElementException when the network has no such node
     */
    private Node node(final String id)
    {
        return this.network.getNode(id).orElseThrow();
    }

}
