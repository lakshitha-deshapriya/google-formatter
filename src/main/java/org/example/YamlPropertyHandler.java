package org.example;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class YamlPropertyHandler {

    public List<PropertyScanner.PropertyMatch> scanYamlFileWithLineNumbers(String filePath) {
        List<PropertyScanner.PropertyMatch> matches = new ArrayList<>();
        Map<String, Integer> propertyLineMap = new HashMap<>();

        try {
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

                    int indent = line.indexOf(line.trim());
                    boolean isArrayItem = trimmed.startsWith("-");

                    while (!indentStack.isEmpty() && indent <= indentStack.peek()) {
                        indentStack.pop();
                        if (!pathStack.isEmpty()) {
                            pathStack.pop();
                        }
                    }

                    if (isArrayItem) {
                        String content = trimmed.substring(1).trim(); // Remove the '-'

                        int currentArrayIndex = 0;
                        for (int i = pathStack.size() - 1; i >= 0; i--) {
                            String part = pathStack.get(i);
                            if (part.matches("\\[\\d+\\]")) {
                                if (i == pathStack.size() - 1) {
                                    // Last element is array index, we're continuing same array
                                    currentArrayIndex =
                                            Integer.parseInt(part.substring(1, part.length() - 1))
                                                    + 1;
                                    pathStack.pop();
                                    indentStack.pop();
                                }
                                break;
                            }
                        }

                        pathStack.push("[" + currentArrayIndex + "]");
                        indentStack.push(indent);

                        // Process the content after the dash
                        int colonIndex = content.indexOf(':');
                        if (colonIndex > 0) {
                            String key = content.substring(0, colonIndex).trim();

                            String fullPath = buildPath(pathStack, key);
                            propertyLineMap.put(fullPath, lineNumber);

                            String afterColon = content.substring(colonIndex + 1).trim();
                            if (afterColon.isEmpty() || afterColon.startsWith("#")) {
                                pathStack.push(key);
                                indentStack.push(indent + 2);
                            }
                        }
                    } else {
                        int colonIndex = trimmed.indexOf(':');
                        if (colonIndex > 0) {
                            String key = trimmed.substring(0, colonIndex).trim();

                            String fullPath = buildPath(pathStack, key);
                            propertyLineMap.put(fullPath, lineNumber);

                            String afterColon = trimmed.substring(colonIndex + 1).trim();
                            if (afterColon.isEmpty() || afterColon.startsWith("#")) {
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
                    extractPropertiesWithLineNumbers(
                            yamlMap, "", matches, filePath, propertyLineMap);
                }
            }

        } catch (Exception e) {
            Util.logError("Error reading YAML file: " + filePath + " - " + e.getMessage());
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

    private String normalizeArrayIndices(String path) {
        return path.replaceAll("\\[\\d+\\]", "[*]");
    }

    private List<String> parsePathSegments(String path) {
        List<String> segments = new ArrayList<>();
        String pathWithoutArrays = path.replaceAll("\\[\\d+\\]", "");
        String[] parts = pathWithoutArrays.split("\\.");
        for (String part : parts) {
            if (!part.isEmpty()) {
                segments.add(part);
            }
        }
        return segments;
    }

    private void extractPropertiesWithLineNumbers(
            Map<String, Object> map,
            String prefix,
            List<PropertyScanner.PropertyMatch> matches,
            String filePath,
            Map<String, Integer> lineMap) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String fullPath = prefix.isEmpty() ? key : prefix + "." + key;

            int lineNumber = lineMap.getOrDefault(fullPath, -1);

            if (value instanceof Map) {
                // Nested structure - recurse
                extractPropertiesWithLineNumbers(
                        (Map<String, Object>) value, fullPath, matches, filePath, lineMap);
            } else if (value instanceof List) {
                List<?> list = (List<?>) value;

                for (int i = 0; i < list.size(); i++) {
                    String arrayPath = fullPath + "[" + i + "]";
                    Object arrayElement = list.get(i);

                    if (arrayElement instanceof Map) {
                        // Array of objects - recurse into each object
                        extractPropertiesWithLineNumbers(
                                (Map<String, Object>) arrayElement,
                                arrayPath,
                                matches,
                                filePath,
                                lineMap);
                    } else {
                        // Array of primitives - add the array element as a property
                        int arrayLineNumber = lineMap.getOrDefault(arrayPath, -1);
                        matches.add(
                                new PropertyScanner.PropertyMatch(
                                        filePath,
                                        arrayLineNumber,
                                        arrayPath,
                                        arrayPath
                                                + ": "
                                                + (arrayElement != null
                                                        ? arrayElement.toString()
                                                        : "null"),
                                        PropertyScanner.PatternType.YAML_PROPERTY));
                    }
                }
            } else {
                matches.add(
                        new PropertyScanner.PropertyMatch(
                                filePath,
                                lineNumber,
                                fullPath,
                                fullPath + ": " + (value != null ? value.toString() : "null"),
                                PropertyScanner.PatternType.YAML_PROPERTY));
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
                    Util.log("YAML file does not contain a map structure: " + filePath);
                    return false;
                }
            }

            boolean modified = applyMappingsToYamlData(yamlData, "", mappings);

            if (modified) {
                writeYamlFile(filePath, yamlData);
            }

            return modified;

        } catch (Exception e) {
            Util.logError(
                    "Error renaming properties in YAML file: " + filePath + " - " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private boolean applyMappingsToYamlData(
            Map<String, Object> map, String prefix, Map<String, String> mappings) {
        boolean modified = false;

        List<String> keys = new ArrayList<>(map.keySet());

        for (String key : keys) {
            Object value = map.get(key);
            String fullPath = prefix.isEmpty() ? key : prefix + "." + key;

            if (value instanceof Map) {
                Map<String, Object> nestedMap = (Map<String, Object>) value;
                boolean nestedFlattened =
                        flattenNestedToTopLevel(map, key, fullPath, nestedMap, mappings, prefix);
                if (nestedFlattened) {
                    modified = true;
                    continue;
                }
            }

            String newKeyPath = findNewKeyPathForPath(fullPath, key, mappings);

            if (!key.equals(newKeyPath) && !newKeyPath.contains(".")) {
                map.remove(key);
                map.put(newKeyPath, value);
                modified = true;
            }

            if (value instanceof Map) {
                boolean childModified =
                        applyMappingsToYamlData((Map<String, Object>) value, fullPath, mappings);
                modified = modified || childModified;
            } else if (value instanceof List) {
                boolean listModified =
                        applyMappingsToList((List<Object>) value, fullPath, mappings);
                modified = modified || listModified;
            }
        }

        return modified;
    }

    private boolean flattenNestedToTopLevel(
            Map<String, Object> parentMap,
            String key,
            String fullPath,
            Map<String, Object> nestedMap,
            Map<String, String> mappings,
            String prefix) {

        // Find all leaf properties in this nested structure
        List<String> leafPaths = new ArrayList<>();
        List<Object> leafValues = new ArrayList<>();
        collectLeafProperties(nestedMap, fullPath, leafPaths, leafValues);

        boolean anyFlattened = false;
        Set<String> pathsToRemove = new HashSet<>();

        for (int i = 0; i < leafPaths.size(); i++) {
            String leafPath = leafPaths.get(i);
            Object leafValue = leafValues.get(i);

            // Check if this leaf path has a mapping to a "flatter" structure
            for (Map.Entry<String, String> mapping : mappings.entrySet()) {
                String oldPath = mapping.getKey();
                String newPath = mapping.getValue();

                // Check if this leaf matches
                if (!oldPath.equals(leafPath)
                        && !normalizeArrayIndices(oldPath)
                                .equals(normalizeArrayIndices(leafPath))) {
                    continue;
                }

                int oldDepth = countDots(oldPath);
                int newDepth = countDots(newPath);

                if (newDepth >= oldDepth) {
                    continue;
                }

                String oldRoot =
                        oldPath.contains(".")
                                ? oldPath.substring(0, oldPath.indexOf('.'))
                                : oldPath;
                String newRoot =
                        newPath.contains(".")
                                ? newPath.substring(0, newPath.indexOf('.'))
                                : newPath;

                if (!oldRoot.equals(newRoot) && newPath.contains(".")) {
                    continue;
                }

                int prefixDepth = prefix.isEmpty() ? 0 : countDots(prefix) + 1;
                int newAbsoluteDepth = countDots(newPath);

                if (newAbsoluteDepth <= prefixDepth) {
                    if (prefix.isEmpty() || !newPath.contains(".")) {
                        parentMap.put(newPath, leafValue);
                        pathsToRemove.add(leafPath);
                        anyFlattened = true;
                    }
                }
            }
        }

        if (anyFlattened) {
            for (String pathToRemove : pathsToRemove) {
                removeNestedPath(nestedMap, pathToRemove, fullPath);
            }
            if (nestedMap.isEmpty()) {
                parentMap.remove(key);
            }
        }

        return anyFlattened;
    }

    private void collectLeafProperties(
            Map<String, Object> map, String prefix, List<String> paths, List<Object> values) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String fullPath = prefix + "." + entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Map) {
                collectLeafProperties((Map<String, Object>) value, fullPath, paths, values);
            } else if (value instanceof List) {
                // For lists, add each element as a leaf
                List<?> list = (List<?>) value;
                for (int i = 0; i < list.size(); i++) {
                    Object element = list.get(i);
                    if (element instanceof Map) {
                        collectLeafProperties(
                                (Map<String, Object>) element,
                                fullPath + "[" + i + "]",
                                paths,
                                values);
                    } else {
                        paths.add(fullPath + "[" + i + "]");
                        values.add(element);
                    }
                }
            } else {
                paths.add(fullPath);
                values.add(value);
            }
        }
    }

    private void removeNestedPath(
            Map<String, Object> map, String pathToRemove, String currentPath) {
        // Remove the nested path from the map
        for (Iterator<Map.Entry<String, Object>> it = map.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Object> entry = it.next();
            String fullPath = currentPath + "." + entry.getKey();

            if (pathToRemove.equals(fullPath)) {
                it.remove();
                return;
            }

            if (pathToRemove.startsWith(fullPath + ".")
                    || pathToRemove.startsWith(fullPath + "[")) {
                Object value = entry.getValue();
                if (value instanceof Map) {
                    removeNestedPath((Map<String, Object>) value, pathToRemove, fullPath);
                    if (((Map<?, ?>) value).isEmpty()) {
                        it.remove();
                    }
                }
            }
        }
    }

    private int countDots(String path) {
        int count = 0;
        for (char c : path.toCharArray()) {
            if (c == '.') count++;
        }
        return count;
    }

    private String findNewKeyPathForPath(
            String fullPath, String currentKey, Map<String, String> mappings) {
        String normalizedFullPath = normalizeArrayIndices(fullPath);
        List<String> currentSegments = parsePathSegments(fullPath);
        int currentDepth = currentSegments.size();

        for (Map.Entry<String, String> mapping : mappings.entrySet()) {
            String oldPath = mapping.getKey();
            String newPath = mapping.getValue();
            String normalizedOldPath = normalizeArrayIndices(oldPath);

            List<String> oldSegments = parsePathSegments(oldPath);
            List<String> newSegments = parsePathSegments(newPath);

            if (oldSegments.size() > currentDepth) {
                continue;
            }

            if (!normalizedOldPath.equals(normalizedFullPath)) {
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

            int keyIndexInOld = currentDepth - 1;
            int matchingFromStart = 0;
            int minLen = Math.min(oldSegments.size(), newSegments.size());
            for (int i = 0; i < minLen; i++) {
                if (oldSegments.get(i).equals(newSegments.get(i))) {
                    matchingFromStart++;
                } else {
                    break;
                }
            }

            if (keyIndexInOld < matchingFromStart) {
                continue;
            }

            int matchingFromEnd = 0;
            int oi = oldSegments.size() - 1;
            int ni = newSegments.size() - 1;
            while (oi >= matchingFromStart
                    && ni >= matchingFromStart
                    && oldSegments.get(oi).equals(newSegments.get(ni))) {
                matchingFromEnd++;
                oi--;
                ni--;
            }

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
                    // Only expand to multiple segments if counts differ and this is the first
                    // segment
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

        return currentKey;
    }

    private boolean applyMappingsToList(
            List<Object> list, String prefix, Map<String, String> mappings) {
        boolean modified = false;

        for (int i = 0; i < list.size(); i++) {
            Object element = list.get(i);
            String arrayPath = prefix + "[" + i + "]";

            if (element instanceof Map) {
                boolean childModified =
                        applyMappingsToYamlData((Map<String, Object>) element, arrayPath, mappings);
                modified = modified || childModified;
            } else if (element instanceof List) {
                boolean childModified =
                        applyMappingsToList((List<Object>) element, arrayPath, mappings);
                modified = modified || childModified;
            }
        }

        return modified;
    }

    private void writeYamlFile(String filePath, Map<String, Object> yamlData) throws IOException {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setIndicatorIndent(2);
        options.setIndentWithIndicator(true);
        options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);

        Yaml yaml = new Yaml(options);
        String yamlContent = yaml.dump(yamlData);

        if (!yamlContent.endsWith("\n")) {
            yamlContent += "\n";
        }

        Files.writeString(Path.of(filePath), yamlContent);
    }
}
