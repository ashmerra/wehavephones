package com.wehavephones;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

/**
 * Keeps a session running while its screen is in the background. Without a
 * foreground service Android freezes the app (and the Wine processes it
 * started) or kills it soon after the user switches apps, and online games
 * lose their connection. The partial wake lock and the Wi-Fi lock keep the
 * CPU and the connection up while the screen is off.
 */
public class SessionKeepAliveService extends Service {
    private static final String TAG = "SessionKeepAlive";
    private static final String CHANNEL_ID = "session";
    private static final String EXTRA_CONTENT_INTENT = "content_intent";
    private static final int NOTIFICATION_ID = 1;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    /** Starts the service for the session in this activity; tapping its notification returns to it. */
    public static void start(XServerDisplayActivity activity) {
        Intent returnIntent = new Intent(activity, XServerDisplayActivity.class)
                .putExtras(activity.getIntent())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent contentIntent = PendingIntent.getActivity(activity, 0, returnIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent intent = new Intent(activity, SessionKeepAliveService.class)
                .putExtra(EXTRA_CONTENT_INTENT, contentIntent);
        try {
            activity.startForegroundService(intent);
        }
        catch (RuntimeException e) {
            Log.w(TAG, "Could not start the keep-alive service", e);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, SessionKeepAliveService.class));
    }

    /**
     * Creates the notification channel. On Android 13+ the first creation asks
     * for the notification permission, so call it where that prompt fits.
     */
    public static void createChannel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.session_keep_alive_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel(this);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_session_notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.session_keep_alive_text))
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setShowWhen(false);
        PendingIntent contentIntent = intent != null ? intent.getParcelableExtra(EXTRA_CONTENT_INTENT) : null;
        if (contentIntent != null) builder.setContentIntent(contentIntent);
        startForeground(NOTIFICATION_ID, builder.build());
        acquireLocks();
        return START_NOT_STICKY;
    }

    private void acquireLocks() {
        if (wakeLock == null) {
            PowerManager powerManager = getSystemService(PowerManager.class);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wehavephones:session");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        }
        if (wifiLock == null) {
            WifiManager wifiManager = (WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "wehavephones:session");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        }
    }

    /**
     * Swiping the app away from Recents ends the session and the process, as it
     * did before this service kept the process alive past the removed task.
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        XServerDisplayActivity activity = XServerDisplayActivity.getActiveInstance();
        if (activity != null) activity.endSessionForRemovedTask();
        Runtime.getRuntime().exit(0);
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        wakeLock = null;
        wifiLock = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
