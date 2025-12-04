package org.example;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * JavaParser-based analyzer for @ConfigurationProperties annotated classes.
 * Handles:
 * 1. Prefix changes from CSV mapping
 * 2. Attribute (field) changes
 * 3. Collection changes
 * 4. Changing accessors (getters/setters) of changed attributes throughout the project
 */
public class ConfigurationPropertiesAnalyzer {

    private JavaParser javaParser;

    public ConfigurationPropertiesAnalyzer() {
        this.javaParser = new JavaParser();
    }

    public static class AnalysisResult {
        public String filePath;
        public String prefix;
        public String newPrefix;
        public String className;
        public List<FieldInfo> fields = new ArrayList<>();
        public boolean prefixChanged;
        public int prefixLineNumber;
        public CompilationUnit compilationUnit;
        public ClassOrInterfaceDeclaration classDeclaration;

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
        public boolean isCollection;
        public boolean isMap;
        public String genericType;
        public FieldDeclaration fieldDeclaration;

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
            if (fieldType.contains("List<") || fieldType.contains("Set<") ||
                fieldType.contains("Collection<") || fieldType.endsWith("[]")) {
                this.isCollection = true;
                extractGenericType();
            } else if (fieldType.contains("Map<")) {
                this.isMap = true;
                extractGenericType();
            } else if (!isPrimitiveOrWrapper(fieldType) && !fieldType.equals("String")) {
                this.isNested = true;
            }
        }

        private void extractGenericType() {
            int startIdx = fieldType.indexOf('<');
            int endIdx = fieldType.lastIndexOf('>');
            if (startIdx > 0 && endIdx > startIdx) {
                this.genericType = fieldType.substring(startIdx + 1, endIdx).trim();

                if (this.isMap && genericType.contains(",")) {
                    String[] parts = genericType.split(",");
                    if (parts.length > 1) {
                        this.genericType = parts[1].trim();
                    }
                }
            } else if (fieldType.endsWith("[]")) {
                this.genericType = fieldType.substring(0, fieldType.length() - 2).trim();
            }
        }

        private boolean isPrimitiveOrWrapper(String type) {
            return type.matches("int|long|double|float|boolean|byte|short|char|" +
                              "Integer|Long|Double|Float|Boolean|Byte|Short|Character");
        }

        public String toPropertyName() {
            return fieldName.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();
        }

        public static String toFieldName(String propertyName) {
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
     * Analyzes a file for @ConfigurationProperties annotation and returns analysis result
     */
    public AnalysisResult analyzeFile(String filePath, Map<String, String> mappings) {
        try {
            Path path = Paths.get(filePath);
            ParseResult<CompilationUnit> parseResult = javaParser.parse(path);

            if (!parseResult.isSuccessful() || !parseResult.getResult().isPresent()) {
                System.err.println("Failed to parse file: " + filePath);
                return null;
            }

            CompilationUnit cu = parseResult.getResult().get();
            LexicalPreservingPrinter.setup(cu);

            // Find @ConfigurationProperties annotation on class
            Optional<ClassOrInterfaceDeclaration> classWithAnnotation = cu.findFirst(
                ClassOrInterfaceDeclaration.class,
                cls -> cls.getAnnotationByName("ConfigurationProperties").isPresent()
            );

            if (!classWithAnnotation.isPresent()) {
                // Check for method-level @ConfigurationProperties (e.g., @Bean methods)
                return analyzeMethodLevelConfiguration(filePath, cu, mappings);
            }

            return analyzeClassLevelConfiguration(filePath, cu, classWithAnnotation.get(), mappings);

        } catch (IOException e) {
            System.err.println("Error analyzing file: " + filePath + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * Analyzes class-level @ConfigurationProperties annotation
     */
    private AnalysisResult analyzeClassLevelConfiguration(
            String filePath,
            CompilationUnit cu,
            ClassOrInterfaceDeclaration classDecl,
            Map<String, String> mappings) {

        AnnotationExpr configAnnotation = classDecl.getAnnotationByName("ConfigurationProperties").get();
        String prefix = extractPrefixFromAnnotation(configAnnotation);

        if (prefix == null) {
            System.err.println("Could not extract prefix from @ConfigurationProperties in: " + filePath);
            return null;
        }

        int lineNumber = configAnnotation.getBegin().map(pos -> pos.line).orElse(-1);
        AnalysisResult result = new AnalysisResult(filePath, prefix, classDecl.getNameAsString(), lineNumber);
        result.compilationUnit = cu;
        result.classDeclaration = classDecl;

        // Determine if prefix needs to change
        determineNewPrefix(result, mappings);

        // Analyze fields
        analyzeFields(classDecl, result, mappings);

        return result;
    }

    /**
     * Analyzes method-level @ConfigurationProperties annotation (e.g., @Bean methods)
     */
    private AnalysisResult analyzeMethodLevelConfiguration(
            String filePath,
            CompilationUnit cu,
            Map<String, String> mappings) {

        Optional<MethodDeclaration> methodWithAnnotation = cu.findFirst(
            MethodDeclaration.class,
            method -> method.getAnnotationByName("ConfigurationProperties").isPresent()
        );

        if (!methodWithAnnotation.isPresent()) {
            return null;
        }

        MethodDeclaration method = methodWithAnnotation.get();
        AnnotationExpr configAnnotation = method.getAnnotationByName("ConfigurationProperties").get();
        String prefix = extractPrefixFromAnnotation(configAnnotation);

        if (prefix == null) {
            return null;
        }

        int lineNumber = configAnnotation.getBegin().map(pos -> pos.line).orElse(-1);
        String methodInfo = method.getNameAsString() + " (method-level)";
        AnalysisResult result = new AnalysisResult(filePath, prefix, methodInfo, lineNumber);
        result.compilationUnit = cu;

        // Determine if prefix needs to change
        determineNewPrefix(result, mappings);

        return result;
    }

    /**
     * Analyzes a nested configuration class (without @ConfigurationProperties)
     */
    public AnalysisResult analyzeNestedConfigClass(String filePath, Map<String, String> mappings) {
        try {
            Path path = Paths.get(filePath);
            ParseResult<CompilationUnit> parseResult = javaParser.parse(path);

            if (!parseResult.isSuccessful() || !parseResult.getResult().isPresent()) {
                return null;
            }

            CompilationUnit cu = parseResult.getResult().get();
            LexicalPreservingPrinter.setup(cu);

            Optional<ClassOrInterfaceDeclaration> classDecl = cu.findFirst(ClassOrInterfaceDeclaration.class);
            if (!classDecl.isPresent()) {
                return null;
            }

            ClassOrInterfaceDeclaration cls = classDecl.get();
            String className = cls.getNameAsString();

            // Find matching prefix from mappings
            String matchedPrefix = findMatchingPrefixForNestedClass(className, mappings);
            if (matchedPrefix == null) {
                return null;
            }

            int lineNumber = cls.getBegin().map(pos -> pos.line).orElse(-1);
            AnalysisResult result = new AnalysisResult(filePath, "", className, lineNumber);
            result.compilationUnit = cu;
            result.classDeclaration = cls;

            // Analyze fields for renaming
            analyzeNestedClassFields(cls, matchedPrefix, result, mappings);

            return result;

        } catch (IOException e) {
            System.err.println("Error analyzing nested config class: " + filePath + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * Extracts prefix value from @ConfigurationProperties annotation
     */
    private String extractPrefixFromAnnotation(AnnotationExpr annotation) {
        if (annotation instanceof NormalAnnotationExpr) {
            NormalAnnotationExpr normalAnnotation = (NormalAnnotationExpr) annotation;
            for (MemberValuePair pair : normalAnnotation.getPairs()) {
                if (pair.getNameAsString().equals("prefix")) {
                    Expression value = pair.getValue();
                    if (value instanceof StringLiteralExpr) {
                        return ((StringLiteralExpr) value).asString();
                    }
                }
            }
        } else if (annotation instanceof SingleMemberAnnotationExpr) {
            SingleMemberAnnotationExpr singleAnnotation = (SingleMemberAnnotationExpr) annotation;
            Expression memberValue = singleAnnotation.getMemberValue();
            if (memberValue instanceof StringLiteralExpr) {
                return ((StringLiteralExpr) memberValue).asString();
            }
        }
        return null;
    }

    /**
     * Determines if prefix needs to change based on mappings
     */
    private void determineNewPrefix(AnalysisResult result, Map<String, String> mappings) {
        mappings.entrySet().stream()
            .filter(entry -> !entry.getKey().equalsIgnoreCase(entry.getValue()) &&
                           (entry.getKey().startsWith(result.prefix + ".") ||
                            entry.getKey().startsWith(result.prefix + "[")))
            .findFirst()
            .ifPresent(entry -> {
                result.newPrefix = extractNewPrefix(entry.getKey(), entry.getValue(), result.prefix);
                result.prefixChanged = !result.prefix.equals(result.newPrefix);
            });
    }

    /**
     * Extracts new prefix from property mapping
     */
    private String extractNewPrefix(String oldProperty, String newProperty, String oldPrefix) {
        // Remove array indices before splitting to get clean parts
        String oldPropertyClean = oldProperty.replaceAll("\\[\\d+\\]", "");
        String newPropertyClean = newProperty.replaceAll("\\[\\d+\\]", "");
        String oldPrefixClean = oldPrefix.replaceAll("\\[\\d+\\]", "");

        String[] oldParts = oldPropertyClean.split("\\.");
        String[] newParts = newPropertyClean.split("\\.");
        String[] oldPrefixParts = oldPrefixClean.split("\\.");

        int oldPrefixPartsCount = oldPrefixParts.length;
        int oldPartsCount = oldParts.length;
        int newPartsCount = newParts.length;

        if (oldPartsCount == newPartsCount) {
            StringBuilder newPrefix = new StringBuilder();
            for (int i = 0; i < oldPrefixPartsCount && i < newPartsCount; i++) {
                if (i > 0) newPrefix.append(".");
                newPrefix.append(newParts[i]);
            }
            return newPrefix.toString();
        }

        int remainingPartsAfterPrefix = oldPartsCount - oldPrefixPartsCount;
        int newPrefixPartsCount = newPartsCount - remainingPartsAfterPrefix;

        if (newPrefixPartsCount > 0 && newPrefixPartsCount <= newPartsCount) {
            StringBuilder newPrefix = new StringBuilder();
            for (int i = 0; i < newPrefixPartsCount; i++) {
                if (i > 0) newPrefix.append(".");
                newPrefix.append(newParts[i]);
            }
            return newPrefix.toString();
        }

        return newParts[0];
    }

    /**
     * Analyzes fields in a @ConfigurationProperties class
     */
    private void analyzeFields(ClassOrInterfaceDeclaration classDecl, AnalysisResult result, Map<String, String> mappings) {
        List<FieldDeclaration> fields = classDecl.getFields();

        for (FieldDeclaration field : fields) {
            for (VariableDeclarator variable : field.getVariables()) {
                String fieldName = variable.getNameAsString();
                String fieldType = variable.getType().asString();
                int lineNumber = field.getBegin().map(pos -> pos.line).orElse(-1);

                FieldInfo fieldInfo = new FieldInfo(fieldName, fieldType, lineNumber);
                fieldInfo.fieldDeclaration = field;

                String propertyName = fieldInfo.toPropertyName();
                fieldInfo.fullPropertyPath = result.prefix + "." + propertyName;

                // Check for direct property mapping
                if (mappings.containsKey(fieldInfo.fullPropertyPath)) {
                    String newPropertyPath = mappings.get(fieldInfo.fullPropertyPath);
                    String newPrefix = result.newPrefix != null ? result.newPrefix : result.prefix;

                    if (newPropertyPath.startsWith(newPrefix + ".")) {
                        String newPropertyName = newPropertyPath.substring(newPrefix.length() + 1);
                        fieldInfo.newFieldName = FieldInfo.toFieldName(newPropertyName);
                        fieldInfo.needsRename = !fieldInfo.fieldName.equals(fieldInfo.newFieldName);
                    }
                } else if (fieldInfo.isCollection) {
                    // Check for indexed property mappings
                    checkIndexedPropertyMappings(fieldInfo, result, mappings);
                }

                result.fields.add(fieldInfo);
            }
        }
    }

    /**
     * Analyzes fields in nested configuration class
     */
    private void analyzeNestedClassFields(ClassOrInterfaceDeclaration classDecl, String matchedPrefix,
                                         AnalysisResult result, Map<String, String> mappings) {
        List<FieldDeclaration> fields = classDecl.getFields();

        for (FieldDeclaration field : fields) {
            for (VariableDeclarator variable : field.getVariables()) {
                String fieldName = variable.getNameAsString();
                String fieldType = variable.getType().asString();
                int lineNumber = field.getBegin().map(pos -> pos.line).orElse(-1);

                FieldInfo fieldInfo = new FieldInfo(fieldName, fieldType, lineNumber);
                fieldInfo.fieldDeclaration = field;

                // Check mappings for indexed properties
                for (Map.Entry<String, String> mapping : mappings.entrySet()) {
                    String oldKey = mapping.getKey();
                    String newKey = mapping.getValue();

                    if (!oldKey.startsWith(matchedPrefix + "[")) {
                        continue;
                    }

                    String extractedFieldName = extractFieldNameFromIndexedProperty(oldKey);
                    if (extractedFieldName != null && extractedFieldName.equals(fieldName)) {
                        String newFieldName = extractFieldNameFromIndexedProperty(newKey);
                        if (newFieldName != null && !newFieldName.equals(fieldName)) {
                            fieldInfo.newFieldName = newFieldName;
                            fieldInfo.needsRename = true;
                            fieldInfo.fullPropertyPath = oldKey;
                        }
                        break;
                    }
                }

                result.fields.add(fieldInfo);
            }
        }
    }

    /**
     * Checks for indexed property mappings (e.g., prefix.collection[0].field)
     */
    private void checkIndexedPropertyMappings(FieldInfo fieldInfo, AnalysisResult result, Map<String, String> mappings) {
        String propertyName = fieldInfo.toPropertyName();
        String indexedPattern = result.prefix + "\\." + propertyName + "\\[\\d+\\]\\..*";

        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            if (entry.getKey().matches(indexedPattern)) {
                String oldKey = entry.getKey();
                String newKey = entry.getValue();

                String oldCollectionField = extractCollectionFieldFromIndexedProperty(oldKey, result.prefix);
                String newCollectionField = extractCollectionFieldFromIndexedProperty(newKey,
                    result.newPrefix != null ? result.newPrefix : result.prefix);

                if (oldCollectionField != null && newCollectionField != null &&
                    !oldCollectionField.equals(newCollectionField)) {
                    fieldInfo.newFieldName = FieldInfo.toFieldName(newCollectionField);
                    fieldInfo.needsRename = !fieldInfo.fieldName.equals(fieldInfo.newFieldName);
                    fieldInfo.fullPropertyPath = oldKey;
                }
                break;
            }
        }
    }

    /**
     * Finds matching prefix for nested class based on class name
     */
    private String findMatchingPrefixForNestedClass(String className, Map<String, String> mappings) {
        for (Map.Entry<String, String> mapping : mappings.entrySet()) {
            String oldKey = mapping.getKey();

            if (oldKey.matches(".*\\[\\d+\\]\\.\\w+")) {
                String prefix = oldKey.replaceAll("\\[\\d+\\].*", "");
                String[] parts = prefix.split("\\.");
                String prefixPart = parts[parts.length - 1];

                String normalizedPrefix = prefixPart.replace("-", "").toLowerCase();
                String normalizedClassName = className.toLowerCase();
                String singular = prefixPart.replaceAll("s$", "").replace("-", "").toLowerCase();

                if (normalizedClassName.contains(normalizedPrefix) ||
                    normalizedClassName.contains(singular) ||
                    normalizedPrefix.contains(normalizedClassName.replace("properties", ""))) {
                    return prefix;
                }
            }
        }
        return null;
    }

    /**
     * Extracts field name from indexed property (e.g., prefix[0].fieldName -> fieldName)
     */
    private String extractFieldNameFromIndexedProperty(String propertyKey) {
        if (propertyKey == null) return null;

        int lastBracketClose = propertyKey.lastIndexOf(']');
        if (lastBracketClose >= 0 && lastBracketClose < propertyKey.length() - 1) {
            String afterBracket = propertyKey.substring(lastBracketClose + 1);
            if (afterBracket.startsWith(".")) {
                String fieldPart = afterBracket.substring(1);
                return FieldInfo.toFieldName(fieldPart);
            }
        }
        return null;
    }

    /**
     * Extracts collection field name from indexed property
     */
    private String extractCollectionFieldFromIndexedProperty(String propertyKey, String prefix) {
        if (propertyKey == null || prefix == null) return null;

        String withoutPrefix = propertyKey;
        if (propertyKey.startsWith(prefix + ".")) {
            withoutPrefix = propertyKey.substring(prefix.length() + 1);
        }

        int bracketIndex = withoutPrefix.indexOf('[');
        if (bracketIndex > 0) {
            return withoutPrefix.substring(0, bracketIndex);
        }

        return null;
    }

    /**
     * Applies all changes to the analyzed file
     */
    public void applyChanges(AnalysisResult analysis) {
        if (analysis == null || analysis.compilationUnit == null) {
            return;
        }

        boolean modified = false;

        // 1. Change prefix if needed
        if (analysis.prefixChanged && analysis.newPrefix != null) {
            modified = updatePrefixAnnotation(analysis) || modified;
        }

        // 2. Rename fields
        for (FieldInfo field : analysis.fields) {
            if (field.needsRename && field.newFieldName != null) {
                modified = renameField(analysis, field) || modified;
            }
        }

        // 3. Write changes if modified
        if (modified) {
            try {
                String modifiedCode = LexicalPreservingPrinter.print(analysis.compilationUnit);
                Files.write(Paths.get(analysis.filePath), modifiedCode.getBytes());
            } catch (IOException e) {
                System.err.println("Error writing changes to file: " + analysis.filePath + " - " + e.getMessage());
            }
        }
    }

    /**
     * Updates the prefix in @ConfigurationProperties annotation
     */
    private boolean updatePrefixAnnotation(AnalysisResult analysis) {
        if (analysis.classDeclaration != null) {
            Optional<AnnotationExpr> annotation = analysis.classDeclaration
                .getAnnotationByName("ConfigurationProperties");

            if (annotation.isPresent() && annotation.get() instanceof NormalAnnotationExpr) {
                NormalAnnotationExpr normalAnnotation = (NormalAnnotationExpr) annotation.get();
                for (MemberValuePair pair : normalAnnotation.getPairs()) {
                    if (pair.getNameAsString().equals("prefix")) {
                        pair.setValue(new StringLiteralExpr(analysis.newPrefix));
                        return true;
                    }
                }
            }
        } else {
            // Method-level annotation
            Optional<MethodDeclaration> method = analysis.compilationUnit.findFirst(
                MethodDeclaration.class,
                m -> m.getAnnotationByName("ConfigurationProperties").isPresent()
            );

            if (method.isPresent()) {
                Optional<AnnotationExpr> annotation = method.get()
                    .getAnnotationByName("ConfigurationProperties");

                if (annotation.isPresent() && annotation.get() instanceof NormalAnnotationExpr) {
                    NormalAnnotationExpr normalAnnotation = (NormalAnnotationExpr) annotation.get();
                    for (MemberValuePair pair : normalAnnotation.getPairs()) {
                        if (pair.getNameAsString().equals("prefix")) {
                            pair.setValue(new StringLiteralExpr(analysis.newPrefix));
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Renames a field and its accessors (getters/setters) in the class
     */
    private boolean renameField(AnalysisResult analysis, FieldInfo fieldInfo) {
        if (fieldInfo.fieldDeclaration == null) {
            return false;
        }

        boolean modified = false;

        // Rename the field declaration
        for (VariableDeclarator variable : fieldInfo.fieldDeclaration.getVariables()) {
            if (variable.getNameAsString().equals(fieldInfo.fieldName)) {
                variable.setName(fieldInfo.newFieldName);
                modified = true;
                break;
            }
        }

        // Find and rename getters and setters (and update field references inside them)
        if (analysis.classDeclaration != null) {
            modified = renameAccessorsInClass(analysis.classDeclaration, fieldInfo) || modified;
            modified = renameFieldReferencesInMethods(analysis.classDeclaration, fieldInfo) || modified;
        }

        return modified;
    }

    /**
     * Renames getters and setters for a renamed field within the class
     */
    private boolean renameAccessorsInClass(ClassOrInterfaceDeclaration classDecl, FieldInfo fieldInfo) {
        boolean modified = false;
        String capitalizedOld = capitalize(fieldInfo.fieldName);
        String capitalizedNew = capitalize(fieldInfo.newFieldName);

        List<MethodDeclaration> methods = classDecl.getMethods();

        for (MethodDeclaration method : methods) {
            String methodName = method.getNameAsString();

            // Rename getter
            if (methodName.equals("get" + capitalizedOld)) {
                method.setName("get" + capitalizedNew);
                modified = true;
            }
            // Rename setter
            else if (methodName.equals("set" + capitalizedOld)) {
                method.setName("set" + capitalizedNew);
                modified = true;
            }
            // Rename boolean getter
            else if (methodName.equals("is" + capitalizedOld) &&
                    (fieldInfo.fieldType.equals("boolean") || fieldInfo.fieldType.equals("Boolean"))) {
                method.setName("is" + capitalizedNew);
                modified = true;
            }
        }

        return modified;
    }

    /**
     * Renames field references inside method bodies (including parameters in setters)
     */
    private boolean renameFieldReferencesInMethods(ClassOrInterfaceDeclaration classDecl, FieldInfo fieldInfo) {
        boolean modified = false;

        List<MethodDeclaration> methods = classDecl.getMethods();

        for (MethodDeclaration method : methods) {
            // Find all NameExpr (simple name references like 'fieldName' or 'this.fieldName')
            List<NameExpr> nameExprs = method.findAll(NameExpr.class);

            for (NameExpr nameExpr : nameExprs) {
                if (nameExpr.getNameAsString().equals(fieldInfo.fieldName)) {
                    nameExpr.setName(fieldInfo.newFieldName);
                    modified = true;
                }
            }

            // Also handle FieldAccessExpr (like 'this.fieldName')
            List<FieldAccessExpr> fieldAccesses = method.findAll(FieldAccessExpr.class);

            for (FieldAccessExpr fieldAccess : fieldAccesses) {
                if (fieldAccess.getNameAsString().equals(fieldInfo.fieldName)) {
                    fieldAccess.setName(fieldInfo.newFieldName);
                    modified = true;
                }
            }
        }

        return modified;
    }

    /**
     * Updates getter/setter calls throughout a project for renamed fields
     */
    public List<String> updateAccessorCallsInProject(String projectPath, Map<String, String> fieldRenames) {
        List<String> modifiedFiles = new ArrayList<>();

        try {
            Files.walk(Paths.get(projectPath))
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !path.toString().contains("/target/") &&
                              !path.toString().contains("/build/"))
                .forEach(path -> {
                    if (updateAccessorCallsInFile(path.toString(), fieldRenames, new HashMap<>())) {
                        modifiedFiles.add(path.toString());
                    }
                });
        } catch (IOException e) {
            System.err.println("Error walking project directory: " + e.getMessage());
        }

        return modifiedFiles;
    }

    /**
     * Updates getter/setter calls in a single file
     */
    public boolean updateAccessorCallsInFile(String filePath, Map<String, String> fieldRenames,
                                            Map<String, PropertyRenameRunner.ClassInfo> classInfoMap) {
        try {
            ParseResult<CompilationUnit> parseResult = javaParser.parse(Paths.get(filePath));

            if (!parseResult.isSuccessful() || !parseResult.getResult().isPresent()) {
                return false;
            }

            CompilationUnit cu = parseResult.getResult().get();
            LexicalPreservingPrinter.setup(cu);
            boolean modified = false;

            // Extract the package of the current file
            String currentPackage = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");

            // Extract all imports in the current file
            Set<String> imports = new HashSet<>();
            cu.getImports().forEach(importDecl -> {
                String importPath = importDecl.getNameAsString();
                imports.add(importPath);
                // Also add just the class name for easier lookup
                String className = importPath.substring(importPath.lastIndexOf('.') + 1);
                imports.add(className);
            });

            // Find all method call expressions
            List<MethodCallExpr> methodCalls = cu.findAll(MethodCallExpr.class);

            for (MethodCallExpr methodCall : methodCalls) {
                String methodName = methodCall.getNameAsString();

                // Skip static method calls (e.g., MembershipUtils.getAppId())
                // Static methods are not generated from instance fields
                if (isStaticMethodCall(methodCall)) {
                    continue;
                }

                // Check if this is a getter or setter call for a renamed field
                for (Map.Entry<String, String> rename : fieldRenames.entrySet()) {
                    String[] parts = rename.getKey().split("\\.");
                    if (parts.length != 2) continue;

                    String className = parts[0];
                    String oldFieldName = parts[1];
                    String newFieldName = rename.getValue();

                    // Check if we should update this accessor call
                    if (!shouldUpdateAccessor(className, classInfoMap, currentPackage, imports)) {
                        continue;
                    }

                    String capitalizedOld = capitalize(oldFieldName);
                    String capitalizedNew = capitalize(newFieldName);

                    if (methodName.equals("get" + capitalizedOld)) {
                        methodCall.setName("get" + capitalizedNew);
                        modified = true;
                    } else if (methodName.equals("set" + capitalizedOld)) {
                        methodCall.setName("set" + capitalizedNew);
                        modified = true;
                    } else if (methodName.equals("is" + capitalizedOld)) {
                        methodCall.setName("is" + capitalizedNew);
                        modified = true;
                    }
                }
            }

            if (modified) {
                String modifiedCode = LexicalPreservingPrinter.print(cu);
                Files.write(Paths.get(filePath), modifiedCode.getBytes());
                return true;
            }

        } catch (IOException e) {
            System.err.println("Error updating accessor calls in file: " + filePath + " - " + e.getMessage());
        }

        return false;
    }

    /**
     * Checks if a method call is a static method call.
     * Static method calls are invoked on class names (e.g., MembershipUtils.getAppId())
     * rather than on instance variables (e.g., config.getAppId())
     */
    private boolean isStaticMethodCall(MethodCallExpr methodCall) {
        // Check if the method has a scope (the part before the dot)
        if (!methodCall.getScope().isPresent()) {
            return false; // No scope means it's a local method call
        }

        com.github.javaparser.ast.expr.Expression scope = methodCall.getScope().get();

        // If the scope is a NameExpr, check if it starts with uppercase (likely a class name)
        if (scope instanceof com.github.javaparser.ast.expr.NameExpr) {
            String scopeName = ((com.github.javaparser.ast.expr.NameExpr) scope).getNameAsString();
            // If the name starts with an uppercase letter, it's likely a class name (static call)
            if (!scopeName.isEmpty() && Character.isUpperCase(scopeName.charAt(0))) {
                return true;
            }
        }

        // If the scope is a FieldAccessExpr ending with 'class', it's a static call
        // e.g., SomeClass.class.getMethod()
        if (scope instanceof com.github.javaparser.ast.expr.FieldAccessExpr) {
            com.github.javaparser.ast.expr.FieldAccessExpr fieldAccess =
                (com.github.javaparser.ast.expr.FieldAccessExpr) scope;
            if (fieldAccess.getNameAsString().equals("class")) {
                return true;
            }
        }

        return false;
    }

    /**
     * Determines if we should update accessor calls for the given class.
     * Returns true if:
     * 1. The classInfoMap is empty (backward compatibility - update all)
     * 2. The class is in the same package as the current file, OR
     * 3. The class is explicitly imported in the current file, OR
     * 4. A wildcard import covers the class's package
     */
    private boolean shouldUpdateAccessor(String className, Map<String, PropertyRenameRunner.ClassInfo> classInfoMap,
                                        String currentPackage, Set<String> imports) {
        // If classInfoMap is empty, use old behavior (update all) for backward compatibility
        if (classInfoMap == null || classInfoMap.isEmpty()) {
            return true;
        }

        // Get the class info for the renamed class
        PropertyRenameRunner.ClassInfo classInfo = classInfoMap.get(className);
        if (classInfo == null) {
            // If we don't have class info for this specific class, check if any import suggests it could be this class
            // This handles cases where the class name might not match exactly or is from an external library
            for (String importStr : imports) {
                if (importStr.endsWith("." + className) || importStr.equals(className)) {
                    return true;
                }
            }
            // If still not found, be conservative and don't update
            return false;
        }

        // Check if the class is in the same package
        if (currentPackage.equals(classInfo.packageName)) {
            return true;
        }

        // Check if the class is explicitly imported
        String fullClassName = classInfo.packageName.isEmpty()
            ? className
            : classInfo.packageName + "." + className;

        if (imports.contains(fullClassName) || imports.contains(className)) {
            return true;
        }

        // Check for wildcard imports
        if (!classInfo.packageName.isEmpty()) {
            String wildcardImport = classInfo.packageName + ".*";
            for (String importStr : imports) {
                if (importStr.endsWith(".*") && wildcardImport.equals(importStr)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Capitalizes first letter of a string
     */
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}

