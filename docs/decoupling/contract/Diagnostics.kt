/*
 * MiRoVA - Migration of Road Vehicle Automation (DFG FOR 5702)
 * Copyright (c) 2026 Marvin Baumann, Karlsruhe Institute of Technology (KIT),
 * Institute for Transport Studies (IfV). All rights reserved.
 *
 * NOTE: Replace this block with the exact KIT/MiRoVA header template.
 */
package edu.kit.ifv.mirova.api

import kotlin.time.Duration

/**
 * Where the core reports what it decided and why.
 *
 * ## Why this is a port and not a logger
 *
 * The core writes to no file, opens no stream and knows no logging framework. What it produces is a
 * stream of typed events; whether they become a CSV, a trace in a driving simulator's own recorder,
 * or nothing at all is the host's decision. `DefectDiagnostics` in the Java model — counters behind a
 * system property, a CSV path in another, a shutdown hook to write the report — is exactly the
 * arrangement this replaces: three host concerns compiled into the model.
 *
 * ## Cost when nobody is listening
 *
 * [NONE] is the default and every method on it is empty, so a JIT that has seen only that
 * implementation removes the call. Anything expensive to build must therefore be built **inside** the
 * implementation, not at the call site — which is why the events are simple values and why
 * [isEnabled] exists for the rare case where the core would otherwise assemble something costly.
 */
interface Diagnostics {

    /**
     * Whether anything is listening.
     *
     * The core checks this only before work it would not otherwise do. It must never change what the
     * model computes — a run with diagnostics on and one with them off must produce identical
     * trajectories.
     */
    val isEnabled: Boolean

    /**
     * Reports one decision event.
     *
     * @param event what happened
     */
    fun record(event: DiagnosticEvent)

    companion object {
        /** Discards everything. The default. */
        @JvmField
        val NONE: Diagnostics = object : Diagnostics {
            override val isEnabled: Boolean get() = false
            override fun record(event: DiagnosticEvent) = Unit
        }
    }
}

/**
 * Something the core decided, did, or declined to do.
 *
 * The set is closed, so a host can exhaust it in a `when` and know it has handled everything. Every
 * event carries the agent and the time, because a host aggregating across a population has neither
 * otherwise.
 */
sealed class DiagnosticEvent {

    /** The agent the event concerns. */
    abstract val agent: ParticipantId

    /** When it happened. */
    abstract val time: Duration

    /**
     * The manoeuvre pattern in charge changed.
     *
     * @property agent the agent
     * @property time when
     * @property from the pattern that was active, or `null` at the first selection
     * @property to the pattern now active
     * @property reason why the arbitrator preferred it, in one short phrase
     */
    data class PatternSwitched(
        override val agent: ParticipantId,
        override val time: Duration,
        val from: String?,
        val to: String,
        val reason: String,
    ) : DiagnosticEvent()

    /**
     * The active pattern's finite-state machine moved.
     *
     * @property agent the agent
     * @property time when
     * @property pattern the pattern
     * @property from the state left
     * @property to the state entered
     */
    data class StateChanged(
        override val agent: ParticipantId,
        override val time: Duration,
        val pattern: String,
        val from: String,
        val to: String,
    ) : DiagnosticEvent()

    /**
     * A lane change was requested, refused by gap acceptance, completed or aborted.
     *
     * @property agent the agent
     * @property time when
     * @property side the direction
     * @property outcome what happened to it
     * @property desire the desire that produced it, on the same scale as the `dFree`/`dMand`
     *           thresholds
     */
    data class LaneChange(
        override val agent: ParticipantId,
        override val time: Duration,
        val side: Side,
        val outcome: LaneChangeOutcome,
        val desire: Double,
    ) : DiagnosticEvent()

    /**
     * A relaxation opened, decayed to nothing, hit its lifetime cap, or was abandoned.
     *
     * The four mechanisms of the relaxation are not separately observable in the Java model, which is
     * why the calibration of τ = 20 s cannot be traced back through the published runs.
     *
     * @property agent the agent
     * @property time when
     * @property leader the leader the relaxation concerns
     * @property phase what happened to it
     * @property deficit the headway deficit still being tolerated, in metres
     */
    data class Relaxation(
        override val agent: ParticipantId,
        override val time: Duration,
        val leader: ParticipantId,
        val phase: RelaxationPhase,
        val deficit: Double,
    ) : DiagnosticEvent()

    /**
     * The host answered [Observation.Unknown] where the core wanted a value, and a fallback was used.
     *
     * The one event a host should watch during integration: a near-field host will produce these
     * legitimately, but a stream of them from a host that ought to know the answer means the adapter
     * is not wired up.
     *
     * @property agent the agent
     * @property time when
     * @property query the query that could not be answered
     * @property fallback what the core used instead, described in one phrase
     */
    data class UnknownAnswered(
        override val agent: ParticipantId,
        override val time: Duration,
        val query: String,
        val fallback: String,
    ) : DiagnosticEvent()

    /**
     * The host called back later than the agent asked for.
     *
     * Not a failure — a host stepping at a fixed interval will produce these whenever a manoeuvre asks
     * for a shorter one — but it is a silent source of behavioural drift, because every time constant
     * in the model is evaluated on the interval that actually elapsed. This makes the drift visible.
     *
     * @property agent the agent
     * @property time when
     * @property requested the bound the previous [TacticalCommand] asked for
     * @property measured the interval that actually elapsed
     */
    data class EvaluationLate(
        override val agent: ParticipantId,
        override val time: Duration,
        val requested: Duration,
        val measured: Duration,
    ) : DiagnosticEvent()

    /**
     * The core clamped its own output, or found itself in a situation it can describe but not resolve
     * — no acceptable gap at the end of a ramp, a required deceleration beyond `bMax`.
     *
     * Not an error: the model has a defined answer in each case. It is the counter that told Phase 0.5
     * which situations actually occur, and how often.
     *
     * @property agent the agent
     * @property time when
     * @property kind what was hit
     * @property detail a short phrase naming the site
     */
    data class LimitReached(
        override val agent: ParticipantId,
        override val time: Duration,
        val kind: LimitKind,
        val detail: String,
    ) : DiagnosticEvent()
}

/** What became of a lane change. */
enum class LaneChangeOutcome {
    /** Requested of the host. */
    REQUESTED,

    /** Wanted, but no acceptable gap. */
    GAP_REJECTED,

    /** The host reported it finished. */
    COMPLETED,

    /** The host reported it abandoned, or the core withdrew it. */
    ABORTED,
}

/** What became of a relaxation. */
enum class RelaxationPhase {
    /** A cut-in was detected and a deficit opened. */
    OPENED,

    /** The deficit decayed to nothing. */
    DECAYED,

    /** The lifetime cap of three time constants was reached. */
    EXPIRED,

    /** Abandoned because the required deceleration made it no longer credible; fades over one second. */
    ABORTED,
}

/** A bound the core ran into. */
enum class LimitKind {
    /** The commanded acceleration was clamped to what the vehicle can do. */
    VEHICLE_ACCELERATION,

    /** The commanded deceleration was clamped to what the driver will apply. */
    DRIVER_DECELERATION,

    /** A gap was required that no neighbour offered. */
    NO_ACCEPTABLE_GAP,

    /** A route lane change is still required and the road to make it in has run out. */
    ROUTE_DEADLINE_MISSED,
}
