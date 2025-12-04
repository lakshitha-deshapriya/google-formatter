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
                if (line.trim().isEmpty()) {
                    continue;
                }

                if (firstLine) {
                    firstLine = false;
                    String[] headers = splitCSVLine(line);

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
                        Util.logError(
                                "Error: CSV file must contain 'Old Property Key' and 'New Property Key' columns");
                        return mappings;
                    }

                    continue;
                }

                String[] parts = splitCSVLine(line);

                if (parts.length > Math.max(oldPropertyKeyIndex, newPropertyKeyIndex)) {
                    String oldKey = parts[oldPropertyKeyIndex].trim();
                    String newKey = parts[newPropertyKeyIndex].trim();

                    if (!oldKey.isEmpty() && !newKey.isEmpty()) {
                        mappings.put(oldKey, newKey);
                    }
                }
            }
        } catch (IOException e) {
            Util.logError("Error reading CSV file: " + csvFilePath + " - " + e.getMessage());
        }

        return mappings;
    }

    private String[] splitCSVLine(String line) {
        return line.split(",");
    }
}
