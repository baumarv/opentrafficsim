package org.opentrafficsim.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Checks that the JAXB plugin's schema list names every schema in the directory, in sorted order.
 * <p>
 * The list is explicit rather than the wildcard <code>*.xsd</code>, because the order in which XJC reads the schemas
 * decides the order of the factory methods it generates into <code>ObjectFactory</code>, and a wildcard is expanded by a
 * directory scan: Windows returns the entries sorted, Linux file systems do not. The generated sources are committed, so
 * that difference made every build on the other platform rewrite <code>ObjectFactory</code> and leave the tree dirty -
 * which the build stamp then refused. Measured: reversing the list moves <code>createLink</code> from line 141 to 71,
 * reproducing on Windows exactly what the cluster produced.
 * </p>
 * <p>
 * This test exists because an explicit list has one failure mode of its own: a schema added to the directory but not to
 * the list is simply not processed. That is loud only if something already references the missing types - if the new
 * file is merely imported by a schema we do not list, or nothing uses its types yet, it is absent in silence. The point
 * of checking here is to make that failure loud by construction instead of leaving it to chance.
 * </p>
 * <p>
 * Only the top level of the directory is compared, which is what <code>*.xsd</code> matched: <code>ref/</code> holds the
 * W3C schemas that are pulled in by import rather than processed as entry points.
 * </p>
 * <p>
 * Copyright (c) 2013-2024 Delft University of Technology, PO Box 5, 2600 AA, Delft, the Netherlands. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public class SchemaIncludesTest
{
    /** The directory the plugin scans, relative to the module. */
    private static final String SCHEMA_DIR = "src/main/resources/xsd";

    /**
     * Tests that the schema list in the pom equals the schemas on disk, in sorted order.
     * @throws IOException when the pom or the schema directory cannot be read
     */
    @Test
    public void schemaListMatchesTheDirectory() throws IOException
    {
        File module = new File(System.getProperty("user.dir"));
        File pom = new File(module, "pom.xml");
        assertTrue(pom.isFile(), "expected the module's pom.xml at " + pom.getAbsolutePath());
        File schemaDirectory = new File(module, SCHEMA_DIR);
        assertTrue(schemaDirectory.isDirectory(), "expected the schema directory at " + schemaDirectory.getAbsolutePath());

        List<String> listed = listedSchemas(Files.readString(pom.toPath(), StandardCharsets.UTF_8));
        String[] found = schemaDirectory.list((dir, name) -> name.endsWith(".xsd"));
        List<String> onDisk = new ArrayList<>(Arrays.asList(found == null ? new String[0] : found));
        onDisk.sort(String::compareTo);

        assertTrue(!onDisk.isEmpty(), "no .xsd found in " + schemaDirectory.getAbsolutePath());
        assertEquals(onDisk, listed,
                "the <schemaIncludes> list in ots-xml/pom.xml must name every .xsd in " + SCHEMA_DIR + ", sorted. "
                        + "Missing from the list: " + minus(onDisk, listed) + "; listed but absent from disk: "
                        + minus(listed, onDisk) + ". A schema that is not listed is not processed, and its types are "
                        + "then missing without any other symptom. The order matters as well: it decides the order of "
                        + "the generated ObjectFactory methods, which are committed.");
    }

    /**
     * Returns the schema file names of the {@code schemaIncludes} block, in the order the pom lists them.
     * @param pomText the pom's text
     * @return the listed file names
     */
    private static List<String> listedSchemas(final String pomText)
    {
        int start = pomText.indexOf("<schemaIncludes>");
        int end = pomText.indexOf("</schemaIncludes>");
        assertTrue(start >= 0 && end > start, "no <schemaIncludes> block in the pom");
        List<String> names = new ArrayList<>();
        Matcher matcher = Pattern.compile("<include>([^<]+)</include>").matcher(pomText.substring(start, end));
        while (matcher.find())
        {
            names.add(matcher.group(1).trim());
        }
        return names;
    }

    /**
     * Returns the entries of the first list that the second does not contain.
     * @param all the entries to filter
     * @param other the entries to remove
     * @return the difference, as a printable list
     */
    private static List<String> minus(final List<String> all, final List<String> other)
    {
        List<String> difference = new ArrayList<>(all);
        difference.removeAll(other);
        return difference;
    }
}
