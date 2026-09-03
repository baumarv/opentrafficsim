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

**Stage 1b -- `next()` returns a target instead of a plan (done, trace verified).** `next()` and
`abort()` now answer with an `ActionState`: the target, the `FINISHED` sentinel, or `null`. They are
decisions and nothing else -- they do not enter anything, do not build a plan, and leave the machine
as they found it. `transitionTo` is gone, and with it the recursion; stage 2 came with this step
rather than after it, because a method that returns a target needs something to consume it.

Ending the maneuver is the `FINISHED` sentinel rather than a second method. That is what removed the
defect recorded in section 1.7: `PreventUndercuttingPattern` called `finishManeuver()` for its side
effects, threw the plan away and then transitioned, marking the pattern finished and immediately
entering a state in it. With one channel for the question "what does this state hand over to", the
contradiction cannot be written down any more.

**What is still open from 1b.** The per-tick `setRunning(true)` calls inside `executeControl()` are
redundant now that `enter()` exists, but removing them is a separate question: `isRunning` is read
per tick by `PatternSelector` to decide whether `checkContext()` runs at all, and
`FarAnticipationState` and `AdjacentCongestionState` clear it every tick on purpose. That is
behaviour wearing the clothes of bookkeeping, and it deserves its own verified step.

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

### Stage 2 -- Centralise the driver loop (done, trace verified, folded into 1b)

The loop lives in `ActionState.update()` rather than in `ManeuverPattern.update()` as sketched below.
`HybridPlanArbitrator` calls `update()` directly on the locked state, so the loop has to be reachable
from a state and not only from a pattern; putting it in the state serves both entry points without a
second copy.

`MAX_TRANSITIONS_PER_TICK` is 32. Exceeding it throws with the pattern and the last state named,
which turns the reachable `CongestedMerge` cycle from a `StackOverflowError` into a report.

The sketch this stage was planned from:

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

### Stage 3 -- Declarative transition table (done, trace verified)

Each state declares `transitions()`: an ordered list of named rules, built once per instance and cached. `next()` is final
and walks it, taking the first rule that applies. 17 tables, 28 rules.

**The plan's shape did not survive contact either.** It assumed a rule splits into a guard and a target factory. Three
things in the actual transitions do not fit that:

- several rules carry a value the guard computed into the state they lead to -- the vehicle alongside that
  `SolveParallelVehicleState` is given;
- the merge decision shares one expensive piece of analysis (follower deceleration, kinematic reachability of the
  downstream gap) across three possible outcomes;
- splitting either would mean computing things twice or passing them sideways through a field.

So a rule is a *named answer to one question* -- "what does this state hand over to, right now" -- returning the target or
`null`, and carrying its name and declared target alongside for description. Guard and target stay together because the code
needs them together.

**`abort()` is gone.** It was never an exceptional path: it was asked on every state in every tick and answered with a
state, which is to say it was the highest-priority guard under another name (section 1.8). It is now simply the first entry
in the same table.

**The superstate is now expressible, which is what stage 5 needs.** `MandatoryLaneChangeState.commonTransitions()` holds
the three rules that belong to the manoeuvre rather than to a phase of it, and each state's table is
`commonTransitions()` plus its own. Three states opt out and now say why in a comment instead of by omission:

| State | Takes | Why |
|:--|:--|:--|
| `AnticipateMergeState` | neither | the target lane is not alongside yet, so the gap rule has nothing to look at; carries its own end condition |
| `EmergencyStopState` | the desire rule only | its own gap rule is the same test without the readiness precondition, and the stop rule would be circular here |
| `ExecuteLaneChangeState` | neither | mid-crossing there is no gap to evaluate and no stopping to reconsider |

`EmergencyStopState` is the one the plan flagged as the stage-5 model decision. It is now a written-down decision rather
than a missing call, which is the whole point of doing stage 3 before stage 5.

**Export.** `TransitionGraphExport` writes the graph as PlantUML by asking the states for their tables. It needs live state
instances, since tables are built per instance and several rules are inherited; wiring it to a recorded run is a follow-up.

The sketch this stage was planned from:

```java
transitions = List.of(
    on(() -> desire() < DMAND,                 () -> TERMINAL),
    on(() -> possible(dir) && mayExecute(dir), () -> new ExecuteLaneChange(dir)),
    on(() -> stopAccel() < CRITICAL,           () -> new EmergencyStop()));
```

The order encodes exactly today's `if` cascade, so equivalence is checkable by inspection and not
only by the trace. Gain: the graph becomes machine-readable (PlantUML export for the dissertation),
and a test can pin the guard order.

### Stage 4 -- Share the lateral execution mechanism (done, trace verified)

**The plan was wrong about the shape of this one, and the code corrected it.** It assumed
`ExecuteLaneChangeState` and `PerformLaneChangeState` differed only in whether `commitToAction` is
called, and could therefore be merged into one class with a flag or three. Comparing them line by
line shows something else: what they duplicate is the *mechanism*, and what they differ in is
*policy*, on four counts.

Identical, character for character:

- trigger a reduced-safety-distance relaxation for the current leader and for every leader on the
  target lane, but only while the crossing has not yet begun;
- take the minimum of the plain car-following acceleration and the response to each of those
  leaders;
- set the indicator matching the direction;
- decide the movement is over once the vehicle has stopped crossing and is on a different lane.

Genuinely different, and deliberately so:

| | `PerformLaneChangeState` | `ExecuteLaneChangeState` |
|:--|:--|:--|
| start | gated: aborts unless the resulting speed clears a minimum and the gap is still open | ungated: the gap was established before the state was entered |
| lock | taken only once the gate passes, so a waiting vehicle does not block cooperative patterns | taken unconditionally |
| give up | when the gate fails | when the desire drops below `DMAND`, and never mid-crossing |
| worth | discretionary desire, zero if the vehicle cannot move | mandatory desire |

Merging those into one class would encode four policy differences as four flags -- worse than the two
classes that exist. So the **mechanism** moved into `LateralExecution` and the **policies** stayed
where they are. 77 lines removed, none of them a decision.

The lesson generalises to stage 5: duplication is a reason to extract what is shared, not a reason to
assume the things sharing it are the same thing.

### Stage 5 -- Hierarchy, for `MandatoryLaneChangePattern` only (done, trace verified)

**It turned out to need no behaviour change at all**, which is the opposite of what this plan predicted. Two findings
account for that.

*The superstate arrived in stage 3.* `commonTransitions()` already holds the rules that belong to the manoeuvre rather
than to a phase of it, and each state's table is those plus its own. The structural work this stage was written for was
mostly done by expressing transitions as a table.

*`EmergencyStopState` could never have inherited the full set anyway.* The plan assumed the question was whether it
*should*. But one of the three rules, `cannotStopInTime`, would answer `EmergencyStop` while the vehicle is already in
`EmergencyStop` -- a self-transition every tick, which the bounded driver loop from stage 2 would report as a cycle after
32 steps. So only the gap rule was ever in question, and there the difference is narrow: `mayExecuteLaneChange()` already
returns true at the ramp end, in congestion, when obstructed, and under a speed tolerance that widens from 20 to 40 km/h as
the time on the ramp runs out. The two rules diverge only for a vehicle that is more than 20 m from the ramp end, facing a
target lane flowing above 40 km/h, more than 20-40 km/h below the speed it could still reach, and needing more than 5 m/s2
to stop. Today such a vehicle merges. **Decision: it keeps merging** -- refusing a gap to the one vehicle that is running
out of lane is how vehicles end up deleted at the ramp end.

#### What this stage did do

The congested branch's hysteresis was compared against in four separate places, and its upper half lived as a field inside
`CongestedMergeState` while `CongestedFollowLeaderState` reached into that class to use it. It is now one pair of
constants documented as one mechanism, and two rules -- `enterCongestedRule()` and `leaveCongestedRule()` -- that the
states name in their tables.

One placement resisted. In `resolveMergeObstacle` the congested test stays inline, because it sits *below* the open-gap
test: hoisting it into the table would route a slow vehicle with an open gap into the congested branch instead of leaving
it where it is. The states that do hoist it have no such test in front of it.

#### The congested branch is deliberately not a clean composite

`CongestedCreepState` carries no recovery rule, and should not. Creeping is a commitment made in the expectation that the
vehicle alongside will clear shortly; leaving the moment the ego picks up speed would abandon that while the block is
still there. It exits through `CongestedMergeState` once the block has gone, one transition later, and the recovery rule
applies there. An exit rule on a composite's boundary would apply to every sub-state and would remove this. The exception
is recorded in the state's Javadoc so that a later tidying does not quietly undo it.

#### The plan as it was written



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

## 4b. The `old/` package

Removed while doing 1b. Ten files, about 109 call sites, all of them overriding the two methods whose
signature this stage changes, and none of them reachable: the only references outside the package
were three imports and one commented-out line in `MirovaTacticalPlannerFactory`. Converting dead code
to a new contract is work that buys nothing, and leaving it behind under the old contract would have
meant keeping both contracts alive. Git remembers it.

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
| 0 Trace test | **done**; two references, both deterministic, Freiburg covers the congested branch |
| 1a Constructor / enter-exit | **done**, trace verified on both cases |
| 1b `next()` returns a target | **done**, trace verified |
| 2 Driver loop | **done**, folded into 1b |
| 4 Shared lateral execution | **done**, trace verified |
| 3 Transition table | **done**, trace verified |
| 5 Congested-branch consolidation | **done**, trace verified, no behaviour change |
| 6 SingleStatePattern | not started |
