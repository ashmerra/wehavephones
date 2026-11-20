package com.wehavephones.mobile;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.wehavephones.R;
import com.wehavephones.container.Container;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Account and region settings for the selected game. Its region (which realms
 * and which Battle.net region WoW signs in to) can differ from the other
 * games, and Play writes it as WoW's portal for this game only. The WoW game
 * accounts Battle.net listed for the game (WoW1, WoW2) are shown from its log.
 */
final class GameAccountDialog {
    private GameAccountDialog() {}

    static void show(WeHavePhonesActivity activity, Container container, BattleNetGameActions.Entry entry) {
        final Context context = activity;
        final String dir = !entry.game.directoryName.isEmpty() ? entry.game.directoryName : entry.game.flavor.directoryName;
        Executors.newSingleThreadExecutor().execute(() -> {
            List<BattleNetGameAccounts.Account> accounts = container != null
                    ? BattleNetGameAccounts.forGame(WeHavePhonesPaths.bnetLogsDir(container), entry.uid) : new ArrayList<>();
            activity.runOnUiThread(() -> {
                if (!activity.isFinishing()) build(activity, context, dir, entry, accounts);
            });
        });
    }

    private static void build(WeHavePhonesActivity activity, Context context, String dir,
            BattleNetGameActions.Entry entry, List<BattleNetGameAccounts.Account> accounts) {
        int pad = Math.round(20 * context.getResources().getDisplayMetrics().density);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad / 2, pad, 0);

        TextView accountLabel = label(context, R.string.wehavephones_account_game_accounts);
        content.addView(accountLabel);
        TextView accountText = new TextView(context);
        accountText.setTextColor(context.getColor(R.color.wehavephones_text));
        accountText.setTextSize(14);
        accountText.setText(describe(context, accounts));
        content.addView(accountText);

        content.addView(label(context, R.string.wehavephones_account_region));
        String[] names = context.getResources().getStringArray(R.array.wehavephones_regions);
        String global = WeHavePhonesConfig.getGameRegion(context);
        boolean china = WowFirstBoot.isChina(global);
        RadioGroup group = new RadioGroup(context);
        String own = WeHavePhonesConfig.getGameRegionFor(context, dir);
        RadioButton follow = radio(context, context.getString(R.string.wehavephones_account_region_follow, regionName(context, global)));
        follow.setTag("");
        group.addView(follow);
        if (own.isEmpty()) follow.setChecked(true);
        for (int i = 0; i < WowFirstBoot.REGIONS.length && i < names.length; i++) {
            String region = WowFirstBoot.REGIONS[i];
            // China has its own Battle.net app and accounts, so it never mixes with the others.
            if (WowFirstBoot.isChina(region) != china) continue;
            RadioButton button = radio(context, names[i]);
            button.setTag(region);
            group.addView(button);
            if (region.equals(own)) button.setChecked(true);
        }
        content.addView(group);
        TextView hint = new TextView(context);
        hint.setTextColor(context.getColor(R.color.wehavephones_text_dim));
        hint.setTextSize(12.5f);
        hint.setText(R.string.wehavephones_account_region_hint);
        hint.setPadding(0, pad / 3, 0, 0);
        content.addView(hint);

        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(content);
        new AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.wehavephones_account_title, BattleNetGameActions.title(entry.game)))
                .setView(scroll)
                .setPositiveButton(R.string.wehavephones_account_save, (dialog, which) -> {
                    View checked = group.findViewById(group.getCheckedRadioButtonId());
                    String region = checked != null && checked.getTag() != null ? checked.getTag().toString() : "";
                    WeHavePhonesConfig.setGameRegionFor(context, dir, region);
                    activity.updateStageStatus(region.isEmpty()
                            ? context.getString(R.string.wehavephones_account_saved_follow, BattleNetGameActions.title(entry.game))
                            : context.getString(R.string.wehavephones_account_saved, BattleNetGameActions.title(entry.game), regionName(context, region)), false);
                })
                .setNegativeButton(R.string.wehavephones_games_cancel, null)
                .show();
    }

    static String describe(Context context, List<BattleNetGameAccounts.Account> accounts) {
        if (accounts.isEmpty()) return context.getString(R.string.wehavephones_account_none);
        StringBuilder out = new StringBuilder();
        for (BattleNetGameAccounts.Account account : accounts) {
            if (out.length() > 0) out.append('\n');
            out.append(account.name).append("  ·  ").append(regionName(context, account.region.toLowerCase(java.util.Locale.ROOT)));
        }
        if (accounts.size() > 1) out.append('\n').append(context.getString(R.string.wehavephones_account_pick_in_bnet));
        return out.toString();
    }

    static String regionName(Context context, String region) {
        String[] names = context.getResources().getStringArray(R.array.wehavephones_regions);
        for (int i = 0; i < WowFirstBoot.REGIONS.length && i < names.length; i++) {
            if (WowFirstBoot.REGIONS[i].equalsIgnoreCase(region)) return names[i];
        }
        return region.toUpperCase(java.util.Locale.ROOT);
    }

    private static TextView label(Context context, int text) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextColor(context.getColor(R.color.wehavephones_accent));
        label.setTextSize(12);
        label.setAllCaps(true);
        int pad = Math.round(12 * context.getResources().getDisplayMetrics().density);
        label.setPadding(0, pad, 0, pad / 3);
        return label;
    }

    private static RadioButton radio(Context context, String text) {
        RadioButton button = new RadioButton(context);
        button.setId(View.generateViewId());
        button.setText(text);
        button.setTextColor(context.getColor(R.color.wehavephones_text));
        button.setTextSize(14);
        return button;
    }
}
