# MiRoVA Framework — Development Context

## 1. Role & Project
This repository is OpenTrafficSim (OTS). On top of it lives the **MiRoVA** (Migration of Road Vehicle Automation) framework: a cognitive extension layer that implements human-like, physically consistent, and computationally efficient driving behaviors. The primary module for MiRoVA code is `ots-road` and `ots-demo` (under `org.opentrafficsim.demo.mirova`).

**Developer**: Marvin Baumann (KIT — Karlsruhe Institute of Technology)
**Current focus**: SP6 (Sub-Project 6) of the MiRoVA project. The original project proposal exists but is outdated — defer to current code and Marvin's descriptions for the actual state of work.

**Supplementary material** (papers, reference docs): `.claude/material/`
- `IEEE_ITSC_...pdf` — Marvin's own paper describing the architecture (ITSC 2026, proof-of-concept on merging scenario). This is the primary reference for the intended design.
- `Keane und Gao - 2021...pdf` — Foundation for the RelaxationState implementation (τ_s≈15s, τ_v≈5s)
- `Berghaus und Oeser - 2025...pdf` — Foundation for the `AccelToGap` state in `GapSearchPattern`/`MandatoryLaneChangePattern`. Provides the DTH car-following model and calibrated parameter values (T_des≈0.7s for mergers, τ_LC=6s, DRAC_min≈-0.1 to -1.5 m/s²) from German freeway data (A59 Duisburg, A4 Cologne).
- `SP6 final candidate.pdf` — DFG project proposal. Note: originally described SUMO, but OTS was chosen instead (correct decision for this architecture).

## 2. Comprehensive Documentation Map
To save context window token usage and avoid parsing raw codebase files, refer directly to the modular documentation files under `docs/mirova/`:
- **Landing Map / Overview**: [docs/mirova/README.md](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/docs/mirova/README.md)
- **OTS Integration & GTU Lifecycle**: [docs/mirova/ots_integration.md](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/docs/mirova/ots_integration.md)
- **Layer 1: Perception & Belief**: [docs/mirova/layer1_perception_belief.md](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/docs/mirova/layer1_perception_belief.md)
- **Layer 2: Desire / Motivation**: [docs/mirova/layer2_desire.md](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/docs/mirova/layer2_desire.md)
- **Layer 3: Intention / FSMs**: [docs/mirova/layer3_decision_intention.md](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/docs/mirova/layer3_decision_intention.md)
- **Layer 4: Reactive / Execution**: [docs/mirova/layer4_reactive_control.md](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/docs/mirova/layer4_reactive_control.md)
- **Arbitration & Plan Selector**: [docs/mirova/arbitration.md](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/docs/mirova/arbitration.md)
- **Parameter influence on the congested branch** (what each behavioural parameter measurably does, and what is not a parameter effect): [docs/mirova/parameter_sensitivity.md](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/docs/mirova/parameter_sensitivity.md)
- **Scenario Management**: [docs/mirova/scenarios_and_simulations.md](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/docs/mirova/scenarios_and_simulations.md)
- **OTS XML Format**: [docs/mirova/ots_xml_format.md](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/docs/mirova/ots_xml_format.md)
- **OTS Editor Reference**: [docs/mirova/ots_editor.md](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/docs/mirova/ots_editor.md)
- **Performance: where the CPU goes, and why** (incl. the `LaneBasedGtu.CACHING=false` decision): [docs/mirova/performance_investigation_synthesis.md](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/docs/mirova/performance_investigation_synthesis.md)
- **Python Pipeline (diss_mvb)**: [docs/mirova/python_pipeline.md](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/docs/mirova/python_pipeline.md)



## 3. The Four-Layer Architecture ("The Loop")
All implementations must follow this layered structure:

| Layer | Name | Responsibility |
|---|---|---|
| 1 | **Perception & Context** (`ContextManager`) | Filters raw OTS perception into semantic contexts: `EgoContext`, `NeighborsContext`, `InfrastructureContext` |
| 2 | **Cognition** (`KnowledgeChunk`) | Computes dimensionless or physical *Desires* (motivations) — no actions |
| 3 | **Decision** (`PatternSelector`) | Selects the active `ManeuverPattern` based on aggregated desires |
| 4 | **Procedure & Action** (`ManeuverPattern` / `ActionState`) | Implements the FSM and returns the `SimpleOperationalPlan` |

## 4. Coding Standards

- **Units**: Use DJUnits (`Length`, `Speed`, `Acceleration`, `Duration`) exclusively. Never use primitive `double` for physical values.
- **Language**: All code, comments, and documentation in English.
- **Javadoc**: Strict KIT/MiRoVA header template (Copyright 2026, Marvin Baumann). Escape generics in Javadoc (e.g., `List&lt;Gtu&gt;`). No empty tags.
- **Performance**: Favor O(1) lookups. Use ID-based caching for expensive car-following evaluations within the same simulation tick.
- **Imports**: Always include full imports and the mandatory MiRoVA class header.
- Do not modify `ParameterTypes.T` in tactical states (parameter-hacking is being replaced).

## 5. Current Focus: Longitudinal Control & Relaxation (Keane & Gao 2021)

Replacing parameter-hacking (e.g., temporarily reducing `T` or `s_0`) with a **2-parameter relaxation model**.

### Key Components

**`RelaxationState`**
- Manages exponential decay of spatial headway deficits (γ_s) and speed differences (γ_v)
- Independent time constants: τ_s ≈ 15 s, τ_v ≈ 5 s

**`MirovaCarFollowingUtil`**
- Transparent wrapper utility for all car-following calls
- Intercepts acceleration requests and injects virtual distance/speed buffers from `EgoContext`
- Must be used for **all** acceleration calculations — never call the car-following model directly

**ID-Based Caching (`EgoContext.tickAccelerationCache`)**
- Cleared every `update()` tick
- Ensures the car-following model is evaluated at most once per leader per tick, even if multiple patterns/states query it

**Passive Cut-In Detection (`NeighborsContext`)**
- Acts as an edge-trigger: detects when a leader ID changes
- Notifies `EgoContext` to initialize a new `RelaxationState` for the new leader

**Proactive Triggering**
- `MandatoryLaneChangePattern` can pre-register a `RelaxationState` for a target leader on an adjacent lane
- Enables smooth merging before the lane change is physically completed

## 6. Merging & Anticipation Logic

- **Long-Range Anticipation**: `AnticipateMergeState` uses a temporary lookahead boost (up to 1000 m) to sample average speed at bottlenecks.
- **Signal Smoothing**: Exponential Moving Average low-pass filter on anticipated speeds; smoothing factor α derived from simulation timestep `dt` and time constant τ.

## 7. Verification Checklist (apply when generating or refactoring code)

- [ ] No `ParameterTypes.T` modifications in tactical states
- [ ] `MirovaCarFollowingUtil` used for all acceleration calculations
- [ ] All public methods have complete Javadocs (KIT/MiRoVA standard)
- [ ] Full imports included
- [ ] Mandatory MiRoVA class header present
- [ ] Physical values use DJUnits, not primitive `double`

## 8. Modular Git Commit Rule

- **Conventional Commits**: Always use structured, conventional commit messages (`feat(...)`, `fix(...)`, `refactor(...)`, `docs(...)`).
- **Cluster by Subsystem**: Never create monolithic commits across unrelated layers. Separate core logic, watchdog fixes, maneuver patterns, scenario runners, and evaluation scripts into distinct commits.


