package com.wehavephones.mobile;

import android.content.ComponentCallbacks2;
import android.content.Context;

import com.wehavephones.XServerDisplayActivity;

/**
 * Bridges the Android trim callbacks (WeHavePhones UI and the guarded
 * {@link XServerDisplayActivity.MemoryTrimListener} hook) to the session
 * renderer. The hook is null until the WeHavePhones UI installs it, so the
 * runtime flavor is unaffected.
 */
public final class WeHavePhonesMemoryTrim {
    private static volatile int trimCount;
    private static volatile long lastTrimMillis;

    private WeHavePhonesMemoryTrim() {}

    public static void install() {
        XServerDisplayActivity.setMemoryTrimListener(WeHavePhonesMemoryTrim::onSessionTrim);
    }

    public static void uninstall() {
        XServerDisplayActivity.setMemoryTrimListener(null);
    }

    static void onSessionTrim(int level, XServerDisplayActivity session) {
        if (session == null) return;
        if (!shouldTrim(session.getApplicationContext(), level)) return;
        session.trimMemoryCaches();
        record();
    }

    /** Called from {@link WeHavePhonesActivity#onTrimMemory(int)}. */
    public static void trimFromUi(WeHavePhonesActivity activity, int level) {
        if (activity == null || !shouldTrim(activity.getApplicationContext(), level)) return;
        XServerDisplayActivity session = XServerDisplayActivity.getActiveInstance();
        if (session != null) session.trimMemoryCaches();
        record();
    }

    public static int getTrimCount() {
        return trimCount;
    }

    public static long getLastTrimMillis() {
        return lastTrimMillis;
    }

    private static boolean shouldTrim(Context context, int level) {
        // UI-hidden alone is not memory pressure; trimming there costs a resume hitch.
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return false;
        return level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW;
    }

    private static void record() {
        trimCount++;
        lastTrimMillis = System.currentTimeMillis();
    }
}
