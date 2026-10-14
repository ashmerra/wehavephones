package com.wehavephones;

import android.os.Environment;
import android.util.Log;

import android.view.ViewGroup;

import com.wehavephones.container.Container;
import com.wehavephones.mobile.InputBridgeCompanion;
import com.wehavephones.mobile.LaunchOverlayController;
import com.wehavephones.mobile.WeHavePhonesConfig;
import com.wehavephones.mobile.WeHavePhonesLaunchPlan;
import com.wehavephones.mobile.devices.DeviceProfileSelector;
import com.wehavephones.core.GraphicsPlan;
import com.wehavephones.core.FileUtils;
import com.wehavephones.xenvironment.ImageFs;

import java.io.File;

/**
 * WeHavePhones session start: batch shortcuts (bnet.bat, wow.bat, wcstart.bat)
 * run through wcrun.exe, so Wine does not show a console window before
 * Battle.net or WoW opens. Also decides whether the performance overlay
 * (Settings > Diagnostics) is shown, whether the game keeps running when the
 * user switches apps, and puts Play's launch overlay over the session in place
 * of the "Starting up" dialog. Optionally starts the user's own Input Bridge
 * helper (Settings > Start Input Bridge).
 */
public final class SessionLaunchExtras {
    private static final String ASSET = "wehavephones/wcrun.exe";

    private SessionLaunchExtras() {}

    /** Imports own the container until the complete game folder is published. */
    public static boolean canStartSession(XServerDisplayActivity activity) {
        if (!com.wehavephones.mobile.WowImportService.isRunning()) return true;
        android.widget.Toast.makeText(activity, com.wehavephones.R.string.wehavephones_import_wait,
                android.widget.Toast.LENGTH_LONG).show();
        return false;
    }

    /**
     * Copies wcrun.exe into C:\windows and returns its guest path, or null
     * when it could not be installed (batch files then start normally).
     */
    public static String installHiddenBatchLauncher(XServerDisplayActivity activity, File rootDir) {
        if (rootDir == null) return null;
        try {
            File dst = new File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows/" + WeHavePhonesLaunchPlan.HIDDEN_RUNNER_NAME);
            FileUtils.copy(activity, ASSET, dst);
            if (!dst.isFile()) return null;
            FileUtils.chmod(dst, 0771);
            return WeHavePhonesLaunchPlan.HIDDEN_RUNNER_GUEST;
        }
        catch (Exception e) {
            Log.w("WeHavePhones", "Failed to install " + WeHavePhonesLaunchPlan.HIDDEN_RUNNER_NAME, e);
            return null;
        }
    }

    /**
     * Settings > Start Input Bridge: guest path of the ib.exe that
     * the user copied with the Input Bridge app, or null to start nothing.
     */
    public static String companionProgram(XServerDisplayActivity activity) {
        if (!WeHavePhonesConfig.isStartInputBridge(activity)) return null;
        File exe = InputBridgeCompanion.hostExe(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
        Container container = activity.getContainer();
        String guest = exe.isFile() && container != null
                ? InputBridgeCompanion.guestPath(exe, container.drivesIterator()) : null;
        if (guest == null) Log.i("WeHavePhones", "Input Bridge not started: " + exe + " is missing or not on a drive");
        return guest;
    }

    /** Settings > Diagnostics: FPS, frame time and memory over the game. */
    public static boolean isPerformanceOverlayEnabled(XServerDisplayActivity activity) {
        return WeHavePhonesConfig.isPerformanceOverlay(activity);
    }

    /** Settings > Background: keep the game running (and online) while another app is in front. */
    public static boolean keepRunningInBackground(XServerDisplayActivity activity) {
        return WeHavePhonesConfig.isKeepRunningInBackground(activity);
    }

    /** False when Play's launch overlay covers the start instead of the "Starting up" dialog. */
    public static boolean showStartupPreloader(XServerDisplayActivity activity) {
        return !LaunchOverlayController.coversSessionStart();
    }

    /** The session's views exist: Play's launch overlay goes on top of them. */
    public static void onSessionViewsCreated(XServerDisplayActivity activity, ViewGroup root) {
        LaunchOverlayController.attach(activity, root);
    }

    /** Cancel the launch before stopping Wine so exit cannot look like a launch failure. */
    public static void onSessionEnded(XServerDisplayActivity activity) {
        com.wehavephones.mobile.OneButtonLauncher.cancelActive();
        WeHavePhonesConfig.clearLastStageStatusForSessionExit(activity);
        LaunchOverlayController.onSessionEnded();
    }

    /** The first application window was mapped (any thread). */
    public static void onFirstWindowShown(XServerDisplayActivity activity) {
        LaunchOverlayController.onWindowShown();
    }

    /**
     * Per-device graphics setup from the device rules. Blocks (driver extraction, first-run
     * probe), so call it from the session's background setup thread.
     */
    public static GraphicsPlan graphicsPlan(XServerDisplayActivity activity) {
        return DeviceProfileSelector.select(activity);
    }
}
