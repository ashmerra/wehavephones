package com.wehavephones.mobile;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.wehavephones.R;
import com.wehavephones.MainActivity;
import com.wehavephones.XServerDisplayActivity;
import com.wehavephones.container.Container;
import com.wehavephones.container.ContainerManager;

import java.util.ArrayList;
import java.util.Comparator;

/** Persistent container and runtime controls; installation is a separate first-run screen. */
public class SetupFragment extends Fragment {
    private WeHavePhonesActivity activity;
    private Spinner containerSpinner, profileSpinner;
    private TextView profileDescription;
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
        containerSpinner = view.findViewById(R.id.SPActiveContainer);
        view.findViewById(R.id.BTManageContainers).setOnClickListener(v -> {
            Intent intent = new Intent(activity, MainActivity.class);
            intent.putExtra("selected_menu_item_id", R.id.main_menu_containers);
            startActivity(intent);
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
    }

    private void refreshContainers() {
        ArrayList<Container> containers = new ContainerManager(activity).getContainers();
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
        containerSpinner.setEnabled(!containers.isEmpty());
        containerSpinner.setSelection(selected, false);
        containerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View selectedView, int position, long id) {
                if (position < 0 || position >= containers.size()) return;
                Container container = containers.get(position);
                if (container.id == WeHavePhonesConfig.getContainerId(activity)) return;
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
