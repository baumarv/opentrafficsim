package repro;

import java.util.Locale;

import org.djunits.unit.SpeedUnit;
import org.djunits.value.vdouble.scalar.Speed;
import org.opentrafficsim.base.parameters.ParameterSet;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.road.gtu.tactical.following.AbstractIdm;
import org.opentrafficsim.road.network.speed.SpeedLimit;
import org.opentrafficsim.road.network.speed.SpeedLimits;

/**
 * Evaluates {@code AbstractIdm.DESIRED_SPEED} for the four combinations of a lane speed limit and a GTU-type speed limit,
 * with both adherence factors at 1.0, and prints the result next to the value its Javadoc describes.
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public final class DesiredSpeedRepro
{
    /** Lane speed limit of the example. */
    private static final SpeedLimit LANE_LIMIT = new SpeedLimit(new Speed(100.0, SpeedUnit.KM_PER_HOUR), false);

    /** GTU-type speed limit of the example. */
    private static final SpeedLimit GTU_TYPE_LIMIT = new SpeedLimit(new Speed(80.0, SpeedUnit.KM_PER_HOUR), false);

    /** Maximum vehicle speed, high enough not to bind. */
    private static final Speed MAX_VEHICLE_SPEED = new Speed(200.0, SpeedUnit.KM_PER_HOUR);

    /**
     * Utility class.
     */
    private DesiredSpeedRepro()
    {
        // utility class
    }

    /**
     * Prints the desired speed per combination of speed limits.
     * @param args String[]; not used
     * @throws Exception when a parameter is missing
     */
    public static void main(final String[] args) throws Exception
    {
        ParameterSet parameters = new ParameterSet();
        parameters.setParameter(ParameterTypes.FSPEED, 1.0);
        parameters.setParameter(ParameterTypes.FSPEED_GTU, 1.0);
        print(parameters, "lane 100 only", new SpeedLimits(LANE_LIMIT, null), 100.0);
        print(parameters, "GTU type 80 only", new SpeedLimits(null, GTU_TYPE_LIMIT), 80.0);
        print(parameters, "lane 100 and GTU type 80", new SpeedLimits(LANE_LIMIT, GTU_TYPE_LIMIT), 80.0);
        print(parameters, "none", new SpeedLimits(null, null), 130.0);
    }

    /**
     * Prints one line: the case, the desired speed returned and the one expected.
     * @param parameters ParameterSet; parameters with both adherence factors
     * @param label String; the case
     * @param limits SpeedLimits; the speed limits of the case
     * @param expectedKmh double; the desired speed the Javadoc describes, in km/h
     * @throws Exception when a parameter is missing
     */
    private static void print(final ParameterSet parameters, final String label, final SpeedLimits limits,
            final double expectedKmh) throws Exception
    {
        Speed desired = AbstractIdm.DESIRED_SPEED.desiredSpeed(parameters, limits, MAX_VEHICLE_SPEED);
        double kmh = desired.getInUnit(SpeedUnit.KM_PER_HOUR);
        System.out.printf(Locale.ROOT, "%-26s desired %6.1f km/h, expected %6.1f km/h%s%n", label, kmh, expectedKmh,
                Math.abs(kmh - expectedKmh) > 1e-9 ? "   <-- WRONG" : "");
    }
}
