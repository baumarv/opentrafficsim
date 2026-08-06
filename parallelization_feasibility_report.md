# Parallelization Feasibility Report

This document presents a structured investigation into the feasibility of three parallelization and performance optimization strategies for the **OpenTrafficSim (OTS)** core simulator and its **MiRoVA** (Migration of Road Vehicle Automation) cognitive driver-behavior extension layer.

---

## 1. Current Execution Model

### 1.1 Simulator Execution Architecture & Concurrency
OpenTrafficSim (OTS) is fundamentally a **single-threaded, discrete-event simulator** powered by the DSOL (`DEVSSimulator`) event engine. Each active Ground Transportation Unit (GTU / vehicle) schedules its own kinematic update events (`SimEvent<Duration>`) on the simulator's global event queue.

- **Intra-Simulation Concurrency**: There is **no GTU-level multi-threading or concurrency** inside OTS core or the MiRoVA framework. standard Java concurrency utilities like `ForkJoinPool`, `CompletableFuture`, or parallel streams are not used for GTU state evaluation or tactical plan generation.
- **Inter-Simulation Concurrency**: Multi-threading is currently implemented exclusively at the scenario level in [`ScenarioManager.java`](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-demo/src/main/java/org/opentrafficsim/demo/mirova/scenariomanagement/ScenarioManager.java#L92) via `Executors.newFixedThreadPool(parallelThreads)`. This provides coarse-grained task parallelism by executing independent simulation runs (parameter variations and Monte Carlo seed replications) on separate worker threads.

### 1.2 Operational Plan Call Chain
For a given simulation tick, the call chain from the DSOL event queue down to physical operational plan generation proceeds as follows:

```
1. DSOL DEVSSimulator.step() / processNextEvent()
   └─► SimEvent triggers Gtu.move(DirectedPoint2d)
        └─► LaneBasedGtu.move(DirectedPoint2d) [LaneBasedGtu.java:L492]
             └─► super.move(DirectedPoint2d) [Gtu.java:L421]
                  ├─► Perception: tactPlanner.getPerception().perceive()
                  ├─► Plan Generation: MirovaTacticalPlanner.generateOperationalPlan(Time, DirectedPoint2d)
                  │    └─► MirovaTacticalPlanner.update() [MirovaTacticalPlanner.java:L370]
                  │         ├─► Layer 1: VehicleContextManager.advanceTick() & updateContext()
                  │         │    └─► EgoContext, NeighborsContext, InfrastructureContext, MacroTrafficContext
                  │         ├─► Layer 2: updateLaneChangeDesire()
                  │         │    └─► Evaluates DesireIncentives (RouteIncentive, CruisingSpeedIncentive, KeepRightIncentive, ProhibitDeadEndIncentive)
                  │         ├─► Layer 3 & 4: HybridPlanArbitrator.arbitrate(relevantPatterns)
                  │         │    ├─► Step 1: Active Lane-Change Lock Check
                  │         │    ├─► Step 2: Winner-Takes-All (Desire >= D_FREE) -> ManeuverPattern / ActionState.update()
                  │         │    │    └─► Calls MirovaCarFollowingUtil.followSingleLeader()
                  │         │    └─► Step 3: Below-Threshold Voting (min acceleration across patterns)
                  │         └─► Returns SimpleOperationalPlan
                  ├─► Set Operational Plan & Schedule next move event on DSOL scheduler
                  └─► LaneBasedGtu post-move: Update position on Lane objects, schedule enter/leave triggers, execute sensor triggers (LoopDetector.triggerResponse())
```

### 1.3 Shared, Mutable State & Cross-GTU Interactions
During a GTU's update tick, reading or writing shared mutable state creates potential data races if GTU updates are naively executed concurrently:

1. **Lane Infrastructure Registries (`Lane.java`)**: `Lane.gtuList` (`ArrayList<LaneBasedGtu>`), `Lane.detectors`, and `Lane.laneBasedObjects`. When GTUs execute `move()`, they register/deregister on `Lane` objects. If GTU A updates its position while GTU B reads `Lane.gtuList` for perception, a `ConcurrentModificationException` or inconsistent headway calculation occurs.
2. **Direct Neighbor References (`NeighborsPerception`)**: `HeadwayGtuType.WRAP` provides direct live references to neighbor `LaneBasedGtu` instances. Evaluating GTU A's desire reads GTU B's current speed, acceleration, and position. If GTU B is concurrently modifying these fields during its `move()` phase, GTU A reads torn or partially updated state.
3. **Detector & Sampling State**: `LoopDetector` measurements, `TrafficLightDetector`, `RoadSampler`, and `VehicleDiffusionLogger` accumulate statistics when GTUs pass over sensors. While [`VehicleDiffusionLogger`](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/util/VehicleDiffusionLogger.java#L51) uses `Collections.synchronizedList`, standard OTS detectors use unsynchronized collections (`ArrayList`, `HashMap`).

---

## 2. Strategy 1: Task-Level Parallelism (à la Tomás et al. 2026)

### 2.1 Overview
Keep the sequential simulation tick loop, but farm out independent per-agent read-heavy computations (desire aggregation, pattern scoring) across worker threads during a single timestep, ensuring each worker only writes to its own agent's state.

### 2.2 Feasibility Analysis

#### A. Read-Only vs. Write Operations
- **Read-Only Computations**: Evaluating [`RouteIncentive`](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/DesireLayer/RouteIncentive.java), [`CruisingSpeedIncentive`](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/DesireLayer/CruisingSpeedIncentive.java), [`KeepRightIncentive`](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/DesireLayer/KeepRightIncentive.java), and [`ProhibitDeadEndIncentive`](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/DesireLayer/ProhibitDeadEndIncentive.java), as well as checking pattern utilities (`checkContext()`, `checkAbility()`), reads neighbor GTU properties (speed, position, turn indicator) and infrastructure geometry, but writes **strictly** to the ego GTU's own fields.
- **Instance-Scoped Caching**: The single-tick acceleration cache [`EgoContext.tickAccelerationCache`](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/BeliefLayer/EgoContext.java#L102) and active relaxations [`activeRelaxations`](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/BeliefLayer/EgoContext.java#L96) are instance fields of `EgoContext`, which is instantiated per `MirovaTacticalPlanner` (per GTU). Because each GTU owns a separate `EgoContext` instance, concurrent execution of GTU A and GTU B writes to completely disjoint maps. **There are no race conditions on `tickAccelerationCache` across different GTUs.**

#### B. Thread-Safety of Shared Resources
- **Static Parameters & Models**: Classes like [`Wiedemann99`](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/ReactiveLayer/Wiedemann99.java), `MirovaIdmPlus`, and `MirovaParameters` contain immutable parameter definitions (`public static final ParameterType...`). These are completely thread-safe for concurrent read access.
- **Shared Network & Infrastructure**: Network lookup tables, `Link` structures, and `Lane` geometry are immutable during simulation execution and safe for concurrent reads.
- **Unsafe Shared Structures**: Modifying `Lane.gtuList` or triggering `LoopDetector`s during parallel plan evaluation is non-thread-safe.

### 2.3 Feasibility Classification & Categorization

| Category | Component / Method | Requirements / Refactoring Needed |
| :--- | :--- | :--- |
| **Feasible without changes** | Pure Desire calculation (`DesireIncentive.computeDesire()`), Pattern utility scoring (`checkContext()`, `checkAbility()`), `EgoContext.tickAccelerationCache` writes | Input perception snapshots must be frozen for the duration of the parallel evaluation phase. |
| **Requires refactoring** | Full GTU tick cycle (`MirovaTacticalPlanner.generateOperationalPlan()`), `NeighborsContext` cut-in edge triggers | **Two-Phase Tick Execution (Barrier Architecture)**:<br>1. *Phase 1 (Parallel Evaluation)*: All GTUs concurrently run `perceive()`, `updateLaneChangeDesire()`, `arbitrate()`, and generate `SimpleOperationalPlan`. GTU positions and `Lane.gtuList` are strictly READ-ONLY.<br>2. *Phase 2 (Sequential State Application)*: The main simulator thread sequentially applies `SimpleOperationalPlan`s, updates GTU kinematics, mutates `Lane.gtuList`, and triggers `LoopDetector`s. |
| **Not feasible / High Risk** | Fine-grained asynchronous event scheduling | Asynchronous continuous-time updates without a synchronized tick barrier cause race conditions on neighbor state reads. |

---

## 3. Strategy 2: Vehicle Grouping Under Congestion (à la Chen et al. / QarSUMO 2020)

### 3.1 Overview
In densely packed, slow-moving queues, only the lead vehicle of a platoon undergoes full behavioral evaluation. Followers skip expensive perception and decision steps, maintaining longitudinal spacing via lightweight car-following.

### 3.2 Feasibility Analysis

#### A. Existing Congestion & Criticality Signals
MiRoVA and OTS already compute necessary per-GTU signals to detect standing or slow queues:
- `EgoContext.getEgoSpeed()` and `ParameterTypes.VCONG` (congestion threshold, default $v < 60\text{ km/h}$).
- In [`MandatoryLaneChangePattern`](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/IntentionLayer/ManeuverPatterns/MandatoryLaneChangePattern.java#L100), congested states trigger at $v_{\text{ego}} < 15\text{ km/h}$ (`CongestedMergeState`).
- Vehicle stoppage ($v \le 0.1\text{ m/s}$) is already tracked in `MirovaTacticalPlanner.checkAndHandleVehicleDiffusion()`.
- A cheap criticality/platoon condition (e.g. $v_{\text{ego}} < 5\text{ km/h}$ AND in-lane leader headway $< 10\text{ m}$) can be derived without adding new instrumentation.

#### B. Architectural Support for Bypassing Cognition/Decision Layers
- **Current Flow**: [`MirovaTacticalPlanner.update()`](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/MirovaTacticalPlanner.java#L370) unconditionally executes Layer 2 (`updateLaneChangeDesire()`) and Layer 3/4 (`arbitrator.arbitrate()`) every tick.
- **Kinematic Requirement**: A follower GTU in a queue **cannot** simply copy the leader's exact `SimpleOperationalPlan` acceleration vector, because follower gaps and relative speeds differ. The follower still requires longitudinal car-following ([`MirovaCarFollowingUtil.followSingleLeader()`](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/ReactiveLayer/MirovaCarFollowingUtil.java#L64)).
- **Bypass Feasibility**: A follower can bypass Layer 2 (DesireIncentives) and Layer 3 (evaluating complex pattern FSMs like `GapOpenerPattern`, `PreventUndercuttingPattern`, `AnticipateDownstreamMergePattern`), locking into a lightweight "QueueFollowing" state. **This requires a new code path in `MirovaTacticalPlanner`.**

#### C. Risks of Skipping Tactical/Strategic Layers
1. **Missed Passive Cut-In Detection**: [`NeighborsContext`](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/BeliefLayer/NeighborsContext.java#L28) detects cut-ins edge-triggered during perception. If perception is skipped, a vehicle cutting into the queue in front of GTU B will not trigger a [`RelaxationState`](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/BeliefLayer/RelaxationState.java), leading to artificial hard emergency braking when updates resume.
2. **Hard Braking Safety Override**: In `MirovaCarFollowingUtil.followSingleLeader()`, if the leader brakes hard ($a < -1.0\text{ m/s}^2$), `RelaxationState` is immediately cleared to prevent rear-end collisions. Skipping leader state checks risks missing hard-braking events.
3. **FSM Timer & Filter Desynchronization**: Maneuver patterns maintain internal timers and Exponential Moving Average (EMA) low-pass speed filters. Skipping updates desynchronizes state machine durations.

#### D. Platoon Data Structures
OTS maintains ordered vehicle lists per lane (`Lane.getGtuList()`) and direct leader references (`NeighborsContext.getLeader()`). However, **OTS/MiRoVA has no pre-built `Platoon` or `VehicleGroup` data structure**. Dynamic platoon management (header identification, follower tracking, split/merge logic) would need to be built from scratch.

### 3.3 Feasibility Classification & Categorization

| Category | Component / Method | Requirements / Refactoring Needed |
| :--- | :--- | :--- |
| **Feasible without changes** | Single-vehicle queue car-following (`MirovaCarFollowingUtil.followSingleLeader()`) | Evaluated at full tick resolution. |
| **Requires refactoring** | `MirovaTacticalPlanner.update()`, Platoon management | **New Queue-Following Bypass Path**:<br>1. Implement a `PlatoonManager` to track queue membership.<br>2. Add a bypass condition in `MirovaTacticalPlanner.update()` for non-lead queue GTUs.<br>3. Ensure `NeighborsContext` still checks for lead vehicle ID changes (cut-in detection) every tick. |
| **Not feasible / High Risk** | Completely skipping perception and car-following for follower GTUs | Blindly copying leader acceleration vectors without individual gap regulation causes severe grid collisions or phantom shockwaves. |

---

## 4. Strategy 3: Relaxed Update Frequency (à la Xu et al. / SEMSim 2017)

### 4.1 Overview
Higher cognitive layers (Desire, Decision) are re-evaluated less frequently in stable/free-flow conditions (e.g., every $1.0\text{ s}$) and more frequently in unstable/congested conditions (every $0.2\text{ s}$), while the Reactive layer (car-following) maintains full temporal resolution ($0.2\text{ s}$ tick).

### 4.2 Feasibility Analysis

#### A. Distinguishing Stable vs. Unstable Local Traffic
Stable free-flow traffic can be cheaply approximated using existing metrics without new instrumentation:
- Ego speed is high ($v_{\text{ego}} \ge V_{\text{CONG}}$).
- No active headway relaxation (`EgoContext.hasActiveRelaxation() == false`).
- No active lane change lock (`HybridPlanArbitrator.isChangingLane() == false`).
- In-lane leader gap is large ($s > s_{\text{stable}}$) and relative speed is small ($|\Delta v| < 1.0\text{ m/s}$).

#### B. Current Re-Evaluation Decoupling
Currently, **all layers are evaluated every single tick** ($\Delta t = 0.2\text{ s}$):
- `MirovaTacticalPlanner.update()` unconditionally invokes Layer 1 (`updateContext()`), Layer 2 (`updateLaneChangeDesire()`), Layer 3 (`arbitrate()`), and Layer 4 (`MirovaCarFollowingUtil`).
- There is currently no decoupling between cognitive desire evaluation and reactive car-following.

#### C. Minimal Code Path Change
1. Cache the `laneChangeDesire` vector (Layer 2) and the active `ManeuverPattern` / plan proposal (Layer 3) in `MirovaTacticalPlanner`.
2. Introduce a cognitive evaluation timer (`Time lastCognitionTime`).
3. On intermediate ticks ($t < lastCognitionTime + T_{\text{cognition}}$):
   - Skip `updateLaneChangeDesire()` and `HybridPlanArbitrator` Step 2 (winner-takes-all pattern re-evaluation).
   - Reuse the cached active `ManeuverPattern`.
   - Execute ONLY Layer 4 ([`MirovaCarFollowingUtil.followSingleLeader()`](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/ReactiveLayer/MirovaCarFollowingUtil.java#L64)) at full tick resolution ($0.2\text{ s}$).
4. **Constraint Compliance**: This approach respects the constraint of **not modifying `ParameterTypes.T`**. `ParameterTypes.T` (desired headway $T \approx 0.9 - 1.5\text{ s}$) and relaxation parameters ($\tau_s, \tau_v$) govern physical driving dynamics. The cognitive update interval $T_{\text{cognition}}$ is an independent execution parameter in `MirovaParameters`.

#### D. Safety Risks & Safety-Critical Patterns
Flagged patterns whose correctness depends on tick-exact re-evaluation:
- [`PreventUndercuttingPattern`](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/IntentionLayer/ManeuverPatterns/PreventUndercuttingPattern.java): Monitors left-side neighbor speed. If a left vehicle decelerates suddenly, delaying re-evaluation by $1.0\text{ s}$ can lead to illegal right-side overtaking or side collisions.
- [`GapOpenerPattern`](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/IntentionLayer/ManeuverPatterns/GapOpenerPattern.java): Monitors adjacent turn indicators. Delayed response can block merging vehicles or force emergency braking.
- [`MandatoryLaneChangePattern`](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/IntentionLayer/ManeuverPatterns/MandatoryLaneChangePattern.java#L110) (`EmergencyStopState`): Approaching a ramp end requires tick-exact gap checking to prevent overshooting the physical lane end buffer ($10\text{ m}$).
- **Hard Braking Safety Override**: The leader hard-braking check ($a < -1.0\text{ m/s}^2$) in `MirovaCarFollowingUtil.followSingleLeader()` MUST remain active on every tick.

### 4.3 Feasibility Classification & Categorization

| Category | Component / Method | Requirements / Refactoring Needed |
| :--- | :--- | :--- |
| **Feasible without changes** | Layer 4 Reactive Car-Following (`MirovaCarFollowingUtil`) | Executed at full $0.2\text{ s}$ resolution every tick. |
| **Requires refactoring** | `MirovaTacticalPlanner.update()` | **Decoupled Cognitive Schedule**:<br>1. Add $T_{\text{cognition}}$ parameter to `MirovaParameters`.<br>2. Cache Layer 2 desires and Layer 3 winning pattern.<br>3. Bypass Layer 2 & 3 on intermediate ticks during stable traffic.<br>4. Force immediate $0.2\text{ s}$ re-evaluation if an edge trigger occurs (leader hard braking, turn signal detected, or active relaxation). |
| **Not feasible / High Risk** | Relaxing update frequency for safety-critical patterns (`PreventUndercuttingPattern`, `EmergencyStopState`) | De-prioritizing safety checks in dense or unstable traffic compromises simulation fidelity and safety guarantees. |

---

## 5. Recommendation & Ranking

The three parallelization / optimization strategies are ranked below based on:
1. **Implementation Effort**: Code complexity and architectural changes required.
2. **Risk to Correctness Guarantees**: Impact on `MirovaCarFollowingUtil` invariants, Keane & Gao relaxation dynamics, and 3-step plan arbitration.
3. **Expected Performance Benefit**: Evaluated specifically for the target scenario (Freiburg Nord / A5 single interchange: ~100–500 active vehicles).

### 5.1 Strategy Evaluation Matrix

| Criterion | Strategy 1: Task-Level Parallelism | Strategy 2: Vehicle Grouping | Strategy 3: Relaxed Update Frequency |
| :--- | :--- | :--- | :--- |
| **Implementation Effort** | **Moderate** (Requires 2-phase barrier architecture in simulation loop) | **High** (Requires `PlatoonManager` and new Queue-Following planner bypass) | **Low** (Requires caching Layer 2/3 outputs and adding a tick timer) |
| **Risk to Correctness** | **Low** (Zero risk if 2-phase barrier is strictly enforced) | **High** (Risk of missing cut-ins, desynchronizing FSM timers, or causing collisions) | **Low–Moderate** (Low risk if safety-critical overrides remain on 1-tick resolution) |
| **Expected Benefit (Freiburg Nord / A5)** | **Low** (High thread barrier synchronization overhead for small N ≈ 100–500 GTUs) | **Low–Moderate** (Benefit limited to standing queues on off-ramps) | **High** (2x–3x reduction in cognitive layer evaluations across all free-flow vehicles) |

### 5.2 Overall Ranking

#### 🥇 Rank 1: Strategy 3 — Relaxed Update Frequency by Traffic State
- **Rationale**: Strategy 3 provides the highest performance return per unit of implementation effort. In highway interchange scenarios like Freiburg Nord / A5, the vast majority of vehicles spend significant time in stable free-flow or steady car-following. Re-evaluating heavy cognitive structures (`RouteIncentive`, `CruisingSpeedIncentive`, complex FSM checks) every $1.0\text{ s}$ instead of $0.2\text{ s}$ cuts cognitive CPU load by up to $80\%$ for those vehicles, without altering physical parameter `T` or compromising car-following safety.
- **Primary Guardrail**: Ensure safety-critical pattern checks (hard leader braking, `PreventUndercuttingPattern`, `EmergencyStopState`) trigger immediate 1-tick re-evaluations.

#### 🥈 Rank 2: Strategy 1 — Task-Level Parallelism (Two-Phase Barrier)
- **Rationale**: Strategy 1 is architecturally sound and completely eliminates data races if implemented as a Two-Phase Tick Execution barrier (Parallel Plan Generation $\rightarrow$ Sequential State Application). However, for a single interchange scenario (Freiburg Nord / A5 with ~100–500 GTUs), thread dispatch and barrier synchronization overhead in Java will diminish multi-core speedup.
- **Context Note**: [`ScenarioManager`](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-demo/src/main/java/org/opentrafficsim/demo/mirova/scenariomanagement/ScenarioManager.java#L92) already achieves near-linear CPU core utilization by running independent scenario replications in parallel. Strategy 1 is best reserved for single large-scale city simulations (10,000+ GTUs).

#### 🥉 Rank 3: Strategy 2 — Vehicle Grouping Under Congestion
- **Rationale**: Strategy 2 carries the highest architectural risk. Skipping perception and tactical layers for queue followers jeopardizes MiRoVA's core behavioral innovations: `NeighborsContext` passive cut-in detection, `RelaxationState` headway decay, and `ManeuverPattern` state machine timers. Furthermore, constructing and maintaining dynamic platoon structures from scratch adds substantial complexity for limited performance gain outside standing queues.

---

## 6. Open Questions for Marvin

1. **Multi-Run Parallelism vs. Intra-Simulation Parallelism**: Since `ScenarioManager` already parallelizes full simulation runs across CPU cores via `Executors.newFixedThreadPool`, is intra-simulation GTU parallelization (Strategy 1) a priority, or is improving single-thread simulation throughput (Strategy 3) preferable?
2. **Cognitive Re-Evaluation Granularity**: For Strategy 3, should the cognitive re-evaluation interval ($T_{\text{cognition}} \approx 0.5\text{ s} - 1.0\text{ s}$) be a global GTU parameter or dynamically adjusted per vehicle based on local traffic density?
3. **Safety-Critical Pattern Whitelist**: Does Marvin agree with the proposed whitelist of safety-critical patterns (`PreventUndercuttingPattern`, `GapOpenerPattern`, `MandatoryLaneChangePattern.EmergencyStopState`) that must remain locked to 1-tick resolution ($0.2\text{ s}$)?
