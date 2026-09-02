# Parameter Access and Units — Record and Working Rules

> Closes the open item *"`ParameterSet.getParameter` is the obvious next target and no work has been
> done on it"* from [`performance_investigation_synthesis.md`](performance_investigation_synthesis.md).
> The cost analysis behind it is in [`djunits_hashcode_finding.md`](djunits_hashcode_finding.md).

Two things live in this file. **Part 1** records what was changed and why, so the reasoning survives
the commits. **Part 2** is the working rule for anyone — human or agent — touching parameters or
DJUnits in MiRoVA from here on. If you only need the rule, skip to Part 2.

---

## The rule in three sentences

1. A parameter that never changes after a vehicle is created is read from
   `MirovaTacticalPlanner.getParams()`, not through `getParameter`.
2. DJUnits types stay on interfaces, fields and return values; arithmetic between them runs on SI
   doubles inside a method and is wrapped once at the end.
3. A physical literal inside per-tick code is a `static final` constant with a name, never an inline
   `new Speed(20.0, KM_PER_HOUR)`.

---

# Part 1 — What changed

## Why `getParameter` was expensive

`ParameterSet.getParameter` is a `LinkedHashMap` lookup keyed by `ParameterType`. That sounds cheap,
and the map itself is. The cost was in the key.

`ParameterType.hashCode()` was recomputed on every lookup, and one of its four components is
`this.defaultValue.hashCode()`. For any parameter whose default is a DJUnits scalar — which is most
of them — that call enters exactly the chain documented in
[`djunits_hashcode_finding.md`](djunits_hashcode_finding.md):

```
ParameterType.hashCode()
  └─ DoubleScalar.hashCode()
       └─ Unit.hashCode()            hashes 9 fields
            └─ Quantity.hashCode()   hashes siDimensions, the standard unit id,
                                     and the key sets of BOTH unit maps
                 └─ SIDimensions.hashCode()
```

`Quantity.hashCode()` iterates every registered unit of that quantity and allocates an iterator for
each key set. So a single parameter lookup cost **O(number of units registered for the quantity)**,
and it did so on every access. That is profiling candidate 2 from the 2026-08-20/21 investigation —
about 15 % of CPU, and explicitly untouched by the `LaneBasedGtu.CACHING=false` decision.

On top of that, the success path of `getParameter` allocated a varargs `Object[]` and called
`getId()` on every single call, because the null check went through
`Throw.when(cond, Class, "…%s…", parameterType.getId())` — arguments are evaluated whether or not
the condition holds.

**This is a different fix from the rejected DJUnits patch.** That patch was declined for a good
operational reason: it required a hand-built jar installed into every environment's `.m2`, invisible
to CI, silently falling back to stock wherever someone forgot. `ParameterType` and `ParameterSet` are
**OTS's own classes, inside this repository**, so caching the hash there ships through the normal
build and cannot silently revert. It removes the same chain for the parameter-lookup entry point
without taking on the dependency risk. `djunits.version` remains stock `5.2.1`.

## The three stages

### Stage 1 — make the lookup itself cheap (`826a70ae8`)

*`ots-base/…/parameters/ParameterType.java`, `ParameterSet.java`*

- `ParameterType` gained a `cachedHashCode`, computed once in the private constructor. Every field
  contributing to the hash is `final`, so the value is invariant. `hashCode()` returns it;
  `equals()` short-circuits on it before the string comparisons.
- `ParameterSet.getParameter` throws an explicit `ParameterException` instead of using `Throw.when`,
  removing the per-call varargs allocation and `getId()`.

This one change benefits **all** parameter reads in OTS, including the `old/` packages and code
outside MiRoVA that was never migrated.

### Stage 2 — stop looking parameters up at all (`7b32b5974` … `aa830ed4d`)

*New: `ots-road/…/mirova/core/MirovaParameterSnapshot.java`*

An immutable per-GTU snapshot resolves every parameter that is constant over a vehicle's lifetime
**once**, in the `MirovaTacticalPlanner` constructor, and exposes them as primitive SI fields plus
shared DJUnits scalars.

Active MiRoVA code (excluding the `old/` packages) went from **171 `getParameter` calls to 74**, of
which 48 are now consolidated into the snapshot constructor that runs once per vehicle.

The snapshot is also installed into the vehicle's own `ParameterSet` under
`MirovaParameterSnapshot.TYPE`. That is how the car-following models reach it: their interfaces carry
a `Parameters` object and no vehicle reference, so one lookup on that key replaces the eight that
`MirovaIdmPlus` used to perform per evaluation.

Five `try/catch` blocks and two silent numeric fallbacks disappeared along with the lookups that
could throw — including an `aMaxScaleSI = 3.5` in `EgoContext` that would have quietly overridden a
configured `A_MAX`.

### Stage 3 — stop allocating scalars on the hot path (`1d0069b1d`, `8df187cf3`)

Three kinds of allocation were removed from per-tick code:

| Kind | Example before | After |
|---|---|---|
| Literal rebuilt per call | `leftSpeedDelta.le(new Speed(20.0, KM_PER_HOUR))` | `static final Speed OVERTAKE_MIN_SPEED_DELTA` |
| Chain whose result never left the method | `leftDistance.divide(leftSpeedDelta.abs())` then compared | `distance.si / Math.abs(delta.si)` compared as `double` |
| Chain producing a value that *is* returned | `speed.times(T).plus(s0)` — two `Length` allocations | `Length.instantiateSI(speed.si * T.si + s0Si)` — one |

About 30 literals were hoisted into named constants. The naming turned out to be the more valuable
half: `-2.0`, `10.0` and `15 km/h` appeared repeatedly with nothing to say what they governed, and
repeated occurrences are now visibly the *same* threshold rather than coincidentally equal numbers.

## Verification

A 60-minute Freiburg-Nord run (`RunFreiburgMergeWatch`, seed 42, headless), executed on
`826a70ae8~1` and on `8df187cf3` and compared:

| Output | Result |
|---|---|
| Trajectories (`sampler_RoadSampler…csv`) | **byte-identical**, 104 188 lines, 12 178 373 bytes |
| `detector_periodic`, `detector_positions` | byte-identical |
| `diffused_vehicles.csv`, `simulation_demand*.csv` | byte-identical |
| `run.log` | identical apart from the output path |

The zip container hashes differ because zip embeds timestamps; the contents do not.

### How much it saved

Wall clock of the simulation loop alone — bracketed by the first `[SIM ` progress line and
`[OUTPUT] Writing sampler output`, so network construction and demand preparation are excluded.
Five repetitions per variant, interleaved (pre, new, pre, new, …) so that drift in machine load hits
both equally, each preceded by a discarded warm-up run that pays the demand-cache miss and the JIT.

| Variant | min | median | max |
|---|---|---|---|
| `826a70ae8~1` (before stage 1) | 59.88 s | 62.01 s | 64.11 s |
| `8df187cf3` (all three stages) | 47.77 s | 49.12 s | 49.87 s |

**Median 62.01 s → 49.12 s, 20.8 % faster.** The distributions do not overlap: the slowest new run
beats the fastest old one, so this is not noise at n=5. The spread also halved, from 4.2 s to 2.1 s,
which is what less allocation does to GC jitter.

Two limits on that number. It is wall clock, not a profile — it says the run got a fifth faster, not
where the remaining time goes. And it is the combined effect of all three stages; no attempt was made
to attribute it between the hash cache, the snapshot and the scalar allocations.

**What this does not cover:** one seed, one scenario, one parameterisation. Untouched by this run are
the `LmrsFactory` path in `MergeScenario` (a vehicle with no snapshot, exercising the fallback in
`MirovaIdmPlus`), deadlock diffusion (0 events here), and the capacity drop (`false` in this
configuration).

---

# Part 2 — Working rules

## Rule 1 — read constant parameters from the snapshot

```java
// no
double tau = this.vehicle.getParameters().getParameter(MirovaParameters.RELAXATION_TAU_SPACE).si;

// yes
double tau = this.vehicle.getParams().relaxationTauSpaceSi;
```

`MirovaTacticalPlanner.getParams()` returns the vehicle's `MirovaParameterSnapshot`. Every field
exists in two forms where both are useful:

| Suffix | Type | Use for |
|---|---|---|
| `…Si` | `double` | arithmetic and comparisons |
| `…Scalar` | `Length`/`Speed`/`Acceleration`/`Duration` | passing to an OTS interface that demands the type |

The `…Scalar` fields are built once per vehicle and shared. Passing one along is free; never rebuild
an equivalent scalar next to it.

## Rule 2 — never snapshot a parameter that is written at runtime

This is the one rule that can silently corrupt results rather than merely underperform. A snapshot
field for a parameter that something overwrites freezes it at its creation-time value, and nothing
will fail — the simulation just quietly stops responding to that parameter.

**Currently excluded, and why:**

| Parameter | Written by |
|---|---|
| `ParameterTypes.T` | `PreventUndercuttingPattern`, LMRS `Tailgating` |
| `ParameterTypes.LOOKAHEAD` | `InfrastructureContext` (anticipation boost) |
| `ParameterTypes.LCDUR` | OTS core / lane change |
| `DLC`, `DLEFT`, `DRIGHT`, `RHO`, `TMIN`, `TMAX` | LMRS, every tick |
| `CURRENT_DRIVING_MODE` | `Wiedemann99` — see *Known gaps* |

**Before adding a field to the snapshot, run this check:**

```bash
grep -rn "setParameter\(Resettable\)\?(.*<PARAMETER>" --include=*.java ots-road/src/main ots-core/src/main
```

If it returns anything outside a factory or an `old/` package, the parameter is state, not a
parameter. Leave it on `getParameter` and add a comment saying why, as
`MirovaIdmPlus.MIROVA_HEADWAY` does for `T`.

## Rule 3 — DJUnits on the boundary, SI doubles inside

The DJUnits requirement in `CLAUDE.md` holds for everything that crosses a boundary: method
signatures, fields, return values, anything handed to OTS. It does **not** require that intermediate
arithmetic allocate a scalar per operation.

```java
// no - three allocations, none of which outlives the method
Length desired = s0.plus(followerSpeed.times(T)).times(factor);
return calculateDeceleration(egoSpeed.minus(gtuSpeed), gtu.getDistance(), desired);

// yes - identical arithmetic, no intermediates
double desiredSi = (params.s0Si + followerSpeed.si * T.si) * factor;
return calculateDeceleration(egoSpeed.si - gtuSpeed.si, gtu.getDistance().si, desiredSi);
```

**On equivalence:** DJUnits' `times`/`plus`/`minus`/`divide` operate on the `si` fields directly, so
writing the same operations in the same order on SI doubles is bit-identical, not merely close. Keep
the order — `(a + b * c) * d` is not `(a * d) + (b * c * d)` in floating point.

When a private method only reads `.si` from its scalar arguments, give it a `double` overload and let
the scalar-typed one delegate. Public signatures stay as they are.

## Rule 4 — physical literals are named constants

Any `new Speed(…)`, `Length.instantiateSI(…)` or equivalent inside a method that runs per tick is
rebuilt on every call. Hoist it to `private static final` and give it a name that says what it
governs, not what its value is.

Where the value is one DJUnits already provides, use that: `Acceleration.ZERO` rather than
`Acceleration.instantiateSI(0.0)`, `Speed.ZERO`, `Duration.ZERO`, `Acceleration.POSITIVE_INFINITY`,
`Acceleration.NaN`.

Static initialisers (`MirovaParameters`, `W99ParameterTypes`) and the `ExtendedData*` loggers are
exempt — the first run once, the second at sampling rather than tick frequency.

## Rule 5 — adding a new parameter

1. Declare the `ParameterType` in `MirovaParameters` **with a default value**.
   `setDefaultParameters` reflects over that class and demands one from every parameter type it
   finds; a type without a default breaks vehicle construction. This is why
   `MirovaParameterSnapshot.TYPE` lives on the snapshot class instead.
2. Apply the Rule 2 check.
3. If constant: add a `…Si` field to `MirovaParameterSnapshot`, resolve it in the constructor, and
   add a `…Scalar` field only if a call site actually needs the object.
4. Read it via `getParams()` everywhere.

## Rule 6 — proving a change is behaviour-neutral

Anything in this area should be verified by output comparison, not by reasoning alone. The procedure:

```bash
# baseline worktree at the commit before the change
git worktree add <path> <ref>
cd <path> && mvn -o -q -pl ots-demo -am compile -DskipTests
mvn -o -q -pl ots-demo -am dependency:build-classpath -Dmdep.outputFile=cp_demo.txt -DincludeScope=runtime
```

**The classpath is the trap.** `dependency:build-classpath` resolves the `ots-*` modules to the
installed jars in `~/.m2`, which are stale and identical for both trees. Taken as-is, both runs
execute the same code and come out trivially identical while verifying nothing. Put each tree's own
`target/classes` directories **first** on the classpath.

Run both with `-Dmirova.gui=false` and the same seed, then compare the unzipped sampler and detector
output byte for byte. Note that the JVM does not exit after a headless run — AWT threads keep it
alive — so wait for the `[OUTPUT] Writing sampler output` line and kill the process.

---

## Known gaps

- **`Wiedemann99` stores state in the `ParameterSet`.** `CURRENT_DRIVING_MODE` is a `String` written
  four times per evaluation through `setParameter`. That is a context value wearing a parameter's
  clothes; it belongs in `EgoContext`. Not addressed, and the reason `Wiedemann99` was left out of
  the snapshot migration.
- **`PreventUndercuttingPattern` still overwrites `ParameterTypes.T`** — the parameter-hacking that
  `CLAUDE.md` says is being replaced by the relaxation model. Two sites remain.
- **`InfrastructureContext.computeAnticipatedLaneDrop` hard-codes a 1000 m lookahead** next to a
  now-removed dead statement that read `extendedLookAheadDistance` and discarded it. The parameter's
  default is also 1000 m, so wiring it up would be behaviour-neutral by default and make the distance
  configurable. Left alone deliberately: it is a behaviour change, not a refactoring.
- **No profiling has been re-run since these changes.** The wall-clock gain is measured (20.8 %
  median, above), but the CPU breakdown is not: it is unknown what now sits at the top of the profile,
  and whether `ParameterSet.getParameter` still appears at all. The 2026-08 profiling pipeline in
  `diss_mvb/scripts/simulation/ots/profiling/` is what would answer that.
