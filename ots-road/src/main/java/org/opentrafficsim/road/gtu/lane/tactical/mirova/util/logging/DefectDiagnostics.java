package org.opentrafficsim.road.gtu.lane.tactical.mirova.util.logging;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts the three defects identified in the Phase 0 decoupling inventory, so that their effect on the published
 * results can be judged before any of them is repaired.
 * <p>
 * The inventory named three places where the model may already be behaving differently from its description, and in
 * each case the question is not whether the construction is wrong -- it is -- but whether it ever fired on the
 * facility the results were produced on. That cannot be answered by reading the code, so it is measured here first
 * and repaired afterwards.
 * </p>
 * <ol>
 * <li><b>The look-ahead leak.</b> {@code InfrastructureContext.distanceToLaneChangeExtendedLookahead} raises
 * {@code LOOKAHEAD} to the extended value, queries the lane-change information, and lowers it again. The two
 * statements are not guarded, so an exception from the query in between leaves that vehicle with a 1000 m look-ahead
 * for the rest of its life. {@link #lookaheadLeak()} counts exactly those escapes, {@link #lookaheadRestored()} the
 * calls that completed normally, so the two together give the rate.</li>
 * <li><b>Swallowed exceptions.</b> Some 56 {@code catch} blocks in the behavioural code substitute a plausible
 * default -- zero, infinity, {@code null}, {@code false} -- and continue. Each is a place where a modelling failure
 * is indistinguishable from a modelling decision. {@link #swallowed(String)} counts them per site.</li>
 * <li><b>The speed-limit transition term.</b> {@code LongitudinalControl} adds
 * {@code SpeedLimitUtil.considerSpeedLimitTransitions} as an acceleration candidate and then takes the minimum over
 * all candidates. It is the only consumer of the full speed-limit prospect, and therefore the only reason the
 * decoupled contract would have to carry curvature and speed bumps. {@link #speedLimitTransition(double, double)}
 * records how often it is actually the binding candidate, and by how much.</li>
 * </ol>
 * <p>
 * <b>Off unless asked for.</b> Enabled by {@code -Dmirova.defectDiag=true}; with it unset every method here is a
 * predictable branch on a {@code static final boolean} and no counter is touched, so a production run is unaffected.
 * A report is printed at shutdown; {@code -Dmirova.defectDiagFile=<path>} additionally writes it as CSV, one row per
 * counter, which is what the cluster campaign collects.
 * </p>
 * <p>
 * The counters are process-wide and unsynchronised beyond the atomics, so they describe one replication and assume
 * one replication per process -- the same assumption {@code MergeGateDiagnostics} already makes, and the way the
 * campaigns and the local harness run.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public final class DefectDiagnostics
{

    /** Whether the diagnostics collect anything. */
    public static final boolean ENABLED = Boolean.getBoolean("mirova.defectDiag");

    /** Optional CSV output file, or {@code null} when only the stdout report is wanted. */
    private static final Path TARGET = resolveTarget();

    /** Swallowed exceptions, by site key. */
    private static final Map<String, AtomicLong> SWALLOWED = new ConcurrentHashMap<>();

    /**
     * Exception classes seen per site, so that a site firing for two different reasons is not read as one.
     * Values are joined with a slash; a site rarely sees more than two.
     */
    private static final Map<String, Map<String, AtomicLong>> SWALLOWED_CAUSES = new ConcurrentHashMap<>();

    /** Extended-look-ahead queries that returned normally and reset the parameter. */
    private static final AtomicLong LOOKAHEAD_RESTORED = new AtomicLong();

    /** Extended-look-ahead queries that threw, leaving the parameter raised for the rest of the vehicle's life. */
    private static final AtomicLong LOOKAHEAD_LEAKED = new AtomicLong();

    /** Evaluations of the speed-limit transition term. */
    private static final AtomicLong TRANSITION_CALLS = new AtomicLong();

    /** Evaluations in which the term produced a finite candidate at all. */
    private static final AtomicLong TRANSITION_FINITE = new AtomicLong();

    /** Evaluations in which the term was the binding candidate, i.e. removing it would change the acceleration. */
    private static final AtomicLong TRANSITION_BINDING = new AtomicLong();

    /** Summed absolute effect on the commanded acceleration, in micrometres per second squared. */
    private static final AtomicLong TRANSITION_EFFECT_UM = new AtomicLong();

    /** Largest single effect on the commanded acceleration, in micrometres per second squared. */
    private static final AtomicLong TRANSITION_EFFECT_MAX_UM = new AtomicLong();

    /** Effect above which a binding evaluation counts as materially changing the behaviour [m/s^2]. */
    private static final double TRANSITION_MATERIAL_EFFECT = 0.01;

    /** Binding evaluations whose effect exceeded {@link #TRANSITION_MATERIAL_EFFECT}. */
    private static final AtomicLong TRANSITION_MATERIAL = new AtomicLong();

    static
    {
        if (ENABLED)
        {
            Runtime.getRuntime().addShutdownHook(new Thread(DefectDiagnostics::report));
        }
    }

    /** Utility class, not instantiated. */
    private DefectDiagnostics()
    {
        //
    }

    /**
     * Resolves the optional CSV target from {@code -Dmirova.defectDiagFile}.
     * @return the output path, or {@code null} when the property is unset or the diagnostics are off
     */
    private static Path resolveTarget()
    {
        if (!Boolean.getBoolean("mirova.defectDiag"))
        {
            return null;
        }
        String value = System.getProperty("mirova.defectDiagFile");
        if (value == null || value.isBlank())
        {
            return null;
        }
        return Paths.get(value.trim());
    }

    // =========================================================================================
    // 1) The look-ahead leak
    // =========================================================================================

    /** Records an extended-look-ahead query that completed and reset the parameter. */
    public static void lookaheadRestored()
    {
        LOOKAHEAD_RESTORED.incrementAndGet();
    }

    /**
     * Records an extended-look-ahead query that escaped by exception, leaving {@code LOOKAHEAD} raised.
     * <p>
     * This is the defect itself: the vehicle keeps the extended look-ahead permanently, which widens every
     * perception range it feeds and changes the route desire it computes.
     * </p>
     */
    public static void lookaheadLeak()
    {
        LOOKAHEAD_LEAKED.incrementAndGet();
    }

    // =========================================================================================
    // 2) Swallowed exceptions
    // =========================================================================================

    /**
     * Records a caught exception that was replaced by a default value.
     * @param site String; a stable key naming the catch block, as {@code ClassName.method} with a numeric suffix
     *            where one method has several; the mapping to file and line is listed in
     *            {@code docs/decoupling/phase05-report.md}
     * @param cause Throwable; the exception that was swallowed, used only for its class name; may be {@code null}
     */
    public static void swallowed(final String site, final Throwable cause)
    {
        SWALLOWED.computeIfAbsent(site, k -> new AtomicLong()).incrementAndGet();
        String type = cause == null ? "none" : cause.getClass().getSimpleName();
        SWALLOWED_CAUSES.computeIfAbsent(site, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(type, k -> new AtomicLong()).incrementAndGet();
    }

    /**
     * Records a caught exception whose type is not available at the call site.
     * @param site String; a stable key naming the catch block
     */
    public static void swallowed(final String site)
    {
        swallowed(site, null);
    }

    // =========================================================================================
    // 3) The speed-limit transition term
    // =========================================================================================

    /**
     * Records one evaluation of the speed-limit transition term and whether it changed the answer.
     * @param withSi double; the commanded acceleration with the transition candidate included [m/s^2]
     * @param withoutSi double; the commanded acceleration the same tick would have produced without it [m/s^2]
     */
    public static void speedLimitTransition(final double withSi, final double withoutSi)
    {
        TRANSITION_CALLS.incrementAndGet();
        if (Double.isInfinite(withoutSi) && Double.isInfinite(withSi))
        {
            return;
        }
        double effect = Math.abs(withoutSi - withSi);
        if (effect <= 0.0 || Double.isNaN(effect))
        {
            return;
        }
        TRANSITION_BINDING.incrementAndGet();
        long effectUm = Math.round(effect * 1e6);
        TRANSITION_EFFECT_UM.addAndGet(effectUm);
        TRANSITION_EFFECT_MAX_UM.accumulateAndGet(effectUm, Math::max);
        if (effect > TRANSITION_MATERIAL_EFFECT)
        {
            TRANSITION_MATERIAL.incrementAndGet();
        }
    }

    /** Records that the transition term produced a finite candidate, whether or not it turned out to bind. */
    public static void speedLimitTransitionFinite()
    {
        TRANSITION_FINITE.incrementAndGet();
    }

    // =========================================================================================
    // Reporting
    // =========================================================================================

    /** Prints the collected counts, and writes them as CSV when a target file was named. */
    public static void report()
    {
        List<String> rows = new ArrayList<>();
        rows.add("counter,site,value,detail");

        long restored = LOOKAHEAD_RESTORED.get();
        long leaked = LOOKAHEAD_LEAKED.get();
        long lookaheadTotal = restored + leaked;
        System.out.println("[DEFECT] --- look-ahead leak (InfrastructureContext.distanceToLaneChangeExtendedLookahead)");
        if (lookaheadTotal == 0)
        {
            System.out.println("[DEFECT]   never called");
        }
        else
        {
            System.out.printf(Locale.ROOT, "[DEFECT]   calls %d, leaked %d (%.4f %%)%n",
                    lookaheadTotal, leaked, 100.0 * leaked / lookaheadTotal);
        }
        rows.add("lookahead,calls," + lookaheadTotal + ",");
        rows.add("lookahead,leaked," + leaked + ",");

        System.out.println("[DEFECT] --- speed-limit transition term (LongitudinalControl)");
        long calls = TRANSITION_CALLS.get();
        long finite = TRANSITION_FINITE.get();
        long binding = TRANSITION_BINDING.get();
        long material = TRANSITION_MATERIAL.get();
        if (calls == 0)
        {
            System.out.println("[DEFECT]   never evaluated");
        }
        else
        {
            System.out.printf(Locale.ROOT,
                    "[DEFECT]   evaluations %d, finite candidate %d (%.2f %%), binding %d (%.4f %%),"
                            + " effect > %.2f m/s2 in %d (%.4f %%)%n",
                    calls, finite, 100.0 * finite / calls, binding, 100.0 * binding / calls,
                    TRANSITION_MATERIAL_EFFECT, material, 100.0 * material / calls);
            if (binding > 0)
            {
                System.out.printf(Locale.ROOT, "[DEFECT]   mean effect when binding %.4f m/s2, largest %.4f m/s2%n",
                        TRANSITION_EFFECT_UM.get() / 1e6 / binding, TRANSITION_EFFECT_MAX_UM.get() / 1e6);
            }
        }
        rows.add("speedLimitTransition,evaluations," + calls + ",");
        rows.add("speedLimitTransition,finite," + finite + ",");
        rows.add("speedLimitTransition,binding," + binding + ",");
        rows.add("speedLimitTransition,material," + material + ",");
        rows.add("speedLimitTransition,meanEffectSi,"
                + (binding == 0 ? "0" : String.format(Locale.ROOT, "%.9f", TRANSITION_EFFECT_UM.get() / 1e6 / binding))
                + ",");
        rows.add("speedLimitTransition,maxEffectSi,"
                + String.format(Locale.ROOT, "%.9f", TRANSITION_EFFECT_MAX_UM.get() / 1e6) + ",");

        System.out.println("[DEFECT] --- swallowed exceptions, by site");
        if (SWALLOWED.isEmpty())
        {
            System.out.println("[DEFECT]   none fired");
        }
        else
        {
            SWALLOWED.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                    .forEach(e ->
                    {
                        String causes = causesOf(e.getKey());
                        System.out.printf(Locale.ROOT, "[DEFECT]   %-64s %10d   %s%n",
                                e.getKey(), e.getValue().get(), causes);
                    });
        }
        SWALLOWED.forEach((site, count) -> rows.add("swallowed," + site + "," + count.get() + "," + causesOf(site)));

        if (TARGET != null)
        {
            try
            {
                Path parent = TARGET.toAbsolutePath().getParent();
                if (parent != null)
                {
                    Files.createDirectories(parent);
                }
                Files.write(TARGET, String.join(System.lineSeparator(), rows).getBytes(StandardCharsets.UTF_8));
                System.out.println("[DEFECT] written to " + TARGET.toAbsolutePath());
            }
            catch (IOException exception)
            {
                throw new UncheckedIOException(exception);
            }
        }
    }

    /**
     * Renders the exception classes seen at one site.
     * @param site String; the site key
     * @return String; the class names with their counts, joined by a slash
     */
    private static String causesOf(final String site)
    {
        Map<String, AtomicLong> causes = SWALLOWED_CAUSES.get(site);
        if (causes == null || causes.isEmpty())
        {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        causes.forEach((type, count) ->
        {
            if (builder.length() > 0)
            {
                builder.append('/');
            }
            builder.append(type).append('=').append(count.get());
        });
        return builder.toString();
    }
}
