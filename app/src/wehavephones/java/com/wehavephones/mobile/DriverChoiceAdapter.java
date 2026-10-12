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
 * Rows of the Diagnostics "Graphics driver" dropdown, styled like the Play
 * screen's game versions: a gem, the driver name and a line saying where the
 * driver comes from. The current pick is bright, the others dimmed.
 */
final class DriverChoiceAdapter extends ArrayAdapter<DriverChoiceAdapter.Row> {
    static final class Row {
        /** Null for Automatic. */
        final DeviceRules.Driver driver;
        final String name;
        final String meta;
        final boolean selected;

        Row(DeviceRules.Driver driver, String name, String meta, boolean selected) {
            this.driver = driver;
            this.name = name;
            this.meta = meta;
            this.selected = selected;
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
        name.setText(row.name);
        name.setTextColor(context.getColor(row.selected ? R.color.wehavephones_accent : R.color.wehavephones_text));
        meta.setText(row.meta);
        view.setPaddingRelative(view.getPaddingStart(), view.getPaddingTop(), view.getPaddingStart(), view.getPaddingBottom());

        Drawable gem = view.findViewById(R.id.VGameGem).getBackground().mutate();
        gem.setTintMode(PorterDuff.Mode.MULTIPLY);
        gem.setTint(GameVersionAdapter.DEFAULT_ACCENT);
        gem.setAlpha(row.selected ? 255 : 110);
        return view;
    }
}
