# MiRoVA Framework — Development Context

## 1. Role & Project
This repository is OpenTrafficSim (OTS). On top of it lives the **MiRoVA** (Migration of Road Vehicle Automation) framework: a cognitive extension layer that implements human-like, physically consistent, and computationally efficient driving behaviors. The primary module for MiRoVA code is `ots-road` and `ots-demo` (under `org.opentrafficsim.demo.mirova`).

**Developer**: Marvin Baumann (KIT — Karlsruhe Institute of Technology)
**Current focus**: SP6 (Sub-Project 6) of the MiRoVA project. The original project proposal exists but is outdated — defer to current code and Marvin's descriptions for the actual state of work.

**Supplementary material** (papers, reference docs): `.claude/material/`
- `IEEE_ITSC_...pdf` — Marvin's own paper describing the architecture (ITSC 2026, proof-of-concept on merging scenario). This is the primary reference for the intended design.
- `Keane und Gao - 2021...pdf` — Foundation for the `RelaxationState` implementation. The paper gives a two-parameter model; the code implements the one-parameter case, see §5.
- `Berghaus und Oeser - 2025...pdf` — Foundation for the speed-synchronisation states of `MandatoryLaneChangePattern` (`SynchroniseMergeSpeedState`, `MatchLeaderSpeedState`). There is no `GapSearchPattern` and no `AccelToGap` state; both names are historical. Provides the DTH car-following model and calibrated parameter values (T_des≈0.7s for mergers, τ_LC=6s, DRAC_min≈-0.1 to -1.5 m/s²) from German freeway data (A59 Duisburg, A4 Cologne).
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
- **Parameter access & DJUnits usage** (the snapshot, which parameters must never be snapshotted, SI-vs-scalar arithmetic, equivalence checks): [docs/mirova/parameter_access_and_units.md](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/docs/mirova/parameter_access_and_units.md)
- **Performance: where the CPU goes, and why** (incl. the `LaneBasedGtu.CACHING=false` decision): [docs/mirova/performance_investigation_synthesis.md](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/docs/mirova/performance_investigation_synthesis.md)
- **Decoupling from OTS — Phase 0 inventory** (every OTS/DJUnits/DSOL touchpoint, the query surface a host must
  answer, the red flags): [docs/decoupling/inventory.md](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/docs/decoupling/inventory.md)
- **Decoupling — Phase 0.5 report** (what was repaired, what is behind a behaviour switch and what each switch does):
  [docs/decoupling/phase05-report.md](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/docs/decoupling/phase05-report.md)
- **Parameter reference** (every parameter, its default, where it is read, snapshot or live, and which are dead):
  [docs/decoupling/parameters.md](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/docs/decoupling/parameters.md)
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

- **Units**: Use DJUnits (`Length`, `Speed`, `Acceleration`, `Duration`) on all signatures, fields and return values. Never use primitive `double` for a physical value that crosses a boundary. Intermediate arithmetic *inside* a method may run on `.si` doubles and be wrapped once at the end — see [parameter_access_and_units.md](docs/mirova/parameter_access_and_units.md) for when this applies and why it is bit-identical.
- **Parameters**: Read constant parameters from `vehicle.getParams()` (the `MirovaParameterSnapshot`), not via `getParameter`. Before adding a parameter to the snapshot, verify nothing writes it at runtime — a snapshotted mutable parameter silently freezes. Physical literals in per-tick code belong in named `static final` constants.
- **Language**: All code, comments, and documentation in English.
- **Javadoc**: Strict KIT/MiRoVA header template (Copyright 2026, Marvin Baumann). Escape generics in Javadoc (e.g., `List&lt;Gtu&gt;`). No empty tags.
- **Performance**: Favor O(1) lookups. Use ID-based caching for expensive car-following evaluations within the same simulation tick.
- **Imports**: Always include full imports and the mandatory MiRoVA class header.
- Do not modify `ParameterTypes.T` in tactical states (parameter-hacking is being replaced).

## 5. Current Focus: Longitudinal Control & Relaxation (Keane & Gao 2021)

Replacing parameter-hacking (e.g., temporarily reducing `T` or `s_0`) with a relaxation model.

### Key Components

**`RelaxationState`** — a **one-parameter** model on the headway buffer
- Manages the exponential decay of a spatial headway deficit (γ_s) with time constant **τ_s = 20 s**
  (`RELAXATION_TAU_SPACE`, default `tau_relax_s`).
- There is **no speed buffer**. Keane & Gao describe a second, independent decay on the speed difference;
  MiRoVA does not use it. Where a cut-in also costs speed, the deficit is routed into the *headway* buffer
  instead — the buffer is seeded with `desiredHeadway × safetyDistanceReductionFactorLaneChange` rather than
  with the raw gap deficit. Tolerating a speed difference directly produced collisions.
- Three further mechanisms sit on top of the buffer and are part of the model:
  - **Acceleration damping** (`aRelaxDamping`, default 0.40): while a relaxation is active, positive
    acceleration is scaled by a factor rising from 0.40 back to 1.0 as the buffer decays.
  - **Lifetime cap** (`relaxMaxLifetime`, default 3.0): a relaxation is collected after 3·τ_s regardless of
    how large its initial deficit was.
  - **Fade-out on abort** (`tRelaxFade`, default 1.0 s): when a relaxation is abandoned — the leader brakes
    past `aRelaxAbort` or drops below 10 km/h — the buffer fades to zero over this interval rather than being
    dropped in one step, which would be a braking impulse the model inflicts on itself.

**`MirovaCarFollowingUtil`**
- Transparent wrapper utility for all car-following calls
- Intercepts acceleration requests and injects the virtual distance buffer from `EgoContext`
- Must be used for **all** acceleration calculations — never call the car-following model directly

**ID-Based Caching (`EgoContext.tickAccelerationCache`)**
- Cleared every `update()` tick
- Ensures the car-following model is evaluated at most once per leader per tick, even if multiple patterns/states query it

**Passive Cut-In Detection (`NeighborsContext`)**
- Acts as an edge-trigger: detects when a leader ID changes
- Notifies `EgoContext` to initialize a new `RelaxationState` for the new leader

**Proactive Triggering**
- `LateralExecution.accelerationForLateralMove` pre-registers a relaxation with a reduced safety distance for
  every leader on the lane being entered, and for the current leader, while the lateral movement has not yet
  begun. This is what lets a merger accept the tighter headway it is deliberately taking.
- It is called by both lane-change execution states, so it covers the mandatory and the discretionary path.

## 6. Merging & Anticipation Logic

- **Long-Range Anticipation**: `MandatoryLaneChangePattern.checkContext` and `AnticipateMergeState` ask
  `InfrastructureContext.getDistanceToLaneChangeExtendedLookahead()`, which raises `LOOKAHEAD` to
  `extendedLookAheadDistance` (1000 m) around one perception query.
  **Caveat, measured in Phase 0.5:** that query is memoised by OTS per vehicle per tick and keyed by the
  relative lane alone, so the raise has no effect once anything else has asked in the same tick — and the
  desires always have, since they run before the patterns. The extended look-ahead is therefore very likely
  inert; see `docs/decoupling/phase05-report.md` (BC-7). The merge reference speed is a separate mechanism and
  is unaffected.
- **Signal Smoothing**: Exponential Moving Average low-pass filter on the merge reference speed in
  `AnticipateMergeState`. The smoothing factor is `0.25 · DT`, taken from the `DT` **parameter** rather than
  from the interval the planner was actually called with, computed linearly rather than as `1 − exp(−dt/τ)`,
  and fixed once in the constructor. `BC-1` offers the corrected form behind a switch.

## 7. Verification Checklist (apply when generating or refactoring code)

- [ ] No `ParameterTypes.T` modifications in tactical states
- [ ] `MirovaCarFollowingUtil` used for all acceleration calculations
- [ ] All public methods have complete Javadocs (KIT/MiRoVA standard)
- [ ] Full imports included
- [ ] Mandatory MiRoVA class header present
- [ ] Physical values use DJUnits on signatures, fields and return values
- [ ] Constant parameters read from `getParams()`, not `getParameter`
- [ ] Nothing added to the snapshot is written at runtime
- [ ] No physical literals built inline in per-tick code
- [ ] No behaviour change smuggled into a refactoring commit. A commit is labelled `[no behaviour change]` or
      `[behaviour change]`, and a behaviour change sits behind a switch whose default reproduces the published
      model. See `docs/decoupling/phase05-report.md`.
- [ ] Nothing reads another agent's parameters, car-following model or internal state. Only what a driver can
      observe: position, net gap, speed, acceleration, length, indicator, identity.

## 8. Modular Git Commit Rule

- **Conventional Commits**: Always use structured, conventional commit messages (`feat(...)`, `fix(...)`, `refactor(...)`, `docs(...)`).
- **Cluster by Subsystem**: Never create monolithic commits across unrelated layers. Separate core logic, watchdog fixes, maneuver patterns, scenario runners, and evaluation scripts into distinct commits.


