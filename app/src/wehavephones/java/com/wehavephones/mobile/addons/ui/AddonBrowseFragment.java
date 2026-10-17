package com.wehavephones.mobile.addons.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.wehavephones.mobile.addons.AddonManager;
import com.wehavephones.mobile.addons.AddonPrefs;
import com.wehavephones.mobile.addons.AddonsActivity;
import com.wehavephones.mobile.addons.model.AddonRecord;
import com.wehavephones.mobile.addons.model.ProviderType;
import com.wehavephones.mobile.addons.model.RemoteAddon;
import com.wehavephones.mobile.addons.provider.AddonProvider;
import com.wehavephones.R;

import java.util.ArrayList;
import java.util.List;

public class AddonBrowseFragment extends Fragment {
    private AddonsActivity activity;
    private AddonManager manager;
    private Spinner providerSpinner;
    private EditText queryField;
    private TextView statusView;
    private LinearLayout rows;
    private final ArrayList<ProviderType> providerTypes = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_addons_browse, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        activity = (AddonsActivity)requireActivity();
        manager = activity.getManager();
        providerSpinner = view.findViewById(R.id.SPBrowseProvider);
        queryField = view.findViewById(R.id.ETBrowseQuery);
        statusView = view.findViewById(R.id.TVBrowseStatus);
        rows = view.findViewById(R.id.LLBrowseRows);

        ArrayList<String> labels = new ArrayList<>();
        for (AddonProvider provider : manager.providers().all()) {
            if (!provider.type().isRemote()) continue;
            providerTypes.add(provider.type());
            labels.add(provider.type().displayName + (provider.isConfigured() ? "" : " (needs key)"));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                R.layout.wehavephones_spinner_item, labels);
        adapter.setDropDownViewResource(R.layout.wehavephones_spinner_dropdown_item);
        providerSpinner.setAdapter(adapter);
        // A picker only helps once more than one provider is offered.
        providerSpinner.setVisibility(providerTypes.size() > 1 ? View.VISIBLE : View.GONE);
        providerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View selectedView, int position, long id) {
                showHint();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        String saved = AddonPrefs.getSelectedProvider(requireContext());
        for (int i = 0; i < providerTypes.size(); i++) {
            if (providerTypes.get(i).id.equals(saved)) {
                providerSpinner.setSelection(i, false);
                break;
            }
        }

        showHint();
        view.findViewById(R.id.BTBrowseImport).setOnClickListener((v) -> activity.launchImportPicker());
        view.findViewById(R.id.BTBrowseSearch).setOnClickListener((v) -> search());
        queryField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search();
                return true;
            }
            return false;
        });
    }

    private void showHint() {
        ProviderType provider = selected();
        if (provider == ProviderType.WAGO) statusView.setText(R.string.wehavephones_addons_wago_note);
        else if (provider == ProviderType.GITHUB && providerTypes.size() == 1) {
            statusView.setText(R.string.wehavephones_addons_browse_hint_github);
        }
        else statusView.setText("");
    }

    private ProviderType selected() {
        int position = providerSpinner.getSelectedItemPosition();
        if (position < 0 || position >= providerTypes.size()) return ProviderType.GITHUB;
        return providerTypes.get(position);
    }

    private void search() {
        final ProviderType provider = selected();
        AddonPrefs.setSelectedProvider(requireContext(), provider.id);
        String query = queryField.getText().toString().trim();
        statusView.setText(R.string.wehavephones_addons_loading);
        rows.removeAllViews();
        activity.setBusy(true, getString(R.string.wehavephones_addons_loading));
        manager.search(provider, query, new AddonManager.Callback<List<RemoteAddon>>() {
            @Override
            public void onSuccess(List<RemoteAddon> addons) {
                if (!isAdded()) return;
                // The panel shows the result count, so the rail's Loading line clears.
                activity.setBusy(false, "");
                if (addons == null || addons.isEmpty()) {
                    statusView.setText(R.string.wehavephones_addons_no_results);
                    return;
                }
                statusView.setText(getString(R.string.wehavephones_addons_results, addons.size()));
                LayoutInflater inflater = LayoutInflater.from(requireContext());
                for (final RemoteAddon addon : addons) {
                    View row = inflater.inflate(R.layout.wehavephones_addon_search_row, rows, false);
                    bindResult(row, addon);
                    rows.addView(row);
                }
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                activity.setBusy(false, "");
                statusView.setText(getString(R.string.wehavephones_addons_error, message));
            }
        });
    }

    private void bindResult(View row, final RemoteAddon addon) {
        ((TextView)row.findViewById(R.id.TVSearchName)).setText(addon.name);
        StringBuilder author = new StringBuilder();
        if (!addon.author.isEmpty()) author.append(addon.author);
        if (addon.downloads > 0) {
            if (author.length() > 0) author.append("  \u00b7  ");
            author.append(addon.downloads).append(" downloads");
        }
        if (addon.latestVersion() != null && !addon.latestVersion().isEmpty()) {
            if (author.length() > 0) author.append("  \u00b7  ");
            author.append("v").append(addon.latestVersion());
        }
        ((TextView)row.findViewById(R.id.TVSearchAuthor)).setText(author.toString());
        TextView summary = row.findViewById(R.id.TVSearchSummary);
        summary.setText(addon.summary);
        summary.setVisibility(addon.summary == null || addon.summary.isEmpty() ? View.GONE : View.VISIBLE);
        Button install = row.findViewById(R.id.BTSearchInstall);
        install.setOnClickListener((v) -> install(row, addon));
        showInstalled(row, isInstalled(addon));
        // The row opens the project page, the only detail a search result has.
        final String url = websiteUrl(addon);
        row.setOnClickListener(url.isEmpty() ? null : (v) -> activity.openWebsite(url));
        row.setClickable(!url.isEmpty());
    }

    private static void showInstalled(View row, boolean installed) {
        row.findViewById(R.id.BTSearchInstall).setVisibility(installed ? View.GONE : View.VISIBLE);
        row.findViewById(R.id.TVSearchInstalled).setVisibility(installed ? View.VISIBLE : View.GONE);
    }

    private boolean isInstalled(RemoteAddon addon) {
        for (AddonRecord record : manager.recordsForCurrentFlavor()) {
            if (record.providerId.equals(addon.provider.id) && record.externalId.equalsIgnoreCase(addon.externalId)) {
                return true;
            }
        }
        return false;
    }

    private void install(final View row, final RemoteAddon addon) {
        activity.setBusy(true, getString(R.string.wehavephones_addons_updating, addon.name));
        manager.installAddon(addon, new AddonManager.Callback<String>() {
            @Override
            public void onSuccess(String installed) {
                if (!isAdded()) return;
                activity.setBusy(false, null);
                activity.showStatus(getString(R.string.wehavephones_addons_install_done, addon.name));
                showInstalled(row, true);
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

    private static String websiteUrl(RemoteAddon addon) {
        if (addon.website != null && !addon.website.isEmpty()) return addon.website;
        if (addon.provider == ProviderType.GITHUB) return "https://github.com/" + addon.externalId;
        if (addon.provider == ProviderType.CURSEFORGE) {
            return "https://www.curseforge.com/wow/addons/" + addon.externalId;
        }
        if (addon.provider == ProviderType.WAGO) return "https://addons.wago.io/addons/" + addon.externalId;
        return "";
    }
}
