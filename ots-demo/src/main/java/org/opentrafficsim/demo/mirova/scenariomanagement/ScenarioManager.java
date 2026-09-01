package org.opentrafficsim.demo.mirova.scenariomanagement;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.*;

/**
 * ScenarioManager — orchestrates multi-run scenario simulations with multiple seeds,
 * optional parameter variations, output handling and parallel execution.
 */
public class ScenarioManager {

    /** Path to the root output directory. */
    private final File outputRoot;

    /** Registered scenarios with their parameter variations. */
    private final Map<String, ScenarioEntry> scenarios = new LinkedHashMap<>();

    /** Number of replications (= different seeds) per scenario configuration. */
    private int replications = 1;

    /** Constructs a ScenarioManager with the given output root directory.
     * @param outputRoot the root output directory for all scenarios
     */
    public ScenarioManager(final File outputRoot) {
        this.outputRoot = outputRoot;
        if (!outputRoot.exists())
        {
            outputRoot.mkdirs();
            System.out.println("Created new output directory: " + outputRoot.getAbsolutePath());
        }
    }

    // ------------------------------------------------------------
    // Registration API
    // ------------------------------------------------------------

    /** Adds a scenario to the manager.
     * @param name name of the scenario
     * @param generator scenario generator instance
     * */
    public void addScenario(final String name, final Class<? extends ScenarioGenerator> scenarioClass) {
        this.scenarios.put(name, new ScenarioEntry(scenarioClass));
    }

    /** Adds a parameter variation entry for the given scenario.
     * @param scenarioName name of the scenario
     * @param params parameter variation to add
     * */
    public void addParameterVariation(final String scenarioName, final ScenarioParameters params) {
        this.scenarios.get(scenarioName).parameterVariations.add(params);
    }

    /** Sets how many seeds to run per scenario-parameter set.
     * @param replications number of replications
     *
     *  */
    public void setReplications(final int replications) {
        this.replications = replications;
    }

    // ------------------------------------------------------------
    // Main execution logic
    // ------------------------------------------------------------

    /**
     * Environment variable that, when set to "1" or "true", suppresses the post-run Python plotting step. Intended for cluster
     * (non-Windows) execution where neither the virtual environment nor the plotting script is available.
     */
    public static final String ENV_SKIP_POSTPROCESSING = "MIROVA_SKIP_POSTPROCESSING";

    /** Environment variable overriding the Python executable used for post-run plotting. */
    public static final String ENV_PYTHON_EXECUTABLE = "MIROVA_PYTHON";

    /** Environment variable overriding the path of the post-run plotting script. */
    public static final String ENV_PLOT_SCRIPT = "MIROVA_PLOT_SCRIPT";

    /**
     * Interprets an environment variable value as a boolean flag. Accepted true values are "1", "true" and "yes"
     * (case-insensitive); anything else, including null, yields false.
     * @param value String; the raw environment variable value, may be null
     * @return boolean; true if the value denotes an enabled flag
     */
    public static boolean isTruthy(final String value)
    {
        if (value == null)
        {
            return false;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("1") || normalized.equals("true") || normalized.equals("yes");
    }

    /**
     * Returns the value of an environment variable, or the given fallback when the variable is unset or blank.
     * @param name String; the environment variable name
     * @param fallback String; the value to use when the variable is not set
     * @return String; the resolved value
     */
    private static String envOrDefault(final String name, final String fallback)
    {
        String value = System.getenv(name);
        return (value == null || value.trim().isEmpty()) ? fallback : value;
    }

    /**
     * Runs all scenarios including parameter variations &amp; replications. Post-processing is executed unless the environment
     * variable {@value #ENV_SKIP_POSTPROCESSING} requests otherwise; this preserves the historical behaviour of all existing
     * (local/Windows) call sites.
     * @param parallelThreads number of parallel workers
     * @param enableGUI whether to enable the GUI (currently always disabled for worker scripts)
     * @return boolean; true if all runs completed without errors
     * @throws InterruptedException when a worker thread is interrupted
     * @throws ExecutionException when a worker task fails
     * @throws SecurityException when the scenario generator cannot be instantiated reflectively
     * @throws NoSuchMethodException when the scenario generator has no default constructor
     * @throws InvocationTargetException when the scenario generator constructor throws
     * @throws IllegalArgumentException when the scenario generator constructor rejects the arguments
     * @throws IllegalAccessException when the scenario generator constructor is not accessible
     * @throws InstantiationException when the scenario generator cannot be instantiated
     */
    public boolean runAll(final int parallelThreads, final boolean enableGUI) throws InterruptedException, ExecutionException, InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException, NoSuchMethodException, SecurityException {
        return runAll(parallelThreads, enableGUI, isTruthy(System.getenv(ENV_SKIP_POSTPROCESSING)));
    }

    /**
     * Runs all scenarios including parameter variations &amp; replications, with explicit control over the post-run Python
     * plotting step.
     * @param parallelThreads number of parallel workers
     * @param enableGUI whether to enable the GUI (currently always disabled for worker scripts)
     * @param skipPostProcessing when true, the post-run Python plotting script is not invoked
     * @return boolean; true if all runs completed without errors
     * @throws InterruptedException when a worker thread is interrupted
     * @throws ExecutionException when a worker task fails
     * @throws SecurityException when the scenario generator cannot be instantiated reflectively
     * @throws NoSuchMethodException when the scenario generator has no default constructor
     * @throws InvocationTargetException when the scenario generator constructor throws
     * @throws IllegalArgumentException when the scenario generator constructor rejects the arguments
     * @throws IllegalAccessException when the scenario generator constructor is not accessible
     * @throws InstantiationException when the scenario generator cannot be instantiated
     */
    public boolean runAll(final int parallelThreads, final boolean enableGUI, final boolean skipPostProcessing) throws InterruptedException, ExecutionException, InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException, NoSuchMethodException, SecurityException {

        // The ordered enumeration of all runs; also the addressing basis of runByGlobalIndex(int)
        List<RunAddress> runs = enumerateRuns();
        final int totalRuns = runs.size();

        System.out.println("Starting ScenarioManager with " + parallelThreads + " parallel threads. Total runs to execute: " + totalRuns);
        org.opentrafficsim.road.network.factory.xml.parser.XmlParser.warmUpJAXBContext();
        ExecutorService pool = Executors.newFixedThreadPool(parallelThreads);
        CompletionService<Boolean> completionService = new ExecutorCompletionService<>(pool);

        // Map to collect error stack traces per variation directory in the background
        ConcurrentMap<File, List<String>> variationErrorsMap = new ConcurrentHashMap<>();

        // One folder per (scenario, variation), created on first use and shared by that variation's replications
        Map<String, File> variationFolders = new LinkedHashMap<>();

        try {
            for (RunAddress address : runs) {
                        File variationFolder = variationFolders.computeIfAbsent(address.variationKey(),
                                key -> prepareVariationFolder(address, false));

                        PreparedRun prepared =
                                prepareRun(address.generatorClass, address.variation, address.replicationIndex, variationFolder);
                        ScenarioSimulationScript script = prepared.script;

                        final File varFolder = variationFolder;
                        final long seedVal = prepared.seed;
                        final ClassLoader mainClassLoader = Thread.currentThread().getContextClassLoader();
                        completionService.submit(() -> {
                            try {
                                Thread.currentThread().setContextClassLoader(mainClassLoader);
                                script.start();
                                return true;
                            } catch (Throwable e) {
                                // Suppress verbose stack trace on standard error, collect it instead
                                StringBuilder sb = new StringBuilder();
                                Throwable rootCause = e.getCause() != null ? e.getCause() : e;
                                sb.append("Seed ").append(seedVal).append(" failed: ").append(rootCause.toString()).append("\n");
                                for (StackTraceElement element : rootCause.getStackTrace()) {
                                    sb.append("\tat ").append(element.toString()).append("\n");
                                }
                                if (rootCause.getCause() != null) {
                                    sb.append("Caused by: ").append(rootCause.getCause().toString()).append("\n");
                                    for (StackTraceElement element : rootCause.getCause().getStackTrace()) {
                                        sb.append("\tat ").append(element.toString()).append("\n");
                                    }
                                }
                                variationErrorsMap.computeIfAbsent(varFolder, k -> new CopyOnWriteArrayList<>()).add(sb.toString());
                                return false;
                            }
                        });
            }

            // Wait for all to finish and print progress on the main thread
            int completed = 0;
            int failed = 0;
            while (completed < totalRuns) {
                Future<Boolean> completedFuture = completionService.take();
                boolean success = false;
                try {
                    success = completedFuture.get();
                } catch (Throwable t) {
                    success = false;
                }
                completed++;
                if (!success) {
                    failed++;
                }
                System.out.println(String.format("[PROGRESS] %d/%d simulations completed (%d%%, %d failed)", completed, totalRuns, (completed * 100) / totalRuns, failed));
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
            pool.shutdownNow();
        }

        // Write collected errors to each variation's folder
        for (Map.Entry<File, List<String>> entryErr : variationErrorsMap.entrySet()) {
            File varFolder = entryErr.getKey();
            List<String> errors = entryErr.getValue();
            if (!errors.isEmpty()) {
                File errorsFile = new File(varFolder, "errors.txt");
                try (FileWriter writer = new FileWriter(errorsFile)) {
                    writer.write("Total failed runs: " + errors.size() + "\n\n");
                    for (String err : errors) {
                        writer.write(err);
                        writer.write("----------------------------------------\n\n");
                    }
                } catch (IOException e) {
                    System.err.println("Failed to write errors.txt to " + varFolder.getAbsolutePath() + ": " + e.getMessage());
                }
            }
        }

        System.out.println("All scenarios completed.");
        int totalFailed = 0;
        for (List<String> errs : variationErrorsMap.values()) {
            totalFailed += errs.size();
        }
        if (totalFailed > 0) {
            System.out.println("Warning: " + totalFailed + " runs failed with errors. Details written to errors.txt in the respective variation folders.");
        } else {
            System.out.println("Success: All runs completed without errors.");
        }

        // Run post-run plotting script automatically (unless explicitly skipped, e.g. on the cluster)
        if (skipPostProcessing)
        {
            System.out.println("[INFO] Post-run plotting script skipped.");
            return totalFailed == 0;
        }
        try
        {
            String pythonExe = envOrDefault(ENV_PYTHON_EXECUTABLE,
                    "D:\\Mitarbeitende\\gw2128\\repositories\\mirova\\venv\\Scripts\\python.exe");
            String scriptPath = envOrDefault(ENV_PLOT_SCRIPT,
                    "D:\\Mitarbeitende\\gw2128\\repositories\\diss_mvb\\scripts\\simulation\\ots\\plot_scenario_results.py");


            System.out.println("[INFO] Triggering post-run plotting script...");
            ProcessBuilder pb = new ProcessBuilder(pythonExe, scriptPath, "--output-dir", this.outputRoot.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream())))
            {
                String line;
                while ((line = reader.readLine()) != null)
                {
                    System.out.println("[Plot Script] " + line);
                }
            }
            int exitCode = process.waitFor();
            System.out.println("[INFO] Plotting script completed with exit code: " + exitCode);
        }
        catch (Exception e)
        {
            System.err.println("[WARNING] Failed to run post-run plotting script: " + e.getMessage());
        }
        return totalFailed == 0;
    }

    /**
     * Prepares one individual simulation run. This is the single place where the run seed is derived and the simulation
     * script is built; both {@link #runAll(int, boolean, boolean)} and {@link #runByGlobalIndex(int)} use it, so that a run
     * identified by (variation, replicationIndex) is bit-for-bit identically configured no matter which of the two execution
     * paths drives it.
     * @param genClass Class&lt;? extends ScenarioGenerator&gt;; the scenario generator class to instantiate
     * @param paramsVariation ScenarioParameters; the parameter variation for this run
     * @param replicationIndex int; the 0-based replication index within the variation
     * @param variationFolder File; the folder of the parameter variation; the run folder is created inside it
     * @return PreparedRun; the built simulation script together with its seed and run folder
     * @throws InstantiationException when the scenario generator cannot be instantiated
     * @throws IllegalAccessException when the scenario generator constructor is not accessible
     * @throws IllegalArgumentException when the scenario generator constructor rejects the arguments
     * @throws InvocationTargetException when the scenario generator constructor throws
     * @throws NoSuchMethodException when the scenario generator has no default constructor
     * @throws SecurityException when the scenario generator cannot be instantiated reflectively
     */
    private PreparedRun prepareRun(final Class<? extends ScenarioGenerator> genClass,
            final ScenarioParameters paramsVariation, final int replicationIndex, final File variationFolder)
            throws InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException,
            NoSuchMethodException, SecurityException {

        // → create NEW ScenarioGenerator instance
        ScenarioGenerator generator = genClass.getDeclaredConstructor().newInstance();
        ScenarioParameters defaultParams = generator.getDefaultParameters();
        // copy parameters
        ScenarioParameters runParams = paramsVariation.copy();
        long seed = seedFor(defaultParams, replicationIndex);
        runParams.setSeed(seed);
        // build output folder
        File runFolder = new File(variationFolder, "run_seed_" + seed);
        runFolder.mkdirs();

        generator.setOutputDirectory(runFolder);

        // Create SimulationScript
        ScenarioSimulationScript script =
                generator.buildSimulationScript(defaultParams.copy().applyOverridesFrom(runParams));

        script.setGuiEnabled(false);

        return new PreparedRun(script, seed, runFolder);
    }

    /**
     * Derives the seed of a single replication from the scenario generator's own default parameters, exactly as the
     * replication loop of {@link #runAll(int, boolean, boolean)} does. The base seed deliberately comes from the generator
     * defaults and not from the caller's parameter variation, matching the historical behaviour.
     * @param generatorDefaults ScenarioParameters; the default parameters of the scenario generator
     * @param replicationIndex int; the 0-based replication index
     * @return long; the seed of that replication
     */
    public static long seedFor(final ScenarioParameters generatorDefaults, final int replicationIndex) {
        return generatorDefaults.getSeed() + (long) replicationIndex * REPLICATION_SEED_SPACING;
    }

    /**
     * Distance between the seeds of consecutive replications.
     * <p>
     * Consecutive integers do not work here. The model registers its arrival-process stream as
     * {@code MersenneTwister(seed)} and a second stream as {@code MersenneTwister(seed + 1)}, so with a spacing of
     * one, replication <i>k</i>'s second stream is bit-identical to replication <i>k+1</i>'s arrival stream. The
     * replications of a cell then share sequences instead of being independent draws, which is precisely what the
     * paired seed statistics assume they are not.
     * </p>
     * <p>
     * A prime spacing keeps the seeds readable in run folder names, unlike a hashed seed, while putting far more
     * distance between them than any offset the model applies internally.
     * </p>
     */
    public static final long REPLICATION_SEED_SPACING = 1000003L;

    /**
     * Derives the seed of a single replication of the given scenario generator class, without running anything. Intended for
     * cluster entry points that execute one replication per process, and for verifying seed equivalence with the batched
     * execution path.
     * @param genClass Class&lt;? extends ScenarioGenerator&gt;; the scenario generator class
     * @param replicationIndex int; the 0-based replication index
     * @return long; the seed of that replication
     * @throws InstantiationException when the scenario generator cannot be instantiated
     * @throws IllegalAccessException when the scenario generator constructor is not accessible
     * @throws IllegalArgumentException when the scenario generator constructor rejects the arguments
     * @throws InvocationTargetException when the scenario generator constructor throws
     * @throws NoSuchMethodException when the scenario generator has no default constructor
     * @throws SecurityException when the scenario generator cannot be instantiated reflectively
     */
    public static long seedFor(final Class<? extends ScenarioGenerator> genClass, final int replicationIndex)
            throws InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException,
            NoSuchMethodException, SecurityException {
        return seedFor(genClass.getDeclaredConstructor().newInstance().getDefaultParameters(), replicationIndex);
    }

    /**
     * Returns the ordered enumeration of every run of all currently registered scenarios: for each scenario in registration
     * order, for each of its parameter variations in list order, for each replication index. This is the single definition of
     * the run order; both {@link #runAll(int, boolean, boolean)} and {@link #runByGlobalIndex(int)} walk it, so a global index
     * always addresses the same run as the corresponding position in a batched execution.
     * @return List&lt;RunAddress&gt;; the ordered list of all runs
     */
    private List<RunAddress> enumerateRuns() {
        List<RunAddress> runs = new ArrayList<>();
        for (Map.Entry<String, ScenarioEntry> entry : this.scenarios.entrySet()) {
            List<ScenarioParameters> variations = entry.getValue().parameterVariations;
            for (int variationIndex = 0; variationIndex < variations.size(); variationIndex++) {
                for (int replication = 0; replication < this.replications; replication++) {
                    runs.add(new RunAddress(entry.getKey(), entry.getValue().generatorClass,
                            variations.get(variationIndex), variationIndex, replication));
                }
            }
        }
        return runs;
    }

    /**
     * Returns the total number of runs over all registered scenarios, parameter variations and replications. This is the
     * number of SLURM array tasks needed to execute the study one run at a time; valid global indices are
     * {@code 0 .. countRuns() - 1}.
     * @return int; the total number of runs
     */
    public int countRuns() {
        return enumerateRuns().size();
    }

    /**
     * Returns a human-readable description of every run, in global-index order. Intended for generating an informational
     * manifest; execution never depends on it, since the authoritative mapping is the deterministic enumeration itself.
     * @return List&lt;String&gt;; one description line per run, indexed by global index
     * @throws InstantiationException when a scenario generator cannot be instantiated
     * @throws IllegalAccessException when a scenario generator constructor is not accessible
     * @throws IllegalArgumentException when a scenario generator constructor rejects the arguments
     * @throws InvocationTargetException when a scenario generator constructor throws
     * @throws NoSuchMethodException when a scenario generator has no default constructor
     * @throws SecurityException when a scenario generator cannot be instantiated reflectively
     */
    public List<String> describeRuns() throws InstantiationException, IllegalAccessException, IllegalArgumentException,
            InvocationTargetException, NoSuchMethodException, SecurityException {
        List<String> lines = new ArrayList<>();
        List<RunAddress> runs = enumerateRuns();
        for (int index = 0; index < runs.size(); index++) {
            RunAddress address = runs.get(index);
            long seed = seedFor(address.generatorClass, address.replicationIndex);
            lines.add(index + "\t" + address.scenarioName + "\tvariation_" + address.variationIndex + "\treplication="
                    + address.replicationIndex + "\tseed=" + seed + "\t" + address.variation);
        }
        return lines;
    }

    /**
     * Runs exactly one run of the enumeration in the calling thread, without any thread pool and without post-processing.
     * Intended for cluster execution where one SLURM array task corresponds to one simulation run on one core.
     * <p>
     * The run is configured through the same {@code prepareRun} path as the batched {@link #runAll(int, boolean, boolean)},
     * so seed and all derived parameters are identical to those the batched execution would produce for the same position in
     * the enumeration. The output folder layout ({@code <scenario>/<variation>/run_seed_<seed>}) is likewise unchanged,
     * except that the variation folder name is derived deterministically from the variation index instead of a random UUID,
     * so that concurrent array tasks of the same variation share one folder and a re-submitted task reuses it.
     * </p>
     * @param globalIndex int; the 0-based index into the run enumeration
     * @return boolean; true if the run completed without errors
     * @throws InstantiationException when the scenario generator cannot be instantiated
     * @throws IllegalAccessException when the scenario generator constructor is not accessible
     * @throws IllegalArgumentException when the scenario generator constructor rejects the arguments
     * @throws InvocationTargetException when the scenario generator constructor throws
     * @throws NoSuchMethodException when the scenario generator has no default constructor
     * @throws SecurityException when the scenario generator cannot be instantiated reflectively
     */
    public boolean runByGlobalIndex(final int globalIndex)
            throws InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException,
            NoSuchMethodException, SecurityException {

        List<RunAddress> runs = enumerateRuns();
        if (globalIndex < 0 || globalIndex >= runs.size()) {
            throw new IllegalArgumentException(
                    "Global run index " + globalIndex + " is out of range; this study has " + runs.size()
                            + " runs (valid indices 0.." + (runs.size() - 1) + ").");
        }

        RunAddress address = runs.get(globalIndex);
        // Writes runParams.txt for this variation, exactly as the batched path does
        File variationFolder = prepareVariationFolder(address, true);
        PreparedRun prepared =
                prepareRun(address.generatorClass, address.variation, address.replicationIndex, variationFolder);

        System.out.println("Starting run " + globalIndex + " of " + runs.size() + ": scenario=" + address.scenarioName
                + ", variation=" + address.variationIndex + ", replication=" + address.replicationIndex + ", seed="
                + prepared.seed);
        System.out.println("Run folder: " + prepared.runFolder.getAbsolutePath());

        try {
            prepared.script.start();
            System.out.println("[PROGRESS] 1/1 simulations completed (100%, 0 failed)");
            return true;
        } catch (Throwable e) {
            Throwable rootCause = e.getCause() != null ? e.getCause() : e;
            StringBuilder sb = new StringBuilder();
            sb.append("Seed ").append(prepared.seed).append(" failed: ").append(rootCause.toString()).append("\n");
            for (StackTraceElement element : rootCause.getStackTrace()) {
                sb.append("\tat ").append(element.toString()).append("\n");
            }
            System.err.println("[ERROR] " + sb);

            // Written into the run folder, not the shared variation folder: concurrent array tasks of the same
            // variation must not overwrite each other's error reports.
            File errorsFile = new File(prepared.runFolder, "errors.txt");
            try (FileWriter writer = new FileWriter(errorsFile)) {
                writer.write("Total failed runs: 1\n\n");
                writer.write(sb.toString());
            } catch (IOException ioException) {
                System.err.println("Failed to write errors.txt to " + prepared.runFolder.getAbsolutePath() + ": "
                        + ioException.getMessage());
            }
            return false;
        }
    }

    /**
     * Creates the output folder of a variation and writes its {@code runParams.txt} metadata, which downstream
     * post-processing uses to recover which parameters produced which output.
     * @param address RunAddress; the run whose variation folder is prepared
     * @param deterministicName boolean; when true the folder is named {@code variation_<variationIndex>} so that separate
     *            processes executing runs of the same variation agree on it; when false a random UUID is used, preserving the
     *            historical naming of the batched path
     * @return File; the variation folder
     */
    private File prepareVariationFolder(final RunAddress address, final boolean deterministicName) {
        File scenarioFolder = new File(this.outputRoot, address.scenarioName);
        scenarioFolder.mkdirs();
        File variationFolder = new File(scenarioFolder,
                deterministicName ? "variation_" + address.variationIndex : "variation_" + UUID.randomUUID().toString());
        variationFolder.mkdirs();

        // Save runParams as a text file in variationFolder. Written only when absent and moved into place atomically, so
        // that concurrent single-run processes sharing this folder cannot produce a torn file.
        File paramsFile = new File(variationFolder, "runParams.txt");
        if (!paramsFile.exists()) {
            try {
                File tempFile = File.createTempFile("runParams", ".tmp", variationFolder);
                try (FileWriter writer = new FileWriter(tempFile)) {
                    writer.write(address.variation.toString());
                }
                try {
                    java.nio.file.Files.move(tempFile.toPath(), paramsFile.toPath(),
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.FileAlreadyExistsException e) {
                    tempFile.delete(); // another process won the race; its content is identical
                }
            } catch (IOException e) {
                System.err.println("    [ERROR] Failed to write parameter variation: " + e.getMessage());
            }
        }
        return variationFolder;
    }

    /**
     * The address of one individual run within the enumeration of a study.
     */
    private static class RunAddress {

        /** The name of the scenario this run belongs to. */
        private final String scenarioName;

        /** The scenario generator class. */
        private final Class<? extends ScenarioGenerator> generatorClass;

        /** The parameter variation of this run. */
        private final ScenarioParameters variation;

        /** The 0-based index of the variation within its scenario. */
        private final int variationIndex;

        /** The 0-based replication index within the variation. */
        private final int replicationIndex;

        /**
         * Constructor.
         * @param scenarioName String; the name of the scenario this run belongs to
         * @param generatorClass Class&lt;? extends ScenarioGenerator&gt;; the scenario generator class
         * @param variation ScenarioParameters; the parameter variation of this run
         * @param variationIndex int; the 0-based index of the variation within its scenario
         * @param replicationIndex int; the 0-based replication index within the variation
         */
        RunAddress(final String scenarioName, final Class<? extends ScenarioGenerator> generatorClass,
                final ScenarioParameters variation, final int variationIndex, final int replicationIndex) {
            this.scenarioName = scenarioName;
            this.generatorClass = generatorClass;
            this.variation = variation;
            this.variationIndex = variationIndex;
            this.replicationIndex = replicationIndex;
        }

        /**
         * Returns the key identifying the variation this run belongs to; all replications of one variation share it.
         * @return String; the variation key
         */
        String variationKey() {
            return this.scenarioName + "#" + this.variationIndex;
        }
    }

    /**
     * An individual simulation run that has been fully configured but not yet started.
     */
    private static class PreparedRun {

        /** The configured simulation script. */
        private final ScenarioSimulationScript script;

        /** The seed of this run. */
        private final long seed;

        /** The output folder of this run. */
        private final File runFolder;

        /**
         * Constructor.
         * @param script ScenarioSimulationScript; the configured simulation script
         * @param seed long; the seed of this run
         * @param runFolder File; the output folder of this run
         */
        PreparedRun(final ScenarioSimulationScript script, final long seed, final File runFolder) {
            this.script = script;
            this.seed = seed;
            this.runFolder = runFolder;
        }
    }

    /**
     * Silences all verbose logging and warning/error prints from background execution threads,
     * allowing only the main thread to write to System.out and System.err.
     */
    public static void silenceBackgroundThreads() {
        System.setOut(new ThreadFilteringPrintStream(System.out));
        System.setErr(new ThreadFilteringPrintStream(System.err));
        // Configure tinylog to Level.ERROR to save CPU time on background log formatting
        org.pmw.tinylog.Configurator.defaultConfig()
            .level(org.pmw.tinylog.Level.ERROR)
            .activate();
    }

    /**
     * Internal structure to hold scenario and its parameter variations.
     */
    class ScenarioEntry {
        Class<? extends ScenarioGenerator> generatorClass;
        List<ScenarioParameters> parameterVariations = new ArrayList<>();

        /** Constructor.
         * @param clazz scenario generator class
         */
        ScenarioEntry(final Class<? extends ScenarioGenerator> clazz) {
            this.generatorClass = clazz;
        }
    }

    /**
     * A custom PrintStream that filters out console output originating from background threads,
     * ensuring only the main thread's progress messages are displayed.
     */
    private static class ThreadFilteringPrintStream extends java.io.PrintStream
    {
        private final java.io.PrintStream original;
        private final ThreadLocal<java.io.ByteArrayOutputStream> buffers = ThreadLocal.withInitial(java.io.ByteArrayOutputStream::new);

        public ThreadFilteringPrintStream(final java.io.PrintStream original)
        {
            super(original);
            this.original = original;
        }

        @Override
        public void write(final int b)
        {
            if (Thread.currentThread().getName().equals("main"))
            {
                this.original.write(b);
                return;
            }
            
            java.io.ByteArrayOutputStream buf = this.buffers.get();
            buf.write(b);
            if (b == '\n' || b == '\r')
            {
                flushThreadBuffer(buf);
            }
        }

        @Override
        public void write(final byte[] buf, final int off, final int len)
        {
            if (Thread.currentThread().getName().equals("main"))
            {
                this.original.write(buf, off, len);
                return;
            }
            
            java.io.ByteArrayOutputStream threadBuf = this.buffers.get();
            for (int i = 0; i < len; i++)
            {
                int b = buf[off + i];
                threadBuf.write(b);
                if (b == '\n' || b == '\r')
                {
                    flushThreadBuffer(threadBuf);
                }
            }
        }
        
        private void flushThreadBuffer(java.io.ByteArrayOutputStream threadBuf)
        {
            byte[] data = threadBuf.toByteArray();
            threadBuf.reset();
            if (data.length == 0)
            {
                return;
            }
            try
            {
                String s = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                if (s.contains("[SIM ") || s.contains("[PROGRESS]") || s.contains("[ERROR]") || s.contains("[WATCHDOG]") || s.contains("[OUTPUT]"))
                {
                    this.original.write(data);
                }
            }
            catch (Exception e)
            {
                // ignore
            }
        }
    }
}

