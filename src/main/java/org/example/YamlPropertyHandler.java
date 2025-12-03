package org.example;

import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.util.*;

public class YamlPropertyHandler {

    /**
     * Scans a YAML file and returns property matches with full dot-notation paths
     */
    public List<PropertyScanner.PropertyMatch> scanYamlFile(String filePath) {
        List<PropertyScanner.PropertyMatch> matches = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(filePath)) {
            Yaml yaml = new Yaml();
            Object data = yaml.load(fis);

            if (data instanceof Map) {
                Map<String, Object> yamlMap = (Map<String, Object>) data;
                extractProperties(yamlMap, "", matches, filePath);
            }

        } catch (Exception e) {
            System.err.println("Error reading YAML file: " + filePath + " - " + e.getMessage());
        }

        return matches;
    }

    /**
     * Recursively extract properties from YAML structure
     */
    private void extractProperties(Map<String, Object> map, String prefix,
                                   List<PropertyScanner.PropertyMatch> matches, String filePath) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String fullPath = prefix.isEmpty() ? key : prefix + "." + key;

            if (value instanceof Map) {
                // Nested structure - recurse
                extractProperties((Map<String, Object>) value, fullPath, matches, filePath);
            } else if (value instanceof List) {
                // Process array elements
                List<?> list = (List<?>) value;
                for (int i = 0; i < list.size(); i++) {
                    String arrayPath = fullPath + "[" + i + "]";
                    Object arrayElement = list.get(i);

                    if (arrayElement instanceof Map) {
                        // Array of objects - recurse into each object
                        extractProperties((Map<String, Object>) arrayElement, arrayPath, matches, filePath);
                    } else {
                        // Array of primitives - add the array element as a property
                        matches.add(new PropertyScanner.PropertyMatch(
                            filePath,
                            -1, // Line number not easily available with SnakeYAML
                            arrayPath,
                            arrayPath + ": " + (arrayElement != null ? arrayElement.toString() : "null"),
                            "YAML property"
                        ));
                    }
                }
            } else {
                // Leaf property
                matches.add(new PropertyScanner.PropertyMatch(
                    filePath,
                    -1, // Line number not easily available with SnakeYAML
                    fullPath,
                    fullPath + ": " + (value != null ? value.toString() : "null"),
                    "YAML property"
                ));
            }
        }
    }

    /**
     * Scans YAML file with line number tracking using a custom approach
     */
    public List<PropertyScanner.PropertyMatch> scanYamlFileWithLineNumbers(String filePath) {
        List<PropertyScanner.PropertyMatch> matches = new ArrayList<>();
        Map<String, Integer> propertyLineMap = new HashMap<>();

        try {
            // First pass: build line number map including array elements
            try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
                String line;
                int lineNumber = 0;
                Stack<String> pathStack = new Stack<>();
                Stack<Integer> indentStack = new Stack<>();
                indentStack.push(-1);

                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    String trimmed = line.trim();

                    // Skip comments and empty lines
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }

                    // Calculate indentation
                    int indent = line.indexOf(line.trim());

                    // Check if this is an array item (starts with -)
                    boolean isArrayItem = trimmed.startsWith("-");

                    // Pop stack if we've dedented
                    while (!indentStack.isEmpty() && indent <= indentStack.peek()) {
                        indentStack.pop();
                        if (!pathStack.isEmpty()) {
                            pathStack.pop();
                        }
                    }

                    if (isArrayItem) {
                        // Handle array item
                        String content = trimmed.substring(1).trim(); // Remove the '-'

                        // Determine the array index by counting how many array indices are at this level
                        int currentArrayIndex = 0;
                        // Look at the current path to find the last array index at this level
                        for (int i = pathStack.size() - 1; i >= 0; i--) {
                            String part = pathStack.get(i);
                            if (part.matches("\\[\\d+\\]")) {
                                // Found an array index at the same or parent level
                                // We need to increment from the last one at same indent level
                                if (i == pathStack.size() - 1) {
                                    // Last element is array index, we're continuing same array
                                    currentArrayIndex = Integer.parseInt(part.substring(1, part.length() - 1)) + 1;
                                    pathStack.pop();
                                    indentStack.pop();
                                }
                                break;
                            }
                        }

                        // Add array index to path
                        pathStack.push("[" + currentArrayIndex + "]");
                        indentStack.push(indent);

                        // Process the content after the dash
                        int colonIndex = content.indexOf(':');
                        if (colonIndex > 0) {
                            String key = content.substring(0, colonIndex).trim();

                            // Build full path
                            String fullPath = buildPath(pathStack, key);

                            // Store line number for this property
                            propertyLineMap.put(fullPath, lineNumber);

                            // Check if this has a value on the same line
                            String afterColon = content.substring(colonIndex + 1).trim();
                            if (afterColon.isEmpty() || afterColon.startsWith("#")) {
                                // No value, this is a parent key
                                pathStack.push(key);
                                indentStack.push(indent + 2); // Account for "- " offset
                            }
                        } else if (content.isEmpty()) {
                            // Just a dash with no content on this line, array of objects coming
                            // The array index stays on stack for next lines
                        }
                    } else {
                        // Regular property (not array item)
                        // Extract key (before colon)
                        int colonIndex = trimmed.indexOf(':');
                        if (colonIndex > 0) {
                            String key = trimmed.substring(0, colonIndex).trim();

                            // Build full path
                            String fullPath = buildPath(pathStack, key);

                            // Store line number for this property
                            propertyLineMap.put(fullPath, lineNumber);

                            // Check if this has a value on the same line
                            String afterColon = trimmed.substring(colonIndex + 1).trim();
                            if (afterColon.isEmpty() || afterColon.startsWith("#")) {
                                // No value, this is a parent key
                                pathStack.push(key);
                                indentStack.push(indent);
                            }
                        }
                    }
                }
            }
                            // Second pass: parse YAML structure
            try (FileInputStream fis = new FileInputStream(filePath)) {
                Yaml yaml = new Yaml();
                Object data = yaml.load(fis);

                if (data instanceof Map) {
                    Map<String, Object> yamlMap = (Map<String, Object>) data;
                    extractPropertiesWithLineNumbers(yamlMap, "", matches, filePath, propertyLineMap);
                }
            }

        } catch (Exception e) {
            System.err.println("Error reading YAML file: " + filePath + " - " + e.getMessage());
        }

        return matches;
    }

    /**
     * Helper method to build full path from stack
     */
    private String buildPath(Stack<String> pathStack, String key) {
        StringBuilder fullPath = new StringBuilder();
        for (String part : pathStack) {
            if (part.startsWith("[")) {
                fullPath.append(part);
            } else {
                if (fullPath.length() > 0 && !fullPath.toString().endsWith("]")) {
                    fullPath.append(".");
                }
                fullPath.append(part);
            }
        }
        fullPath.append(".");
        if (fullPath.length() > 0 && !fullPath.toString().endsWith("]")) {

        }
        fullPath.append(key);
        return fullPath.toString();
    }

    /**
     * Helper method to extract the new key from a property path
     */
    private String extractNewKey(String newPropertyPath) {
        // Remove any trailing array notation
        String pathWithoutArraySuffix = newPropertyPath;
        int lastBracketIndex = pathWithoutArraySuffix.lastIndexOf('[');
        if (lastBracketIndex > 0) {
            // Check if there's content after the bracket
            int closeBracket = pathWithoutArraySuffix.indexOf(']', lastBracketIndex);
            if (closeBracket > lastBracketIndex && closeBracket < pathWithoutArraySuffix.length() - 1) {
                // There's content after the bracket (e.g., [0].key)
                pathWithoutArraySuffix = pathWithoutArraySuffix.substring(closeBracket + 2); // +2 to skip ].
                return pathWithoutArraySuffix;
            }
        }

        // Extract last part after the last dot
        int lastDotIndex = newPropertyPath.lastIndexOf('.');
        if (lastDotIndex >= 0) {
            return newPropertyPath.substring(lastDotIndex + 1);
        }
        return newPropertyPath;
    }

    private void extractPropertiesWithLineNumbers(Map<String, Object> map, String prefix,
                                                  List<PropertyScanner.PropertyMatch> matches,
                                                  String filePath, Map<String, Integer> lineMap) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String fullPath = prefix.isEmpty() ? key : prefix + "." + key;

            int lineNumber = lineMap.getOrDefault(fullPath, -1);

            if (value instanceof Map) {
                // Nested structure - recurse
                extractPropertiesWithLineNumbers((Map<String, Object>) value, fullPath, matches, filePath, lineMap);
            } else if (value instanceof List) {
                List<?> list = (List<?>) value;

                // Process array elements
                for (int i = 0; i < list.size(); i++) {
                    String arrayPath = fullPath + "[" + i + "]";
                    Object arrayElement = list.get(i);

                    if (arrayElement instanceof Map) {
                        // Array of objects - recurse into each object
                        extractPropertiesWithLineNumbers((Map<String, Object>) arrayElement, arrayPath, matches, filePath, lineMap);
                    } else {
                        // Array of primitives - add the array element as a property
                        int arrayLineNumber = lineMap.getOrDefault(arrayPath, -1);
                        matches.add(new PropertyScanner.PropertyMatch(
                            filePath,
                            arrayLineNumber,
                            arrayPath,
                            arrayPath + ": " + (arrayElement != null ? arrayElement.toString() : "null"),
                            "YAML property"
                        ));
                    }
                }
            } else {
                // Leaf property
                matches.add(new PropertyScanner.PropertyMatch(
                    filePath,
                    lineNumber,
                    fullPath,
                    fullPath + ": " + (value != null ? value.toString() : "null"),
                    "YAML property"
                ));
            }
        }
    }

    /**
     * Rename properties in a YAML file
     */
    public boolean renamePropertiesInYaml(String filePath, Map<String, String> mappings) {
        try {
            // Read all lines
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }

            boolean modified = false;
            Stack<String> pathStack = new Stack<>();
            Stack<Integer> indentStack = new Stack<>();
            indentStack.push(-1);

            // Process each line
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.trim();

                // Skip comments and empty lines
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }

                // Calculate indentation
                int indent = line.indexOf(line.trim());

                // Check if this is an array item (starts with -)
                boolean isArrayItem = trimmed.startsWith("-");

                // Pop stack if we've dedented
                while (!indentStack.isEmpty() && indent <= indentStack.peek()) {
                    indentStack.pop();
                    if (!pathStack.isEmpty()) {
                        pathStack.pop();
                    }
                }

                if (isArrayItem) {
                    // Handle array item
                    String content = trimmed.substring(1).trim(); // Remove the '-'

                    // Determine the array index
                    int currentArrayIndex = 0;
                    for (int j = pathStack.size() - 1; j >= 0; j--) {
                        String part = pathStack.get(j);
                        if (part.matches("\\[\\d+\\]")) {
                            if (j == pathStack.size() - 1) {
                                currentArrayIndex = Integer.parseInt(part.substring(1, part.length() - 1)) + 1;
                                pathStack.pop();
                                indentStack.pop();
                            }
                            break;
                        }
                    }

                    // Add array index to path
                    pathStack.push("[" + currentArrayIndex + "]");
                    indentStack.push(indent);

                    // Process the content after the dash
                    int colonIndex = content.indexOf(':');
                    if (colonIndex > 0) {
                        String key = content.substring(0, colonIndex).trim();

                        // Build full path
                        String fullPath = buildPath(pathStack, key);

                        // Check if we need to rename this property
                        if (mappings.containsKey(fullPath)) {
                            String newPropertyPath = mappings.get(fullPath);

                            // Extract the new key (last part after the last dot)
                            String newKey = extractNewKey(newPropertyPath);

                            // Only rename if the key actually changed
                            if (!key.equals(newKey)) {
                                // Replace the key in the line while preserving indentation and value
                                int keyStartInLine = line.indexOf(key, line.indexOf("-") + 1);
                                if (keyStartInLine > 0) {
                                    String beforeKey = line.substring(0, keyStartInLine);
                                    String afterKey = line.substring(keyStartInLine + key.length());
                                    lines.set(i, beforeKey + newKey + afterKey);
                                    modified = true;
                                }
                            }
                        }

                        // Check if this has a value on the same line
                        String afterColon = content.substring(colonIndex + 1).trim();
                        if (afterColon.isEmpty() || afterColon.startsWith("#")) {
                            // No value, this is a parent key
                            pathStack.push(key);
                            indentStack.push(indent + 2); // Account for "- " offset
                        }
                    }
                } else {
                    // Regular property (not array item)
                    // Extract key (before colon)
                    int colonIndex = trimmed.indexOf(':');
                    if (colonIndex > 0) {
                        String key = trimmed.substring(0, colonIndex).trim();

                        // Build full path
                        String fullPath = buildPath(pathStack, key);

                        // Check if we need to rename this property
                        if (mappings.containsKey(fullPath)) {
                            String newPropertyPath = mappings.get(fullPath);

                            // Extract the new key (last part after the last dot)
                            String newKey = extractNewKey(newPropertyPath);

                            // Only rename if the key actually changed
                            if (!key.equals(newKey)) {
                                // Replace the key in the line while preserving indentation and value
                                int keyStartInLine = line.indexOf(key);
                                if (keyStartInLine >= 0) {
                                    String beforeKey = line.substring(0, keyStartInLine);
                                    String afterKey = line.substring(keyStartInLine + key.length());
                                    lines.set(i, beforeKey + newKey + afterKey);
                                    modified = true;
                                }
                            }
                        }

                        // Check if this has a value on the same line
                        String afterColon = trimmed.substring(colonIndex + 1).trim();
                        if (afterColon.isEmpty() || afterColon.startsWith("#")) {
                            // No value, this is a parent key
                            pathStack.push(key);
                            indentStack.push(indent);
                        }
                    }
                }
            }

            // Write back if modified
            if (modified) {
                // Check if original file had trailing newline before writing
                boolean hadTrailingNewline = false;
                try (RandomAccessFile raf = new RandomAccessFile(filePath, "r")) {
                    if (raf.length() > 0) {
                        raf.seek(raf.length() - 1);
                        byte lastByte = raf.readByte();
                        hadTrailingNewline = (lastByte == '\n' || lastByte == '\r');
                    }
                }

                try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
                    for (int i = 0; i < lines.size(); i++) {
                        writer.write(lines.get(i));
                        if (i < lines.size() - 1 || hadTrailingNewline) {
                            writer.write("\n");
                        }
                    }
                }
            }

            return modified;

        } catch (Exception e) {
            System.err.println("Error renaming properties in YAML file: " + filePath + " - " + e.getMessage());
            return false;
        }
    }
}

