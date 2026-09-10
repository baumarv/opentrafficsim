package org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Counts, per merging vehicle, which condition refuses the lane change.
 * <p>
 * A ramp vehicle that never merges runs out of acceleration lane and stops. Reconstructing the reason from
 * trajectories narrows it to the rear side - the follower on the target lane approaches such a vehicle some
 * 14 km/h faster than it approaches one that merges - but cannot settle it, because two of the five conditions
 * of {@code checkIfLaneChangeIsPossible} are car-following evaluations and {@code mayExecuteLaneChange} leaves
 * no trace in the trajectory at all. This records the model's own answers instead.
 * </p>
 * <p>
 * One row per vehicle rather than one per evaluation: the question is which condition held a vehicle back over
 * its whole run on the acceleration lane, and a per-tick log of every merging vehicle would be larger than the
 * trajectory file it would have to be joined with. Whether a vehicle later stranded is not known while it is
 * driving, so that is left to the analysis, which joins these rows to the trajectories by identifier.
 * </p>
 * <p>
 * The counters are static and are never reset, so they describe one replication and assume one replication per
 * process, which is how the campaigns and the local harness run. Nothing here is synchronised either. Both hold
 * as long as that stays true; running replications concurrently in one JVM would mix the tallies.
 * </p>
 * <p>
 * Off unless {@code -Dmirova.gateDiag=<file>} names an output file.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See OpenTrafficSim License.
 * </p>
 * @author Marvin Baumann
 */
public final class MergeGateDiagnostics
{
    /** Output file, or {@code null} when the recording is off. */
    private static final Path TARGET = resolveTarget();

    /** Whether anything is recorded at all. */
    public static final boolean ENABLED = TARGET != null;

    /** Per-vehicle counters, in the order the vehicles were first seen. */
    private static final Map<String, long[]> ROWS = new LinkedHashMap<>();

    /** Per vehicle, where the gap conditions were last evaluated, as link and lane. */
    private static final Map<String, String> WHERE = new LinkedHashMap<>();

    /**
     * Records where a vehicle is while its gap conditions are evaluated.
     * <p>
     * The rows used to be restricted to vehicles standing on an acceleration lane, which is the merge. The same
     * conditions decide the exit, where the ego stands on a through lane and wants the one beside it, and nothing
     * was recorded for it. Kept as the last place seen rather than the first: a vehicle evaluates the gap over a
     * stretch of road and it is the end of that stretch the outcome is decided at.
     * </p>
     * @param gtuId String; the vehicle
     * @param location String; link and lane, separated by a slash
     */
    public static void where(final String gtuId, final String location)
    {
        if (ENABLED)
        {
            WHERE.put(gtuId, location);
        }
    }

    /** Number of counters held per vehicle. */
    private static final int FIELDS = 10;

    /**
     * Per vehicle, the least demanding ego deceleration seen in a tick where nothing else refused, in m/s^2.
     * <p>
     * A threshold below this value would have let that vehicle through at least once, so the distribution over the
     * vehicles that never merged answers what threshold the ego side would need. Absent when no such tick occurred.
     * </p>
     */
    private static final Map<String, Double> BEST_EGO_DECEL = new LinkedHashMap<>();

    /**
     * Per vehicle, the same for the deceleration demanded of the follower, so the two sides can be compared on the
     * same footing rather than the ego side being read on its own.
     */
    private static final Map<String, Double> BEST_FOLLOWER_DECEL = new LinkedHashMap<>();

    /** Index of the number of evaluations. */
    private static final int N = 0;

    /** Index of the count of evaluations refused because the lane change was not legal. */
    private static final int NOT_LEGAL = 1;

    /** Index of the count refused by the ego's own deceleration. */
    private static final int EGO_DECEL = 2;

    /** Index of the count refused by the deceleration demanded of the follower. */
    private static final int FOLLOWER_DECEL = 3;

    /** Index of the count refused by the gap behind. */
    private static final int REAR_GAP = 4;

    /** Index of the count refused by the gap ahead. */
    private static final int FRONT_GAP = 5;

    /** Index of the count where all five conditions held. */
    private static final int GAP_OK = 6;

    /** Index of the count where the ego was in a fit state to execute. */
    private static final int READY = 7;

    /** Index of the count where the gap and the readiness held together, i.e. the change was taken. */
    private static final int BOTH = 8;

    /** Index of the furthest position on the acceleration lane the vehicle reached, in decimetres. */
    private static final int MAX_X_DM = 9;

    /** Utility class. */
    private MergeGateDiagnostics()
    {
        // utility
    }

    /**
     * Reads the output path from the system property.
     * @return Path; the file to write, or {@code null} when the property is absent
     */
    private static Path resolveTarget()
    {
        String value = System.getProperty("mirova.gateDiag");
        return value == null || value.trim().isEmpty() ? null : Paths.get(value.trim());
    }

    /**
     * Records one evaluation of the gap conditions.
     * @param gtuId String; the merging vehicle
     * @param xDm int; its position on the acceleration lane, in decimetres
     * @param legal boolean; whether the lane change was legal
     * @param egoDecelOk boolean; whether the ego's own deceleration stayed within its threshold
     * @param followerDecelOk boolean; whether the follower's deceleration stayed within its threshold
     * @param rearOk boolean; whether the gap behind was sufficient
     * @param frontOk boolean; whether the gap ahead was sufficient
     */
    public static void gapEvaluated(final String gtuId, final int xDm, final boolean legal,
            final boolean egoDecelOk, final boolean followerDecelOk, final boolean rearOk, final boolean frontOk)
    {
        gapEvaluated(gtuId, xDm, legal, egoDecelOk, followerDecelOk, rearOk, frontOk, Double.NaN, Double.NaN);
    }

    /**
     * Records one evaluation together with the decelerations the manoeuvre would demand.
     * @param gtuId String; the merging vehicle
     * @param xDm int; its position on the acceleration lane, in decimetres
     * @param legal boolean; whether the lane change was legal
     * @param egoDecelOk boolean; whether the ego's own deceleration stayed within its threshold
     * @param followerDecelOk boolean; whether the follower's deceleration stayed within its threshold
     * @param rearOk boolean; whether the gap behind was sufficient
     * @param frontOk boolean; whether the gap ahead was sufficient
     * @param egoDecelSi double; the deceleration the ego would need, in m/s^2
     * @param followerDecelSi double; the deceleration the follower would need, in m/s^2
     */
    public static void gapEvaluated(final String gtuId, final int xDm, final boolean legal,
            final boolean egoDecelOk, final boolean followerDecelOk, final boolean rearOk, final boolean frontOk,
            final double egoDecelSi, final double followerDecelSi)
    {
        if (!ENABLED)
        {
            return;
        }
        long[] c = ROWS.computeIfAbsent(gtuId, k -> new long[FIELDS]);
        c[N]++;
        if (!legal)
        {
            c[NOT_LEGAL]++;
        }
        if (!egoDecelOk)
        {
            c[EGO_DECEL]++;
        }
        if (!followerDecelOk)
        {
            c[FOLLOWER_DECEL]++;
        }
        if (!rearOk)
        {
            c[REAR_GAP]++;
        }
        if (!frontOk)
        {
            c[FRONT_GAP]++;
        }
        if (legal && egoDecelOk && followerDecelOk && rearOk && frontOk)
        {
            c[GAP_OK]++;
        }
        c[MAX_X_DM] = Math.max(c[MAX_X_DM], xDm);

        // The value is only informative where the ego side was the sole obstacle: elsewhere a milder threshold
        // would have changed nothing, because another condition was refusing anyway.
        if (legal && rearOk && frontOk && followerDecelOk && !Double.isNaN(egoDecelSi))
        {
            BEST_EGO_DECEL.merge(gtuId, egoDecelSi, Math::max);
        }
        if (legal && rearOk && frontOk && egoDecelOk && !Double.isNaN(followerDecelSi))
        {
            BEST_FOLLOWER_DECEL.merge(gtuId, followerDecelSi, Math::max);
        }
    }

    /** Ticks spent in the parallel state, in total and with the overlap that started it already gone. */
    private static final long[] PARALLEL = new long[4];

    /** Which of the three conditions of the overtake branch refuse it, and how often it is taken. */
    private static final long[] BRANCH = new long[5];

    /**
     * Records which condition keeps the parallel state on its yield branch rather than its overtake branch.
     * @param enoughTime boolean; whether enough lane time remained
     * @param enoughAccel boolean; whether the car-following acceleration allowed pulling ahead
     * @param behind boolean; whether the blocker was not already ahead
     */
    public static void parallelBranch(final boolean enoughTime, final boolean enoughAccel, final boolean behind)
    {
        if (!ENABLED)
        {
            return;
        }
        BRANCH[0]++;
        if (!enoughTime)
        {
            BRANCH[1]++;
        }
        if (!enoughAccel)
        {
            BRANCH[2]++;
        }
        if (!behind)
        {
            BRANCH[3]++;
        }
        if (enoughTime && enoughAccel && behind)
        {
            BRANCH[4]++;
        }
    }

    /**
     * Records one tick of the parallel-conflict state against both criteria that describe it.
     * <p>
     * The state is entered on a physical overlap but left only once the blocker is a full desired headway clear, so
     * the two do not describe the same situation. Counting the ticks in which the overlap is gone while the wide
     * criterion still holds measures what that asymmetry costs, which no trajectory can show: the blocker's identity
     * is not written to the sampler.
     * </p>
     * @param overlapping boolean; whether the entry criterion still holds
     * @param wideBlock boolean; whether the exit criterion still holds
     * @param braking boolean; whether the commanded acceleration is a deceleration
     */
    public static void parallelTick(final boolean overlapping, final boolean wideBlock, final boolean braking)
    {
        if (!ENABLED)
        {
            return;
        }
        PARALLEL[0]++;
        if (overlapping)
        {
            PARALLEL[1]++;
        }
        if (!overlapping && wideBlock)
        {
            PARALLEL[2]++;
            if (braking)
            {
                PARALLEL[3]++;
            }
        }
    }

    /** Car-following branch counts: calls, IDM+ accepted, held at B_CRIT, kinematic, and the sub-cases. */
    private static final long[] CF = new long[8];

    /** Sum of the deceleration IDM+ asked for and of what the kinematics required, on the B_CRIT branch. */
    private static final double[] CF_SUM = new double[2];

    /** Histogram of the kinematic requirement on the B_CRIT branch, in whole m/s^2 from 0 to -4. */
    private static final long[] CF_DKIN = new long[5];

    /**
     * Records which branch of the car-following model answered, and with what the branch had to work.
     * <p>
     * The bounds only apply once IDM+ asks for more than the critical brake, so the question is how often that
     * happens at all and what the kinematics say when it does. A kinematic requirement of exactly zero means the
     * leader is not closing, where the bound is the only thing keeping a small gap from being ignored.
     * </p>
     * @param aIdmSi double; what IDM+ asked for, in m/s^2
     * @param acceptedIdm boolean; whether that answer was returned unchanged
     * @param heldAtCrit boolean; whether the critical brake was returned instead
     * @param dKinSi double; the kinematic requirement, in m/s^2; may be negative infinity
     * @param closing boolean; whether the ego is approaching the leader at all
     */
    public static void cfBranch(final double aIdmSi, final boolean acceptedIdm, final boolean heldAtCrit,
            final double dKinSi, final boolean closing)
    {
        if (!ENABLED)
        {
            return;
        }
        CF[0]++;
        if (acceptedIdm)
        {
            CF[1]++;
            return;
        }
        if (heldAtCrit)
        {
            CF[2]++;
            CF_SUM[0] += aIdmSi;
            CF_SUM[1] += dKinSi;
            if (!closing)
            {
                CF[4]++;
            }
            int bucket = (int) Math.min(4.0, Math.max(0.0, Math.floor(-dKinSi)));
            CF_DKIN[bucket]++;
        }
        else
        {
            CF[3]++;
            if (Double.isInfinite(dKinSi))
            {
                CF[5]++;
            }
            if (dKinSi < -6.0)
            {
                CF[6]++;
            }
        }
    }

    /** Per vehicle, what the parallel state saw: see the index constants below. */
    private static final Map<String, double[]> PAR_ROWS = new LinkedHashMap<>();

    /** Number of values held per vehicle for the parallel state. */
    private static final int PAR_FIELDS = 8;

    /** Ticks with the blocker ahead of the ego. */
    private static final int P_AHEAD = 0;

    /** Ticks with the blocker behind the ego, where yielding brakes towards it. */
    private static final int P_BEHIND = 1;

    /** Ticks on the overtake branch. */
    private static final int P_OVERTAKE = 2;

    /** Ticks with the blocker behind on which the yield branch was taken anyway. */
    private static final int P_YIELD_TO_FOLLOWER = 3;

    /** Smallest remaining lane seen on the overtake branch, in metres. */
    private static final int P_MIN_REST = 4;

    /** Speed difference to the blocker on the overtake branch, summed, in m/s. */
    private static final int P_DV_SUM = 5;

    /** Most adverse speed difference seen on the overtake branch, in m/s. */
    private static final int P_DV_WORST = 6;

    /** Smallest remaining lane seen while the state was active at all, in metres. */
    private static final int P_MIN_REST_ANY = 7;

    /**
     * Records one tick of the parallel state from the point of view of the blocker.
     * @param gtuId String; the merging vehicle
     * @param ahead boolean; whether the blocker is ahead of the ego
     * @param overtaking boolean; whether the overtake branch was taken this tick
     * @param dvSi double; the blocker's speed minus the ego's, in m/s; positive means it is pulling away
     * @param restSi double; the remaining lane, in metres
     */
    public static void parallelBlocker(final String gtuId, final boolean ahead, final boolean overtaking,
            final double dvSi, final double restSi)
    {
        if (!ENABLED)
        {
            return;
        }
        double[] r = PAR_ROWS.computeIfAbsent(gtuId, k ->
        {
            double[] fresh = new double[PAR_FIELDS];
            fresh[P_MIN_REST] = Double.NaN;
            fresh[P_DV_WORST] = Double.NaN;
            fresh[P_MIN_REST_ANY] = Double.MAX_VALUE;
            return fresh;
        });
        r[ahead ? P_AHEAD : P_BEHIND]++;
        r[P_MIN_REST_ANY] = Math.min(r[P_MIN_REST_ANY], restSi);
        if (overtaking)
        {
            r[P_OVERTAKE]++;
            r[P_MIN_REST] = Double.isNaN(r[P_MIN_REST]) ? restSi : Math.min(r[P_MIN_REST], restSi);
            r[P_DV_SUM] += dvSi;
            r[P_DV_WORST] = Double.isNaN(r[P_DV_WORST]) ? dvSi : Math.max(r[P_DV_WORST], dvSi);
        }
        else if (!ahead)
        {
            r[P_YIELD_TO_FOLLOWER]++;
        }
    }

    /** Per vehicle: readiness evaluations, refusals, and the reference speed the refusals were judged against. */
    private static final Map<String, double[]> READY_ROWS = new LinkedHashMap<>();

    /** Number of values held per vehicle for the readiness test. */
    private static final int READY_FIELDS = 6;

    /**
     * Records one evaluation of the readiness test and which of its grounds admitted the change.
     * <p>
     * The test asks whether the ego is up to the speed of the traffic it is joining, which is a merge question. At
     * an exit the target is a deceleration lane, and when it is empty the reference falls back to the speed limit,
     * so the ego can be held until it matches a speed nothing is travelling at. This records the reference the
     * decision was made against so that reading can be confirmed or dropped.
     * </p>
     * @param gtuId String; the vehicle
     * @param ready boolean; whether the change was permitted
     * @param synchronised boolean; whether it was permitted because the speed was matched
     * @param atLaneEnd boolean; whether it was permitted because the lane is about to end
     * @param obstructedOrCongested boolean; whether it was permitted because the target lane is slow or blocked
     * @param referenceSi double; the reference speed of the target lane, in m/s
     * @param egoSi double; the ego's own speed, in m/s
     */
    public static void readinessDetail(final String gtuId, final boolean ready, final boolean synchronised,
            final boolean atLaneEnd, final boolean obstructedOrCongested, final double referenceSi,
            final double egoSi)
    {
        if (!ENABLED)
        {
            return;
        }
        double[] r = READY_ROWS.computeIfAbsent(gtuId, k -> new double[READY_FIELDS]);
        r[0]++;
        if (!ready)
        {
            r[1]++;
            r[2] += referenceSi;
            r[3] += egoSi;
        }
        else if (synchronised)
        {
            r[4]++;
        }
        else if (atLaneEnd || obstructedOrCongested)
        {
            r[5]++;
        }
    }

    /**
     * Records the readiness test, which is asked on every path into the execution.
     * @param gtuId String; the merging vehicle
     * @param gapOpen boolean; whether the gap conditions held
     * @param ready boolean; whether the ego was in a fit state to execute
     */
    public static void readinessEvaluated(final String gtuId, final boolean gapOpen, final boolean ready)
    {
        if (!ENABLED)
        {
            return;
        }
        long[] c = ROWS.computeIfAbsent(gtuId, k -> new long[FIELDS]);
        if (ready)
        {
            c[READY]++;
        }
        if (gapOpen && ready)
        {
            c[BOTH]++;
        }
    }

    /** Writes the per-vehicle view of the readiness test, beside the gate file. */
    private static void writeReadiness() throws IOException
    {
        Path target = Paths.get(TARGET.toString().replace(".csv", "_ready.csv"));
        try (BufferedWriter w = Files.newBufferedWriter(target, StandardCharsets.UTF_8))
        {
            w.write("gtuId,n,refused,refRefSum,refEgoSum,viaSpeed,viaLaneEndOrBlocked,where");
            w.newLine();
            for (Map.Entry<String, double[]> e : READY_ROWS.entrySet())
            {
                double[] r = e.getValue();
                StringBuilder sb = new StringBuilder(e.getKey());
                for (double v : r)
                {
                    sb.append(',').append(String.format(Locale.ROOT, "%.3f", v));
                }
                sb.append(',').append(WHERE.getOrDefault(e.getKey(), ""));
                w.write(sb.toString());
                w.newLine();
            }
        }
        System.out.println("[GATE] wrote " + READY_ROWS.size() + " readiness rows to " + target.toAbsolutePath());
    }

    /** Writes the per-vehicle view of the parallel state, beside the gate file. */
    private static void writeParallel() throws IOException
    {
        Path target = Paths.get(TARGET.toString().replace(".csv", "_parallel.csv"));
        try (BufferedWriter w = Files.newBufferedWriter(target, StandardCharsets.UTF_8))
        {
            w.write("gtuId,ahead,behind,overtake,yieldToFollower,minRestOvertake,dvSum,dvWorst,minRest");
            w.newLine();
            for (Map.Entry<String, double[]> e : PAR_ROWS.entrySet())
            {
                double[] r = e.getValue();
                StringBuilder sb = new StringBuilder(e.getKey());
                for (double v : r)
                {
                    sb.append(',').append(Double.isNaN(v) || v == Double.MAX_VALUE ? ""
                            : String.format(Locale.ROOT, "%.3f", v));
                }
                w.write(sb.toString());
                w.newLine();
            }
        }
        System.out.println("[GATE] wrote " + PAR_ROWS.size() + " parallel rows to " + target.toAbsolutePath());
    }

    /** Writes the collected rows. Called explicitly at the end of a run, because a shutdown hook never fires here. */
    public static void write()
    {
        if (!ENABLED)
        {
            return;
        }
        try
        {
            if (TARGET.getParent() != null)
            {
                Files.createDirectories(TARGET.getParent());
            }
            try (BufferedWriter w = Files.newBufferedWriter(TARGET, StandardCharsets.UTF_8))
            {
                w.write("gtuId,n,notLegal,egoDecel,followerDecel,rearGap,frontGap,gapOk,ready,both,maxXdm,"
                        + "bestEgoDecel,bestFollowerDecel,where");
                w.newLine();
                for (Map.Entry<String, long[]> e : ROWS.entrySet())
                {
                    long[] c = e.getValue();
                    StringBuilder sb = new StringBuilder(e.getKey());
                    for (long v : c)
                    {
                        sb.append(',').append(v);
                    }
                    Double be = BEST_EGO_DECEL.get(e.getKey());
                    Double bf = BEST_FOLLOWER_DECEL.get(e.getKey());
                    sb.append(',').append(be == null ? "" : String.format(Locale.ROOT, "%.4f", be));
                    sb.append(',').append(bf == null ? "" : String.format(Locale.ROOT, "%.4f", bf));
                    sb.append(',').append(WHERE.getOrDefault(e.getKey(), ""));
                    w.write(sb.toString());
                    w.newLine();
                }
            }
            writeParallel();
            writeReadiness();
            System.out.println("[GATE] wrote " + ROWS.size() + " vehicles to " + TARGET.toAbsolutePath());
            System.out.println("[PARALLEL] ticks=" + PARALLEL[0] + " overlapping=" + PARALLEL[1]
                    + " heldByHysteresisOnly=" + PARALLEL[2] + " ofThoseBraking=" + PARALLEL[3]);
            System.out.println("[CF] calls=" + CF[0] + " idmAccepted=" + CF[1] + " heldAtBcrit=" + CF[2]
                    + " kinematic=" + CF[3] + " ofHeld_notClosing=" + CF[4] + " ofKin_crashImminent=" + CF[5]
                    + " ofKin_beyondBmax=" + CF[6]);
            if (CF[2] > 0)
            {
                System.out.println("[CF] onHeldBranch meanAidm=" + String.format(Locale.ROOT, "%.2f", CF_SUM[0] / CF[2])
                        + " meanDkin=" + String.format(Locale.ROOT, "%.2f", CF_SUM[1] / CF[2])
                        + " dkinHist[0..-1,-1..-2,-2..-3,-3..-4,<-4]=" + CF_DKIN[0] + "," + CF_DKIN[1] + ","
                        + CF_DKIN[2] + "," + CF_DKIN[3] + "," + CF_DKIN[4]);
            }
            System.out.println("[BRANCH] ticks=" + BRANCH[0] + " noTime=" + BRANCH[1] + " noAccel="
                    + BRANCH[2] + " alreadyAhead=" + BRANCH[3] + " overtook=" + BRANCH[4]);
        }
        catch (IOException exception)
        {
            throw new UncheckedIOException(exception);
        }
    }
}
