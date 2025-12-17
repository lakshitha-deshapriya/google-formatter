package org.example;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class PropertyRenamer {

    private final ConfigurationPropertiesAnalyzer configPropertiesAnalyzer;

    public PropertyRenamer() {
        this.configPropertiesAnalyzer = new ConfigurationPropertiesAnalyzer();
    }

    public List<RenameResult> renamePropertiesInFile(
            String filePath,
            List<PropertyScanner.PropertyMatch> propertyMatches,
            Map<String, String> mappings) {
        List<RenameResult> results = new ArrayList<>();

        if (propertyMatches.isEmpty() && isNestedConfigClass(mappings)) {
            return handleNestedConfigClass(filePath, mappings);
        }

        if (propertyMatches.isEmpty()) {
            return results;
        }

        boolean hasConfigurationProperties =
                propertyMatches.stream()
                        .anyMatch(
                                m ->
                                        m.patternType
                                                == PropertyScanner.PatternType
                                                        .CONFIGURATION_PROPERTIES_PREFIX);

        if (hasConfigurationProperties) {
            return handleConfigurationPropertiesFile(filePath, propertyMatches, mappings);
        }
        return handleRegularFile(filePath, propertyMatches, mappings);
    }

    private boolean isNestedConfigClass(Map<String, String> mappings) {
        for (String key : mappings.keySet()) {
            if (key.matches(".*\\[\\d+\\]\\.\\w+.*")) {
                return true;
            }
        }
        return false;
    }

    private List<RenameResult> handleNestedConfigClass(
            String filePath, Map<String, String> mappings) {
        List<RenameResult> results = new ArrayList<>();

        ConfigurationPropertiesAnalyzer.AnalysisResult analysis =
                configPropertiesAnalyzer.analyzeNestedConfigClass(filePath, mappings);

        if (analysis == null || analysis.fields.isEmpty()) {
            return results;
        }

        for (ConfigurationPropertiesAnalyzer.FieldInfo field : analysis.fields) {
            if (field.needsRename) {
                results.add(
                        new RenameResult(
                                filePath,
                                field.lineNumber,
                                field.fieldName,
                                field.newFieldName,
                                RenameStatus.RENAMED,
                                PropertyScanner.PatternType.NESTED_CONFIGURATION_FIELD));
            }
        }

        // Apply changes to the file
        configPropertiesAnalyzer.applyChanges(analysis);

        return results;
    }

    private List<RenameResult> handleConfigurationPropertiesFile(
            String filePath,
            List<PropertyScanner.PropertyMatch> propertyMatches,
            Map<String, String> mappings) {
        List<RenameResult> results = new ArrayList<>();

        ConfigurationPropertiesAnalyzer.AnalysisResult analysis =
                configPropertiesAnalyzer.analyzeFile(filePath, mappings);

        if (analysis == null) {
            return handleRegularFile(filePath, propertyMatches, mappings);
        }

        if (analysis.prefixChanged) {
            results.add(
                    new RenameResult(
                            filePath,
                            analysis.prefixLineNumber,
                            analysis.prefix,
                            analysis.newPrefix,
                            RenameStatus.RENAMED,
                            PropertyScanner.PatternType.CONFIGURATION_PROPERTIES_PREFIX));
        } else if (mappings.containsKey(analysis.prefix)) {
            results.add(
                    new RenameResult(
                            filePath,
                            analysis.prefixLineNumber,
                            analysis.prefix,
                            analysis.prefix,
                            RenameStatus.UNCHANGED,
                            PropertyScanner.PatternType.CONFIGURATION_PROPERTIES_PREFIX));
        } else {
            results.add(
                    new RenameResult(
                            filePath,
                            analysis.prefixLineNumber,
                            analysis.prefix,
                            null,
                            RenameStatus.NO_MAPPING,
                            PropertyScanner.PatternType.CONFIGURATION_PROPERTIES_PREFIX));
        }

        for (ConfigurationPropertiesAnalyzer.FieldInfo field : analysis.fields) {
            if (field.needsRename) {
                String newPrefix =
                        analysis.newPrefix != null ? analysis.newPrefix : analysis.prefix;
                String newPropertyPath = newPrefix + "." + field.toPropertyName();

                // If there's a specific mapping for this field, use it
                if (mappings.containsKey(field.fullPropertyPath)) {
                    newPropertyPath = mappings.get(field.fullPropertyPath);
                }

                results.add(
                        new RenameResult(
                                filePath,
                                field.lineNumber,
                                field.fullPropertyPath,
                                newPropertyPath,
                                RenameStatus.RENAMED,
                                PropertyScanner.PatternType.CONFIGURATION_PROPERTIES_FIELD,
                                field.fieldName));
            } else if (mappings.containsKey(field.fullPropertyPath)) {
                results.add(
                        new RenameResult(
                                filePath,
                                field.lineNumber,
                                field.fullPropertyPath,
                                field.fullPropertyPath,
                                RenameStatus.UNCHANGED,
                                PropertyScanner.PatternType.CONFIGURATION_PROPERTIES_FIELD,
                                field.fieldName));
            }
        }

        configPropertiesAnalyzer.applyChanges(analysis);

        List<PropertyScanner.PropertyMatch> nonPrefixMatches = new ArrayList<>();
        for (PropertyScanner.PropertyMatch match : propertyMatches) {
            if (match.patternType != PropertyScanner.PatternType.CONFIGURATION_PROPERTIES_PREFIX) {
                nonPrefixMatches.add(match);
            }
        }

        if (!nonPrefixMatches.isEmpty()) {
            results.addAll(handleRegularFile(filePath, nonPrefixMatches, mappings));
        }

        return results;
    }

    private List<RenameResult> handleYamlFile(
            String filePath,
            List<PropertyScanner.PropertyMatch> propertyMatches,
            Map<String, String> mappings) {
        List<RenameResult> results = new ArrayList<>();

        try {
            YamlPropertyHandler yamlHandler = new YamlPropertyHandler();

            boolean hasChanges = false;
            for (PropertyScanner.PropertyMatch match : propertyMatches) {
                String oldKey = match.propertyKey;

                if (mappings.containsKey(oldKey)) {
                    String newKey = mappings.get(oldKey);

                    if (oldKey.equals(newKey)) {
                        results.add(
                                new RenameResult(
                                        filePath,
                                        match.lineNumber,
                                        oldKey,
                                        newKey,
                                        RenameStatus.UNCHANGED,
                                        match.patternType));
                    } else {
                        hasChanges = true;
                        results.add(
                                new RenameResult(
                                        filePath,
                                        match.lineNumber,
                                        oldKey,
                                        newKey,
                                        RenameStatus.RENAMED,
                                        match.patternType));
                    }
                } else {
                    results.add(
                            new RenameResult(
                                    filePath,
                                    match.lineNumber,
                                    oldKey,
                                    null,
                                    RenameStatus.NO_MAPPING,
                                    match.patternType));
                }
            }

            if (hasChanges) {
                yamlHandler.renamePropertiesInYaml(filePath, mappings);
            }

        } catch (Exception e) {
            Util.logError("Error processing YAML file: " + filePath + " - " + e.getMessage());
        }

        return results;
    }

    private List<RenameResult> handleRegularFile(
            String filePath,
            List<PropertyScanner.PropertyMatch> propertyMatches,
            Map<String, String> mappings) {
        List<RenameResult> results = new ArrayList<>();

        if (filePath.endsWith(".properties")
                || filePath.endsWith(".yml")
                || filePath.endsWith(".yaml")) {
            return handlePropertiesFile(filePath, propertyMatches, mappings);
        }

        try {
            // Read the entire file
            File file = new File(filePath);
            String modifiedContent = readFileContent(file);
            boolean fileModified = false;

            // Process each property match
            for (PropertyScanner.PropertyMatch match : propertyMatches) {
                String oldKey = match.propertyKey;
                String extractedKey = extractPropertyKey(oldKey);

                if (mappings.containsKey(extractedKey)) {
                    String newKey = mappings.get(extractedKey);

                    if (extractedKey.equals(newKey)) {
                        results.add(
                                new RenameResult(
                                        filePath,
                                        match.lineNumber,
                                        extractedKey,
                                        newKey,
                                        RenameStatus.UNCHANGED,
                                        match.patternType));
                    } else {
                        String replacementResult =
                                replaceBasedOnPatternType(
                                        modifiedContent,
                                        oldKey,
                                        extractedKey,
                                        newKey,
                                        match.patternType);

                        if (!replacementResult.equals(modifiedContent)) {
                            modifiedContent = replacementResult;
                            fileModified = true;
                        }

                        results.add(
                                new RenameResult(
                                        filePath,
                                        match.lineNumber,
                                        extractedKey,
                                        newKey,
                                        RenameStatus.RENAMED,
                                        match.patternType));
                    }
                } else {
                    results.add(
                            new RenameResult(
                                    filePath,
                                    match.lineNumber,
                                    extractedKey,
                                    null,
                                    RenameStatus.NO_MAPPING,
                                    match.patternType));
                }
            }

            if (fileModified) {
                writeFileContent(file, modifiedContent);
            }

        } catch (IOException e) {
            Util.logError("Error processing file: " + filePath + " - " + e.getMessage());
        }

        return results;
    }

    private List<RenameResult> handlePropertiesFile(
            String filePath,
            List<PropertyScanner.PropertyMatch> propertyMatches,
            Map<String, String> mappings) {
        List<RenameResult> results = new ArrayList<>();

        if (filePath.endsWith(".yml") || filePath.endsWith(".yaml")) {
            return handleYamlFile(filePath, propertyMatches, mappings);
        }

        String fileName = new File(filePath).getName().toLowerCase();
        if (fileName.matches("message(_[a-z]{2})?\\.properties")) {
            return results;
        }

        try {
            File file = new File(filePath);
            List<String> lines = new ArrayList<>();

            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }

            boolean fileModified = false;
            for (PropertyScanner.PropertyMatch match : propertyMatches) {
                String oldKey = match.propertyKey;

                if (mappings.containsKey(oldKey)) {
                    String newKey = mappings.get(oldKey);

                    if (oldKey.equals(newKey)) {
                        results.add(
                                new RenameResult(
                                        filePath,
                                        match.lineNumber,
                                        oldKey,
                                        newKey,
                                        RenameStatus.UNCHANGED,
                                        match.patternType));
                    } else {
                        int lineIndex = match.lineNumber - 1;
                        if (lineIndex >= 0 && lineIndex < lines.size()) {
                            String line = lines.get(lineIndex);

                            String newLine =
                                    line.replaceFirst(
                                            "^(\\s*)" + Pattern.quote(oldKey) + "(\\s*[=:])",
                                            "$1" + newKey + "$2");

                            lines.set(lineIndex, newLine);
                            fileModified = true;

                            results.add(
                                    new RenameResult(
                                            filePath,
                                            match.lineNumber,
                                            oldKey,
                                            newKey,
                                            RenameStatus.RENAMED,
                                            match.patternType));
                        }
                    }
                } else {
                    results.add(
                            new RenameResult(
                                    filePath,
                                    match.lineNumber,
                                    oldKey,
                                    null,
                                    RenameStatus.NO_MAPPING,
                                    match.patternType));
                }
            }

            if (fileModified) {
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
                    if (hadTrailingNewline) {
                        writer.write("\n");
                    }
                }
            }

        } catch (IOException e) {
            Util.logError("Error processing properties file: " + filePath + " - " + e.getMessage());
        }

        return results;
    }

    private String extractPropertyKey(String fullProperty) {
        String trimmed = fullProperty.trim();

        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            String inner = trimmed.substring(2, trimmed.length() - 1);
            int colonIndex = inner.indexOf(':');
            if (colonIndex > 0) {
                return inner.substring(0, colonIndex).trim();
            }
            return inner.trim();
        }
        if (trimmed.contains(":") && trimmed.split(":").length == 2) {
            return trimmed.split(":")[0].trim();
        }
        return trimmed;
    }

    private String replaceBasedOnPatternType(
            String content,
            String originalKey,
            String extractedKey,
            String newKey,
            PropertyScanner.PatternType patternType) {
        switch (patternType) {
            case VALUE_ANNOTATION:
                return replaceInValueAnnotation(content, originalKey, extractedKey, newKey);

            case CONFIGURATION_PROPERTY_ANNOTATION:
                return replaceInConfigurationProperty(content, extractedKey, newKey);

            case CONFIGURATION_PROPERTIES_PREFIX:
                return replaceInConfigurationProperties(content, extractedKey, newKey);

            case ENVIRONMENT_GET_PROPERTY:
                return replaceInEnvironmentGetProperty(content, extractedKey, newKey);

            case SYSTEM_GET_PROPERTY:
                return replaceInSystemGetProperty(content, extractedKey, newKey);

            case PROPERTY_SOURCE_ANNOTATION:
                return replaceInPropertySource(content, extractedKey, newKey);

            case PROPERTY_PLACEHOLDER:
                return replaceInPropertyPlaceholder(content, originalKey, extractedKey, newKey);

            default:
                return content;
        }
    }

    private String replaceInValueAnnotation(
            String content, String originalKey, String extractedKey, String newKey) {
        String trimmedKey = originalKey.trim();
        if (trimmedKey.startsWith("${") && trimmedKey.endsWith("}")) {
            // Use a regex that matches the property key followed by either : (default value) or }
            // This handles both ${property} and ${property:default} in the actual content
            String regexPattern = "\\$\\{\\s*" + Pattern.quote(extractedKey) + "\\s*(?=[:}])";
            String replacement = "\\${" + newKey;
            return content.replaceAll(regexPattern, replacement);
        }
        return content;
    }

    private String replaceInConfigurationProperty(
            String content, String extractedKey, String newKey) {
        String pattern =
                "@ConfigurationProperty\\s*\\(\\s*[\"']"
                        + Pattern.quote(extractedKey)
                        + "[\"']\\s*\\)";
        String replacement = "@ConfigurationProperty(\"" + newKey + "\")";
        return content.replaceAll(pattern, replacement);
    }

    private String replaceInConfigurationProperties(
            String content, String extractedKey, String newKey) {
        String pattern =
                "@ConfigurationProperties\\s*\\(\\s*prefix\\s*=\\s*[\"']"
                        + Pattern.quote(extractedKey)
                        + "[\"']\\s*\\)";
        String replacement = "@ConfigurationProperties(prefix = \"" + newKey + "\")";
        return content.replaceAll(pattern, replacement);
    }

    private String replaceInEnvironmentGetProperty(
            String content, String extractedKey, String newKey) {
        String pattern = "getProperty\\s*\\(\\s*[\"']" + Pattern.quote(extractedKey) + "[\"']";
        String replacement = "getProperty(\"" + newKey + "\"";
        return content.replaceAll(pattern, replacement);
    }

    private String replaceInSystemGetProperty(String content, String extractedKey, String newKey) {
        String pattern =
                "System\\.getProperty\\s*\\(\\s*[\"']" + Pattern.quote(extractedKey) + "[\"']";
        String replacement = "System.getProperty(\"" + newKey + "\"";
        return content.replaceAll(pattern, replacement);
    }

    private String replaceInPropertySource(String content, String extractedKey, String newKey) {
        String pattern =
                "@PropertySource\\s*\\(\\s*[\"']" + Pattern.quote(extractedKey) + "[\"']\\s*\\)";
        String replacement = "@PropertySource(\"" + newKey + "\")";
        return content.replaceAll(pattern, replacement);
    }

    private String replaceInPropertyPlaceholder(
            String content, String originalKey, String extractedKey, String newKey) {
        String trimmedKey = originalKey.trim();
        if (trimmedKey.startsWith("${") && trimmedKey.endsWith("}")) {
            // Use a regex that matches the property key followed by either : (default value) or }
            // This handles both ${property} and ${property:default} in the actual content
            String regexPattern = "\\$\\{\\s*" + Pattern.quote(extractedKey) + "\\s*(?=[:}])";
            String replacement = "\\${" + newKey;
            return content.replaceAll(regexPattern, replacement);
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

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            if (raf.length() > 0) {
                raf.seek(raf.length() - 1);
                byte lastByte = raf.readByte();
                if (lastByte == '\n') {
                    content.append("\n");
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

    public List<RenameResult> updateGetterSetterCalls(
            String filePath,
            Map<String, String> fieldRenames,
            Map<String, PropertyRenameRunner.ClassInfo> classInfoMap) {
        List<RenameResult> results = new ArrayList<>();

        if (fieldRenames.isEmpty()) {
            return results;
        }

        try {
            boolean wasModified =
                    configPropertiesAnalyzer.updateAccessorCallsInFile(
                            filePath, fieldRenames, classInfoMap);

            if (wasModified) {
                for (Map.Entry<String, String> entry : fieldRenames.entrySet()) {
                    String key = entry.getKey();
                    String newFieldName = entry.getValue();

                    String[] parts = key.split("\\.");
                    if (parts.length != 2) continue;

                    String className = parts[0];
                    String oldFieldName = parts[1];

                    results.add(
                            new RenameResult(
                                    filePath,
                                    -1,
                                    className + "." + oldFieldName,
                                    className + "." + newFieldName,
                                    RenameStatus.RENAMED,
                                    PropertyScanner.PatternType.ACCESSOR_CALLS));
                }
            }
        } catch (Exception e) {
            Util.logError(
                    "Error updating getter/setter calls in file: "
                            + filePath
                            + " - "
                            + e.getMessage());
        }

        return results;
    }

    public enum RenameStatus {
        RENAMED,
        UNCHANGED,
        NO_MAPPING
    }

    public static class RenameResult {
        public final String filePath;
        public final int lineNumber;
        public final String oldKey;
        public final String newKey;
        public final RenameStatus status;
        public final PropertyScanner.PatternType patternType;
        public final String extraInfo;

        public RenameResult(
                String filePath,
                int lineNumber,
                String oldKey,
                String newKey,
                RenameStatus status,
                PropertyScanner.PatternType patternType) {
            this(filePath, lineNumber, oldKey, newKey, status, patternType, null);
        }

        public RenameResult(
                String filePath,
                int lineNumber,
                String oldKey,
                String newKey,
                RenameStatus status,
                PropertyScanner.PatternType patternType,
                String extraInfo) {
            this.filePath = filePath;
            this.lineNumber = lineNumber;
            this.oldKey = oldKey;
            this.newKey = newKey;
            this.status = status;
            this.patternType = patternType;
            this.extraInfo = extraInfo;
        }

        public String getPatternDescription() {
            return extraInfo != null
                    ? patternType.getDisplayName(extraInfo)
                    : patternType.getDisplayName();
        }
    }
}
