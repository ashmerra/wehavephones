package com.wehavephones.mobile.addons;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.wehavephones.mobile.CurseForgeLauncher;
import com.wehavephones.mobile.WowProduct;
import com.wehavephones.mobile.addons.install.DiscoveredAddon;
import com.wehavephones.mobile.addons.model.AddonRecord;
import com.wehavephones.mobile.addons.model.ProviderType;
import com.wehavephones.mobile.addons.model.TocData;
import com.wehavephones.mobile.addons.ui.AddonBrowseFragment;
import com.wehavephones.mobile.addons.ui.AddonDetailFragment;
import com.wehavephones.mobile.addons.ui.AddonListFragment;
import com.wehavephones.mobile.addons.ui.AddonSettingsFragment;
import com.wehavephones.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Dedicated addon manager host: product selector, shared status/progress bar
 * and fragment screens (list, browse, detail, provider settings).
 */
public class AddonsActivity extends AppCompatActivity {
    private static final int REQUEST_IMPORT_ZIP = 41;

    private AddonManager manager;
    private Spinner productSpinner;
    private TextView pathView;
    private TextView statusView;
    private ProgressBar progressBar;
    private boolean bindingProductSpinner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_addons);
        manager = new AddonManager(this);

        productSpinner = findViewById(R.id.SPAddonProduct);
        pathView = findViewById(R.id.TVAddonsPath);
        statusView = findViewById(R.id.TVAddonsStatus);
        progressBar = findViewById(R.id.PBAddons);

        productSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (bindingProductSpinner) return;
                WowProduct product = productAt(position);
                if (product == null) return;
                manager.selectProduct(product.directoryName);
                refreshProductUi();
                refreshCurrentFragment();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        findViewById(R.id.BTAddonsSettings).setOnClickListener((v) -> showScreen(new AddonSettingsFragment()));
        findViewById(R.id.BTAddonsRescan).setOnClickListener((v) -> rescan());
        findViewById(R.id.BTAddonsUpdateAll).setOnClickListener((v) -> updateAll());
        findViewById(R.id.BTAddonsBrowse).setOnClickListener((v) -> showScreen(new AddonBrowseFragment()));
        findViewById(R.id.BTAddonsImport).setOnClickListener((v) -> launchImportPicker());
        // CurseForge's own app changes AddOns, and the list rescans when shown again.
        findViewById(R.id.BTAddonsCurseForge).setOnClickListener((v) ->
                CurseForgeLauncher.open(this, (message, error) -> showStatus(message)));

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.FLAddonsScreen, new AddonListFragment())
                    .commit();
        }
        refreshProductUi();
    }

    @Override
    protected void onDestroy() {
        if (manager != null) manager.shutdown();
        super.onDestroy();
    }

    public AddonManager getManager() {
        return manager;
    }

    public void showScreen(Fragment fragment) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        fragmentManager.beginTransaction()
                .replace(R.id.FLAddonsScreen, fragment)
                .addToBackStack(null)
                .commit();
    }

    public void showList() {
        getSupportFragmentManager().popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
    }

    @Override
    public void onBackPressed() {
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (fragmentManager.getBackStackEntryCount() > 0) {
            fragmentManager.popBackStack();
        }
        else {
            super.onBackPressed();
        }
    }

    public void setBusy(boolean busy, String message) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (message != null) statusView.setText(message);
    }

    public void showStatus(String message) {
        statusView.setText(message != null ? message : "");
    }

    public void showError(String message) {
        statusView.setText(getString(R.string.wehavephones_addons_error,
                message != null ? message : "unknown error"));
    }

    public void refreshCurrentFragment() {
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.FLAddonsScreen);
        if (current instanceof AddonListFragment) {
            ((AddonListFragment)current).refresh();
        }
    }

    private void refreshProductUi() {
        List<WowProduct> products = manager.products();
        if (products.isEmpty()) {
            pathView.setText(R.string.wehavephones_addons_no_products);
            if (productSpinner.getAdapter() == null || productSpinner.getAdapter().getCount() != 0) {
                bindingProductSpinner = true;
                productSpinner.setAdapter(new ArrayAdapter<>(this, R.layout.wehavephones_spinner_item,
                        new ArrayList<String>()));
                bindingProductSpinner = false;
            }
            return;
        }

        if (productSpinner.getAdapter() == null
                || productSpinner.getAdapter().getCount() != products.size()) {
            ArrayList<String> labels = new ArrayList<>();
            for (WowProduct product : products) {
                labels.add(product.describe());
            }
            bindingProductSpinner = true;
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.wehavephones_spinner_item, labels);
            adapter.setDropDownViewResource(R.layout.wehavephones_spinner_dropdown_item);
            productSpinner.setAdapter(adapter);
            bindingProductSpinner = false;
        }

        WowProduct selected = manager.selectedProduct();
        if (selected != null) {
            int index = products.indexOf(selected);
            if (index >= 0 && productSpinner.getSelectedItemPosition() != index) {
                bindingProductSpinner = true;
                productSpinner.setSelection(index, false);
                bindingProductSpinner = false;
            }
        }
        File addonsDir = manager.addonsDir();
        StringBuilder builder = new StringBuilder();
        if (selected != null) {
            builder.append(getString(R.string.wehavephones_addons_product_line,
                    selected.describe(), manager.interfaceNumber()));
            builder.append('\n');
        }
        if (addonsDir != null) {
            builder.append(getString(R.string.wehavephones_addons_path)).append(": ").append(addonsDir.getPath());
        }
        else {
            builder.append(getString(R.string.wehavephones_addons_missing));
        }
        pathView.setText(builder.toString());
    }

    private WowProduct productAt(int position) {
        List<WowProduct> products = manager.products();
        if (position < 0 || position >= products.size()) return null;
        return products.get(position);
    }

    private void rescan() {
        setBusy(true, getString(R.string.wehavephones_addons_scanning));
        manager.rescan(new AddonManager.Callback<Integer>() {
            @Override
            public void onSuccess(Integer count) {
                setBusy(false, null);
                if (count == null || count < 0) {
                    showStatus(getString(R.string.wehavephones_addons_no_addons_dir));
                }
                else {
                    showStatus(getString(R.string.wehavephones_addons_scan_done, count));
                }
                refreshCurrentFragment();
            }

            @Override
            public void onError(String message) {
                setBusy(false, null);
                showError(message);
            }
        });
    }

    private void updateAll() {
        setBusy(true, getString(R.string.wehavephones_addons_checking));
        manager.updateAll(new AddonManager.Callback<Integer>() {
            @Override
            public void onSuccess(Integer updated) {
                setBusy(false, null);
                int count = updated != null ? updated : 0;
                showStatus(count == 0 ? getString(R.string.wehavephones_addons_nothing_to_update)
                        : getString(R.string.wehavephones_addons_update_all_done, count));
                refreshCurrentFragment();
            }

            @Override
            public void onError(String message) {
                setBusy(false, null);
                showError(message);
            }
        });
    }

    private void launchImportPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip", "application/octet-stream", "application/x-zip-compressed"});
        try {
            startActivityForResult(intent, REQUEST_IMPORT_ZIP);
        }
        catch (Exception e) {
            showError("No file picker available");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT_ZIP || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        prepareImport(uri);
    }

    private void prepareImport(Uri uri) {
        setBusy(true, getString(R.string.wehavephones_addons_import_reading));
        manager.prepareImport(uri, new AddonManager.Callback<AddonManager.ImportPreview>() {
            @Override
            public void onSuccess(final AddonManager.ImportPreview preview) {
                setBusy(false, null);
                showImportDialog(preview);
            }

            @Override
            public void onError(String message) {
                setBusy(false, null);
                showError(message);
            }
        });
    }

    private void showImportDialog(final AddonManager.ImportPreview preview) {
        final ArrayList<DiscoveredAddon> found = preview.addons;
        final String[] labels = new String[found.size()];
        final boolean[] checked = new boolean[found.size()];
        for (int i = 0; i < found.size(); i++) {
            DiscoveredAddon addon = found.get(i);
            StringBuilder label = new StringBuilder(addon.displayName());
            if (!addon.version().isEmpty()) label.append(" ").append(addon.version());
            TocData toc = addon.toc;
            if (toc != null) {
                TocData.ProviderBinding binding = toc.identity();
                if (binding != null && binding.provider.offered) {
                    label.append(" [").append(binding.provider.displayName)
                            .append(':').append(binding.externalId).append(']');
                }
            }
            labels[i] = label.toString();
            checked[i] = true;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.wehavephones_addons_import_title)
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setNegativeButton(R.string.wehavephones_addons_cancel, (dialog, which) ->
                        manager.discardPreview(preview))
                .setPositiveButton(R.string.wehavephones_addons_import_confirm, (dialog, which) -> {
                    ArrayList<DiscoveredAddon> chosen = new ArrayList<>();
                    for (int i = 0; i < found.size(); i++) {
                        if (checked[i]) chosen.add(found.get(i));
                    }
                    confirmImport(preview, chosen);
                })
                .show();
    }

    private void confirmImport(AddonManager.ImportPreview preview, ArrayList<DiscoveredAddon> chosen) {
        if (chosen.isEmpty()) {
            manager.discardPreview(preview);
            return;
        }
        setBusy(true, getString(R.string.wehavephones_addons_import_reading));
        manager.confirmImport(preview, chosen, new AddonManager.Callback<String>() {
            @Override
            public void onSuccess(String installed) {
                setBusy(false, null);
                showStatus(getString(R.string.wehavephones_addons_import_done, installed));
                showList();
                refreshCurrentFragment();
            }

            @Override
            public void onError(String message) {
                setBusy(false, null);
                showError(message);
            }
        });
    }

    public void confirmDelete(final AddonRecord record) {
        if (record == null) return;
        if (record.unrecognized) {
            deleteRecord(record);
            return;
        }
        if (!manager.index().confirmDelete) {
            deleteRecord(record);
            return;
        }
        StringBuilder folders = new StringBuilder();
        for (String folder : record.folderNames) {
            if (folders.length() > 0) folders.append('\n');
            folders.append(folder);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.wehavephones_addons_delete_title)
                .setMessage(getString(R.string.wehavephones_addons_delete_message, folders.toString()))
                .setNegativeButton(R.string.wehavephones_addons_cancel, null)
                .setPositiveButton(R.string.wehavephones_addons_delete, (dialog, which) -> deleteRecord(record))
                .show();
    }

    private void deleteRecord(final AddonRecord record) {
        setBusy(true, null);
        manager.delete(record, new AddonManager.Callback<String>() {
            @Override
            public void onSuccess(String backupName) {
                setBusy(false, null);
                showStatus(getString(R.string.wehavephones_addons_deleted, record.displayName()));
                showList();
                refreshCurrentFragment();
            }

            @Override
            public void onError(String message) {
                setBusy(false, null);
                showError(message);
            }
        });
    }

    public void showDetail(AddonRecord record) {
        showScreen(AddonDetailFragment.newInstance(record.id));
    }

    public void openWebsite(String url) {
        if (url == null || url.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        }
        catch (Exception e) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show();
        }
    }
}
