package org.opentrafficsim.demo.mirova.scenariomanagement.sampling;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.djutils.draw.point.Point2d;
import org.opentrafficsim.core.gtu.Gtu;
import org.opentrafficsim.core.gtu.GtuErrorHandler;
import org.opentrafficsim.road.gtu.LaneBasedGtu;
import org.opentrafficsim.road.network.LanePosition;

/**
 * Reports why a GTU is removed, instead of removing it silently.
 * <p>
 * {@code GtuErrorHandler.DELETE} logs the identifier and destroys the vehicle, discarding the exception. This handler keeps
 * that behaviour - the vehicle is still destroyed, so a run is comparable to one without it - and records the exception, the
 * place and the state it happened in.
 * </p>
 * <p>
 * Two records are kept. A tally by cause, printed at the end of a run: the same few causes repeat many times, and the first
 * question is which of them dominates and where on the road it strikes. And one row per removed vehicle, written to
 * {@value #CSV_FILE} with the full stack traces beside it in {@value #TRACES_FILE}, for looking at single cases.
 * </p>
 * <p>
 * A stack trace is only as complete as the JVM makes it. HotSpot stops filling in the trace of an exception it has thrown
 * often from the same place, so a run that is to be diagnosed needs {@code -XX:-OmitStackTraceInFastThrow}; the runners in
 * {@code cluster/} set it.
 * </p>
 * <p>
 * The records are static and never reset, so they describe one replication and assume one replication per process, which is
 * how the campaigns and the local runner work; nothing here is synchronised either.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public final class GtuDeletionDiagnostics implements GtuErrorHandler
{
    /** The handler, which reports and then does what {@code DELETE} does. */
    public static final GtuDeletionDiagnostics INSTANCE = new GtuDeletionDiagnostics();

    /** Name of the file with one row per removed vehicle. */
    public static final String CSV_FILE = "removed_vehicles.csv";

    /** Name of the file with the distinct stack traces, by their hash. */
    public static final String TRACES_FILE = "removed_vehicles_traces.txt";

    /** Header of {@link #CSV_FILE}. */
    public static final String CSV_HEADER = "gtu_id,gtu_type,sim_time_s,route_destination,roaming,link_id,lane_id,position_m,"
            + "x_m,y_m,speed_mps,exception,message,thrown_at,trace_hash";

    /** Number of stack frames that identify a cause in the tally. */
    private static final int TALLY_FRAMES = 3;

    /** Longest message kept. */
    private static final int MAX_MESSAGE_LENGTH = 160;

    /** Per cause: count, sum of positions, lowest and highest position, to a metre. */
    private static final Map<String, long[]> CAUSES = new LinkedHashMap<>();

    /** Per cause, the lanes it struck on. */
    private static final Map<String, Map<String, Long>> LANES = new LinkedHashMap<>();

    /** One CSV row per removed vehicle, in the order of removal. */
    private static final List<String> ROWS = new ArrayList<>();

    /** The distinct stack traces, by hash, in order of first occurrence. */
    private static final Map<String, String> TRACES = new LinkedHashMap<>();

    /**
     * Singleton.
     */
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
     * Adds one removal to the records.
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
        String message = root.getMessage() == null ? "" : root.getMessage();
        if (message.length() > MAX_MESSAGE_LENGTH)
        {
            message = message.substring(0, MAX_MESSAGE_LENGTH);
        }
        StackTraceElement[] frames = root.getStackTrace();
        // The message alone does not identify a NullPointerException, so the throwing frames are part of the cause.
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < Math.min(TALLY_FRAMES, frames.length); i++)
        {
            where.append(i == 0 ? " at " : " <- ").append(shortFrame(frames[i]));
        }
        String cause = root.getClass().getSimpleName() + ": " + message + where;

        // The whole chain, so that the wrapper exceptions between the model and the handler are visible as well.
        StringWriter trace = new StringWriter();
        ex.printStackTrace(new PrintWriter(trace));
        // The hash is over the frames of the root cause only: the message carries positions and differs per vehicle.
        StringBuilder frameText = new StringBuilder(root.getClass().getName());
        for (StackTraceElement frame : frames)
        {
            frameText.append('|').append(frame);
        }
        String hash = String.format(Locale.ROOT, "%08x", frameText.toString().hashCode());
        TRACES.putIfAbsent(hash, trace.toString());

        String link = "";
        String lane = "unknown";
        String position = "";
        boolean roaming = false;
        String destination = "";
        long positionM = -1;
        String x = "";
        String y = "";
        String speed = "";
        double time = Double.NaN;
        try
        {
            time = gtu.getSimulator().getSimulatorTime().si;
            speed = String.format(Locale.ROOT, "%.3f", gtu.getSpeed().si);
            Point2d location = gtu.getLocation();
            x = String.format(Locale.ROOT, "%.3f", location.x);
            y = String.format(Locale.ROOT, "%.3f", location.y);
        }
        catch (Exception suppressed)
        {
            // What cannot be read stays empty; reading the state of a vehicle that just failed may fail as well.
        }
        if (gtu instanceof LaneBasedGtu laneBasedGtu)
        {
            try
            {
                // Where the vehicle was going says whether it is somewhere its route does not lead.
                destination = laneBasedGtu.getStrategicalPlanner().getRoute().map((route) ->
                {
                    try
                    {
                        return route.destinationNode().getId();
                    }
                    catch (Exception exception)
                    {
                        return "";
                    }
                }).orElse("");
            }
            catch (Exception suppressed)
            {
                destination = "";
            }
            try
            {
                // A GTU that is on no lane is "roaming" in OTS; it then has no lane position, only a nearest one.
                roaming = laneBasedGtu.getLane() == null;
                LanePosition ref = roaming ? laneBasedGtu.getRoamingPosition() : laneBasedGtu.getPosition();
                if (ref != null && ref.lane() != null)
                {
                    link = ref.lane().getLink().getId();
                    lane = ref.lane().getId();
                    position = String.format(Locale.ROOT, "%.3f", ref.position().si);
                    positionM = Math.round(ref.position().si);
                }
            }
            catch (Exception suppressed)
            {
                // The position is what the removal is often about, so failing to read it is expected and must not
                // replace the cause being recorded.
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
        LANES.computeIfAbsent(cause, k -> new LinkedHashMap<>()).merge((roaming ? "roaming near " : "") + link + "/" + lane, 1L,
                Long::sum);

        ROWS.add(String.join(",", csv(gtu.getId()), csv(gtu.getType().getId()), String.format(Locale.ROOT, "%.3f", time),
                csv(destination), String.valueOf(roaming), csv(link), csv(lane), position, x, y, speed, csv(root.getClass().getName()),
                csv(message), csv(frames.length == 0 ? "" : shortFrame(frames[0])), hash));
    }

    /**
     * Renders a stack frame as {@code Class.method:line}, without the package.
     * @param frame StackTraceElement; the frame
     * @return String; the short form
     */
    private static String shortFrame(final StackTraceElement frame)
    {
        String className = frame.getClassName();
        return className.substring(className.lastIndexOf('.') + 1) + "." + frame.getMethodName() + ":" + frame.getLineNumber();
    }

    /**
     * Quotes a text field for a CSV file when it needs it.
     * @param text String; the text
     * @return String; the field, quoted when it contains a comma, a quote or a line break
     */
    private static String csv(final String text)
    {
        String value = text == null ? "" : text.replace("\r", " ").replace("\n", " ");
        return value.contains(",") || value.contains("\"") ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }

    /**
     * Returns the number of vehicles removed so far in this process.
     * @return long; the number of removed vehicles
     */
    public static long total()
    {
        return ROWS.size();
    }

    /**
     * Writes {@value #CSV_FILE} and {@value #TRACES_FILE} into a directory. The CSV file is written also when no vehicle was
     * removed, with its header only, so that its absence never has to be interpreted.
     * @param directory File; the output directory of the run
     * @throws IOException when a file cannot be written
     */
    public static void write(final File directory) throws IOException
    {
        StringBuilder csv = new StringBuilder(CSV_HEADER).append('\n');
        for (String row : ROWS)
        {
            csv.append(row).append('\n');
        }
        Files.writeString(new File(directory, CSV_FILE).toPath(), csv.toString(), StandardCharsets.UTF_8);
        StringBuilder traces = new StringBuilder();
        for (Map.Entry<String, String> trace : TRACES.entrySet())
        {
            traces.append("=== trace_hash ").append(trace.getKey()).append(" ===\n").append(trace.getValue()).append('\n');
        }
        Files.writeString(new File(directory, TRACES_FILE).toPath(), traces.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Prints the tally. Called at the end of a run, because the vehicles are gone by then and nothing else reports.
     */
    public static void report()
    {
        if (CAUSES.isEmpty())
        {
            System.out.println("[DELETED] no vehicles removed");
            return;
        }
        long total = CAUSES.values().stream().mapToLong(c -> c[0]).sum();
        System.out.println("[DELETED] " + total + " vehicles removed, by cause:");
        CAUSES.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0])).forEach(e ->
        {
            long[] c = e.getValue();
            String where = c[2] == Long.MAX_VALUE ? "position unknown"
                    : String.format(Locale.ROOT, "x mean %d m, %d..%d", c[1] / Math.max(1, c[0]), c[2], c[3]);
            System.out.println("[DELETED]   " + c[0] + "x (" + (100 * c[0] / total) + " %) " + e.getKey());
            System.out.println("[DELETED]      " + where + " | lanes " + LANES.get(e.getKey()));
        });
    }
}
