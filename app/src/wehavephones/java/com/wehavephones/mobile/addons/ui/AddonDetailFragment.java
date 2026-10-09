package com.wehavephones.mobile.addons.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.wehavephones.mobile.addons.AddonManager;
import com.wehavephones.mobile.addons.AddonsActivity;
import com.wehavephones.mobile.addons.model.AddonRecord;
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
        if (record == null) {
            ((TextView)view.findViewById(R.id.TVDetailName)).setText(R.string.wehavephones_addons_empty);
            view.findViewById(R.id.BTDetailUpdate).setVisibility(View.GONE);
            view.findViewById(R.id.BTDetailToggle).setVisibility(View.GONE);
            view.findViewById(R.id.BTDetailDelete).setVisibility(View.GONE);
            return;
        }
        ((TextView)view.findViewById(R.id.TVDetailName)).setText(record.displayName());
        TextView status = view.findViewById(R.id.TVDetailStatus);
        status.setText(AddonUi.statusLabel(requireContext(), record.installStatus()));
        status.setTextColor(AddonUi.statusColor(requireContext(), record.installStatus()));
        AddonUi.showNote(view.findViewById(R.id.TVDetailAuto),
                AddonUi.autoInstallNote(requireContext(), manager, record));

        StringBuilder meta = new StringBuilder();
        appendLine(meta, R.string.wehavephones_addons_detail_provider, record.shownProvider().displayName
                + (!record.provider().offered || record.externalId.isEmpty()
                        ? "" : " (" + record.externalId + ")"));
        appendLine(meta, R.string.wehavephones_addons_detail_installed,
                record.installedVersion().isEmpty()
                        ? getString(R.string.wehavephones_addons_version_unknown) : record.installedVersion());
        if (!record.latestVersion.isEmpty()) {
            appendLine(meta, R.string.wehavephones_addons_detail_latest, record.latestVersion);
        }
        if (record.toc != null) {
            if (!record.toc.primaryInterface().isEmpty()) {
                appendLine(meta, R.string.wehavephones_addons_detail_interface, record.toc.primaryInterface());
            }
            if (!record.toc.author.isEmpty()) {
                appendLine(meta, R.string.wehavephones_addons_detail_author, record.toc.author);
            }
            if (!record.toc.website.isEmpty()) {
                appendLine(meta, R.string.wehavephones_addons_detail_website, record.toc.website);
            }
        }
        StringBuilder folders = new StringBuilder();
        for (String folder : record.folderNames) {
            if (folders.length() > 0) folders.append(", ");
            folders.append(folder);
        }
        appendLine(meta, R.string.wehavephones_addons_detail_folders, folders.toString());
        if (!record.lastError.isEmpty()) {
            appendLine(meta, R.string.wehavephones_addons_detail_error, record.lastError);
        }
        ((TextView)view.findViewById(R.id.TVDetailMeta)).setText(meta.toString());

        TextView changelog = view.findViewById(R.id.TVDetailChangelog);
        changelog.setText(R.string.wehavephones_addons_loading);

        Button update = view.findViewById(R.id.BTDetailUpdate);
        update.setVisibility(record.hasOfferedBinding() ? View.VISIBLE : View.GONE);
        update.setText(record.installedVersion().isEmpty()
                ? R.string.wehavephones_addons_install : R.string.wehavephones_addons_update);
        update.setOnClickListener((v) -> updateRecord());

        Button toggle = view.findViewById(R.id.BTDetailToggle);
        toggle.setText(record.enabled ? R.string.wehavephones_addons_disable : R.string.wehavephones_addons_enable);
        toggle.setOnClickListener((v) -> toggleRecord());

        view.findViewById(R.id.BTDetailDelete).setVisibility(manager.isRequired(record) ? View.GONE : View.VISIBLE);
        view.findViewById(R.id.BTDetailDelete).setOnClickListener((v) -> activity.confirmDelete(record));

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
                activity.showStatus(record.enabled
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
            }
        });
    }

    private void appendLine(StringBuilder builder, int labelRes, String value) {
        if (value == null || value.isEmpty()) return;
        if (builder.length() > 0) builder.append('\n');
        builder.append(getString(labelRes)).append(": ").append(value);
    }
}
