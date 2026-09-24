package org.opentrafficsim.demo.mirova.scenariomanagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.djunits.value.vdouble.scalar.Speed;
import org.junit.jupiter.api.Test;
import org.opentrafficsim.demo.mirova.scenariomanagement.libraries.DesiredSpeedLibrary;

import nl.tudelft.simulation.jstats.streams.MersenneTwister;
import nl.tudelft.simulation.jstats.streams.StreamInterface;

/**
 * The desired-speed shift moves every draw by exactly the stated amount, and zero changes nothing.
 * <p>
 * Both halves matter. A study that sets the shift and gets the unshifted distribution would report the
 * value it asked for beside numbers from a driver population nobody moved — and the campaign's whole
 * question is whether moving it does anything, so that failure would answer it wrongly rather than
 * loudly. And a default that does not reproduce the measured distribution bit for bit would silently
 * change every run that does not mention the shift, which is all of them so far.
 * </p>
 * <p>
 * Driven through the same random stream on both sides: the distribution is an inverse-CDF draw, so with
 * one stream and one sequence of uniforms a constant shift of the support points must appear as the same
 * constant on every single draw. That is an exact equality, not a distributional claim, and it needs no
 * sample size to be convincing.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class DesiredSpeedShiftTest
{
    /** Draws compared; enough to cross every segment of the interpolated distribution. */
    private static final int DRAWS = 2000;

    /** The seed both streams are built with, so the two draw the same uniforms. */
    private static final long SEED = 20260924L;

    /** Tolerance on a speed comparison [km/h]; the arithmetic is a sum, so this is generous. */
    private static final double TOLERANCE = 1e-9;

    /**
     * Draws a fixed sequence from the car distribution at a given shift.
     * @param shiftKmh double; the shift [km/h]
     * @return double[]; the draws, in km/h
     */
    private static double[] draws(final double shiftKmh)
    {
        StreamInterface stream = new MersenneTwister(SEED);
        double[] values = new double[DRAWS];
        var distribution = DesiredSpeedLibrary.carsLimit140_DensityLow(stream, shiftKmh);
        for (int i = 0; i < DRAWS; i++)
        {
            Speed speed = distribution.get();
            values[i] = speed.getInUnit(org.djunits.unit.SpeedUnit.KM_PER_HOUR);
        }
        return values;
    }

    /** A shift of zero reproduces the measured distribution exactly, draw for draw. */
    @Test
    public void zeroShiftChangesNothing()
    {
        StreamInterface stream = new MersenneTwister(SEED);
        var original = DesiredSpeedLibrary.carsLimit140_DensityLow(stream);
        double[] unshifted = new double[DRAWS];
        for (int i = 0; i < DRAWS; i++)
        {
            unshifted[i] = original.get().getInUnit(org.djunits.unit.SpeedUnit.KM_PER_HOUR);
        }
        double[] zero = draws(0.0);
        for (int i = 0; i < DRAWS; i++)
        {
            assertEquals(unshifted[i], zero[i], TOLERANCE,
                    "draw " + i + " differs between the no-argument distribution and a shift of zero");
        }
    }

    /** Every draw moves by exactly the shift, so the shape is untouched and the location moves. */
    @Test
    public void everyDrawMovesByExactlyTheShift()
    {
        double[] base = draws(0.0);
        for (double shift : new double[] {5.0, 10.0, -3.0})
        {
            double[] shifted = draws(shift);
            for (int i = 0; i < DRAWS; i++)
            {
                assertEquals(base[i] + shift, shifted[i], TOLERANCE,
                        "draw " + i + " did not move by " + shift + " km/h");
            }
        }
    }

    /** The draws really do span the distribution, so the equality above is not tested on one segment. */
    @Test
    public void theDrawsCoverTheDistribution()
    {
        double[] base = draws(0.0);
        double minimum = Double.MAX_VALUE;
        double maximum = -Double.MAX_VALUE;
        for (double value : base)
        {
            minimum = Math.min(minimum, value);
            maximum = Math.max(maximum, value);
        }
        assertTrue(minimum < 100.0, "the draws never reach the lower segments; lowest was " + minimum);
        assertTrue(maximum > 180.0, "the draws never reach the upper segments; highest was " + maximum);
    }
}
