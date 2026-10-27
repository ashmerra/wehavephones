package com.wehavephones.mobile;

import android.app.AlertDialog;
import android.os.SystemClock;
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
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.wehavephones.R;
import com.wehavephones.XServerDisplayActivity;
import com.wehavephones.renderer.ViewTransformation;
import com.wehavephones.xserver.Pointer;
import com.wehavephones.xserver.XKeycode;
import com.wehavephones.xserver.XServer;

/** Native movement/camera overlay. Spells and other game UI receive absolute mouse taps. */
public final class MobileTouchControlsView extends View implements MobileTouchInput.Sink {
    private final XServer xServer;
    private final XServerDisplayActivity activity;
    private final MobileTouchInput input;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final RectF[] buttons = new RectF[7];
    private final String[] labels;
    private final MobileTouchInput.Zone[] zones = {MobileTouchInput.Zone.ESCAPE, MobileTouchInput.Zone.MAP,
            MobileTouchInput.Zone.BAGS, MobileTouchInput.Zone.MORE, MobileTouchInput.Zone.TARGET, MobileTouchInput.Zone.INTERACT,
            MobileTouchInput.Zone.JUMP};
    private float stickX, stickY, radius, knobX, knobY;
    private int stickPointer = -1;
    private XKeycode nextModifier, heldModifier;
    private final Runnable holdCheck;

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
                activity.getString(R.string.wehavephones_touch_jump)};
        for (int i = 0; i < buttons.length; i++) buttons[i] = new RectF();
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentDescription(activity.getString(R.string.wehavephones_settings_mobile_touch));
        setVisibility(GONE);
    }

    public void showForGame(boolean running) {
        boolean show = running && WeHavePhonesConfig.isMobileTouchEnabled(getContext());
        if (show == (getVisibility() == VISIBLE)) return;
        cancelTouches();
        setVisibility(show ? VISIBLE : GONE);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        cancelTouches();
        radius = Math.min(66 * density, h * .16f);
        stickX = Math.max(radius + 22 * density, w * .12f);
        stickY = h * .69f;
        knobX = stickX;
        knobY = stickY;
        float side = Math.min(48 * density, h * .13f), gap = 8 * density;
        // Keep utility keys away from the player frame and the bottom action bars.
        for (int i = 0; i < 4; i++) {
            float x = w * .45f + i * (side + gap);
            buttons[i].set(x, 16 * density, x + side, 16 * density + side);
        }
        for (int i = 4; i < 7; i++) {
            float y = h * .48f + (i - 4) * (side + gap);
            buttons[i].set(w - side - 22 * density, y, w - 22 * density, y + side);
        }
    }

    private MobileTouchInput.Zone zone(float x, float y) {
        for (int i = 0; i < buttons.length; i++) if (buttons[i].contains(x, y)) return zones[i];
        if (Math.hypot(x - stickX, y - stickY) <= radius * 1.2f) return MobileTouchInput.Zone.STICK;
        if (MobileTouchInput.isCameraArea(x, y, getWidth(), getHeight())) return MobileTouchInput.Zone.CAMERA;
        return MobileTouchInput.Zone.UI;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return false;
        int index = event.getActionIndex(), id = event.getPointerId(index);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                MobileTouchInput.Zone zone = zone(event.getX(index), event.getY(index));
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
        paint.setStyle(Paint.Style.FILL);
        // Match the controller overlay's navy backing, blue outlines and silver labels.
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
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(12 * density);
        for (int i = 0; i < buttons.length; i++) {
            RectF rect = buttons[i];
            paint.setColor(0x99091628);
            canvas.drawRoundRect(rect, 12 * density, 12 * density, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(0xB38CC4F7);
            canvas.drawRoundRect(rect, 12 * density, 12 * density, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFEEF5FF);
            canvas.drawText(labels[i], rect.centerX(), rect.centerY() - (paint.ascent() + paint.descent()) / 2, paint);
        }
        paint.setColor(0xB38CC4F7);
        canvas.drawText(getContext().getString(input.isUiMode() ? R.string.wehavephones_touch_windows : R.string.wehavephones_touch_camera),
                getWidth() * .70f, getHeight() * .23f, paint);
        if (nextModifier != null) {
            String modifier = nextModifier == XKeycode.KEY_SHIFT_L ? "Shift" : nextModifier == XKeycode.KEY_CTRL_L ? "Ctrl" : "Alt";
            canvas.drawText(getContext().getString(R.string.wehavephones_touch_next_modifier, modifier),
                    getWidth() * .70f, getHeight() * .29f, paint);
        }
    }

    @Override public void key(MobileTouchInput.Key key, boolean down) {
        if (key == MobileTouchInput.Key.MORE) {
            if (!down) post(this::showMore);
            return;
        }
        XKeycode code;
        switch (key) {
            case FORWARD: code = XKeycode.KEY_W; break;
            case BACK: code = XKeycode.KEY_S; break;
            case LEFT: code = XKeycode.KEY_A; break;
            case RIGHT: code = XKeycode.KEY_D; break;
            case JUMP: code = XKeycode.KEY_SPACE; break;
            case TARGET: code = XKeycode.KEY_TAB; break;
            case INTERACT: code = XKeycode.KEY_F; break;
            case BAGS: code = XKeycode.KEY_B; break;
            case MAP: code = XKeycode.KEY_M; break;
            case BAR: code = XKeycode.KEY_N; break;
            default: code = XKeycode.KEY_ESC;
        }
        if (down) xServer.injectKeyPress(code);
        else xServer.injectKeyRelease(code);
    }

    @Override public void click(float x, float y) {
        if (!position(x, y)) return;
        pressModifier();
        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
        releaseModifier();
    }

    private boolean position(float x, float y) {
        ViewTransformation transform = new ViewTransformation();
        transform.update(getWidth(), getHeight(), xServer.screenInfo.width, xServer.screenInfo.height);
        boolean fullscreen = xServer.getRenderer().isFullscreen();
        float scaleX = fullscreen ? (float)getWidth() / xServer.screenInfo.width : transform.aspect;
        float scaleY = fullscreen ? (float)getHeight() / xServer.screenInfo.height : transform.aspect;
        int guestX = Math.round((x - (fullscreen ? 0 : transform.viewOffsetX)) / scaleX);
        int guestY = Math.round((y - (fullscreen ? 0 : transform.viewOffsetY)) / scaleY);
        if (guestX < 0 || guestY < 0 || guestX >= xServer.screenInfo.width || guestY >= xServer.screenInfo.height) return false;
        xServer.injectPointerMove(guestX, guestY);
        return true;
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
                final int which = j;
                Button button = new Button(activity);
                button.setText(actions[j]);
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
            case 0: tap(XKeycode.KEY_F9); break;
            case 1: input.setUiMode(!input.isUiMode()); invalidate(); break;
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
            case 9: nextModifier = XKeycode.KEY_SHIFT_L; invalidate(); break;
            case 10: nextModifier = XKeycode.KEY_CTRL_L; invalidate(); break;
            case 11: nextModifier = XKeycode.KEY_ALT_L; invalidate(); break;
            case 12: nextModifier = null; invalidate(); break;
            default: tap(XKeycode.KEY_ESC);
        }
    }

    private void showKeyboard() {
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
        float scale = Math.min((float)getWidth() / xServer.screenInfo.width, (float)getHeight() / xServer.screenInfo.height);
        xServer.injectPointerMoveDelta(Math.round(dx / scale * .65f), Math.round(dy / scale * .65f));
    }

    public void cancelTouches() {
        removeCallbacks(holdCheck);
        input.cancel();
        releaseModifier();
        stickPointer = -1;
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
