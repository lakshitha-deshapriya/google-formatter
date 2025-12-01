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

    static {
        // @Value annotation patterns - supports multi-line with flexible whitespace
        PROPERTY_PATTERNS.add(Pattern.compile("@Value\\s*\\(\\s*\"((?:[^\"\\\\]|\\\\[\\s\\S])*)\"\\s*\\)"));

        // @ConfigurationProperty annotation patterns
        PROPERTY_PATTERNS.add(Pattern.compile("@ConfigurationProperty\\s*\\(\\s*\"((?:[^\"\\\\]|\\\\[\\s\\S])*)\"\\s*\\)"));
        PROPERTY_PATTERNS.add(Pattern.compile("@ConfigurationProperties\\s*\\(\\s*prefix\\s*=\\s*\"((?:[^\"\\\\]|\\\\[\\s\\S])*)\"\\s*\\)"));

        // Environment.getProperty() patterns
        PROPERTY_PATTERNS.add(Pattern.compile("getProperty\\s*\\(\\s*\"((?:[^\"\\\\]|\\\\[\\s\\S])*)\""));

        // System.getProperty() patterns
        PROPERTY_PATTERNS.add(Pattern.compile("System\\.getProperty\\s*\\(\\s*\"((?:[^\"\\\\]|\\\\[\\s\\S])*)\""));

        // @PropertySource annotation
        PROPERTY_PATTERNS.add(Pattern.compile("@PropertySource\\s*\\(\\s*\"((?:[^\"\\\\]|\\\\[\\s\\S])*)\"\\s*\\)"));

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

        // Check if it's a .properties or .yml file
        if (filePath.endsWith(".properties") || filePath.endsWith(".yml") || filePath.endsWith(".yaml")) {
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
                String patternType = getPatternType(pattern);

                // For annotations, use DOTALL flag to match across newlines
                Pattern multiLinePattern = Pattern.compile(pattern.pattern(), Pattern.DOTALL);
                Matcher matcher = multiLinePattern.matcher(fileContent);

                while (matcher.find()) {
                    String propertyKey = matcher.group(1).trim();  // Trim whitespace
                    int matchStart = matcher.start();

                    // Calculate line number by counting newlines before the match
                    int lineNumber = calculateLineNumber(fileContent, matchStart);

                    // Get the context (the actual line where match starts, trimmed)
                    String context = lines.get(lineNumber - 1).trim();

                    PropertyMatch newMatch = new PropertyMatch(
                            filePath,
                            lineNumber,
                            propertyKey,
                            context,
                            patternType
                    );

                    // Check for duplicates before adding
                    if (!isDuplicate(matches, newMatch)) {
                        matches.add(newMatch);
                    }
                }
            }

            // Remove placeholder patterns if annotation patterns exist for the same property
            matches = filterDuplicates(matches);

        } catch (IOException e) {
            System.err.println("Error reading file: " + filePath + " - " + e.getMessage());
        }

        // Print results for this file (only if not in silent mode)
        if (!silentMode && !matches.isEmpty()) {
            System.out.println(filePath);

            for (PropertyMatch match : matches) {
                System.out.println("Line: " + match.lineNumber +
                                 ", Usage: " + match.patternType +
                                 ", Property: " + match.propertyKey);
            }
            System.out.println();
        }

        return matches;
    }

    private String getPatternType(Pattern pattern) {
        String patternStr = pattern.pattern();

        if (patternStr.contains("@Value")) {
            return "@Value annotation";
        } else if (patternStr.contains("@ConfigurationProperty\\s*\\(")) {
            return "@ConfigurationProperty annotation";
        } else if (patternStr.contains("@ConfigurationProperties")) {
            return "@ConfigurationProperties annotation (prefix)";
        } else if (patternStr.contains("getProperty")) {
            if (patternStr.contains("System")) {
                return "System.getProperty()";
            }
            return "Environment.getProperty()";
        } else if (patternStr.contains("@PropertySource")) {
            return "@PropertySource annotation";
        } else if (patternStr.contains("\\$\\{")) {
            return "Property placeholder ${...}";
        }

        return "Unknown pattern";
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
            if (existing.lineNumber == newMatch.lineNumber &&
                existing.propertyKey.equals(newMatch.propertyKey) &&
                existing.patternType.equals(newMatch.patternType)) {
                return true;
            }
        }
        return false;
    }

    private List<PropertyMatch> filterDuplicates(List<PropertyMatch> matches) {
        List<PropertyMatch> filtered = new ArrayList<>();

        for (PropertyMatch match : matches) {
            boolean shouldAdd = true;

            if (match.patternType.equals("Property placeholder ${...}")) {
                for (PropertyMatch other : matches) {
                    if (!other.patternType.equals("Property placeholder ${...}")) {
                        boolean sameArea = Math.abs(other.lineNumber - match.lineNumber) <= 5;
                        boolean propertyContained = other.propertyKey.contains(match.propertyKey) ||
                                                   match.propertyKey.equals(extractPropertyKey(other.propertyKey));

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

                // Skip comments and empty lines
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                    continue;
                }

                // Extract property key (before = or :)
                int separatorIndex = -1;
                if (trimmed.contains("=")) {
                    separatorIndex = trimmed.indexOf("=");
                } else if (trimmed.contains(":")) {
                    separatorIndex = trimmed.indexOf(":");
                }

                if (separatorIndex > 0) {
                    String propertyKey = trimmed.substring(0, separatorIndex).trim();

                    PropertyMatch match = new PropertyMatch(
                        filePath,
                        lineNumber,
                        propertyKey,
                        trimmed,
                        "Property file entry"
                    );

                    matches.add(match);
                }
            }

        } catch (IOException e) {
            System.err.println("Error reading properties file: " + filePath + " - " + e.getMessage());
        }

        // Print results for this file (only if not in silent mode)
        if (!silentMode && !matches.isEmpty()) {
            System.out.println(filePath);
            for (PropertyMatch match : matches) {
                System.out.println("Line: " + match.lineNumber +
                                 ", Property: " + match.propertyKey);
            }
            System.out.println();
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
        return fullProperty;
    }

    public static class PropertyMatch {
        public final String filePath;
        public final int lineNumber;
        public final String propertyKey;
        public final String context;
        public final String patternType;

        public PropertyMatch(String filePath, int lineNumber, String propertyKey, String context, String patternType) {
            this.filePath = filePath;
            this.lineNumber = lineNumber;
            this.propertyKey = propertyKey;
            this.context = context;
            this.patternType = patternType;
        }
    }
}

