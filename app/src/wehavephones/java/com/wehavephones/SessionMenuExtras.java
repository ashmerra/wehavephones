package com.wehavephones;

import android.view.Menu;
import android.view.MenuItem;

/** Flavor hook for the session side menu; WeHavePhones adds nothing (it types no credentials). */
public final class SessionMenuExtras {
    private SessionMenuExtras() {}

    public static void onCreateMenu(XServerDisplayActivity activity, Menu menu) {}

    /** Returns true when the item was handled (the drawer then closes). */
    public static boolean onItemSelected(XServerDisplayActivity activity, MenuItem item) {
        return false;
    }
}
