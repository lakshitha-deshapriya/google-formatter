# Google Java Formatter and Configuration Property Scanner

This project provides two utilities for Java projects:

1. **Google Java Format** - Formats Java files according to Google's Java Style Guide
2. **Configuration Property Scanner** - Scans Java files to find all configuration properties being used

## Features

### Property Scanner

The Property Scanner can detect the following patterns in Java files:

- `@Value` annotations
  - Example: `@Value("${server.port}")`
- `@ConfigurationProperty` annotations
  - Example: `@ConfigurationProperty("app.name")`
- `@ConfigurationProperties` annotations (with prefix)
  - Example: `@ConfigurationProperties(prefix = "app.database")`
- `Environment.getProperty()` calls
  - Example: `env.getProperty("db.url")`
- `System.getProperty()` calls
  - Example: `System.getProperty("java.home")`
- `@PropertySource` annotations
  - Example: `@PropertySource("classpath:custom.properties")`
- Property placeholders
  - Example: `${spring.application.name}`

## Building the Project

```bash
mvn clean package
```

## Running the Application

### Option 1: Using Maven

```bash
mvn exec:java -Dexec.mainClass="org.example.App"
```

### Option 2: Using the JAR file

```bash
java -jar target/GoogleFormatter-1.0-SNAPSHOT.jar
```

### Option 3: Using the JAR file with a base directory argument

```bash
java -jar target/GoogleFormatter-1.0-SNAPSHOT.jar /path/to/your/projects/
```

## Usage

When you run the application, you'll be presented with a menu:

```
================================================================================
Java Project Utility
================================================================================
Select an option:
1. Format Java files (Google Java Format)
2. Scan for configuration properties
3. Rename configuration properties (CSV mapping)

Enter your choice (1, 2, or 3):
```

### Using the Formatter (Option 1)

1. Select option `1`
2. Enter the service folder name (relative to the base path)
3. Enter class names to format (comma-separated, or leave empty to format all)

### Using the Property Scanner (Option 2)

1. Select option `2`
2. Enter the service folder name (relative to the base path)
3. The scanner will recursively scan all Java files and report:
   - File path
   - Line number
   - Property key
   - Context (the actual line of code)
   - Pattern type (e.g., @Value annotation, System.getProperty(), etc.)

### Using the Property Renamer (Option 3)

1. Select option `3`
2. Enter the service folder name (relative to the base path)
3. Enter the path to your CSV mapping file
4. The tool will:
   - Scan all Java files for configuration properties
   - Rename properties based on your CSV mapping
   - Create backup files (`.bak`) for all modified files
   - Generate a detailed summary report

**CSV Format:**
```csv
oldPropertyKey,newPropertyKey
app.server.port,application.server.port
db.connection.url,database.connection.url
```

For detailed documentation on the Property Renamer, see [PROPERTY_RENAMING_GUIDE.md](PROPERTY_RENAMING_GUIDE.md).

## Example Output

```
================================================================================
File: /path/to/your/project/src/main/java/com/example/MyService.java
================================================================================
  [Line 25] @Value annotation
    Property: ${server.port}
    Context: @Value("${server.port}")

  [Line 30] Environment.getProperty()
    Property: database.url
    Context: String dbUrl = env.getProperty("database.url");

  [Line 45] @ConfigurationProperties annotation (prefix)
    Property: app.config
    Context: @ConfigurationProperties(prefix = "app.config")
```

## Default Base Directory

The default base directory is set to:
```
/Users/lakshithadeshapriya/Mine/Work/101Digital/Code/
```

You can override this by passing a directory path as a command-line argument.

## Requirements

- Java 8 or higher
- Maven 3.x
- google-java-format (for the formatter feature)

## Notes

- The property scanner skips common build directories: `target`, `build`, `.git`, `node_modules`
- The scanner looks for `.java` files only
- All patterns are detected using regular expressions
- The scanner provides the line number and context for each property found

