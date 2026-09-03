package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;

import org.djunits.unit.DurationUnit;
import org.djunits.value.vdouble.scalar.Duration;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioGenerator;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioParameters;
import org.opentrafficsim.demo.mirova.scenariomanagement.ScenarioSimulationScript;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.BeliefLayer.RelaxationDiagnostics;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;

/**
 * Short interactive Freiburg-Nord run for visually inspecting merge behaviour on the on-ramp.
 * <p>
 * The point of this runner is to watch <i>how fast</i> vehicles are when they merge, and how that depends on the traffic state
 * on the main lanes. Slow merges are legitimate when the target lane is congested -- the merger simply has nothing to
 * synchronise with. They are not legitimate when the main lanes are flowing, because a real driver accelerates on the ramp
 * towards the speed of the traffic they are joining, largely irrespective of whether a gap happens to be available early. The
 * demand window is therefore chosen to pass through both regimes, so both cases can be observed in one run.
 * </p>
 * <h3>Configuration</h3>
 * <p>
 * Every value this run depends on is a constant in the CONFIGURATION block below: edit one, run again, watch what changes.
 * Nothing is read from system properties or arguments.
 * </p>
 * <p>
 * The defaults reproduce the calibration the studies use. Where {@link FreiburgStudyParameters} names a value, the constant
 * refers to it rather than repeating the number, so those defaults cannot silently drift away from the studies; the remaining
 * values are spelled out with the study baseline noted next to them. Bear in mind that this is a copy of the calibration made
 * editable, not the calibration itself -- {@link FreiburgStudyParameters#baseBehaviorParams()} remains the single source of
 * truth for what the studies actually measure, and a value changed here changes only this interactive run.
 * </p>
 * <p>
 * Two things are deliberately absent. The truck share and the traffic volume are not configurable, because for Freiburg-Nord
 * both come from the demand CSV via the OD matrix rather than from a parameter; setting them here would suggest an effect they
 * do not have. And {@code cooperativeLaneChangesEnabled} is set for trucks only, exactly as the baseline does, so that cars
 * keep the model default instead of silently acquiring an explicit setting the studies never gave them.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See OpenTrafficSim License.
 * </p>
 * @author Marvin Baumann
 */
public final class RunFreiburgMergeWatch
{
        // =================================================================================================================
        // CONFIGURATION -- edit anything below and re-run
        // =================================================================================================================

        // ---- Run -------------------------------------------------------------------------------------------------------

        /** Start of the demand window, {@code yyyy-MM-dd HH:mm:ss}. */
        private static final String DEMAND_START =
                        System.getProperty("mirova.demandStart", "2025-10-13 13:00:00");

        /** End of the demand window. Changing the window may trigger a fresh demand preparation, which takes a while. */
        private static final String DEMAND_END =
                        System.getProperty("mirova.demandEnd", "2025-10-13 16:00:00");

        /** Simulated duration in minutes, counted from the start of the demand window. */
        private static final double SIMULATED_MINUTES =
                        Double.parseDouble(System.getProperty("mirova.minutes", "60.0"));

        /** Random seed. Override with {@code -Dmirova.seed=<n>} to run several seeds in parallel. */
        private static final long SEED = Long.getLong("mirova.seed", 42L);

        /**
         * Whether to show the animation. Turning this off makes the run headless and much faster.
         * <p>
         * Override with {@code -Dmirova.gui=false} for batch runs. Note that the JVM does not terminate on its own
         * after such a run - AWT threads keep it alive even with the animation disabled - so a script that waits for
         * the process to exit will wait forever. Wait for the trajectory file instead and kill the process.
         * </p>
         */
        private static final boolean SHOW_GUI = Boolean.parseBoolean(System.getProperty("mirova.gui", "true"));

        /** Trajectory recording. Pure overhead while watching, needed only if the run is to be evaluated afterwards. */
        private static final boolean RECORD_TRAJECTORIES = true;

        /** Output directory. Override with {@code -Dmirova.outputDir=<path>} so parallel runs do not collide. */
        private static final String OUTPUT_DIR = System.getProperty("mirova.outputDir", "target/freiburg-merge-watch2");

        /** Demand aggregation interval in minutes. Study baseline. */
        private static final int DEMAND_AGGREGATION_MIN = FreiburgStudyParameters.AGGREGATION_MIN;

        /** Whether the demand profile is smoothed. Study baseline: false. */
        private static final boolean DEMAND_SMOOTH = false;

        // ---- Cars ------------------------------------------------------------------------------------------------------

        /** Desired time headway of cars [s]. Study baseline via FreiburgStudyParameters; the tighter sweep uses 0.90. */
        private static final double CAR_T = Double.parseDouble(
                        System.getProperty("mirova.carT", Double.toString(FreiburgStudyParameters.CAR_T)));

        /**
         * Leader deceleration at which an active relaxation is abandoned, in m/s^2.
         * <p>
         * Exposed so the safety of a longer-lived relaxation can be checked locally before a campaign spends cluster
         * time on it. The default is the framework value.
         * </p>
         */
        private static final double RELAXATION_ABORT_DECELERATION =
                        Double.parseDouble(System.getProperty("mirova.abortDecel", "-1.0"));

        /** Fade-out duration when a relaxation is abandoned, in s. Zero reproduces the original hard discard. */
        private static final double RELAXATION_FADE_SECONDS =
                        Double.parseDouble(System.getProperty("mirova.relaxFade", "1.0"));

        /** Longest a relaxation may run, in multiples of tau_s. A large value reproduces the original behaviour. */
        private static final double RELAXATION_MAX_LIFETIME =
                        Double.parseDouble(System.getProperty("mirova.relaxLife", "3.0"));

        /**
         * Maximum acceleration of cars, in m/s^2.
         * <p>
         * Exposed for the sensitivity screen. Trucks already had their own property; cars did not, so the value
         * could only be changed by editing {@link FreiburgStudyParameters}.
         * </p>
         */
        private static final double CAR_A = Double.parseDouble(
                        System.getProperty("mirova.carA", Double.toString(FreiburgStudyParameters.CAR_A)));

        /**
         * Stopped bumper-to-bumper distance of cars, in m.
         * <p>
         * Sets the jam density directly, and through it the speed the model settles at inside a queue - the
         * quantity the nightly campaign missed by 10 to 20 km/h across all eighteen cells.
         * </p>
         */
        private static final double CAR_S0 = Double.parseDouble(
                        System.getProperty("mirova.carS0", Double.toString(FreiburgStudyParameters.CAR_S0)));

        /**
         * Comfortable deceleration of both vehicle types, in m/s^2.
         * <p>
         * Untested so far. It governs how hard a vehicle brakes when its gap closes and therefore how deep a
         * disturbance cuts before it recovers, which makes it the most plausible untried lever on jam speed.
         * Applied to cars and trucks together: splitting it would double the cell count of the screen for a
         * distinction the field data cannot resolve.
         * </p>
         */
        private static final double COMFORTABLE_DECELERATION = Double.parseDouble(System.getProperty(
                        "mirova.b", Double.toString(FreiburgStudyParameters.COMFORTABLE_DECELERATION)));

        /** Speed gain of cars, driving the socio-speed sensitivity [km/h]. Study baseline: 15.0. */
        private static final double CAR_V_GAIN = 15.0;

        /** Maximum acceleration of cars [m/s^2]. Study baseline: 3.5. */
        private static final double CAR_A_MAX = 3.5;

        /** Deceleration a car accepts in order to cooperate with a merger [m/s^2]. Study baseline. */
        private static final double CAR_COOPERATIVE_DECELERATION_THRESHOLD = Double.parseDouble(System.getProperty(
                        "mirova.coopNear", Double.toString(FreiburgStudyParameters.CAR_COOPERATIVE_DECELERATION_THRESHOLD)));

        /**
         * Deceleration a car accepts in order to cooperate while the merger is still far from the lane end [m/s^2].
         * <p>
         * {@code GapOpenerPattern.getDynamicCooperativeDecelerationThreshold} interpolates linearly between this value,
         * which applies from {@code LOOKAHEAD} onwards, and {@link #CAR_COOPERATIVE_DECELERATION_THRESHOLD}, which
         * applies within 100 m of the lane end. Changing only one of the two moves the whole ramp, so a sweep has to
         * vary both. Override with {@code -Dmirova.coopFar=<value>}.
         * </p>
         */
        private static final double COOP_DECEL_FAR = Double.parseDouble(System.getProperty("mirova.coopFar", "-1.0"));

        /**
         * Deceleration a follower on the target lane is expected to accept at the lowest mandatory desire [m/s^2].
         * <p>
         * {@code EgoContext.computeFollowerDecelerationThreshold} interpolates between this value and
         * {@link #FOLLOWER_DECEL_MAX} over the lane-change desire above {@code DMAND}, so both ends bound the same ramp.
         * Override with {@code -Dmirova.folMin=<value>}.
         * </p>
         */
        private static final double FOLLOWER_DECEL_MIN = Double.parseDouble(System.getProperty("mirova.folMin",
                        Double.toString(FreiburgStudyParameters.CAR_FOLLOWER_DECELERATION_MIN)));

        /** Deceleration a follower is expected to accept at full desire [m/s^2]. Override {@code -Dmirova.folMax}. */
        private static final double FOLLOWER_DECEL_MAX = Double.parseDouble(System.getProperty("mirova.folMax",
                        Double.toString(FreiburgStudyParameters.CAR_FOLLOWER_DECELERATION_MAX)));

        /** Long-range anticipation for cars. Study baseline: false. */
        private static final boolean CAR_FAR_ANTICIPATION = false;

        /** Lane-change safety distance reduction factor of cars. Study baseline; the sweep also uses 0.50. */
        private static final double CAR_SAFETY_DISTANCE_FACTOR = 0.45;

        /** Capacity drop modelling for cars. Study baseline: false. */
        private static final boolean CAR_CAPACITY_DROP = false;

        /** Relaxation acceleration damping factor of cars. Study baseline: 0.80; the sweep also uses 0.60. */
        private static final double CAR_RELAXATION_DAMPING_FACTOR =
                        Double.parseDouble(System.getProperty("mirova.damping", "0.70"));

        /** Whether relaxation acceleration damping is active for cars. Study baseline: true. */
        private static final boolean CAR_RELAXATION_DAMPING_ENABLED = true;

        // ---- Trucks ----------------------------------------------------------------------------------------------------

        /** Desired time headway of trucks [s]. Study baseline via FreiburgStudyParameters; the tighter sweep uses 1.20. */
        private static final double TRUCK_T = Double.parseDouble(
                        System.getProperty("mirova.truckT", Double.toString(FreiburgStudyParameters.TRUCK_T)));

        /**
         * Car-following acceleration of trucks [m/s^2]. Override with {@code -Dmirova.truckA}.
         * <p>
         * IDM treats this as a ceiling, not as the acceleration a vehicle shows: the free term scales it down with
         * speed and the interaction term reduces it further while following. Field medians of 0.60 to 0.87 m/s^2 are
         * therefore not directly comparable - they average a process that starts higher - and they were measured
         * pulling away from a ramp meter, which is not this site.
         * </p>
         */
        private static final double TRUCK_A = Double.parseDouble(
                        System.getProperty("mirova.truckA", Double.toString(FreiburgStudyParameters.TRUCK_A)));

        /** Stopping distance of trucks [m]. Override with {@code -Dmirova.truckS0}. */
        private static final double TRUCK_S0 = Double.parseDouble(
                        System.getProperty("mirova.truckS0", Double.toString(FreiburgStudyParameters.TRUCK_S0)));

        /** Speed gain of trucks [km/h]. Study baseline: 30.0. */
        private static final double TRUCK_V_GAIN = 30.0;

        /** Maximum acceleration of trucks [m/s^2]. Study baseline: 1.3. */
        private static final double TRUCK_A_MAX = 1.3;

        /** Deceleration a truck accepts in order to cooperate with a merger [m/s^2]. Study baseline. */
        private static final double TRUCK_COOPERATIVE_DECELERATION_THRESHOLD =
                        FreiburgStudyParameters.TRUCK_COOPERATIVE_DECELERATION_THRESHOLD;

        /** Whether trucks perform cooperative lane changes at all. Study baseline: false. */
        private static final boolean TRUCK_COOPERATIVE_LANE_CHANGES = false;

        /** Long-range anticipation for trucks. Study baseline: false. */
        private static final boolean TRUCK_FAR_ANTICIPATION = false;

        /** Lane-change safety distance reduction factor of trucks. Study baseline; the sweep also uses 0.50. */
        private static final double TRUCK_SAFETY_DISTANCE_FACTOR = 0.45;

        /** Capacity drop modelling for trucks. Study baseline: false. */
        private static final boolean TRUCK_CAPACITY_DROP = false;

        /** Relaxation acceleration damping factor of trucks. Study baseline: 0.80; the sweep also uses 0.60. */
        private static final double TRUCK_RELAXATION_DAMPING_FACTOR =
                        Double.parseDouble(System.getProperty("mirova.damping", "0.70"));

        /** Whether relaxation acceleration damping is active for trucks. Study baseline: true. */
        private static final boolean TRUCK_RELAXATION_DAMPING_ENABLED = true;

        // =================================================================================================================
        // END OF CONFIGURATION
        // =================================================================================================================

        /** Utility class, not instantiated. */
        private RunFreiburgMergeWatch()
        {
        }

        /**
         * Starts the run.
         * @param args command line arguments, unused -- configure via the constants above
         * @throws Exception on simulation errors
         */
        public static void main(final String[] args) throws Exception
        {
                File outputDir = new File(OUTPUT_DIR);
                outputDir.mkdirs();

                ScenarioGenerator scenario = new FreiburgNord();
                scenario.setOutputDirectory(outputDir);

                // The scenario defaults carry the demand wiring and network settings but no behaviour parameters, so every
                // behavioural value the run uses is the one configured above.
                // Start from the study baseline so that every behavioural value it defines - including ones added
                // later - reaches this run, then apply the watch-specific deviations below. Before this, the block
                // below was a second, independent copy of the baseline and the two had already drifted apart.
                ScenarioParameters params = scenario.getDefaultParameters().copy();
                params.applyOverridesFrom(FreiburgStudyParameters.baseBehaviorParams());

                params.set("car." + ParameterTypes.T.getId(), CAR_T);
                params.set("car." + ParameterTypes.A.getId(), CAR_A);
                params.set("car." + ParameterTypes.S0.getId(), CAR_S0);
                params.set("car." + ParameterTypes.B.getId(), COMFORTABLE_DECELERATION);
                for (String type : new String[] {"car.", "truck."})
                {
                        params.set(type + MirovaParameters.RELAXATION_FADE_DURATION.getId(),
                                        Duration.instantiateSI(RELAXATION_FADE_SECONDS));
                        params.set(type + MirovaParameters.RELAXATION_MAX_LIFETIME_FACTOR.getId(),
                                        RELAXATION_MAX_LIFETIME);
                }
                params.set("car." + MirovaParameters.RELAXATION_ABORT_DECELERATION.getId(),
                                org.djunits.value.vdouble.scalar.Acceleration
                                                .instantiateSI(RELAXATION_ABORT_DECELERATION));
                params.set("truck." + MirovaParameters.RELAXATION_ABORT_DECELERATION.getId(),
                                org.djunits.value.vdouble.scalar.Acceleration
                                                .instantiateSI(RELAXATION_ABORT_DECELERATION));
                params.set("truck." + ParameterTypes.B.getId(), COMFORTABLE_DECELERATION);
                params.set("car." + MirovaParameters.vGain.getId(), CAR_V_GAIN);
                params.set("car." + MirovaParameters.A_MAX.getId(), CAR_A_MAX);
                params.set("car." + MirovaParameters.cooperativeDecelerationThreshold.getId(),
                                CAR_COOPERATIVE_DECELERATION_THRESHOLD);
                params.set("car." + MirovaParameters.farAnticipationEnabled.getId(), CAR_FAR_ANTICIPATION);
                params.set("car." + MirovaParameters.safetyDistanceReductionFactorLaneChange.getId(),
                                CAR_SAFETY_DISTANCE_FACTOR);
                params.set("car." + MirovaParameters.CAPACITY_DROP_ENABLED.getId(), CAR_CAPACITY_DROP);
                params.set("car." + MirovaParameters.RELAXATION_ACC_DAMPING_FACTOR.getId(), CAR_RELAXATION_DAMPING_FACTOR);
                params.set("car." + MirovaParameters.RELAXATION_ACC_DAMPING_ENABLED.getId(), CAR_RELAXATION_DAMPING_ENABLED);
                params.set("car." + MirovaParameters.preemptiveCooperativeDeceleration.getId(), COOP_DECEL_FAR);
                params.set("car." + MirovaParameters.minFollowerDecelerationThreshold.getId(), FOLLOWER_DECEL_MIN);
                params.set("car." + MirovaParameters.maxFollowerDecelerationThreshold.getId(), FOLLOWER_DECEL_MAX);

                params.set("truck." + ParameterTypes.T.getId(), TRUCK_T);
                params.set("truck." + ParameterTypes.A.getId(), TRUCK_A);
                params.set("truck." + ParameterTypes.S0.getId(), TRUCK_S0);
                params.set("truck." + MirovaParameters.vGain.getId(), TRUCK_V_GAIN);
                params.set("truck." + MirovaParameters.A_MAX.getId(), TRUCK_A_MAX);
                params.set("truck." + MirovaParameters.cooperativeDecelerationThreshold.getId(),
                                TRUCK_COOPERATIVE_DECELERATION_THRESHOLD);
                params.set("truck." + MirovaParameters.cooperativeLaneChangesEnabled.getId(), TRUCK_COOPERATIVE_LANE_CHANGES);
                params.set("truck." + MirovaParameters.farAnticipationEnabled.getId(), TRUCK_FAR_ANTICIPATION);
                params.set("truck." + MirovaParameters.safetyDistanceReductionFactorLaneChange.getId(),
                                TRUCK_SAFETY_DISTANCE_FACTOR);
                params.set("truck." + MirovaParameters.CAPACITY_DROP_ENABLED.getId(), TRUCK_CAPACITY_DROP);
                params.set("truck." + MirovaParameters.RELAXATION_ACC_DAMPING_FACTOR.getId(), TRUCK_RELAXATION_DAMPING_FACTOR);
                params.set("truck." + MirovaParameters.RELAXATION_ACC_DAMPING_ENABLED.getId(),
                                TRUCK_RELAXATION_DAMPING_ENABLED);

                params.setSeed(SEED);

                params.set("demandStartDate", DEMAND_START);
                params.set("demandEndDate", DEMAND_END);
                params.set("demandAggregation", DEMAND_AGGREGATION_MIN);
                params.set("demandSmooth", DEMAND_SMOOTH);

                // Only a slice of the demand window is simulated -- this run is for looking, not for producing statistics.
                params.setSimulationTime(new Duration(SIMULATED_MINUTES, DurationUnit.MINUTE));
                params.set("enableTrajectoryRecording", RECORD_TRAJECTORIES);

                printConfiguration();

                ScenarioSimulationScript script = scenario.buildSimulationScript(params);
                script.setGuiEnabled(SHOW_GUI);
                script.start();

                // The JVM does not exit on its own after a headless run - AWT threads keep it alive - so a shutdown
                // hook would never fire. Report here instead, once the run has finished writing its output.
                RelaxationDiagnostics.report();
        }

        /**
         * Writes the active configuration to the console, so that a screenshot of a run can be traced back to its settings.
         */
        private static void printConfiguration()
        {
                System.out.println("[MergeWatch] window " + DEMAND_START + " .. " + DEMAND_END + ", " + SIMULATED_MINUTES
                                + " min simulated, seed " + SEED + ", gui=" + SHOW_GUI);
                System.out.println("[MergeWatch] cooperation: near=" + CAR_COOPERATIVE_DECELERATION_THRESHOLD + ", far="
                                + COOP_DECEL_FAR + " | follower: min=" + FOLLOWER_DECEL_MIN + ", max=" + FOLLOWER_DECEL_MAX);
                System.out.println("[MergeWatch] truck: a=" + TRUCK_A + ", T=" + TRUCK_T + ", s0=" + TRUCK_S0);
                System.out.println("[MergeWatch] relaxation abort at " + RELAXATION_ABORT_DECELERATION
                                + " m/s2, fade " + RELAXATION_FADE_SECONDS + " s, max life "
                                + RELAXATION_MAX_LIFETIME + " x tau_s");
                System.out.println("[MergeWatch] screen: b=" + COMFORTABLE_DECELERATION
                                + ", carA=" + CAR_A + ", carS0=" + CAR_S0);
                System.out.println("[MergeWatch] car:   T=" + CAR_T + "s, vGain=" + CAR_V_GAIN + ", aMax=" + CAR_A_MAX
                                + ", coopDecel=" + CAR_COOPERATIVE_DECELERATION_THRESHOLD + ", safetyDist="
                                + CAR_SAFETY_DISTANCE_FACTOR + ", damping=" + CAR_RELAXATION_DAMPING_FACTOR + " (enabled="
                                + CAR_RELAXATION_DAMPING_ENABLED + "), farAnticipation=" + CAR_FAR_ANTICIPATION
                                + ", capacityDrop=" + CAR_CAPACITY_DROP);
                System.out.println("[MergeWatch] truck: T=" + TRUCK_T + "s, vGain=" + TRUCK_V_GAIN + ", aMax=" + TRUCK_A_MAX
                                + ", coopDecel=" + TRUCK_COOPERATIVE_DECELERATION_THRESHOLD + ", safetyDist="
                                + TRUCK_SAFETY_DISTANCE_FACTOR + ", damping=" + TRUCK_RELAXATION_DAMPING_FACTOR + " (enabled="
                                + TRUCK_RELAXATION_DAMPING_ENABLED + "), farAnticipation=" + TRUCK_FAR_ANTICIPATION
                                + ", capacityDrop=" + TRUCK_CAPACITY_DROP + ", cooperativeLaneChanges="
                                + TRUCK_COOPERATIVE_LANE_CHANGES);
        }
}
