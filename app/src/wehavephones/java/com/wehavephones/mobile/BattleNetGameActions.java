package com.wehavephones.mobile;

import android.content.Context;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.wehavephones.R;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The Play selector's game catalog and per-game Agent actions. */
final class BattleNetGameActions {
    private static final WowFlavor[] CATALOG = {
            WowFlavor.RETAIL, WowFlavor.FOREVER, WowFlavor.CLASSIC, WowFlavor.ANNIVERSARY, WowFlavor.CLASSIC_ERA,
    };

    private BattleNetGameActions() {}

    /** The game and the ids the Agent needs. */
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
        out.sort(Comparator.comparing(entry -> !entry.game.installed));
        return out;
    }

    private static WeHavePhonesLaunchPlan plan(Context context) {
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

    static String title(BattleNetGames.Game game) {
        String edition = game.getEdition();
        String name = game.getTitle();
        return edition.isEmpty() ? name : name + "  ·  " + edition;
    }

    static void confirmUninstall(WeHavePhonesActivity activity, Entry entry) {
        String name = title(entry.game);
        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.wehavephones_games_uninstall_title, name))
                .setMessage(R.string.wehavephones_games_uninstall_message)
                .setPositiveButton(R.string.wehavephones_games_uninstall_confirm,
                        (dialog, which) -> start(activity, entry, BattleNetAgentJobs.Action.UNINSTALL))
                .setNegativeButton(R.string.wehavephones_games_cancel, null)
                .show();
    }

    /** Starts an Agent job for the selected game. */
    static void start(WeHavePhonesActivity activity, Entry entry, BattleNetAgentJobs.Action action) {
        BattleNetGames.Game game = entry.game;
        BattleNetAgentJobs.Job job = new BattleNetAgentJobs.Job(action, entry.uid, entry.productCode,
                title(game), GameVersionAdapter.monogram(game), GameVersionAdapter.accent(game),
                !game.directoryName.isEmpty() ? game.directoryName : game.flavor.directoryName);
        String error = BattleNetAgentJobs.start(activity, job);
        if (error != null) {
            activity.updateStageStatus(error, true);
            Toast.makeText(activity, error, Toast.LENGTH_LONG).show();
        }
    }
}
