package com.wehavephones.mobile;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import com.wehavephones.R;
import com.wehavephones.XServerDisplayActivity;

/**
 * Returns to the WeHavePhones menu when the game closes: polls for the WoW
 * process during a Wine session and, once {@link WowExitDetector} says it is
 * gone for good, ends the session like the side menu's Exit (Battle.net and
 * Wine are stopped, the app restarts on the menu).
 */
public final class WowExitWatcher {
    private static final String TAG = "WeHavePhonesExitWatcher";
    private static final long POLL_MS = 2_000L;

    private static Thread poller;

    private WowExitWatcher() {}

    /** Session start; the poller stops when the session activity is replaced or finished. */
    public static synchronized void start(XServerDisplayActivity activity) {
        if (poller != null && poller.isAlive()) poller.interrupt();
        final Context context = activity.getApplicationContext();
        poller = new Thread(() -> poll(context, activity), "wehavephones-exit-watcher");
        poller.setDaemon(true);
        poller.start();
    }

    private static void poll(Context context, XServerDisplayActivity activity) {
        WowExitDetector detector = new WowExitDetector();
        WowExitDetector.State last = WowExitDetector.State.WAITING;
        try {
            while (!Thread.currentThread().isInterrupted() && XServerDisplayActivity.getActiveInstance() == activity
                    && !activity.isFinishing() && !activity.isDestroyed()) {
                boolean paused = !activity.isSessionActive() || RuntimeStageController.isRunning();
                boolean running = !paused && ProcessScanner.findWowProcess() != null;
                WowExitDetector.State state = detector.onScan(SystemClock.elapsedRealtime(), running, paused);
                if (state != last) Log.i(TAG, last + " -> " + state);
                if (state == WowExitDetector.State.CLOSING && last != WowExitDetector.State.CLOSING) {
                    activity.runOnUiThread(() -> Toast.makeText(context, R.string.wehavephones_exit_returning, Toast.LENGTH_SHORT).show());
                }
                if (state == WowExitDetector.State.EXIT) {
                    activity.runOnUiThread(activity::exitSession);
                    return;
                }
                last = state;
                Thread.sleep(POLL_MS);
            }
        }
        catch (InterruptedException ignored) {}
        catch (Throwable throwable) {
            Log.w(TAG, "watcher stopped", throwable);
        }
    }
}
