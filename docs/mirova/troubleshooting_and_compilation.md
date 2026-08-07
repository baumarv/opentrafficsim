# MiRoVA Compilation & Troubleshooting Guide

This guide documents common compilation issues, ClassLoader pitfalls, Maven execution errors, and their definitive solutions for the OpenTrafficSim (OTS) / MiRoVA framework workspace.

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
