package org.example;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class ConfigurationPropertiesAnalyzer {

    private final JavaParser javaParser;

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

    public AnalysisResult analyzeFile(String filePath, Map<String, String> mappings) {
        try {
            Path path = Paths.get(filePath);
            ParseResult<CompilationUnit> parseResult = javaParser.parse(path);

            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                Util.logError("Failed to parse file: " + filePath);
                return null;
            }

            CompilationUnit cu = parseResult.getResult().get();
            LexicalPreservingPrinter.setup(cu);

            // Find @ConfigurationProperties annotation on class
            Optional<ClassOrInterfaceDeclaration> classWithAnnotation = cu.findFirst(
                ClassOrInterfaceDeclaration.class,
                cls -> cls.getAnnotationByName("ConfigurationProperties").isPresent()
            );

            if (classWithAnnotation.isEmpty()) {
                return analyzeMethodLevelConfiguration(filePath, cu, mappings);
            }

            return analyzeClassLevelConfiguration(filePath, cu, classWithAnnotation.get(), mappings);

        } catch (IOException e) {
            Util.logError("Error analyzing file: " + filePath + " - " + e.getMessage());
            return null;
        }
    }

    private AnalysisResult analyzeClassLevelConfiguration(
            String filePath,
            CompilationUnit cu,
            ClassOrInterfaceDeclaration classDecl,
            Map<String, String> mappings) {

        AnnotationExpr configAnnotation = classDecl.getAnnotationByName("ConfigurationProperties").get();
        String prefix = extractPrefixFromAnnotation(configAnnotation);

        if (prefix == null) {
            Util.logError("Could not extract prefix from @ConfigurationProperties in: " + filePath);
            return null;
        }

        int lineNumber = configAnnotation.getBegin().map(pos -> pos.line).orElse(-1);
        AnalysisResult result = new AnalysisResult(filePath, prefix, classDecl.getNameAsString(), lineNumber);
        result.compilationUnit = cu;
        result.classDeclaration = classDecl;

        determineNewPrefix(result, mappings);

        analyzeFields(classDecl, result, mappings);

        return result;
    }

    private AnalysisResult analyzeMethodLevelConfiguration(
            String filePath,
            CompilationUnit cu,
            Map<String, String> mappings) {

        Optional<MethodDeclaration> methodWithAnnotation = cu.findFirst(
            MethodDeclaration.class,
            method -> method.getAnnotationByName("ConfigurationProperties").isPresent()
        );

        if (methodWithAnnotation.isEmpty()) {
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

        determineNewPrefix(result, mappings);

        return result;
    }

    public AnalysisResult analyzeNestedConfigClass(String filePath, Map<String, String> mappings) {
        try {
            Path path = Paths.get(filePath);
            ParseResult<CompilationUnit> parseResult = javaParser.parse(path);

            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                return null;
            }

            CompilationUnit cu = parseResult.getResult().get();
            LexicalPreservingPrinter.setup(cu);

            Optional<ClassOrInterfaceDeclaration> classDecl = cu.findFirst(ClassOrInterfaceDeclaration.class);
            if (classDecl.isEmpty()) {
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

            analyzeNestedClassFields(cls, matchedPrefix, result, mappings);

            return result;

        } catch (IOException e) {
            Util.logError("Error analyzing nested config class: " + filePath + " - " + e.getMessage());
            return null;
        }
    }

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

    private String extractNewPrefix(String oldProperty, String newProperty, String oldPrefix) {
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
                Util.logError("Error writing changes to file: " + analysis.filePath + " - " + e.getMessage());
            }
        }
    }

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

    private boolean renameField(AnalysisResult analysis, FieldInfo fieldInfo) {
        if (fieldInfo.fieldDeclaration == null) {
            return false;
        }

        boolean modified = false;
        for (VariableDeclarator variable : fieldInfo.fieldDeclaration.getVariables()) {
            if (variable.getNameAsString().equals(fieldInfo.fieldName)) {
                variable.setName(fieldInfo.newFieldName);
                modified = true;
                break;
            }
        }

        if (analysis.classDeclaration != null) {
            modified = renameAccessorsInClass(analysis.classDeclaration, fieldInfo) || modified;
            modified = renameFieldReferencesInMethods(analysis.classDeclaration, fieldInfo) || modified;
        }

        return modified;
    }

    private boolean renameAccessorsInClass(ClassOrInterfaceDeclaration classDecl, FieldInfo fieldInfo) {
        boolean modified = false;
        String capitalizedOld = capitalize(fieldInfo.fieldName);
        String capitalizedNew = capitalize(fieldInfo.newFieldName);

        List<MethodDeclaration> methods = classDecl.getMethods();

        for (MethodDeclaration method : methods) {
            String methodName = method.getNameAsString();

            if (methodName.equals("get" + capitalizedOld)) {
                method.setName("get" + capitalizedNew);
                modified = true;
            }
            else if (methodName.equals("set" + capitalizedOld)) {
                method.setName("set" + capitalizedNew);
                modified = true;
            }
            else if (methodName.equals("is" + capitalizedOld) &&
                    (fieldInfo.fieldType.equals("boolean") || fieldInfo.fieldType.equals("Boolean"))) {
                method.setName("is" + capitalizedNew);
                modified = true;
            }
        }

        return modified;
    }

    private boolean renameFieldReferencesInMethods(ClassOrInterfaceDeclaration classDecl, FieldInfo fieldInfo) {
        boolean modified = false;

        List<MethodDeclaration> methods = classDecl.getMethods();

        for (MethodDeclaration method : methods) {
            List<NameExpr> nameExprs = method.findAll(NameExpr.class);

            for (NameExpr nameExpr : nameExprs) {
                if (nameExpr.getNameAsString().equals(fieldInfo.fieldName)) {
                    nameExpr.setName(fieldInfo.newFieldName);
                    modified = true;
                }
            }

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

    public boolean updateAccessorCallsInFile(String filePath, Map<String, String> fieldRenames,
                                            Map<String, PropertyRenameRunner.ClassInfo> classInfoMap) {
        try {
            ParseResult<CompilationUnit> parseResult = javaParser.parse(Paths.get(filePath));

            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                return false;
            }

            CompilationUnit cu = parseResult.getResult().get();
            LexicalPreservingPrinter.setup(cu);
            boolean modified = false;

            String currentPackage = cu.getPackageDeclaration()
                .map(NodeWithName::getNameAsString)
                .orElse("");

            Set<String> imports = new HashSet<>();
            cu.getImports().forEach(importDecl -> {
                String importPath = importDecl.getNameAsString();
                imports.add(importPath);
                String className = importPath.substring(importPath.lastIndexOf('.') + 1);
                imports.add(className);
            });

            List<MethodCallExpr> methodCalls = cu.findAll(MethodCallExpr.class);

            for (MethodCallExpr methodCall : methodCalls) {
                String methodName = methodCall.getNameAsString();

                // Static methods are not generated from instance fields
                if (isStaticMethodCall(methodCall)) {
                    continue;
                }

                for (Map.Entry<String, String> rename : fieldRenames.entrySet()) {
                    String[] parts = rename.getKey().split("\\.");
                    if (parts.length != 2) continue;

                    String className = parts[0];
                    String oldFieldName = parts[1];
                    String newFieldName = rename.getValue();

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
            Util.logError("Error updating accessor calls in file: " + filePath + " - " + e.getMessage());
        }

        return false;
    }

    private boolean isStaticMethodCall(MethodCallExpr methodCall) {
        // Check if the method has a scope (the part before the dot)
        if (methodCall.getScope().isEmpty()) {
            return false;
        }

        com.github.javaparser.ast.expr.Expression scope = methodCall.getScope().get();

        if (scope instanceof com.github.javaparser.ast.expr.NameExpr) {
            String scopeName = ((com.github.javaparser.ast.expr.NameExpr) scope).getNameAsString();
            if (!scopeName.isEmpty() && Character.isUpperCase(scopeName.charAt(0))) {
                return true;
            }
        }

        if (scope instanceof com.github.javaparser.ast.expr.FieldAccessExpr) {
            com.github.javaparser.ast.expr.FieldAccessExpr fieldAccess =
                (com.github.javaparser.ast.expr.FieldAccessExpr) scope;
            return fieldAccess.getNameAsString().equals("class");
        }

        return false;
    }

    private boolean shouldUpdateAccessor(String className, Map<String, PropertyRenameRunner.ClassInfo> classInfoMap,
                                        String currentPackage, Set<String> imports) {
        if (classInfoMap == null || classInfoMap.isEmpty()) {
            return true;
        }

        PropertyRenameRunner.ClassInfo classInfo = classInfoMap.get(className);
        if (classInfo == null) {
            for (String importStr : imports) {
                if (importStr.endsWith("." + className) || importStr.equals(className)) {
                    return true;
                }
            }
            return false;
        }

        if (currentPackage.equals(classInfo.packageName)) {
            return true;
        }

        String fullClassName = classInfo.packageName.isEmpty()
            ? className
            : classInfo.packageName + "." + className;

        if (imports.contains(fullClassName) || imports.contains(className)) {
            return true;
        }

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

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}

