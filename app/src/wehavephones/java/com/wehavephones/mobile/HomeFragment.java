package com.wehavephones.mobile;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListPopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.Fragment;

import com.wehavephones.R;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HomeFragment extends Fragment {
    private static final ExecutorService STATUS_EXECUTOR = Executors.newSingleThreadExecutor();

    private static final ExecutorService UPDATE_EXECUTOR = Executors.newSingleThreadExecutor();

    private WeHavePhonesActivity activity;
    private TextView stageStatus;
    private TextView gameTitle;
    private TextView gameEdition;
    private View crestGem;
    private TextView crestLetter;
    private View gameSwitch;
    private View playButton;
    private TextView playLabel;
    private ImageView playIcon;
    private View openBattleNet;
    private View setupDetails;
    private View gameOptions;
    private ListPopupWindow gamePopup;
    private GameVersionAdapter gameAdapter;
    private Button checkUpdatesButton;
    private DriverPicker driverPicker;
    private BattleNetGameActions.Entry selectedEntry;
    private List<BattleNetGameActions.Entry> entries = Collections.emptyList();
    private final Map<BattleNetGames.Game, BattleNetUpdateCheck.Result> updates = new HashMap<>();
    private boolean checkingUpdates;
    private int updateRun;
    private WeHavePhonesStatus status;
    private String stageMessage = "";
    private boolean stageError;

    private final WeHavePhonesActivity.StageUpdater stageUpdater = (message, error) -> {
        if (!isAdded()) return;
        stageMessage = message;
        stageError = error;
        renderStageStatus();
    };

    private final BattleNetAgentJobs.Listener jobListener = (job) -> {
        if (getView() == null) return;
        if (job.finished() && !BattleNetAgentJobs.isRunning()) refresh();
        renderGameActions();
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_wehavephones_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        activity = (WeHavePhonesActivity)getActivity();
        stageStatus = view.findViewById(R.id.TVStageStatus);
        gameTitle = view.findViewById(R.id.TVGameTitle);
        gameEdition = view.findViewById(R.id.TVGameEdition);
        crestGem = view.findViewById(R.id.VCrestGem);
        crestLetter = view.findViewById(R.id.TVCrestLetter);
        gameSwitch = view.findViewById(R.id.LLGameSwitch);
        playButton = view.findViewById(R.id.BTPlay);
        playLabel = view.findViewById(R.id.TVPlayLabel);
        playIcon = view.findViewById(R.id.IVPlayIcon);
        openBattleNet = view.findViewById(R.id.BTOpenBattleNet);
        setupDetails = view.findViewById(R.id.TVSetupDetails);
        gameOptions = view.findViewById(R.id.BTGameOptions);
        gameOptions.setOnClickListener(v -> showGameOptions());
        driverPicker = new DriverPicker(this, view.findViewById(R.id.LLDriverSection),
                view.findViewById(R.id.LLDriverSwitch), view.findViewById(R.id.TVDriverName));

        playButton.setOnClickListener((v) -> {
            if (status == null) return;
            if (missingSetup() != 0) {
                activity.showSection(new SetupFragment());
                return;
            }
            if (selectedEntry == null) {
                showGameVersions();
                return;
            }
            BattleNetGames.Game game = selectedEntry.game;
            if (!game.installed || hasUpdate(game)) {
                BattleNetGameActions.start(activity, selectedEntry,
                        game.installed ? BattleNetAgentJobs.Action.UPDATE : BattleNetAgentJobs.Action.INSTALL);
                return;
            }
            LaunchOverlayController.setGame(new LaunchOverlayController.Game(
                    game.getTitle(), game.version, GameVersionAdapter.accent(game), GameVersionAdapter.monogram(game)));
            WeHavePhonesPlayController.play(activity);
        });
        openBattleNet.setOnClickListener((v) ->
                WeHavePhonesPlayController.openBattleNet(activity, activity::updateStageStatus));
        gameSwitch.setOnClickListener((v) -> showGameVersions());
        setupDetails.setOnClickListener((v) -> activity.showSection(new DiagnosticsFragment()));
    }

    @Override
    public void onResume() {
        super.onResume();
        activity.setStageUpdater(stageUpdater);
        BattleNetAgentJobs.addListener(jobListener);
        activity.setAtmosphereAnimated(true);
        refresh();
        driverPicker.refresh();
    }

    @Override
    public void onPause() {
        BattleNetAgentJobs.removeListener(jobListener);
        if (gamePopup != null) gamePopup.dismiss();
        activity.setStageUpdater(null);
        activity.setAtmosphereAnimated(false);
        super.onPause();
    }

    public void refresh() {
        final Context context = getContext();
        final View view = getView();
        if (context == null || view == null) return;
        final Context appContext = context.getApplicationContext();
        final WeHavePhonesActivity host = activity;
        STATUS_EXECUTOR.execute(() -> {
            final WeHavePhonesStatus loaded;
            final List<BattleNetGameActions.Entry> loadedEntries;
            try {
                loaded = WeHavePhonesStatus.load(appContext);
                loadedEntries = BattleNetGameActions.entries(appContext, loaded.games);
            }
            catch (Throwable throwable) {
                return;
            }
            host.runOnUiThread(() -> {
                if (getView() != view) return;
                if (gamePopup != null) gamePopup.dismiss();
                status = loaded;
                entries = loadedEntries;
                updateRun++;
                checkingUpdates = false;
                updates.clear();
                render();
            });
        });
    }

    private void render() {
        BattleNetGames.Game selected = BattleNetGames.pick(games(), WeHavePhonesConfig.getProductDir(requireContext()));
        selectedEntry = null;
        for (BattleNetGameActions.Entry entry : entries) {
            if (entry.game == selected) {
                selectedEntry = entry;
                break;
            }
        }
        setupDetails.setVisibility(missingSetup() != 0 ? View.VISIBLE : View.GONE);
        renderGame();
        checkSelectedGame();
    }

    private List<BattleNetGames.Game> games() {
        List<BattleNetGames.Game> games = new ArrayList<>();
        for (BattleNetGameActions.Entry entry : entries) games.add(entry.game);
        return games;
    }

    private void renderGame() {
        BattleNetGames.Game game = selectedEntry == null ? null : selectedEntry.game;
        if (game == null) {
            gameTitle.setText(R.string.wehavephones_game_default_title);
            gameEdition.setText(R.string.wehavephones_game_not_installed);
            tintCrest("W");
        } else {
            gameTitle.setText(game.getTitle());
            String edition = game.getEdition();
            String detail = game.installed ? game.version : getString(R.string.wehavephones_game_not_installed_short);
            String line = edition.isEmpty() ? detail : detail.isEmpty() ? edition : edition + "  \u00b7  " + detail;
            if (hasUpdate(game)) {
                SpannableStringBuilder text = new SpannableStringBuilder(line);
                if (!line.isEmpty()) text.append("  \u00b7  ");
                int start = text.length();
                text.append(getString(R.string.wehavephones_home_update_available, updates.get(game).latestVersion));
                text.setSpan(new ForegroundColorSpan(requireContext().getColor(R.color.wehavephones_accent)),
                        start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                gameEdition.setText(text);
            } else {
                gameEdition.setText(line);
            }
            tintCrest(GameVersionAdapter.monogram(game));
        }
        renderGameActions();
        renderStageStatus();
    }

    private boolean hasUpdate(BattleNetGames.Game game) {
        BattleNetUpdateCheck.Result result = updates.get(game);
        return result != null && result.updateAvailable;
    }

    private void checkSelectedGame() {
        if (selectedEntry != null && selectedEntry.game.installed && !updates.containsKey(selectedEntry.game)) {
            checkUpdates(Collections.singletonList(selectedEntry), false);
        }
    }

    private void checkUpdates(List<BattleNetGameActions.Entry> candidates, boolean force) {
        if (status == null || status.container == null || checkingUpdates) return;
        if (force) {
            checkingUpdates = true;
            renderCheckUpdatesButton();
        }
        final int run = ++updateRun;
        final File root = WeHavePhonesPaths.wowRootFor(status.container);
        final WeHavePhonesActivity host = activity;
        final View view = getView();
        UPDATE_EXECUTOR.execute(() -> {
            Map<BattleNetGames.Game, BattleNetUpdateCheck.Result> results = new HashMap<>();
            for (BattleNetGameActions.Entry entry : candidates) {
                if (entry.game.installed && !entry.productCode.isEmpty()) {
                    results.put(entry.game, BattleNetUpdateCheck.check(root, entry.productCode, force));
                }
            }
            host.runOnUiThread(() -> {
                if (getView() != view || run != updateRun) return;
                updates.putAll(results);
                if (force) checkingUpdates = false;
                renderGame();
                if (gameAdapter != null) gameAdapter.notifyDataSetChanged();
                renderCheckUpdatesButton();
            });
        });
    }

    private void tintCrest(String letter) {
        Drawable gem = crestGem.getBackground().mutate();
        gem.setTintMode(PorterDuff.Mode.MULTIPLY);
        gem.setTint(0xFF0D315A);
        crestLetter.setText(letter);
    }

    private void showGameVersions() {
        if (entries.isEmpty() || gamePopup != null) return;
        Context context = requireContext();
        List<BattleNetGameActions.Entry> choices = entries;
        final ListPopupWindow popup = new ListPopupWindow(context);
        gamePopup = popup;
        gameAdapter = new GameVersionAdapter(context, games(), updates);
        popup.setAnchorView(gameSwitch);
        popup.setAdapter(gameAdapter);
        popup.setWidth(Math.round(320 * context.getResources().getDisplayMetrics().density));
        popup.setBackgroundDrawable(context.getDrawable(R.drawable.wehavephones_card_background));
        popup.setModal(true);
        if (choices.stream().anyMatch(entry -> entry.game.installed)) {
            checkUpdatesButton = (Button)getLayoutInflater().inflate(R.layout.wehavephones_game_update_button, null);
            popup.setPromptPosition(ListPopupWindow.POSITION_PROMPT_BELOW);
            popup.setPromptView(checkUpdatesButton);
            checkUpdatesButton.setOnClickListener(v -> checkUpdates(choices, true));
            renderCheckUpdatesButton();
        }
        popup.setOnDismissListener(() -> {
            gamePopup = null;
            gameAdapter = null;
            checkUpdatesButton = null;
        });
        popup.setOnItemClickListener((parent, item, position, id) -> {
            popup.dismiss();
            selectedEntry = choices.get(position);
            WeHavePhonesConfig.setProductDir(context, selectedEntry.game.selectionKey());
            renderGame();
            checkSelectedGame();
        });
        popup.show();
    }

    private void renderCheckUpdatesButton() {
        if (checkUpdatesButton == null) return;
        checkUpdatesButton.setEnabled(!checkingUpdates && !BattleNetAgentJobs.isRunning());
        checkUpdatesButton.setText(checkingUpdates ? R.string.wehavephones_games_checking_updates
                : R.string.wehavephones_games_check_updates);
    }

    private void showGameOptions() {
        BattleNetGameActions.Entry entry = selectedEntry;
        if (entry == null || BattleNetAgentJobs.isRunning()) return;
        PopupMenu menu = new PopupMenu(requireContext(), gameOptions);
        menu.getMenu().add(R.string.wehavephones_games_settings).setOnMenuItemClickListener(item -> {
            GameAccountDialog.show(activity, status.container, entry);
            return true;
        });
        if (entry.game.installed) {
            menu.getMenu().add(R.string.wehavephones_agent_action_repair).setOnMenuItemClickListener(item -> {
                BattleNetGameActions.start(activity, entry, BattleNetAgentJobs.Action.REPAIR);
                return true;
            });
            menu.getMenu().add(R.string.wehavephones_games_uninstall_confirm).setOnMenuItemClickListener(item -> {
                BattleNetGameActions.confirmUninstall(activity, entry);
                return true;
            });
        }
        menu.show();
    }

    /** The first missing piece Play needs, as a message, or 0 when setup is complete. */
    private int missingSetup() {
        if (status == null) return 0;
        if (!status.imageFsValid) return R.string.wehavephones_missing_system;
        if (!status.containerReady) return R.string.wehavephones_missing_container;
        if (!status.bnetInstalled) return R.string.wehavephones_missing_bnet;
        return 0;
    }

    private void renderGameActions() {
        boolean blocked = missingSetup() != 0;
        boolean busy = BattleNetAgentJobs.isRunning();
        BattleNetGames.Game game = selectedEntry == null ? null : selectedEntry.game;
        int label = blocked ? R.string.wehavephones_finish_setup
                : game == null ? R.string.wehavephones_game_version
                : !game.installed ? R.string.wehavephones_install
                : hasUpdate(game) ? R.string.wehavephones_games_update : R.string.wehavephones_play;
        int icon = blocked ? R.drawable.wehavephones_ic_setup
                : label == R.string.wehavephones_play ? R.drawable.wehavephones_ic_play_fill
                : game == null ? R.drawable.wehavephones_ic_caret_down : R.drawable.wehavephones_ic_download;
        playButton.setBackgroundResource(blocked ? R.drawable.wehavephones_button_warn : R.drawable.wehavephones_button_primary);
        playButton.setEnabled(!busy && status != null);
        openBattleNet.setEnabled(!busy && status != null && status.bnetInstalled);
        playIcon.setImageResource(icon);
        playLabel.setText(label);
        playLabel.setTextSize(label == R.string.wehavephones_play ? 26 : 22);
        gameOptions.setVisibility(!blocked && game != null ? View.VISIBLE : View.GONE);
        gameOptions.setEnabled(!busy);
        renderCheckUpdatesButton();
    }

    /** "<stage>. <what Play will do>", or the error alone in red. */
    private void renderStageStatus() {
        Context context = requireContext();
        int missing = missingSetup();
        if (missing != 0) {
            SpannableStringBuilder text = new SpannableStringBuilder(getString(missing));
            text.setSpan(new ForegroundColorSpan(context.getColor(R.color.wehavephones_error)), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            stageStatus.setText(text);
            return;
        }
        if (status != null && selectedEntry == null && !stageError
                && (stageMessage.isEmpty() || !activity.hasStageFromThisRun())) {
            stageStatus.setText(R.string.wehavephones_home_setup_complete);
            return;
        }
        // A message restored from an earlier app run ("Still logging in...") is stale.
        String message = stageMessage.isEmpty() || !activity.hasStageFromThisRun()
                ? getString(R.string.wehavephones_stage_idle) : stageMessage;
        SpannableStringBuilder text = new SpannableStringBuilder(message);
        if (stageError) {
            text.setSpan(new ForegroundColorSpan(context.getColor(R.color.wehavephones_error)), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        else {
            text.setSpan(new ForegroundColorSpan(context.getColor(R.color.wehavephones_text)), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (selectedEntry != null && selectedEntry.game.installed && !hasUpdate(selectedEntry.game)) {
                text.append(' ').append(getString(R.string.wehavephones_home_plan_battlenet));
            }
        }
        stageStatus.setText(text);
    }
}
