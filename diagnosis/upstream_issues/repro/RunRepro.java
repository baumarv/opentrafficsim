package repro;

import org.djunits.value.vdouble.scalar.Duration;
import org.opentrafficsim.core.dsol.OtsSimulator;
import org.opentrafficsim.core.perception.HistoryManagerDevs;

/**
 * Runs the {@code StraightModel} demo of {@code ots-demo} without its sink detector, headless, for 600 s simulated.
 * <p>
 * {@code ReproNoSink} is a copy of {@code org.opentrafficsim.demo.StraightModel} in package {@code repro}, renamed, with the
 * line {@code new SinkDetector(sinkLane, new Length(10.0, METER), DefaultsNl.ROAD_USERS);} removed and an import of
 * {@code org.opentrafficsim.demo.InputParameterHelper} added. Run with {@code -XX:-OmitStackTraceInFastThrow}; the failing
 * GTUs are logged by {@code LaneBasedGtu.move} as {@code GtuException} and destroyed.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public final class RunRepro
{
    /** Simulated duration. */
    private static final Duration RUN_LENGTH = Duration.ofSI(600.0);

    /**
     * Utility class.
     */
    private RunRepro()
    {
        // utility class
    }

    /**
     * Runs the model.
     * @param args String[]; not used
     * @throws Exception when the model cannot be initialised
     */
    public static void main(final String[] args) throws Exception
    {
        OtsSimulator simulator = new OtsSimulator("repro");
        ReproNoSink model = new ReproNoSink(simulator);
        simulator.initialize(Duration.ZERO, Duration.ZERO, RUN_LENGTH, model, HistoryManagerDevs.noHistory(simulator));
        while (simulator.getSimulatorTime().lt(RUN_LENGTH))
        {
            simulator.step();
        }
        System.out.println("done; GTUs in the network: " + model.getNetwork().getGTUs().size());
        System.exit(0);
    }
}
