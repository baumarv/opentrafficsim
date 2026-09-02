package org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ReactiveLayer;

import java.io.Serializable;

import org.djunits.unit.AccelerationUnit;
import org.djunits.value.vdouble.scalar.Acceleration;
import org.djunits.value.vdouble.scalar.Length;
import org.djunits.value.vdouble.scalar.Speed;
import org.opentrafficsim.base.parameters.ParameterException;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.base.parameters.Parameters;
import org.opentrafficsim.road.gtu.lane.perception.PerceptionIterable;
import org.opentrafficsim.road.gtu.lane.perception.headway.Headway;
import org.opentrafficsim.road.gtu.lane.tactical.following.AbstractIdm;
import org.opentrafficsim.road.gtu.lane.tactical.following.DesiredHeadwayModel;
import org.opentrafficsim.road.gtu.lane.tactical.following.DesiredSpeedModel;
import org.opentrafficsim.road.gtu.lane.tactical.following.IdmPlus;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameterSnapshot;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;

/**
 * Custom implementation of the IDM+ for the MiRoVA framework supporting dynamic headways.
 * <p>
 * This class extends the standard IDM+ and implements the {@link DynamicHeadwayProvider} interface. It exposes dynamic headway
 * calculations safely to the cognitive layers and intercepts the interaction term to filter out unrealistic deceleration spikes
 * (e.g., during cut-ins). It applies a stateless kinematic bounding: if the raw IDM+ deceleration exceeds comfortable limits,
 * it caps the deceleration at {@code BCRIT} unless physical kinematics strictly demand a hard emergency braking
 * ({@code B_MAX}).
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class MirovaIdmPlus extends AbstractIdm implements DynamicHeadwayProvider, Serializable
{
    /** Serial version UID for serialization. */
    private static final long serialVersionUID = 20260430L;

    /**
     * MiRoVA-specific desired headway model with optional capacity drop mechanism.
     * <p>
     * Extends the standard IDM headway {@code s0 + v * T} with a speed-dependent capacity drop addon. When the capacity drop
     * is enabled ({@link MirovaParameters#CAPACITY_DROP_ENABLED}), an additional headway term is added at low speeds:
     * </p>
     *
     * <pre>
     *   T_eff = T + alpha(v) * T_DISCHARGE_ADDON
     *   alpha(v) = max(0, (V_CRIT_DISCHARGE - v) / V_CRIT_DISCHARGE)
     * </pre>
     * <p>
     * The linear ramp factor alpha(v) ensures a smooth transition: full addon at standstill, zero addon at or above
     * V_CRIT_DISCHARGE. This models the empirically observed capacity drop where discharge flow from congestion is 5-20% lower
     * than pre-breakdown capacity, without requiring a separate relaxation mechanism.
     * </p>
     */
    public static final DesiredHeadwayModel MIROVA_HEADWAY = new DesiredHeadwayModel()
    {
        @Override
        public Length desiredHeadway(final Parameters parameters, final Speed speed) throws ParameterException
        {
            // T stays a live lookup: it is overwritten at runtime by PreventUndercuttingPattern and by the LMRS
            // Tailgating class, so it is not part of the snapshot.
            double tBase = parameters.getParameter(T).si;
            double vSi = speed.si;
            double tEff = tBase;

            MirovaParameterSnapshot snapshot = MirovaParameterSnapshot.ofOrNull(parameters);
            double s0 = snapshot != null ? snapshot.s0Si : parameters.getParameter(S0).si;

            tEff = CapacityDrop.absoluteHeadwayTime(parameters, snapshot, vSi, tEff);

            return Length.instantiateSI(s0 + vSi * tEff);
        }
    };

    /**
     * Default constructor using the MiRoVA headway model (with capacity drop support) and default desired speed.
     */
    public MirovaIdmPlus()
    {
        super(MIROVA_HEADWAY, DESIRED_SPEED);
    }

    /**
     * Constructor with modular models for desired headway and desired speed.
     * @param desiredHeadwayModel DesiredHeadwayModel; the desired headway model to use.
     * @param desiredSpeedModel DesiredSpeedModel; the desired speed model to use.
     */
    public MirovaIdmPlus(final DesiredHeadwayModel desiredHeadwayModel, final DesiredSpeedModel desiredSpeedModel)
    {
        super(desiredHeadwayModel, desiredSpeedModel);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final String getName()
    {
        return "MiRoVA-IDM+";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final String getLongName()
    {
        return "MiRoVA Intelligent Driver Model+ with Kinematic Bounding";
    }

    /**
     * {@inheritDoc}
     * <p>
     * Safely wraps the protected method from {@link AbstractIdm} to make it available to the MiRoVA cognitive layers via the
     * {@link DynamicHeadwayProvider} interface.
     * </p>
     */
    @Override
    public Length calculateDynamicDesiredHeadway(final Parameters parameters, final Speed speed, final Length desiredHeadway,
            final Speed leaderSpeed) throws ParameterException
    {
        return super.dynamicDesiredHeadway(parameters, speed, desiredHeadway, leaderSpeed);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Obtains the raw IDM+ interaction acceleration and applies stateless kinematic bounding. If the IDM+ demands a
     * deceleration stronger than {@code B_CRIT}, the physically required deceleration is computed: when the physics
     * tolerate it the deceleration is capped at {@code B_CRIT}, otherwise the requirement itself is applied, bounded
     * by {@code B_MAX}.
     * </p>
     * <p>
     * <b>The distance this sees may not be the real one.</b> {@code MirovaCarFollowingUtil} hands the model a synthetic
     * leader carrying the relaxation's virtual buffers, so the kinematic check runs on an enlarged gap and a reduced
     * speed difference while a relaxation is active - it can conclude that {@code B_CRIT} suffices where the physical
     * gap says otherwise. The physical net therefore sits in that utility, on the unmodified perception; what remains
     * here is the comfort filter this method was written to be.
     * </p>
     * @param aFree Acceleration; the acceleration calculated for free-flow conditions.
     * @param parameters Parameters; the parameter set of the GTU.
     * @param speed Speed; the current speed of the ego GTU.
     * @param desiredSpeed Speed; the desired speed of the ego GTU.
     * @param desiredHeadway Length; the static desired headway.
     * @param leaders PerceptionIterable&lt;? extends Headway&gt;; the perceived leading vehicles.
     * @return Acceleration; the bounded interaction acceleration.
     * @throws ParameterException if a required parameter is missing.
     */
    @Override
    protected final Acceleration combineInteractionTerm(final Acceleration aFree, final Parameters parameters,
            final Speed speed, final Speed desiredSpeed, final Length desiredHeadway,
            final PerceptionIterable<? extends Headway> leaders) throws ParameterException
    {
        // 0. Capacity drop, relative form. The absolute form lives in MIROVA_HEADWAY; this one has to sit here
        // because the desired-headway model is handed only the parameters and the current speed, while the ramp is
        // defined against what this vehicle wants to drive - which is passed to this method and to no other.
        Length effectiveHeadway =
                CapacityDrop.relativeDesiredHeadway(parameters, speed, desiredSpeed, desiredHeadway);

        // 1. Get raw IDM+ acceleration using the superclass implementation
        MirovaParameterSnapshot snapshot = MirovaParameterSnapshot.ofOrNull(parameters);
        Acceleration a = parameters.getParameter(A);
        Headway leader = leaders.first();
        double sRatio =
                dynamicDesiredHeadway(parameters, speed, effectiveHeadway, leader.getSpeed()).si / leader.getDistance().si;
        double aInt = a.si * (1 - sRatio * sRatio);
        Acceleration aIdm = new Acceleration(aInt < aFree.si ? aInt : aFree.si, AccelerationUnit.SI);
        Acceleration bCrit =
                snapshot != null ? snapshot.bCritMirovaScalar : parameters.getParameter(MirovaParameters.B_CRIT);

        // 2. If IDM deceleration is within comfortable limits (or we are accelerating), accept it
        if (aIdm.si >= bCrit.si)
        {
            return aIdm;
        }

        // 3. We are exceeding bCrit. Assess kinematic necessity.
        Length s = leader.getDistance();
        Length s0 = snapshot != null ? snapshot.s0Scalar : parameters.getParameter(ParameterTypes.S0);
        Speed deltaV = speed.minus(leader.getSpeed());

        double dKinSi = 0.0; // Required deceleration (positive value)

        if (deltaV.gt0() && s.gt(s0))
        {
            // d_kin = (dv^2) / (2 * (s - s0))
            dKinSi = -(deltaV.si * deltaV.si) / (2.0 * (s.si - s0.si));
        }
        else if (s.le(s0) && deltaV.gt0())
        {
            // Crash imminent
            dKinSi = Double.NEGATIVE_INFINITY;
        }

        // 4. Apply stateless fallback logic
        if (dKinSi >= bCrit.si)
        {
            // Physics allow us to handle this with a comfortable critical brake (filters cut-in shock)
            return bCrit;
        }

        // Physics demand more than the critical brake: give them exactly what they demand, bounded by B_MAX.
        //
        // The two branches this replaces both misstated that. One returned B_MAX for any requirement between B_CRIT
        // and B_MAX, so a situation calling for -4.0 m/s was braked at -6.0; the other returned a hard-coded
        // -9.0 m/s for anything beyond B_MAX, which made "maximum deceleration" not a maximum at all and left a
        // value in the model that no parameter could reach or explain.
        Acceleration bMax = snapshot != null ? snapshot.bMaxScalar : parameters.getParameter(MirovaParameters.B_MAX);
        return Acceleration.instantiateSI(Math.max(dKinSi, bMax.si));
    }

}
