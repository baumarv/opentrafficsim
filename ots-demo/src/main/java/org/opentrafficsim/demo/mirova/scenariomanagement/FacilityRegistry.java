package org.opentrafficsim.demo.mirova.scenariomanagement;

import java.util.LinkedHashMap;
import java.util.Map;

import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.FreiburgFacility;

/**
 * Lookup of the known {@link TrafficFacility} implementations by short name, mirroring {@link StudyRegistry}.
 * <p>
 * A facility may also be selected by its fully qualified class name, so a new one can be run without touching this class;
 * registering it here merely gives it a convenient short name.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author Marvin Baumann
 */
public final class FacilityRegistry
{
    /** The known facilities, by short name, in listing order. */
    private static final Map<String, Class<? extends TrafficFacility>> FACILITIES = new LinkedHashMap<>();

    static
    {
        FACILITIES.put(FreiburgFacility.NAME, FreiburgFacility.class);
    }

    /** Utility class; not to be instantiated. */
    private FacilityRegistry()
    {
        // utility class
    }

    /**
     * Resolves a facility by its short name, or, failing that, by its fully qualified class name.
     * @param selector String; the short name or fully qualified class name of the facility
     * @return TrafficFacility; a new instance of the selected facility
     * @throws IllegalArgumentException when the selector matches neither a known short name nor an instantiable
     *             {@link TrafficFacility} class
     */
    public static TrafficFacility resolve(final String selector)
    {
        Class<? extends TrafficFacility> facilityClass = FACILITIES.get(selector);
        if (facilityClass == null)
        {
            try
            {
                Class<?> named = Class.forName(selector);
                if (!TrafficFacility.class.isAssignableFrom(named))
                {
                    throw new IllegalArgumentException(
                            "Class " + selector + " does not implement " + TrafficFacility.class.getName() + ".");
                }
                facilityClass = named.asSubclass(TrafficFacility.class);
            }
            catch (ClassNotFoundException e)
            {
                throw new IllegalArgumentException("Unknown facility '" + selector + "'. Known facilities: "
                        + String.join(", ", FACILITIES.keySet())
                        + ". Alternatively give the fully qualified class name of a TrafficFacility.");
            }
        }

        try
        {
            return facilityClass.getDeclaredConstructor().newInstance();
        }
        catch (ReflectiveOperationException e)
        {
            throw new IllegalArgumentException(
                    "Facility '" + selector + "' could not be instantiated; it needs a public no-argument constructor.", e);
        }
    }

    /**
     * Returns the short names of all registered facilities.
     * @return String; the known short names, comma-separated
     */
    public static String knownFacilities()
    {
        return String.join(", ", FACILITIES.keySet());
    }
}
