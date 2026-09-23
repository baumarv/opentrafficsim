package org.opentrafficsim.demo.mirova.scenariomanagement;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.opentrafficsim.base.parameters.ParameterType;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;

/**
 * Every {@code car.}/{@code truck.} key a study can set must resolve to a parameter type.
 * <p>
 * {@code ScenarioGenerator.getParameters} looks a key up and applies it <i>only when it resolves</i>:
 * <pre>
 *   ParameterType&lt;?&gt; pt = PARAMETER_TYPES.get(paramId);
 *   if (pt != null) { applyParameter(parameters, pt, entry.getValue()); }
 * </pre>
 * A key that resolves to nothing is therefore dropped without a word. The run starts, the manifest
 * records the value the study asked for, every plot is labelled with it, and the driver never saw it.
 * There is no output anywhere that distinguishes that from a parameter that was applied and did
 * nothing -- which is exactly the question a parameter study exists to answer.
 * </p>
 * <p>
 * This pins the resolution for every parameter {@link MirovaParameters} declares, so a new one cannot
 * be added, set by a study and silently ignored.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class ScenarioParameterKeysResolveTest
{
    /**
     * The lookup key the generator builds from a {@code car.}-prefixed scenario key.
     * @param id String; the parameter's id
     * @return String; the lookup key
     */
    private static String lookupKey(final String id)
    {
        return id.toLowerCase(Locale.ROOT);
    }

    /**
     * Every parameter MirovaParameters declares resolves in the generator's table.
     * @throws Exception when a declared field cannot be read reflectively
     */
    @Test
    public void everyMirovaParameterResolves() throws Exception
    {
        List<String> unresolved = new ArrayList<>();
        int checked = 0;
        for (Field field : MirovaParameters.class.getFields())
        {
            if (!Modifier.isStatic(field.getModifiers()) || !ParameterType.class.isAssignableFrom(field.getType()))
            {
                continue;
            }
            ParameterType<?> declared = (ParameterType<?>) field.get(null);
            if (declared == null)
            {
                continue;
            }
            checked++;
            if (ScenarioGenerator.PARAMETER_TYPES.get(lookupKey(declared.getId())) == null)
            {
                unresolved.add(field.getName() + " (id=" + declared.getId() + ")");
            }
        }
        assertTrue(checked > 0, "no parameters were checked; the reflection this test shares with the "
                + "generator no longer finds MirovaParameters' fields, so neither does the generator");
        assertTrue(unresolved.isEmpty(), "these parameters cannot be set by a study -- a scenario key for "
                + "them is dropped without a word: " + unresolved);
    }

    /**
     * The threshold curvature in particular, by the exact key a study writes.
     * <p>
     * Spelled out rather than left to the sweep above, because this is the key the {@code tamacurve}
     * study sets and every number of that campaign is meaningless if it does not arrive.
     * </p>
     */
    @Test
    public void theThresholdCurvatureResolvesByItsScenarioKey()
    {
        String scenarioKey = "car." + MirovaParameters.thresholdCurvature.getId();
        String paramId = scenarioKey.substring("car.".length()).toLowerCase(Locale.ROOT);
        assertNotNull(ScenarioGenerator.PARAMETER_TYPES.get(paramId),
                "'" + scenarioKey + "' does not resolve, so a study that sets it runs the default and "
                        + "reports the value it asked for");
    }
}
