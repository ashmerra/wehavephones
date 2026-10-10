package android.util;

import java.util.ArrayList;

public final class Log {
    public static final ArrayList<String> LINES = new ArrayList<>();
    public static boolean echo;

    private Log() {}

    private static int add(String level, String tag, String message) {
        synchronized (LINES) {
            LINES.add(level + "/" + tag + ": " + message);
        }
        if (echo) System.out.println(level + "/" + tag + ": " + message);
        return 0;
    }

    public static int i(String tag, String message) { return add("I", tag, message); }
    public static int w(String tag, String message) { return add("W", tag, message); }
    public static int e(String tag, String message) { return add("E", tag, message); }
    public static int e(String tag, String message, Throwable error) { return add("E", tag, message + " " + error); }
}
