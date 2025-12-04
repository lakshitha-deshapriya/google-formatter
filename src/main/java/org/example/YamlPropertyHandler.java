package org.example;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class YamlPropertyHandler {

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
                            PropertyScanner.PatternType.YAML_PROPERTY
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
                    PropertyScanner.PatternType.YAML_PROPERTY
                ));
            }
        }
    }

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
        return fullPath.append(".").append(key).toString();
    }

    /**
     * Normalize array indices in a path by replacing specific indices with a generic placeholder.
     * For example: "config[0].appId" becomes "config[*].appId"
     * This allows matching paths with different array indices.
     */
    private String normalizeArrayIndices(String path) {
        return path.replaceAll("\\[\\d+\\]", "[*]");
    }

    private List<String> parsePathSegments(String path) {
        List<String> segments = new ArrayList<>();
        // Remove array indices and split by dots
        String pathWithoutArrays = path.replaceAll("\\[\\d+\\]", "");
        String[] parts = pathWithoutArrays.split("\\.");
        for (String part : parts) {
            if (!part.isEmpty()) {
                segments.add(part);
            }
        }
        return segments;
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
                            PropertyScanner.PatternType.YAML_PROPERTY
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
                    PropertyScanner.PatternType.YAML_PROPERTY
                ));
            }
        }
    }

    public boolean renamePropertiesInYaml(String filePath, Map<String, String> mappings) {
        try {
            // Load YAML file using SnakeYAML
            Map<String, Object> yamlData;
            try (FileInputStream fis = new FileInputStream(filePath)) {
                Yaml yaml = new Yaml();
                Object data = yaml.load(fis);
                if (data instanceof Map) {
                    yamlData = (Map<String, Object>) data;
                } else {
                    System.err.println("YAML file does not contain a map structure: " + filePath);
                    return false;
                }
            }

            // Apply mappings to the loaded YAML data
            boolean modified = applyMappingsToYamlData(yamlData, "", mappings);

            // Write back if modified using SnakeYAML dumper
            if (modified) {
                writeYamlFile(filePath, yamlData);
            }

            return modified;

        } catch (Exception e) {
            System.err.println("Error renaming properties in YAML file: " + filePath + " - " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Recursively apply mappings to the YAML data structure.
     * This modifies the data in place by renaming keys according to the mappings.
     * Handles cases where a single key needs to be expanded into nested structure.
     */
    private boolean applyMappingsToYamlData(Map<String, Object> map, String prefix, Map<String, String> mappings) {
        boolean modified = false;

        // We need to iterate over a copy of keys since we might be modifying the map
        List<String> keys = new ArrayList<>(map.keySet());

        for (String key : keys) {
            Object value = map.get(key);
            String fullPath = prefix.isEmpty() ? key : prefix + "." + key;

            // Find the new path for this key
            String newKeyPath = findNewKeyPathForPath(fullPath, key, mappings);

            if (!key.equals(newKeyPath)) {
                // Remove the old key
                map.remove(key);

                // Check if newKeyPath contains dots (needs to create nested structure)
                if (newKeyPath.contains(".")) {
                    // Create nested structure
                    String[] parts = newKeyPath.split("\\.");
                    Map<String, Object> current = map;

                    for (int i = 0; i < parts.length - 1; i++) {
                        String part = parts[i];
                        if (!current.containsKey(part)) {
                            current.put(part, new LinkedHashMap<String, Object>());
                        }
                        Object next = current.get(part);
                        if (next instanceof Map) {
                            current = (Map<String, Object>) next;
                        } else {
                            // Cannot create nested structure, target already has a value
                            break;
                        }
                    }
                    // Put the value at the final key
                    current.put(parts[parts.length - 1], value);
                } else {
                    // Simple rename
                    map.put(newKeyPath, value);
                }
                modified = true;
            }

            // Recurse into nested structures (use original fullPath for matching since mappings use old paths)
            if (value instanceof Map) {
                boolean childModified = applyMappingsToYamlData((Map<String, Object>) value, fullPath, mappings);
                modified = modified || childModified;
            } else if (value instanceof List) {
                boolean listModified = applyMappingsToList((List<Object>) value, fullPath, mappings);
                modified = modified || listModified;
            }
        }

        return modified;
    }

    /**
     * Find the new key path for a given full path.
     * Only returns a different key if there's a direct mapping that applies to this exact path segment.
     */
    private String findNewKeyPathForPath(String fullPath, String currentKey, Map<String, String> mappings) {
        String normalizedFullPath = normalizeArrayIndices(fullPath);
        List<String> currentSegments = parsePathSegments(fullPath);
        int currentDepth = currentSegments.size();

        for (Map.Entry<String, String> mapping : mappings.entrySet()) {
            String oldPath = mapping.getKey();
            String newPath = mapping.getValue();
            String normalizedOldPath = normalizeArrayIndices(oldPath);

            // The mapping must start with the current path or be the current path
            if (!normalizedOldPath.startsWith(normalizedFullPath) &&
                !normalizedOldPath.equals(normalizedFullPath)) {
                continue;
            }

            // Additional check: if it starts with our path, it must be followed by . or [ or be exact
            if (!normalizedOldPath.equals(normalizedFullPath)) {
                String remainder = normalizedOldPath.substring(normalizedFullPath.length());
                if (!remainder.isEmpty() && !remainder.startsWith(".") && !remainder.startsWith("[")) {
                    continue;
                }
            }

            List<String> oldSegments = parsePathSegments(oldPath);
            List<String> newSegments = parsePathSegments(newPath);

            // Verify that all segments up to current depth match exactly
            if (currentDepth > oldSegments.size()) {
                continue;
            }

            boolean allSegmentsMatch = true;
            for (int i = 0; i < currentDepth; i++) {
                if (!currentSegments.get(i).equals(oldSegments.get(i))) {
                    allSegmentsMatch = false;
                    break;
                }
            }

            if (!allSegmentsMatch) {
                continue;
            }

            // The current key is at index (currentDepth - 1) in the old segments
            int keyIndexInOld = currentDepth - 1;

            // Count how many segments from the START are identical between old and new
            int matchingFromStart = 0;
            int minLen = Math.min(oldSegments.size(), newSegments.size());
            for (int i = 0; i < minLen; i++) {
                if (oldSegments.get(i).equals(newSegments.get(i))) {
                    matchingFromStart++;
                } else {
                    break;
                }
            }

            // If the current key is in the matching-from-start region, no rename needed
            if (keyIndexInOld < matchingFromStart) {
                continue;
            }

            // Count how many segments from the END are identical (starting after matchingFromStart)
            int matchingFromEnd = 0;
            int oi = oldSegments.size() - 1;
            int ni = newSegments.size() - 1;
            while (oi >= matchingFromStart && ni >= matchingFromStart &&
                   oldSegments.get(oi).equals(newSegments.get(ni))) {
                matchingFromEnd++;
                oi--;
                ni--;
            }

            // Calculate the non-matching middle region boundaries
            int nonMatchingOldStart = matchingFromStart;
            int nonMatchingOldEnd = oldSegments.size() - matchingFromEnd;
            int nonMatchingNewStart = matchingFromStart;
            int nonMatchingNewEnd = newSegments.size() - matchingFromEnd;

            // If the current key is in the matching-from-end region, no rename needed
            if (keyIndexInOld >= nonMatchingOldEnd) {
                continue;
            }

            // Check if current key is in the non-matching middle region
            if (keyIndexInOld >= nonMatchingOldStart && keyIndexInOld < nonMatchingOldEnd) {
                int posInNonMatching = keyIndexInOld - nonMatchingOldStart;
                int nonMatchingOldCount = nonMatchingOldEnd - nonMatchingOldStart;
                int nonMatchingNewCount = nonMatchingNewEnd - nonMatchingNewStart;

                // If old and new have same number of non-matching segments, do 1-to-1 mapping
                if (nonMatchingOldCount == nonMatchingNewCount) {
                    int newIndex = nonMatchingNewStart + posInNonMatching;
                    if (newIndex < nonMatchingNewEnd) {
                        String newSegment = newSegments.get(newIndex);
                        if (!newSegment.equals(currentKey)) {
                            return newSegment;
                        }
                      }
                } else if (posInNonMatching == 0 && nonMatchingNewCount > 0) {
                    // Only expand to multiple segments if counts differ and this is the first segment
                    StringBuilder newKeyPath = new StringBuilder();
                    for (int i = nonMatchingNewStart; i < nonMatchingNewEnd; i++) {
                        if (i > nonMatchingNewStart) {
                            newKeyPath.append(".");
                        }
                        newKeyPath.append(newSegments.get(i));
                    }
                    return newKeyPath.toString();
                }
            }
        }

        return currentKey; // No change needed
    }

    /**
     * Recursively apply mappings to list elements.
     */
    private boolean applyMappingsToList(List<Object> list, String prefix, Map<String, String> mappings) {
        boolean modified = false;

        for (int i = 0; i < list.size(); i++) {
            Object element = list.get(i);
            String arrayPath = prefix + "[" + i + "]";

            if (element instanceof Map) {
                boolean childModified = applyMappingsToYamlData((Map<String, Object>) element, arrayPath, mappings);
                modified = modified || childModified;
            } else if (element instanceof List) {
                boolean childModified = applyMappingsToList((List<Object>) element, arrayPath, mappings);
                modified = modified || childModified;
            }
        }

        return modified;
    }

    /**
     * Write YAML data to file using SnakeYAML with proper formatting.
     * Always adds a newline at the end of the file.
     */
    private void writeYamlFile(String filePath, Map<String, Object> yamlData) throws IOException {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setIndicatorIndent(2);  // Indent array dashes under the parent key
        options.setIndentWithIndicator(true);  // Ensure proper indentation with indicators
        options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);

        Yaml yaml = new Yaml(options);
        String yamlContent = yaml.dump(yamlData);

        // Ensure the content ends with a newline
        if (!yamlContent.endsWith("\n")) {
            yamlContent += "\n";
        }

        // Write to file
        Files.writeString(Path.of(filePath), yamlContent);
    }
}

