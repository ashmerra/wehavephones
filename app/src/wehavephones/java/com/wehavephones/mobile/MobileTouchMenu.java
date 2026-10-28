package com.wehavephones.mobile;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.wehavephones.R;

/** Full-screen touch menu in the app's rail and LCD style. Large menus replace the game view while open. */
final class MobileTouchMenu {
    enum Category {
        WINDOWS(R.string.wehavephones_touch_cat_windows, R.string.wehavephones_touch_cat_windows_hint, R.drawable.wehavephones_touch_ic_app_window),
        TARGETING(R.string.wehavephones_touch_cat_targeting, R.string.wehavephones_touch_cat_targeting_hint, R.drawable.wehavephones_touch_ic_crosshair),
        MOVEMENT(R.string.wehavephones_touch_cat_movement, R.string.wehavephones_touch_cat_movement_hint, R.drawable.wehavephones_touch_ic_person_simple_run),
        TYPING(R.string.wehavephones_touch_cat_typing, R.string.wehavephones_touch_cat_typing_hint, R.drawable.wehavephones_touch_ic_keyboard),
        TOUCH(R.string.wehavephones_touch_cat_touch, R.string.wehavephones_touch_cat_touch_hint, R.drawable.wehavephones_touch_ic_sliders_horizontal);
        final int title, hint, icon;
        Category(int title, int hint, int icon) { this.title = title; this.hint = hint; this.icon = icon; }
    }

    enum Action {
        CHARACTER(Category.WINDOWS, R.drawable.wehavephones_touch_ic_user, R.string.wehavephones_touch_act_character),
        SPELLBOOK(Category.WINDOWS, R.drawable.wehavephones_touch_ic_book_open, R.string.wehavephones_touch_act_spellbook),
        TALENTS(Category.WINDOWS, R.drawable.wehavephones_touch_ic_tree_structure, R.string.wehavephones_touch_act_talents),
        QUESTS(Category.WINDOWS, R.drawable.wehavephones_touch_ic_scroll, R.string.wehavephones_touch_act_quests),
        MAP(Category.WINDOWS, R.drawable.wehavephones_touch_ic_map_trifold, R.string.wehavephones_touch_map),
        BAGS(Category.WINDOWS, R.drawable.wehavephones_touch_ic_backpack, R.string.wehavephones_touch_bags),
        SOCIAL(Category.WINDOWS, R.drawable.wehavephones_touch_ic_users_three, R.string.wehavephones_touch_act_social),
        SKILLS(Category.WINDOWS, R.drawable.wehavephones_touch_ic_chart_bar, R.string.wehavephones_touch_act_skills),
        REPUTATION(Category.WINDOWS, R.drawable.wehavephones_touch_ic_handshake, R.string.wehavephones_touch_act_reputation),
        HONOR(Category.WINDOWS, R.drawable.wehavephones_touch_ic_flag_banner, R.string.wehavephones_touch_act_honor),
        PET_SPELLS(Category.WINDOWS, R.drawable.wehavephones_touch_ic_paw_print, R.string.wehavephones_touch_act_pet),
        MACROS(Category.WINDOWS, R.drawable.wehavephones_touch_ic_terminal_window, R.string.wehavephones_touch_act_macros),
        GAME_MENU(Category.WINDOWS, R.drawable.wehavephones_touch_ic_gear_six, R.string.wehavephones_touch_act_game_menu),
        HELP(Category.WINDOWS, R.drawable.wehavephones_touch_ic_lifebuoy, R.string.wehavephones_touch_act_help),

        SELF(Category.TARGETING, R.drawable.wehavephones_touch_ic_user_circle, R.string.wehavephones_touch_act_self),
        PARTY1(Category.TARGETING, R.drawable.wehavephones_touch_ic_users, R.string.wehavephones_touch_act_party, 1),
        PARTY2(Category.TARGETING, R.drawable.wehavephones_touch_ic_users, R.string.wehavephones_touch_act_party, 2),
        PARTY3(Category.TARGETING, R.drawable.wehavephones_touch_ic_users, R.string.wehavephones_touch_act_party, 3),
        PARTY4(Category.TARGETING, R.drawable.wehavephones_touch_ic_users, R.string.wehavephones_touch_act_party, 4),
        FRIEND(Category.TARGETING, R.drawable.wehavephones_touch_ic_heart, R.string.wehavephones_touch_act_friend),
        ASSIST(Category.TARGETING, R.drawable.wehavephones_touch_ic_user_switch, R.string.wehavephones_touch_act_assist),
        PREVIOUS(Category.TARGETING, R.drawable.wehavephones_touch_ic_arrow_counter_clockwise, R.string.wehavephones_touch_act_previous),
        CORPSE(Category.TARGETING, R.drawable.wehavephones_touch_ic_skull, R.string.wehavephones_touch_act_corpse),
        NAMEPLATES(Category.TARGETING, R.drawable.wehavephones_touch_ic_tag, R.string.wehavephones_touch_act_nameplates),

        AUTO_RUN(Category.MOVEMENT, R.drawable.wehavephones_touch_ic_person_simple_run, R.string.wehavephones_touch_auto_run),
        FOLLOW(Category.MOVEMENT, R.drawable.wehavephones_touch_ic_footprints, R.string.wehavephones_touch_act_follow),
        WALK(Category.MOVEMENT, R.drawable.wehavephones_touch_ic_person_simple_walk, R.string.wehavephones_touch_act_walk),
        SIT(Category.MOVEMENT, R.drawable.wehavephones_touch_ic_armchair, R.string.wehavephones_touch_act_sit),
        DIVE(Category.MOVEMENT, R.drawable.wehavephones_touch_ic_arrow_fat_line_down, R.string.wehavephones_touch_act_dive),

        CHAT(Category.TYPING, R.drawable.wehavephones_touch_ic_chat_circle_text, R.string.wehavephones_touch_act_chat),
        REPLY(Category.TYPING, R.drawable.wehavephones_touch_ic_arrow_bend_up_left, R.string.wehavephones_touch_act_reply),
        KEYBOARD(Category.TYPING, R.drawable.wehavephones_touch_ic_keyboard, R.string.wehavephones_touch_keyboard),
        INSPECT(Category.TYPING, R.drawable.wehavephones_touch_ic_magnifying_glass, R.string.wehavephones_touch_inspect),
        SHIFT(Category.TYPING, R.drawable.wehavephones_touch_ic_arrow_fat_up, 0),
        CTRL(Category.TYPING, R.drawable.wehavephones_touch_ic_control, 0),
        ALT(Category.TYPING, R.drawable.wehavephones_touch_ic_option, 0),
        NO_MODIFIER(Category.TYPING, R.drawable.wehavephones_touch_ic_prohibit, R.string.wehavephones_touch_act_clear_modifier),

        SIZE_125(Category.TOUCH, R.drawable.wehavephones_touch_ic_text_aa, R.string.wehavephones_touch_act_size, 125),
        SIZE_150(Category.TOUCH, R.drawable.wehavephones_touch_ic_text_aa, R.string.wehavephones_touch_act_size, 150),
        SIZE_175(Category.TOUCH, R.drawable.wehavephones_touch_ic_text_aa, R.string.wehavephones_touch_act_size, 175),
        GUIDE(Category.TOUCH, R.drawable.wehavephones_touch_ic_hand_pointing, R.string.wehavephones_touch_act_guide),
        HIDE(Category.TOUCH, R.drawable.wehavephones_touch_ic_arrows_in_simple, R.string.wehavephones_touch_act_hide),
        UNDO(Category.TOUCH, R.drawable.wehavephones_touch_ic_arrow_counter_clockwise, R.string.wehavephones_touch_act_undo),
        PAGE(Category.TOUCH, R.drawable.wehavephones_touch_ic_stack, R.string.wehavephones_touch_act_page);

        final Category category;
        final int icon, label, arg;
        Action(Category category, int icon, int label) { this(category, icon, label, 0); }
        Action(Category category, int icon, int label, int arg) {
            this.category = category; this.icon = icon; this.label = label; this.arg = arg;
        }

        String text(Activity activity) {
            if (this == SHIFT) return "Shift";
            if (this == CTRL) return "Ctrl";
            if (this == ALT) return "Alt";
            return arg == 0 ? activity.getString(label) : activity.getString(label, arg);
        }
    }

    interface Host {
        void run(Action action);
        /** Toggles and choices that are currently on: size, Dive, an armed modifier, Inspect or auto run. */
        boolean isActive(Action action);
        String modeName();
    }

    private final Activity activity;
    private final Host host;
    private final float density;
    private Dialog dialog;
    private Category category;
    private TextView title, hint;
    private LinearLayout grid;
    private final TextView[] rail = new TextView[Category.values().length];

    MobileTouchMenu(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
        density = activity.getResources().getDisplayMetrics().density;
    }

    boolean isShowing() { return dialog != null && dialog.isShowing(); }

    void dismiss() { if (dialog != null) dialog.dismiss(); }

    void show(Category initial) {
        if (activity.isFinishing() || isShowing()) return;
        category = initial;
        dialog = new Dialog(activity, R.style.WeHavePhonesTouchMenu);
        dialog.setContentView(build());
        Window window = dialog.getWindow();
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }
        // Keep the game's immersive mode: a focusable dialog would bring the system bars back.
        window.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        dialog.show();
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController insets = window.getInsetsController();
            if (insets != null) {
                insets.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                insets.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else window.getDecorView().setSystemUiVisibility(activity.getWindow().getDecorView().getSystemUiVisibility());
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        select(category);
    }

    private View build() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setFitsSystemWindows(true);
        Drawable shell = activity.getDrawable(R.drawable.wehavephones_screen_background).mutate();
        shell.setAlpha(246);
        root.setBackground(shell);

        ScrollView railScroll = new ScrollView(activity);
        railScroll.setBackgroundResource(R.drawable.wehavephones_rail_background);
        railScroll.setFillViewport(true);
        railScroll.setVerticalScrollBarEnabled(false);
        root.addView(railScroll, new LinearLayout.LayoutParams(dp(184), ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout railItems = new LinearLayout(activity);
        railItems.setOrientation(LinearLayout.VERTICAL);
        railItems.setPadding(dp(6), dp(6), dp(6), dp(6));
        railScroll.addView(railItems, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout brand = new LinearLayout(activity);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setPadding(dp(4), 0, dp(4), 0);
        ImageView phone = new ImageView(activity);
        phone.setImageResource(R.drawable.wehavephones_flip_phone);
        phone.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        brand.addView(phone, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView logo = new TextView(activity);
        logo.setText(R.string.wehavephones_logo);
        logo.setTextColor(activity.getColor(R.color.wehavephones_on_shell));
        logo.setTextSize(14);
        logo.setTypeface(android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL));
        logo.setPadding(dp(6), 0, 0, 0);
        brand.addView(logo);
        railItems.addView(brand, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        ImageView divider = new ImageView(activity, null, 0, R.style.WeHavePhonesDivider);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.setMargins(dp(14), dp(4), dp(14), dp(2));
        railItems.addView(divider, dividerParams);

        for (Category item : Category.values()) {
            TextView key = railKey(R.style.WeHavePhonesRailItem, item.icon, activity.getString(item.title));
            key.setOnClickListener(view -> select(item));
            rail[item.ordinal()] = key;
            railItems.addView(key, railParams(44));
        }
        View spacer = new View(activity);
        railItems.addView(spacer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        TextView close = railKey(R.style.WeHavePhonesRailItem, R.drawable.wehavephones_touch_ic_x,
                activity.getString(R.string.wehavephones_touch_close));
        close.setOnClickListener(view -> dismiss());
        railItems.addView(close, railParams(44));

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        FrameLayout lcd = new FrameLayout(activity);
        lcd.setBackgroundResource(R.drawable.wehavephones_lcd_frame);
        content.addView(lcd, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout screen = new LinearLayout(activity);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(dp(14), dp(10), dp(14), dp(6));
        lcd.addView(screen, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        title = new TextView(activity, null, 0, R.style.WeHavePhonesScreenTitle);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView mode = new TextView(activity);
        mode.setText(activity.getString(R.string.wehavephones_touch_menu_mode, host.modeName()));
        mode.setTextColor(activity.getColor(R.color.wehavephones_key_text));
        mode.setTextSize(12);
        mode.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        mode.setBackgroundResource(R.drawable.wehavephones_button_primary_normal);
        mode.setPadding(dp(10), dp(4), dp(10), dp(6));
        header.addView(mode);
        screen.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        hint = new TextView(activity, null, 0, R.style.WeHavePhonesHint);
        screen.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        scrollParams.topMargin = dp(8);
        screen.addView(scroll, scrollParams);
        grid = new LinearLayout(activity);
        grid.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(grid, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    private void select(Category selected) {
        category = selected;
        for (Category item : Category.values()) rail[item.ordinal()].setSelected(item == selected);
        title.setText(selected.title);
        hint.setText(selected.hint);
        grid.removeAllViews();
        float widthDp = activity.getResources().getDisplayMetrics().widthPixels / density - 184 - 60;
        int columns = Math.max(3, Math.min(6, (int)(widthDp / 128)));
        LinearLayout row = null;
        int index = 0;
        for (Action action : Action.values()) {
            if (action.category != selected) continue;
            if (index % columns == 0) {
                row = new LinearLayout(activity);
                grid.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            row.addView(tile(action), tileParams());
            index++;
        }
        // Keep the last row's tiles the same width as the rows above.
        while (row != null && index % columns != 0) {
            row.addView(new View(activity), tileParams());
            index++;
        }
    }

    private TextView tile(Action action) {
        boolean active = host.isActive(action);
        TextView tile = new TextView(activity);
        tile.setText(action.text(activity));
        tile.setGravity(Gravity.CENTER);
        tile.setTextSize(14);
        tile.setMaxLines(2);
        tile.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        int color = active ? activity.getColor(R.color.wehavephones_key_text) : activity.getColor(R.color.wehavephones_text_title);
        tile.setTextColor(color);
        tile.setBackgroundResource(active ? R.drawable.wehavephones_button_primary : R.drawable.wehavephones_button_outline);
        Drawable icon = activity.getDrawable(action.icon).mutate();
        icon.setBounds(0, 0, dp(28), dp(28));
        icon.setTint(color);
        tile.setCompoundDrawables(null, icon, null, null);
        tile.setCompoundDrawablePadding(dp(4));
        tile.setPadding(dp(6), dp(10), dp(6), dp(10));
        tile.setSelected(active);
        tile.setOnClickListener(view -> {
            dismiss();
            host.run(action);
        });
        return tile;
    }

    private TextView railKey(int style, int icon, String text) {
        TextView key = new TextView(activity, null, 0, style);
        key.setText(text);
        Drawable drawable = activity.getDrawable(icon).mutate();
        drawable.setBounds(0, 0, dp(22), dp(22));
        key.setCompoundDrawablesRelative(drawable, null, null, null);
        ColorStateList tint = activity.getColorStateList(R.color.wehavephones_rail_icon);
        key.setCompoundDrawableTintList(tint);
        return key;
    }

    private LinearLayout.LayoutParams railParams(int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height));
        params.topMargin = dp(4);
        return params;
    }

    private LinearLayout.LayoutParams tileParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(84), 1);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        return params;
    }

    private int dp(float value) { return Math.round(value * density); }
}
