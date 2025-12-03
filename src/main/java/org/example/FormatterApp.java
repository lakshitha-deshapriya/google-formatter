package org.example;

public class FormatterApp {
    public static void main(String[] args) throws InterruptedException {
        String separator = repeatString("=", 80);

        System.out.println(separator);
        System.out.println("Google Java Formatter");
        System.out.println(separator);
        System.out.println();

        FormatterRunner formatterRunner = new FormatterRunner();
        formatterRunner.runFormatter(args);
    }

    private static String repeatString(String str, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(str);
        }
        return sb.toString();
    }
}

