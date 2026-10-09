package com.wehavephones.mobile;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.wehavephones.mobile.addons.AddonsActivity;
import com.wehavephones.mobile.addons.required.ConsolePortUpdater;
import com.wehavephones.MainActivity;
import com.wehavephones.R;
import com.wehavephones.SessionKeepAliveService;

public class WeHavePhonesActivity extends AppCompatActivity {
    public static final byte PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE = 11;

    public interface StageUpdater {
        void onStageUpdate(String message, boolean error);
    }

    private volatile String lastStageMessage = "";
    private volatile boolean lastStageError = false;
    /** False until a stage update arrives in this process; the restored message is from an earlier run. */
    private volatile boolean stageFromThisRun = false;
    private StageUpdater stageUpdater;
    private boolean firstRunShowing;
    private boolean initialPermissionsChecked;
    private boolean storagePermissionPending;
    private boolean notificationChannelPrepared;
    private Runnable afterStoragePermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            initialPermissionsChecked = savedInstanceState.getBoolean("initialPermissionsChecked");
            storagePermissionPending = savedInstanceState.getBoolean("storagePermissionPending");
        }
        setContentView(R.layout.activity_wehavephones);
        colorLogo(findViewById(R.id.TVWeHavePhonesLogo));
        lastStageMessage = WeHavePhonesConfig.getLastStageStatus(this);
        WeHavePhonesMemoryTrim.install();
        GameCategoryHint.apply(this);
        // Battle.net keeps the login; drop any account older builds saved.
        SecretStore.purgeAccountCredentials(this);

        firstRunShowing = !WeHavePhonesInitialSetup.isComplete(this);
        findViewById(R.id.WeHavePhonesRail).setVisibility(firstRunShowing ? View.GONE : View.VISIBLE);
        Fragment restored = getSupportFragmentManager().findFragmentById(R.id.FLWeHavePhonesScreen);
        if (savedInstanceState == null || (restored instanceof FirstRunSetupFragment) != firstRunShowing) {
            getSupportFragmentManager().popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.FLWeHavePhonesScreen, firstRunShowing ? new FirstRunSetupFragment() : new HomeFragment())
                    .commitNow();
        }
        setUpRail();
        ConsolePortUpdater.syncInBackground(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;
        // Wait for the startup screen to be visible. Android 13+ also asks
        // about notifications when our first channel is created; let the file
        // access request finish before triggering that second dialog.
        if (!initialPermissionsChecked) {
            initialPermissionsChecked = true;
            requestAppPermissions();
        }
        prepareNotificationPermission();
    }

    private void prepareNotificationPermission() {
        if (!storagePermissionPending && !notificationChannelPrepared && hasWindowFocus()
                && WeHavePhonesConfig.isKeepRunningInBackground(this)) {
            notificationChannelPrepared = true;
            SessionKeepAliveService.createChannel(this);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putBoolean("initialPermissionsChecked", initialPermissionsChecked);
        outState.putBoolean("storagePermissionPending", storagePermissionPending);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (firstRunShowing && WeHavePhonesInitialSetup.isComplete(this)) finishInitialSetup();
    }

    /** Landscape navigation rail; each entry replaces the screen on top of Play, so Back returns to Play. */
    private void setUpRail() {
        findViewById(R.id.RailPlay).setOnClickListener((v) -> showHome());
        findViewById(R.id.RailAddons).setOnClickListener((v) -> startActivity(new Intent(this, AddonsActivity.class)));
        findViewById(R.id.RailSetup).setOnClickListener((v) -> showSection(new SetupFragment()));
        findViewById(R.id.RailSettings).setOnClickListener((v) -> showSection(new WeHavePhonesSettingsFragment()));
        findViewById(R.id.RailDiagnostics).setOnClickListener((v) -> showSection(new DiagnosticsFragment()));
        // Classic Winlator screens (containers, shortcuts, input controls, settings), restyled for this flavor.
        findViewById(R.id.RailRuntime).setOnClickListener((v) -> startActivity(new Intent(this, MainActivity.class)));
        FragmentManager fragmentManager = getSupportFragmentManager();
        fragmentManager.registerFragmentLifecycleCallbacks(new FragmentManager.FragmentLifecycleCallbacks() {
            @Override
            public void onFragmentResumed(@NonNull FragmentManager manager, @NonNull Fragment fragment) {
                if (fragment.getId() == R.id.FLWeHavePhonesScreen) highlightRail(fragment);
            }
        }, false);
    }

    private void highlightRail(Fragment current) {
        int[][] entries = {
                {R.id.RailPlay, current instanceof HomeFragment ? 1 : 0},
                {R.id.RailSetup, current instanceof SetupFragment ? 1 : 0},
                {R.id.RailSettings, current instanceof WeHavePhonesSettingsFragment ? 1 : 0},
                {R.id.RailDiagnostics, current instanceof DiagnosticsFragment ? 1 : 0},
        };
        for (int[] entry : entries) findViewById(entry[0]).setSelected(entry[1] == 1);
    }

    /** The approved wordmark keeps the middle word in flip-phone blue. */
    private static void colorLogo(TextView logo) {
        android.text.SpannableString name = new android.text.SpannableString(logo.getText());
        int start = name.toString().indexOf("Have");
        if (start >= 0) name.setSpan(new android.text.style.ForegroundColorSpan(0xFF62B2F2),
                start, start + 4, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        logo.setText(name);
    }

    /** Shared LCD screen background lifecycle. */
    public void setAtmosphereAnimated(boolean animated) {
        WeHavePhonesAtmosphereView atmosphere = findViewById(R.id.VAtmosphere);
        if (atmosphere != null) atmosphere.setAnimated(animated);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        WeHavePhonesMemoryTrim.trimFromUi(this, level);
    }

    @Override
    protected void onDestroy() {
        WeHavePhonesMemoryTrim.uninstall();
        super.onDestroy();
    }

    public void showScreen(Fragment fragment) {
        if (firstRunShowing) return;
        FragmentManager fragmentManager = getSupportFragmentManager();
        fragmentManager.beginTransaction()
                .replace(R.id.FLWeHavePhonesScreen, fragment)
                .addToBackStack(null)
                .commit();
    }

    /** Rail navigation: Play plus one screen, never a deeper stack. */
    public void showSection(Fragment fragment) {
        if (firstRunShowing) return;
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.FLWeHavePhonesScreen);
        if (current != null && current.getClass() == fragment.getClass()) return;
        showHome();
        showScreen(fragment);
    }

    public void showHome() {
        if (firstRunShowing) return;
        getSupportFragmentManager().popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
    }

    /** The first-run screen is never placed on the navigation back stack. */
    public void finishInitialSetup() {
        if (!firstRunShowing || !WeHavePhonesInitialSetup.isComplete(this)
                || getSupportFragmentManager().isStateSaved()) return;
        firstRunShowing = false;
        findViewById(R.id.WeHavePhonesRail).setVisibility(View.VISIBLE);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.FLWeHavePhonesScreen, new HomeFragment()).commit();
    }

    public void setStageUpdater(StageUpdater updater) {
        this.stageUpdater = updater;
        if (updater != null && !lastStageMessage.isEmpty()) updater.onStageUpdate(lastStageMessage, lastStageError);
    }

    public void updateStageStatus(String message, boolean error) {
        stageFromThisRun = true;
        lastStageMessage = message != null ? message : "";
        lastStageError = error;
        WeHavePhonesConfig.setLastStageStatus(this, lastStageMessage);
        StageUpdater updater = stageUpdater;
        if (updater != null) updater.onStageUpdate(lastStageMessage, lastStageError);
    }

    public boolean hasStageFromThisRun() {
        return stageFromThisRun;
    }

    public String getLastStageMessage() {
        return lastStageMessage;
    }

    public boolean isLastStageError() {
        return lastStageError;
    }

    @Override
    public void onBackPressed() {
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (fragmentManager.getBackStackEntryCount() > 0) {
            fragmentManager.popBackStack();
        }
        else {
            super.onBackPressed();
        }
    }

    private boolean hasStoragePermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /** Continue the requested setup step as soon as file access is granted. */
    public void withStoragePermissions(Runnable action) {
        if (hasStoragePermissions()) {
            action.run();
            return;
        }
        afterStoragePermission = action;
        initialPermissionsChecked = true;
        requestAppPermissions();
    }

    private boolean requestAppPermissions() {
        if (hasStoragePermissions()) {
            return false;
        }
        if (storagePermissionPending) return true;
        storagePermissionPending = true;
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE},
                PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE);
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE) {
            storagePermissionPending = false;
            Runnable action = afterStoragePermission;
            afterStoragePermission = null;
            if (hasStoragePermissions()) {
                if (action != null) action.run();
            }
            else {
                Toast.makeText(this, R.string.wehavephones_permission_required, Toast.LENGTH_LONG).show();
            }
            prepareNotificationPermission();
        }
    }
}
