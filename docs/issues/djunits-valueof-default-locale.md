# Draft issue: `Scalar.valueOf(String)` parses through the default locale

**Where to file:** DJUnits (`djunits/djunits`). The root cause is in djutils' `NumberParser`, but DJUnits
is what chooses the parameterless constructor, so the decision belongs there. Mention djutils in the text
so the maintainers can route it.

**Not an OTS issue**, although that is where we hit it: every caller of `Length.valueOf` and its siblings is
affected, in any project.

---

## Title

`Length.valueOf("2.3 mm")` returns 23 mm under a locale whose decimal separator is ','

## What happens

`Scalar.valueOf(String)` — checked in `Length`, and the same shape in the other scalar types — parses like
this:

```java
NumberParser numberParser = new NumberParser().lenient().trailing();
double d = numberParser.parseDouble(text);
```

`new NumberParser()` takes `Locale.getDefault()` (djutils `NumberParser`, the two-argument constructor
delegates to `this(trailing, lenient, Locale.getDefault())`). Under a locale where `.` is the *grouping*
separator — German, Dutch, Italian, Spanish and others — `"2.3"` is read as `23`, leniently and without
complaint.

So the same string parses to two different quantities depending on the machine's locale, and the failure is
silent: no exception, a plausible value, three orders of magnitude apart in the worst case.

## Reproducing

```java
Locale.setDefault(Locale.GERMANY);
System.out.println(Length.valueOf("2.3 mm"));   // 23.0000000 mm
Locale.setDefault(Locale.US);
System.out.println(Length.valueOf("2.3 mm"));   // 2.30000000 mm
```

Or, without touching the JVM's default: run OTS' own `LengthBeginEndAdapterTest` on a German-locale
machine. It fails with

```
expected: <LengthBeginEnd [begin=false, absolute=true, offset=2,30000000 mm, fraction=0.0]>
 but was: <LengthBeginEnd [begin=false, absolute=true, offset=23,0000000 mm, fraction=0.0]>
```

and passes with `-Duser.language=en -Duser.country=US`. That is how we found it: the OTS test suite cannot
pass on a German-locale machine.

## Why we think it is a defect rather than a policy

`valueOf(String)` is the counterpart of `toString()`, and it is what XML and configuration parsers reach
for. Those inputs are *data*, not text presented to a user: an XML document means the same thing wherever
it is read. A locale-sensitive parse makes a document's meaning depend on the reader's machine, and the
project that consumes it usually cannot tell, because the result is a valid number.

DJUnits already knows the locale is in play: the exception message built in the `catch` block names
`Locale.getDefault(Locale.Category.FORMAT)`. The information is there; only the parse does not act on it.

## Suggested fix

Parse with `Locale.ROOT` in `valueOf(String)` — djutils' `NumberParser` already has the fluent method:

```java
NumberParser numberParser = new NumberParser().lenient().trailing().locale(Locale.ROOT);
```

If locale-sensitive parsing is wanted for user input, an overload `valueOf(String, Locale)` would serve
that, leaving the single-argument form deterministic. The same applies to `NumberParser`'s parameterless
constructor in djutils, which is where the default is actually chosen.

## What we did in the meantime

In our OTS fork, the one adapter on the XML path that took this route
(`LengthBeginEndAdapter.unmarshal`) now parses with `Locale.ROOT` at the call site, with a comment pointing
here. We deliberately did **not** use `Locale.setDefault` around the call: it is global state and would
change unrelated sites in a process running several simulations in one JVM.

We checked our own networks: every value that reaches this adapter is integral, except one `END-0.00m`,
which is zero under either reading. No result of ours was affected — which is luck, not design.
