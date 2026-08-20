# DJUnits 5.2.1 — `hashCode()` cost, source verification and patch proposal

Verification and proposal only. **No patch was applied, no dependency version was changed, no
`pom.xml` was touched.** Prepared as evidence for the in-person conversation with the DJUnits/OTS
authors; no upstream issue or PR was drafted.

## 1. Source provenance — exactly the version in the build

| | |
|---|---|
| Version in `pom.xml` | `<djunits.version>5.2.1</djunits.version>` (root pom, line 52) |
| Sources artifact | `djunits-5.2.1-sources.jar`, already resolved in the local repository |
| SHA-1 (sources) | `720a6a6e795641ea73b4b49375567659c6265884` — matches the published `.sha1` |
| SHA-1 (binary) | `f7c8c686292c178c2e415ba742acd3234e404301` — matches the published `.sha1` |
| Artifact pom | `<artifactId>djunits</artifactId> <version>5.2.1</version>` |

No GitHub clone was needed and no version guessing was involved: the sources artifact for exactly
the version the build uses resolves from Maven and its checksum verifies. Everything below is read
from that jar.

## 2. The call chain, confirmed line by line against the source

The profile's stack maps onto the real code exactly:

| Profiled frame | Source | What it does |
|---|---|---|
| `DoubleScalar.hashCode() line: 293` | `value/vdouble/scalar/base/DoubleScalar.java:291` | `getDisplayUnit().getStandardUnit().hashCode()` + the SI value bits |
| `Unit.hashCode() line: 674` | `unit/Unit.java:670` | hashes 9 fields, including `quantity` |
| `Quantity.hashCode() line: 428` | `quantity/Quantity.java:421` | hashes `siDimensions`, `standardUnit.getId()`, **and both unit-map key sets** |
| `SIDimensions.hashCode() line: 337` | `unit/si/SIDimensions.java:333` | two `Arrays.hashCode(byte[])` |
| `Arrays.hashCode(byte[]) line: 4383` | JDK | the leaf |

The two entry points found in the profile reach the identical chain:

- **`ParameterType.hashCode()` → `DoubleScalar.hashCode()` → …**, via `ParameterSet.getParameter(...)`.
  This is profiling candidate 2, ~15 % of CPU, and is **unaffected by the `LaneBasedGtu.CACHING`
  experiment**.
- **`RelativePosition.hashCode()` → `DoubleScalar.hashCode()` → …**, via the position `MultiKeyMap`.
  61.7 % of `DoubleScalar.hashCode`'s 40.5 % CPU share. This one may largely disappear if
  `CACHING=false` ships — but it is the *same* underlying cost, which is why the two findings
  belong together rather than being separate problems.

### The part the profile could not show: `Quantity.hashCode()` walks two whole maps

```java
// Quantity.java:421
result = prime * result + ((this.siDimensions == null) ? 0 : this.siDimensions.hashCode());
result = prime * result + ((this.standardUnit == null) ? 0 : this.standardUnit.getId().hashCode());
result = prime * result + ((this.unitsByAbbreviation == null) ? 0 : this.unitsByAbbreviation.keySet().hashCode());
result = prime * result + ((this.unitsById == null) ? 0 : this.unitsById.keySet().hashCode());
```

`unitsById` and `unitsByAbbreviation` hold **every registered unit of that quantity**. Hashing one
`Length` therefore iterates both key sets — dozens of entries for a well-populated quantity —
allocating a `LinkedHashMap$LinkedKeyIterator` for each. That is precisely the allocation the
profile ranked as the single largest allocated type (17 % of all sampled allocation, 87.6 % of it
attributed to `Quantity.hashCode` / `Unit.hashCode`).

So the cost is not merely "a deep hash chain". It is **O(number of units registered for the
quantity) per scalar hash**.

## 3. Immutability — the premise only partly holds

This is the finding that changes the shape of the patch. DJUnits' documentation says it "stores
almost everything in immutable objects"; for these three classes that is true of one and a half.

### `SIDimensions` — genuinely immutable

```java
private final byte[] dimensions;
private final byte[] denominator;
private final boolean fractional;
```

All fields `final`, no setters, no existing hash caching. `Serializable` with
`serialVersionUID = 20190818L`. **Safe to cache unconditionally.**

(The arrays are final references to mutable arrays, but nothing in the class hands them out
unguarded for mutation, and the constructor is `protected`.)

### `Quantity` — **not** immutable

```java
private final SIDimensions siDimensions;   // final
private final String name;                 // final
private final Map<String, U> unitsById = new LinkedHashMap<>();            // final ref, MUTABLE map
private final Map<String, U> unitsByAbbreviation = new LinkedHashMap<>();  // final ref, MUTABLE map
private U standardUnit = null;             // NOT final
```

And the mutators are public API:

```java
public void registerUnit(final U unit, final SIPrefixes siPrefixes, final double siPrefixPower)  // line 121
public void unregister(final U unit)                                                              // line 217
```

`registerUnit` assigns `standardUnit` (line 126) and puts into both maps (lines 173/181/200/208);
`unregister` removes from them (lines 222/230). **These are exactly the fields `hashCode()` reads.**
A `Quantity`'s hash therefore legitimately changes over its lifetime, and a `String`-style
unconditional lazy cache would be **incorrect**, not merely risky.

No setters in the bean sense, no existing hash caching. `Serializable`, `serialVersionUID = 20190818L`.

`equals()` mirrors `hashCode()` field for field, including both key sets — so the two are currently
consistent with each other.

### `Unit` — **not** immutable either

```java
private String id;                      // none of these are final
private Set<String> abbreviations;
private String defaultDisplayAbbreviation;
private String defaultTextualAbbreviation;
private String name;
private Scale scale;
private UnitSystem unitSystem;
private boolean generated;
private boolean baseSIUnit;
private Quantity<U> quantity;
```

`Unit` is constructed empty and populated by `Unit.Builder` through `build()`, which at line 161
calls `this.quantity.registerUnit((U) this, …)`. No setters, no existing hash caching.
`Serializable, Cloneable`, `serialVersionUID = 20190818L`.

There is a second-order problem specific to `Unit`: its hash includes `quantity.hashCode()`, and the
quantity's hash changes when a **sibling** unit registers. So a cached `Unit` hash goes stale
because of an event that does not touch that `Unit` at all.

### Singleton or fresh instances?

**Shared singletons**, which is the favourable case. Units are `public static final` constants
created in static initialisers — DJUnits' own `LengthUnit.METER` etc., and in this project
`DimensionlessUnitMirova.SI` / `.BASE`
([DimensionlessUnitMirova.java:31-43](../../ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/util/units/DimensionlessUnitMirova.java#L31-L43)).
There is exactly one `Quantity` and one `Unit` instance per unit in the JVM, shared by every scalar.

That matters twice over. The memory cost of one extra `int` per instance is negligible — a few
hundred objects, not one per scalar. And the caching pay-off is maximal, because the same handful
of instances are hashed millions of times.

`DoubleScalar` instances, by contrast, *are* created per value — which is why the cache belongs on
`Unit`/`Quantity`/`SIDimensions` and not on the scalar.

### Registration timing in this project

The only `Unit.Builder` use outside DJUnits is `DimensionlessUnitMirova`, and it registers from a
`static final` initialiser — so registration completes at class load. No OTS or MiRoVA code calls
`registerUnit` or `unregister` at runtime. In practice the unit registry is therefore frozen before
any simulation hashing happens. That makes an invalidating cache safe *here*, but it is an
assumption about usage, not a property of the class — which is why the patch below invalidates
explicitly rather than relying on it.

## 4. Patch proposal (not applied)

Minimal, mechanical, `String`-style. The three classes differ in how much invalidation they need,
which follows directly from section 3.

### 4a. `SIDimensions` — unconditional lazy cache

Fully immutable, so this is the textbook case.

```diff
@@ class SIDimensions implements Serializable
     /** Whether this SIDimensions contains fractional dimensions. */
     private final boolean fractional;
 
+    /** Cached hash code; 0 means "not yet computed", as in java.lang.String. */
+    private transient int cachedHashCode;
+
@@ public int hashCode()
     @Override
     public int hashCode()
     {
+        if (this.cachedHashCode != 0)
+        {
+            return this.cachedHashCode;
+        }
         final int prime = 31;
         int result = 1;
         result = prime * result + Arrays.hashCode(this.denominator);
         result = prime * result + Arrays.hashCode(this.dimensions);
-        return result;
+        this.cachedHashCode = result;
+        return result;
     }
```

### 4b. `Quantity` — lazy cache **plus explicit invalidation**

Cannot be an unconditional cache: `registerUnit`/`unregister` change the hashed state.

```diff
@@ class Quantity<U extends Unit<U>> implements Serializable
     private U standardUnit = null;
 
+    /** Cached hash code; 0 means "not yet computed". Reset by registerUnit/unregister. */
+    private transient int cachedHashCode;
+
@@ public void registerUnit(final U unit, final SIPrefixes siPrefixes, final double siPrefixPower)
     {
         Throw.whenNull(unit, "unit cannot be null");
+        this.cachedHashCode = 0; // the unit maps and standardUnit are part of the hash
@@ public void unregister(final U unit)
     {
         Throw.whenNull(unit, "null unit cannot be removed from the unit registry");
+        this.cachedHashCode = 0;
@@ public int hashCode()
     @Override
     public int hashCode()
     {
+        if (this.cachedHashCode != 0)
+        {
+            return this.cachedHashCode;
+        }
         final int prime = 31;
         int result = 1;
         ...
-        return result;
+        this.cachedHashCode = result;
+        return result;
     }
```

This alone removes both `keySet().hashCode()` walks and the iterator allocation behind them — the
dominant cost and effectively all of the allocation.

### 4c. `Unit` — lazy cache, invalidated on build **and** on sibling registration

The more invasive one, because of the transitive dependency on the quantity's hash. Two options:

**Option A (simple, recommended for a local patch):** do not cache `Unit` at all. With 4a and 4b in
place, `Unit.hashCode()` degrades to nine field hashes of `String`s and small objects — all of which
memoise their own hashes — plus one now-O(1) `quantity.hashCode()`. The expensive part is already
gone, and no staleness question arises.

**Option B (complete):** cache in `Unit` and have `Quantity` invalidate its registered units:

```diff
@@ class Unit<U extends Unit<U>> implements Serializable, Cloneable
     private Quantity<U> quantity;
 
+    /** Cached hash code; 0 means "not yet computed". Reset by build() and by the quantity. */
+    private transient int cachedHashCode;
+
+    /** Invalidates the cached hash; called by Quantity when its unit registry changes. */
+    void invalidateHashCode()
+    {
+        this.cachedHashCode = 0;
+    }
+
@@ public int hashCode()
+        if (this.cachedHashCode != 0)
+        {
+            return this.cachedHashCode;
+        }
```

with `Quantity.registerUnit`/`unregister` looping over `this.unitsById.values()` and calling
`invalidateHashCode()`. Correct, but it introduces a back-reference cycle in the invalidation logic
for a benefit that 4a + 4b have already captured. **I would propose Option A.**

### Serialization

All three are `Serializable` with a fixed `serialVersionUID`. Declaring the new field `transient`
keeps the serialized form byte-identical and the `serialVersionUID` valid, and a deserialized
instance simply recomputes its hash on first use — the same approach `java.lang.String` takes. No
`readObject`/`writeObject` changes are needed.

### Thread safety

The `String` pattern applies unchanged: the field is written after the value is fully computed, an
unsynchronised `int` write is atomic, and a benign race merely recomputes the same value. No
`volatile` and no locking required — provided the hashed state itself is not being mutated
concurrently, which is already an existing precondition of `registerUnit`.

## 5. Local-patch feasibility

**The version wiring is clean.** The root `pom.xml` declares djunits once in `<dependencyManagement>`
using the `${djunits.version}` property (lines 52 and 133-135). Every consuming module —
`ots-base`, `ots-core`, `ots-draw`, `ots-opendrive`, `ots-xml` — declares the dependency **without a
`<version>`**, so all of them inherit it. Verified:

```
  ots-base:      own <version> declaration: 0
  ots-core:      own <version> declaration: 0
  ots-draw:      own <version> declaration: 0
  ots-opendrive: own <version> declaration: 0
  ots-xml:       own <version> declaration: 0
```

`ots-road` does not declare djunits at all and receives it transitively. The dependency direction is
`djunits → djutils-base 2.3.1`, not the reverse, so nothing pulls djunits in behind the property's
back.

**Consequence:** a local patch is a one-line change to `<djunits.version>`. No module-level overrides
to chase, no dependency-mediation conflict, no risk of two djunits versions on the classpath. The
route would be: clone djunits at the `5.2.1` tag, apply 4a + 4b, build, `mvn install` as
`5.2.1-mirova-patched`, flip the property.

Two caveats worth stating before anyone does that:

- A locally installed artifact is invisible to anyone else and to CI. Anything building this repo
  elsewhere silently falls back to stock `5.2.1` and loses the gain, without an error. If it is
  adopted, it needs to be obvious in the build rather than tucked into a property.
- The measurement should be repeated on an idle machine first (see below).

## 6. Measured impact — what this is worth

From `performance_profile_2026-08-20.md`, steady-state window, 4 952 CPU samples:

| Consumer of `DoubleScalar.hashCode` | Share of that call | Share of total CPU | Affected by the `CACHING` experiment? |
|---|---|---|---|
| `ParameterType.hashCode` ← `ParameterSet.getParameter` | 36.5 % | **~15 %** | **No** — fully alive regardless |
| `RelativePosition.hashCode` ← position `MultiKeyMap` | 61.7 % | ~25 % | Yes — likely shrinks or disappears |
| `AbstractHeadway.hashCode` | 1.7 % | ~0.7 % | No |

`DoubleScalar.hashCode` totals **40.5 % of CPU**, and the chain is responsible for **15 % of all
sampled allocation** (the `LinkedKeyIterator` churn inside `Quantity.hashCode`).

**The durable case for the patch is the ~15 % from `ParameterSet.getParameter`**, which no
OTS-side experiment touches: IDM re-reads its parameters on every evaluation, and each read hashes a
`ParameterType` whose hash walks the whole unit registry of its quantity. If `CACHING=false` ships,
that 15 % becomes the dominant remaining share of the 40 %; if it does not, or for other
scenarios and other OTS users where the position cache does pay off, the ~25 % is added on top.

The structural argument is the stronger one, though: `Unit`/`Quantity`/`SIDimensions` used as
hash-map keys has now been found at **two independent sites** in the OTS stack, reached by entirely
different code paths. A fix at the DJUnits level helps every such site, including ones not yet
written. Any fix confined to one OTS call site does not.

**Caveat on the numbers.** All measurements were taken on a machine carrying heavy external CPU load
(100 % across 32 cores from another user's job). Relative attribution holds under contention —
sampling is proportional to the CPU the thread actually received — but memory-bound work such as this
hash chasing may be somewhat overstated, and no absolute throughput figure from those runs is
meaningful. Before presenting a number as a headline, repeat the profile on an idle node. The
*shape* of the finding — an O(units-in-quantity) hash on a shared singleton, invoked millions of
times — does not depend on the measurement conditions and can be argued from the source alone.
