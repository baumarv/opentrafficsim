package org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ReactiveLayer;

import org.djunits.value.vdouble.scalar.Length;
import org.djunits.value.vdouble.scalar.Speed;
import org.opentrafficsim.base.parameters.ParameterException;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.base.parameters.Parameters;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameterSnapshot;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;

/**
 * The capacity drop: a queue discharges at a lower flow than the facility carried before it broke down.
 * <p>
 * Without it the model reproduces the opposite. Measured over 432 runs on nine days it discharged 4.8 to 8.4 %
 * <i>more</i> than it carried before breakdown, against a field measurement of some 10 % less - the one quantity of
 * the whole calibration whose sign was wrong. The mechanism widens the desired headway as a vehicle slows, which
 * lowers the flow it discharges at without touching the capacity it carries in free flow.
 * </p>
 * <h2>Two formulations</h2>
 * <p>
 * <b>Absolute</b>, the original: {@code T_eff = T + alpha(v) * T_DISCHARGE_ADDON} with
 * {@code alpha(v) = max(0, (V_CRIT_DISCHARGE - v) / V_CRIT_DISCHARGE)}.
 * </p>
 * <p>
 * <b>Relative</b>: {@code T_eff = T * (1 + f_T * alpha)} with
 * {@code alpha = max(0, (f_v * v_desired - v) / (f_v * v_desired))}. Both axes are fractions for a reason. An addon
 * in seconds is a different fraction of a car's headway than of a truck's - 0.4 s is 40 % at {@code T} = 1.00 and
 * 31 % at 1.30 - so an absolute addon produces a capacity drop that differs by vehicle type without anyone having
 * chosen that. And an absolute speed threshold treats a truck wanting 80 km/h and a car wanting 130 as being in the
 * same traffic state at the same speed; it also sits awkwardly against the target, since a detector's jam speed is a
 * harmonic mean over the cross-section with individual vehicles above and below it.
 * </p>
 * <p>
 * The two are <b>mutually exclusive by construction</b>: {@link #absoluteHeadwayTime} declines to act whenever the
 * relative fraction is set, so a configuration gets one or the other and never both stacked. With both fractions at
 * their default of zero, and the enable flag at its default of {@code false}, every method here returns its input
 * unchanged.
 * </p>
 * <h2>Why this is a class rather than inline code</h2>
 * <p>
 * The reactive layer keeps its behavioural mechanisms separable: relaxation and the cooperative gap reserve live in
 * {@link MirovaCarFollowingUtil} as perception-space blocks and work with any car-following model. The capacity drop
 * was written inline in {@code MirovaIdmPlus} and could not be reused, inspected or tested on its own. Gathering it
 * here does not yet make it model-independent - the relative form is still invoked from the interaction term,
 * because that is the only place the desired speed its threshold needs is available - but it does put the whole
 * mechanism in one file, with one set of parameters and one description.
 * </p>
 * <p>
 * Making it genuinely model-independent means expressing it in perception space, as the other blocks are: widening
 * the desired gap is equivalent, at the equilibrium the model settles at, to shrinking the perceived distance. That
 * is a change of behaviour and not a refactoring, because the two agree at equilibrium but not transiently - raising
 * {@code T} enlarges the desired gap directly, whereas shrinking the perceived distance changes the ratio of
 * desired to perceived gap,
 * so the two react differently while a gap is closing. It is therefore left alone here.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See OpenTrafficSim License.
 * </p>
 * @author Marvin Baumann
 */
public final class CapacityDrop
{

    /** Utility class. */
    private CapacityDrop()
    {
        //
    }

    /**
     * Returns whether the capacity drop is switched on for this vehicle.
     * @param parameters Parameters; the parameter set of the GTU
     * @param snapshot MirovaParameterSnapshot; the constant-parameter snapshot, or {@code null} if absent
     * @return boolean; whether the mechanism is enabled
     * @throws ParameterException if the parameter is missing
     */
    public static boolean isEnabled(final Parameters parameters, final MirovaParameterSnapshot snapshot)
            throws ParameterException
    {
        return snapshot != null ? snapshot.capacityDropEnabled
                : parameters.getParameter(MirovaParameters.CAPACITY_DROP_ENABLED);
    }

    /**
     * Returns the ramp threshold as a fraction of the desired speed; zero selects the absolute formulation.
     * @param parameters Parameters; the parameter set of the GTU
     * @param snapshot MirovaParameterSnapshot; the constant-parameter snapshot, or {@code null} if absent
     * @return double; the fraction, zero when the relative formulation is not in use
     * @throws ParameterException if the parameter is missing
     */
    public static double thresholdFraction(final Parameters parameters, final MirovaParameterSnapshot snapshot)
            throws ParameterException
    {
        return snapshot != null ? snapshot.vCritDischargeFraction
                : parameters.getParameter(MirovaParameters.V_CRIT_DISCHARGE_FRACTION);
    }

    /**
     * Applies the absolute formulation to a headway time, for the desired-headway model.
     * <p>
     * Returns {@code tBaseSi} unchanged when the mechanism is off, when the relative formulation is in use, or when
     * the vehicle is at or above the threshold.
     * </p>
     * @param parameters Parameters; the parameter set of the GTU
     * @param snapshot MirovaParameterSnapshot; the constant-parameter snapshot, or {@code null} if absent
     * @param vSi double; the current speed in m/s
     * @param tBaseSi double; the desired headway time in s, before the capacity drop
     * @return double; the effective headway time in s
     * @throws ParameterException if a required parameter is missing
     */
    public static double absoluteHeadwayTime(final Parameters parameters, final MirovaParameterSnapshot snapshot,
            final double vSi, final double tBaseSi) throws ParameterException
    {
        // The relative formulation supersedes this one and is applied in the interaction term, where the desired
        // speed its threshold is defined against is available. Skipping here keeps the two from stacking.
        if (!isEnabled(parameters, snapshot) || thresholdFraction(parameters, snapshot) > 0.0)
        {
            return tBaseSi;
        }
        double vCritSi = snapshot != null ? snapshot.vCritDischargeSi
                : parameters.getParameter(MirovaParameters.V_CRIT_DISCHARGE).si;
        if (vSi >= vCritSi || vCritSi <= 0.0)
        {
            return tBaseSi;
        }
        double alpha = (vCritSi - vSi) / vCritSi;
        double deltaT = snapshot != null ? snapshot.tDischargeAddonSi
                : parameters.getParameter(MirovaParameters.T_DISCHARGE_ADDON).si;
        return tBaseSi + alpha * deltaT;
    }

    /**
     * Applies the relative formulation to a desired headway, for the interaction term.
     * <p>
     * Returns {@code desiredHeadway} unchanged when the mechanism is off, when either fraction is zero, when the
     * desired speed is unknown, or when the vehicle is at or above the threshold.
     * </p>
     * @param parameters Parameters; the parameter set of the GTU
     * @param speed Speed; the current speed
     * @param desiredSpeed Speed; the speed this vehicle wants to drive
     * @param desiredHeadway Length; the desired headway before the capacity drop
     * @return Length; the desired headway with the capacity drop applied
     * @throws ParameterException if a required parameter is missing
     */
    public static Length relativeDesiredHeadway(final Parameters parameters, final Speed speed,
            final Speed desiredSpeed, final Length desiredHeadway) throws ParameterException
    {
        MirovaParameterSnapshot snapshot = MirovaParameterSnapshot.ofOrNull(parameters);
        if (!isEnabled(parameters, snapshot))
        {
            return desiredHeadway;
        }
        double vFraction = thresholdFraction(parameters, snapshot);
        double tFraction = snapshot != null ? snapshot.tDischargeFraction
                : parameters.getParameter(MirovaParameters.T_DISCHARGE_FRACTION);
        if (vFraction <= 0.0 || tFraction <= 0.0 || desiredSpeed == null || desiredSpeed.si <= 0.0)
        {
            return desiredHeadway;
        }
        double vThreshold = vFraction * desiredSpeed.si;
        if (speed.si >= vThreshold)
        {
            return desiredHeadway;
        }
        double alpha = (vThreshold - speed.si) / vThreshold;
        // T stays a live lookup: it is overwritten at runtime by PreventUndercuttingPattern and by the LMRS
        // Tailgating class, so it is not part of the snapshot.
        double deltaT = tFraction * parameters.getParameter(ParameterTypes.T).si;
        return Length.instantiateSI(desiredHeadway.si + speed.si * alpha * deltaT);
    }
}
