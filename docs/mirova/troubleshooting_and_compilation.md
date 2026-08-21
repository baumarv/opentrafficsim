# MiRoVA Compilation & Troubleshooting Guide

This guide documents common compilation issues, ClassLoader pitfalls, Maven execution errors, and their definitive solutions for the OpenTrafficSim (OTS) / MiRoVA framework workspace.

For how the pieces fit together in the first place — studies, run addressing, the cluster tooling — see [scenariomanagement_architecture.md](scenariomanagement_architecture.md).

---

## 🚀 Quick Reference: Recommended Build & Run Commands

| Task | Command | Notes |
|:---|:---|:---|
| **Fast Install (All Modules)** | `mvn install "-Dmaven.test.skip=true" "-Dmaven.javadoc.skip=true" "-Djacoco.skip=true"` | Skips tests, javadocs, and JaCoCo coverage |
| **Fast Install (Specific Submodule)** | `mvn install -pl ots-road -Dmaven.test.skip=true -Dmaven.javadoc.skip=true -Djacoco.skip=true` | Installs updated JAR into local `.m2` repo |
| **Build Classpath for `ots-demo`** | `mvn dependency:build-classpath "-Dmdep.outputFile=classpath.txt"` | (Run inside `ots-demo` folder) |
| **Direct Java Simulation Execution** | `$cp = "target/classes;../ots-xml/target/classes;../ots-road/target/classes;../ots-core/target/classes;../ots-base/target/classes;../ots-kpi/target/classes;" + (Get-Content classpath.txt); java -cp $cp org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.RunFreiburgParallel` | Fast, avoids `exec-maven-plugin` JAXB ClassLoader issues |

---

## ❌ Known Issues & Solutions

### 1. GlassFish JAXB ClassLoader Mismatch (`InternalError` / `XmlJavaTypeAdapter` / `NoClassDefFoundError`)

#### 🚩 Symptoms
When running simulations via `mvn exec:java`, the execution crashes with either:
```text
java.lang.InternalError: Fehler beim Aufruf von Reflektion auf Zielklassen. Stellen Sie sicher, dass alle referenzierten Klassen im Classpath vorhanden sind: interface jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter
```
or
```text
java.lang.NoClassDefFoundError: org/opentrafficsim/xml/generated/Ots
```

#### 🔍 Root Cause
- `exec-maven-plugin:3.6.3:java` executes main classes inside an isolated `ExecJavaClassLoader`.
- GlassFish JAXB uses reflection via `RuntimeInlineAnnotationReader` to discover JAXB adapter annotations (`@XmlJavaTypeAdapter`) on generated classes (`org.opentrafficsim.xml.generated.*`).
- When worker threads are spawned by `ScenarioManager` (`ExecutorService`), they inherit a generic thread context classloader that cannot access the plugin-isolated classes.

#### ✅ Solution
Use **Direct Java Launch** with explicit local `target/classes` paths:
```powershell
cd d:\Mitarbeitende\gw2128\repositories\opentrafficsim\ots-demo
mvn dependency:build-classpath "-Dmdep.outputFile=classpath.txt"
$cp = "target/classes;../ots-xml/target/classes;../ots-road/target/classes;../ots-core/target/classes;../ots-base/target/classes;../ots-kpi/target/classes;" + (Get-Content classpath.txt)
java -cp $cp org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.RunFreiburgParallel
```

---

### 2. Edits in `ots-road` or `ots-xml` Not Taking Effect / `ClassNotFoundException`

#### 🚩 Symptoms
- You modified a class in `ots-road` or `ots-xml` (e.g., added a new parameter or logging class), but `RunFreiburgParallel` acts as if the changes don't exist.
- `ClassNotFoundException` for a newly introduced class.

#### 🔍 Root Cause
- `ots-demo` declares `ots-road` and `ots-xml` as Maven dependencies.
- Maven resolves these dependencies from the **local `.m2` repository** (`C:\Users\<user>\.m2\repository\org\opentrafficsim\...`).
- Running `mvn compile` inside `ots-road` only updates `ots-road/target/classes`, **not** the `.m2` JAR file.

#### ✅ Solution
Always install updated submodules into `.m2` using `mvn install`:
```powershell
mvn install -pl ots-road,ots-xml "-Dmaven.test.skip=true" "-Dmaven.javadoc.skip=true" "-Djacoco.skip=true"
```

---

### 3. Build Failures during JaCoCo Report or Surefire Unit Tests

#### 🚩 Symptoms
Running standard `mvn install` or `mvn compile` fails with:
```text
[ERROR] Failed to execute goal org.jacoco:jacoco-maven-plugin:0.8.12:report (post-unit-test) on project ots-xml
```
or unit test failures in unrelated modules.

#### 🔍 Root Cause
JaCoCo requires test execution data files (`jacoco-ut.exec`), which are missing when tests are skipped using `-DskipTests` without disabling JaCoCo.

#### ✅ Solution
Pass all three fast-build flags:
```powershell
mvn install "-Dmaven.test.skip=true" "-Dmaven.javadoc.skip=true" "-Djacoco.skip=true"
```

---

### 4. JAXB Race Condition in Parallel Multithreaded Simulations

#### 🚩 Symptoms
When running 24+ parallel threads in `ScenarioManager`, random threads crash on XML parsing with concurrent classloading exceptions.

#### 🔍 Root Cause
`JAXBContext.newInstance(Ots.class)` is **not thread-safe** during initial creation and class discovery.

#### ✅ Solution
Pre-warm the static `JAXBContext` on the main thread before starting the thread pool:
```java
// In main() before starting ScenarioManager thread pool:
org.opentrafficsim.road.network.factory.xml.parser.XmlParser.warmUpJAXBContext();
```

---

### 5. Inconsistent `ots-xml` State after a Module-Scoped `mvn clean`

#### 🚩 Symptoms
Several unrelated-looking failures, all traceable to the same cause. At **runtime**, in code that was never touched:
```text
JAXB Context Warmup Exception: java.lang.NoClassDefFoundError: ColorType
java.lang.NoClassDefFoundError: StringType
```
thrown from `XmlParser.parseXml` → `FreiburgNord.buildNetwork`, even though the class in question is present in `ots-xml/target/classes` and loadable with `javap`.

At **build time**, in files nobody edited (e.g. `ShortMerge.java`, `TrafCodDemo2.java`):
```text
[ERROR] cannot access XmlParserException
  class file for XmlParserException not found
[ERROR] Failed to execute goal ...maven-jar-plugin:jar (default-jar) on project ots-xml:
  java.nio.file.NoSuchFileException: ...\ots-xml\target\classes\...\DemandParser$2$1.class
[ERROR] Failed to execute goal ...maven-source-plugin:jar-no-fork (attach-sources) on project ots-xml:
  ...\ots-xml\src\main\java\org\opentrafficsim\xml\generated\AccelerationDistType.java
```

#### 🔍 Root Cause
`ots-xml` generates its JAXB binding classes during `generate-sources` (`jaxb:4.0.8:generate`).

- A module-scoped `mvn clean -pl ots-xml` deletes `target/`, including those generated sources.
- If the following rebuild is **offline** (`-o`) or otherwise partial, an **incomplete JAR can still be installed into `.m2`**, and the plugin can fail midway leaving `target/classes` and the installed artifact disagreeing with each other.
- `ots-demo` resolves `ots-xml` from `.m2` (see issue 2), so it then compiles and runs against that inconsistent artifact — which is why the errors surface in classes that were never modified.
- **Incremental compilation masks it**: an unchanged source file is not recompiled, so a stale `target/classes` may keep working for several builds before a clean build exposes the breakage.

#### 🔬 Diagnosis
`TestReflection` reproduces the failure in seconds, without running a simulation. It enumerates the generated classes **as the JVM sees them** and performs the same reflection the JAXB annotation reader does:

```powershell
java -cp $cp org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.TestReflection
```

Healthy:
```text
Loaded from: file:/C:/Users/<user>/.m2/repository/org/opentrafficsim/ots-xml/1.7.6/ots-xml-1.7.6.jar  (JAR - typically the .m2 copy)
Scanned 116 classes, 0 offending.
```

Broken:
```text
Loaded from: .../ots-xml/target/classes/org/opentrafficsim/xml/generated  (directory - typically a module's target/classes)
  OFFENDING: org.opentrafficsim.xml.generated.LinkAnimationType
             NoClassDefFoundError: org/opentrafficsim/xml/bindings/types/ColorType
Scanned 116 classes, 7 offending.
```

The **`Loaded from:`** line is usually the actual answer: it names which copy of `ots-xml` this classpath resolves to, which is the thing that differs between a working and a failing run. Exit code is 0 when clean and 1 when any class fails, so it can gate a run in a script. Pass a package name as an argument to scan something else.

#### ✅ Solution
Rebuild the whole dependency chain in one **online** clean build, so `jaxb:generate` runs and every artifact is regenerated and reinstalled consistently:
```powershell
mvn clean install -pl ots-demo -am "-Dmaven.test.skip=true" "-Dmaven.javadoc.skip=true" "-Djacoco.skip=true"
```
Leave off `-o` here: the JAXB plugin and its dependencies must be resolvable.

#### 🛡️ Prevention
- Avoid module-scoped `mvn clean -pl ots-xml`. Clean the chain (`-pl ots-demo -am`) or not at all.
- Never trust an incremental `BUILD SUCCESS` after an `ots-xml` failure — verify with a clean build before drawing conclusions from a run.

---

### 6. Dangling Source References Surviving Incremental Builds

#### 🚩 Symptoms
A clean build fails on files nobody has touched recently, naming classes that no longer exist:
```text
[ERROR] cannot find symbol: class ExtendedDataRelaxedHeadway
[ERROR] cannot find symbol: class ExtendedDataHeadwayRelaxationProgress
[ERROR] cannot find symbol: class ExtendedDataRelaxationTargetHeadway
```
Incremental builds keep succeeding, sometimes for days. The failure only appears when something forces a full recompile — `mvn clean install`, a CI release build, or a script that cleans (for instance `cluster/profile_matrix.sh`).

#### 🔍 Root Cause
When a class is deleted, javac only reports the break in files it actually recompiles. A referencing file that has not changed keeps its cached bytecode in `target/classes` and is never re-read, so the dangling reference stays invisible.

This is related to issue 5 but not the same thing: there the *artifact* was inconsistent while the sources were fine; here the **sources are genuinely broken** and the stale artifact is merely hiding it. Rebuilding does not fix it — it reveals it.

Two forms, both fatal to a clean build:
- an actual **usage** (`new ExtendedDataRelaxedHeadway()`), and
- a bare **import** of the deleted class with no usage at all — easy to miss, because searching for usages finds nothing.

#### ✅ Solution
Remove the references. If a whole call was chained onto a builder, keep the chain's receiver intact:
```java
// broken: the first call was chained directly onto build(...)
RoadSampler.build(this.network).registerExtendedDataType(new ExtendedDataRelaxedHeadway())
        .registerExtendedDataType(new ExtendedDataActionState())

// fixed
RoadSampler.build(this.network)
        .registerExtendedDataType(new ExtendedDataActionState())
```

#### 🛡️ Prevention
When deleting a class, search for its **name**, not for the way you expect it to be used:
```bash
grep -rn "ClassName" --include=*.java . | grep -v "/target/"
```
Grepping for `registerExtendedDataType` would have found the usages but missed the import-only file; grepping for the class name finds both. Then confirm with a clean build of the affected module chain:
```bash
mvn clean install -pl ots-demo -am -Dmaven.test.skip=true -Dmaven.javadoc.skip=true -Djacoco.skip=true
```
An incremental `BUILD SUCCESS` after a deletion proves nothing.

Note that `ots-demo/src/test/.../doc/tutorials/SimpleSimulation.java` is a tutorial source file with no `@Test` methods, so the test phase does not exercise these scenarios. The GitHub release workflow does run `mvn clean package`, which would catch it — but only on release, which is far too late to be a useful signal.

---
