# Layer 2: Desire Layer (Cognition)

The **Desire Layer** (Layer 2) represents the **Cognitive / Declarative Knowledge** of the MiRoVA framework. Following the ACT-R cognitive theory, each desire source is modeled as a declarative "Knowledge Chunk" implemented as a concrete subclass of `DesireIncentive`. 

Crucially, this layer **only** computes the vehicle's motivations (**desires**). It does not decide on or execute actions — that is strictly the role of Layer 3 and Layer 4.

---

## 📐 The `Desire` Class

Lateral motivations in MiRoVA are encapsulated in the [Desire](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/DesireLayer/Desire.java) class. It mirrors the LMRS (Lane change Model with Relaxation and Synchronization) model structure by separating lane change motivation into:

1.  **Mandatory Desire ($d_{mandatory}$)**: Triggered by absolute topological requirements (e.g. route choice, lane endings). These are non-negotiable motivations.
2.  **Discretionary Desire ($d_{discretionary}$)**: Triggered by efficiency or comfort requirements (e.g. overtaking slower traffic, keeping right). These are optional motivations.

A `Desire` object tracks values independently for both lateral directions (Left and Right), combining them to calculate total lateral desire. The combination formula follows LMRS (Schakel et al. 2012):

$$d_{total} = d_{mandatory} + (1 - |d_{mandatory}|) \cdot d_{discretionary}$$

This ensures mandatory desires always dominate and discretionary desires fade out when mandatory desire approaches 1.0.

---

## 🧠 Base Class: `DesireIncentive`

Every declarative incentive extends [DesireIncentive](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/DesireLayer/DesireIncentive.java). It provides:

*   `isApplicable()`: Checks if this knowledge chunk is contextually relevant to the current driving situation.
*   `computeDesire()`: Calculates the directional left/right desire components based on current perception data.

The base class wires up all relevant OTS perception categories at construction time so that subclasses can access them without redundant lookups.

---

## 🚦 Registered Incentives — Detailed Breakdown

The incentives registered in [MirovaTacticalPlannerFactory](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/MirovaTacticalPlannerFactory.java#L144-L151):

---

### 1. `RouteIncentive` — Mandatory Route Following

**Source**: [RouteIncentive.java](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/DesireLayer/RouteIncentive.java)  
**Desire Type**: **Mandatory** (`mandatory = true`)  
**Applicability**: Always active (must constantly monitor lane validity and route topology).

**Algorithm (LMRS Schakel 2012, Equations 6-7)**:

The incentive computes a "desire-to-leave" value $d_{leave}$ for the current lane and adjacent lanes based on the remaining distance to the required lane change:

$$d_{leave}(lane) = \max\!\left(\max\!\left(1 - \frac{x}{n \cdot x_{lookahead}},\; 1 - \frac{x/v_{desired}}{n \cdot t_0}\right),\; 0\right)$$

Where:
- $x$: remaining distance before a mandatory split (from `LaneChangeInfo.remainingDistance()`)
- $n$: number of required lane changes
- $x_{lookahead}$: `ParameterTypes.LOOKAHEAD` (look-ahead distance)
- $t_0$: `ParameterTypes.T0` (minimum time headway for mandatory changes)
- $v_{desired}$: vehicle's desired speed for that lane

The directional desire is then computed using the Schakel formula:
- If the target lane has a **lower** $d_{leave}$ than the current lane → positive desire to move there: $d_{dir} = d_{leave,current}$
- If the target lane has a **higher** $d_{leave}$ (deadend there) → negative desire (veto): $d_{dir} = -d_{leave,target}$

**Special Behavior**: The veto mechanism is critical — it not only motivates moving *to* the correct lane but actively **prevents** discretionary lane changes *into* dead-end lanes.

---

### 2. `CruisingSpeedIncentive` — Discretionary Speed Gain

**Source**: [CruisingSpeedIncentive.java](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/DesireLayer/CruisingSpeedIncentive.java)  
**Desire Type**: **Discretionary** (`mandatory = false`)  
**Applicability**: Always applicable during normal driving.

**Algorithm (LMRS Schakel 2012, Equations 8-10)**:

$$d_{left} = a_{gain} \cdot \frac{v_{left} - v_{current}}{v_{gain}}, \quad d_{right} = a_{gain} \cdot \frac{\min(v_{right} - v_{current}, 0)}{v_{gain}} \text{ (in free-flow)}$$

Where:
- $v_{left/right/current}$: **anticipated** speeds on each lane (from `InfrastructureContext.getAnticipatedSpeed()`)
- $v_{gain}$: `MirovaParameters.vGain` (speed gain threshold, default ≈ 69.6 km/h)
- $a_{gain}$: dampening factor based on current car-following acceleration relative to max:

$$a_{gain} = \frac{a_{max} - a_{cf}}{a_{max}} \quad \text{if } a_{cf} > 0 \text{ and } v_{ego} > 5 \text{ m/s, else } 1.0$$

**Key Asymmetry**: Right lane desire in **free-flow** is capped at 0 (no speed gain from moving right to a same-speed lane). In **congestion** ($v < v_{CONG}$), normal speed difference applies in both directions.

---

### 3. `KeepRightIncentive` — German Keep-Right Obligation

**Source**: [KeepRightIncentive.java](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/DesireLayer/KeepRightIncentive.java)  
**Desire Type**: **Discretionary** (`mandatory = false`)  
**Applicability**: When a right lane exists and a legal lane change to the right is physically possible.

**Algorithm**:

A constant desire of `MirovaParameters.DFREE` (default: 0.365) is added to the right direction **if and only if all three conditions hold**:
1. The anticipated right lane speed ≥ ego vehicle's desired speed (right lane is not slower)
2. Legal lane-change distance to the right ≥ `ParameterTypes.LOOKAHEAD` (sufficient space ahead)
3. Right lane is **not congested** ($v_{right} > v_{CONG}$)

This models the German *Rechtsfahrgebot* (§ 2 StVO) — the obligation to drive in the rightmost available lane unless overtaking.

---

### 4. `ProhibitDeadEndIncentive` — Suppress Lane Changes onto Merging Ramps

**Source**: [ProhibitDeadEndIncentive.java](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/DesireLayer/ProhibitDeadEndIncentive.java)  
**Desire Type**: **Discretionary** (but with negative values acting as vetoes)  
**Applicability**: When `InfrastructureContext.getParallelMerge(dir)` is true for either direction.

**Purpose**: Prevents the ego vehicle on a main lane from voluntarily changing into a merging ramp lane (which will shortly end), even if that direction happens to have a temporarily higher anticipated speed.

**Algorithm**: If a parallel merge is detected in direction `dir`, applies a desire of `-DMAND` in that direction:
$$d_{dir} = -D_{MAND} \quad \text{(default: -0.577)}$$

This strong negative desire reliably suppresses discretionary lane changes into ending lanes.

---

### 5. `CongestionIncentive` — Congestion Avoidance (Currently Disabled)

**Source**: [CongestionIncentive.java](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/DesireLayer/CongestionIncentive.java)  
**Status**: Currently disabled in the factory (`// planner.addKnowledgeChunk(new CongestionIncentive(planner))`).  
**Purpose**: Would add anticipatory desires to change lanes away from lanes with downstream congestion.

---

## 🗳️ Desire Aggregation in `MirovaTacticalPlanner`

During each simulation tick, `MirovaTacticalPlanner.updateLaneChangeDesire()` aggregates all applicable incentives. For each direction, the total desire is built up using the LMRS combination rule, separating mandatory and discretionary contributions:

```java
// Simplified representation of desire aggregation
this.laneChangeDesire = Desire.zero();
for (DesireIncentive incentive : this.knowledgeChunks) {
    if (incentive.isApplicable()) {
        this.laneChangeDesire = this.laneChangeDesire.plus(incentive.computeDesire());
    }
}
```

The resulting `laneChangeDesire` is exposed via the planner's getters and serves as the primary input for:
1. **Layer 3 (Intent)**: Desire magnitude determines which ManeuverPatterns become active.
2. **Arbitration**: Desire direction + magnitude determines plan selection.
3. **Turn Indicators**: The dominant desire direction drives the turn signal.

---

## 🔑 Key Parameters Used by Desire Layer

| Parameter | Class | Default | Description |
|:--|:--|:--|:--|
| `DFREE` | `MirovaParameters` | 0.365 | Desire threshold for discretionary lane change |
| `DMAND` | `MirovaParameters` | 0.577 | Desire threshold for mandatory lane change |
| `DSEARCH` | `MirovaParameters` | 0.788 | Desire threshold triggering active gap search |
| `LOOKAHEAD` | `ParameterTypes` | — | Look-ahead distance for route/infra checks |
| `T0` | `ParameterTypes` | — | Minimum time headway for mandatory changes |
| `VCONG` | `ParameterTypes` | — | Speed threshold below which traffic is congested |
| `vGain` | `MirovaParameters` | 69.6 km/h | Speed gain reference for discretionary desires |
