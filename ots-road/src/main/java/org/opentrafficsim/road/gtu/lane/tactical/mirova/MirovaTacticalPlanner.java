package org.opentrafficsim.road.gtu.lane.tactical.mirova;

import org.djunits.unit.AccelerationUnit;
import org.djunits.unit.DurationUnit;
import org.djunits.unit.LengthUnit;
import org.djunits.unit.SpeedUnit;
import org.djunits.value.vdouble.scalar.*;
import org.djutils.draw.point.DirectedPoint2d;
import org.djutils.exceptions.Try;
import org.opentrafficsim.base.parameters.*;
import org.opentrafficsim.core.gtu.*;
import org.opentrafficsim.core.gtu.perception.*;
import org.opentrafficsim.core.gtu.plan.operational.OperationalPlan;
import org.opentrafficsim.core.gtu.plan.operational.OperationalPlanException;
import org.opentrafficsim.core.network.LateralDirectionality;
import org.opentrafficsim.core.network.Link;
import org.opentrafficsim.core.network.NetworkException;
import org.opentrafficsim.road.gtu.lane.*;
import org.opentrafficsim.road.gtu.lane.perception.*;
import org.opentrafficsim.road.gtu.lane.perception.categories.*;
import org.opentrafficsim.road.gtu.lane.perception.categories.neighbors.DirectNeighborsPerception;
import org.opentrafficsim.road.gtu.lane.perception.categories.neighbors.NeighborsPerception;
import org.opentrafficsim.road.gtu.lane.perception.headway.*;
import org.opentrafficsim.road.gtu.lane.plan.operational.*;
import org.opentrafficsim.road.gtu.lane.tactical.AbstractLaneBasedTacticalPlanner;
import org.opentrafficsim.road.gtu.lane.tactical.following.CarFollowingModel;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.*;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ArbitrationLayer.HybridPlanArbitrator;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ArbitrationLayer.PatternSelector;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.*;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.DesireLayer.Desire;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.DesireLayer.DesireIncentive;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ActionState;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ManeuverPattern;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ReactiveLayer.MirovaCarFollowingUtil;
import org.opentrafficsim.road.gtu.lane.tactical.util.ConflictUtil;
import org.opentrafficsim.road.gtu.lane.tactical.util.SpeedLimitUtil;
import org.opentrafficsim.road.gtu.lane.tactical.util.TrafficLightUtil;
import org.opentrafficsim.road.gtu.lane.tactical.util.lmrs.LmrsParameters;
import org.opentrafficsim.road.gtu.lane.tactical.util.lmrs.LmrsUtil;
import org.opentrafficsim.road.network.*;
import org.opentrafficsim.road.network.lane.Lane;
import org.opentrafficsim.road.network.speed.*;

import org.opentrafficsim.road.gtu.lane.tactical.mirova.util.VehicleDiffusionLogger;

import java.util.*;

/**
 * Abstract base vehicle for the MIROVA tactical framework.
 * <p>
 * Provides:
 * <ul>
 * <li>Integration of LMRS-based tactical reasoning</li>
 * <li>Voting arbiter for maneuver arbitration</li>
 * <li>Central {@link VehicleContextManager} for contextual data handling</li>
 * </ul>
 * <p>
 * Copyright (c) 2025 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class MirovaTacticalPlanner extends AbstractLaneBasedTacticalPlanner
{
    // ----------------------------------------------------------------------
    // Tactical and Planning Components
    // ----------------------------------------------------------------------

    /** Serial version UID for serialization compatibility. */
    private static final long serialVersionUID = 1L;

    /** The active action state of the currently executing maneuver. */
    protected ActionState currentActionState = null;

    /** The operational plan generated for the current simulation step. */
    protected SimpleOperationalPlan operationalPlan;

    /** The lane change object handling the physical lane change constraints. */
    protected final LaneChange laneChange;

    /** The maneuver pattern that won the arbitration in the previous simulation step. */
    protected ManeuverPattern lastActivePattern = null;

    /** Hybrid three-step arbitrator for selecting the operational plan each tick. */
    protected HybridPlanArbitrator hybridArbitrator;

    // ----------------------------------------------------------------------
    // LMRS Desire Dynamics
    // ----------------------------------------------------------------------

    /** Current total lateral desire vector (left/right). */
    protected Desire laneChangeDesire = Desire.zero();

    /** Current mandatory lateral desire vector (left/right). */
    protected Desire mandatoryLaneChangeDesire = Desire.zero();

    /** Current discretionary lateral desire vector (left/right). */
    protected Desire discretionaryLaneChangeDesire = Desire.zero();

    /** Absolute magnitude of the current lane change desire. */
    protected Double absoluteDesire = 0.0;

    /** Relaxation time for the desire vector. */
    protected Duration desireRelaxationTime = new Duration(0.0, DurationUnit.SI);

    /** Socio-speed pressure experienced by the GTU. */
    private Double socioSpeedPressure = 0.0;

    /** Time since last lane change maneuver started. */
    private Duration timeSinceLastLaneChange = new Duration(0.0, DurationUnit.SI);

    /** GTU specific parameters. */
    private Parameters params;

    // ----------------------------------------------------------------------
    // Knowledge Base and Patterns
    // ----------------------------------------------------------------------

    /** Declarative knowledge base for this vehicle. */
    protected final List<DesireIncentive> knowledgeChunks = new ArrayList<>();

    /** Procedural knowledge: the maneuver patterns available to this vehicle. */
    protected final List<ManeuverPattern> maneuverPatterns = new ArrayList<>();

    /**
     * * The ActionState that currently locks the tactical planner. This is ONLY set during physical points of no return (e.g.,
     * executing a lane change).
     */
    protected ActionState lockedActionState = null;

    // ----------------------------------------------------------------------
    // Context Manager Integration
    // ----------------------------------------------------------------------

    /** Central contextual model for this vehicle. */
    private final VehicleContextManager contextManager;

    /** Simulation time at which the vehicle was created. */
    private Duration createTime;

    /** Simulation time at which the vehicle became stopped/deadlocked at route end or during a lane change. */
    private Duration stoppageStartTime = null;

    // ----------------------------------------------------------------------
    // Construction
    // ----------------------------------------------------------------------

    /**
     * Instantiates the MIROVA Tactical Planner. * @param carFollowingModel the car following model
     * @param gtu the lane based GTU
     * @param lanePerception the lane perception system
     * @throws ParameterException if a required parameter is missing
     */
    public MirovaTacticalPlanner(final CarFollowingModel carFollowingModel, final LaneBasedGtu gtu,
            final LanePerception lanePerception) throws ParameterException
    {
        super(carFollowingModel, gtu, lanePerception);

        this.laneChange = Try.assign(() -> new LaneChange(gtu), "Parameter LCDUR is required.", GtuException.class);
        this.contextManager = new VehicleContextManager(this);
        this.params = getGtu().getParameters();
        this.laneChange.setDesiredLaneChangeDuration(getGtu().getParameters().getParameter(ParameterTypes.LCDUR));
        this.createTime = gtu.getSimulator().getSimulatorTime();
        this.hybridArbitrator = new HybridPlanArbitrator(this);
    }

    // ----------------------------------------------------------------------
    // Main Tactical Update
    // ----------------------------------------------------------------------

    /**
     * Generates the operational plan for the current simulation step.
     * <p>
     * This method checks if the vehicle is fully positioned; if not, it returns a default plan to skip the current step.
     * Otherwise, it invokes the main tactical update routine to compute the vehicle's behavior.
     * </p>
     * @param startTime the start time of the operational plan
     * @param locationAtStartTime the location of the vehicle at the start time
     * @return the generated {@link OperationalPlan}
     * @throws GtuException if GTU-related errors occur
     * @throws NetworkException if network-related errors occur
     * @throws ParameterException if parameter access fails
     */
    @Override
    public OperationalPlan generateOperationalPlan(final Time startTime, final DirectedPoint2d locationAtStartTime)
            throws GtuException, NetworkException, ParameterException
    {
        if (getGtu().isDestroyed())
        {
            return OperationalPlan.standStill(getGtu(), locationAtStartTime, startTime, Duration.ZERO);
        }

        Duration dt = getGtu().getParameters().getParameter(ParameterTypes.DT);
        SimpleOperationalPlan plan;
        Boolean justCreated = (startTime.si < this.createTime.si + 1.0);

        if (getGtu().getFront() == null || getGtu().getReferencePosition() == null || getGtu().getOperationalPlan() == null
                || justCreated)
        {
            // GTU is not fully positioned yet -> skip this tick
            Acceleration acc = getGtu().getCarFollowingAcceleration();
            plan = new SimpleOperationalPlan(acc, dt);
        }
        else
        {
            plan = this.update();
        }

        if (getGtu().isDestroyed())
        {
            return OperationalPlan.standStill(getGtu(), locationAtStartTime, startTime, Duration.ZERO);
        }

        return LaneOperationalPlanBuilder.buildPlanFromSimplePlan(getGtu(), startTime, plan, this.getLaneChange());
    }

    /**
     * Checks if the GTU is stopped due to an off-ramp / route-end deadlock or blocked lane change,
     * and diffuses (destroys) the GTU if the stoppage duration exceeds vehicleDiffusionTime.
     * @return true if the vehicle was diffused (destroyed), false otherwise
     */
    protected boolean checkAndHandleVehicleDiffusion()
    {
        try
        {
            if (getGtu() == null || getGtu().isDestroyed())
            {
                return true;
            }

            Speed speed = getGtu().getSpeed();
            boolean isStopped = speed != null && speed.si <= 0.1;

            if (!isStopped)
            {
                this.stoppageStartTime = null;
                return false;
            }

            // Condition 1: Vehicle is currently executing an active lane change while stopped
            boolean activeLaneChange = this.laneChange != null && this.laneChange.isChangingLane();

            // Condition 2: Vehicle is standing VERY close to the emergency stopping position / route lane end
            boolean nearEmergencyStoppingPosition = false;
            boolean nearCriticalLaneEnd = false;
            InfrastructureContext infraContext = this.contextManager.getCategory("Infrastructure", InfrastructureContext.class);
            if (infraContext != null)
            {
                Length routeDistToLaneEnd = infraContext.getRouteDistanceToLaneEnd();
                Length emergencyStoppingDist = getGtu().getParameters().getParameter(MirovaParameters.emergencyStoppingDistance);
                if (routeDistToLaneEnd != null && emergencyStoppingDist != null)
                {
                    if (routeDistToLaneEnd.si < 2.0 * emergencyStoppingDist.si)
                    {
                        nearEmergencyStoppingPosition = true;
                    }
                    if (routeDistToLaneEnd.si < 100.0)
                    {
                        nearCriticalLaneEnd = true;
                    }
                }
            }

            // A stopped vehicle mid-lane-change is only deadlocked if it is near a critical lane end or emergency stop position.
            // Stopped vehicles in normal mainline traffic queues (away from lane ends) are not deadlocked.
            if ((activeLaneChange && (nearCriticalLaneEnd || nearEmergencyStoppingPosition)) || nearEmergencyStoppingPosition)
            {
                Duration currentTime = getGtu().getSimulator().getSimulatorTime();
                if (this.stoppageStartTime == null)
                {
                    this.stoppageStartTime = currentTime;
                }

                Duration stoppageDuration = currentTime.minus(this.stoppageStartTime);
                Duration maxDiffusionTime = getGtu().getParameters().getParameter(MirovaParameters.vehicleDiffusionTime);

                if (stoppageDuration.gt(maxDiffusionTime))
                {
                    double distM = (infraContext != null && infraContext.getRouteDistanceToLaneEnd() != null) ? infraContext.getRouteDistanceToLaneEnd().si : -1.0;
                    String laneIdStr = "UNKNOWN";
                    try {
                        if (getGtu().getReferencePosition() != null && getGtu().getReferencePosition().lane() != null) {
                            laneIdStr = getGtu().getReferencePosition().lane().getId();
                        }
                    } catch (Exception ex) {}

                    String reasonStr = activeLaneChange ? "BLOCKED_LANE_CHANGE_DEADLOCK" : "ROUTE_LANE_END_EMERGENCY_STOP";

                    System.out.printf(
                        "[DIFFUSION] GTU %s deadlocked for %.1fs (activeLaneChange=%b, distToLaneEnd=%.1fm, lane=%s). Removing vehicle from simulation.%n",
                        getGtu().getId(),
                        stoppageDuration.si,
                        activeLaneChange,
                        distM,
                        laneIdStr
                    );

                    VehicleDiffusionLogger.logDiffusion(
                        getGtu().getId(),
                        currentTime.si,
                        laneIdStr,
                        distM,
                        activeLaneChange,
                        reasonStr
                    );

                    getGtu().destroy();
                    return true;
                }
            }
            else
            {
                // Stopped in a normal traffic queue, not at route end/active lane change -> reset timer
                this.stoppageStartTime = null;
            }
        }
        catch (Exception e)
        {
            // Fail-safe
        }
        return false;
    }

    /**
     * Executes one full tactical decision cycle for the MIROVA vehicle.
     * <p>
     * This method represents the central update routine that governs the vehicle’s tactical behavior on a microscopic level.
     * The process follows a strict 4-layer architecture:
     * </p>
     * <ol>
     * <li><b>Perception & Context:</b> Updates world knowledge via {@link VehicleContextManager}.</li>
     * <li><b>Cognition:</b> Computes aggregated motivation (desire) from all {@link DesireIncentive}s.</li>
     * <li><b>Relaxation:</b> Applies temporal smoothing to desired headways to prevent abrupt maneuvers.</li>
     * <li><b>Decision & Action:</b> Evaluates running maneuvers, selects exclusive or parallel {@link ManeuverPattern}s, and
     * outputs a physical {@link SimpleOperationalPlan}. Defaults to standard car-following.</li>
     * </ol>
     * @return the {@link SimpleOperationalPlan} representing the vehicle’s tactical decision for the current time step
     * @throws ParameterException if a parameter lookup fails during desire or ability checks
     * @throws NullPointerException if required perception or context data are unavailable
     * @throws IllegalArgumentException if a consistency condition is violated
     * @throws NetworkException if the network structure cannot be queried
     * @throws GtuException if GTU state errors occur
     */
    public SimpleOperationalPlan update()
            throws ParameterException, NullPointerException, IllegalArgumentException, GtuException, NetworkException
    {
        // 1. Update perception and contextual information
        this.contextManager.advanceTick();
        updateTimeSinceLastLaneChange();
        this.updateContext();

        // 1b. Check for deadlock diffusion (VISSIM-style vehicle removal)
        if (checkAndHandleVehicleDiffusion())
        {
            Duration dt = this.getGtu().getParameters().getParameter(ParameterTypes.DT);
            return new SimpleOperationalPlan(Acceleration.ZERO, dt, LateralDirectionality.NONE);
        }

        // 2. Compute current LMRS-style net desire (aggregated from all knowledge chunks)
        updateLaneChangeDesire();

        // 3. Derive a single scalar desire magnitude for car-following adjustments
        this.absoluteDesire = this.laneChangeDesire.magnitude();

        // 4. Scale the deceleration thresholds with the current desire
        updateDecelerationThresholds();

        // 5. Reset operational plan for this time step
        this.operationalPlan = null;

        // 6. Determine operational plan using the hybrid three-step arbitration scheme.
        ArrayList<ManeuverPattern> relevantPatterns =
                PatternSelector.getAllRelevantPatterns(new ArrayList<>(this.maneuverPatterns));

        SimpleOperationalPlan arbitratedPlan = this.hybridArbitrator.arbitrate(relevantPatterns);

        if (arbitratedPlan != null)
        {
            this.operationalPlan = arbitratedPlan;
            this.lastActivePattern = this.hybridArbitrator.getLastActivePattern();
            this.currentActionState = this.hybridArbitrator.getLastActiveState();
        }
        else
        {
            // Fallback: pure car-following when no pattern is active or yields a valid plan.
            this.lastActivePattern = null;
            this.currentActionState = null;

            EgoContext egoContext = getContextManager().getCategory("Ego", EgoContext.class);
            Acceleration cfAcceleration = egoContext.getCurrentCarFollowingAcceleration();
            Duration dt = this.getGtu().getParameters().getParameter(ParameterTypes.DT);

            this.operationalPlan = new SimpleOperationalPlan(cfAcceleration, dt, LateralDirectionality.NONE);
        }

        // 7. Update turn indicator intent based on plan and desires
        if (this.operationalPlan.getIndicatorIntent().isLeft())
        {
            getGtu().setTurnIndicatorStatus(TurnIndicatorStatus.LEFT);
        }
        else if (this.operationalPlan.getIndicatorIntent().isRight())
        {
            getGtu().setTurnIndicatorStatus(TurnIndicatorStatus.RIGHT);
        }
        // else if (getLaneChangeDesire().magnitude() > getDMand())
        // {
        // // if strong desire but no explicit indicator intent, use desire direction for indicators
        // if (getLaneChangeDesire().dominantDirection() == LateralDirectionality.LEFT)
        // {
        // getGtu().setTurnIndicatorStatus(TurnIndicatorStatus.LEFT);
        // }
        // else if (getLaneChangeDesire().dominantDirection() == LateralDirectionality.RIGHT)
        // {
        // getGtu().setTurnIndicatorStatus(TurnIndicatorStatus.RIGHT);
        // }
        // }
        else
        {
            getGtu().setTurnIndicatorStatus(TurnIndicatorStatus.NONE);
        }

        return this.operationalPlan;
    }

    /**
     * Returns all {@link DesireIncentive}s currently assigned to this vehicle. These represent the declarative knowledge
     * influencing tactical reasoning.
     * @return list of all knowledge chunks
     */
    public List<DesireIncentive> getKnowledgeChunks()
    {
        return this.knowledgeChunks;
    }

    /**
     * Registers a new {@link DesireIncentive} to this vehicle. This method is typically called in the constructor of the
     * concrete vehicle class.
     * @param chunk the knowledge chunk to add
     */
    public void addKnowledgeChunk(final DesireIncentive chunk)
    {
        if (chunk != null && !this.knowledgeChunks.contains(chunk))
        {
            this.knowledgeChunks.add(chunk);
        }
    }

    /**
     * Registers a {@link ManeuverPattern} with this vehicle. Typically called when the planner is assembled.
     * @param pattern the maneuver pattern to add
     */
    public void addManeuverPattern(final ManeuverPattern pattern)
    {
        if (pattern != null && !this.maneuverPatterns.contains(pattern))
        {
            this.maneuverPatterns.add(pattern);
        }
    }

    /**
     * Returns all {@link ManeuverPattern}s registered with this vehicle.
     * @return list of maneuver patterns
     */
    public ArrayList<ManeuverPattern> getManeuverPatterns()
    {
        return new ArrayList<>(this.maneuverPatterns);
    }

    // ----------------------------------------------------------------------
    // LMRS Desire Integration
    // ----------------------------------------------------------------------

    /**
     * Computes and updates the total (mandatory + discretionary) desire vector for this vehicle based on all active
     * {@link DesireIncentive}s.
     * <p>
     * The result represents the LMRS-style aggregated motivation for lane changing, which can later be used for tactical
     * decisions (e.g., thresholding, maneuver selection).
     * </p>
     * @throws ParameterException if any chunk's desire computation fails
     * @throws NetworkException if the network structure cannot be queried
     * @throws GtuException if GTU state errors occur
     */
    protected void updateLaneChangeDesire() throws ParameterException, GtuException, NetworkException
    {
        this.mandatoryLaneChangeDesire = Desire.zero();
        this.discretionaryLaneChangeDesire = Desire.zero();

        // collect all desires from active chunks
        for (DesireIncentive chunk : this.getKnowledgeChunks())
        {
            if (chunk.isApplicable())
            {
                Desire d = chunk.computeDesire();
                if (d.isMandatory())
                {
                    this.mandatoryLaneChangeDesire = this.mandatoryLaneChangeDesire.add(d);
                }
                else
                {
                    this.discretionaryLaneChangeDesire = this.discretionaryLaneChangeDesire.add(d);
                }
            }
        }

        // combine mandatory + discretionary using LMRS weighting per direction
        double dSync = this.getDMand();
        double dCoop = this.getDFree();

        this.laneChangeDesire =
                Desire.combine(this.mandatoryLaneChangeDesire, this.discretionaryLaneChangeDesire, dSync, dCoop);
    }

    /**
     * Returns the current combined LMRS desire. * @return the combined lane change desire
     */
    public Desire getLaneChangeDesire()
    {
        return this.laneChangeDesire;
    }

    // ----------------------------------------------------------------------
    // Context Handling
    // ----------------------------------------------------------------------

    /** Updates all registered context categories once per simulation tick. */
    public void updateContext()
    {
        this.contextManager.updateFromPerception();
    }

    /**
     * Returns the central vehicle context manager. * @return the context manager
     */
    public VehicleContextManager getContextManager()
    {
        return this.contextManager;
    }

    /**
     * Generic accessor for a full context category. * @param <T> the type of the context category
     * @param clazz the class type of the context category
     * @return the requested context category, or null if not found
     */
    public <T extends ContextCategory> T getContext(final Class<T> clazz)
    {
        for (ContextCategory cat : this.contextManager.getAllCategories().values())
        {
            if (clazz.isInstance(cat))
            {
                return clazz.cast(cat);
            }
        }
        return null;
    }

    /**
     * Generic accessor for a specific value in a context category. * @param <T> the value type
     * @param categoryName the name of the category
     * @param key the key mapping to the value
     * @param clazz the class type of the value
     * @return the context value, or null if not found
     */
    public <T> T getContextValue(final String categoryName, final String key, final Class<T> clazz)
    {
        ContextCategory cat = this.contextManager.getCategory(categoryName, ContextCategory.class);
        return cat != null ? cat.getValue(key, clazz) : null;
    }

    /**
     * Returns the currently locked action state, or {@code null} if no lock is active.
     * @return the locked {@link ActionState}, or {@code null}
     */
    public ActionState getLockedActionState()
    {
        return this.lockedActionState;
    }

    /**
     * Commits the vehicle to a specific action state, bypassing utility arbitration.
     * @param state the ActionState to lock
     */
    public void commitToAction(final ActionState state)
    {
        this.lockedActionState = state;
    }

    /**
     * Releases the physical action lock, allowing utility arbitration to resume.
     */
    public void releaseActionLock()
    {
        this.lockedActionState = null;
    }

    /**
     * Gets the currently active action state. * @return the current action state
     */
    public ActionState getCurrentActionState()
    {
        return this.currentActionState;
    }

    /**
     * Sets the currently active action state. * @param currentActionState the action state to set
     */
    public void setCurrentActionState(final ActionState currentActionState)
    {
        this.currentActionState = currentActionState;
    }

    /**
     * Retrieves the lane change model. * @return the lane change instance
     */
    public LaneChange getLaneChange()
    {
        return this.laneChange;
    }

    private void updateDecelerationThresholds() throws ParameterException
    {
        final Parameters parameters = this.getGtu().getParameters();
        Acceleration maxEgo = parameters.getParameter(MirovaParameters.maxEgoDecelerationThreshold);
        Acceleration minEgo = parameters.getParameter(MirovaParameters.minEgoDecelerationThreshold);
        Acceleration maxFollower = parameters.getParameter(MirovaParameters.maxFollowerDecelerationThreshold);
        Acceleration minFollower = parameters.getParameter(MirovaParameters.minFollowerDecelerationThreshold);

        Double currentLaneChangeDesire =
                this.laneChangeDesire.magnitude() > getDFree() ? this.laneChangeDesire.magnitude() : getDFree(); // binary
                                                                                                                 // desire for
                                                                                                                 // simplicity,
                                                                                                                 // can be
                                                                                                                 // refined to
                                                                                                                 // use actual
                                                                                                                 // magnitude
        Double desireFactor = Math.min(currentLaneChangeDesire - getDFree(), 1.0);

        // interpolate linear based on desire (between DFREE and 1.0)
        Acceleration newEgo = Acceleration.instantiateSI(minEgo.si + (maxEgo.si - minEgo.si) * desireFactor);
        Acceleration newFollower =
                Acceleration.instantiateSI(minFollower.si + (maxFollower.si - minFollower.si) * desireFactor);

        parameters.setParameterResettable(MirovaParameters.egoDecelerationThreshold, newEgo);
        parameters.setParameterResettable(MirovaParameters.followerDecelerationThreshold, newFollower);
    }

    /**
     * Computes the longitudinal acceleration for the current time step, considering all tactical and environmental influences
     * provided via the vehicle’s {@link VehicleContextManager}.
     * <p>
     * The following control components are considered:
     * <ul>
     * <li><b>Leader-following behavior:</b> based on perceived headway, automatically applying Keane and Gao (2021)
     * relaxation.</li>
     * <li><b>Speed-limit adaptation:</b> handling of upcoming lower speed limits.</li>
     * <li><b>Transition effects:</b> optional curvature or bump-based deceleration.</li>
     * </ul>
     * The resulting acceleration is the minimum (most restrictive) value among all components.
     * </p>
     * <p>
     * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
     * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
     * </p>
     * @return final longitudinal acceleration [m/s²]
     * @throws ParameterException if parameter retrieval fails
     * @throws NetworkException if the network structure cannot be queried
     * @throws GtuException if GTU state errors occur
     */
    public Acceleration computeLongitudinalAcceleration() throws ParameterException, GtuException, NetworkException
    {
        // 1. Retrieve tightly coupled contexts and parameters
        EgoContext ego = this.getContext(EgoContext.class);
        InfrastructureContext infra = this.getContext(InfrastructureContext.class);
        NeighborsContext neighbors = this.getContext(NeighborsContext.class);
        Parameters parameters = this.getParameters();

        // List of candidate accelerations
        List<Acceleration> candidates = new ArrayList<>();

        // =========================================================================================
        // SCHRITT 3: ZEITLÜCKEN-HACK WURDE HIER GELÖSCHT!
        // parameters.setParameterResettable(ParameterTypes.T, this.getCurrentRelaxedHeadway());
        // Das Basis-Modell bleibt ab sofort unangetastet.
        // =========================================================================================

        // 2. Leader-following (incorporating automatic 2-parameter relaxation via our Utility)
        // We do not need that when the current LC has already entered the traget lane
        if (!getLaneChange().isChangingLane() || getLaneChange().getFraction() <= 0.5)
        {

            Iterable<HeadwayGtu> currentLeaders = neighbors.getLeaders(LateralDirectionality.NONE);
            double maxLeadersToConsider = getParameters().getParameter(MirovaParameters.CF_MAX_LEADERS);
            List<HeadwayGtu> limitedLeaders = new ArrayList<>();
            int leaderCount = 0;
            for (HeadwayGtu leader : currentLeaders)
            {
                if (leaderCount >= maxLeadersToConsider)
                {
                    break;
                }
                limitedLeaders.add(leader);
                leaderCount++;
            }
            currentLeaders = limitedLeaders;
            Acceleration aCf = MirovaCarFollowingUtil.followMultipleLeaders(this, currentLeaders);
            candidates.add(aCf);
        }

        // 3. Transition deceleration (e.g., curvature or bumps)
        Acceleration aTrans = SpeedLimitUtil.considerSpeedLimitTransitions(parameters, ego.getEgoSpeed(), getPerception()
                .getPerceptionCategory(InfrastructurePerception.class).getSpeedLimitProspect(RelativeLane.CURRENT),
                this.getCarFollowingModel());

        if (aTrans != null && aTrans.lt(Acceleration.POSITIVE_INFINITY))
        {
            candidates.add(aTrans);
        }

        // 4. Upcoming lower speed limit ahead
        SpeedLimitInfo nextLimit = infra.getNextSpeedLimit();
        Speed currentLegalLimit = infra.getLegalSpeedLimit();

        // Null-Safety: Prüfe, ob sowohl das nächste als auch das aktuelle SpeedLimit bekannt sind
        if (nextLimit != null && currentLegalLimit != null)
        {
            Speed nextLegal = SpeedLimitUtil.getLegalSpeedLimit(nextLimit);

            if (nextLegal.lt(currentLegalLimit))
            {
                // Nutze den OTS-Parameter anstelle der harten 200 Meter
                Length distanceToLimit = parameters.getParameter(ParameterTypes.LOOKAHEAD);
                Acceleration aLimit = MirovaCarFollowingUtil.approachTargetSpeed(this, distanceToLimit, nextLegal);

                if (aLimit != null)
                {
                    candidates.add(aLimit);
                }
            }
        }

        // 5. Compute most restrictive acceleration safely
        Acceleration fallbackAcc = (getLaneChange().isChangingLane() && getLaneChange().getFraction() > 0.5)
                ? MirovaCarFollowingUtil.freeAcceleration(this)
                : MirovaCarFollowingUtil.followSingleLeader(this, neighbors.getLeader(LateralDirectionality.NONE));
        return candidates.stream().filter(java.util.Objects::nonNull).min(Acceleration::compareTo)
                .orElse(fallbackAcc);
    }

    /**
     * Gets the current absolute lateral desire. * @return the magnitude of the lateral desire
     */
    public Double getDesire()
    {
        return this.absoluteDesire;
    }

    /**
     * Sets the absolute desire and its relaxation time. * @param desire the desire magnitude
     * @param desireRelaxationTime the relaxation duration
     */
    public void setDesire(final Double desire, final Duration desireRelaxationTime)
    {
        this.absoluteDesire = desire;
        this.desireRelaxationTime = desireRelaxationTime;
    }

    /**
     * Gets the mandatory lane change desire vector. * @return the mandatory lane change desire
     */
    public Desire getMandatoryLaneChangeDesire()
    {
        return this.mandatoryLaneChangeDesire;
    }

    /**
     * Gets the discretionary lane change desire vector. * @return the discretionary lane change desire
     */
    public Desire getDiscretionaryLaneChangeDesire()
    {
        return this.discretionaryLaneChangeDesire;
    }

    /**
     * Returns the free driving distance constant. * @return the value of DFREE
     * @throws ParameterException if parameter resolution fails
     */
    public double getDFree() throws ParameterException
    {
        return getParameters().getParameter(MirovaParameters.DFREE);
    }

    /**
     * Returns the mandatory driving distance constant. * @return the value of DMAND
     * @throws ParameterException if parameter resolution fails
     */
    public double getDMand() throws ParameterException
    {
        return getParameters().getParameter(MirovaParameters.DMAND);
    }

    /**
     * Returns the speed difference threshold (vGain) used in LMRS. * @return the value of vGain
     * @throws ParameterException if parameter resolution fails
     */
    public Speed getVGain() throws ParameterException
    {
        return getParameters().getParameter(MirovaParameters.vGain);
    }

    /**
     * Returns the critical speed threshold (vCrit) used in LMRS. * @return the value of vCrit
     * @throws ParameterException if parameter resolution fails
     */
    public Speed getVCrit() throws ParameterException
    {
        return getParameters().getParameter(MirovaParameters.vCrit);
    }

    /**
     * Returns the sensitivity parameter for social speed dynamics. * @return the value of socioSpeedSensitivity
     * @throws ParameterException if parameter resolution fails
     */
    public Double getSocioSpeedSensitivity() throws ParameterException
    {
        return getParameters().getParameter(MirovaParameters.socioSpeedSensitivity);
    }

    /**
     * Retrieves the simple operational plan calculated for the current tick. * @return the operational plan
     */
    public SimpleOperationalPlan getOperationalPlan()
    {
        return this.operationalPlan;
    }

    /**
     * Retrieves the parameters attached to this GTU. * @return the parameters object
     */
    public Parameters getParameters()
    {
        return this.params;
    }

    /**
     * Sets the socio speed pressure experienced by the GTU. * @param newValue the new socio speed pressure
     */
    public void setSocioSpeedPressure(final Double newValue)
    {
        this.socioSpeedPressure = newValue;
    }

    /**
     * Gets the socio speed pressure currently experienced by the GTU. * @return the current socio speed pressure
     */
    public Double getSocioSpeedPressure()
    {
        return this.socioSpeedPressure;
    }

    /**
     * Retrieves the time duration since the last lane change started. * @return the duration since the last lane change
     */
    public Duration getTimeSinceLastLaneChange()
    {
        return this.timeSinceLastLaneChange;
    }

    /**
     * Updates the tracker for the time since the last lane change. * @throws ParameterException if accessing the time step (DT)
     * parameter fails
     */
    public void updateTimeSinceLastLaneChange() throws ParameterException
    {
        if (this.laneChange.isChangingLane())
        {
            this.timeSinceLastLaneChange = Duration.ZERO;
        }
        else
        {
            this.timeSinceLastLaneChange = this.timeSinceLastLaneChange.plus(getParameters().getParameter(ParameterTypes.DT));
        }
    }
}
