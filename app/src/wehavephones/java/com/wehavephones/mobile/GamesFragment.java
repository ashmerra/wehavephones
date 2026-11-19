package com.wehavephones.mobile;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.wehavephones.R;
import com.wehavephones.container.Container;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Games: every WoW game Battle.net offers or has installed, each with
 * Install, Update, Check files, Uninstall and its account settings. The work
 * runs through {@link BattleNetAgentJobs} (Blizzard's Agent, no Battle.net
 * window). Update badges come from {@link BattleNetUpdateCheck}.
 */
public class GamesFragment extends Fragment {
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    /** Live games offered even before Battle.net has registered them on this device. */
    private static final WowFlavor[] CATALOG = {
            WowFlavor.RETAIL, WowFlavor.FOREVER, WowFlavor.CLASSIC, WowFlavor.ANNIVERSARY, WowFlavor.CLASSIC_ERA,
    };

    private WeHavePhonesActivity activity;
    private LinearLayout list;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, BattleNetUpdateCheck.Result> updates = new HashMap<>();
    private Container container;
    private int loads;

    /** One row: the game and the ids the Agent needs. */
    static final class Entry {
        final BattleNetGames.Game game;
        final String uid;
        final String productCode;

        Entry(BattleNetGames.Game game, String uid, String productCode) {
            this.game = game;
            this.uid = uid;
            this.productCode = productCode;
        }
    }

    private final BattleNetAgentJobs.Listener listener = (status) -> {
        if (!isAdded()) return;
        render();
        if (status.finished()) load(true);
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_wehavephones_games, parent, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        activity = (WeHavePhonesActivity)getActivity();
        list = view.findViewById(R.id.LLGamesList);
        view.findViewById(R.id.BTGamesRefresh).setOnClickListener((v) -> load(true));
    }

    @Override
    public void onResume() {
        super.onResume();
        activity.setAtmosphereAnimated(true);
        BattleNetAgentJobs.addListener(listener);
        load(false);
    }

    @Override
    public void onPause() {
        BattleNetAgentJobs.removeListener(listener);
        activity.setAtmosphereAnimated(false);
        super.onPause();
    }

    private void load(boolean forceUpdateCheck) {
        final Context context = requireContext().getApplicationContext();
        final int run = ++loads;
        LOADER.execute(() -> {
            WeHavePhonesStatus status = WeHavePhonesStatus.load(context);
            List<Entry> loaded = entries(context, status.games);
            post(run, () -> {
                container = status.container;
                entries.clear();
                entries.addAll(loaded);
                render();
            });
            File root = status.container != null ? WeHavePhonesPaths.wowRootFor(status.container) : null;
            if (forceUpdateCheck) BattleNetUpdateCheck.clear();
            for (Entry entry : loaded) {
                if (!entry.game.installed || entry.game.productCode.isEmpty()) continue;
                BattleNetUpdateCheck.Result result = BattleNetUpdateCheck.check(root, entry.game.productCode, false);
                if (result == null) continue;
                post(run, () -> {
                    updates.put(entry.game.productCode, result);
                    render();
                });
            }
        });
    }

    private void post(int run, Runnable action) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            if (isAdded() && run == loads) action.run();
        });
    }

    /** Battle.net's games plus the live catalog, each with its Agent uid and product code. */
    static List<Entry> entries(Context context, List<BattleNetGames.Game> games) {
        WeHavePhonesLaunchPlan plan = plan(context);
        List<Entry> out = new ArrayList<>();
        for (BattleNetGames.Game game : games) {
            // Battle.net may list a game by uid only (wow_ptr): name it from the launch table.
            WeHavePhonesLaunchPlan.Product known = plan != null && !game.flavor.isKnown() ? plan.forUid(game.uid) : null;
            if (known != null && WowFlavor.forDirectory(known.dir).isKnown()) game.flavor = WowFlavor.forDirectory(known.dir);
            String code = !game.productCode.isEmpty() ? game.productCode : game.flavor.productCode;
            String uid = !game.uid.isEmpty() ? game.uid
                    : plan != null && !game.directoryName.isEmpty() ? plan.resolve(game.directoryName).uid : code;
            out.add(new Entry(game, uid, code));
        }
        for (WowFlavor flavor : CATALOG) {
            boolean known = false;
            for (Entry entry : out) {
                if (entry.game.flavor == flavor || flavor.productCode.equalsIgnoreCase(entry.productCode)
                        || flavor.directoryName.equalsIgnoreCase(entry.game.directoryName)) {
                    known = true;
                    break;
                }
            }
            if (known) continue;
            BattleNetGames.Game game = new BattleNetGames.Game();
            game.flavor = flavor;
            game.productCode = flavor.productCode;
            game.directoryName = flavor.directoryName;
            String uid = plan != null ? plan.resolve(flavor.directoryName).uid : flavor.productCode;
            game.uid = uid;
            out.add(new Entry(game, uid, flavor.productCode));
        }
        return out;
    }

    static WeHavePhonesLaunchPlan plan(Context context) {
        try (InputStream input = context.getAssets().open(WeHavePhonesLaunchPlan.ASSET_PATH)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) > 0) out.write(buffer, 0, n);
            return WeHavePhonesLaunchPlan.parse(new String(out.toByteArray(), StandardCharsets.UTF_8), null);
        }
        catch (Exception e) {
            return null;
        }
    }

    // ---- rows ---------------------------------------------------------------------------

    private void render() {
        if (list == null) return;
        list.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        String selected = WeHavePhonesConfig.getProductDir(requireContext());
        for (Entry entry : entries) {
            View row = inflater.inflate(R.layout.wehavephones_game_row, list, false);
            bind(row, entry, selected);
            list.addView(row);
        }
    }

    private void bind(View row, Entry entry, String selected) {
        Context context = requireContext();
        BattleNetGames.Game game = entry.game;
        ((TextView)row.findViewById(R.id.TVGameRowTitle)).setText(title(game));
        ((TextView)row.findViewById(R.id.TVGameRowLetter)).setText(GameVersionAdapter.monogram(game));
        Drawable gem = row.findViewById(R.id.VGameRowGem).getBackground().mutate();
        gem.setTintMode(PorterDuff.Mode.MULTIPLY);
        gem.setTint(0xFF0D315A);

        TextView status = row.findViewById(R.id.TVGameRowStatus);
        Button primary = row.findViewById(R.id.BTGameRowPrimary);
        Button repair = row.findViewById(R.id.BTGameRowRepair);
        Button settings = row.findViewById(R.id.BTGameRowSettings);
        Button uninstall = row.findViewById(R.id.BTGameRowUninstall);
        LaunchBarView bar = row.findViewById(R.id.VGameRowBar);

        BattleNetAgentJobs.Status job = BattleNetAgentJobs.current();
        boolean busy = BattleNetAgentJobs.isRunning();
        boolean mine = job != null && entry.uid.equals(job.job.uid) && (busy || job.phase == BattleNetAgentJobs.Phase.FAILED);
        BattleNetUpdateCheck.Result update = updates.get(game.productCode);

        if (mine) {
            status.setText(job.detail.isEmpty() ? job.headline : job.headline + "  ·  " + job.detail);
            status.setTextColor(context.getColor(job.phase == BattleNetAgentJobs.Phase.FAILED
                    ? R.color.wehavephones_error : R.color.wehavephones_text));
            bar.setVisibility(busy ? View.VISIBLE : View.GONE);
            bar.setFraction((float)Math.max(0, job.fraction), job.phase == BattleNetAgentJobs.Phase.FAILED);
        }
        else if (!game.installed) {
            status.setText(!game.sources.isEmpty() ? R.string.wehavephones_games_registered : R.string.wehavephones_games_not_installed);
        }
        else if (update != null && update.updateAvailable) {
            status.setText(getString(R.string.wehavephones_games_update_available, game.version, update.latestVersion));
            status.setTextColor(context.getColor(R.color.wehavephones_accent));
        }
        else if (update != null) status.setText(getString(R.string.wehavephones_games_up_to_date, game.version));
        else status.setText(getString(R.string.wehavephones_games_installed, game.version));

        boolean isSelected = game.installed && game.selectionKey().equalsIgnoreCase(selected);
        if (!game.installed) {
            primary.setText(R.string.wehavephones_install);
            primary.setOnClickListener((v) -> start(entry, BattleNetAgentJobs.Action.INSTALL));
        }
        else if (update != null && update.updateAvailable) {
            primary.setText(R.string.wehavephones_games_update);
            primary.setOnClickListener((v) -> start(entry, BattleNetAgentJobs.Action.UPDATE));
        }
        else {
            primary.setText(isSelected ? R.string.wehavephones_games_selected : R.string.wehavephones_games_play);
            primary.setEnabled(!isSelected);
            primary.setOnClickListener((v) -> {
                WeHavePhonesConfig.setProductDir(requireContext(), game.selectionKey());
                activity.showHome();
            });
        }
        primary.setVisibility(mine && busy ? View.GONE : View.VISIBLE);
        if (busy && !mine) primary.setEnabled(false);
        repair.setVisibility(game.installed && !(mine && busy) ? View.VISIBLE : View.GONE);
        repair.setEnabled(!busy);
        repair.setOnClickListener((v) -> start(entry, BattleNetAgentJobs.Action.REPAIR));
        uninstall.setVisibility(game.installed && !(mine && busy) ? View.VISIBLE : View.GONE);
        uninstall.setEnabled(!busy);
        uninstall.setOnClickListener((v) -> confirmUninstall(entry));
        settings.setOnClickListener((v) -> GameAccountDialog.show(activity, container, entry));
    }

    static String title(BattleNetGames.Game game) {
        String edition = game.getEdition();
        String name = game.getTitle();
        return edition.isEmpty() ? name : name + "  ·  " + edition;
    }

    private void confirmUninstall(Entry entry) {
        String name = title(entry.game);
        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.wehavephones_games_uninstall_title, name))
                .setMessage(R.string.wehavephones_games_uninstall_message)
                .setPositiveButton(R.string.wehavephones_games_uninstall_confirm,
                        (dialog, which) -> start(entry, BattleNetAgentJobs.Action.UNINSTALL))
                .setNegativeButton(R.string.wehavephones_games_cancel, null)
                .show();
    }

    private void start(Entry entry, BattleNetAgentJobs.Action action) {
        start(activity, entry, action);
    }

    /** Starts an Agent job for a row (also used by Play's Install). */
    static void start(WeHavePhonesActivity activity, Entry entry, BattleNetAgentJobs.Action action) {
        BattleNetGames.Game game = entry.game;
        BattleNetAgentJobs.Job job = new BattleNetAgentJobs.Job(action, entry.uid, entry.productCode,
                title(game), GameVersionAdapter.monogram(game), GameVersionAdapter.accent(game));
        String error = BattleNetAgentJobs.start(activity, job);
        if (error != null) activity.updateStageStatus(error, true);
        if (error != null) android.widget.Toast.makeText(activity, error, android.widget.Toast.LENGTH_LONG).show();
    }
}
