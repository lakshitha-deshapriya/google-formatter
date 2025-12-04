package org.example;

public class Util {
    private Util() {}

    public static String repeatString(String str, int count) {
        return String.valueOf(str).repeat(Math.max(0, count));
    }

    public static void log(String value) {
        System.out.println(value);
    }

    public static void logInline(String value) {
        System.out.print(value);
    }

    public static void logError(String value) {
        System.err.println(value);
    }
}
