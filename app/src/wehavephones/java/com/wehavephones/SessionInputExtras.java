package com.wehavephones;

import com.wehavephones.inputcontrols.ControlsProfile;
import com.wehavephones.mobile.BattleNetSignInWatcher;
import com.wehavephones.mobile.GlueControllerBridge;
import com.wehavephones.mobile.WowExitWatcher;
import com.wehavephones.mobile.VirtualGamepadController;

/**
 * WeHavePhones session hooks: the controller bridge for WoW's pre-game screens,
 * the keyboard for Battle.net's sign-in and the return to the menu when the game closes.
 */
public final class SessionInputExtras {
    private static VirtualGamepadController gamepad;
    private SessionInputExtras() {}

    public static void onSessionCreated(XServerDisplayActivity activity) {
        if (gamepad != null) gamepad.stop();
        gamepad = new VirtualGamepadController(activity);
        gamepad.start();
        GlueControllerBridge.start(activity);
        WowExitWatcher.start(activity);
        BattleNetSignInWatcher.start(activity);
    }

    public static void onSessionResumed(XServerDisplayActivity activity) {
        if (gamepad != null && gamepad.isFor(activity)) gamepad.refresh();
    }

    /** Input Controls was confirmed (or a shortcut applied a profile). Keeps a changed choice. */
    public static void onControlsChosen(XServerDisplayActivity activity, ControlsProfile previous, boolean previousShown) {
        if (gamepad != null && gamepad.isFor(activity)) gamepad.onControlsChosen(previous, previousShown);
    }

    public static void onSessionDestroyed(XServerDisplayActivity activity) {
        if (gamepad != null && gamepad.isFor(activity)) {
            gamepad.stop();
            gamepad = null;
        }
    }

    /** Controller input that WinHandler consumed. */
    public static void onControllerInput(XServerDisplayActivity activity) {
        GlueControllerBridge.onControllerInput(activity);
    }
}
