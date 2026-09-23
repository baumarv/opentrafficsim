# The sampler's decision columns read an interface, not one concrete planner

> **Do not rebuild on the cluster until `tamascreen` has finished.** Its pending array tasks load classes at
> start from `cp.txt`. Rebuilding the workspace while the array is running would have later tasks compute on a
> different build from earlier ones, and the campaign would mix two builds with nothing in the output saying
> so. Merging on GitHub is safe; the cluster rebuild waits.

## What was wrong

A TaMA run wrote a trajectory file of the right size, with the right columns, in which `ActionState` was
`none` and `LaneChangeDesireLeft/Right`, `AccelerationDamping` and `CurrentCFAcceleration` were `NaN` on every
row. The five columns asked `instanceof MirovaTacticalPlanner` and fell back silently. Nothing failed, nothing
warned. Found on the campaign pilot, not by a test.

No pre-registered metric of the running screen reads these columns — checked, not assumed: the three
calibration scripts contain zero references to any of the five — which is why the array was not held for this.
What was affected is the merge-tactic analysis.

## What is in it

`DriverStateObservable`: state name, both lane-change desires, the damping factor, the car-following
acceleration. `MirovaTacticalPlanner` implements it, so its behaviour is unchanged and the `instanceof` branch
is gone. Only the five columns `FreiburgNord` registers are converted; the rest of that package is registered
by no campaign scenario and is left alone rather than changed untested.

The interface states what an implementation owes: no side effects, and "unknown" is allowed while a plausible
substitute is not — a substituted number cannot be told from a measured one afterwards.

## Evidence

`TrajectoryColumnsIT` drives **both models in the same test**, on `FreiburgNord`, for 120 simulated seconds,
and reads the trajectory file each run wrote:

- MiRoVA: 675 of 1989 rows carry a state; all five columns filled.
- TaMA: 779 of 2137 rows carry a state; all five columns filled.

It found two things a weaker test would have passed. It first failed on TaMA with *"`ActionState` is empty on
all 2137 rows"* while that run made **16 lane changes** — the state source in the core was wrong, not the
manoeuvres missing. After that fix it accepted `MandatoryLaneChangePattern$Synchronising@6cdc7c38`: a column
that is full and useless at once, because an identity hash names an instance rather than a state. The test now
refuses any state name containing `@`.

`DriverStateObservableTest` pins at source level that no registered column binds to one concrete planner
again — a regression there changes nothing under MiRoVA and only empties the columns for everyone else, which
is exactly the thing that does not show up.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
