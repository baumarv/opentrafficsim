/*
 * MiRoVA - Migration of Road Vehicle Automation (DFG FOR 5702)
 * Copyright (c) 2026 Marvin Baumann, Karlsruhe Institute of Technology (KIT),
 * Institute for Transport Studies (IfV). All rights reserved.
 *
 * NOTE: Replace this block with the exact KIT/MiRoVA header template.
 */
package edu.kit.ifv.mirova.api

import edu.kit.ifv.units.Acceleration
import edu.kit.ifv.units.Distance
import edu.kit.ifv.units.Speed
import kotlin.random.Random
import kotlin.time.Duration

/**
 * Stable identifier of a traffic participant, assigned by the host simulator.
 * Used for tick-level caching and for tracking neighbours across ticks.
 */
@JvmInline
value class ParticipantId(val value: Long)

/** Lateral direction for lane-change requests, indicators and lane-change progress. */
enum class LateralDirection { LEFT, NONE, RIGHT }

/**
 * Simulator-agnostic view of the world for one agent at one tick, provided by the host.
 * Implementations may compute content lazily, but must return identical values
 * for repeated calls within the same tick.
 */
interface PerceptionSnapshot {
    /** Host simulation time at the start of this tick. */
    val time: Duration

    /** Kinematic and vehicle state of the ego vehicle. */
    val ego: EgoState

    /** Static and route-related information within perception range. */
    val infrastructure: InfrastructureState

    /**
     * Neighbours on a lane relative to the ego lane (-1 = right, 0 = own, +1 = left),
     * sorted by ascending absolute net distance. Only observable quantities are included.
     */
    fun neighbors(relativeLane: Int): List<NeighborState>
}

/** Ego state; vehicle limits come from the host, driver preferences from [DriverParameters]. */
data class EgoState(
    val speed: Speed,
    val acceleration: Acceleration,
    val length: Distance,
    val maxAcceleration: Acceleration,
    val maxDeceleration: Acceleration,
    /** Progress of an ongoing lane change executed by the host, or null if none. */
    val laneChange: LaneChangeProgress?,
)

/** Progress of a host-executed lane change; [fraction] runs from 0 to 1. */
data class LaneChangeProgress(val direction: LateralDirection, val fraction: Double)

/** Observable state of a neighbour. No access to the neighbour's internal desires. */
data class NeighborState(
    val id: ParticipantId,
    /** Net gap (bumper to bumper): positive = downstream leader, negative = upstream follower. */
    val netDistance: Distance,
    val speed: Speed,
    val length: Distance,
    val indicator: LateralDirection,
)

/** Infrastructure and route constraints within perception range. */
data class InfrastructureState(
    val speedLimit: Speed,
    val lanesLeft: Int,
    val lanesRight: Int,
    /** Distance to the end of the ego lane, or null if no lane end lies within range. */
    val distanceToLaneEnd: Distance?,
    /** Lane changes required by the route (negative = to the right, positive = to the left). */
    val requiredLaneChanges: Int,
    /** Remaining distance to realise [requiredLaneChanges], or null if none are required. */
    val distanceToRouteDecision: Distance?,
)

/** Result of one tactical step. The host is responsible for executing it. */
data class TacticalCommand(
    val acceleration: Acceleration,
    val laneChangeRequest: LateralDirection,
    /** Desired lane-change duration; only evaluated if [laneChangeRequest] is not NONE. */
    val laneChangeDuration: Duration?,
    val indicator: LateralDirection,
)

/** A single MiRoVA driver. Stateful; exactly one instance per vehicle. */
interface DriverAgent {
    /** Identifier of the vehicle this agent controls. */
    val id: ParticipantId

    /**
     * Executes one pass of the loop (context, cognition, decision, procedure).
     *
     * @param snapshot perception of the current tick
     * @param dt duration until the next call
     * @return the tactical command for this tick
     */
    fun step(snapshot: PerceptionSnapshot, dt: Duration): TacticalCommand

    /**
     * Informs the agent that a lane change executed by the host has ended.
     *
     * @param direction direction of the lane change
     * @param completed true if completed, false if aborted by the host
     */
    fun onLaneChangeFinished(direction: LateralDirection, completed: Boolean)
}

/** Single entry point for host simulators. */
interface DriverModelFactory {
    /**
     * Creates a driver agent.
     *
     * @param id identifier assigned by the host
     * @param parameters driver parameters (already sampled for this driver)
     * @param random random stream owned by the host, for reproducibility across hosts
     * @return a new, stateful driver agent
     */
    fun create(id: ParticipantId, parameters: DriverParameters, random: Random): DriverAgent
}

/**
 * Typed parameter key owned by the MiRoVA core.
 *
 * @param T value type, preferably a kotlin-units type
 */
class ParameterKey<T : Any>(
    val name: String,
    val default: T,
    val description: String,
    val isValid: (T) -> Boolean = { true },
)

/**
 * Immutable driver parameter set. Resolved once into typed fields at agent creation,
 * so no map lookups happen in the per-tick hot path.
 */
class DriverParameters(private val values: Map<ParameterKey<*>, Any>) {
    /**
     * Returns the value for [key], or its default if unset.
     *
     * @param key parameter key
     * @return the configured or default value
     */
    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> get(key: ParameterKey<T>): T {
        val value = (values[key] ?: key.default) as T
        require(key.isValid(value)) { "Invalid value for parameter '${key.name}': $value" }
        return value
    }
}
