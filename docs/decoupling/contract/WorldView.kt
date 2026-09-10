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

/**
 * Everything one agent may ask the host about the world, for the duration of one tick.
 *
 * ## What belongs here and what does not
 *
 * The host answers **geometric and topological questions only**. Anything that interprets, caches
 * over time, or decides stays in the core: the belief contexts, the per-tick lazy caching, the
 * relaxation, the reclassification of a leader after a cut-in. A host that computes a desire or a
 * headway is doing the core's job.
 *
 * Everything visible through this port is something a human driver could observe: relative position,
 * net gap, speed, length, indicator, and a stable identity to recognise a vehicle by. There is
 * deliberately no access to another agent's parameters, car-following model, desires or active
 * manoeuvre. The Java model read all three of those, and each is now either estimated in the core or
 * gone; see `contract.md` §3.
 *
 * ## Tick consistency
 *
 * A `WorldView` is valid for exactly one tick. Within that tick every query must return the same
 * answer however often it is called, so that two layers asking the same question cannot disagree.
 * Implementations are free — and encouraged — to compute lazily and memoise, which is what the OTS
 * perception already does.
 *
 * **The core never retains a `WorldView`, nor anything reachable from one, beyond the call it was
 * given in.** [PerceivedVehicle] instances are values and may be read during the tick, but only
 * [ParticipantId] may be remembered across ticks.
 *
 * ## Ranges
 *
 * Every query that looks along the road takes its range as an argument rather than reading it from a
 * parameter. That is a direct consequence of Phase 0.5: the Java model raised the `LOOKAHEAD`
 * parameter around one perception call to widen it, and because the perception memoises per tick,
 * the widening never once took effect — 100.0000 % of 136.5 million calls were answered from the
 * narrower memo. A range that is an argument cannot fail that way.
 */
interface WorldView {

    // -----------------------------------------------------------------------------------------
    // Ego
    // -----------------------------------------------------------------------------------------

    /** The ego vehicle's own state and physical limits. */
    val ego: EgoState

    // -----------------------------------------------------------------------------------------
    // Neighbours
    // -----------------------------------------------------------------------------------------

    /**
     * The vehicles ahead on the given lane, nearest first, within [range].
     *
     * Lazy: the sequence may compute each element on demand, and callers are expected to stop early —
     * most take one, longitudinal control takes two. A host must not materialise the whole
     * perception range to answer this.
     *
     * **Provisional shape.** A `Sequence` allocates at the port boundary, once per lane per tick per
     * vehicle. An index-based `leader(lane, index, range)` would not. Which is right depends on a
     * measurement that does not exist yet; see `contract.md` §2.
     *
     * @param lane the lane to look along
     * @param range how far ahead to look
     * @return the leaders, nearest first; empty when there are none
     */
    fun leaders(lane: RelativeLane, range: Distance): Sequence<PerceivedVehicle>

    /**
     * The vehicles behind on the given lane, nearest first, within [range].
     *
     * @param lane the lane to look along
     * @param range how far back to look
     * @return the followers, nearest first; empty when there are none
     */
    fun followers(lane: RelativeLane, range: Distance): Sequence<PerceivedVehicle>

    // -----------------------------------------------------------------------------------------
    // Infrastructure
    // -----------------------------------------------------------------------------------------

    /**
     * Whether the given lane exists in the current cross-section.
     *
     * @param lane the lane to test
     * @return true when the lane is there at all
     */
    fun laneExists(lane: RelativeLane): Boolean

    /**
     * Whether the ego is permitted and physically able to use the lane on the given side.
     *
     * Folds together what the Java model asked in three separate ways: that the lane is not a
     * shoulder, that its type admits this vehicle class, and that a lane change to it is legal here.
     *
     * @param side the side to test
     * @return true when the ego may drive there
     */
    fun laneIsUsable(side: Side): Boolean

    /**
     * How much further the ego may still change into the lane on the given side.
     *
     * @param side the side to test
     * @param range how far ahead to look
     * @return the remaining distance in which the change is legal; [Observation.Absent] when a change
     *         to that side is not legal here at all
     */
    fun laneChangePossibility(side: Side, range: Distance): Observation<Distance>

    /**
     * What the route demands of the given lane: how many lane changes are still required from it,
     * and how much road is left to make them in.
     *
     * This is the route-following signal the mandatory lane-change desire is built on.
     *
     * **Every host must answer this.** [Observation.Unknown] is not permitted: a driver who does not
     * know where they are going has no mandatory lane-change desire at all, and hiding that behind a
     * fallback would be a large behavioural difference expressed as a missing value. A host with no
     * route model answers [Observation.Absent] — "the route asks nothing of this lane" — which is
     * honest about what the driver then does.
     *
     * @param lane the lane to ask about
     * @param range how far ahead to look
     * @return the requirement, or [Observation.Absent] when the route demands nothing from this lane
     *         within [range], including because the host models no route at all
     */
    fun routeRequirement(lane: RelativeLane, range: Distance): Observation<RouteRequirement>

    /**
     * The distance to the point where the given lane physically ends.
     *
     * Distinct from [routeRequirement]: this is a dead end, not a route decision.
     *
     * @param lane the lane to ask about
     * @param range how far ahead to look
     * @return the distance, or [Observation.Absent] when the lane does not end within [range]
     */
    fun distanceToLaneEnd(lane: RelativeLane, range: Distance): Observation<Distance>

    /**
     * The legal speed limits along the given lane, nearest first, each with the distance at which it
     * begins; the first entry at zero distance is the limit in force now.
     *
     * Reduced in Phase 0.5 from a full OTS "speed limit prospect". The only consumer of the richer
     * form was `SpeedLimitUtil.considerSpeedLimitTransitions`, which reacts solely to curvature and
     * speed-bump limit types — and nothing in OTS ever populates either, so it returned "no
     * constraint" on every one of 141 million evaluations. Curvature and speed bumps are therefore
     * not in this contract. Anticipation of an upcoming lower limit is done in the core from this
     * list, because a driving-simulator scenario will contain limit changes even where Freiburg-Nord
     * does not.
     *
     * @param lane the lane to look along
     * @param range how far ahead to look
     * @return the limits, nearest first; never empty — the limit in force now is always present
     */
    fun speedLimits(lane: RelativeLane, range: Distance): Sequence<SpeedLimitAhead>

    /**
     * Whether a second lane on the given side ends within [range] while the first continues.
     *
     * A derived answer rather than raw topology: the core must not learn a road graph. It is what the
     * dead-end incentive needs in order to tell "the lane beside me is merging in" from "the lane
     * beside me continues".
     *
     * @param side the side to test
     * @param range how far ahead to look
     * @return true when such a merge lies ahead
     */
    fun parallelMergeAhead(side: Side, range: Distance): Boolean

    // -----------------------------------------------------------------------------------------
    // Traffic beyond the individual neighbours
    // -----------------------------------------------------------------------------------------

    /**
     * The mean speed of traffic on the given lane within [range].
     *
     * **A host that cannot answer must return [Observation.Unknown], not a guess.**
     *
     * Provisional. The core already computes the same quantity from the leaders it perceives
     * (`InfrastructureContext.getAnticipatedSpeed`), and BC-5 offers exactly that substitution. If
     * BC-5 is adopted this query leaves the contract, and with it the last place where a host supplies
     * an aggregate the core could derive itself. It is retained in v1 only because BC-5 has not yet
     * been compared against the golden reference.
     *
     * @param lane the lane to ask about
     * @param range how far ahead to aggregate
     * @return the mean speed, [Observation.Absent] when the lane is empty, or [Observation.Unknown]
     */
    fun meanSpeed(lane: RelativeLane, range: Distance): Observation<Speed>

    /**
     * The speed of the traffic the ego would be joining if it merged to the given side, within
     * [range] — including the case where that lane is not yet alongside but lies ahead on the path.
     *
     * This replaces the Java model's lane-segment scan, which read the position and speed of every
     * vehicle on a lane found up to a kilometre downstream. That is not something a driver can see,
     * and not something a near-field host can answer. Bounding it by the ego's own perception range
     * is decision BC-6.
     *
     * **[Observation.Unknown] is an expected answer**, not an error: a host with no notion of a
     * downstream merge lane says so, and the core falls back to the speed limit, capped at its own
     * desired speed. That fallback already exists in the merge reference cascade.
     *
     * @param side the side the ego would merge towards
     * @param range how far ahead to look for that traffic
     * @return the speed to synchronise with, [Observation.Absent] when the lane is visible but empty,
     *         or [Observation.Unknown] when the host cannot see that far
     */
    fun expectedMergeSpeed(side: Side, range: Distance): Observation<Speed>
}

/**
 * The ego vehicle's own state, and the physical limits the host imposes on it.
 *
 * Driver preferences — desired headway, desired speed factor, thresholds — are **not** here. They
 * are driver parameters owned by the core. What the host supplies is what belongs to the vehicle
 * rather than to the person in it.
 */
interface EgoState {

    /** Current speed. */
    val speed: Speed

    /** Current acceleration, as executed in the previous tick. */
    val acceleration: Acceleration

    /** Vehicle length, used for gap arithmetic. */
    val length: Distance

    /** The highest speed this vehicle can reach, irrespective of what the driver wants. */
    val maxSpeed: Speed

    /**
     * The strongest deceleration the vehicle can produce, as a **positive magnitude**.
     *
     * A physical limit, not the driver's comfort threshold — those are parameters. It follows the
     * parameter convention rather than the acceleration convention because it is a bound the vehicle
     * brings, not a value the model computed: magnitudes for what is brought, signs for what is
     * computed. See `contract.md` §7.
     */
    val maxDeceleration: Acceleration

    /**
     * The strongest acceleration the vehicle can produce **at its current speed**.
     *
     * Speed-dependent by nature, which is why it is a query on the state rather than a constant: the
     * Java model carries a piece-wise linear curve falling from 3.5 m/s² at standstill to zero at
     * 250 km/h. That curve is a property of the vehicle and belongs to the host.
     *
     * @param at the speed to evaluate the limit at
     * @return the strongest acceleration available at that speed
     */
    fun maxAccelerationAt(at: Speed): Acceleration

    /**
     * The lane change the host is currently executing for this vehicle, if any.
     *
     * The core decides that a lane change should start, and how long it should take; the host
     * executes the geometry and reports progress back here. The core never learns which lane it is
     * on — only whether a movement is under way and how far along it is.
     */
    val laneChange: LaneChangeProgress?
}

/**
 * Progress of a lane change the host is executing.
 *
 * @property side the direction of the movement
 * @property fraction how far the movement has progressed, from 0 at the start to 1 at completion
 */
data class LaneChangeProgress(
    val side: Side,
    val fraction: Double,
)

/**
 * One perceived vehicle, carrying only what a driver could observe about it.
 *
 * Deliberately absent, and each for a reason established in Phase 0:
 *
 * - **its parameters** — the Java model read the front leader's own parameter set to judge whether it
 *   could cooperate. Replaced by BC-2, which uses the ego's own.
 * - **its car-following model** — read at the same site, for the same purpose.
 * - **its desired speed** — read by the social interaction incentive. Replaced by BC-4, which
 *   estimates it from the speed the vehicle was last seen holding while unobstructed.
 *
 * @property id stable identity, so the core can recognise this vehicle again next tick
 * @property netGap bumper-to-bumper gap. Positive ahead of the ego, negative when the ego has drawn
 *           level with or past this vehicle's reference point — which is how the merge logic detects
 *           a vehicle alongside, so the sign carries meaning and must not be clamped.
 * @property speed its speed
 * @property acceleration its acceleration, signed
 * @property length its length
 * @property indicator its turn indicator, the one genuinely inter-agent signal in the model and the
 *           trigger for cooperative gap opening
 * @property isAlongside whether it overlaps the ego longitudinally
 */
data class PerceivedVehicle(
    val id: ParticipantId,
    val netGap: Distance,
    val speed: Speed,
    val acceleration: Acceleration,
    val length: Distance,
    val indicator: IndicatorState,
    val isAlongside: Boolean,
)

/**
 * What the route still demands of a lane.
 *
 * @property laneChangesRequired how many lane changes are still needed from this lane to stay on the
 *           route; zero means the lane is on the route
 * @property remainingDistance how much road is left in which to make them
 * @property deadEnd whether failing to make them ends in a dead end rather than a wrong turn
 */
data class RouteRequirement(
    val laneChangesRequired: Int,
    val remainingDistance: Distance,
    val deadEnd: Boolean,
)

/**
 * A legal speed limit and where it begins.
 *
 * @property distance how far ahead it takes effect; zero for the limit in force now
 * @property limit the legal limit itself
 * @property vehicleClassLimit the limit that applies to this vehicle class, where one is lower —
 *           a lorry limit, for instance. Equal to [limit] when there is none.
 */
data class SpeedLimitAhead(
    val distance: Distance,
    val limit: Speed,
    val vehicleClassLimit: Speed,
)
