package com.wehavephones.mobile.addons.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.wehavephones.mobile.WowProduct;
import com.wehavephones.mobile.addons.AddonManager;
import com.wehavephones.mobile.addons.AddonsActivity;
import com.wehavephones.mobile.addons.model.ProviderType;
import com.wehavephones.mobile.addons.provider.AddonSecrets;
import com.wehavephones.mobile.addons.required.ConsolePortUpdater;
import com.wehavephones.R;

import java.io.File;

/**
 * Settings tab: update preferences (applied at once), the AddOns folder,
 * provider keys behind one Set button each, and ConsolePort.
 */
public class AddonSettingsFragment extends Fragment {
    private static final ProviderType[] KEYED_PROVIDERS = {
            ProviderType.GITHUB, ProviderType.CURSEFORGE, ProviderType.WAGO};

    private AddonsActivity activity;
    private AddonManager manager;
    private CheckBox preferNolib;
    private CheckBox autoCheck;
    private CheckBox confirmDelete;
    private LinearLayout keyRows;

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
        preferNolib = view.findViewById(R.id.CBPreferNolib);
        autoCheck = view.findViewById(R.id.CBAutoCheck);
        confirmDelete = view.findViewById(R.id.CBConfirmDelete);
        keyRows = view.findViewById(R.id.LLProviderKeys);

        preferNolib.setChecked(manager.index().preferNolib);
        autoCheck.setChecked(manager.index().autoCheckOnOpen);
        confirmDelete.setChecked(manager.index().confirmDelete);
        preferNolib.setOnCheckedChangeListener((button, checked) -> saveSettings());
        autoCheck.setOnCheckedChangeListener((button, checked) -> saveSettings());
        confirmDelete.setOnCheckedChangeListener((button, checked) -> saveSettings());

        showFolder(view);
        showKeyRows();
        showConsolePortStatus(view);
        view.findViewById(R.id.BTConsolePortCheck).setOnClickListener((v) -> checkConsolePort());
    }

    private void saveSettings() {
        manager.updateSettings(preferNolib.isChecked(), autoCheck.isChecked(),
                confirmDelete.isChecked(), manager.index().wowRootOverride);
    }

    private void showFolder(View view) {
        WowProduct product = manager.selectedProduct();
        TextView interfaceView = view.findViewById(R.id.TVSettingsInterface);
        interfaceView.setVisibility(product != null ? View.VISIBLE : View.GONE);
        if (product != null) {
            interfaceView.setText(getString(R.string.wehavephones_addons_product_line,
                    product.describe(), manager.interfaceNumber()));
        }
        File addonsDir = manager.addonsDir();
        TextView path = view.findViewById(R.id.TVSettingsPath);
        Button copy = view.findViewById(R.id.BTSettingsCopyPath);
        if (addonsDir == null) {
            path.setText(R.string.wehavephones_addons_missing);
            copy.setVisibility(View.GONE);
            return;
        }
        final String full = addonsDir.getPath();
        // Shortened at the start, so the game folder and AddOns stay readable.
        int game = full.indexOf("/World of Warcraft/");
        path.setText(game > 0 ? "…" + full.substring(game) : full);
        copy.setOnClickListener((v) -> {
            ClipboardManager clipboard = (ClipboardManager)requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.wehavephones_addons_path), full));
            activity.showStatus(getString(R.string.wehavephones_addons_path_copied));
        });
    }

    private void showKeyRows() {
        keyRows.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (ProviderType type : KEYED_PROVIDERS) {
            if (!type.offered) continue;
            View row = inflater.inflate(R.layout.wehavephones_addon_key_row, keyRows, false);
            boolean saved = AddonSecrets.has(requireContext(), type);
            ((TextView)row.findViewById(R.id.TVKeyName)).setText(keyLabel(type));
            ((TextView)row.findViewById(R.id.TVKeyState)).setText(saved
                    ? R.string.wehavephones_addons_key_state_saved : R.string.wehavephones_addons_key_state_none);
            Button test = row.findViewById(R.id.BTKeyTest);
            test.setVisibility(saved ? View.VISIBLE : View.GONE);
            test.setOnClickListener((v) -> testKey(type));
            Button set = row.findViewById(R.id.BTKeySet);
            set.setText(saved ? R.string.wehavephones_addons_change_key : R.string.wehavephones_addons_set_key);
            set.setOnClickListener((v) -> showKeyDialog(type, saved));
            keyRows.addView(row);
        }
    }

    private static int keyLabel(ProviderType type) {
        switch (type) {
            case CURSEFORGE: return R.string.wehavephones_addons_curseforge_key;
            case WAGO: return R.string.wehavephones_addons_wago_token;
            case GITHUB:
            default: return R.string.wehavephones_addons_github_pat;
        }
    }

    private void showKeyDialog(final ProviderType type, boolean saved) {
        Context context = requireContext();
        final EditText field = new EditText(context);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setSingleLine(true);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        field.setHint(saved ? R.string.wehavephones_addons_key_configured : R.string.wehavephones_addons_key_hint);
        FrameLayout frame = new FrameLayout(context);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        frame.setPadding(padding, padding / 2, padding, 0);
        frame.addView(field);
        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(keyLabel(type))
                .setView(frame)
                .setNegativeButton(R.string.wehavephones_addons_cancel, null)
                .setPositiveButton(R.string.wehavephones_addons_save_key, (dialog, which) -> {
                    // An empty field keeps the saved key. Clear removes it.
                    String value = field.getText().toString().trim();
                    if (!value.isEmpty()) saveKey(type, value, R.string.wehavephones_addons_key_saved);
                });
        if (saved) {
            builder.setNeutralButton(R.string.wehavephones_addons_clear_key, (dialog, which) ->
                    saveKey(type, "", R.string.wehavephones_addons_key_cleared));
        }
        builder.show();
    }

    private void saveKey(final ProviderType type, final String value, final int doneMessage) {
        activity.setBusy(true, null);
        manager.saveProviderKey(type, value, new AddonManager.Callback<String>() {
            @Override
            public void onSuccess(String result) {
                if (!isAdded()) return;
                activity.setBusy(false, null);
                activity.showStatus(getString(doneMessage));
                showKeyRows();
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                activity.setBusy(false, null);
                activity.showError(message);
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
                activity.showStatus(getString(R.string.wehavephones_addons_key_ok_provider,
                        getString(R.string.wehavephones_addons_key_ok), type.displayName));
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                activity.setBusy(false, null);
                activity.showStatus(getString(R.string.wehavephones_addons_key_test_failed, message));
            }
        });
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
                activity.showStatus(report.summary());
                showConsolePortStatus(requireView());
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
