package org.opentrafficsim.demo.mirova.scenariomanagement.scenarios;

import java.io.File;
import java.lang.reflect.Field;

public class TestReflection
{
    public static void main(String[] args)
    {
        try
        {
            File dir = new File("d:\\Mitarbeitende\\gw2128\\repositories\\opentrafficsim\\ots-xml\\src\\main\\java\\org\\opentrafficsim\\xml\\generated");
            System.out.println("Scanning directory: " + dir.getAbsolutePath());
            if (!dir.exists())
            {
                System.out.println("Directory does not exist!");
                return;
            }
            File[] files = dir.listFiles();
            if (files == null)
            {
                System.out.println("No files found!");
                return;
            }

            int offendingCount = 0;
            for (File f : files)
            {
                if (f.getName().endsWith(".java"))
                {
                    String className = "org.opentrafficsim.xml.generated." + f.getName().substring(0, f.getName().length() - 5);
                    try
                    {
                        Class<?> clazz = Class.forName(className);
                        clazz.getDeclaredFields();
                        // Scan inner classes as well
                        for (Class<?> inner : clazz.getDeclaredClasses())
                        {
                            inner.getDeclaredFields();
                        }
                    }
                    catch (NoClassDefFoundError e)
                    {
                        offendingCount++;
                        System.out.println("Found offending class: " + className);
                        System.out.println("Error details: " + e.toString());
                    }
                    catch (ClassNotFoundException e)
                    {
                        System.out.println("Class not found: " + className);
                    }
                }
            }
            System.out.println("Scan complete. Offending count: " + offendingCount);
        }
        catch (Throwable t)
        {
            t.printStackTrace();
        }
    }
}
