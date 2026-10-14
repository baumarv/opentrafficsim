package org.opentrafficsim.demo.mirova.scenariomanagement.models;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.djunits.unit.SpeedUnit;
import org.djunits.value.vdouble.scalar.Acceleration;
import org.djunits.value.vdouble.scalar.Duration;
import org.djunits.value.vdouble.scalar.Length;
import org.djunits.value.vdouble.scalar.Speed;
import org.opentrafficsim.base.parameters.ParameterType;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.road.gtu.tactical.lmrs.LmrsFactory.Setting;
import org.opentrafficsim.road.gtu.tactical.util.lmrs.Cooperation;
import org.opentrafficsim.road.gtu.tactical.util.lmrs.GapAcceptance;
import org.opentrafficsim.road.gtu.tactical.util.lmrs.LmrsParameters;
import org.opentrafficsim.road.gtu.tactical.util.lmrs.Synchronization;

/**
 * The configurations the LMRS provider starts from before a run's own settings and parameter overrides are applied.
 * <p>
 * {@link #FINAL_V2} is the default of the benchmark. It composes the LMRS as upstream's {@code ShortMerge} demo does since
 * upstream commit {@code d921ef913} (synchronization and cooperation that stop below the creep speed, so that a merger
 * creeps into a gap instead of synchronizing with a standing queue), and gives cars and trucks the driver parameters of the
 * MiRoVA campaign {@code final_v2} wherever the LMRS has a parameter of the same meaning. {@link #UPSTREAM} touches nothing
 * and is exactly what {@code new LmrsFactory<>(Lmrs::new)} gives.
 * </p>
 * <p>
 * Gap acceptance stays {@code INFORMED}, as in {@code ShortMerge}. Since {@code d921ef913} both gap-acceptance variants judge a
 * gap with the headway relaxed by the lane-change desire, T = (1 - desire) Tmax, down to zero at full desire, so the
 * acceptance of very small gaps under urgency is part of either.
 * </p>
 * <p>
 * Where the {@code final_v2} values come from: {@code TamaFinalValidationStudy.parameters} of the MiRoVA checkout, resolved by
 * that code itself, at commit {@code e045b306f}. Carried over are those with the same meaning in the LMRS:
 * </p>
 * <table>
 * <caption>Driver parameters of {@link #FINAL_V2}</caption>
 * <tr><th>parameter</th><th>car</th><th>truck</th><th>upstream</th></tr>
 * <tr><td>{@code a}</td><td>1.40 m/s2</td><td>1.25 m/s2</td><td>1.25 m/s2</td></tr>
 * <tr><td>{@code b}</td><td>1.75 m/s2</td><td>1.75 m/s2</td><td>2.09 m/s2</td></tr>
 * <tr><td>{@code s0}</td><td>3.0 m</td><td>6.0 m</td><td>3.0 m</td></tr>
 * <tr><td>{@code T} and {@code Tmax}</td><td>1.00 s</td><td>1.30 s</td><td>1.2 s</td></tr>
 * <tr><td>{@code vGain}</td><td>15 km/h</td><td>30 km/h</td><td>69.6 km/h</td></tr>
 * </table>
 * <p>
 * {@code T} is the current headway of the LMRS, which it lowers to {@code Tmin} after a lane change and relaxes back to
 * {@code Tmax}; the desired headway of {@code final_v2} is therefore set on both. {@code Tmin} keeps upstream's 0.56 s.
 * {@code vGain} enters the speed incentive of both models in the same formula, d = aGain (v_target - v_current) / vGain.
 * Not carried over, because the LMRS has nothing of that meaning: the deceleration thresholds of the merge and of
 * cooperation, the safety-distance reduction and relaxation damping of the headway relaxation, the maximum acceleration of
 * the MiRoVA patterns, far anticipation and the capacity drop. The cooperative lane changes of {@code final_v2} (cars on,
 * trucks off) are mapped to the courtesy incentive of the LMRS, which makes the same move: changing lane to let a merger in.
 * The courtesy incentive is weighted by the parameter {@code socio}, which stays at upstream's value of 1.0.
 * </p>
 * <p>
 * The desired-speed distribution of {@code final_v2} is a property of the scenario, not of the driver model, and is set by
 * the facility (see {@code FreiburgStudyParameters}).
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public enum LmrsDefaults
{
    /** Upstream's factory as it is: no setting and no parameter touched. */
    UPSTREAM("upstream")
    {
        @Override
        public LmrsSettings settings(final String vehicleClass)
        {
            return new LmrsSettings();
        }

        @Override
        public Map<ParameterType<?>, Object> parameters(final String vehicleClass)
        {
            return Collections.emptyMap();
        }
    },

    /** The composition of upstream's ShortMerge demo, with the driver parameters of the MiRoVA campaign final_v2. */
    FINAL_V2("final-v2")
    {
        @Override
        public LmrsSettings settings(final String vehicleClass)
        {
            boolean car = isCar(vehicleClass);
            return new LmrsSettings()
                    // stop synchronizing and cooperating below the creep speed, so that a merger creeps into a gap
                    .set(Setting.SYNCHRONIZATION, Synchronization.ALIGN_GAP_MOVING)
                    .set(Setting.COOPERATION, Cooperation.PASSIVE_MOVING)
                    // upstream's default, stated: it judges gaps with the desire-relaxed headway
                    .set(Setting.GAP_ACCEPTANCE, GapAcceptance.INFORMED)
                    // final_v2: cooperative lane changes for cars, not for trucks
                    .set(Setting.INCENTIVE_COURTESY, car);
        }

        @Override
        public Map<ParameterType<?>, Object> parameters(final String vehicleClass)
        {
            boolean car = isCar(vehicleClass);
            Map<ParameterType<?>, Object> parameters = new LinkedHashMap<>();
            parameters.put(ParameterTypes.A, car ? CAR_A : TRUCK_A);
            parameters.put(ParameterTypes.B, B);
            parameters.put(ParameterTypes.S0, car ? CAR_S0 : TRUCK_S0);
            // T before Tmax: T is the current headway and has no constraint against Tmax
            parameters.put(ParameterTypes.T, car ? CAR_T : TRUCK_T);
            parameters.put(ParameterTypes.TMAX, car ? CAR_T : TRUCK_T);
            parameters.put(LmrsParameters.VGAIN, car ? CAR_VGAIN : TRUCK_VGAIN);
            return Collections.unmodifiableMap(parameters);
        }
    };

    /** The defaults a run starts from when it names none. */
    public static final LmrsDefaults DEFAULT = FINAL_V2;

    /** final_v2: maximum acceleration of cars. */
    public static final Acceleration CAR_A = Acceleration.ofSI(1.40);

    /** final_v2: maximum acceleration of trucks. */
    public static final Acceleration TRUCK_A = Acceleration.ofSI(1.25);

    /** final_v2: comfortable deceleration, cars and trucks. */
    public static final Acceleration B = Acceleration.ofSI(1.75);

    /** final_v2: stopping distance of cars. */
    public static final Length CAR_S0 = Length.ofSI(3.0);

    /** final_v2: stopping distance of trucks. */
    public static final Length TRUCK_S0 = Length.ofSI(6.0);

    /** final_v2: desired headway of cars. */
    public static final Duration CAR_T = Duration.ofSI(1.00);

    /** final_v2: desired headway of trucks. */
    public static final Duration TRUCK_T = Duration.ofSI(1.30);

    /** final_v2: speed gain at full lane-change desire, cars. */
    public static final Speed CAR_VGAIN = new Speed(15.0, SpeedUnit.KM_PER_HOUR);

    /** final_v2: speed gain at full lane-change desire, trucks. */
    public static final Speed TRUCK_VGAIN = new Speed(30.0, SpeedUnit.KM_PER_HOUR);

    /** Name the defaults are selected and recorded by. */
    private final String id;

    /**
     * Constructor.
     * @param id String; the name the defaults are selected and recorded by
     */
    LmrsDefaults(final String id)
    {
        this.id = id;
    }

    /**
     * Returns the name the defaults are selected and recorded by.
     * @return String; the name, for example {@code final-v2}
     */
    public String id()
    {
        return this.id;
    }

    /**
     * Returns the settings of the factory of one vehicle class.
     * @param vehicleClass String; {@code car} or {@code truck}
     * @return LmrsSettings; the settings, empty when nothing is changed
     */
    public abstract LmrsSettings settings(String vehicleClass);

    /**
     * Returns the driver parameters of one vehicle class, in the order they are to be applied.
     * @param vehicleClass String; {@code car} or {@code truck}
     * @return Map&lt;ParameterType&lt;?&gt;, Object&gt;; parameter to value, empty when nothing is changed
     */
    public abstract Map<ParameterType<?>, Object> parameters(String vehicleClass);

    /**
     * Returns the defaults with the given name.
     * @param id String; the name, for example {@code final-v2} or {@code upstream}
     * @return LmrsDefaults; the defaults
     * @throws IllegalArgumentException when no defaults have that name
     */
    public static LmrsDefaults of(final String id)
    {
        for (LmrsDefaults defaults : values())
        {
            if (defaults.id.equals(id.trim().toLowerCase(Locale.ROOT)))
            {
                return defaults;
            }
        }
        throw new IllegalArgumentException("No LMRS defaults '" + id + "'; known: upstream, final-v2.");
    }

    /**
     * Returns whether a vehicle class is the car class, and refuses any class but car and truck.
     * @param vehicleClass String; the vehicle class
     * @return boolean; true for {@code car}, false for {@code truck}
     * @throws IllegalArgumentException for any other vehicle class, which these defaults have no values for
     */
    static boolean isCar(final String vehicleClass)
    {
        if ("car".equals(vehicleClass))
        {
            return true;
        }
        if ("truck".equals(vehicleClass))
        {
            return false;
        }
        throw new IllegalArgumentException("The LMRS defaults have values for car and truck, not for '" + vehicleClass + "'.");
    }
}
