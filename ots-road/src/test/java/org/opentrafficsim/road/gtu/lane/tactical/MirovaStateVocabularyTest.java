package org.opentrafficsim.road.gtu.lane.tactical;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * MiRoVA's manoeuvre-state vocabulary is pinned, because another repository maps onto it.
 * <p>
 * TaMA declares a mapping from its own state names to these. A name changed here without the mapping being
 * changed there does not break either build: it makes every row carrying that state drop out of a join,
 * quietly, in an evaluation nobody is watching at the time. So this side pins what it publishes.
 * </p>
 * <p>
 * The counterpart is {@code StateVocabularyTest} in the TaMA repository, which holds the same list and checks
 * that every target of the mapping is in it. When this test fails, that list is what has to change with it.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class MirovaStateVocabularyTest
{
    /** Where the manoeuvre patterns live. */
    private static final Path PATTERN_DIR = Path.of("src", "main", "java", "org", "opentrafficsim", "road", "gtu",
            "lane", "tactical", "mirova", "core", "IntentionLayer", "ManeuverPatterns");

    /** A state name is the literal a {@code toString} returns, up to its bracketed parameters. */
    private static final Pattern NAME = Pattern.compile("return \"([A-Za-z:]+)(?:\\[|\")");

    /**
     * The names TaMA's mapping targets, and therefore the ones that may not change silently.
     * <p>
     * Two of these carry their pattern (`PreventUndercutting:Shadowing`) and the rest do not. That is this
     * side's inconsistency; it is pinned as it is rather than tidied, because the pin has to match what a run
     * writes, not what would have been neater.
     * </p>
     */
    private static final Set<String> PINNED = new TreeSet<>(List.of("AnticipateMergeState", "CongestedCreepState",
            "CongestedFollowLeaderState", "EmergencyStopState", "ExecuteLaneChange", "FarAnticipationState",
            "MatchLeaderSpeedState", "NearAnticipationState", "OpenGapState", "PerformLaneChangeState",
            "PreventUndercutting:PrepareLaneChange", "PreventUndercutting:Shadowing", "SolveParallelVehicleState",
            "SynchroniseMergeSpeedState"));

    /**
     * The vocabulary the pattern sources actually declare is the pinned one.
     * @throws IOException when a pattern source cannot be read
     */
    @Test
    public void theVocabularyIsTheOnePinnedForTheMapping() throws IOException
    {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> sources = Files.list(PATTERN_DIR))
        {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList())
            {
                Matcher matcher = NAME.matcher(Files.readString(source, StandardCharsets.UTF_8));
                while (matcher.find())
                {
                    found.add(matcher.group(1));
                }
            }
        }
        // FINISHED is a marker rather than a manoeuvre state and is not part of the published vocabulary.
        found.remove("FINISHED");
        assertEquals(PINNED, found,
                "MiRoVA's state vocabulary changed. TaMA's StateVocabulary maps onto these names; a name "
                        + "changed here and not there drops every row carrying it out of a join, silently. "
                        + "Added here: " + minus(found, PINNED) + "; gone from here: " + minus(PINNED, found));
    }

    /**
     * Set difference, for the message.
     * @param from Set&lt;String&gt;; the set to subtract from
     * @param remove Set&lt;String&gt;; what to subtract
     * @return Set&lt;String&gt;; the difference
     */
    private static Set<String> minus(final Set<String> from, final Set<String> remove)
    {
        Set<String> result = new TreeSet<>(from);
        result.removeAll(remove);
        return result;
    }
}
