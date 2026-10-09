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
        providerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View selectedView, int position, long id) {
                if (selected() == ProviderType.WAGO) {
                    statusView.setText(R.string.wehavephones_addons_wago_note);
                }
                else {
                    statusView.setText("");
                }
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

        view.findViewById(R.id.BTBrowseSearch).setOnClickListener((v) -> search());
        queryField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search();
                return true;
            }
            return false;
        });
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
                activity.setBusy(false, null);
                if (addons == null || addons.isEmpty()) {
                    statusView.setText(R.string.wehavephones_addons_no_results);
                    return;
                }
                statusView.setText(getString(R.string.wehavephones_addons_scan_done, addons.size()));
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
                activity.setBusy(false, null);
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
        ((TextView)row.findViewById(R.id.TVSearchSummary)).setText(addon.summary);
        row.findViewById(R.id.BTSearchInstall).setOnClickListener((v) -> install(addon));
        Button website = row.findViewById(R.id.BTSearchWebsite);
        final String url = websiteUrl(addon);
        website.setVisibility(url.isEmpty() ? View.GONE : View.VISIBLE);
        website.setOnClickListener((v) -> activity.openWebsite(url));
    }

    private void install(final RemoteAddon addon) {
        activity.setBusy(true, getString(R.string.wehavephones_addons_updating, addon.name));
        manager.installAddon(addon, new AddonManager.Callback<String>() {
            @Override
            public void onSuccess(String installed) {
                if (!isAdded()) return;
                activity.setBusy(false, null);
                activity.showStatus(getString(R.string.wehavephones_addons_install_done, addon.name));
                activity.showList();
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
