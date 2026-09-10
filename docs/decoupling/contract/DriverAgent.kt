/*
 * MiRoVA - Migration of Road Vehicle Automation (DFG FOR 5702)
 * Copyright (c) 2026 Marvin Baumann, Karlsruhe Institute of Technology (KIT),
 * Institute for Transport Studies (IfV). All rights reserved.
 *
 * NOTE: Replace this block with the exact KIT/MiRoVA header template.
 */
package edu.kit.ifv.mirova.api

import edu.kit.ifv.units.Acceleration
import kotlin.time.Duration

/**
 * One driver, driving one vehicle, for that vehicle's whole life.
 *
 * The agent holds all the state the model needs across ticks: the belief contexts, the active
 * manoeuvre pattern and its finite-state machine, the relaxation states, the smoothed anticipated
 * speeds. None of that is visible to the host, and none of it is passed in per tick.
 *
 * ## The contract in one paragraph
 *
 * The host calls [step] with the time now and a [WorldView] valid for that instant. The agent
 * returns a [TacticalCommand]: an acceleration to apply, possibly a lane-change request, possibly an
 * indicator change, and how long the host may wait before asking again. The host applies the
 * acceleration, executes any lateral motion itself, and calls back no later than the requested
 * interval. That is the whole loop.
 *
 * ## Threading
 *
 * An agent is **not** thread-safe and must be stepped by one thread at a time. Different agents are
 * independent and may be stepped concurrently, on two conditions the host must meet: its [WorldView]
 * implementation is safe for concurrent reads, and each agent has its own [RandomStream]. The core
 * makes no such requirement of itself and holds no shared mutable state between agents. See
 * `contract.md` §10.
 */
interface DriverAgent {

    /** Identity of the vehicle this agent drives, as the host assigned it. */
    val id: ParticipantId

    /**
     * Decides what to do now.
     *
     * Must be called with a non-decreasing [now]. Calling twice at the same instant with the same
     * world is permitted and returns the same command; the agent treats it as one tick.
     *
     * The [world] is valid only for the duration of this call and the agent retains nothing reachable
     * from it. Any host object handed in may be invalidated as soon as this method returns.
     *
     * @param now the simulation time of this decision, measured from the start of the simulation
     * @param world what the agent may ask about the world at this instant
     * @return what the host should do with the vehicle
     * @throws IllegalArgumentException if [now] precedes the previous call
     */
    fun step(now: Duration, world: WorldView): TacticalCommand

    /**
     * Tells the agent that a lane change it requested has finished, and which way it went.
     *
     * The host executes lateral motion, so only the host knows when the vehicle has arrived. Until
     * this is called the agent believes the movement is still under way and will keep the manoeuvre
     * pattern that requested it alive.
     *
     * @param side the direction the completed change went in
     */
    fun onLaneChangeCompleted(side: Side)

    /**
     * Tells the agent that a lane change it requested will not happen after all — the host aborted
     * it, or refused it.
     *
     * The agent unwinds: the manoeuvre pattern is abandoned and any relaxation opened for a leader on
     * the target lane fades out over one second rather than vanishing, so the acceleration stays
     * continuous.
     *
     * @param side the direction of the abandoned change
     */
    fun onLaneChangeAborted(side: Side)

    /**
     * Releases whatever the agent holds. The agent must not be stepped afterwards.
     *
     * Present because a host with hundreds of thousands of vehicles per run wants the caches gone at
     * a known moment rather than at the garbage collector's convenience.
     */
    fun dispose()
}

/**
 * Creates driver agents.
 *
 * One factory serves a whole population and holds what is shared: the immutable defaults, the
 * distributions to draw individual parameters from, and the random stream to draw them with.
 *
 * **The random stream is the host's.** The core owns the parameters and the distributions but never
 * the entropy: reproducibility is a property of a run, the host owns the run, and a core that seeded
 * itself would make two runs of the same scenario differ. See `contract.md` §7.
 */
interface DriverAgentFactory {

    /**
     * Creates one agent, drawing its individual parameters from the configured distributions.
     *
     * The [RandomStream] the agent draws from is the host's, derived per agent; see [RandomStream].
     *
     * @param id the identity the host has given the vehicle
     * @param overrides parameters fixed for this vehicle instead of drawn — a scenario pinning one
     *        driver's desired speed, or a calibration run pinning all of them
     * @return the new agent
     */
    fun create(id: ParticipantId, overrides: DriverParameters = DriverParameters.EMPTY): DriverAgent
}

/**
 * A source of pseudo-random numbers, supplied by the host, **for one agent**.
 *
 * Deliberately minimal: the core draws parameters at construction and nothing at runtime, so two
 * methods suffice. Implemented by the host over whatever generator gives its runs their
 * reproducibility.
 *
 * **One stream per agent, derived by the host from the run seed and the [ParticipantId].** A single
 * shared stream, however thread-safe, is not reproducible once agents are created or stepped
 * concurrently: the order of draws then depends on scheduling, and two runs of the same scenario with
 * the same seed produce different populations. Thread safety is not the property that matters here;
 * determinism is.
 */
interface RandomStream {

    /**
     * Returns a uniform value in [0, 1).
     *
     * @return the next uniform draw
     */
    fun nextDouble(): Double

    /**
     * Returns a standard normal value, mean 0 and standard deviation 1.
     *
     * @return the next normal draw
     */
    fun nextGaussian(): Double
}

/**
 * What the driver has decided, expressed so that any host can carry it out.
 *
 * Nothing here names a lane, a link, a road or a position. The command is a longitudinal
 * acceleration, an optional request to move sideways, an optional signal, and a deadline — the four
 * things a driver actually produces. **The host executes the lateral motion**; the core only asks for
 * it and is told when it is done.
 *
 * @property acceleration the acceleration to apply until the next decision, **signed** — negative to
 *           brake. Parameters are magnitudes, computed accelerations carry their direction. Already
 *           bounded by the
 *           vehicle's physical limits as the host reported them, so the host need not clamp it —
 *           though a host that must clamp for its own reasons may, and the agent will see the result
 *           next tick through [EgoState.acceleration].
 * @property laneChange a request to begin moving sideways, or `null` for none. Repeating the same
 *           request on consecutive ticks while a change is under way is not a new request; the host
 *           ignores it. A request while a change to the other side is under way is a reversal and the
 *           host should treat the first as aborted.
 * @property indicator the turn indicator to show. Always the full desired state, never a delta, so a
 *           host that loses a command still converges. [IndicatorState.NONE] means switch it off.
 * @property nextEvaluationAfter how long the host may wait before calling [DriverAgent.step] again.
 *           An upper bound, not a schedule: a host that steps at a fixed interval simply steps, and a
 *           host that can schedule events uses this to avoid waking a free-flowing driver as often as
 *           one negotiating a merge. Calling earlier is always allowed; calling later changes the
 *           behaviour.
 */
data class TacticalCommand(
    val acceleration: Acceleration,
    val laneChange: LaneChangeRequest?,
    val indicator: IndicatorState,
    val nextEvaluationAfter: Duration,
)

/**
 * A request to move the vehicle sideways.
 *
 * @property side the direction to move in
 * @property duration how long the movement should take. The core decides this per manoeuvre — an
 *           unhurried change on an empty motorway takes longer than one into a gap that is closing —
 *           and a host that cannot honour a requested duration should take as long as it must and
 *           report completion when it is done.
 */
data class LaneChangeRequest(
    val side: Side,
    val duration: Duration,
)
