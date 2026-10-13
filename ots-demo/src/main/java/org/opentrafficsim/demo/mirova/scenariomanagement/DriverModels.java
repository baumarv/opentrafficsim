package org.opentrafficsim.demo.mirova.scenariomanagement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Supplier;

import org.opentrafficsim.demo.mirova.scenariomanagement.models.LmrsDriverModelProvider;

/**
 * Registry of the driver models a run can select with {@code --model}.
 * <p>
 * Two sources are searched: the built-in providers listed here, and every {@link DriverModelProvider} registered through
 * {@link ServiceLoader}. The second is how a model that lives in another code base is added: its jar carries a
 * {@code META-INF/services} entry and is put on the classpath, and nothing in this repository changes.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public final class DriverModels
{
    /** Key of the default driver model: the LMRS of upstream OTS with its upstream defaults. */
    public static final String LMRS = LmrsDriverModelProvider.KEY;

    /** Built-in providers by key. A supplier, because a provider holds what it built and is therefore created per request. */
    private static final Map<String, Supplier<DriverModelProvider>> BUILT_IN = new LinkedHashMap<>();

    static
    {
        BUILT_IN.put(LmrsDriverModelProvider.KEY, LmrsDriverModelProvider::new);
    }

    /**
     * Utility class.
     */
    private DriverModels()
    {
        // utility class
    }

    /**
     * Returns a new provider for the given key.
     * <p>
     * A key that matches no provider, or more than one, is a configuration error and fails the run during setup instead of
     * silently driving with another model.
     * </p>
     * @param key String; the model key
     * @return DriverModelProvider; a provider for that key
     * @throws IllegalArgumentException when the key matches no provider or more than one
     */
    public static DriverModelProvider resolve(final String key)
    {
        List<DriverModelProvider> matching = new ArrayList<>();
        Supplier<DriverModelProvider> builtIn = BUILT_IN.get(key);
        if (builtIn != null)
        {
            matching.add(builtIn.get());
        }
        for (DriverModelProvider provider : ServiceLoader.load(DriverModelProvider.class))
        {
            if (provider.key().equals(key))
            {
                matching.add(provider);
            }
        }
        if (matching.size() != 1)
        {
            throw new IllegalArgumentException("Driver model '" + key + "' matches " + matching.size()
                    + " provider(s); available: " + availableKeys() + ". Use one of these keys, or put exactly one provider "
                    + "of that key on the classpath.");
        }
        return matching.get(0);
    }

    /**
     * Returns the keys of all driver models that can be selected in this JVM.
     * @return List&lt;String&gt;; the keys, built-in models first
     */
    public static List<String> availableKeys()
    {
        List<String> keys = new ArrayList<>(BUILT_IN.keySet());
        for (DriverModelProvider provider : ServiceLoader.load(DriverModelProvider.class))
        {
            keys.add(provider.key());
        }
        return keys;
    }
}
