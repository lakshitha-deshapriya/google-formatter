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
            boolean firstLine = true;

            while ((line = reader.readLine()) != null) {
                // Skip header line
                if (firstLine) {
                    firstLine = false;
                    continue;
                }

                // Skip empty lines
                if (line.trim().isEmpty()) {
                    continue;
                }

                // Parse CSV line
                String[] parts = line.split(",", 2);
                if (parts.length == 2) {
                    String oldKey = parts[0].trim();
                    String newKey = parts[1].trim();

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
}

