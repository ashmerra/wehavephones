package com.wehavephones.mobile;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.wehavephones.R;

import java.util.List;

/**
 * Rows of the Home "Game version" dropdown: a gem in the game's accent color,
 * its name, and the installed build or "not installed" for games Battle.net
 * lists but has not installed (dimmed, offered as Install).
 */
final class GameVersionAdapter extends ArrayAdapter<BattleNetGames.Game> {
    /** Gem color for games outside the catalog: the game-font yellow. */
    static final int DEFAULT_ACCENT = 0xFF0D315A;

    /** List rows everywhere: the Play screen opens this as a popup, not a closed dropdown. */
    private final boolean popupRows;

    GameVersionAdapter(Context context, List<BattleNetGames.Game> games) {
        this(context, games, false);
    }

    GameVersionAdapter(Context context, List<BattleNetGames.Game> games, boolean popupRows) {
        super(context, R.layout.wehavephones_game_version_item, games);
        this.popupRows = popupRows;
    }

    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        return bind(position, convertView, parent, popupRows);
    }

    @Override
    public View getDropDownView(int position, View convertView, @NonNull ViewGroup parent) {
        return bind(position, convertView, parent, true);
    }

    private View bind(int position, View convertView, ViewGroup parent, boolean dropDown) {
        View view = convertView != null ? convertView
                : LayoutInflater.from(getContext()).inflate(R.layout.wehavephones_game_version_item, parent, false);
        BattleNetGames.Game game = getItem(position);
        if (game == null) return view;
        Context context = getContext();

        TextView name = view.findViewById(R.id.TVGameName);
        TextView meta = view.findViewById(R.id.TVGameMeta);
        name.setText(game.getDisplayName());
        name.setTextColor(context.getColor(game.installed ? R.color.wehavephones_accent : R.color.wehavephones_text_dim));
        if (game.installed) {
            meta.setText(game.version.isEmpty() ? context.getString(R.string.wehavephones_game_installed) : game.version);
        }
        else {
            meta.setText(R.string.wehavephones_game_not_installed_entry);
        }
        // The closed dropdown leaves room for the chevron; open rows use the full width.
        view.setPaddingRelative(view.getPaddingStart(), view.getPaddingTop(),
                dropDown ? view.getPaddingStart() : dp(context, 36), view.getPaddingBottom());

        Drawable gem = view.findViewById(R.id.VGameGem).getBackground().mutate();
        gem.setTintMode(PorterDuff.Mode.MULTIPLY);
        gem.setTint(DEFAULT_ACCENT);
        gem.setAlpha(game.installed ? 255 : 110);
        return view;
    }

    static int accent(BattleNetGames.Game game) {
        WowFlavor.Family family = game.flavor.family;
        return family != null ? family.accentColor : DEFAULT_ACCENT;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
