package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Set;
import java.util.TreeSet;

import org.djunits.unit.DurationUnit;
import org.djunits.unit.FrequencyUnit;
import org.djunits.unit.LengthUnit;
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
import org.opentrafficsim.base.parameters.ParameterSet;
import org.opentrafficsim.base.parameters.ParameterType;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.base.parameters.Parameters;
import org.opentrafficsim.core.definitions.DefaultsNl;
import org.opentrafficsim.core.distributions.ConstantSupplier;
import org.opentrafficsim.core.distributions.FrequencyAndObject;
import org.opentrafficsim.core.distributions.ObjectDistribution;
import org.opentrafficsim.core.dsol.OtsSimulatorInterface;
import org.opentrafficsim.core.gtu.GtuCharacteristics;
import org.opentrafficsim.core.gtu.GtuErrorHandler;
import org.opentrafficsim.core.gtu.GtuException;
import org.opentrafficsim.core.gtu.GtuType;
import org.opentrafficsim.core.idgenerator.IdSupplier;
import org.opentrafficsim.core.network.Link;
import org.opentrafficsim.core.network.NetworkException;
import org.opentrafficsim.core.network.Node;
import org.opentrafficsim.core.network.route.FixedRouteGenerator;
import org.opentrafficsim.core.network.route.ProbabilisticRouteGenerator;
import org.opentrafficsim.core.network.route.Route;
import org.opentrafficsim.core.object.DetectorType;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioGenerator;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioOutputConfiguration;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.libraries.DesiredSpeedLibrary;
import org.opentrafficsim.draw.graphs.GraphPath;
import org.opentrafficsim.road.gtu.generator.GeneratorPositions;
import org.opentrafficsim.road.gtu.generator.LaneBasedGtuGenerator;
import org.opentrafficsim.road.gtu.generator.TtcRoomChecker;
import org.opentrafficsim.road.gtu.generator.characteristics.LaneBasedGtuCharacteristics;
import org.opentrafficsim.road.gtu.generator.characteristics.LaneBasedGtuCharacteristicsGeneratorOd;
import org.opentrafficsim.road.gtu.generator.characteristics.LaneBasedGtuTemplate;
import org.opentrafficsim.road.gtu.generator.characteristics.LaneBasedGtuTemplateDistribution;
import org.opentrafficsim.road.gtu.generator.headway.HeadwayGenerator;
import org.opentrafficsim.road.gtu.lane.VehicleModel;
import org.opentrafficsim.road.gtu.lane.tactical.following.CarFollowingModelFactory;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.DefaultMirovaPerceptionFactory;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.MirovaTacticalPlannerFactory;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ReactiveLayer.AbstractWiedemannModel;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ReactiveLayer.MirovaIdmPlusFactory;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ReactiveLayer.W99ParameterTypes;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ReactiveLayer.Wiedemann99;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ReactiveLayer.Wiedemann99Factory;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging.extendeddata.ExtendedDataActionState;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging.extendeddata.ExtendedDataCurrentCFAcceleration;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging.extendeddata.ExtendedDataCurrentDesiredSpeed;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging.extendeddata.ExtendedDataEgoDecelLeft;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging.extendeddata.ExtendedDataEgoDecelRight;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging.extendeddata.ExtendedDataFollowerDecelLeft;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging.extendeddata.ExtendedDataFollowerDecelRight;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging.extendeddata.ExtendedDataFrontGapDeltaSpeed;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging.extendeddata.ExtendedDataFrontGapDistance;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging.extendeddata.ExtendedDataFrontGapTimeHeadway;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging.extendeddata.ExtendedDataHeadwayRelaxationProgress;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging.extendeddata.ExtendedDataIsChangingLane;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging.extendeddata.ExtendedDataLaneChangeDesireLeft;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging.extendeddata.ExtendedDataLaneChangeDesireRight;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging.extendeddata.ExtendedDataLaneChangePlan;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging.extendeddata.ExtendedDataLaneChangePlanDirection;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging.extendeddata.ExtendedDataRelaxationTargetHeadway;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging.extendeddata.ExtendedDataRelaxedHeadway;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging.extendeddata.ExtendedDataSocioSpeedPressure;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging.extendeddata.ExtendedDataW99DrivingMode;
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
import org.opentrafficsim.road.gtu.lane.tactical.following.IdmPlusFactory;
import org.opentrafficsim.road.gtu.lane.tactical.lmrs.DefaultLmrsPerceptionFactory;
import org.opentrafficsim.road.gtu.lane.tactical.lmrs.LmrsFactory;
import org.opentrafficsim.road.gtu.lane.tactical.util.lmrs.LmrsParameters;

import nl.tudelft.simulation.jstats.distributions.DistContinuous;
import nl.tudelft.simulation.jstats.distributions.DistNormal;
import nl.tudelft.simulation.jstats.distributions.DistUniform;
import nl.tudelft.simulation.jstats.streams.MersenneTwister;
import nl.tudelft.simulation.jstats.streams.StreamInterface;

public class FreiburgNord extends ScenarioGenerator
{

    public FreiburgNord()
    {
        super("FreiburgNord");
    }

    @Override
    public void buildNetwork(final OtsSimulatorInterface sim) throws Exception
    {
        URL xmlURL = URLResource.getResource("/resources/mirova/FreiburgNord.xml");
        this.network = new RoadNetwork("FreiburgNord", sim);
        // XmlParser/JAXB uses shared OTS default objects (NL stripe definitions) that are not thread-safe
        synchronized (FreiburgNord.class)
        {
            new XmlParser(this.network).setUrl(xmlURL).build();
        }

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

    @Override
    public RoadNetwork setupSimulation(final OtsSimulatorInterface sim, final ScenarioParameters params) throws Exception
    {
        this.stream = new MersenneTwister(params.getSeed());

        buildNetwork(sim);
        getOutputConfiguration().setRoadNetwork(this.network);
        buildRoutes();
        buildGtuTemplates(sim);
        buildRoadSamplers();
        buildOutputConfiguration();
        // createVehiclesFromGenerator(params, sim);
        createVehiclesFromODMatrix(params, sim);
        return this.network;
    }

    public void createVehiclesFromGenerator(final ScenarioParameters params, final OtsSimulatorInterface sim) throws Exception
    {
        HeadwayGenerator headwayGenerator =
                new HeadwayGenerator(new Frequency(params.getDemand(), FrequencyUnit.PER_HOUR), this.stream);

        ObjectDistribution<LaneBasedGtuTemplate> gtuTypeDistribution = new ObjectDistribution<>(this.stream);
        gtuTypeDistribution.add(new FrequencyAndObject<>(1.0 - params.getTruckShare(), this.gtuTemplates.get(DefaultsNl.CAR)));
        gtuTypeDistribution.add(new FrequencyAndObject<>(params.getTruckShare(), this.gtuTemplates.get(DefaultsNl.TRUCK)));

        LaneBasedGtuTemplateDistribution characteristicsGenerator = new LaneBasedGtuTemplateDistribution(gtuTypeDistribution);

        // Create generator
        new LaneBasedGtuGenerator("Gen", headwayGenerator, characteristicsGenerator,
                GeneratorPositions.create(this.initialLongitudinalPositions, this.stream, getLaneBiases()), this.network, sim,
                new TtcRoomChecker(new Duration(1.0, DurationUnit.SI)), new IdSupplier(""));

    }

    @Override
    public void buildGtuTemplates(final OtsSimulatorInterface sim) throws Exception
    {
        LaneBasedStrategicalPlannerFactory<?> strategicalPlannerFactoryCars = buildStrategicalPlannerFactoryCar();
        // LaneBasedStrategicalPlannerFactory<?> strategicalPlannerFactoryCars = buildLmrsStrategicalPlannerFactoryCar();
        LaneBasedStrategicalPlannerFactory<?> strategicalPlannerFactoryTrucks = buildStrategicalPlannerFactoryTruck();
        // LaneBasedStrategicalPlannerFactory<?> strategicalPlannerFactoryTrucks = buildLmrsStrategicalPlannerFactoryTruck();
        FrequencyAndObject<Route> routeAE =
                new FrequencyAndObject<Route>(1.0 - this.defaultParameters.getMergeShare(), this.routes.get("A-E"));
        FrequencyAndObject<Route> routeFE =
                new FrequencyAndObject<Route>(this.defaultParameters.getMergeShare(), this.routes.get("F-E"));

        Supplier<Route> routeGenerator = new ProbabilisticRouteGenerator(List.of(routeAE, routeFE), this.stream);

        LaneBasedGtuTemplate car = new LaneBasedGtuTemplate(DefaultsNl.CAR, new ConstantSupplier<>(Length.instantiateSI(4.0)),
                new ConstantSupplier<>(Length.instantiateSI(2.0)), DesiredSpeedLibrary.hoogendoornCars(this.stream),
                strategicalPlannerFactoryCars, routeGenerator);

        this.gtuTemplates.put(DefaultsNl.CAR, car);

        LaneBasedGtuTemplate truck = new LaneBasedGtuTemplate(DefaultsNl.TRUCK,
                new ConstantSupplier<>(Length.instantiateSI(12.0)), new ConstantSupplier<>(Length.instantiateSI(2.5)),
                DesiredSpeedLibrary.hoogendoornTrucks(this.stream), strategicalPlannerFactoryTrucks, routeGenerator);

        this.gtuTemplates.put(DefaultsNl.TRUCK, truck);
    }

    /**
     * ------------------------------------------------------------ Build strategical planner factory for cars
     * @return
     */
    public LaneBasedStrategicalPlannerFactory<?> buildStrategicalPlannerFactoryCar()
    {
        // CarFollowingModelFactory<Wiedemann99> w99CarFactory = new Wiedemann99Factory(this.stream) {
        // @Override
        // public Parameters getParameters() throws ParameterException {
        // ParameterSet parameters = new ParameterSet();
        // parameters.setDefaultParameters(W99ParameterTypes.class);
        // //parameters.setParameter(ParameterTypes.T, Duration.instantiateSI(1.0)); // desired time headway
        // DistContinuous fSpeed = new DistNormal(FreiburgNord.this.stream, 123.7 / 120.0, 0.1);
        // parameters.setParameter(AbstractWiedemannModel.FSPEED, fSpeed.draw());
        // return parameters;
        // }
        // };

        MirovaTacticalPlannerFactory mirovaTacticalPlannerFactoryCars =
                new MirovaTacticalPlannerFactory(new MirovaIdmPlusFactory(this.stream), new DefaultMirovaPerceptionFactory())
                {
                    @Override
                    public Parameters getParameters() throws ParameterException
                    {
                        Parameters parameters = getDefaultParameters();

                        // parameters.setParameter(ParameterTypes.TMAX, new Duration(0.7, DurationUnit.SI));
                        // parameters.setParameter(ParameterTypes.TMIN, new Duration(0.6, DurationUnit.SI));
                        parameters.setParameter(ParameterTypes.T, new Duration(0.9, DurationUnit.SI)); // desired time headway
                        parameters.setParameter(MirovaParameters.socioSpeedSensitivity, 0.75);
                        DistContinuous vGain = new DistUniform(FreiburgNord.this.stream, 20, 50);
                        parameters.setParameter(MirovaParameters.vGain, new Speed(vGain.draw(), SpeedUnit.KM_PER_HOUR));
                        return parameters;
                    }
                };

        LaneBasedStrategicalPlannerFactory<?> strategicalPlannerFactoryCars =
                new LaneBasedStrategicalRoutePlannerFactory(mirovaTacticalPlannerFactoryCars);

        return strategicalPlannerFactoryCars;
    }

    public LaneBasedStrategicalPlannerFactory<?> buildLmrsStrategicalPlannerFactoryCar() throws ParameterException
    {
        LmrsFactory lmrsTacticalPlannerFactoryCars =
                new LmrsFactory(new MirovaIdmPlusFactory(this.stream), new DefaultLmrsPerceptionFactory());

        lmrsTacticalPlannerFactoryCars.getParameters().setParameter(ParameterTypes.TMAX, new Duration(0.7, DurationUnit.SI));
        lmrsTacticalPlannerFactoryCars.getParameters().setParameter(ParameterTypes.TMIN, new Duration(0.6, DurationUnit.SI));
        // DistContinuous vGain = new DistUniform(FreiburgNord.this.stream, 20, 50);
        // lmrsTacticalPlannerFactoryCars.getParameters().setParameter(LmrsParameters.VGAIN, new Speed(30,
        // SpeedUnit.KM_PER_HOUR));

        LaneBasedStrategicalPlannerFactory<?> strategicalPlannerFactoryCars =
                new LaneBasedStrategicalRoutePlannerFactory(lmrsTacticalPlannerFactoryCars);

        return strategicalPlannerFactoryCars;
    }

    public LaneBasedStrategicalPlannerFactory<?> buildStrategicalPlannerFactoryTruck()
    {
        // CarFollowingModelFactory<Wiedemann99> w99TruckFactory = new Wiedemann99Factory(this.stream) {
        // @Override
        // public Parameters getParameters() throws ParameterException {
        // ParameterSet parameters = new ParameterSet();
        // parameters.setDefaultParameters(W99ParameterTypes.class);
        // DistContinuous fSpeed = new DistNormal(FreiburgNord.this.stream, 123.7 / 120.0, 0.1);
        // parameters.setParameter(AbstractWiedemannModel.FSPEED, fSpeed.draw());
        // return parameters;
        // }
        // };

        MirovaTacticalPlannerFactory mirovaTacticalPlannerFactoryTrucks =
                new MirovaTacticalPlannerFactory(new MirovaIdmPlusFactory(this.stream), new DefaultMirovaPerceptionFactory())
                {
                    @Override
                    public Parameters getParameters() throws ParameterException
                    {
                        Parameters parameters = getDefaultParameters();
                        // parameters.setParameter(ParameterTypes.TMAX, new Duration(0.9, DurationUnit.SI));
                        // parameters.setParameter(ParameterTypes.TMIN, new Duration(0.8, DurationUnit.SI));
                        parameters.setParameter(ParameterTypes.T, new Duration(1.2, DurationUnit.SI)); // desired time headway
                        DistContinuous vGain = new DistUniform(FreiburgNord.this.stream, 90, 110);
                        parameters.setParameter(MirovaParameters.vGain, new Speed(vGain.draw(), SpeedUnit.KM_PER_HOUR)); // higher
                                                                                                                         // vGain
                                                                                                                         // for
                                                                                                                         // trucks
                                                                                                                         // to
                                                                                                                         // reduce
                                                                                                                         // discretionary
                                                                                                                         // lane
                                                                                                                         // changes
                        parameters.setParameter(MirovaParameters.socioSpeedSensitivity, 0.75); // more conservative lane changes
                                                                                               // for trucks
                        parameters.setParameter(MirovaParameters.cooperativeLaneChangesEnabled, false); // disable cooperative
                                                                                                        // lane changes for
                                                                                                        // trucks
                        return parameters;
                    }
                };

        LaneBasedStrategicalPlannerFactory<?> strategicalPlannerFactoryTrucks =
                new LaneBasedStrategicalRoutePlannerFactory(mirovaTacticalPlannerFactoryTrucks);

        return strategicalPlannerFactoryTrucks;
    }

    public LaneBasedStrategicalPlannerFactory<?> buildLmrsStrategicalPlannerFactoryTruck() throws ParameterException
    {
        LmrsFactory lmrsTacticalPlannerFactoryTrucks =
                new LmrsFactory(new MirovaIdmPlusFactory(this.stream), new DefaultLmrsPerceptionFactory());

        lmrsTacticalPlannerFactoryTrucks.getParameters().setParameter(ParameterTypes.TMAX, new Duration(0.9, DurationUnit.SI));
        lmrsTacticalPlannerFactoryTrucks.getParameters().setParameter(ParameterTypes.TMIN, new Duration(0.8, DurationUnit.SI));
        // DistContinuous vGain = new DistUniform(FreiburgNord.this.stream, 90, 110);
        // lmrsTacticalPlannerFactoryTrucks.getParameters().setParameter(LmrsParameters.VGAIN, new Speed(30,
        // SpeedUnit.KM_PER_HOUR)); // higher vGain for trucks to reduce discretionary lane changes

        LaneBasedStrategicalPlannerFactory<?> strategicalPlannerFactoryTrucks =
                new LaneBasedStrategicalRoutePlannerFactory(lmrsTacticalPlannerFactoryTrucks);

        return strategicalPlannerFactoryTrucks;
    }

    public LaneBasedGtuCharacteristicsGeneratorOd buildOdsCharacteristicsGenerator(final OtsSimulatorInterface sim)
    {
        return new LaneBasedGtuCharacteristicsGeneratorOd()
        {
            @Override
            public LaneBasedGtuCharacteristics draw(final Node origin, final Node destination, final Category category,
                    final StreamInterface randomStream) throws GtuException
            {
                GtuType gtuType = category.get(GtuType.class);
                LaneBasedGtuTemplate template = FreiburgNord.this.gtuTemplates.get(gtuType);
                Route route = null;
                try
                {
                    route = FreiburgNord.this.network.getShortestRouteBetween(gtuType, origin, destination);

                }
                catch (NetworkException exception)
                {
                    exception.printStackTrace();
                }
                GtuCharacteristics gtuCharacteristics = getGtuTemplates().get(gtuType).get(); // Defaults.NL.apply(gtuType,
                                                                                              // randomStream).get() ;
                VehicleModel vehicleModel = VehicleModel.MINMAX;
                LaneBasedStrategicalPlannerFactory<?> strategical =
                        FreiburgNord.this.gtuTemplates.get(gtuType).getStrategicalPlannerFactory();
                return new LaneBasedGtuCharacteristics(gtuCharacteristics, strategical, route, origin, destination,
                        vehicleModel);
            }
        };
    }

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
                "D:\\Mitarbeitende\\gw2128\\repositories\\diss_mvb\\data\\simulation_demand_test.csv", String.class);
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

    @Override
    public List<Node> getOrigins(final RoadNetwork network)
    {
        List<Node> origins = new ArrayList<>();
        origins.add(network.getNode("N1_1"));
        origins.add(network.getNode("R7_1"));
        return origins;
    }

    @Override
    public List<Node> getDestinations(final RoadNetwork network)
    {
        List<Node> destinations = new ArrayList<>();
        destinations.add(network.getNode("N5_3"));
        destinations.add(network.getNode("R6_8"));
        return destinations;
    }

    @Override
    public void setDefaultParameters()
    {
        this.defaultParameters.setDemand(4500.0); // vehicles per hour
        this.defaultParameters.setTruckShare(0.1); // 5% trucks
        this.defaultParameters.setSeed(42L); // random see
        this.defaultParameters.setMergeShare(0.2); // 20% of overall demand merges from on-ramp
    }

    public GeneratorPositions.LaneBiases getLaneBiases()
    {
        GeneratorPositions.LaneBiases laneBiases = new GeneratorPositions.LaneBiases();
        laneBiases.addBias(DefaultsNl.VEHICLE, GeneratorPositions.LaneBias.bySpeed(150, 80)); // slow vehicles prefer right lane
        return laneBiases;
    }

    public Map<GtuType, LaneBasedGtuTemplate> getGtuTemplates()
    {
        return this.gtuTemplates;
    }

    /**
     * ------------------------------------------------------------ Build road samplers
     * @param sim OtsSimulatorInterface
     * @throws NetworkException
     */
    @Override
    public void buildRoadSamplers() throws NetworkException
    {

        RoadSampler sampler = RoadSampler.build(this.network)
                // .registerExtendedDataType(new ExtendedDataRelaxedHeadway())
                // .registerExtendedDataType(new ExtendedDataHeadwayRelaxationProgress())
                // .registerExtendedDataType(new ExtendedDataRelaxationTargetHeadway())
                .registerExtendedDataType(new ExtendedDataActionState())
                .registerExtendedDataType(new ExtendedDataLaneChangeDesireLeft())
                .registerExtendedDataType(new ExtendedDataLaneChangeDesireRight())
                // .registerExtendedDataType(new ExtendedDataIsChangingLane())
                // .registerExtendedDataType(new ExtendedDataLaneChangePlan())
                // .registerExtendedDataType(new ExtendedDataLaneChangePlanDirection())
                // .registerExtendedDataType(new ExtendedDataFrontGapTimeHeadway())
                // .registerExtendedDataType(new ExtendedDataFrontGapDeltaSpeed())
                // .registerExtendedDataType(new ExtendedDataFrontGapDistance())
                // //.registerExtendedDataType(new ExtendedDataW99DrivingMode())
                // .registerExtendedDataType(new ExtendedDataFollowerDecelRight())
                // .registerExtendedDataType(new ExtendedDataFollowerDecelLeft())
                // .registerExtendedDataType(new ExtendedDataEgoDecelRight())
                // .registerExtendedDataType(new ExtendedDataEgoDecelLeft())
                // //.registerExtendedDataType(new ExtendedDataCurrentCFAcceleration())
                // .registerExtendedDataType(new ExtendedDataCurrentDesiredSpeed())
                // //.registerExtendedDataType(new ExtendedDataSocioSpeedPressure())
                .create();

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
                if ((linkId.equals("L1a") && lane.getId().startsWith("FORWARD"))
                        || (linkId.equals("L7a") && lane.getId().equals("FORWARD1"))
                        || (linkId.equals("L5a") && lane.getId().startsWith("FORWARD")))
                {
                    this.listLoopDetectors.add(new LoopDetector("det_" + lane.getFullId(),
                            new LanePosition(lane, lane.getLength().times(0.5)), Length.ZERO, DefaultsNl.LOOP_DETECTOR,
                            Time.instantiateSI(0.0), Duration.instantiateSI(60.0), LoopDetector.HARMONIC_MEAN_SPEED));
                }
                if (linkId.equals("L2a"))
                {
                    GraphPath<LaneDataRoad> path = GraphLaneUtil.createPath("path", lane);
                    sampler.scheduleStartRecording(Time.instantiateSI(0), path.get(0).getSource(0));
                }
            }
        }

        this.listRoadSamplers.add(sampler);

    }

    @Override
    public ScenarioOutputConfiguration buildOutputConfiguration()
    {
        this.outputConfiguration.setRoadNetwork(network).addRoadSamplers(this.listRoadSamplers)
                .addLoopDetectors(this.listLoopDetectors);
        return this.outputConfiguration;
    }
}
