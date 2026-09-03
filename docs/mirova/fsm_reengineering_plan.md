# FSM Re-Engineering Plan (Layer 3)

Status: in progress on branch `fsm_reengineering`.
Goal: make the `ManeuverPattern` / `ActionState` machinery an actual state machine in the
software-design sense, **without changing a single simulation result**, and only then decide the
one place where a behavioural change is unavoidable.

---

## 1. Why

The MiRoVA Layer 3 machinery is described as an FSM and behaves like one, but it is not built like
one. Six patterns are currently active:

| Pattern | Lines | States |
|:--|--:|--:|
| `MandatoryLaneChangePattern` | 2214 | 9 |
| `GapOpenerPattern` | 552 | 1 |
| `AnticipateDownstreamMergePattern` | 511 | 2 |
| `PreventUndercuttingPattern` | 510 | 2 |
| `SimpleLaneChangePattern` | 324 | 1 |
| `AnticipateAdjacentCongestionPattern` | 217 | 1 |

`MandatoryLaneChangePattern` is not an outlier; it is simply the place where the weaknesses of the
shared framework become visible. The findings below apply to all six.

### 1.1 The constructor mutates the machine

`ActionState`'s constructor calls `setCurrentActionState(this)` and `setRunning(true)`. A state
therefore cannot be *constructed* without *entering* it. In
`MandatoryLaneChangeState.checkCommonTransitions` the expression `new ExecuteLaneChangeState(...)`
mutates the pattern before `transitionTo` has even been called. Look-ahead ("which state would
apply?") and unit-testing a state in isolation are both impossible.

### 1.2 `next()` returns a plan, not a target

Transitions are executed by `transitionTo()` recursively calling `nextState.update()`. Two
consequences:

- Transition chains run as **unbounded recursion** within a single tick.
  `CongestedMergeState` -> `CongestedFollowLeaderState` -> (parallel block) -> `CongestedMergeState`
  is a topologically reachable cycle with no depth guard.
- The target state's `abort()` and `next()` re-run in the same tick, so guard evaluation order is
  coupled to call order instead of being stated explicitly.

### 1.3 One "state" is really a choice node

`CongestedMergeState.executeControl()` is effectively unreachable: its `next()` always transitions.
It is a UML *choice pseudostate* modelled as a state, because the framework has no notion of a
decision node.

### 1.4 No entry/exit hooks; states are re-instantiated on every transition

State-local data (the EMA filter in `AnticipateMergeState`, timers) is silently reset on every
re-entry. That is a model effect, not a design effect, and it is currently documented nowhere.

### 1.5 The guard hierarchy is copied by hand

Five `next()` implementations in `MandatoryLaneChangePattern` open with `checkCommonTransitions(...)`.
That is by definition a *superstate transition*. `EmergencyStopState.next()` does **not** call it --
whether deliberately or by omission cannot be told from the code. See section 4, stage 5.

### 1.6 Cross-pattern states are an undeclared submachine

Three patterns transition into `SimpleLaneChangePattern.PerformLaneChangeState`, constructed with
*their own* pattern as the parent:

- `GapOpenerPattern:412`
- `PreventUndercuttingPattern:231`, `:434`
- `AnticipateDownstreamMergePattern:258`

This is a submachine: a reusable sub-automaton with a defined entry and exit. In the code it is just
a `static class` that happens to live in another file. And `MandatoryLaneChangePattern` does **not**
use it: `ExecuteLaneChangeState` is a copy of `PerformLaneChangeState` -- same relaxation trigger,
same minimum over the target-lane leaders, same `originLane` completion test. The only substantial
difference is `commitToAction()`. Two copies of lane-change execution that drift apart separately.

### 1.7 Lifecycle is scattered over six places

`setRunning(true)` in the `ActionState` constructor, again in `PerformLaneChangeState.executeControl()`
(every tick), `setRunning(false)` in `finishManeuver()`; `releaseActionLock()` partly in
`transitionTo`, partly by hand before `finishManeuver()` (`MandatoryLaneChangePattern:2172`, `:2192`,
the latter carrying the comment `HIER EINFUEGEN`); `commitToAction()` in `executeControl` every tick.

A concrete defect this produces -- `PreventUndercuttingPattern:433`:

```java
finishManeuver();                                    // return value discarded, isRunning := false
return transitionTo(new SimpleLaneChangePattern.PerformLaneChangeState(...));
```

The pattern is marked finished and then a state is entered. That this is currently harmless rests
solely on `PerformLaneChangeState.executeControl()` setting `isRunning` back to `true` every tick.

### 1.8 Termination is modelled inconsistently

Sometimes in `abort()` (`NearAnticipationState`, `GapOpenerPattern` with three exits), sometimes in
`next()` (`PerformLaneChangeState`), sometimes both. `abort()` is not semantically an abort but
"the highest-priority guard", and in four classes it is wrapped in
`catch (Exception) { printStackTrace(); }`.

---

## 2. Guiding principle

Not *one* generic FSM framework for everything, but **two levels**: a minimal contract that all six
patterns satisfy, and a hierarchy extension that only `MandatoryLaneChangePattern` needs. The four
small patterns must not become more complicated in the process.

Explicitly **not** proposed: a generic HSM engine, orthogonal regions, history states. The need is
two levels deep and will stay that way.

---

## 3. Definition of "same functionality"

Behavioural equivalence means: **the trace is bit-identical.** Stage 0 builds that trace. No later
stage is considered done until the trace test passes, except stage 5, whose deviation is deliberate,
isolated, and documented.

---

## 4. Stages

### Stage 0 -- Regression net (prerequisite for everything) -- DONE

`FsmTraceRecorder` is pushed once from the end of `MirovaTacticalPlanner.update()`, where the
pattern, the action state and the acceleration the vehicle acts on are all settled. It writes one
gzipped row per vehicle per tick:
`(t, gtuId, pattern, state, a, indicator, laneChange)`, sorted by (time, vehicle) so the file does
not depend on the order the simulator happened to visit the vehicles. It is off unless started, and
costs one static null check per vehicle per tick while off.

`FsmTraceHarness` runs a fixed-seed case; `FsmTraceRegressionTest` compares it against the committed
reference and reports the *first* differing row, which is the only one that says where the machine
took a different branch. The test is gated behind `-Dmirova.fsmtrace=true`, because a case is a full
headless simulation.

**Determinism is verified**: two runs of the merge case from the same seed produce byte-identical
traces.

#### What the merge reference covers

600 s of `MergeScenario` at seed 42 sweeps the demand from 1000 to 6500 veh/h, giving 289 395 rows.
Reached: `AnticipateMergeState`, `SynchroniseMergeSpeedState`, `MatchLeaderSpeedState`,
`SolveParallelVehicleState`, `EmergencyStopState`, `ExecuteLaneChangeState`,
`CongestedFollowLeaderState`, plus `GapOpenerPattern`, `PreventUndercuttingPattern` and
`SimpleLaneChangePattern` throughout.

#### Known gaps, to be closed before stage 5

- **`CongestedCreepState` is never entered** in the reference. The congested branch is therefore only
  partly netted, and stage 5 restructures exactly that branch. A case that reaches it is needed
  first.
- **`CongestedMergeState` produces no row at all.** This is not a gap but a measurement: as the
  router it always transitions, so it never produces a plan. Section 1.3 predicted this; the trace
  confirms it.
- **`AnticipateDownstreamMergePattern` and `AnticipateAdjacentCongestionPattern` are not reached**
  by the merge case.
- **The highway case does not run.** `SimpleHighwayScenario` at 4000 veh/h destroys GTUs with a
  `NullPointerException` and the watchdog aborts the run at 300 s for lack of detector flow. This is
  a pre-existing scenario problem, not a Layer 3 one, so the case is kept in the harness without a
  reference; the test skips a case whose reference is missing. A `FreiburgNord` case is the likely
  replacement for the missing coverage.

Ticks in which `update()` is not reached at all -- a GTU not yet fully positioned, or a tick the
deadlock watchdog cuts short -- produce no row. That is deliberate: the trace covers the decision
cycle, and none of those paths involve the FSM.

Side benefit: the trace directly yields the state-occupancy statistics for the dissertation.

### Stage 1 -- Decouple the `ActionState` contract

Split into two steps, each verified on its own, because the lifecycle turned out to be more
entangled than the plan assumed.

**Stage 1a -- construction no longer enters (done, trace verified).** The constructor no longer calls
`setCurrentActionState(this)` / `setRunning(true)`. Entering is `enter()`, which does that
bookkeeping in one place and then calls the new `onEntry()` hook; `exit()` calls `onExit()`. Only the
transition machinery and `ManeuverPattern.update()` call them.

**Stage 1b -- `next()` returns a target instead of a plan (not started).** Held back deliberately:
`isRunning` is read per tick by `PatternSelector` to decide whether `checkContext()` runs at all, and
several states re-assert `setRunning(true)` from inside `executeControl()` while
`FarAnticipationState` and `AdjacentCongestionState` clear it every tick on purpose. Those
per-tick assertions are redundant now that `enter()` exists, but removing them changes when a
pattern counts as running, so it needs its own verified step rather than being folded into 1a.

#### The contract, once 1b is done

```java
public abstract class ActionState {
    protected ActionState(ManeuverPattern p) { ... }   // no side effects any more
    protected void onEntry() {}
    protected void onExit()  {}
    public abstract SimpleOperationalPlan executeControl();
    public abstract ActionState next();                 // pure decision, no plan
    public double getUtility();
}
```

- `next()` returns a target state or `null`; `finishManeuver()` becomes the sentinel
  `ActionState.TERMINAL`.
- `abort()` disappears as a separate method -- it is a guard. Where it is pattern-wide identical
  (desire &lt; `DMAND`) it moves to the superstate in stage 5; the pattern-specific exits become
  ordinary, high-priority transitions.
- `setRunning` / `releaseActionLock` / `commitToAction` leave constructors and `executeControl`
  and are served exclusively by `onEntry` / `onExit` and the driver loop.

### Stage 2 -- Centralise the driver loop

`ManeuverPattern.update()` resolves transition chains iteratively instead of recursively:

```java
int depth = 0;
while (depth++ < MAX_TRANSITIONS_PER_TICK) {
    ActionState target = this.current.next();
    if (target == null) { break; }
    this.current.onExit();
    if (target == ActionState.TERMINAL) { terminate(); break; }
    target.onEntry();
    this.current = target;
}
return this.current.executeControl();
```

Semantically identical to today's recursion (chain within one tick, last state produces the plan),
but bounded and in one place. The `CongestedMerge` cycle becomes loggable instead of a
`StackOverflowError`.

### Stage 3 -- Declarative transition table

One ordered list per state, built once:

```java
transitions = List.of(
    on(() -> desire() < DMAND,                 () -> TERMINAL),
    on(() -> possible(dir) && mayExecute(dir), () -> new ExecuteLaneChange(dir)),
    on(() -> stopAccel() < CRITICAL,           () -> new EmergencyStop()));
```

The order encodes exactly today's `if` cascade, so equivalence is checkable by inspection and not
only by the trace. Gain: the graph becomes machine-readable (PlantUML export for the dissertation),
and a test can pin the guard order.

### Stage 4 -- `LateralExecutionState` as a real submachine

Merge `ExecuteLaneChangeState` and `PerformLaneChangeState` into **one** class, parameterised by
`direction`, `cooperative` (flag already exists) and `commit` (whether `commitToAction` is called --
the only real difference). Every pattern then shares the same execution state, and the relaxation
trigger exists once. Largest gain per unit of risk: purely mechanical, trace-verifiable, removes
about 110 duplicated lines.

### Stage 5 -- Hierarchy, for `MandatoryLaneChangePattern` only

```
MandatoryLaneChange  (superstate: guards for ExecuteLaneChange / EmergencyStop / terminal)
+-- FreeFlowMerge   (v_ego >= 15 km/h) : Anticipate -> Synchronise <-> MatchLeader <-> SolveParallel
+-- CongestedMerge  (v_ego <  15 km/h) : [choice] -> Creep | FollowLeader   (exit: v_ego > 30 km/h)
```

`CompositeActionState`: `next()` checks its own transitions first, then delegates to the substate.
Effects:

- `checkCommonTransitions` exists once instead of being copied five times.
- The 15/30 km/h hysteresis exists once instead of at four sites (`:831`, `:1376`, `:1573`, `:1875`).
- `CongestedMergeState` disappears as a class -- it *is* the composite's choice node.

**This stage contains the only genuine model decision of the whole rework.**
`EmergencyStopState.next()` does not call `checkCommonTransitions` today. Under the superstate it
would do so automatically. That changes behaviour and the trace will deviate. It must be decided and
justified deliberately -- presumption: intentional, because the desire-based termination should not
fire during an emergency -- not carried along silently.

### Stage 6 -- Tidy up

The four single-state patterns get a `SingleStatePattern` base that pre-fills `next()` / `onEntry` /
`onExit` sensibly. Their code shrinks without them having to take part in the hierarchy machinery.

---

## 5. Order and risk

| Stage | Effort | Trace changes | Note |
|:--|:--|:--|:--|
| 0 Trace test | small | -- | prerequisite |
| 1 API split | medium | no | touches all 6 patterns, mechanical |
| 2 Driver loop | small | no | |
| 4 Submachine | small | no | best gain/risk ratio |
| 3 Transition table | medium | no | makes the graph exportable |
| 5 Hierarchy | large | **yes, at EmergencyStop** | model decision required |
| 6 SingleStatePattern | small | no | |

Pulling stage 4 ahead of stage 3 is deliberate: removing the duplicate pays off immediately and does
not need the table.

---

## 6. Progress

| Stage | State |
|:--|:--|
| 0 Trace test | **done** (merge reference recorded and verified deterministic; congested-creep and anticipation coverage still missing) |
| 1a Constructor / enter-exit | **done**, trace verified on both cases |
| 1b `next()` returns a target | not started |
| 2 Driver loop | not started |
| 4 Submachine | not started |
| 3 Transition table | not started |
| 5 Hierarchy | not started |
| 6 SingleStatePattern | not started |
