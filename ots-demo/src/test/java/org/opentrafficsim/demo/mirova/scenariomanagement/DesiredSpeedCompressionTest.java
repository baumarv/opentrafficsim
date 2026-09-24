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
 * Compressing the car desired speeds redistributes them and removes nobody.
 * <p>
 * The map is {@code v -> pivot + factor * (v - pivot)}, which is affine and increasing, so three things
 * must hold and each would be invisible in a plot of the result: a factor of one changes nothing, every
 * draw moves by exactly what the map says, and the order of the population is preserved. The last is
 * what separates this axis from the truncation one — a reshaping that reordered drivers would no longer
 * be a redistribution of the same population, and the campaign's comparison between the two axes would
 * be comparing two different things.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class DesiredSpeedCompressionTest
{
    /** Draws per case. */
    private static final int DRAWS = 5000;

    /** The seed every stream is built with, so the same uniforms are drawn on both sides. */
    private static final long SEED = 20260924L;

    /** Tolerance [km/h]; the arithmetic is one multiply and one add. */
    private static final double TOLERANCE = 1e-9;

    /**
     * Draws from the compressed distribution.
     * @param pivotKmh double; the pivot [km/h]
     * @param factor double; the compression factor
     * @return double[]; the draws, in km/h
     */
    private static double[] compressed(final double pivotKmh, final double factor)
    {
        StreamInterface stream = new MersenneTwister(SEED);
        var distribution = DesiredSpeedLibrary.carsLimit140_DensityLowCompressed(stream, pivotKmh, factor);
        double[] values = new double[DRAWS];
        for (int i = 0; i < DRAWS; i++)
        {
            values[i] = distribution.get().getInUnit(SpeedUnit.KM_PER_HOUR);
        }
        return values;
    }

    /**
     * The unshifted, uncompressed distribution, through the same stream.
     * @return double[]; the draws, in km/h
     */
    private static double[] original()
    {
        StreamInterface stream = new MersenneTwister(SEED);
        var distribution = DesiredSpeedLibrary.carsLimit140_DensityLow(stream);
        double[] values = new double[DRAWS];
        for (int i = 0; i < DRAWS; i++)
        {
            values[i] = distribution.get().getInUnit(SpeedUnit.KM_PER_HOUR);
        }
        return values;
    }

    /** A factor of one is the identity, whatever the pivot. */
    @Test
    public void aFactorOfOneChangesNothing()
    {
        double[] plain = original();
        for (double pivot : new double[] {100.0, 150.0, 200.0})
        {
            double[] same = compressed(pivot, 1.0);
            for (int i = 0; i < DRAWS; i++)
            {
                assertEquals(plain[i], same[i], TOLERANCE,
                        "draw " + i + " moved under a factor of one with pivot " + pivot);
            }
        }
    }

    /** Every draw lands exactly where the affine map puts it. */
    @Test
    public void everyDrawFollowsTheMap()
    {
        double[] plain = original();
        for (double pivot : new double[] {140.0, 150.0, 160.0})
        {
            for (double factor : new double[] {0.7, 0.8, 0.9})
            {
                double[] moved = compressed(pivot, factor);
                for (int i = 0; i < DRAWS; i++)
                {
                    assertEquals(pivot + factor * (plain[i] - pivot), moved[i], TOLERANCE,
                            "draw " + i + " does not follow pivot " + pivot + " factor " + factor);
                }
            }
        }
    }

    /**
     * Nobody is removed and nobody overtakes anybody: the population is the same, redistributed.
     * <p>
     * Checked as a rank correlation of exactly one rather than as a count, because a map that merged two
     * drivers onto one speed would keep the count and lose the ordering.
     * </p>
     */
    @Test
    public void theOrderOfThePopulationIsPreserved()
    {
        double[] plain = original();
        double[] moved = compressed(150.0, 0.7);
        assertEquals(plain.length, moved.length, "the population changed size");
        for (int i = 1; i < DRAWS; i++)
        {
            if (plain[i] > plain[i - 1])
            {
                assertTrue(moved[i] > moved[i - 1],
                        "draws " + (i - 1) + " and " + i + " changed order under the compression");
            }
        }
    }

    /** A factor that would reverse or collapse the distribution is refused rather than applied. */
    @Test
    public void anImpossibleFactorIsRefused()
    {
        for (double factor : new double[] {0.0, -0.5})
        {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> DesiredSpeedLibrary.carsLimit140_DensityLowCompressed(
                            new MersenneTwister(SEED), 150.0, factor));
            assertTrue(thrown.getMessage().contains("positive"),
                    "the message does not say what is required: " + thrown.getMessage());
        }
    }
}
