package com.wehavephones.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.widget.ListPopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.wehavephones.R;
import com.wehavephones.mobile.devices.DeviceDriverStore;
import com.wehavephones.mobile.devices.DeviceProfileSelector;
import com.wehavephones.mobile.devices.DeviceRules;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * The Play screen's "Graphics driver" dropdown. The button shows Automatic or the picked driver,
 * never which driver Automatic uses; the open list marks that driver instead. Shown when this
 * phone's profile offers more than one driver (not on Mali).
 */
final class DriverPicker {
    private final Fragment fragment;
    private final View section;
    private final View button;
    private final TextView value;
    private List<DriverChoiceAdapter.Row> rows = new ArrayList<>();

    DriverPicker(Fragment fragment, View section, View button, TextView value) {
        this.fragment = fragment;
        this.section = section;
        this.button = button;
        this.value = value;
        button.setOnClickListener((v) -> show());
    }

    void refresh() {
        final Activity host = fragment.getActivity();
        if (host == null) return;
        final Context appContext = host.getApplicationContext();
        Executors.newSingleThreadExecutor().execute(() -> {
            final List<DriverChoiceAdapter.Row> loaded = rows(host, appContext);
            host.runOnUiThread(() -> {
                if (!fragment.isAdded()) return;
                rows = loaded;
                section.setVisibility(loaded.size() > 2 ? View.VISIBLE : View.GONE);
                for (DriverChoiceAdapter.Row row : loaded) {
                    if (row.selected) value.setText(row.name);
                }
            });
        });
    }

    // Automatic, then the profile's drivers and choices; no rows when the rules cannot be read.
    private static List<DriverChoiceAdapter.Row> rows(Context host, Context appContext) {
        ArrayList<DriverChoiceAdapter.Row> rows = new ArrayList<>();
        DeviceRules.Profile profile = DeviceProfileSelector.matchedProfile(appContext);
        if (profile == null) return rows;
        DeviceRules.Driver picked = profile.pickerDriver(DeviceProfileSelector.driverChoice(appContext));
        DeviceRules.Driver automatic = DeviceProfileSelector.automaticDriver(appContext, profile);
        rows.add(new DriverChoiceAdapter.Row(null, host.getString(R.string.wehavephones_driver_automatic),
                host.getString(R.string.wehavephones_driver_row_automatic), picked == null, false));
        for (DeviceRules.Driver driver : profile.pickerDrivers()) {
            int source = driver.isSystem() ? R.string.wehavephones_driver_row_system
                    : driver.asset != null ? R.string.wehavephones_driver_row_builtin
                    : DeviceDriverStore.isInstalled(appContext, driver) ? R.string.wehavephones_driver_row_downloaded
                    : R.string.wehavephones_driver_row_download;
            String meta = driver == automatic
                    ? host.getString(R.string.wehavephones_driver_row_used_by_automatic, host.getString(source))
                    : host.getString(source);
            rows.add(new DriverChoiceAdapter.Row(driver, driver.id, meta, driver == picked, driver == automatic));
        }
        return rows;
    }

    private void show() {
        final Activity host = fragment.getActivity();
        if (host == null || rows.isEmpty()) return;
        final List<DriverChoiceAdapter.Row> shown = rows;
        final ListPopupWindow popup = new ListPopupWindow(host);
        popup.setAnchorView(button);
        popup.setAdapter(new DriverChoiceAdapter(host, shown));
        popup.setWidth(Math.round(320 * host.getResources().getDisplayMetrics().density));
        popup.setBackgroundDrawable(host.getDrawable(R.drawable.wehavephones_card_background));
        popup.setModal(true);
        popup.setOnItemClickListener((parent, item, position, id) -> {
            popup.dismiss();
            if (!fragment.isAdded() || position >= shown.size() || shown.get(position).selected) return;
            pick(shown.get(position).driver);
        });
        popup.show();
    }

    // Saves the pick once the driver is on the phone, downloading it first when needed.
    private void pick(final DeviceRules.Driver driver) {
        final Activity host = fragment.getActivity();
        if (host == null) return;
        final Context appContext = host.getApplicationContext();
        final String name = driver != null ? driver.id : host.getString(R.string.wehavephones_driver_automatic);
        final AlertDialog progress = driver != null && !driver.isSystem()
                ? new AlertDialog.Builder(host)
                        .setMessage(host.getString(R.string.wehavephones_driver_downloading, name))
                        .setCancelable(false)
                        .show()
                : null;
        Executors.newSingleThreadExecutor().execute(() -> {
            String message;
            try {
                if (driver != null) DeviceDriverStore.install(appContext, driver);
                DeviceProfileSelector.setDriverChoice(appContext, driver);
                message = host.getString(R.string.wehavephones_driver_saved, name);
            }
            catch (Throwable throwable) {
                message = host.getString(R.string.wehavephones_driver_failed, name, String.valueOf(throwable.getMessage()));
            }
            final String text = message;
            host.runOnUiThread(() -> {
                if (progress != null) progress.dismiss();
                Toast.makeText(host, text, Toast.LENGTH_LONG).show();
                if (fragment.isAdded()) refresh();
            });
        });
    }
}
