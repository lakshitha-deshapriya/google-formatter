package org.example;

import java.io.File;
import java.util.Arrays;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class FormatterRunner {

    public void runFormatter(String[] args) throws InterruptedException {
        String baseProjFolder;
        if (args.length > 0) {
            baseProjFolder = args[0];
        } else {
            baseProjFolder = "/Users/lakshithadeshapriya/Mine/Work/101Digital/Code/";
        }
        Scanner scanner = new Scanner(System.in);

        System.out.print("Service folder name: ");
        String serviceFolder = scanner.nextLine();

        System.out.print("Class names: ");
        String classes = scanner.nextLine();

        Set<String> classNames =
                Arrays.stream(classes.split(","))
                        .filter(name -> name != null && !name.isEmpty())
                        .map(String::trim)
                        .collect(Collectors.toSet());

        String directoryPath = baseProjFolder + serviceFolder + "/";

        File directory = new File(directoryPath);
        if (!directory.exists()) {
            System.out.println("Directory not found: " + directoryPath);
            return;
        }

        ExecutorService executorService = Executors.newFixedThreadPool(25);

        // Call the recursive method to traverse all files in the directory
        traverseDirectory(directory, classNames, serviceFolder, executorService);

        executorService.shutdown();
        executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

        System.out.println("Class format finished...");
    }

    private void traverseDirectory(File directory, Set<String> classNames, String serviceFolder, ExecutorService executorService) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    traverseDirectory(file, classNames, serviceFolder, executorService);
                } else if (file.isFile()
                        && file.getName().endsWith(".java")
                        && !file.getAbsolutePath().contains(serviceFolder + "/target/")) {
                    if (classNames.isEmpty()
                            || classNames.contains(file.getName().replace(".java", ""))) {
                        executorService.submit(
                                () -> new CommandRunner().runCommand(file.getAbsolutePath()));
                    }
                }
            }
        }
    }
}
