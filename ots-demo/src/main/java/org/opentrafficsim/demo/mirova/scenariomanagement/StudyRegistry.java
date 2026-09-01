package org.opentrafficsim.demo.mirova.scenariomanagement;

import java.util.LinkedHashMap;
import java.util.Map;

import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.DateStudy;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.FreiburgCombinationStudy;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.FreiburgDampingStudy;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.FreiburgBehaviourStudy;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.FreiburgCarStudy;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.FreiburgSensitivityStudy;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.FreiburgSettledStudy;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.FreiburgSmoothnessStudy;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.FreiburgValidationStudy;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.FreiburgMergeGridStudy;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.FreiburgParameterStudy;

/**
 * Lookup of the known {@link StudyDefinition} implementations by short name.
 * <p>
 * A study may also be selected by its fully qualified class name, so a new study can be run on the cluster without touching
 * this class; registering it here merely gives it a convenient short name.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author Marvin Baumann
 */
public final class StudyRegistry
{
    /** The known studies, by short name, in listing order. */
    private static final Map<String, Class<? extends StudyDefinition>> STUDIES = new LinkedHashMap<>();

    static
    {
        STUDIES.put("dates", DateStudy.class);
        STUDIES.put("paramgrid", FreiburgParameterStudy.class);
        STUDIES.put("combos", FreiburgCombinationStudy.class);
        STUDIES.put("damping", FreiburgDampingStudy.class);
        STUDIES.put("mergegrid", FreiburgMergeGridStudy.class);
        STUDIES.put("behaviour", FreiburgBehaviourStudy.class);
        STUDIES.put("carparams", FreiburgCarStudy.class);
        STUDIES.put("validation", FreiburgValidationStudy.class);
        STUDIES.put("smoothness", FreiburgSmoothnessStudy.class);
        STUDIES.put("settled", FreiburgSettledStudy.class);
        STUDIES.put("sensitivity", FreiburgSensitivityStudy.class);
    }

    /** Utility class; not to be instantiated. */
    private StudyRegistry()
    {
        // utility class
    }

    /**
     * Resolves a study by its short name, or, failing that, by its fully qualified class name.
     * @param selector String; the short name or fully qualified class name of the study
     * @return StudyDefinition; a new instance of the selected study
     * @throws IllegalArgumentException when the selector matches neither a known short name nor an instantiable
     *             {@link StudyDefinition} class
     */
    public static StudyDefinition resolve(final String selector)
    {
        Class<? extends StudyDefinition> studyClass = STUDIES.get(selector);
        if (studyClass == null)
        {
            try
            {
                Class<?> named = Class.forName(selector);
                if (!StudyDefinition.class.isAssignableFrom(named))
                {
                    throw new IllegalArgumentException(
                            "Class " + selector + " does not implement " + StudyDefinition.class.getName() + ".");
                }
                studyClass = named.asSubclass(StudyDefinition.class);
            }
            catch (ClassNotFoundException e)
            {
                throw new IllegalArgumentException(
                        "Unknown study '" + selector + "'. Known studies: " + String.join(", ", STUDIES.keySet())
                                + ". Alternatively give the fully qualified class name of a StudyDefinition.");
            }
        }

        try
        {
            return studyClass.getDeclaredConstructor().newInstance();
        }
        catch (ReflectiveOperationException e)
        {
            throw new IllegalArgumentException(
                    "Study '" + selector + "' could not be instantiated; it needs a public no-argument constructor.", e);
        }
    }

    /**
     * Returns the short names of all registered studies.
     * @return String; the known short names, comma-separated
     */
    public static String knownStudies()
    {
        return String.join(", ", STUDIES.keySet());
    }
}
