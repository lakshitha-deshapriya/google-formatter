package org.example;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class PropertyReportExporter {

    private final List<PropertyMatch> allMatches = new ArrayList<>();

    public void addMatch(PropertyMatch match) {
        allMatches.add(match);
    }

    public List<PropertyMatch> getAllMatches() {
        return allMatches;
    }

    public void exportToCSV(String outputPath) {
        if (allMatches.isEmpty()) {
            System.out.println("No properties found to export.");
            return;
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {
            // Write CSV header
            writer.write("File Path,Line Number,Property Key,Pattern Type,Context");
            writer.newLine();

            // Write data rows
            for (PropertyMatch match : allMatches) {
                writer.write(escapeCsv(match.filePath));
                writer.write(",");
                writer.write(String.valueOf(match.lineNumber));
                writer.write(",");
                writer.write(escapeCsv(match.propertyKey));
                writer.write(",");
                writer.write(escapeCsv(match.patternType));
                writer.write(",");
                writer.write(escapeCsv(match.context));
                writer.newLine();
            }

            System.out.println("\n" + repeatString("=", 80));
            System.out.println("CSV Report exported successfully!");
            System.out.println("File: " + outputPath);
            System.out.println("Total properties found: " + allMatches.size());
            System.out.println(repeatString("=", 80));

        } catch (IOException e) {
            System.err.println("Error writing CSV file: " + e.getMessage());
        }
    }

    public String generateReportFileName(String baseDirectory) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String sanitizedPath = baseDirectory.replaceAll("[^a-zA-Z0-9]", "_");
        return "property_scan_report_" + sanitizedPath + "_" + timestamp + ".csv";
    }

    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        // Escape quotes and wrap in quotes if contains comma, quote, or newline
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String repeatString(String str, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(str);
        }
        return sb.toString();
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

