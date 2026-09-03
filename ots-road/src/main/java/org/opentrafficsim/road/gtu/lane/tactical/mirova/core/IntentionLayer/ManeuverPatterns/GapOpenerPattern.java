package org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ManeuverPatterns;

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
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;
import org.opentrafficsim.road.gtu.lane.perception.RelativeLane;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.EgoContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.InfrastructureContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.NeighborsContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.InfrastructureContext.LaneDropInfo;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ActionState;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ManeuverPattern;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ManeuverPatterns.SimpleLaneChangePattern.PerformLaneChangeState;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ReactiveLayer.MirovaCarFollowingUtil;

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
    /**
     * Extra headway a cooperating vehicle holds to its own leader, in seconds; zero keeps the previous behaviour.
     * <p>
     * With zero the ego follows the merge candidate as though it were its own leader, which is a car-following
     * equilibrium behind a vehicle on the ramp and therefore terminates at ramp speed, with the ego's own front gap
     * growing for as long as the cooperation lasts. Above zero the ego instead holds an enlarged headway to its own
     * leader, which opens the same gap by the same deceleration - the integral of the speed difference is the gap
     * increment either way - but settles back at mainline speed once it is open.
     * </p>
     * <p>
     * Set with {@code -Dmirova.coopHeadwayReserve=1.0}. A system property rather than a model parameter because this
     * is under test; it becomes a parameter if the comparison supports it.
     * </p>
     */
    private static final double COOP_HEADWAY_RESERVE =
            Double.parseDouble(System.getProperty("mirova.coopHeadwayReserve", "0.0"));

    /** Decay constant of the reserve [s]. A hard end would return the opened gap in one step. */
    private static final double COOP_RESERVE_TAU =
            Double.parseDouble(System.getProperty("mirova.coopReserveTau", "5.0"));

    private static final long serialVersionUID = 20260507L;

    /** The ID of the vehicle we are actively cooperating with. */
    protected String activeMergeCandidateId = null;

    /** The current headway object for the active merge candidate. */
    protected HeadwayGtu activeMergeCandidate = null;

    /** The lateral direction from which the candidate is merging. */
    protected LateralDirectionality directionOfMergeCandidate = null;

    /** Distance threshold to consider cooperation near a lane drop in slow traffic. */
    private static final Length DISTANCE_THRESHOLD_MERGE_COOPERATION = Length.instantiateSI(250.0);

    /** Ego speed below which cooperation is judged against the standing-queue criterion instead. */
    private static final Speed COOPERATION_STANDSTILL_SPEED = new Speed(5.0, SpeedUnit.KM_PER_HOUR);

    /** The cooperative headway reserve as a scalar, built once rather than on every tick of every cooperating vehicle. */
    private static final Duration COOP_HEADWAY_RESERVE_SCALAR = Duration.instantiateSI(COOP_HEADWAY_RESERVE);

    /** The decay constant of the cooperative headway reserve as a scalar. */
    private static final Duration COOP_RESERVE_TAU_SCALAR = Duration.instantiateSI(COOP_RESERVE_TAU);

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
        super(vehicle);
        this.initialActionState = () -> new OpenGapState(this);
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
     * <p>
     * Additionally verifies that the candidate has not moved past ego's own front leader. If the candidate's longitudinal
     * distance exceeds ego's front gap, it will target a merge gap further downstream — cooperation from the ego would be
     * ineffective because the gap being created is beyond the candidate's intended merge point.
     * </p>
     * @return {@code true} if a candidate was found and locked; {@code false} otherwise
     * @throws ParameterException if parameter retrieval fails
     */
    protected boolean findNewCandidate() throws ParameterException
    {
        double dMand = this.vehicle.getParams().dMand;
        if (this.vehicle.getMandatoryLaneChangeDesire().magnitude() >= dMand)
        {
            return false;
        }

        NeighborsContext neighbors = this.vehicle.getContextManager().getCategory("Neighbors", NeighborsContext.class);
        Length egoFrontGap = neighbors.getFrontGapDistance(LateralDirectionality.NONE);
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
                            this.vehicle.getParams().considerGapOpeningLookaheadDistanceScalar))
                    {
                        break;
                    }
                    boolean indicatesTowardsUs = (dir.isRight() && candidate.isLeftTurnIndicatorOn())
                            || (dir.isLeft() && candidate.isRightTurnIndicatorOn());
                    if (indicatesTowardsUs)
                    {
                        // Candidate must lie within ego's own front gap. If it has moved past ego's leader it
                        // will merge into a gap further downstream where ego's deceleration cannot contribute.
                        if (distanceCandidate.gt(egoFrontGap))
                        {
                            continue;
                        }
                        EgoContext ego = this.vehicle.getContextManager().getCategory("Ego", EgoContext.class);
                        // we are behind the candidate and check deceleration
                        if (distanceCandidate.gt(this.vehicle.getParams().s0Scalar))
                        {
                            Acceleration cooperationAcceleration = neighbors.getGtuDeceleration(candidate);
                            Acceleration decelThreshold = getDynamicCooperativeDecelerationThreshold(candidate, dir);

                            if (cooperationAcceleration.ge(decelThreshold))
                            {
                                // If our front leader can also cooperate, we prefer a cooperative gap opening from the front
                                // leader to create a larger gap for the candidate and avoid unnecessary braking on the
                                // mainline. If the leader cannot cooperate, we take the opportunity to cooperate ourselves and
                                // open a gap for the candidate.
                                if (leaderCanCooperate(candidate))
                                {
                                    return false;
                                }
                                else
                                {
                                    this.activeMergeCandidateId = candidate.getId();
                                    this.directionOfMergeCandidate = dir;
                                    return true;
                                }
                            }

                        }
                        // we are parallel to the candidate, but since we have not much space to drive forward, we can also
                        // consider cooperation
                        else if (ego.getEgoSpeed().lt(COOPERATION_STANDSTILL_SPEED) && egoFrontGap.si < 15.0)
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

    /**
     * Evaluates whether the front leader of the ego can cooperate by braking to create a gap for the candidate. Cooperation is
     * only deemed feasible if the induced deceleration on the leader does not exceed the
     * {@link MirovaParameters#cooperativeDecelerationThreshold}. This check is used to prevent unsafe emergency braking on the
     * mainline when the candidate is too close to the leader or has a much higher speed.
     * @param candidate the candidate GTU for which we are evaluating cooperation feasibility
     * @return true if cooperation is feasible, false otherwise
     * @throws ParameterException if a parameter lookup fails
     **/
    protected boolean leaderCanCooperate(HeadwayGtu candidate) throws ParameterException
    {
        HeadwayGtu frontLeader =
                this.vehicle.getContextManager().getCategory("Neighbors", NeighborsContext.class).getCurrentLeader();
        if (frontLeader == null)
        {
            // System.out.println("GTU: " + this.vehicle.getGtu().getId()
            // + " has no front leader, cannot cooperate to open gap for candidate " + candidate.getId());
            return false;
        }

        Length distanceToFrontLeader = frontLeader.getDistance();
        Length leaderLength = frontLeader.getLength();
        Speed leaderSpeed = frontLeader.getSpeed();
        Length candidateDistance = candidate.getDistance();

        Length leaderToCandidateDistance = candidateDistance.minus(distanceToFrontLeader).minus(leaderLength);

        double safetyDistanceReductionFactor =
                this.vehicle.getParams().safetyDistanceReductionFactorLaneChange;
        Length leaderDesiredHeadway = frontLeader.getCarFollowingModel()
                .desiredHeadway(frontLeader.getParameters(), leaderSpeed).times(safetyDistanceReductionFactor);

        if (leaderToCandidateDistance.si < leaderDesiredHeadway.si)
        {
            // System.out.println("GTU " + this.vehicle.getGtu().getId() + ": Leader " + frontLeader.getId()
            // + " cannot cooperate to open gap for candidate " + candidate.getId()
            // + " because leaderToCandidateDistance (" + leaderToCandidateDistance
            // + ") is less than leaderDesiredHeadway (" + leaderDesiredHeadway + ")");
            return false;
        }

        Speed leaderCandidateSpeedDelta = leaderSpeed.minus(candidate.getSpeed());

        if (leaderCandidateSpeedDelta.si <= 0)
        {
            // System.out.println("GTU " + this.vehicle.getGtu().getId() + ": Leader " + frontLeader.getId()
            // + " cannot cooperate to open gap for candidate " + candidate.getId()
            // + " because leaderCandidateSpeedDelta (" + leaderCandidateSpeedDelta + ") is not positive)");
            return true;
        }

        Length leaderCandidateDistanceHeadwway = leaderToCandidateDistance.minus(leaderDesiredHeadway);

        Acceleration leaderInducedAcceleration = Acceleration
                .instantiateSI(-Math.pow(leaderCandidateSpeedDelta.si, 2.0) / (2.0 * leaderCandidateDistanceHeadwway.si));

        Acceleration decelThreshold = getDynamicCooperativeDecelerationThreshold(candidate, this.directionOfMergeCandidate);

        return leaderInducedAcceleration.ge(decelThreshold);
    }

    /**
     * Calculates the dynamic cooperative deceleration threshold based on the candidate's distance to its lane end.
     * <p>
     * At large distances (d >= LOOKAHEAD, e.g. >= 400m), cooperation willingness is conservative, bounded by
     * {@link MirovaParameters#preemptiveCooperativeDeceleration} (e.g. -0.5 m/s^2).
     * As the candidate approaches the lane end (d <= 100m), cooperation willingness
     * ramps up to {@link MirovaParameters#cooperativeDecelerationThreshold} (e.g. -2.0 m/s^2).
     * </p>
     * @param candidate the candidate GTU for merge cooperation
     * @param dir the direction of the merge candidate (RIGHT or LEFT)
     * @return Acceleration dynamic threshold (negative value)
     * @throws ParameterException if parameter lookup fails
     */
    public Acceleration getDynamicCooperativeDecelerationThreshold(HeadwayGtu candidate, LateralDirectionality dir)
            throws ParameterException
    {
        Acceleration maxCoop = this.vehicle.getParams().cooperativeDecelerationThresholdScalar;
        Acceleration minCoop = this.vehicle.getParams().preemptiveCooperativeDecelerationScalar;

        InfrastructureContext infra = this.vehicle.getContext(InfrastructureContext.class);
        RelativeLane relLane = (dir != null && dir.isLeft()) ? RelativeLane.LEFT : RelativeLane.RIGHT;
        Length distToLaneEnd = infra != null ? infra.getRouteDistanceToLaneEnd(relLane) : null;

        if (distToLaneEnd == null || Double.isInfinite(distToLaneEnd.si))
        {
            return maxCoop;
        }

        double lookahead = this.vehicle.getParameters().getParameter(ParameterTypes.LOOKAHEAD).si;
        double criticalDist = 100.0; // 100m critical distance threshold near ramp end

        if (distToLaneEnd.si >= lookahead)
        {
            return minCoop;
        }
        else if (distToLaneEnd.si <= criticalDist)
        {
            return maxCoop;
        }
        else
        {
            double ratio = (distToLaneEnd.si - criticalDist) / (lookahead - criticalDist);
            ratio = Math.min(1.0, Math.max(0.0, ratio));
            // Linear interpolation between maxCoop (-2.0) and minCoop (-0.5)
            double interpolatedSI = maxCoop.si + (minCoop.si - maxCoop.si) * ratio;
            return Acceleration.instantiateSI(interpolatedSI);
        }
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
        public ActionState next() throws OperationalPlanException, ParameterException, GtuException, NetworkException
        {

            EgoContext ego = this.vehicle.getContextManager().getCategory("Ego", EgoContext.class);
            NeighborsContext neighbors = this.vehicle.getContextManager().getCategory("Neighbors", NeighborsContext.class);

            if (ego.getEgoSpeed().gt(this.vehicle.getParams().vCongScalar)
                    && this.vehicle.getParams().cooperativeLaneChangesEnabled)
            {
                LateralDirectionality oppositeDir = this.maneuverPattern.directionOfMergeCandidate.isLeft()
                        ? LateralDirectionality.RIGHT : LateralDirectionality.LEFT;
                if (this.vehicle.getMandatoryLaneChangeDesire().getMandatoryDesire(oppositeDir) >= 0.0
                        && neighbors.checkIfLaneChangeIsPossible(oppositeDir))
                {
                    return new PerformLaneChangeState(this.maneuverPattern, oppositeDir, true);
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
            Acceleration aCooperation = Acceleration.POSITIVE_INFINITY;

            HeadwayGtu candidate = this.maneuverPattern.activeMergeCandidate;
            if (candidate != null)
            {
                Acceleration decelThreshold = this.maneuverPattern.getDynamicCooperativeDecelerationThreshold(candidate,
                        this.maneuverPattern.directionOfMergeCandidate);
                if (candidate.getDistance().si > 0)
                {
                    aCooperation = MirovaCarFollowingUtil.followSingleLeader(vehicle, candidate);

                    aCooperation = aCooperation.gt(decelThreshold) ? aCooperation : decelThreshold;
                }
                else
                {
                    aCooperation =
                            this.vehicle.getParams().preemptiveCooperativeDecelerationScalar;
                }
            }

            if (COOP_HEADWAY_RESERVE > 0.0)
            {
                // Hold an enlarged headway to the ego's own leader instead of following the candidate. The gap that
                // matters to the merger is the ego's front gap, and this opens it without handing the ego's speed to
                // a vehicle on the ramp. The reserve decays after the cooperation ends, so the gap is released
                // gradually rather than closed the moment the candidate stops indicating.
                ego.reserveHeadwayForCooperation(COOP_HEADWAY_RESERVE_SCALAR, COOP_RESERVE_TAU_SCALAR);
                return new SimpleOperationalPlan(ego.getCurrentCarFollowingAcceleration(),
                        this.maneuverPattern.getPatternSpecificTimestep());
            }

            Acceleration finalAcceleration = Acceleration.min(aCooperation, aDirectLeader);
            return new SimpleOperationalPlan(finalAcceleration, this.vehicle.getParams().dtScalar);
        }

        @Override
        public ActionState abort() throws ParameterException, GtuException, NetworkException
        {
            this.maneuverPattern.activeMergeCandidate = this.maneuverPattern.getActiveMergeCandidate();
            HeadwayGtu candidate = this.maneuverPattern.activeMergeCandidate;
            EgoContext ego = this.vehicle.getContextManager().getCategory("Ego", EgoContext.class);
            if (candidate == null || (!candidate.isLeftTurnIndicatorOn() && !candidate.isRightTurnIndicatorOn()))
            {
                return FINISHED;
            }

            NeighborsContext neighbors = this.vehicle.getContextManager().getCategory("Neighbors", NeighborsContext.class);
            Length egoFrontGap = neighbors.getFrontGapDistance(LateralDirectionality.NONE);

            // Abort if the candidate has moved past ego's own front leader. It will target a gap further
            // downstream where ego's deceleration cannot contribute to a successful merge.
            // if (candidate.getDistance().gt(egoFrontGap))

            // Leader of Ego vehicle can cooperate to open a gap for the candidate
            if (this.maneuverPattern.leaderCanCooperate(candidate))
            {
                return FINISHED;
            }

            if (candidate.getDistance().lt(this.vehicle.getParams().s0Scalar))
            {
                if (ego.getEgoSpeed().ge(COOPERATION_STANDSTILL_SPEED) || egoFrontGap.si >= 15.0)
                {
                    // Parallel cooperation is aborted once we have enough space to drive forward
                    return FINISHED;
                }
            }
            this.maneuverPattern.setRunning(false);
            return null;
        }

        /**
         * Returns how much this cooperation is worth, rising as the candidate runs out of lane.
         * <p>
         * This used to return {@link MirovaParameters#DFREE} unchanged, so every cooperation carried the same weight:
         * a vehicle that has just switched on its indicator a lookahead distance away counted for exactly as much as
         * one about to run out of acceleration lane, and the arbitration could not prefer the second. A constant
         * utility means the arbitration is not arbitrating.
         * </p>
         * <p>
         * The scale is the same one {@link GapOpenerPattern#getDynamicCooperativeDecelerationThreshold} already uses
         * for the deceleration it permits - full weight within the critical distance of the lane end, falling to the
         * base value at the lookahead - so the two rise together rather than on separate scales.
         * </p>
         * @return double; the utility of cooperating with the current candidate
         */
        @Override
        public double getUtility()
        {
            try
            {
                double base = this.vehicle.getParams().dFree;
                HeadwayGtu candidate = this.maneuverPattern.getActiveMergeCandidate();
                InfrastructureContext infra = this.vehicle.getContext(InfrastructureContext.class);
                LateralDirectionality dir = this.maneuverPattern.directionOfMergeCandidate;
                if (candidate == null || infra == null || dir == null)
                {
                    return base;
                }
                RelativeLane rel = dir.isLeft() ? RelativeLane.LEFT : RelativeLane.RIGHT;
                Length toEnd = infra.getRouteDistanceToLaneEnd(rel);
                if (toEnd == null || Double.isInfinite(toEnd.si))
                {
                    return base;
                }
                double lookahead = this.vehicle.getParameters().getParameter(ParameterTypes.LOOKAHEAD).si;
                double critical = 100.0;
                double urgency = 1.0 - Math.min(1.0, Math.max(0.0,
                        (toEnd.si - critical) / Math.max(lookahead - critical, 1.0)));
                return base * (1.0 + urgency);
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
