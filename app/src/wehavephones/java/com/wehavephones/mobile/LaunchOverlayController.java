package com.wehavephones.mobile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.ViewGroup;

import com.wehavephones.R;
import com.wehavephones.XServerDisplayActivity;
import com.wehavephones.container.Container;

import java.io.File;
import java.lang.ref.WeakReference;

/**
 * Owns the launch overlay: Play arms it, the launchers' stage callbacks feed
 * {@link LaunchProgress}, and the game session attaches a
 * {@link LaunchOverlayView} over its surface when it starts (see
 * {@code SessionLaunchExtras}). The overlay ends at WoW's first frame: the
 * D3D device line in {@code Logs/gx.log}, plus a short grace for the login
 * screen.
 * Main thread only, apart from the first-frame watcher.
 */
public final class LaunchOverlayController {
    private static final String TAG = "WeHavePhonesOverlay";
    private static final long FIRST_FRAME_POLL_MS = 1000L;
    /** The login screen draws a few seconds after the device; the overlay leaves just before. */
    private static final long FIRST_FRAME_GRACE_MS = 8_000L;
    /** Never keep covering a running game longer than this. */
    private static final long FIRST_FRAME_CAP_MS = 150_000L;

    /** Selected game as shown on Play: title, build, crest accent and letter. */
    public static final class Game {
        final String title;
        final String version;
        final int accent;
        final String letter;

        public Game(String title, String version, int accent, String letter) {
            this.title = title;
            this.version = version;
            this.accent = accent;
            this.letter = letter;
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile LaunchProgress progress;
    private static Game game;
    private static long startedAt;
    private static long launchWallMs;
    private static volatile int generation;
    private static WeakReference<LaunchOverlayView> view = new WeakReference<>(null);

    private LaunchOverlayController() {}

    public static void setGame(Game selected) {
        game = selected;
    }

    /** Called by Play right before a launcher starts. A live session gets the overlay at once. */
    public static void begin(Context context) {
        generation++;
        progress = new LaunchProgress();
        startedAt = SystemClock.elapsedRealtime();
        launchWallMs = System.currentTimeMillis();
        XServerDisplayActivity session = XServerDisplayActivity.getActiveInstance();
        if (session != null && session.isSessionActive()) {
            progress.onWindowShown();
            ViewGroup root = session.findViewById(R.id.FLXServerDisplay);
            if (root != null) attach(session, root);
        }
    }

    /** True while the overlay covers the game (the launchers' toasts stay quiet meanwhile). */
    public static boolean isCovering() {
        return progress != null && progress.isCovering();
    }

    private static boolean launcherRunning() {
        return OneButtonLauncher.isRunning();
    }

    public static void onOneButtonStage(Context context, OneButtonLauncher.Stage stage, String message, boolean error) {
        if (progress == null) return;
        progress.onOneButtonStage(stage.name(), message, error);
        if (stage == OneButtonLauncher.Stage.RUNNING && progress.isAwaitingFirstFrame()) {
            if (message.equals(context.getString(R.string.wehavephones_launch_already_running))) progress.onFirstFrame();
            else watchFirstFrame(context.getApplicationContext());
        }
        render();
    }

    public static void onOneButtonProgress(double stepProgress) {
        if (progress == null) return;
        progress.onStepProgress(stepProgress);
        render();
    }

    // ---- session hooks (SessionLaunchExtras) --------------------------------------------

    /** The session replaces its "Starting up" dialog with the overlay when Play started it. */
    public static boolean coversSessionStart() {
        return isCovering() && launcherRunning();
    }

    public static void attach(XServerDisplayActivity activity, ViewGroup root) {
        if (!coversSessionStart() && !(isCovering() && activity.isSessionActive())) return;
        LaunchOverlayView existing = view.get();
        if (existing != null && existing.getParent() == root) return;
        if (existing != null) existing.detach();
        LaunchOverlayView overlay = new LaunchOverlayView(activity);
        root.addView(overlay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        view = new WeakReference<>(overlay);
        render();
    }

    /** First application window mapped (any thread). */
    public static void onWindowShown() {
        MAIN.post(() -> {
            if (progress == null) return;
            progress.onWindowShown();
            render();
        });
    }

    public static void onSessionEnded() {
        generation++;
        progress = null;
        final LaunchOverlayView overlay = view.get();
        view = new WeakReference<>(null);
        MAIN.post(() -> { if (overlay != null) overlay.detach(); });
    }

    // ---- overlay buttons ---------------------------------------------------------------

    static void showGame() {
        if (progress == null) return;
        progress.onShowGame();
        render();
    }

    /** Cancel / Back to menu: stop the launch and end the session like the side menu's Exit. */
    static void cancel(XServerDisplayActivity activity) {
        OneButtonLauncher.cancelActive();
        progress = null;
        generation++;
        LaunchOverlayView overlay = view.get();
        if (overlay != null) overlay.detach();
        activity.exitSession();
    }

    // ---- internals ---------------------------------------------------------------------

    private static void render() {
        LaunchOverlayView overlay = view.get();
        if (overlay != null && progress != null) overlay.render(progress, game, startedAt);
    }

    private static void watchFirstFrame(final Context context) {
        final int run = generation;
        final long wall = launchWallMs;
        Thread watcher = new Thread(() -> {
            long deadline = SystemClock.elapsedRealtime() + FIRST_FRAME_CAP_MS;
            File gxLog = gxLog(context);
            while (SystemClock.elapsedRealtime() < deadline && stillWaiting(run)) {
                if (gxLog == null) gxLog = gxLog(context);
                if (gxLog != null && gxLog.lastModified() >= wall - 2000L
                        && WowClientFiles.fileContains(gxLog, WowClientFiles.DEVICE_READY_MARKER, 64 * 1024)) {
                    Log.i(TAG, "WoW device created; showing the game after the grace period");
                    sleep(FIRST_FRAME_GRACE_MS);
                    break;
                }
                sleep(FIRST_FRAME_POLL_MS);
            }
            MAIN.post(() -> {
                if (!stillWaiting(run)) return;
                progress.onFirstFrame();
                render();
            });
        }, "wehavephones-overlay-first-frame");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static boolean stillWaiting(int run) {
        LaunchProgress current = progress;
        return run == generation && current != null && current.isAwaitingFirstFrame();
    }

    private static File gxLog(Context context) {
        Container container = WeHavePhonesContainers.resolve(context);
        WowProduct product = container != null ? WowInstallationScanner.selected(context, container) : null;
        return product != null ? new File(new File(product.directory, "Logs"), "gx.log") : null;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
