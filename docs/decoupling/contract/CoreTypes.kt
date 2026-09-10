/*
 * MiRoVA - Migration of Road Vehicle Automation (DFG FOR 5702)
 * Copyright (c) 2026 Marvin Baumann, Karlsruhe Institute of Technology (KIT),
 * Institute for Transport Studies (IfV). All rights reserved.
 *
 * NOTE: Replace this block with the exact KIT/MiRoVA header template.
 */
package edu.kit.ifv.mirova.api

/**
 * Stable identifier of a traffic participant, assigned by the host simulator.
 *
 * Must be stable for the lifetime of the participant and must not be reused while any agent can
 * still perceive it: the core keys per-tick caches, relaxation states and remembered observations by
 * this value, and a reused identifier would silently transfer one vehicle's history to another.
 */
@JvmInline
value class ParticipantId(val value: Long)

/**
 * A lane, named relative to the one the ego occupies.
 *
 * The core never learns a road graph and never holds a host lane object. Everything it asks about
 * infrastructure is asked about one of these three.
 */
enum class RelativeLane {
    /** The lane the ego is on. */
    CURRENT,

    /** The adjacent lane to the left in the direction of travel. */
    LEFT,

    /** The adjacent lane to the right in the direction of travel. */
    RIGHT,
}

/**
 * A lateral direction, for lane-change requests, indicators and adjacent-lane queries.
 */
enum class Side {
    /** To the left in the direction of travel. */
    LEFT,

    /** To the right in the direction of travel. */
    RIGHT;

    /** The lane on this side of the ego. */
    val lane: RelativeLane
        get() = if (this == LEFT) RelativeLane.LEFT else RelativeLane.RIGHT

    /** The opposite side. */
    val opposite: Side
        get() = if (this == LEFT) RIGHT else LEFT
}

/**
 * The state of a turn indicator, as an observable signal and as a command.
 */
enum class IndicatorState {
    /** No indicator. */
    NONE,

    /** Indicating left. */
    LEFT,

    /** Indicating right. */
    RIGHT,
}

/**
 * The answer to a query that may have no answer, distinguishing two different reasons.
 *
 * The distinction is the point. Today's Java model spells both "no leader on the left lane" and "the
 * computation failed" as `null` or `POSITIVE_INFINITY`, so a modelling decision and a modelling
 * failure are indistinguishable at the point of use. Here they are separate cases and the core must
 * handle each explicitly.
 *
 * This type lives at the port boundary only. Inside the core an absent distance is a named sentinel
 * (`Distance.ABSENT`), resolved once in the Belief layer, so the hot path neither allocates nor
 * unwraps. See `contract.md` §8.
 *
 * @param T the type of the value when there is one
 */
sealed class Observation<out T : Any> {

    /** The query has an answer. */
    data class Measured<out T : Any>(
        /** The observed value. */
        val value: T,
    ) : Observation<T>()

    /**
     * There is genuinely nothing to report: no leader on that lane, no lane end within range, no
     * speed limit change ahead. A normal, expected outcome that the core must model.
     */
    object Absent : Observation<Nothing>()

    /**
     * The host cannot answer this query at all — not "nothing is there" but "I do not know".
     *
     * A driving simulator that models only near-field traffic answers [Unknown] to a query about the
     * mean speed of a lane a kilometre downstream. The core must degrade to a defined fallback and
     * must never treat this as [Absent].
     */
    object Unknown : Observation<Nothing>()

    /**
     * Returns the value, or `null` for [Absent] and [Unknown].
     *
     * Convenience for call sites that genuinely treat both the same way. Prefer an exhaustive `when`
     * where they differ, which is most places.
     *
     * @return the measured value, or `null`
     */
    fun valueOrNull(): T? = (this as? Measured)?.value
}
