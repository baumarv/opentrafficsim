package org.opentrafficsim.demo.mirova.scenariomanagement;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.djunits.value.vdouble.scalar.Acceleration;
import org.djunits.value.vdouble.scalar.Duration;
import org.djunits.value.vdouble.scalar.Frequency;
import org.djunits.value.vdouble.scalar.Length;
import org.djunits.value.vdouble.scalar.LinearDensity;
import org.djunits.value.vdouble.scalar.Speed;
import org.opentrafficsim.base.parameters.ParameterType;

/**
 * Resolves and converts the per-vehicle-class parameter overrides of a scenario.
 * <p>
 * A scenario addresses a behavioural parameter as {@code "car.<parameter id>"} or {@code "truck.<parameter id>"} in its
 * {@link ScenarioParameters}. This class turns such an entry into a {@link ParameterType} and a value of that type's class. It
 * knows no driver model: a model passes the classes that declare its parameter types and gets back what it can set.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public final class ParameterOverrides
{
    /** The SI unit of each unit-typed parameter class, for the message of {@link #rejectBareNumber}. */
    private static final Map<Class<?>, String> SI_UNIT_OF = new LinkedHashMap<>();

    static
    {
        SI_UNIT_OF.put(Duration.class, "seconds");
        SI_UNIT_OF.put(Length.class, "metres");
        SI_UNIT_OF.put(Speed.class, "metres per second");
        SI_UNIT_OF.put(Acceleration.class, "metres per second squared");
        SI_UNIT_OF.put(Frequency.class, "hertz");
        SI_UNIT_OF.put(LinearDensity.class, "per metre");
    }

    /**
     * Utility class.
     */
    private ParameterOverrides()
    {
        // utility class
    }

    /**
     * Collects the public static parameter types declared by the given classes, keyed by lower-case parameter id.
     * @param declaringClasses Class&lt;?&gt;...; the classes whose public static {@link ParameterType} fields are collected
     * @return Map&lt;String, ParameterType&lt;?&gt;&gt;; lower-case parameter id to parameter type; later classes win on a clash
     */
    public static Map<String, ParameterType<?>> parameterTypesOf(final Class<?>... declaringClasses)
    {
        Map<String, ParameterType<?>> types = new LinkedHashMap<>();
        for (Class<?> declaringClass : declaringClasses)
        {
            for (Field field : declaringClass.getFields())
            {
                if (Modifier.isStatic(field.getModifiers()) && ParameterType.class.isAssignableFrom(field.getType()))
                {
                    try
                    {
                        ParameterType<?> parameterType = (ParameterType<?>) field.get(null);
                        if (parameterType != null)
                        {
                            types.put(parameterType.getId().toLowerCase(Locale.ROOT), parameterType);
                        }
                    }
                    catch (IllegalAccessException exception)
                    {
                        throw new IllegalStateException("Cannot read parameter type " + declaringClass.getName() + "."
                                + field.getName(), exception);
                    }
                }
            }
        }
        return types;
    }

    /**
     * Extracts the overrides of one vehicle class from the parameters of a scenario.
     * @param parameters ScenarioParameters; the parameters of the run
     * @param vehicleClass String; {@code "car"} or {@code "truck"}
     * @return Map&lt;String, Object&gt;; lower-case parameter id, without the vehicle class prefix, to the value given
     */
    public static Map<String, Object> forVehicleClass(final ScenarioParameters parameters, final String vehicleClass)
    {
        String prefix = vehicleClass + ".";
        Map<String, Object> overrides = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : parameters.asUnmodifiableMap().entrySet())
        {
            if (entry.getKey().startsWith(prefix))
            {
                overrides.put(entry.getKey().substring(prefix.length()).toLowerCase(Locale.ROOT), entry.getValue());
            }
        }
        return overrides;
    }

    /**
     * Extracts the settings addressed to one driver model from the parameters of a scenario: the entries whose key starts
     * with the model key and a dot.
     * @param parameters ScenarioParameters; the parameters of the run
     * @param modelKey String; the key of the driver model
     * @return Map&lt;String, Object&gt;; setting name, without the model prefix, to the value as it was set
     */
    public static Map<String, Object> forModel(final ScenarioParameters parameters, final String modelKey)
    {
        String prefix = modelKey + ".";
        Map<String, Object> settings = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : parameters.asUnmodifiableMap().entrySet())
        {
            if (entry.getKey().startsWith(prefix))
            {
                settings.put(entry.getKey().substring(prefix.length()), entry.getValue());
            }
        }
        return settings;
    }

    /**
     * Converts a value given for a parameter into the value class of that parameter.
     * <p>
     * A value of the parameter's own class is taken as it is. A bare number is accepted for dimensionless parameters only: a
     * number cannot carry a unit, and reading it as SI is how a gain meant as 15 km/h once ran at 15 m/s. Text is accepted
     * for every parameter, as it comes from a command line: for a quantity it must carry its unit, for example
     * {@code "1.1 s"} or {@code "15 km/h"}.
     * </p>
     * @param parameterType ParameterType&lt;?&gt;; the parameter being set
     * @param value Object; the value given: the parameter's own type, a Number, a Boolean or a String
     * @return Object; the value as an instance of the parameter's value class
     * @throws IllegalArgumentException when the value cannot be converted
     */
    public static Object convert(final ParameterType<?> parameterType, final Object value)
    {
        Class<?> valueClass = parameterType.getValueClass();
        if (valueClass.isInstance(value))
        {
            return value;
        }
        if (value instanceof Number number)
        {
            if (valueClass == Double.class)
            {
                return number.doubleValue();
            }
            if (valueClass == Integer.class)
            {
                return (int) number.doubleValue();
            }
            rejectBareNumber(parameterType, valueClass, number.doubleValue());
        }
        if (value instanceof String string)
        {
            if (valueClass == Boolean.class)
            {
                return Boolean.parseBoolean(string);
            }
            if (valueClass == String.class)
            {
                return string;
            }
            if (valueClass == Double.class)
            {
                return Double.valueOf(string.trim());
            }
            if (valueClass == Integer.class)
            {
                return Integer.valueOf(string.trim());
            }
            if (SI_UNIT_OF.containsKey(valueClass))
            {
                // Every DJUnits scalar has a static valueOf(String) that reads a number with its unit.
                try
                {
                    return valueClass.getMethod("valueOf", String.class).invoke(null, string.trim());
                }
                catch (ReflectiveOperationException exception)
                {
                    throw new IllegalArgumentException("Cannot read '" + string + "' as the " + valueClass.getSimpleName()
                            + " of parameter '" + parameterType.getId() + "'. Give a number with its unit, for example "
                            + "\"1.1 s\".", exception);
                }
            }
        }
        throw new IllegalArgumentException("Cannot convert a value of type " + value.getClass().getSimpleName()
                + " to the class " + valueClass.getSimpleName() + " of parameter '" + parameterType.getId() + "'.");
    }

    /**
     * Refuses a bare number where a unit-typed parameter was expected.
     * @param parameterType ParameterType&lt;?&gt;; the parameter that was being set
     * @param valueClass Class&lt;?&gt;; its value class
     * @param value double; the bare number offered
     * @throws IllegalArgumentException always
     */
    private static void rejectBareNumber(final ParameterType<?> parameterType, final Class<?> valueClass, final double value)
    {
        String unit = SI_UNIT_OF.get(valueClass);
        String type = valueClass.getSimpleName();
        throw new IllegalArgumentException("Parameter '" + parameterType.getId() + "' is a " + type
                + " and was given the bare number " + value + ". A bare number cannot carry a unit, and reading it as SI"
                + (unit == null ? "" : " (" + unit + ")") + " is how a gain meant as 15 km/h came to run at 54 km/h. "
                + "Pass a typed value, for example " + type + ".ofSI(" + value + ").");
    }
}
