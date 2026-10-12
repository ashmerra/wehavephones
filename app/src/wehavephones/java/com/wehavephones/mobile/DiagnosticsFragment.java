package com.wehavephones.mobile;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListPopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.wehavephones.BuildConfig;
import com.wehavephones.R;
import com.wehavephones.core.GraphicsPlan;
import com.wehavephones.mobile.devices.DeviceDriverStore;
import com.wehavephones.mobile.devices.DeviceIdentity;
import com.wehavephones.mobile.devices.DeviceProfileSelector;
import com.wehavephones.mobile.devices.DeviceReport;
import com.wehavephones.mobile.devices.DeviceRules;
import com.wehavephones.mobile.devices.DeviceRulesStore;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

public class DiagnosticsFragment extends Fragment {
    private static final long SAMPLE_INTERVAL_MS = 2000L;
    private static final int MAX_SAMPLES = 300;

    private WeHavePhonesActivity activity;
    private TextView diagnosticsView;
    private TextView memoryView;
    private View driverSwitch;
    private TextView driverName;
    private TextView driverMeta;
    private List<DriverChoiceAdapter.Row> driverRows = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<MemoryReport.Sample> samples = new ArrayList<>();
    private volatile String appliedLabels = "";

    private final Runnable sampler = new Runnable() {
        @Override
        public void run() {
            if (activity == null || !isVisible()) return;
            MemoryReport.Sample sample = WeHavePhonesMemoryDiagnostics.sample();
            samples.add(sample);
            while (samples.size() > MAX_SAMPLES) samples.remove(0);
            if (memoryView != null) memoryView.setText(panelText(sample));
            handler.postDelayed(this, SAMPLE_INTERVAL_MS);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_wehavephones_diagnostics, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        activity = (WeHavePhonesActivity)getActivity();
        diagnosticsView = view.findViewById(R.id.TVDiagnostics);
        memoryView = view.findViewById(R.id.TVMemoryPanel);
        driverSwitch = view.findViewById(R.id.LLDriverSwitch);
        driverName = view.findViewById(R.id.TVDriverName);
        driverMeta = view.findViewById(R.id.TVDriverMeta);
        driverSwitch.setOnClickListener((v) -> showDriverPicker());
        view.findViewById(R.id.BTRefresh).setOnClickListener((v) -> refresh());
        view.findViewById(R.id.BTCopy).setOnClickListener((v) -> copyToClipboard());
        view.findViewById(R.id.BTSaveMemory).setOnClickListener((v) -> saveMemoryReport());
        view.findViewById(R.id.BTDeviceUpdate).setOnClickListener((v) -> checkDeviceUpdates());
        view.findViewById(R.id.BTDeviceReport).setOnClickListener((v) -> reportProblem());
        refresh();
        refreshDriverPicker();
    }

    @Override
    public void onResume() {
        super.onResume();
        handler.post(sampler);
    }

    @Override
    public void onPause() {
        super.onPause();
        handler.removeCallbacks(sampler);
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacks(sampler);
        diagnosticsView = null;
        memoryView = null;
        driverSwitch = null;
        driverName = null;
        driverMeta = null;
        super.onDestroyView();
    }

    private String panelText(MemoryReport.Sample sample) {
        return WeHavePhonesMemoryDiagnostics.format(sample)
                + "\n\nApplied overrides:\n" + appliedLabels
                + "\nTrim callbacks: " + WeHavePhonesMemoryTrim.getTrimCount()
                + (WeHavePhonesMemoryTrim.getLastTrimMillis() > 0
                        ? " (last " + timeLabel(WeHavePhonesMemoryTrim.getLastTrimMillis()) + ")"
                        : "");
    }

    private void refresh() {
        if (diagnosticsView == null) return;
        diagnosticsView.setText(R.string.wehavephones_diag_empty);
        Executors.newSingleThreadExecutor().execute(() -> {
            String report;
            String labels;
            try {
                Context appContext = activity.getApplicationContext();
                report = WeHavePhonesDiagnostics.buildReport(appContext);
                labels = String.join("\n", WeHavePhonesMemoryOverrides.appliedLabels(appContext,
                        WeHavePhonesContainers.resolve(appContext)));
            }
            catch (Throwable throwable) {
                report = "Diagnostics failed: " + throwable;
                labels = "";
            }
            final String result = report;
            final String labelResult = labels;
            if (!isAdded()) return;
            activity.runOnUiThread(() -> {
                if (!isAdded()) return;
                if (diagnosticsView != null) diagnosticsView.setText(result);
                appliedLabels = labelResult;
                MemoryReport.Sample sample = samples.isEmpty() ? null : samples.get(samples.size() - 1);
                if (memoryView != null && sample != null) memoryView.setText(panelText(sample));
            });
        });
    }

    private void saveMemoryReport() {
        final WeHavePhonesActivity host = activity;
        if (host == null) return;
        final Context appContext = host.getApplicationContext();
        final ArrayList<MemoryReport.Sample> snapshot = new ArrayList<>(samples);
        Executors.newSingleThreadExecutor().execute(() -> {
            File file = WeHavePhonesMemoryDiagnostics.writeReport(appContext, snapshot);
            final String message = file != null
                    ? host.getString(R.string.wehavephones_diag_memory_saved, file.getPath())
                    : host.getString(R.string.wehavephones_diag_memory_save_failed);
            host.runOnUiThread(() -> Toast.makeText(host, message, Toast.LENGTH_LONG).show());
        });
    }

    private void checkDeviceUpdates() {
        final WeHavePhonesActivity host = activity;
        if (host == null) return;
        final Context appContext = host.getApplicationContext();
        Toast.makeText(host, R.string.wehavephones_diag_device_checking, Toast.LENGTH_SHORT).show();
        Executors.newSingleThreadExecutor().execute(() -> {
            String message;
            try {
                DeviceRulesStore.RefreshResult result = DeviceRulesStore.refreshNow(appContext);
                if (result.isUpdated()) {
                    message = host.getString(R.string.wehavephones_diag_device_updated, result.rulesVersion);
                }
                else if (DeviceRulesStore.RefreshResult.CURRENT.equals(result.status)) {
                    message = host.getString(R.string.wehavephones_diag_device_current, result.rulesVersion);
                }
                else {
                    message = host.getString(R.string.wehavephones_diag_device_failed, result.rulesVersion, result.message);
                }
            }
            catch (Throwable throwable) {
                message = host.getString(R.string.wehavephones_diag_device_failed, 0, String.valueOf(throwable));
            }
            final String text = message;
            host.runOnUiThread(() -> {
                Toast.makeText(host, text, Toast.LENGTH_LONG).show();
                if (isAdded()) {
                    refresh();
                    refreshDriverPicker();
                }
            });
        });
    }

    // The dropdown shows when this phone's profile offers more than one driver (not on Mali).
    private void refreshDriverPicker() {
        final WeHavePhonesActivity host = activity;
        if (host == null) return;
        final Context appContext = host.getApplicationContext();
        Executors.newSingleThreadExecutor().execute(() -> {
            final DriverRows loaded = driverRows(host, appContext);
            final List<DriverChoiceAdapter.Row> rows = loaded.rows;
            host.runOnUiThread(() -> {
                if (!isAdded() || driverSwitch == null) return;
                driverRows = rows;
                driverSwitch.setVisibility(rows.size() > 2 ? View.VISIBLE : View.GONE);
                DriverChoiceAdapter.Row current = rows.isEmpty() ? null : rows.get(0);
                for (DriverChoiceAdapter.Row row : rows) {
                    if (row.selected) current = row;
                }
                if (current == null) return;
                driverName.setText(current.name);
                driverMeta.setText(current.driver == null && loaded.automaticUses != null
                        ? host.getString(R.string.wehavephones_diag_driver_meta_using, loaded.automaticUses)
                        : host.getString(R.string.wehavephones_diag_driver_title));
            });
        });
    }

    private static final class DriverRows {
        final ArrayList<DriverChoiceAdapter.Row> rows = new ArrayList<>();
        /** The driver Automatic used in the last session, or null when unknown. */
        String automaticUses;
    }

    // Automatic, then the profile's drivers and choices; no rows when the rules cannot be read.
    private static DriverRows driverRows(Context host, Context appContext) {
        DriverRows result = new DriverRows();
        ArrayList<DriverChoiceAdapter.Row> rows = result.rows;
        DeviceRules.Profile profile = DeviceProfileSelector.matchedProfile(appContext);
        if (profile == null) return result;
        DeviceRules.Driver picked = profile.pickerDriver(DeviceProfileSelector.driverChoice(appContext));
        GraphicsPlan plan = DeviceProfileSelector.lastPlan(appContext);
        DeviceRules.Driver used = plan != null && plan.driverChoice.isEmpty() && profile.name.equals(plan.profileName)
                ? profile.pickerDriver(plan.driverId) : null;
        if (used != null) result.automaticUses = used.displayName();
        rows.add(new DriverChoiceAdapter.Row(null, host.getString(R.string.wehavephones_diag_driver_automatic),
                used != null ? host.getString(R.string.wehavephones_diag_driver_row_automatic, used.displayName())
                        : host.getString(R.string.wehavephones_diag_driver_row_automatic_rules),
                picked == null));
        for (DeviceRules.Driver driver : profile.pickerDrivers()) {
            int meta = driver.isSystem() ? R.string.wehavephones_diag_driver_row_system
                    : driver.asset != null ? R.string.wehavephones_diag_driver_row_builtin
                    : DeviceDriverStore.isInstalled(appContext, driver) ? R.string.wehavephones_diag_driver_row_downloaded
                    : R.string.wehavephones_diag_driver_row_download;
            rows.add(new DriverChoiceAdapter.Row(driver, driver.displayName(), host.getString(meta), driver == picked));
        }
        return result;
    }

    private void showDriverPicker() {
        final WeHavePhonesActivity host = activity;
        if (host == null || driverSwitch == null || driverRows.isEmpty()) return;
        final List<DriverChoiceAdapter.Row> rows = driverRows;
        final ListPopupWindow popup = new ListPopupWindow(host);
        popup.setAnchorView(driverSwitch);
        popup.setAdapter(new DriverChoiceAdapter(host, rows));
        popup.setBackgroundDrawable(host.getDrawable(R.drawable.wehavephones_card_background));
        popup.setModal(true);
        popup.setOnItemClickListener((parent, item, position, id) -> {
            popup.dismiss();
            if (!isAdded() || position >= rows.size() || rows.get(position).selected) return;
            pickDriver(rows.get(position).driver);
        });
        popup.show();
    }

    // Saves the pick once the driver is on the phone, downloading it first when needed.
    private void pickDriver(final DeviceRules.Driver driver) {
        final WeHavePhonesActivity host = activity;
        if (host == null) return;
        final Context appContext = host.getApplicationContext();
        final String name = driver != null ? driver.displayName() : host.getString(R.string.wehavephones_diag_driver_automatic);
        final AlertDialog progress = driver != null && !driver.isSystem()
                ? new AlertDialog.Builder(host)
                        .setMessage(host.getString(R.string.wehavephones_diag_driver_downloading, name))
                        .setCancelable(false)
                        .show()
                : null;
        Executors.newSingleThreadExecutor().execute(() -> {
            String message;
            try {
                if (driver != null) DeviceDriverStore.install(appContext, driver);
                DeviceProfileSelector.setDriverChoice(appContext, driver);
                message = host.getString(R.string.wehavephones_diag_driver_saved, name);
            }
            catch (Throwable throwable) {
                message = host.getString(R.string.wehavephones_diag_driver_failed, name, String.valueOf(throwable.getMessage()));
            }
            final String text = message;
            host.runOnUiThread(() -> {
                if (progress != null) progress.dismiss();
                Toast.makeText(host, text, Toast.LENGTH_LONG).show();
                if (isAdded()) {
                    refresh();
                    refreshDriverPicker();
                }
            });
        });
    }

    private void reportProblem() {
        final WeHavePhonesActivity host = activity;
        if (host == null) return;
        final Context appContext = host.getApplicationContext();
        Executors.newSingleThreadExecutor().execute(() -> {
            String url;
            try {
                DeviceIdentity identity = DeviceIdentity.detect(appContext);
                String phone = (identity.manufacturer + " " + identity.model).trim();
                url = DeviceReport.issueUrl(WeHavePhonesDiagnostics.deviceReportFields(appContext),
                        BuildConfig.VERSION_NAME, phone, identity.glRenderer, identity.release);
            }
            catch (Throwable throwable) {
                url = DeviceReport.ISSUE_URL + "&source=In%20the%20app";
            }
            final String link = url;
            host.runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
                }
                catch (RuntimeException e) {
                    Toast.makeText(host, R.string.wehavephones_diag_device_report_failed, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void copyToClipboard() {
        if (activity == null) return;
        ClipboardManager clipboardManager = (ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager == null) return;
        String memory = memoryView != null ? memoryView.getText().toString() : "";
        String diagnostics = diagnosticsView != null ? diagnosticsView.getText().toString() : "";
        clipboardManager.setPrimaryClip(ClipData.newPlainText("wehavephones-diagnostics",
                "== Memory panel ==\n" + memory + "\n\n" + diagnostics));
        Toast.makeText(activity, R.string.wehavephones_diag_copied, Toast.LENGTH_SHORT).show();
    }

    private static String timeLabel(long millis) {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(millis));
    }
}
