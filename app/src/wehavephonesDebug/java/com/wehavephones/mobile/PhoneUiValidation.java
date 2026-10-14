package com.wehavephones.mobile;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.wehavephones.MainActivity;
import com.wehavephones.R;
import com.wehavephones.contentdialog.ContentDialog;
import com.wehavephones.mobile.addons.AddonsActivity;
import com.wehavephones.mobile.addons.ui.AddonBrowseFragment;
import com.wehavephones.mobile.addons.ui.AddonSettingsFragment;

import java.io.File;
import java.io.FileOutputStream;

/** ADB-only validation of actual native views, without completing setup, creating
 * a container, launching Wine, changing preferences or storing account fixtures.
 * The host must check that no live game/session is running before invoking it. */
public final class PhoneUiValidation extends Instrumentation {
    private File output;
    private WeHavePhonesActivity launcher;
    private Activity other;
    private Configuration original;
    private int screenshots;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            output = new File(getTargetContext().getCacheDir(), "ui-validation");
            if (!output.isDirectory() && !output.mkdirs()) throw new AssertionError("Cannot create screenshot directory");
            launcher = (WeHavePhonesActivity)startActivitySync(new Intent(getTargetContext(), WeHavePhonesActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            original = new Configuration(launcher.getResources().getConfiguration());
            capture("first-run");
            show(new HomeFragment());
            require(R.id.BTPlay); require(R.id.BTOpenBattleNet);
            fullyVisible(R.id.BTPlay); fullyVisible(R.id.BTOpenBattleNet);
            capture("play");
            show(new SetupFragment());
            require(R.id.SPActiveContainer);
            require(R.id.BTImportWow);
            fullyVisible(R.id.BTImportWow);
            capture("setup");
            show(new WeHavePhonesSettingsFragment());
            require(R.id.CBDisableVirtualGamepad); require(R.id.CBKeepRunningInBackground);
            require(R.id.CBPerformanceOverlay);
            require(R.id.SPSettingsRegion); require(R.id.SPGameResolution);
            capture("settings");
            show(new DiagnosticsFragment()); capture("diagnostics");
            // Render the actual overlay resource without starting a game session.
            onMain(() -> {
                FrameLayout host = launcher.findViewById(android.R.id.content);
                FrameLayout overlay = new FrameLayout(launcher);
                LayoutInflater.from(launcher).inflate(R.layout.wehavephones_launch_overlay, overlay, true);
                ((TextView)overlay.findViewById(R.id.TVOverlayGame)).setText(R.string.wehavephones_game_default_title);
                ((TextView)overlay.findViewById(R.id.TVOverlayHeadline)).setText(R.string.wehavephones_launch_starting);
                ((TextView)overlay.findViewById(R.id.TVOverlayDetail)).setText(R.string.wehavephones_overlay_detail_runtime);
                ((TextView)overlay.findViewById(R.id.TVOverlayTip)).setText(launcher.getResources().getStringArray(R.array.wehavephones_overlay_tips)[0]);
                ((LaunchBarView)overlay.findViewById(R.id.VOverlayBar)).setAnimated(false);
                ((LaunchBarView)overlay.findViewById(R.id.VOverlayBar)).setFraction(0.45f, false);
                overlay.setTag("validation-overlay");
                host.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
            });
            capture("launch-overlay");
            onMain(() -> {
                FrameLayout host = launcher.findViewById(android.R.id.content);
                host.removeView(host.findViewWithTag("validation-overlay"));
                ContentDialog dialog = new ContentDialog(launcher);
                dialog.setTitle("Phone UI check");
                dialog.setMessage("Silver keys, readable LCD text and native dialog controls.");
                dialog.show();
                dialog.getWindow().getDecorView().setTag("validation-dialog");
            });
            capture("dialog");
            // A real Back event dismisses the native dialog before the next activity.
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            other = startActivitySync(new Intent(getTargetContext(), AddonsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            capture("addons");
            final AddonsActivity addons = (AddonsActivity)other;
            onMain(() -> addons.getSupportFragmentManager().beginTransaction()
                    .replace(R.id.FLAddonsScreen, new AddonBrowseFragment()).commitNow());
            capture("addons-browse");
            onMain(() -> addons.getSupportFragmentManager().beginTransaction()
                    .replace(R.id.FLAddonsScreen, new AddonSettingsFragment()).commitNow());
            capture("addon-providers");
            onMain(other::finish); other = null;
            // edit_input_controls avoids MainActivity's automatic imagefs installer.
            other = startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .putExtra("edit_input_controls", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            capture("input-controls");
            final MainActivity runtime = (MainActivity)other;
            onMain(() -> runtime.onNavigationItemSelected(((com.google.android.material.navigation.NavigationView)
                    runtime.findViewById(R.id.NavigationView)).getMenu().findItem(R.id.main_menu_containers)));
            capture("containers");
            onMain(() -> ((androidx.drawerlayout.widget.DrawerLayout)runtime.findViewById(R.id.DrawerLayout))
                    .openDrawer(androidx.core.view.GravityCompat.START, false));
            capture("runtime-drawer");
            onMain(other::finish); other = null;
            // App-local font scale only; restore it in finally. Device settings are untouched.
            onMain(() -> {
                Configuration large = new Configuration(original); large.fontScale = 1.3f;
                launcher.getResources().updateConfiguration(large, launcher.getResources().getDisplayMetrics());
            });
            show(new WeHavePhonesSettingsFragment()); capture("settings-large-text");
            result.putString("stream", "PASS: " + screenshots + " native screenshots; real controls inflated without runtime/setup actions\n");
            result.putString("screenshots", output.getAbsolutePath());
            cleanup();
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(failure));
            try { cleanup(); } catch (Throwable ignored) { /* Report the original failure. */ }
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    /** Runs on the main thread and rethrows failures here, so they reach onStart's
     * catch as FAIL output instead of crashing the process. */
    private void onMain(Runnable action) {
        Throwable[] failure = new Throwable[1];
        runOnMainSync(() -> {
            try { action.run(); } catch (Throwable thrown) { failure[0] = thrown; }
        });
        if (failure[0] instanceof RuntimeException) throw (RuntimeException)failure[0];
        if (failure[0] instanceof Error) throw (Error)failure[0];
    }

    private String name(int id) {
        try { return launcher.getResources().getResourceEntryName(id); }
        catch (RuntimeException unknown) { return String.valueOf(id); }
    }

    private void show(Fragment fragment) {
        onMain(() -> {
            launcher.findViewById(R.id.WeHavePhonesRail).setVisibility(View.VISIBLE);
            launcher.getSupportFragmentManager().beginTransaction().replace(R.id.FLWeHavePhonesScreen, fragment).commitNow();
        });
        waitForIdleSync();
    }

    private void require(int id) {
        onMain(() -> {
            View control = launcher.findViewById(id);
            if (control == null || control.getVisibility() != View.VISIBLE) throw new AssertionError("Missing native control " + name(id));
        });
    }

    private void fullyVisible(int id) {
        waitForIdleSync(); SystemClock.sleep(300);
        onMain(() -> {
            View view = launcher.findViewById(id);
            android.graphics.Rect visible = new android.graphics.Rect();
            if (!view.getGlobalVisibleRect(visible) || visible.height() < view.getHeight())
                throw new AssertionError("Main action below initial viewport: " + name(id));
        });
    }

    private void capture(String name) throws Exception {
        waitForIdleSync(); SystemClock.sleep(600);
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new AssertionError("Screenshot unavailable: unlock phone");
        try (FileOutputStream file = new FileOutputStream(new File(output, name + ".png"))) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, file)) throw new AssertionError("Screenshot encoding failed");
        } finally { bitmap.recycle(); }
        screenshots++;
    }

    private void cleanup() {
        onMain(() -> {
            if (other != null) other.finish();
            if (launcher != null) {
                if (original != null) launcher.getResources().updateConfiguration(original, launcher.getResources().getDisplayMetrics());
                launcher.finish();
            }
        });
    }
}
