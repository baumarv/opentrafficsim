package org.opentrafficsim.demo.mirova.scenariomanagement.libraries;

import java.util.Locale;

import org.opentrafficsim.road.gtu.generator.headway.ArrivalsHeadwayGenerator.HeadwayDistribution;

import nl.tudelft.simulation.jstats.streams.StreamInterface;

/**
 * Arrival headway distributions for the inflows of a motorway merge.
 * <p>
 * OTS defaults every origin to {@code EXPONENTIAL}, that is Poisson arrivals. On a motorway mainline carrying more than
 * 2000 veh/h per lane that is not merely an approximation but a physical impossibility: the exponential density is
 * highest at a headway of zero, so it keeps drawing gaps below the length of a car, and vehicles at such flows are in
 * any case already platooned by the upstream network rather than arriving independently. The short-headway bursts this
 * produces are exactly what triggers a breakdown at a merge, so the choice of distribution acts directly on both the
 * breakdown rate and its run-to-run variance.
 * </p>
 * <p>
 * A drawn value here is a dimensionless factor with mean 1.0 that {@code ArrivalsHeadwayGenerator} integrates against
 * the demand profile. Any distribution used must therefore keep that mean, or it silently rescales demand: a
 * distribution with mean 0.9 delivers roughly eleven percent more traffic than the OD matrix specifies.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See OpenTrafficSim License.
 * </p>
 * @author Marvin Baumann
 */
public final class HeadwayDistributionLibrary
{

    /** Utility class. */
    private HeadwayDistributionLibrary()
    {
        //
    }

    /**
     * Exponential arrivals with a floor under the headway, at unchanged mean.
     * <p>
     * Draws {@code m + (1 - m) * (-ln u)}. The exponential term has mean 1, so the result has mean
     * {@code m + (1 - m) = 1} for every {@code m} and the demand the OD matrix specifies is delivered exactly. What
     * changes is the lower tail: no factor below {@code m} can occur, so at a mean headway of 1.8 s and
     * {@code m = 0.4} no arrival follows its predecessor by less than 0.72 s.
     * </p>
     * <p>
     * This is the shifted exponential of renewal theory, the arrival process obtained when a fixed dead time follows
     * each event. It is the standard headway model for uninterrupted flow for exactly the reason it is wanted here,
     * and it degenerates to the two familiar cases at the ends of its range: {@code m = 0} is the OTS default
     * {@code EXPONENTIAL}, {@code m = 1} is {@code CONSTANT}.
     * </p>
     * @param minimumFraction double; the floor as a fraction of the mean headway, in [0, 1)
     * @return HeadwayDistribution; the distribution
     * @throws IllegalArgumentException when the fraction is outside [0, 1)
     */
    public static HeadwayDistribution shiftedExponential(final double minimumFraction)
    {
        if (!(minimumFraction >= 0.0 && minimumFraction < 1.0))
        {
            throw new IllegalArgumentException(
                    "minimumFraction must be in [0, 1) but is " + minimumFraction
                            + "; at 1.0 the distribution has no randomness left, use CONSTANT instead");
        }
        return new HeadwayDistribution()
        {
            @Override
            public double draw(final StreamInterface randomStream)
            {
                return minimumFraction + (1.0 - minimumFraction) * -Math.log(randomStream.nextDouble());
            }

            @Override
            public String getName()
            {
                return String.format(Locale.ROOT, "SHIFTED_EXPONENTIAL(%.2f)", minimumFraction);
            }
        };
    }
}
