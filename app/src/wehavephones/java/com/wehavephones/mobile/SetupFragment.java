package com.wehavephones.mobile;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.wehavephones.R;
import com.wehavephones.BuildConfig;
import com.wehavephones.MainActivity;
import com.wehavephones.XServerDisplayActivity;
import com.wehavephones.container.Container;
import com.wehavephones.container.ContainerManager;
import com.wehavephones.mobile.updates.AppUpdates;

import java.util.ArrayList;
import java.util.Comparator;

/** Persistent container and runtime controls; installation is a separate first-run screen. */
public class SetupFragment extends Fragment {
    private WeHavePhonesActivity activity;
    private Spinner containerSpinner, profileSpinner;
    private TextView profileDescription;
    private View importButton, cancelImportButton, manageContainers;
    private TextView importStatus;
    private ProgressBar importProgress;
    private boolean hasContainers;
    private final Handler importHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshImport = new Runnable() {
        @Override public void run() {
            if (!isAdded() || getView() == null) return;
            renderImport();
            importHandler.postDelayed(this, 500);
        }
    };
    private WeHavePhonesRuntimeProfiles profiles;
    private ArrayList<WeHavePhonesRuntimeProfiles.Profile> profileList = new ArrayList<>();

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_wehavephones_setup, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        activity = (WeHavePhonesActivity)getActivity();
        AppUpdates updates = AppUpdates.get(activity);
        ((TextView)view.findViewById(R.id.TVAppUpdateVersion)).setText(getString(R.string.wehavephones_update_version, BuildConfig.VERSION_NAME));
        ((TextView)view.findViewById(R.id.TVAppUpdateHint)).setText(updates.isSupported()
                ? R.string.wehavephones_update_card_hint : R.string.wehavephones_update_card_dev);
        CheckBox automaticUpdates = view.findViewById(R.id.CBAutomaticAppUpdates);
        automaticUpdates.setChecked(updates.isSupported() && updates.isAutomatic());
        automaticUpdates.setEnabled(updates.isSupported());
        automaticUpdates.setOnCheckedChangeListener((button, checked) -> updates.setAutomatic(checked));
        view.findViewById(R.id.BTCheckAppUpdates).setOnClickListener(v -> activity.checkForAppUpdates());
        containerSpinner = view.findViewById(R.id.SPActiveContainer);
        manageContainers = view.findViewById(R.id.BTManageContainers);
        manageContainers.setOnClickListener(v -> {
            Intent intent = new Intent(activity, MainActivity.class);
            intent.putExtra("selected_menu_item_id", R.id.main_menu_containers);
            startActivity(intent);
        });
        view.findViewById(R.id.BTNewContainer).setOnClickListener(v -> {
            if (WowImportService.isRunning()) {
                Toast.makeText(activity, R.string.wehavephones_import_wait, Toast.LENGTH_LONG).show();
                return;
            }
            activity.showSection(FirstRunSetupFragment.forNewContainer());
        });
        importButton = view.findViewById(R.id.BTImportWow);
        cancelImportButton = view.findViewById(R.id.BTCancelWowImport);
        importStatus = view.findViewById(R.id.TVImportWowStatus);
        importProgress = view.findViewById(R.id.PBImportWow);
        importButton.setOnClickListener(v -> activity.withStoragePermissions(() -> {
            WowImportService.start(activity.getApplicationContext(), WeHavePhonesContainers.resolve(activity));
            if (getView() != null) renderImport();
        }));
        cancelImportButton.setOnClickListener(v -> {
            WowImportService.cancel();
            cancelImportButton.setEnabled(false);
        });
        profileSpinner = view.findViewById(R.id.SPRuntimeProfile);
        profileDescription = view.findViewById(R.id.TVProfileDescription);

        profiles = WeHavePhonesRuntimeProfiles.loadOrNull(activity);
        ArrayList<String> names = new ArrayList<>();
        if (profiles != null) {
            profileList = profiles.getProfiles();
            for (WeHavePhonesRuntimeProfiles.Profile profile : profileList) names.add(profile.name);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, R.layout.wehavephones_spinner_item, names);
        adapter.setDropDownViewResource(R.layout.wehavephones_spinner_dropdown_item);
        profileSpinner.setAdapter(adapter);
        profileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View selectedView, int position, long id) {
                selectProfile(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        String selected = WeHavePhonesConfig.getRuntimeProfile(activity);
        int initial = 0;
        for (int i = 0; i < profileList.size(); i++) {
            if (profileList.get(i).name.equals(selected)) {
                initial = i;
                break;
            }
        }
        if (!profileList.isEmpty()) profileSpinner.setSelection(initial, false);

    }

    @Override
    public void onResume() {
        super.onResume();
        // Container management can create, rename, or remove entries while this
        // setup view stays on the back stack.
        refreshContainers();
        importHandler.post(refreshImport);
    }

    @Override public void onPause() {
        importHandler.removeCallbacks(refreshImport);
        super.onPause();
    }

    @Override public void onDestroyView() {
        importHandler.removeCallbacks(refreshImport);
        super.onDestroyView();
    }

    private void renderImport() {
        WowImportService.State state = WowImportService.state();
        importButton.setEnabled(hasContainers && !state.running);
        manageContainers.setEnabled(!state.running);
        containerSpinner.setEnabled(hasContainers && !state.running);
        cancelImportButton.setVisibility(state.running ? View.VISIBLE : View.GONE);
        importProgress.setVisibility(state.running ? View.VISIBLE : View.GONE);
        importProgress.setIndeterminate(state.total == 0);
        if (state.total > 0) importProgress.setProgress(state.percent());
        importStatus.setVisibility(state.message.isEmpty() ? View.GONE : View.VISIBLE);
        importStatus.setText(state.message);
        importStatus.setTextColor(activity.getColor(state.error ? R.color.wehavephones_error : R.color.wehavephones_text_dim));
        if (!state.running) cancelImportButton.setEnabled(true);
    }

    private void refreshContainers() {
        ArrayList<Container> containers = new ContainerManager(activity).getContainers();
        hasContainers = !containers.isEmpty();
        containers.sort(Comparator.comparingInt(container -> container.id));
        Container active = WeHavePhonesContainers.resolve(activity);
        ArrayList<String> labels = new ArrayList<>();
        int selected = 0;
        for (int i = 0; i < containers.size(); i++) {
            Container container = containers.get(i);
            labels.add(getString(R.string.wehavephones_settings_container_entry, container.getName(), container.id));
            if (active != null && container.id == active.id) selected = i;
        }
        if (containers.isEmpty()) labels.add(getString(R.string.wehavephones_settings_container_empty));

        containerSpinner.setOnItemSelectedListener(null);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, R.layout.wehavephones_spinner_item, labels);
        adapter.setDropDownViewResource(R.layout.wehavephones_spinner_dropdown_item);
        containerSpinner.setAdapter(adapter);
        containerSpinner.setEnabled(hasContainers && !WowImportService.isRunning());
        containerSpinner.setSelection(selected, false);
        containerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View selectedView, int position, long id) {
                if (position < 0 || position >= containers.size()) return;
                Container container = containers.get(position);
                if (container.id == WeHavePhonesConfig.getContainerId(activity)) return;
                if (WowImportService.isRunning()) {
                    Toast.makeText(activity, R.string.wehavephones_import_wait, Toast.LENGTH_LONG).show();
                    refreshContainers();
                    return;
                }
                XServerDisplayActivity session = XServerDisplayActivity.getActiveInstance();
                if ((session != null && session.isSessionActive()) || OneButtonLauncher.isRunning()
                        || ProcessScanner.isWineSessionRunning()) {
                    // Launchers reuse the live Wine session. Changing its target
                    // would otherwise pair another prefix's files with that session.
                    Toast.makeText(activity, R.string.wehavephones_settings_container_busy, Toast.LENGTH_LONG).show();
                    refreshContainers();
                    return;
                }
                WeHavePhonesContainers.remember(activity, container);
                activity.updateStageStatus(getString(R.string.wehavephones_settings_container_selected, container.getName()), false);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void selectProfile(int position) {
        if (profiles == null || position < 0 || position >= profileList.size()) return;
        WeHavePhonesRuntimeProfiles.Profile profile = profileList.get(position);
        WeHavePhonesConfig.setRuntimeProfile(activity, profile.name);
        profileDescription.setText(profile.description);
    }
}
