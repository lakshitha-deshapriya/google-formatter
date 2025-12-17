package org.example;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PropertyScanner {

    private static final List<Pattern> PROPERTY_PATTERNS = new ArrayList<>();
    private boolean silentMode = false;

    private static final Pattern VALUE_PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    static {
        // @Value annotation patterns - supports multi-line with flexible whitespace
        PROPERTY_PATTERNS.add(
                Pattern.compile("@Value\\s*\\(\\s*\"((?:[^\"\\\\]|\\\\[\\s\\S])*)\"\\s*\\)"));

        // @ConfigurationProperty annotation patterns
        PROPERTY_PATTERNS.add(
                Pattern.compile(
                        "@ConfigurationProperty\\s*\\(\\s*\"((?:[^\"\\\\]|\\\\[\\s\\S])*)\"\\s*\\)"));
        PROPERTY_PATTERNS.add(
                Pattern.compile(
                        "@ConfigurationProperties\\s*\\(\\s*prefix\\s*=\\s*\"((?:[^\"\\\\]|\\\\[\\s\\S])*)\"\\s*\\)"));

        // Environment.getProperty() patterns
        PROPERTY_PATTERNS.add(
                Pattern.compile("getProperty\\s*\\(\\s*\"((?:[^\"\\\\]|\\\\[\\s\\S])*)\""));

        // System.getProperty() patterns
        PROPERTY_PATTERNS.add(
                Pattern.compile(
                        "System\\.getProperty\\s*\\(\\s*\"((?:[^\"\\\\]|\\\\[\\s\\S])*)\""));

        // @PropertySource annotation
        PROPERTY_PATTERNS.add(
                Pattern.compile(
                        "@PropertySource\\s*\\(\\s*\"((?:[^\"\\\\]|\\\\[\\s\\S])*)\"\\s*\\)"));

        // application.properties/yml references in strings (common pattern)
        PROPERTY_PATTERNS.add(Pattern.compile("\\$\\{([^}]+)}"));
    }

    public void setSilentMode(boolean silentMode) {
        this.silentMode = silentMode;
    }

    public List<PropertyMatch> scanFile(String filePath) {
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            return new ArrayList<>();
        }

        // Check if it's a YAML file
        if (filePath.endsWith(".yml") || filePath.endsWith(".yaml")) {
            YamlPropertyHandler yamlHandler = new YamlPropertyHandler();
            List<PropertyMatch> matches = yamlHandler.scanYamlFileWithLineNumbers(filePath);

            // Print results for this file (only if not in silent mode)
            if (!silentMode && !matches.isEmpty()) {
                Util.log(filePath);
                for (PropertyMatch match : matches) {
                    Util.log("Line: " + match.lineNumber + ", Property: " + match.propertyKey);
                }
                Util.log("");
            }

            return matches;
        }

        // Check if it's a .properties file
        if (filePath.endsWith(".properties")) {
            return scanPropertiesFile(filePath);
        }

        List<PropertyMatch> matches = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            // Read entire file content
            StringBuilder contentBuilder = new StringBuilder();
            List<String> lines = new ArrayList<>();
            String line;

            while ((line = reader.readLine()) != null) {
                lines.add(line);
                contentBuilder.append(line).append("\n");
            }

            String fileContent = contentBuilder.toString();

            // Process multi-line patterns (annotations that might span multiple lines)
            for (Pattern pattern : PROPERTY_PATTERNS) {
                PatternType patternType = getPatternType(pattern);

                // For annotations, use DOTALL flag to match across newlines
                Pattern multiLinePattern = Pattern.compile(pattern.pattern(), Pattern.DOTALL);
                Matcher matcher = multiLinePattern.matcher(fileContent);

                while (matcher.find()) {
                    String propertyKey = matcher.group(1).trim(); // Trim whitespace
                    int matchStart = matcher.start();

                    // Calculate line number by counting newlines before the match
                    int lineNumber = calculateLineNumber(fileContent, matchStart);

                    // Get the context (the actual line where match starts, trimmed)
                    String context = lines.get(lineNumber - 1).trim();

                    // For @Value annotations, check if it contains complex strings with embedded placeholders
                    if (patternType == PatternType.VALUE_ANNOTATION) {
                        List<PropertyMatch> extractedMatches = extractPropertyPlaceholdersFromValue(
                                filePath, lineNumber, propertyKey, context);
                        for (PropertyMatch extracted : extractedMatches) {
                            if (!isDuplicate(matches, extracted)) {
                                matches.add(extracted);
                            }
                        }
                    } else {
                        PropertyMatch newMatch =
                                new PropertyMatch(
                                        filePath, lineNumber, propertyKey, context, patternType);

                        // Check for duplicates before adding
                        if (!isDuplicate(matches, newMatch)) {
                            matches.add(newMatch);
                        }
                    }
                }
            }

            // Remove placeholder patterns if annotation patterns exist for the same property
            matches = filterDuplicates(matches);

        } catch (IOException e) {
            Util.logError("Error reading file: " + filePath + " - " + e.getMessage());
        }

        // Print results for this file (only if not in silent mode)
        if (!silentMode && !matches.isEmpty()) {
            Util.log(filePath);

            for (PropertyMatch match : matches) {
                Util.log(
                        "Line: "
                                + match.lineNumber
                                + ", Usage: "
                                + match.patternType.getDisplayName()
                                + ", Property: "
                                + match.propertyKey);
            }
            Util.log("");
        }

        return matches;
    }

    private PatternType getPatternType(Pattern pattern) {
        String patternStr = pattern.pattern();

        if (patternStr.contains("@Value")) {
            return PatternType.VALUE_ANNOTATION;
        } else if (patternStr.contains("@ConfigurationProperty\\s*\\(")) {
            return PatternType.CONFIGURATION_PROPERTY_ANNOTATION;
        } else if (patternStr.contains("@ConfigurationProperties")) {
            return PatternType.CONFIGURATION_PROPERTIES_PREFIX;
        } else if (patternStr.contains("getProperty")) {
            if (patternStr.contains("System")) {
                return PatternType.SYSTEM_GET_PROPERTY;
            }
            return PatternType.ENVIRONMENT_GET_PROPERTY;
        } else if (patternStr.contains("@PropertySource")) {
            return PatternType.PROPERTY_SOURCE_ANNOTATION;
        } else if (patternStr.contains("\\$\\{")) {
            return PatternType.PROPERTY_PLACEHOLDER;
        }

        return PatternType.UNKNOWN;
    }

    private List<PropertyMatch> extractPropertyPlaceholdersFromValue(
            String filePath, int lineNumber, String valueContent, String context) {
        List<PropertyMatch> matches = new ArrayList<>();

        String trimmed = valueContent.trim();
        if (trimmed.startsWith("${") && trimmed.endsWith("}") && !trimmed.substring(2).contains("${")) {
            String inner = trimmed.substring(2, trimmed.length() - 1);
            int colonIndex = inner.indexOf(':');
            String propertyKey = colonIndex > 0 ? inner.substring(0, colonIndex).trim() : inner.trim();
            matches.add(new PropertyMatch(filePath, lineNumber, "${" + propertyKey + "}", context, PatternType.VALUE_ANNOTATION));
        } else {
            Matcher placeholderMatcher = VALUE_PLACEHOLDER_PATTERN.matcher(valueContent);
            while (placeholderMatcher.find()) {
                String placeholder = placeholderMatcher.group(1).trim();
                // Remove default value if present
                int colonIndex = placeholder.indexOf(':');
                String propertyKey = colonIndex > 0 ? placeholder.substring(0, colonIndex).trim() : placeholder;
                PropertyMatch match = new PropertyMatch(
                        filePath, lineNumber, "${" + propertyKey + "}", context, PatternType.VALUE_ANNOTATION);
                if (!isDuplicate(matches, match)) {
                    matches.add(match);
                }
            }
        }

        return matches;
    }

    private int calculateLineNumber(String content, int position) {
        int lineNumber = 1;
        for (int i = 0; i < position && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lineNumber++;
            }
        }
        return lineNumber;
    }

    private boolean isDuplicate(List<PropertyMatch> matches, PropertyMatch newMatch) {
        for (PropertyMatch existing : matches) {
            if (existing.lineNumber == newMatch.lineNumber
                    && existing.propertyKey.equals(newMatch.propertyKey)
                    && existing.patternType == newMatch.patternType) {
                return true;
            }
        }
        return false;
    }

    private List<PropertyMatch> filterDuplicates(List<PropertyMatch> matches) {
        List<PropertyMatch> filtered = new ArrayList<>();

        for (PropertyMatch match : matches) {
            boolean shouldAdd = true;

            if (match.patternType == PatternType.PROPERTY_PLACEHOLDER) {
                for (PropertyMatch other : matches) {
                    if (other.patternType != PatternType.PROPERTY_PLACEHOLDER) {
                        boolean sameArea = Math.abs(other.lineNumber - match.lineNumber) <= 5;

                        // Extract the base property key (without default value) from both
                        String matchExtracted = extractPropertyKey(match.propertyKey);
                        String otherExtracted = extractPropertyKey(other.propertyKey);

                        boolean propertyContained =
                                other.propertyKey.contains(match.propertyKey)
                                        || match.propertyKey.equals(otherExtracted)
                                        || matchExtracted.equals(otherExtracted);

                        if (sameArea && propertyContained) {
                            shouldAdd = false;
                            break;
                        }
                    }
                }
            }

            if (shouldAdd) {
                filtered.add(match);
            }
        }

        return filtered;
    }

    private List<PropertyMatch> scanPropertiesFile(String filePath) {
        List<PropertyMatch> matches = new ArrayList<>();
        File file = new File(filePath);

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            int lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmed = line.trim();

                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                    continue;
                }

                int separatorIndex = -1;
                if (trimmed.contains("=")) {
                    separatorIndex = trimmed.indexOf("=");
                } else if (trimmed.contains(":")) {
                    separatorIndex = trimmed.indexOf(":");
                }

                if (separatorIndex > 0) {
                    String propertyKey = trimmed.substring(0, separatorIndex).trim();

                    PropertyMatch match =
                            new PropertyMatch(
                                    filePath,
                                    lineNumber,
                                    propertyKey,
                                    trimmed,
                                    PatternType.PROPERTY_FILE_ENTRY);

                    matches.add(match);
                }
            }

        } catch (IOException e) {
            Util.logError("Error reading properties file: " + filePath + " - " + e.getMessage());
        }

        // Print results for this file (only if not in silent mode)
        if (!silentMode && !matches.isEmpty()) {
            Util.log(filePath);
            for (PropertyMatch match : matches) {
                Util.log("Line: " + match.lineNumber + ", Property: " + match.propertyKey);
            }
            Util.log("");
        }

        return matches;
    }

    private String extractPropertyKey(String fullProperty) {
        if (fullProperty.startsWith("${") && fullProperty.endsWith("}")) {
            String inner = fullProperty.substring(2, fullProperty.length() - 1);
            // Remove default value if present
            int colonIndex = inner.indexOf(':');
            if (colonIndex > 0) {
                return inner.substring(0, colonIndex);
            }
            return inner;
        }
        int colonIndex = fullProperty.indexOf(':');
        if (colonIndex > 0) {
            return fullProperty.substring(0, colonIndex);
        }
        return fullProperty;
    }

    public static class PropertyMatch {
        public final String filePath;
        public final int lineNumber;
        public final String propertyKey;
        public final String context;
        public final PatternType patternType;

        public PropertyMatch(
                String filePath,
                int lineNumber,
                String propertyKey,
                String context,
                PatternType patternType) {
            this.filePath = filePath;
            this.lineNumber = lineNumber;
            this.propertyKey = propertyKey;
            this.context = context;
            this.patternType = patternType;
        }
    }

    public enum PatternType {
        VALUE_ANNOTATION("@Value annotation"),
        CONFIGURATION_PROPERTY_ANNOTATION("@ConfigurationProperty annotation"),
        CONFIGURATION_PROPERTIES_PREFIX("@ConfigurationProperties annotation (prefix)"),
        ENVIRONMENT_GET_PROPERTY("Environment.getProperty()"),
        SYSTEM_GET_PROPERTY("System.getProperty()"),
        PROPERTY_SOURCE_ANNOTATION("@PropertySource annotation"),
        PROPERTY_PLACEHOLDER("Property placeholder ${...}"),
        PROPERTY_FILE_ENTRY("Property file entry"),
        YAML_PROPERTY("YAML property"),
        NESTED_CONFIGURATION_FIELD("Nested configuration field (used in indexed property)"),
        CONFIGURATION_PROPERTIES_FIELD("ConfigurationProperties field"),
        ACCESSOR_CALLS("Accessor calls updated (getter/setter)"),
        UNKNOWN("Unknown pattern");

        private final String displayName;

        PatternType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getDisplayName(String fieldName) {
            if (this == CONFIGURATION_PROPERTIES_FIELD) {
                return displayName + " (" + fieldName + ")";
            }
            return displayName;
        }
    }
}
