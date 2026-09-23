package org.opentrafficsim.road.gtu.lane.tactical;

import org.djunits.value.vdouble.scalar.Acceleration;

/**
 * What a tactical planner exposes to the trajectory sampler about its own decision state.
 * <p>
 * The sampler's extended columns used to ask {@code instanceof MirovaTacticalPlanner} and fall back to
 * {@code "none"} and {@code NaN} for anything else. That is invisible in the output: a run on another driver model
 * produces a file of the right size, with the right columns, in which five of them are empty on every row. It was
 * found on a campaign pilot, not in a test, and only because two runs of the same seed differed in file size.
 * </p>
 * <p>
 * This interface is what the columns ask instead. A planner that can answer implements it; one that cannot is
 * recorded as empty, as before, but now by its own choice rather than by the sampler's ignorance of it.
 * </p>
 * <h3>What an implementation owes the caller</h3>
 * <p>
 * Every method is called once per sampled vehicle per sampling interval, on the simulation thread, and must be
 * <b>free of side effects</b>: it reports what the planner already decided and must not compute, cache, schedule or
 * advance anything. A sampler that changes the run it samples is not a sampler.
 * </p>
 * <p>
 * Returning "unknown" is always allowed and is the honest answer where the concept does not apply: {@code NaN} for
 * the numeric quantities, {@code null} for the acceleration, {@link #NO_ACTION_STATE} for the state. An
 * implementation must not substitute a plausible number for a quantity it does not have.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public interface DriverStateObservable
{
    /** What {@link #actionStateName()} returns when no state is active. Written by the sampler as-is. */
    String NO_ACTION_STATE = "none";

    /**
     * Name of the manoeuvre state the planner is currently executing.
     * <p>
     * The vocabulary is the implementation's own and is <b>not</b> translated here. Two driver models that use
     * different names must publish a mapping between them rather than quietly agreeing on one set, because a rename
     * inside an adapter cannot be undone by a reader of the output afterwards.
     * </p>
     * @return String; the state name, or {@link #NO_ACTION_STATE}; never null
     */
    String actionStateName();

    /**
     * Desire to change lane to the left, on the model's own scale.
     * @return double; the desire, or {@code NaN} when the planner does not compute one
     */
    double laneChangeDesireLeft();

    /**
     * Desire to change lane to the right, on the model's own scale.
     * @return double; the desire, or {@code NaN} when the planner does not compute one
     */
    double laneChangeDesireRight();

    /**
     * Factor by which positive acceleration is currently scaled while a relaxation is active.
     * @return double; 1.0 when nothing is damped, or {@code NaN} when the planner has no such mechanism
     */
    double accelerationDampingFactor();

    /**
     * The acceleration the car-following model alone asks for, before anything the planner does to it.
     * <p>
     * Recorded next to the acceleration actually executed: their difference is what patterns, arbitration and clamps
     * take away, and attributing that by component is the only way to answer by measurement why queued vehicles
     * accelerate far below what the car-following model alone would give.
     * </p>
     * @return Acceleration; the car-following acceleration, or null when it is not available this tick
     */
    Acceleration carFollowingAcceleration();
}
