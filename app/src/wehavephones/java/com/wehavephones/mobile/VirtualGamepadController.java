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

/** Session-scoped touch controls, with immediate USB/Bluetooth controller handoff. */
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
        boolean show = gameRunning && !WeHavePhonesConfig.isVirtualGamepadDisabled(activity)
                && ExternalController.getController(0) == null;
        ControlsProfile current = view.getProfile();
        if (show) {
            if (virtualProfile == null) virtualProfile = findVirtualProfile();
            if (virtualProfile != null && (current != virtualProfile
                    || view.getVisibility() != View.VISIBLE || !view.isShowTouchscreenControls())) {
                view.setShowTouchscreenControls(true);
                activity.showInputControls(virtualProfile);
            }
        } else if (current != null && (current == virtualProfile || current.isVirtualGamepad())) {
            // Release both the guest gamepad and pre-game keyboard bridge before detaching.
            current.getGamepadState().copy(new GamepadState());
            activity.getWinHandler().onVirtualGamepadInput(current.getGamepadState());
            activity.hideInputControls();
            // Recreate elements so old touch pointer IDs, toggles and stick positions cannot survive.
            current.loadElements(view);
        }
        activity.getWinHandler().refreshGamepadController();
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
