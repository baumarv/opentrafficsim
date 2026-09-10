/*
 * MiRoVA - Migration of Road Vehicle Automation (DFG FOR 5702)
 * Copyright (c) 2026 Marvin Baumann, Karlsruhe Institute of Technology (KIT),
 * Institute for Transport Studies (IfV). All rights reserved.
 *
 * NOTE: Replace this block with the exact KIT/MiRoVA header template.
 *
 * UNIT LITERALS: kotlin-units builds its types from numeric extensions -- `3.0.meters`,
 * `1.25.metersPerSecondSquared`. Distance is backed by Long micrometres, Speed and Acceleration by
 * Double SI. Speeds use `.kmh`.
 */
package edu.kit.ifv.mirova.api

import edu.kit.ifv.units.Acceleration
import edu.kit.ifv.units.Distance
import edu.kit.ifv.units.Speed
import edu.kit.ifv.units.kmh
import edu.kit.ifv.units.meters
import edu.kit.ifv.units.metersPerSecondSquared
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A typed parameter key: its identity, its default, and its documentation.
 *
 * Keys are declared once in [DriverParameterKeys] and compared by identity, so a key the core does
 * not know cannot be set. That is deliberate: the Java model let any scenario write any string-keyed
 * parameter into a shared set, and Phase 0.5 found studies that had been setting keys nothing read —
 * `TMIN` and `TMAX` on MiRoVA factories, `FAR_ANTICIPATION_ENABLED` on a pattern that is not
 * registered.
 *
 * @param T the value type
 * @property id stable textual identifier, for configuration files and diagnostics
 * @property default the value used when nothing else supplies one
 * @property description what the parameter means, in one sentence
 */
class ParameterKey<T : Any> internal constructor(
    val id: String,
    val default: T,
    val description: String,
) {
    /**
     * Returns the identifier, so that keys print legibly in diagnostics.
     *
     * @return the identifier
     */
    override fun toString(): String = id
}

/**
 * An immutable set of parameter values.
 *
 * Used in two roles: as the population defaults handed to a [DriverAgentFactory], and as the
 * per-vehicle overrides handed to [DriverAgentFactory.create]. A key that is set nowhere falls back
 * to its distribution, and failing that to [ParameterKey.default].
 *
 * The core resolves the whole set **once, when the agent is constructed**, into an internal snapshot
 * of primitives. Nothing is looked up per tick and nothing is written at runtime. The Java model had
 * exactly one runtime write left after Phase 0.5 — `ParameterTypes.T`, set and reset around a
 * car-following call — and in this contract the headway factor is an argument to that call instead;
 * see [CarFollowingModel.followingAcceleration].
 */
class DriverParameters private constructor(private val values: Map<ParameterKey<*>, Any>) {

    /**
     * Returns the value for [key], or its default when none is set.
     *
     * @param key the key to read
     * @param T the value type
     * @return the configured value, or [ParameterKey.default]
     */
    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> get(key: ParameterKey<T>): T = values[key] as T? ?: key.default

    /**
     * Returns whether a value is explicitly set for [key].
     *
     * @param key the key to test
     * @return true when this set carries a value of its own
     */
    operator fun contains(key: ParameterKey<*>): Boolean = key in values

    /**
     * Returns a copy with [key] set to [value].
     *
     * @param key the key to set
     * @param value the value to set it to
     * @param T the value type
     * @return the extended set
     */
    fun <T : Any> with(key: ParameterKey<T>, value: T): DriverParameters =
        DriverParameters(values + (key to value))

    /**
     * Returns a copy with every entry of [other] applied on top of this one.
     *
     * @param other the overriding set
     * @return the merged set
     */
    fun override(other: DriverParameters): DriverParameters =
        DriverParameters(values + other.values)

    companion object {
        /** The empty set: every key takes its default. */
        @JvmField
        val EMPTY: DriverParameters = DriverParameters(emptyMap())
    }
}

/**
 * A distribution to draw one parameter from when an agent is created.
 *
 * The core owns the distribution **shapes** — the spread of desired speed across a population is
 * driver heterogeneity and travels with the driver to any road. The host owns the entropy
 * ([RandomStream]) and the facility-specific calibration that selects and parametrises a shape. That
 * split is the one settled in `parameters.md` §4.
 *
 * @param T the value type
 */
interface ParameterDistribution<T : Any> {

    /**
     * Draws one value.
     *
     * @param random the host's random stream
     * @return the drawn value
     */
    fun draw(random: RandomStream): T
}

/**
 * Every parameter the core reads, in contract v1.
 *
 * The list is closed and complete: each entry has at least one read site in the Java model that
 * survives into the core. Parameters that were declared but read nowhere are not here — Phase 0.5
 * deleted eight of them.
 *
 * Deliberately **absent**:
 *
 * - `LOOKAHEAD` and `LOOKBACK`. Driver parameters in the Java model, query range arguments in this
 *   contract. Making the range an argument is what makes the memoisation defect of Phase 0.5
 *   unrepresentable; see [WorldView].
 * - `DT`. The step is not a driver property. It becomes [TacticalCommand.nextEvaluationAfter], which
 *   the driver requests and the host may better.
 * - `EXTENDED_LOOK_AHEAD_DISTANCE`. Its one live use — projecting the path to find the lane a ramp
 *   vehicle will merge into — is now the range argument of [WorldView.expectedMergeSpeed], bounded by
 *   what the driver can see (decision BC-6). Its three threshold uses tested "is there a route lane
 *   change in range at all", which [WorldView.routeRequirement] answers directly.
 * - the six Phase 0.5 `bc*` switches. They exist to compare the Java model against itself. Whichever
 *   way each is settled, the core implements one behaviour, not a switch.
 */
object DriverParameterKeys {

    // -----------------------------------------------------------------------------------------
    // Car following
    // -----------------------------------------------------------------------------------------

    /** Desired time headway in free flow. */
    @JvmField
    val DESIRED_HEADWAY: ParameterKey<Duration> =
        ParameterKey("T", 1.2.seconds, "Desired time headway in free flow")

    /** Minimum bumper-to-bumper gap at standstill. */
    @JvmField
    val STANDSTILL_GAP: ParameterKey<Distance> =
        ParameterKey("s0", 3.0.meters, "Minimum bumper-to-bumper gap at standstill")

    /** Desired acceleration in free flow. */
    @JvmField
    val DESIRED_ACCELERATION: ParameterKey<Acceleration> =
        ParameterKey("a", 1.25.metersPerSecondSquared, "Desired acceleration in free flow")

    /** Comfortable deceleration, used for planned stops such as the end of a ramp. */
    @JvmField
    val COMFORTABLE_DECELERATION: ParameterKey<Acceleration> =
        ParameterKey("b", (-2.09).metersPerSecondSquared, "Comfortable deceleration")

    /** Deceleration the driver treats as the limit of ordinary braking. */
    @JvmField
    val CRITICAL_DECELERATION: ParameterKey<Acceleration> =
        ParameterKey("bCrit", (-3.5).metersPerSecondSquared, "Critical deceleration")

    /** Strongest deceleration the driver will ever apply, whatever the situation. */
    @JvmField
    val MAX_DECELERATION: ParameterKey<Acceleration> =
        ParameterKey("bMax", (-6.0).metersPerSecondSquared,
            "Strongest deceleration the driver will apply")

    /**
     * Strongest acceleration the driver will demand of the vehicle at standstill.
     *
     * The driver's willingness, not the vehicle's ability — the latter is
     * [EgoState.maxAccelerationAt] and comes from the host. The effective value is the smaller of the
     * two.
     */
    @JvmField
    val MAX_ACCELERATION: ParameterKey<Acceleration> =
        ParameterKey("aMax", 3.5.metersPerSecondSquared,
            "Strongest acceleration the driver will demand")

    /** How many leaders longitudinal control evaluates; the most constraining one wins. */
    @JvmField
    val CAR_FOLLOWING_LEADERS: ParameterKey<Int> =
        ParameterKey("cfMaxLeaders", 2, "Number of leaders longitudinal control evaluates")

    // -----------------------------------------------------------------------------------------
    // Desired speed
    // -----------------------------------------------------------------------------------------

    /**
     * Factor on the legal limit that gives this driver's desired speed.
     *
     * The one parameter that is drawn rather than configured in every study.
     */
    @JvmField
    val SPEED_FACTOR: ParameterKey<Double> =
        ParameterKey("fSpeed", 1.0, "Desired speed as a factor of the legal limit")

    /** Speed below which this driver considers traffic congested. */
    @JvmField
    val CONGESTED_SPEED: ParameterKey<Speed> =
        ParameterKey("vCong", 60.0.kmh, "Speed below which traffic counts as congested")

    // -----------------------------------------------------------------------------------------
    // Desire thresholds (layer 2 into layer 3)
    // -----------------------------------------------------------------------------------------

    /** Desire above which a voluntary lane change is considered at all. */
    @JvmField
    val DESIRE_FREE: ParameterKey<Double> =
        ParameterKey("dFree", 0.365, "Desire threshold for a voluntary lane change")

    /** Desire above which a lane change counts as mandatory and gap acceptance relaxes. */
    @JvmField
    val DESIRE_MANDATORY: ParameterKey<Double> =
        ParameterKey("dMand", 0.577, "Desire threshold above which a lane change is mandatory")

    /** Time horizon over which a required route lane change becomes urgent. */
    @JvmField
    val ROUTE_TIME_HORIZON: ParameterKey<Duration> =
        ParameterKey("t0", 43.seconds, "Time horizon for route-following urgency")

    // -----------------------------------------------------------------------------------------
    // Social interaction
    // -----------------------------------------------------------------------------------------

    /** Speed-difference scale in the cruising and social pressure desire terms. */
    @JvmField
    val SPEED_GAIN: ParameterKey<Speed> =
        ParameterKey("vGain", 69.6.kmh, "Speed-difference scale for lane-change desire")

    /** How strongly this driver yields to a faster follower. */
    @JvmField
    val SOCIO_SPEED_SENSITIVITY: ParameterKey<Double> =
        ParameterKey("socio", 0.25, "Sensitivity to pressure from a faster follower")

    /** Whether this driver opens a gap for a merging neighbour at all. */
    @JvmField
    val COOPERATION_ENABLED: ParameterKey<Boolean> =
        ParameterKey("cooperate", true, "Whether the driver cooperates with a merging neighbour")

    /** Deceleration this driver will accept in order to open a gap. */
    @JvmField
    val COOPERATIVE_DECELERATION: ParameterKey<Acceleration> =
        ParameterKey("bCoop", (-3.0).metersPerSecondSquared, "Deceleration accepted to open a gap")

    /** Deceleration applied pre-emptively, before a neighbour has indicated. */
    @JvmField
    val PREEMPTIVE_COOPERATIVE_DECELERATION: ParameterKey<Acceleration> =
        ParameterKey("bCoopPre", (-1.0).metersPerSecondSquared, "Deceleration applied pre-emptively")

    /** How far ahead this driver notices a neighbour who may need a gap. */
    @JvmField
    val COOPERATION_RANGE: ParameterKey<Distance> =
        ParameterKey("dCoop", 100.0.meters, "Range within which a neighbour's need for a gap is noticed")

    /** Time headway below which being overtaken on the right counts as undercutting. */
    @JvmField
    val UNDERCUTTING_HEADWAY: ParameterKey<Duration> =
        ParameterKey("tUndercut", 5.seconds,
            "Headway below which right-side overtaking counts as undercutting")

    // -----------------------------------------------------------------------------------------
    // Gap acceptance
    // -----------------------------------------------------------------------------------------

    /** How far the required safety gap may be reduced while changing lane under pressure. */
    @JvmField
    val GAP_REDUCTION_FACTOR: ParameterKey<Double> =
        ParameterKey("fGap", 0.5, "Reduction of the required safety gap during a lane change")

    /** Deceleration this driver will impose on a new follower at the least urgent desire. */
    @JvmField
    val MIN_FOLLOWER_DECELERATION: ParameterKey<Acceleration> =
        ParameterKey("bFollowerMin", (-2.0).metersPerSecondSquared,
            "Deceleration imposed on a new follower at minimum urgency")

    /** Deceleration this driver will impose on a new follower at the most urgent desire. */
    @JvmField
    val MAX_FOLLOWER_DECELERATION: ParameterKey<Acceleration> =
        ParameterKey("bFollowerMax", (-4.0).metersPerSecondSquared,
            "Deceleration imposed on a new follower at maximum urgency")

    /** Deceleration this driver will accept for itself at the least urgent desire. */
    @JvmField
    val MIN_EGO_DECELERATION: ParameterKey<Acceleration> =
        ParameterKey("bEgoMin", (-2.0).metersPerSecondSquared,
            "Deceleration the driver accepts at minimum urgency")

    /** Deceleration this driver will accept for itself at the most urgent desire. */
    @JvmField
    val MAX_EGO_DECELERATION: ParameterKey<Acceleration> =
        ParameterKey("bEgoMax", (-4.0).metersPerSecondSquared,
            "Deceleration the driver accepts at maximum urgency")

    // -----------------------------------------------------------------------------------------
    // Relaxation (Keane and Gao)
    // -----------------------------------------------------------------------------------------

    /**
     * Decay time constant of the headway deficit after a cut-in.
     *
     * Phase 0.5 established that this is a **one-parameter** model. The speed buffer the Java code
     * declared alongside it was never fed, and `RELAXATION_TAU_SPEED` was withdrawn with it. The
     * value is 20 s, not the 15 s of the paper, and that is a calibration the published results rest
     * on.
     */
    @JvmField
    val RELAXATION_TAU: ParameterKey<Duration> =
        ParameterKey("tauRelax", 20.seconds, "Decay time constant of the headway deficit")

    /** Lifetime cap on one relaxation, in multiples of [RELAXATION_TAU]. */
    @JvmField
    val RELAXATION_MAX_LIFETIME_FACTOR: ParameterKey<Double> =
        ParameterKey("relaxLifetime", 3.0, "Lifetime cap on a relaxation, in multiples of tau")

    /** Whether the acceleration produced while relaxed is damped. */
    @JvmField
    val RELAXATION_DAMPING_ENABLED: ParameterKey<Boolean> =
        ParameterKey("relaxDampingOn", true, "Whether acceleration is damped while relaxed")

    /** Damping factor applied to the acceleration while a relaxation is active. */
    @JvmField
    val RELAXATION_DAMPING: ParameterKey<Double> =
        ParameterKey("relaxDamping", 0.40, "Damping factor applied while relaxed")

    /** Deceleration beyond which a relaxation is abandoned as no longer credible. */
    @JvmField
    val RELAXATION_ABORT_DECELERATION: ParameterKey<Acceleration> =
        ParameterKey("relaxAbortB", (-1.0).metersPerSecondSquared,
            "Deceleration beyond which a relaxation is abandoned")

    /** Time over which an abandoned relaxation fades out, rather than vanishing. */
    @JvmField
    val RELAXATION_FADE: ParameterKey<Duration> =
        ParameterKey("relaxFade", 1.seconds, "Fade-out time of an abandoned relaxation")

    // -----------------------------------------------------------------------------------------
    // Lane change execution
    // -----------------------------------------------------------------------------------------

    /** Duration of an unhurried lane change. */
    @JvmField
    val LANE_CHANGE_DURATION: ParameterKey<Duration> =
        ParameterKey("lcDuration", 3.seconds, "Duration of an unhurried lane change")

    /**
     * Duration of a lane change made in congestion.
     *
     * Declared in the Java model but read only from commented-out code, and kept by decision A.7 for
     * exactly this: the duration is now part of [LaneChangeRequest], so the distinction between an
     * unhurried change and one squeezed into a queue is expressible at the port.
     */
    @JvmField
    val CONGESTED_LANE_CHANGE_DURATION: ParameterKey<Duration> =
        ParameterKey("lcDurationCongested", 1500.milliseconds,
            "Duration of a lane change made in congestion")

    // -----------------------------------------------------------------------------------------
    // Capacity drop
    // -----------------------------------------------------------------------------------------

    /** Whether the discharge-headway capacity drop is modelled. */
    @JvmField
    val CAPACITY_DROP_ENABLED: ParameterKey<Boolean> =
        ParameterKey("capDropOn", false, "Whether the capacity drop is modelled")

    /** Absolute headway added while discharging from a queue. */
    @JvmField
    val DISCHARGE_HEADWAY_ADDON: ParameterKey<Duration> =
        ParameterKey("tDischargeAddon", 500.milliseconds, "Absolute headway added while discharging")

    /** Speed below which discharge behaviour applies. */
    @JvmField
    val DISCHARGE_CRITICAL_SPEED: ParameterKey<Speed> =
        ParameterKey("vCritDischarge", 40.0.kmh,
            "Speed below which discharge behaviour applies")

    /** Headway addition as a fraction of the desired headway, as an alternative to the absolute form. */
    @JvmField
    val DISCHARGE_HEADWAY_FRACTION: ParameterKey<Double> =
        ParameterKey("tDischargeFraction", 0.0, "Headway addition as a fraction of the desired headway")

    /** Speed threshold for the fractional form of the discharge addition. */
    @JvmField
    val DISCHARGE_CRITICAL_SPEED_FRACTION: ParameterKey<Double> =
        ParameterKey("vCritDischargeFraction", 0.0, "Speed threshold for the fractional discharge addition")

    /** Every key above, for configuration loaders and diagnostics to iterate. */
    @JvmField
    val ALL: List<ParameterKey<*>> = listOf(
        DESIRED_HEADWAY, STANDSTILL_GAP, DESIRED_ACCELERATION, COMFORTABLE_DECELERATION,
        CRITICAL_DECELERATION, MAX_DECELERATION, MAX_ACCELERATION, CAR_FOLLOWING_LEADERS,
        SPEED_FACTOR, CONGESTED_SPEED,
        DESIRE_FREE, DESIRE_MANDATORY, ROUTE_TIME_HORIZON,
        SPEED_GAIN, SOCIO_SPEED_SENSITIVITY, COOPERATION_ENABLED, COOPERATIVE_DECELERATION,
        PREEMPTIVE_COOPERATIVE_DECELERATION, COOPERATION_RANGE, UNDERCUTTING_HEADWAY,
        GAP_REDUCTION_FACTOR, MIN_FOLLOWER_DECELERATION, MAX_FOLLOWER_DECELERATION,
        MIN_EGO_DECELERATION, MAX_EGO_DECELERATION,
        RELAXATION_TAU, RELAXATION_MAX_LIFETIME_FACTOR, RELAXATION_DAMPING_ENABLED,
        RELAXATION_DAMPING, RELAXATION_ABORT_DECELERATION, RELAXATION_FADE,
        LANE_CHANGE_DURATION, CONGESTED_LANE_CHANGE_DURATION,
        CAPACITY_DROP_ENABLED, DISCHARGE_HEADWAY_ADDON, DISCHARGE_CRITICAL_SPEED,
        DISCHARGE_HEADWAY_FRACTION, DISCHARGE_CRITICAL_SPEED_FRACTION,
    )
}
