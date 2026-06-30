package org.opentrafficsim.animation.data;

import java.awt.Color;
import java.awt.geom.RectangularShape;

import org.djunits.value.vdouble.scalar.Length;
import org.djunits.value.vdouble.scalar.Acceleration;
import org.djunits.value.vdouble.scalar.Speed;
import org.opentrafficsim.animation.gtu.colorer.GtuColorerManager;
import org.opentrafficsim.core.network.LateralDirectionality;
import org.opentrafficsim.draw.gtu.DefaultCarAnimation.GtuData;
import org.opentrafficsim.road.gtu.lane.LaneBasedGtu;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.MirovaTacticalPlanner;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.EgoContext;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.DesireLayer.Desire;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ActionState;

/**
 * Animation data of a LaneBasedGtu.
 * <p>
 * Copyright (c) 2023-2024 Delft University of Technology, PO Box 5, 2600 AA, Delft, the Netherlands. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/wjschakel">Wouter Schakel</a>
 */
public class AnimationGtuData extends AnimationIdentifiableShape<LaneBasedGtu> implements GtuData
{

    /** GTU colorer. */
    private final GtuColorerManager gtuColorerManager;

    /** Marker. */
    private final GtuMarker marker;

    /**
     * Constructor.
     * @param gtuColorerManager factory.
     * @param gtu GTU.
     * @param marker marker
     */
    public AnimationGtuData(final GtuColorerManager gtuColorerManager, final LaneBasedGtu gtu, final GtuMarker marker)
    {
        super(gtu);
        this.gtuColorerManager = gtuColorerManager;
        this.marker = marker;
    }

    @Override
    public Color getColor()
    {
        return this.gtuColorerManager.getColor(getObject());
    }

    @Override
    public Length getLength()
    {
        return getObject().getLength();
    }

    @Override
    public Length getWidth()
    {
        return getObject().getWidth();
    }

    @Override
    public Length getFront()
    {
        return getObject().getFront().dx();
    }

    @Override
    public Length getRear()
    {
        return getObject().getRear().dx();
    }

    @Override
    public boolean leftIndicatorOn()
    {
        return getObject().getTurnIndicatorStatus().isLeftOrBoth();
    }

    @Override
    public boolean rightIndicatorOn()
    {
        return getObject().getTurnIndicatorStatus().isRightOrBoth();
    }

    @Override
    public RectangularShape getMarker()
    {
        return this.marker.getShape();
    }

    @Override
    public boolean isBrakingLightsOn()
    {
        return getObject().isBrakingLightsOn();
    }

    /**
     * Returns the current action state of the GTU's tactical planner, if it is a MirovaTacticalPlanner.
     * @return action state, or null if none.
     */
    public ActionState getActionState()
    {
        if (getObject().getTacticalPlanner() instanceof MirovaTacticalPlanner planner)
        {
            return planner.getCurrentActionState();
        }
        return null;
    }

    /**
     * Helper to get the EgoContext of the vehicle if it is running MirovaTacticalPlanner.
     * @return EgoContext, or null if not using MiRoVA or not available.
     */
    private EgoContext getEgoContext()
    {
        if (getObject().getTacticalPlanner() instanceof MirovaTacticalPlanner planner)
        {
            return planner.getContext(EgoContext.class);
        }
        return null;
    }

    /**
     * Returns the current ego speed from the belief layer (EgoContext).
     * @return current ego speed, or null if unavailable.
     */
    public Speed getEgoSpeed()
    {
        EgoContext ego = getEgoContext();
        return ego != null ? ego.getEgoSpeed() : null;
    }

    /**
     * Returns the current desired speed from the belief layer (EgoContext).
     * @return desired speed, or null if unavailable.
     */
    public Speed getDesiredSpeed()
    {
        EgoContext ego = getEgoContext();
        if (ego != null)
        {
            try
            {
                return ego.getCurrentDesiredSpeed();
            }
            catch (Exception e)
            {
                return null;
            }
        }
        return null;
    }

    /**
     * Returns the current baseline car-following acceleration of the ego vehicle from the belief layer.
     * @return car-following acceleration, or null if unavailable.
     */
    public Acceleration getCarFollowingAcceleration()
    {
        EgoContext ego = getEgoContext();
        if (ego != null)
        {
            try
            {
                return ego.getCurrentCarFollowingAcceleration();
            }
            catch (Exception e)
            {
                return null;
            }
        }
        return null;
    }

    /**
     * Returns the maximum physical acceleration currently possible from the belief layer.
     * @return maximum physical acceleration, or null if unavailable.
     */
    public Acceleration getMaxPhysicalAcceleration()
    {
        EgoContext ego = getEgoContext();
        return ego != null ? ego.getMaxPhysicalAcceleration() : null;
    }

    /**
     * Returns the ego acceptable deceleration threshold for a left lane change from the belief layer.
     * @return deceleration threshold left, or null if unavailable.
     */
    public Acceleration getEgoDecelThresholdLeft()
    {
        EgoContext ego = getEgoContext();
        if (ego != null)
        {
            try
            {
                return ego.getEgoDecelerationThreshold(LateralDirectionality.LEFT);
            }
            catch (Exception e)
            {
                return null;
            }
        }
        return null;
    }

    /**
     * Returns the ego acceptable deceleration threshold for a right lane change from the belief layer.
     * @return deceleration threshold right, or null if unavailable.
     */
    public Acceleration getEgoDecelThresholdRight()
    {
        EgoContext ego = getEgoContext();
        if (ego != null)
        {
            try
            {
                return ego.getEgoDecelerationThreshold(LateralDirectionality.RIGHT);
            }
            catch (Exception e)
            {
                return null;
            }
        }
        return null;
    }

    /**
     * Returns the expected deceleration threshold for the left target lane follower from the belief layer.
     * @return follower deceleration threshold left, or null if unavailable.
     */
    public Acceleration getFollowerDecelThresholdLeft()
    {
        EgoContext ego = getEgoContext();
        if (ego != null)
        {
            try
            {
                return ego.getFollowerDecelerationThreshold(LateralDirectionality.LEFT);
            }
            catch (Exception e)
            {
                return null;
            }
        }
        return null;
    }

    /**
     * Returns the expected deceleration threshold for the right target lane follower from the belief layer.
     * @return follower deceleration threshold right, or null if unavailable.
     */
    public Acceleration getFollowerDecelThresholdRight()
    {
        EgoContext ego = getEgoContext();
        if (ego != null)
        {
            try
            {
                return ego.getFollowerDecelerationThreshold(LateralDirectionality.RIGHT);
            }
            catch (Exception e)
            {
                return null;
            }
        }
        return null;
    }

    /**
     * Returns the desired front headway distance in the current lane from the belief layer.
     * @return desired front headway, or null if unavailable.
     */
    public Length getDesiredFrontHeadway()
    {
        EgoContext ego = getEgoContext();
        return ego != null ? ego.getDesiredFrontHeadway(LateralDirectionality.NONE) : null;
    }

    /**
     * Returns the current lane change desire (LMRS-based Desire) of the vehicle.
     * @return lane change desire, or null if unavailable.
     */
    public Desire getLaneChangeDesire()
    {
        if (getObject().getTacticalPlanner() instanceof MirovaTacticalPlanner planner)
        {
            return planner.getLaneChangeDesire();
        }
        return null;
    }

    @Override
    public String toString()
    {
        return "Gtu " + getId();
    }

}
