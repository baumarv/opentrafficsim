# Performance notes

Three changes to OpenTrafficSim outside `ots-demo`, each in its own commit, each revertible on its own.
None has a runtime switch, as decided: they are refactorings that leave results unchanged, and the check for that is
"the commit before against the commit after".

**The short version: all three leave the simulation output identical, and none of them makes an LMRS run measurably
faster.** The two parameter changes save about 1 % of CPU time, which is inside the spread between two runs of the
same code. The JAXB change saves 30 to 40 ms per network parse after the first in a JVM, which a cluster run, parsing
once, never sees. They were worth far more under MiRoVA, which asked for parameters much more often.

## How it was measured

- Scenario: Freiburg-Nord, 2025-09-22, 13:00–22:00 (32 400 s simulated), seed 42, `--model=lmrs`,
  `--lmrsParameters=default`. About 685 000 trajectory rows and 8 100 detector rows.
- JVM: Java 17.0.2, `-Xmx6g -XX:ActiveProcessorCount=1`, locale `en_US`, as on the cluster.
- Machine: a shared 32-core Windows server that was at about 80 % CPU load from other work during the measurements.
  Wall-clock times are therefore not comparable between sessions (the same baseline took 248 s to 268 s in one
  session and 427 s to 471 s in another). The comparison below uses **process CPU time**, which each run now writes
  into `run.properties` (`process.cpu.seconds`), and runs all variants at the same time under the same load.
- Each variant ran from its own copy of the compiled classes, so that a later build could not change a running
  measurement.
- Determinism: the content of all seven output files (trajectories, their header, detector data and positions with
  their metadata, `diffused_vehicles.csv`) compared by SHA-256 after unzipping. The zip archives themselves differ
  between any two runs, because they carry timestamps.

## Baseline

The LMRS baseline is deterministic: five runs of the unchanged code with the same seed, in three sessions, gave
identical content in all seven files.

| Run | Process CPU time |
|---|---|
| baseline 1 | 453.8 s |
| baseline 2 | 458.7 s |

One simulated day costs about 7.6 minutes of CPU time. The two baseline runs differ by 4.9 s (1.1 %); that is the
resolution of this measurement.

## The three changes

| | 1. `ParameterType.equals` | 2. `ParameterSet.getParameter` | 3. `XmlParser` JAXB context |
|---|---|---|---|
| Commit | `da00ed968` | `ec81e2236` | `579280e95` |
| File | `ots-base/…/parameters/ParameterType.java` | `ots-base/…/parameters/ParameterSet.java` | `ots-xml/…/xml/parser/XmlParser.java` |
| Change | Returns `false` as soon as the two cached hash codes differ, before comparing id, description, value class and default value. | Checks `result == null` itself and calls `Throw.when` only when that fails. Before, `Throw.when` was called on every get, which allocates a varargs array and calls `getId()` for a message that is not built. | One `JAXBContext` is created on first use and shared by all parses in the JVM, instead of one per parse. The unmarshaller is still created per parse. |
| Why results cannot change | The hash code is computed from exactly the four fields `equals` compares, so unequal hash codes imply unequal objects. Holds for every default value that honours the `equals`/`hashCode` contract. | Same check, same exception type, same message from the same call. Only the source line number that `Throw.when` embeds in the message moves (176 → 182). | A `JAXBContext` is immutable and thread-safe; the classes it describes cannot change within a JVM. A context that fails to build is not cached and the `JAXBException` reaches the caller as before. |
| Process CPU time, full day | 451.1 s, 451.6 s | 450.1 s, 452.9 s | 450.3 s, 453.8 s, 446.0 s, 452.8 s |
| Against the baseline mean (456.3 s) | −1.1 % | −1.1 % | −1.2 % (cumulative; no effect of its own expected) |
| Output against baseline | identical, 2 of 2 runs | identical, 2 of 2 runs | identical, 5 of 5 runs |
| Unit tests | `ots-base`: 58 run, 0 failed | `ots-base`: 58 run, 0 failed | `ots-xml`: 5 pass, 1 fails with and without the change, see below |
| State upstream | Not upstream. Upstream already caches the hash code, which was the other half of the fork's change. | Not upstream. | Not upstream. |

The CPU times are cumulative: each column includes the changes to its left. The differences between the columns
are smaller than the difference between the two baseline runs, so the honest statement is "about 1 % for the two
parameter changes together, and not distinguishable from zero for each one alone".

### Change 3 measured where it acts

Six consecutive parse-and-build cycles of `FreiburgNord.xml` in one JVM, three repetitions, in milliseconds:

| | 1st | 2nd | 3rd | 4th | 5th | 6th |
|---|---|---|---|---|---|---|
| before | 1466 / 2171 / 1656 | 158 / 189 / 178 | 137 / 160 / 197 | 118 / 122 / 151 | 115 / 98 / 115 | 213 / 108 / 122 |
| after | 1663 / 1730 / 1857 | 104 / 365 / 127 | 95 / 119 / 132 | 91 / 92 / 122 | 71 / 90 / 75 | 81 / 90 / 91 |

The first parse is unchanged; it is dominated by class loading. From the second parse on, the median falls from
about 130 ms to about 92 ms. That matters for a batch of many runs in one process, or a test suite that builds many
networks. It does not matter for the cluster, where every JVM parses once.

The fork's version also had a `warmUpJAXBContext()` method that entry points called before starting threads. It is
not needed and was not ported: the context is created under a lock on first use.

### The failing upstream test

`ots-xml` `ParserTest.testParser` fails on this branch with and without change 3. The test tree is identical to
upstream's. Its resource `test-network.xml` still includes `../xsd/defaults/default_gtutypes.xml`, a path the 1.8
resolver no longer maps, and the parser reports
`Unable to load file:///…/xsd/defaults/default_gtutypes.xml`. I did not touch it. It may pass in upstream's CI if
the fallback path resolves from another working directory; I did not investigate further.

## Not ported, and why

| Item | Reason |
|---|---|
| `LaneBasedGtu.CACHING = false` | The fork's largest gain (about −16 % CPU under MiRoVA). The flag and the `MultiKeyMap` position cache it switched were removed upstream in `da3fc0687`. What remains is an unconditional one-entry cache per GTU keyed on simulation time. Nothing to port and nothing to switch. |
| `ParameterType` hash-code cache | Already upstream. |
| The DJUnits scalar-hashing patch | Rejected on the fork already, because it needs a hand-installed jar. |
| Tick caches, parameter snapshot, reduced perception queries | Part of MiRoVA. |

## Where the time goes now

Not measured. The fork's profile does not transfer: it was taken with MiRoVA on OTS 1.7.6, and both the driver
model and `LaneBasedGtu` are different here. If LMRS runtime matters for the campaign, the next step is a profile
of an LMRS day on this branch, not more ports from the fork.
