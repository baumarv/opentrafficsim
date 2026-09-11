package org.opentrafficsim.road.gtu.lane.tactical.mirova;

import org.djunits.value.vdouble.scalar.Time;
import org.opentrafficsim.road.gtu.lane.plan.operational.SimpleOperationalPlan;

/**
 * Watches a {@link MirovaTacticalPlanner} decide, once per tactical tick.
 * <p>
 * The seam exists so that an external recorder -- the TaMA equivalence harness, for instance -- can read what a
 * vehicle decided without the model knowing anything about recording, and without reflection. It is called at the one
 * point in the cycle where the pattern, the action state, the desires and the acceleration the vehicle acts on are all
 * settled, which is the same point {@code FsmTraceRecorder} is called from and the only point at which a tick is
 * internally consistent.
 * </p>
 * <h3>What is available</h3>
 * <p>
 * The planner is passed whole, so everything it exposes is reachable: {@link MirovaTacticalPlanner#getLaneChangeDesire}
 * for the combined desire, {@link MirovaTacticalPlanner#getMandatoryLaneChangeDesire} and
 * {@link MirovaTacticalPlanner#getDiscretionaryLaneChangeDesire} for the two halves,
 * {@link MirovaTacticalPlanner#getKnowledgeChunks} with {@code getDesire()} on each for the per-incentive
 * contributions, {@link MirovaTacticalPlanner#getActivePattern} and
 * {@link MirovaTacticalPlanner#getCurrentActionState} for what is driving, {@link MirovaTacticalPlanner#getParams}
 * for the parameters it resolved, and {@link MirovaTacticalPlanner#getArbitrationProposals} for what each relevant
 * pattern proposed before the arbitration chose between them. The plan carries the acceleration, the lane-change
 * direction and the indicator intent.
 * </p>
 * <h3>Read-only, and why that is a promise rather than a guarantee</h3>
 * <p>
 * An observer <b>must not</b> mutate the planner, its contexts, its patterns or the plan, and must not call anything
 * that advances state -- which in practice means: read the accessors, copy what you need, return. Java cannot enforce
 * this through a reference, and wrapping every object in a read-only view would cost an allocation per tick on the
 * hot path for a promise a caller can break anyway. The contract is therefore stated rather than enforced. An observer
 * that breaks it changes the model, and the trajectories will say so.
 * </p>
 * <h3>Cost when nobody is watching</h3>
 * <p>
 * A planner with no observer performs one null check per tick and nothing else: no allocation, no branch into
 * recording code, and no object it would not otherwise have created. The unobserved path is identical.
 * </p>
 * <h3>Installing one per vehicle class</h3>
 * <p>
 * Through the factory hook the scenario management already provides. The hook is handed the fully configured tactical
 * planner factory of each class, and a decorator can attach an observer to every planner that factory creates:
 * </p>
 *
 * <pre>
 * ScenarioGenerator.setTacticalPlannerFactoryHook((vehicleClass, built) -&gt;
 * {
 *     if (built instanceof MirovaTacticalPlannerFactory)
 *     {
 *         ((MirovaTacticalPlannerFactory) built).setObserver(recorderFor(vehicleClass));
 *     }
 *     return built;
 * });
 * </pre>
 * <p>
 * The hook is handed the factory of each class before any vehicle is built, so one call installs the observer on every
 * planner that class will create. The factory is returned unchanged; nothing is wrapped.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
@FunctionalInterface
public interface MirovaTacticalPlannerObserver
{
    /**
     * Called after a tactical tick has been decided, before the plan is returned to the host.
     * <p>
     * Called exactly once per tick, on the thread that stepped the planner, and not at all on the ticks the planner
     * skips because the vehicle is not yet positioned or is being destroyed.
     * </p>
     * @param time Time; the absolute simulation time of the decision
     * @param planner MirovaTacticalPlanner; the planner, fully configured and with this tick's state settled
     * @param plan SimpleOperationalPlan; what the vehicle will act on -- acceleration, lane-change direction and
     *            indicator intent
     */
    void afterTick(Time time, MirovaTacticalPlanner planner, SimpleOperationalPlan plan);
}
