package org.opentrafficsim.demo.mirova.scenariomanagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.djunits.unit.SpeedUnit;
import org.junit.jupiter.api.Test;
import org.opentrafficsim.demo.mirova.scenariomanagement.libraries.DesiredSpeedLibrary;

import nl.tudelft.simulation.jstats.streams.MersenneTwister;
import nl.tudelft.simulation.jstats.streams.StreamInterface;

/**
 * Truncating the car desired-speed distribution removes the slow drivers and keeps the rest's shape.
 * <p>
 * Three things have to hold for a campaign built on this to mean anything: nothing below the bound
 * survives, the surviving shape is the original conditional on being above it rather than a rescaled
 * guess, and a bound below the distribution changes nothing at all — because that last case is what
 * every run that does not ask for a truncation uses.
 * </p>
 * <p>
 * The conditional check is the one worth having. Renormalisation is easy to write and easy to get
 * subtly wrong — dividing by the wrong denominator, or forgetting that the first support point moves —
 * and either mistake still produces a plausible distribution with no slow drivers in it. So the share
 * of draws above an interior speed is compared against the value the original table implies for it,
 * computed here from that table rather than from the code under test.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class DesiredSpeedTruncationTest
{
    /** Draws per case; enough that a share is determined to well under a percentage point. */
    private static final int DRAWS = 200_000;

    /** The seed every stream is built with. */
    private static final long SEED = 20260924L;

    /** Support points of the car distribution, restated here so the check has its own source. */
    private static final double[] SPEEDS =
            {80, 90, 100, 110, 120, 130, 140, 150, 160, 170, 180, 190, 200};

    /** Cumulative probabilities at those support points. */
    private static final double[] CDF =
            {0.0, 0.036, 0.083, 0.156, 0.294, 0.448, 0.593, 0.721, 0.824, 0.893, 0.939, 0.959, 1.0};

    /**
     * The cumulative probability of the original table at a speed, by linear interpolation.
     * @param speed double; the speed [km/h]
     * @return double; the cumulative probability
     */
    private static double originalCdf(final double speed)
    {
        for (int i = 1; i < SPEEDS.length; i++)
        {
            if (speed <= SPEEDS[i])
            {
                return CDF[i - 1] + (speed - SPEEDS[i - 1]) / (SPEEDS[i] - SPEEDS[i - 1]) * (CDF[i] - CDF[i - 1]);
            }
        }
        return 1.0;
    }

    /**
     * Draws from the truncated distribution.
     * @param minimumKmh double; the bound [km/h]
     * @return double[]; the draws, in km/h
     */
    private static double[] truncated(final double minimumKmh)
    {
        StreamInterface stream = new MersenneTwister(SEED);
        var distribution = DesiredSpeedLibrary.carsLimit140_DensityLowAbove(stream, minimumKmh);
        double[] values = new double[DRAWS];
        for (int i = 0; i < DRAWS; i++)
        {
            values[i] = distribution.get().getInUnit(SpeedUnit.KM_PER_HOUR);
        }
        return values;
    }

    /** Nothing below the bound survives, however small the sample's luck. */
    @Test
    public void noDrawFallsBelowTheBound()
    {
        for (double minimum : new double[] {100.0, 110.0, 120.0})
        {
            double lowest = Double.MAX_VALUE;
            for (double value : truncated(minimum))
            {
                lowest = Math.min(lowest, value);
            }
            assertTrue(lowest >= minimum - 1e-9,
                    "a draw of " + lowest + " km/h survived a bound of " + minimum);
        }
    }

    /** The surviving shape is the original conditional on being above the bound. */
    @Test
    public void theShapeIsTheOriginalConditional()
    {
        for (double minimum : new double[] {100.0, 110.0})
        {
            double[] draws = truncated(minimum);
            double cut = originalCdf(minimum);
            for (double probe : new double[] {130.0, 150.0, 170.0})
            {
                double expected = (originalCdf(probe) - cut) / (1.0 - cut);
                int below = 0;
                for (double value : draws)
                {
                    if (value <= probe)
                    {
                        below++;
                    }
                }
                assertEquals(expected, (double) below / DRAWS, 0.005,
                        "with a bound of " + minimum + " the share below " + probe
                                + " km/h is not the original table's conditional share");
            }
        }
    }

    /** A bound at or below the lowest support point changes nothing, draw for draw. */
    @Test
    public void aBoundBelowTheDistributionChangesNothing()
    {
        StreamInterface stream = new MersenneTwister(SEED);
        var original = DesiredSpeedLibrary.carsLimit140_DensityLow(stream);
        double[] plain = new double[DRAWS];
        for (int i = 0; i < DRAWS; i++)
        {
            plain[i] = original.get().getInUnit(SpeedUnit.KM_PER_HOUR);
        }
        double[] bounded = truncated(SPEEDS[0]);
        for (int i = 0; i < DRAWS; i++)
        {
            assertEquals(plain[i], bounded[i], 1e-9, "draw " + i + " changed under a bound of " + SPEEDS[0]);
        }
    }

    /** A bound above the whole distribution is refused rather than answered with an empty one. */
    @Test
    public void aBoundAboveTheDistributionIsRefused()
    {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> DesiredSpeedLibrary.carsLimit140_DensityLowAbove(new MersenneTwister(SEED), 210.0));
        assertTrue(thrown.getMessage().contains("200"),
                "the message does not say what the highest support point is: " + thrown.getMessage());
    }
}
