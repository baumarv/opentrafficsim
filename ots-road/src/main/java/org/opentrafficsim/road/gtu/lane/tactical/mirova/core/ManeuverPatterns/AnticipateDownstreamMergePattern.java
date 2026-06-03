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
import org.opentrafficsim.road.gtu.lane.perception.RelativeLane;
import org.opentrafficsim.road.gtu.lane.plan.operational.SimpleOperationalPlan;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.MirovaTacticalPlanner;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ActionState;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ManeuverPattern;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ManeuverPatterns.SimpleLaneChangePattern.PerformLaneChangeState;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.context.EgoContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.context.InfrastructureContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.context.InfrastructureContext.LaneDropInfo;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.context.InfrastructureContext.ScanDirection;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.context.MacroTrafficContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.context.NeighborsContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.following.MirovaCarFollowingUtil;
import org.opentrafficsim.road.network.lane.Lane;

/**
 * Anticipates a downstream lane drop and proactively adapts ego speed to avoid abrupt deceleration.
 * <p>
 * This pattern activates when a lane-end on an adjacent lane is detected within the cooperation horizon: 30 s time-to-end at
 * free-flow speeds, or 250 m distance at slow speeds. It samples the average speed on the merging target lane far downstream
 * and derives a smooth approach acceleration. A preemptive evasive lane change to the opposite side may be triggered when above
 * congestion speed and a free gap is available.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class AnticipateDownstreamMergePattern extends ManeuverPattern implements Serializable
{
    /** Serial version UID. */
    private static final long serialVersionUID = 20260507L;

    /** Distance threshold for cooperation in slow-moving traffic. */
    private static final Length DISTANCE_THRESHOLD_MERGE_COOPERATION = Length.instantiateSI(250.0);

    /** Time-to-lane-end threshold for cooperation activation at free-flow speeds. */
    private static final Duration TIME_THRESHOLD_MERGE_COOPERATION = Duration.instantiateSI(30.0);

    /** Lanes with an approaching lane drop within the cooperation horizon. */
    protected ArrayList<LateralDirectionality> listLanesWithCooperationNeeds = new ArrayList<>();

    /** Anticipated lane drop info per lateral direction, populated by {@link #checkContext()}. */
    protected Map<LateralDirectionality, LaneDropInfo> anticipatedLaneDropMap =
            new java.util.EnumMap<>(LateralDirectionality.class);

    /**
     * Constructs a new AnticipateDownstreamMergePattern.
     * @param vehicle the tactical planner executing this pattern
     */
    public AnticipateDownstreamMergePattern(final MirovaTacticalPlanner vehicle)
    {
        super(PatternType.PARALLEL, vehicle);
        // Context-aware factory: FarAnticipationState calls setRunning(false) each tick so this lambda
        // is re-invoked every tick for the far phase. At that point listLanesWithCooperationNeeds is
        // already populated by checkContext(), so we can decide which state to create.
        this.initialActionState = () ->
        {
            if (!this.listLanesWithCooperationNeeds.isEmpty())
            {
                LateralDirectionality dir = this.listLanesWithCooperationNeeds.get(0);
                RelativeLane rel = dir.isLeft() ? RelativeLane.LEFT : RelativeLane.RIGHT;
                InfrastructureContext infra =
                        this.vehicle.getContextManager().getCategory("Infrastructure", InfrastructureContext.class);
                if (!infra.getDistanceToLaneEnd(rel).eq(Length.POSITIVE_INFINITY))
                {
                    return new NearAnticipationState(this);
                }
            }
            return new FarAnticipationState(this);
        };
        this.requiredContextKeys.add("Ego");
        this.requiredContextKeys.add("Infrastructure");
        this.requiredContextKeys.add("MacroTraffic");
        this.requiredContextKeys.add("Neighbors");
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
     * Detects adjacent lane-end events within the cooperation horizon.
     * <p>
     * Checks both direct lane-end distances and anticipated lane drop info from the {@link InfrastructureContext}. Returns
     * {@code true} only when a lane drop is imminent — not for general adjacent congestion without a lane drop (that case is
     * handled by {@link AnticipateAdjacentCongestionPattern}).
     * </p>
     * @return {@code true} if a lane drop on an adjacent lane is within the cooperation horizon
     * @throws ParameterException if parameter retrieval fails
     */
    @Override
    public boolean checkContext() throws ParameterException
    {
        this.listLanesWithCooperationNeeds.clear();
        this.anticipatedLaneDropMap.clear();

        InfrastructureContext infra =
                this.vehicle.getContextManager().getCategory("Infrastructure", InfrastructureContext.class);
        EgoContext ego = this.vehicle.getContextManager().getCategory("Ego", EgoContext.class);
        Speed egoSpeed = ego.getEgoSpeed();

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
                    return true;
                }
            }
            if (!distanceToEndLeft.eq(Length.POSITIVE_INFINITY))
            {
                Duration timeToEndLeft = Duration.instantiateSI(distanceToEndLeft.si / egoSpeed.si);
                if (timeToEndLeft.lt(TIME_THRESHOLD_MERGE_COOPERATION))
                {
                    this.listLanesWithCooperationNeeds.add(LateralDirectionality.LEFT);
                    return true;
                }
            }
        }
        else
        {
            if (!distanceToEndRight.eq(Length.POSITIVE_INFINITY) && distanceToEndRight.lt(DISTANCE_THRESHOLD_MERGE_COOPERATION))
            {
                this.listLanesWithCooperationNeeds.add(LateralDirectionality.RIGHT);
                return true;
            }
            if (!distanceToEndLeft.eq(Length.POSITIVE_INFINITY) && distanceToEndLeft.lt(DISTANCE_THRESHOLD_MERGE_COOPERATION))
            {
                this.listLanesWithCooperationNeeds.add(LateralDirectionality.LEFT);
                return true;
            }
        }

        return false;
    }

    /*
     * ========================================================================================= STATE: FarAnticipationState
     * Active while the ramp is not yet a directly adjacent lane (getDistanceToLaneEnd == INFINITY). Samples downstream
     * merge-point speed via LaneDropInfo and applies a smooth approach acceleration. Transitions to NearAnticipationState once
     * the ramp becomes adjacent. =========================================================================================
     */

    /**
     * Far-range anticipation state: active when the downstream ramp is not yet a directly adjacent lane.
     * <p>
     * Samples average speed near the merge bottleneck via {@link LaneDropInfo} and derives a smooth approach acceleration.
     * Triggers an evasive cooperative lane change away from the merge side when ego is above congestion speed and an opposite
     * gap is available. Transitions to {@link NearAnticipationState} as soon as the ramp becomes the directly adjacent lane.
     * </p>
     */
    public static class FarAnticipationState extends ActionState
    {
        /** The parent pattern, typed for direct field access. */
        private final AnticipateDownstreamMergePattern mergePattern;

        /**
         * Constructor.
         * @param pattern AnticipateDownstreamMergePattern; the parent pattern
         */
        public FarAnticipationState(final AnticipateDownstreamMergePattern pattern)
        {
            super(pattern);
            this.mergePattern = pattern;
        }

        @Override
        public SimpleOperationalPlan abort() throws ParameterException, GtuException, NetworkException
        {
            return null;
        }

        @Override
        public SimpleOperationalPlan next() throws OperationalPlanException, ParameterException, GtuException, NetworkException
        {
            if (this.mergePattern.listLanesWithCooperationNeeds.isEmpty())
            {
                return null;
            }

            LateralDirectionality dir = this.mergePattern.listLanesWithCooperationNeeds.get(0);

            // Evasive cooperative LC away from the merge side (only in the far phase, where time permits).
            EgoContext ego = this.vehicle.getContextManager().getCategory("Ego", EgoContext.class);
            NeighborsContext neighbors = this.vehicle.getContextManager().getCategory("Neighbors", NeighborsContext.class);

            if (ego != null && neighbors != null
                    && ego.getEgoSpeed().gt(this.vehicle.getParameters().getParameter(ParameterTypes.VCONG))
                    && this.vehicle.getParameters().getParameter(MirovaParameters.cooperativeLaneChangesEnabled))
            {
                LateralDirectionality oppositeDir = dir.isLeft() ? LateralDirectionality.RIGHT : LateralDirectionality.LEFT;

                if (this.vehicle.getLaneChangeDesire().getMandatoryDesire(oppositeDir) >= 0
                        && neighbors.checkIfLaneChangeIsPossible(oppositeDir))
                {
                    return transitionTo(new PerformLaneChangeState(this.mergePattern, oppositeDir, true));
                }
            }

            RelativeLane relativeLane = dir.isLeft() ? RelativeLane.LEFT : RelativeLane.RIGHT;
            InfrastructureContext infra =
                    this.vehicle.getContextManager().getCategory("Infrastructure", InfrastructureContext.class);

            // Ramp is now directly adjacent — hand off to the near-range state.
            if (infra != null && !infra.getDistanceToLaneEnd(relativeLane).eq(Length.POSITIVE_INFINITY))
            {
                return transitionTo(new NearAnticipationState(this.mergePattern));
            }

            return null;
        }

        @Override
        public SimpleOperationalPlan executeControl()
                throws ParameterException, OperationalPlanException, GtuException, NetworkException
        {
            this.mergePattern.setRunning(false);

            EgoContext ego = this.vehicle.getContextManager().getCategory("Ego", EgoContext.class);
            InfrastructureContext infra =
                    this.vehicle.getContextManager().getCategory("Infrastructure", InfrastructureContext.class);
            MacroTrafficContext macro = this.vehicle.getContextManager().getCategory("MacroTraffic", MacroTrafficContext.class);

            Acceleration aDirectLeader = ego.getCurrentCarFollowingAcceleration();
            Acceleration aAnticipation = new Acceleration(Double.POSITIVE_INFINITY, AccelerationUnit.METER_PER_SECOND_2);

            if (!this.mergePattern.listLanesWithCooperationNeeds.isEmpty())
            {
                LateralDirectionality dir = this.mergePattern.listLanesWithCooperationNeeds.get(0);
                RelativeLane relativeLane = dir.isLeft() ? RelativeLane.LEFT : RelativeLane.RIGHT;
                LateralDirectionality oppositeDir = dir.isLeft() ? LateralDirectionality.RIGHT : LateralDirectionality.LEFT;
                LaneDropInfo laneDropInfo = this.mergePattern.anticipatedLaneDropMap.get(dir);

                if (laneDropInfo != null)
                {
                    Lane laneDropLane = laneDropInfo.getLane();
                    Lane mainroadLane = laneDropLane != null
                            ? laneDropLane.getAdjacentLane(oppositeDir, this.vehicle.getGtu().getType()) : null;

                    if (infra.getDistanceToLaneEnd(relativeLane).si > 50.0 && mainroadLane != null)
                    {
                        Length mainroadLaneLength = mainroadLane.getLength();
                        Length startPos =
                                Length.max(Length.ZERO, mainroadLaneLength.minus(DISTANCE_THRESHOLD_MERGE_COOPERATION));
                        Speed downstreamSpeed = infra.getLaneAverageSpeed(mainroadLane, startPos, mainroadLaneLength, 4,
                                ScanDirection.FRONT_TO_BACK);

                        if (downstreamSpeed.lt(this.vehicle.getParameters().getParameter(ParameterTypes.VCONG)))
                        {
                            aAnticipation = MirovaCarFollowingUtil.approachTargetSpeed(this.vehicle, Length.ZERO,
                                    Speed.max(downstreamSpeed, new Speed(30.0, SpeedUnit.KM_PER_HOUR)));
                        }
                    }
                }

                try
                {
                    // Far-range macro check: slow down if the downstream merge zone is already congested.
                    if (macro.getAverageSpeed(relativeLane).lt(this.vehicle.getParameters().getParameter(ParameterTypes.VCONG))
                            && ego.getEgoSpeed().gt(this.vehicle.getParameters().getParameter(ParameterTypes.VCONG)))
                    {
                        aAnticipation = Acceleration.min(aAnticipation,
                                this.vehicle.getParameters().getParameter(MirovaParameters.preemptiveCooperativeDeceleration));
                    }
                }
                catch (OperationalPlanException exception)
                {
                    // macro speed unavailable; keep current anticipation value
                }
            }

            // No anticipation signal was computed — this is plain car-following, nothing to contribute.
            if (aAnticipation.si == Double.POSITIVE_INFINITY)
            {
                return null;
            }
            Acceleration accCoop = this.vehicle.getParameters().getParameter(MirovaParameters.cooperativeDecelerationThreshold);
            Acceleration finalAcc = Acceleration.min(aDirectLeader, Acceleration.max(aAnticipation, accCoop));
            return new SimpleOperationalPlan(finalAcc, this.vehicle.getParameters().getParameter(ParameterTypes.DT));
        }

        @Override
        public double getUtility()
        {
            return 0.15;
        }

        @Override
        public String toString()
        {
            return "FarAnticipationState";
        }
    }

    /*
     * ========================================================================================= STATE: NearAnticipationState
     * Active once the ramp is a directly adjacent lane (getDistanceToLaneEnd < INFINITY). Applies preemptive deceleration
     * whenever ramp vehicles are detected and ego is above vCong.
     * =========================================================================================
     */

    /**
     * Near-range anticipation state: active once the downstream ramp is the directly adjacent lane.
     * <p>
     * When vehicles are detected on the ramp (macro traffic data is available for the relative lane) and the ego speed exceeds
     * the congestion threshold, a preemptive deceleration of {@link MirovaParameters#preemptiveCooperativeDeceleration} is
     * applied to create space for mergers. In the absence of ramp traffic or when already at or below congestion speed, the
     * plan falls back to standard car-following acceleration.
     * </p>
     */
    public static class NearAnticipationState extends ActionState
    {
        /** The parent pattern, typed for direct field access. */
        private final AnticipateDownstreamMergePattern mergePattern;

        /**
         * Constructor.
         * @param pattern AnticipateDownstreamMergePattern; the parent pattern
         */
        public NearAnticipationState(final AnticipateDownstreamMergePattern pattern)
        {
            super(pattern);
            this.mergePattern = pattern;
        }

        @Override
        public SimpleOperationalPlan abort() throws ParameterException, GtuException, NetworkException
        {
            // PatternSelector only calls checkContext() when isRunning() == false. Because NearAnticipationState
            // keeps the pattern running, we must refresh listLanesWithCooperationNeeds manually here each tick.
            try
            {
                if (!this.mergePattern.checkContext())
                {
                    return finishManeuver();
                }
            }
            catch (Exception e)
            {
                // If context evaluation fails, terminate conservatively rather than running blind.
                return finishManeuver();
            }
            return null;
        }

        @Override
        public SimpleOperationalPlan next() throws OperationalPlanException, ParameterException, GtuException, NetworkException
        {
            // Termination is handled by abort(); no internal FSM transition needed from the near state.
            return null;
        }

        @Override
        public SimpleOperationalPlan executeControl()
                throws ParameterException, OperationalPlanException, GtuException, NetworkException
        {
            EgoContext ego = this.vehicle.getContextManager().getCategory("Ego", EgoContext.class);
            MacroTrafficContext macro = this.vehicle.getContextManager().getCategory("MacroTraffic", MacroTrafficContext.class);

            Acceleration aAnticipation = new Acceleration(Double.POSITIVE_INFINITY, AccelerationUnit.METER_PER_SECOND_2);

            if (!this.mergePattern.listLanesWithCooperationNeeds.isEmpty())
            {
                LateralDirectionality dir = this.mergePattern.listLanesWithCooperationNeeds.get(0);
                RelativeLane relativeLane = dir.isLeft() ? RelativeLane.LEFT : RelativeLane.RIGHT;

                try
                {
                    // If macro data is available the ramp carries vehicles; apply preemptive deceleration
                    // whenever ego is faster than the congestion threshold regardless of ramp speed.
                    macro.getAverageSpeed(relativeLane); // throws OperationalPlanException if no data
                    if (ego.getEgoSpeed().gt(this.vehicle.getParameters().getParameter(ParameterTypes.VCONG)))
                    {
                        aAnticipation =
                                this.vehicle.getParameters().getParameter(MirovaParameters.preemptiveCooperativeDeceleration);
                    }
                }
                catch (OperationalPlanException exception)
                {
                    // No vehicles detected on the ramp — no preemptive action needed.
                }
            }

            // No preemptive signal triggered — nothing to contribute beyond plain car-following.
            if (aAnticipation.si == Double.POSITIVE_INFINITY)
            {
                return null;
            }

            Acceleration aDirectLeader = ego.getCurrentCarFollowingAcceleration();
            Acceleration accCoop = this.vehicle.getParameters().getParameter(MirovaParameters.cooperativeDecelerationThreshold);
            Acceleration finalAcc = Acceleration.min(aDirectLeader, Acceleration.max(aAnticipation, accCoop));
            return new SimpleOperationalPlan(finalAcc, this.vehicle.getParameters().getParameter(ParameterTypes.DT));
        }

        @Override
        public double getUtility()
        {
            // Higher than FarAnticipationState (0.15): ramp is adjacent, preemptive action more urgent.
            // Stays well below GapOpener (D_FREE ≈ 0.365) which responds to an active indicator.
            return 0.25;
        }

        @Override
        public String toString()
        {
            return "NearAnticipationState";
        }
    }
}
