package org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts where the merge reference speed comes from, and how far away that is.
 * <p>
 * {@code MERGE_REFERENCE_RANGE_LIMITED} (BC-6) claims that with the switch on, no merge reference is taken from beyond
 * the range the ego can see. That is a claim about every path through
 * {@code InfrastructureContext.getDownstreamAdjacentLane}, not only the projection it bounds, and the way to hold it to
 * the claim is to measure, against the ego's own look-ahead, how far away the traffic a reference was taken from
 * actually was -- which is the far end of the window scanned on the lane that was found, not the lane's start.
 * </p>
 * <p>
 * Two things are counted, at the two points where they are known:
 * </p>
 * <ul>
 * <li><b>Lanes found</b>, in {@code computeDownstreamAdjacentLane}, with the distance from the ego to the start of the
 * lane and whether the post-projection fallback supplied it. A find is not yet a reference: the cascade reaches step 3
 * only while the target lane is not physically alongside.</li>
 * <li><b>References taken</b>, in {@code MandatoryLaneChangePattern.getMergeReferenceSpeed} step 3, when the scan over
 * that lane produced a usable speed. This is the number the claim is about.</li>
 * </ul>
 * <p>
 * <b>Off unless asked for.</b> Enabled by {@code -Dmirova.mergeRefDiag=true}; with it unset every method here is a
 * predictable branch on a static final boolean and no counter is touched. The counters are process-wide, which is what
 * a diagnostic over a whole run wants.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public final class MergeReferenceDiagnostics
{

    /** Whether the diagnostics collect anything. */
    public static final boolean ENABLED = Boolean.getBoolean("mirova.mergeRefDiag");

    /** Merge lanes found by the path projection. */
    private static final AtomicLong FOUND = new AtomicLong();

    /** Merge lanes found whose start lies beyond the ego's look-ahead. */
    private static final AtomicLong FOUND_BEYOND = new AtomicLong();

    /** Merge lanes supplied by the fallback that steps past the projection. */
    private static final AtomicLong FOUND_BY_FALLBACK = new AtomicLong();

    /** Merge lanes the fallback would have supplied and the range bound refused. */
    private static final AtomicLong FALLBACK_REFUSED = new AtomicLong();

    /** Greatest distance in millimetres at which a merge lane was found. */
    private static final AtomicLong FOUND_MAX_MM = new AtomicLong();

    /** Merge references taken from a found lane in step 3 of the cascade. */
    private static final AtomicLong TAKEN = new AtomicLong();

    /** Merge references taken from a lane whose start lies beyond the ego's look-ahead. */
    private static final AtomicLong TAKEN_BEYOND = new AtomicLong();

    /** Greatest distance in millimetres reached by the window a merge reference was taken from. */
    private static final AtomicLong TAKEN_MAX_MM = new AtomicLong();

    static
    {
        if (ENABLED)
        {
            Runtime.getRuntime().addShutdownHook(new Thread(MergeReferenceDiagnostics::report));
        }
    }

    /** Utility class. */
    private MergeReferenceDiagnostics()
    {
        //
    }

    /**
     * Records a merge lane found by the projection.
     * @param distanceSi double; distance from the ego to the start of that lane [m]
     * @param lookaheadSi double; the ego's own look-ahead [m]
     * @param fromFallback boolean; whether the post-projection fallback supplied it
     */
    public static void laneFound(final double distanceSi, final double lookaheadSi, final boolean fromFallback)
    {
        FOUND.incrementAndGet();
        if (fromFallback)
        {
            FOUND_BY_FALLBACK.incrementAndGet();
        }
        if (distanceSi > lookaheadSi)
        {
            FOUND_BEYOND.incrementAndGet();
        }
        max(FOUND_MAX_MM, distanceSi);
    }

    /** Records a merge lane the range bound kept the fallback from returning. */
    public static void fallbackRefused()
    {
        FALLBACK_REFUSED.incrementAndGet();
    }

    /**
     * Records a merge reference actually taken from a found lane.
     * <p>
     * What counts as beyond the range is the far end of the window scanned on that lane, not the lane's start: the
     * reference is the mean speed of the vehicles in that window, so the furthest of them is where it originates.
     * </p>
     * @param distanceSi double; distance from the ego to the start of that lane [m]
     * @param windowEndSi double; distance from the ego to the far end of the scanned window [m]
     * @param lookaheadSi double; the ego's own look-ahead [m]
     */
    public static void referenceTaken(final double distanceSi, final double windowEndSi, final double lookaheadSi)
    {
        TAKEN.incrementAndGet();
        if (windowEndSi > lookaheadSi)
        {
            TAKEN_BEYOND.incrementAndGet();
        }
        max(TAKEN_MAX_MM, windowEndSi);
    }

    /**
     * Raises a counter to a new maximum.
     * @param counter AtomicLong; the counter holding millimetres
     * @param metres double; the candidate value
     */
    private static void max(final AtomicLong counter, final double metres)
    {
        long mm = Math.round(metres * 1000.0);
        long seen = counter.get();
        while (mm > seen && !counter.compareAndSet(seen, mm))
        {
            seen = counter.get();
        }
    }

    /** Prints the collected counts. */
    public static void report()
    {
        long found = FOUND.get();
        if (found == 0)
        {
            // Still say how often the range bound refused one. Without that number, "none found" cannot be told
            // apart from "this code never ran", and the two mean opposite things about the switch.
            System.out.printf("[MERGEREF] no merge lane was found in this run; the range bound refused %d%n",
                    FALLBACK_REFUSED.get());
            return;
        }
        System.out.printf("[MERGEREF] lanes found %d, of them beyond the ego's look-ahead %d (%.1f %%), "
                + "furthest %.1f m%n", found, FOUND_BEYOND.get(), 100.0 * FOUND_BEYOND.get() / found,
                FOUND_MAX_MM.get() / 1000.0);
        System.out.printf("[MERGEREF]   supplied by the post-projection fallback %d, refused by the range bound %d%n",
                FOUND_BY_FALLBACK.get(), FALLBACK_REFUSED.get());
        long taken = TAKEN.get();
        if (taken == 0)
        {
            System.out.println("[MERGEREF] no merge reference was taken from a found lane");
            return;
        }
        System.out.printf("[MERGEREF] references taken %d, of them reaching beyond the ego's look-ahead %d "
                + "(%.1f %%), furthest sampled point %.1f m%n", taken, TAKEN_BEYOND.get(),
                100.0 * TAKEN_BEYOND.get() / taken, TAKEN_MAX_MM.get() / 1000.0);
    }
}
