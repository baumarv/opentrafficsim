package org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

import org.djunits.value.vdouble.scalar.Acceleration;
import org.djunits.value.vdouble.scalar.Time;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ActionState;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer.ManeuverPattern;

/**
 * Records the tactical decision of every MiRoVA vehicle in every tick, so that a refactoring of the Layer 3 state machine can
 * be proven to leave the model behaviour untouched.
 * <p>
 * This is deliberately not the same thing as {@link MirovaCsvLogger}. That logger samples the vehicle population at a fixed
 * interval from a scheduled event, which is the right tool for analysis but useless as a regression net: what it captures
 * depends on the scheduling order and on the sampling interval, not on the decision cycle. This recorder is pushed once from
 * the end of the decision cycle instead, so it sees exactly one row per vehicle per tick, exactly the values the vehicle
 * acted on.
 * </p>
 * <p>
 * The recorder is off unless {@link #start(Path)} is called, and while it is off it costs one static null check per vehicle
 * per tick. Rows are held in memory and sorted by (time, vehicle) when the trace is written, so that the file does not depend
 * on the order in which the simulator happened to visit the vehicles. That buffering is the reason this is a tool for short,
 * deliberately started runs, not something to leave enabled over a two-hour scenario.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public final class FsmTraceRecorder
{

    /** Header of the trace file. */
    public static final String HEADER = "time,gtuId,pattern,state,a,indicator,laneChange";

    /** Name used when no pattern or no action state was active in a tick. */
    private static final String NONE = "-";

    /** The recording currently in progress, or {@code null} when recording is off. */
    private static FsmTraceRecorder active = null;

    /** Destination of the trace file. */
    private final Path target;

    /** Collected rows, sorted only when the trace is written. */
    private final List<Row> rows = new ArrayList<>();

    /**
     * Creates a recorder writing to the given file.
     * @param target the file the trace is written to when {@link #stop()} is called
     */
    private FsmTraceRecorder(final Path target)
    {
        this.target = target;
    }

    // ----------------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------------

    /**
     * Starts recording into the given file. Any recording already in progress is discarded unwritten, which makes a forgotten
     * {@link #stop()} in a preceding run a lost trace rather than a corrupted one.
     * @param target the file the trace is written to when recording stops
     */
    public static void start(final Path target)
    {
        active = new FsmTraceRecorder(target);
    }

    /**
     * Starts recording into the given file.
     * @param target path of the file the trace is written to when recording stops
     */
    public static void start(final String target)
    {
        start(Paths.get(target));
    }

    /**
     * Returns whether a recording is currently in progress.
     * @return {@code true} while a recording is running
     */
    public static boolean isRecording()
    {
        return active != null;
    }

    /**
     * Stops the recording and writes the trace, sorted by time and then vehicle id.
     * @return the file that was written
     * @throws IOException if the trace cannot be written
     */
    public static Path stop() throws IOException
    {
        FsmTraceRecorder recorder = active;
        active = null;
        if (recorder == null)
        {
            throw new IllegalStateException("FsmTraceRecorder.stop() called while no recording was running.");
        }
        return recorder.write();
    }

    // ----------------------------------------------------------------------
    // Recording
    // ----------------------------------------------------------------------

    /**
     * Records the tactical decision of one vehicle for one tick. Does nothing unless a recording is running.
     * @param time the simulation time of the decision
     * @param gtuId the id of the deciding vehicle
     * @param pattern the maneuver pattern that produced the plan, or {@code null} for plain car-following
     * @param state the action state that produced the plan, or {@code null} for plain car-following
     * @param acceleration the acceleration the vehicle acts on this tick
     * @param indicator the turn indicator intent of the plan
     * @param laneChange the lateral direction of the plan
     */
    public static void record(final Time time, final String gtuId, final ManeuverPattern pattern, final ActionState state,
            final Acceleration acceleration, final String indicator, final String laneChange)
    {
        FsmTraceRecorder recorder = active;
        if (recorder == null)
        {
            return;
        }
        recorder.rows.add(new Row(time.si, gtuId, name(pattern), name(state), acceleration == null ? Double.NaN
                : acceleration.si, indicator, laneChange));
    }

    /**
     * Returns the simple class name of a pattern, or the placeholder when there was none.
     * @param pattern the pattern, possibly {@code null}
     * @return the name to write into the trace
     */
    private static String name(final ManeuverPattern pattern)
    {
        return pattern == null ? NONE : pattern.getClass().getSimpleName();
    }

    /**
     * Returns the name of an action state, or the placeholder when there was none. The state's own {@code toString()} is used
     * because several states encode the target direction in it, which is part of the decision and must therefore be part of
     * the trace.
     * @param state the state, possibly {@code null}
     * @return the name to write into the trace
     */
    private static String name(final ActionState state)
    {
        return state == null ? NONE : sanitize(state.toString());
    }

    /**
     * Replaces separators a state name may legitimately contain, so that one recorded decision stays one CSV row with one
     * field per column. Several states put a direction or a candidate vehicle into their {@code toString()} and separate them
     * with a comma, which would otherwise split the state across two columns.
     * @param name the raw name
     * @return the name with commas and line breaks replaced by semicolons
     */
    private static String sanitize(final String name)
    {
        return name.replace(',', ';').replace('\n', ';').replace('\r', ';');
    }

    // ----------------------------------------------------------------------
    // Output
    // ----------------------------------------------------------------------

    /**
     * Sorts and writes the collected rows.
     * @return the file that was written
     * @throws IOException if the trace cannot be written
     */
    private Path write() throws IOException
    {
        this.rows.sort(Comparator.comparingDouble((final Row r) -> r.time).thenComparing(r -> r.gtuId));
        Path parent = this.target.toAbsolutePath().getParent();
        if (parent != null)
        {
            Files.createDirectories(parent);
        }
        try (BufferedWriter writer = openWriter())
        {
            writer.write(HEADER);
            writer.write('\n');
            for (Row row : this.rows)
            {
                writer.write(row.toCsv());
                writer.write('\n');
            }
        }
        return this.target;
    }

    /**
     * Opens the writer for the target file, gzipping it when the name says so. The reference traces live in the repository,
     * which an uncompressed trace of a few hundred thousand rows has no business doing.
     * @return the writer, which the caller closes
     * @throws IOException if the file cannot be opened
     */
    private BufferedWriter openWriter() throws IOException
    {
        if (this.target.getFileName().toString().endsWith(".gz"))
        {
            OutputStream out = new GZIPOutputStream(Files.newOutputStream(this.target));
            return new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        }
        return Files.newBufferedWriter(this.target, StandardCharsets.UTF_8);
    }

    /**
     * One recorded decision. Kept as a value holder rather than a pre-formatted string so that the rows can be sorted on the
     * numeric time without re-parsing it.
     */
    private static final class Row
    {
        /** Simulation time in seconds. */
        private final double time;

        /** Id of the deciding vehicle. */
        private final String gtuId;

        /** Name of the active maneuver pattern. */
        private final String pattern;

        /** Name of the active action state. */
        private final String state;

        /** Acceleration acted on, in m/s2. */
        private final double acceleration;

        /** Turn indicator intent. */
        private final String indicator;

        /** Lateral direction of the plan. */
        private final String laneChange;

        /**
         * Creates a row.
         * @param time simulation time in seconds
         * @param gtuId id of the deciding vehicle
         * @param pattern name of the active maneuver pattern
         * @param state name of the active action state
         * @param acceleration acceleration acted on, in m/s2
         * @param indicator turn indicator intent
         * @param laneChange lateral direction of the plan
         */
        Row(final double time, final String gtuId, final String pattern, final String state, final double acceleration,
                final String indicator, final String laneChange)
        {
            this.time = time;
            this.gtuId = gtuId;
            this.pattern = pattern;
            this.state = state;
            this.acceleration = acceleration;
            this.indicator = indicator;
            this.laneChange = laneChange;
        }

        /**
         * Formats this row as a CSV line. The acceleration is written with a fixed number of decimals: the trace is compared
         * byte for byte, so a locale-dependent or shortest-round-trip representation would make the comparison depend on
         * something other than the model.
         * @return the CSV line, without the line separator
         */
        String toCsv()
        {
            return String.format(Locale.ROOT, "%.3f,%s,%s,%s,%.9f,%s,%s", this.time, this.gtuId, this.pattern, this.state,
                    this.acceleration, this.indicator, this.laneChange);
        }
    }
}
