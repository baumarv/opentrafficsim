package org.opentrafficsim.road.gtu.lane.tactical.mirova.core.DesireLayer;

import org.opentrafficsim.core.network.LateralDirectionality;

/**
 * Represents directional lane-change desires according to the LMRS philosophy.
 * <p>
 * A {@code Desire} encodes the tendency of a vehicle to move laterally to the left or right. It tracks both the combined total
 * desire and the separated <b>mandatory</b> (required) and <b>discretionary</b> (optional) components. This enables the
 * cognitive layer to evaluate aggregated motivations while still providing access to the underlying reasons for maneuver
 * decisions.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public final class Desire
{

    // ----------------------------------------------------------------------
    // Core fields
    // ----------------------------------------------------------------------

    /** Total desire to move left (combined mandatory and discretionary). */
    private final double left;

    /** Total desire to move right (combined mandatory and discretionary). */
    private final double right;

    /** Purely mandatory desire to move left. */
    private final double leftMandatory;

    /** Purely mandatory desire to move right. */
    private final double rightMandatory;

    /** Purely discretionary desire to move left. */
    private final double leftDiscretionary;

    /** Purely discretionary desire to move right. */
    private final double rightDiscretionary;

    /** Whether this desire vector fundamentally contains a mandatory motivation. */
    private final boolean mandatory;

    /**
     * BC-13: whether this desire is on the capped scale.
     * <p>
     * Carried by the value rather than read from a parameter, because a {@code Desire} has no vehicle. The incentives
     * set it from {@code bcDesireCapped}; {@link #add}, {@link #scale} and {@link #combine} propagate it, so anything
     * derived from a capped desire is capped too.
     * </p>
     */
    private final boolean capped;

    /** The largest value a desire may take when capped. See {@link MirovaParameters#DESIRE_CAPPED}. */
    private static final double DESIRE_MAXIMUM = 1.0;

    /**
     * Applies the BC-13 cap.
     * @param value double; the raw desire
     * @param cap boolean; whether to cap
     * @return double; the value, at most {@link #DESIRE_MAXIMUM} when capped, unchanged below
     */
    private static double capped(final double value, final boolean cap)
    {
        return cap && value > DESIRE_MAXIMUM ? DESIRE_MAXIMUM : value;
    }

    // ----------------------------------------------------------------------
    // Construction
    // ----------------------------------------------------------------------

    /**
     * Constructs a directional desire vector from a single motivation source.
     * <p>
     * This constructor automatically assigns the input values to the respective mandatory or discretionary internal fields
     * based on the flag.
     * </p>
     * @param left double; directional desire to move left (positive = stronger)
     * @param right double; directional desire to move right (positive = stronger)
     * @param mandatory boolean; true if this represents a mandatory desire (required maneuver)
     */
    public Desire(final double left, final double right, final boolean mandatory)
    {
        this(left, right, mandatory, false);
    }

    /**
     * The same, told whether this desire is on the capped scale.
     * <p>
     * BC-13. A single-source desire caps its total and the one component that carries it identically, so the
     * decomposition stays consistent with the total.
     * </p>
     * @param left double; directional desire to move left (positive = stronger)
     * @param right double; directional desire to move right (positive = stronger)
     * @param mandatory boolean; true if this represents a mandatory desire (required maneuver)
     * @param cap boolean; whether to cap at 1 above, from {@code bcDesireCapped}
     */
    public Desire(final double left, final double right, final boolean mandatory, final boolean cap)
    {
        double l = capped(left, cap);
        double r = capped(right, cap);
        this.left = l;
        this.right = r;
        this.mandatory = mandatory;
        this.capped = cap;

        if (mandatory)
        {
            this.leftMandatory = l;
            this.rightMandatory = r;
            this.leftDiscretionary = 0.0;
            this.rightDiscretionary = 0.0;
        }
        else
        {
            this.leftMandatory = 0.0;
            this.rightMandatory = 0.0;
            this.leftDiscretionary = l;
            this.rightDiscretionary = r;
        }
    }

    /**
     * Private constructor to explicitly set all tracked components during mathematical combinations.
     * @param left double; total left desire
     * @param right double; total right desire
     * @param leftMandatory double; purely mandatory left component
     * @param rightMandatory double; purely mandatory right component
     * @param leftDiscretionary double; purely discretionary left component
     * @param rightDiscretionary double; purely discretionary right component
     * @param mandatory boolean; true if any mandatory motivation is present
     */
    private Desire(final double left, final double right, final double leftMandatory, final double rightMandatory,
            final double leftDiscretionary, final double rightDiscretionary, final boolean mandatory)
    {
        this(left, right, leftMandatory, rightMandatory, leftDiscretionary, rightDiscretionary, mandatory, false);
    }

    /**
     * The same, told whether this desire is on the capped scale.
     * <p>
     * BC-13. Only the totals are capped here, as in OTS, whose {@code Desire} has nothing but totals. The mandatory
     * and discretionary components stay as the uncapped provenance of the total, so where the cap binds the total is
     * no longer their sum. Nothing reads them as one: the thresholds interpolate on the total.
     * </p>
     * @param left double; total left desire
     * @param right double; total right desire
     * @param leftMandatory double; purely mandatory left component
     * @param rightMandatory double; purely mandatory right component
     * @param leftDiscretionary double; purely discretionary left component
     * @param rightDiscretionary double; purely discretionary right component
     * @param mandatory boolean; true if any mandatory motivation is present
     * @param cap boolean; whether to cap at 1 above, from {@code bcDesireCapped}
     */
    private Desire(final double left, final double right, final double leftMandatory, final double rightMandatory,
            final double leftDiscretionary, final double rightDiscretionary, final boolean mandatory,
            final boolean cap)
    {
        this.capped = cap;
        this.left = capped(left, cap);
        this.right = capped(right, cap);
        this.leftMandatory = leftMandatory;
        this.rightMandatory = rightMandatory;
        this.leftDiscretionary = leftDiscretionary;
        this.rightDiscretionary = rightDiscretionary;
        this.mandatory = mandatory;
    }

    /**
     * Whether this desire is on the capped scale. BC-13.
     * @return boolean; true when the cap applies to this value and to anything derived from it
     */
    public boolean isCapped()
    {
        return this.capped;
    }

    /**
     * Returns a zero desire (no lateral preference).
     * @return Desire; a desire with 0.0 magnitude in all components
     */
    public static Desire zero()
    {
        return new Desire(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, false);
    }

    // ----------------------------------------------------------------------
    // Total Getters
    // ----------------------------------------------------------------------

    /**
     * Gets the total combined desire to move left.
     * @return double; the leftward desire magnitude
     */
    public double getLeft()
    {
        return this.left;
    }

    /**
     * Gets the total combined desire to move right.
     * @return double; the rightward desire magnitude
     */
    public double getRight()
    {
        return this.right;
    }

    /**
     * Checks whether this desire vector contains a mandatory motivation.
     * @return boolean; true if mandatory components exist, false if purely discretionary
     */
    public boolean isMandatory()
    {
        return this.mandatory;
    }

    // ----------------------------------------------------------------------
    // Separated Component Getters
    // ----------------------------------------------------------------------

    /**
     * Retrieves the purely mandatory desire magnitude for a specific lateral direction.
     * @param direction LateralDirectionality; the requested lateral direction
     * @return double; the mandatory desire magnitude for the given direction (0.0 for NONE)
     */
    public double getMandatoryDesire(final LateralDirectionality direction)
    {
        switch (direction)
        {
            case LEFT:
                return this.leftMandatory;
            case RIGHT:
                return this.rightMandatory;
            default:
                return 0.0;
        }
    }

    /**
     * Retrieves the purely discretionary desire magnitude for a specific lateral direction.
     * @param direction LateralDirectionality; the requested lateral direction
     * @return double; the discretionary desire magnitude for the given direction (0.0 for NONE)
     */
    public double getDiscretionaryDesire(final LateralDirectionality direction)
    {
        switch (direction)
        {
            case LEFT:
                return this.leftDiscretionary;
            case RIGHT:
                return this.rightDiscretionary;
            default:
                return 0.0;
        }
    }

    /**
     * Retrieves the total combined desire magnitude for a specific lateral direction.
     * @param direction LateralDirectionality; the requested lateral direction
     * @return double; the desire magnitude for the given direction (0.0 for NONE)
     */
    public double getDirectionalDesire(final LateralDirectionality direction)
    {
        switch (direction)
        {
            case LEFT:
                return this.left;
            case RIGHT:
                return this.right;
            default:
                return 0.0;
        }
    }

    // ----------------------------------------------------------------------
    // Combination utilities
    // ----------------------------------------------------------------------

    /**
     * Adds another desire vector component-wise. The resulting desire retains the separated mandatory and discretionary
     * components.
     * @param other Desire; the other desire to add
     * @return Desire; the summed desire object
     */
    public Desire add(final Desire other)
    {
        return new Desire(this.left + other.left, this.right + other.right, this.leftMandatory + other.leftMandatory,
                this.rightMandatory + other.rightMandatory, this.leftDiscretionary + other.leftDiscretionary,
                this.rightDiscretionary + other.rightDiscretionary, this.mandatory || other.mandatory,
                this.capped || other.capped);
    }

    /**
     * Returns a scaled version of this desire. All separated components are scaled uniformly.
     * @param factor double; scaling factor (e.g., to attenuate influence)
     * @return Desire; the scaled desire object
     */
    public Desire scale(final double factor)
    {
        return new Desire(this.left * factor, this.right * factor, this.leftMandatory * factor, this.rightMandatory * factor,
                this.leftDiscretionary * factor, this.rightDiscretionary * factor, this.mandatory, this.capped);
    }

    /**
     * Combines mandatory and discretionary desires into a single net desire vector, applying LMRS-inspired per-direction
     * weighting.
     * <p>
     * The LMRS weights are applied to the discretionary components to ensure mathematical consistency inside the resulting
     * object (Total = Mandatory + Effective Discretionary).
     * </p>
     * @param mandatoryDesire Desire; the aggregated mandatory desire components
     * @param discretionaryDesire Desire; the aggregated discretionary desire components
     * @param dSync double; threshold for synchronization
     * @param dCoop double; threshold for cooperation (mandatory dominance)
     * @return Desire; the combined directional desire preserving all internal components
     */
    public static Desire combine(final Desire mandatoryDesire, final Desire discretionaryDesire, final double dSync,
            final double dCoop)
    {
        // Per-direction discretionary weighting based on total perceived desire in that direction
        double wDisLeft = computeDiscLcWeight(mandatoryDesire.left, discretionaryDesire.left, dSync, dCoop);
        double wDisRight = computeDiscLcWeight(mandatoryDesire.right, discretionaryDesire.right, dSync, dCoop);

        // Sum up the pure mandatory components (cross-summing ensures safety if a mixed object was passed)
        double totalMandLeft = mandatoryDesire.leftMandatory + discretionaryDesire.leftMandatory;
        double totalMandRight = mandatoryDesire.rightMandatory + discretionaryDesire.rightMandatory;

        // Sum up the discretionary components, applying the LMRS weight to the discretionary contribution
        double effectiveDisLeft = mandatoryDesire.leftDiscretionary + (wDisLeft * discretionaryDesire.leftDiscretionary);
        double effectiveDisRight = mandatoryDesire.rightDiscretionary + (wDisRight * discretionaryDesire.rightDiscretionary);

        // Calculate the perfectly consistent total values
        double leftCombined = totalMandLeft + effectiveDisLeft;
        double rightCombined = totalMandRight + effectiveDisRight;

        boolean combinedMandatory = totalMandLeft > 0 || totalMandRight > 0;

        return new Desire(leftCombined, rightCombined, totalMandLeft, totalMandRight, effectiveDisLeft, effectiveDisRight,
                combinedMandatory, mandatoryDesire.capped || discretionaryDesire.capped);
    }

    // ----------------------------------------------------------------------
    // Utility functions
    // ----------------------------------------------------------------------

    /**
     * Computes the LMRS weighting factor θ_v for combining mandatory and discretionary desires.
     * <p>
     * Based on Schakel et al. (2012), Eq. (12). The weight determines how strongly the discretionary component should
     * contribute, depending on the interaction between mandatory and discretionary desires.
     * </p>
     * @param mandatory double; the mandatory (route-following) desire for this direction
     * @param discretionary double; the discretionary (free) desire for this direction
     * @param dSync double; synchronization threshold (below which mandatory and discretionary coexist)
     * @param dCoop double; cooperation threshold (above which mandatory dominates completely)
     * @return double; weighting factor θ_v in [0, 1]
     */
    public static double computeDiscLcWeight(final double mandatory, final double discretionary, final double dSync,
            final double dCoop)
    {
        double product = mandatory * discretionary;
        double absMand = Math.abs(mandatory);

        if (product >= 0.0 || absMand <= dSync)
        {
            return 1.0;
        }

        if (absMand >= dCoop)
        {
            return 0.0;
        }

        return (dCoop - absMand) / (dCoop - dSync);
    }

    /**
     * Returns the overall total magnitude of this desire (the maximum absolute directional value).
     * @return double; maximum(left, right)
     */
    public double magnitude()
    {
        return Math.max(0, Math.max(this.left, this.right));
    }

    /**
     * Determines the dominant total direction of this desire.
     * @return LateralDirectionality; LEFT or RIGHT, or NONE if no dominant direction exists
     */
    // This method is where NONE comes from, and NONE is load-bearing elsewhere. Six per-tick caches are
    // keyed with `dir == LEFT ? ... : ...`, which sends NONE to the RIGHT entry; today that is harmless
    // only because of where NONE currently arrives, which was measured rather than assumed. Widening the
    // tie window below, or returning NONE in a new situation, can make five of those aliases live in one
    // change. See docs/decoupling/cache-audit.md section 2 before altering this.
    public LateralDirectionality dominantDirection()
    {
        if (Math.abs(this.left - this.right) < 1e-3)
        {
            return LateralDirectionality.NONE;
        }
        return (this.left > this.right) ? LateralDirectionality.LEFT : LateralDirectionality.RIGHT;
    }

    /**
     * Returns a detailed string representation of the desire vector including its components.
     * @return String; the formatted string representation
     */
    @Override
    public String toString()
    {
        return String.format("Desire[L: %.2f (m:%.2f, d:%.2f) | R: %.2f (m:%.2f, d:%.2f)]", this.left, this.leftMandatory,
                this.leftDiscretionary, this.right, this.rightMandatory, this.rightDiscretionary);
    }
}
