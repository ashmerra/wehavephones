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
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListPopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
    private View verifyGame;
    private View uninstallGame;
    private View accountSection;
    private View accountSwitch;
    private TextView accountName;
    private View accountCaret;
    private View driverSection;
    private ListPopupWindow accountPopup;
    /** The selected game's WoW licenses in Battle.net's order, and Battle.net's own selection. */
    private List<BattleNetGameAccounts.Account> accounts = Collections.emptyList();
    private BattleNetGameAccounts.Account battleNetAccount;
    private ListPopupWindow gamePopup;
    private GameVersionAdapter gameAdapter;
    private DriverPicker driverPicker;
    private BattleNetGameActions.Entry selectedEntry;
    private List<BattleNetGameActions.Entry> entries = Collections.emptyList();
    private final Map<BattleNetGames.Game, BattleNetUpdateCheck.Result> updates = new HashMap<>();
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
        verifyGame = view.findViewById(R.id.BTVerifyGame);
        uninstallGame = view.findViewById(R.id.BTUninstallGame);
        accountSection = view.findViewById(R.id.LLAccountSection);
        accountSwitch = view.findViewById(R.id.LLAccountSwitch);
        accountName = view.findViewById(R.id.TVAccountName);
        accountCaret = view.findViewById(R.id.IVAccountCaret);
        driverSection = view.findViewById(R.id.LLDriverSection);
        driverPicker = new DriverPicker(this, driverSection,
                view.findViewById(R.id.LLDriverSwitch), view.findViewById(R.id.TVDriverName));
        verifyGame.setOnClickListener(v -> {
            if (canManageGame()) BattleNetGameActions.start(activity, selectedEntry, BattleNetAgentJobs.Action.REPAIR);
        });
        uninstallGame.setOnClickListener(v -> {
            if (canManageGame()) BattleNetGameActions.confirmUninstall(activity, selectedEntry);
        });
        accountSwitch.setOnClickListener(v -> showAccounts());

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
        if (accountPopup != null) accountPopup.dismiss();
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
        loadAccounts();
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
        BattleNetGameActions.Entry entry = selectedEntry;
        if (entry == null || !entry.game.installed || entry.productCode.isEmpty() || status.container == null
                || updates.containsKey(entry.game)) return;
        final int run = ++updateRun;
        final File root = WeHavePhonesPaths.wowRootFor(status.container);
        final WeHavePhonesActivity host = activity;
        final View view = getView();
        UPDATE_EXECUTOR.execute(() -> {
            BattleNetUpdateCheck.Result result = BattleNetUpdateCheck.check(root, entry.productCode, false);
            host.runOnUiThread(() -> {
                if (getView() != view || run != updateRun) return;
                updates.put(entry.game, result);
                renderGame();
                if (gameAdapter != null) gameAdapter.notifyDataSetChanged();
            });
        });
    }

    private void loadAccounts() {
        if (accountPopup != null) accountPopup.dismiss();
        accounts = Collections.emptyList();
        battleNetAccount = null;
        renderAccount();
        BattleNetGameActions.Entry entry = selectedEntry;
        if (entry == null || status.container == null) return;
        final File logs = WeHavePhonesPaths.bnetLogsDir(status.container);
        final WeHavePhonesActivity host = activity;
        final View view = getView();
        STATUS_EXECUTOR.execute(() -> {
            BattleNetGameAccounts.Snapshot snapshot = BattleNetGameAccounts.read(logs, entry.uid);
            host.runOnUiThread(() -> {
                if (getView() != view || selectedEntry != entry) return;
                if (accountPopup != null) accountPopup.dismiss();
                accounts = snapshot.accounts;
                battleNetAccount = snapshot.selected;
                renderAccount();
            });
        });
    }

    /** The license Play will use: the one chosen here, else Battle.net's own selection. */
    private BattleNetGameAccounts.Account playAccount() {
        if (selectedEntry == null) return null;
        BattleNetGameAccounts.Account chosen = BattleNetGameAccounts.Account.fromId(
                WeHavePhonesConfig.getGameAccount(requireContext(), selectedEntry.uid));
        if (chosen != null && accounts.contains(chosen)) return chosen;
        if (battleNetAccount != null && accounts.contains(battleNetAccount)) return battleNetAccount;
        return accounts.isEmpty() ? null : accounts.get(0);
    }

    private String accountLabel(BattleNetGameAccounts.Account account) {
        for (BattleNetGameAccounts.Account other : accounts) {
            if (!other.region.equals(account.region)) {
                return getString(R.string.wehavephones_account_with_region, account.name, account.region);
            }
        }
        return account.name;
    }

    private void renderAccount() {
        BattleNetGameAccounts.Account account = playAccount();
        if (account == null) accountName.setText(R.string.wehavephones_account_bnet_default);
        else accountName.setText(accountLabel(account));
        renderGameActions();
    }

    private void showAccounts() {
        if (accountPopup != null || !accountSwitch.isEnabled() || accounts.size() < 2) return;
        Context context = requireContext();
        final List<BattleNetGameAccounts.Account> choices = accounts;
        final BattleNetGameActions.Entry entry = selectedEntry;
        List<String> rows = new ArrayList<>();
        for (BattleNetGameAccounts.Account account : choices) rows.add(accountLabel(account));
        ListPopupWindow popup = new ListPopupWindow(context);
        accountPopup = popup;
        popup.setAnchorView(accountSwitch);
        popup.setWidth(accountSwitch.getWidth());
        popup.setBackgroundDrawable(context.getDrawable(R.drawable.wehavephones_card_background));
        popup.setModal(true);
        popup.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, rows));
        popup.setOnDismissListener(() -> accountPopup = null);
        popup.setOnItemClickListener((parent, item, position, id) -> {
            popup.dismiss();
            if (entry != selectedEntry) return;
            // Applied by Play (OneButtonLauncher), which selects it in Battle.net before clicking Play.
            WeHavePhonesConfig.setGameAccount(context, entry.uid, choices.get(position).id());
            renderAccount();
        });
        popup.show();
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
        popup.setOnDismissListener(() -> {
            gamePopup = null;
            gameAdapter = null;
        });
        popup.setOnItemClickListener((parent, item, position, id) -> {
            popup.dismiss();
            selectedEntry = choices.get(position);
            WeHavePhonesConfig.setProductDir(context, selectedEntry.game.selectionKey());
            renderGame();
            checkSelectedGame();
            loadAccounts();
        });
        popup.show();
    }

    private boolean canManageGame() {
        return status != null && missingSetup() == 0 && !BattleNetAgentJobs.isRunning()
                && selectedEntry != null && selectedEntry.game.installed;
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
        boolean manage = canManageGame();
        verifyGame.setEnabled(manage);
        uninstallGame.setEnabled(manage);
        boolean showAccount = !blocked && game != null;
        accountSection.setVisibility(showAccount ? View.VISIBLE : View.GONE);
        accountSwitch.setEnabled(showAccount && !busy && accounts.size() > 1);
        accountCaret.setVisibility(accounts.size() > 1 ? View.VISIBLE : View.INVISIBLE);
        ViewGroup.MarginLayoutParams driverLayout = (ViewGroup.MarginLayoutParams)driverSection.getLayoutParams();
        int driverMargin = showAccount ? Math.round(10 * getResources().getDisplayMetrics().density) : 0;
        if (driverLayout.getMarginStart() != driverMargin) {
            driverLayout.setMarginStart(driverMargin);
            driverSection.setLayoutParams(driverLayout);
        }
    }

    /** The current stage, or the error in red. */
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
        }
        stageStatus.setText(text);
    }
}
