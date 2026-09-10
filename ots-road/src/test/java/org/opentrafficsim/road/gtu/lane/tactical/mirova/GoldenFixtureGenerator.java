package org.opentrafficsim.road.gtu.lane.tactical.mirova;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.djunits.value.vdouble.scalar.Duration;
import org.djunits.value.vdouble.scalar.Length;
import org.junit.jupiter.api.Test;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.RelaxationDiagnostics;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.RelaxationState;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.VehicleContextManager;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.DesireLayer.Desire;

/**
 * Drives the stage 0 MiRoVA classes through fixed input sequences and writes what they answered as
 * JSON, so that the Kotlin port in TaMA can be asserted to reproduce it.
 * <p>
 * <b>Test scope only.</b> Nothing in the production tree is touched, read or changed. This class
 * exists to <i>measure</i> the Java model, not to check it: it asserts almost nothing itself, and a
 * fixture that changes is a signal to look, not a failure. The equivalence assertions live on the
 * Kotlin side, against the files this writes.
 * </p>
 * <p>
 * <b>Switch configuration.</b> The fixtures are the core reference model, not the OTS defaults, so
 * they are generated as the {@code coreset} variant of {@code Phase05ReferenceStudy} - and for
 * these six classes the only switch that reaches any of them is {@code bcContextOrderFixed}, which
 * is recorded as true. The other four switches in {@code coreset} (BC-1, BC-2, BC-4, BC-6) act in
 * the Intention and Desire layers' call sites, none of which is exercised here, and BC-9 is not in
 * {@code coreset} at all.
 * </p>
 * <p>
 * Run it with:
 * </p>
 *
 * <pre>
 * mvn -o -pl ots-road test -Dtest=GoldenFixtureGenerator \
 *     -Dmirova.golden.out=/path/to/tama-core/src/test/resources/golden
 * </pre>
 * <p>
 * Without the property it writes to {@code target/golden} and the files are simply left there.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class GoldenFixtureGenerator
{

    /** Where the fixtures are written. */
    private static final Path OUT = Paths.get(System.getProperty("mirova.golden.out", "target/golden"));

    /** The commit the fixtures were generated from, recorded in every file. */
    private static final String SOURCE_COMMIT = "e93306f958aa228c9672c87cfb8182468dd99a1e";

    /** The switch configuration, recorded in every file. */
    private static final String SWITCHES = "coreset (bc1, bc2, bc4, bc6, bc8); of these only bc8 reaches stage 0";

    // ------------------------------------------------------------------
    // RelaxationState
    // ------------------------------------------------------------------

    /**
     * Writes {@code relaxation_state.json}.
     * <p>
     * Each case is an ordered trace of steps against one {@link RelaxationState}: {@code sample}
     * reads the buffer and the two flags at a time, {@code beginFade} calls
     * {@link RelaxationState#beginFade}. Order matters, which is why this is a trace and not a set
     * of independent points - {@code beginFade} is idempotent and the second call must be seen to
     * do nothing.
     * </p>
     * <p>
     * The cases cover: plain decay over four time constants; the guard clauses for a time before
     * the start and for a non-positive deficit; a fade begun mid-decay and sampled across it; a
     * fade of zero duration, which is the behaviour that preceded the fade; a fade begun twice; and
     * a fade begun before the relaxation's own start time, which drives {@code done <= 0}.
     * </p>
     * @throws IOException when the fixture cannot be written
     */
    @Test
    public void writeRelaxationStateFixture() throws IOException
    {
        List<String> cases = new ArrayList<>();

        // Plain exponential decay with tau = 20 s, the calibrated value.
        cases.add(relaxationCase("plain_decay_tau20", 100.0, 6.0, 20.0,
                new double[] {100.0, 100.5, 101.0, 105.0, 110.0, 120.0, 140.0, 160.0, 200.0, 300.0}, new double[0][]));

        // tau = 15 s, the value the paper gives, so the fixture is not silently tied to one tau.
        cases.add(relaxationCase("plain_decay_tau15", 0.0, 3.25, 15.0,
                new double[] {0.0, 1.0, 7.5, 15.0, 30.0, 45.0, 90.0}, new double[0][]));

        // Sampled before the relaxation began: the guard returns zero.
        cases.add(relaxationCase("before_start", 50.0, 4.0, 20.0,
                new double[] {40.0, 49.999, 50.0, 51.0}, new double[0][]));

        // Non-positive deficits: nothing to give, and not an error.
        cases.add(relaxationCase("zero_deficit", 10.0, 0.0, 20.0, new double[] {10.0, 20.0}, new double[0][]));
        cases.add(relaxationCase("negative_deficit", 10.0, -2.5, 20.0, new double[] {10.0, 20.0}, new double[0][]));

        // A fade begun at t = 110, over 1 s, which is the tRelaxFade default.
        cases.add(relaxationCase("fade_mid_decay", 100.0, 6.0, 20.0,
                new double[] {105.0, 110.0, 110.25, 110.5, 110.75, 110.999, 111.0, 111.5, 120.0},
                new double[][] {{110.0, 1.0}}));

        // A fade of zero duration ends the relaxation at once - the behaviour before the fade.
        cases.add(relaxationCase("fade_zero_duration", 100.0, 6.0, 20.0,
                new double[] {105.0, 110.0, 110.001, 111.0}, new double[][] {{110.0, 0.0}}));

        // A fade of negative duration takes the same branch.
        cases.add(relaxationCase("fade_negative_duration", 100.0, 6.0, 20.0,
                new double[] {110.0, 111.0}, new double[][] {{110.0, -1.0}}));

        // Begun twice: the second call must be ignored, so the fade still ends at 111, not 116.
        cases.add(relaxationCase("fade_begun_twice", 100.0, 6.0, 20.0,
                new double[] {110.5, 111.5, 116.5}, new double[][] {{110.0, 1.0}, {115.0, 5.0}}));

        // Begun before the sample times reach it: done <= 0, so the factor is still one.
        cases.add(relaxationCase("fade_not_yet_due", 100.0, 6.0, 20.0,
                new double[] {105.0, 106.0}, new double[][] {{107.0, 1.0}}));

        write("relaxation_state.json", "RelaxationState: buffer decay, the guard clauses and the fade-out", cases);
    }

    /**
     * Runs one {@link RelaxationState} trace and renders it as a JSON object.
     * <p>
     * The fade calls are interleaved with the samples by time: every fade whose time is at or
     * before the next sample is applied first. That reproduces the order the model itself would
     * produce, where the abort condition fires during a tick and the buffer is read afterwards.
     * </p>
     * @param name the case name
     * @param startTimeS the relaxation's start time [s]
     * @param deficitM the initial space deficit [m]
     * @param tauS the time constant [s]
     * @param sampleTimesS the times at which the buffer is read [s], ascending
     * @param fadeCalls pairs of (time [s], duration [s]) at which beginFade is called, ascending
     * @return the case as a JSON object
     */
    private static String relaxationCase(final String name, final double startTimeS, final double deficitM,
            final double tauS, final double[] sampleTimesS, final double[][] fadeCalls)
    {
        RelaxationState state = new RelaxationState(Duration.instantiateSI(startTimeS),
                Length.instantiateSI(deficitM), Duration.instantiateSI(tauS));

        List<String> steps = new ArrayList<>();
        int nextFade = 0;
        for (double tS : sampleTimesS)
        {
            while (nextFade < fadeCalls.length && fadeCalls[nextFade][0] <= tS)
            {
                double atS = fadeCalls[nextFade][0];
                double durationS = fadeCalls[nextFade][1];
                state.beginFade(Duration.instantiateSI(atS), Duration.instantiateSI(durationS));
                steps.add(obj(field("op", "beginFade"), num("atS", atS), num("durationS", durationS)));
                nextFade++;
            }
            Duration now = Duration.instantiateSI(tS);
            steps.add(obj(field("op", "sample"), num("tS", tS), num("bufferM", state.getVirtualSpaceBuffer(now).si),
                    bool("isFading", state.isFading()), bool("isFadedOut", state.isFadedOut(now))));
        }

        return obj(field("name", name), num("startTimeS", startTimeS), num("initialSpaceDeficitM", deficitM),
                num("tauSpaceS", tauS), arr("steps", steps));
    }

    // ------------------------------------------------------------------
    // Desire
    // ------------------------------------------------------------------

    /**
     * Writes {@code desire.json}.
     * <p>
     * Two parts. The first sweeps {@link Desire#computeDiscLcWeight} over its whole input range for
     * three threshold pairs, so that <b>every branch including the interpolation is covered</b>:
     * </p>
     * <ul>
     * <li>{@code (dSync, dCoop) = (0.577, 0.788)} - {@code d_mand} and {@code d_search}, the
     * paper's pairing, where the taper is reachable;</li>
     * <li>{@code (0.577, 0.365)} - what {@code MirovaTacticalPlanner} actually passes, where
     * {@code dCoop < dSync} makes the taper unreachable and theta steps at {@code d_mand};</li>
     * <li>{@code (0.5, 0.5)} - degenerate, where the second test fires before the division would.</li>
     * </ul>
     * <p>
     * This is deliberately independent of Q9: the function is correct and the defect is at the call
     * site, so the sweep pins the function's behaviour whichever way Q9 is decided. The second pair
     * is included so that the step function is recorded too, and can be recognised if it is ever
     * seen again.
     * </p>
     * <p>
     * The second part exercises the object: the two constructors, {@code add}, {@code scale},
     * {@code combine}, {@code magnitude}, {@code dominantDirection} and the component accessors,
     * including the dead band where neither side dominates.
     * </p>
     * @throws IOException when the fixture cannot be written
     */
    @Test
    public void writeDesireFixture() throws IOException
    {
        double[][] thresholdPairs = {{0.577, 0.788}, {0.577, 0.365}, {0.5, 0.5}};
        double[] discretionaries = {-1.0, -0.5, -0.1, 0.0, 0.1, 0.5, 1.0};

        List<String> weights = new ArrayList<>();
        for (double[] pair : thresholdPairs)
        {
            for (int i = -20; i <= 20; i++)
            {
                double mandatory = i * 0.05;
                for (double discretionary : discretionaries)
                {
                    weights.add(obj(num("mandatory", mandatory), num("discretionary", discretionary),
                            num("dSync", pair[0]), num("dCoop", pair[1]),
                            num("weight", Desire.computeDiscLcWeight(mandatory, discretionary, pair[0], pair[1]))));
                }
            }
        }

        List<String> objects = new ArrayList<>();
        objects.add(desireCase("zero", Desire.zero()));
        objects.add(desireCase("mandatory_left", new Desire(0.8, -0.2, true)));
        objects.add(desireCase("discretionary_right", new Desire(-0.1, 0.4, false)));
        objects.add(desireCase("sum_of_two", new Desire(0.8, -0.2, true).add(new Desire(-0.1, 0.4, false))));
        objects.add(desireCase("scaled", new Desire(0.8, -0.2, true).scale(0.375)));
        objects.add(desireCase("scaled_negative", new Desire(0.8, -0.2, true).scale(-2.0)));
        objects.add(desireCase("dead_band", new Desire(0.5, 0.5004, false)));
        objects.add(desireCase("dead_band_edge", new Desire(0.5, 0.501, false)));
        objects.add(desireCase("both_negative", new Desire(-0.3, -0.7, false)));

        // combine, over pairings that reach each branch of the weighting on at least one side.
        objects.add(desireCase("combine_agreeing",
                Desire.combine(new Desire(0.9, 0.0, true), new Desire(0.4, 0.0, false), 0.577, 0.788)));
        objects.add(desireCase("combine_conflicting_strong",
                Desire.combine(new Desire(0.9, 0.0, true), new Desire(-0.4, 0.0, false), 0.577, 0.788)));
        objects.add(desireCase("combine_conflicting_interpolated",
                Desire.combine(new Desire(0.68, 0.0, true), new Desire(-0.4, 0.0, false), 0.577, 0.788)));
        objects.add(desireCase("combine_conflicting_weak",
                Desire.combine(new Desire(0.3, 0.0, true), new Desire(-0.4, 0.0, false), 0.577, 0.788)));
        objects.add(desireCase("combine_both_sides",
                Desire.combine(new Desire(0.68, -0.7, true), new Desire(-0.4, 0.35, false), 0.577, 0.788)));
        objects.add(desireCase("combine_planner_thresholds",
                Desire.combine(new Desire(0.68, 0.0, true), new Desire(-0.4, 0.0, false), 0.577, 0.365)));
        objects.add(desireCase("combine_mixed_inputs",
                Desire.combine(new Desire(0.5, 0.0, true).add(new Desire(0.2, 0.0, false)),
                        new Desire(-0.4, 0.1, false), 0.577, 0.788)));

        String body = "{\n  " + field("generator", "GoldenFixtureGenerator.writeDesireFixture") + ",\n  "
                + field("sourceCommit", SOURCE_COMMIT) + ",\n  " + field("switches", SWITCHES) + ",\n  "
                + field("description", "Desire: the LMRS weighting over its full input range, and the object's algebra")
                + ",\n  " + arr("weights", weights) + ",\n  " + arr("objects", objects) + "\n}\n";
        writeRaw("desire.json", body);
    }

    /**
     * Renders one {@link Desire} with every component and derived value.
     * @param name the case name
     * @param desire the desire to render
     * @return the case as a JSON object
     */
    private static String desireCase(final String name, final Desire desire)
    {
        return obj(field("name", name), num("left", desire.getLeft()), num("right", desire.getRight()),
                num("leftMandatory", desire.getMandatoryDesire(
                        org.opentrafficsim.core.network.LateralDirectionality.LEFT)),
                num("rightMandatory", desire.getMandatoryDesire(
                        org.opentrafficsim.core.network.LateralDirectionality.RIGHT)),
                num("leftDiscretionary", desire.getDiscretionaryDesire(
                        org.opentrafficsim.core.network.LateralDirectionality.LEFT)),
                num("rightDiscretionary", desire.getDiscretionaryDesire(
                        org.opentrafficsim.core.network.LateralDirectionality.RIGHT)),
                num("directionalNone", desire.getDirectionalDesire(
                        org.opentrafficsim.core.network.LateralDirectionality.NONE)),
                bool("isMandatory", desire.isMandatory()), num("magnitude", desire.magnitude()),
                field("dominantDirection", desire.dominantDirection().name()));
    }

    // ------------------------------------------------------------------
    // RelaxationDiagnostics
    // ------------------------------------------------------------------

    /**
     * Writes {@code relaxation_diagnostics.json}.
     * <p>
     * The counters are process-wide statics with no accessors, so they are read back by reflection.
     * That is the only way to measure them, and it is measurement rather than re-derivation: the
     * numbers in the fixture are what the Java class actually accumulated.
     * </p>
     * <p>
     * What is being pinned is the rounding. Lifetimes and distances are accumulated as whole
     * milliseconds and millimetres through {@code Math.round}, which rounds ties towards positive
     * infinity - so 0.0015 becomes 2 and -0.0015 becomes -1, and a port that used a
     * half-away-from-zero rounding would disagree on exactly those inputs. Several of the values
     * below are chosen to sit on a tie for that reason.
     * </p>
     * <p>
     * The counters are not reset between runs, so this test reads them before and after and reports
     * the difference. It must not run alongside anything else that touches the class.
     * </p>
     * @throws Exception when a counter field cannot be read
     */
    @Test
    public void writeRelaxationDiagnosticsFixture() throws Exception
    {
        String[] counters = {"CREATED", "CREATED_DEFICIT_MM", "ABORT_DECEL", "ABORT_SPEED", "EXPIRED", "LIFE_DECEL",
                "LIFE_SPEED", "LIFE_EXPIRED", "DISCARDED_MM", "DISCARDED_LARGE", "CALLS_RELAXED", "CALLS_PLAIN"};
        Map<String, Long> before = readCounters(counters);

        List<String> events = new ArrayList<>();

        double[] deficits = {1.5, 0.0015, -0.0015, 6.25, 0.3335};
        for (double d : deficits)
        {
            RelaxationDiagnostics.created(d);
            events.add(obj(field("event", "created"), num("initialDeficitMetres", d)));
        }

        double[] decelLifetimes = {12.5, 0.0005, 59.9994};
        for (double l : decelLifetimes)
        {
            RelaxationDiagnostics.abortedByDeceleration(l);
            events.add(obj(field("event", "abortedByDeceleration"), num("lifetimeSeconds", l)));
        }

        double[] speedLifetimes = {3.3335, 0.25};
        for (double l : speedLifetimes)
        {
            RelaxationDiagnostics.abortedBySpeed(l);
            events.add(obj(field("event", "abortedBySpeed"), num("lifetimeSeconds", l)));
        }

        double[] expiredLifetimes = {60.0, 45.6789};
        for (double l : expiredLifetimes)
        {
            RelaxationDiagnostics.expired(l);
            events.add(obj(field("event", "expired"), num("lifetimeSeconds", l)));
        }

        // 0.5 exactly is not "large"; the test is strictly greater than.
        double[] discards = {0.5, 0.5001, 9.2, 0.0005};
        for (double b : discards)
        {
            RelaxationDiagnostics.discarded(b);
            events.add(obj(field("event", "discarded"), num("bufferMetres", b)));
        }

        boolean[] calls = {true, true, false, true, false};
        for (boolean relaxed : calls)
        {
            RelaxationDiagnostics.carFollowingCall(relaxed);
            events.add(obj(field("event", "carFollowingCall"), bool("relaxed", relaxed)));
        }

        Map<String, Long> after = readCounters(counters);
        List<String> totals = new ArrayList<>();
        for (String counter : counters)
        {
            totals.add(obj(field("counter", counter), num("value", after.get(counter) - before.get(counter))));
        }

        String body = "{\n  " + field("generator", "GoldenFixtureGenerator.writeRelaxationDiagnosticsFixture") + ",\n  "
                + field("sourceCommit", SOURCE_COMMIT) + ",\n  " + field("switches", SWITCHES) + ",\n  "
                + field("description",
                        "RelaxationDiagnostics: the accumulated counters after a fixed event sequence, "
                                + "pinning Math.round's tie behaviour")
                + ",\n  " + arr("events", events) + ",\n  " + arr("totals", totals) + "\n}\n";
        writeRaw("relaxation_diagnostics.json", body);
    }

    /**
     * Reads the named private static {@link AtomicLong} counters off {@link RelaxationDiagnostics}.
     * @param names the field names
     * @return the current values, keyed by field name
     * @throws Exception when a field is missing or inaccessible
     */
    private static Map<String, Long> readCounters(final String[] names) throws Exception
    {
        Map<String, Long> values = new LinkedHashMap<>();
        for (String name : names)
        {
            Field field = RelaxationDiagnostics.class.getDeclaredField(name);
            field.setAccessible(true);
            values.put(name, ((AtomicLong) field.get(null)).get());
        }
        return values;
    }

    // ------------------------------------------------------------------
    // VehicleContextManager
    // ------------------------------------------------------------------

    /**
     * Writes {@code context_update_order.json}.
     * <p>
     * <b>This fixture records the two order constants, not a driven run.</b>
     * {@code VehicleContextManager}'s constructor builds four real context categories from a
     * {@code MirovaTacticalPlanner}, which needs a GTU, a network and a simulator, and its
     * {@code orderedCategories} reads the switch off that planner's parameter snapshot. Standing
     * all of that up inside a unit test would be a fixture about OTS rather than about the
     * ordering.
     * </p>
     * <p>
     * What is behavioural about this class is <i>which order</i>, and that is exactly what the two
     * constants hold; they are read off the class by reflection, so the fixture is measured from
     * the source of truth rather than transcribed. {@code bcContextOrderFixed} is true in the core
     * reference set, so {@code UPDATE_ORDER_FIXED} is the order the Kotlin port must implement, and
     * {@code UPDATE_ORDER} is recorded beside it as the inherited order every published result was
     * produced with.
     * </p>
     * <p>
     * The remaining behaviour - named categories first and in order, anything else after in
     * registration order - is not covered here. It is asserted on the Kotlin side against fakes,
     * and the limitation is stated there too.
     * </p>
     * @throws Exception when a constant cannot be read
     */
    @Test
    public void writeContextUpdateOrderFixture() throws Exception
    {
        List<String> orders = new ArrayList<>();
        orders.add(obj(field("name", "UPDATE_ORDER"), bool("bcContextOrderFixed", false),
                field("description", "inherited from HashMap iteration; what every published result used"),
                arr("order", quoteAll(readOrderConstant("UPDATE_ORDER")))));
        orders.add(obj(field("name", "UPDATE_ORDER_FIXED"), bool("bcContextOrderFixed", true),
                field("description", "the dependency order, BC-8; what the core implements"),
                arr("order", quoteAll(readOrderConstant("UPDATE_ORDER_FIXED")))));

        write("context_update_order.json",
                "VehicleContextManager: the two context update orders, read off the class", orders);
    }

    /**
     * Reads a private static {@code String[]} order constant off {@link VehicleContextManager}.
     * @param name the field name
     * @return the order
     * @throws Exception when the field is missing or inaccessible
     */
    private static List<String> readOrderConstant(final String name) throws Exception
    {
        Field field = VehicleContextManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return List.of((String[]) field.get(null));
    }

    // ------------------------------------------------------------------
    // JSON, written by hand
    // ------------------------------------------------------------------

    /**
     * Writes a fixture whose body is a single named array of cases.
     * @param fileName the file to write
     * @param description what the fixture covers
     * @param cases the rendered cases
     * @throws IOException when the file cannot be written
     */
    private static void write(final String fileName, final String description, final List<String> cases)
            throws IOException
    {
        writeRaw(fileName, "{\n  " + field("generator", "GoldenFixtureGenerator") + ",\n  "
                + field("sourceCommit", SOURCE_COMMIT) + ",\n  " + field("switches", SWITCHES) + ",\n  "
                + field("description", description) + ",\n  " + arr("cases", cases) + "\n}\n");
    }

    /**
     * Writes a rendered fixture to the output directory.
     * @param fileName the file to write
     * @param body the complete JSON document
     * @throws IOException when the file cannot be written
     */
    private static void writeRaw(final String fileName, final String body) throws IOException
    {
        Files.createDirectories(OUT);
        Path target = OUT.resolve(fileName);
        Files.write(target, body.getBytes(StandardCharsets.UTF_8));
        System.out.println("[golden] wrote " + target.toAbsolutePath());
    }

    /**
     * Renders a JSON object from already-rendered members.
     * @param members the members, each {@code "key": value}
     * @return the object
     */
    private static String obj(final String... members)
    {
        return "{" + String.join(", ", members) + "}";
    }

    /**
     * Renders a named array of already-rendered elements, one per line.
     * @param key the member name
     * @param elements the elements
     * @return the member
     */
    private static String arr(final String key, final List<String> elements)
    {
        return "\"" + key + "\": [\n    " + String.join(",\n    ", elements) + "\n  ]";
    }

    /**
     * Renders a string member.
     * @param key the member name
     * @param value the value; must not contain a quote or a backslash
     * @return the member
     */
    private static String field(final String key, final String value)
    {
        return "\"" + key + "\": \"" + value + "\"";
    }

    /**
     * Renders a numeric member.
     * <p>
     * {@code Double.toString} is used rather than a fixed number of decimals, because it
     * round-trips exactly: the shortest decimal that reads back as the same double. A formatted
     * value would make the fixture a statement about the formatting.
     * </p>
     * @param key the member name
     * @param value the value
     * @return the member
     */
    private static String num(final String key, final double value)
    {
        return "\"" + key + "\": " + Double.toString(value);
    }

    /**
     * Renders a numeric member from a long.
     * @param key the member name
     * @param value the value
     * @return the member
     */
    private static String num(final String key, final long value)
    {
        return "\"" + key + "\": " + Long.toString(value);
    }

    /**
     * Renders a boolean member.
     * @param key the member name
     * @param value the value
     * @return the member
     */
    private static String bool(final String key, final boolean value)
    {
        return "\"" + key + "\": " + Boolean.toString(value);
    }

    /**
     * Quotes each string so it can be used as a JSON array element.
     * @param values the strings
     * @return the quoted strings
     */
    private static List<String> quoteAll(final List<String> values)
    {
        List<String> quoted = new ArrayList<>(values.size());
        for (String value : values)
        {
            quoted.add("\"" + value + "\"");
        }
        return quoted;
    }
}
