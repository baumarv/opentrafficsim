# Cache and key audit — every memo in the MiRoVA tree

**Question asked of each cache: does the key cover every argument the stored value depends on?** Where it
does not, two calls that should get different answers collide on one entry and whichever ran first wins.

**Status: complete.** Every memo in `ots-road/.../tactical/mirova/` is listed below. Three collisions were
real and are now behind switches (BC-10, BC-11, BC-12); four more have the same *shape* but are not
reachable, each checked by measurement rather than by reading; the rest are correctly keyed. Two further
findings that are not collisions are recorded at the end.

Measurements are on the two twenty-minute production cells — 2025-10-27 (congested) and 2025-09-22 (free
flow) — with every switch at its default. Each probe's inertness was proven by the recording reproducing
its reference (`e8dcc43420f564cd152f75aaa5054315`, `303125a4b9b9860751ffe4b4ecca4bd8`).

---

## 1. The three real collisions — all behind switches

| | cache | key by default | what the value also depends on | switch |
|---|---|---|---|---|
| 1 | `EgoContext.tickAccelerationCache`, via `MirovaCarFollowingUtil` | `leaderId` | the headway factor the call asked for (`T` is multiplied for the duration of one call) | **BC-11** `bcHeadwayFactorKey` |
| 2 | `NeighborsContext` induced deceleration, two overloads | `"inducedDecel_" + gtuId` | *which overload* — the two compute different quantities — and, for one of them, three caller-supplied arguments | **BC-10** `bcInducedDecelKey` |
| 3 | `EgoContext` ego and follower deceleration thresholds | `dir == LEFT ? …_LEFT : …_RIGHT` | the direction, via `getDirectionalDesire(dir)`; `NONE` aliases onto the RIGHT key | **BC-12** `bcDecelThresholdKey` |

The published model keeps all three; see [`phase05-report.md`](phase05-report.md) for what each switch does
and what it moves, and [`contract.md`](contract.md) §0 for which are in the core set (BC-10 and BC-12 yes,
BC-11 no).

## 2. Same shape, not reachable — checked by measurement

`dir.isLeft() ? …_LEFT : …_RIGHT` sends **anything that is not LEFT**, `NONE` included, to the RIGHT key.
That shape occurs five more times. It bites only if `NONE` actually arrives, which was measured rather
than assumed, because `MandatoryLaneChangePattern.getTargetDirection()` returns `dominantDirection()` live
and *can* be NONE.

| cache | site | NONE reached? | verdict |
|---|---|---|---|
| `egoDecel_LEFT/RIGHT` | `NeighborsContext.getEgoDeceleration` | **no** — 0 of 30 100 and 32 471 calls | latent |
| `followerDecel_LEFT/RIGHT` | `NeighborsContext.getFollowerDeceleration` | **no** — same call path, 0 NONE | latent |
| `laneChangePossibleLeft/Right` | `NeighborsContext.getIfLaneChangePossible` | **no** — 0 of 29 328 and 31 000 calls | latent |
| `PARALLEL_MERGE_LEFT/RIGHT` | `InfrastructureContext.getParallelMerge` | **no** — its only caller loops over an explicit `{LEFT, RIGHT}` array | unreachable by construction |
| `leftLaneAvailable` / `rightLaneAvailable` | `InfrastructureContext.getIfLaneAvailable` | **yes — 16 997 and 15 177 times** | reached but inert, see below |

`getIfLaneAvailable` is the one case where NONE does arrive, and it is worth recording why it is
nevertheless harmless, because the reason is not the one that made BC-12 harmless:

- In **100 %** of those calls NONE was *served* an entry that a genuine RIGHT query had already written.
  NONE never wrote the entry itself, so unlike BC-12 it cannot poison a later RIGHT read. The collision
  runs one way only.
- The served answer equalled what NONE would have computed for itself in **100 %** of cases (0 differences
  in 32 174 cross-reads across both cells). Inside `checkLaneAvailable`, `isLeft()` is false for NONE, so
  every branch takes the RIGHT path anyway; only the OTS query `getLegalLaneChangePossibility(CURRENT, dir)`
  sees the difference, and OTS's own memo keys `(fromLane, lat)` correctly and agreed here.

No switch is proposed for any of these five. They are recorded so that a future change to
`dominantDirection()`, to `getTargetDirection()`, or to any caller that begins passing NONE is known to
make five latent aliases live at once.

## 3. Correctly keyed

- Every single-valued per-tick key in `EgoContext`, `NeighborsContext`, `InfrastructureContext` and
  `MacroTrafficContext` — ego speed, car-following acceleration, desired speed, speed limits, leaders and
  followers, gap distances, delta speeds and time headways, average speeds, lane-end distances, anticipated
  speeds and lane drops. Each is either keyless (one value per tick) or keyed by the one argument it varies
  with, with a three-way `LEFT`/`RIGHT`/`CURRENT` branch where a lane is involved, which covers NONE.
- `downstreamAdjacentLane_*` and `anticipatedLaneDrop_*` key by `direction.ordinal()`, i.e. one key per
  enum constant **including NONE** — the correct form, and the one the aliasing sites above should take if
  they are ever fixed.
- `mergeReferenceSpeed_<direction>` (written by `MandatoryLaneChangePattern` into `EgoContext`'s cache):
  everything that varies is either the direction, which is in the key, or per-vehicle state.
- `EgoContext.activeRelaxations` and `NeighborsContext.unobstructedSpeed` are state and observation, not
  memos of a pure function; each is keyed by the vehicle it describes. `unobstructedSpeed` is additionally
  bounded (16 entries) and expired (10 s), so it cannot grow.
- The OTS-side memo `DirectInfrastructurePerception.computeIfAbsent` keys `(fromLane, lat)` correctly. Its
  role in making the extended look-ahead inert (BC-7) is a *warm-cache* problem, not a key problem: the
  memo is right, but it is already filled under the normal look-ahead before the raise happens.

## 4. Two findings that are not collisions

**`ContextCategory.values` is never cleared and has no reader.** `cacheValue(key, v, true)` writes into
both `cache` and `values`; `invalidateCache()` clears only `cache`. Nothing reads `values`:
`getValue`/`getAllValues` are reached only through `MirovaTacticalPlanner.getContextValue`, which has **no
callers anywhere in the tree**. So every value a vehicle ever caches is retained for that vehicle's whole
life, and with id-bearing keys (`inducedDecel_<id>`) the map grows with the number of distinct neighbours
it ever perceives. It is dead weight rather than a defect — but it is also stale data that would be wrong
if anything ever did read it, since entries outlive the tick they describe by design. Recommended for
deletion in the port; the core has no equivalent.

**`laneAverageSpeed_` keys the lane by id alone.** The key covers all five arguments — lane, start, end,
`maxVehicles`, scan direction — so it is not a collision in the sense of this audit. But it identifies the
lane by `lane.getId()`, where the rest of the tree uses `link.getId() + "/" + lane.getId()`; two lanes with
the same id on different links would collide. Not observed, and the per-tick lifetime makes it narrow.

**Over-keyed, harmless.** `desiredFrontHeadway_{LEFT,RIGHT,CURRENT}` holds three keys for a value that does
not depend on the direction at all — `computeDesiredFrontHeadway()` takes no argument. Three entries, one
number. Wasteful, not wrong; noted so it is not read as evidence that the quantity is direction-dependent.

---

## What this audit changed

Three switches, each measured before being written: BC-10, BC-11, BC-12. Two of the three are in the core
set on structural grounds — the core has no such memo — and BC-11 is deliberately out, because the core
reproduces the leader-only key on purpose (ADR-014). None of them is on by default; the published model
keeps every collision it has always had.
