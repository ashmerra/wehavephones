package com.wehavephones.mobile;

import android.util.Log;
import android.os.Environment;

import com.wehavephones.BuildConfig;
import com.wehavephones.XServerDisplayActivity;
import com.wehavephones.container.Container;
import com.wehavephones.core.FileUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Opens the soft keyboard while Battle.net shows its email or password form.
 * Battle.net's sign-in window also appears during an automatic sign-in, so the
 * window alone is not enough; this follows Battle.net's own log, which says when
 * a credential form finished loading. Only the log is read; nothing is sent to
 * Battle.net.
 */
public final class BattleNetSignInWatcher {
    private static final String TAG = "BattleNetSignInWatcher";
    private static final long POLL_MS = 1_000L;
    /** Moving from the email page to the password page briefly shows no form. */
    private static final long HIDE_AFTER_MS = 2_000L;

    private static Thread poller;

    private BattleNetSignInWatcher() {}

    /** Session start; the poller stops when the session activity is replaced or finished. */
    public static synchronized void start(XServerDisplayActivity activity) {
        if (poller != null && poller.isAlive()) poller.interrupt();
        poller = new Thread(() -> poll(activity), "wehavephones-signin-watcher");
        poller.setDaemon(true);
        poller.start();
    }

    private static void poll(XServerDisplayActivity activity) {
        String newerThan = null;
        BattleNetLauncherLog.Tail tail = null;
        boolean shown = false;
        int loads = 0;
        long goneSince = -1;
        String lastAuthenticationReport = "";
        try {
            while (!Thread.currentThread().isInterrupted() && XServerDisplayActivity.getActiveInstance() == activity
                    && !activity.isFinishing() && !activity.isDestroyed()) {
                Thread.sleep(POLL_MS);
                Container container = activity.getContainer();
                if (container == null) continue;
                File logsDir = WeHavePhonesPaths.bnetLogsDir(container);
                if (newerThan == null) {
                    // Logs from earlier sessions are not this Battle.net.
                    ArrayList<File> logs = BattleNetLauncherLog.listLogs(logsDir);
                    newerThan = logs.isEmpty() ? "" : logs.get(0).getName();
                }
                File main = BattleNetLauncherLog.findMainLog(logsDir, newerThan);
                if (main == null) continue;
                if (tail == null || !tail.file.equals(main)) {
                    tail = new BattleNetLauncherLog.Tail(main);
                    loads = 0;
                }
                tail.poll();
                if (BuildConfig.DEBUG) {
                    String report = "Battle.net authentication diagnostics\n" + main.getName() + "\n"
                            + tail.log.authenticationSummary();
                    if (!report.equals(lastAuthenticationReport)) {
                        File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "WeHavePhones");
                        if ((directory.isDirectory() || directory.mkdirs())
                                && FileUtils.write(new File(directory, "battle-net-auth.txt"), report.getBytes(StandardCharsets.UTF_8))) {
                            lastAuthenticationReport = report;
                        }
                    }
                }

                boolean form = tail.log.loginForm() != BattleNetLauncherLog.LoginForm.NONE;
                int formLoads = tail.log.getLoginFormLoads();
                long now = System.currentTimeMillis();
                if (form && (!shown || formLoads != loads)) {
                    Log.i(TAG, "sign-in form shown: " + tail.log.loginForm());
                    activity.setBattleNetSignInFormShown(true);
                    shown = true;
                }
                loads = formLoads;
                if (form) goneSince = -1;
                else if (shown) {
                    if (goneSince < 0) goneSince = now;
                    else if (now - goneSince >= HIDE_AFTER_MS) {
                        Log.i(TAG, "sign-in form gone");
                        activity.setBattleNetSignInFormShown(false);
                        shown = false;
                        goneSince = -1;
                    }
                }
            }
        }
        catch (InterruptedException ignored) {}
        catch (Throwable throwable) {
            Log.w(TAG, "watcher stopped", throwable);
        }
    }
}
