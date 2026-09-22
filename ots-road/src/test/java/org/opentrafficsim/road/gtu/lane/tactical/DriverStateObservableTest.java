package org.opentrafficsim.road.gtu.lane.tactical;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.MirovaTacticalPlanner;

/**
 * The sampler columns must read the interface, not one concrete planner.
 * <p>
 * The defect these pin was invisible in the output and was found on a campaign pilot: the columns asked
 * {@code instanceof MirovaTacticalPlanner}, so a run on any other driver model produced a file of the right size,
 * with the right columns, in which five of them were {@code none} and {@code NaN} on every row. Nothing failed,
 * nothing warned, and every metric reading those columns would have been empty for sixteen of seventeen cells.
 * </p>
 * <p>
 * The source is checked rather than the behaviour because the behaviour needs a running simulation with a GTU, and
 * a regression here does not change behaviour under MiRoVA at all -- it only empties the columns for everyone
 * else, which is exactly the thing that does not show up.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class DriverStateObservableTest
{
    /** The extended-data columns FreiburgNord registers, which are the ones a campaign run writes. */
    private static final List<String> REGISTERED_COLUMNS = List.of("ExtendedDataActionState",
            "ExtendedDataLaneChangeDesireLeft", "ExtendedDataLaneChangeDesireRight", "ExtendedDataAccelerationDamping",
            "ExtendedDataCurrentCFAcceleration");

    /** Where those classes live. */
    private static final Path COLUMN_DIR = Path.of("src", "main", "java", "org", "opentrafficsim", "road", "gtu",
            "lane", "tactical", "mirova", "util", "logging", "extendeddata");

    /** The MiRoVA planner must satisfy the interface, or the columns would go empty for MiRoVA instead. */
    @Test
    public void theMirovaPlannerIsObservable()
    {
        assertTrue(DriverStateObservable.class.isAssignableFrom(MirovaTacticalPlanner.class),
                "MirovaTacticalPlanner no longer implements DriverStateObservable");
    }

    /**
     * No registered column may name the concrete planner.
     * @throws IOException when a column source cannot be read
     */
    @Test
    public void noRegisteredColumnBindsToOneConcretePlanner() throws IOException
    {
        List<String> offenders = new ArrayList<>();
        for (String column : REGISTERED_COLUMNS)
        {
            Path source = COLUMN_DIR.resolve(column + ".java");
            assertTrue(Files.isRegularFile(source), "column source not found: " + source);
            String text = Files.readString(source, StandardCharsets.UTF_8);
            if (text.contains("MirovaTacticalPlanner"))
            {
                offenders.add(column);
            }
            assertTrue(text.contains("DriverStateObservable"),
                    column + " does not read DriverStateObservable, so it is empty for every other driver model");
        }
        assertTrue(offenders.isEmpty(), "these columns bind to one concrete planner again: " + offenders);
    }

    /** The interface's "unknown" value for the state is the string the sampler writes, and is not empty. */
    @Test
    public void theUnknownStateIsAWrittenValue()
    {
        assertFalse(DriverStateObservable.NO_ACTION_STATE.isBlank(),
                "an empty state name would be indistinguishable from a column that was never filled");
    }
}
