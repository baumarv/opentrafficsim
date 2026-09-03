package org.opentrafficsim.road.gtu.lane.tactical.mirova.core.IntentionLayer;

import org.opentrafficsim.base.parameters.ParameterException;
import org.opentrafficsim.core.gtu.GtuException;
import org.opentrafficsim.core.gtu.plan.operational.OperationalPlanException;
import org.opentrafficsim.core.network.NetworkException;

/**
 * One named rule in a state's ordered transition table: under some condition, this state hands over to that one.
 * <p>
 * A rule answers a single question -- what does this state hand over to, right now -- with the target state or {@code null}.
 * Guard and target are deliberately not separated into two callbacks. Several transitions carry a value the guard computed
 * into the state they lead to (the blocking vehicle a merger has to resolve), and the merge decision shares one expensive
 * piece of analysis across three possible outcomes. Splitting those would mean either computing them twice or passing them
 * sideways through a field.
 * </p>
 * <p>
 * What the rule does carry separately is its {@link #getName() name} and the {@link #getTarget() state it leads to}, so that
 * the transition graph can be read off the code instead of being reconstructed from a chain of {@code if} statements.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public final class Transition
{

    /** What this rule reacts to, in a few words. */
    private final String name;

    /** The state this rule leads to, for documentation and export. */
    private final String target;

    /** The rule itself. */
    private final Rule rule;

    /**
     * Creates a transition rule.
     * @param name what the rule reacts to, e.g. {@code "gap open and ego ready"}
     * @param target the state it leads to, e.g. {@code "ExecuteLaneChange"}; {@code "-"} where it depends on the situation
     * @param rule the rule, returning the target state or {@code null}
     */
    public Transition(final String name, final String target, final Rule rule)
    {
        this.name = name;
        this.target = target;
        this.rule = rule;
    }

    /**
     * Evaluates this rule.
     * @return the state to hand over to, {@link ActionState#FINISHED} to end the maneuver, or {@code null} if the rule does
     *         not apply right now
     * @throws ParameterException if a parameter lookup fails
     * @throws OperationalPlanException if plan construction fails
     * @throws GtuException if a GTU query fails
     * @throws NetworkException if a network query fails
     */
    public ActionState evaluate() throws ParameterException, OperationalPlanException, GtuException, NetworkException
    {
        return this.rule.evaluate();
    }

    /**
     * Returns what this rule reacts to.
     * @return the rule's name
     */
    public String getName()
    {
        return this.name;
    }

    /**
     * Returns the state this rule leads to.
     * @return the target's name, or {@code "-"} where the rule picks between several
     */
    public String getTarget()
    {
        return this.target;
    }

    @Override
    public String toString()
    {
        return this.name + " -> " + this.target;
    }

    /**
     * The body of a transition rule. Separate from {@link Transition} so that a rule can be written as a lambda.
     */
    @FunctionalInterface
    public interface Rule
    {
        /**
         * Decides whether this rule applies, and to what.
         * @return the state to hand over to, or {@code null}
         * @throws ParameterException if a parameter lookup fails
         * @throws OperationalPlanException if plan construction fails
         * @throws GtuException if a GTU query fails
         * @throws NetworkException if a network query fails
         */
        ActionState evaluate() throws ParameterException, OperationalPlanException, GtuException, NetworkException;
    }
}
