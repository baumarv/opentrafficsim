package org.opentrafficsim.road.gtu.lane.tactical.mirova.core;

import java.io.Serializable;

import org.djunits.value.vdouble.scalar.Acceleration;
import org.djunits.value.vdouble.scalar.Duration;
import org.djunits.value.vdouble.scalar.Length;
import org.djunits.value.vdouble.scalar.Speed;
import org.opentrafficsim.base.parameters.ParameterException;
import org.opentrafficsim.base.parameters.ParameterType;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.base.parameters.Parameters;

/**
 * Immutable per-GTU snapshot of all behavioural parameters that are constant over the lifetime of a vehicle.
 * <p>
 * Reading a parameter through {@link Parameters#getParameter} is a hash map lookup on a {@code ParameterType} key, followed by
 * unwrapping a boxed DJUnits scalar. That is cheap in isolation but not free, and the MiRoVA layers perform it several hundred
 * times per tick per vehicle: {@code VCONG} and {@code DT} alone are read at 35 call sites each. This class resolves every
 * such parameter exactly once, when the vehicle is created, and exposes the results as plain fields.
 * </p>
 * <p>
 * <b>Why primitive doubles here, against the general DJUnits rule.</b> The coding standard requires DJUnits types for physical
 * values, and it holds everywhere else. This class is the single, deliberate exception: it is the boundary at which typed
 * parameters are converted into the SI doubles that the arithmetic actually runs on. Every primitive field carries the
 * {@code Si} suffix to make the unit explicit at the point of use, and the DJUnits scalars are kept alongside in the
 * {@code ...Scalar} fields for the call sites that need an object (car-following interfaces, plan construction). Those scalars
 * are built once and shared, replacing per-tick allocations.
 * </p>
 * <p>
 * <b>What is deliberately absent.</b> Only parameters that are never written after vehicle creation belong here. Excluded are:
 * </p>
 * <ul>
 * <li>{@code ParameterTypes.T} - overwritten by {@code PreventUndercuttingPattern} and by the LMRS {@code Tailgating}
 * class;</li>
 * <li>{@code ParameterTypes.LOOKAHEAD} - overwritten by {@code InfrastructureContext} for the anticipation boost;</li>
 * <li>{@code DLC}, {@code DLEFT}, {@code DRIGHT}, {@code RHO}, {@code TMIN}, {@code TMAX} - written each tick by the OTS
 * core.</li>
 * </ul>
 * <p>
 * These remain genuine mutable state and must keep going through {@link Parameters#getParameter}. Adding a field here for a
 * parameter that is written at runtime would silently freeze it at its creation-time value.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public final class MirovaParameterSnapshot implements Serializable
{

    /** Serial version UID for serialization. */
    private static final long serialVersionUID = 20260902L;

    /**
     * The parameter type under which a vehicle carries its own snapshot.
     * <p>
     * This is the handle through which the snapshot is reached from code that is handed a {@link Parameters} object and
     * nothing else - above all the car-following models, whose interfaces carry no vehicle reference. One map lookup on this
     * key replaces the several that such a model would otherwise perform per evaluation.
     * </p>
     * <p>
     * It deliberately lives here rather than in {@link MirovaParameters}: that class is passed to
     * {@code ParameterSet.setDefaultParameters}, which reflects over every parameter type it declares and demands a default
     * value from each. A snapshot has no sensible default - a vehicle that was not built through the MiRoVA tactical planner
     * simply has none, and reading it should fail loudly rather than hand out parameters belonging to nobody.
     * </p>
     */
    public static final ParameterType<MirovaParameterSnapshot> TYPE = new ParameterType<>("mirovaSnapshot",
            "Per-vehicle snapshot of the constant MiRoVA parameters", MirovaParameterSnapshot.class);

    // ----------------------------------------------------------------------
    // Core OTS parameters (constant per vehicle)
    // ----------------------------------------------------------------------

    /** Simulation time step [s]. */
    public final double dtSi;

    /** Simulation time step, as a scalar. */
    public final Duration dtScalar;

    /** Speed threshold below which traffic counts as congested [m/s]. */
    public final double vCongSi;

    /** Speed threshold below which traffic counts as congested, as a scalar. */
    public final Speed vCongScalar;

    /** Stopping distance, i.e. desired gap at standstill [m]. */
    public final double s0Si;

    /** Stopping distance, as a scalar. */
    public final Length s0Scalar;

    /** Maximum comfortable acceleration [m/s2]. */
    public final double aSi;

    /** Maximum comfortable deceleration, positive by OTS convention [m/s2]. */
    public final double bSi;

    /** Critical deceleration, positive by OTS convention [m/s2]. */
    public final double bCritSi;

    /** Look-ahead time [s]. */
    public final double t0Si;

    // ----------------------------------------------------------------------
    // Tactical lane changing
    // ----------------------------------------------------------------------

    /** Desire threshold for a free lane change [-]. */
    public final double dFree;

    /** Desire threshold for a mandatory lane change [-]. */
    public final double dMand;

    /** Desire threshold for an active gap search [-]. */
    public final double dSearch;

    /** Additional distance required for emergency stopping maneuvers [m]. */
    public final double emergencyStoppingDistanceSi;

    /** Additional distance required for emergency stopping maneuvers, as a scalar. */
    public final Length emergencyStoppingDistanceScalar;

    /** Time after which a stopped vehicle is removed to prevent gridlock [s]. */
    public final double vehicleDiffusionTimeSi;

    /** Look-ahead distance to check for mandatory lane changes [m]. */
    public final double mandatoryLaneChangeLookAheadDistanceSi;

    /** Extended look-ahead distance for lane change decisions [m]. */
    public final double extendedLookAheadDistanceSi;

    /** Extended look-ahead distance, as a scalar. */
    public final Length extendedLookAheadDistanceScalar;

    /** Lane change duration in low speed, congested situations [s]. */
    public final double congestedLaneChangeDurationSi;

    /** Lane change duration in congested situations, as a scalar. */
    public final Duration congestedLaneChangeDurationScalar;

    /** MiRoVA critical deceleration, strictly negative [m/s2]. */
    public final double bCritMirovaSi;

    /** MiRoVA critical deceleration, as a scalar. */
    public final Acceleration bCritMirovaScalar;

    /** MiRoVA maximum deceleration, strictly negative [m/s2]. */
    public final double bMaxSi;

    /** MiRoVA maximum deceleration, as a scalar. */
    public final Acceleration bMaxScalar;

    /** MiRoVA maximum acceleration [m/s2]. */
    public final double aMaxSi;

    /** MiRoVA maximum acceleration, as a scalar. */
    public final Acceleration aMaxScalar;

    /** Scaling factor for the maximum physical acceleration [-]. */
    public final double accelerationScalingFactor;

    /** Speed below which the vehicle counts as standing still [m/s]. */
    public final double standstillSpeedThresholdSi;

    /** Speed below which the vehicle counts as standing still, as a scalar. */
    public final Speed standstillSpeedThresholdScalar;

    // ----------------------------------------------------------------------
    // Social interaction
    // ----------------------------------------------------------------------

    /** Speed gain threshold for lane change desire [m/s]. */
    public final double vGainSi;

    /** Critical speed for social interaction [m/s]. */
    public final double vCritSi;

    /** Sensitivity to speed-related social pressure [-]. */
    public final double socioSpeedSensitivity;

    /** Waiting time before a lane change in the opposite direction [s]. */
    public final double socialInteractionCooldownSi;

    // ----------------------------------------------------------------------
    // Lane change safety
    // ----------------------------------------------------------------------

    /** Factor reducing the safety distance during a lane change [-]. */
    public final double safetyDistanceReductionFactorLaneChange;

    /** Minimum deceleration imposed on a follower during a lane change [m/s2]. */
    public final double minFollowerDecelerationThresholdSi;

    /** Minimum deceleration imposed on a follower, as a scalar. */
    public final Acceleration minFollowerDecelerationThresholdScalar;

    /** Maximum deceleration imposed on a follower during a lane change [m/s2]. */
    public final double maxFollowerDecelerationThresholdSi;

    /** Maximum deceleration imposed on a follower, as a scalar. */
    public final Acceleration maxFollowerDecelerationThresholdScalar;

    /** Minimum deceleration accepted by the ego vehicle during a lane change [m/s2]. */
    public final double minEgoDecelerationThresholdSi;

    /** Minimum deceleration accepted by the ego vehicle, as a scalar. */
    public final Acceleration minEgoDecelerationThresholdScalar;

    /** Maximum deceleration accepted by the ego vehicle during a lane change [m/s2]. */
    public final double maxEgoDecelerationThresholdSi;

    /** Maximum deceleration accepted by the ego vehicle, as a scalar. */
    public final Acceleration maxEgoDecelerationThresholdScalar;

    // ----------------------------------------------------------------------
    // Cooperation
    // ----------------------------------------------------------------------

    /** Deceleration threshold for cooperative maneuvers [m/s2]. */
    public final double cooperativeDecelerationThresholdSi;

    /** Deceleration threshold for cooperative maneuvers, as a scalar. */
    public final Acceleration cooperativeDecelerationThresholdScalar;

    /** Deceleration for preemptive cooperative maneuvers [m/s2]. */
    public final double preemptiveCooperativeDecelerationSi;

    /** Deceleration for preemptive cooperative maneuvers, as a scalar. */
    public final Acceleration preemptiveCooperativeDecelerationScalar;

    /** Whether cooperative lane changes are enabled. */
    public final boolean cooperativeLaneChangesEnabled;

    /** Whether far-range speed anticipation is enabled. */
    public final boolean farAnticipationEnabled;

    /** Look-ahead distance over which gap opening for cooperation is considered [m]. */
    public final double considerGapOpeningLookaheadDistanceSi;

    /** Look-ahead distance for gap opening, as a scalar. */
    public final Length considerGapOpeningLookaheadDistanceScalar;

    // ----------------------------------------------------------------------
    // Undercutting, relaxation and car-following
    // ----------------------------------------------------------------------

    /** Time-to-collision threshold below which undercutting is prevented [s]. */
    public final double undercuttingTtcThresholdSi;

    /** Time-to-collision threshold below which undercutting is prevented, as a scalar. */
    public final Duration undercuttingTtcThresholdScalar;

    /** Spatial relaxation time constant tau_s [s]. */
    public final double relaxationTauSpaceSi;

    /** Spatial relaxation time constant, as a scalar. */
    public final Duration relaxationTauSpaceScalar;

    /** Speed relaxation time constant tau_v [s]. */
    public final double relaxationTauSpeedSi;

    /** Speed relaxation time constant, as a scalar. */
    public final Duration relaxationTauSpeedScalar;

    /** Time-to-collision threshold for emergency braking [s]. */
    public final double ttcEmergencyBrakingSi;

    /** Maximum number of leaders considered in car-following [-]. */
    public final double cfMaxLeaders;

    /** Whether acceleration damping during active headway relaxation is enabled. */
    public final boolean relaxationAccDampingEnabled;

    /** Acceleration scaling factor during active headway relaxation [-]. */
    public final double relaxationAccDampingFactor;

    // ----------------------------------------------------------------------
    // Capacity drop
    // ----------------------------------------------------------------------

    /** Whether the capacity drop mechanism is enabled. */
    public final boolean capacityDropEnabled;

    /** Additional time headway applied during congested discharge [s]. */
    public final double tDischargeAddonSi;

    /** Critical speed threshold for the capacity drop ramp [m/s]. */
    public final double vCritDischargeSi;

    /** Additional desired headway during congested discharge, as a fraction of T [-]. */
    public final double tDischargeFraction;

    /** Capacity drop ramp threshold, as a fraction of the desired speed [-]. */
    public final double vCritDischargeFraction;

    /**
     * Resolves every constant parameter of the given set exactly once.
     * @param p the parameter set of the vehicle, fully populated
     * @throws ParameterException if any of the parameters read here was not set
     */
    public MirovaParameterSnapshot(final Parameters p) throws ParameterException
    {
        this.dtScalar = p.getParameter(ParameterTypes.DT);
        this.dtSi = this.dtScalar.si;
        this.vCongScalar = p.getParameter(ParameterTypes.VCONG);
        this.vCongSi = this.vCongScalar.si;
        this.s0Scalar = p.getParameter(ParameterTypes.S0);
        this.s0Si = this.s0Scalar.si;
        this.aSi = p.getParameter(ParameterTypes.A).si;
        this.bSi = p.getParameter(ParameterTypes.B).si;
        this.bCritSi = p.getParameter(ParameterTypes.BCRIT).si;
        this.t0Si = p.getParameter(ParameterTypes.T0).si;

        this.dFree = p.getParameter(MirovaParameters.DFREE);
        this.dMand = p.getParameter(MirovaParameters.DMAND);
        this.dSearch = p.getParameter(MirovaParameters.DSEARCH);
        this.emergencyStoppingDistanceScalar = p.getParameter(MirovaParameters.emergencyStoppingDistance);
        this.emergencyStoppingDistanceSi = this.emergencyStoppingDistanceScalar.si;
        this.vehicleDiffusionTimeSi = p.getParameter(MirovaParameters.vehicleDiffusionTime).si;
        this.mandatoryLaneChangeLookAheadDistanceSi =
                p.getParameter(MirovaParameters.mandatoryLaneChangeLookAheadDistance).si;
        this.extendedLookAheadDistanceScalar = p.getParameter(MirovaParameters.extendedLookAheadDistance);
        this.extendedLookAheadDistanceSi = this.extendedLookAheadDistanceScalar.si;
        this.congestedLaneChangeDurationScalar = p.getParameter(MirovaParameters.congestedLaneChangeDuration);
        this.congestedLaneChangeDurationSi = this.congestedLaneChangeDurationScalar.si;
        this.bCritMirovaScalar = p.getParameter(MirovaParameters.B_CRIT);
        this.bCritMirovaSi = this.bCritMirovaScalar.si;
        this.bMaxScalar = p.getParameter(MirovaParameters.B_MAX);
        this.bMaxSi = this.bMaxScalar.si;
        this.aMaxScalar = p.getParameter(MirovaParameters.A_MAX);
        this.aMaxSi = this.aMaxScalar.si;
        this.accelerationScalingFactor = p.getParameter(MirovaParameters.ACCELERATION_SCALING_FACTOR);
        this.standstillSpeedThresholdScalar = p.getParameter(MirovaParameters.standstill_speed_threshold);
        this.standstillSpeedThresholdSi = this.standstillSpeedThresholdScalar.si;

        this.vGainSi = p.getParameter(MirovaParameters.vGain).si;
        this.vCritSi = p.getParameter(MirovaParameters.vCrit).si;
        this.socioSpeedSensitivity = p.getParameter(MirovaParameters.socioSpeedSensitivity);
        this.socialInteractionCooldownSi = p.getParameter(MirovaParameters.socialInteractionCooldown).si;

        this.safetyDistanceReductionFactorLaneChange =
                p.getParameter(MirovaParameters.safetyDistanceReductionFactorLaneChange);
        this.minFollowerDecelerationThresholdScalar = p.getParameter(MirovaParameters.minFollowerDecelerationThreshold);
        this.minFollowerDecelerationThresholdSi = this.minFollowerDecelerationThresholdScalar.si;
        this.maxFollowerDecelerationThresholdScalar = p.getParameter(MirovaParameters.maxFollowerDecelerationThreshold);
        this.maxFollowerDecelerationThresholdSi = this.maxFollowerDecelerationThresholdScalar.si;
        this.minEgoDecelerationThresholdScalar = p.getParameter(MirovaParameters.minEgoDecelerationThreshold);
        this.minEgoDecelerationThresholdSi = this.minEgoDecelerationThresholdScalar.si;
        this.maxEgoDecelerationThresholdScalar = p.getParameter(MirovaParameters.maxEgoDecelerationThreshold);
        this.maxEgoDecelerationThresholdSi = this.maxEgoDecelerationThresholdScalar.si;

        this.cooperativeDecelerationThresholdScalar = p.getParameter(MirovaParameters.cooperativeDecelerationThreshold);
        this.cooperativeDecelerationThresholdSi = this.cooperativeDecelerationThresholdScalar.si;
        this.preemptiveCooperativeDecelerationScalar = p.getParameter(MirovaParameters.preemptiveCooperativeDeceleration);
        this.preemptiveCooperativeDecelerationSi = this.preemptiveCooperativeDecelerationScalar.si;
        this.cooperativeLaneChangesEnabled = p.getParameter(MirovaParameters.cooperativeLaneChangesEnabled);
        this.farAnticipationEnabled = p.getParameter(MirovaParameters.farAnticipationEnabled);
        this.considerGapOpeningLookaheadDistanceScalar =
                p.getParameter(MirovaParameters.considerGapOpeningLookaheadDistance);
        this.considerGapOpeningLookaheadDistanceSi = this.considerGapOpeningLookaheadDistanceScalar.si;

        this.undercuttingTtcThresholdScalar = p.getParameter(MirovaParameters.undercuttingTTCThreshold);
        this.undercuttingTtcThresholdSi = this.undercuttingTtcThresholdScalar.si;
        this.relaxationTauSpaceScalar = p.getParameter(MirovaParameters.RELAXATION_TAU_SPACE);
        this.relaxationTauSpaceSi = this.relaxationTauSpaceScalar.si;
        this.relaxationTauSpeedScalar = p.getParameter(MirovaParameters.RELAXATION_TAU_SPEED);
        this.relaxationTauSpeedSi = this.relaxationTauSpeedScalar.si;
        this.ttcEmergencyBrakingSi = p.getParameter(MirovaParameters.ttc_emergency_braking).si;
        this.cfMaxLeaders = p.getParameter(MirovaParameters.CF_MAX_LEADERS);
        this.relaxationAccDampingEnabled = p.getParameter(MirovaParameters.RELAXATION_ACC_DAMPING_ENABLED);
        this.relaxationAccDampingFactor = p.getParameter(MirovaParameters.RELAXATION_ACC_DAMPING_FACTOR);

        this.capacityDropEnabled = p.getParameter(MirovaParameters.CAPACITY_DROP_ENABLED);
        this.tDischargeAddonSi = p.getParameter(MirovaParameters.T_DISCHARGE_ADDON).si;
        this.vCritDischargeSi = p.getParameter(MirovaParameters.V_CRIT_DISCHARGE).si;
        this.tDischargeFraction = p.getParameter(MirovaParameters.T_DISCHARGE_FRACTION);
        this.vCritDischargeFraction = p.getParameter(MirovaParameters.V_CRIT_DISCHARGE_FRACTION);
    }

    /**
     * Builds the snapshot for the given parameter set and stores it in that same set under {@link #TYPE}, so that it is
     * reachable from any code that is handed a {@link Parameters} but no vehicle reference - notably the car-following
     * models, whose interfaces carry nothing else.
     * @param p the parameter set of the vehicle, fully populated
     * @return the snapshot that was installed
     * @throws ParameterException if any of the parameters read by the constructor was not set
     */
    public static MirovaParameterSnapshot install(final Parameters p) throws ParameterException
    {
        MirovaParameterSnapshot snapshot = new MirovaParameterSnapshot(p);
        p.setParameter(TYPE, snapshot);
        return snapshot;
    }

    /**
     * Returns the snapshot installed in the given parameter set.
     * @param p the parameter set of the vehicle
     * @return the snapshot of this vehicle
     * @throws ParameterException if no snapshot was installed, which means the vehicle was not built through the MiRoVA
     *             tactical planner
     */
    public static MirovaParameterSnapshot of(final Parameters p) throws ParameterException
    {
        return p.getParameter(TYPE);
    }

    /**
     * Returns the snapshot installed in the given parameter set, or {@code null} if there is none.
     * <p>
     * Unlike {@link #of}, this tolerates a vehicle that was not built through the MiRoVA tactical planner. That case is real:
     * {@code MirovaIdmPlus} is also used behind a plain {@code LmrsFactory}, whose vehicles carry neither a snapshot nor the
     * MiRoVA parameters it is built from. Call sites that can be reached by such a vehicle must use this method and keep a
     * fallback path reading the parameters directly.
     * </p>
     * @param p the parameter set of the vehicle
     * @return the snapshot of this vehicle, or {@code null} if none was installed
     */
    public static MirovaParameterSnapshot ofOrNull(final Parameters p)
    {
        return p.getParameterOrNull(TYPE);
    }

    /**
     * Returns the desired speed of the vehicle, which is not part of the snapshot because it depends on the speed limit in
     * force and therefore changes along the route.
     * @param p the parameter set of the vehicle
     * @param speedLimit the speed limit currently in force
     * @return the desired speed
     * @throws ParameterException if the speed factor was not set
     */
    public static Speed desiredSpeed(final Parameters p, final Speed speedLimit) throws ParameterException
    {
        return speedLimit.times(p.getParameter(ParameterTypes.FSPEED));
    }

    @Override
    public String toString()
    {
        return "MirovaParameterSnapshot [dt=" + this.dtSi + "s, vCong=" + this.vCongSi + "m/s, s0=" + this.s0Si + "m, tauS="
                + this.relaxationTauSpaceSi + "s, tauV=" + this.relaxationTauSpeedSi + "s, capacityDrop="
                + this.capacityDropEnabled + "]";
    }
}
