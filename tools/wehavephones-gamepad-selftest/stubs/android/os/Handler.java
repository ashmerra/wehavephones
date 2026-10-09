package android.os;
import java.util.concurrent.ConcurrentLinkedQueue;
public class Handler {
    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    public Handler(Looper looper) {}
    public boolean post(Runnable task) { queue.add(task); return true; }
    public void removeCallbacksAndMessages(Object token) { queue.clear(); }
    public void drain() { Runnable task; while ((task = queue.poll()) != null) task.run(); }
}
