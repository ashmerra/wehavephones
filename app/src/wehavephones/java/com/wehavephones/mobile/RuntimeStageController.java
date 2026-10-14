package com.wehavephones.mobile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.wehavephones.R;
import com.wehavephones.XServerDisplayActivity;
import com.wehavephones.container.Container;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class RuntimeStageController {
    public interface Listener {
        void onStageMessage(String message, boolean error);
    }

    private static final long WOW_DETECT_TIMEOUT_MS = 20 * 60 * 1000L;
    private static final long SESSION_STOP_TIMEOUT_MS = 30 * 1000L;
    private static final long POLL_INTERVAL_MS = 2000L;

    private static volatile RuntimeStageController active;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private volatile boolean running;

    private RuntimeStageController(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    public static boolean isRunning() {
        RuntimeStageController controller = active;
        return controller != null && controller.running;
    }

    public static RuntimeStageController getActive() {
        return active;
    }

    public static RuntimeStageController start(Context context, Listener listener) {
        RuntimeStageController existing = active;
        if (existing != null && existing.running) return existing;
        RuntimeStageController controller = new RuntimeStageController(context, listener);
        active = controller;
        controller.start();
        return controller;
    }

    public void cancel() {
        running = false;
    }

    private void start() {
        running = true;
        Thread thread = new Thread(this::runHandoff, "wehavephones-handoff");
        thread.setDaemon(true);
        thread.start();
    }

    private void runHandoff() {
        try {
            post("Applying Battle.net runtime profile (handoff stage 1)", false);
            WeHavePhonesRuntimeApplier.Result bnetApply = WeHavePhonesRuntimeApplier.apply(appContext, WeHavePhonesRuntimeApplier.Stage.BATTLE_NET);
            if (!bnetApply.success) {
                failWith("Battle.net runtime profile failed", bnetApply);
                return;
            }

            WeHavePhonesRuntimeProfiles profiles = WeHavePhonesRuntimeProfiles.loadOrNull(appContext);
            if (profiles == null) {
                post("Bundled runtime profiles could not be read", true);
                return;
            }

            Container container = bnetApply.container;
            File bnetShortcut = WeHavePhonesShortcuts.ensureBnetShortcut(container);
            if (bnetShortcut == null) {
                post("Could not create the Battle.net shortcut", true);
                return;
            }

            post("Starting Battle.net. Sign in and press Play for World of Warcraft", false);
            if (!launchOnMain(container, bnetShortcut)) {
                post("Could not start the Battle.net session", true);
                return;
            }

            post("Waiting for Wow-ARM64.exe / Wow.exe to spawn", false);
            ProcessScanner.Entry wowProcess = waitForWowProcess();
            if (wowProcess == null) {
                post("Timed out waiting for the WoW process. Press Play in Battle.net and retry", true);
                return;
            }

            post("WoW process " + wowProcess.pid + " detected. Switching to the WoW runtime", false);
            stopSession();

            WeHavePhonesRuntimeApplier.Result wowApply = WeHavePhonesRuntimeApplier.apply(appContext, WeHavePhonesRuntimeApplier.Stage.WOW);
            if (!wowApply.success) {
                failWith("WoW runtime profile failed", wowApply);
                return;
            }
            container = wowApply.container;
            File wowShortcut = WeHavePhonesShortcuts.ensureWowShortcut(container);
            if (wowShortcut == null) {
                post("Could not create the World of Warcraft shortcut", true);
                return;
            }

            post("Launching World of Warcraft on the WoW runtime", false);
            if (!launchOnMain(container, wowShortcut)) {
                post("Could not start the World of Warcraft session", true);
                return;
            }
            post("Handoff complete: World of Warcraft launched", false);
            startLauncherReaper();
        }
        catch (Throwable throwable) {
            post("Handoff failed: " + throwable, true);
        }
        finally {
            running = false;
            active = null;
        }
    }

    private ProcessScanner.Entry waitForWowProcess() {
        long deadline = SystemClock.elapsedRealtime() + WOW_DETECT_TIMEOUT_MS;
        while (running && SystemClock.elapsedRealtime() < deadline) {
            ProcessScanner.Entry entry = ProcessScanner.findWowProcess();
            if (entry != null) return entry;
            sleep(POLL_INTERVAL_MS);
        }
        return null;
    }

    /**
     * Launcher close. In handoff mode
     * Battle.net is already stopped by the session switch, but this also reaps
     * any Agent/CEF process that re-spawns while WoW is running. The reaper refuses to kill anything until
     * a WoW process exists.
     */
    private void startLauncherReaper() {
        WeHavePhonesLauncherReaper.start(appContext, new WeHavePhonesLauncherReaper.Listener() {
            @Override
            public void onLauncherClosed(int count) {
                post(appContext.getString(R.string.wehavephones_memory_launcher_closed, count), false);
            }

            @Override
            public void onLauncherCloseSkipped(String message) {}
        });
    }

    private void stopSession() {
        XServerDisplayActivity session = XServerDisplayActivity.getActiveInstance();
        if (session != null) {
            mainHandler.post(session::requestSessionShutdown);
            long deadline = SystemClock.elapsedRealtime() + SESSION_STOP_TIMEOUT_MS;
            while (running && SystemClock.elapsedRealtime() < deadline) {
                XServerDisplayActivity current = XServerDisplayActivity.getActiveInstance();
                if (current == null || !current.isSessionActive()) break;
                sleep(500);
            }
        }
        int killed = ProcessScanner.killWineProcesses();
        if (killed > 0) post("Stopped " + killed + " leftover Wine process(es)", false);
        sleep(1500);
    }

    private boolean launchOnMain(final Container container, final File shortcut) {
        final boolean[] launched = {false};
        final CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                launched[0] = WeHavePhonesSessionLauncher.launch(appContext, container, shortcut);
            }
            finally {
                latch.countDown();
            }
        });
        try {
            latch.await(10, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return launched[0];
    }

    private void failWith(String message, WeHavePhonesRuntimeApplier.Result result) {
        post(message + ": " + String.join("; ", result.errors), true);
    }

    private void post(final String message, final boolean error) {
        if (listener == null) return;
        mainHandler.post(() -> listener.onStageMessage(message, error));
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
