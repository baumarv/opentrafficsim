/*
 * MiRoVA - Migration of Road Vehicle Automation (DFG FOR 5702)
 * Copyright (c) 2026 Marvin Baumann, Karlsruhe Institute of Technology (KIT),
 * Institute for Transport Studies (IfV). All rights reserved.
 *
 * NOTE: Replace this block with the exact KIT/MiRoVA header template.
 *
 * SIGN CONVENTION (ADR candidate, contract.md section 7): every parameter is a positive magnitude,
 * decelerations included. Only computed accelerations are signed -- what a car-following model
 * returns, what a TacticalCommand carries, what EgoState reports. A parameter is a bound the driver
 * brings, and a bound has no direction; the sign appears when the model applies it. OTS carried both
 * conventions at once, with ParameterTypes.B positive and MirovaParameters.B_MAX negative.
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
import edu.kit.ifv.units.metersPerSecond
import edu.kit.ifv.units.meters
import edu.kit.ifv.units.metersPerSecondSquared
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Where a default value came from.
 *
 * Carried per key so that a number nobody has ever justified says so at the point of use. Phase 1
 * counted them: of the keys below, twelve rest on a Freiburg-Nord calibration, nine on a published
 * source, thirteen on an assumption, one on an OTS default nobody chose, and three could not be
 * classified at all — among them `vGain`, which scales every discretionary desire, and `tauRelax`,
 * which is the relaxation. See `default-parameters.md`.
 *
 * The tag describes **the default this key carries**, not the value a given run uses. If the defaults
 * are changed to the production set, these change with them in the same commit.
 */
enum class Provenance {
    /** A published source is named for it. */
    LITERATURE,

    /** Chosen by a grid or factorial on Freiburg-Nord, with the evidence recorded. */
    CALIBRATION,

    /** A plausible value nobody has tested or sourced. */
    ASSUMPTION,

    /** Never set by anything; whatever OpenTrafficSim ships. */
    OTS_DEFAULT,

    /** Its origin could not be established from the code, and was not guessed. */
    UNKNOWN,
}

/**
 * The range of values a key admits.
 *
 * Checked when an agent is constructed, so that a malformed parameter set fails there and not at the
 * first tick (`contract.md` §10). The bounds are not decoration: `socio` is multiplied by a social
 * pressure that lies in [0, 1), and a value above 1 would let one incentive alone produce a desire
 * outside the [-1, 1] scale every threshold in the model is expressed on.
 */
enum class Bound {
    /** Any value of the type; for booleans and counts that carry no numeric range. */
    NONE,

    /** Strictly greater than zero. Every physical magnitude, decelerations included. */
    POSITIVE,

    /** Zero or greater. */
    NON_NEGATIVE,

    /** Within [0, 1] inclusive. */
    UNIT_INTERVAL,
}

/**
 * A typed parameter key: its identity, its default, its bound, its provenance, and its documentation.
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
 * @property bound the range of values the key admits, checked at construction
 * @property provenance where that default came from
 * @property description what the parameter means, in one sentence
 */
class ParameterKey<T : Any> internal constructor(
    val id: String,
    val default: T,
    val bound: Bound,
    val provenance: Provenance,
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
 * Defaults are the **car** values of the production parameter set of the published campaign; the truck
 * values are [DriverPresets.TRUCK]. One default set can only be one vehicle class, and the car is the
 * majority class and the one the calibration was steered by. See `default-parameters.md`.
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
        ParameterKey("T", 1.1.seconds, Bound.POSITIVE, Provenance.CALIBRATION, "Desired time headway in free flow")

    /** Minimum bumper-to-bumper gap at standstill. */
    @JvmField
    val STANDSTILL_GAP: ParameterKey<Distance> =
        ParameterKey("s0", 3.0.meters,
            Bound.POSITIVE, Provenance.CALIBRATION, "Minimum bumper-to-bumper gap at standstill")

    /** Desired acceleration in free flow. */
    @JvmField
    val DESIRED_ACCELERATION: ParameterKey<Acceleration> =
        ParameterKey("a", 1.4.metersPerSecondSquared,
            Bound.POSITIVE, Provenance.LITERATURE, "Desired acceleration in free flow")

    /** Comfortable deceleration, used for planned stops such as the end of a ramp; a positive magnitude. */
    @JvmField
    val COMFORTABLE_DECELERATION: ParameterKey<Acceleration> =
        ParameterKey("b", 1.75.metersPerSecondSquared,
            Bound.POSITIVE, Provenance.CALIBRATION, "Comfortable deceleration")

    /** Deceleration the driver treats as the limit of ordinary braking; a positive magnitude. */
    @JvmField
    val CRITICAL_DECELERATION: ParameterKey<Acceleration> =
        ParameterKey("bCrit", 3.5.metersPerSecondSquared,
            Bound.POSITIVE, Provenance.ASSUMPTION, "Critical deceleration")

    /** Strongest deceleration the driver will ever apply, whatever the situation; a positive magnitude. */
    @JvmField
    val MAX_DECELERATION: ParameterKey<Acceleration> =
        ParameterKey("bMax", 6.0.metersPerSecondSquared,
            Bound.POSITIVE, Provenance.ASSUMPTION, "Strongest deceleration the driver will apply")

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
            Bound.POSITIVE, Provenance.LITERATURE, "Strongest acceleration the driver will demand")

    /** How many leaders longitudinal control evaluates; the most constraining one wins. */
    @JvmField
    val CAR_FOLLOWING_LEADERS: ParameterKey<Int> =
        ParameterKey("cfMaxLeaders", 2,
            Bound.POSITIVE, Provenance.ASSUMPTION, "Number of leaders longitudinal control evaluates")

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
        ParameterKey("fSpeed", 1.0,
            Bound.POSITIVE, Provenance.OTS_DEFAULT, "Desired speed as a factor of the legal limit")

    /** Speed below which this driver considers traffic congested. */
    @JvmField
    val CONGESTED_SPEED: ParameterKey<Speed> =
        ParameterKey("vCong", 60.0.kmh,
            Bound.POSITIVE, Provenance.LITERATURE, "Speed below which traffic counts as congested")

    // -----------------------------------------------------------------------------------------
    // Desire thresholds (layer 2 into layer 3)
    // -----------------------------------------------------------------------------------------

    /** Desire above which a voluntary lane change is considered at all. */
    @JvmField
    val DESIRE_FREE: ParameterKey<Double> =
        ParameterKey("dFree", 0.365,
            Bound.UNIT_INTERVAL, Provenance.LITERATURE, "Desire threshold for a voluntary lane change")

    /** Desire above which a lane change counts as mandatory and gap acceptance relaxes. */
    @JvmField
    val DESIRE_MANDATORY: ParameterKey<Double> =
        ParameterKey("dMand", 0.577,
            Bound.UNIT_INTERVAL, Provenance.LITERATURE, "Desire threshold above which a lane change is mandatory")

    /** Time horizon over which a required route lane change becomes urgent. */
    @JvmField
    val ROUTE_TIME_HORIZON: ParameterKey<Duration> =
        ParameterKey("t0", 43.seconds,
            Bound.POSITIVE, Provenance.OTS_DEFAULT, "Time horizon for route-following urgency")

    // -----------------------------------------------------------------------------------------
    // Social interaction
    // -----------------------------------------------------------------------------------------

    /**
     * Speed-difference scale in the cruising and social pressure desire terms.
     *
     * **15 km/h — the intended value, and not the one the published results ran with.** The study wrote
     * a bare `15.0` into a `ParameterTypeSpeed` and the generator read bare numbers as SI, so every
     * published run used 15 m/s, i.e. 54 km/h, and trucks 108. The intended values are now the model
     * values; the published pair survives as the `legacy` study variant and the `published-model` tag.
     *
     * **The model is uncalibrated at this value** until it is recalibrated jointly with the parameters
     * that may have been compensating for the larger one. See `default-parameters.md` §6 and
     * `recalibration-inventory.md`.
     */
    @JvmField
    val SPEED_GAIN: ParameterKey<Speed> =
        ParameterKey("vGain", 15.0.kmh,
            Bound.POSITIVE, Provenance.UNKNOWN, "Speed-difference scale for lane-change desire")

    /**
     * How strongly this driver yields to a faster follower.
     *
     * Bounded to [0, 1]. OTS's LMRS constrains its own `SOCIO` the same way; MiRoVA dropped the bound
     * and kept a different value (0.25 against LMRS's 1.0), and nothing in the tree ever set it above
     * 1 — the demo scenarios use 0.75, the calibrated studies leave it at the default. The bound is
     * restored here because the term it multiplies is a pressure in [0, 1): above 1, this incentive
     * alone could put a desire outside the scale the thresholds live on.
     */
    @JvmField
    val SOCIO_SPEED_SENSITIVITY: ParameterKey<Double> =
        ParameterKey("socio", 0.25,
            Bound.UNIT_INTERVAL, Provenance.ASSUMPTION, "Sensitivity to pressure from a faster follower")

    /** Whether this driver opens a gap for a merging neighbour at all. */
    @JvmField
    val COOPERATION_ENABLED: ParameterKey<Boolean> =
        ParameterKey("cooperate", true,
            Bound.NONE, Provenance.ASSUMPTION, "Whether the driver cooperates with a merging neighbour")

    /** Deceleration this driver will accept in order to open a gap. */
    @JvmField
    val COOPERATIVE_DECELERATION: ParameterKey<Acceleration> =
        ParameterKey("bCoop", 3.0.metersPerSecondSquared,
            Bound.POSITIVE, Provenance.CALIBRATION, "Deceleration accepted to open a gap")

    /** Deceleration applied pre-emptively, before a neighbour has indicated. */
    @JvmField
    val PREEMPTIVE_COOPERATIVE_DECELERATION: ParameterKey<Acceleration> =
        ParameterKey("bCoopPre", 1.0.metersPerSecondSquared,
            Bound.POSITIVE, Provenance.ASSUMPTION, "Deceleration applied pre-emptively")

    /** How far ahead this driver notices a neighbour who may need a gap. */
    @JvmField
    val COOPERATION_RANGE: ParameterKey<Distance> =
        ParameterKey("dCoop", 100.0.meters,
            Bound.POSITIVE, Provenance.LITERATURE, "Range within which a neighbour's need for a gap is noticed")

    /** Time headway below which being overtaken on the right counts as undercutting. */
    @JvmField
    val UNDERCUTTING_HEADWAY: ParameterKey<Duration> =
        ParameterKey("tUndercut", 5.seconds,
            Bound.POSITIVE, Provenance.LITERATURE, "Headway below which right-side overtaking counts as undercutting")

    // -----------------------------------------------------------------------------------------
    // Gap acceptance
    // -----------------------------------------------------------------------------------------

    /** How far the required safety gap may be reduced while changing lane under pressure. */
    @JvmField
    val GAP_REDUCTION_FACTOR: ParameterKey<Double> =
        ParameterKey("fGap", 0.40,
            Bound.UNIT_INTERVAL, Provenance.CALIBRATION, "Reduction of the required safety gap during a lane change")

    /** Deceleration this driver will impose on a new follower at the least urgent desire. */
    @JvmField
    val MIN_FOLLOWER_DECELERATION: ParameterKey<Acceleration> =
        ParameterKey("bFollowerMin", 2.0.metersPerSecondSquared,
            Bound.POSITIVE, Provenance.CALIBRATION, "Deceleration imposed on a new follower at minimum urgency")

    /** Deceleration this driver will impose on a new follower at the most urgent desire. */
    @JvmField
    val MAX_FOLLOWER_DECELERATION: ParameterKey<Acceleration> =
        ParameterKey("bFollowerMax", 4.0.metersPerSecondSquared,
            Bound.POSITIVE, Provenance.CALIBRATION, "Deceleration imposed on a new follower at maximum urgency")

    /** Deceleration this driver will accept for itself at the least urgent desire. */
    @JvmField
    val MIN_EGO_DECELERATION: ParameterKey<Acceleration> =
        ParameterKey("bEgoMin", 2.0.metersPerSecondSquared,
            Bound.POSITIVE, Provenance.ASSUMPTION, "Deceleration the driver accepts at minimum urgency")

    /** Deceleration this driver will accept for itself at the most urgent desire. */
    @JvmField
    val MAX_EGO_DECELERATION: ParameterKey<Acceleration> =
        ParameterKey("bEgoMax", 4.0.metersPerSecondSquared,
            Bound.POSITIVE, Provenance.ASSUMPTION, "Deceleration the driver accepts at maximum urgency")

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
        ParameterKey("tauRelax", 20.seconds,
            Bound.POSITIVE, Provenance.UNKNOWN, "Decay time constant of the headway deficit")

    /** Lifetime cap on one relaxation, in multiples of [RELAXATION_TAU]. */
    @JvmField
    val RELAXATION_MAX_LIFETIME_FACTOR: ParameterKey<Double> =
        ParameterKey("relaxLifetime", 3.0,
            Bound.POSITIVE, Provenance.ASSUMPTION, "Lifetime cap on a relaxation, in multiples of tau")

    /** Whether the acceleration produced while relaxed is damped. */
    @JvmField
    val RELAXATION_DAMPING_ENABLED: ParameterKey<Boolean> =
        ParameterKey("relaxDampingOn", true,
            Bound.NONE, Provenance.CALIBRATION, "Whether acceleration is damped while relaxed")

    /** Damping factor applied to the acceleration while a relaxation is active. */
    @JvmField
    val RELAXATION_DAMPING: ParameterKey<Double> =
        ParameterKey("relaxDamping", 1.00,
            Bound.UNIT_INTERVAL, Provenance.CALIBRATION, "Damping factor applied while relaxed")

    /** Deceleration beyond which a relaxation is abandoned as no longer credible. */
    @JvmField
    val RELAXATION_ABORT_DECELERATION: ParameterKey<Acceleration> =
        ParameterKey("relaxAbortB", 1.0.metersPerSecondSquared,
            Bound.POSITIVE, Provenance.ASSUMPTION, "Deceleration beyond which a relaxation is abandoned")

    /** Time over which an abandoned relaxation fades out, rather than vanishing. */
    @JvmField
    val RELAXATION_FADE: ParameterKey<Duration> =
        ParameterKey("relaxFade", 1.seconds,
            Bound.POSITIVE, Provenance.ASSUMPTION, "Fade-out time of an abandoned relaxation")

    // -----------------------------------------------------------------------------------------
    // Lane change execution
    // -----------------------------------------------------------------------------------------

    /** Duration of an unhurried lane change. */
    @JvmField
    val LANE_CHANGE_DURATION: ParameterKey<Duration> =
        ParameterKey("lcDuration", 3.seconds,
            Bound.POSITIVE, Provenance.OTS_DEFAULT, "Duration of an unhurried lane change")

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
            Bound.POSITIVE, Provenance.ASSUMPTION, "Duration of a lane change made in congestion")

    // -----------------------------------------------------------------------------------------
    // Capacity drop
    // -----------------------------------------------------------------------------------------

    /** Whether the discharge-headway capacity drop is modelled. */
    @JvmField
    val CAPACITY_DROP_ENABLED: ParameterKey<Boolean> =
        ParameterKey("capDropOn", false, Bound.NONE, Provenance.ASSUMPTION, "Whether the capacity drop is modelled")

    /** Absolute headway added while discharging from a queue. */
    @JvmField
    val DISCHARGE_HEADWAY_ADDON: ParameterKey<Duration> =
        ParameterKey("tDischargeAddon", 500.milliseconds,
            Bound.POSITIVE, Provenance.ASSUMPTION, "Absolute headway added while discharging")

    /** Speed below which discharge behaviour applies. */
    @JvmField
    val DISCHARGE_CRITICAL_SPEED: ParameterKey<Speed> =
        ParameterKey("vCritDischarge", 40.0.kmh,
            Bound.POSITIVE, Provenance.ASSUMPTION, "Speed below which discharge behaviour applies")

    /** Headway addition as a fraction of the desired headway, as an alternative to the absolute form. */
    @JvmField
    val DISCHARGE_HEADWAY_FRACTION: ParameterKey<Double> =
        ParameterKey("tDischargeFraction", 0.0,
            Bound.NON_NEGATIVE, Provenance.ASSUMPTION, "Headway addition as a fraction of the desired headway")

    /** Speed threshold for the fractional form of the discharge addition. */
    @JvmField
    val DISCHARGE_CRITICAL_SPEED_FRACTION: ParameterKey<Double> =
        ParameterKey("vCritDischargeFraction", 0.0,
            Bound.NON_NEGATIVE, Provenance.ASSUMPTION, "Speed threshold for the fractional discharge addition")

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

/**
 * Named parameter sets for vehicle classes other than the default.
 *
 * [DriverParameterKeys] carries the car values of the production set; a class that differs is a
 * preset the host applies as an override, not a second set of defaults. Which class a driver belongs
 * to is population configuration, and population configuration is the host's.
 */
object DriverPresets {

    /**
     * The truck values of the production parameter set of the published campaign.
     *
     * Seven keys differ from the car defaults. Every one of them is set by
     * `FreiburgStudyParameters.baseBehaviorParams` or by the override layers above it, and every one
     * was confirmed against a registered run rather than read off a constant; three constants in that
     * class are overridden before a run sees them. `b`, `fGap`, `relaxDamping` and `capDropOn` are the
     * same for both classes and are therefore not repeated here.
     *
     * Provenance, key by key: `T` and `a` from the Freiburg-Nord calibration (the acceleration by a
     * factorial that found it the strongest single axis tested), `s0` from Kesting's 2:1 ratio to the
     * car value, `aMax` from the reference's acceleration curve scaled 1.3/3.5, `bCoop` from the
     * calibration, `vGain` unsourced as for cars, and `cooperate` — trucks do not open gaps —
     * unsourced entirely: no rationale for it is recorded anywhere in the code.
     */
    @JvmField
    val TRUCK: DriverParameters = DriverParameters.EMPTY
        .with(DriverParameterKeys.DESIRED_HEADWAY, 1.4.seconds)
        .with(DriverParameterKeys.STANDSTILL_GAP, 6.0.meters)
        .with(DriverParameterKeys.DESIRED_ACCELERATION, 1.25.metersPerSecondSquared)
        .with(DriverParameterKeys.MAX_ACCELERATION, 1.3.metersPerSecondSquared)
        .with(DriverParameterKeys.SPEED_GAIN, 30.0.kmh)
        .with(DriverParameterKeys.COOPERATIVE_DECELERATION, 1.0.metersPerSecondSquared)
        .with(DriverParameterKeys.COOPERATION_ENABLED, false)
}
