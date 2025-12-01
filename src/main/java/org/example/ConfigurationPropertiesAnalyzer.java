package org.example;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConfigurationPropertiesAnalyzer {

    // Patterns for parsing class structure
    private static final Pattern CLASS_DECLARATION = Pattern.compile(
        "@ConfigurationProperties\\s*\\(\\s*prefix\\s*=\\s*\"([^\"]+)\"\\s*\\)\\s*(?:.*?\\n)*?\\s*(?:public\\s+)?class\\s+(\\w+)",
        Pattern.DOTALL
    );

    private static final Pattern FIELD_DECLARATION = Pattern.compile(
        "^\\s*private\\s+([\\w<>\\[\\],\\s]+)\\s+(\\w+)\\s*(?:=.*)?;",
        Pattern.MULTILINE
    );

    private static final Pattern GETTER_METHOD = Pattern.compile(
        "public\\s+([\\w<>\\[\\],\\s]+)\\s+get(\\w+)\\s*\\(",
        Pattern.MULTILINE
    );

    private static final Pattern SETTER_METHOD = Pattern.compile(
        "public\\s+void\\s+set(\\w+)\\s*\\(([\\w<>\\[\\],\\s]+)\\s+\\w+\\)",
        Pattern.MULTILINE
    );

    /**
     * Result of analyzing a @ConfigurationProperties class
     */
    public static class AnalysisResult {
        public String filePath;
        public String prefix;
        public String newPrefix;
        public String className;
        public List<FieldInfo> fields = new ArrayList<>();
        public boolean prefixChanged;
        public int prefixLineNumber;

        public AnalysisResult(String filePath, String prefix, String className, int prefixLineNumber) {
            this.filePath = filePath;
            this.prefix = prefix;
            this.className = className;
            this.prefixLineNumber = prefixLineNumber;
            this.prefixChanged = false;
        }
    }

    /**
     * Information about a field in the configuration class
     */
    public static class FieldInfo {
        public String fieldName;
        public String fieldType;
        public String fullPropertyPath;
        public String newFieldName;
        public int lineNumber;
        public boolean needsRename;
        public boolean isNested;
        public boolean isCollection; // List, Set, array
        public boolean isMap;
        public String genericType; // For collections and maps

        public FieldInfo(String fieldName, String fieldType, int lineNumber) {
            this.fieldName = fieldName;
            this.fieldType = fieldType;
            this.lineNumber = lineNumber;
            this.needsRename = false;
            this.isNested = false;
            this.isCollection = false;
            this.isMap = false;
            analyzeType();
        }

        private void analyzeType() {
            // Check if it's a collection type
            if (fieldType.contains("List<") || fieldType.contains("Set<") ||
                fieldType.contains("Collection<") || fieldType.endsWith("[]")) {
                this.isCollection = true;
                extractGenericType();
            } else if (fieldType.contains("Map<")) {
                this.isMap = true;
                extractGenericType();
            } else if (!isPrimitiveOrWrapper(fieldType) && !fieldType.equals("String")) {
                // If it's not a primitive, wrapper, or String, it might be a nested configuration
                this.isNested = true;
            }
        }

        private void extractGenericType() {
            int startIdx = fieldType.indexOf('<');
            int endIdx = fieldType.lastIndexOf('>');
            if (startIdx > 0 && endIdx > startIdx) {
                this.genericType = fieldType.substring(startIdx + 1, endIdx).trim();

                // For maps, extract the value type (after the comma)
                if (this.isMap && genericType.contains(",")) {
                    String[] parts = genericType.split(",");
                    if (parts.length > 1) {
                        this.genericType = parts[1].trim();
                    }
                }
            } else if (fieldType.endsWith("[]")) {
                // Array type
                this.genericType = fieldType.substring(0, fieldType.length() - 2).trim();
            }
        }

        private boolean isPrimitiveOrWrapper(String type) {
            return type.matches("int|long|double|float|boolean|byte|short|char|" +
                              "Integer|Long|Double|Float|Boolean|Byte|Short|Character");
        }

        /**
         * Convert field name to property format (camelCase to kebab-case)
         */
        public String toPropertyName() {
            // Convert camelCase to kebab-case
            return fieldName.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();
        }

        /**
         * Convert property name to field name (kebab-case to camelCase)
         */
        public static String toFieldName(String propertyName) {
            // Convert kebab-case to camelCase
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
    }

    /**
     * Analyze a file with @ConfigurationProperties annotation
     */
    public AnalysisResult analyzeFile(String filePath, Map<String, String> mappings) {
        try {
            String content = readFileContent(new File(filePath));

            // Find @ConfigurationProperties annotation and extract prefix
            Matcher classMatcher = CLASS_DECLARATION.matcher(content);
            if (!classMatcher.find()) {
                return null; // Not a @ConfigurationProperties class
            }

            String prefix = classMatcher.group(1);
            String className = classMatcher.group(2);
            int prefixLineNumber = calculateLineNumber(content, classMatcher.start());

            AnalysisResult result = new AnalysisResult(filePath, prefix, className, prefixLineNumber);

            // Identify the new prefix by analyzing property structure
            mappings.entrySet().stream()
                    .filter(
                            entry ->
                                    !entry.getKey().equalsIgnoreCase(entry.getValue())
                                            && entry.getKey().startsWith(prefix))
                    .findFirst()
                    .ifPresent(
                            entry -> {
                                result.newPrefix = determineNewPrefix(
                                        entry.getKey(),
                                        entry.getValue(),
                                        prefix
                                );
                                result.prefixChanged = !prefix.equals(result.newPrefix);
                            });

            // Find the main class body boundaries
            int classStartPos = classMatcher.end();
            int classEndPos = findClassEnd(content, classStartPos);
            String classBody = content.substring(classStartPos, classEndPos);

            // Find all fields in the main class only (not in nested classes)
            List<String> mainClassFields = extractMainClassFields(classBody);

            int currentPos = classStartPos;
            for (String fieldLine : mainClassFields) {
                Matcher fieldMatcher = FIELD_DECLARATION.matcher(fieldLine);
                if (fieldMatcher.find()) {
                    String fieldType = fieldMatcher.group(1).trim();
                    String fieldName = fieldMatcher.group(2);

                    // Calculate line number relative to the original content
                    int fieldPosInBody = classBody.indexOf(fieldLine);
                    int absolutePos = classStartPos + fieldPosInBody;
                    int lineNumber = calculateLineNumber(content, absolutePos);

                    FieldInfo fieldInfo = new FieldInfo(fieldName, fieldType, lineNumber);

                    // Build full property path: prefix.field-name
                    String propertyName = fieldInfo.toPropertyName();
                    fieldInfo.fullPropertyPath = prefix + "." + propertyName;

                    // Check if this property has a mapping
                    if (mappings.containsKey(fieldInfo.fullPropertyPath)) {
                        String newPropertyPath = mappings.get(fieldInfo.fullPropertyPath);

                        // Extract the new field name from the new property path
                        // Remove the prefix part and get the remaining property name
                        String newPrefix = result.newPrefix != null ? result.newPrefix : prefix;

                        if (newPropertyPath.startsWith(newPrefix + ".")) {
                            String newPropertyName = newPropertyPath.substring(newPrefix.length() + 1);
                            fieldInfo.newFieldName = FieldInfo.toFieldName(newPropertyName);
                            fieldInfo.needsRename = !fieldInfo.fieldName.equals(fieldInfo.newFieldName);
                        }
                    }

                    result.fields.add(fieldInfo);
                }
            }

            return result;

        } catch (IOException e) {
            System.err.println("Error analyzing file: " + filePath + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * Analyze a file that is a nested configuration class (not annotated with @ConfigurationProperties)
     * This handles cases like IdentityAdapterDefinition where fields are mapped via indexed properties.
     */
    public AnalysisResult analyzeNestedConfigClass(String filePath, Map<String, String> mappings) {
        try {
            String content = readFileContent(new File(filePath));

            // Find the class declaration
            Pattern classPattern = Pattern.compile("(?:public\\s+)?class\\s+(\\w+)", Pattern.MULTILINE);
            Matcher classMatcher = classPattern.matcher(content);
            if (!classMatcher.find()) {
                return null;
            }

            String className = classMatcher.group(1);
            int classLineNumber = calculateLineNumber(content, classMatcher.start());

            // Create a pseudo analysis result (no prefix for nested classes)
            AnalysisResult result = new AnalysisResult(filePath, "", className, classLineNumber);

            // Find all fields
            Matcher fieldMatcher = FIELD_DECLARATION.matcher(content);
            while (fieldMatcher.find()) {
                String fieldType = fieldMatcher.group(1).trim();
                String fieldName = fieldMatcher.group(2);
                int lineNumber = calculateLineNumber(content, fieldMatcher.start());

                FieldInfo fieldInfo = new FieldInfo(fieldName, fieldType, lineNumber);

                // Check mappings for indexed properties like identity.adapters[0].appId
                // We need to find mappings that end with the field name
                for (Map.Entry<String, String> mapping : mappings.entrySet()) {
                    String oldKey = mapping.getKey();
                    String newKey = mapping.getValue();

                    // Extract field name from indexed property (e.g., adapters[0].appId -> appId)
                    String extractedFieldName = extractFieldNameFromIndexedProperty(oldKey);

                    if (extractedFieldName != null && extractedFieldName.equals(fieldName)) {
                        // Extract the new field name from the new property
                        String newFieldNameExtracted = extractFieldNameFromIndexedProperty(newKey);

                        if (newFieldNameExtracted != null && !newFieldNameExtracted.equals(fieldName)) {
                            fieldInfo.newFieldName = newFieldNameExtracted;
                            fieldInfo.needsRename = true;
                            fieldInfo.fullPropertyPath = oldKey;
                        }
                    }
                }

                result.fields.add(fieldInfo);
            }

            return result;

        } catch (IOException e) {
            System.err.println("Error analyzing nested config class: " + filePath + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * Extract field name from indexed property notation.
     * E.g., "identity.adapters[0].appId" -> "appId"
     *       "identity.adapters[0].baseUrl" -> "baseUrl"
     */
    private String extractFieldNameFromIndexedProperty(String propertyKey) {
        if (propertyKey == null) {
            return null;
        }

        // Check if it contains array index notation
        Pattern indexedPattern = Pattern.compile("\\[\\d+\\]\\.([\\w-]+)$");
        Matcher matcher = indexedPattern.matcher(propertyKey);

        if (matcher.find()) {
            String fieldPart = matcher.group(1);
            // Convert kebab-case to camelCase
            return FieldInfo.toFieldName(fieldPart);
        }

        return null;
    }

    /**
     * Determine the new prefix by comparing old and new property structures.
     *
     * Logic:
     * 1. Split both properties by '.'
     * 2. If the number of parts match, extract the same number of parts as the old prefix from the new property
     * 3. If the number of parts differ:
     *    - Remove the old prefix from the old property
     *    - Count the remaining parts in the old property (after prefix removal)
     *    - Remove that many parts from the end of the new property
     *    - The remaining parts form the new prefix
     *
     * Example: identity.enabled -> identity.provider.enabled
     *   - Old prefix: "identity" (1 part)
     *   - Old property: "identity.enabled" (2 parts)
     *   - After removing prefix: "enabled" (1 part remaining)
     *   - New property: "identity.provider.enabled" (3 parts)
     *   - Remove 1 part from end: "identity.provider" (new prefix)
     */
    private String determineNewPrefix(String oldProperty, String newProperty, String oldPrefix) {
        String[] oldParts = oldProperty.split("\\.");
        String[] newParts = newProperty.split("\\.");
        String[] oldPrefixParts = oldPrefix.split("\\.");

        int oldPrefixPartsCount = oldPrefixParts.length;
        int oldPartsCount = oldParts.length;
        int newPartsCount = newParts.length;

        // Case 1: If the number of parts match, extract same number of parts as old prefix
        if (oldPartsCount == newPartsCount) {
            StringBuilder newPrefix = new StringBuilder();
            for (int i = 0; i < oldPrefixPartsCount && i < newPartsCount; i++) {
                if (i > 0) {
                    newPrefix.append(".");
                }
                newPrefix.append(newParts[i]);
            }
            return newPrefix.toString();
        }

        // Case 2: Number of parts differ
        // Count remaining parts after removing old prefix
        int remainingPartsAfterPrefix = oldPartsCount - oldPrefixPartsCount;

        // Calculate new prefix parts count
        int newPrefixPartsCount = newPartsCount - remainingPartsAfterPrefix;

        // Build the new prefix
        if (newPrefixPartsCount > 0 && newPrefixPartsCount <= newPartsCount) {
            StringBuilder newPrefix = new StringBuilder();
            for (int i = 0; i < newPrefixPartsCount; i++) {
                if (i > 0) {
                    newPrefix.append(".");
                }
                newPrefix.append(newParts[i]);
            }
            return newPrefix.toString();
        }

        // Fallback: return the first part of the new property
        return newParts[0];
    }

    /**
     * Apply changes to the file based on analysis results
     */
    public void applyChanges(AnalysisResult analysis) {
        if (analysis == null) {
            return;
        }

        try {
            File file = new File(analysis.filePath);
            String content = readFileContent(file);
            String modifiedContent = content;

            // 1. Change the prefix if needed
            if (analysis.prefixChanged && analysis.newPrefix != null) {
                String prefixPattern = "@ConfigurationProperties\\s*\\(\\s*prefix\\s*=\\s*\"" +
                                      Pattern.quote(analysis.prefix) + "\"\\s*\\)";
                String prefixReplacement = "@ConfigurationProperties(prefix = \"" +
                                          analysis.newPrefix + "\")";
                modifiedContent = modifiedContent.replaceAll(prefixPattern, prefixReplacement);
            }

            // 2. Rename fields that need renaming
            for (FieldInfo field : analysis.fields) {
                if (field.needsRename && field.newFieldName != null) {
                    modifiedContent = renameFieldInClass(modifiedContent, field);
                }
            }

            // Write changes if anything was modified
            if (!modifiedContent.equals(content)) {
                writeFileContent(file, modifiedContent);
            }

        } catch (IOException e) {
            System.err.println("Error applying changes to file: " + analysis.filePath + " - " + e.getMessage());
        }
    }

    /**
     * Rename a field throughout the class including getters and setters
     */
    private String renameFieldInClass(String content, FieldInfo field) {
        String result = content;

        // 1. Rename field declaration
        // Match: private Type fieldName;
        String fieldPattern = "\\bprivate\\s+" + Pattern.quote(field.fieldType) +
                            "\\s+" + Pattern.quote(field.fieldName) + "\\s*(?:=.*)?;";
        String fieldReplacement = "private " + field.fieldType + " " + field.newFieldName + ";";
        result = result.replaceAll(fieldPattern, fieldReplacement);

        // 2. Rename field usages (this.fieldName or just fieldName)
        // Use word boundaries to avoid partial matches
        String usagePattern = "\\b(?:this\\.)?(" + Pattern.quote(field.fieldName) + ")\\b";
        result = result.replaceAll(usagePattern, field.newFieldName);

        // 3. Rename getters: getFieldName() -> getNewFieldName()
        String capitalizedOld = capitalize(field.fieldName);
        String capitalizedNew = capitalize(field.newFieldName);

        String getterPattern = "\\bget" + Pattern.quote(capitalizedOld) + "\\s*\\(";
        String getterReplacement = "get" + capitalizedNew + "(";
        result = result.replaceAll(getterPattern, getterReplacement);

        // 4. Rename setters: setFieldName() -> setNewFieldName()
        String setterPattern = "\\bset" + Pattern.quote(capitalizedOld) + "\\s*\\(";
        String setterReplacement = "set" + capitalizedNew + "(";
        result = result.replaceAll(setterPattern, setterReplacement);

        // 5. Handle boolean getters: isFieldName() -> isNewFieldName()
        if (field.fieldType.equals("boolean") || field.fieldType.equals("Boolean")) {
            String boolGetterPattern = "\\bis" + Pattern.quote(capitalizedOld) + "\\s*\\(";
            String boolGetterReplacement = "is" + capitalizedNew + "(";
            result = result.replaceAll(boolGetterPattern, boolGetterReplacement);
        }

        return result;
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
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

    private int findClassEnd(String content, int classStartPos) {
        // For simplicity, we'll find the last closing brace
        // A better approach would be to track brace depth
        int lastBrace = content.lastIndexOf('}');
        return lastBrace > classStartPos ? lastBrace : content.length();
    }

    private List<String> extractMainClassFields(String classBody) {
        List<String> fields = new ArrayList<>();
        String[] lines = classBody.split("\n");

        int braceDepth = 0;
        boolean inNestedClass = false;

        for (String line : lines) {
            String trimmed = line.trim();

            // Track nested class/interface declarations
            if (trimmed.matches(".*\\b(class|interface|enum)\\s+\\w+.*")) {
                inNestedClass = true;
                braceDepth = 0;
            }

            // Count braces to track when we exit nested classes
            for (char c : line.toCharArray()) {
                if (c == '{') braceDepth++;
                if (c == '}') {
                    braceDepth--;
                    if (inNestedClass && braceDepth <= 0) {
                        inNestedClass = false;
                    }
                }
            }

            // Only capture field declarations when not in a nested class
            if (!inNestedClass && trimmed.matches("^private\\s+[\\w<>\\[\\],\\s]+\\s+\\w+\\s*(?:=.*)?;.*")) {
                fields.add(line);
            }
        }

        return fields;
    }

    private String readFileContent(File file) throws IOException {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
        }

        // Remove trailing newline if added
        if (content.length() > 0 && content.charAt(content.length() - 1) == '\n') {
            content.setLength(content.length() - 1);
        }

        return content.toString();
    }

    private void writeFileContent(File file, String content) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(content);
        }
    }
}

