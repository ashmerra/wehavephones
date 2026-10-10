package com.wehavephones.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.text.format.Formatter;
import android.util.Log;

import com.wehavephones.R;
import com.wehavephones.XServerDisplayActivity;
import com.wehavephones.container.Container;
import com.wehavephones.container.ContainerManager;

import java.io.File;

/** A user-started copy that continues while Setup is recreated or the phone screen is off. */
public final class WowImportService extends Service {
    private static final String CHANNEL = "wow-import";
    private static final int NOTIFICATION = 16;
    private static final String CANCEL = "cancel";
    private static volatile boolean cancelRequested;
    private static volatile State state = new State(false, false, 0, 0, "");

    public static final class State {
        public final boolean running, error;
        public final long copied, total;
        public final String message;

        State(boolean running, boolean error, long copied, long total, String message) {
            this.running = running; this.error = error;
            this.copied = copied; this.total = total; this.message = message;
        }

        public int percent() { return total > 0 ? (int)(100.0 * copied / total) : 0; }
    }

    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private long lastProgress;

    public static State state() { return state; }
    public static boolean isRunning() { return state.running; }
    public static void cancel() { cancelRequested = true; }

    public static boolean hasLiveSession() {
        XServerDisplayActivity live = XServerDisplayActivity.getActiveInstance();
        return (live != null && live.isSessionActive()) || OneButtonLauncher.isRunning()
                || RuntimeStageController.isRunning() || ProcessScanner.isWineSessionRunning();
    }

    public static synchronized void start(Context context, Container container) {
        if (isRunning()) return;
        if (container == null || !WeHavePhonesPaths.driveC(container).isDirectory()) {
            state = new State(false, true, 0, 0, context.getString(R.string.wehavephones_import_needs_container));
            return;
        }
        if (hasLiveSession()) {
            state = new State(false, true, 0, 0, context.getString(R.string.wehavephones_import_close_session));
            return;
        }
        cancelRequested = false;
        state = new State(true, false, 0, 0, context.getString(R.string.wehavephones_import_checking));
        try {
            context.startForegroundService(new Intent(context, WowImportService.class).putExtra("container_id", container.id));
        }
        catch (RuntimeException failure) {
            Log.w("WowImport", "Could not start import", failure);
            state = new State(false, true, 0, 0, context.getString(R.string.wehavephones_import_copy_failed));
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && CANCEL.equals(intent.getAction())) {
            cancel();
            if (worker == null) stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (worker != null) return START_NOT_STICKY;
        if (intent == null || !intent.hasExtra("container_id")) { stopSelf(startId); return START_NOT_STICKY; }
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                getString(R.string.wehavephones_import_title), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        startForeground(NOTIFICATION, notification(state));
        PowerManager manager = getSystemService(PowerManager.class);
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wehavephones:wow-import");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
        int containerId = intent.getIntExtra("container_id", 0);
        worker = new Thread(() -> runImport(containerId), "wow-import");
        worker.start();
        return START_NOT_STICKY;
    }

    private void runImport(int containerId) {
        State result;
        try {
            Container container = new ContainerManager(this).getContainerById(containerId);
            if (container == null || !WeHavePhonesPaths.driveC(container).isDirectory()) {
                result = new State(false, true, 0, 0, getString(R.string.wehavephones_import_needs_container));
            }
            else if (hasLiveSession()) {
                result = new State(false, true, 0, 0, getString(R.string.wehavephones_import_close_session));
            }
            else {
                File source = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        WowFileImporter.FOLDER_NAME);
                WowFileImporter.copy(source, WeHavePhonesPaths.wowRoot(container),
                        WeHavePhonesPaths.wowRootFallback(container), this::progress, () -> cancelRequested);
                result = new State(false, false, state.total, state.total,
                        getString(R.string.wehavephones_import_done, container.getName()));
            }
        }
        catch (WowFileImporter.ImportException failure) {
            int message;
            switch (failure.problem) {
                case MISSING_SOURCE: message = R.string.wehavephones_import_missing; break;
                case INVALID_SOURCE: message = R.string.wehavephones_import_invalid; break;
                case ALREADY_INSTALLED: message = R.string.wehavephones_import_existing; break;
                case SOURCE_CHANGED: message = R.string.wehavephones_import_source_changed; break;
                case CANCELLED: message = R.string.wehavephones_import_cancelled; break;
                case NOT_ENOUGH_SPACE: message = R.string.wehavephones_import_no_space; break;
                default: message = R.string.wehavephones_import_copy_failed;
            }
            String text = failure.problem == WowFileImporter.Problem.NOT_ENOUGH_SPACE
                    ? getString(message, Formatter.formatFileSize(this, failure.requiredBytes),
                            Formatter.formatFileSize(this, failure.availableBytes)) : getString(message);
            result = new State(false, failure.problem != WowFileImporter.Problem.CANCELLED, state.copied, state.total, text);
            if (failure.problem == WowFileImporter.Problem.COPY_FAILED) Log.w("WowImport", "Import failed", failure);
        }
        catch (RuntimeException failure) {
            Log.w("WowImport", "Import failed", failure);
            result = new State(false, true, 0, 0, getString(R.string.wehavephones_import_copy_failed));
        }
        final State finished = result;
        new Handler(Looper.getMainLooper()).post(() -> {
            stopForeground(STOP_FOREGROUND_REMOVE);
            releaseWakeLock();
            worker = null;
            // Notification cancellation delivers another start ID for this same job.
            stopSelf();
            state = finished;
        });
    }

    private void progress(long copied, long total) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (copied != 0 && copied != total && now - lastProgress < 500) return;
        lastProgress = now;
        String message = getString(R.string.wehavephones_import_progress,
                Formatter.formatFileSize(this, copied), Formatter.formatFileSize(this, total));
        state = new State(true, false, copied, total, message);
        getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(state));
    }

    private Notification notification(State current) {
        PendingIntent open = PendingIntent.getActivity(this, 16,
                new Intent(this, WeHavePhonesActivity.class).putExtra("show_wow_import", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel = PendingIntent.getService(this, 16,
                new Intent(this, WowImportService.class).setAction(CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_session_notification)
                .setContentTitle(getString(R.string.wehavephones_import_title)).setContentText(current.message)
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
                .setProgress(100, current.percent(), current.total == 0)
                .addAction(new Notification.Action.Builder(null, getString(R.string.cancel), cancel).build()).build();
    }

    @Override public void onDestroy() {
        if (worker != null) { cancel(); worker.interrupt(); }
        releaseWakeLock();
        super.onDestroy();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
