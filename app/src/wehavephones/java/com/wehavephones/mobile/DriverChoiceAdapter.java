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
import com.wehavephones.mobile.devices.DeviceRules;

import java.util.List;

/**
 * Rows of the Play screen's "Graphics driver" dropdown, styled like its game
 * versions: a gem, the driver name and a line saying where the driver comes
 * from. The current pick is bright, the others dimmed; the line of the driver
 * Automatic uses says so in the accent color.
 */
final class DriverChoiceAdapter extends ArrayAdapter<DriverChoiceAdapter.Row> {
    static final class Row {
        /** Null for Automatic. */
        final DeviceRules.Driver driver;
        final String name;
        final String meta;
        final boolean selected;
        /** The driver Automatic uses. */
        final boolean automatic;

        Row(DeviceRules.Driver driver, String name, String meta, boolean selected, boolean automatic) {
            this.driver = driver;
            this.name = name;
            this.meta = meta;
            this.selected = selected;
            this.automatic = automatic;
        }
    }

    DriverChoiceAdapter(Context context, List<Row> rows) {
        super(context, R.layout.wehavephones_game_version_item, rows);
    }

    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        View view = convertView != null ? convertView
                : LayoutInflater.from(getContext()).inflate(R.layout.wehavephones_game_version_item, parent, false);
        Row row = getItem(position);
        if (row == null) return view;
        Context context = getContext();

        TextView name = view.findViewById(R.id.TVGameName);
        TextView meta = view.findViewById(R.id.TVGameMeta);
        boolean described = row.driver != null && !row.driver.isSystem() && !row.driver.description.isEmpty();
        name.setMaxLines(described ? 2 : 1);
        meta.setMaxLines(1);
        name.setText(row.name);
        name.setTextColor(context.getColor(row.selected ? R.color.wehavephones_accent : R.color.wehavephones_text));
        meta.setText(row.meta);
        meta.setTextColor(context.getColor(row.automatic ? R.color.wehavephones_accent : R.color.wehavephones_text_dim));
        view.setPaddingRelative(view.getPaddingStart(), view.getPaddingTop(), view.getPaddingStart(), view.getPaddingBottom());

        Drawable gem = view.findViewById(R.id.VGameGem).getBackground().mutate();
        gem.setTintMode(PorterDuff.Mode.MULTIPLY);
        gem.setTint(GameVersionAdapter.DEFAULT_ACCENT);
        gem.setAlpha(row.selected ? 255 : 110);
        return view;
    }
}
