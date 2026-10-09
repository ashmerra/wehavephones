package com.wehavephones.mobile.addons.ui;

import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.wehavephones.mobile.addons.AddonManager;
import com.wehavephones.mobile.addons.AddonsActivity;
import com.wehavephones.mobile.addons.model.ProviderType;
import com.wehavephones.mobile.addons.provider.AddonSecrets;
import com.wehavephones.mobile.addons.required.ConsolePortUpdater;
import com.wehavephones.R;

public class AddonSettingsFragment extends Fragment {
    private AddonsActivity activity;
    private AddonManager manager;
    private TextView statusView;
    private EditText curseForgeField;
    private EditText wagoField;
    private EditText gitHubField;
    private CheckBox preferNolib;
    private CheckBox autoCheck;
    private CheckBox confirmDelete;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_addons_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        activity = (AddonsActivity)requireActivity();
        manager = activity.getManager();
        statusView = view.findViewById(R.id.TVSettingsStatus);
        curseForgeField = view.findViewById(R.id.ETCurseForgeKey);
        wagoField = view.findViewById(R.id.ETWagoToken);
        gitHubField = view.findViewById(R.id.ETGitHubPat);
        preferNolib = view.findViewById(R.id.CBPreferNolib);
        autoCheck = view.findViewById(R.id.CBAutoCheck);
        confirmDelete = view.findViewById(R.id.CBConfirmDelete);

        showOffered(view.findViewById(R.id.LLCurseForgeKey), ProviderType.CURSEFORGE);
        showOffered(view.findViewById(R.id.LLWagoKey), ProviderType.WAGO);
        showConfigured(curseForgeField, ProviderType.CURSEFORGE);
        showConfigured(wagoField, ProviderType.WAGO);
        showConfigured(gitHubField, ProviderType.GITHUB);

        preferNolib.setChecked(manager.index().preferNolib);
        autoCheck.setChecked(manager.index().autoCheckOnOpen);
        confirmDelete.setChecked(manager.index().confirmDelete);

        view.findViewById(R.id.BTCurseForgeSave).setOnClickListener((v) ->
                saveKey(ProviderType.CURSEFORGE, curseForgeField));
        view.findViewById(R.id.BTCurseForgeTest).setOnClickListener((v) ->
                testKey(ProviderType.CURSEFORGE));
        view.findViewById(R.id.BTCurseForgeClear).setOnClickListener((v) ->
                clearKey(ProviderType.CURSEFORGE, curseForgeField));

        view.findViewById(R.id.BTWagoSave).setOnClickListener((v) ->
                saveKey(ProviderType.WAGO, wagoField));
        view.findViewById(R.id.BTWagoTest).setOnClickListener((v) ->
                testKey(ProviderType.WAGO));
        view.findViewById(R.id.BTWagoClear).setOnClickListener((v) ->
                clearKey(ProviderType.WAGO, wagoField));

        view.findViewById(R.id.BTGitHubSave).setOnClickListener((v) ->
                saveKey(ProviderType.GITHUB, gitHubField));
        view.findViewById(R.id.BTGitHubTest).setOnClickListener((v) ->
                testKey(ProviderType.GITHUB));
        view.findViewById(R.id.BTGitHubClear).setOnClickListener((v) ->
                clearKey(ProviderType.GITHUB, gitHubField));

        Button save = view.findViewById(R.id.BTSettingsSave);
        save.setOnClickListener((v) -> {
            manager.updateSettings(preferNolib.isChecked(), autoCheck.isChecked(),
                    confirmDelete.isChecked(), manager.index().wowRootOverride);
            statusView.setText(R.string.wehavephones_addons_settings_saved);
        });

        showConsolePortStatus(view);
        view.findViewById(R.id.BTConsolePortCheck).setOnClickListener((v) -> checkConsolePort());
    }

    private void showConsolePortStatus(View view) {
        String version = ConsolePortUpdater.availableVersion(requireContext());
        long checkedAt = ConsolePortUpdater.lastCheckAt(requireContext());
        String checked = checkedAt > 0
                ? DateUtils.getRelativeTimeSpanString(checkedAt).toString()
                : getString(R.string.wehavephones_addons_consoleport_never);
        ((TextView)view.findViewById(R.id.TVConsolePortStatus)).setText(getString(
                R.string.wehavephones_addons_consoleport_status,
                version.isEmpty() ? getString(R.string.wehavephones_addons_version_unknown) : version, checked));
    }

    private void checkConsolePort() {
        activity.setBusy(true, getString(R.string.wehavephones_addons_consoleport_checking));
        ConsolePortUpdater.checkNow(requireContext(), new AddonManager.Callback<ConsolePortUpdater.Report>() {
            @Override
            public void onSuccess(ConsolePortUpdater.Report report) {
                if (!isAdded()) return;
                activity.setBusy(false, null);
                statusView.setText(report.summary());
                showConsolePortStatus(requireView());
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                activity.setBusy(false, null);
                statusView.setText(getString(R.string.wehavephones_addons_error, message));
            }
        });
    }

    private static void showOffered(View section, ProviderType type) {
        section.setVisibility(type.offered ? View.VISIBLE : View.GONE);
    }

    private void showConfigured(EditText field, ProviderType type) {
        if (AddonSecrets.has(requireContext(), type)) {
            field.setHint(R.string.wehavephones_addons_key_configured);
        }
    }

    private void saveKey(final ProviderType type, EditText field) {
        final String value = field.getText().toString();
        activity.setBusy(true, null);
        manager.saveProviderKey(type, value, new AddonManager.Callback<String>() {
            @Override
            public void onSuccess(String result) {
                if (!isAdded()) return;
                activity.setBusy(false, null);
                field.setText("");
                statusView.setText(R.string.wehavephones_addons_key_saved);
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                activity.setBusy(false, null);
                statusView.setText(getString(R.string.wehavephones_addons_error, message));
            }
        });
    }

    private void clearKey(final ProviderType type, final EditText field) {
        activity.setBusy(true, null);
        manager.saveProviderKey(type, "", new AddonManager.Callback<String>() {
            @Override
            public void onSuccess(String result) {
                if (!isAdded()) return;
                activity.setBusy(false, null);
                field.setText("");
                statusView.setText(R.string.wehavephones_addons_key_cleared);
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                activity.setBusy(false, null);
                statusView.setText(getString(R.string.wehavephones_addons_error, message));
            }
        });
    }

    private void testKey(final ProviderType type) {
        activity.setBusy(true, getString(R.string.wehavephones_addons_loading));
        manager.testProvider(type, new AddonManager.Callback<String>() {
            @Override
            public void onSuccess(String result) {
                if (!isAdded()) return;
                activity.setBusy(false, null);
                statusView.setText(getString(R.string.wehavephones_addons_key_ok_provider,
                        getString(R.string.wehavephones_addons_key_ok), type.displayName));
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                activity.setBusy(false, null);
                statusView.setText(getString(R.string.wehavephones_addons_key_test_failed, message));
            }
        });
    }
}
