package org.example;

import java.io.File;
import java.util.Scanner;

public class PropertyScanRunner {

    public void runPropertyScan(String[] args) {
        String baseProjFolder;
        if (args.length > 0) {
            baseProjFolder = args[0];
        } else {
            baseProjFolder = "/Users/lakshithadeshapriya/Mine/Work/101Digital/Code/";
        }

        Scanner scanner = new Scanner(System.in);

        System.out.print("Service folder name: ");
        String serviceFolder = scanner.nextLine();

        String directoryPath = baseProjFolder + serviceFolder + "/";

        File directory = new File(directoryPath);
        if (!directory.exists()) {
            System.out.println("Directory not found: " + directoryPath);
            return;
        }

        String separator = repeatString("=", 80);

        System.out.println("\n" + separator);
        System.out.println("SCANNING FOR CONFIGURATION PROPERTIES");
        System.out.println(separator);
        System.out.println("Base Directory: " + directoryPath);
        System.out.println("Looking for:");
        System.out.println("  - @Value annotations");
        System.out.println("  - @ConfigurationProperty annotations");
        System.out.println("  - @ConfigurationProperties annotations");
        System.out.println("  - Environment.getProperty() calls");
        System.out.println("  - System.getProperty() calls");
        System.out.println("  - @PropertySource annotations");
        System.out.println("  - Property placeholders ${...}");
        System.out.println(separator);

        PropertyScanner propertyScanner = new PropertyScanner();
        int totalProperties = 0;

        // Call the recursive method to traverse all files in the directory
        totalProperties = traverseDirectory(directory, propertyScanner);

        System.out.println("\n" + separator);
        System.out.println("Property scan finished!");
        System.out.println("Total properties found: " + totalProperties);
        System.out.println(separator);
    }

    private static String repeatString(String str, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(str);
        }
        return sb.toString();
    }

    private int traverseDirectory(File directory, PropertyScanner propertyScanner) {
        int count = 0;
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    // Skip target, build, and common build output directories
                    String dirName = file.getName();
                    if (!dirName.equals("target") &&
                        !dirName.equals("build") &&
                        !dirName.equals(".git") &&
                        !dirName.equals("node_modules")) {
                        count += traverseDirectory(file, propertyScanner);
                    }
                } else if (file.isFile() && file.getName().endsWith(".java")) {
                    java.util.List<PropertyScanner.PropertyMatch> matches = propertyScanner.scanFile(file.getAbsolutePath());
                    count += matches.size();
                }
            }
        }
        return count;
    }
}

