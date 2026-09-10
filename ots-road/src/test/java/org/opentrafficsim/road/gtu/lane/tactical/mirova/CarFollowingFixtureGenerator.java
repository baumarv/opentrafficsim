package org.opentrafficsim.road.gtu.lane.tactical.mirova;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.djunits.unit.AccelerationUnit;
import org.djunits.unit.DurationUnit;
import org.djunits.unit.LengthUnit;
import org.djunits.unit.SpeedUnit;
import org.djunits.value.vdouble.scalar.Acceleration;
import org.djunits.value.vdouble.scalar.Duration;
import org.djunits.value.vdouble.scalar.Length;
import org.djunits.value.vdouble.scalar.Speed;
import org.junit.jupiter.api.Test;
import org.opentrafficsim.base.parameters.ParameterSet;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.base.parameters.Parameters;
import org.opentrafficsim.road.gtu.lane.perception.PerceptionIterable;
import org.opentrafficsim.road.gtu.lane.tactical.following.AbstractIdm;
import org.opentrafficsim.road.gtu.lane.perception.PerceptionIterableSet;
import org.opentrafficsim.road.gtu.lane.perception.headway.Headway;
import org.opentrafficsim.core.definitions.DefaultsNl;
import org.opentrafficsim.road.gtu.lane.perception.headway.HeadwayGtuSimple;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.MirovaParameters;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ReactiveLayer.CapacityDrop;
import org.opentrafficsim.road.gtu.lane.tactical.mirova.core.ReactiveLayer.MirovaIdmPlus;
import org.opentrafficsim.road.network.speed.SpeedLimitInfo;
import org.opentrafficsim.road.network.speed.SpeedLimitTypes;

/**
 * Drives the stage 2 MiRoVA reactive-layer classes through fixed inputs and writes what they
 * answered, so that the Kotlin port can be asserted to reproduce it.
 * <p>
 * <b>Test scope only.</b> Nothing in the production tree is touched, read or changed.
 * </p>
 * <p>
 * <b>Why this one is different from the stage 0 generator.</b> The Kotlin IDM+ is written from the
 * published model (Schakel et al.), deliberately <i>not</i> from {@code MirovaIdmPlus} or
 * {@code AbstractIdm}. So these fixtures are not a transcription check - they are a comparison
 * between the published equations and this implementation of them, and a disagreement is a finding
 * rather than a porting bug. Two disagreements are known in advance and the cases are laid out to
 * separate them:
 * </p>
 * <ul>
 * <li>{@code MirovaIdmPlus} applies a <b>comfort filter</b> below {@code bCrit} that is not part of
 * IDM+. The {@code idm_plus} cases keep the raw model above that threshold so the pure equations can
 * be compared; the {@code comfort_filter} cases cross it deliberately.</li>
 * <li>{@code AbstractIdm} floors the free term at {@code -b0} = 0.5 m/s², which the published model
 * does not and the contract has no key for. The {@code above_desired_speed} cases record what the
 * Java model does there, and the Kotlin side asserts the published value instead.</li>
 * </ul>
 * <p>
 * Parameters are the <b>coreset</b> values, which for the reactive layer are the production car
 * defaults; none of the five coreset switches reaches this code. The capacity-drop cases are the one
 * exception and say so: {@code capDropOn} is false in every campaign, so a fixture generated at the
 * coreset would leave the entire mechanism untested. Those cases turn it on explicitly and are
 * labelled outside the coreset.
 * </p>
 * <p>
 * Run it with:
 * </p>
 *
 * <pre>
 * mvn -o -pl ots-road test -Dtest=CarFollowingFixtureGenerator \
 *     -Dmirova.golden.out=/path/to/tama-core/src/test/resources/golden
 * </pre>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class CarFollowingFixtureGenerator
{

    /** Where the fixtures are written. */
    private static final Path OUT = Paths.get(System.getProperty("mirova.golden.out", "target/golden"));

    /** The commit the fixtures were generated from. */
    private static final String SOURCE_COMMIT = "e93306f958aa228c9672c87cfb8182468dd99a1e";

    /** The parameter set, recorded in every file. */
    private static final String SWITCHES = "coreset; the production car defaults, no switch reaches the reactive layer";

    /** The model under test. */
    private final MirovaIdmPlus model = new MirovaIdmPlus();

    /**
     * The production car parameter set, resolved.
     * <p>
     * Written out here rather than taken from a study class, so that the fixture states its own
     * inputs and a later change to a study cannot silently move them. The values are
     * {@code default-parameters.md} §1 and §2.
     * </p>
     * @return the parameters
     */
    private static Parameters carParameters()
    {
        ParameterSet parameters = new ParameterSet();
        parameters.setDefaultParameters(ParameterTypes.class);
        parameters.setDefaultParameters(MirovaParameters.class);
        // AbstractIdm declares its own two: the flattening exponent, and the floor on the free
        // term that the published model does not have.
        parameters.setDefaultParameters(AbstractIdm.class);
        setSi(parameters, ParameterTypes.T, new Duration(1.1, DurationUnit.SECOND));
        setSi(parameters, ParameterTypes.S0, new Length(3.0, LengthUnit.METER));
        setSi(parameters, ParameterTypes.A, new Acceleration(1.4, AccelerationUnit.SI));
        setSi(parameters, ParameterTypes.B, new Acceleration(1.75, AccelerationUnit.SI));
        return parameters;
    }

    /**
     * Sets one parameter, wrapping the checked exception the OTS API declares.
     * @param parameters Parameters; the set
     * @param type org.opentrafficsim.base.parameters.ParameterType&lt;T&gt;; the parameter
     * @param value T; the value
     * @param <T> the value type
     */
    private static <T> void setSi(final ParameterSet parameters,
            final org.opentrafficsim.base.parameters.ParameterType<T> type, final T value)
    {
        try
        {
            parameters.setParameter(type, value);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("cannot set " + type.getId(), e);
        }
    }

    /**
     * A speed limit info carrying one legal limit.
     * @param limitKmh double; the limit in km/h
     * @return SpeedLimitInfo; the info
     */
    private static SpeedLimitInfo speedLimit(final double limitKmh)
    {
        SpeedLimitInfo info = new SpeedLimitInfo();
        info.addSpeedInfo(SpeedLimitTypes.FIXED_SIGN, new Speed(limitKmh, SpeedUnit.KM_PER_HOUR));
        info.addSpeedInfo(SpeedLimitTypes.MAX_VEHICLE_SPEED, new Speed(200.0, SpeedUnit.KM_PER_HOUR));
        return info;
    }

    /**
     * A single-leader perception set at the given gap and speed.
     * @param gapM double; the bumper-to-bumper gap in m
     * @param leaderSpeedMs double; the leader's speed in m/s
     * @return PerceptionIterable; the leaders
     */
    private static PerceptionIterable<Headway> leader(final double gapM, final double leaderSpeedMs)
    {
        try
        {
            Headway headway = (Headway) new HeadwayGtuSimple("leader", DefaultsNl.CAR, new Length(gapM, LengthUnit.METER),
                    new Length(4.0, LengthUnit.METER), new Length(1.8, LengthUnit.METER),
                    new Speed(leaderSpeedMs, SpeedUnit.SI), Acceleration.ZERO,
                    new Speed(leaderSpeedMs, SpeedUnit.SI));
            return new PerceptionIterableSet<Headway>(headway);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("cannot build a leader at " + gapM + " m", e);
        }
    }

    // ------------------------------------------------------------------
    // IDM+
    // ------------------------------------------------------------------

    /**
     * Writes {@code idm_plus.json}.
     * <p>
     * A sweep over speed, gap and leader speed, with the desired speed held at the legal limit. The
     * cases are grouped so that each group isolates one question.
     * </p>
     * @throws IOException when the fixture cannot be written
     */
    @Test
    public void writeIdmPlusFixture() throws IOException
    {
        Parameters parameters = carParameters();
        SpeedLimitInfo limit = speedLimit(120.0);
        double desiredSpeedMs = desiredSpeedOf(parameters, limit);

        List<String> free = new ArrayList<>();
        for (double v : new double[] {0.0, 5.0, 15.0, 25.0, 30.0, 33.0, desiredSpeedMs})
        {
            free.add(evaluateFree(parameters, limit, v, desiredSpeedMs));
        }

        // Above the desired speed, where AbstractIdm floors the free term at -b0 and the published
        // model does not. Recorded so the divergence is measured rather than argued about.
        List<String> aboveDesired = new ArrayList<>();
        for (double v : new double[] {desiredSpeedMs + 1.0, desiredSpeedMs + 5.0, desiredSpeedMs + 15.0})
        {
            aboveDesired.add(evaluateFree(parameters, limit, v, desiredSpeedMs));
        }

        // Following, in situations mild enough that the comfort filter does not fire.
        List<String> following = new ArrayList<>();
        double[][] cases = {
                {25.0, 100.0, 25.0}, {25.0, 50.0, 25.0}, {25.0, 33.0, 25.0}, {25.0, 40.0, 20.0},
                {25.0, 60.0, 30.0}, {25.0, 200.0, 10.0}, {10.0, 15.0, 10.0}, {5.0, 10.0, 8.0},
                {0.0, 3.0, 0.0}, {0.0, 10.0, 0.0}, {30.0, 80.0, 33.0}, {20.0, 25.0, 22.0},
                {15.0, 18.0, 20.0}, {25.0, 45.0, 27.0}, {33.0, 120.0, 33.0},
        };
        for (double[] c : cases)
        {
            following.add(evaluateFollowing(parameters, limit, c[0], c[1], c[2], desiredSpeedMs));
        }

        // A faster leader, where the dynamic term goes negative and the floor decides.
        List<String> fasterLeader = new ArrayList<>();
        for (double[] c : new double[][] {{20.0, 10.0, 35.0}, {25.0, 8.0, 40.0}, {10.0, 5.0, 30.0}})
        {
            fasterLeader.add(evaluateFollowing(parameters, limit, c[0], c[1], c[2], desiredSpeedMs));
        }

        // Deliberately across bCrit, where the comfort filter is what answers.
        List<String> comfortFilter = new ArrayList<>();
        // Two branches to reach, and they are easy to conflate. Below bCrit the filter asks what
        // the gap physically requires: if bCrit suffices it returns bCrit, and only if the physics
        // demand more does it hand over the requirement. The first four cases below close on the
        // leader slowly at a short gap, so IDM+ diverges but the kinematics are mild - that is the
        // bCrit branch. The rest close fast, and the kinematics take over.
        for (double[] c : new double[][] {{25.0, 5.0, 24.0}, {20.0, 4.0, 19.5}, {15.0, 4.0, 15.0},
                {25.0, 6.0, 25.0}, {30.0, 10.0, 5.0}, {30.0, 6.0, 0.0}, {25.0, 4.0, 0.0},
                {33.0, 20.0, 10.0}, {30.0, 3.5, 0.0}, {30.0, 2.0, 0.0}, {20.0, 3.1, 0.0}})
        {
            comfortFilter.add(evaluateFollowing(parameters, limit, c[0], c[1], c[2], desiredSpeedMs));
        }

        // The headway factor, which used to be a parameter mutation.
        List<String> headwayFactors = new ArrayList<>();
        for (double factor : new double[] {1.0, 0.75, 0.5, 0.25})
        {
            Parameters reduced = carParameters();
            setSi((ParameterSet) reduced, ParameterTypes.T, new Duration(1.1 * factor, DurationUnit.SECOND));
            String rendered = evaluateFollowing(reduced, limit, 25.0, 33.0, 25.0, desiredSpeedMs);
            headwayFactors.add(rendered.substring(0, rendered.length() - 1) + ", \"headwayFactor\": " + factor + "}");
        }

        String body = "{\n  " + field("generator", "CarFollowingFixtureGenerator.writeIdmPlusFixture") + ",\n  "
                + field("sourceCommit", SOURCE_COMMIT) + ",\n  " + field("switches", SWITCHES) + ",\n  "
                + field("description", "MirovaIdmPlus: free flow, following, the comfort filter and the headway factor")
                + ",\n  " + num("desiredSpeedMs", desiredSpeedMs) + ",\n  " + arr("free", free) + ",\n  "
                + arr("aboveDesiredSpeed", aboveDesired) + ",\n  " + arr("following", following) + ",\n  "
                + arr("fasterLeader", fasterLeader) + ",\n  " + arr("comfortFilter", comfortFilter) + ",\n  "
                + arr("headwayFactors", headwayFactors) + "\n}\n";
        writeRaw("idm_plus.json", body);
    }

    /**
     * The desired speed the model derives from the limit, with the checked exception wrapped.
     * @param parameters Parameters; the parameter set
     * @param limit SpeedLimitInfo; the speed limit
     * @return double; the desired speed in m/s
     */
    private double desiredSpeedOf(final Parameters parameters, final SpeedLimitInfo limit)
    {
        try
        {
            return model.desiredSpeed(parameters, limit).si;
        }
        catch (Exception e)
        {
            throw new IllegalStateException("cannot derive the desired speed", e);
        }
    }

    /**
     * Evaluates the free-flow acceleration and renders it.
     * @param parameters Parameters; the parameter set
     * @param limit SpeedLimitInfo; the speed limit
     * @param speedMs double; the ego speed in m/s
     * @param desiredSpeedMs double; the desired speed in m/s
     * @return the case as a JSON object
     */
    private String evaluateFree(final Parameters parameters, final SpeedLimitInfo limit, final double speedMs,
            final double desiredSpeedMs)
    {
        try
        {
            Acceleration a = model.followingAcceleration(parameters, new Speed(speedMs, SpeedUnit.SI), limit,
                    new PerceptionIterableSet<Headway>());
            return obj(num("speedMs", speedMs), num("desiredSpeedMs", desiredSpeedMs),
                    num("accelerationMs2", a.si));
        }
        catch (Exception e)
        {
            throw new IllegalStateException("free acceleration at v=" + speedMs, e);
        }
    }

    /**
     * Evaluates the following acceleration and renders it.
     * @param parameters Parameters; the parameter set
     * @param limit SpeedLimitInfo; the speed limit
     * @param speedMs double; the ego speed in m/s
     * @param gapM double; the gap in m
     * @param leaderSpeedMs double; the leader speed in m/s
     * @param desiredSpeedMs double; the desired speed in m/s
     * @return the case as a JSON object
     */
    private String evaluateFollowing(final Parameters parameters, final SpeedLimitInfo limit, final double speedMs,
            final double gapM, final double leaderSpeedMs, final double desiredSpeedMs)
    {
        try
        {
            Acceleration a = model.followingAcceleration(parameters, new Speed(speedMs, SpeedUnit.SI), limit,
                    leader(gapM, leaderSpeedMs));
            return obj(num("speedMs", speedMs), num("gapM", gapM), num("leaderSpeedMs", leaderSpeedMs),
                    num("desiredSpeedMs", desiredSpeedMs), num("accelerationMs2", a.si));
        }
        catch (Exception e)
        {
            throw new IllegalStateException("following at v=" + speedMs + " s=" + gapM, e);
        }
    }

    // ------------------------------------------------------------------
    // Capacity drop
    // ------------------------------------------------------------------

    /**
     * Writes {@code capacity_drop.json}.
     * <p>
     * <b>Outside the coreset.</b> {@code capDropOn} is false in every campaign, so these cases turn
     * it on explicitly; a fixture generated at the coreset would leave the whole mechanism untested.
     * Both formulations are covered, and so is the rule that they never stack.
     * </p>
     * @throws Exception when a parameter cannot be set
     */
    @Test
    public void writeCapacityDropFixture() throws Exception
    {
        List<String> absolute = new ArrayList<>();
        ParameterSet on = (ParameterSet) carParameters();
        on.setParameter(MirovaParameters.CAPACITY_DROP_ENABLED, true);
        on.setParameter(MirovaParameters.T_DISCHARGE_ADDON, new Duration(0.5, DurationUnit.SECOND));
        on.setParameter(MirovaParameters.V_CRIT_DISCHARGE, new Speed(40.0, SpeedUnit.KM_PER_HOUR));
        for (double v : new double[] {0.0, 2.0, 5.0, 11.0, 11.111111111111112, 12.0, 25.0})
        {
            absolute.add(obj(num("speedMs", v), num("headwayInS", 1.1),
                    num("headwayOutS", CapacityDrop.absoluteHeadwayTime(on, null, v, 1.1))));
        }

        // Off: every input passes through.
        List<String> disabled = new ArrayList<>();
        ParameterSet off = (ParameterSet) carParameters();
        for (double v : new double[] {0.0, 5.0, 25.0})
        {
            disabled.add(obj(num("speedMs", v), num("headwayInS", 1.1),
                    num("headwayOutS", CapacityDrop.absoluteHeadwayTime(off, null, v, 1.1))));
        }

        // The relative form, and with it the rule that the absolute one then declines to act.
        List<String> relative = new ArrayList<>();
        List<String> exclusive = new ArrayList<>();
        ParameterSet both = (ParameterSet) carParameters();
        both.setParameter(MirovaParameters.CAPACITY_DROP_ENABLED, true);
        both.setParameter(MirovaParameters.T_DISCHARGE_ADDON, new Duration(0.5, DurationUnit.SECOND));
        both.setParameter(MirovaParameters.V_CRIT_DISCHARGE, new Speed(40.0, SpeedUnit.KM_PER_HOUR));
        both.setParameter(MirovaParameters.T_DISCHARGE_FRACTION, 0.4);
        both.setParameter(MirovaParameters.V_CRIT_DISCHARGE_FRACTION, 0.5);
        double desired = 33.333333333333336;
        for (double v : new double[] {0.0, 5.0, 10.0, 16.0, 16.666666666666668, 20.0, 30.0})
        {
            Length in = Length.instantiateSI(3.0 + v * 1.1);
            Length out = CapacityDrop.relativeDesiredHeadway(both, new Speed(v, SpeedUnit.SI),
                    new Speed(desired, SpeedUnit.SI), in);
            relative.add(obj(num("speedMs", v), num("desiredSpeedMs", desired), num("equilibriumInM", in.si),
                    num("equilibriumOutM", out.si)));
            exclusive.add(obj(num("speedMs", v), num("headwayInS", 1.1),
                    num("headwayOutS", CapacityDrop.absoluteHeadwayTime(both, null, v, 1.1))));
        }

        String body = "{\n  " + field("generator", "CarFollowingFixtureGenerator.writeCapacityDropFixture") + ",\n  "
                + field("sourceCommit", SOURCE_COMMIT) + ",\n  "
                + field("switches", "OUTSIDE the coreset: capDropOn is forced true, since it is false in every campaign")
                + ",\n  " + field("description", "CapacityDrop: both formulations, and the rule that they never stack")
                + ",\n  " + arr("absolute", absolute) + ",\n  " + arr("disabled", disabled) + ",\n  "
                + arr("relative", relative) + ",\n  " + arr("absoluteWhileRelativeSet", exclusive) + "\n}\n";
        writeRaw("capacity_drop.json", body);
    }

    // ------------------------------------------------------------------
    // JSON, written by hand
    // ------------------------------------------------------------------

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
     * @param members the members
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
     * @param value the value
     * @return the member
     */
    private static String field(final String key, final String value)
    {
        return "\"" + key + "\": \"" + value + "\"";
    }

    /**
     * Renders a numeric member, with {@code Double.toString} so the value round-trips exactly.
     * @param key the member name
     * @param value the value
     * @return the member
     */
    private static String num(final String key, final double value)
    {
        return "\"" + key + "\": " + Double.toString(value);
    }
}
