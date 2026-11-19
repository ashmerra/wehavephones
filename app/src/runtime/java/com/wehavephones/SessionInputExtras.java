package com.wehavephones;

import com.wehavephones.inputcontrols.ControlsProfile;

/** Flavor hook for session input; the winlator flavor adds nothing. */
public final class SessionInputExtras {
    private SessionInputExtras() {}

    public static void onSessionCreated(XServerDisplayActivity activity) {}

    public static void onSessionResumed(XServerDisplayActivity activity) {}

    public static void onTextFocus(XServerDisplayActivity activity, String boxName) {}

    public static void onGameTextField(XServerDisplayActivity activity) {}

    public static void onControlsChosen(XServerDisplayActivity activity, ControlsProfile previous, boolean previousShown) {}

    public static void onSessionDestroyed(XServerDisplayActivity activity) {}

    /** Controller input that WinHandler consumed. */
    public static void onControllerInput(XServerDisplayActivity activity) {}

    /** True while a session hook uses the controller itself and the guest sees an idle pad. */
    public static boolean holdsGamepad(XServerDisplayActivity activity) {
        return false;
    }
}
