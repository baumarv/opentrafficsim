# Workspace Rules: MiRoVA Framework

## Context & Token Optimization Rule

> [!IMPORTANT]
> To save token usage and prevent context limits from being reached, you **MUST NOT** parse or read the entire OpenTrafficSim/MiRoVA codebase at the start of your tasks. 
> 
> Instead, refer to the modular documentation map in [CLAUDE.md](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/CLAUDE.md#L15-L24) and read only the specific documentation file under `docs/mirova/` that is relevant to the task before inspecting code:
> - Central Entrypoint: [docs/mirova/README.md](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/docs/mirova/README.md)
> - OTS Integration & GTU Lifecycle: [docs/mirova/ots_integration.md](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/docs/mirova/ots_integration.md)
> - Layer 1: Perception & Belief: [docs/mirova/layer1_perception_belief.md](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/docs/mirova/layer1_perception_belief.md)
> - Layer 2: Desire / Motivation: [docs/mirova/layer2_desire.md](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/docs/mirova/layer2_desire.md)
> - Layer 3: Intention / FSMs: [docs/mirova/layer3_decision_intention.md](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/docs/mirova/layer3_decision_intention.md)
> - Layer 4: Reactive / Control: [docs/mirova/layer4_reactive_control.md](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/docs/mirova/layer4_reactive_control.md)
> - Arbitration & Decision: [docs/mirova/arbitration.md](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/docs/mirova/arbitration.md)
> - Scenario Management: [docs/mirova/scenarios_and_simulations.md](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/docs/mirova/scenarios_and_simulations.md)
> - OTS XML Format: [docs/mirova/ots_xml_format.md](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/docs/mirova/ots_xml_format.md)
> - OTS Editor Reference: [docs/mirova/ots_editor.md](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/docs/mirova/ots_editor.md)
> - Python Evaluation Pipeline: [docs/mirova/python_pipeline.md](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/docs/mirova/python_pipeline.md)

## Implementation Checklist

Whenever you generate or modify code in the MiRoVA framework, check the Verification Checklist in [CLAUDE.md](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/CLAUDE.md#L78-L86).

## Documentation Maintenance Rule

> [!IMPORTANT]
> Whenever you modify behavioral logic, tactical planner algorithms, or introduce new parameters or features (such as discretionary lane-changing constraints or deadlock watchdog settings), you **MUST** update the corresponding documentation files under `docs/mirova/` immediately to keep them fully synchronized with the implementation.

## Modular Git Commit Rule

> [!IMPORTANT]
> Whenever committing code changes, you **MUST** split modifications into logically clustered, modular commits following Conventional Commits syntax (`feat(...)`, `fix(...)`, `refactor(...)`, `docs(...)`) instead of dumping all changes into a single monolithic commit.
> 
> Standard commit grouping pattern:
> 1. **Core Behavioral & Parameter Logic**: `feat(mirova): ...` (Core algorithms, parameters, context models)
> 2. **Watchdogs & Safety Fixes**: `fix(mirova): ...` (Refining deadlocks, emergency stopping, edge-cases)
> 3. **Maneuver Patterns & Intention Layer**: `feat(mirova): ...` (FSM states, action patterns like GapOpenerPattern)
> 4. **Scenarios & Execution Scripts**: `feat(demo): ...` (Runner scripts, scenario management, parameters)
> 5. **Evaluation Pipeline & Dashboard**: `feat(scripts): ...` (Python plotting, dashboard table features in dependent repos)


