package org.example;

import java.io.*;
import java.util.*;

public class PropertyRenameRunner {

    public static class ClassInfo {
        public final String packageName;
        public final String filePath;

        public ClassInfo(String packageName, String filePath) {
            this.packageName = packageName;
            this.filePath = filePath;
        }
    }

    public void runPropertyRename(String[] args, boolean renameGetSet) {
        String baseProjFolder;
        if (args.length > 0) {
            baseProjFolder = args[0];
        } else {
            baseProjFolder = "/Users/lakshithadeshapriya/Mine/Work/101Digital/Code/";
        }

        Scanner scanner = new Scanner(System.in);

        Util.logInline("Service folder name: ");
        String serviceFolder = scanner.nextLine();

        String directoryPath = baseProjFolder + serviceFolder + "/";

        File directory = new File(directoryPath);
        if (!directory.exists()) {
            Util.log("Directory not found: " + directoryPath);
            return;
        }

        Util.logInline(
                "CSV mapping file path (press Enter for default 'sample-property-mapping.csv'): ");
        String csvFilePath = scanner.nextLine().trim();

        // Use default if no path provided
        if (csvFilePath.isEmpty()) {
            csvFilePath = "sample-property-mapping.csv";
            Util.log("Using default CSV file: " + csvFilePath);
        }

        File csvFile = new File(csvFilePath);
        if (!csvFile.exists() || !csvFile.isFile()) {
            Util.log("CSV file not found: " + csvFilePath);
            return;
        }

        String separator = Util.repeatString("=", 80);

        Util.log("\n" + separator);
        Util.log("PROPERTY RENAMING TOOL");
        Util.log(separator);
        Util.log("Base Directory: " + directoryPath);
        Util.log("CSV Mapping File: " + csvFilePath);
        Util.log(separator);

        // Read the CSV mappings
        PropertyMappingReader mappingReader = new PropertyMappingReader();
        Map<String, String> mappings = mappingReader.readMappings(csvFilePath);

        Util.log("Loaded " + mappings.size() + " property mappings from CSV");
        Util.log(separator);

        // Scan and rename properties
        PropertyScanner propertyScanner = new PropertyScanner();
        propertyScanner.setSilentMode(true); // Disable console output from scanner
        PropertyRenamer propertyRenamer = new PropertyRenamer();

        List<PropertyRenamer.RenameResult> allResults = new ArrayList<>();
        Map<String, List<PropertyScanner.PropertyMatch>> fileMatches = new HashMap<>();
        Map<String, String> fieldRenames =
                new HashMap<>(); // Track field renames: "ClassName.oldField" -> "newField"

        // First, scan all files and collect property matches
        Util.log("Scanning files...");
        List<String> allJavaFiles = new ArrayList<>();
        collectPropertyMatches(directory, propertyScanner, fileMatches, allJavaFiles);

        Util.log("Found properties in " + fileMatches.size() + " files");
        Util.log("\nRenaming properties...\n");

        // Process each file and rename properties
        for (Map.Entry<String, List<PropertyScanner.PropertyMatch>> entry :
                fileMatches.entrySet()) {
            String filePath = entry.getKey();
            List<PropertyScanner.PropertyMatch> matches = entry.getValue();

            List<PropertyRenamer.RenameResult> results =
                    propertyRenamer.renamePropertiesInFile(filePath, matches, mappings);
            allResults.addAll(results);

            trackFieldRenames(results, filePath, fieldRenames);
        }

        Util.log("Checking for nested configuration classes...\n");
        for (String javaFile : allJavaFiles) {
            if (!fileMatches.containsKey(javaFile)) {
                // This file has no property matches, but might be a nested config class
                List<PropertyRenamer.RenameResult> results =
                        propertyRenamer.renamePropertiesInFile(
                                javaFile, new ArrayList<>(), mappings);
                allResults.addAll(results);

                // Track field renames from nested classes for later getter/setter updates
                trackFieldRenames(results, javaFile, fieldRenames);
            }
        }

        if (!fieldRenames.isEmpty() && renameGetSet) {
            Util.log("Updating getter/setter calls for renamed fields...\n");

            // Build a map of className -> (package, file path) from ALL Java files
            // This ensures we have package information for all classes that may have been renamed
            Map<String, ClassInfo> classInfoMap = new HashMap<>();
            for (String javaFile : allJavaFiles) {
                String className = extractClassName(javaFile);
                if (className != null) {
                    String packageName = extractPackageName(javaFile);
                    classInfoMap.put(className, new ClassInfo(packageName, javaFile));
                }
            }

            for (String javaFile : allJavaFiles) {
                List<PropertyRenamer.RenameResult> results =
                        propertyRenamer.updateGetterSetterCalls(
                                javaFile, fieldRenames, classInfoMap);
                allResults.addAll(results);
            }
        }

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

    private String extractPackageName(String filePath) {
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("package ")) {
                    String packageDecl = line.substring(8).trim();
                    if (packageDecl.endsWith(";")) {
                        packageDecl = packageDecl.substring(0, packageDecl.length() - 1);
                    }
                    return packageDecl.trim();
                }
                if (line.startsWith("import ")
                        || line.contains("class ")
                        || line.contains("interface ")) {
                    break;
                }
            }
        } catch (IOException e) {
            Util.logError("Error reading package from file: " + filePath + " - " + e.getMessage());
        }
        return "";
    }

    private void trackFieldRenames(
            List<PropertyRenamer.RenameResult> results,
            String filePath,
            Map<String, String> fieldRenames) {
        String className = extractClassName(filePath);
        if (className == null) {
            return;
        }

        for (PropertyRenamer.RenameResult result : results) {
            if (result.status == PropertyRenamer.RenameStatus.RENAMED) {
                // Track renames from ConfigurationProperties fields
                if (result.patternType
                        == PropertyScanner.PatternType.CONFIGURATION_PROPERTIES_FIELD) {
                    String oldFieldName = result.extraInfo;
                    String key = className + "." + oldFieldName;

                    String newFieldName = extractNewFieldName(result.oldKey, result.newKey);
                    if (newFieldName != null && !newFieldName.equals(oldFieldName)) {
                        fieldRenames.put(key, newFieldName);
                    }
                } else if (result.patternType
                        == PropertyScanner.PatternType.NESTED_CONFIGURATION_FIELD) {
                    String key = className + "." + result.oldKey;
                    fieldRenames.put(key, result.newKey);
                }
            }
        }
    }

    private String extractNewFieldName(String oldPropertyPath, String newPropertyPath) {
        if (oldPropertyPath == null || newPropertyPath == null) {
            return null;
        }

        // Remove array indices to get clean paths
        String oldClean = oldPropertyPath.replaceAll("\\[\\d+\\]", "");
        String newClean = newPropertyPath.replaceAll("\\[\\d+\\]", "");

        // Split by dots and compare segments
        String[] oldParts = oldClean.split("\\.");
        String[] newParts = newClean.split("\\.");

        if (oldParts.length >= 2 && newParts.length >= 2) {
            for (int i = 1; i < Math.min(oldParts.length, newParts.length); i++) {
                if (!oldParts[i].equals(newParts[i])) {
                    // Convert from property name (kebab-case) to field name (camelCase)
                    return toFieldName(newParts[i]);
                }
            }
        }

        return null;
    }

    private String toFieldName(String propertyName) {
        // Convert property name (kebab-case) to field name (camelCase)
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = false;

        for (char c : propertyName.toCharArray()) {
            if (c == '-' || c == '_' || c == '.') {
                capitalizeNext = true;
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                result.append(c);
            }
        }

        return result.toString();
    }

    private void collectPropertyMatches(
            File directory,
            PropertyScanner scanner,
            Map<String, List<PropertyScanner.PropertyMatch>> fileMatches,
            List<String> allJavaFiles) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    // Skip target, build, and common build output directories
                    String dirName = file.getName();
                    if (!dirName.equals("target")
                            && !dirName.equals("build")
                            && !dirName.equals(".git")
                            && !dirName.equals("node_modules")) {
                        collectPropertyMatches(file, scanner, fileMatches, allJavaFiles);
                    }
                } else if (file.isFile()
                        && (file.getName().endsWith(".java")
                                || file.getName().endsWith(".properties")
                                || file.getName().endsWith(".yml")
                                || file.getName().endsWith(".yaml"))) {

                    // Skip excluded files (api.yml and message property files)
                    if (shouldExcludeFile(file)) {
                        Util.log("Skipping file: " + file.getName());
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

        if ((fileName.endsWith("yml") || fileName.endsWith("yaml"))
                && !fileName.startsWith("application")) {
            return true;
        }

        return fileName.startsWith("message")
                && (fileName.endsWith(".properties")
                        || fileName.endsWith(".yml")
                        || fileName.endsWith(".yaml"));
    }

    private void generateSummaryReport(
            List<PropertyRenamer.RenameResult> results, String separator) {
        List<PropertyRenamer.RenameResult> fieldChanges = new ArrayList<>();
        List<PropertyRenamer.RenameResult> unchangedFieldChanges = new ArrayList<>();
        List<PropertyRenamer.RenameResult> propertyResults = new ArrayList<>();

        for (PropertyRenamer.RenameResult result : results) {
            if (isFieldChange(result)) {
                if (isFieldActuallyUnchanged(result)) {
                    unchangedFieldChanges.add(result);
                } else {
                    fieldChanges.add(result);
                }
            } else {
                propertyResults.add(result);
            }
        }

        // Categorize property results (excluding field changes)
        List<PropertyRenamer.RenameResult> renamed = new ArrayList<>();
        List<PropertyRenamer.RenameResult> unchanged = new ArrayList<>();
        List<PropertyRenamer.RenameResult> noMapping = new ArrayList<>();

        for (PropertyRenamer.RenameResult result : propertyResults) {
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

        Util.log("\n" + separator);
        Util.log("PROPERTY RENAME SUMMARY");
        Util.log(separator);
        Util.log("Total properties processed: " + propertyResults.size());
        Util.log("  - Renamed: " + renamed.size());
        Util.log("  - Unchanged (mapping exists, same value): " + unchanged.size());
        Util.log("  - No mapping found: " + noMapping.size());
        if (!fieldChanges.isEmpty() || !unchangedFieldChanges.isEmpty()) {
            Util.log("\nTotal field changes: " + fieldChanges.size());
            Util.log("  - Unchanged fields (mapping exists, same value): " + unchangedFieldChanges.size());
        }
        Util.log(separator);

        // Print renamed properties
        if (!renamed.isEmpty()) {
            Util.log("\n" + Util.repeatString("-", 80));
            Util.log("RENAMED PROPERTIES (" + renamed.size() + ")");
            Util.log(Util.repeatString("-", 80));

            Map<String, List<PropertyRenamer.RenameResult>> groupedByFile = groupByFile(renamed);
            for (Map.Entry<String, List<PropertyRenamer.RenameResult>> entry :
                    groupedByFile.entrySet()) {
                String[] fileParts = entry.getKey().split(File.separator);
                String fileName = fileParts[fileParts.length - 1];
                for (PropertyRenamer.RenameResult result : entry.getValue()) {

                    String fileDisplay =
                            String.format(
                                    "File: %-50s Line %4d: %s → %s [%s]",
                                    fileName,
                                    result.lineNumber,
                                    result.oldKey,
                                    result.newKey,
                                    result.getPatternDescription());
                    Util.log(fileDisplay);
                }
            }
        }

        // Print unchanged properties
        if (!unchanged.isEmpty()) {
            Util.log("\n" + Util.repeatString("-", 80));
            Util.log("UNCHANGED PROPERTIES (" + unchanged.size() + ")");
            Util.log(Util.repeatString("-", 80));

            Map<String, List<PropertyRenamer.RenameResult>> groupedByFile = groupByFile(unchanged);
            for (Map.Entry<String, List<PropertyRenamer.RenameResult>> entry :
                    groupedByFile.entrySet()) {
                String[] fileParts = entry.getKey().split(File.separator);
                String fileName = fileParts[fileParts.length - 1];
                for (PropertyRenamer.RenameResult result : entry.getValue()) {
                    Util.log(
                            String.format(
                                    "File: %-50s Line %4d: %s [%s]",
                                    fileName,
                                    result.lineNumber,
                                    result.oldKey,
                                    result.getPatternDescription()));
                }
            }
        }

        // Print field changes
        if (!fieldChanges.isEmpty()) {
            Util.log("\n" + Util.repeatString("-", 80));
            Util.log("FIELD CHANGES (" + fieldChanges.size() + ")");
            Util.log(Util.repeatString("-", 80));

            Map<String, List<PropertyRenamer.RenameResult>> groupedByFile =
                    groupByFile(fieldChanges);
            for (Map.Entry<String, List<PropertyRenamer.RenameResult>> entry :
                    groupedByFile.entrySet()) {
                String[] fileParts = entry.getKey().split(File.separator);
                String fileName = fileParts[fileParts.length - 1];
                for (PropertyRenamer.RenameResult result : entry.getValue()) {
                    if (result.status == PropertyRenamer.RenameStatus.RENAMED) {
                        Util.log(
                                String.format(
                                        "File: %-50s Line %4d: %s → %s [%s]",
                                        fileName,
                                        Math.max(result.lineNumber, 0),
                                        result.oldKey,
                                        result.newKey,
                                        result.getPatternDescription()));
                    } else {
                        Util.log(
                                String.format(
                                        "File: %-50s Line %4d: %s [%s]",
                                        fileName,
                                        Math.max(result.lineNumber, 0),
                                        result.oldKey,
                                        result.getPatternDescription()));
                    }
                }
            }
        }

        // Print unchanged field changes
        if (!unchangedFieldChanges.isEmpty()) {
            Util.log("\n" + Util.repeatString("-", 80));
            Util.log("UNCHANGED FIELDS (mapping exists, same value) (" + unchangedFieldChanges.size() + ")");
            Util.log(Util.repeatString("-", 80));

            Map<String, List<PropertyRenamer.RenameResult>> groupedByFile =
                    groupByFile(unchangedFieldChanges);
            for (Map.Entry<String, List<PropertyRenamer.RenameResult>> entry :
                    groupedByFile.entrySet()) {
                String[] fileParts = entry.getKey().split(File.separator);
                String fileName = fileParts[fileParts.length - 1];
                for (PropertyRenamer.RenameResult result : entry.getValue()) {
                    Util.log(
                            String.format(
                                    "File: %-50s Line %4d: %s [%s]",
                                    fileName,
                                    Math.max(result.lineNumber, 0),
                                    result.oldKey,
                                    result.getPatternDescription()));
                }
            }
        }

        // Print properties without mapping
        if (!noMapping.isEmpty()) {
            Util.log("\n" + Util.repeatString("-", 80));
            Util.log("PROPERTIES WITHOUT MAPPING (" + noMapping.size() + ")");
            Util.log(Util.repeatString("-", 80));

            Map<String, List<PropertyRenamer.RenameResult>> groupedByFile = groupByFile(noMapping);
            for (Map.Entry<String, List<PropertyRenamer.RenameResult>> entry :
                    groupedByFile.entrySet()) {
                String[] fileParts = entry.getKey().split(File.separator);
                String fileName = fileParts[fileParts.length - 1];
                for (PropertyRenamer.RenameResult result : entry.getValue()) {
                    Util.log(
                            String.format(
                                    "File: %-50s Line %4d: %s [%s]",
                                    fileName,
                                    result.lineNumber,
                                    result.oldKey,
                                    result.getPatternDescription()));
                }
            }
        }

        // Print cross-check summary for unchanged fields at the end
        if (!unchangedFieldChanges.isEmpty() || !unchanged.isEmpty()) {
            Util.log("\n" + separator);
            Util.log("CROSS-CHECK: ALL UNCHANGED PROPERTY FIELDS");
            Util.log(separator);
            Util.log("The following properties had mappings but required no changes:");
            Util.log("");

            // List unchanged field changes
            if (!unchangedFieldChanges.isEmpty()) {
                Util.log("Unchanged Fields:");
                for (PropertyRenamer.RenameResult result : unchangedFieldChanges) {
                    Util.log("  - " + result.oldKey);
                }
            }

            // List unchanged properties
            if (!unchanged.isEmpty()) {
                Util.log("\nUnchanged Properties:");
                for (PropertyRenamer.RenameResult result : unchanged) {
                    Util.log("  - " + result.oldKey);
                }
            }
        }

        Util.log("\n" + separator);
        Util.log("Property renaming completed!");
        Util.log(separator);
    }

    private boolean isFieldChange(PropertyRenamer.RenameResult result) {
        return result.patternType == PropertyScanner.PatternType.CONFIGURATION_PROPERTIES_FIELD
                || result.patternType == PropertyScanner.PatternType.NESTED_CONFIGURATION_FIELD
                || result.patternType == PropertyScanner.PatternType.ACCESSOR_CALLS;
    }

    private boolean isFieldActuallyUnchanged(PropertyRenamer.RenameResult result) {
        if (result.status == PropertyRenamer.RenameStatus.UNCHANGED) {
            return true;
        }

        if (result.status == PropertyRenamer.RenameStatus.NO_MAPPING) {
            return false;
        }

        if (result.oldKey == null || result.newKey == null) {
            return false;
        }

        if (result.oldKey.equals(result.newKey)) {
            return true;
        }

        String oldFieldName = extractFieldNameFromKey(result.oldKey);
        String newFieldName = extractFieldNameFromKey(result.newKey);

        if (oldFieldName != null && newFieldName != null) {
            String oldCamelCase = toCamelCase(oldFieldName);
            String newCamelCase = toCamelCase(newFieldName);
            return oldCamelCase.equals(newCamelCase);
        }

        return false;
    }

    private String extractFieldNameFromKey(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        String cleaned = key.replaceAll("\\[\\d+\\]", "");
        String[] parts = cleaned.split("\\.");
        return parts.length > 0 ? parts[parts.length - 1] : key;
    }

    private String toCamelCase(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = false;

        for (char c : input.toCharArray()) {
            if (c == '-' || c == '_') {
                capitalizeNext = true;
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                result.append(c);
            }
        }

        return result.toString();
    }

    private Map<String, List<PropertyRenamer.RenameResult>> groupByFile(
            List<PropertyRenamer.RenameResult> results) {
        Map<String, List<PropertyRenamer.RenameResult>> grouped = new LinkedHashMap<>();
        for (PropertyRenamer.RenameResult result : results) {
            grouped.computeIfAbsent(result.filePath, k -> new ArrayList<>()).add(result);
        }
        return grouped;
    }
}
