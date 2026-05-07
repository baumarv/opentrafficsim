package org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ManeuverPatterns.parallel;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Map;

import org.djunits.unit.AccelerationUnit;
import org.djunits.value.vdouble.scalar.Acceleration;
import org.djunits.value.vdouble.scalar.Duration;
import org.djunits.value.vdouble.scalar.Length;
import org.djunits.value.vdouble.scalar.Speed;
import org.opentrafficsim.base.parameters.ParameterException;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.core.gtu.GtuException;
import org.opentrafficsim.core.gtu.plan.operational.OperationalPlanException;
import org.opentrafficsim.core.network.LateralDirectionality;
import org.opentrafficsim.core.network.NetworkException;
import org.opentrafficsim.road.gtu.lane.perception.RelativeLane;
import org.opentrafficsim.road.gtu.lane.perception.headway.HeadwayGtu;
import org.opentrafficsim.road.gtu.lane.plan.operational.SimpleOperationalPlan;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.MirovaTacticalPlanner;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ActionState;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ManeuverPattern;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ManeuverPatterns.exclusive.SimpleLaneChangePattern.PerformLaneChangeState;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.context.EgoContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.context.InfrastructureContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.context.InfrastructureContext.LaneDropInfo;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.context.MacroTrafficContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.context.NeighborsContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.following.MirovaCarFollowingUtil;

/**
 * Opens a gap for a merging neighbor that has indicated its intention via a turn indicator.
 * <p>
 * As a <b>Parallel Maneuver</b> in Layer 4 of the MiRoVA architecture, this pattern continuously scans adjacent lanes for
 * vehicles whose indicator points toward the ego lane within a cooperation zone (defined by lane-drop proximity or adjacent
 * congestion). When a candidate is identified, cooperative braking is applied to create a safe merging gap. An evasive lane
 * change to the opposite side may be triggered if the adjacent lane permits.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 *
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class GapOpenerPattern extends ManeuverPattern implements Serializable
{
    /** Serial version UID. */
    private static final long serialVersionUID = 20260507L;

    /** The ID of the vehicle we are actively cooperating with. */
    protected String activeMergeCandidateId = null;

    /** The current headway object for the active merge candidate. */
    protected HeadwayGtu activeMergeCandidate = null;

    /** The lateral direction from which the candidate is merging. */
    protected LateralDirectionality directionOfMergeCandidate = null;

    /** Distance threshold to consider cooperation near a lane drop in slow traffic. */
    private static final Length DISTANCE_THRESHOLD_MERGE_COOPERATION = Length.instantiateSI(250.0);

    /** Time-to-lane-end threshold for cooperation activation at free-flow speeds. */
    private static final Duration TIME_THRESHOLD_MERGE_COOPERATION = Duration.instantiateSI(30.0);

    /** Lanes currently identified as requiring gap-opening cooperation. */
    protected ArrayList<LateralDirectionality> listLanesWithCooperationNeeds = new ArrayList<>();

    /** Cache for anticipated lane drop info per lateral direction. */
    protected Map<LateralDirectionality, LaneDropInfo> anticipatedLaneDropMap =
            new java.util.EnumMap<>(LateralDirectionality.class);

    /**
     * Constructs a new GapOpenerPattern.
     *
     * @param vehicle the tactical planner executing this pattern
     */
    public GapOpenerPattern(final MirovaTacticalPlanner vehicle)
    {
        super(PatternType.PARALLEL, vehicle);
        this.initialActionState = () -> new OpenGapState(this);
        this.requiredContextKeys.add("Ego");
        this.requiredContextKeys.add("Neighbors");
        this.requiredContextKeys.add("Infrastructure");
        this.requiredContextKeys.add("MacroTraffic");
    }

    /**
     * Returns {@code true} unconditionally; ability is implicitly guaranteed when context holds.
     *
     * @return {@code true}
     */
    @Override
    public boolean checkAbility()
    {
        return true;
    }

    /**
     * Checks if a merge candidate with an active turn indicator is present within a cooperation zone.
     * <p>
     * A cooperation zone is triggered by lane-drop proximity (time-based at free-flow speeds, distance-based in congestion) or
     * by adjacent congestion. If a valid candidate is found, its ID and direction are locked for use in {@link OpenGapState}.
     * </p>
     *
     * @return {@code true} if a cooperation need exists and a candidate with indicator has been identified
     * @throws ParameterException if parameter retrieval fails
     */
    @Override
    public boolean checkContext() throws ParameterException
    {
        this.listLanesWithCooperationNeeds.clear();
        this.anticipatedLaneDropMap.clear();
        this.activeMergeCandidateId = null;
        this.directionOfMergeCandidate = null;
        this.activeMergeCandidate = null;

        InfrastructureContext infra =
                this.vehicle.getContextManager().getCategory("Infrastructure", InfrastructureContext.class);
        MacroTrafficContext macro = this.vehicle.getContextManager().getCategory("MacroTraffic", MacroTrafficContext.class);
        EgoContext ego = this.vehicle.getContextManager().getCategory("Ego", EgoContext.class);

        Speed leftLaneSpeed = Speed.POSITIVE_INFINITY;
        Speed rightLaneSpeed = Speed.POSITIVE_INFINITY;
        try
        {
            leftLaneSpeed = macro.getAverageSpeed(RelativeLane.LEFT);
            rightLaneSpeed = macro.getAverageSpeed(RelativeLane.RIGHT);
        }
        catch (OperationalPlanException | ParameterException exception)
        {
            // context missing; keep positive infinity as default
        }

        Speed egoSpeed = ego.getEgoSpeed();
        Speed vCong = this.vehicle.getParameters().getParameter(ParameterTypes.VCONG);

        Length distanceToEndRight = infra.getDistanceToLaneEnd(RelativeLane.RIGHT);
        if (distanceToEndRight.eq(Length.POSITIVE_INFINITY))
        {
            LaneDropInfo dropInfoRight = infra.getAnticipatedLaneDropInfo(LateralDirectionality.RIGHT);
            if (dropInfoRight != null)
            {
                distanceToEndRight = dropInfoRight.getDistance();
                this.anticipatedLaneDropMap.put(LateralDirectionality.RIGHT, dropInfoRight);
            }
        }

        Length distanceToEndLeft = infra.getDistanceToLaneEnd(RelativeLane.LEFT);
        if (distanceToEndLeft.eq(Length.POSITIVE_INFINITY))
        {
            LaneDropInfo dropInfoLeft = infra.getAnticipatedLaneDropInfo(LateralDirectionality.LEFT);
            if (dropInfoLeft != null)
            {
                distanceToEndLeft = dropInfoLeft.getDistance();
                this.anticipatedLaneDropMap.put(LateralDirectionality.LEFT, dropInfoLeft);
            }
        }

        if (egoSpeed.si > 15)
        {
            if (!distanceToEndRight.eq(Length.POSITIVE_INFINITY))
            {
                Duration timeToEndRight = Duration.instantiateSI(distanceToEndRight.si / egoSpeed.si);
                if (timeToEndRight.lt(TIME_THRESHOLD_MERGE_COOPERATION))
                {
                    this.listLanesWithCooperationNeeds.add(LateralDirectionality.RIGHT);
                }
                else if (rightLaneSpeed.lt(vCong) && rightLaneSpeed.si < egoSpeed.si + 3.0)
                {
                    this.listLanesWithCooperationNeeds.add(LateralDirectionality.RIGHT);
                }
            }
            else if (rightLaneSpeed.lt(vCong) && rightLaneSpeed.si < egoSpeed.si + 3.0)
            {
                this.listLanesWithCooperationNeeds.add(LateralDirectionality.RIGHT);
            }

            if (!distanceToEndLeft.eq(Length.POSITIVE_INFINITY))
            {
                Duration timeToEndLeft = Duration.instantiateSI(distanceToEndLeft.si / egoSpeed.si);
                if (timeToEndLeft.lt(TIME_THRESHOLD_MERGE_COOPERATION))
                {
                    this.listLanesWithCooperationNeeds.add(LateralDirectionality.LEFT);
                }
                else if (leftLaneSpeed.lt(vCong) && leftLaneSpeed.si < egoSpeed.si + 3.0)
                {
                    this.listLanesWithCooperationNeeds.add(LateralDirectionality.LEFT);
                }
            }
            else if (leftLaneSpeed.lt(vCong) && leftLaneSpeed.si < egoSpeed.si + 3.0)
            {
                this.listLanesWithCooperationNeeds.add(LateralDirectionality.LEFT);
            }
        }
        else
        {
            if (!distanceToEndRight.eq(Length.POSITIVE_INFINITY)
                    && distanceToEndRight.lt(DISTANCE_THRESHOLD_MERGE_COOPERATION))
            {
                this.listLanesWithCooperationNeeds.add(LateralDirectionality.RIGHT);
            }
            if (!distanceToEndLeft.eq(Length.POSITIVE_INFINITY)
                    && distanceToEndLeft.lt(DISTANCE_THRESHOLD_MERGE_COOPERATION))
            {
                this.listLanesWithCooperationNeeds.add(LateralDirectionality.LEFT);
            }
        }

        if (this.listLanesWithCooperationNeeds.isEmpty())
        {
            return false;
        }

        NeighborsContext neighbors = this.vehicle.getContextManager().getCategory("Neighbors", NeighborsContext.class);
        return findNewCandidate(neighbors);
    }

    /**
     * Retrieves the current {@link HeadwayGtu} for the locked candidate ID from the neighbors context.
     *
     * @return the updated HeadwayGtu, or {@code null} if the candidate has merged or is no longer visible
     */
    public HeadwayGtu getActiveMergeCandidate()
    {
        if (this.activeMergeCandidateId == null || this.directionOfMergeCandidate == null)
        {
            return null;
        }
        NeighborsContext neighbors = this.vehicle.getContextManager().getCategory("Neighbors", NeighborsContext.class);
        Iterable<HeadwayGtu> leaders = neighbors.getLeaders(this.directionOfMergeCandidate);
        if (leaders != null)
        {
            for (HeadwayGtu gtu : leaders)
            {
                if (gtu.getId().equals(this.activeMergeCandidateId))
                {
                    return gtu;
                }
            }
        }
        return null;
    }

    /**
     * Scans lanes in {@code listLanesWithCooperationNeeds} for a vehicle with its indicator pointing toward the ego lane.
     *
     * @param neighbors the neighbors context containing adjacent perception data
     * @return {@code true} if a candidate was found and locked; {@code false} otherwise
     * @throws ParameterException if parameter retrieval fails
     */
    protected boolean findNewCandidate(final NeighborsContext neighbors) throws ParameterException
    {
        for (LateralDirectionality dir : this.listLanesWithCooperationNeeds)
        {
            Iterable<HeadwayGtu> adjacentLeaders = neighbors.getLeaders(dir);
            if (adjacentLeaders != null)
            {
                for (HeadwayGtu candidate : adjacentLeaders)
                {
                    boolean indicatesTowardsUs = (dir.isRight() && candidate.isLeftTurnIndicatorOn())
                            || (dir.isLeft() && candidate.isRightTurnIndicatorOn());
                    if (indicatesTowardsUs
                            && candidate.getDistance().gt(this.vehicle.getParameters().getParameter(ParameterTypes.S0)))
                    {
                        this.activeMergeCandidateId = candidate.getId();
                        this.directionOfMergeCandidate = dir;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /*
     * =========================================================================================
     * STATE: OpenGapState
     * =========================================================================================
     */

    /**
     * Cooperatively brakes to open a gap for the identified merge candidate.
     * <p>
     * Applies a two-leader car-following approach: the ego follows the merge candidate as a virtual leader on the adjacent lane.
     * The cooperative acceleration is capped by {@link MirovaParameters#cooperativeDecelerationThreshold} to prevent unsafe
     * emergency braking on the mainline. An evasive lane change to the opposite side is triggered when conditions permit.
     * </p>
     */
    public static class OpenGapState extends ActionState
    {
        /** The parent pattern. */
        private final GapOpenerPattern maneuverPattern;

        /**
         * Constructor.
         *
         * @param pattern the parent maneuver pattern
         */
        public OpenGapState(final GapOpenerPattern pattern)
        {
            super(pattern);
            this.maneuverPattern = pattern;
        }

        @Override
        public SimpleOperationalPlan next() throws OperationalPlanException, ParameterException, GtuException, NetworkException
        {
            this.maneuverPattern.activeMergeCandidate = this.maneuverPattern.getActiveMergeCandidate();
            HeadwayGtu candidate = this.maneuverPattern.activeMergeCandidate;

            if (candidate == null || (!candidate.isLeftTurnIndicatorOn() && !candidate.isRightTurnIndicatorOn()
                    && candidate.getDistance().gt(this.vehicle.getParameters().getParameter(ParameterTypes.S0))))
            {
                return finishManeuver();
            }

            EgoContext ego = this.vehicle.getContextManager().getCategory("Ego", EgoContext.class);
            NeighborsContext neighbors = this.vehicle.getContextManager().getCategory("Neighbors", NeighborsContext.class);

            if (ego.getEgoSpeed().gt(this.vehicle.getParameters().getParameter(ParameterTypes.VCONG))
                    && this.vehicle.getParameters().getParameter(MirovaParameters.cooperativeLaneChangesEnabled))
            {
                LateralDirectionality oppositeDir = this.maneuverPattern.directionOfMergeCandidate.isLeft()
                        ? LateralDirectionality.RIGHT : LateralDirectionality.LEFT;
                if (neighbors.checkIfLaneChangeIsPossible(oppositeDir))
                {
                    return transitionTo(new PerformLaneChangeState(this.maneuverPattern, oppositeDir));
                }
            }

            return null;
        }

        @Override
        public SimpleOperationalPlan executeControl()
                throws ParameterException, OperationalPlanException, GtuException, NetworkException
        {
            this.maneuverPattern.setRunning(true);
            this.maneuverPattern.setCurrentActionState(this);

            EgoContext ego = this.vehicle.getContextManager().getCategory("Ego", EgoContext.class);
            Acceleration aDirectLeader = ego.getCurrentCarFollowingAcceleration();
            Acceleration aCooperation = new Acceleration(Double.POSITIVE_INFINITY, AccelerationUnit.METER_PER_SECOND_2);

            HeadwayGtu candidate = this.maneuverPattern.activeMergeCandidate;
            if (candidate != null && candidate.getDistance().si > 0)
            {
                aCooperation = MirovaCarFollowingUtil.followSingleLeader(vehicle, candidate);
                Acceleration decelThreshold =
                        this.vehicle.getParameters().getParameter(MirovaParameters.cooperativeDecelerationThreshold);
                aCooperation = aCooperation.gt(decelThreshold) ? aCooperation : decelThreshold;
            }

            Acceleration finalAcceleration = Acceleration.min(aCooperation, aDirectLeader);
            return new SimpleOperationalPlan(finalAcceleration,
                    this.vehicle.getParameters().getParameter(ParameterTypes.DT));
        }

        @Override
        public SimpleOperationalPlan abort() throws ParameterException, GtuException, NetworkException
        {
            this.maneuverPattern.setRunning(false);
            return null;
        }

        @Override
        public double getUtility()
        {
            return 0.5;
        }

        @Override
        public String toString()
        {
            return "OpenGapState";
        }
    }
}
