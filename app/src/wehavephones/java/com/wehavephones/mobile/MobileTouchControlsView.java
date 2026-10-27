package com.wehavephones.mobile;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.wehavephones.R;
import com.wehavephones.XServerDisplayActivity;
import com.wehavephones.renderer.ViewTransformation;
import com.wehavephones.xserver.Pointer;
import com.wehavephones.xserver.XKeycode;
import com.wehavephones.xserver.XServer;

/** Native movement/camera overlay. Spells and other game UI receive absolute mouse taps. */
public final class MobileTouchControlsView extends View implements MobileTouchInput.Sink {
    private final XServer xServer;
    private final MobileTouchInput input;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final RectF[] buttons = new RectF[7];
    private final String[] labels;
    private final MobileTouchInput.Zone[] zones = {MobileTouchInput.Zone.ESCAPE, MobileTouchInput.Zone.MAP,
            MobileTouchInput.Zone.BAGS, MobileTouchInput.Zone.BAR, MobileTouchInput.Zone.TARGET, MobileTouchInput.Zone.INTERACT,
            MobileTouchInput.Zone.JUMP};
    private float stickX, stickY, radius, knobX, knobY;
    private int stickPointer = -1;

    public MobileTouchControlsView(XServerDisplayActivity activity) {
        super(activity);
        xServer = activity.getXServer();
        density = getResources().getDisplayMetrics().density;
        input = new MobileTouchInput(this, ViewConfiguration.get(activity).getScaledTouchSlop());
        labels = new String[]{activity.getString(R.string.wehavephones_touch_menu),
                activity.getString(R.string.wehavephones_touch_map), activity.getString(R.string.wehavephones_touch_bags),
                activity.getString(R.string.wehavephones_touch_bar),
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
        if (x > getWidth() * .52f && x < getWidth() * .88f
                && y > getHeight() * .19f && y < getHeight() * .63f) return MobileTouchInput.Zone.CAMERA;
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
                input.down(id, zone, event.getX(index), event.getY(index));
                break;
            case MotionEvent.ACTION_MOVE:
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
        paint.setColor(0x55313D50);
        canvas.drawCircle(stickX, stickY, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f * density);
        paint.setColor(0x99FFFFFF);
        canvas.drawCircle(stickX, stickY, radius, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x99DCE5EF);
        canvas.drawCircle(knobX, knobY, radius * .38f, paint);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(12 * density);
        for (int i = 0; i < buttons.length; i++) {
            RectF rect = buttons[i];
            paint.setColor(0x99313D50);
            canvas.drawRoundRect(rect, 12 * density, 12 * density, paint);
            paint.setColor(Color.WHITE);
            canvas.drawText(labels[i], rect.centerX(), rect.centerY() - (paint.ascent() + paint.descent()) / 2, paint);
        }
        paint.setColor(0x99FFFFFF);
        canvas.drawText(getContext().getString(R.string.wehavephones_touch_camera),
                getWidth() * .70f, getHeight() * .23f, paint);
    }

    @Override public void key(MobileTouchInput.Key key, boolean down) {
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
        ViewTransformation transform = new ViewTransformation();
        transform.update(getWidth(), getHeight(), xServer.screenInfo.width, xServer.screenInfo.height);
        boolean fullscreen = xServer.getRenderer().isFullscreen();
        float scaleX = fullscreen ? (float)getWidth() / xServer.screenInfo.width : transform.aspect;
        float scaleY = fullscreen ? (float)getHeight() / xServer.screenInfo.height : transform.aspect;
        int guestX = Math.round((x - (fullscreen ? 0 : transform.viewOffsetX)) / scaleX);
        int guestY = Math.round((y - (fullscreen ? 0 : transform.viewOffsetY)) / scaleY);
        if (guestX < 0 || guestY < 0 || guestX >= xServer.screenInfo.width || guestY >= xServer.screenInfo.height) return;
        xServer.injectPointerMove(guestX, guestY);
        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
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
        input.cancel();
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
