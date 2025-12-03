package org.example;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class PropertyMappingReader {

    public Map<String, String> readMappings(String csvFilePath) {
        Map<String, String> mappings = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(csvFilePath))) {
            String line;
            int oldPropertyKeyIndex = -1;
            int newPropertyKeyIndex = -1;
            boolean firstLine = true;

            while ((line = reader.readLine()) != null) {
                // Skip empty lines
                if (line.trim().isEmpty()) {
                    continue;
                }

                // Parse header line to find column indices
                if (firstLine) {
                    firstLine = false;
                    String[] headers = splitCSVLine(line);

                    // Find the indices of "Old Property Key" and "New Property Key" columns
                    for (int i = 0; i < headers.length; i++) {
                        String header = headers[i].trim();
                        if (header.equalsIgnoreCase("Old Property Key")) {
                            oldPropertyKeyIndex = i;
                        } else if (header.equalsIgnoreCase("New Property Key")) {
                            newPropertyKeyIndex = i;
                        }
                    }

                    // Validate that we found both columns
                    if (oldPropertyKeyIndex == -1 || newPropertyKeyIndex == -1) {
                        System.err.println("Error: CSV file must contain 'Old Property Key' and 'New Property Key' columns");
                        System.err.println("Found Old Property Key at index: " + oldPropertyKeyIndex);
                        System.err.println("Found New Property Key at index: " + newPropertyKeyIndex);
                        return mappings;
                    }

                    continue;
                }

                // Parse data line
                String[] parts = splitCSVLine(line);

                // Extract values from the identified columns
                if (parts.length > Math.max(oldPropertyKeyIndex, newPropertyKeyIndex)) {
                    String oldKey = parts[oldPropertyKeyIndex].trim();
                    String newKey = parts[newPropertyKeyIndex].trim();

                    if (!oldKey.isEmpty() && !newKey.isEmpty()) {
                        mappings.put(oldKey, newKey);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading CSV file: " + csvFilePath + " - " + e.getMessage());
        }

        return mappings;
    }

    /**
     * Splits a CSV line handling basic tab-separated values
     */
    private String[] splitCSVLine(String line) {
        return line.split(",");
    }
}

