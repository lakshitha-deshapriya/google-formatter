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

    // Pattern for method-level @ConfigurationProperties (e.g., @Bean methods returning collections)
    private static final Pattern METHOD_DECLARATION = Pattern.compile(
        "@ConfigurationProperties\\s*\\(\\s*prefix\\s*=\\s*\"([^\"]+)\"\\s*\\)\\s*(?:.*?\\n)*?\\s*public\\s+(?:List|Set|Collection)<(\\w+)>\\s+(\\w+)\\s*\\(",
        Pattern.DOTALL
    );

    private static final Pattern FIELD_DECLARATION = Pattern.compile(
        "^\\s*(?:@\\w+(?:\\s+@\\w+)*\\s+)?(private|public)\\s+([\\w<>\\[\\],\\s]+)\\s+(\\w+)\\s*(?:=.*)?;",
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

            // First try to find @ConfigurationProperties on a class declaration
            Matcher classMatcher = CLASS_DECLARATION.matcher(content);
            if (classMatcher.find()) {
                return analyzeClassLevelConfiguration(filePath, content, classMatcher, mappings);
            }

            // If not found on class, try to find it on a method (e.g., @Bean method returning List<Type>)
            Matcher methodMatcher = METHOD_DECLARATION.matcher(content);
            if (methodMatcher.find()) {
                return analyzeMethodLevelConfiguration(filePath, content, methodMatcher, mappings);
            }

            return null; // Not a @ConfigurationProperties class or method

        } catch (IOException e) {
            System.err.println("Error analyzing file: " + filePath + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * Analyze class-level @ConfigurationProperties (original behavior)
     */
    private AnalysisResult analyzeClassLevelConfiguration(String filePath, String content,
                                                          Matcher classMatcher, Map<String, String> mappings) {
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
                String fieldType = fieldMatcher.group(2).trim(); // Group 2 is now the type
                String fieldName = fieldMatcher.group(3); // Group 3 is now the field name

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
                    } else if (fieldInfo.isCollection) {
                        // Check for indexed property mappings (e.g., identity.adapters[0].appId)
                        String indexedPropertyPattern = prefix + "\\." + propertyName + "\\[\\d+\\]\\..*";
                        for (Map.Entry<String, String> entry : mappings.entrySet()) {
                            if (entry.getKey().matches(indexedPropertyPattern)) {
                                // Found an indexed property mapping for this collection field
                                String oldKey = entry.getKey();
                                String newKey = entry.getValue();

                                // Extract the collection field name from both keys
                                String oldCollectionField = extractCollectionFieldFromIndexedProperty(oldKey, prefix);
                                String newCollectionField = extractCollectionFieldFromIndexedProperty(newKey,
                                    result.newPrefix != null ? result.newPrefix : prefix);

                                if (oldCollectionField != null && newCollectionField != null &&
                                    !oldCollectionField.equals(newCollectionField)) {
                                    fieldInfo.newFieldName = FieldInfo.toFieldName(newCollectionField);
                                    fieldInfo.needsRename = !fieldInfo.fieldName.equals(fieldInfo.newFieldName);
                                    fieldInfo.fullPropertyPath = oldKey; // Store for reference
                                }
                                break; // Found a mapping for this field
                            }
                        }
                    }

                    result.fields.add(fieldInfo);
                }
            }

            return result;
    }

    private AnalysisResult analyzeMethodLevelConfiguration(String filePath, String content,
                                                           Matcher methodMatcher, Map<String, String> mappings) {
        String prefix = methodMatcher.group(1);
        String elementType = methodMatcher.group(2);  // e.g., "IdentitySettingProperties"
        String methodName = methodMatcher.group(3);
        int prefixLineNumber = calculateLineNumber(content, methodMatcher.start());

        // Create an analysis result for the method-level configuration
        AnalysisResult result = new AnalysisResult(filePath, prefix, elementType + " (method: " + methodName + ")", prefixLineNumber);

        // Check if prefix needs to be changed
        mappings.entrySet().stream()
                .filter(entry -> !entry.getKey().equalsIgnoreCase(entry.getValue()) &&
                               (entry.getKey().startsWith(prefix + ".") ||
                                entry.getKey().startsWith(prefix + "[")))
                .findFirst()
                .ifPresent(entry -> {
                    result.newPrefix = determineNewPrefixForIndexed(
                            entry.getKey(),
                            entry.getValue(),
                            prefix
                    );
                    result.prefixChanged = !prefix.equals(result.newPrefix);
                });

        return result;
    }

    private String determineNewPrefixForIndexed(String oldProperty, String newProperty, String oldPrefix) {
        // Extract prefix before the bracket or dot
        String newPrefixPart;
        if (newProperty.contains("[")) {
            newPrefixPart = newProperty.substring(0, newProperty.indexOf('['));
        } else if (newProperty.contains(".")) {
            newPrefixPart = newProperty.substring(0, newProperty.indexOf('.'));
        } else {
            newPrefixPart = newProperty;
        }
        return newPrefixPart;
    }

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

            String matchedPrefix = null;

            for (Map.Entry<String, String> mapping : mappings.entrySet()) {
                String oldKey = mapping.getKey();

                // Check if this is an indexed property mapping
                if (oldKey.matches(".*\\[\\d+\\]\\.\\w+")) {
                    // Extract the full prefix before [0] including any dots
                    Pattern prefixPattern = Pattern.compile("^(.+?)\\[\\d+\\]\\.");
                    Matcher m = prefixPattern.matcher(oldKey);

                    if (m.find()) {
                        String fullPrefix = m.group(1); // e.g., "identity.adapters" or "identity-settings"

                        // Extract the last part of the prefix (the collection/class identifier)
                        String[] parts = fullPrefix.split("\\.");
                        String prefixPart = parts[parts.length - 1]; // e.g., "adapters" or "identity-settings"

                        String normalizedPrefix = prefixPart.replace("-", "").toLowerCase();
                        String normalizedClassName = className.toLowerCase();

                        String singular = prefixPart.replaceAll("s$", "").replace("-", "").toLowerCase();

                        if (normalizedClassName.contains(normalizedPrefix) ||
                            normalizedClassName.contains(singular) ||
                            normalizedPrefix.contains(normalizedClassName.replace("properties", ""))) {
                            matchedPrefix = fullPrefix;
                            break;
                        }
                    }
                }
            }

            if (matchedPrefix == null) {
                return null;
            }

            // Create a pseudo analysis result (no prefix for nested classes)
            AnalysisResult result = new AnalysisResult(filePath, "", className, classLineNumber);

            // Find all fields
            Matcher fieldMatcher = FIELD_DECLARATION.matcher(content);
            while (fieldMatcher.find()) {
                String fieldType = fieldMatcher.group(2).trim(); // Group 2 is the type
                String fieldName = fieldMatcher.group(3); // Group 3 is the field name
                int lineNumber = calculateLineNumber(content, fieldMatcher.start());

                FieldInfo fieldInfo = new FieldInfo(fieldName, fieldType, lineNumber);

                // Check mappings for indexed properties ONLY for the matched prefix
                for (Map.Entry<String, String> mapping : mappings.entrySet()) {
                    String oldKey = mapping.getKey();
                    String newKey = mapping.getValue();

                    // Only process if this mapping belongs to the same prefix context
                    if (!oldKey.startsWith(matchedPrefix + "[")) {
                        continue;
                    }

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
                        break; // Found the mapping for this field, stop searching
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

    private String extractCollectionFieldFromIndexedProperty(String propertyKey, String prefix) {
        if (propertyKey == null || prefix == null) {
            return null;
        }

        // Remove the prefix and leading dot
        String withoutPrefix = propertyKey;
        if (propertyKey.startsWith(prefix + ".")) {
            withoutPrefix = propertyKey.substring(prefix.length() + 1);
        }

        // Extract the part before [index]
        Pattern collectionPattern = Pattern.compile("^([\\w-]+)\\[\\d+\\]");
        Matcher matcher = collectionPattern.matcher(withoutPrefix);

        if (matcher.find()) {
            return matcher.group(1);
        }

        return null;
    }

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

        int remainingPartsAfterPrefix = oldPartsCount - oldPrefixPartsCount;

        int newPrefixPartsCount = newPartsCount - remainingPartsAfterPrefix;

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

        return newParts[0];
    }

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

    private String renameFieldInClass(String content, FieldInfo field) {
        String result = content;

        // 1. Rename field declaration
        // Match: private Type fieldName; or private Type fieldName = value;
        String fieldPattern = "(\\bprivate\\s+" + Pattern.quote(field.fieldType) +
                            "\\s+)" + Pattern.quote(field.fieldName) + "(\\s*(?:=.*)?;)";
        String fieldReplacement = "$1" + field.newFieldName + "$2";
        result = result.replaceAll(fieldPattern, fieldReplacement);

        // 2. Rename getters and setters (method names only, not parameters)
        String capitalizedOld = capitalize(field.fieldName);
        String capitalizedNew = capitalize(field.newFieldName);

        // Rename getter method: getFieldName( -> getNewFieldName(
        String getterPattern = "\\bget" + Pattern.quote(capitalizedOld) + "(\\s*\\()";
        String getterReplacement = "get" + capitalizedNew + "$1";
        result = result.replaceAll(getterPattern, getterReplacement);

        // Rename setter method: setFieldName( -> setNewFieldName(
        String setterPattern = "\\bset" + Pattern.quote(capitalizedOld) + "(\\s*\\()";
        String setterReplacement = "set" + capitalizedNew + "$1";
        result = result.replaceAll(setterPattern, setterReplacement);

        // Handle boolean getters: isFieldName( -> isNewFieldName(
        if (field.fieldType.equals("boolean") || field.fieldType.equals("Boolean")) {
            String boolGetterPattern = "\\bis" + Pattern.quote(capitalizedOld) + "(\\s*\\()";
            String boolGetterReplacement = "is" + capitalizedNew + "$1";
            result = result.replaceAll(boolGetterPattern, boolGetterReplacement);
        }

        result = renameFieldUsages(result, field.fieldName, field.newFieldName);

        return result;
    }

    /**
     * Rename field usages in the class while avoiding method parameters and local variables.
     * Uses a line-by-line approach to detect and skip parameter/variable declarations.
     */
    private String renameFieldUsages(String content, String oldName, String newName) {
        String[] lines = content.split("\n", -1);
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.matches(".*\\b\\w+\\s+" + Pattern.quote(oldName) + "\\s*[),=].*")) {
                result.append(line);
            } else {
                String pattern = "\\b(?:this\\.)?(" + Pattern.quote(oldName) + ")\\b";
                String replacement = newName;

                line = line.replaceAll(pattern, replacement);
                result.append(line);
            }

            if (i < lines.length - 1) {
                result.append("\n");
            }
        }

        return result.toString();
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
        boolean nestedClassHasConfigProperties = false;
        List<String> recentAnnotations = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();

            // Track annotations (they appear before class/field declarations)
            if (trimmed.startsWith("@")) {
                recentAnnotations.add(trimmed);
            }

            // Track nested class/interface declarations
            if (trimmed.matches(".*\\b(class|interface|enum)\\s+\\w+.*")) {
                inNestedClass = true;
                braceDepth = 0;

                // Check if any recent annotations include @ConfigurationProperties
                nestedClassHasConfigProperties = recentAnnotations.stream()
                    .anyMatch(ann -> ann.contains("@ConfigurationProperties"));

                // Clear annotations after processing class declaration
                recentAnnotations.clear();
            }

            // Count braces to track when we exit nested classes
            for (char c : line.toCharArray()) {
                if (c == '{') braceDepth++;
                if (c == '}') {
                    braceDepth--;
                    if (inNestedClass && braceDepth <= 0) {
                        inNestedClass = false;
                        nestedClassHasConfigProperties = false;
                    }
                }
            }

            boolean isFieldDeclaration = trimmed.matches(".*\\b(private|public)\\s+[\\w<>\\[\\],\\s]+\\s+\\w+\\s*(?:=.*)?;.*");

            if (isFieldDeclaration) {
                if (!inNestedClass || nestedClassHasConfigProperties) {
                    fields.add(line);
                }
                recentAnnotations.clear();
            }

            // Clear annotations on non-annotation, non-field lines (like method declarations)
            if (!trimmed.startsWith("@") &&
                !trimmed.isEmpty() &&
                !isFieldDeclaration &&
                !trimmed.matches(".*\\b(class|interface|enum)\\s+\\w+.*")) {
                recentAnnotations.clear();
            }
        }

        return fields;
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
}

