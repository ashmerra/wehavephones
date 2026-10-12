package com.wehavephones.mobile;

import android.app.AlertDialog;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.wehavephones.R;
import com.wehavephones.BuildConfig;
import com.wehavephones.mobile.updates.AppUpdates;

import java.util.ArrayList;
import java.util.Arrays;

public class WeHavePhonesSettingsFragment extends Fragment {
    private WeHavePhonesActivity activity;
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_wehavephones_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        activity = (WeHavePhonesActivity)getActivity();
        AppUpdates updates = AppUpdates.get(activity);
        ((TextView)view.findViewById(R.id.TVAppUpdateVersion)).setText(getString(R.string.wehavephones_update_version, BuildConfig.VERSION_NAME));
        ((TextView)view.findViewById(R.id.TVAppUpdateHint)).setText(updates.isSupported()
                ? R.string.wehavephones_update_hint : R.string.wehavephones_update_dev);
        CheckBox automaticUpdates = view.findViewById(R.id.CBAutomaticAppUpdates);
        automaticUpdates.setChecked(updates.isSupported() && updates.isAutomatic());
        automaticUpdates.setEnabled(updates.isSupported());
        automaticUpdates.setOnCheckedChangeListener((button, checked) -> updates.setAutomatic(checked));
        view.findViewById(R.id.BTCheckAppUpdates).setOnClickListener(v -> activity.checkForAppUpdates());
        setUpGamePreferences(view);

        setUpResolution(view.findViewById(R.id.SPGameResolution));

        CheckBox disableGamepad = view.findViewById(R.id.CBDisableVirtualGamepad);
        disableGamepad.setChecked(WeHavePhonesConfig.isVirtualGamepadDisabled(activity));
        disableGamepad.setOnCheckedChangeListener((button, checked) -> {
            WeHavePhonesConfig.setVirtualGamepadDisabled(activity, checked);
            activity.updateStageStatus(getString(R.string.wehavephones_settings_saved), false);
        });

        CheckBox keepRunning = view.findViewById(R.id.CBKeepRunningInBackground);
        keepRunning.setChecked(WeHavePhonesConfig.isKeepRunningInBackground(activity));
        keepRunning.setOnCheckedChangeListener((button, checked) -> {
            WeHavePhonesConfig.setKeepRunningInBackground(activity, checked);
            activity.updateStageStatus(getString(R.string.wehavephones_settings_saved), false);
        });

        CheckBox performanceOverlay = view.findViewById(R.id.CBPerformanceOverlay);
        performanceOverlay.setChecked(WeHavePhonesConfig.isPerformanceOverlay(activity));
        performanceOverlay.setOnCheckedChangeListener((button, checked) -> {
            WeHavePhonesConfig.setPerformanceOverlay(activity, checked);
            activity.updateStageStatus(getString(R.string.wehavephones_settings_saved), false);
        });
    }

    private void setUpGamePreferences(View view) {
        Spinner region = view.findViewById(R.id.SPSettingsRegion);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(activity,
                R.array.wehavephones_regions, R.layout.wehavephones_spinner_item);
        adapter.setDropDownViewResource(R.layout.wehavephones_spinner_dropdown_item);
        region.setAdapter(adapter);
        region.setSelection(Arrays.asList(WowFirstBoot.REGIONS).indexOf(WeHavePhonesConfig.getGameRegion(activity)), false);
        region.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View child, int position, long id) {
                String selected = WowFirstBoot.REGIONS[position];
                String current = WeHavePhonesConfig.getGameRegion(activity);
                if (selected.equals(current)) return;
                // The installed Battle.net app only signs in to its own side.
                if (WowFirstBoot.isChina(selected) != WowFirstBoot.isChina(current)
                        && BattleNetManager.isInstalled(WeHavePhonesContainers.resolve(activity))) {
                    region.setSelection(Arrays.asList(WowFirstBoot.REGIONS).indexOf(current), false);
                    new AlertDialog.Builder(activity)
                            .setMessage(R.string.wehavephones_settings_region_china)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    return;
                }
                WeHavePhonesConfig.setGameRegionOverride(activity, selected);
                activity.updateStageStatus(getString(R.string.wehavephones_settings_saved), false);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setUpResolution(Spinner spinner) {
        DisplayMetrics metrics = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        final String nativeSize = MemoryTransforms.nativeScreenSize(metrics.widthPixels, metrics.heightPixels);
        final String[] sizes = MemoryTransforms.screenSizeOptions(nativeSize);
        ArrayList<String> labels = new ArrayList<>();
        for (String size : sizes) {
            if (size.equals(MemoryTransforms.DEFAULT_SCREEN_SIZE)) {
                labels.add(getString(R.string.wehavephones_settings_resolution_default, size));
            } else if (size.equals(nativeSize)) {
                labels.add(getString(R.string.wehavephones_settings_resolution_native, size));
            } else {
                labels.add(size);
            }
        }
        String current = WeHavePhonesConfig.getGameScreenSize(activity);
        // A size set outside this list (by hand in the prefs) stays selectable.
        if (!Arrays.asList(sizes).contains(current)) labels.add(current);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, R.layout.wehavephones_spinner_item, labels);
        adapter.setDropDownViewResource(R.layout.wehavephones_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        int index = Arrays.asList(sizes).indexOf(current);
        spinner.setSelection(index >= 0 ? index : labels.size() - 1, false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View selectedView, int position, long id) {
                String size = position < sizes.length ? sizes[position] : current;
                if (size.equals(WeHavePhonesConfig.getGameScreenSize(activity))) return;
                WeHavePhonesConfig.setGameScreenSize(activity, size);
                activity.updateStageStatus(getString(R.string.wehavephones_settings_saved), false);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

}
