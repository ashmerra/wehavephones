package com.wehavephones.core;

import android.content.Context;

public abstract class GPUInformation {
    public static String renderer = "";

    public static String getRenderer(Context context) { return renderer; }

    public static boolean mayBeAdreno(Context context) {
        return renderer.isEmpty() || renderer.toLowerCase(java.util.Locale.ENGLISH).contains("adreno");
    }
}
