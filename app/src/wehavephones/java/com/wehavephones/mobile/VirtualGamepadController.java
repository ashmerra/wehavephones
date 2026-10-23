package com.wehavephones.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import com.wehavephones.XServerDisplayActivity;
import com.wehavephones.core.FileUtils;
import com.wehavephones.inputcontrols.ControlsProfile;
import com.wehavephones.inputcontrols.ExternalController;
import com.wehavephones.inputcontrols.GamepadState;
import com.wehavephones.inputcontrols.InputControlsManager;
import com.wehavephones.widget.InputControlsView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Session-scoped touch controls, with immediate USB/Bluetooth controller handoff.
 * A profile picked in Input Controls switches the session to manual: the user's choice then stays.
 */
public final class VirtualGamepadController implements InputManager.InputDeviceListener,
        SharedPreferences.OnSharedPreferenceChangeListener {
    private final XServerDisplayActivity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final InputManager inputManager;
    private final SharedPreferences preferences;
    private Thread poller;
    private boolean stopped;
    private boolean gameRunning;
    private ControlsProfile virtualProfile;
    private boolean manual;
    private static volatile String sessionState = "";
    private static volatile String lastChange = "";

    public VirtualGamepadController(XServerDisplayActivity activity) {
        this.activity = activity;
        inputManager = (InputManager) activity.getSystemService(Context.INPUT_SERVICE);
        preferences = WeHavePhonesConfig.prefs(activity);
    }

    public boolean isFor(XServerDisplayActivity activity) { return this.activity == activity; }

    public void start() {
        inputManager.registerInputDeviceListener(this, handler);
        preferences.registerOnSharedPreferenceChangeListener(this);
        // Process scanning stays off the UI thread, including while Battle.net launches WoW.
        poller = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    boolean running = ProcessScanner.findWowProcess() != null;
                    handler.post(() -> {
                        gameRunning = running;
                        refresh();
                    });
                    Thread.sleep(500);
                }
            } catch (InterruptedException ignored) {}
        }, "wehavephones-virtual-gamepad");
        poller.setDaemon(true);
        poller.start();
    }

    public void stop() {
        stopped = true;
        inputManager.unregisterInputDeviceListener(this);
        preferences.unregisterOnSharedPreferenceChangeListener(this);
        if (poller != null) poller.interrupt();
        handler.removeCallbacksAndMessages(null);
    }

    public void refresh() {
        InputControlsView view = activity.getInputControlsView();
        if (stopped || activity.isFinishing() || activity.isDestroyed() || view == null) return;
        if (!manual) applyAutomatic(view);
        activity.getWinHandler().refreshGamepadController();
        sessionState = "Mode: " + (manual ? "manual (picked in Input Controls)" : "automatic")
                + "\nOverlay: " + describe(view.getProfile(), view)
                + "\nWoW running: " + (gameRunning ? "yes" : "no") + "\n";
    }

    /** The user confirmed Input Controls. A changed profile or visibility stops automatic show/hide. */
    public void onControlsChosen(ControlsProfile previous, boolean previousShown) {
        InputControlsView view = activity.getInputControlsView();
        if (stopped || view == null) return;
        ControlsProfile current = view.getProfile();
        int previousId = previous != null ? previous.id : 0;
        int currentId = current != null ? current.id : 0;
        if (previousId == currentId && previousShown == view.isShowTouchscreenControls()) return;
        manual = true;
        if (previous != null && previous != current && previous.isVirtualGamepad()) release(previous, view);
        note("picked " + describe(current, view));
        refresh();
    }

    private void applyAutomatic(InputControlsView view) {
        ExternalController physical = ExternalController.getController(0);
        boolean disabled = WeHavePhonesConfig.isVirtualGamepadDisabled(activity);
        boolean show = gameRunning && !disabled && physical == null;
        ControlsProfile current = view.getProfile();
        if (show) {
            // The editor reloads the manager's profiles. Drop our pre-edit object as well.
            if (virtualProfile != null) {
                virtualProfile = activity.getInputControlsManager().getProfile(virtualProfile.id);
            }
            if (virtualProfile == null) virtualProfile = findVirtualProfile();
            if (virtualProfile != null && (current != virtualProfile
                    || view.getVisibility() != View.VISIBLE || !view.isShowTouchscreenControls())) {
                view.setShowTouchscreenControls(true);
                activity.showInputControls(virtualProfile);
                note("showed " + virtualProfile.getName() + " (WoW running)");
            }
        } else if (current != null && (current == virtualProfile || current.isVirtualGamepad())) {
            String reason = !gameRunning ? "WoW not running"
                    : disabled ? "Disable virtual gamepad is on"
                    : "controller connected: " + physical.getName();
            release(current, view);
            activity.hideInputControls();
            note("hid " + current.getName() + " (" + reason + ")");
        }
    }

    private void release(ControlsProfile profile, InputControlsView view) {
        // Release both the guest gamepad and pre-game keyboard bridge before detaching.
        profile.getGamepadState().copy(new GamepadState());
        activity.getWinHandler().onVirtualGamepadInput(profile.getGamepadState());
        // Recreate elements so old touch pointer IDs, toggles and stick positions cannot survive.
        profile.loadElements(view);
    }

    private static String describe(ControlsProfile profile, InputControlsView view) {
        if (profile == null) return "none";
        return profile.getName() + " (#" + profile.id + (profile.isVirtualGamepad() ? ", gamepad profile" : "")
                + (view.getVisibility() == View.VISIBLE && view.isShowTouchscreenControls() ? ", visible)" : ", hidden)");
    }

    private static void note(String change) {
        lastChange = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " " + change;
        Log.i("VirtualGamepad", change);
    }

    /** Controls state of the current or last session in this app process, for diagnostics. */
    public static String describeSession() {
        String state = sessionState;
        if (state.isEmpty()) return "No game session since the app started\n";
        String change = lastChange;
        return state + "Last change: " + (change.isEmpty() ? "none" : change) + "\n";
    }

    private ControlsProfile findVirtualProfile() {
        InputControlsManager manager = activity.getInputControlsManager();
        for (ControlsProfile profile : manager.getProfiles()) {
            if ("Virtual Gamepad".equals(profile.getName())) return profile;
        }
        try {
            // Restore a deleted stock profile under a fresh id, preserving custom profiles.
            return manager.importProfile(new JSONObject(FileUtils.readString(activity,
                    "inputcontrols/profiles/controls-3.icp")));
        } catch (Exception e) {
            Log.w("VirtualGamepad", "Cannot load the bundled virtual gamepad", e);
            return null;
        }
    }

    @Override public void onInputDeviceAdded(int deviceId) { refresh(); }
    @Override public void onInputDeviceRemoved(int deviceId) { refresh(); }
    @Override public void onInputDeviceChanged(int deviceId) { refresh(); }
    @Override public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (WeHavePhonesConfig.KEY_DISABLE_VIRTUAL_GAMEPAD.equals(key)) refresh();
    }
}
