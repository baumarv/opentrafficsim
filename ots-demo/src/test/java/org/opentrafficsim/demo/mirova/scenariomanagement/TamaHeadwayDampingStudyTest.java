package org.opentrafficsim.demo.mirova.scenariomanagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.TamaHeadwayDampingStudy;

/**
 * The grid has to be a grid, and the days have to be days something happens on.
 * <p>
 * Both properties can be lost silently. A grid that has grown a hole still runs and still plots; the hole
 * only shows up as a missing point in a figure nobody cross-checks against the cell list. And a day list
 * that drifts towards days the field does not break down on produces empty discharge and jam columns -
 * which is exactly what the previous campaign delivered, where of three days only one could produce a
 * number at all.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class TamaHeadwayDampingStudyTest
{
    /** The label of the one cell outside the grid. */
    private static final String REFERENCE = TamaHeadwayDampingStudy.BASE_LABEL;

    /** Reads "T=1.00 d=0.40" back out of a cell's description. */
    private static final Pattern VALUES = Pattern.compile("T=([0-9.]+) d=([0-9.]+)");

    /**
     * Every headway is tried at every damping factor: the grid is complete.
     * <p>
     * Checked as a product rather than as a count, because a grid can hold the right number of cells and
     * still miss a corner if one is duplicated.
     * </p>
     */
    @Test
    public void theGridIsComplete()
    {
        Set<String> headways = new LinkedHashSet<>();
        Set<String> dampings = new LinkedHashSet<>();
        Set<String> pairs = new LinkedHashSet<>();
        TamaHeadwayDampingStudy.cells().forEach((label, description) ->
        {
            if (REFERENCE.equals(label))
            {
                return;
            }
            Matcher matcher = VALUES.matcher(description);
            assertTrue(matcher.find(), "cell '" + label + "' does not report its values: " + description);
            headways.add(matcher.group(1));
            dampings.add(matcher.group(2));
            pairs.add(matcher.group(1) + "/" + matcher.group(2));
        });
        assertTrue(headways.size() >= 3, "fewer than three headways remain: " + headways);
        assertTrue(dampings.size() >= 3, "fewer than three damping factors remain: " + dampings);
        assertEquals(headways.size() * dampings.size(), pairs.size(),
                "the grid has a hole or a duplicate: " + headways.size() + " headways and " + dampings.size()
                        + " damping factors should give " + (headways.size() * dampings.size())
                        + " combinations, but " + pairs.size() + " distinct pairs are registered: " + pairs);
    }

    /**
     * The grid brackets the range the screening pointed at, rather than sitting on one side of it.
     * <p>
     * The values asked for were 0.9 to 1.2 for the headway and 0.4 to 0.6 for the damping. A grid whose
     * extremes fall inside that range cannot show an optimum at the edge as an edge.
     * </p>
     */
    @Test
    public void theGridSpansTheRangeItWasAskedFor()
    {
        double minT = Double.MAX_VALUE;
        double maxT = -Double.MAX_VALUE;
        double minD = Double.MAX_VALUE;
        double maxD = -Double.MAX_VALUE;
        for (Map.Entry<String, String> cell : TamaHeadwayDampingStudy.cells().entrySet())
        {
            if (REFERENCE.equals(cell.getKey()))
            {
                continue;
            }
            Matcher matcher = VALUES.matcher(cell.getValue());
            assertTrue(matcher.find());
            minT = Math.min(minT, Double.parseDouble(matcher.group(1)));
            maxT = Math.max(maxT, Double.parseDouble(matcher.group(1)));
            minD = Math.min(minD, Double.parseDouble(matcher.group(2)));
            maxD = Math.max(maxD, Double.parseDouble(matcher.group(2)));
        }
        assertEquals(TamaHeadwayDampingStudy.T_LOW, minT, 1e-9, "the grid no longer reaches the low headway");
        assertEquals(TamaHeadwayDampingStudy.T_HIGH, maxT, 1e-9, "the grid no longer reaches the high headway");
        assertEquals(TamaHeadwayDampingStudy.DAMPING_LOW, minD, 1e-9, "the grid no longer reaches the low damping");
        assertEquals(TamaHeadwayDampingStudy.DAMPING_HIGH, maxD, 1e-9, "the grid no longer reaches the high damping");
    }

    /**
     * The reference cell keeps the previous campaign's own values and is not part of the grid.
     * <p>
     * Its damping of 1.00 is no damping at all, which is why it sits outside: the grid is about how much
     * damping to have, and a cell with none is the thing being compared against, not a level of it.
     * </p>
     */
    @Test
    public void theReferenceKeepsThePreviousCampaignsValues()
    {
        assertEquals("T=1.00 d=1.00", TamaHeadwayDampingStudy.cells().get(REFERENCE),
                "the reference cell no longer carries the previous campaign's headway and damping, so the "
                        + "grid has nothing to be read against");
    }

    /**
     * The days are named and there are more of them than the three the previous campaign used.
     * <p>
     * The count is the point: of those three, one had no empirical breakdown and one never broke down in
     * simulation, so most cells' discharge and jam duration rested on a single day.
     * </p>
     */
    @Test
    public void thereAreMoreDaysThanBefore()
    {
        assertTrue(TamaHeadwayDampingStudy.DAYS.size() >= 6,
                "only " + TamaHeadwayDampingStudy.DAYS.size() + " days remain; the previous campaign's three "
                        + "left most cells resting on one day that could produce a number");
        assertEquals(TamaHeadwayDampingStudy.DAYS.size(), new LinkedHashSet<>(TamaHeadwayDampingStudy.DAYS).size(),
                "a day is listed twice: " + TamaHeadwayDampingStudy.DAYS);
    }

    /** The two days the previous campaign was misled by are not in the list. */
    @Test
    public void theTwoUninformativeDaysAreGone()
    {
        assertTrue(!TamaHeadwayDampingStudy.DAYS.contains("2025-09-22"),
                "2025-09-22 is back in the list; the field shows no breakdown there at all, so it can "
                        + "produce neither a discharge nor a jam duration");
        assertTrue(!TamaHeadwayDampingStudy.DAYS.contains("2025-10-27"),
                "2025-10-27 is back in the list; the simulation never broke down there");
        for (String excluded : new String[] {"2025-10-14", "2025-09-26"})
        {
            assertTrue(!TamaHeadwayDampingStudy.DAYS.contains(excluded),
                    excluded + " is held out of the evaluation and must not be run");
        }
    }
}
