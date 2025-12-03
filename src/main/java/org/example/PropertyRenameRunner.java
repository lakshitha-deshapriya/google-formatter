package org.example;

import java.io.File;
import java.util.*;

public class PropertyRenameRunner {

    public void runPropertyRename(String[] args, boolean renameGetSet) {
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

        System.out.print("CSV mapping file path (press Enter for default 'sample-property-mapping.csv'): ");
        String csvFilePath = scanner.nextLine().trim();

        // Use default if no path provided
        if (csvFilePath.isEmpty()) {
            csvFilePath = "sample-property-mapping.csv";
            System.out.println("Using default CSV file: " + csvFilePath);
        }

        File csvFile = new File(csvFilePath);
        if (!csvFile.exists() || !csvFile.isFile()) {
            System.out.println("CSV file not found: " + csvFilePath);
            return;
        }

        String separator = repeatString("=", 80);

        System.out.println("\n" + separator);
        System.out.println("PROPERTY RENAMING TOOL");
        System.out.println(separator);
        System.out.println("Base Directory: " + directoryPath);
        System.out.println("CSV Mapping File: " + csvFilePath);
        System.out.println(separator);

        // Read the CSV mappings
        PropertyMappingReader mappingReader = new PropertyMappingReader();
        Map<String, String> mappings = mappingReader.readMappings(csvFilePath);

        System.out.println("Loaded " + mappings.size() + " property mappings from CSV");
        System.out.println(separator);

        // Scan and rename properties
        PropertyScanner propertyScanner = new PropertyScanner();
        propertyScanner.setSilentMode(true);  // Disable console output from scanner
        PropertyRenamer propertyRenamer = new PropertyRenamer();

        List<PropertyRenamer.RenameResult> allResults = new ArrayList<>();
        Map<String, List<PropertyScanner.PropertyMatch>> fileMatches = new HashMap<>();
        Map<String, String> fieldRenames = new HashMap<>();  // Track field renames: "ClassName.oldField" -> "newField"

        // First, scan all files and collect property matches
        System.out.println("Scanning files...");
        List<String> allJavaFiles = new ArrayList<>();
        collectPropertyMatches(directory, propertyScanner, fileMatches, allJavaFiles);

        System.out.println("Found properties in " + fileMatches.size() + " files");
        System.out.println("\nRenaming properties...\n");

        // Process each file and rename properties
        for (Map.Entry<String, List<PropertyScanner.PropertyMatch>> entry : fileMatches.entrySet()) {
            String filePath = entry.getKey();
            List<PropertyScanner.PropertyMatch> matches = entry.getValue();

            List<PropertyRenamer.RenameResult> results =
                propertyRenamer.renamePropertiesInFile(filePath, matches, mappings);
            allResults.addAll(results);
        }

        // Also process files that might be nested configuration classes
        // (files that don't have property matches but might contain fields used in indexed properties)
        System.out.println("Checking for nested configuration classes...\n");
        for (String javaFile : allJavaFiles) {
            if (!fileMatches.containsKey(javaFile)) {
                // This file has no property matches, but might be a nested config class
                List<PropertyRenamer.RenameResult> results =
                    propertyRenamer.renamePropertiesInFile(javaFile, new ArrayList<>(), mappings);
                allResults.addAll(results);

                // Track field renames from nested classes for later getter/setter updates
                for (PropertyRenamer.RenameResult result : results) {
                    if (result.status == PropertyRenamer.RenameStatus.RENAMED &&
                        result.patternType.contains("Nested configuration field")) {
                        // Extract class name from file path
                        String className = extractClassName(javaFile);
                        if (className != null) {
                            String key = className + "." + result.oldKey;
                            fieldRenames.put(key, result.newKey);
                        }
                    }
                }
            }
        }

        // Update getter/setter calls in all files based on field renames
        if (!fieldRenames.isEmpty() && renameGetSet) {
            System.out.println("Updating getter/setter calls for renamed fields...\n");
            for (String javaFile : allJavaFiles) {
                List<PropertyRenamer.RenameResult> results =
                    propertyRenamer.updateGetterSetterCalls(javaFile, fieldRenames);
                allResults.addAll(results);
            }
        }

        // Generate summary report
        generateSummaryReport(allResults, separator);
    }

    private String extractClassName(String filePath) {
        File file = new File(filePath);
        String fileName = file.getName();
        if (fileName.endsWith(".java")) {
            return fileName.substring(0, fileName.length() - 5);
        }
        return null;
    }

    private void collectPropertyMatches(File directory, PropertyScanner scanner,
                                       Map<String, List<PropertyScanner.PropertyMatch>> fileMatches,
                                       List<String> allJavaFiles) {
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
                        collectPropertyMatches(file, scanner, fileMatches, allJavaFiles);
                    }
                } else if (file.isFile() && (file.getName().endsWith(".java") ||
                                             file.getName().endsWith(".properties") ||
                                             file.getName().endsWith(".yml") ||
                                             file.getName().endsWith(".yaml"))) {

                    // Skip excluded files (api.yml and message property files)
                    if (shouldExcludeFile(file)) {
                        System.out.println("Skipping file: " + file.getName());
                        continue;
                    }

                    String absolutePath = file.getAbsolutePath();
                    allJavaFiles.add(absolutePath);

                    List<PropertyScanner.PropertyMatch> matches = scanner.scanFile(absolutePath);
                    if (!matches.isEmpty()) {
                        fileMatches.put(absolutePath, matches);
                    }
                }
            }
        }
    }

    private boolean shouldExcludeFile(File file) {
        String fileName = file.getName();

        if ((fileName.endsWith("yml") || fileName.endsWith("yaml")) && !fileName.startsWith("application")) {
            return true;
        }

        if (fileName.startsWith("message") &&
            (fileName.endsWith(".properties") || fileName.endsWith(".yml") || fileName.endsWith(".yaml"))) {
            return true;
        }

        return false;
    }

    private void generateSummaryReport(List<PropertyRenamer.RenameResult> results, String separator) {
        // Categorize results
        List<PropertyRenamer.RenameResult> renamed = new ArrayList<>();
        List<PropertyRenamer.RenameResult> unchanged = new ArrayList<>();
        List<PropertyRenamer.RenameResult> noMapping = new ArrayList<>();

        for (PropertyRenamer.RenameResult result : results) {
            switch (result.status) {
                case RENAMED:
                    renamed.add(result);
                    break;
                case UNCHANGED:
                    unchanged.add(result);
                    break;
                case NO_MAPPING:
                    noMapping.add(result);
                    break;
            }
        }

        System.out.println("\n" + separator);
        System.out.println("PROPERTY RENAME SUMMARY");
        System.out.println(separator);
        System.out.println("Total properties processed: " + results.size());
        System.out.println("  - Renamed: " + renamed.size());
        System.out.println("  - Unchanged (mapping exists, same value): " + unchanged.size());
        System.out.println("  - No mapping found: " + noMapping.size());
        System.out.println(separator);

        // Print renamed properties
        if (!renamed.isEmpty()) {
            System.out.println("\n" + repeatString("-", 80));
            System.out.println("RENAMED PROPERTIES (" + renamed.size() + ")");
            System.out.println(repeatString("-", 80));

            Map<String, List<PropertyRenamer.RenameResult>> groupedByFile = groupByFile(renamed);
            for (Map.Entry<String, List<PropertyRenamer.RenameResult>> entry :
                    groupedByFile.entrySet()) {
                String[] fileParts = entry.getKey().split(File.separator);
                String fileName = fileParts[fileParts.length - 1];
                for (PropertyRenamer.RenameResult result : entry.getValue()) {

                    String fileDisplay = String.format("File: %-50s Line %4d: %s → %s [%s]",
                            fileName,
                            result.lineNumber,
                            result.oldKey,
                            result.newKey,
                            result.patternType);
                    System.out.println(fileDisplay);
                }
            }
        }

        // Print unchanged properties
        if (!unchanged.isEmpty()) {
            System.out.println("\n" + repeatString("-", 80));
            System.out.println("UNCHANGED PROPERTIES (" + unchanged.size() + ")");
            System.out.println(repeatString("-", 80));

            Map<String, List<PropertyRenamer.RenameResult>> groupedByFile = groupByFile(unchanged);
            for (Map.Entry<String, List<PropertyRenamer.RenameResult>> entry : groupedByFile.entrySet()) {
                String[] fileParts = entry.getKey().split(File.separator);
                String fileName = fileParts[fileParts.length - 1];
                for (PropertyRenamer.RenameResult result : entry.getValue()) {
                    System.out.println(String.format("File: %-50s Line %4d: %s [%s]",
                                     fileName,
                                     result.lineNumber,
                                     result.oldKey,
                                     result.patternType));
                }
            }
        }

        // Print properties without mapping
        if (!noMapping.isEmpty()) {
            System.out.println("\n" + repeatString("-", 80));
            System.out.println("PROPERTIES WITHOUT MAPPING (" + noMapping.size() + ")");
            System.out.println(repeatString("-", 80));

            Map<String, List<PropertyRenamer.RenameResult>> groupedByFile = groupByFile(noMapping);
            for (Map.Entry<String, List<PropertyRenamer.RenameResult>> entry : groupedByFile.entrySet()) {
                String[] fileParts = entry.getKey().split(File.separator);
                String fileName = fileParts[fileParts.length - 1];
                for (PropertyRenamer.RenameResult result : entry.getValue()) {
                    System.out.println(String.format("File: %-50s Line %4d: %s [%s]",
                                     fileName,
                                     result.lineNumber,
                                     result.oldKey,
                                     result.patternType));
                }
            }
        }

        System.out.println("\n" + separator);
        System.out.println("Property renaming completed!");
        System.out.println(separator);
    }

    private Map<String, List<PropertyRenamer.RenameResult>> groupByFile(List<PropertyRenamer.RenameResult> results) {
        Map<String, List<PropertyRenamer.RenameResult>> grouped = new LinkedHashMap<>();
        for (PropertyRenamer.RenameResult result : results) {
            grouped.computeIfAbsent(result.filePath, k -> new ArrayList<>()).add(result);
        }
        return grouped;
    }

    private static String repeatString(String str, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(str);
        }
        return sb.toString();
    }
}

