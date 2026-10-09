package com.wehavephones;

import android.view.ViewGroup;

import java.io.File;

/** Flavor hook for the session start command; the winlator flavor adds nothing. */
public final class SessionLaunchExtras {
    private SessionLaunchExtras() {}

    /**
     * Guest path of a program that runs a batch file without a console
     * window, or null to start batch files normally.
     */
    public static String installHiddenBatchLauncher(XServerDisplayActivity activity, File rootDir) {
        return null;
    }

    /** Show the performance overlay regardless of the container's Show FPS flag. */
    public static boolean isPerformanceOverlayEnabled(XServerDisplayActivity activity) {
        return false;
    }

    /** Keep the session running while hidden instead of pausing it (upstream pauses). */
    public static boolean keepRunningInBackground(XServerDisplayActivity activity) {
        return false;
    }

    /** Show the "Starting up" dialog while the session starts. */
    public static boolean showStartupPreloader(XServerDisplayActivity activity) {
        return true;
    }

    /** Called once the session's views exist; nothing to add here. */
    public static void onSessionViewsCreated(XServerDisplayActivity activity, ViewGroup root) {}

    public static void onSessionEnded(XServerDisplayActivity activity) {}

    /** Called when the first application window is mapped; nothing to do here. */
    public static void onFirstWindowShown(XServerDisplayActivity activity) {}
}
