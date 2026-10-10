package android.util;

/** Compile-time stub for the host GpuProbe self-test. */
public final class Log {
    private Log() {}

    public static int i(String tag, String msg) {
        System.out.println("I/" + tag + ": " + msg);
        return 0;
    }

    public static int w(String tag, String msg) {
        System.out.println("W/" + tag + ": " + msg);
        return 0;
    }
}
