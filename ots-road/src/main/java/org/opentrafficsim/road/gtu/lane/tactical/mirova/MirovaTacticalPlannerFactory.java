package org.opentrafficsim.road.gtu.lane.tactical.mirova;

import java.io.Serializable;

import org.djunits.value.vdouble.scalar.Duration;
import org.opentrafficsim.base.parameters.ParameterException;
import org.opentrafficsim.base.parameters.ParameterSet;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.base.parameters.Parameters;
import org.opentrafficsim.core.gtu.plan.operational.OperationalPlanException;
import org.opentrafficsim.road.gtu.lane.LaneBasedGtu;
import org.opentrafficsim.road.gtu.lane.perception.PerceptionFactory;
import org.opentrafficsim.road.gtu.lane.tactical.AbstractLaneBasedTacticalPlannerFactory;
import org.opentrafficsim.road.gtu.lane.tactical.following.CarFollowingModel;
import org.opentrafficsim.road.gtu.lane.tactical.following.CarFollowingModelFactory;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.DesireLayer.CongestionIncentive;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.DesireLayer.CruisingSpeedIncentive;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.DesireLayer.KeepRightIncentive;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.DesireLayer.RouteIncentive;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.DesireLayer.ProhibitDeadEndIncentive;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ManeuverPatterns.AnticipateAdjacentCongestionPattern;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ManeuverPatterns.AnticipateDownstreamMergePattern;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ManeuverPatterns.GapOpenerPattern;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ManeuverPatterns.MandatoryLaneChangePattern;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ManeuverPatterns.PreventUndercuttingPattern;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ManeuverPatterns.SimpleLaneChangePattern;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ManeuverPatterns.old.exclusive.GapSearchPattern;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ManeuverPatterns.old.parallel.AnticipatingUpstreamMergingSpeedPattern;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ManeuverPatterns.old.parallel.MergeCooperationPattern;
import org.opentrafficsim.road.gtu.lane.tactical.util.ConflictUtil;
import org.opentrafficsim.road.gtu.lane.tactical.util.TrafficLightUtil;
import org.opentrafficsim.road.gtu.lane.tactical.util.lmrs.LmrsParameters;
import org.opentrafficsim.road.gtu.lane.tactical.util.lmrs.LmrsUtil;

/**
 * Factory that creates instances of {@link MirovaTacticalPlanner}.
 * <p>
 * This factory initializes the cognitive architecture of the MiRoVA framework for a GTU. It sets up the foundational layers by
 * registering the declarative knowledge (Layer 2) via
 * {@link org.opentrafficsim.road.gtu.lane.tactical.mirova.core.DesireLayer.DesireIncentive}s and the procedural knowledge
 * (Layer 4) via {@link org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ManeuverPattern}s. It also provides
 * the default parameter sets required for the perception and tactical models.
 * </p>
 * <p>
 * Copyright (c) 2025 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class MirovaTacticalPlannerFactory extends AbstractLaneBasedTacticalPlannerFactory<MirovaTacticalPlanner>
        implements Serializable
{

    /**
     * Constructor allowing custom car-following model and perception factory.
     * @param carFollowingModelFactory factory to generate the car-following model
     * @param perceptionFactory factory to generate the perception module
     */
    public MirovaTacticalPlannerFactory(final CarFollowingModelFactory<? extends CarFollowingModel> carFollowingModelFactory,
            final PerceptionFactory perceptionFactory)
    {
        super(carFollowingModelFactory, perceptionFactory);
    }

    /**
     * Creates a fully initialized {@link MirovaTacticalPlanner} for the given GTU.
     * @param gtu the lane-based GTU to attach the tactical planner to
     * @return the generated MiRoVA tactical planner
     */
    @Override
    public MirovaTacticalPlanner create(final LaneBasedGtu gtu)
    {
        try
        {
            gtu.setParameters(getParameters());
            MirovaTacticalPlanner planner =
                    new MirovaTacticalPlanner(nextCarFollowingModel(gtu), gtu, getPerceptionFactory().generatePerception(gtu));
            setDesireLayer(planner);
            setIntentionLayer(planner);
            return planner;
        }
        catch (Exception e)
        {
            throw new RuntimeException("Could not create MirovaTacticalPlanner.", e);
        }
    }

    /**
     * Retrieves the parameters required for the MiRoVA tactical planner.
     * @return a set of default parameters
     * @throws ParameterException if a parameter cannot be initialized
     */
    @Override
    public Parameters getParameters() throws ParameterException
    {
        return getDefaultParameters();
    }

    /**
     * Builds and returns the default parameter set for the MiRoVA framework.
     * @return a {@link Parameters} set containing all base settings
     * @throws ParameterException if setting a default parameter fails
     */
    public Parameters getDefaultParameters() throws ParameterException
    {
        ParameterSet parameters = new ParameterSet();
        parameters.setDefaultParameters(ConflictUtil.class);
        parameters.setDefaultParameters(TrafficLightUtil.class);
        parameters.setDefaultParameters(LmrsUtil.class);
        parameters.setDefaultParameters(LmrsParameters.class);

        parameters.setDefaultParameter(ParameterTypes.VCONG);
        parameters.setDefaultParameter(ParameterTypes.T0);
        parameters.setDefaultParameter(ParameterTypes.LCDUR);

        parameters.setDefaultParameter(ParameterTypes.A);
        parameters.setDefaultParameter(ParameterTypes.B);
        parameters.setDefaultParameter(ParameterTypes.BCRIT);
        parameters.setDefaultParameter(ParameterTypes.B0);
        parameters.setDefaultParameter(ParameterTypes.TMIN);
        parameters.setDefaultParameter(ParameterTypes.TMAX);
        parameters.setDefaultParameter(ParameterTypes.TAU);
        parameters.setDefaultParameter(ParameterTypes.LOOKAHEAD);
        parameters.setDefaultParameter(ParameterTypes.LOOKBACK);

        getCarFollowingParameters().setAllIn(parameters);
        getPerceptionFactory().getParameters().setAllIn(parameters);

        parameters.setDefaultParameters(MirovaParameters.class);

        // Overwrite default DT for MiRoVA specific simulation precision
        parameters.setParameter(ParameterTypes.DT, Duration.instantiateSI(0.2));

        return parameters;
    }

    /**
     * Registers the initial declarative knowledge components (Layer 2) to the tactical planner.
     * @param planner the MiRoVA tactical planner instance
     * @throws ParameterException if required parameters are missing
     * @throws OperationalPlanException if planning capabilities are compromised
     */
    protected void setDesireLayer(final MirovaTacticalPlanner planner) throws ParameterException, OperationalPlanException
    {
        planner.addKnowledgeChunk(new CruisingSpeedIncentive(planner));
        planner.addKnowledgeChunk(new KeepRightIncentive(planner));
        planner.addKnowledgeChunk(new RouteIncentive(planner));
        planner.addKnowledgeChunk(new ProhibitDeadEndIncentive(planner));
        // planner.addKnowledgeChunk(new CongestionIncentive(planner));
    }

    /**
     * Registers the initial procedural maneuver patterns (Layer 4) to the tactical planner.
     * @param planner the MiRoVA tactical planner instance
     * @throws ParameterException if required parameters are missing
     */
    protected void setIntentionLayer(final MirovaTacticalPlanner planner) throws ParameterException
    {
        // Exclusive maneuvers (one at a time)
        // planner.addManeuverPattern(new GapSearchPattern(planner));
        planner.addManeuverPattern(new SimpleLaneChangePattern(planner));

        // Parallel maneuvers (can run simultaneously alongside standard car-following)
        planner.addManeuverPattern(new PreventUndercuttingPattern(planner));
        planner.addManeuverPattern(new MandatoryLaneChangePattern(planner));
        planner.addManeuverPattern(new GapOpenerPattern(planner));
        // Deactivated: on this facility it produced no measurable cooperation and one large artefact.
        // Its activation cannot tell a lane drop from the end of the modelled network - it asks
        // InfrastructureContext for the distance to the end of an adjacent lane, which is finite on the
        // last link because no lane there has a successor, and any vehicle in that lane counts as ramp
        // traffic. NearAnticipationState then decelerates while the ego is above VCONG (60 km/h), so every
        // vehicle on the final link was held at exactly that speed: 83-95 % of all intervals at det_L5a
        // sat between 59 and 62 km/h with a hard floor at 58.9, while det_L3a ran at 119.
        //
        // A paired comparison over 10 seeds, with and without: speed at det_L5a +29 and +47 km/h
        // (t = 314 and 91), and nothing else moved - merges -2.5 (t = -0.55), merge speed +6.1
        // (t = 1.07), flow +27 veh/h (t = 0.45), standstills -24 (t = -0.84). It also carried a tail
        // risk: on one seed of ten the facility collapsed to a merge speed of 8 km/h with 344 vehicles
        // stopped on the ramp, against 66 km/h and 69 without the pattern.
        //
        // Re-enabling it requires the activation to establish that a lane actually drops - the ego's own
        // lane continuing past the point where the adjacent one ends - and the running state to re-check
        // that, since PatternSelector only calls checkContext() while the pattern is not running.
        // planner.addManeuverPattern(new AnticipateDownstreamMergePattern(planner));
        // planner.addManeuverPattern(new AnticipateAdjacentCongestionPattern(planner));
    }

}
