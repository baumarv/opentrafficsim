package org.opentrafficsim.demo.mirova.scenariomanagement.models;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opentrafficsim.road.gtu.tactical.lmrs.LmrsFactory;
import org.opentrafficsim.road.gtu.tactical.lmrs.LmrsFactory.Setting;
import org.opentrafficsim.road.gtu.tactical.util.lmrs.Cooperation;
import org.opentrafficsim.road.gtu.tactical.util.lmrs.GapAcceptance;
import org.opentrafficsim.road.gtu.tactical.util.lmrs.Synchronization;

/**
 * A composition of the LMRS, stated with the LMRS's own types.
 * <p>
 * This is a list of {@link Setting}s of upstream's {@link LmrsFactory} with the values to give them, for example
 * {@code new LmrsSettings().set(Setting.SYNCHRONIZATION, Synchronization.ALIGN_GAP)}. Setting and value are checked against
 * each other by the compiler, and a strategy is the object the LMRS itself uses, not a name for it. A scenario hands such an
 * object to the LMRS provider as the scenario parameter {@link LmrsDriverModelProvider#KEY_SETTINGS}.
 * </p>
 * <p>
 * The class can also read a factory back: {@link #read(LmrsFactory)} returns what a factory is actually set to, by name. That
 * is what a run records, so that the record states the composition that ran and not the one that was asked for.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public final class LmrsSettings
{
    /** The settings of {@link LmrsFactory} by the name of their constant in {@link Setting}, in declaration order. */
    private static final Map<String, Setting<?>> SETTINGS = new LinkedHashMap<>();

    /**
     * The strategies of the LMRS that are objects rather than enum values, each with the name of its constant. Keyed by the
     * object, by identity: the names repeat between the strategy types (PASSIVE is a synchronization and a cooperation).
     */
    private static final Map<Object, String> STRATEGIES = new IdentityHashMap<>();

    static
    {
        for (Map.Entry<String, ?> setting : constantsOf(Setting.class, Setting.class).entrySet())
        {
            SETTINGS.put(setting.getKey(), (Setting<?>) setting.getValue());
        }
        for (Class<?> strategyType : List.of(Synchronization.class, Cooperation.class, GapAcceptance.class))
        {
            for (Map.Entry<String, ?> strategy : constantsOf(strategyType, strategyType).entrySet())
            {
                STRATEGIES.put(strategy.getValue(), strategy.getKey());
            }
        }
    }

    /** Setting to value, in the order they were set. */
    private final Map<Setting<?>, Object> values = new LinkedHashMap<>();

    /**
     * Constructor of an empty list of settings, which leaves a factory as it is.
     */
    public LmrsSettings()
    {
        // empty
    }

    /**
     * Sets a setting, for all GTU types.
     * @param setting Setting&lt;V&gt;; the setting, a constant of {@link Setting}
     * @param value V; its value
     * @param <V> the value type of the setting
     * @return LmrsSettings; this object for method chaining
     * @throws NullPointerException when setting or value is null
     */
    public <V> LmrsSettings set(final Setting<V> setting, final V value)
    {
        if (setting == null || value == null)
        {
            throw new NullPointerException("setting and value may not be null; leave a setting out to keep its default");
        }
        this.values.put(setting, value);
        return this;
    }

    /**
     * Returns whether no setting is set.
     * @return boolean; whether no setting is set
     */
    public boolean isEmpty()
    {
        return this.values.isEmpty();
    }

    /**
     * Applies the settings to a factory, each through {@link LmrsFactory#set(Setting, Object)}.
     * @param factory LmrsFactory&lt;?&gt;; the factory to configure
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void applyTo(final LmrsFactory<?> factory)
    {
        for (Map.Entry<Setting<?>, Object> entry : this.values.entrySet())
        {
            // set(Setting<V>, V) guaranteed that the value fits the setting
            factory.set((Setting) entry.getKey(), entry.getValue());
        }
    }

    /**
     * Reads what a factory is set to.
     * <p>
     * Every setting whose value can be named is returned: switches, enum values and the strategies that are constants of
     * {@link Synchronization}, {@link Cooperation} and {@link GapAcceptance}. Settings that hold functions or collections of
     * components - the car-following model, custom incentives - have no name to report and are left out.
     * </p>
     * @param factory LmrsFactory&lt;?&gt;; the factory to read
     * @return Map&lt;String, String&gt;; name of the setting's constant in {@link Setting} to the name of its value; with
     *         different values per GTU type they are joined by a bar
     */
    public static Map<String, String> read(final LmrsFactory<?> factory)
    {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Setting<?>> entry : SETTINGS.entrySet())
        {
            List<?> list = entry.getValue().getListFunction().apply(factory);
            List<String> names = new ArrayList<>();
            for (Object value : list)
            {
                String name = nameOf(value);
                if (name == null)
                {
                    names = null;
                    break;
                }
                names.add(name);
            }
            if (names != null && !names.isEmpty())
            {
                result.put(entry.getKey(), String.join("|", names));
            }
        }
        return result;
    }

    /**
     * Returns the name of a setting value, when it has one.
     * @param value Object; the value
     * @return String; its name, or null when the value is a function, a collection or otherwise has no name
     */
    private static String nameOf(final Object value)
    {
        if (value instanceof Boolean || value instanceof Enum || value instanceof String || value instanceof Number)
        {
            return String.valueOf(value);
        }
        return STRATEGIES.get(value);
    }

    /**
     * Collects the public static constants of a type that a class declares.
     * @param declaringClass Class&lt;?&gt;; the class or interface declaring the constants
     * @param type Class&lt;T&gt;; the type of the constants to collect
     * @param <T> the type of the constants
     * @return Map&lt;String, T&gt;; field name to constant, in declaration order
     */
    private static <T> Map<String, T> constantsOf(final Class<?> declaringClass, final Class<T> type)
    {
        Map<String, T> constants = new LinkedHashMap<>();
        for (Field field : declaringClass.getDeclaredFields())
        {
            if (Modifier.isPublic(field.getModifiers()) && Modifier.isStatic(field.getModifiers())
                    && type.isAssignableFrom(field.getType()))
            {
                try
                {
                    constants.put(field.getName(), type.cast(field.get(null)));
                }
                catch (IllegalAccessException exception)
                {
                    throw new IllegalStateException("Cannot read " + declaringClass.getName() + "." + field.getName(),
                            exception);
                }
            }
        }
        return constants;
    }

    @Override
    public String toString()
    {
        Map<String, String> named = new LinkedHashMap<>();
        for (Map.Entry<Setting<?>, Object> entry : this.values.entrySet())
        {
            String setting = "?";
            for (Map.Entry<String, Setting<?>> known : SETTINGS.entrySet())
            {
                if (known.getValue() == entry.getKey())
                {
                    setting = known.getKey();
                }
            }
            String value = nameOf(entry.getValue());
            named.put(setting, value == null ? entry.getValue().getClass().getName() : value);
        }
        return "LmrsSettings" + named;
    }
}
