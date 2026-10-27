package com.wehavephones;

import android.view.ViewGroup;

import com.wehavephones.inputcontrols.ControlsProfile;
import com.wehavephones.mobile.BattleNetSignInWatcher;
import com.wehavephones.mobile.GlueControllerBridge;
import com.wehavephones.mobile.WowExitWatcher;
import com.wehavephones.mobile.VirtualGamepadController;
import com.wehavephones.mobile.MobileTouchControlsView;

/**
 * WeHavePhones session hooks: the controller bridge for WoW's pre-game screens,
 * the keyboard for Battle.net's sign-in and the return to the menu when the game closes.
 */
public final class SessionInputExtras {
    private static VirtualGamepadController gamepad;
    private static MobileTouchControlsView touch;
    private SessionInputExtras() {}

    public static void onSessionCreated(XServerDisplayActivity activity) {
        if (gamepad != null) gamepad.stop();
        if (touch != null) {
            touch.cancelTouches();
            ViewGroup previousRoot = (ViewGroup) touch.getParent();
            if (previousRoot != null) previousRoot.removeView(touch);
            touch = null;
        }
        gamepad = new VirtualGamepadController(activity);
        // Session creation precedes setupUI. The poller's refresh runs after the input view exists.
        gamepad.setGameRunningListener(running -> {
            if (touch == null) {
                touch = new MobileTouchControlsView(activity);
                ViewGroup root = (ViewGroup) activity.getInputControlsView().getParent();
                root.addView(touch, root.indexOfChild(activity.getInputControlsView()) + 1);
            }
            touch.showForGame(running);
        });
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
            if (touch != null) {
                touch.cancelTouches();
                ViewGroup root = (ViewGroup) touch.getParent();
                if (root != null) root.removeView(touch);
                touch = null;
            }
        }
    }

    /** Controller input that WinHandler consumed. */
    public static void onControllerInput(XServerDisplayActivity activity) {
        GlueControllerBridge.onControllerInput(activity);
    }
}
