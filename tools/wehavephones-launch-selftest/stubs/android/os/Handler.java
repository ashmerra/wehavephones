package android.os;

public class Handler {
    public Handler(Looper looper) {}

    public boolean post(Runnable runnable) {
        runnable.run();
        return true;
    }
}
