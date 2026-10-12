package com.wehavephones.mobile.updates;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import androidx.appcompat.app.AppCompatActivity;

import com.wehavephones.R;
import com.wehavephones.mobile.WeHavePhonesActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** Android owns installation and the final approval. No uninstall, data clearing or silent install. */
public final class AppUpdateInstallActivity extends AppCompatActivity {
    private static final int SOURCE_PERMISSION = 1;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile int sessionId = -1;
    private volatile boolean committed;
    private boolean ownsInstall;
    private ProgressDialog progress;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (handleResult(getIntent())) return;
        if (savedInstanceState != null) {
            if (savedInstanceState.getBoolean("committed")) {
                // Android still owns a committed session and will deliver its result separately.
                committed = true;
                finish();
                return;
            }
            // A process interrupted staging or the permission screen. Start over explicitly.
            sessionId = savedInstanceState.getInt("session", -1);
            abandon();
        }
        prepare();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleResult(intent);
    }

    private void prepare() {
        if (!AppUpdates.get(this).isSupported()) {
            fail(getString(R.string.wehavephones_update_dev));
        } else if (AppUpdates.get(this).isBusy()) {
            fail(getString(R.string.wehavephones_update_preparing));
        } else if (AppUpdateUi.isSessionBusy()) {
            fail(getString(R.string.wehavephones_update_busy));
        } else if (!getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this).setTitle(R.string.wehavephones_update_title)
                    .setMessage(R.string.wehavephones_update_permission)
                    .setPositiveButton(R.string.wehavephones_update_allow, (d, which) -> {
                        try {
                            startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName())), SOURCE_PERMISSION);
                        } catch (RuntimeException e) { fail(e.getMessage()); }
                    })
                    .setNegativeButton(android.R.string.cancel, (d, which) -> finish())
                    .setOnCancelListener(d -> finish()).show();
        } else {
            stage();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SOURCE_PERMISSION) {
            if (getPackageManager().canRequestPackageInstalls()) prepare();
            else finish();
        }
    }

    private void stage() {
        ownsInstall = AppUpdates.get(this).beginInstall();
        if (!ownsInstall) { fail(getString(R.string.wehavephones_update_preparing)); return; }
        progress = new ProgressDialog(this);
        progress.setMessage(getString(R.string.wehavephones_update_preparing));
        progress.setCancelable(false);
        progress.setButton(AlertDialog.BUTTON_NEGATIVE, getString(android.R.string.cancel), (d, which) -> finish());
        progress.show();
        new Thread(() -> {
            try {
                File apk = AppUpdates.get(this).verifiedApk();
                PackageInstaller installer = getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(getPackageName());
                params.setSize(apk.length());
                if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
                sessionId = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(sessionId);
                     FileInputStream input = new FileInputStream(apk);
                     OutputStream output = session.openWrite("base.apk", 0, apk.length())) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (cancelled.get()) throw new java.io.IOException("Update cancelled");
                        output.write(buffer, 0, count);
                    }
                    session.fsync(output);
                }
                runOnUiThread(() -> commit(installer));
            } catch (Exception e) {
                abandon();
                runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) fail(e.getMessage()); });
            }
        }, "wehavephones-update-install").start();
    }

    private void commit(PackageInstaller installer) {
        if (cancelled.get() || isFinishing() || isDestroyed()) { abandon(); return; }
        if (AppUpdateUi.isSessionBusy()) { abandon(); fail(getString(R.string.wehavephones_update_busy)); return; }
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            Intent callback = new Intent(this, AppUpdateInstallActivity.class)
                    .setAction(getPackageName() + ".APP_UPDATE_RESULT")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            // PackageInstaller fills in the status and confirmation Intent.
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent result = PendingIntent.getActivity(this, sessionId, callback, flags);
            getSharedPreferences("wehavephones_app_updates", MODE_PRIVATE).edit().putInt("install_session", sessionId).apply();
            committed = true;
            session.commit(result.getIntentSender());
        } catch (Exception e) { committed = false; abandon(); fail(e.getMessage()); }
    }

    private boolean handleResult(Intent intent) {
        if (!intent.hasExtra(PackageInstaller.EXTRA_STATUS)) return false;
        int expected = getSharedPreferences("wehavephones_app_updates", MODE_PRIVATE).getInt("install_session", -1);
        if (expected < 0 || expected != intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -2)) { finish(); return true; }
        committed = true;
        if (progress != null) progress.dismiss();
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmation == null) { fail("Android did not provide an installation confirmation"); return true; }
            try { startActivity(confirmation); finish(); }
            catch (RuntimeException e) { fail(e.getMessage()); }
        } else {
            getSharedPreferences("wehavephones_app_updates", MODE_PRIVATE).edit().remove("install_session").apply();
            if (status == PackageInstaller.STATUS_SUCCESS) {
                startActivity(new Intent(this, WeHavePhonesActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                finish();
            } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                finish();
            } else {
                fail(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            }
        }
        return true;
    }

    private void fail(String detail) {
        if (progress != null) progress.dismiss();
        new AlertDialog.Builder(this).setTitle(R.string.wehavephones_update_title)
                .setMessage(getString(R.string.wehavephones_update_error, detail == null ? "Installation failed" : detail))
                .setPositiveButton(android.R.string.ok, (d, which) -> finish()).setOnCancelListener(d -> finish()).show();
    }

    private void abandon() {
        if (sessionId >= 0) {
            try { getPackageManager().getPackageInstaller().abandonSession(sessionId); } catch (RuntimeException ignored) { }
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("session", sessionId);
        state.putBoolean("committed", committed);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        if (!committed) { cancelled.set(true); abandon(); }
        if (ownsInstall) AppUpdates.get(this).finishInstall();
        if (progress != null) progress.dismiss();
        super.onDestroy();
    }
}
