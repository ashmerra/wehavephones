package com.wehavephones.mobile.addons;

import android.content.Context;

import com.wehavephones.mobile.WeHavePhonesConfig;

/** UI state for the addon manager that is not part of the addon index. */
public final class AddonPrefs {
    private static final String KEY_SELECTED_PRODUCT = "wehavephones_addon_product";
    private static final String KEY_SELECTED_PROVIDER = "wehavephones_addon_provider";
    private static final String KEY_LAST_UPDATE_ALL = "wehavephones_addon_last_update_all";

    private AddonPrefs() {}

    /** The game selected on Home (shared with Play); the old addon-only key is a fallback. */
    public static String getSelectedProduct(Context context) {
        String selected = WeHavePhonesConfig.getProductDir(context);
        if (selected != null && !selected.isEmpty()) return selected;
        return WeHavePhonesConfig.prefs(context).getString(KEY_SELECTED_PRODUCT, "");
    }

    public static void setSelectedProduct(Context context, String directoryName) {
        WeHavePhonesConfig.setProductDir(context, directoryName);
    }

    public static String getSelectedProvider(Context context) {
        return WeHavePhonesConfig.prefs(context).getString(KEY_SELECTED_PROVIDER, "curseforge");
    }

    public static void setSelectedProvider(Context context, String providerId) {
        WeHavePhonesConfig.prefs(context).edit().putString(KEY_SELECTED_PROVIDER, providerId).apply();
    }

    public static long getLastUpdateAll(Context context) {
        return WeHavePhonesConfig.prefs(context).getLong(KEY_LAST_UPDATE_ALL, 0);
    }

    public static void setLastUpdateAll(Context context, long value) {
        WeHavePhonesConfig.prefs(context).edit().putLong(KEY_LAST_UPDATE_ALL, value).apply();
    }
}
