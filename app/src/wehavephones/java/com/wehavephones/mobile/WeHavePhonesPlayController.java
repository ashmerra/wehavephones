package com.wehavephones.mobile;

import android.util.Log;
import android.widget.Toast;

import com.wehavephones.mobile.addons.required.ConsolePortUpdater;
import com.wehavephones.BuildConfig;
import com.wehavephones.R;
import com.wehavephones.XServerDisplayActivity;
import com.wehavephones.container.Container;
import com.wehavephones.container.Shortcut;
import com.wehavephones.core.EnvVars;
import com.wehavephones.core.FileUtils;
import com.wehavephones.winhandler.WinHandler;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public final class WeHavePhonesPlayController {
    private WeHavePhonesPlayController() {}

    private static boolean blockDuringImport(WeHavePhonesActivity activity) {
        if (!WowImportService.isRunning()) return false;
        activity.updateStageStatus(activity.getString(R.string.wehavephones_import_wait), true);
        return true;
    }

    /** Play always goes through Battle.net, which signs the game in. */
    public static void play(final WeHavePhonesActivity activity) {
        if (blockDuringImport(activity)) return;
        if (RuntimeStageController.isRunning()) {
            activity.updateStageStatus(activity.getString(R.string.wehavephones_stage_already_running), false);
            return;
        }
        if (OneButtonLauncher.isRunning()) {
            activity.updateStageStatus(activity.getString(R.string.wehavephones_launch_in_progress), false);
            return;
        }
        activity.updateStageStatus(activity.getString(R.string.wehavephones_stage_preparing), false);

        Executors.newSingleThreadExecutor().execute(() -> {
            final WeHavePhonesStatus status = WeHavePhonesStatus.load(activity.getApplicationContext());
            if (status.container == null) {
                activity.runOnUiThread(() -> activity.updateStageStatus(activity.getString(R.string.wehavephones_stage_setup_required), true));
                return;
            }
            if (status.profiles == null) {
                activity.runOnUiThread(() -> activity.updateStageStatus(activity.getString(R.string.wehavephones_stage_no_profiles), true));
                return;
            }

            // Controller play needs ConsolePort in every non-Forever game; never blocks the launch.
            ConsolePortUpdater.Report controllers = ConsolePortUpdater.ensureInstalled(
                    activity.getApplicationContext(), status.container,
                    (product, version) -> activity.runOnUiThread(() -> activity.updateStageStatus(
                            activity.getString(R.string.wehavephones_stage_consoleport, version, product.getDisplayName()), false)));
            Log.i(ConsolePortUpdater.TAG, controllers.summary());

            if (status.profiles.isHandoff()) {
                activity.runOnUiThread(() -> {
                    RuntimeStageController.start(activity, activity::updateStageStatus);
                    activity.updateStageStatus(activity.getString(R.string.wehavephones_stage_handoff_started), false);
                });
                return;
            }

            activity.runOnUiThread(() -> startOneButton(activity));
        });
    }

    /**
     * Ask Battle.net to install a game it lists but that is not on disk
     * ({@code Battle.net.exe --game=<id> --install}). A running client gets the
     * request forwarded in-session; otherwise the verified runtime is applied
     * and Battle.net starts with the flags. The user confirms the install
     * (location, size) in the Battle.net window.
     */
    public static void install(final WeHavePhonesActivity activity, final BattleNetGames.Game game) {
        if (blockDuringImport(activity)) return;
        final String name = game.getDisplayName();
        final String flags = WeHavePhonesLaunchPlan.installFlags(game.bnetGameId());
        if (flags.isEmpty()) {
            activity.updateStageStatus(activity.getString(R.string.wehavephones_install_unknown_id, name), true);
            return;
        }
        if (OneButtonLauncher.isRunning() || RuntimeStageController.isRunning()) {
            activity.updateStageStatus(activity.getString(R.string.wehavephones_launch_in_progress), false);
            return;
        }
        activity.updateStageStatus(activity.getString(R.string.wehavephones_install_starting, name), false);

        Executors.newSingleThreadExecutor().execute(() -> {
            final android.content.Context context = activity.getApplicationContext();
            XServerDisplayActivity session = XServerDisplayActivity.getActiveInstance();
            WinHandler winHandler = session != null && session.isSessionActive() ? session.getWinHandler() : null;
            if (winHandler != null && ProcessScanner.isLauncherRunning()) {
                final Container container = WeHavePhonesContainers.resolve(context);
                String command = WeHavePhonesLaunchPlan.winHandlerCommand(WeHavePhonesLaunchPlan.INSTALL_BAT_GUEST);
                if (container == null || !FileUtils.write(WeHavePhonesPaths.installBat(container),
                        WeHavePhonesLaunchPlan.installBat(WeHavePhonesPaths.bnetGuestDir(container), game.bnetGameId()).getBytes(StandardCharsets.UTF_8))) {
                    activity.runOnUiThread(() -> activity.updateStageStatus(
                            activity.getString(R.string.wehavephones_launch_write_failed, WeHavePhonesLaunchPlan.INSTALL_BAT_NAME), true));
                    return;
                }
                winHandler.exec(command);
                activity.runOnUiThread(() -> {
                    activity.updateStageStatus(activity.getString(R.string.wehavephones_install_sent, name), false);
                    WeHavePhonesSessionLauncher.launch(activity, container, null);
                });
                return;
            }

            final WeHavePhonesRuntimeApplier.Result result = WeHavePhonesRuntimeApplier.apply(activity, WeHavePhonesRuntimeApplier.Stage.BATTLE_NET);
            WeHavePhonesRuntimeProfiles profiles = WeHavePhonesRuntimeProfiles.loadOrNull(context);
            if (!result.success || profiles == null || result.profile == null) {
                final String message = activity.getString(R.string.wehavephones_stage_apply_failed) + ": " + result.errors;
                activity.runOnUiThread(() -> activity.updateStageStatus(message, true));
                return;
            }
            String startBat = WeHavePhonesLaunchPlan.appendBnetFlags(profiles.buildBnetBat(result.profile), flags);
            if (!FileUtils.write(WeHavePhonesPaths.startBat(result.container), startBat.getBytes(StandardCharsets.UTF_8))) {
                activity.runOnUiThread(() -> activity.updateStageStatus(
                        activity.getString(R.string.wehavephones_launch_write_failed, WeHavePhonesLaunchPlan.START_BAT_NAME), true));
                return;
            }
            final File shortcut = WeHavePhonesShortcuts.ensureShortcut(result.container,
                    WeHavePhonesLaunchPlan.START_SHORTCUT_NAME, WeHavePhonesLaunchPlan.START_BAT_GUEST);
            activity.runOnUiThread(() -> {
                if (shortcut != null && WeHavePhonesSessionLauncher.launch(activity, result.container, shortcut)) {
                    activity.updateStageStatus(activity.getString(R.string.wehavephones_install_sent, name), false);
                }
                else {
                    activity.updateStageStatus(activity.getString(R.string.wehavephones_stage_launch_failed), true);
                }
            });
        });
    }

    /**
     * Single-runtime one-button flow under the launch overlay; after it steps
     * aside, stage changes are mirrored as toasts over the game.
     */
    private static void startOneButton(final WeHavePhonesActivity activity) {
        final android.content.Context appContext = activity.getApplicationContext();
        final OneButtonLauncher.Stage[] shown = {null};
        LaunchOverlayController.begin(activity);
        OneButtonLauncher.start(activity, new OneButtonLauncher.Listener() {
            @Override
            public void onStage(OneButtonLauncher.Stage stage, String message, boolean error) {
                activity.updateStageStatus(message, error);
                boolean covered = LaunchOverlayController.isCovering();
                LaunchOverlayController.onOneButtonStage(appContext, stage, message, error);
                if (stage == shown[0] && !error) return;
                shown[0] = stage;
                if (!covered) Toast.makeText(appContext, message, error ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onProgress(double progress) {
                LaunchOverlayController.onOneButtonProgress(progress);
            }
        });
    }

    /**
     * Play's "Open Battle.net" (and Play with no game installed yet): start
     * Battle.net alone, which signs in with the login it saved itself.
     */
    public static void openBattleNet(WeHavePhonesActivity activity, WeHavePhonesActivity.StageUpdater listener) {
        if (blockDuringImport(activity)) return;
        listener.onStageUpdate(activity.getString(R.string.wehavephones_bnet_launching), false);
        XServerDisplayActivity live = XServerDisplayActivity.getActiveInstance();
        if (live != null && live.isSessionActive()) {
            Container container = WeHavePhonesContainers.resolve(activity);
            if (container != null) WeHavePhonesSessionLauncher.launch(activity, container, null);
            return;
        }
        Executors.newSingleThreadExecutor().execute(() -> {
            WeHavePhonesRuntimeApplier.Result result = WeHavePhonesRuntimeApplier.apply(activity, WeHavePhonesRuntimeApplier.Stage.BATTLE_NET);
            if (!result.success) {
                final String message = activity.getString(R.string.wehavephones_stage_apply_failed) + ": " + result.errors;
                activity.runOnUiThread(() -> listener.onStageUpdate(message, true));
                return;
            }
            final Container container = result.container;
            WeHavePhonesStatus status = WeHavePhonesStatus.load(activity.getApplicationContext());
            BattleNetGames.Game selected = BattleNetGames.pick(status.games, WeHavePhonesConfig.getProductDir(activity));
            final File shortcut = WeHavePhonesShortcuts.ensureBnetShortcut(container);
            String javascriptMode = BuildConfig.DEBUG && shortcut != null
                    ? new EnvVars(new Shortcut(container, shortcut).getExtra("envVars")).get("WHP_BNET_V8_MODE") : "";
            String flags = WeHavePhonesLaunchPlan.openFlags(selected != null ? selected.bnetGameId() : null,
                    javascriptMode, BuildConfig.DEBUG);
            if (!flags.isEmpty()) {
                File batch = WeHavePhonesPaths.bnetBat(container);
                String content = FileUtils.readString(batch);
                if (content == null || !FileUtils.write(batch,
                        WeHavePhonesLaunchPlan.appendBnetFlags(content, flags).getBytes(StandardCharsets.UTF_8))) {
                    activity.runOnUiThread(() -> listener.onStageUpdate(
                            activity.getString(R.string.wehavephones_launch_write_failed, "bnet.bat"), true));
                    return;
                }
            }
            // Pressing Play in Battle.net then also closes Battle.net, as Play's launch does.
            String exitStatus = WeHavePhonesBnetConfig.applyExitOnGameLaunch(WeHavePhonesPaths.bnetRoamingDir(container),
                    WeHavePhonesPaths.wehavephonesDir(activity));
            Log.i(OneButtonLauncher.TAG, "game launch: " + exitStatus);
            activity.runOnUiThread(() -> {
                if (WeHavePhonesSessionLauncher.launch(activity, container, shortcut)) {
                    activity.updateStageStatus(activity.getString(R.string.wehavephones_bnet_launching), false);
                    startLauncherReaper(activity);
                }
                else listener.onStageUpdate(activity.getString(R.string.wehavephones_stage_launch_failed), true);
            });
        });
    }

    /** Closes Battle.net once WoW runs. */
    static void startLauncherReaper(final WeHavePhonesActivity activity) {
        WeHavePhonesLauncherReaper.start(activity, new WeHavePhonesLauncherReaper.Listener() {
            @Override
            public void onLauncherClosed(final int count) {
                activity.runOnUiThread(() -> {
                    String message = activity.getString(R.string.wehavephones_memory_launcher_closed, count);
                    activity.updateStageStatus(message, false);
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onLauncherCloseSkipped(final String message) {
                activity.runOnUiThread(() -> activity.updateStageStatus(message, false));
            }
        });
    }
}
