package org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging;

import java.util.LinkedHashMap;
import java.util.Map;

import org.opentrafficsim.core.gtu.Gtu;
import org.opentrafficsim.core.gtu.GtuErrorHandler;
import org.opentrafficsim.road.gtu.lane.LaneBasedGtu;
import org.opentrafficsim.road.network.lane.LanePosition;

/**
 * Reports why a GTU is removed, instead of removing it silently.
 * <p>
 * {@code GtuErrorHandler.DELETE} logs the identifier and destroys the vehicle, discarding the exception. The
 * cause of these removals has therefore never been established: they are attributed to lane changes that began
 * too late to finish before the ramp ends, but that reading rests on the position they happen at rather than on
 * anything the model said. This handler keeps the behaviour - the vehicle is still destroyed, so a run is
 * comparable to one without it - and records the exception, the place and the state it happened in.
 * </p>
 * <p>
 * Grouped by exception type and message rather than logged per vehicle: the same few causes repeat hundreds of
 * times in a run, and the interesting quantity is which of them dominates and where on the road it strikes.
 * </p>
 * <p>
 * The tally is static and is never reset, so it describes one replication and assumes one replication per process,
 * which is how the campaigns and the local harness run; nothing here is synchronised either.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See OpenTrafficSim License.
 * </p>
 * @author Marvin Baumann
 */
public final class GtuDeletionDiagnostics implements GtuErrorHandler
{
    /** The handler, which reports and then does what {@code DELETE} does. */
    public static final GtuDeletionDiagnostics INSTANCE = new GtuDeletionDiagnostics();

    /** Per cause: count, and the positions it struck at, to a metre. */
    private static final Map<String, long[]> CAUSES = new LinkedHashMap<>();

    /** Per cause, the lanes it struck on. */
    private static final Map<String, Map<String, Long>> LANES = new LinkedHashMap<>();

    /** Utility instance. */
    private GtuDeletionDiagnostics()
    {
        // singleton
    }

    @Override
    public void handle(final Gtu gtu, final Exception ex) throws Exception
    {
        record(gtu, ex);
        gtu.destroy();
    }

    /**
     * Adds one removal to the tally.
     * @param gtu Gtu; the vehicle being removed
     * @param ex Exception; why it is being removed
     */
    private static void record(final Gtu gtu, final Exception ex)
    {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root)
        {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (message != null && message.length() > 120)
        {
            message = message.substring(0, 120);
        }
        // The message alone does not identify a NullPointerException, so the throwing frame is part of the key.
        StringBuilder where = new StringBuilder();
        StackTraceElement[] frames = root.getStackTrace();
        for (int i = 0; i < Math.min(3, frames.length); i++)
        {
            where.append(i == 0 ? " at " : " <- ").append(frames[i].getClassName()
                    .substring(frames[i].getClassName().lastIndexOf('.') + 1)).append('.')
                    .append(frames[i].getMethodName()).append(':').append(frames[i].getLineNumber());
        }
        String cause = root.getClass().getSimpleName() + ": " + message + where;

        String lane = "unknown";
        long positionM = -1;
        if (gtu instanceof LaneBasedGtu)
        {
            try
            {
                LanePosition ref = ((LaneBasedGtu) gtu).getReferencePosition();
                if (ref != null && ref.lane() != null)
                {
                    lane = ref.lane().getLink().getId() + "/" + ref.lane().getId();
                    positionM = Math.round(ref.position().si);
                }
            }
            catch (Exception suppressed)
            {
                // The reference position is what the removal is often about, so failing to read it is expected
                // and must not replace the cause being recorded.
                lane = "unreadable";
            }
        }

        long[] c = CAUSES.computeIfAbsent(cause, k -> new long[] {0, 0, Long.MAX_VALUE, Long.MIN_VALUE});
        c[0]++;
        if (positionM >= 0)
        {
            c[1] += positionM;
            c[2] = Math.min(c[2], positionM);
            c[3] = Math.max(c[3], positionM);
        }
        LANES.computeIfAbsent(cause, k -> new LinkedHashMap<>()).merge(lane, 1L, Long::sum);
    }

    /** Prints the tally. Called at the end of a run, because the vehicles are gone by then and nothing else reports. */
    public static void report()
    {
        if (CAUSES.isEmpty())
        {
            System.out.println("[DELETED] no vehicles removed");
            return;
        }
        long total = CAUSES.values().stream().mapToLong(c -> c[0]).sum();
        System.out.println("[DELETED] " + total + " vehicles removed, by cause:");
        CAUSES.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .forEach(e ->
                {
                    long[] c = e.getValue();
                    String where = c[2] == Long.MAX_VALUE ? "position unknown"
                            : String.format("x mean %d m, %d..%d", c[1] / Math.max(1, c[0]), c[2], c[3]);
                    System.out.println("[DELETED]   " + c[0] + "x (" + (100 * c[0] / total) + " %) " + e.getKey());
                    System.out.println("[DELETED]      " + where + " | lanes " + LANES.get(e.getKey()));
                });
    }
}
