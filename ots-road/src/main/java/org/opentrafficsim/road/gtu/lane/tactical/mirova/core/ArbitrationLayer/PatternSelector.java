package org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ArbitrationLayer;

import java.util.ArrayList;

import org.opentrafficsim.base.parameters.ParameterException;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.*;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ManeuverPattern;

/**
 * Determines which {@link ManeuverPattern}s are eligible for arbitration in the current tick.
 * <p>
 * Part of <b>Layer 3 (Decision)</b> in the MiRoVA architecture. A pattern qualifies either because it is already running --
 * a running pattern keeps its turn until it finishes, so that a manoeuvre in progress is never dropped halfway -- or because
 * it is both contextually applicable and physically feasible right now.
 * </p>
 * <p>
 * Choosing between the eligible patterns is not done here. That is the job of the arbitrator, which scores the plans they
 * produce rather than ranking the patterns themselves.
 * </p>
 * <p>
 * Copyright (c) 2025 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public final class PatternSelector
{

    /**
     * * Private constructor to prevent instantiation of this utility class.
     */
    private PatternSelector()
    {
        // Utility class
    }

    /**
     * Filters a list of patterns to those that are currently running or contextually relevant and physically feasible.
     * @param listPatterns the list of all potential maneuver patterns to evaluate
     * @return a filtered list of patterns that pass both context and ability checks, or are already running
     * @throws ParameterException if a perception or parameter lookup fails during checks
     */
    public static ArrayList<ManeuverPattern> getAllRelevantPatterns(final ArrayList<ManeuverPattern> listPatterns)
            throws ParameterException
    {
        ArrayList<ManeuverPattern> listRelevantPatterns = new ArrayList<>();

        for (ManeuverPattern p : listPatterns)
        {
            if (p.isRunning())
            {
                listRelevantPatterns.add(p);
            }
            else if (p.checkContext() && p.checkAbility())
            {
                listRelevantPatterns.add(p);
            }
        }
        return listRelevantPatterns;
    }

}
