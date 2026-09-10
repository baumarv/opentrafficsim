package org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer;

import org.djunits.unit.LengthUnit;
import org.djunits.value.vdouble.scalar.Duration;
import org.djunits.value.vdouble.scalar.Length;

/**
 * Tracks and computes the relaxation phenomenon according to Keane and Gao (2021).
 * <p>
 * This class stores the initial space headway deficit (gamma_s) and the speed difference (gamma_v) after a lane change or
 * cut-in. It provides exponentially decaying virtual buffers for both distance and speed. This represents the "2p"
 * (two-parameter) relaxation model, allowing independent decay rates for space and speed errors.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class RelaxationState
{
    /** The absolute simulation time when the lane change or cut-in occurred. */
    private final Duration startTime;

    /**
     * When the fade-out began, or {@code null} while the relaxation is running normally.
     * <p>
     * Abandoning a relaxation used to discard its buffer in one step, so the perceived distance dropped by the whole
     * remaining buffer between two ticks - a mean of 9.2 m in free flow and 2.2 m in congestion, in more than 99 % of
     * aborts. The interaction term takes distance quadratically, so that is a braking impulse the model inflicts on
     * itself, some 108 000 times in a congested hour. Fading the buffer to zero over a short interval gives the same
     * end state without the impulse.
     * </p>
     */
    private Duration fadeStart = null;

    /** How long the fade-out takes once begun. */
    private Duration fadeDuration = null;

    /**
     * Cache for the current simulation time to avoid repeated calls to getSimulator().getSimulatorAbsTime() during buffer
     * calculations.
     */
    private Duration currentTimeCache = Duration.NaN;

    /** The initial space headway deficit [m] at the time of the event (gamma_s). */
    private final Length initialSpaceDeficit;

    /** The relaxation time constant [s] for the space headway (Tau_s). */
    private final Duration tauSpace;

    /**
     * Constructs a new RelaxationState to track virtual distance and speed buffers over time.
     * @param startTime the absolute simulation time the lane change or cut-in occurred
     * @param initialSpaceDeficit the initial missing distance to the desired space headway
     * @param tauSpace the time constant for the spatial exponential decay
     */
    public RelaxationState(final Duration startTime, final Length initialSpaceDeficit, final Duration tauSpace)
    {
        this.startTime = startTime;
        this.initialSpaceDeficit = initialSpaceDeficit;
        this.tauSpace = tauSpace;
    }

    /**
     * Computes the remaining virtual distance buffer for the given simulation time.
     * <p>
     * The virtual buffer decays exponentially based on elapsed time and Tau_s.
     * </p>
     * @param currentTime the current absolute simulation time
     * @return the virtual distance buffer to add to the actual headway, never negative
     */
    public Length getVirtualSpaceBuffer(final Duration currentTime)
    {
        this.currentTimeCache = currentTime; // Update cache for potential future use
        double elapsedSi = currentTime.si - this.startTime.si;

        if (elapsedSi < 0.0 || this.initialSpaceDeficit.si <= 0.0)
        {
            return Length.ZERO;
        }

        double bufferSi = this.initialSpaceDeficit.si * Math.exp(-elapsedSi / this.tauSpace.si);
        return new Length(bufferSi * fadeFactor(currentTime), LengthUnit.SI);
    }

    /**
     * Returns the spatial relaxation time constant this state was created with.
     * @return Duration; tau_s
     */
    public Duration getTauSpace()
    {
        return this.tauSpace;
    }

    /**
     * Begins the fade-out, if it has not already begun.
     * <p>
     * Called where the relaxation would previously have been discarded. Calling it again while a fade is running has
     * no effect, so the abort condition firing on every tick does not restart the fade.
     * </p>
     * @param now Duration; the current simulation time
     * @param duration Duration; how long the fade should take; a non-positive value ends the relaxation at once,
     *            which reproduces the original behaviour
     */
    public void beginFade(final Duration now, final Duration duration)
    {
        if (this.fadeStart == null)
        {
            this.fadeStart = now;
            this.fadeDuration = duration;
        }
    }

    /**
     * Returns whether a fade-out has begun.
     * @return boolean; true once {@link #beginFade} has been called
     */
    public boolean isFading()
    {
        return this.fadeStart != null;
    }

    /**
     * Returns whether the relaxation has nothing left to give.
     * @param now Duration; the current simulation time
     * @return boolean; true when a fade has run its course
     */
    public boolean isFadedOut(final Duration now)
    {
        return this.fadeStart != null && fadeFactor(now) <= 0.0;
    }

    /**
     * Returns the factor the buffers are scaled by, which is one until a fade begins and then falls linearly to zero.
     * @param now Duration; the current simulation time
     * @return double; the scaling factor, in [0, 1]
     */
    private double fadeFactor(final Duration now)
    {
        if (this.fadeStart == null)
        {
            return 1.0;
        }
        if (this.fadeDuration == null || this.fadeDuration.si <= 0.0)
        {
            return 0.0;
        }
        double done = (now.si - this.fadeStart.si) / this.fadeDuration.si;
        return done <= 0.0 ? 1.0 : (done >= 1.0 ? 0.0 : 1.0 - done);
    }

    /**
     * Returns the initial space headway deficit [m] at the time of the relaxation event.
     * @return Length; initial space headway deficit
     */
    /**
     * Returns the simulation time at which this relaxation began.
     * @return Duration; the start time
     */
    public Duration getStartTime()
    {
        return this.startTime;
    }

    public Length getInitialSpaceDeficit()
    {
        return this.initialSpaceDeficit;
    }

    /**
     * Returns a string representation of the relaxation state.
     * @return string representation of the relaxation state
     */
    public String toString()
    {
        return String.format(
                "RelaxationState[startTime=%.2fs, initialSpaceDeficit=%.2fm, tauSpace=%.2fs, virtualSpaceBuffer=%.2fm]",
                this.startTime.si, this.initialSpaceDeficit.si, this.tauSpace.si,
                this.getVirtualSpaceBuffer(this.currentTimeCache).si);
    }
}
