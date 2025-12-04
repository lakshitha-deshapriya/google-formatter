package org.example;

public class PropertyRenamerApp {
    public static void main(String[] args) {
        String separator = Util.repeatString("=", 80);

        Util.log(separator);
        Util.log("Configuration Property Renamer");
        Util.log(separator);
        Util.log("");

        PropertyRenameRunner propertyRenameRunner = new PropertyRenameRunner();
        propertyRenameRunner.runPropertyRename(args, true);
    }
}
