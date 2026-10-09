package com.wehavephones.mobile.addons.ui;

import android.content.Context;
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

import com.wehavephones.mobile.addons.AddonManager;
import com.wehavephones.mobile.addons.AddonPrefs;
import com.wehavephones.mobile.addons.AddonsActivity;
import com.wehavephones.mobile.addons.model.AddonRecord;
import com.wehavephones.mobile.addons.model.InstallStatus;
import com.wehavephones.R;

import java.util.List;

public class AddonListFragment extends Fragment {
    private static final long AUTO_CHECK_INTERVAL_MS = 15 * 60 * 1000L;

    private AddonsActivity activity;
    private AddonManager manager;
    private LinearLayout rows;
    private TextView emptyView;
    private boolean scanned;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_addons_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        activity = (AddonsActivity)requireActivity();
        manager = activity.getManager();
        rows = view.findViewById(R.id.LLAddonRows);
        emptyView = view.findViewById(R.id.TVAddonsEmpty);
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
        scanThenAutoCheck();
    }

    /** Picks up folders written outside the manager, such as the ConsolePort auto-install. */
    private void scanThenAutoCheck() {
        manager.rescan(new AddonManager.Callback<Integer>() {
            @Override
            public void onSuccess(Integer count) {
                if (!isAdded()) return;
                scanned = true;
                refresh();
                maybeAutoCheck();
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                scanned = true;
                refresh();
                activity.showError(message);
            }
        });
    }

    public void refresh() {
        if (!isAdded() || manager == null || manager.isClosed()) return;
        Context context = requireContext();
        List<AddonRecord> records = manager.recordsForCurrentFlavor();
        rows.removeAllViews();
        emptyView.setVisibility(scanned && records.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = LayoutInflater.from(context);
        for (final AddonRecord record : records) {
            if (record.ignored && record.unrecognized) continue;
            View row = inflater.inflate(R.layout.wehavephones_addon_row, rows, false);
            bindRow(row, record);
            rows.addView(row);
        }
    }

    private void bindRow(View row, final AddonRecord record) {
        TextView name = row.findViewById(R.id.TVAddonName);
        TextView status = row.findViewById(R.id.TVAddonStatus);
        TextView meta = row.findViewById(R.id.TVAddonMeta);
        TextView folders = row.findViewById(R.id.TVAddonFolders);
        TextView auto = row.findViewById(R.id.TVAddonAuto);
        Button update = row.findViewById(R.id.BTAddonUpdate);
        Button toggle = row.findViewById(R.id.BTAddonToggle);
        Button details = row.findViewById(R.id.BTAddonDetails);
        Button delete = row.findViewById(R.id.BTAddonDelete);

        name.setText(record.displayName());
        status.setText(AddonUi.statusLabel(requireContext(), record.installStatus()));
        status.setTextColor(AddonUi.statusColor(requireContext(), record.installStatus()));

        String installed = record.installedVersion().isEmpty()
                ? getString(R.string.wehavephones_addons_version_unknown) : record.installedVersion();
        String provider = record.shownProvider().displayName;
        if (record.hasOfferedBinding() && !record.latestVersion.isEmpty()) {
            String latest = record.latestVersion;
            meta.setText(getString(R.string.wehavephones_addons_row_meta, installed, latest, provider));
        }
        else {
            meta.setText(getString(R.string.wehavephones_addons_row_meta_local, installed, provider));
        }
        if (record.installedInterface().isEmpty()) {
            folders.setText(getString(R.string.wehavephones_addons_row_folders, join(record.folderNames)));
        }
        else {
            folders.setText(getString(R.string.wehavephones_addons_row_folders_interface,
                    join(record.folderNames), record.installedInterface()));
        }
        if (!record.lastError.isEmpty()) {
            folders.setText(getString(R.string.wehavephones_addons_row_error, record.lastError));
        }
        AddonUi.showNote(auto, AddonUi.autoInstallNote(requireContext(), manager, record));

        update.setVisibility(record.hasOfferedBinding() && !record.unrecognized
                ? View.VISIBLE : View.GONE);
        update.setText(record.installedVersion().isEmpty()
                ? R.string.wehavephones_addons_install : R.string.wehavephones_addons_update);
        update.setOnClickListener((v) -> updateRecord(record));
        toggle.setVisibility(record.unrecognized ? View.GONE : View.VISIBLE);
        toggle.setText(record.enabled ? R.string.wehavephones_addons_disable : R.string.wehavephones_addons_enable);
        toggle.setOnClickListener((v) -> toggleRecord(record));
        details.setOnClickListener((v) -> activity.showDetail(record));
        delete.setVisibility(manager.isRequired(record) ? View.GONE : View.VISIBLE);
        delete.setOnClickListener((v) -> activity.confirmDelete(record));
        row.setOnClickListener((v) -> activity.showDetail(record));
    }

    private void updateRecord(final AddonRecord record) {
        activity.setBusy(true, getString(R.string.wehavephones_addons_updating, record.displayName()));
        manager.updateRecord(record, new AddonManager.Callback<String>() {
            @Override
            public void onSuccess(String installed) {
                activity.setBusy(false, null);
                activity.showStatus(getString(R.string.wehavephones_addons_install_done, record.displayName()));
                refresh();
            }

            @Override
            public void onError(String message) {
                activity.setBusy(false, null);
                activity.showError(message);
            }
        });
    }

    private void toggleRecord(final AddonRecord record) {
        activity.setBusy(true, null);
        manager.setEnabled(record, !record.enabled, new AddonManager.Callback<String>() {
            @Override
            public void onSuccess(String value) {
                activity.setBusy(false, null);
                activity.showStatus(record.enabled
                        ? getString(R.string.wehavephones_addons_enabled, record.displayName())
                        : getString(R.string.wehavephones_addons_disabled, record.displayName()));
                refresh();
            }

            @Override
            public void onError(String message) {
                activity.setBusy(false, null);
                activity.showError(message);
            }
        });
    }

    private void maybeAutoCheck() {
        if (!manager.index().autoCheckOnOpen) return;
        if (manager.recordsForCurrentFlavor().isEmpty()) return;
        long last = AddonPrefs.getLastUpdateAll(requireContext());
        if (System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL_MS) return;
        activity.setBusy(true, getString(R.string.wehavephones_addons_checking));
        manager.checkUpdates(false, new AddonManager.Callback<Integer>() {
            @Override
            public void onSuccess(Integer checked) {
                if (!isAdded()) return;
                activity.setBusy(false, null);
                activity.showStatus(getString(R.string.wehavephones_addons_check_done,
                        checked != null ? checked : 0));
                refresh();
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                activity.setBusy(false, null);
                activity.showError(message);
            }
        });
    }

    private static String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) builder.append(", ");
            builder.append(value);
        }
        return builder.toString();
    }
}
