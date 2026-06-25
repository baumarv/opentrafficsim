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
    /** Active scenario parameters for the current setup. */
    private ScenarioParameters currentParameters;

    /** Cached map of all known ParameterType instances from standard OTS and Mirova. */
    private static final Map<String, ParameterType<?>> PARAMETER_TYPES = new HashMap<>();
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
    private static void applyParameter(Parameters parameters, ParameterType<?> pt, Object value) throws ParameterException
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
            else if (valueClass == Double.class)
            {
                convertedValue = Double.parseDouble(strVal);
            }
            else if (valueClass == Integer.class)
            {
                convertedValue = Integer.parseInt(strVal);
            }
            else
            {
                try
                {
                    // Attempt to call valueOf(String) method for DJUnits classes
                    java.lang.reflect.Method valueOfMethod = valueClass.getMethod("valueOf", String.class);
                    convertedValue = valueOfMethod.invoke(null, strVal);
                }
                catch (Exception e)
                {
                    throw new IllegalArgumentException(
                            "Cannot parse string value '" + strVal + "' for parameter class " + valueClass.getName(), e);
                }
            }
        }

        if (convertedValue != null)
        {
            parameters.setParameter((ParameterType) pt, convertedValue);
        }
        else
        {
            throw new IllegalArgumentException("Cannot convert value " + value + " to type " + valueClass.getName());
        }
    }

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
                DesiredSpeedLibrary.trucksLimit80_DensityClass1(this.stream), strategicalPlannerFactoryTrucks, routeGenerator);
        this.gtuTemplates.put(DefaultsNl.TRUCK, truck);
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

                        // 1. Apply baseline default car parameters
                        parameters.setParameter(ParameterTypes.T, new Duration(0.9, DurationUnit.SI));
                        parameters.setParameter(MirovaParameters.socioSpeedSensitivity, 0.75);
                        DistContinuous vGainDist = new DistUniform(FreiburgNord.this.stream, 20, 50);
                        parameters.setParameter(MirovaParameters.vGain, new Speed(vGainDist.draw(), SpeedUnit.KM_PER_HOUR));

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

                        // 1. Apply baseline default truck parameters
                        parameters.setParameter(ParameterTypes.T, new Duration(1.2, DurationUnit.SI));
                        DistContinuous vGainDist = new DistUniform(FreiburgNord.this.stream, 90, 110);
                        parameters.setParameter(MirovaParameters.vGain, new Speed(vGainDist.draw(), SpeedUnit.KM_PER_HOUR));
                        parameters.setParameter(MirovaParameters.cooperativeLaneChangesEnabled, false);

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
                    route = FreiburgNord.this.network.getShortestRouteBetween(gtuType, origin, destination);
                }
                catch (NetworkException exception)
                {
                    exception.printStackTrace();
                }
                GtuCharacteristics gtuCharacteristics = getGtuTemplates().get(gtuType).get();
                VehicleModel vehicleModel = VehicleModel.MINMAX;
                LaneBasedStrategicalPlannerFactory<?> strategical =
                        FreiburgNord.this.gtuTemplates.get(gtuType).getStrategicalPlannerFactory();
                return new LaneBasedGtuCharacteristics(gtuCharacteristics, strategical, route, origin, destination,
                        vehicleModel);
            }
        };
    }

    /**
     * Builds the shortest path route definitions (A-E main highway and F-E ramp merge).
     * @throws Exception when path calculation fails
     */
    @Override
    public void buildRoutes() throws Exception
    {
        GtuType car = DefaultsNl.CAR;
        Route routeAE = this.network.getShortestRouteBetween(car, this.network.getNode("N1_1"), this.network.getNode("N5_3"));
        Route routeFE = this.network.getShortestRouteBetween(car, this.network.getNode("R7_1"), this.network.getNode("N5_3"));

        this.routes.put("A-E", routeAE);
        this.routes.put("F-E", routeFE);
    }

    /**
     * ------------------------------------------------------------ Create vehicles from OD matrix
     * @param params
     * @param sim
     * @throws Exception
     */
    public void createVehiclesFromODMatrix(final ScenarioParameters params, final OtsSimulatorInterface sim) throws Exception
    {
        String demandCsv = params.getOrDefault("demandCsv",
                "D:\\Mitarbeitende\\gw2128\\repositories\\diss_mvb\\scripts\\evaluation\\fielddata\\detectors\\io\\data\\demand_freiburg_20250925_06-12_low_demand.csv",
                String.class);
        File csvFile = new File(demandCsv);

        Categorization categorization = new Categorization("MyCategorization", GtuType.class);
        List<Node> origins = getOrigins(this.network);
        List<Node> destinations = getDestinations(this.network);

        Category carCat = new Category(categorization, DefaultsNl.CAR);
        Category truckCat = new Category(categorization, DefaultsNl.TRUCK);

        OdMatrix odMatrix;

        if (csvFile.exists())
        {
            System.out.println("Loading simulation demand from CSV: " + csvFile.getAbsolutePath());
            TreeSet<Double> uniqueTimes = new TreeSet<>();
            Map<String, Map<Double, Double>> demandMap = new HashMap<>();

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
                    String origin = parts[2].trim();
                    String destination = parts[3].trim();
                    String gtuType = parts[4].trim();
                    double demand = Double.parseDouble(parts[5].trim());

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

            odMatrix = new OdMatrix("OD_Merge", origins, destinations, categorization, timeVector, Interpolation.STEPWISE);

            for (Map.Entry<String, Map<Double, Double>> entry : demandMap.entrySet())
            {
                String[] keyParts = entry.getKey().split(";");
                String originName = keyParts[0];
                String destName = keyParts[1];
                String gtuTypeStr = keyParts[2];

                Node originNode = this.network.getNode(originName);
                Node destNode = this.network.getNode(destName);

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
        }
        else
        {
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
        laneBiases.addBias(DefaultsNl.TRUCK, new GeneratorPositions.LaneBias(
                new GeneratorPositions.RoadPosition.ByValue(0.0), 1.0, 1.0));
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
        RoadSampler sampler = RoadSampler.build(this.network).registerExtendedDataType(new ExtendedDataActionState())
                .registerExtendedDataType(new ExtendedDataLaneChangeDesireLeft())
                .registerExtendedDataType(new ExtendedDataLaneChangeDesireRight()).create();

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
                        || (linkId.equals("L5a") && lane.getId().startsWith("Lane")))
                {
                    this.listLoopDetectors.add(new LoopDetector("det_" + lane.getFullId(),
                            new LanePosition(lane, lane.getLength().times(0.5)), Length.ZERO, DefaultsNl.LOOP_DETECTOR,
                            Time.instantiateSI(0.0), Duration.instantiateSI(60.0), LoopDetector.HARMONIC_MEAN_SPEED));
                }

                // Record trajectory paths starting at link L2a
                if (linkId.equals("L1a") || linkId.equals("L2a") || linkId.equals("L3a") || linkId.equals("L4a"))
                {
                    GraphPath<LaneDataRoad> path = GraphLaneUtil.createPath("path", lane);
                    sampler.scheduleStartRecording(Time.instantiateSI(0), path.get(0).getSource(0));
                }
            }
        }

        this.listRoadSamplers.add(sampler);
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

    /**
     * Builds the simulation script for FreiburgNord, dynamically reading the simulation duration from the configured demand CSV
     * file if no explicit duration was set.
     * @param params ScenarioParameters; parameters for this simulation run
     * @return ScenarioSimulationScript; the constructed simulation script
     */
    @Override
    public ScenarioSimulationScript buildSimulationScript(final ScenarioParameters params)
    {
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
                            Duration durationFromCsv = new Duration(maxTimeSec, DurationUnit.SI);
                            params.setSimulationTime(durationFromCsv);
                            System.out.println("Dynamically set simulation duration from CSV: " + durationFromCsv + " ("
                                    + csvFile.getName() + ")");
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
        return super.buildSimulationScript(params);
    }
}
