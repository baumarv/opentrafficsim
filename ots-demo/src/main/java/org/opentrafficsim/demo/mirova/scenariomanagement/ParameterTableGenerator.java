package org.opentrafficsim.demo.mirova.scenariomanagement;

import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.djunits.value.vdouble.scalar.base.DoubleScalar;
import org.opentrafficsim.base.parameters.ParameterSet;
import org.opentrafficsim.base.parameters.ParameterType;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.FreiburgProductionStudy;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;

/**
 * Writes {@code parametertable.tex} from the parameter set a production run actually resolves.
 * <p>
 * The point of generating it is that a parameter table in a paper is a claim about what ran, and the only way to keep
 * that claim true is to take it from the run rather than from a constant. Every value below is resolved through
 * {@link ScenarioGenerator#applyParameter} exactly as the simulation resolves it: the four override layers of the
 * production study are applied first, and a parameter no layer sets falls back to its declared default, which is then
 * marked as such.
 * </p>
 * <p>
 * Two tables are emitted. The first is the current model. The second is the {@code legacy} variant -- the
 * parameterisation the published results were produced with, which differs in the speed gain: the papers ran at
 * 15 m/s and 30 m/s, and the model now carries the intended 15 km/h and 30 km/h. A paper stating its parameters can
 * therefore state exactly what its own results used.
 * </p>
 * <p>
 * The generator does not touch any paper source. It writes one file, and nothing includes it automatically.
 * </p>
 * <pre>
 *   java ...ParameterTableGenerator [output.tex] [date] [demandDir]
 * </pre>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public final class ParameterTableGenerator
{
    /** Default output path. */
    private static final String DEFAULT_OUTPUT = "docs/mirova/parametertable.tex";

    /** Marker appended to a value that no override layer sets, so it is the declared default. */
    private static final String DEFAULT_MARK = "\\textsuperscript{d}";

    /** Marker appended to a parameter that cannot be read on its own. */
    private static final String COUPLED_MARK = "\\textsuperscript{$\\dagger$}";

    /** One row of the table. */
    private static final class Row
    {
        /** LaTeX symbol. */
        private final String symbol;

        /** What the parameter means, in a few words. */
        private final String meaning;

        /** The parameter itself. */
        private final ParameterType<?> type;

        /** Unit label for the printed value. */
        private final String unit;

        /** Factor from SI to the printed unit. */
        private final double fromSi;

        /** Where the value comes from. */
        private final String provenance;

        /** Whether the parameter is coupled to another and cannot be read alone. */
        private final boolean coupled;

        /**
         * Constructs a row.
         * @param symbol String; the LaTeX symbol
         * @param meaning String; what the parameter means
         * @param type ParameterType&lt;?&gt;; the parameter
         * @param unit String; the unit label of the printed value
         * @param fromSi double; the factor from SI to that unit
         * @param provenance String; where the value comes from
         * @param coupled boolean; whether it may not be read alone
         */
        private Row(final String symbol, final String meaning, final ParameterType<?> type, final String unit,
                final double fromSi, final String provenance, final boolean coupled)
        {
            this.symbol = symbol;
            this.meaning = meaning;
            this.type = type;
            this.unit = unit;
            this.fromSi = fromSi;
            this.provenance = provenance;
            this.coupled = coupled;
        }
    }

    /** The rows, in the order the table prints them. */
    private static final List<Row> ROWS = new ArrayList<>();

    static
    {
        // Desire thresholds
        ROWS.add(new Row("$d_\\mathrm{free}$", "Discretionary lane-change threshold", MirovaParameters.DFREE, "--", 1.0,
                "literature", false));
        ROWS.add(new Row("$d_\\mathrm{mand}$", "Mandatory lane-change threshold", MirovaParameters.DMAND, "--", 1.0,
                "literature", false));
        ROWS.add(new Row("$d_\\mathrm{search}$", "Active gap-search threshold", MirovaParameters.DSEARCH, "--", 1.0,
                "literature", false));
        // Speed
        ROWS.add(new Row("$v_\\mathrm{gain}$", "Speed difference at full desire", MirovaParameters.vGain, "km/h", 3.6,
                "intended value, not recalibrated", false));
        ROWS.add(new Row("$v_\\mathrm{cong}$", "Congestion speed threshold", ParameterTypes.VCONG, "km/h", 3.6,
                "literature", false));
        ROWS.add(new Row("$\\sigma$", "Socio-speed sensitivity", MirovaParameters.socioSpeedSensitivity, "--", 1.0,
                "assumption", false));
        // Car following
        ROWS.add(new Row("$T$", "Desired time headway", ParameterTypes.T, "s", 1.0, "calibration", true));
        ROWS.add(new Row("$s_0$", "Standstill gap", ParameterTypes.S0, "m", 1.0, "literature, calibrated", false));
        ROWS.add(new Row("$a$", "Desired acceleration", ParameterTypes.A, "m/s\\textsuperscript{2}", 1.0,
                "literature, calibrated", false));
        ROWS.add(new Row("$b$", "Comfortable deceleration", ParameterTypes.B, "m/s\\textsuperscript{2}", 1.0,
                "calibration", false));
        ROWS.add(new Row("$a_\\mathrm{max}$", "Acceleration ceiling at standstill", MirovaParameters.A_MAX,
                "m/s\\textsuperscript{2}", 1.0, "literature", false));
        // Cooperation and gap acceptance
        ROWS.add(new Row("$b_\\mathrm{coop}$", "Deceleration accepted to open a gap",
                MirovaParameters.cooperativeDecelerationThreshold, "m/s\\textsuperscript{2}", 1.0, "calibration",
                false));
        ROWS.add(new Row("$b_\\mathrm{pre}$", "Pre-emptive cooperative deceleration",
                MirovaParameters.preemptiveCooperativeDeceleration, "m/s\\textsuperscript{2}", 1.0, "assumption",
                false));
        ROWS.add(new Row("$f_\\mathrm{LC}$", "Safety-distance reduction while changing lane",
                MirovaParameters.safetyDistanceReductionFactorLaneChange, "--", 1.0, "calibration", false));
        ROWS.add(new Row("$b_\\mathrm{f,min}$", "Deceleration imposed on a follower, minimum urgency",
                MirovaParameters.minFollowerDecelerationThreshold, "m/s\\textsuperscript{2}", 1.0, "calibration",
                true));
        ROWS.add(new Row("$b_\\mathrm{f,max}$", "Deceleration imposed on a follower, maximum urgency",
                MirovaParameters.maxFollowerDecelerationThreshold, "m/s\\textsuperscript{2}", 1.0, "calibration",
                true));
        // Relaxation
        ROWS.add(new Row("$\\tau_\\mathrm{relax}$", "Relaxation time constant", MirovaParameters.RELAXATION_TAU_SPACE,
                "s", 1.0, "unsourced", false));
        ROWS.add(new Row("$\\gamma_\\mathrm{relax}$", "Relaxation acceleration damping",
                MirovaParameters.RELAXATION_ACC_DAMPING_FACTOR, "--", 1.0, "calibration", true));
        ROWS.add(new Row("$k_\\mathrm{relax}$", "Relaxation lifetime cap, in time constants",
                MirovaParameters.RELAXATION_MAX_LIFETIME_FACTOR, "--", 1.0, "assumption", false));
        // Ranges
        ROWS.add(new Row("$x_\\mathrm{coop}$", "Cooperation candidate scan range",
                MirovaParameters.considerGapOpeningLookaheadDistance, "m", 1.0, "literature", false));
        ROWS.add(new Row("$x_\\mathrm{ext}$", "Extended anticipation look-ahead",
                MirovaParameters.extendedLookAheadDistance, "m", 1.0, "literature", false));
        ROWS.add(new Row("$x_\\mathrm{stop}$", "Emergency-stop buffer distance",
                MirovaParameters.emergencyStoppingDistance, "m", 1.0, "literature", false));
        ROWS.add(new Row("$t_\\mathrm{undercut}$", "Undercutting time-to-collision threshold",
                MirovaParameters.undercuttingTTCThreshold, "s", 1.0, "literature", false));
    }

    /** Utility class. */
    private ParameterTableGenerator()
    {
        //
    }

    /**
     * Registers the production study and returns the parameter set of the requested variant.
     * @param variant String; {@code "production"} or {@code "legacy"}
     * @param date String; the date to register
     * @param demandDir String; the demand directory
     * @return ScenarioParameters; the resolved cell
     * @throws Exception when the study cannot be registered
     */
    @SuppressWarnings("unchecked")
    private static ScenarioParameters cellOf(final String variant, final String date, final String demandDir)
            throws Exception
    {
        ScenarioManager manager = new ScenarioManager(new File(System.getProperty("java.io.tmpdir"), "paramtable"));
        Map<String, String> options = new LinkedHashMap<>();
        options.put("dates", date);
        options.put("demand", demandDir);
        options.put("replications", "1");
        options.put("variants", variant);
        new FreiburgProductionStudy().register(manager, options);

        Field scenariosField = ScenarioManager.class.getDeclaredField("scenarios");
        scenariosField.setAccessible(true);
        Map<String, Object> scenarios = (Map<String, Object>) scenariosField.get(manager);
        Object entry = scenarios.values().iterator().next();
        Field variationsField = entry.getClass().getDeclaredField("parameterVariations");
        variationsField.setAccessible(true);
        return ((List<ScenarioParameters>) variationsField.get(entry)).get(0);
    }

    /**
     * Resolves one parameter for one vehicle class, exactly as the simulation resolves it.
     * @param cell ScenarioParameters; the registered cell
     * @param prefix String; {@code "car."} or {@code "truck."}
     * @param row Row; the row being printed
     * @return String; the formatted value, marked when it is the declared default
     * @throws Exception when the parameter cannot be applied
     */
    private static String resolve(final ScenarioParameters cell, final String prefix, final Row row) throws Exception
    {
        Object raw = null;
        for (Map.Entry<String, Object> e : cell.asUnmodifiableMap().entrySet())
        {
            if (e.getKey().equalsIgnoreCase(prefix + row.type.getId()))
            {
                raw = e.getValue();
                break;
            }
        }

        ParameterSet set = new ParameterSet();
        boolean isDefault = raw == null;
        if (isDefault)
        {
            set.setDefaultParameter(row.type);
        }
        else
        {
            ScenarioGenerator.applyParameter(set, row.type, raw);
        }
        Object value = set.getParameter(row.type);

        String text;
        if (value instanceof DoubleScalar)
        {
            text = format(((DoubleScalar<?, ?>) value).getSI() * row.fromSi);
        }
        else if (value instanceof Double)
        {
            text = format((Double) value);
        }
        else
        {
            text = String.valueOf(value);
        }
        return text + (isDefault ? DEFAULT_MARK : "");
    }

    /**
     * Formats a number without trailing noise.
     * @param v double; the value
     * @return String; the formatted value
     */
    private static String format(final double v)
    {
        double rounded = Math.round(v * 1000.0) / 1000.0;
        if (rounded == Math.rint(rounded) && Math.abs(rounded) < 1e6)
        {
            return String.valueOf((long) rounded);
        }
        return String.valueOf(rounded);
    }

    /**
     * Writes one table.
     * @param w PrintWriter; the output
     * @param cell ScenarioParameters; the registered cell
     * @param label String; the LaTeX label suffix
     * @param caption String; the caption
     * @throws Exception when a parameter cannot be resolved
     */
    private static void writeTable(final PrintWriter w, final ScenarioParameters cell, final String label,
            final String caption) throws Exception
    {
        w.println("\\begin{table}[htbp]");
        w.println("  \\centering");
        w.println("  \\caption{" + caption + "}");
        w.println("  \\label{tab:parameters" + label + "}");
        w.println("  \\begin{tabular}{llrrll}");
        w.println("    \\toprule");
        w.println("    Symbol & Meaning & Car & Truck & Unit & Provenance \\\\");
        w.println("    \\midrule");
        for (Row row : ROWS)
        {
            w.println("    " + row.symbol + (row.coupled ? COUPLED_MARK : "") + " & " + row.meaning + " & "
                    + resolve(cell, "car.", row) + " & " + resolve(cell, "truck.", row) + " & " + row.unit + " & "
                    + row.provenance + " \\\\");
        }
        w.println("    \\bottomrule");
        w.println("  \\end{tabular}");
        w.println();
        w.println("  \\vspace{2pt}");
        w.println("  \\footnotesize");
        w.println("  \\textsuperscript{d} declared default: no scenario layer sets this parameter.\\\\");
        w.println("  \\textsuperscript{$\\dagger$} coupled: $T$ and the relaxation damping $\\gamma_\\mathrm{relax}$");
        w.println("  were calibrated against each other and act on the same quantity, the discharge rate; lengthening");
        w.println("  the headway was what made switching the damping off admissible, and neither may be quoted or");
        w.println("  varied alone. The follower deceleration pair bounds one interpolation ramp and is likewise a");
        w.println("  pair, not two numbers.");
        w.println("\\end{table}");
    }

    /**
     * Writes the file.
     * @param args String[]; optional output path, date and demand directory
     * @throws Exception when the study cannot be registered or the file cannot be written
     */
    public static void main(final String[] args) throws Exception
    {
        String output = args.length > 0 ? args[0] : DEFAULT_OUTPUT;
        String date = args.length > 1 ? args[1] : "2025-09-16";
        String demandDir = args.length > 2 ? args[2] : "cluster/demand";

        ScenarioParameters production = cellOf(FreiburgProductionStudy.VARIANT_LABEL, date, demandDir);
        ScenarioParameters legacy = cellOf(FreiburgProductionStudy.LEGACY_LABEL, date, demandDir);

        File file = new File(output);
        if (file.getParentFile() != null)
        {
            file.getParentFile().mkdirs();
        }
        try (PrintWriter w = new PrintWriter(file, "UTF-8"))
        {
            w.println("% Generated by ParameterTableGenerator. Do not edit by hand.");
            w.println("% Values are resolved from the registered production run, not read off constants:");
            w.println("% the four override layers are applied and unset parameters fall back to their declared");
            w.println("% default, which is marked. Regenerate after any parameter change.");
            w.println("%");
            w.println("% Requires booktabs.");
            w.println();
            writeTable(w, production, "", "Parameters of the MiRoVA model as currently configured. The speed gain "
                    + "$v_\\mathrm{gain}$ carries its intended value and has not yet been recalibrated jointly with "
                    + "the parameters marked as coupled.");
            w.println();
            writeTable(w, legacy, "published", "Parameters behind the published results (TR-B, HEUREKA). Identical to "
                    + "the table above except for the speed gain, which the published runs used at 15 and 30 m/s "
                    + "(54 and 108\\,km/h); see the tag \\texttt{published-model} and the \\texttt{legacy} study "
                    + "variant.");
        }
        System.out.println("written: " + file.getAbsolutePath());
    }
}
