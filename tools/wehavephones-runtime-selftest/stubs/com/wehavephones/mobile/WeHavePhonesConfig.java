package com.wehavephones.mobile;

import android.content.Context;

/**
 * Host self-test stub for the preference-backed profile selection.
 */
public final class WeHavePhonesConfig {
    public static volatile String selectedProfile;

    private WeHavePhonesConfig() {}

    public static String getRuntimeProfile(Context context) {
        return selectedProfile;
    }
}
