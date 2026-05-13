package org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ManeuverPatterns;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Map;

import org.djunits.unit.AccelerationUnit;
import org.djunits.unit.SpeedUnit;
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
import org.opentrafficsim.road.gtu.lane.perception.headway.HeadwayGtu;
import org.opentrafficsim.road.gtu.lane.plan.operational.SimpleOperationalPlan;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.MirovaTacticalPlanner;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ActionState;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ManeuverPattern;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ManeuverPatterns.SimpleLaneChangePattern.PerformLaneChangeState;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.context.EgoContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.context.InfrastructureContext.LaneDropInfo;
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
     * @return {@code true}
     */
    @Override
    public boolean checkAbility()
    {
        return true;
    }

    /**
     * Checks if a merge candidate with an active turn indicator is present within the
     * {@link MirovaParameters#considerGapOpeningLookaheadDistance}. If a valid candidate is found, its ID and direction are
     * locked for use in {@link OpenGapState}.
     * @return {@code true} if a cooperation need exists and a candidate with indicator has been identified
     * @throws ParameterException if parameter retrieval fails
     */
    @Override
    public boolean checkContext() throws ParameterException
    {
        this.activeMergeCandidateId = null;
        this.directionOfMergeCandidate = null;
        this.activeMergeCandidate = null;

        return findNewCandidate();
    }

    /**
     * Retrieves the current {@link HeadwayGtu} for the locked candidate ID from the neighbors context.
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
     * Scans adjacent lanes for vehicles with active turn indicators pointing toward the ego lane within the
     * {@link MirovaParameters#considerGapOpeningLookaheadDistance}. Cooperation with valid candidates must also meet
     * {@link MirovaParameters#cooperativeDecelerationThreshold} requirements to ensure safety.
     * @return {@code true} if a candidate was found and locked; {@code false} otherwise
     * @throws ParameterException if parameter retrieval fails
     */
    protected boolean findNewCandidate() throws ParameterException
    {
        NeighborsContext neighbors = this.vehicle.getContextManager().getCategory("Neighbors", NeighborsContext.class);
        // list with LateralDirectionality.Left and LateralDirectionality.Right
        ArrayList<LateralDirectionality> lanesToCheck = new ArrayList<>();
        lanesToCheck.add(LateralDirectionality.RIGHT);
        lanesToCheck.add(LateralDirectionality.LEFT);

        for (LateralDirectionality dir : lanesToCheck)
        {
            Iterable<HeadwayGtu> adjacentLeaders = neighbors.getLeaders(dir);

            if (adjacentLeaders != null)
            {
                for (HeadwayGtu candidate : adjacentLeaders)
                {
                    Length distanceCandidate = candidate.getDistance();
                    if (distanceCandidate.gt(
                            this.vehicle.getParameters().getParameter(MirovaParameters.considerGapOpeningLookaheadDistance)))
                    {
                        break;
                    }
                    boolean indicatesTowardsUs = (dir.isRight() && candidate.isLeftTurnIndicatorOn())
                            || (dir.isLeft() && candidate.isRightTurnIndicatorOn());
                    if (indicatesTowardsUs)
                    {
                        EgoContext ego = this.vehicle.getContextManager().getCategory("Ego", EgoContext.class);
                        // we are behin the candidate and check deceleration
                        if (distanceCandidate.gt(this.vehicle.getParameters().getParameter(ParameterTypes.S0)))
                        {
                            Acceleration cooperationAcceleration = neighbors.getGtuDeceleration(candidate);
                            Acceleration decelThreshold = this.vehicle.getParameters()
                                    .getParameter(MirovaParameters.cooperativeDecelerationThreshold);
                            if (cooperationAcceleration.ge(decelThreshold))
                            {
                                this.activeMergeCandidateId = candidate.getId();
                                this.directionOfMergeCandidate = dir;
                                return true;
                            }

                        }
                        // we are parallel to the candidate, but since we have not much space to drive forward, we can also
                        // consider cooperation
                        else if (ego.getEgoSpeed().lt(new Speed(5.0, SpeedUnit.KM_PER_HOUR))
                                && neighbors.getFrontGapDistance(LateralDirectionality.NONE).si < 15.0)
                        {
                            this.activeMergeCandidateId = candidate.getId();
                            this.directionOfMergeCandidate = dir;
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /*
     * ========================================================================================= STATE: OpenGapState
     * =========================================================================================
     */

    /**
     * Cooperatively brakes to open a gap for the identified merge candidate.
     * <p>
     * Applies a two-leader car-following approach: the ego follows the merge candidate as a virtual leader on the adjacent
     * lane. The cooperative acceleration is capped by {@link MirovaParameters#cooperativeDecelerationThreshold} to prevent
     * unsafe emergency braking on the mainline. An evasive lane change to the opposite side is triggered when conditions
     * permit.
     * </p>
     */
    public static class OpenGapState extends ActionState
    {
        /** The parent pattern. */
        private final GapOpenerPattern maneuverPattern;

        /**
         * Constructor.
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

            EgoContext ego = this.vehicle.getContextManager().getCategory("Ego", EgoContext.class);
            NeighborsContext neighbors = this.vehicle.getContextManager().getCategory("Neighbors", NeighborsContext.class);

            if (ego.getEgoSpeed().gt(this.vehicle.getParameters().getParameter(ParameterTypes.VCONG))
                    && this.vehicle.getParameters().getParameter(MirovaParameters.cooperativeLaneChangesEnabled))
            {
                LateralDirectionality oppositeDir = this.maneuverPattern.directionOfMergeCandidate.isLeft()
                        ? LateralDirectionality.RIGHT : LateralDirectionality.LEFT;
                if (neighbors.checkIfLaneChangeIsPossible(oppositeDir))
                {
                    return transitionTo(new PerformLaneChangeState(this.maneuverPattern, oppositeDir, true));
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
            if (candidate != null)
            {
                Acceleration decelThreshold =
                        this.vehicle.getParameters().getParameter(MirovaParameters.cooperativeDecelerationThreshold);
                if (candidate.getDistance().si > 0)
                {
                    aCooperation = MirovaCarFollowingUtil.followSingleLeader(vehicle, candidate);

                    aCooperation = aCooperation.gt(decelThreshold) ? aCooperation : decelThreshold;
                }
                else
                {
                    aCooperation =
                            this.vehicle.getParameters().getParameter(MirovaParameters.preemptiveCooperativeDeceleration);
                }
            }

            Acceleration finalAcceleration = Acceleration.min(aCooperation, aDirectLeader);
            return new SimpleOperationalPlan(finalAcceleration, this.vehicle.getParameters().getParameter(ParameterTypes.DT));
        }

        @Override
        public SimpleOperationalPlan abort() throws ParameterException, GtuException, NetworkException
        {
            this.maneuverPattern.activeMergeCandidate = this.maneuverPattern.getActiveMergeCandidate();
            HeadwayGtu candidate = this.maneuverPattern.activeMergeCandidate;
            EgoContext ego = this.vehicle.getContextManager().getCategory("Ego", EgoContext.class);
            if (candidate == null || ((!candidate.isLeftTurnIndicatorOn() && !candidate.isRightTurnIndicatorOn())))

            {
                return finishManeuver();
            }

            if (candidate.getDistance().lt(this.vehicle.getParameters().getParameter(ParameterTypes.S0)))
            {
                NeighborsContext neighbors = this.vehicle.getContextManager().getCategory("Neighbors", NeighborsContext.class);
                if (ego.getEgoSpeed().ge(new Speed(5.0, SpeedUnit.KM_PER_HOUR))
                        || neighbors.getFrontGapDistance(LateralDirectionality.NONE).si >= 15.0)
                {
                    // Parallel cooperation is aborted once we have enough space to drive forward
                    return finishManeuver();
                }

            }
            this.maneuverPattern.setRunning(false);
            return null;
        }

        @Override
        public double getUtility()
        {
            try
            {
                return this.vehicle.getParameters().getParameter(MirovaParameters.DFREE);
            }
            catch (ParameterException e)
            {
                return 0.0;
            }

        }

        @Override
        public String toString()
        {
            return "OpenGapState[candidate=" + this.maneuverPattern.activeMergeCandidateId + ", direction="
                    + this.maneuverPattern.directionOfMergeCandidate + "]";
        }
    }
}
