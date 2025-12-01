package org.example;

import java.util.Scanner;

/**
 * Hello world!
 *
 */
public class App 
{
    public static void main( String[] args ) throws InterruptedException {
        Scanner scanner = new Scanner(System.in);

        String separator = repeatString("=", 80);

        System.out.println(separator);
        System.out.println("Java Project Utility");
        System.out.println(separator);
        System.out.println("Select an option:");
        System.out.println("1. Format Java files (Google Java Format)");
        System.out.println("2. Scan for configuration properties");
        System.out.println("3. Rename configuration properties (CSV mapping)");
        System.out.print("\nEnter your choice (1, 2, or 3): ");

        String choice = scanner.nextLine().trim();

        System.out.println();

        switch (choice) {
            case "1":
                System.out.println("---Starting formatter---");
                Runner runner = new Runner();
                runner.runFormatter(args);
                break;
            case "2":
                System.out.println("---Starting property scanner---");
                PropertyScanRunner propertyScanRunner = new PropertyScanRunner();
                propertyScanRunner.runPropertyScan(args);
                break;
            case "3":
                System.out.println("---Starting property renamer---");
                PropertyRenameRunner propertyRenameRunner = new PropertyRenameRunner();
                propertyRenameRunner.runPropertyRename(args);
                break;
            default:
                System.out.println("Invalid choice. Please run again and select 1, 2, or 3.");
        }
    }

    private static String repeatString(String str, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(str);
        }
        return sb.toString();
    }
}
