package com.wehavephones.mobile;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Button;
import android.widget.Spinner;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.wehavephones.R;
import com.wehavephones.XServerDisplayActivity;
import com.wehavephones.container.Container;
import com.wehavephones.container.ContainerManager;
import com.wehavephones.core.TarCompressorUtils;
import com.wehavephones.xenvironment.ImageFs;
import com.wehavephones.xenvironment.ImageFsInstaller;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * First-time setup, also reopened from Setup to add another container with its
 * own Battle.net (China runs a separate Battle.net app).
 */
public class FirstRunSetupFragment extends Fragment {
    private static final String ARG_NEW_CONTAINER = "newContainer";
    private static final AtomicBoolean WORKING = new AtomicBoolean();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static volatile String lastMessage = "";
    /** The container created by the new-container screen, 0 until it exists. */
    private static volatile int addedContainerId;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!isAdded() || getView() == null) return;
            refreshState();
            refreshHandler.postDelayed(this, 1500);
        }
    };
    private WeHavePhonesActivity activity;
    private TextView setupLog;
    private ProgressBar progressBar;
    private String selectedRegion;
    private Button prepareButton, bnetButton;
    private Spinner regionSpinner;
    private TextView bnetGuidance;

    /** Setup for an extra container; existing containers are left as they are. */
    public static FirstRunSetupFragment forNewContainer() {
        if (!WORKING.get()) {
            addedContainerId = 0;
            lastMessage = "";
        }
        FirstRunSetupFragment fragment = new FirstRunSetupFragment();
        Bundle args = new Bundle();
        args.putBoolean(ARG_NEW_CONTAINER, true);
        fragment.setArguments(args);
        return fragment;
    }

    private boolean isNewContainer() {
        return getArguments() != null && getArguments().getBoolean(ARG_NEW_CONTAINER);
    }

    /** The container this screen sets up: the active one, or the one it added. */
    private Container targetContainer() {
        if (!isNewContainer()) return WeHavePhonesContainers.resolve(activity);
        return addedContainerId > 0 ? new ContainerManager(activity).getContainerById(addedContainerId) : null;
    }

    private InitialSetupState setupState() {
        if (!isNewContainer()) return WeHavePhonesInitialSetup.load(activity);
        return WeHavePhonesInitialSetup.load(activity, targetContainer(), false);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_wehavephones_first_run, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        activity = (WeHavePhonesActivity)getActivity();
        setupLog = view.findViewById(R.id.TVSetupLog);
        progressBar = view.findViewById(R.id.PBSetup);

        prepareButton = view.findViewById(R.id.BTRunAll);
        bnetButton = view.findViewById(R.id.BTBnet);
        prepareButton.setOnClickListener(v -> runPreparation());
        bnetButton.setOnClickListener(v -> {
            activity.withStoragePermissions(() -> {
                if (liveSession() || !setupState().containerPrepared
                        || !WORKING.compareAndSet(false, true)) return;
                refreshState();
                installBattleNet();
            });
        });
        bnetGuidance = view.findViewById(R.id.TVBnetGuidance);
        Spinner region = regionSpinner = view.findViewById(R.id.SPSetupRegion);
        selectedRegion = WeHavePhonesConfig.getGameRegion(activity);
        if (isNewContainer()) {
            ((TextView)view.findViewById(R.id.TVFirstRunLabel)).setText(R.string.wehavephones_new_container_label);
            ((TextView)view.findViewById(R.id.TVFirstRunTitle)).setText(R.string.wehavephones_new_container_title);
            ((TextView)view.findViewById(R.id.TVFirstRunIntro)).setText(R.string.wehavephones_new_container_intro);
            ((TextView)view.findViewById(R.id.TVFirstRunRegionLater)).setText(R.string.wehavephones_new_container_region);
        }
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(activity,
                R.array.wehavephones_regions, R.layout.wehavephones_spinner_item);
        adapter.setDropDownViewResource(R.layout.wehavephones_spinner_dropdown_item);
        region.setAdapter(adapter);
        for (int i = 0; i < WowFirstBoot.REGIONS.length; i++) {
            if (WowFirstBoot.REGIONS[i].equals(WeHavePhonesConfig.getGameRegion(activity))) region.setSelection(i, false);
        }
        region.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View child, int position, long id) {
                selectedRegion = WowFirstBoot.REGIONS[position];
                // Before the new container exists, the active one is another container.
                Container target = targetContainer();
                if (target != null && target.id == WeHavePhonesConfig.getContainerId(activity)) {
                    WeHavePhonesConfig.setGameRegion(activity, WowFirstBoot.REGIONS[position]);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        setupLog.setText(lastMessage.isEmpty() ? getString(R.string.wehavephones_first_run_status) : lastMessage);
    }

    @Override public void onStart() {
        super.onStart();
        refreshState();
        refreshHandler.post(refresh);
    }

    @Override public void onStop() {
        refreshHandler.removeCallbacks(refresh);
        super.onStop();
    }

    @Override public void onResume() {
        super.onResume();
        refreshState();
    }

    @Override public void onDestroyView() {
        refreshHandler.removeCallbacks(refresh);
        prepareButton = bnetButton = null;
        setupLog = bnetGuidance = null;
        regionSpinner = null;
        progressBar = null;
        super.onDestroyView();
    }

    private boolean liveSession() {
        XServerDisplayActivity session = XServerDisplayActivity.getActiveInstance();
        return (session != null && session.isSessionActive()) || ProcessScanner.isWineSessionRunning();
    }

    void refreshState() {
        if (!isAdded() || prepareButton == null) return;
        InitialSetupState state = setupState();
        boolean busy = WORKING.get();
        if (!busy && state.complete && isResumed()) {
            if (isNewContainer()) activity.showHome();
            else activity.finishInitialSetup();
            return;
        }
        boolean live = liveSession();
        prepareButton.setText(state.containerPrepared ? R.string.wehavephones_first_run_container_ready : R.string.wehavephones_first_run_container_install);
        prepareButton.setEnabled(!busy && !state.containerPrepared && !live);
        bnetButton.setEnabled(!busy && state.containerPrepared && !live);
        regionSpinner.setEnabled(!busy);
        bnetGuidance.setText(state.containerPrepared ? R.string.wehavephones_first_run_bnet_hint : R.string.wehavephones_first_run_bnet_locked);
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (!lastMessage.isEmpty()) setupLog.setText(lastMessage);
        else if (state.containerPrepared) setupLog.setText(R.string.wehavephones_first_run_container_next);
    }

    private void runPreparation() {
        activity.withStoragePermissions(this::prepareContainer);
    }

    private void prepareContainer() {
        if (liveSession()) {
            appendLine(activity.getString(R.string.wehavephones_settings_container_busy));
            return;
        }
        if (!WORKING.compareAndSet(false, true)) return;
        refreshState();
        clearLog();
        appendLine(activity.getString(R.string.wehavephones_setup_running));
        if (!ImageFs.find(activity).isValid()) {
            ImageFsInstaller.installIfNeeded(activity, success -> {
                if (success) startPreparation();
                else { appendLine(activity.getString(R.string.unable_to_install_system_files)); finishBusy(); }
            });
        }
        else startPreparation();
    }

    private void startPreparation() {
        WORKER.execute(() -> {
            try {
                if (!ensureSystemFiles() || !ensureWineLayers()) {
                    finishBusy();
                    return;
                }
                Container container = targetContainer();
                if (container != null) {
                    WeHavePhonesContainers.remember(activity, container);
                    appendLine(activity.getString(R.string.wehavephones_setup_container_ok, container.getName()));
                    continueRunAll();
                    return;
                }
                String wineVersion = currentWineVersion();
                WeHavePhonesContainers.CreateCallback onCreated = (created, error) -> {
                    if (created == null) {
                        appendLine(activity.getString(R.string.wehavephones_error_prefix) + error);
                        finishBusy();
                        return;
                    }
                    if (isNewContainer()) addedContainerId = created.id;
                    appendLine(activity.getString(R.string.wehavephones_setup_container_created, created.getName()));
                    WeHavePhonesConfig.setGameRegion(activity, selectedRegion);
                    continueRunAll();
                };
                if (isNewContainer()) {
                    WeHavePhonesContainers.create(activity, activity.getString(R.string.wehavephones_new_container_name,
                            selectedRegion.toUpperCase(Locale.US)), wineVersion, onCreated);
                }
                else WeHavePhonesContainers.createDefault(activity, wineVersion, onCreated);
            }
            catch (Throwable throwable) {
                appendLine(activity.getString(R.string.wehavephones_error_prefix) + throwable);
                finishBusy();
            }
        });
    }

    private void continueRunAll() {
        WORKER.execute(() -> {
            try {
                if (!applyProfile() || !ensureShortcuts()) { finishBusy(); return; }
                Container container = WeHavePhonesContainers.resolve(activity);
                container.putExtra(WeHavePhonesInitialSetup.PREPARED_PROFILE, WeHavePhonesRuntimeProfiles.loadOrNull(activity).getSelectedProfile(activity).name);
                container.saveData();
                appendLine(activity.getString(R.string.wehavephones_first_run_container_next));
            }
            catch (Throwable throwable) {
                appendLine(activity.getString(R.string.wehavephones_error_prefix) + throwable);
            }
            finishBusy();
        });
    }

    private boolean ensureSystemFiles() {
        ImageFs imageFs = ImageFs.find(activity);
        if (imageFs.isValid()) {
            appendLine(activity.getString(R.string.wehavephones_setup_system_files_ok));
            return true;
        }
        activity.runOnUiThread(() -> ImageFsInstaller.installIfNeeded(activity));
        appendLine(activity.getString(R.string.wehavephones_setup_system_files_wait));
        return false;
    }

    // Installs bundled layers (downloads only optional layers); a fresh
    // install has none of them, and the container is created from the layer's
    // prefixPack. Runs on a worker thread.
    private boolean ensureWineLayers() {
        WeHavePhonesRuntimeProfiles profiles = WeHavePhonesRuntimeProfiles.loadOrNull(activity);
        if (profiles == null) return false;
        LinkedHashMap<String, WeHavePhonesRuntimeProfiles.Layer> layers = new LinkedHashMap<>();
        WeHavePhonesRuntimeProfiles.Profile[] needed = {
                profiles.isHandoff() ? profiles.getBnetStageProfile() : profiles.getSingleStageProfile(),
                profiles.getSelectedProfile(activity)
        };
        for (WeHavePhonesRuntimeProfiles.Profile profile : needed) {
            if (profile != null && profile.layer != null) {
                layers.put(profile.layer.name, profile.layer);
            }
        }

        File optDir = ImageFs.find(activity).getInstalledWineDir();
        File cacheDir = new File(activity.getCacheDir(), "wine-layers");
        for (WeHavePhonesRuntimeProfiles.Layer layer : layers.values()) {
            if (WineLayerInstaller.isInstalled(optDir, layer.name)) continue;
            appendLine(activity.getString(layer.asset.isEmpty() ? R.string.wehavephones_setup_wine_downloading
                    : R.string.wehavephones_setup_wine_bundled, layer.name, layer.sizeLabel));
            final int[] lastPercent = {-1};
            String error = !layer.asset.isEmpty()
                    ? WineLayerInstaller.installBundled(layer.name, layer.sha256, optDir, cacheDir,
                    () -> activity.getAssets().open(layer.asset),
                    (archive, destination) -> TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, archive, destination))
                    : WineLayerInstaller.install(layer.name, layer.url, layer.sha256, optDir, cacheDir,
                    (archive, destination) -> TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, archive, destination),
                    (done, total) -> {
                        int percent = total > 0 ? (int)(done * 100 / total) : -1;
                        if (percent == lastPercent[0]) return;
                        lastPercent[0] = percent;
                        setProgress(percent);
                    });
            setProgress(-1);
            if (error != null) {
                appendLine(activity.getString(R.string.wehavephones_error_prefix) + error);
                return false;
            }
            appendLine(activity.getString(R.string.wehavephones_setup_wine_installed, layer.name));
        }
        return true;
    }

    private boolean applyProfile() {
        if (!ensureWineLayers()) return false;
        WeHavePhonesRuntimeProfiles profiles = WeHavePhonesRuntimeProfiles.loadOrNull(activity);
        if (profiles == null) {
            appendLine(activity.getString(R.string.wehavephones_error_prefix) + activity.getString(R.string.wehavephones_stage_no_profiles));
            return false;
        }
        WeHavePhonesRuntimeApplier.Stage stage = profiles.isHandoff()
                ? WeHavePhonesRuntimeApplier.Stage.BATTLE_NET
                : WeHavePhonesRuntimeApplier.Stage.SINGLE;
        WeHavePhonesRuntimeApplier.Result result = WeHavePhonesRuntimeApplier.apply(activity, profiles,
                profiles.getSelectedProfile(activity), stage);
        appendSummary(result);
        if (result.success) appendLine(activity.getString(R.string.wehavephones_setup_profile_ok));
        return result.success;
    }

    private boolean ensureShortcuts() {
        Container container = WeHavePhonesContainers.resolve(activity);
        if (container == null) {
            appendLine(activity.getString(R.string.wehavephones_error_prefix) + activity.getString(R.string.wehavephones_stage_setup_required));
            return false;
        }
        File bnet = WeHavePhonesShortcuts.ensureBnetShortcut(container);
        File wow = WeHavePhonesShortcuts.ensureWowShortcut(container);
        if (bnet != null && wow != null) appendLine(activity.getString(R.string.wehavephones_setup_shortcuts_ok));
        else appendLine(activity.getString(R.string.wehavephones_error_prefix) + activity.getString(R.string.wehavephones_setup_shortcuts_failed));
        return bnet != null && wow != null;
    }

    private void installBattleNet() {
        final Container container = targetContainer();
        WeHavePhonesContainers.remember(activity, container);
        if (container != null && BattleNetManager.isInstalled(container)) {
            appendLine(activity.getString(R.string.wehavephones_setup_bnet_installed));
            finishBusy();
            return;
        }
        appendLine(activity.getString(R.string.wehavephones_setup_bnet_downloading));
        refreshState();
        BattleNetManager.prepareInstaller(activity, container, selectedRegion, (installer, guestPath, error) -> {
            if (error != null) {
                appendLine(activity.getString(R.string.wehavephones_error_prefix) + error);
                finishBusy();
                return;
            }
            appendLine(activity.getString(R.string.wehavephones_installer_ready, installer.getPath()));
            if (container == null) {
                appendLine(activity.getString(R.string.wehavephones_error_prefix) + activity.getString(R.string.wehavephones_stage_setup_required));
                finishBusy();
                return;
            }
            StringBuilder launchError = new StringBuilder();
            if (BattleNetManager.launchInstaller(activity, container, installer, guestPath, launchError)) {
                appendLine(activity.getString(R.string.wehavephones_setup_bnet_launching));
            }
            else {
                appendLine(activity.getString(R.string.wehavephones_error_prefix) + launchError);
            }
            finishBusy();
        });
    }

    private String currentWineVersion() {
        WeHavePhonesRuntimeProfiles profiles = WeHavePhonesRuntimeProfiles.loadOrNull(activity);
        if (profiles == null) return "";
        WeHavePhonesRuntimeProfiles.Profile profile = profiles.isHandoff()
                ? profiles.getBnetStageProfile()
                : profiles.getSingleStageProfile();
        return profile != null ? profile.wineVersion : "";
    }

    private void appendSummary(WeHavePhonesRuntimeApplier.Result result) {
        for (String step : result.steps) appendLine(step);
        for (String error : result.errors) appendLine(activity.getString(R.string.wehavephones_error_prefix) + error);
    }

    private void appendLine(final String line) {
        lastMessage = line;
        activity.runOnUiThread(() -> {
            if (isAdded() && setupLog != null) setupLog.setText(line);
        });
    }

    private void clearLog() {
        lastMessage = "";
    }

    // percent < 0 returns the bar to indeterminate (extracting or idle).
    private void setProgress(int percent) {
        activity.runOnUiThread(() -> {
            if (!isAdded() || progressBar == null) return;
            progressBar.setIndeterminate(percent < 0);
            if (percent >= 0) progressBar.setProgress(percent);
        });
    }

    private void finishBusy() {
        activity.runOnUiThread(() -> {
            WORKING.set(false);
            // An install can finish after the view was recreated. Update the
            // current screen, rather than the fragment that started the work.
            Fragment current = activity.getSupportFragmentManager().findFragmentById(R.id.FLWeHavePhonesScreen);
            if (current instanceof FirstRunSetupFragment) ((FirstRunSetupFragment)current).refreshState();
        });
    }
}
