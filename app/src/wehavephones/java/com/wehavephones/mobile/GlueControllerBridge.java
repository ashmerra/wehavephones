package com.wehavephones.mobile;

import android.content.Context;
import android.util.Log;

import com.wehavephones.mobile.addons.required.ConsolePort;
import com.wehavephones.XServerDisplayActivity;
import com.wehavephones.container.Container;
import com.wehavephones.inputcontrols.ExternalController;
import com.wehavephones.inputcontrols.GamepadState;
import com.wehavephones.winhandler.WinHandler;
import com.wehavephones.xserver.XKeycode;
import com.wehavephones.xserver.XServer;

import java.io.File;
import java.io.IOException;

/**
 * Drives {@link GlueInputBridge} for a Wine session: a poller follows the
 * running WoW process and its SavedVariables writes, and controller input
 * from the session injects the mapped keys into the X server while the game
 * is on its pre-game screens. Forever has native controller support and is
 * left alone, like ConsolePort.
 */
public final class GlueControllerBridge {
    private static final String TAG = "WeHavePhonesGlueBridge";
    private static final long POLL_MS = 500;
    private static final String CONSOLEPORT_SAVED_VARIABLES = "SavedVariables/" + ConsolePort.CORE_FOLDER + ".lua";

    private static final GlueInputBridge BRIDGE = new GlueInputBridge();
    private static Thread poller;

    private GlueControllerBridge() {}

    /** Session start; the poller stops when the session activity is replaced or destroyed. */
    public static synchronized void start(XServerDisplayActivity activity) {
        if (poller != null && poller.isAlive()) poller.interrupt();
        final Context context = activity.getApplicationContext();
        poller = new Thread(() -> poll(context, activity), "wehavephones-glue-bridge");
        poller.setDaemon(true);
        poller.start();
    }

    /** Physical or on-screen gamepad input that WinHandler consumed; runs on the UI thread. */
    public static void onControllerInput(XServerDisplayActivity activity) {
        WinHandler winHandler = activity.getWinHandler();
        GamepadState state = winHandler != null ? winHandler.getGamepadState() : null;
        final XServer xServer = activity.getXServer();
        if (state == null || xServer == null) return;
        int buttons = GlueInputBridge.buttons(state.isPressed(ExternalController.IDX_BUTTON_A),
                state.isPressed(ExternalController.IDX_BUTTON_B),
                state.dpad[0], state.dpad[2], state.dpad[3], state.dpad[1]);
        BRIDGE.onControllerState(buttons, new GlueInputBridge.KeySink() {
            @Override
            public void press(int key) {
                xServer.injectKeyPress(keycode(key));
            }

            @Override
            public void release(int key) {
                xServer.injectKeyRelease(keycode(key));
            }
        });
    }

    private static XKeycode keycode(int key) {
        switch (key) {
            case GlueInputBridge.ENTER: return XKeycode.KEY_ENTER;
            case GlueInputBridge.ESCAPE: return XKeycode.KEY_ESC;
            case GlueInputBridge.UP: return XKeycode.KEY_UP;
            case GlueInputBridge.DOWN: return XKeycode.KEY_DOWN;
            case GlueInputBridge.LEFT: return XKeycode.KEY_LEFT;
            default: return XKeycode.KEY_RIGHT;
        }
    }

    private static void poll(Context context, XServerDisplayActivity activity) {
        int pid = -1;
        File gameDir = null;
        try {
            while (!Thread.currentThread().isInterrupted() && XServerDisplayActivity.getActiveInstance() == activity) {
                if (pid != -1 && !new File("/proc/" + pid).exists()) {
                    pid = -1;
                    gameDir = null;
                    BRIDGE.onGameStopped();
                    Log.i(TAG, "game exited");
                }
                if (pid == -1) {
                    ProcessScanner.Entry wow = ProcessScanner.findWowProcess();
                    if (wow != null) {
                        pid = wow.pid;
                        gameDir = bridgedGameDirectory(context, wow.pid);
                        boolean armed = gameDir != null && consolePortActive(gameDir);
                        if (gameDir != null) BRIDGE.onGameStarted(armed, writes(gameDir));
                        Log.i(TAG, "game " + ProcessScanner.program(wow) + ": "
                                + (gameDir == null ? "no bridge" : armed ? "bridge on" : "bridge off (no ConsolePort)"));
                    }
                }
                else if (gameDir != null) {
                    boolean wasActive = BRIDGE.isActive();
                    BRIDGE.onWrites(System.currentTimeMillis(), writes(gameDir));
                    if (wasActive != BRIDGE.isActive()) {
                        Log.i(TAG, BRIDGE.isActive() ? "pre-game screens: bridge on" : "in the world: bridge off");
                    }
                }
                Thread.sleep(POLL_MS);
            }
        }
        catch (InterruptedException ignored) {}
        catch (Throwable throwable) {
            Log.w(TAG, "poller stopped", throwable);
        }
        BRIDGE.onGameStopped();
    }

    private static GlueInputBridge.Writes writes(File gameDir) {
        long global = newestSavedVariables(new File(gameDir, "WTF/SavedVariables"));
        long consolePort = 0;
        long logoutMarkers = new File(gameDir, "Logs/EditMode.log").lastModified();
        File[] accounts = new File(gameDir, "WTF/Account").listFiles();
        if (accounts != null) {
            for (File account : accounts) {
                consolePort = Math.max(consolePort, new File(account, CONSOLEPORT_SAVED_VARIABLES).lastModified());
                logoutMarkers = Math.max(logoutMarkers, new File(account, "tts-cache-account.txt").lastModified());
            }
        }
        return new GlueInputBridge.Writes(global, consolePort, logoutMarkers);
    }

    /** Newest SavedVariables file, ignoring the {@code .bak} copies written while loading. */
    private static long newestSavedVariables(File directory) {
        long newest = 0;
        File[] files = directory.listFiles();
        if (files == null) return 0;
        for (File file : files) {
            if (file.getName().endsWith(".lua")) newest = Math.max(newest, file.lastModified());
        }
        return newest;
    }

    /** The product directory the process runs, or null for Forever and unknown games. */
    private static File bridgedGameDirectory(Context context, int pid) {
        Container container = WeHavePhonesContainers.resolve(context);
        if (container == null) return null;
        // Wine keeps the game's directory as the process cwd; argv0 is only the exe name.
        String name;
        try {
            name = new File("/proc/" + pid + "/cwd").getCanonicalFile().getName();
        }
        catch (IOException e) {
            return null;
        }
        for (WowProduct product : WowInstallationScanner.scan(container)) {
            if (product.directory == null || !product.directoryName.equalsIgnoreCase(name)) continue;
            return ConsolePort.requiredFor(product.getFlavor()) ? product.directory : null;
        }
        return null;
    }

    /** Only controller players need the bridge; ConsolePort's SavedVariables are also a signal. */
    private static boolean consolePortActive(File gameDir) {
        File addons = new File(gameDir, "Interface/AddOns");
        return !ConsolePort.isDisabled(addons) && !ConsolePort.installedVersion(addons).isEmpty();
    }
}
