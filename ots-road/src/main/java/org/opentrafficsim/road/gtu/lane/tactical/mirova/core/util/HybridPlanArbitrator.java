package org.opentrafficsim.road.gtu.lane.tactical.mirova.core.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.djunits.value.vdouble.scalar.Acceleration;
import org.djunits.value.vdouble.scalar.Duration;
import org.opentrafficsim.base.parameters.ParameterException;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.core.gtu.GtuException;
import org.opentrafficsim.core.network.LateralDirectionality;
import org.opentrafficsim.core.network.NetworkException;
import org.opentrafficsim.road.gtu.lane.plan.operational.SimpleOperationalPlan;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.MirovaTacticalPlanner;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ActionState;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ManeuverPattern;

/**
 * Hybrid plan arbitrator implementing the three-step MiRoVA selection scheme.
 * <p>
 * This arbitrator replaces the flat maximum-utility strategy with a context-sensitive hybrid:
 * below the {@code D_FREE} threshold all active patterns contribute longitudinally (minimum
 * acceleration), while above it a single winner is committed to. A pre-commitment lock for
 * in-progress lane changes is checked first and always takes precedence.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class HybridPlanArbitrator
{
    /** Multiplier applied to the utility of the last-active pattern to favor continuity over oscillation. */
    private static final double HYSTERESIS_MULTIPLIER = 1.10;

    /** The ego vehicle whose patterns this arbitrator evaluates. */
    private final MirovaTacticalPlanner vehicle;

    /** The pattern that produced the selected plan in the most recent tick (for hysteresis). */
    private ManeuverPattern lastActivePattern = null;

    /** The action state that was active when the last plan was selected (for planner state sync). */
    private ActionState lastActiveState = null;

    // ----------------------------------------------------------------------
    // Construction
    // ----------------------------------------------------------------------

    /**
     * Creates a new hybrid arbitrator bound to the given ego vehicle.
     *
     * @param vehicle MirovaTacticalPlanner; the ego vehicle whose patterns this arbitrator evaluates
     */
    public HybridPlanArbitrator(final MirovaTacticalPlanner vehicle)
    {
        this.vehicle = vehicle;
    }

    // ----------------------------------------------------------------------
    // Core arbitration
    // ----------------------------------------------------------------------

    /**
     * Selects a {@link SimpleOperationalPlan} from the active patterns using the three-step hybrid scheme.
     * <p>
     * The three steps, executed in order each tick:
     * <ol>
     * <li><b>Commitment check:</b> if a lane-change pattern holds the action lock, execute it
     *     unconditionally and return; release lock only when the action signals completion (null plan).</li>
     * <li><b>Above-threshold (desire &gt;= D_FREE):</b> winner-takes-all by maximum utility; if the
     *     winner is a lane-change pattern, commit the vehicle to its action state.</li>
     * <li><b>Below-threshold:</b> aggregate longitudinal acceleration as the minimum across all active
     *     patterns; lateral direction is taken from the highest-utility lane-change proposal, if any.</li>
     * </ol>
     * Returns {@code null} if no pattern produced a valid plan; the caller is then responsible for
     * falling back to pure car-following.
     * </p>
     *
     * @param relevantPatterns ArrayList&lt;ManeuverPattern&gt;; patterns that passed context and ability checks
     * @return SimpleOperationalPlan; the selected plan, or {@code null} when no pattern is active
     * @throws ParameterException if a required parameter cannot be retrieved
     * @throws NullPointerException if required perception or context data are unavailable
     * @throws IllegalArgumentException if an argument is inconsistent
     * @throws GtuException if a GTU state error occurs
     * @throws NetworkException if a network query fails
     */
    public SimpleOperationalPlan arbitrate(final ArrayList<ManeuverPattern> relevantPatterns)
            throws ParameterException, NullPointerException, IllegalArgumentException, GtuException, NetworkException
    {
        // ----------------------------------------------------------------
        // STEP 1 — Commitment check (lane-change patterns only)
        // If an LC pattern holds the action lock, its plan wins unconditionally.
        // ----------------------------------------------------------------
        ActionState locked = this.vehicle.getLockedActionState();
        if (locked != null && locked.getManeuverPattern().isLaneChangePattern())
        {
            SimpleOperationalPlan lockedPlan = locked.update();
            if (lockedPlan != null)
            {
                // Committed LC maneuver still running — return its plan immediately.
                this.lastActivePattern = locked.getManeuverPattern();
                this.lastActiveState = locked.getManeuverPattern().getCurrentActionState();
                return lockedPlan;
            }
            // Action signalled completion (null plan); release lock and fall through to re-arbitrate.
            this.vehicle.releaseActionLock();
        }

        // ----------------------------------------------------------------
        // Build scored proposals from all relevant patterns.
        // ----------------------------------------------------------------
        double dFree = this.vehicle.getDFree();
        List<ScoredOperationalPlan> proposals = new ArrayList<>();

        for (ManeuverPattern pattern : relevantPatterns)
        {
            SimpleOperationalPlan plan = pattern.update();
            if (plan == null)
            {
                continue;
            }
            double utility = pattern.getCurrentActionState().getUtility();
            // Hysteresis: boost utility of the last-active pattern to reduce oscillation.
            if (pattern.equals(this.lastActivePattern))
            {
                utility *= HYSTERESIS_MULTIPLIER;
            }
            proposals.add(new ScoredOperationalPlan(plan, utility, pattern, pattern.getCurrentActionState()));
        }

        if (proposals.isEmpty())
        {
            return null; // caller falls back to car-following
        }

        // ----------------------------------------------------------------
        // STEP 2 — Above-threshold: winner-takes-all
        // If any pattern's desire meets or exceeds D_FREE, select the one
        // with the highest utility and commit if it is an LC pattern.
        // ----------------------------------------------------------------
        boolean anyAboveThreshold = false;
        for (ManeuverPattern pattern : relevantPatterns)
        {
            if (pattern.getDesire() >= dFree)
            {
                anyAboveThreshold = true;
                break;
            }
        }

        if (anyAboveThreshold)
        {
            ScoredOperationalPlan winner = Collections.max(proposals); // natural order = utility
            this.lastActivePattern = winner.getSourcePattern();
            this.lastActiveState = winner.getSourcePattern().getCurrentActionState();
            if (winner.getSourcePattern().isLaneChangePattern())
            {
                this.vehicle.commitToAction(winner.getSourceState());
            }
            return winner.getOperationalPlan();
        }

        // ----------------------------------------------------------------
        // STEP 3 — Below-threshold: minimum-acceleration aggregation
        // Take the most restrictive longitudinal acceleration from all proposals.
        // Lateral direction comes from the highest-utility LC proposal, if any.
        // ----------------------------------------------------------------
        Acceleration minAcc = proposals.stream()
                .map(p -> p.getOperationalPlan().getAcceleration())
                .min(Comparator.comparingDouble(a -> a.si))
                .orElseThrow(() -> new IllegalStateException("Proposals list unexpectedly empty after size check."));

        LateralDirectionality lateral = LateralDirectionality.NONE;
        Optional<ScoredOperationalPlan> bestLcProposal = proposals.stream()
                .filter(p -> p.getSourcePattern().isLaneChangePattern())
                .max(Comparator.comparingDouble(ScoredOperationalPlan::getUtility));
        if (bestLcProposal.isPresent())
        {
            LateralDirectionality lcLateral = bestLcProposal.get().getOperationalPlan().getLaneChangeDirection();
            if (lcLateral != null && (lcLateral.isLeft() || lcLateral.isRight()))
            {
                lateral = lcLateral;
            }
        }

        // Track the best-utility pattern for hysteresis in the next tick.
        ScoredOperationalPlan best = Collections.max(proposals);
        this.lastActivePattern = best.getSourcePattern();
        this.lastActiveState = best.getSourcePattern().getCurrentActionState();

        Duration dt = this.vehicle.getParameters().getParameter(ParameterTypes.DT);
        return new SimpleOperationalPlan(minAcc, dt, lateral);
    }

    // ----------------------------------------------------------------------
    // Accessors for planner state synchronisation
    // ----------------------------------------------------------------------

    /**
     * Returns the pattern that produced the winning plan in the most recent arbitration tick.
     * Used by {@link MirovaTacticalPlanner} to update its {@code lastActivePattern} field.
     *
     * @return ManeuverPattern; the last-active pattern, or {@code null} before the first arbitration
     */
    public ManeuverPattern getLastActivePattern()
    {
        return this.lastActivePattern;
    }

    /**
     * Returns the action state that was current when the winning plan was selected.
     * Used by {@link MirovaTacticalPlanner} to synchronise its {@code currentActionState} field.
     *
     * @return ActionState; the last-active action state, or {@code null} before the first arbitration
     */
    public ActionState getLastActiveState()
    {
        return this.lastActiveState;
    }
}
