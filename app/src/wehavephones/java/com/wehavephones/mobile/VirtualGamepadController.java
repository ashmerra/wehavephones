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
    // Persisted because the app process restarts when a session ends, before diagnostics are read.
    private static final String KEY_STATE = "wehavephones_controls_state";
    private static final String KEY_LAST_CHANGE = "wehavephones_controls_last_change";
    private String sessionState = "";

    public VirtualGamepadController(XServerDisplayActivity activity) {
        this.activity = activity;
        inputManager = (InputManager) activity.getSystemService(Context.INPUT_SERVICE);
        preferences = WeHavePhonesConfig.prefs(activity);
    }

    public boolean isFor(XServerDisplayActivity activity) { return this.activity == activity; }

    public void start() {
        preferences.edit().putString(KEY_LAST_CHANGE, "").apply();
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
        String state = "Mode: " + (manual ? "manual (picked in Input Controls)" : "automatic")
                + "\nOverlay: " + describe(view.getProfile(), view)
                + "\nWoW running: " + (gameRunning ? "yes" : "no") + "\n";
        if (!state.equals(sessionState)) {
            sessionState = state;
            preferences.edit().putString(KEY_STATE, state).apply();
        }
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
        // The view loads elements on first draw, and with them the gamepad flag.
        String kind = !profile.isElementsLoaded() ? ", not drawn yet" : profile.isVirtualGamepad() ? ", gamepad profile" : "";
        return profile.getName() + " (#" + profile.id + kind
                + (view.getVisibility() == View.VISIBLE && view.isShowTouchscreenControls() ? ", visible)" : ", hidden)");
    }

    private void note(String change) {
        String stamped = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " " + change;
        preferences.edit().putString(KEY_LAST_CHANGE, stamped).apply();
        Log.i("VirtualGamepad", change);
    }

    /** Controls state of the current or last session, for diagnostics. */
    public static String describeSession(Context context) {
        SharedPreferences prefs = WeHavePhonesConfig.prefs(context);
        String state = prefs.getString(KEY_STATE, "");
        if (state.isEmpty()) return "No session recorded\n";
        String change = prefs.getString(KEY_LAST_CHANGE, "");
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
