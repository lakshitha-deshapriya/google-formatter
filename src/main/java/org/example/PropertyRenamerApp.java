package org.example;

public class PropertyRenamerApp {
    public static void main(String[] args) {
        // Check for help flag
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                printUsage();
                return;
            }
        }

        String separator = Util.repeatString("=", 80);

        Util.log(separator);
        Util.log("Configuration Property Renamer");
        Util.log(separator);
        Util.log("");

        PropertyRenameRunner propertyRenameRunner = new PropertyRenameRunner();
        propertyRenameRunner.runPropertyRename(args, true);
    }

    private static void printUsage() {
        Util.log("Usage: java -jar property-renamer.jar [OPTIONS]");
        Util.log("");
        Util.log("Options:");
        Util.log("  --base-folder <path>    Base project folder path");
        Util.log("  --mapping-file <path>   Path to CSV property mapping file");
        Util.log("  --help, -h              Show this help message");
        Util.log("");
        Util.log("Positional arguments (for backward compatibility):");
        Util.log("  java -jar property-renamer.jar [baseFolderPath] [csvMappingPath]");
        Util.log("");
        Util.log("Examples:");
        Util.log("  java -jar property-renamer.jar");
        Util.log("  java -jar property-renamer.jar /path/to/projects");
        Util.log("  java -jar property-renamer.jar /path/to/projects /path/to/mapping.csv");
        Util.log("  java -jar property-renamer.jar --base-folder /path/to/projects");
        Util.log("  java -jar property-renamer.jar --mapping-file /path/to/mapping.csv");
        Util.log("  java -jar property-renamer.jar --base-folder /path/to/projects --mapping-file /path/to/mapping.csv");
    }
}
