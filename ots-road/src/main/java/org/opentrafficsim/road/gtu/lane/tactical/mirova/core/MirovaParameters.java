package org.opentrafficsim.road.gtu.lane.tactical.mirova.core;

import org.djunits.unit.SpeedUnit;
import org.djunits.value.vdouble.scalar.Acceleration;
import org.djunits.value.vdouble.scalar.Duration;
import org.djunits.value.vdouble.scalar.Length;
import org.djunits.value.vdouble.scalar.Speed;
import org.opentrafficsim.base.parameters.ParameterTypeAcceleration;
import org.opentrafficsim.base.parameters.ParameterTypeBoolean;
import org.opentrafficsim.base.parameters.ParameterTypeDouble;
import org.opentrafficsim.base.parameters.ParameterTypeDuration;
import org.opentrafficsim.base.parameters.ParameterTypeLength;
import org.opentrafficsim.base.parameters.ParameterTypeSpeed;
import org.opentrafficsim.base.parameters.constraint.ConstraintInterface;
import org.opentrafficsim.base.parameters.constraint.NumericConstraint;

/**
 * Defines the specific parameters used within the MiRoVA tactical planner framework.
 * <p>
 * These parameters govern various aspects of the cognitive and tactical layers, including lane change desires (LMRS based),
 * social interactions, cooperation thresholds, and safety margins.
 * </p>
 * <p>
 * Copyright (c) 2025 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public final class MirovaParameters implements ConstraintInterface
{

        /**
         * Private constructor to prevent instantiation of this parameter utility class.
         */
        private MirovaParameters()
        {
                // prevent instantiation
        }

        // ----------------------------------------------------------------------
        // Tactical lane changing parameters
        // ----------------------------------------------------------------------

        /** Desire threshold for free lane change. */
        public static final ParameterTypeDouble DFREE =
                        new ParameterTypeDouble("DFREE", "Desire threshold for free lane change", 0.365, POSITIVE);

        /** Desire threshold for mandatory lane change. */
        public static final ParameterTypeDouble DMAND =
                        new ParameterTypeDouble("DMAND", "Desire threshold for mandatory lane change", 0.577, POSITIVE);

        /** Additional distance required for emergency stopping maneuvers. */
        public static final ParameterTypeLength emergencyStoppingDistance = new ParameterTypeLength(
                        "EMERGENCY_STOPPING_DISTANCE", "Additional distance required for emergency stopping maneuvers",
                        Length.instantiateSI(5.0), POSITIVE);

        /** Time threshold after which a stopped/deadlocked vehicle is removed to prevent gridlock. */
        public static final ParameterTypeDuration vehicleDiffusionTime = new ParameterTypeDuration("VEHICLE_DIFFUSION_TIME",
                        "Time threshold after which a stopped/deadlocked vehicle is removed to prevent gridlock",
                        Duration.instantiateSI(60.0), POSITIVE);

        /** Extended look-ahead distance for lane change decisions. */
        public static final ParameterTypeLength extendedLookAheadDistance = new ParameterTypeLength(
                        "EXTENDED_LOOK_AHEAD_DISTANCE", "Extended look-ahead distance for lane change decisions",
                        Length.instantiateSI(1000.0), POSITIVE);

        /** Lane change duration in low speed, congested situations. */
        public static final ParameterTypeDuration congestedLaneChangeDuration = new ParameterTypeDuration(
                        "CONGESTED_LANE_CHANGE_DURATION", "Lane change duration in low speed, congested situations",
                        Duration.instantiateSI(1.5), POSITIVE);

        /**
         * Critical deceleration limit for the MiRoVA framework.
         * <p>
         * This parameter represents a comfortable but strong braking limit (e.g., stopping for a yellow light). It is strictly
         * defined as a negative acceleration to align directly with kinematic equations.
         * </p>
         */
        public static final ParameterTypeAcceleration B_CRIT =
                        new ParameterTypeAcceleration("bCritMirova", "Critical deceleration (strictly negative acceleration)",
                                        Acceleration.instantiateSI(-3.5), NumericConstraint.NEGATIVE);

        /**
         * Maximum physical deceleration limit for the MiRoVA framework.
         * <p>
         * This parameter represents the absolute emergency braking capability of the vehicle. It is strictly defined as a
         * negative acceleration to align directly with kinematic equations.
         * </p>
         */
        public static final ParameterTypeAcceleration B_MAX =
                        new ParameterTypeAcceleration("bMaxMirova", "Maximum deceleration (strictly negative acceleration)",
                                        Acceleration.instantiateSI(-6.0), NumericConstraint.NEGATIVE);

        // ----------------------------------------------------------------------
        // Social interaction parameters
        // ----------------------------------------------------------------------

        /** Speed gain threshold for lane change desire. */
        public static final ParameterTypeSpeed vGain = new ParameterTypeSpeed("VGAIN",
                        "Speed gain threshold for lane change desire", new Speed(69.6, SpeedUnit.KM_PER_HOUR), POSITIVE);

        /** Sensitivity to speed-related social pressure. */
        public static final ParameterTypeDouble socioSpeedSensitivity = new ParameterTypeDouble("SOCIO_SPEED_SENSITIVITY",
                        "Sensitivity to speed-related social pressure", 0.25, POSITIVE);

        // ----------------------------------------------------------------------
        // Lane change safety parameters
        // ----------------------------------------------------------------------

        /** Factor to reduce safety distance during lane change. */
        public static final ParameterTypeDouble safetyDistanceReductionFactorLaneChange =
                        new ParameterTypeDouble("SAFETY_DISTANCE_REDUCTION_FACTOR_LANE_CHANGE",
                                        "Factor to reduce safety distance during lane change", 0.5, POSITIVE);

        /** Minimum deceleration for follower vehicles in lane change maneuvers. */
        public static final ParameterTypeAcceleration minFollowerDecelerationThreshold = new ParameterTypeAcceleration(
                        "MIN_FOLLOWER_DECELERATION_THRESHOLD", "Minimum deceleration for follower vehicles in lc maneuvers",
                        Acceleration.instantiateSI(-2.0), NEGATIVE);

        /** Maximum deceleration for follower vehicles in lane change maneuvers. */
        public static final ParameterTypeAcceleration maxFollowerDecelerationThreshold = new ParameterTypeAcceleration(
                        "MAX_FOLLOWER_DECELERATION_THRESHOLD", "Maximum deceleration for follower vehicles in lc maneuvers",
                        Acceleration.instantiateSI(-4.0), NEGATIVE);

        /** Minimum deceleration for ego vehicle in lane change maneuvers. */
        public static final ParameterTypeAcceleration minEgoDecelerationThreshold = new ParameterTypeAcceleration(
                        "MIN_EGO_DECELERATION_THRESHOLD", "Minimum deceleration for ego vehicle in lc maneuvers",
                        Acceleration.instantiateSI(-2.0), NEGATIVE);

        /** Maximum deceleration for ego vehicle in lane change maneuvers. */
        public static final ParameterTypeAcceleration maxEgoDecelerationThreshold = new ParameterTypeAcceleration(
                        "MAX_EGO_DECELERATION_THRESHOLD", "Maximum deceleration for ego vehicle in lc maneuvers",
                        Acceleration.instantiateSI(-4.0), NEGATIVE);

        // ----------------------------------------------------------------------
        // Cooperation parameters
        // ----------------------------------------------------------------------

        /** Deceleration threshold for cooperative maneuvers. */
        public static final ParameterTypeAcceleration cooperativeDecelerationThreshold = new ParameterTypeAcceleration(
                        "COOPERATIVE_DECELERATION_THRESHOLD", "Deceleration threshold for cooperative maneuvers",
                        Acceleration.instantiateSI(-3.0), NEGATIVE);

        /** Deceleration for preemptive cooperative maneuvers. */
        public static final ParameterTypeAcceleration preemptiveCooperativeDeceleration = new ParameterTypeAcceleration(
                        "PREEMPTIVE_COOPERATIVE_DECELERATION", "Deceleration for preemptive cooperative maneuvers",
                        Acceleration.instantiateSI(-1.0), NEGATIVE);

        /** Enable cooperative lane changes. */
        public static final ParameterTypeBoolean cooperativeLaneChangesEnabled =
                        new ParameterTypeBoolean("COOPERATIVE_LANE_CHANGES_ENABLED", "Enable cooperative lane changes", true);

        /** Enable far-range speed anticipation. */
        public static final ParameterTypeBoolean farAnticipationEnabled =
                        new ParameterTypeBoolean("FAR_ANTICIPATION_ENABLED", "Enable far-range speed anticipation", true);

        /** Look-ahead distance to consider gap opening for cooperation. */
        public static final ParameterTypeLength considerGapOpeningLookaheadDistance = new ParameterTypeLength(
                        "CONSIDER_GAP_OPENING_LOOKAHEAD_DISTANCE",
                        "Look-ahead distance to consider gap opening for cooperation", Length.instantiateSI(100.0), POSITIVE);

        // ----------------------------------------------------------------------
        // Prevent undercutting parameters
        // ----------------------------------------------------------------------

        /** Time To Collision (TTC) threshold to prevent undercutting. */
        public static final ParameterTypeDuration undercuttingTTCThreshold = new ParameterTypeDuration(
                        "UNDERCUTTING_TIME_HEADWAY", "TTC to prevent undercutting", Duration.instantiateSI(5.0), POSITIVE);

        /**
         * Spatial relaxation time constant (Tau_s) for the Keane and Gao (2021) phenomenon.
         */
        public static final ParameterTypeDuration RELAXATION_TAU_SPACE = new ParameterTypeDuration("tau_relax_s",
                        "Spatial relaxation time constant", Duration.instantiateSI(20.0), ConstraintInterface.POSITIVE);

        /**
         * Leader deceleration at which an active relaxation is abandoned, in m/s^2 (negative).
         * <p>
         * The relaxation lets a follower tolerate the short gap a cut-in leaves it, decaying over
         * {@link #RELAXATION_TAU_SPACE}. It is abandoned outright when the leader brakes harder than this, on the
         * grounds that the buffers are no longer safe to trust.
         * </p>
         * <p>
         * Counted inside the model over one congested hour, the framework's original value of -1.0 m/s^2 ends 26 %
         * of all relaxations, after a mean of 2.0 s. Raising it to -3.5 lengthens those to 9.5 s and reduces them to
         * under 1 % - but the accompanying 10 km/h speed condition absorbs the difference and rises from 66 % to 89 %
         * of endings, at a mean lifetime of well under a second. This threshold therefore governs a minority of
         * endings, and the fixed speed condition the majority.
         * </p>
         * <p>
         * A relaxation left alone by both runs its full course: the 8 % that decay naturally live some 60 s, three
         * time constants, as designed. An earlier figure of a 1.0 s mean lifetime came from reconstructing endings
         * from trajectories, which can only see aborts and only on the 199 m of L4a.
         * </p>
         * <p>
         * Exposed as a parameter so that lifetime can be varied. The default preserves the original behaviour.
         * </p>
         */
        public static final ParameterTypeAcceleration RELAXATION_ABORT_DECELERATION =
                        new ParameterTypeAcceleration("aRelaxAbort", "Leader deceleration abandoning a relaxation",
                                        Acceleration.instantiateSI(-1.0));

        /**
         * How long the buffer takes to fade to zero once a relaxation is abandoned.
         * <p>
         * Abandoning a relaxation used to remove its buffer between one tick and the next, so the follower's
         * perceived distance dropped by the whole remaining buffer at once - measured at a mean of 9.2 m in free flow
         * and 2.2 m in congestion, in over 99 % of aborts. Since the interaction term takes distance quadratically,
         * that is a braking impulse the model inflicts on itself.
         * </p>
         * <p>
         * A non-positive value restores the original behaviour, ending the relaxation immediately.
         * </p>
         */
        public static final ParameterTypeDuration RELAXATION_FADE_DURATION = new ParameterTypeDuration(
                        "tRelaxFade", "Fade-out duration when a relaxation is abandoned", Duration.instantiateSI(1.0));

        /**
         * Longest a relaxation may run, as a multiple of {@link #RELAXATION_TAU_SPACE}.
         * <p>
         * The housekeeping used to collect a relaxation once its buffer fell below an absolute 0.1 m. That makes the
         * lifetime depend on the initial deficit rather than on the time constant: a 10 m deficit takes 92 s to decay
         * under 0.1 m and a 20 m deficit 106 s, so a mechanism declared with a 20 s time constant lingered for a
         * minute and a half. After three time constants the buffer is 5 % of the deficit and the relaxation is over
         * in all but name.
         * </p>
         */
        public static final ParameterTypeDouble RELAXATION_MAX_LIFETIME_FACTOR =
                        new ParameterTypeDouble("relaxMaxLifetime",
                                        "Longest a relaxation may run, in multiples of tau_s", 3.0, POSITIVE);

        public static final ParameterTypeDouble CF_MAX_LEADERS = new ParameterTypeDouble("CF_MAX_LEADERS",
                        "Maximum number of leaders considered in car-following", 2, POSITIVE);

        /**
         * Maximum acceleration for the MiRoVA framework.
         */
        public static final ParameterTypeAcceleration A_MAX = new ParameterTypeAcceleration("aMaxMirova",
                        "Maximum acceleration for MiRoVA", Acceleration.instantiateSI(3.5), POSITIVE);

        // ----------------------------------------------------------------------
        // Capacity drop parameters
        // ----------------------------------------------------------------------

        /**
         * Enable or disable the capacity drop mechanism.
         * <p>
         * When enabled, the desired time headway T is increased at low speeds (below {@link #V_CRIT_DISCHARGE}) using a linear
         * ramp, modelling the empirically observed capacity drop phenomenon where the discharge flow from congestion is lower
         * than the pre-breakdown capacity.
         * </p>
         */
        public static final ParameterTypeBoolean CAPACITY_DROP_ENABLED =
                        new ParameterTypeBoolean("capDropEnabled", "Enable capacity drop headway increase", false);

        /**
         * Additional time headway [s] applied during congested discharge.
         * <p>
         * This value is added to the base headway T when the vehicle speed is below {@link #V_CRIT_DISCHARGE}. The addon is
         * scaled by a linear ramp factor alpha(v) = max(0, (vCrit - v) / vCrit), so at standstill the full addon applies and at
         * vCrit it vanishes smoothly.
         * </p>
         */
        public static final ParameterTypeDuration T_DISCHARGE_ADDON =
                        new ParameterTypeDuration("tDischargeAddon", "Additional time headway during congested discharge",
                                        Duration.instantiateSI(0.5), ConstraintInterface.POSITIVE);

        /**
         * Critical speed threshold [km/h] for the capacity drop ramp.
         * <p>
         * Below this speed, the capacity drop headway addon is applied with a linearly increasing factor. Above this speed, the
         * standard headway T is used without modification.
         * </p>
         */
        public static final ParameterTypeSpeed V_CRIT_DISCHARGE = new ParameterTypeSpeed("vCritDischarge",
                        "Critical speed threshold for capacity drop ramp", new Speed(40.0, SpeedUnit.KM_PER_HOUR), POSITIVE);

        /**
         * Additional desired headway during congested discharge, as a fraction of the vehicle's own {@code T}.
         * <p>
         * The relative counterpart of {@link #T_DISCHARGE_ADDON}. An addon in seconds means different things to
         * different vehicles: 0.4 s on a car at {@code T} = 1.00 is a 40 % increase, on a truck at 1.30 only 31 %,
         * so the capacity drop would come out different per vehicle type without anyone having chosen that. As a
         * fraction it is type-independent, and it states directly what it does - a value of 0.2 means the desired
         * headway grows by up to a fifth in the queue.
         * </p>
         * <p>
         * Zero, the default, leaves the mechanism to the absolute {@link #T_DISCHARGE_ADDON} path.
         * </p>
         */
        public static final ParameterTypeDouble T_DISCHARGE_FRACTION = new ParameterTypeDouble("tDischargeFraction",
                        "Additional desired headway during congested discharge, as a fraction of T", 0.0);

        /**
         * Speed below which the capacity drop ramps in, as a fraction of the vehicle's own desired speed.
         * <p>
         * The relative counterpart of {@link #V_CRIT_DISCHARGE}, and the reason the mechanism is applied in
         * {@code MirovaIdmPlus.combineInteractionTerm} rather than in the desired-headway model: that model's
         * signature carries only the parameters and the current speed, so it cannot see what the vehicle wants to
         * drive, while the interaction term is handed the desired speed alongside it.
         * </p>
         * <p>
         * An absolute threshold treats a truck wanting 80 km/h and a car wanting 130 as being in the same traffic
         * state at the same speed, which they are not. It also sits awkwardly against the measurement: the jam speed
         * a detector reports is a harmonic mean over the cross-section, so individual vehicles are slower and faster
         * than it, and an absolute threshold near that mean divides them by a criterion unrelated to the driver.
         * </p>
         * <p>
         * Zero, the default, disables the relative path and leaves {@link #V_CRIT_DISCHARGE} in charge, so existing
         * configurations behave exactly as before.
         * </p>
         */
        public static final ParameterTypeDouble V_CRIT_DISCHARGE_FRACTION =
                        new ParameterTypeDouble("vCritDischargeFraction",
                                        "Capacity drop ramp threshold, as a fraction of the desired speed", 0.0);

        // ----------------------------------------------------------------------
        // Headway relaxation acceleration damping parameters
        // ----------------------------------------------------------------------

        /**
         * Acceleration scaling factor during active headway relaxation (aRelaxDamping).
         * <p>
         * Minimum scaling factor (e.g. 0.40 = 40%) applied to positive acceleration when headway relaxation is 100% active. As
         * the relaxation buffer decays exponentially over time, the acceleration capability recovers towards 1.0 (100%).
         * </p>
         */
        public static final ParameterTypeDouble RELAXATION_ACC_DAMPING_FACTOR = new ParameterTypeDouble("aRelaxDamping",
                        "Acceleration scaling factor during active headway relaxation", 0.40, POSITIVE);

        // ----------------------------------------------------------------------
        // Phase 0.5 behaviour switches
        //
        // Each of these selects between the behaviour every published result was produced with (the default) and a
        // correction identified by the decoupling inventory. They exist so that the two can be compared on the same
        // scenario rather than changed under it; see docs/decoupling/phase05-report.md. Every default is false and
        // reproduces the current model exactly.
        // ----------------------------------------------------------------------

        /**
         * BC-1. Derive the merge anticipation filter from the interval the planner is actually called with.
         * <p>
         * {@code AnticipateMergeState} smooths the merge reference speed with an exponential moving average whose
         * coefficient is fixed in the constructor as {@code 0.25 * DT}: taken from the parameter rather than from the
         * step actually taken, linear in dt rather than {@code 1 - exp(-dt/tau)}, and never revisited afterwards.
         * With this set the coefficient is recomputed per call from the elapsed time and a 4 s time constant, which
         * is the same filter at the configured 0.2 s step and the intended one at any other.
         * </p>
         */
        public static final ParameterTypeBoolean EMA_ALPHA_FROM_ACTUAL_DT = new ParameterTypeBoolean("bcEmaActualDt",
                        "BC-1: merge anticipation filter uses the actual time step", false);

        /**
         * BC-2. Judge the leader's cooperation by the ego's own car-following model rather than the leader's.
         * <p>
         * {@code GapOpenerPattern.leaderCanCooperate} evaluates the front leader's own car-following model against
         * the front leader's own parameter set to decide whether that vehicle could open the gap instead. With this
         * set the ego uses its own model and parameters, on the assumption that drivers expect others to behave as
         * they do.
         * </p>
         */
        public static final ParameterTypeBoolean LEADER_HEADWAY_FROM_OWN_MODEL =
                        new ParameterTypeBoolean("bcLeaderOwnModel",
                                        "BC-2: leader cooperation judged with the ego's own model", false);

        /**
         * BC-5. Take the mean speed of a lane from the leaders the ego perceives.
         * <p>
         * {@code MacroTrafficContext} answers from OTS {@code AnticipationTrafficPerception}, which is a perception
         * model in its own right with its own parameters. {@code InfrastructureContext.getAnticipatedSpeed} already
         * computes the same quantity from the perceived leaders. With this set the second answers for both, so the
         * model has one notion of the speed of a neighbouring lane instead of two.
         * </p>
         */
        public static final ParameterTypeBoolean MEAN_SPEED_FROM_PERCEIVED_LEADERS =
                        new ParameterTypeBoolean("bcMeanSpeedFromLeaders",
                                        "BC-5: lane mean speed from perceived leaders", false);

        /**
         * BC-6. Bound the merge reference speed scan to what the ego could see.
         * <p>
         * Step three of the merge reference cascade scans a lane found up to 1000 m downstream and reads the speed
         * and position of every vehicle on it. With this set the scan is bounded by the ego's own look-ahead and
         * answers "unknown" when nothing is visible within it, which falls through to the speed-limit fallback the
         * cascade already has.
         * </p>
         */
        public static final ParameterTypeBoolean MERGE_REFERENCE_RANGE_LIMITED =
                        new ParameterTypeBoolean("bcMergeRefRangeLimited",
                                        "BC-6: merge reference speed scan bounded by the look-ahead", false);

        /**
         * BC-8. Update the contexts in the order their dependencies call for.
         * <p>
         * The categories refresh in the order a hash table produced -- MacroTraffic, Infrastructure, Neighbors, Ego
         * -- while Neighbors opens relaxations on Ego and Ego collects expired ones, so the collection runs before
         * the creation. With this set the order is Ego, Neighbors, Infrastructure, MacroTraffic.
         * </p>
         */
        public static final ParameterTypeBoolean CONTEXT_UPDATE_ORDER_FIXED =
                        new ParameterTypeBoolean("bcContextOrderFixed",
                                        "BC-8: contexts update in dependency order", false);

        /** Whether acceleration damping during active headway relaxation is enabled. */
        public static final ParameterTypeBoolean RELAXATION_ACC_DAMPING_ENABLED =
                        new ParameterTypeBoolean("aRelaxDampingEnabled",
                                        "Whether acceleration damping during active headway relaxation is enabled", true);
}
