package com.wehavephones.mobile;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * Samsung's game manager ignores the manifest's appCategory and only treats a package as a game
 * when PackageManager holds a category override, which only the installer of record may set
 * (the Play Store does this for its games). scripts/deploy-wehavephones.sh records this app as its
 * own installer, so it can claim the game category here. That classification is what makes
 * Game Booster, its side panel and game performance profiles apply to WeHavePhones.
 */
final class GameCategoryHint {
    private static final String TAG = "WeHavePhonesGameCategory";

    private GameCategoryHint() {}

    static void apply(Context context) {
        String packageName = context.getPackageName();
        PackageManager packageManager = context.getPackageManager();
        try {
            if (!packageName.equals(packageManager.getInstallerPackageName(packageName))) return;
            packageManager.setApplicationCategoryHint(packageName, ApplicationInfo.CATEGORY_GAME);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not set the game category hint", e);
        }
    }
}
