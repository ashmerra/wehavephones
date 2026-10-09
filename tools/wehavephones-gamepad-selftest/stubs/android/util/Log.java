package android.util;
public class Log {
    public static int i(String tag, String text) { return 0; }
    public static int w(String tag, String text) { return 0; }
    public static int w(String tag, String text, Throwable e) { throw new AssertionError(text, e); }
}
