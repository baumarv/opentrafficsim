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
 * The longitudinal model the driver evaluates against one leader.
 *
 * ## One implementation in v1
 *
 * The core ships **IDM+ only**. Wiedemann 99 stays in OTS: Phase 0.5 confirmed it is alive there —
 * `SimpleHighwayScenario` builds a `Wiedemann99Factory` for cars and for trucks and hands both to the
 * MiRoVA planner factory — but it is an OTS car-following model configured by an OTS scenario, and
 * nothing in the MiRoVA layers depends on which model sits underneath. The interface exists so that
 * a host may substitute one, not because the core needs two.
 *
 * ## The headway factor, and why it is an argument
 *
 * The Java model expressed "follow this leader with a reduced headway" by writing
 * `ParameterTypes.T`, calling the model, and resetting it in a `finally`. That is the last runtime
 * parameter write in the tree, and it is a hidden channel: the caller cannot see it, a concurrent
 * reader would observe the wrong value, and forgetting the reset corrupts every later call.
 *
 * Here the factor is [headwayFactor], an explicit argument. `1.0` is the driver's own desired
 * headway; a value below 1 is the yielding behaviour that used to be a mutation. Nothing in the core
 * writes a parameter at runtime.
 *
 * ## Where relaxation lives
 *
 * **Not here.** Relaxation is a property of the driver's belief about a particular leader — how much
 * headway deficit is still being tolerated after that vehicle cut in — and it is applied by the core
 * *before* this call, by passing the buffered gap in [gap]. The model itself is memoryless and
 * therefore substitutable. See `contract.md` §8 for the four mechanisms (exponential decay of the
 * deficit with τ = 20 s, acceleration damping, the lifetime cap at 3 τ, and the one-second fade-out
 * on abort) and where each is applied.
 */
interface CarFollowingModel {

    /**
     * The acceleration this driver would apply behind the given leader.
     *
     * @param speed the ego's current speed
     * @param desiredSpeed the speed the ego would hold if nothing were in the way
     * @param gap the bumper-to-bumper gap to the leader, already carrying any relaxation buffer the
     *        Belief layer has applied
     * @param leaderSpeed the leader's speed
     * @param parameters this driver's resolved parameters
     * @param headwayFactor factor on the desired time headway for this call; 1.0 for the driver's own
     *        headway, below 1 while yielding
     * @return the acceleration, **signed** — negative to brake. Not clamped to the vehicle's physical
     *         limits, which the core applies once, at the end of the tick. The parameters it reads are
     *         positive magnitudes; the sign is produced here.
     */
    fun followingAcceleration(
        speed: Speed,
        desiredSpeed: Speed,
        gap: Distance,
        leaderSpeed: Speed,
        parameters: DriverParameters,
        headwayFactor: Double = 1.0,
    ): Acceleration

    /**
     * The acceleration this driver would apply with nothing in the way.
     *
     * Separate from [followingAcceleration] because there is no leader to describe, and because a
     * model that saturates its interaction term needs the free term on its own.
     *
     * @param speed the ego's current speed
     * @param desiredSpeed the speed the ego would hold if nothing were in the way
     * @param parameters this driver's resolved parameters
     * @return the free-flow acceleration
     */
    fun freeAcceleration(
        speed: Speed,
        desiredSpeed: Speed,
        parameters: DriverParameters,
    ): Acceleration

    /**
     * The gap this driver would want to keep behind a leader at the given speeds.
     *
     * Used by gap acceptance, which asks what a gap *should* be rather than what acceleration a gap
     * produces. Deriving it by inverting [followingAcceleration] would tie the gap-acceptance logic to
     * one model's algebra.
     *
     * @param speed the ego's speed
     * @param leaderSpeed the leader's speed
     * @param parameters this driver's resolved parameters
     * @param headwayFactor factor on the desired time headway; below 1 when the driver is prepared to
     *        accept less than it would like
     * @return the desired bumper-to-bumper gap
     */
    fun desiredGap(
        speed: Speed,
        leaderSpeed: Speed,
        parameters: DriverParameters,
        headwayFactor: Double = 1.0,
    ): Distance
}
