package org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts how relaxations end, and how long they lived.
 * <p>
 * A relaxation can end three ways, and only one of them is visible from trajectories: the abort in
 * {@code MirovaCarFollowingUtil} when the leader brakes or crawls, the natural decay collected in
 * {@code EgoContext.updateFromPerception}, and - invisibly - the leader simply no longer being this vehicle's leader,
 * after which the state is never consulted again and later disappears in that same housekeeping.
 * </p>
 * <p>
 * The third is measured by difference: relaxations created, less those ended by one of the other two, is the number
 * whose leader stopped being the leader.
 * </p>
 * <p>
 * An estimate reconstructed from trajectories can only see the first, and it sees it on the 199 m of link L4a alone,
 * which is the only link the sampler records. Every vehicle leaves that section within 15 to 50 s depending on the
 * traffic state, so the longer half of a relaxation's life is never observed. This class measures the three
 * mechanisms directly instead.
 * </p>
 * <p>
 * <b>Off unless asked for.</b> Enabled by {@code -Dmirova.relaxDiag=true}; with it unset every method here is a
 * predictable branch on a static final boolean and the counters are never touched, so a production run is unaffected.
 * The counters are process-wide rather than per vehicle, which is what a diagnostic over a whole run wants and is the
 * reason this is not part of {@code EgoContext}.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See OpenTrafficSim License.
 * </p>
 * @author Marvin Baumann
 */
public final class RelaxationDiagnostics
{

    /** Whether the diagnostics collect anything. */
    public static final boolean ENABLED = Boolean.getBoolean("mirova.relaxDiag");

    /** Relaxations ended because the leader braked past the abort threshold. */
    private static final AtomicLong ABORT_DECEL = new AtomicLong();

    /** Relaxations ended because the leader fell below the minimum speed. */
    private static final AtomicLong ABORT_SPEED = new AtomicLong();

    /** Relaxations that decayed to nothing and were collected by the housekeeping. */
    private static final AtomicLong EXPIRED = new AtomicLong();

    /** Summed lifetime in seconds, per ending. */
    private static final AtomicLong LIFE_DECEL = new AtomicLong();

    /** Summed lifetime in milliseconds of relaxations ended by the speed condition. */
    private static final AtomicLong LIFE_SPEED = new AtomicLong();

    /** Summed lifetime in milliseconds of relaxations that decayed naturally. */
    private static final AtomicLong LIFE_EXPIRED = new AtomicLong();

    /** Relaxations created. */
    private static final AtomicLong CREATED = new AtomicLong();

    /** Car-following calls made against a leader that has an active relaxation. */
    private static final AtomicLong CALLS_RELAXED = new AtomicLong();

    /** Car-following calls made against a leader that has none. */
    private static final AtomicLong CALLS_PLAIN = new AtomicLong();

    static
    {
        if (ENABLED)
        {
            Runtime.getRuntime().addShutdownHook(new Thread(RelaxationDiagnostics::report));
        }
    }

    /** Utility class. */
    private RelaxationDiagnostics()
    {
        //
    }

    /**
     * Records a relaxation abandoned because the leader braked past the threshold.
     * @param lifetimeSeconds double; how long the relaxation had been running
     */
    public static void abortedByDeceleration(final double lifetimeSeconds)
    {
        ABORT_DECEL.incrementAndGet();
        LIFE_DECEL.addAndGet(Math.round(lifetimeSeconds * 1000.0));
    }

    /**
     * Records a relaxation abandoned because the leader fell below the minimum speed.
     * @param lifetimeSeconds double; how long the relaxation had been running
     */
    public static void abortedBySpeed(final double lifetimeSeconds)
    {
        ABORT_SPEED.incrementAndGet();
        LIFE_SPEED.addAndGet(Math.round(lifetimeSeconds * 1000.0));
    }

    /**
     * Records a relaxation that decayed to nothing on its own.
     * @param lifetimeSeconds double; how long the relaxation had been running
     */
    public static void expired(final double lifetimeSeconds)
    {
        EXPIRED.incrementAndGet();
        LIFE_EXPIRED.addAndGet(Math.round(lifetimeSeconds * 1000.0));
    }

    /** Records that a relaxation was created. */
    public static void created()
    {
        CREATED.incrementAndGet();
    }

    /**
     * Records a car-following call and whether the leader it addressed carried a relaxation.
     * @param relaxed boolean; true when a relaxation was active for that leader
     */
    public static void carFollowingCall(final boolean relaxed)
    {
        (relaxed ? CALLS_RELAXED : CALLS_PLAIN).incrementAndGet();
    }

    /** Prints the collected counts. */
    public static void report()
    {
        long dec = ABORT_DECEL.get();
        long spd = ABORT_SPEED.get();
        long exp = EXPIRED.get();
        long total = dec + spd + exp;
        if (total == 0)
        {
            System.out.println("[RELAX] no relaxation endings recorded");
            return;
        }
        System.out.println("[RELAX] endings: " + total);
        System.out.printf("[RELAX]   leader braked past threshold %8d  %5.1f %%  mean life %6.2f s%n",
                dec, 100.0 * dec / total, dec == 0 ? 0.0 : LIFE_DECEL.get() / 1000.0 / dec);
        System.out.printf("[RELAX]   leader below minimum speed   %8d  %5.1f %%  mean life %6.2f s%n",
                spd, 100.0 * spd / total, spd == 0 ? 0.0 : LIFE_SPEED.get() / 1000.0 / spd);
        System.out.printf("[RELAX]   decayed to nothing           %8d  %5.1f %%  mean life %6.2f s%n",
                exp, 100.0 * exp / total, exp == 0 ? 0.0 : LIFE_EXPIRED.get() / 1000.0 / exp);
        long created = CREATED.get();
        if (created > 0)
        {
            long unaccounted = created - total;
            System.out.printf("[RELAX] created %d, ended by one of the above %d, unaccounted %d (%.1f %%)%n",
                    created, total, unaccounted, 100.0 * unaccounted / created);
            System.out.println("[RELAX]   unaccounted = the leader stopped being the leader, so the state was never"
                    + " consulted again - the ending no trajectory can show");
        }
        long relaxed = CALLS_RELAXED.get();
        long plain = CALLS_PLAIN.get();
        if (relaxed + plain > 0)
        {
            System.out.printf("[RELAX] car-following calls: %d, of which relaxed %d (%.1f %%)%n",
                    relaxed + plain, relaxed, 100.0 * relaxed / (relaxed + plain));
        }
    }
}
