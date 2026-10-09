package android.os;

/** Host stub: uid/pid of the JVM, signals via kill(1). */
public final class Process {
    public static final int SIGNAL_KILL = 9;

    private Process() {}

    public static int myUid() {
        return (int)new com.sun.security.auth.module.UnixSystem().getUid();
    }

    public static int myPid() {
        return (int)ProcessHandle.current().pid();
    }

    public static void killProcess(int pid) {
        sendSignal(pid, 9);
    }

    public static void sendSignal(int pid, int signal) {
        try {
            new ProcessBuilder("kill", "-" + signal, String.valueOf(pid)).start().waitFor();
        }
        catch (Exception ignored) {}
    }
}
