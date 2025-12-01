package org.example;

import java.io.IOException;
import java.util.List;
import java.util.Scanner;

public class CommandRunner {
    public void runCommand(String filePath) {
        try {
            List<String> arguments = List.of("google-java-format", "--aosp", "--replace", filePath);
            ProcessBuilder processBuilder = new ProcessBuilder(arguments);

            Process process = processBuilder.start();

            Scanner scanner = new Scanner(process.getInputStream());
            while (scanner.hasNextLine()) {
                System.out.println(scanner.nextLine());
            }
            scanner.close();

            process.waitFor();
            System.out.println("Formatted class: " + filePath);
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Error while processing command for filePath: " + filePath);
        }
    }
}
