package org.opentrafficsim.demo.mirova.fsmtrace;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ActionState;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ManeuverPattern;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.Transition;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging.FsmTraceRecorder;

/**
 * Writes the Layer 3 transition graph as PlantUML, read off the states themselves rather than reconstructed by hand.
 * <p>
 * This is the reason the transition tables exist. As long as a state's transitions were a cascade of {@code if} statements
 * inside {@code next()}, the only descriptions of the graph were a diagram somebody drew once and a paragraph somebody wrote
 * once, and both went out of date silently. A table can be asked what it contains.
 * </p>
 * <p>
 * The export needs live states, because a table is built by a state instance and several rules are inherited. It therefore
 * takes states that a caller has already constructed -- typically from a running simulation, or from a test that builds one
 * of each.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public final class TransitionGraphExport
{

    /** Utility class, not instantiated. */
    private TransitionGraphExport()
    {
        //
    }

    /**
     * Writes the transition tables of the given states as a PlantUML state diagram.
     * @param states the states to describe, in the order they should appear
     * @param target the file to write
     * @return the file that was written
     * @throws IOException if the file cannot be written
     */
    public static Path writePlantUml(final List<ActionState> states, final Path target) throws IOException
    {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null)
        {
            Files.createDirectories(parent);
        }
        try (PrintStream out = new PrintStream(Files.newOutputStream(target), false, StandardCharsets.UTF_8.name()))
        {
            write(states, out);
        }
        return target;
    }

    /**
     * Writes the transition tables of the given states as a PlantUML state diagram.
     * @param states the states to describe
     * @param out where to write
     */
    public static void write(final List<ActionState> states, final PrintStream out)
    {
        Map<String, ManeuverPattern> patterns = new LinkedHashMap<>();
        for (ActionState state : states)
        {
            patterns.put(state.getManeuverPattern().getClass().getSimpleName(), state.getManeuverPattern());
        }

        out.println("@startuml");
        out.println("hide empty description");
        out.println();
        out.println("' Edges are declared in the code; the counts are from one run of one scenario at one seed.");
        out.println("' \"not taken in this run\" means exactly that, and never that the transition is dead: the");
        out.println("' synthetic merge case, for one, never enters the congested branch at all.");
        out.println();

        for (String pattern : patterns.keySet())
        {
            out.println("state \"" + pattern + "\" as " + identifier(pattern) + " {");

            // The pattern's own starting point, so the diagram has somewhere to begin. Read from the pattern rather than
            // guessed from the order states were entered, which depends on the traffic the run happened to produce.
            // Building it can fail when the run has already ended and the vehicle it belonged to is gone; the diagram is
            // then one arrow poorer rather than lost.
            try
            {
                ActionState initial = patterns.get(pattern).getInitialActionState();
                out.println("  [*] --> " + identifier(initial.getClass().getSimpleName()));
            }
            catch (Exception exception)
            {
                out.println("  ' initial state not available: " + exception.getClass().getSimpleName());
            }

            for (ActionState state : states)
            {
                if (!state.getManeuverPattern().getClass().getSimpleName().equals(pattern))
                {
                    continue;
                }
                String from = identifier(name(state));
                int order = 0;
                List<Transition> rules = state.getTransitions();
                for (Transition transition : rules)
                {
                    int taken = FsmTraceRecorder.getTimesTaken(state, order);
                    order++;
                    String howOften = taken == 0 ? " [not taken in this run]" : " [" + taken + "x]";
                    // A rule may lead to one of several states -- the merge analysis picks between three. Each is drawn as
                    // its own edge under the same rule number, so the diagram shows every branch the rule can take rather
                    // than collapsing them into an unnamed one.
                    for (String target : transition.getTarget().split("\\|"))
                    {
                        String to = "end".equals(target) ? "[*]" : identifier(target);
                        out.println("  " + from + " --> " + to + " : " + order + ". " + transition.getName()
                                + howOften);
                    }
                }
            }
            out.println("}");
            out.println();
        }
        out.println("@enduml");
    }

    /**
     * Returns the name a state appears under. The class name rather than {@code toString()}, which several states use to
     * carry a direction or a candidate vehicle and which would therefore produce one node per vehicle.
     * @param state the state
     * @return the state's name
     */
    private static String name(final ActionState state)
    {
        return state.getClass().getSimpleName();
    }

    /**
     * Turns a name into something PlantUML accepts as an identifier.
     * @param name the name
     * @return the identifier
     */
    private static String identifier(final String name)
    {
        return name.replaceAll("[^A-Za-z0-9_]", "_");
    }

}
