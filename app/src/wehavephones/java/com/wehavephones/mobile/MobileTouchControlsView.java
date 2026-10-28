package com.wehavephones.mobile;

import android.app.AlertDialog;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.os.Build;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.DisplayCutout;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import com.wehavephones.R;
import com.wehavephones.XServerDisplayActivity;
import com.wehavephones.xserver.Pointer;
import com.wehavephones.xserver.XKeycode;
import com.wehavephones.xserver.XServer;

/** Native movement/camera overlay. Spells and other game UI receive absolute mouse taps. */
public final class MobileTouchControlsView extends View implements MobileTouchInput.Sink {
    private final XServer xServer;
    private final XServerDisplayActivity activity;
    private final MobileTouchInput input;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float density;
    private final RectF[] buttons = new RectF[MobileTouchLayout.COUNT];
    private MobileTouchLayout layout;
    private final String[] labels;
    private final MobileTouchInput.Zone[] zones = {MobileTouchInput.Zone.ESCAPE, MobileTouchInput.Zone.MAP,
            MobileTouchInput.Zone.BAGS, MobileTouchInput.Zone.MORE, MobileTouchInput.Zone.TARGET, MobileTouchInput.Zone.INTERACT,
            MobileTouchInput.Zone.JUMP, MobileTouchInput.Zone.MODE, MobileTouchInput.Zone.KEYBOARD, MobileTouchInput.Zone.INSPECT};
    private float stickX, stickY, radius, knobX, knobY;
    private int stickPointer = -1;
    private XKeycode nextModifier, heldModifier;
    private final Runnable holdCheck;
    private boolean dive;
    private boolean toolbarCollapsed;
    private int handlePointer = -1;
    private float handleX = .03f, handleY = .43f, handleLastX, handleLastY;
    private float insetLeft, insetTop, insetRight, insetBottom;

    public MobileTouchControlsView(XServerDisplayActivity activity) {
        super(activity);
        this.activity = activity;
        xServer = activity.getXServer();
        density = getResources().getDisplayMetrics().density;
        input = new MobileTouchInput(this, ViewConfiguration.get(activity).getScaledTouchSlop());
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        holdCheck = () -> input.advanceTime(SystemClock.uptimeMillis());
        labels = new String[]{activity.getString(R.string.wehavephones_touch_menu),
                activity.getString(R.string.wehavephones_touch_map), activity.getString(R.string.wehavephones_touch_bags),
                activity.getString(R.string.wehavephones_touch_more),
                activity.getString(R.string.wehavephones_touch_target), activity.getString(R.string.wehavephones_touch_interact),
                activity.getString(R.string.wehavephones_touch_jump), activity.getString(R.string.wehavephones_touch_windows_short),
                activity.getString(R.string.wehavephones_touch_keyboard), activity.getString(R.string.wehavephones_touch_inspect)};
        for (int i = 0; i < buttons.length; i++) buttons[i] = new RectF();
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentDescription(activity.getString(R.string.wehavephones_settings_mobile_touch));
        setVisibility(GONE);
    }

    public void showForGame(boolean running) {
        boolean show = running && WeHavePhonesConfig.isMobileTouchEnabled(getContext());
        if (show == (getVisibility() == VISIBLE)) return;
        cancelTouches();
        nextModifier = null;
        // Login, realm selection and character creation need the whole screen.
        // Play explicitly returns to movement after entering the world.
        if (show) {
            setWindows(true);
            toolbarCollapsed = true;
            refreshLayout();
        }
        setVisibility(show ? VISIBLE : GONE);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        cancelTouches();
        refreshLayout();
    }

    @Override public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        cancelTouches();
        refreshLayout();
        return super.onApplyWindowInsets(insets);
    }

    @Override protected void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        cancelTouches();
        refreshLayout();
    }

    private void refreshLayout() {
        density = getResources().getDisplayMetrics().density;
        float left = 0, top = 0, right = 0, bottom = 0;
        if (Build.VERSION.SDK_INT >= 28 && getRootWindowInsets() != null) {
            DisplayCutout cutout = getRootWindowInsets().getDisplayCutout();
            if (cutout != null) {
                left = cutout.getSafeInsetLeft(); top = cutout.getSafeInsetTop();
                right = cutout.getSafeInsetRight(); bottom = cutout.getSafeInsetBottom();
            }
        }
        layout = new MobileTouchLayout(getWidth(), getHeight(), density, left, top, right, bottom, input.isUiMode());
        insetLeft = left; insetTop = top; insetRight = right; insetBottom = bottom;
        radius = layout.radius;
        stickX = layout.stickX;
        stickY = layout.stickY;
        knobX = stickX;
        knobY = stickY;
        for (int i = 0; i < buttons.length; i++) {
            MobileTouchLayout.Bounds bounds = layout.buttons[i];
            if (bounds == null) buttons[i].setEmpty();
            else buttons[i].set(bounds.left, bounds.top, bounds.right, bounds.bottom);
        }
        labels[MobileTouchLayout.MODE] = getContext().getString(input.isUiMode()
                ? R.string.wehavephones_touch_play : R.string.wehavephones_touch_windows_short);
        labels[MobileTouchLayout.JUMP] = getContext().getString(dive ? R.string.wehavephones_touch_dive : R.string.wehavephones_touch_jump);
        if (toolbarCollapsed) {
            for (RectF button : buttons) button.setEmpty();
            updateHandle();
            labels[MobileTouchLayout.MODE] = getContext().getString(R.string.wehavephones_touch_handle);
        }
        invalidate();
    }

    private void updateHandle() {
        MobileTouchLayout.Bounds handle = MobileTouchLayout.handle(getWidth(), getHeight(), density,
                handleX * getWidth(), handleY * getHeight(), insetLeft, insetTop, insetRight, insetBottom);
        buttons[MobileTouchLayout.MODE].set(handle.left, handle.top, handle.right, handle.bottom);
    }

    private MobileTouchInput.Zone zone(float x, float y) {
        for (int i = 0; i < buttons.length; i++) if (buttons[i].contains(x, y)) return zones[i];
        if (input.isUiMode()) return MobileTouchInput.Zone.UI;
        if (Math.hypot(x - stickX, y - stickY) <= radius * 1.2f) return MobileTouchInput.Zone.STICK;
        MobileTouchLayout.Bounds viewport = viewport();
        if (viewport.contains(x, y) && MobileTouchInput.isCameraArea(x - viewport.left, y - viewport.top,
                viewport.width(), viewport.height())) return MobileTouchInput.Zone.CAMERA;
        return MobileTouchInput.Zone.UI;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return false;
        int index = event.getActionIndex(), id = event.getPointerId(index);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (handlePointer != -1) return true;
                MobileTouchInput.Zone zone = zone(event.getX(index), event.getY(index));
                if (toolbarCollapsed && zone == MobileTouchInput.Zone.MODE) {
                    handlePointer = id;
                    handleLastX = event.getX(index); handleLastY = event.getY(index);
                }
                if (zone == MobileTouchInput.Zone.STICK && stickPointer == -1) stickPointer = id;
                input.down(id, zone, event.getX(index), event.getY(index), SystemClock.uptimeMillis());
                postDelayed(holdCheck, MobileTouchInput.HOLD_MS);
                break;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    input.updateMultiPosition(event.getPointerId(i), event.getX(i), event.getY(i));
                }
                for (int i = 0; i < event.getPointerCount(); i++) {
                    int pointer = event.getPointerId(i);
                    float x = event.getX(i), y = event.getY(i);
                    input.move(pointer, x, y, stickX, stickY, radius);
                    if (pointer == handlePointer) {
                        handleX += (x - handleLastX) / Math.max(1, getWidth());
                        handleY += (y - handleLastY) / Math.max(1, getHeight());
                        updateHandle();
                        handleX = buttons[MobileTouchLayout.MODE].left / Math.max(1, getWidth());
                        handleY = buttons[MobileTouchLayout.MODE].top / Math.max(1, getHeight());
                        handleLastX = x; handleLastY = y;
                    }
                    if (pointer == stickPointer) {
                        float distance = (float)Math.hypot(x - stickX, y - stickY);
                        float fraction = Math.min(1, radius / Math.max(1, distance));
                        knobX = stickX + (x - stickX) * fraction;
                        knobY = stickY + (y - stickY) * fraction;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                input.advanceTime(SystemClock.uptimeMillis());
                input.up(id);
                if (id == handlePointer) handlePointer = -1;
                if (id == stickPointer) {
                    stickPointer = -1;
                    knobX = stickX;
                    knobY = stickY;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                cancelTouches();
                break;
        }
        invalidate();
        return true;
    }

    @Override protected void onDraw(Canvas canvas) {
        labels[MobileTouchLayout.MORE] = nextModifier == null ? getContext().getString(R.string.wehavephones_touch_more)
                : modifierName();
        paint.setStyle(Paint.Style.FILL);
        // Match the controller overlay's navy backing, blue outlines and silver labels.
        if (!input.isUiMode()) {
            paint.setColor(0x66091628);
            canvas.drawCircle(stickX, stickY, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f * density);
            paint.setColor(0xB38CC4F7);
            canvas.drawCircle(stickX, stickY, radius, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xB3216BBC);
            canvas.drawCircle(knobX, knobY, radius * .38f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(0xB38CC4F7);
            canvas.drawCircle(knobX, knobY, radius * .38f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(layout == null ? 12 * density : layout.textSize);
        for (int i = 0; i < buttons.length; i++) {
            RectF rect = buttons[i];
            if (rect.isEmpty()) continue;
            paint.setColor((i == MobileTouchLayout.INSPECT && input.isInspectMode())
                    || (i == MobileTouchLayout.MORE && nextModifier != null) ? 0xEE216BBC : 0x99091628);
            canvas.drawRoundRect(rect, 12 * density, 12 * density, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(0xB38CC4F7);
            canvas.drawRoundRect(rect, 12 * density, 12 * density, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFEEF5FF);
            paint.setTextSize(layout == null ? 12 * density : layout.textSize);
            float textWidth = paint.measureText(labels[i]);
            if (textWidth > rect.width() * .88f) paint.setTextSize(paint.getTextSize() * rect.width() * .88f / textWidth);
            canvas.drawText(labels[i], rect.centerX(), rect.centerY() - (paint.ascent() + paint.descent()) / 2, paint);
        }
        paint.setTextSize(layout == null ? 12 * density : layout.textSize);
    }

    @Override public void key(MobileTouchInput.Key key, boolean down) {
        if (key == MobileTouchInput.Key.MORE) {
            if (!down) post(this::showMore);
            return;
        }
        if (key == MobileTouchInput.Key.MODE || key == MobileTouchInput.Key.KEYBOARD || key == MobileTouchInput.Key.INSPECT) {
            if (!down) post(() -> {
                if (key == MobileTouchInput.Key.MODE) {
                    if (toolbarCollapsed) { toolbarCollapsed = false; refreshLayout(); }
                    else setWindows(!input.isUiMode());
                }
                else if (key == MobileTouchInput.Key.KEYBOARD) showKeyboard();
                else {
                    input.setInspectMode(!input.isInspectMode());
                    nextModifier = null;
                    if (input.isInspectMode()) Toast.makeText(activity, R.string.wehavephones_touch_inspecting, Toast.LENGTH_SHORT).show();
                    refreshLayout();
                }
            });
            return;
        }
        XKeycode code;
        switch (key) {
            case FORWARD: code = XKeycode.KEY_W; break;
            case BACK: code = XKeycode.KEY_S; break;
            case LEFT: code = XKeycode.KEY_A; break;
            case RIGHT: code = XKeycode.KEY_D; break;
            case JUMP: code = dive ? XKeycode.KEY_F11 : XKeycode.KEY_SPACE; break;
            case TARGET: code = XKeycode.KEY_TAB; break;
            case CORPSE: code = XKeycode.KEY_F10; break;
            case INTERACT: code = XKeycode.KEY_F; break;
            case BAGS: code = XKeycode.KEY_B; break;
            case MAP: code = XKeycode.KEY_M; break;
            case BAR: code = XKeycode.KEY_N; break;
            default: code = XKeycode.KEY_ESC;
        }
        if (down) xServer.injectKeyPress(code);
        else xServer.injectKeyRelease(code);
        if (!down && (key == MobileTouchInput.Key.MAP || key == MobileTouchInput.Key.BAGS)) post(() -> setWindows(true));
    }

    @Override public void click(float x, float y) {
        if (!position(x, y)) return;
        pressModifier();
        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
        releaseModifier();
    }

    private boolean position(float x, float y) {
        MobileTouchLayout.Bounds viewport = viewport();
        if (!viewport.contains(x, y)) return false;
        int guestX = Math.min(xServer.screenInfo.width - 1,
                (int)((x - viewport.left) * xServer.screenInfo.width / viewport.width()));
        int guestY = Math.min(xServer.screenInfo.height - 1,
                (int)((y - viewport.top) * xServer.screenInfo.height / viewport.height()));
        if (guestX < 0 || guestY < 0 || guestX >= xServer.screenInfo.width || guestY >= xServer.screenInfo.height) return false;
        xServer.injectPointerMove(guestX, guestY);
        return true;
    }

    private MobileTouchLayout.Bounds viewport() {
        return MobileTouchLayout.viewport(getWidth(), getHeight(), xServer.screenInfo.width,
                xServer.screenInfo.height, xServer.getRenderer().isFullscreen());
    }

    @Override public void hover(float x, float y) { position(x, y); }

    @Override public void contextClick(float x, float y) {
        if (!position(x, y)) return;
        pressModifier();
        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
        releaseModifier();
    }

    @Override public void drag(float x, float y, boolean down) {
        boolean valid = position(x, y);
        if (down && valid) {
            pressModifier();
            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
        } else if (!down) {
            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
            releaseModifier();
        }
    }

    @Override public void scroll(float x, float y, int clicks) {
        if (!position(x, y)) return;
        Pointer.Button button = clicks > 0 ? Pointer.Button.BUTTON_SCROLL_UP : Pointer.Button.BUTTON_SCROLL_DOWN;
        for (int i = 0; i < Math.abs(clicks); i++) {
            xServer.injectPointerButtonPress(button);
            xServer.injectPointerButtonRelease(button);
        }
    }

    private void tap(XKeycode code) {
        xServer.injectKeyPress(code);
        xServer.injectKeyRelease(code);
    }

    private void showMore() {
        if (activity.isFinishing() || !activity.hasWindowFocus()) return;
        String[] actions = getResources().getStringArray(R.array.wehavephones_touch_actions);
        // Surface targeting while playing, and typing and item modifiers while browsing.
        int[] order = input.isUiMode()
                ? new int[]{0, 2, 3, 19, 9, 10, 11, 12, 1, 14, 15, 16, 5, 4, 6, 7, 8, 13, 17, 18}
                : new int[]{0, 14, 3, 15, 5, 16, 6, 18, 1, 2, 4, 7, 8, 9, 10, 11, 12, 13, 17, 19};
        LinearLayout choices = new LinearLayout(activity);
        choices.setOrientation(LinearLayout.VERTICAL);
        choices.setPadding(dp(12), dp(4), dp(12), dp(4));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(choices);
        AlertDialog dialog = new AlertDialog.Builder(activity, R.style.WeHavePhonesTouchDialog)
                .setTitle(R.string.wehavephones_touch_controls_title)
                .setView(scroll).setNegativeButton(R.string.wehavephones_touch_close, null).create();
        for (int i = 0; i < actions.length; i += 2) {
            LinearLayout row = new LinearLayout(activity);
            choices.addView(row);
            for (int j = i; j < Math.min(i + 2, actions.length); j++) {
                final int which = j < order.length ? order[j] : j;
                Button button = new Button(activity);
                button.setText(actions[which]);
                button.setTextColor(0xFFEEF5FF);
                button.setTextSize(14);
                button.setAllCaps(false);
                button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                button.setBackgroundResource(R.drawable.wehavephones_button_primary);
                button.setBackgroundTintList(null);
                button.setPadding(dp(10), dp(4), dp(10), dp(4));
                button.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(56), 1);
                params.setMargins(dp(4), dp(4), dp(4), dp(4));
                row.addView(button, params);
                button.setOnClickListener(view -> {
                    dialog.dismiss();
                    postDelayed(() -> runMoreAction(which), 200);
                });
            }
        }
        dialog.show();
        dialog.getWindow().setLayout(Math.min(dp(560), (int)(getWidth() * .88f)),
                Math.min(dp(480), (int)(getHeight() * .90f)));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(0xFF8CC4F7);
    }

    private int dp(float value) { return Math.round(value * density); }

    private void runMoreAction(int which) {
        switch (which) {
            case 0: setWindows(true); tap(XKeycode.KEY_F9); break;
            case 1: setWindows(!input.isUiMode()); break;
            case 2: showKeyboard(); break;
            case 3: tap(XKeycode.KEY_ENTER); postDelayed(this::showKeyboard, 150); break;
            case 4: tap(XKeycode.KEY_N); break;
            case 5:
                xServer.injectKeyPress(XKeycode.KEY_SHIFT_L);
                tap(XKeycode.KEY_TAB);
                xServer.injectKeyRelease(XKeycode.KEY_SHIFT_L);
                break;
            case 6: tap(XKeycode.KEY_R); break;
            case 7: tap(XKeycode.KEY_X); break;
            case 9: armModifier(XKeycode.KEY_SHIFT_L); break;
            case 10: armModifier(XKeycode.KEY_CTRL_L); break;
            case 11: armModifier(XKeycode.KEY_ALT_L); break;
            case 12: nextModifier = null; invalidate(); break;
            case 13: tap(XKeycode.KEY_F10); break;
            case 14: showTargets(); break;
            case 15: tap(XKeycode.KEY_F6); break;
            case 16: tap(XKeycode.KEY_G); break;
            case 17: tap(XKeycode.KEY_F7); break;
            case 18: dive = !dive; refreshLayout(); break;
            case 19: setWindows(true); nextModifier = null; toolbarCollapsed = true; refreshLayout(); break;
            default: tap(XKeycode.KEY_ESC);
        }
    }

    private void setWindows(boolean enabled) {
        cancelTouches();
        input.setUiMode(enabled);
        toolbarCollapsed = false;
        refreshLayout();
    }

    private void showTargets() {
        String[] targets = getResources().getStringArray(R.array.wehavephones_touch_targets);
        XKeycode[] keys = {XKeycode.KEY_F1, XKeycode.KEY_F2, XKeycode.KEY_F3, XKeycode.KEY_F4, XKeycode.KEY_F5};
        new AlertDialog.Builder(activity, R.style.WeHavePhonesTouchDialog)
                .setTitle(R.string.wehavephones_touch_target_group).setItems(targets,
                        (dialog, which) -> postDelayed(() -> tap(keys[which]), 150))
                .setNegativeButton(R.string.wehavephones_touch_close, null).show();
    }

    private void showKeyboard() {
        setWindows(true);
        android.view.MenuItem keyboard = new android.widget.PopupMenu(activity, this).getMenu()
                .add(0, R.id.main_menu_keyboard, 0, R.string.wehavephones_touch_keyboard);
        activity.onNavigationItemSelected(keyboard);
    }

    private void pressModifier() {
        if (nextModifier == null) return;
        heldModifier = nextModifier;
        nextModifier = null;
        xServer.injectKeyPress(heldModifier);
    }

    private String modifierName() {
        return nextModifier == XKeycode.KEY_SHIFT_L ? "Shift" : nextModifier == XKeycode.KEY_CTRL_L ? "Ctrl" : "Alt";
    }

    private void armModifier(XKeycode code) {
        nextModifier = code;
        Toast.makeText(activity, getContext().getString(R.string.wehavephones_touch_next_modifier, modifierName()), Toast.LENGTH_SHORT).show();
        invalidate();
    }

    private void releaseModifier() {
        if (heldModifier == null) return;
        xServer.injectKeyRelease(heldModifier);
        heldModifier = null;
    }

    @Override public void camera(boolean down) {
        if (down) {
            xServer.injectPointerMove(xServer.screenInfo.width / 2, xServer.screenInfo.height / 2);
            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
        } else xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
    }

    @Override public void moveCamera(float dx, float dy) {
        MobileTouchLayout.Bounds viewport = viewport();
        xServer.injectPointerMoveDelta(Math.round(dx * xServer.screenInfo.width / viewport.width() * .65f),
                Math.round(dy * xServer.screenInfo.height / viewport.height() * .65f));
    }

    public void cancelTouches() {
        removeCallbacks(holdCheck);
        input.cancel();
        releaseModifier();
        stickPointer = -1;
        handlePointer = -1;
        knobX = stickX;
        knobY = stickY;
        invalidate();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) cancelTouches();
    }

    @Override protected void onDetachedFromWindow() {
        cancelTouches();
        super.onDetachedFromWindow();
    }
}
