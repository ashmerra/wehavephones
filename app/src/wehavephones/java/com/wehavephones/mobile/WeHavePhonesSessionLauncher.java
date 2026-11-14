package com.wehavephones.mobile;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import com.wehavephones.XServerDisplayActivity;
import com.wehavephones.container.Container;

import java.io.File;

public final class WeHavePhonesSessionLauncher {
    private WeHavePhonesSessionLauncher() {}

    public static Intent buildIntent(Context context, Container container, File shortcutFile) {
        Intent intent = new Intent(context, XServerDisplayActivity.class);
        intent.putExtra("container_id", container.id);
        if (shortcutFile != null) intent.putExtra("shortcut_path", shortcutFile.getPath());
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    public static boolean launch(Context context, Container container, File shortcutFile) {
        if (container == null || WowImportService.isRunning()) return false;
        // Found on the Pixel 8a: leftovers from earlier sessions pile up as
        // phantom processes and crashed Android 17's system_server.
        if (noSession()) ProcessScanner.reapLeftoverProcesses();
        try {
            context.startActivity(buildIntent(context, container, shortcutFile));
            return true;
        }
        catch (Exception e) {
            return false;
        }
    }

    /**
     * Reaps leftovers between sessions. The winebus thread left in a leftover
     * winedevice keeps polling input about 1000 times a second, so a set from
     * an ended session used a few percent of a core for hours on the S23.
     * Skipped while Play is staging, a WoW import runs or any Wine program is live.
     */
    public static int reapLeftoversWhenIdle() {
        if (RuntimeStageController.isRunning() || WowImportService.isRunning() || !noSession()) return 0;
        return ProcessScanner.reapLeftoverProcesses();
    }

    private static boolean noSession() {
        return XServerDisplayActivity.getActiveInstance() == null && !ProcessScanner.isWineSessionRunning();
    }
}
