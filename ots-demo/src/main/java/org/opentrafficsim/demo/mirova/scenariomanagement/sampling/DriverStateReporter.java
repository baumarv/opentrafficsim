package org.opentrafficsim.demo.mirova.scenariomanagement.sampling;

import org.djunits.value.vdouble.scalar.Acceleration;

/**
 * What a tactical planner may report to the trajectory sampler about its own decision state.
 * <p>
 * The extended trajectory columns of a scenario ask this interface and nothing else, so they depend on no driver model. A
 * planner that can answer implements it; a planner that does not is recorded with the "unknown" values named below, and the
 * trajectory file keeps the same columns either way. The upstream LMRS does not implement it.
 * </p>
 * <p>
 * Every method is called once per sampled vehicle per sampling interval, on the simulation thread, and must be free of side
 * effects: it reports what the planner already decided and must not compute, cache, schedule or advance anything.
 * </p>
 * <p>
 * Returning "unknown" is always allowed and is the honest answer where a concept does not apply: {@code NaN} for the numeric
 * quantities, {@code null} for the acceleration, {@link #NO_STATE} for the state. An implementation must not substitute a
 * plausible number for a quantity it does not have.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public interface DriverStateReporter
{
    /** What {@link #manoeuvreStateName()} returns when no state is active; also written for planners that report nothing. */
    String NO_STATE = "none";

    /**
     * Returns the name of the manoeuvre state the planner is currently executing.
     * <p>
     * The vocabulary is the implementation's own and is not translated here. Two driver models that use different names
     * must publish a mapping between them, because a rename inside an adapter cannot be undone by a reader of the output.
     * </p>
     * @return String; the state name, or {@link #NO_STATE}; never null
     */
    String manoeuvreStateName();

    /**
     * Returns the desire to change lane to the left, on the model's own scale.
     * @return double; the desire, or {@code NaN} when the planner does not compute one
     */
    double laneChangeDesireLeft();

    /**
     * Returns the desire to change lane to the right, on the model's own scale.
     * @return double; the desire, or {@code NaN} when the planner does not compute one
     */
    double laneChangeDesireRight();

    /**
     * Returns the factor by which the planner currently scales positive acceleration.
     * @return double; 1.0 when nothing is scaled, or {@code NaN} when the planner has no such mechanism
     */
    double accelerationScalingFactor();

    /**
     * Returns the acceleration the car-following model alone asks for, before anything else the planner does to it.
     * @return Acceleration; the car-following acceleration, or null when it is not available this tick
     */
    Acceleration carFollowingAcceleration();
}
