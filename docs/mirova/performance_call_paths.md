# Where the CPU Time Went — Call Paths, Class by Class

This document follows each performance defect through the code, one frame at a time. It shows which class calls
which, where the time is lost, and what the fix changed. It is the long form of the deck "Wo die Rechenzeit blieb"
and of the section "The four questions" in [`performance_improvements.md`](performance_improvements.md). The
numbers are from there; this document explains the mechanisms behind them.

**Code states.** Line numbers are read from source:

| label | commit | what it is |
|---|---|---|
| *before* | `826a70ae8~1` | before the parameter rework (P6), position cache still on by default in OTS |
| *today* | `119b05887` (main) | after P4 and P6 |
| TaMA | `5757034` | `tactical-maneuver-architecture` main |
| DJUnits | 5.2.1 | from `djunits-5.2.1-sources.jar` |
| djutils | 2.3.1 | `djutils-base`, for `MultiKeyMap` and `Throw` |

Paths below are relative to the repository root unless they start with `tama-`, which means the
`tactical-maneuver-architecture` repository. Nothing here was measured beyond what `performance_improvements.md`
records. Where a statement is inferred from reading code rather than from a measurement, it says so.

**Corrections to earlier statements.** The code reading for this document found three statements in the
deck, the ledger and `parameter_access_and_units.md` to be wrong or imprecise. They are corrected here and in
those files; see [§8](#8-corrections-to-earlier-statements).

---

## 1. The shared cause: hashing a DJUnits scalar

Every `Length`, `Speed`, `Duration` or `Acceleration` is a `DoubleScalar`. When one of them is used inside a hash
key, Java calls its `hashCode()`, and in DJUnits 5.2.1 that call walks down four classes:

1. **`DoubleScalar.hashCode()`** (`org/djunits/value/vdouble/scalar/base/DoubleScalar.java:290-298`)
   ```java
   int result = getDisplayUnit().getStandardUnit().hashCode();   // 293
   long temp = Double.doubleToLongBits(this.getSI());            // 295
   result = prime * result + (int) (temp ^ (temp >>> 32));       // 296
   ```
   Hashes the *standard unit* of the scalar's quantity, then the SI value.
2. **`Unit.hashCode()`** (`org/djunits/unit/Unit.java:669-683`) hashes nine fields. One of them,
   `abbreviations`, is a `LinkedHashSet<String>`; `AbstractSet.hashCode()` iterates it with a freshly allocated
   iterator. Another is `quantity`, which leads to step 3.
3. **`Quantity.hashCode()`** (`org/djunits/quantity/Quantity.java:421-433`)
   ```java
   result = prime * result + siDimensions.hashCode();                    // 428
   result = prime * result + standardUnit.getId().hashCode();            // 429
   result = prime * result + unitsByAbbreviation.keySet().hashCode();    // 430  iterates every registered unit
   result = prime * result + unitsById.keySet().hashCode();              // 431  iterates every registered unit again
   ```
   `unitsByAbbreviation` and `unitsById` hold *every unit registered for the quantity*. `LengthUnit` alone has 19
   constants, and registration with SI prefixes adds all prefixed variants. Each `keySet().hashCode()` allocates a
   `LinkedKeyIterator` and walks the whole set.
4. **`SIDimensions.hashCode()`** (`org/djunits/unit/si/SIDimensions.java:333-340`): two `Arrays.hashCode(byte[])`.

**Cost of one scalar hash:** three set iterations with three iterator allocations, plus two byte-array hashes.
Nothing is cached, and it cannot simply be cached, because `Quantity` and `Unit` are mutable:
`Quantity.registerUnit()` and `unregister()` change exactly the maps that go into the hash. A `String`-style cache
would be wrong; it needs invalidation on registration. That is what the August patch did
([`djunits_hashcode_finding.md`](djunits_hashcode_finding.md), [`djunits_patch_experiment.md`](djunits_patch_experiment.md)).

**`equals` is cheap in practice.** `DoubleScalar.equals` (`:302-316`) compares standard units with
`Unit.equals`, which returns at its identity check (`Unit.java:689`) because standard units are singletons. The
set-walking `Quantity.equals` is reached only for two *different* unit instances. And `HashMap` calls `equals`
only when the stored 32-bit hash matches and the keys are not identical (see §2.3). So the cost is in `hashCode`,
not in `equals`.

In OTS, two hot paths put DJUnits scalars into hash keys. They are the next two sections.

---

## 2. Path A: reading a parameter

### 2.1 Before: one parameter read, frame by frame (`826a70ae8~1`)

A typical call site, read on every tick:

```java
// ots-road/.../tactical/mirova/MirovaTacticalPlanner.java:180
Duration dt = getGtu().getParameters().getParameter(ParameterTypes.DT);
```
Others: `EgoContext.java:666` (`A_MAX`), `EgoContext.java:262-264` (relaxation time constants),
`MirovaIdmPlus.java:62-274` (several per car-following evaluation). 171 such read sites existed in active MiRoVA
code.

1. **`Gtu.getParameters()`** (`ots-core/.../core/gtu/Gtu.java:572`) returns the GTU's `ParameterSet`.
2. **`ParameterSet.getParameter(parameterType)`** (`ots-base/.../base/parameters/ParameterSet.java:153-161`)
   ```java
   T result = (T) this.parameters.get(parameterType);   // 157  LinkedHashMap<ParameterType<?>, Object>
   Throw.when(result == null, ParameterException.class,
           "Could not get parameter of type '%s' as it was not set.", parameterType.getId());  // 158-159
   ```
3. **`LinkedHashMap.get` → `HashMap.hash(key)`** (JDK `HashMap.java:336-339`): `key.hashCode() ^ (h >>> 16)`.
4. **`ParameterType.hashCode()`**, recomputed on every call (`ots-base/.../ParameterType.java:227-237`):
   ```java
   result = prime * result + ((defaultValue == null) ? 0 : defaultValue.hashCode()); // 232  ← DJUnits chain, §1
   result = prime * result + description.hashCode();   // 233  String, hash cached by the JDK
   result = prime * result + id.hashCode();            // 234  String, hash cached by the JDK
   result = prime * result + valueClass.hashCode();    // 235  Class, identity hash
   ```
   Only line 232 is expensive, and only for parameter types whose default value is a DJUnits scalar. That is
   most of the ones MiRoVA reads: `DT` (`Duration`, default 0.5 s), `T`, `A`, `B`, `S0`, `LOOKAHEAD`, `A_MAX`,
   the relaxation time constants. For those, **every single parameter read runs the full §1 chain once.**
5. **`HashMap.getNode`** (`HashMap.java:565-583`): finds the bucket and compares
   `node.hash == hash && (node.key == key || key.equals(node.key))`. Parameter types are static singletons, so a
   hit is an identity match and `equals` is never called.

With several hundred parameter reads per vehicle per tick, the hash in step 4 was the cost: about half of the
parameter-access loss measured in the matrix (§2.4).

### 2.2 Today, stage 1: the hash computed once (`826a70ae8`, in `ots-base`)

```java
// ParameterType.java
private final int cachedHashCode;                              // 47
// constructor, lines 147-153: the same four-field formula, once
this.cachedHashCode = hash;
public int hashCode() { return this.cachedHashCode; }          // 241-245
// equals, 263-267: compare the cached hashes before the String comparisons
if (this.cachedHashCode != other.cachedHashCode) { return false; }
```
All four fields are `final`, so the hash is constant over the parameter type's lifetime and computing it once is
exact. `ParameterSet.getParameter` now uses a plain `if (result == null) throw …` (`ParameterSet.java:157-162`)
instead of `Throw.when`; this change is cosmetic (see §8). **The measured 17.9 % of stage 1 is the cached hash.**

### 2.3 Today, stage 2: the per-vehicle snapshot

`MirovaParameterSnapshot` (`ots-road/.../tactical/mirova/core/MirovaParameterSnapshot.java`) resolves every
constant parameter once and stores it as a plain field.

- **Created** once per GTU in the `MirovaTacticalPlanner` constructor (`MirovaTacticalPlanner.java:164-165`):
  `this.params = getGtu().getParameters(); this.paramSnapshot = MirovaParameterSnapshot.install(this.params);`
- **Constructor** (`MirovaParameterSnapshot.java:316-398`): one `getParameter` per constant parameter, stored as
  `…Si` (`double`, for arithmetic) and, where an interface needs an object, `…Scalar` (a DJUnits scalar built
  once and shared).
- **Excluded** because something writes them at runtime (Javadoc `:34-38`): `T`, `LOOKAHEAD`, `DLC`, `DLEFT`,
  `DRIGHT`, `RHO`, `TMIN`, `TMAX`. A snapshotted parameter that is written later would silently freeze.
- **Read** through `getParams()` (`MirovaTacticalPlanner.java:496-499`), e.g. `getParams().dtScalar`
  (`:197/251/284`). The car-following model, which receives only a `Parameters`, uses
  `MirovaParameterSnapshot.ofOrNull(parameters)` with a fallback to `getParameter` (`MirovaIdmPlus.java:69, 164`).

Stage 2 measured 2.8 %, inside the noise. After stage 1, a parameter read is a `LinkedHashMap` lookup with an
O(1) hash, and the snapshot only saves that lookup.

**Stage 3** (no DJUnits allocations for intermediate arithmetic, literals hoisted) measured ±0.0 %. The JIT's
escape analysis and the young-generation collector evidently absorb short-lived scalars well.

### 2.4 What the numbers say about this path

| comparison | before → after | saved |
|---|---|---|
| stages 1+2, stock DJUnits (cache off) | 58.9 s → 47.1 s | 20.1 % |
| the same, with patched DJUnits | 52.2 s → 47.0 s | 9.9 % |

About half of the parameter-access cost was the DJUnits hash reached through `defaultValue.hashCode()`. The
other half was the lookup itself: the map probe and the call overhead at 171 sites.

### 2.5 Open: possibly two parameter sets per vehicle

Code reading, **not verified at runtime**. In `LaneBasedStrategicalRoutePlannerFactory.create` (lines 130-132),
the tactical planner is created first: `MirovaTacticalPlannerFactory.create` sets parameter set A on the GTU
(`:102`), and the planner installs the snapshot in A (`MirovaTacticalPlanner.java:164-165`). Then line 132 calls
`gtu.setParameters(nextParameters(...))`, which builds a **new** set B through `peekParameters` (`:108-117`).

If that holds at runtime:
- the planner's `getParams()` and its captured `this.params` refer to A;
- `getGtu().getParameters()`, and therefore any OTS code reading the GTU's parameters, refers to B, which has no
  snapshot;
- a write to `T` through one set is invisible through the other;
- if the parameter factory draws values from distributions, A and B could hold different values.

The *before* planner captured A in the same way (`:148`), so this is not a regression of P6, and the P6 output
was byte-identical. It is a model question, not a performance one, and it should be checked with a debugger or a
one-line identity log (`planner.getParameters() == gtu.getParameters()`).

---

## 3. Path B: the GTU position cache

### 3.1 Who asks for positions

`LaneBasedGtu.position(lane, relativePosition)` is called very often, mostly by OTS itself:

- **Perception.** `LaneStructure.position(gtu, record, positionType)` (`ots-road/.../perception/structure/LaneStructure.java:452-461`)
  calls `gtu.position(record.getLane(), gtu.getRelativePositions().get(positionType))` for every *other* GTU.
  It is used by the lister and distancer lambdas of `getDownstreamGtus` (`:177-235`), `getUpstreamGtus`
  (`:248-294`), `getFirstDownstreamGtus` and `getFirstUpstreamGtus`. `NavigatingIterable` runs the lister when
  it opens a lane record and the distancer on every `poll()` (`NavigatingIterable.java:214, 248`). **Every
  perceived neighbour on every lane record costs at least one `position()` call on that neighbour.** This is
  what `NeighborsPerception.getLeaders/getFollowers` iterate (§5).
- **Movement bookkeeping** in `LaneBasedGtu.move` (`:492` onward): `getReferencePosition()` (`:531`),
  `scheduleEnterEvent()` (`:533`, which calls `position(lane, getFront())` at `:610`), `scheduleLeaveEvent()`
  (`:534`, `position(lane, getRear())` at `:745`, and again at `:757/760`), and `scheduleTriggers(lane)` for
  every lane of every cross-section (`:537-543`, `position(lane, getRear())` at `:816`).
- **MiRoVA directly:** only four call sites, all in `InfrastructureContext`. In the first profile this path
  accounted for 0.9 % of CPU, which is why a MiRoVA-side position cache was not built.

The `RelativePosition` passed is always the same instance per GTU: `getFront()`/`getRear()` come from
`relativePositions.get(Type)` (`Gtu.java:319-331`), and `getReference()` returns the static
`REFERENCE_POSITION` (`Gtu.java:554-557`).

### 3.2 `position(lane, relativePosition, when)` with the cache on (`LaneBasedGtu.java:1081-1229`)

The method runs under `synchronized (this)`.

1. **`getOperationalPlan(when)`** (`:1085`), a Historical lookup (`Gtu.java:640-643`). This runs always, with or
   without the cache.
2. **Hit check** (`:1088-1107`): if `when.si == cachePositionsTime && plan == cacheOperationalPlan`, it calls
   `cachedPositions.get(lane, relativePosition)`.
3. **Invalidation** (`:1109-1114`): if the time or the plan differs, both fields are reset and
   `cachedPositions.clear()` is called. `changeLaneInstantaneously` also clears it (`:297`).
4. **Miss: compute** (`:1116-1217`, under `synchronized (this.lock)`): `crossSections.get(when)`, a linear scan
   for the lane's cross-section and lateral index, `plan.getLocation(when, relativePosition)`, and
   `lane.getCenterLine().projectFractional(...)`, giving `loc = fraction × lane length`. If that is NaN, it tries
   upstream and downstream cross-sections and finally an endpoint projection.
5. **Store** (`:1219-1225`): `Length.instantiateSI(loc)`; if `CACHING`, `cachedPositions.put(length, lane,
   relativePosition)` and the cache time and plan are updated.

The field is `MultiKeyMap<Length> cachedPositions = new MultiKeyMap<>(Lane.class, RelativePosition.class)`
(`:1071`).

### 3.3 Inside `MultiKeyMap` (djutils 2.3.1, `org/djutils/multikeymap/MultiKeyMap.java`)

`MultiKeyMap` is a tree of nested maps, one level per key, each a `LinkedHashMap<Object, Object>` (`:34`).

- **`get(Object... keys)`** (`:64-67`) allocates a varargs `Object[2]` and an `Arrays$ArrayList`, then descends:
  `getSubMap` (`:177-200`) checks `isAssignableFrom` and does `map.get(lane)`. **`Lane.hashCode` is cached**
  (`Lane.java:1390-1399`), so level 1 is cheap.
- Level 2, `branch.get(relativePosition)` (`:144`), calls **`RelativePosition.hashCode()`**.
- **`put(value, keys...)`** (`:87-93`) walks the same way, creating the sub-map on first use, and hashes the
  `RelativePosition` again.
- **`clear()`** (`:207-225`) clears only the top level.

### 3.4 Why the key is expensive

`RelativePosition` is a Java `record` (`ots-core/.../core/gtu/RelativePosition.java:23`):

```java
public record RelativePosition(Length dx, Length dy, Length dz, Type type)
```

A record without an explicit `hashCode` gets one generated by `java.lang.runtime.ObjectMethods.makeHashCode`,
which folds `31 × acc + hash(component)` over all components **on every call**. That is three
`DoubleScalar.hashCode()` calls, i.e. **three times the §1 chain**, plus `Type.hashCode()`, which is precomputed
(`:89`). One `get` and one `put` per position computed: up to six DJUnits chains per `position()` call.

### 3.5 Why the cache lost

The August profile split the time inside `position()`:

| part | share |
|---|---|
| `MultiKeyMap.put` (storing on a miss) | 53.9 % |
| `MultiKeyMap.get` (lookup) | 20.3 % |
| the geometry itself (step 4) | 25.8 % |

The put:get ratio was 2.7:1: most entries were stored and never read back. The cache is invalidated whenever the
simulation time or the operational plan changes (§3.2 step 3), and most queries come at a new time. So the cache
spent three times as much on bookkeeping as on the work it was meant to save.

Measured in the matrix: switching the cache off saves 16.4 % on the original code. With patched DJUnits (cheap
hashes) it still saves 7.5 %: the `MultiKeyMap` bookkeeping, varargs arrays and nested lookups cost more than
recomputing the geometry, independent of DJUnits.

### 3.6 The fix: `CACHING = false` for MiRoVA scenarios

`ScenarioGenerator.buildSimulationScript` (`ots-demo/.../scenariomanagement/ScenarioGenerator.java:379`) calls
`applyGtuPositionCaching()` as its first statement (`:381`). That method (`:581-592`) reads the system property
`mirova.gtuPositionCaching` (default `false`) and sets the public static `LaneBasedGtu.CACHING`. It is not a
JVM-wide default and not a change to OTS's own default: other users of the library keep the cache.
Every MiRoVA and TaMA run in this repository goes through it; the TaMA live runner logs
`LaneBasedGtu.CACHING = false` as well.

With the cache off, `position()` still does step 1 (`getOperationalPlan`) and step 4 (the geometry) on every call.
That is the remaining cost of positions, now paid without the bookkeeping.

### 3.7 The alternative: precompute the `RelativePosition` hash

A colleague's patch turns the record into a class that computes the hash once in its constructor and returns it
from `hashCode()`. The same pattern as stage 1 for `ParameterType`.

- It acts only where `RelativePosition` is a hash key, and that is only the position cache (`LaneBasedGtu.java:1071`;
  the per-GTU map in `Gtu` is keyed by `Type`, which caches its hash already). So it helps only with
  `CACHING = true`.
- **For MiRoVA and TaMA today: no effect**, the cache is off.
- **For OTS with the cache on (its default): estimated about 10 %** of run time, not measured. On the original
  code the cache cost 11.6 s, of which 4.3 s remained with cheap hashes, so about 7.3 s was hashing. The other
  ~7.5 % (bookkeeping) remains, so `CACHING = false` stays the better choice for us.
- Caveat: the precomputed hash is only correct as long as no `Length` unit is registered after the object is
  built (§1: the `Quantity` hash changes on registration). In this project all length units register at class
  load, but the assumption belongs in the Javadoc. `equals` still compares the three `Length` values, but in the
  `HashMap` the identity check usually succeeds first because OTS reuses the same instances (§3.1).

---

## 4. How the three share the time

The DJUnits hash is the cause; the parameter read and the position cache are the two paths through which it was
reached. Whichever fix is credited first takes the shared part. The matrix contains both orders (original code
70.5 s → today 47.1 s, 23.4 s saved):

| order | step | saved | share |
|---|---|---|---|
| as implemented | position cache off | 11.6 s | 49 % |
| | parameter access (stages 1+2) | 11.8 s | 51 % |
| cause first | DJUnits hash cached (patch) | 14.1 s | 60 % |
| | then position cache off | 4.3 s | 18 % |
| | then parameter access | 5.1 s | 22 % |

Today, the patched DJUnits jar saves 0.0 %: neither path hashes a DJUnits scalar in the simulation loop any more.

---

## 5. One MiRoVA tick, end to end (today)

This is where the queries into OTS come from. Assumptions: all behaviour switches at their defaults (off), the
patterns and incentives the factory registers (`MirovaTacticalPlannerFactory.java:174-214`), and the perception
categories of `DefaultMirovaPerceptionFactory.java:56-65`.

### 5.1 The cost of a perception query, even when OTS answers from its memo

Every OTS perception query goes through `AbstractPerceptionCategory.computeIfAbsent`
(`ots-core/.../gtu/perception/AbstractPerceptionCategory.java:105-115`). A **hit** still costs:
- a capturing lambda allocated at the call site;
- one or two `LinkedHashMap` lookups through `contextualKey` (`:149-153, 201-216`);
- `getSimulatorAbsTime()`, which allocates a new `Time` on every call (`OtsSimulatorInterface.java:108`);
- a `Time.lt` comparison.

On top of that: `perception.getPerceptionCategory(X.class)` is a map lookup (`AbstractPerception.java:82/95`),
and every `gtu.getSpeed()`/`getAcceleration()` on another GTU allocates a `Time` too (`Gtu.java:691-697, 749-755`).
A leader list from `DirectNeighborsPerception.getLeaders(lane)` (`:162-207`) is a lazy reiterable over
`LaneStructure.getDownstreamGtus`: each element costs a `position()` on that GTU (§3.1), some `Length`
arithmetic, and a `HeadwayGtuReal`, whose `getSpeed()` delegates live to `gtu.getSpeed()`.

### 5.2 The sequence

1. **`MirovaTacticalPlanner.generateOperationalPlan`** (`MirovaTacticalPlanner.java:189-219`): positioning
   guard, then `update()` (`:241-331`):
   1. `contextManager.advanceTick()`: `invalidateCache()` on all four contexts.
   2. `contextManager.updateFromPerception()` (`VehicleContextManager.java:184-205`) in the order MacroTraffic,
      Infrastructure, Neighbors, Ego. Only Neighbors queries OTS here: `getCurrentLeader()` →
      `NeighborsPerception.getLeaders(CURRENT)`, the first perception query of the tick, plus cut-in detection.
   3. `DeadlockDiffusionWatchdog.check()`: `gtu.getSpeed()`, more only near standstill.
   4. **Desire layer** (`:441-472`): for the four incentives, `isApplicable()` then `computeDesire()`.
   5. **Pattern admission** (`PatternSelector.java:43-60`): `isRunning()` on all four patterns, `checkContext()`
      and `checkAbility()` on each one not running. This runs even while a committed lane change holds the lock.
   6. **Arbitration** (`HybridPlanArbitrator.java:97-208`): `update()` on every admitted pattern, which runs its
      state's `executeControl()`, usually one or more car-following evaluations, then picks the plan.
   7. Turn indicator, `FsmTraceRecorder.record(...)` (its arguments, including a new `Time`, are evaluated even
      when nothing records).
2. **`LaneOperationalPlanBuilder.buildPlanFromSimplePlan`**: OTS builds the operational plan.

### 5.3 What the desire layer asks OTS for

- **`CruisingSpeedIncentive.computeDesire`** (`CruisingSpeedIncentive.java:69-135`): `getLegalLaneChangePossibility`
  for LEFT and RIGHT; `InfrastructureContext.getAnticipatedSpeed` for CURRENT, LEFT and RIGHT, each walking the
  **entire** leader list within `LOOKAHEAD` (`InfrastructureContext.computeAnticipatedSpeed`, `:442-487`, no
  early stop once leaders are beyond the weighting distance); and the first car-following evaluation of the tick
  (`EgoContext.getCurrentCarFollowingAcceleration`, cached per tick afterwards).
- **`KeepRightIncentive`** (`KeepRightIncentive.java:51-100`): repeats the RIGHT lane-change possibility and the
  cross-section, then `getLegalLaneChangeInfo` for RIGHT and CURRENT, `LOOKAHEAD`, `getDesiredSpeed()`.
- **`RouteIncentive.computeDesire`** (`RouteIncentive.java:75-177`), for each of LEFT, CURRENT, RIGHT that exists:
  `getSpeedLimitProspect(lane).getSpeedLimitInfo(ZERO)` (a new `SpeedLimitInfo` each time), then
  `carFollowingModel.desiredSpeed(...)` (parameter reads and `Speed` allocations), then
  `getLegalLaneChangeInfo(lane)` and iteration over its entries; then `getLegalLaneChangeInfo(CURRENT)` again, the
  lane availability and `getLegalLaneChangePossibility(...).neg()` for both sides. It also reads the ego speed into
  a variable that is never used, and recomputes the desired speed for CURRENT that `LaneBasedGtu.getDesiredSpeed()`
  already holds.
- **`ProhibitDeadEndIncentive.isApplicable`**: `InfrastructureContext.getParallelMerge` for LEFT and RIGHT, which
  walks two adjacent lanes and their successors.

The same OTS question is asked several times per tick; each repeat pays the §5.1 hit cost:

| query | calls per tick |
|---|---|
| `getLegalLaneChangePossibility(CURRENT, RIGHT)` | ~5 |
| `getLegalLaneChangePossibility(CURRENT, LEFT)` | ~3 |
| `getLegalLaneChangeInfo(CURRENT)` | 3–4 |
| `getCrossSection()` | 4, plus 2 inside the traffic perception |
| `getSpeedLimitInfo` for CURRENT at distance 0 | 4 separately built objects |
| `getParameter(LOOKAHEAD)` | ~6 or more |

### 5.4 The mean speed of a lane

`MacroTrafficContext.computeAverageSpeed(lane)` (`MacroTrafficContext.java:174-192`) calls
`TrafficPerception.getSpeed(lane)`, which is `AnticipationTrafficPerception.getSpeed`
(`ots-road/.../perception/categories/AnticipationTrafficPerception.java:74-155`). For the requested lane *and*
each of its neighbours in the cross-section, it collects the whole leader list with an `AnticipationSpeed`
collector that never stops early (`AnticipationSpeed.java:117-124`), calling `gtu.getSpeed()` per vehicle. So
`getSpeed(CURRENT)` walks CURRENT, LEFT and RIGHT. These are the same lists `computeAnticipatedSpeed` already
walked, but the per-vehicle speed reads and `Time` allocations happen again. `PreventUndercuttingPattern.checkAbility`
asks for CURRENT and LEFT on every tick while the pattern is not running.

### 5.5 Context lookups

`MirovaTacticalPlanner.getContext(Class)` (`:515-525`) iterates `contextManager.getAllCategories()`, which returns
`Map.copyOf(categories)`: **a new immutable map on every call** (`VehicleContextManager.java:112-115`), roughly
15–25 times per vehicle and tick. In the profile, `VehicleContextManager.getAllCategories` is the innermost MiRoVA
frame for 7.1 % of all simulation samples.

The per-tick context caches (`ContextCategory.cacheValue/getCachedValue`, `ContextCategory.java:169-195`) store each
value twice (tick cache and persistent map) and cannot store `null`: a getter whose answer is `null` (no leader on
a lane) recomputes on every call.

### 5.6 Where MiRoVA's time goes, by trigger

Innermost MiRoVA frame on the stack, share of all simulation samples of the main thread, from the 2026-09-28
profile (90 min with a jam, 3 480 samples):

| MiRoVA method | share |
|---|---|
| `RouteIncentive.computeDesire` | 16.0 % |
| `MacroTrafficContext.computeAverageSpeed` | 15.1 % |
| `MirovaTacticalPlanner.generateOperationalPlan` (plan building, OTS calls made directly) | 7.2 % |
| `VehicleContextManager.getAllCategories` (`Map.copyOf`) | 7.1 % |
| `InfrastructureContext.computeAnticipatedSpeed` | 6.0 % |
| `CruisingSpeedIncentive.computeDesire` | 5.6 % |
| `NeighborsContext.getLeaders` | 5.0 % |
| `InfrastructureContext.computeParallelMerge` | 3.7 % |
| `MirovaCarFollowingUtil.followSingleLeader` | 2.6 % |
| `InfrastructureContext.checkLaneAvailable` | 2.2 % |
| `ContextCategory.cacheValue` + `getCachedValue` | 4.0 % |

MiRoVA's own code is only 4.7 % of the samples as the innermost frame. The rest is JDK collections (46 %),
generic OTS (28 %), DJUnits (9 %) and djutils (5 %), triggered by the methods above.

---

## 6. One TaMA tick, and how the world view answers

### 6.1 The sequence

1. **`TamaTacticalPlanner.generateOperationalPlan`** (`tama-ots/.../ots/TamaTacticalPlanner.kt:122-147`):
   1. Destroyed or unpositioned vehicles are handled without asking the agent (`:124-131`).
   2. `reportLaneChange(gtu)` tells the agent whether an open lane change completed or aborted (`:133`).
   3. **`val world = OtsWorldView(gtu, perception, gtu.perceptionRange(), laneChange)`** (`:134`). One instance
      per vehicle per tick. `perceptionRange()` reads `LOOKAHEAD` once (`OtsWorldView.kt:784`).
   4. **`val command = agent.step(startTime.si.toDuration(SECONDS), world)`** (`:135`): the whole driver model
      runs against the view.
   5. `toPlan(command)` and `LaneOperationalPlanBuilder.buildPlanFromSimplePlan` (`:138-141, 171-184`): one
      `SimpleOperationalPlan` from the command's acceleration, duration and lane-change direction.
2. **`MirovaDriverAgent.step`** (`tama-core/.../core/MirovaDriverAgent.kt:163-228`):
   1. `following.beginTick(now)` clears the car-following `tickCache`.
   2. `beliefs.beginTick(now, world)`: every context invalidates its caches, then `updateFromPerception` runs
      for Ego, Neighbors, Infrastructure, MacroTraffic (`VehicleContextManager.kt:86-110`).
   3. `DesiredSpeed.of(world, ...)`: `world.speedLimits(CURRENT, range).firstOrNull()` (`DesiredSpeed.kt:75-76`).
   4. `control.acceleration(...)` (`LongitudinalControl.kt:65-115`): `world.leaders(CURRENT, ...)` and
      car following.
   5. `desires.update(...)`, then `arbitrator.arbitrate(...)`, then `lateral.request(...)` if a lane change is
      commanded.

The contexts are plain fields of the agent; there is no equivalent of `getContext()`'s `Map.copyOf`.

### 6.2 The contract: what the core may ask

`WorldView` (`tama-api/.../api/WorldView.kt:56-269`) is the entire interface between the driver model and OTS.
Its contract (`:37-54`): a view is valid for one tick; the same query returns the same answer within the tick;
the core must not keep the view; **every query that looks along the road takes its range as an argument**.

| query | returns |
|---|---|
| `ego` | `EgoState`: speed, acceleration, length, maxSpeed, maxDeceleration, lane-change progress |
| `leaders(lane, range)`, `followers(lane, range)` | `Sequence<PerceivedVehicle>`, nearest first |
| `laneExists(lane)`, `laneIsUsable(side)` | `Boolean` |
| `laneChangePossibility(side, range)` | `Observation<Distance>` |
| `routeRequirement(lane, range)` | `Observation<RouteRequirement>` (lane changes, remaining distance, dead end) |
| `distanceToLaneEnd(lane, range)` | `Observation<Distance>` |
| `speedLimits(lane, range)` | `Sequence<SpeedLimitAhead>` |
| `parallelMergeAhead(side, range)` | `Boolean` |
| `meanSpeed(lane, range)`, `expectedMergeSpeed(side, range)` | `Observation<Speed>` |

`Observation` (`CoreTypes.kt:105-146`) is `Measured(value)`, `Absent` (nothing there) or `Unknown` (the host cannot
say). `PerceivedVehicle` (`WorldView.kt:405-413`) is a small immutable data class: id, net gap, speed,
acceleration, length, indicator, alongside flag. It contains only what a driver can observe.

The units are value classes from `kotlin-units`: `Distance` is a `Long` of micrometres (exact equality, which
matters for memo keys), `Speed` and `Acceleration` wrap a `Double`. On the JVM they are primitives unless passed
as `Any?`, where they are boxed.

### 6.3 How a query is answered: `OtsWorldView` (`tama-ots/.../ots/OtsWorldView.kt`)

Every query goes through one helper (`:478-485`):

```kotlin
private val memo = HashMap<QueryKey, Any?>()                                          // :104
private data class QueryKey(val query: String, val first: Any?, val second: Any? = null) // :113

private fun <T> memoised(query: String, first: Any?, second: Any? = null, answer: () -> T): T {
    val key = QueryKey(query, first, second)
    if (key in memo) return memo[key] as T
    val value = answer(); memo[key] = value; return value
}
```

So for each question, in this order:

1. **The core calls the port**, for example `world.routeRequirement(CURRENT, 10 km)`.
2. **`memoised("routeRequirement", lane, range)`** builds a `QueryKey` from the query name and the *full*
   argument list, and looks it up in a plain `HashMap`. The key's hash is a `String` hash (cached by the JDK), an
   enum identity hash and a `Long`/`Double` hash: no DJUnits anywhere.
3. **On a hit**, the stored answer is returned. The core gets back exactly the object it got the first time in
   this tick.
4. **On a miss**, the lambda runs, and this is the only place TaMA touches OTS. For `routeRequirement`
   (`:149-174`): `infrastructure().getLegalLaneChangeInfo(lane.toOts())`, pick the first entry within the range,
   and build `RouteRequirement(numberOfLaneChanges(), remainingDistance().si.meters, deadEnd())`. **The DJUnits
   value is converted once, with `.si`, into a Kotlin value class**, and the result is stored.
5. From then on, every part of the core that asks the same question in the same tick gets the stored value,
   without touching OTS, without a `Time` allocation, without `computeIfAbsent`.

The other queries follow the same shape (`:117-329`):

| query | OTS call on a miss |
|---|---|
| `leaders`/`followers` | `NeighborsPerception.getLeaders/getFollowers(lane)`, cut at the range, each `HeadwayGtu` mapped to a `PerceivedVehicle` (`:591-603`: `distance.si`, `speed.si`, `acceleration.si`, `length.si`, indicator) |
| `laneExists` | `infrastructure().crossSection.contains(lane)` |
| `laneIsUsable`, `laneChangePossibility` | `getLegalLaneChangePossibility(CURRENT, side)` |
| `distanceToLaneEnd` | walks the `LaneStructure` root record and its successors |
| `speedLimits` | `getSpeedLimitProspect(lane)`, one `getLegalSpeedLimit` per distinct distance, materialised as a list |
| `parallelMergeAhead` | two `getAdjacentLane` calls and a length comparison |
| `meanSpeed` | `TrafficPerception.getSpeed(lane)`, i.e. the same `AnticipationTrafficPerception` as MiRoVA (§5.4) |
| `expectedMergeSpeed` | a lane-path walk and the mean speed of the first three vehicles in a 150 m window |

**Range check** (`:493-500`): a query for more than the GTU's `LOOKAHEAD` throws instead of returning a truncated
answer. This is the lesson of the Phase 0.5 defect, in which OTS's own memo, keyed by the relative lane alone,
answered 100 % of 136.5 million extended look-ahead calls from an entry computed under the shorter range. Because
TaMA's key contains the range, an answer computed for one range can never be returned for another.

### 6.4 Caches on the core side

Above the adapter memo, the core keeps small per-agent caches, cleared at the start of each tick
(`docs/cache-audit-core.md` §2): nearest leader and follower per lane, route and physical distance to the lane end
per lane, the speed-limit list, mean speed per lane, expected merge speed per side, the merge reference speed, and
the car-following answer per leader. Measured hit rates over the 90-minute window:

| site | hits | misses | hit rate |
|---|---|---|---|
| adapter memo | 67.9 M | 45.1 M | 60 % |
| `followerCache` | 2.06 M | 0.73 M | 74 % |
| `leaderCache` | 4.66 M | 6.41 M | 42 % |
| `limits` | 2.72 M | 2.49 M | 52 % |
| `routeDistances` | 0.92 M | 4.83 M | 16 % |
| `tickCache` | 52 k | 5.12 M | 1 % |

Switching the adapter memo off cost 1–12 % and switching `leaderCache` off 3–10 %; the others were not
measurable (`stage11.md`). Each of these caches is small because most of the deduplication has already happened
in the adapter memo.

### 6.5 Parameters in TaMA

`DriverParameters.get(key)` (`tama-api/.../api/DriverParameters.kt:214-215`) is a lookup in an immutable map whose
keys (`ParameterKey`) are plain classes with **identity** `hashCode`. There is no deep hash and no DJUnits in
the key. The values are value classes boxed as `Any` and unboxed on read. It is built once, when the agent is
created, from the OTS parameters (`tama-ots/.../ots/OtsParameters.kt:54-83`). In the profile it is 3.8 % of the
samples as the innermost TaMA frame: not free, but without the §1 chain.

### 6.6 Where TaMA saves, by trigger

Absolute samples as the innermost tactical frame, same 90-minute run, one profile each. The pairs match questions
that serve the same purpose in both models; they are not one-to-one code equivalents.

| purpose | MiRoVA | TaMA | difference |
|---|---|---|---|
| route and lane-change information | `RouteIncentive.computeDesire` 556 | `routeRequirement` 80 + `laneChangePossibility` 38 | **−438** |
| context lookup | `getAllCategories` 247 | — (contexts are fields) | **−247** |
| anticipated speed per lane | `computeAnticipatedSpeed` 208 + `CruisingSpeedIncentive` 195 | `IncentivesKt.anticipatedSpeed` 70 | **−333** |
| mean speed per lane | `computeAverageSpeed` 527 | `meanSpeed` 391 | −136 |
| leader lists | `NeighborsContext.getLeaders` 174 | `leaders` 50 + `NeighborsContext.leader` 47 | −77 |
| parallel merge | `computeParallelMerge` 129 | `parallelMergeAhead` 84 | −45 |
| per-tick caches | `ContextCategory` cache 140 | `QueryKey.equals` + `hashCode` 104 | −36 |
| plan construction and direct OTS calls | `generateOperationalPlan` 250 | `generateOperationalPlan` 321 | +71 |
| parameters | (inside the rows above) | `DriverParameters.get` 79 | +79 |

Total under the tactical layer: 3 227 → 1 786 samples (−45 %). Outside the tactical layer (movement, OTS
history): 253 and 282, the same.

### 6.7 What this means

The saving does not come from TaMA's own code being faster: TaMA's core, adapter and the Kotlin standard library
take *more* samples as the innermost frame than MiRoVA's own code (297 against 162). It comes from four things,
visible in the code and consistent with the table above:

1. **One question, one OTS call per tick.** MiRoVA asks OTS the same question from several places, and every
   repeat pays the full hit path of §5.1 (lambda, context key, `Time` allocation, category lookup). TaMA asks once
   per argument list and serves every repeat from a plain `HashMap` whose key hashes in O(1).
2. **A narrower question.** `RouteIncentive` builds speed-limit information, evaluates the car-following model's
   desired speed for three lanes and reads lane-change information repeatedly. TaMA's route question is a single
   `getLegalLaneChangeInfo` per lane, reduced to three numbers. This row alone is the largest single saving.
3. **Converted once, at the boundary.** Everything behind the port is a primitive or a value class. The core
   does its arithmetic on those; DJUnits appears only inside the adapter's miss path.
4. **No lookup overhead around the model.** No `Map.copyOf` per context access, no double storage in the context
   caches, parameter keys with identity hashes.

What TaMA does **not** save: `meanSpeed` goes to the same `AnticipationTrafficPerception` as MiRoVA and remains its
largest single item (19 %); the memoised `leaders` answer is a lazy sequence, so each iteration maps the
`HeadwayGtu` objects again; and `QueryKey` allocation plus boxing of its arguments costs about 5 %.

**How firm this is.** The −45 % under the tactical layer and the per-owner split are measured, from one profile per
planner with JFR on. The per-trigger pairing in §6.6 and the four reasons above are inferred from reading the code
alongside that profile; no single mechanism was switched off and measured on its own, apart from the adapter memo
(§6.4). Whether the same design would give MiRoVA the same saving is untested; nothing in it depends on Kotlin.

---

## 7. Further findings from the code reading (not measured)

These surfaced while tracing the MiRoVA tick. Each is visible in the code; none has been measured.

1. **Anticipated-speed walks do not stop.** `InfrastructureContext.computeAnticipatedSpeed` walks every leader
   within `LOOKAHEAD` on three lanes every tick, and `AnticipationTrafficPerception.getSpeed` walks the same lists
   again with a collector that never stops.
2. **The fallback acceleration is always computed.** `LongitudinalControl.computeAcceleration:148-150` evaluates
   `fallbackAcc` eagerly. Without a leader, that is a second full car-following evaluation per tick, because the
   null-leader path is not cached.
3. **Pattern admission runs during a locked lane change.** `PatternSelector` checks all patterns before the
   arbitrator's lock shortcut.
4. **Cached `null` counts as a miss** in the context caches, so leader, follower and speed-limit getters recompute
   whenever the answer is `null`.
5. **`getContext()` allocates a map on every call** (§5.5), 7 % of the samples.
6. **`RouteIncentive` does dead and duplicate work** (§5.3).
7. **`FsmTraceRecorder.record` allocates a `Time` per tick** even when no recorder is active.
8. **Possibly two parameter sets per vehicle** (§2.5): a model question, to be verified at runtime.

---

## 8. Corrections to earlier statements

The code reading for this document corrected three statements made earlier in the deck, in
[`performance_improvements.md`](performance_improvements.md) and in
[`parameter_access_and_units.md`](parameter_access_and_units.md):

1. **"`getParameter` allocated a varargs array on every call" is wrong** for djutils 2.3.1. `Throw` has fixed-arity
   overloads for up to three arguments; the call bound to `when(boolean, Class, String, Object)` (`Throw.java:93`),
   which allocates only when the condition is true. The bytecode of the *before* `ParameterSet.class` has no
   `anewarray`. The remaining success-path cost was an eager `getId()` (a final-field getter), which the JIT inlines.
   The replacement with a plain `if` is cosmetic; the 17.9 % of stage 1 is the cached hash.
2. **"On a bucket collision, `equals` ran with string comparisons" is imprecise.** `HashMap` compares the stored
   32-bit hash first and calls `equals` only on an equal hash and non-identical keys. Parameter types are
   singletons, so a normal read is an identity hit; the `equals` shortcut in stage 1 helps only on a true 32-bit
   hash collision, which is rare.
3. **`Unit.hashCode()` is not just nine cheap field hashes.** Its `abbreviations` field is a set that is iterated
   on every call, a third iterator per scalar hash besides the two in `Quantity.hashCode()`.
