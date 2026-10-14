package com.wehavephones.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

public final class WeHavePhonesConfig {
    public static final String BNET_SETUP_SCREEN_SIZE = "1280x800";
    private static final String KEY_GAME_REGION = "wehavephones_game_region_";
    private static final String KEY_GAME_REGION_OVERRIDE = "wehavephones_game_region_override_";
    private static final String KEY_INITIAL_SETUP_COMPLETE = "wehavephones_initial_setup_complete";
    public static final String KEY_RUNTIME_PROFILE = "wehavephones_runtime_profile";
    public static final String KEY_GAME_SCREEN_SIZE = "wehavephones_game_screen_size";
    public static final String KEY_MEMORY_SNAPSHOT = "wehavephones_memory_snapshot";
    public static final String KEY_CONTAINER_ID = "wehavephones_container_id";
    public static final String KEY_PRODUCT_DIR = "wehavephones_product_dir";
    public static final String KEY_LAST_STAGE_STATUS = "wehavephones_last_stage_status";
    public static final String KEY_PERFORMANCE_OVERLAY = "wehavephones_performance_overlay";
    public static final String KEY_DISABLE_VIRTUAL_GAMEPAD = "wehavephones_disable_virtual_gamepad";
    public static final String KEY_KEEP_RUNNING_IN_BACKGROUND = "wehavephones_keep_running_in_background";
    public static final String KEY_START_INPUT_BRIDGE = "wehavephones_start_input_bridge";

    private WeHavePhonesConfig() {}

    public static String getGameRegion(Context context) {
        String region = prefs(context).getString(KEY_GAME_REGION + getContainerId(context), "us");
        return WowFirstBoot.validRegion(region) ? region : "us";
    }

    public static void setGameRegion(Context context, String region) {
        if (WowFirstBoot.validRegion(region)) prefs(context).edit()
                .putString(KEY_GAME_REGION + getContainerId(context), region).apply();
    }

    /** Explicit Settings changes override the portal already saved by WoW. */
    public static void setGameRegionOverride(Context context, String region) {
        if (!WowFirstBoot.validRegion(region)) return;
        prefs(context).edit().putString(KEY_GAME_REGION + getContainerId(context), region)
                .putBoolean(KEY_GAME_REGION_OVERRIDE + getContainerId(context), true).apply();
    }

    public static boolean isGameRegionOverride(Context context) {
        return prefs(context).getBoolean(KEY_GAME_REGION_OVERRIDE + getContainerId(context), false);
    }

    public static boolean isInitialSetupComplete(Context context) {
        return prefs(context).getBoolean(KEY_INITIAL_SETUP_COMPLETE, false);
    }

    public static void setInitialSetupComplete(Context context) {
        prefs(context).edit().putBoolean(KEY_INITIAL_SETUP_COMPLETE, true).apply();
    }

    public static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static String getRuntimeProfile(Context context) {
        return prefs(context).getString(KEY_RUNTIME_PROFILE, null);
    }

    public static void setRuntimeProfile(Context context, String profile) {
        prefs(context).edit().putString(KEY_RUNTIME_PROFILE, profile).apply();
    }

    /** Game resolution (container screen size), low by default to save memory. */
    public static String getGameScreenSize(Context context) {
        return MemoryTransforms.screenSizeOrDefault(prefs(context).getString(KEY_GAME_SCREEN_SIZE, null));
    }

    public static void setGameScreenSize(Context context, String screenSize) {
        prefs(context).edit().putString(KEY_GAME_SCREEN_SIZE, MemoryTransforms.screenSizeOrDefault(screenSize)).apply();
    }

    public static String getMemorySnapshot(Context context) {
        return prefs(context).getString(KEY_MEMORY_SNAPSHOT, "");
    }

    public static void setMemorySnapshot(Context context, String snapshotName) {
        prefs(context).edit().putString(KEY_MEMORY_SNAPSHOT, snapshotName != null ? snapshotName : "").apply();
    }

    public static int getContainerId(Context context) {
        return prefs(context).getInt(KEY_CONTAINER_ID, 0);
    }

    public static void setContainerId(Context context, int containerId) {
        prefs(context).edit().putInt(KEY_CONTAINER_ID, containerId).apply();
    }

    public static String getProductDir(Context context) {
        return prefs(context).getString(KEY_PRODUCT_DIR, null);
    }

    public static void setProductDir(Context context, String productDir) {
        prefs(context).edit().putString(KEY_PRODUCT_DIR, productDir).apply();
    }

    public static String getLastStageStatus(Context context) {
        return prefs(context).getString(KEY_LAST_STAGE_STATUS, "");
    }

    public static void setLastStageStatus(Context context, String status) {
        prefs(context).edit().putString(KEY_LAST_STAGE_STATUS, status).apply();
    }

    /** Menu Exit terminates the process immediately; flush the cleared status before that. */
    public static void clearLastStageStatusForSessionExit(Context context) {
        prefs(context).edit().remove(KEY_LAST_STAGE_STATUS).commit();
    }

    /** Show FPS, frame time and memory over the game while it runs (default off). */
    public static boolean isPerformanceOverlay(Context context) {
        return prefs(context).getBoolean(KEY_PERFORMANCE_OVERLAY, false);
    }

    public static void setPerformanceOverlay(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_PERFORMANCE_OVERLAY, enabled).apply();
    }

    public static boolean isVirtualGamepadDisabled(Context context) {
        return prefs(context).getBoolean(KEY_DISABLE_VIRTUAL_GAMEPAD, false);
    }

    public static void setVirtualGamepadDisabled(Context context, boolean disabled) {
        prefs(context).edit().putBoolean(KEY_DISABLE_VIRTUAL_GAMEPAD, disabled).apply();
    }

    /**
     * Keep the game running and connected while another app is in front or the
     * screen is off (default on): a foreground service with a notification.
     */
    public static boolean isKeepRunningInBackground(Context context) {
        return prefs(context).getBoolean(KEY_KEEP_RUNNING_IN_BACKGROUND, true);
    }

    public static void setKeepRunningInBackground(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_KEEP_RUNNING_IN_BACKGROUND, enabled).apply();
    }

    /** Start the user's own Input Bridge helper (InputBridgeCompanion) with each session (default off). */
    public static boolean isStartInputBridge(Context context) {
        return prefs(context).getBoolean(KEY_START_INPUT_BRIDGE, false);
    }

    public static void setStartInputBridge(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_START_INPUT_BRIDGE, enabled).apply();
    }
}
