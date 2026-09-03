package org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer;

import java.util.Collections;
import java.util.List;

import org.opentrafficsim.base.parameters.ParameterException;
import org.opentrafficsim.core.gtu.GtuException;
import org.opentrafficsim.core.gtu.plan.operational.OperationalPlanException;
import org.opentrafficsim.core.network.NetworkException;
import org.opentrafficsim.road.gtu.lane.plan.operational.SimpleOperationalPlan;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging.FsmTraceRecorder;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.MirovaTacticalPlanner;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.EgoContext;

/**
 * Abstract base class representing an executable action state within a maneuver pattern.
 * <p>
 * Action states define concrete, time-continuous vehicle behavior during a specific phase of a maneuver (e.g., preparation,
 * lane change, completion). They form the atomic units of the Finite State Machine (FSM) representing procedural knowledge in
 * <b>Layer 4 (Procedure & Action)</b> of the MiRoVA architecture.
 * </p>
 * <p>
 * Each ActionState is responsible for:
 * <ul>
 * <li>Executing control logic (e.g., car-following, gap maintenance) by returning a {@link SimpleOperationalPlan}</li>
 * <li>Declaring the rules under which it hands over to another state, or ends the maneuver</li>
 * </ul>
 * <p>
 * Copyright (c) 2025 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public abstract class ActionState
{

    /**
     * Longest chain of transitions resolved within a single tick before the machine is declared to be cycling. Chains longer
     * than a handful are a modelling mistake rather than a legitimate sequence, so the bound is generous but finite.
     */
    private static final int MAX_TRANSITIONS_PER_TICK = 32;

    /**
     * Sentinel returned by a transition rule to end the maneuver rather than move to another state.
     * <p>
     * A sentinel rather than a second method, because ending is one of the answers to the same question -- what does this
     * state hand over to -- and giving it its own channel is what allowed finishManeuver() to be called for its side effects
     * in one place and for its plan in another.
     * </p>
     */
    public static final ActionState FINISHED = new Finished();

    /** Reference to the parent maneuver pattern. */
    protected final ManeuverPattern maneuverPattern;

    /** Associated vehicle (retrieved from the maneuver's knowledge chunk). */
    protected final MirovaTacticalPlanner vehicle;

    /** Indicates whether this state is currently active. */
    protected boolean active = false;

    /** This state's transition rules, built on first use. */
    private List<Transition> transitionTable;

    /** Optional cached operational plan for the current time step. */
    protected SimpleOperationalPlan operationalPlan;

    // ----------------------------------------------------------------------
    // Construction
    // ----------------------------------------------------------------------

    /**
     * Initializes a new ActionState for the given maneuver pattern.
     * <p>
     * Constructing a state does <b>not</b> enter it. Until this was separated, {@code new SomeState(pattern)} made that
     * state the pattern's current one and marked the pattern running as a side effect of the {@code new}, so a state could
     * not be created without altering the machine -- which is why a transition had to be expressed as construction, and
     * why no state could be evaluated or unit-tested in isolation. Entering is now {@link #enter()}, and the transition
     * machinery is the only thing that calls it.
     * </p>
     * @param maneuverPattern the parent maneuver pattern this state belongs to
     */
    public ActionState(final ManeuverPattern maneuverPattern)
    {
        this.maneuverPattern = maneuverPattern;
        // Null only for the FINISHED sentinel, which belongs to no pattern and is never entered or executed.
        this.vehicle = maneuverPattern == null ? null : maneuverPattern.getMirovaTacticalPlanner();
    }

    // ----------------------------------------------------------------------
    // Entry and exit
    // ----------------------------------------------------------------------

    /**
     * Makes this state the pattern's current one and marks the pattern running, then runs {@link #onEntry()}.
     * <p>
     * This is what {@code new SomeState(pattern)} used to do implicitly. Keeping it in one method is what allows the
     * bookkeeping -- the pattern's current state, the running flag, the planner's view of the current state -- to be
     * maintained in a single place instead of being re-asserted from constructors and from {@code executeControl}.
     * </p>
     */
    public final void enter()
    {
        this.active = true;
        this.maneuverPattern.setCurrentActionState(this);
        this.maneuverPattern.setRunning(true);
        this.vehicle.setCurrentActionState(this);
        FsmTraceRecorder.noteStateEntered(this);
        onEntry();
    }

    /**
     * Runs {@link #onExit()} and marks this state no longer active.
     */
    public final void exit()
    {
        onExit();
        this.active = false;
    }

    /**
     * Hook invoked when this state is entered. Empty by default.
     * <p>
     * Work a state does once on entry belongs here rather than in its constructor or in the first {@code executeControl}
     * call: a constructor runs when the state is merely considered, and {@code executeControl} runs every tick.
     * </p>
     */
    protected void onEntry()
    {
        //
    }

    /**
     * Hook invoked when this state is left, whether by transition or by the maneuver finishing. Empty by default.
     */
    protected void onExit()
    {
        //
    }

    // ----------------------------------------------------------------------
    // Core execution cycle
    // ----------------------------------------------------------------------

    /**
     * Runs the state machine for one time step and returns the plan the vehicle acts on.
     * <p>
     * Each state is asked where it hands over to; a state that answers nowhere produces the plan. A state that names a target is left, the target is entered, and the same two questions are put to it, so a chain
     * of transitions resolves inside one tick and the state the chain ends in is the one that acts. The previous
     * implementation did the same thing, but it did it by having a state perform its own transition and call update() on the
     * target, so the chain was an unbounded recursion spread across every state class. Two states that transition to each
     * other were a StackOverflowError waiting for the right traffic situation. Here the chain is a bounded loop in one
     * place, and a cycle is reported as what it is.
     * </p>
     * @return the operational plan for this time step
     * @throws ParameterException if a parameter required for control logic cannot be found
     * @throws NullPointerException if required contextual data is missing
     * @throws IllegalArgumentException if invalid arguments are passed during plan generation
     * @throws GtuException if an error occurs within the GTU state
     * @throws NetworkException if a network-related error occurs during lookup
     */
    public final SimpleOperationalPlan update()
            throws ParameterException, NullPointerException, IllegalArgumentException, GtuException, NetworkException
    {
        ActionState state = this;
        for (int depth = 0; depth < MAX_TRANSITIONS_PER_TICK; depth++)
        {
            ActionState target = state.next();
            if (target == null)
            {
                state.operationalPlan = state.executeControl();
                return state.operationalPlan;
            }
            if (target == FINISHED)
            {
                state.operationalPlan = state.finishManeuver();
                return state.operationalPlan;
            }
            this.vehicle.releaseActionLock();
            state.exit();
            target.enter();
            state = target;
        }
        throw new IllegalStateException("Action states of " + this.maneuverPattern.getClass().getSimpleName()
                + " kept transitioning for " + MAX_TRANSITIONS_PER_TICK + " steps in one tick; the last was " + state
                + ". That is a cycle in the transition graph, not a long chain.");
    }

    // ----------------------------------------------------------------------
    // Abstract responsibilities
    // ----------------------------------------------------------------------

    /**
     * Executes the vehicle control logic for this action state.
     * <p>
     * Example: car-following, cooperative adaptation, or lane-change execution.
     * </p>
     * * @return the operational plan representing the control output for this step
     * @throws ParameterException if a parameter required for control logic cannot be found
     * @throws OperationalPlanException if the generation of the operational plan fails
     * @throws GtuException if an error occurs within the GTU state
     * @throws NetworkException if a network-related error occurs
     */
    public abstract SimpleOperationalPlan executeControl()
            throws ParameterException, OperationalPlanException, GtuException, NetworkException;

    /**
     * Returns this state's transition rules, in the order they are to be evaluated.
     * <p>
     * The order is the machine's behaviour, not a detail of it: the first rule that applies wins, so moving one rule past
     * another changes what the vehicle does. Subclasses that extend an inherited table put the inherited rules first --
     * {@code super.transitions()} then their own -- because a rule that belonged to the enclosing manoeuvre outranks one
     * that belongs to a phase of it.
     * </p>
     * <p>
     * Built once per state instance and cached, so the rules may be lambdas without allocating them every tick.
     * </p>
     * @return the ordered rules; empty for a state that only ever ends by some other means
     */
    protected List<Transition> transitions()
    {
        return Collections.emptyList();
    }

    /**
     * Returns this state's transition rules, for describing the machine rather than running it.
     * <p>
     * The point of holding the rules in a list is that something other than the machine itself can read them: the graph can
     * be exported instead of drawn by hand, and the evaluation order can be pinned by a test instead of being an emergent
     * property of a cascade of {@code if} statements.
     * </p>
     * @return the ordered rules
     */
    public final List<Transition> getTransitions()
    {
        if (this.transitionTable == null)
        {
            this.transitionTable = transitions();
        }
        return this.transitionTable;
    }

    /**
     * Names the state to move to, by evaluating {@link #transitions()} in order and taking the first rule that applies.
     * <p>
     * A decision and nothing else: a rule must not enter its target, must not build a plan, and must leave the machine as it
     * found it. {@link #update()} performs the transition the rule names.
     * </p>
     * <p>
     * There used to be a second method, {@code abort()}, asked before this one. It was never an exceptional path: it was
     * evaluated on every state in every tick and answered with a state, which is to say it was the highest-priority guard
     * wearing another name. It is now simply the first entry in the same table.
     * </p>
     * @return the state to transition to, {@link #FINISHED} to end the maneuver, or {@code null} to stay
     * @throws OperationalPlanException if the generation of the operational plan fails
     * @throws ParameterException if parameter retrieval fails during transition checks
     * @throws GtuException if an error occurs within the GTU state
     * @throws NetworkException if a network-related error occurs
     */
    public final ActionState next()
            throws OperationalPlanException, ParameterException, GtuException, NetworkException
    {
        List<Transition> rules = getTransitions();
        for (int index = 0; index < rules.size(); index++)
        {
            ActionState target = rules.get(index).evaluate();
            if (target != null)
            {
                FsmTraceRecorder.noteTransitionTaken(this, index);
                return target;
            }
        }
        return null;
    }

    /**
     * Calculates and returns the utility of this specific action state.
     * <p>
     * The utility represents the motivation or fitness of the maneuver at this exact stage. Higher values indicate a stronger
     * need or better suitability to execute or continue this state compared to concurrently proposed plans. This score is
     * utilized by the tactical planner's arbitration layer.
     * </p>
     * * @return double; the evaluated utility score for this specific action state
     */
    public double getUtility()
    {
        // Default implementation returns a neutral utility score.
        // Subclasses should override this method to provide context-specific utility evaluations.
        return 0.0;
    }

    // ----------------------------------------------------------------------
    // Helper and lifecycle methods
    // ----------------------------------------------------------------------

    /**
     * Transitions to the specified next state. * @param nextState the new active action state to transition into
     * @return the operational plan generated by the new state's update cycle
     * @throws ParameterException if parameter retrieval fails in the new state
     * @throws NullPointerException if required contextual data is missing in the new state
     * @throws IllegalArgumentException if invalid arguments are passed in the new state
     * @throws GtuException if an error occurs within the GTU state
     * @throws NetworkException if a network-related error occurs
     */
    /**
     * The sentinel type. It is never entered, never asked for a plan, and exists only to be compared against.
     */
    private static final class Finished extends ActionState
    {
        /** Creates the sentinel. It has no pattern, which is safe because nothing ever runs it. */
        Finished()
        {
            super(null);
        }

        @Override
        public SimpleOperationalPlan executeControl()
        {
            throw new UnsupportedOperationException("ActionState.FINISHED is a marker and cannot be executed.");
        }

        @Override
        public String toString()
        {
            return "FINISHED";
        }
    }

    /**
     * Finalizes the maneuver, resetting vehicle state and stopping the maneuver pattern. * @return an operational plan resuming
     * normal driving behavior (e.g., car-following)
     * @throws ParameterException if required parameters cannot be retrieved
     * @throws GtuException if an error occurs within the GTU state
     * @throws NetworkException if a network-related error occurs
     */
    protected SimpleOperationalPlan finishManeuver() throws ParameterException, GtuException, NetworkException
    {
        this.vehicle.releaseActionLock();
        exit();
        this.maneuverPattern.setRunning(false);
        EgoContext egoCtx = this.vehicle.getContext(EgoContext.class);
        return new SimpleOperationalPlan(egoCtx.getCurrentCarFollowingAcceleration(),
                this.maneuverPattern.getPatternSpecificTimestep());
    }

    /**
     * Returns whether this state is currently active. * @return true if active, false otherwise
     */
    public boolean isActive()
    {
        return this.active;
    }

    /**
     * Returns the vehicle executing this action. * @return the MiRoVA tactical planner associated with the vehicle
     */
    public MirovaTacticalPlanner getVehicle()
    {
        return this.vehicle;
    }

    /**
     * Returns the parent maneuver pattern orchestrating this state. * @return the parent maneuver pattern
     */
    public ManeuverPattern getManeuverPattern()
    {
        return this.maneuverPattern;
    }
}
