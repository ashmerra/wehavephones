package com.wehavephones.mobile.addons;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.wehavephones.mobile.WowProduct;
import com.wehavephones.mobile.addons.install.DiscoveredAddon;
import com.wehavephones.mobile.addons.model.AddonRecord;
import com.wehavephones.mobile.addons.model.InstallStatus;
import com.wehavephones.mobile.addons.model.TocData;
import com.wehavephones.mobile.addons.ui.AddonAppsFragment;
import com.wehavephones.mobile.addons.ui.AddonBrowseFragment;
import com.wehavephones.mobile.addons.ui.AddonDetailFragment;
import com.wehavephones.mobile.addons.ui.AddonListFragment;
import com.wehavephones.mobile.addons.ui.AddonSettingsFragment;
import com.wehavephones.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Dedicated addon manager host: a rail with the game picker, the tabs
 * (installed, browse, addon apps, settings) and the shared status line, and
 * the tab screens beside it. Details open over the installed list.
 */
public class AddonsActivity extends AppCompatActivity {
    private static final int REQUEST_IMPORT_ZIP = 41;

    private AddonManager manager;
    private Spinner productSpinner;
    private TextView statusView;
    private TextView updateBadge;
    private ProgressBar progressBar;
    private boolean bindingProductSpinner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_addons);
        manager = new AddonManager(this);

        productSpinner = findViewById(R.id.SPAddonProduct);
        statusView = findViewById(R.id.TVAddonsStatus);
        updateBadge = findViewById(R.id.TVAddonsUpdateBadge);
        progressBar = findViewById(R.id.PBAddons);

        productSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (bindingProductSpinner) return;
                WowProduct product = productAt(position);
                WowProduct selected = manager.selectedProduct();
                if (product == null || selected != null && product.directoryName.equals(selected.directoryName)) return;
                manager.selectProduct(product.directoryName);
                refreshProductUi();
                reopenCurrentTab();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        findViewById(R.id.BTAddonsBack).setOnClickListener((v) -> finish());
        findViewById(R.id.RailAddonsInstalled).setOnClickListener((v) -> showTab(new AddonListFragment()));
        findViewById(R.id.RailAddonsBrowse).setOnClickListener((v) -> showTab(new AddonBrowseFragment()));
        findViewById(R.id.RailAddonsApps).setOnClickListener((v) -> showTab(new AddonAppsFragment()));
        findViewById(R.id.RailAddonsSettings).setOnClickListener((v) -> showTab(new AddonSettingsFragment()));
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(new FragmentManager.FragmentLifecycleCallbacks() {
            @Override
            public void onFragmentResumed(@NonNull FragmentManager fragmentManager, @NonNull Fragment fragment) {
                if (fragment.getId() == R.id.FLAddonsScreen) highlightRail(fragment);
            }
        }, false);

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.FLAddonsScreen, new AddonListFragment())
                    .commit();
        }
        refreshProductUi();
        refreshUpdateBadge();
    }

    @Override
    protected void onDestroy() {
        if (manager != null) manager.shutdown();
        super.onDestroy();
    }

    public AddonManager getManager() {
        return manager;
    }

    /** Rail navigation: one tab at a time, never a deeper stack. */
    public void showTab(Fragment fragment) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        Fragment current = fragmentManager.findFragmentById(R.id.FLAddonsScreen);
        if (current != null && current.getClass() == fragment.getClass()
                && fragmentManager.getBackStackEntryCount() == 0) {
            return;
        }
        fragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        fragmentManager.beginTransaction()
                .replace(R.id.FLAddonsScreen, fragment)
                .commit();
    }

    /** A screen over the current tab, such as an addon's details. */
    public void showScreen(Fragment fragment) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.FLAddonsScreen, fragment)
                .addToBackStack(null)
                .commit();
    }

    public void showInstalled() {
        FragmentManager fragmentManager = getSupportFragmentManager();
        fragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        fragmentManager.beginTransaction()
                .replace(R.id.FLAddonsScreen, new AddonListFragment())
                .commit();
    }

    /** Leaves a pushed screen, back to the tab under it. */
    public void closeScreen() {
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (fragmentManager.getBackStackEntryCount() > 0) fragmentManager.popBackStack();
        else showInstalled();
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

    private void highlightRail(Fragment current) {
        boolean installed = current instanceof AddonListFragment || current instanceof AddonDetailFragment;
        findViewById(R.id.RailAddonsInstalled).setSelected(installed);
        findViewById(R.id.RailAddonsBrowse).setSelected(current instanceof AddonBrowseFragment);
        findViewById(R.id.RailAddonsApps).setSelected(current instanceof AddonAppsFragment);
        findViewById(R.id.RailAddonsSettings).setSelected(current instanceof AddonSettingsFragment);
    }

    /** A new game version shows the same tab afresh, and details fall back to the list. */
    private void reopenCurrentTab() {
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.FLAddonsScreen);
        Fragment next;
        if (current instanceof AddonBrowseFragment) next = new AddonBrowseFragment();
        else if (current instanceof AddonAppsFragment) next = new AddonAppsFragment();
        else if (current instanceof AddonSettingsFragment) next = new AddonSettingsFragment();
        else next = new AddonListFragment();
        FragmentManager fragmentManager = getSupportFragmentManager();
        fragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        fragmentManager.beginTransaction().replace(R.id.FLAddonsScreen, next).commit();
        refreshUpdateBadge();
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
        refreshUpdateBadge();
    }

    /** The rail badge counts the selected game's addons with an update. */
    public void refreshUpdateBadge() {
        if (manager == null || manager.isClosed()) return;
        int updates = 0;
        for (AddonRecord record : manager.recordsForCurrentFlavor()) {
            if (record.installStatus() == InstallStatus.UPDATE) updates++;
        }
        updateBadge.setText(String.valueOf(updates));
        updateBadge.setVisibility(updates > 0 ? View.VISIBLE : View.GONE);
    }

    private void refreshProductUi() {
        List<WowProduct> products = manager.products();
        ProductAdapter adapter = (ProductAdapter)productSpinner.getAdapter();
        if (adapter == null || !adapter.matches(products)) {
            bindingProductSpinner = true;
            productSpinner.setAdapter(new ProductAdapter(products));
            bindingProductSpinner = false;
        }
        if (products.isEmpty()) {
            showStatus(getString(R.string.wehavephones_addons_no_products));
            return;
        }
        WowProduct selected = manager.selectedProduct();
        if (selected != null) {
            int index = -1;
            for (int i = 0; i < products.size(); i++) {
                if (products.get(i).directoryName.equals(selected.directoryName)) index = i;
            }
            if (index >= 0 && productSpinner.getSelectedItemPosition() != index) {
                bindingProductSpinner = true;
                productSpinner.setSelection(index, false);
                bindingProductSpinner = false;
            }
        }
    }

    /** Two-line game picker rows: the game's short name, then its edition and version. */
    private final class ProductAdapter extends ArrayAdapter<WowProduct> {
        private final ArrayList<String> directories = new ArrayList<>();

        ProductAdapter(List<WowProduct> products) {
            super(AddonsActivity.this, R.layout.wehavephones_addon_product_item, new ArrayList<>(products));
            for (WowProduct product : products) directories.add(product.directoryName);
        }

        boolean matches(List<WowProduct> products) {
            if (products.size() != directories.size()) return false;
            for (int i = 0; i < products.size(); i++) {
                if (!products.get(i).directoryName.equals(directories.get(i))) return false;
            }
            return true;
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            return bind(position, convertView, parent, R.layout.wehavephones_addon_product_item);
        }

        @Override
        public View getDropDownView(int position, View convertView, @NonNull ViewGroup parent) {
            return bind(position, convertView, parent, R.layout.wehavephones_addon_product_dropdown_item);
        }

        private View bind(int position, View convertView, ViewGroup parent, int layout) {
            View view = convertView != null && Integer.valueOf(layout).equals(convertView.getTag())
                    ? convertView : LayoutInflater.from(getContext()).inflate(layout, parent, false);
            view.setTag(layout);
            WowProduct product = getItem(position);
            if (product == null) return view;
            String edition = product.getEdition();
            String version = product.getVersion();
            String meta = edition.isEmpty() ? version
                    : version.isEmpty() ? edition : edition + " \u00b7 " + version;
            ((TextView)view.findViewById(R.id.TVProductName)).setText(product.getDisplayName());
            ((TextView)view.findViewById(R.id.TVProductMeta)).setText(meta);
            return view;
        }
    }

    private WowProduct productAt(int position) {
        List<WowProduct> products = manager.products();
        if (position < 0 || position >= products.size()) return null;
        return products.get(position);
    }

    public void rescan() {
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

    public void checkUpdates() {
        setBusy(true, getString(R.string.wehavephones_addons_checking));
        manager.checkUpdates(true, new AddonManager.Callback<Integer>() {
            @Override
            public void onSuccess(Integer checked) {
                setBusy(false, null);
                showStatus(getString(R.string.wehavephones_addons_check_done, checked != null ? checked : 0));
                refreshCurrentFragment();
            }

            @Override
            public void onError(String message) {
                setBusy(false, null);
                showError(message);
            }
        });
    }

    public void updateAll() {
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

    public void launchImportPicker() {
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
                if (binding != null) {
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
                .setOnCancelListener(dialog -> manager.discardPreview(preview))
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
                showInstalled();
                refreshUpdateBadge();
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
                closeScreen();
                refreshUpdateBadge();
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
