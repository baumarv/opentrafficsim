# Run identity: every run records which planner drove it, and refuses when it cannot

A cluster run folder said nothing about the driver model except `build.txt`, and `build.txt` says which
bundles were on the classpath — not which of them steered. Across several hundred campaign runs that is not
enough to attribute a single result. This closes that, and closes a second gap found on the way.

## What is in it

**Every run folder records what drove it.** `run.properties` — scenario, seed, planner, and whatever the
provider resolved — written by the run, not by the build; the same facts appended to `build.txt` under
`# Drove`.

**`TacticalPlannerProvider.describe()`**, defaulting to `name()`. A planner name is a label: it says which
provider was asked, not what it built. TaMA answers with the resolved composition and the SHA-256 of its
fingerprint, per vehicle class.

**`tama.composition` → `tama.composition.requested`**, and `(provider default)` → `(none given)`. That value
was a label, not an identity: it meant `mirova-reference/1` on the day it was written and would mean
something else the day the default changed, invisibly — the failure the provenance chain exists to prevent.

**Two checks, doing different jobs.**

| | when | what it catches |
|---|---|---|
| early | end of setup — network and templates built, nothing simulated | a run asking for a planner the scenario never offers to select; costs seconds, not a run |
| late | after the run | the authoritative record of what actually drove |

## Three defects found on the way, each by a measurement rather than by reading

**1. The check was asked before the answer existed.** Eleven unit tests were green while the rule was
consulted at a moment when no planner had been selected yet: the selection happens when the GTU templates are
built, during simulation setup. A real run reported `built with mirova` for a run that went on to drive TaMA.
The unit tests check the *rule*; only a run through the real path can check *when it is asked*. Hence
`RunIdentityIT`, which drives a scenario through the real runner — it would have gone red where the unit
tests could not.

**2. Only `truck` was recorded, never `car`.** `ServiceLoader.load` runs inside the selection, so each vehicle
class gets its own provider instance, each knowing only its own class — and the description was overwritten
rather than gathered. A run record naming `truck` alone reads as if the cars had driven on something else.
Found by looking at a real `run.properties`, not by a test.

**3. Two scenarios ignore the planner flag entirely.** `SimpleHighwayScenario` and `MergeScenario` override
the factory builders and construct their own — deliberately, they build Wiedemann 99 — so
`tacticalPlanner=tama` selects nothing at all there. Measured at the selection site: the parameter reaches the
run while the selection is never called. They are left as they are (rerouting them would put W99 against IDM+,
comparing two car-following models rather than two driver models); the early check refuses such a run at setup
and names the scenario. **`FreiburgNord` is the one scenario that routes through the selection**, which is why
the campaign and the model comparison both run on it.

## Evidence

- A real TaMA run's `run.properties` and `build.txt`, carrying the planner, the composition and the
  fingerprint `a93e52a7…` — the same hash as the committed reference composition.
- `RunIdentityIT`, both cases: a run records the planner that drove it, and a run asking for a planner the
  scenario cannot select is refused before it is simulated, with no simulation output produced.
- Full `./gradlew build` and full `mvn clean install` green; 108 test classes, no errors.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
