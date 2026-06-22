package org.opentrafficsim.web.test;

import org.opentrafficsim.core.dsol.AbstractOtsModel;
import org.opentrafficsim.core.dsol.OtsSimulatorInterface;
import org.opentrafficsim.road.network.RoadNetwork;

import nl.tudelft.simulation.dsol.SimRuntimeException;

/**
 * TJunctionModel.java.
 * <p>
 * Copyright (c) 2013-2024 Delft University of Technology, PO Box 5, 2600 AA, Delft, the Netherlands. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/averbraeck" target="_blank">Alexander Verbraeck</a>
 */
public class TJunctionModel extends AbstractOtsModel
{
    /** */
    private static final long serialVersionUID = 20161211L;

    /** The network. */
    private RoadNetwork network;

    /**
     * Constructor.
     * @param simulator the simulator for this model
     */
    public TJunctionModel(final OtsSimulatorInterface simulator)
    {
        super(simulator);
    }

    @Override
    public void constructModel() throws SimRuntimeException
    {
        this.network = new RoadNetwork("TJunction", getSimulator());
    }

    @Override
    public RoadNetwork getNetwork()
    {
        return this.network;
    }
}
