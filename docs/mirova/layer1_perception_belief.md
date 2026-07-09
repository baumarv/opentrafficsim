# Layer 1: Perception & Belief Layer

The **Perception & Belief Layer** (Layer 1) acts as the cognitive "sensory filter" of the MiRoVA framework. It abstracts raw physical sensors and perceptions provided by OpenTrafficSim (OTS) into highly structured, semantic "contexts" that are easy for cognitive decision layers to evaluate.

---

## 🏗️ Central Orchestrator: `VehicleContextManager`

Every MiRoVA vehicle contains an instance of [VehicleContextManager](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/BeliefLayer/VehicleContextManager.java). It orchestrates the lifecycle of all context categories and controls cache synchronization.

*   **Tick-Based Cache Synchronization**: Raw OTS perception requests can be computationally expensive. The manager advances the simulation tick counter (`advanceTick()`) at the start of each step, which invalidates all lazy-evaluation caches in registered categories to ensure consistency without redundant evaluations.
*   **Default Context Categories**:
    *   `EgoContext`: Information about the ego vehicle's speed, routing, and relaxation states.
    *   `NeighborsContext`: Spatial relationships with surrounding traffic.
    *   `InfrastructureContext`: Physical network features (merges, speed limits, lane geometry).
    *   `MacroTrafficContext`: Macroscopic traffic conditions.

---

## 🚘 Ego Context (`EgoContext`)

The [EgoContext](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/BeliefLayer/EgoContext.java) manages parameters and dynamic values that belong to the ego vehicle.

### Key Responsibilities:
1.  **State Properties**: Exposes core physical quantities like current Speed, Acceleration, Length, Width, Route, and Target Speed.
2.  **Relaxation State Management**:
    *   Maintains the active [RelaxationState](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/BeliefLayer/RelaxationState.java) mapping for current and target lane leaders.
    *   This is the backbone of the Keane & Gao 2021 relaxation implementation. It maps a leader's unique ID to its respective spatial ($\gamma_s$) and velocity ($\gamma_v$) relaxation scaling factors.
3.  **Tick Acceleration Caching**:
    *   To prevent evaluating car-following models multiple times for the same leader in a single tick (e.g. if queried by different maneuver patterns), `EgoContext` caches the evaluation results in `tickAccelerationCache`, which is cleared each tick.

---

## 👥 Neighbors Context (`NeighborsContext`)

The [NeighborsContext](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/BeliefLayer/NeighborsContext.java) tracks adjacent and in-lane vehicles.

### Key Responsibilities:
1.  **Surrounding Area Identification**: Evaluates direct leaders, followers, and left/right adjacent lane vehicles.
2.  **Passive Cut-In Detection (Edge Trigger)**:
    *   In every simulation step, the context checks if the leader ID on the current lane has changed.
    *   If a new leader is detected (i.e. another vehicle cut in front), it triggers the creation of a new `RelaxationState` instance.
    *   This initiates the decay dynamics of spatial headway deficits, preventing emergency braking and creating a smooth, human-like reaction to cutting-in vehicles.
3.  **Proactive Relaxation Hooks**:
    *   Allows other layers (such as the `MandatoryLaneChangePattern`) to pre-register a relaxation state for a vehicle on a target lane *before* the lane change physically happens, optimizing preparation and spacing.

---

## 🛣️ Infrastructure Context (`InfrastructureContext`)

The [InfrastructureContext](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/BeliefLayer/InfrastructureContext.java) abstracts the physical network.

### Key Responsibilities:
1.  **Lane Structure & Limits**: Keeps track of current speed limits, lane widths, and connectivity.
2.  **Downstream Bottlenecks & Merges**:
    *   Calculates distance to upcoming merges, splits, or lane endings.
    *   Provides long-range anticipation information used to decelerate smoothly when approaching bottlenecks.
3.  **Traffic Control Interfaces**: Detects downstream traffic lights, conflicts (e.g. priority intersections), and yield areas.

---

## 📊 Macro Traffic Context (`MacroTrafficContext`)

The [MacroTrafficContext](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/BeliefLayer/MacroTrafficContext.java) provides context on downstream congestion.

### Key Responsibilities:
1.  **Congestion Detection**: Detects downstream bottlenecks and aggregates spatial density metrics.
2.  **Velocity Profiling**: Samples mean traffic speeds ahead to let the vehicle anticipate bottleneck-induced slow-downs.
