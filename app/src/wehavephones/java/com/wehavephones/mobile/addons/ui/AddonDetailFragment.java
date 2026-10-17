package com.wehavephones.mobile.addons.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;

import com.wehavephones.mobile.addons.AddonManager;
import com.wehavephones.mobile.addons.AddonsActivity;
import com.wehavephones.mobile.addons.model.AddonRecord;
import com.wehavephones.mobile.addons.model.InstallStatus;
import com.wehavephones.R;

public class AddonDetailFragment extends Fragment {
    private static final String ARG_RECORD_ID = "record_id";

    private AddonsActivity activity;
    private AddonManager manager;
    private AddonRecord record;

    public static AddonDetailFragment newInstance(String recordId) {
        AddonDetailFragment fragment = new AddonDetailFragment();
        Bundle args = new Bundle();
        args.putString(ARG_RECORD_ID, recordId);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_addons_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        activity = (AddonsActivity)requireActivity();
        manager = activity.getManager();
        String id = getArguments() != null ? getArguments().getString(ARG_RECORD_ID) : null;
        record = manager.findRecord(id);
        render(view);
    }

    private void render(View view) {
        view.findViewById(R.id.TVDetailBack).setOnClickListener((v) -> activity.closeScreen());
        Button update = view.findViewById(R.id.BTDetailUpdate);
        Button website = view.findViewById(R.id.BTDetailWebsite);
        Button delete = view.findViewById(R.id.BTDetailDelete);
        if (record == null) {
            ((TextView)view.findViewById(R.id.TVDetailName)).setText(R.string.wehavephones_addons_empty);
            update.setVisibility(View.GONE);
            website.setVisibility(View.GONE);
            delete.setVisibility(View.GONE);
            view.findViewById(R.id.LLDetailEnabled).setVisibility(View.GONE);
            return;
        }
        ((TextView)view.findViewById(R.id.TVDetailName)).setText(record.displayName());
        String author = record.toc != null ? record.toc.author : "";
        ((TextView)view.findViewById(R.id.TVDetailAuthor)).setText(author.isEmpty() ? ""
                : getString(R.string.wehavephones_addons_by_author, author));
        AddonUi.showNote(view.findViewById(R.id.TVDetailAuto),
                AddonUi.autoInstallNote(requireContext(), manager, record));

        StringBuilder meta = new StringBuilder();
        InstallStatus shown = AddonUi.rowStatus(record);
        appendLine(meta, R.string.wehavephones_addons_detail_status, AddonUi.statusLabel(requireContext(), shown));
        appendLine(meta, R.string.wehavephones_addons_detail_installed,
                record.installedVersion().isEmpty()
                        ? getString(R.string.wehavephones_addons_version_unknown) : record.installedVersion());
        if (!record.latestVersion.isEmpty()) {
            appendLine(meta, R.string.wehavephones_addons_detail_latest, record.latestVersion);
        }
        appendLine(meta, R.string.wehavephones_addons_detail_provider, AddonUi.sourceLabel(record)
                + (record.hasProviderBinding() ? " \u00b7 " + record.externalId : ""));
        if (record.toc != null && !record.toc.primaryInterface().isEmpty()) {
            appendLine(meta, R.string.wehavephones_addons_detail_interface, record.toc.primaryInterface());
        }
        StringBuilder folders = new StringBuilder();
        for (String folder : record.folderNames) {
            if (folders.length() > 0) folders.append(", ");
            folders.append(folder);
        }
        appendLine(meta, R.string.wehavephones_addons_detail_folders, folders.toString());
        ((TextView)view.findViewById(R.id.TVDetailMeta)).setText(meta.toString());

        TextView error = view.findViewById(R.id.TVDetailError);
        error.setVisibility(record.lastError.isEmpty() ? View.GONE : View.VISIBLE);
        error.setText(getString(R.string.wehavephones_addons_row_error, record.lastError));

        View enabledRow = view.findViewById(R.id.LLDetailEnabled);
        enabledRow.setVisibility(record.unrecognized ? View.GONE : View.VISIBLE);
        SwitchCompat enabled = view.findViewById(R.id.SWDetailEnabled);
        enabled.setOnCheckedChangeListener(null);
        enabled.setChecked(record.enabled);
        enabled.setOnCheckedChangeListener((button, checked) -> {
            if (checked != record.enabled) toggleRecord();
        });

        TextView changelog = view.findViewById(R.id.TVDetailChangelog);
        changelog.setText(R.string.wehavephones_addons_loading);

        update.setVisibility(AddonUi.canUpdate(record) ? View.VISIBLE : View.GONE);
        if (record.installedVersion().isEmpty()) {
            update.setText(R.string.wehavephones_addons_install);
        }
        else if (record.installStatus() == InstallStatus.UPDATE && !record.latestVersion.isEmpty()) {
            update.setText(getString(R.string.wehavephones_addons_update_to, record.latestVersion));
        }
        else {
            update.setText(R.string.wehavephones_addons_update);
        }
        update.setOnClickListener((v) -> updateRecord());

        final String url = AddonUi.websiteUrl(record);
        website.setVisibility(url.isEmpty() ? View.GONE : View.VISIBLE);
        website.setOnClickListener((v) -> activity.openWebsite(url));

        // Required addons are reinstalled before every Play, so they offer Disable instead.
        delete.setVisibility(manager.isRequired(record) ? View.GONE : View.VISIBLE);
        delete.setOnClickListener((v) -> activity.confirmDelete(record));

        manager.loadChangelog(record, new AddonManager.Callback<String>() {
            @Override
            public void onSuccess(String value) {
                if (!isAdded()) return;
                TextView target = requireView().findViewById(R.id.TVDetailChangelog);
                target.setText(value == null || value.isEmpty()
                        ? getString(R.string.wehavephones_addons_changelog_empty) : value);
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                TextView target = requireView().findViewById(R.id.TVDetailChangelog);
                target.setText(getString(R.string.wehavephones_addons_changelog_empty));
            }
        });
    }

    private void updateRecord() {
        activity.setBusy(true, getString(R.string.wehavephones_addons_updating, record.displayName()));
        manager.updateRecord(record, new AddonManager.Callback<String>() {
            @Override
            public void onSuccess(String installed) {
                if (!isAdded()) return;
                activity.setBusy(false, null);
                activity.showStatus(getString(R.string.wehavephones_addons_install_done, record.displayName()));
                record = manager.findRecord(record.id);
                render(requireView());
                activity.refreshUpdateBadge();
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                activity.setBusy(false, null);
                activity.showError(message);
            }
        });
    }

    private void toggleRecord() {
        activity.setBusy(true, null);
        manager.setEnabled(record, !record.enabled, new AddonManager.Callback<String>() {
            @Override
            public void onSuccess(String value) {
                if (!isAdded()) return;
                activity.setBusy(false, null);
                activity.showStatus("enabled".equals(value)
                        ? getString(R.string.wehavephones_addons_enabled, record.displayName())
                        : getString(R.string.wehavephones_addons_disabled, record.displayName()));
                record = manager.findRecord(record.id);
                render(requireView());
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                activity.setBusy(false, null);
                activity.showError(message);
                render(requireView());
            }
        });
    }

    private void appendLine(StringBuilder builder, int labelRes, String value) {
        if (value == null || value.isEmpty()) return;
        if (builder.length() > 0) builder.append('\n');
        builder.append(getString(labelRes)).append(": ").append(value);
    }
}
