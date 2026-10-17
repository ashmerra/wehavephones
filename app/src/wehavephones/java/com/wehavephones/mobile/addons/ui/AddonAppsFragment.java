package com.wehavephones.mobile.addons.ui;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.wehavephones.mobile.AddonApp;
import com.wehavephones.mobile.AddonAppLauncher;
import com.wehavephones.mobile.addons.AddonsActivity;
import com.wehavephones.R;

/**
 * Addon apps tab: the managers' own Windows apps, one row each with its state
 * and one button. Open also installs a newer release from the app's feed, so
 * there is no separate update control.
 */
public class AddonAppsFragment extends Fragment {
    private static final AddonApp[] APPS = {AddonApp.CURSEFORGE, AddonApp.WOWUP};

    private AddonsActivity activity;
    private LinearLayout rows;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_addons_apps, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        activity = (AddonsActivity)requireActivity();
        rows = view.findViewById(R.id.LLAddonApps);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (AddonApp app : APPS) {
            View row = inflater.inflate(R.layout.wehavephones_addon_app_row, rows, false);
            row.setTag(app);
            rows.addView(row);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // Coming back from an app can change what is installed.
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            AddonApp app = (AddonApp)row.getTag();
            bind(row, app, null);
            AddonAppLauncher.loadStatus(activity, app, (status) -> {
                if (isAdded()) bind(row, app, status);
            });
        }
    }

    private void bind(View row, AddonApp app, AddonAppLauncher.Status status) {
        TextView monogram = row.findViewById(R.id.TVAppMonogram);
        monogram.setText(app == AddonApp.CURSEFORGE ? "CF" : "W");
        monogram.setBackgroundTintList(ColorStateList.valueOf(
                app == AddonApp.CURSEFORGE ? 0xFFE8622A : 0xFF5C3BC4));
        ((TextView)row.findViewById(R.id.TVAppName)).setText(app.displayName);

        TextView state = row.findViewById(R.id.TVAppState);
        Button open = row.findViewById(R.id.BTAppOpen);
        open.setOnClickListener((v) -> AddonAppLauncher.open(activity, app,
                (message, error) -> activity.showStatus(message)));

        if (status == null) {
            state.setText("");
            open.setText(R.string.wehavephones_addon_app_open);
            return;
        }
        String installedLine = status.installedVersion != null
                ? getString(R.string.wehavephones_addon_app_installed_version, status.installedVersion)
                : getString(R.string.wehavephones_addon_app_installed);
        if (status.updateReady()) {
            state.setText(getString(R.string.wehavephones_addon_app_update_ready, installedLine, megabytes(status)));
            open.setText(R.string.wehavephones_addon_app_update_open);
        }
        else if (status.installed) {
            state.setText(installedLine);
            open.setText(R.string.wehavephones_addon_app_open);
        }
        else {
            state.setText(status.latest != null
                    ? getString(R.string.wehavephones_addon_app_not_downloaded_size, megabytes(status))
                    : getString(R.string.wehavephones_addon_app_not_downloaded));
            open.setText(R.string.wehavephones_addon_app_download_open);
        }
    }

    private static int megabytes(AddonAppLauncher.Status status) {
        return (int)Math.max(1, Math.round(status.latest.size / (1024.0 * 1024.0)));
    }
}
