package org.example;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class PropertyRenamer {

    private ConfigurationPropertiesAnalyzer configPropertiesAnalyzer;

    public PropertyRenamer() {
        this.configPropertiesAnalyzer = new ConfigurationPropertiesAnalyzer();
    }

    /**
     * Renames properties in a file based on the provided mapping.
     *
     * @param filePath Path to the file to modify
     * @param propertyMatches List of property matches found in the file
     * @param mappings Map of old property key to new property key
     * @return List of RenameResult objects describing what was done
     */
    public List<RenameResult> renamePropertiesInFile(String filePath,
                                                      List<PropertyScanner.PropertyMatch> propertyMatches,
                                                      Map<String, String> mappings) {
        List<RenameResult> results = new ArrayList<>();

        // Check if this file is a nested configuration class (no property matches but has indexed property mappings)
        if (propertyMatches.isEmpty() && isNestedConfigClass(filePath, mappings)) {
            return handleNestedConfigClass(filePath, mappings);
        }

        if (propertyMatches.isEmpty()) {
            return results;
        }

        // Check if this file has @ConfigurationProperties annotation
        boolean hasConfigurationProperties = propertyMatches.stream()
            .anyMatch(m -> m.patternType.equals("@ConfigurationProperties annotation (prefix)"));

        // If it has @ConfigurationProperties, use the specialized analyzer
        if (hasConfigurationProperties) {
            return handleConfigurationPropertiesFile(filePath, propertyMatches, mappings);
        }

        // Otherwise, handle as a regular file
        return handleRegularFile(filePath, propertyMatches, mappings);
    }

    /**
     * Check if a file is a nested configuration class based on indexed property mappings.
     * E.g., if mappings contain "identity.adapters[0].appId" and the file defines appId field.
     */
    private boolean isNestedConfigClass(String filePath, Map<String, String> mappings) {
        // Check if any mapping contains indexed property notation
        for (String key : mappings.keySet()) {
            if (key.matches(".*\\[\\d+\\]\\.\\w+.*")) {
                return true; // This indicates indexed properties exist
            }
        }
        return false;
    }

    /**
     * Handle nested configuration classes (like IdentityAdapterDefinition)
     * that are used in List/array properties with indexed notation.
     */
    private List<RenameResult> handleNestedConfigClass(String filePath, Map<String, String> mappings) {
        List<RenameResult> results = new ArrayList<>();

        // Use the analyzer to process nested config class
        ConfigurationPropertiesAnalyzer.AnalysisResult analysis =
            configPropertiesAnalyzer.analyzeNestedConfigClass(filePath, mappings);

        if (analysis == null || analysis.fields.isEmpty()) {
            return results;
        }

        // Process field renames
        for (ConfigurationPropertiesAnalyzer.FieldInfo field : analysis.fields) {
            if (field.needsRename) {
                results.add(new RenameResult(
                    filePath,
                    field.lineNumber,
                    field.fieldName,
                    field.newFieldName,
                    RenameStatus.RENAMED,
                    "Nested configuration field (used in indexed property)"
                ));
            }
        }

        // Apply changes to the file
        configPropertiesAnalyzer.applyChanges(analysis);

        return results;
    }

    /**
     * Handle files with @ConfigurationProperties annotation.
     * This requires analyzing the class structure and renaming both the prefix and fields.
     */
    private List<RenameResult> handleConfigurationPropertiesFile(String filePath,
                                                                  List<PropertyScanner.PropertyMatch> propertyMatches,
                                                                  Map<String, String> mappings) {
        List<RenameResult> results = new ArrayList<>();

        // Analyze the configuration properties class
        ConfigurationPropertiesAnalyzer.AnalysisResult analysis =
            configPropertiesAnalyzer.analyzeFile(filePath, mappings);

        if (analysis == null) {
            // Fallback to regular processing if analysis fails
            return handleRegularFile(filePath, propertyMatches, mappings);
        }

        // Handle prefix change
        if (analysis.prefixChanged) {
            results.add(new RenameResult(
                filePath,
                analysis.prefixLineNumber,
                analysis.prefix,
                analysis.newPrefix,
                RenameStatus.RENAMED,
                "@ConfigurationProperties annotation (prefix)"
            ));
        } else if (mappings.containsKey(analysis.prefix)) {
            results.add(new RenameResult(
                filePath,
                analysis.prefixLineNumber,
                analysis.prefix,
                analysis.prefix,
                RenameStatus.UNCHANGED,
                "@ConfigurationProperties annotation (prefix)"
            ));
        } else {
            results.add(new RenameResult(
                filePath,
                analysis.prefixLineNumber,
                analysis.prefix,
                null,
                RenameStatus.NO_MAPPING,
                "@ConfigurationProperties annotation (prefix)"
            ));
        }

        // Handle field renames
        for (ConfigurationPropertiesAnalyzer.FieldInfo field : analysis.fields) {
            if (field.needsRename) {
                String newPrefix = analysis.newPrefix != null ? analysis.newPrefix : analysis.prefix;
                String newPropertyPath = newPrefix + "." + field.toPropertyName();

                // If there's a specific mapping for this field, use it
                if (mappings.containsKey(field.fullPropertyPath)) {
                    newPropertyPath = mappings.get(field.fullPropertyPath);
                }

                results.add(new RenameResult(
                    filePath,
                    field.lineNumber,
                    field.fullPropertyPath,
                    newPropertyPath,
                    RenameStatus.RENAMED,
                    "ConfigurationProperties field (" + field.fieldName + ")"
                ));
            } else if (mappings.containsKey(field.fullPropertyPath)) {
                results.add(new RenameResult(
                    filePath,
                    field.lineNumber,
                    field.fullPropertyPath,
                    field.fullPropertyPath,
                    RenameStatus.UNCHANGED,
                    "ConfigurationProperties field (" + field.fieldName + ")"
                ));
            }
        }

        // Apply all changes to the file
        configPropertiesAnalyzer.applyChanges(analysis);

        // Handle any other @Value or getProperty patterns in the same file
        List<PropertyScanner.PropertyMatch> nonPrefixMatches = new ArrayList<>();
        for (PropertyScanner.PropertyMatch match : propertyMatches) {
            if (!match.patternType.equals("@ConfigurationProperties annotation (prefix)")) {
                nonPrefixMatches.add(match);
            }
        }

        if (!nonPrefixMatches.isEmpty()) {
            results.addAll(handleRegularFile(filePath, nonPrefixMatches, mappings));
        }

        return results;
    }

    /**
     * Handle regular files (non-ConfigurationProperties files)
     */
    private List<RenameResult> handleRegularFile(String filePath,
                                                  List<PropertyScanner.PropertyMatch> propertyMatches,
                                                  Map<String, String> mappings) {
        List<RenameResult> results = new ArrayList<>();

        // Check if this is a .properties file
        if (filePath.endsWith(".properties") || filePath.endsWith(".yml") || filePath.endsWith(".yaml")) {
            return handlePropertiesFile(filePath, propertyMatches, mappings);
        }

        try {
            // Read the entire file
            File file = new File(filePath);
            String content = readFileContent(file);
            String modifiedContent = content;
            boolean fileModified = false;

            // Process each property match
            for (PropertyScanner.PropertyMatch match : propertyMatches) {
                String oldKey = match.propertyKey;
                String extractedKey = extractPropertyKey(oldKey);

                // Check if mapping exists
                if (mappings.containsKey(extractedKey)) {
                    String newKey = mappings.get(extractedKey);

                    if (extractedKey.equals(newKey)) {
                        // Mapping exists but no change needed
                        results.add(new RenameResult(
                            filePath,
                            match.lineNumber,
                            extractedKey,
                            newKey,
                            RenameStatus.UNCHANGED,
                            match.patternType
                        ));
                    } else {
                        // Perform the replacement based on pattern type
                        String replacementResult = replaceBasedOnPatternType(
                            modifiedContent,
                            oldKey,
                            extractedKey,
                            newKey,
                            match.patternType
                        );

                        if (!replacementResult.equals(modifiedContent)) {
                            modifiedContent = replacementResult;
                            fileModified = true;
                        }

                        results.add(new RenameResult(
                            filePath,
                            match.lineNumber,
                            extractedKey,
                            newKey,
                            RenameStatus.RENAMED,
                            match.patternType
                        ));
                    }
                } else {
                    // No mapping found
                    results.add(new RenameResult(
                        filePath,
                        match.lineNumber,
                        extractedKey,
                        null,
                        RenameStatus.NO_MAPPING,
                        match.patternType
                    ));
                }
            }

            // Write the modified content back to the file if changes were made
            if (fileModified) {
                writeFileContent(file, modifiedContent);
            }

        } catch (IOException e) {
            System.err.println("Error processing file: " + filePath + " - " + e.getMessage());
        }

        return results;
    }

    /**
     * Handle .properties files
     */
    private List<RenameResult> handlePropertiesFile(String filePath,
                                                     List<PropertyScanner.PropertyMatch> propertyMatches,
                                                     Map<String, String> mappings) {
        List<RenameResult> results = new ArrayList<>();

        try {
            File file = new File(filePath);
            List<String> lines = new ArrayList<>();

            // Read all lines
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }

            boolean fileModified = false;

            // Process each property match
            for (PropertyScanner.PropertyMatch match : propertyMatches) {
                String oldKey = match.propertyKey;

                if (mappings.containsKey(oldKey)) {
                    String newKey = mappings.get(oldKey);

                    if (oldKey.equals(newKey)) {
                        results.add(new RenameResult(
                            filePath,
                            match.lineNumber,
                            oldKey,
                            newKey,
                            RenameStatus.UNCHANGED,
                            match.patternType
                        ));
                    } else {
                        // Replace the property key in the line
                        int lineIndex = match.lineNumber - 1;
                        if (lineIndex >= 0 && lineIndex < lines.size()) {
                            String line = lines.get(lineIndex);

                            // Replace the key (handle both = and : separators)
                            String newLine = line.replaceFirst(
                                "^(\\s*)" + Pattern.quote(oldKey) + "(\\s*[=:])",
                                "$1" + newKey + "$2"
                            );

                            lines.set(lineIndex, newLine);
                            fileModified = true;

                            results.add(new RenameResult(
                                filePath,
                                match.lineNumber,
                                oldKey,
                                newKey,
                                RenameStatus.RENAMED,
                                match.patternType
                            ));
                        }
                    }
                } else {
                    results.add(new RenameResult(
                        filePath,
                        match.lineNumber,
                        oldKey,
                        null,
                        RenameStatus.NO_MAPPING,
                        match.patternType
                    ));
                }
            }

            // Write back if modified
            if (fileModified) {
                // Check if original file had trailing newline
                boolean hadTrailingNewline = false;
                try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                    if (raf.length() > 0) {
                        raf.seek(raf.length() - 1);
                        byte lastByte = raf.readByte();
                        hadTrailingNewline = (lastByte == '\n' || lastByte == '\r');
                    }
                }

                try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
                    for (int i = 0; i < lines.size(); i++) {
                        writer.write(lines.get(i));
                        if (i < lines.size() - 1) {
                            writer.write("\n");
                        }
                    }
                    // Preserve trailing newline if the original file had one
                    if (hadTrailingNewline) {
                        writer.write("\n");
                    }
                }
            }

        } catch (IOException e) {
            System.err.println("Error processing properties file: " + filePath + " - " + e.getMessage());
        }

        return results;
    }

    private String extractPropertyKey(String fullProperty) {
        // Trim first to remove any whitespace
        String trimmed = fullProperty.trim();

        // Extract property key from ${property.name} or ${property.name:defaultValue}
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            String inner = trimmed.substring(2, trimmed.length() - 1);
            // Remove default value if present
            int colonIndex = inner.indexOf(':');
            if (colonIndex > 0) {
                return inner.substring(0, colonIndex).trim();
            }
            return inner.trim();
        }
        if (trimmed.contains(":") && trimmed.split(":").length == 2){
            return trimmed.split(":")[0].trim();
        }
        return trimmed;
    }

    /**
     * Applies replacement strategy based on the pattern type.
     */
    private String replaceBasedOnPatternType(String content, String originalKey, String extractedKey,
                                            String newKey, String patternType) {
        switch (patternType) {
            case "@Value annotation":
                return replaceInValueAnnotation(content, originalKey, extractedKey, newKey);

            case "@ConfigurationProperty annotation":
                return replaceInConfigurationProperty(content, extractedKey, newKey);

            case "@ConfigurationProperties annotation (prefix)":
                return replaceInConfigurationProperties(content, extractedKey, newKey);

            case "Environment.getProperty()":
                return replaceInEnvironmentGetProperty(content, extractedKey, newKey);

            case "System.getProperty()":
                return replaceInSystemGetProperty(content, extractedKey, newKey);

            case "@PropertySource annotation":
                return replaceInPropertySource(content, extractedKey, newKey);

            case "Property placeholder ${...}":
                return replaceInPropertyPlaceholder(content, originalKey, extractedKey, newKey);

            default:
                return content;
        }
    }

    /**
     * Replace in @Value annotation: @Value("${property.key}") or @Value("${property.key:default}")
     * Handles multi-line annotations by replacing just the property key, preserving all formatting.
     */
    private String replaceInValueAnnotation(String content, String originalKey, String extractedKey, String newKey) {
        // Handle ${property.key} format
        String trimmedKey = originalKey.trim();
        if (trimmedKey.startsWith("${") && trimmedKey.endsWith("}")) {
            // Check if it has a default value
            if (trimmedKey.contains(":")) {
                // For properties with default values, replace the key part before the colon
                // Use \\s* to handle optional whitespace around the property key
                String regexPattern = "\\$\\{\\s*" + Pattern.quote(extractedKey) + "\\s*:";
                String replacement = "\\${" + newKey + ":";

                return content.replaceAll(regexPattern, replacement);
            } else {
                // Simple property without default value
                // Use word boundary or lookahead to match the property key followed by } (with possible text after })
                // This handles cases like ${property.key} and ${property.key}-suffix
                String regexPattern = "\\$\\{\\s*" + Pattern.quote(extractedKey) + "\\s*(?=\\})";
                String replacement = "\\${" + newKey;

                return content.replaceAll(regexPattern, replacement);
            }
        }
        return content;
    }

    /**
     * Replace in @ConfigurationProperty annotation: @ConfigurationProperty("property.key")
     */
    private String replaceInConfigurationProperty(String content, String extractedKey, String newKey) {
        // Match @ConfigurationProperty("oldKey") and replace with @ConfigurationProperty("newKey")
        String pattern = "@ConfigurationProperty\\s*\\(\\s*[\"']" + Pattern.quote(extractedKey) + "[\"']\\s*\\)";
        String replacement = "@ConfigurationProperty(\"" + newKey + "\")";
        return content.replaceAll(pattern, replacement);
    }

    /**
     * Replace in @ConfigurationProperties annotation: @ConfigurationProperties(prefix = "property.prefix")
     */
    private String replaceInConfigurationProperties(String content, String extractedKey, String newKey) {
        // Match @ConfigurationProperties(prefix = "oldKey") and replace with @ConfigurationProperties(prefix = "newKey")
        String pattern = "@ConfigurationProperties\\s*\\(\\s*prefix\\s*=\\s*[\"']" + Pattern.quote(extractedKey) + "[\"']\\s*\\)";
        String replacement = "@ConfigurationProperties(prefix = \"" + newKey + "\")";
        return content.replaceAll(pattern, replacement);
    }

    /**
     * Replace in Environment.getProperty(): env.getProperty("property.key")
     */
    private String replaceInEnvironmentGetProperty(String content, String extractedKey, String newKey) {
        // Match getProperty("oldKey") patterns (could be env.getProperty or environment.getProperty)
        String pattern = "getProperty\\s*\\(\\s*[\"']" + Pattern.quote(extractedKey) + "[\"']";
        String replacement = "getProperty(\"" + newKey + "\"";
        return content.replaceAll(pattern, replacement);
    }

    /**
     * Replace in System.getProperty(): System.getProperty("property.key")
     */
    private String replaceInSystemGetProperty(String content, String extractedKey, String newKey) {
        // Match System.getProperty("oldKey")
        String pattern = "System\\.getProperty\\s*\\(\\s*[\"']" + Pattern.quote(extractedKey) + "[\"']";
        String replacement = "System.getProperty(\"" + newKey + "\"";
        return content.replaceAll(pattern, replacement);
    }

    /**
     * Replace in @PropertySource annotation: @PropertySource("classpath:application.properties")
     */
    private String replaceInPropertySource(String content, String extractedKey, String newKey) {
        // Match @PropertySource("oldKey")
        String pattern = "@PropertySource\\s*\\(\\s*[\"']" + Pattern.quote(extractedKey) + "[\"']\\s*\\)";
        String replacement = "@PropertySource(\"" + newKey + "\")";
        return content.replaceAll(pattern, replacement);
    }

    /**
     * Replace in property placeholder: ${property.key} in strings or text
     */
    private String replaceInPropertyPlaceholder(String content, String originalKey, String extractedKey, String newKey) {
        String trimmedKey = originalKey.trim();
        if (trimmedKey.startsWith("${") && trimmedKey.endsWith("}")) {
            if (trimmedKey.contains(":")) {
                String regexPattern = "\\$\\{\\s*" + Pattern.quote(extractedKey) + "\\s*:";
                String replacement = "\\${" + newKey + ":";

                return content.replaceAll(regexPattern, replacement);
            } else {
                String regexPattern = "\\$\\{\\s*" + Pattern.quote(extractedKey) + "\\s*(?=\\})";
                String replacement = "\\${" + newKey;

                return content.replaceAll(regexPattern, replacement);
            }
        } else if (trimmedKey.contains(":")) {
            String regexPattern = "\\b" + Pattern.quote(extractedKey) + "\\s*:";
            String replacement = newKey + ":";

            return content.replaceAll(regexPattern, replacement);
        } else {
            String regexPattern = "\\b" + Pattern.quote(extractedKey) + "\\b";

            return content.replaceAll(regexPattern, newKey);
        }
    }

    private String readFileContent(File file) throws IOException {
        // Read entire file preserving exact format including trailing newlines
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (!firstLine) {
                    content.append("\n");
                }
                content.append(line);
                firstLine = false;
            }
        }

        // Check if original file ended with newline(s) and preserve them
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            if (raf.length() > 0) {
                raf.seek(raf.length() - 1);
                byte lastByte = raf.readByte();
                if (lastByte == '\n') {
                    content.append("\n");
                    // Check for multiple trailing newlines
                    if (raf.length() > 1) {
                        raf.seek(raf.length() - 2);
                        byte secondLastByte = raf.readByte();
                        if (secondLastByte == '\n') {
                            content.append("\n");
                        }
                    }
                }
            }
        }

        return content.toString();
    }

    private void writeFileContent(File file, String content) throws IOException {

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(content);
        }
    }

    public List<RenameResult> updateGetterSetterCalls(String filePath, Map<String, String> fieldRenames) {
        List<RenameResult> results = new ArrayList<>();

        if (fieldRenames.isEmpty()) {
            return results;
        }

        try {
            File file = new File(filePath);
            String content = readFileContent(file);
            String modifiedContent = content;
            boolean fileModified = false;

            // For each field rename, update getter/setter calls
            for (Map.Entry<String, String> entry : fieldRenames.entrySet()) {
                String key = entry.getKey(); // e.g., "IdentityAdapterDefinition.appId"
                String newFieldName = entry.getValue(); // e.g., "appIds"

                // Extract class name and old field name
                String[] parts = key.split("\\.");
                if (parts.length != 2) continue;

                String className = parts[0];
                String oldFieldName = parts[1];

                // Generate getter/setter names
                String oldGetter = "get" + capitalize(oldFieldName);
                String newGetter = "get" + capitalize(newFieldName);
                String oldSetter = "set" + capitalize(oldFieldName);
                String newSetter = "set" + capitalize(newFieldName);

                // Update getter calls: .getOldField( -> .getNewField(
                String getterPattern = "\\." + Pattern.quote(oldGetter) + "(\\s*\\()";
                String getterReplacement = "." + newGetter + "$1";
                String updated = modifiedContent.replaceAll(getterPattern, getterReplacement);

                if (!updated.equals(modifiedContent)) {
                    modifiedContent = updated;
                    fileModified = true;
                    results.add(new RenameResult(
                        filePath,
                        -1,
                        className + "." + oldGetter + "()",
                        className + "." + newGetter + "()",
                        RenameStatus.RENAMED,
                        "Getter call update"
                    ));
                }

                // Update setter calls: .setOldField( -> .setNewField(
                String setterPattern = "\\." + Pattern.quote(oldSetter) + "(\\s*\\()";
                String setterReplacement = "." + newSetter + "$1";
                updated = modifiedContent.replaceAll(setterPattern, setterReplacement);

                if (!updated.equals(modifiedContent)) {
                    modifiedContent = updated;
                    fileModified = true;
                    results.add(new RenameResult(
                        filePath,
                        -1,
                        className + "." + oldSetter + "()",
                        className + "." + newSetter + "()",
                        RenameStatus.RENAMED,
                        "Setter call update"
                    ));
                }

                // Handle boolean getters: .isOldField( -> .isNewField(
                String oldIsGetter = "is" + capitalize(oldFieldName);
                String newIsGetter = "is" + capitalize(newFieldName);
                String isGetterPattern = "\\." + Pattern.quote(oldIsGetter) + "(\\s*\\()";
                String isGetterReplacement = "." + newIsGetter + "$1";
                updated = modifiedContent.replaceAll(isGetterPattern, isGetterReplacement);

                if (!updated.equals(modifiedContent)) {
                    modifiedContent = updated;
                    fileModified = true;
                    results.add(new RenameResult(
                        filePath,
                        -1,
                        className + "." + oldIsGetter + "()",
                        className + "." + newIsGetter + "()",
                        RenameStatus.RENAMED,
                        "Boolean getter call update"
                    ));
                }
            }

            // Write changes if anything was modified
            if (fileModified) {
                writeFileContent(file, modifiedContent);
            }

        } catch (IOException e) {
            System.err.println("Error updating getter/setter calls in file: " + filePath + " - " + e.getMessage());
        }

        return results;
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    public enum RenameStatus {
        RENAMED,        // Property was renamed
        UNCHANGED,      // Mapping exists but old and new are the same
        NO_MAPPING      // No mapping found for this property
    }

    public static class RenameResult {
        public final String filePath;
        public final int lineNumber;
        public final String oldKey;
        public final String newKey;
        public final RenameStatus status;
        public final String patternType;

        public RenameResult(String filePath, int lineNumber, String oldKey, String newKey,
                           RenameStatus status, String patternType) {
            this.filePath = filePath;
            this.lineNumber = lineNumber;
            this.oldKey = oldKey;
            this.newKey = newKey;
            this.status = status;
            this.patternType = patternType;
        }
    }
}

