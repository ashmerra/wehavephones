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
import android.widget.ImageView;
import android.widget.ListPopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.wehavephones.R;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HomeFragment extends Fragment {
    private static final ExecutorService STATUS_EXECUTOR = Executors.newSingleThreadExecutor();

    private WeHavePhonesActivity activity;
    private TextView stageStatus;
    private TextView gameTitle;
    private TextView gameEdition;
    private View crestGem;
    private TextView crestLetter;
    private View gameSwitch;
    private View gameSwitchCaret;
    private TextView gameVersionsHint;
    private View playButton;
    private TextView playLabel;
    private ImageView playIcon;
    private View openBattleNet;
    private View setupDetails;
    private DriverPicker driverPicker;
    private BattleNetGames.Game selectedGame;
    private WeHavePhonesStatus status;
    private String stageMessage = "";
    private boolean stageError;

    private final WeHavePhonesActivity.StageUpdater stageUpdater = (message, error) -> {
        if (!isAdded()) return;
        stageMessage = message;
        stageError = error;
        renderStageStatus();
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
        gameSwitchCaret = view.findViewById(R.id.IVGameSwitch);
        playButton = view.findViewById(R.id.BTPlay);
        playLabel = view.findViewById(R.id.TVPlayLabel);
        gameVersionsHint = view.findViewById(R.id.TVGameVersionsHint);
        playIcon = view.findViewById(R.id.IVPlayIcon);
        openBattleNet = view.findViewById(R.id.BTOpenBattleNet);
        setupDetails = view.findViewById(R.id.TVSetupDetails);
        driverPicker = new DriverPicker(this, view.findViewById(R.id.LLDriverSection),
                view.findViewById(R.id.LLDriverSwitch), view.findViewById(R.id.TVDriverName));

        playButton.setOnClickListener((v) -> {
            if (status == null) return;
            if (missingSetup() != 0) {
                activity.showSection(new SetupFragment());
                return;
            }
            if (selectedGame == null) {
                WeHavePhonesPlayController.openBattleNet(activity, activity::updateStageStatus);
                return;
            }
            if (selectedGame != null && !selectedGame.installed) {
                WeHavePhonesPlayController.install(activity, selectedGame);
                return;
            }
            LaunchOverlayController.setGame(selectedGame == null ? null : new LaunchOverlayController.Game(
                    selectedGame.getTitle(), selectedGame.version, GameVersionAdapter.accent(selectedGame), monogram(selectedGame)));
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
        activity.setAtmosphereAnimated(true);
        refresh();
        driverPicker.refresh();
    }

    @Override
    public void onPause() {
        activity.setStageUpdater(null);
        activity.setAtmosphereAnimated(false);
        super.onPause();
    }

    public void refresh() {
        final Context context = getContext();
        if (context == null) return;
        final Context appContext = context.getApplicationContext();
        STATUS_EXECUTOR.execute(() -> {
            final WeHavePhonesStatus loaded;
            try {
                loaded = WeHavePhonesStatus.load(appContext);
            }
            catch (Throwable throwable) {
                return;
            }
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                status = loaded;
                render();
            });
        });
    }

    private void render() {
        BattleNetGames.Game selected = BattleNetGames.pick(status.games, WeHavePhonesConfig.getProductDir(requireContext()));
        renderGameVersions(selected);
        renderGame(selected);
        renderSetupState();
        renderStageStatus();
    }

    private void renderGame(BattleNetGames.Game game) {
        selectedGame = game;
        renderPlayButton();
        if (game == null) {
            gameTitle.setText(R.string.wehavephones_game_default_title);
            gameEdition.setText(R.string.wehavephones_game_not_installed);
            tintCrest(GameVersionAdapter.DEFAULT_ACCENT, "W");
            return;
        }
        gameTitle.setText(game.getTitle());
        String edition = game.getEdition();
        String detail = game.installed ? game.version : getString(R.string.wehavephones_game_not_installed_short);
        if (edition.isEmpty()) gameEdition.setText(detail);
        else gameEdition.setText(detail.isEmpty() ? edition : edition + "  \u00b7  " + detail);
        tintCrest(GameVersionAdapter.accent(game), monogram(game));
    }

    private void tintCrest(int accent, String letter) {
        Drawable gem = crestGem.getBackground().mutate();
        gem.setTintMode(PorterDuff.Mode.MULTIPLY);
        gem.setTint(0xFF0D315A);
        crestLetter.setText(letter);
    }

    private static String monogram(BattleNetGames.Game game) {
        WowFlavor.Family family = game.flavor.family;
        if (family == null) {
            String name = game.getDisplayName();
            return name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(java.util.Locale.US);
        }
        switch (family) {
            case FOREVER: return "F";
            case CLASSIC: return "C";
            case TITAN: return "T";
            case ANNIVERSARY: return "A";
            case CLASSIC_ERA: return "E";
            default: return "W";
        }
    }

    /**
     * The title opens the game version list: what the Battle.net client on
     * this device reports (installed games first, then games it lists but has
     * not installed); the choice drives Play/Install and Add-ons.
     */
    private void renderGameVersions(BattleNetGames.Game selected) {
        boolean empty = status.games.isEmpty();
        gameSwitch.setEnabled(!empty);
        gameSwitchCaret.setVisibility(empty ? View.GONE : View.VISIBLE);
        gameVersionsHint.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void showGameVersions() {
        if (status == null || status.games.isEmpty()) return;
        Context context = requireContext();
        final ListPopupWindow popup = new ListPopupWindow(context);
        popup.setAnchorView(gameSwitch);
        popup.setAdapter(new GameVersionAdapter(context, status.games, true));
        popup.setWidth(Math.round(320 * context.getResources().getDisplayMetrics().density));
        popup.setBackgroundDrawable(context.getDrawable(R.drawable.wehavephones_card_background));
        popup.setModal(true);
        popup.setOnItemClickListener((parent, item, position, id) -> {
            popup.dismiss();
            if (!isAdded() || position >= status.games.size()) return;
            BattleNetGames.Game game = status.games.get(position);
            WeHavePhonesConfig.setProductDir(requireContext(), game.selectionKey());
            renderGame(game);
        });
        popup.show();
    }

    /** The first missing piece Play needs, as a message, or 0 when setup is complete. */
    private int missingSetup() {
        if (status == null) return 0;
        if (!status.imageFsValid) return R.string.wehavephones_missing_system;
        if (!status.containerReady) return R.string.wehavephones_missing_container;
        if (!status.bnetInstalled) return R.string.wehavephones_missing_bnet;
        return 0;
    }

    /** Missing setup turns Play into a red "Finish setup" button; otherwise it plays, installs or opens Battle.net. */
    private void renderPlayButton() {
        boolean blocked = missingSetup() != 0;
        BattleNetGames.Game game = selectedGame;
        playButton.setBackgroundResource(blocked ? R.drawable.wehavephones_button_warn : R.drawable.wehavephones_button_primary);
        playIcon.setImageResource(blocked ? R.drawable.wehavephones_ic_setup : R.drawable.wehavephones_ic_play_fill);
        playLabel.setText(blocked ? R.string.wehavephones_finish_setup : game == null ? R.string.wehavephones_open_bnet
                : !game.installed ? R.string.wehavephones_install : R.string.wehavephones_play);
        playLabel.setTextSize(blocked ? 22 : game == null ? 18 : 26);
    }

    private void renderSetupState() {
        renderPlayButton();
        openBattleNet.setEnabled(status.bnetInstalled);
        setupDetails.setVisibility(missingSetup() != 0 ? View.VISIBLE : View.GONE);
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
        if (status != null && selectedGame == null && !stageError
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
            text.append(' ').append(getString(R.string.wehavephones_home_plan_battlenet));
        }
        stageStatus.setText(text);
    }
}
