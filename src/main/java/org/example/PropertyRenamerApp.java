package org.example;

public class PropertyRenamerApp {
    public static void main(String[] args) {
        String separator = repeatString("=", 80);

        System.out.println(separator);
        System.out.println("Configuration Property Renamer");
        System.out.println(separator);
        System.out.println();

        PropertyRenameRunner propertyRenameRunner = new PropertyRenameRunner();
        propertyRenameRunner.runPropertyRename(args, true);
    }

    private static String repeatString(String str, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(str);
        }
        return sb.toString();
    }
}

