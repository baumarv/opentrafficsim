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
     * Runs all scenarios including parameter variations & replications.
     * @param parallelThreads number of parallel workers
     * @throws InterruptedException
     * @throws ExecutionException
     * @throws SecurityException
     * @throws NoSuchMethodException
     * @throws InvocationTargetException
     * @throws IllegalArgumentException
     * @throws IllegalAccessException
     * @throws InstantiationException
     */
    public boolean runAll(final int parallelThreads, final boolean enableGUI) throws InterruptedException, ExecutionException, InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException, NoSuchMethodException, SecurityException {

        // Calculate total tasks to run
        int totalTasks = 0;
        for (ScenarioEntry entry : this.scenarios.values()) {
            totalTasks += entry.parameterVariations.size() * this.replications;
        }
        final int totalRuns = totalTasks;

        System.out.println("Starting ScenarioManager with " + parallelThreads + " parallel threads. Total runs to execute: " + totalRuns);
        ExecutorService pool = Executors.newFixedThreadPool(parallelThreads);
        CompletionService<Boolean> completionService = new ExecutorCompletionService<>(pool);

        // Map to collect error stack traces per variation directory in the background
        ConcurrentMap<File, List<String>> variationErrorsMap = new ConcurrentHashMap<>();

        for (Map.Entry<String, ScenarioEntry> entry : this.scenarios.entrySet()) {
            String scenarioName = entry.getKey();
            Class<? extends ScenarioGenerator> genClass = entry.getValue().generatorClass;
            List<ScenarioParameters> variations = entry.getValue().parameterVariations;

            File scenarioFolder = new File(this.outputRoot, scenarioName);
            scenarioFolder.mkdirs();

            for (ScenarioParameters paramsVariation : variations) {
                // Create unique folder for this variation
                File variationFolder = new File(scenarioFolder, "variation_" + UUID.randomUUID().toString());
                variationFolder.mkdirs();
                // Save runParams as a text file in variationFolder
                File paramsFile = new File(variationFolder, "runParams.txt");
                try (FileWriter writer = new FileWriter(paramsFile)) {
                    writer.write(paramsVariation.toString());
                } catch (IOException e) {
                    System.err.println("    [ERROR] Failed to write parameter variation: " + e.getMessage());
                }

                for (int run = 0; run < this.replications; run++) {
                    // → create NEW ScenarioGenerator instance
                    ScenarioGenerator generator = genClass.getDeclaredConstructor().newInstance();
                    ScenarioParameters defaultParams = generator.getDefaultParameters();
                    // copy parameters
                    ScenarioParameters runParams = paramsVariation.copy();
                    long seed = defaultParams.getSeed() + run;
                    runParams.setSeed(seed);
                    // build output folder
                    File runFolder = new File(variationFolder, "run_seed_" + seed);
                    runFolder.mkdirs();

                    generator.setOutputDirectory(runFolder);

                    // Create SimulationScript
                    ScenarioSimulationScript script =
                            generator.buildSimulationScript(defaultParams.copy().applyOverridesFrom(runParams));

                    script.setGuiEnabled(false);

                    final File varFolder = variationFolder;
                    final long seedVal = seed;
                    completionService.submit(() -> {
                        try {
                            script.start();
                            return true;
                        } catch (Exception e) {
                            // Suppress verbose stack trace on standard error, collect it instead
                            StringBuilder sb = new StringBuilder();
                            sb.append("Seed ").append(seedVal).append(" failed: ").append(e.toString()).append("\n");
                            for (StackTraceElement element : e.getStackTrace()) {
                                sb.append("\tat ").append(element.toString()).append("\n");
                            }
                            variationErrorsMap.computeIfAbsent(varFolder, k -> new CopyOnWriteArrayList<>()).add(sb.toString());
                            return false;
                        }
                    });
                }
            }
        }

        // Wait for all to finish and print progress on the main thread
        int completed = 0;
        int failed = 0;
        while (completed < totalRuns) {
            Future<Boolean> completedFuture = completionService.take();
            boolean success = completedFuture.get();
            completed++;
            if (!success) {
                failed++;
            }
            System.out.println(String.format("[PROGRESS] %d/%d simulations completed (%d%%, %d failed)", completed, totalRuns, (completed * 100) / totalRuns, failed));
        }

        pool.shutdown();
        pool.awaitTermination(7, TimeUnit.DAYS);

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

        // Run post-run plotting script automatically
        try
        {
            String pythonExe = "D:\\Mitarbeitende\\gw2128\\repositories\\mirova\\venv\\Scripts\\python.exe";
            String scriptPath =
                    "D:\\Mitarbeitende\\gw2128\\repositories\\diss_mvb\\scripts\\simulation\\ots\\plot_scenario_results.py";
            
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

