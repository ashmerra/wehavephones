package com.wehavephones.mobile.updates;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;

import com.wehavephones.R;
import com.wehavephones.XServerDisplayActivity;
import com.wehavephones.mobile.OneButtonLauncher;
import com.wehavephones.mobile.ProcessScanner;
import com.wehavephones.mobile.WeHavePhonesActivity;
import com.wehavephones.mobile.WeHavePhonesInitialSetup;
import com.wehavephones.mobile.WowImportService;

/** Foreground-only prompts; downloads themselves belong to AppUpdates. */
public final class AppUpdateUi implements AppUpdates.Listener {
    private final WeHavePhonesActivity activity;
    private final AppUpdates updates;
    private AlertDialog dialog;
    private AppUpdates.Phase displayedPhase;
    private boolean resumed, manual, installWhenReady;

    public AppUpdateUi(WeHavePhonesActivity activity) {
        this.activity = activity;
        updates = AppUpdates.get(activity);
    }

    public void onResume() {
        resumed = true;
        updates.addListener(this);
    }

    public void onPause() {
        resumed = false;
        updates.removeListener(this);
        dismiss();
    }

    public void onForegroundReady() {
        if (!WeHavePhonesInitialSetup.isComplete(activity)) return;
        updates.check(false);
        onUpdateStateChanged();
    }

    public void checkManually() {
        if (!updates.isSupported()) {
            new AlertDialog.Builder(activity).setTitle(R.string.wehavephones_update_title)
                    .setMessage(R.string.wehavephones_update_dev).setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        manual = true;
        if (updates.phase() == AppUpdates.Phase.READY) onUpdateStateChanged();
        else updates.check(true);
    }

    /** Includes orphaned Wine processes and an import that must not be interrupted by replacing the app. */
    public static boolean isSessionBusy() {
        XServerDisplayActivity session = XServerDisplayActivity.getActiveInstance();
        return (session != null && session.isSessionActive()) || OneButtonLauncher.isRunning()
                || WowImportService.isRunning() || ProcessScanner.isWineSessionRunning();
    }

    @Override public void onUpdateStateChanged() {
        if (!resumed || activity.isFinishing() || activity.isDestroyed()
                || (!activity.hasWindowFocus() && (dialog == null || !dialog.isShowing()))) return;
        AppUpdates.Phase phase = updates.phase();
        if (phase == AppUpdates.Phase.DOWNLOADING && updates.isCancellationRequested()) { dismiss(); return; }
        if (dialog != null && dialog.isShowing() && phase == displayedPhase) {
            if (dialog instanceof ProgressDialog) ((ProgressDialog)dialog).setProgress(updates.progress());
            return;
        }
        // Checks may finish while the game is in the foreground; never steal its screen.
        if (!manual && (phase == AppUpdates.Phase.AVAILABLE || phase == AppUpdates.Phase.READY)
                && (!updates.isAutomatic() || isSessionBusy() || !WeHavePhonesInitialSetup.isComplete(activity))) return;
        dismiss();
        displayedPhase = phase;
        if (phase == AppUpdates.Phase.CHECKING && manual) {
            ProgressDialog progress = new ProgressDialog(activity);
            progress.setMessage(activity.getString(R.string.wehavephones_update_checking));
            progress.setCancelable(false);
            dialog = progress;
            progress.show();
        } else if (phase == AppUpdates.Phase.DOWNLOADING) {
            ProgressDialog progress = new ProgressDialog(activity);
            progress.setTitle(R.string.wehavephones_update_downloading);
            progress.setMessage(activity.getString(R.string.wehavephones_update_download_hint));
            progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progress.setMax(100);
            progress.setProgress(updates.progress());
            progress.setCancelable(false);
            progress.setButton(AlertDialog.BUTTON_NEGATIVE, activity.getString(android.R.string.cancel),
                    (d, which) -> { installWhenReady = false; manual = false; updates.cancelDownload(); });
            dialog = progress;
            progress.show();
        } else if (phase == AppUpdates.Phase.AVAILABLE || phase == AppUpdates.Phase.READY) {
            AppRelease release = updates.release();
            if (release == null) return;
            Context context = activity.getApplicationContext();
            long lastPrompt = context.getSharedPreferences("wehavephones_app_updates", Context.MODE_PRIVATE)
                    .getLong("prompt_" + release.version, 0);
            long now = System.currentTimeMillis();
            if (!manual && !installWhenReady && now >= lastPrompt && now - lastPrompt < 24L * 60 * 60 * 1000) return;
            if (phase == AppUpdates.Phase.READY && installWhenReady) {
                installWhenReady = false;
                manual = false;
                install();
                return;
            }
            context.getSharedPreferences("wehavephones_app_updates", Context.MODE_PRIVATE)
                    .edit().putLong("prompt_" + release.version, now).apply();
            String message = activity.getString(R.string.wehavephones_update_available,
                    release.version, Math.round(release.size / 1000000.0));
            if (!release.notes.isEmpty()) message += "\n\n" + release.notes;
            dialog = new AlertDialog.Builder(activity).setTitle(R.string.wehavephones_update_title)
                    .setMessage(message)
                    .setPositiveButton(phase == AppUpdates.Phase.READY ? R.string.wehavephones_update_install : R.string.wehavephones_update_download,
                            (d, which) -> {
                                if (isSessionBusy()) { showBusy(); return; }
                                if (phase == AppUpdates.Phase.READY) { manual = false; install(); }
                                else { installWhenReady = true; manual = true; updates.download(); }
                            })
                    .setNegativeButton(R.string.wehavephones_update_later, (d, which) -> { manual = false; installWhenReady = false; })
                    .setOnCancelListener(d -> { manual = false; installWhenReady = false; }).create();
            dialog.show();
        } else if (manual && (phase == AppUpdates.Phase.IDLE || phase == AppUpdates.Phase.ERROR)) {
            manual = false;
            installWhenReady = false;
            dialog = new AlertDialog.Builder(activity).setTitle(R.string.wehavephones_update_title)
                    .setMessage(phase == AppUpdates.Phase.IDLE ? activity.getString(R.string.wehavephones_update_current)
                            : activity.getString(R.string.wehavephones_update_error, updates.error()))
                    .setPositiveButton(android.R.string.ok, null).create();
            dialog.show();
        }
    }

    private void install() {
        if (isSessionBusy()) { showBusy(); return; }
        activity.startActivity(new Intent(activity, AppUpdateInstallActivity.class));
    }

    private void showBusy() {
        manual = false;
        installWhenReady = false;
        dialog = new AlertDialog.Builder(activity).setMessage(R.string.wehavephones_update_busy)
                .setPositiveButton(android.R.string.ok, null).create();
        dialog.show();
    }

    private void dismiss() {
        if (dialog != null) dialog.dismiss();
        dialog = null;
        displayedPhase = null;
    }
}
