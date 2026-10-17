package com.wehavephones.mobile.addons.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;

import com.wehavephones.mobile.addons.AddonManager;
import com.wehavephones.mobile.addons.AddonPrefs;
import com.wehavephones.mobile.addons.AddonsActivity;
import com.wehavephones.mobile.addons.model.AddonRecord;
import com.wehavephones.mobile.addons.model.InstallStatus;
import com.wehavephones.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Installed tab: one compact row per addon with an enable switch, its version
 * and source, and one status or Update button. Tapping a row opens details.
 */
public class AddonListFragment extends Fragment {
    private static final long AUTO_CHECK_INTERVAL_MS = 15 * 60 * 1000L;
    private static final int MENU_CHECK = 1;
    private static final int MENU_RESCAN = 2;
    private static final int MENU_IMPORT = 3;

    private enum Filter { ALL, UPDATES, DISABLED }

    private AddonsActivity activity;
    private AddonManager manager;
    private LinearLayout rows;
    private TextView emptyView;
    private TextView summaryView;
    private Button updateAllButton;
    private View filtersView;
    private TextView filterAll;
    private TextView filterUpdates;
    private TextView filterDisabled;
    private Filter filter = Filter.ALL;
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
        summaryView = view.findViewById(R.id.TVAddonsSummary);
        updateAllButton = view.findViewById(R.id.BTAddonsUpdateAll);
        filtersView = view.findViewById(R.id.LLAddonFilters);
        filterAll = view.findViewById(R.id.TVFilterAll);
        filterUpdates = view.findViewById(R.id.TVFilterUpdates);
        filterDisabled = view.findViewById(R.id.TVFilterDisabled);

        updateAllButton.setOnClickListener((v) -> activity.updateAll());
        view.findViewById(R.id.BTAddonsMore).setOnClickListener(this::showMoreMenu);
        filterAll.setOnClickListener((v) -> setFilter(Filter.ALL));
        filterUpdates.setOnClickListener((v) -> setFilter(Filter.UPDATES));
        filterDisabled.setOnClickListener((v) -> setFilter(Filter.DISABLED));
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
        scanThenAutoCheck();
    }

    /** Picks up folders written outside the manager, such as the ConsolePort auto-install or an addon app. */
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

    private void showMoreMenu(View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(Menu.NONE, MENU_CHECK, 0, R.string.wehavephones_addons_check_updates);
        menu.getMenu().add(Menu.NONE, MENU_RESCAN, 1, R.string.wehavephones_addons_rescan);
        menu.getMenu().add(Menu.NONE, MENU_IMPORT, 2, R.string.wehavephones_addons_import_ellipsis);
        menu.setOnMenuItemClickListener((item) -> {
            if (item.getItemId() == MENU_CHECK) activity.checkUpdates();
            else if (item.getItemId() == MENU_RESCAN) activity.rescan();
            else if (item.getItemId() == MENU_IMPORT) activity.launchImportPicker();
            return true;
        });
        menu.show();
    }

    private void setFilter(Filter value) {
        filter = value;
        refresh();
    }

    public void refresh() {
        if (!isAdded() || manager == null || manager.isClosed()) return;
        Context context = requireContext();
        ArrayList<AddonRecord> shown = new ArrayList<>();
        ArrayList<AddonRecord> required = new ArrayList<>();
        int total = 0;
        int updates = 0;
        int disabled = 0;
        for (AddonRecord record : manager.recordsForCurrentFlavor()) {
            if (record.ignored && record.unrecognized) continue;
            total++;
            boolean hasUpdate = record.installStatus() == InstallStatus.UPDATE;
            if (hasUpdate) updates++;
            if (!record.enabled) disabled++;
            if (filter == Filter.UPDATES && !hasUpdate) continue;
            if (filter == Filter.DISABLED && record.enabled) continue;
            if (manager.isRequired(record)) required.add(record);
            else shown.add(record);
        }
        // Updates first, then by name.
        shown.sort((a, b) -> {
            boolean ua = a.installStatus() == InstallStatus.UPDATE;
            boolean ub = b.installStatus() == InstallStatus.UPDATE;
            if (ua != ub) return ua ? -1 : 1;
            return a.displayName().compareToIgnoreCase(b.displayName());
        });

        String count = getResources().getQuantityString(R.plurals.wehavephones_addons_count, total, total);
        summaryView.setText(updates == 0 ? count : getString(R.string.wehavephones_addons_summary, count,
                getResources().getQuantityString(R.plurals.wehavephones_addons_updates_count, updates, updates)));
        summaryView.setVisibility(scanned || total > 0 ? View.VISIBLE : View.INVISIBLE);
        updateAllButton.setVisibility(updates > 0 ? View.VISIBLE : View.GONE);
        updateAllButton.setText(getString(R.string.wehavephones_addons_update_all_count, updates));

        // A chip only appears when it would narrow the list, and All only beside another chip.
        if (filter == Filter.UPDATES && updates == 0 || filter == Filter.DISABLED && disabled == 0) {
            filter = Filter.ALL;
            refresh();
            return;
        }
        filterUpdates.setVisibility(updates > 0 ? View.VISIBLE : View.GONE);
        filterDisabled.setVisibility(disabled > 0 ? View.VISIBLE : View.GONE);
        filtersView.setVisibility(updates > 0 || disabled > 0 ? View.VISIBLE : View.GONE);
        filterAll.setText(getString(R.string.wehavephones_addons_filter_all, total));
        filterUpdates.setText(getString(R.string.wehavephones_addons_filter_updates, updates));
        filterDisabled.setText(getString(R.string.wehavephones_addons_filter_disabled, disabled));
        filterAll.setSelected(filter == Filter.ALL);
        filterUpdates.setSelected(filter == Filter.UPDATES);
        filterDisabled.setSelected(filter == Filter.DISABLED);

        rows.removeAllViews();
        boolean empty = shown.isEmpty() && required.isEmpty();
        emptyView.setText(total == 0 ? R.string.wehavephones_addons_empty : R.string.wehavephones_addons_filter_empty);
        emptyView.setVisibility(empty && (scanned || total > 0) ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = LayoutInflater.from(context);
        for (AddonRecord record : shown) addRow(inflater, record);
        if (!required.isEmpty()) {
            TextView group = (TextView)inflater.inflate(R.layout.wehavephones_addon_group, rows, false);
            group.setText(R.string.wehavephones_addons_group_required);
            rows.addView(group);
            for (AddonRecord record : required) addRow(inflater, record);
        }
        activity.refreshUpdateBadge();
    }

    private void addRow(LayoutInflater inflater, AddonRecord record) {
        View row = inflater.inflate(R.layout.wehavephones_addon_row, rows, false);
        bindRow(row, record);
        rows.addView(row);
    }

    private void bindRow(View row, final AddonRecord record) {
        Context context = requireContext();
        SwitchCompat enabled = row.findViewById(R.id.SWAddonEnabled);
        TextView name = row.findViewById(R.id.TVAddonName);
        TextView meta = row.findViewById(R.id.TVAddonMeta);
        TextView status = row.findViewById(R.id.TVAddonStatus);
        Button update = row.findViewById(R.id.BTAddonUpdate);

        name.setText(record.displayName());
        String installed = record.installedVersion().isEmpty()
                ? getString(R.string.wehavephones_addons_version_unknown) : record.installedVersion();
        String source = AddonUi.sourceLabel(record);
        boolean hasUpdate = record.installStatus() == InstallStatus.UPDATE;
        if (hasUpdate && !record.latestVersion.isEmpty()) {
            meta.setText(getString(R.string.wehavephones_addons_row_meta, installed, record.latestVersion, source));
        }
        else {
            meta.setText(getString(R.string.wehavephones_addons_row_meta_local, installed, source));
        }

        // Unrecognized folders have nothing to rename, so they cannot be switched off.
        enabled.setVisibility(record.unrecognized ? View.INVISIBLE : View.VISIBLE);
        enabled.setChecked(record.enabled);
        enabled.setOnCheckedChangeListener((button, checked) -> {
            if (checked != record.enabled) toggleRecord(record);
        });
        row.setAlpha(record.enabled ? 1f : 0.6f);

        boolean showUpdate = hasUpdate && AddonUi.canUpdate(record);
        update.setVisibility(showUpdate ? View.VISIBLE : View.GONE);
        update.setOnClickListener((v) -> updateRecord(record));
        status.setVisibility(showUpdate ? View.GONE : View.VISIBLE);
        InstallStatus shown = AddonUi.rowStatus(record);
        status.setText(AddonUi.statusLabel(context, shown));
        status.setTextColor(AddonUi.statusColor(context, shown));
        status.setCompoundDrawablesRelativeWithIntrinsicBounds(
                shown == InstallStatus.UP_TO_DATE ? R.drawable.wehavephones_ic_check : 0, 0, 0, 0);
        status.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(
                AddonUi.statusColor(context, shown)));
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
                if (!isAdded()) return;
                activity.setBusy(false, null);
                activity.showStatus("enabled".equals(value)
                        ? getString(R.string.wehavephones_addons_enabled, record.displayName())
                        : getString(R.string.wehavephones_addons_disabled, record.displayName()));
                refresh();
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                activity.setBusy(false, null);
                activity.showError(message);
                refresh();
            }
        });
    }

    private void maybeAutoCheck() {
        if (!manager.index().autoCheckOnOpen) return;
        List<AddonRecord> records = manager.recordsForCurrentFlavor();
        if (records.isEmpty()) return;
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
}
