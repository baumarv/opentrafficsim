package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Diagnostic for the JAXB class-loading failures described in {@code docs/mirova/troubleshooting_and_compilation.md},
 * issues 1 and 5.
 * <p>
 * Both present as a {@code NoClassDefFoundError} thrown from deep inside the GlassFish JAXB annotation reader while parsing
 * a network XML, naming a class that is demonstrably present on disk. The cause is that the generated binding classes come
 * from an artifact that disagrees with itself - typically an {@code ots-xml} JAR installed from a partial build, or a
 * {@code target/classes} tree left inconsistent by a module-scoped clean.
 * </p>
 * <p>
 * This tool reproduces the failure directly and cheaply: it enumerates the generated classes as the JVM sees them, loads
 * each one and asks for its declared fields - the very reflection the annotation reader performs - and reports every class
 * that fails. It also prints the artifact the classes were actually loaded from, which is usually the answer to the real
 * question: <i>which</i> copy is the JVM using?
 * </p>
 * <p>
 * Usage: {@code TestReflection [packageName]}, defaulting to {@value #DEFAULT_PACKAGE}. Run it with the same classpath as
 * the failing simulation, otherwise it inspects a different artifact than the one that broke:
 * </p>
 * <pre>
 * java -cp "$(cat cp.txt)" org.opentrafficsim.demo.mirova.scenariomanagement.scenarios.TestReflection
 * </pre>
 * <p>
 * Exits with 0 when every class loads, and 1 when any class fails, so it can gate a run in a script.
 * </p>
 * <p>
 * Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author Marvin Baumann
 */
public final class TestReflection
{
    /** The package scanned when none is given: the JAXB classes generated from the OTS XML schema. */
    public static final String DEFAULT_PACKAGE = "org.opentrafficsim.xml.generated";

    /** Utility class; not to be instantiated. */
    private TestReflection()
    {
        // utility class
    }

    /**
     * Main execution method.
     * @param args String[]; optionally the package to scan, defaulting to {@value #DEFAULT_PACKAGE}
     */
    public static void main(final String[] args)
    {
        String packageName = (args.length > 0 && !args[0].trim().isEmpty()) ? args[0].trim() : DEFAULT_PACKAGE;
        String resourcePath = packageName.replace('.', '/');

        // Deliberately resolved through the class loader rather than from a source directory: the point is to inspect the
        // artifact this JVM would actually load, which is exactly what differs between a healthy and a broken build.
        URL location = Thread.currentThread().getContextClassLoader().getResource(resourcePath);
        if (location == null)
        {
            location = TestReflection.class.getClassLoader().getResource(resourcePath);
        }
        if (location == null)
        {
            System.err.println("Package " + packageName + " is not on the classpath at all.");
            System.err.println("Run this with the same -cp as the failing simulation.");
            System.exit(2);
            return;
        }

        System.out.println("Package:  " + packageName);
        System.out.println("Loaded from: " + describeLocation(location));

        List<String> classNames;
        try
        {
            classNames = listClassNames(location, packageName, resourcePath);
        }
        catch (Exception exception)
        {
            System.err.println("Could not enumerate " + packageName + ": " + exception);
            System.exit(2);
            return;
        }

        if (classNames.isEmpty())
        {
            System.err.println("No classes found in " + packageName + " - is the artifact empty or filtered?");
            System.exit(2);
            return;
        }

        int offending = 0;
        for (String className : classNames)
        {
            try
            {
                Class<?> clazz = Class.forName(className, false, TestReflection.class.getClassLoader());
                // The annotation reader walks fields of the class and of its inner classes; both must resolve.
                clazz.getDeclaredFields();
                for (Class<?> inner : clazz.getDeclaredClasses())
                {
                    inner.getDeclaredFields();
                }
            }
            // LinkageError covers NoClassDefFoundError, which is the form this failure normally takes.
            catch (LinkageError | ClassNotFoundException error)
            {
                offending++;
                System.out.println("  OFFENDING: " + className);
                System.out.println("             " + error.getClass().getSimpleName() + ": " + error.getMessage());
            }
        }

        System.out.println("Scanned " + classNames.size() + " classes, " + offending + " offending.");
        if (offending > 0)
        {
            System.out.println();
            System.out.println("This is the signature of an inconsistent ots-xml artifact. Rebuild the whole chain online:");
            System.out.println("  mvn clean install -pl ots-demo -am "
                    + "\"-Dmaven.test.skip=true\" \"-Dmaven.javadoc.skip=true\" \"-Djacoco.skip=true\"");
            System.out.println("See docs/mirova/troubleshooting_and_compilation.md, issue 5.");
        }
        System.exit(offending > 0 ? 1 : 0);
    }

    /**
     * Lists the fully qualified names of the classes in a package, reading either a JAR or an exploded directory. Inner
     * classes are skipped, since they are reached through their enclosing class.
     * @param location URL; the class loader's location for the package
     * @param packageName String; the package being scanned
     * @param resourcePath String; the package name as a resource path
     * @return List&lt;String&gt;; the fully qualified class names, sorted
     * @throws Exception when the location cannot be read
     */
    private static List<String> listClassNames(final URL location, final String packageName, final String resourcePath)
            throws Exception
    {
        List<String> classNames = new ArrayList<>();

        if ("jar".equals(location.getProtocol()))
        {
            String path = location.getPath();
            String jarPath = URLDecoder.decode(path.substring(path.indexOf(':') + 1, path.indexOf('!')),
                    StandardCharsets.UTF_8);
            try (JarFile jar = new JarFile(jarPath))
            {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements())
                {
                    String name = entries.nextElement().getName();
                    if (name.startsWith(resourcePath) && name.endsWith(".class") && !name.contains("$"))
                    {
                        classNames.add(name.substring(0, name.length() - ".class".length()).replace('/', '.'));
                    }
                }
            }
        }
        else
        {
            File directory = new File(URLDecoder.decode(location.getPath(), StandardCharsets.UTF_8));
            File[] files = directory.listFiles();
            if (files != null)
            {
                for (File file : files)
                {
                    String name = file.getName();
                    if (name.endsWith(".class") && !name.contains("$"))
                    {
                        classNames.add(packageName + "." + name.substring(0, name.length() - ".class".length()));
                    }
                }
            }
        }

        Collections.sort(classNames);
        return classNames;
    }

    /**
     * Describes where a package was loaded from, in a form that identifies the artifact: the JAR path for a JAR, otherwise
     * the directory.
     * @param location URL; the class loader's location for the package
     * @return String; a human-readable description of the artifact
     */
    private static String describeLocation(final URL location)
    {
        String path = location.toString();
        if (path.startsWith("jar:"))
        {
            return path.substring(4, path.indexOf('!')) + "  (JAR - typically the .m2 copy)";
        }
        return path + "  (directory - typically a module's target/classes)";
    }
}
