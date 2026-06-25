package org.opentrafficsim.demo.mirova.scenariomanagement.libraries;

import java.util.Arrays;

import org.djunits.unit.SpeedUnit;
import org.djunits.value.vdouble.scalar.Speed;
import org.opentrafficsim.core.units.distributions.ContinuousDistDoubleScalar;

import nl.tudelft.simulation.jstats.distributions.DistEmpiricalInterpolated;
import nl.tudelft.simulation.jstats.distributions.DistUniform;
import nl.tudelft.simulation.jstats.distributions.empirical.AbstractEmpiricalDistribution;
import nl.tudelft.simulation.jstats.distributions.empirical.InterpolatedEmpiricalDistribution;
import nl.tudelft.simulation.jstats.streams.StreamInterface;

/**
 * DesiredSpeedLibrary
 * -------------------
 *
 * Contains a collection of desired–speed (free–flow speed) distributions
 * for cars and trucks on motorways. These distributions represent
 * empirical observations of how drivers choose their desired speed under
 * different posted speed limits and traffic density conditions.
 *
 * The distributions follow a cumulative–distribution–function (CDF)
 * representation and are interpolated to yield continuous values.
 *
 */
public class DesiredSpeedLibrary {

    /**
     * Do not instantiate.
     */
    private DesiredSpeedLibrary()
    {
        //
    }



    // ----------------------------------------------------------------------
    // Empirical distributions from german motorways with different speed limits.
    // ----------------------------------------------------------------------

    /** Cars on motorways with 100 km/h limit
     *  These distributions already account for the speed limit and driver compliance.
     *  They are taken from a Vissim model, in which speed limits are not explicitly represented.
     *  Use with caution in OTS, where speed limits should be modeled explicitly.
     * @param stream
     * @return */
    public static ContinuousDistDoubleScalar.Rel<Speed, SpeedUnit>
            cars100kmh(final StreamInterface stream) {

        InterpolatedEmpiricalDistribution dist =
            new InterpolatedEmpiricalDistribution(
                new Number[] {88, 95, 100, 110, 120, 130},
                new double[] {0.0, 0.03, 0.10, 0.70, 0.91, 1.0}
            );

        return new ContinuousDistDoubleScalar.Rel<>(
            new DistEmpiricalInterpolated(stream, dist),
            SpeedUnit.KM_PER_HOUR
        );
    }

    /** Cars on motorways with 120 km/h limit
     *  These distributions already account for the speed limit and driver compliance.
     *  They are taken from a Vissim model, in which speed limits are not explicitly represented.
     *  Use with caution in OTS, where speed limits should be modeled explicitly.
     * @param stream
     * @return */
    public static ContinuousDistDoubleScalar.Rel<Speed, SpeedUnit>
            cars120kmh(final StreamInterface stream) {

        InterpolatedEmpiricalDistribution dist =
            new InterpolatedEmpiricalDistribution(
                new Number[] {85, 105, 110, 125, 140, 155},
                new double[] {0.0, 0.03, 0.10, 0.68, 0.91, 1.0}
            );

        return new ContinuousDistDoubleScalar.Rel<>(
            new DistEmpiricalInterpolated(stream, dist),
            SpeedUnit.KM_PER_HOUR
        );
    }

    /** Cars on motorways with 130 km/h limit
     *  These distributions already account for the speed limit and driver compliance.
     *  They are taken from a Vissim model, in which speed limits are not explicitly represented.
     *  Use with caution in OTS, where speed limits should be modeled explicitly.
     * @param stream
     * @return */
    public static ContinuousDistDoubleScalar.Rel<Speed, SpeedUnit>
            cars130kmh(final StreamInterface stream) {

        InterpolatedEmpiricalDistribution dist =
            new InterpolatedEmpiricalDistribution(
                new Number[] {80, 98, 110, 130, 135, 143, 155, 170},
                new double[] {0.0, 0.03, 0.10, 0.68, 0.80, 0.91, 0.97, 1.0}
            );

        return new ContinuousDistDoubleScalar.Rel<>(
            new DistEmpiricalInterpolated(stream, dist),
            SpeedUnit.KM_PER_HOUR
        );
    }

    /** Cars on unrestricted german motorways.
     * @param stream
     * @return */
    public static ContinuousDistDoubleScalar.Rel<Speed, SpeedUnit>
            carsUnrestricted(final StreamInterface stream) {

        InterpolatedEmpiricalDistribution dist =
            new InterpolatedEmpiricalDistribution(
                new Number[] {80, 99, 109, 121, 131, 149, 165, 185, 205},
                new double[] {0.0, 0.03, 0.10, 0.26, 0.47, 0.80, 0.93, 0.99, 1.0}
            );

        return new ContinuousDistDoubleScalar.Rel<>(
            new DistEmpiricalInterpolated(stream, dist),
            SpeedUnit.KM_PER_HOUR
        );
    }

    /* ====================================================================== */
    /**
     * The following passenger–car distributions are conceptually derived from empirical
     * free–flow speed observations published in:
     *
     *   Weyland, C. M. L. (2023).
     *   *Microscopic traffic flow simulation of motorways with
     *   variable speed limit control* (Doctoral dissertation,
     *   Karlsruhe Institute of Technology).
     *   https://doi.org/10.5445/IR/1000162768
     * Passenger cars under a posted speed limit of 80 km/h,
     * medium traffic density.
     */

    /* ====================================================================== */
    /* Cars – Speed Limit 80 km/h                                             */
    /* ====================================================================== */

    /** Passenger cars under a posted speed limit of 80 km/h,
     * Represents a moderately constrained environment with some drivers still
     * choosing speeds somewhat above the posted limit.
     */
    public static ContinuousDistDoubleScalar.Rel<Speed, SpeedUnit>
            carsLimit80_DensityMedium(final StreamInterface stream)
    {
        InterpolatedEmpiricalDistribution dist =
            new InterpolatedEmpiricalDistribution(
                new Number[] {60, 80, 90, 100, 110, 120, 130, 140, 160, 180, 200},
                new double[] {0.00, 0.04, 0.12, 0.23, 0.41, 0.60, 0.79, 0.88, 0.95, 0.99, 1.00}
            );
        return new ContinuousDistDoubleScalar.Rel<>(
                new DistEmpiricalInterpolated(stream, dist),
                SpeedUnit.KM_PER_HOUR);
    }

    /**
     * Passenger cars under a posted speed limit of 80 km/h,
     * high traffic density.
     *
     * Compared to the medium–density case, the distribution shifts further
     * toward lower desired speeds due to increased interaction and constraints.
     */
    public static ContinuousDistDoubleScalar.Rel<Speed, SpeedUnit>
            carsLimit80_DensityHigh(final StreamInterface stream)
    {
        InterpolatedEmpiricalDistribution dist =
            new InterpolatedEmpiricalDistribution(
                new Number[] {60, 80, 90, 100, 110, 120, 130, 140, 160, 180, 200},
                new double[] {0.00, 0.055, 0.14, 0.27, 0.41, 0.60, 0.79, 0.88, 0.95, 0.99, 1.00}
            );
        return new ContinuousDistDoubleScalar.Rel<>(
                new DistEmpiricalInterpolated(stream, dist),
                SpeedUnit.KM_PER_HOUR);
    }

    /* ====================================================================== */
    /* Cars – Speed Limit 100 km/h                                            */
    /* ====================================================================== */

    /** Passenger cars, 100 km/h limit, medium density. */
    public static ContinuousDistDoubleScalar.Rel<Speed, SpeedUnit>
            carsLimit100_DensityMedium(final StreamInterface stream)
    {
        InterpolatedEmpiricalDistribution dist =
            new InterpolatedEmpiricalDistribution(
                new Number[] {70, 80, 90, 100, 110, 120, 130, 140, 160, 180, 200},
                new double[] {0.00, 0.02, 0.08, 0.20, 0.40, 0.60, 0.78, 0.88, 0.96, 0.985, 1.00}
            );
        return new ContinuousDistDoubleScalar.Rel<>(
                new DistEmpiricalInterpolated(stream, dist),
                SpeedUnit.KM_PER_HOUR);
    }

    /** Passenger cars, 100 km/h limit, high density. */
    public static ContinuousDistDoubleScalar.Rel<Speed, SpeedUnit>
            carsLimit100_DensityHigh(final StreamInterface stream)
    {
        InterpolatedEmpiricalDistribution dist =
            new InterpolatedEmpiricalDistribution(
                new Number[] {70, 80, 90, 100, 110, 120, 130, 140, 160, 180, 200},
                new double[] {0.00, 0.05, 0.14, 0.27, 0.45, 0.63, 0.80, 0.89, 0.96, 0.985, 1.00}
            );
        return new ContinuousDistDoubleScalar.Rel<>(
                new DistEmpiricalInterpolated(stream, dist),
                SpeedUnit.KM_PER_HOUR);
    }

    /* ====================================================================== */
    /* Cars – Speed Limit 120 km/h                                            */
    /* ====================================================================== */

    /** Passenger cars, 120 km/h limit, low density (free flow). */
    public static ContinuousDistDoubleScalar.Rel<Speed, SpeedUnit>
            carsLimit120_DensityLow(final StreamInterface stream)
    {
        InterpolatedEmpiricalDistribution dist =
            new InterpolatedEmpiricalDistribution(
                new Number[] {
                    70, 80, 90, 100, 110, 120, 130, 140,
                    150, 160, 170, 180, 190, 200
                },
                new double[] {
                    0.00, 0.012, 0.036, 0.083, 0.168, 0.322,
                    0.519, 0.691, 0.819, 0.903, 0.945, 0.963,
                    0.975, 1.0
                }
            );
        return new ContinuousDistDoubleScalar.Rel<>(
                new DistEmpiricalInterpolated(stream, dist),
                SpeedUnit.KM_PER_HOUR);
    }

    /** Passenger cars, 120 km/h limit, medium density. */
    public static ContinuousDistDoubleScalar.Rel<Speed, SpeedUnit>
            carsLimit120_DensityMedium(final StreamInterface stream)
    {
        InterpolatedEmpiricalDistribution dist =
            new InterpolatedEmpiricalDistribution(
                new Number[] {
                    70, 80, 90, 100, 110, 120, 130, 140,
                    150, 160, 170, 180, 190, 200
                },
                new double[] {
                    0.00, 0.015, 0.045, 0.095, 0.185, 0.340,
                    0.535, 0.705, 0.830, 0.910, 0.950, 0.967,
                    0.978, 1.0
                }
            );
        return new ContinuousDistDoubleScalar.Rel<>(
                new DistEmpiricalInterpolated(stream, dist),
                SpeedUnit.KM_PER_HOUR);
    }

    /** Passenger cars, 120 km/h limit, high density. */
    public static ContinuousDistDoubleScalar.Rel<Speed, SpeedUnit>
            carsLimit120_DensityHigh(final StreamInterface stream)
    {
        InterpolatedEmpiricalDistribution dist =
            new InterpolatedEmpiricalDistribution(
                new Number[] {
                    70, 80, 90, 100, 110, 120, 130, 140,
                    150, 160, 170, 180, 190, 200
                },
                new double[] {
                    0.00, 0.020, 0.055, 0.110, 0.200, 0.360,
                    0.555, 0.720, 0.840, 0.915, 0.955, 0.970,
                    0.980, 1.0
                }
            );
        return new ContinuousDistDoubleScalar.Rel<>(
                new DistEmpiricalInterpolated(stream, dist),
                SpeedUnit.KM_PER_HOUR);
    }

    /* ====================================================================== */
    /* Cars – Speed Limit 140 km/h                                            */
    /* ====================================================================== */

    /** Passenger cars, 140 km/h limit, low density (free flow). */
    public static ContinuousDistDoubleScalar.Rel<Speed, SpeedUnit>
            carsLimit140_DensityLow(final StreamInterface stream)
    {
        InterpolatedEmpiricalDistribution dist =
            new InterpolatedEmpiricalDistribution(
                new Number[] {80, 90, 100, 110, 120, 130, 140, 150,
                              160, 170, 180, 190, 200},
                new double[] {0.0, 0.036, 0.083, 0.156, 0.294,
                              0.448, 0.593, 0.721, 0.824, 0.893,
                              0.939, 0.959, 1.0}
            );
        return new ContinuousDistDoubleScalar.Rel<>(
                new DistEmpiricalInterpolated(stream, dist),
                SpeedUnit.KM_PER_HOUR);
    }

    /** Passenger cars, 140 km/h limit, medium density. */
    public static ContinuousDistDoubleScalar.Rel<Speed, SpeedUnit>
            carsLimit140_DensityMedium(final StreamInterface stream)
    {
        InterpolatedEmpiricalDistribution dist =
            new InterpolatedEmpiricalDistribution(
                new Number[] {80, 90, 100, 110, 120, 130, 140, 150,
                              160, 170, 180, 190, 200},
                new double[] {0.0, 0.037, 0.095, 0.166, 0.325,
                              0.520, 0.673, 0.772, 0.839, 0.889,
                              0.923, 0.944, 1.0}
            );
        return new ContinuousDistDoubleScalar.Rel<>(
                new DistEmpiricalInterpolated(stream, dist),
                SpeedUnit.KM_PER_HOUR);
    }

    /** Passenger cars, 140 km/h limit, high density. */
    public static ContinuousDistDoubleScalar.Rel<Speed, SpeedUnit>
            carsLimit140_DensityHigh(final StreamInterface stream)
    {
        InterpolatedEmpiricalDistribution dist =
            new InterpolatedEmpiricalDistribution(
                new Number[] {80, 90, 100, 110, 120, 130, 140, 150,
                              160, 170, 180, 190, 200},
                new double[] {0.0, 0.059, 0.141, 0.230, 0.384,
                              0.583, 0.735, 0.828, 0.893, 0.936,
                              0.962, 0.977, 1.0}
            );
        return new ContinuousDistDoubleScalar.Rel<>(
                new DistEmpiricalInterpolated(stream, dist),
                SpeedUnit.KM_PER_HOUR);
    }

    /**
     * Desired speed distribution for passenger cars based on Hoogendoorn (European freeways).
     * Normal distribution: mean 120 km/h, sd 14 km/h, truncated at 5% and 95%.
     * Normal distribution: mean 90 km/h, sd 10 km/h, truncated at 5% and 95%.
     * Source: Hoogendoorn, S. P. (2005). Vehicle-Type and Lane–Specific Free Speed Distributions on Motorways:
     * A Novel Estimation Approach Using Censored Observations: A Novel Estimation Approach Using Censored Observations.
     * Transportation Research Record: Journal of the Transportation Research Board, 1934(1), 148-156.
     * https://doi.org/10.1177/0361198105193400116
     * @param stream Random stream
     * @return desired speed distribution (km/h)
     */
    public static ContinuousDistDoubleScalar.Rel<Speed, SpeedUnit>
            hoogendoornCars(final StreamInterface stream)
    {
        // 5% and 95% quantile truncation
        double vMin = 120 - 1.645 * 14; // ≈ 97 km/h
        double vMax = 120 + 1.645 * 14; // ≈ 143 km/h

        // Discretize speed values (11 points between vMin and vMax)
        Number[] values = new Number[11];
        double[] cdf = new double[11];

        for (int i = 0; i < 11; i++)
        {
            double v = vMin + i * (vMax - vMin) / 10.0;
            values[i] = v;

            // Normalized CDF between 0.05 and 0.95
            double z = (v - 120) / 14.0;
            double Phi = 0.5 * (1 + erf(z / Math.sqrt(2)));   // Standard normal CDF
            double PhiClipped = (Phi - 0.05) / 0.90;           // Rescale to [0,1]
            cdf[i] = Math.max(0.0, Math.min(1.0, PhiClipped));
        }

        InterpolatedEmpiricalDistribution dist =
            new InterpolatedEmpiricalDistribution(values, cdf);

        return new ContinuousDistDoubleScalar.Rel<>(
                new DistEmpiricalInterpolated(stream, dist),
                SpeedUnit.KM_PER_HOUR);
    }

    /**
     * Desired speed distribution for heavy-duty vehicles (Hoogendoorn).
     * Normal distribution: mean 90 km/h, sd 10 km/h, truncated at 5% and 95%.
     * Source: Hoogendoorn, S. P. (2005). Vehicle-Type and Lane–Specific Free Speed Distributions on Motorways:
     * A Novel Estimation Approach Using Censored Observations: A Novel Estimation Approach Using Censored Observations.
     * Transportation Research Record: Journal of the Transportation Research Board, 1934(1), 148-156.
     * https://doi.org/10.1177/0361198105193400116
     *
     * @param stream Random stream
     * @return desired speed distribution (km/h)
     */
    public static ContinuousDistDoubleScalar.Rel<Speed, SpeedUnit>
            hoogendoornTrucks(final StreamInterface stream)
    {
        double vMin = 90 - 1.645 * 10;  // ≈ 73 km/h
        double vMax = 90 + 1.645 * 10;  // ≈ 106 km/h

        Number[] values = new Number[11];
        double[] cdf = new double[11];

        for (int i = 0; i < 11; i++)
        {
            double v = vMin + i * (vMax - vMin) / 10.0;
            values[i] = v;

            double z = (v - 90) / 10.0;
            double Phi = 0.5 * (1 + erf(z / Math.sqrt(2)));
            double PhiClipped = (Phi - 0.05) / 0.90;
            cdf[i] = Math.max(0.0, Math.min(1.0, PhiClipped));
        }

        InterpolatedEmpiricalDistribution dist =
            new InterpolatedEmpiricalDistribution(values, cdf);

        return new ContinuousDistDoubleScalar.Rel<>(
                new DistEmpiricalInterpolated(stream, dist),
                SpeedUnit.KM_PER_HOUR);
    }

    /** Helper: error function approximation */
    private static double erf(final double x)
    {
        // Numerical approximation (Abramowitz/Stegun)
        double t = 1.0 / (1.0 + 0.5 * Math.abs(x));
        double tau =
            t * Math.exp(-x*x - 1.26551223 +
                  t * (1.00002368 +
                  t * (0.37409196 +
                  t * (0.09678418 +
                  t * (-0.18628806 +
                  t * (0.27886807 +
                  t * (-1.13520398 +
                  t * (1.48851587 +
                  t * (-0.82215223 +
                  t * 0.17087277)))))))));

        return x >= 0 ? 1 - tau : tau - 1;
    }



    /**
     * @param stream
     * @return
     */
    public static ContinuousDistDoubleScalar.Rel<Speed, SpeedUnit> trucks(final StreamInterface stream) {
             return new ContinuousDistDoubleScalar.Rel<>(new DistUniform(stream, 80.0, 100.0), SpeedUnit.KM_PER_HOUR);
    }

    /**
     * Trucks, speed limit 80 km/h, density class 1 (empirical distribution from Weyland).
     */
    public static ContinuousDistDoubleScalar.Rel<Speed, SpeedUnit>
            trucksLimit80_DensityClass1(final StreamInterface stream)
    {
        InterpolatedEmpiricalDistribution dist =
            new InterpolatedEmpiricalDistribution(
                new Number[] {
                    45, 46, 47, 48, 49, 50,
                    51, 52, 53, 54, 55, 56,
                    57, 58, 59, 60, 61, 62,
                    63, 64, 65, 66, 67, 68,
                    69, 70, 71, 72, 73, 74,
                    75, 76, 77, 78, 79, 80,
                    81, 82, 83, 84, 85, 86,
                    87, 88, 89, 90, 91, 92,
                    93, 94, 95, 96, 97, 98,
                    99, 100, 101, 102, 103, 104,
                    105, 106, 107, 108, 109, 110,
                    111, 112, 113, 114, 115
                },
                new double[] {
                    0, 0.00067691, 0.00203074, 0.00406149, 0.00676915, 0.01015372,
                    0.01421521, 0.01890081, 0.02425845, 0.03024823, 0.03678745, 0.0429907,
                    0.04868473, 0.05434991, 0.05988261, 0.06509267, 0.07039572, 0.07668485,
                    0.08315676, 0.09023607, 0.09904047, 0.1095835, 0.12139628, 0.13371123,
                    0.14766425, 0.16421135, 0.18157865, 0.19973335, 0.22007222, 0.24281563,
                    0.26493046, 0.29013745, 0.31745135, 0.3464613, 0.37871693, 0.4110963,
                    0.44448723, 0.48092874, 0.52199208, 0.56343708, 0.60562833, 0.64497465,
                    0.68347451, 0.72356993, 0.75906739, 0.79175843, 0.82374684, 0.85217536,
                    0.87294308, 0.89208526, 0.90883523, 0.92325841, 0.93534371, 0.94334721,
                    0.95088053, 0.95850818, 0.96459685, 0.96915725, 0.97407769, 0.97695795,
                    0.98019824, 0.98379856, 0.98703885, 0.9899191, 0.99243933, 0.99459952,
                    0.99639968, 0.99783981, 0.9989199, 0.99963997, 1
                }
            );
        return new ContinuousDistDoubleScalar.Rel<>(
                new DistEmpiricalInterpolated(stream, dist),
                SpeedUnit.KM_PER_HOUR);
    }

    /**
     * Trucks, speed limit 80 km/h, density class 2 (empirical distribution from Weyland).
     */
    public static ContinuousDistDoubleScalar.Rel<Speed, SpeedUnit>
            trucksLimit80_DensityClass2(final StreamInterface stream)
    {
        InterpolatedEmpiricalDistribution dist =
            new InterpolatedEmpiricalDistribution(
                new Number[] {
                    41.16347, 42.078213, 42.992957, 43.907701, 44.822445, 45.737189,
                    46.651932, 47.566676, 48.48142, 49.396164, 50.310907, 51.225651,
                    52.140395, 53.021262, 53.89159, 54.763031, 55.632312, 56.49196,
                    57.348372, 58.197827, 59.030323, 60.060489, 61.133804, 62.219039,
                    63.332678, 64.489543, 65.665252, 66.859827, 68.094793, 69.374866,
                    70.654599, 71.991947, 73.095906, 74.045203, 74.994501, 75.943798,
                    76.893095, 77.842393, 78.79169, 79.740988, 80.690285, 81.639583,
                    82.58888, 83.538178, 84.487475, 85.436773, 86.38607, 87.335368,
                    88.284665, 89.233963, 90.18326, 91.132558, 92.081855, 93.031153,
                    93.98045, 94.929747, 95.879045, 96.828342, 97.77764, 98.726937,
                    99.676235, 100.625532, 101.57483, 102.524127, 103.473425, 104.422722,
                    105.37202, 106.321317, 107.270615, 108.219912, 109.16921
                },
                new double[] {
                    0, 0.00067691, 0.00203074, 0.00406149, 0.00676915, 0.01015372,
                    0.01421521, 0.01890081, 0.02425845, 0.03024823, 0.03678745, 0.0429907,
                    0.04868473, 0.05434991, 0.05988261, 0.06509267, 0.07039572, 0.07668485,
                    0.08315676, 0.09023607, 0.09904047, 0.1095835, 0.12139628, 0.13371123,
                    0.14766425, 0.16421135, 0.18157865, 0.19973335, 0.22007222, 0.24281563,
                    0.26493046, 0.29013745, 0.31745135, 0.3464613, 0.37871693, 0.4110963,
                    0.44448723, 0.48092874, 0.52199208, 0.56343708, 0.60562833, 0.64497465,
                    0.68347451, 0.72356993, 0.75906739, 0.79175843, 0.82374684, 0.85217536,
                    0.87294308, 0.89208526, 0.90883523, 0.92325841, 0.93534371, 0.94334721,
                    0.95088053, 0.95850818, 0.96459685, 0.96915725, 0.97407769, 0.97695795,
                    0.98019824, 0.98379856, 0.98703885, 0.9899191, 0.99243933, 0.99459952,
                    0.99639968, 0.99783981, 0.9989199, 0.99963997, 1
                }
            );
        return new ContinuousDistDoubleScalar.Rel<>(
                new DistEmpiricalInterpolated(stream, dist),
                SpeedUnit.KM_PER_HOUR);
    }

    /**
     * Trucks, speed limit 80 km/h, density class 1 (modified uniform distribution from Weyland).
     */
    public static ContinuousDistDoubleScalar.Rel<Speed, SpeedUnit>
            trucksLimit80_DensityClass1_Modified(final StreamInterface stream)
    {
        InterpolatedEmpiricalDistribution dist =
            new InterpolatedEmpiricalDistribution(
                new Number[] {
                    70, 95
                },
                new double[] {
                    0, 1
                }
            );
        return new ContinuousDistDoubleScalar.Rel<>(
                new DistEmpiricalInterpolated(stream, dist),
                SpeedUnit.KM_PER_HOUR);
    }

    /**
     * Trucks, speed limit 80 km/h, density class 2 (modified uniform distribution from Weyland).
     */
    public static ContinuousDistDoubleScalar.Rel<Speed, SpeedUnit>
            trucksLimit80_DensityClass2_Modified(final StreamInterface stream)
    {
        InterpolatedEmpiricalDistribution dist =
            new InterpolatedEmpiricalDistribution(
                new Number[] {
                    65, 90
                },
                new double[] {
                    0, 1
                }
            );
        return new ContinuousDistDoubleScalar.Rel<>(
                new DistEmpiricalInterpolated(stream, dist),
                SpeedUnit.KM_PER_HOUR);
    }

    /**
     * Trucks, speed limit 100 km/h, density class 1 (empirical distribution from Weyland).
     */
    public static ContinuousDistDoubleScalar.Rel<Speed, SpeedUnit>
            trucksLimit100_DensityClass1(final StreamInterface stream)
    {
        InterpolatedEmpiricalDistribution dist =
            new InterpolatedEmpiricalDistribution(
                new Number[] {
                    55, 56, 57, 58, 59, 60,
                    61, 62, 63, 64, 65, 66,
                    67, 68, 69, 70, 71, 72,
                    73, 74, 75, 76, 77, 78,
                    79, 80, 81, 82, 83, 84,
                    85, 86, 87, 88, 89, 90,
                    91, 92, 93, 94, 95, 96,
                    97, 98, 99, 100, 101, 102,
                    103, 104, 105, 106, 107, 108,
                    109, 110, 111, 112, 113, 114,
                    115, 116, 117, 118, 119, 120,
                    121, 122, 123, 124, 125
                },
                new double[] {
                    0, 0.00041409, 0.00124228, 0.00248455, 0.00414092, 0.00621138,
                    0.00869593, 0.01169671, 0.01535776, 0.01981172, 0.02509238, 0.03089806,
                    0.03736472, 0.04399651, 0.05148642, 0.05996206, 0.06877239, 0.07857873,
                    0.08862624, 0.09977351, 0.11115656, 0.12409601, 0.13749945, 0.15188869,
                    0.1686256, 0.18614755, 0.20555737, 0.22676488, 0.25540662, 0.2872706,
                    0.32354774, 0.36289869, 0.40544893, 0.45062697, 0.49584631, 0.542078,
                    0.58846223, 0.63392972, 0.67327839, 0.70951424, 0.74205772, 0.77042471,
                    0.795493, 0.81766747, 0.83717462, 0.85348456, 0.86790792, 0.88065475,
                    0.89180934, 0.9015454, 0.90949819, 0.91659141, 0.92284794, 0.92840845,
                    0.93357803, 0.93935058, 0.94482348, 0.95018269, 0.95552025, 0.96087721,
                    0.96683713, 0.97286674, 0.9782934, 0.98311709, 0.98733781, 0.99095558,
                    0.99397039, 0.99638223, 0.99819112, 0.99939704, 1
                }
            );
        return new ContinuousDistDoubleScalar.Rel<>(
                new DistEmpiricalInterpolated(stream, dist),
                SpeedUnit.KM_PER_HOUR);
    }

    /**
     * Trucks, speed limit 100 km/h, density class 2 (empirical distribution from Weyland).
     */
    public static ContinuousDistDoubleScalar.Rel<Speed, SpeedUnit>
            trucksLimit100_DensityClass2(final StreamInterface stream)
    {
        InterpolatedEmpiricalDistribution dist =
            new InterpolatedEmpiricalDistribution(
                new Number[] {
                    52.757934, 53.71717, 54.676405, 55.63564, 56.594875, 57.55411,
                    58.513345, 59.472581, 60.431816, 61.391051, 62.40156, 63.420939,
                    64.448976, 65.480757, 66.523764, 67.57978, 68.642102, 69.718263,
                    70.800188, 71.897833, 72.95544, 74.026176, 75.103052, 76.190262,
                    77.298821, 78.416974, 79.553859, 80.490593, 81.472186, 82.453778,
                    83.435371, 84.416964, 85.398556, 86.380149, 87.361741, 88.343334,
                    89.324927, 90.306519, 91.288112, 92.269705, 93.251297, 94.23289,
                    95.214482, 96.196075, 97.177668, 98.15926, 99.140853, 100.122445,
                    101.104038, 102.085631, 103.067223, 104.048816, 105.030408, 106.012001,
                    106.993594, 107.975186, 108.956779, 109.938371, 110.919964, 111.901557,
                    112.883149, 113.864742, 114.846334, 115.827927, 116.80952, 117.791112,
                    118.772705, 119.754297, 120.73589, 121.717483, 122.699075
                },
                new double[] {
                    0, 0.00041409, 0.00124228, 0.00248455, 0.00414092, 0.00621138,
                    0.00869593, 0.01169671, 0.01535776, 0.01981172, 0.02509238, 0.03089806,
                    0.03736472, 0.04399651, 0.05148642, 0.05996206, 0.06877239, 0.07857873,
                    0.08862624, 0.09977351, 0.11115656, 0.12409601, 0.13749945, 0.15188869,
                    0.1686256, 0.18614755, 0.20555737, 0.22676488, 0.25540662, 0.2872706,
                    0.32354774, 0.36289869, 0.40544893, 0.45062697, 0.49584631, 0.542078,
                    0.58846223, 0.63392972, 0.67327839, 0.70951424, 0.74205772, 0.77042471,
                    0.795493, 0.81766747, 0.83717462, 0.85348456, 0.86790792, 0.88065475,
                    0.89180934, 0.9015454, 0.90949819, 0.91659141, 0.92284794, 0.92840845,
                    0.93357803, 0.93935058, 0.94482348, 0.95018269, 0.95552025, 0.96087721,
                    0.96683713, 0.97286674, 0.9782934, 0.98311709, 0.98733781, 0.99095558,
                    0.99397039, 0.99638223, 0.99819112, 0.99939704, 1
                }
            );
        return new ContinuousDistDoubleScalar.Rel<>(
                new DistEmpiricalInterpolated(stream, dist),
                SpeedUnit.KM_PER_HOUR);
    }

    /**
     * Trucks, speed limit 100 km/h, density class 3 (empirical distribution from Weyland).
     */
    public static ContinuousDistDoubleScalar.Rel<Speed, SpeedUnit>
            trucksLimit100_DensityClass3(final StreamInterface stream)
    {
        InterpolatedEmpiricalDistribution dist =
            new InterpolatedEmpiricalDistribution(
                new Number[] {
                    51.311111, 52.24404, 53.176969, 54.109899, 55.042828, 55.975757,
                    56.908686, 57.841616, 58.774545, 59.707474, 60.666265, 61.629529,
                    62.59716, 63.566679, 64.541861, 65.523603, 66.508527, 67.50043,
                    68.495241, 69.49798, 70.61354, 71.760809, 72.920967, 74.102823,
                    75.329504, 76.576329, 77.862482, 78.7284, 79.688503, 80.648605,
                    81.608708, 82.56881, 83.528913, 84.489015, 85.449117, 86.40922,
                    87.369322, 88.329425, 89.289527, 90.24963, 91.209732, 92.169835,
                    93.129937, 94.090039, 95.050142, 96.010244, 96.970347, 97.930449,
                    98.890552, 99.850654, 100.810757, 101.770859, 102.730961, 103.691064,
                    104.651166, 105.611269, 106.571371, 107.531474, 108.491576, 109.451679,
                    110.411781, 111.371883, 112.331986, 113.292088, 114.252191, 115.212293,
                    116.172396, 117.132498, 118.092601, 119.052703, 120.012805
                },
                new double[] {
                    0, 0.00041409, 0.00124228, 0.00248455, 0.00414092, 0.00621138,
                    0.00869593, 0.01169671, 0.01535776, 0.01981172, 0.02509238, 0.03089806,
                    0.03736472, 0.04399651, 0.05148642, 0.05996206, 0.06877239, 0.07857873,
                    0.08862624, 0.09977351, 0.11115656, 0.12409601, 0.13749945, 0.15188869,
                    0.1686256, 0.18614755, 0.20555737, 0.22676488, 0.25540662, 0.2872706,
                    0.32354774, 0.36289869, 0.40544893, 0.45062697, 0.49584631, 0.542078,
                    0.58846223, 0.63392972, 0.67327839, 0.70951424, 0.74205772, 0.77042471,
                    0.795493, 0.81766747, 0.83717462, 0.85348456, 0.86790792, 0.88065475,
                    0.89180934, 0.9015454, 0.90949819, 0.91659141, 0.92284794, 0.92840845,
                    0.93357803, 0.93935058, 0.94482348, 0.95018269, 0.95552025, 0.96087721,
                    0.96683713, 0.97286674, 0.9782934, 0.98311709, 0.98733781, 0.99095558,
                    0.99397039, 0.99638223, 0.99819112, 0.99939704, 1
                }
            );
        return new ContinuousDistDoubleScalar.Rel<>(
                new DistEmpiricalInterpolated(stream, dist),
                SpeedUnit.KM_PER_HOUR);
    }

    /**
     * Trucks, speed limit 100 km/h, density class 1 (modified uniform distribution from Weyland).
     */
    public static ContinuousDistDoubleScalar.Rel<Speed, SpeedUnit>
            trucksLimit100_DensityClass1_Modified(final StreamInterface stream)
    {
        InterpolatedEmpiricalDistribution dist =
            new InterpolatedEmpiricalDistribution(
                new Number[] {
                    79, 100
                },
                new double[] {
                    0, 1
                }
            );
        return new ContinuousDistDoubleScalar.Rel<>(
                new DistEmpiricalInterpolated(stream, dist),
                SpeedUnit.KM_PER_HOUR);
    }

    /**
     * Trucks, speed limit 100 km/h, density class 2 (modified uniform distribution from Weyland).
     */
    public static ContinuousDistDoubleScalar.Rel<Speed, SpeedUnit>
            trucksLimit100_DensityClass2_Modified(final StreamInterface stream)
    {
        InterpolatedEmpiricalDistribution dist =
            new InterpolatedEmpiricalDistribution(
                new Number[] {
                    77, 98
                },
                new double[] {
                    0, 1
                }
            );
        return new ContinuousDistDoubleScalar.Rel<>(
                new DistEmpiricalInterpolated(stream, dist),
                SpeedUnit.KM_PER_HOUR);
    }

    /**
     * Trucks, speed limit 100 km/h, density class 3 (modified uniform distribution from Weyland).
     */
    public static ContinuousDistDoubleScalar.Rel<Speed, SpeedUnit>
            trucksLimit100_DensityClass3_Modified(final StreamInterface stream)
    {
        InterpolatedEmpiricalDistribution dist =
            new InterpolatedEmpiricalDistribution(
                new Number[] {
                    75, 96
                },
                new double[] {
                    0, 1
                }
            );
        return new ContinuousDistDoubleScalar.Rel<>(
                new DistEmpiricalInterpolated(stream, dist),
                SpeedUnit.KM_PER_HOUR);
    }
}

