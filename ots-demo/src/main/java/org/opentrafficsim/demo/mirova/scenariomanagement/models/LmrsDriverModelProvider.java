package org.opentrafficsim.demo.mirova.scenariomanagement.models;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.opentrafficsim.base.parameters.ParameterType;
import org.opentrafficsim.base.parameters.ParameterTypes;
import org.opentrafficsim.demo.mirova.scenariomanagement.DriverModelProvider;
import org.opentrafficsim.demo.mirova.scenariomanagement.ParameterOverrides;
import org.opentrafficsim.road.gtu.strategical.LaneBasedStrategicalPlannerFactory;
import org.opentrafficsim.road.gtu.strategical.LaneBasedStrategicalRoutePlannerFactory;
import org.opentrafficsim.road.gtu.tactical.lmrs.Lmrs;
import org.opentrafficsim.road.gtu.tactical.lmrs.LmrsFactory;
import org.opentrafficsim.road.gtu.tactical.lmrs.LmrsFactory.CooperationConverter;
import org.opentrafficsim.road.gtu.tactical.lmrs.LmrsFactory.FullerImplementation;
import org.opentrafficsim.road.gtu.tactical.lmrs.LmrsFactory.GapAcceptanceConverter;
import org.opentrafficsim.road.gtu.tactical.lmrs.LmrsFactory.Setting;
import org.opentrafficsim.road.gtu.tactical.lmrs.LmrsFactory.SynchronizationConverter;
import org.opentrafficsim.road.gtu.tactical.util.lmrs.LmrsParameters;

/**
 * The LMRS of upstream OpenTrafficSim, as a selectable driver model.
 * <p>
 * The factory is upstream's {@link LmrsFactory}, started from a named configuration, {@link LmrsDefaults}: by default
 * {@link LmrsDefaults#FINAL_V2}, the composition of upstream's ShortMerge demo with the driver parameters of the MiRoVA campaign
 * final_v2. {@code lmrs.defaults=upstream} starts from upstream's factory as it is, exactly what
 * {@code new LmrsFactory<>(Lmrs::new)} gives.
 * </p>
 * <p>
 * A run may compose the LMRS differently, in two ways. A program hands over an {@link LmrsSettings} object under
 * {@link #KEY_SETTINGS}, which states settings and values with the LMRS's own types. A command line addresses single settings
 * as text, {@code lmrs.<name>=<value>}, see {@link #SETTINGS_HELP}. Either may be addressed to one vehicle class,
 * {@code lmrs.car.<name>} or {@code lmrs.car.settings}, and then applies to that class only. The order is: the defaults, the
 * settings for all classes, the settings for this class. Either way the settings are applied through
 * {@link LmrsFactory#set(Setting, Object)}, the same way a program using upstream would set them, and only what is named is
 * set. The command-line options of {@link LmrsFactory} itself are deliberately not used: their defaults differ from the
 * defaults of a factory built in code (the perception model, for one), so parsing through them would change the model even
 * when no option is given.
 * </p>
 * <p>
 * {@link #describe()} reports the composition as it is read back from the factory that was built, every setting with its
 * value, and names the settings that differ from a factory upstream builds.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class LmrsDriverModelProvider implements DriverModelProvider
{
    /** Key this model is selected by. */
    public static final String KEY = "lmrs";

    /** Name of the setting under which a program hands over an {@link LmrsSettings} object. */
    public static final String KEY_SETTINGS = "settings";

    /** Name of the setting that selects the configuration the factory starts from, see {@link LmrsDefaults}. */
    public static final String KEY_DEFAULTS = "defaults";

    /** The vehicle classes a setting may be addressed to, as {@code <vehicle class>.<name>}. */
    public static final List<String> VEHICLE_CLASSES = List.of("car", "truck");

    /** Name of the setting that selects the synchronization strategy. */
    public static final String SYNCHRONIZATION = "synchronization";

    /** Name of the setting that selects the cooperation strategy. */
    public static final String COOPERATION = "cooperation";

    /** Name of the setting that selects the gap-acceptance strategy. */
    public static final String GAP_ACCEPTANCE = "gapAcceptance";

    /** Name of the setting that selects the implementation of Fuller's task-capability perception model. */
    public static final String FULLER_IMPLEMENTATION = "fullerImplementation";

    /** The settings that switch a component on or off, by name; the names are those of the factory's own options. */
    private static final Map<String, Setting<Boolean>> BOOLEAN_SETTINGS = new LinkedHashMap<>();

    static
    {
        BOOLEAN_SETTINGS.put("incentiveRoute", Setting.INCENTIVE_ROUTE);
        BOOLEAN_SETTINGS.put("incentiveGetInLane", Setting.INCENTIVE_GET_IN_LANE);
        BOOLEAN_SETTINGS.put("incentiveSpeedWithCourtesy", Setting.INCENTIVE_SPEED_WITH_COURTESY);
        BOOLEAN_SETTINGS.put("incentiveCourtesy", Setting.INCENTIVE_COURTESY);
        BOOLEAN_SETTINGS.put("incentiveQueue", Setting.INCENTIVE_QUEUE);
        BOOLEAN_SETTINGS.put("incentiveStayRight", Setting.INCENTIVE_STAY_ON_SLOW_LANES);
        BOOLEAN_SETTINGS.put("incentiveKeep", Setting.INCENTIVE_KEEP);
        BOOLEAN_SETTINGS.put("accelerationSpeedLimitTransition", Setting.ACCELERATION_SPEED_LIMIT_TRANSITION);
        BOOLEAN_SETTINGS.put("accelerationNoSlowLaneOvertake", Setting.ACCELERATION_NO_SLOW_LANE_OVERTAKE);
        BOOLEAN_SETTINGS.put("socialPressure", Setting.SOCIO_PRESSURE);
        BOOLEAN_SETTINGS.put("tailgating", Setting.SOCIO_TAILGATING);
        BOOLEAN_SETTINGS.put("socioLaneChange", Setting.SOCIO_LANE_CHANGE);
        BOOLEAN_SETTINGS.put("socioSpeed", Setting.SOCIO_SPEED);
    }

    /** The settings this provider understands, with their values and the defaults of upstream's factory. */
    public static final String SETTINGS_HELP = KEY_DEFAULTS + "=final-v2|shortmerge|upstream (default: final-v2), " + SYNCHRONIZATION
            + "=PASSIVE|PASSIVE_MOVING|ALIGN_GAP|ALIGN_GAP_MOVING (upstream: PASSIVE), " + COOPERATION + "=NONE|PASSIVE|PASSIVE_MOVING (upstream: PASSIVE), " + GAP_ACCEPTANCE
            + "=INFORMED|EGO_HEADWAY (upstream: INFORMED), " + FULLER_IMPLEMENTATION + "=" + fullerNames()
            + " (upstream: NONE), and true|false for " + String.join(", ", BOOLEAN_SETTINGS.keySet())
            + " (upstream: true for incentiveRoute, incentiveSpeedWithCourtesy and incentiveKeep, false for the others); "
            + "each also as car.<name> or truck.<name> for one vehicle class";

    /** The parameter types an override may address: the standard OTS ones and those of the LMRS. */
    private static final Map<String, ParameterType<?>> PARAMETER_TYPES =
            ParameterOverrides.parameterTypesOf(ParameterTypes.class, LmrsParameters.class);

    /** The parameter overrides that were applied, one {@code name=value} line each. */
    private final List<String> applied = new ArrayList<>();

    /** The settings of the factory that was built, read back from it: setting name to value name. */
    private Map<String, String> effectiveSettings = new LinkedHashMap<>();

    /** The names of the settings whose value differs from a factory upstream builds. */
    private final List<String> changedSettings = new ArrayList<>();

    /** Vehicle class of the request this provider served. */
    private String vehicleClass = null;

    /** The configuration the factory was started from. */
    private LmrsDefaults defaults = LmrsDefaults.DEFAULT;

    /**
     * Constructor.
     */
    public LmrsDriverModelProvider()
    {
        // nothing to initialise; a provider is created per request
    }

    @Override
    public String key()
    {
        return KEY;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public LaneBasedStrategicalPlannerFactory<?> createStrategicalPlannerFactory(final Request request) throws Exception
    {
        this.vehicleClass = request.vehicleClass();
        LmrsFactory<Lmrs> factory = new LmrsFactory<>(List.of(request.gtuType()), Lmrs::new).setStream(request.stream());
        Map<String, Object> forAll = new LinkedHashMap<>();
        Map<String, Object> forThisClass = new LinkedHashMap<>();
        for (Map.Entry<String, Object> setting : request.modelSettings().entrySet())
        {
            String name = setting.getKey();
            int dot = name.indexOf('.');
            if (KEY_DEFAULTS.equals(name))
            {
                this.defaults = LmrsDefaults.of(String.valueOf(setting.getValue()));
            }
            else if (dot < 0)
            {
                forAll.put(name, setting.getValue());
            }
            else if (!VEHICLE_CLASSES.contains(name.substring(0, dot)))
            {
                throw new IllegalArgumentException("'" + KEY + "." + name + "' addresses no vehicle class; a setting for one "
                        + "class is " + KEY + ".<" + String.join("|", VEHICLE_CLASSES) + ">.<name>.");
            }
            else if (name.substring(0, dot).equals(request.vehicleClass()))
            {
                forThisClass.put(name.substring(dot + 1), setting.getValue());
            }
            // a setting for another vehicle class is that class's provider's to apply and check
        }
        this.defaults.settings(request.vehicleClass()).applyTo(factory);
        applySettings(factory, forAll);
        applySettings(factory, forThisClass);
        // What is reported is read back from the factory, and compared with one built the way upstream builds it. The
        // record then states what ran, and stays right when upstream changes a default.
        this.effectiveSettings = LmrsSettings.read(factory);
        Map<String, String> upstream = LmrsSettings.read(new LmrsFactory<>(List.of(request.gtuType()), Lmrs::new));
        for (Map.Entry<String, String> effective : this.effectiveSettings.entrySet())
        {
            if (!effective.getValue().equals(upstream.get(effective.getKey())))
            {
                this.changedSettings.add(effective.getKey());
            }
        }
        // The parameters of the defaults first, the run's own overrides replace them; each parameter is added once.
        Map<ParameterType<?>, Object> parameters = new LinkedHashMap<>(this.defaults.parameters(request.vehicleClass()));
        Map<ParameterType<?>, String> origin = new LinkedHashMap<>();
        parameters.keySet().forEach((parameterType) -> origin.put(parameterType, "default"));
        for (Map.Entry<String, Object> override : request.parameterOverrides().entrySet())
        {
            ParameterType<?> parameterType = PARAMETER_TYPES.get(override.getKey());
            if (parameterType == null)
            {
                throw new IllegalArgumentException("Parameter override '" + request.vehicleClass() + "." + override.getKey()
                        + "' names no parameter of OTS or the LMRS. Refusing to run: an override that is silently ignored "
                        + "leaves a run labelled with a parameter it never used.");
            }
            parameters.put(parameterType, ParameterOverrides.convert(parameterType, override.getValue()));
            origin.put(parameterType, "override");
        }
        for (Map.Entry<ParameterType<?>, Object> parameter : parameters.entrySet())
        {
            factory.addParameter(request.gtuType(), (ParameterType) parameter.getKey(), parameter.getValue());
            this.applied.add(origin.get(parameter.getKey()) + "." + request.vehicleClass() + "." + parameter.getKey().getId()
                    + "=" + parameter.getValue());
        }
        // The LMRS factory is its own parameter factory: it is what applies the overrides above to each vehicle.
        return new LaneBasedStrategicalRoutePlannerFactory(factory, factory);
    }

    /**
     * Applies model settings to the factory, in the order given: an {@link LmrsSettings} object under {@link #KEY_SETTINGS},
     * any other as a single setting given as text.
     * @param factory LmrsFactory&lt;Lmrs&gt;; the factory being configured
     * @param settings Map&lt;String, Object&gt;; name of the setting to its value
     * @throws Exception when a name is no setting of this provider, or a value is not one of its values
     */
    private void applySettings(final LmrsFactory<Lmrs> factory, final Map<String, Object> settings) throws Exception
    {
        for (Map.Entry<String, Object> setting : settings.entrySet())
        {
            if (KEY_SETTINGS.equals(setting.getKey()))
            {
                if (!(setting.getValue() instanceof LmrsSettings typed))
                {
                    throw new IllegalArgumentException("'" + KEY + "." + KEY_SETTINGS + "' takes an "
                            + LmrsSettings.class.getSimpleName() + " object, not a "
                            + setting.getValue().getClass().getSimpleName() + ".");
                }
                typed.applyTo(factory);
            }
            else
            {
                applySetting(factory, setting.getKey(), String.valueOf(setting.getValue()).trim());
            }
        }
    }

    /**
     * Applies one model setting to the factory and records it.
     * @param factory LmrsFactory&lt;Lmrs&gt;; the factory being configured
     * @param name String; the name of the setting
     * @param value String; its value as text
     * @throws Exception when the name is no setting of this provider, or the value is not one of its values
     */
    private void applySetting(final LmrsFactory<Lmrs> factory, final String name, final String value) throws Exception
    {
        if (SYNCHRONIZATION.equals(name))
        {
            factory.set(Setting.SYNCHRONIZATION, new SynchronizationConverter().convert(value));
        }
        else if (COOPERATION.equals(name))
        {
            factory.set(Setting.COOPERATION, new CooperationConverter().convert(value));
        }
        else if (GAP_ACCEPTANCE.equals(name))
        {
            factory.set(Setting.GAP_ACCEPTANCE, new GapAcceptanceConverter().convert(value));
        }
        else if (FULLER_IMPLEMENTATION.equals(name))
        {
            try
            {
                factory.set(Setting.FULLER_IMPLEMENTATION, FullerImplementation.valueOf(value.toUpperCase(Locale.ROOT)));
            }
            catch (IllegalArgumentException exception)
            {
                throw new IllegalArgumentException("'" + value + "' is no " + FULLER_IMPLEMENTATION + "; use one of "
                        + fullerNames() + ".", exception);
            }
        }
        else if (BOOLEAN_SETTINGS.containsKey(name))
        {
            if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value))
            {
                throw new IllegalArgumentException("Setting " + KEY + "." + name + " takes true or false, not '" + value
                        + "'.");
            }
            factory.set(BOOLEAN_SETTINGS.get(name), Boolean.parseBoolean(value));
        }
        else
        {
            throw new IllegalArgumentException("'" + KEY + "." + name + "' is no setting of the LMRS provider. Refusing to "
                    + "run: a setting that is silently ignored leaves a run labelled with a model it did not use. Known "
                    + "settings: " + SETTINGS_HELP + ".");
        }
    }

    /**
     * Returns the names of the implementations of Fuller's model, for messages.
     * @return String; the names, separated by a bar
     */
    private static String fullerNames()
    {
        List<String> names = new ArrayList<>();
        for (FullerImplementation implementation : FullerImplementation.values())
        {
            names.add(implementation.name());
        }
        return String.join("|", names);
    }

    @Override
    public String describe()
    {
        StringBuilder text = new StringBuilder();
        text.append(this.vehicleClass).append(".factory=").append(LmrsFactory.class.getName()).append('\n');
        text.append(this.vehicleClass).append(".defaults=").append(this.defaults.id()).append('\n');
        text.append(this.vehicleClass).append(".settings=").append(this.changedSettings.isEmpty() ? "upstream defaults"
                : "changed: " + String.join(", ", this.changedSettings)).append('\n');
        text.append(this.vehicleClass).append(".parameterOverrides=")
                .append(this.applied.isEmpty() ? "none (upstream defaults)" : "applied, see default.* and override.*")
                .append('\n');
        for (Map.Entry<String, String> setting : this.effectiveSettings.entrySet())
        {
            text.append("setting.").append(this.vehicleClass).append('.').append(setting.getKey()).append('=')
                    .append(setting.getValue()).append('\n');
        }
        for (String line : this.applied)
        {
            text.append(line).append('\n');
        }
        return text.toString();
    }
}
