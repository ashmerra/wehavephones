package com.wehavephones.mobile;

import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.DisplayCutout;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import java.util.HashSet;
import java.util.Set;

import com.wehavephones.R;
import com.wehavephones.XServerDisplayActivity;
import com.wehavephones.xserver.Pointer;
import com.wehavephones.xserver.XKeycode;
import com.wehavephones.xserver.XServer;

/** Native movement/camera overlay. Spells and other game UI receive absolute mouse taps. */
public final class MobileTouchControlsView extends View implements MobileTouchInput.Sink, MobileTouchMenu.Host {
    // The app's navy keys, blue primary keys and silver labels, kept translucent over the game.
    private static final int LIP = 0xCC06162D, KEY_TOP = 0xD9173F6B, KEY_BOTTOM = 0xD90A2448, KEY_EDGE = 0xCC6FA6DB,
            ACTIVE_TOP = 0xF22272BA, ACTIVE_BOTTOM = 0xF21059AF, ACTIVE_EDGE = 0xFF75BDF4,
            SILVER_LIGHT = 0xFFEFF4F8, SILVER = 0xFFBAC7D6, BLUE_LIGHT = 0xFF2C91E5, WHITE = 0xFFFFFFFF,
            SHADE = 0x9906162D, PANEL = 0xF20B2344;
    private static final long BANNER_MS = 3400, RIPPLE_MS = 260, PULSE_MS = 380, HOLD_DELAY_MS = 120;
    private static final MobileTouchInput.Zone[] ZONES = {MobileTouchInput.Zone.ESCAPE, MobileTouchInput.Zone.MAP,
            MobileTouchInput.Zone.BAGS, MobileTouchInput.Zone.MORE, MobileTouchInput.Zone.TARGET, MobileTouchInput.Zone.INTERACT,
            MobileTouchInput.Zone.JUMP, MobileTouchInput.Zone.PLAY, MobileTouchInput.Zone.MENUS, MobileTouchInput.Zone.KEYBOARD,
            MobileTouchInput.Zone.INSPECT, MobileTouchInput.Zone.HANDLE};
    private static final int[] ICONS = {R.drawable.wehavephones_touch_ic_x, R.drawable.wehavephones_touch_ic_map_trifold,
            R.drawable.wehavephones_touch_ic_backpack, R.drawable.wehavephones_touch_ic_squares_four,
            R.drawable.wehavephones_touch_ic_crosshair, R.drawable.wehavephones_touch_ic_hand_grabbing,
            R.drawable.wehavephones_touch_ic_arrow_fat_line_up, R.drawable.wehavephones_touch_ic_sword,
            R.drawable.wehavephones_touch_ic_cursor_click, R.drawable.wehavephones_touch_ic_keyboard,
            R.drawable.wehavephones_touch_ic_magnifying_glass, R.drawable.wehavephones_flip_phone};
    private static final int[] LABELS = {R.string.wehavephones_touch_menu, R.string.wehavephones_touch_map,
            R.string.wehavephones_touch_bags, R.string.wehavephones_touch_more, R.string.wehavephones_touch_target,
            R.string.wehavephones_touch_interact, R.string.wehavephones_touch_jump, R.string.wehavephones_touch_play,
            R.string.wehavephones_touch_menus, R.string.wehavephones_touch_keyboard, R.string.wehavephones_touch_inspect,
            R.string.wehavephones_touch_handle};

    private final XServer xServer;
    private final XServerDisplayActivity activity;
    private final MobileTouchInput input;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Typeface bold = Typeface.create("sans-serif-medium", Typeface.BOLD);
    private final Path path = new Path();
    private final RectF face = new RectF(), scratch = new RectF();
    private float density;
    private final RectF[] buttons = new RectF[MobileTouchLayout.COUNT];
    private final Drawable[] icons = new Drawable[MobileTouchLayout.COUNT];
    private final String[] labels = new String[MobileTouchLayout.COUNT];
    private final Drawable dive, skull, runner, expand;
    private MobileTouchLayout layout;
    private MobileTouchLayout.Bounds lastViewport;
    private float stickX, stickY, radius, knobX, knobY;
    private int stickPointer = -1;
    private XKeycode nextModifier, heldModifier;
    private final Runnable holdCheck;
    private boolean diving;
    private boolean toolbarCollapsed;
    private int handlePointer = -1;
    private float handleX = .03f, handleY = .43f, handleLastX, handleLastY;
    private float insetLeft, insetTop, insetRight, insetBottom;
    // Feedback that the finger would otherwise hide: hint banners, tap ripples and completed holds.
    private String bannerTitle, bannerHint;
    private final Set<String> sessionHints = new HashSet<>();
    private Drawable bannerIcon;
    private long bannerAt = -BANNER_MS, rippleAt = -RIPPLE_MS, pulseAt = -PULSE_MS;
    private float rippleX, rippleY, pulseX, pulseY;
    private boolean guide, swallowing;
    private final MobileTouchMenu menu;

    public MobileTouchControlsView(XServerDisplayActivity activity) {
        super(activity);
        this.activity = activity;
        xServer = activity.getXServer();
        density = getResources().getDisplayMetrics().density;
        input = new MobileTouchInput(this, ViewConfiguration.get(activity).getScaledTouchSlop());
        paint.setTypeface(medium);
        text.setTypeface(medium);
        holdCheck = () -> input.advanceTime(SystemClock.uptimeMillis());
        for (int i = 0; i < buttons.length; i++) {
            buttons[i] = new RectF();
            icons[i] = activity.getDrawable(ICONS[i]).mutate();
            labels[i] = activity.getString(LABELS[i]);
        }
        dive = activity.getDrawable(R.drawable.wehavephones_touch_ic_arrow_fat_line_down).mutate();
        skull = activity.getDrawable(R.drawable.wehavephones_touch_ic_skull).mutate();
        runner = activity.getDrawable(R.drawable.wehavephones_touch_ic_person_simple_run).mutate();
        expand = activity.getDrawable(R.drawable.wehavephones_touch_ic_caret_down).mutate();
        menu = new MobileTouchMenu(activity, this);
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentDescription(activity.getString(R.string.wehavephones_settings_mobile_touch));
        setVisibility(GONE);
    }

    public void showForGame(boolean running) {
        boolean show = running && WeHavePhonesConfig.isMobileTouchEnabled(getContext());
        if (show == (getVisibility() == VISIBLE)) return;
        cancelTouches();
        nextModifier = null;
        guide = false;
        // Login, realm selection and character creation need the whole screen.
        // Play explicitly returns to movement after entering the world.
        if (show) {
            sessionHints.clear();
            bannerAt = -BANNER_MS;
            input.setUiMode(true);
            toolbarCollapsed = true;
            refreshLayout();
            if (!WeHavePhonesConfig.isMobileTouchGuideSeen(getContext(), "menus")) {
                banner(icons[MobileTouchLayout.HANDLE], labels[MobileTouchLayout.HANDLE],
                        getContext().getString(R.string.wehavephones_touch_handle_hint));
            }
        } else menu.dismiss();
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
        lastViewport = viewport();
        layout = new MobileTouchLayout(getWidth(), getHeight(), density, left, top, right, bottom, lastViewport, input.isUiMode());
        insetLeft = left; insetTop = top; insetRight = right; insetBottom = bottom;
        radius = layout.radius;
        stickX = layout.stickX;
        stickY = layout.stickY;
        knobX = stickX;
        knobY = stickY;
        for (int i = 0; i < buttons.length; i++) {
            MobileTouchLayout.Bounds bounds = layout.buttons[i];
            if (bounds == null || toolbarCollapsed) buttons[i].setEmpty();
            else buttons[i].set(bounds.left, bounds.top, bounds.right, bounds.bottom);
        }
        labels[MobileTouchLayout.JUMP] = getContext().getString(diving ? R.string.wehavephones_touch_dive : R.string.wehavephones_touch_jump);
        if (toolbarCollapsed) updateHandle();
        invalidate();
    }

    private void updateHandle() {
        MobileTouchLayout.Bounds handle = MobileTouchLayout.handle(getWidth(), getHeight(), density,
                handleX * getWidth(), handleY * getHeight(), insetLeft, insetTop, insetRight, insetBottom);
        buttons[MobileTouchLayout.HANDLE].set(handle.left, handle.top, handle.right, handle.bottom);
    }

    private MobileTouchInput.Zone zone(float x, float y) {
        for (int i = 0; i < buttons.length; i++) if (buttons[i].contains(x, y)) return ZONES[i];
        if (toolbarCollapsed || input.isUiMode()) return MobileTouchInput.Zone.UI;
        if (Math.hypot(x - stickX, y - stickY) <= radius * 1.2f) return MobileTouchInput.Zone.STICK;
        MobileTouchLayout.Bounds viewport = viewport();
        if (viewport.contains(x, y) && MobileTouchInput.isCameraArea(x - viewport.left, y - viewport.top,
                viewport.width(), viewport.height())) return MobileTouchInput.Zone.CAMERA;
        return MobileTouchInput.Zone.UI;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return false;
        int action = event.getActionMasked();
        // The guide closes on the first touch. That gesture never reaches the game.
        if (guide && action == MotionEvent.ACTION_DOWN) {
            guide = false;
            swallowing = true;
            invalidate();
        }
        if (swallowing) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) swallowing = false;
            return true;
        }
        int index = event.getActionIndex(), id = event.getPointerId(index);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (handlePointer != -1) return true;
                MobileTouchLayout.Bounds viewport = viewport();
                if (lastViewport == null || viewport.width() != lastViewport.width() || viewport.height() != lastViewport.height()) {
                    // The renderer changed between letterboxed and stretched output.
                    refreshLayout();
                }
                MobileTouchInput.Zone zone = zone(event.getX(index), event.getY(index));
                if (zone == MobileTouchInput.Zone.HANDLE) {
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
                    input.move(pointer, x, y, stickX, stickY, radius, SystemClock.uptimeMillis());
                    if (pointer == handlePointer) {
                        handleX += (x - handleLastX) / Math.max(1, getWidth());
                        handleY += (y - handleLastY) / Math.max(1, getHeight());
                        updateHandle();
                        handleX = buttons[MobileTouchLayout.HANDLE].left / Math.max(1, getWidth());
                        handleY = buttons[MobileTouchLayout.HANDLE].top / Math.max(1, getHeight());
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
                if (id == stickPointer) input.move(id, event.getX(index), event.getY(index),
                        stickX, stickY, radius, SystemClock.uptimeMillis());
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
        if (layout == null) return;
        long now = SystemClock.uptimeMillis();
        input.advanceTime(now);
        boolean animating = input.isRunLockPending();
        boolean menus = input.isUiMode();
        if (menus && !toolbarCollapsed) drawBrackets(canvas);
        if (!menus && !toolbarCollapsed) drawStick(canvas);
        for (int i = 0; i < buttons.length; i++) {
            if (buttons[i].isEmpty() || i == MobileTouchLayout.MENUS) continue;
            if (i == MobileTouchLayout.PLAY) drawSwitch(canvas);
            else if (i == MobileTouchLayout.HANDLE) drawHandle(canvas);
            else drawKey(canvas, i);
        }
        // Keep animating from the first touch: a still finger sends no move events.
        MobileTouchInput.Hold hold = input.pendingHold(now, 0);
        if (hold != null) animating = true;
        if (hold != null && hold.progress * MobileTouchInput.HOLD_MS >= HOLD_DELAY_MS) {
            float[] center = holdCenter(hold.zone, hold.x, hold.y);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(7 * density);
            paint.setColor(SHADE);
            canvas.drawCircle(center[0], center[1], center[2], paint);
            paint.setStrokeWidth(4 * density);
            paint.setColor(BLUE_LIGHT);
            scratch.set(center[0] - center[2], center[1] - center[2], center[0] + center[2], center[1] + center[2]);
            canvas.drawArc(scratch, -90, 360 * hold.progress, false, paint);
        }
        if (now - pulseAt < PULSE_MS) {
            float t = (now - pulseAt) / (float) PULSE_MS;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(BLUE_LIGHT);
            paint.setAlpha((int)(150 * (1 - t)));
            canvas.drawCircle(pulseX, pulseY, (30 + 22 * t) * density, paint);
            animating = true;
        }
        if (now - rippleAt < RIPPLE_MS) {
            float t = (now - rippleAt) / (float) RIPPLE_MS;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3 * density);
            paint.setColor(SILVER_LIGHT);
            paint.setAlpha((int)(220 * (1 - t)));
            canvas.drawCircle(rippleX, rippleY, (6 + 20 * t) * density, paint);
            animating = true;
        }
        paint.setAlpha(255);
        if (now - bannerAt < BANNER_MS) {
            drawBanner(canvas, now - bannerAt);
            animating = true;
        }
        if (guide) drawGuide(canvas);
        if (animating) postInvalidateOnAnimation();
    }

    /** Center and radius of a hold ring: around the finger, or around the Target key. */
    private float[] holdCenter(MobileTouchInput.Zone zone, float x, float y) {
        RectF target = buttons[MobileTouchLayout.TARGET];
        if (zone == MobileTouchInput.Zone.TARGET && !target.isEmpty()) {
            return new float[]{target.centerX(), target.centerY(), target.width() * .62f};
        }
        return new float[]{x, y, 34 * density};
    }

    private boolean isActive(int key) {
        switch (key) {
            case MobileTouchLayout.INSPECT: return input.isInspectMode();
            case MobileTouchLayout.MORE: return nextModifier != null;
            case MobileTouchLayout.JUMP: return diving;
            default: return false;
        }
    }

    private void drawKey(Canvas canvas, int key) {
        RectF rect = buttons[key];
        boolean active = isActive(key), pressed = input.isPressed(ZONES[key]);
        drawFace(canvas, rect, active, pressed);
        Drawable icon = key == MobileTouchLayout.JUMP && diving ? dive : icons[key];
        String label = key == MobileTouchLayout.MORE && nextModifier != null ? modifierName() : labels[key];
        drawIconLabel(canvas, face, icon, label, active || pressed);
        if (key == MobileTouchLayout.TARGET) {
            // Holding Target selects a nearby corpse. Show that second action on the key.
            float r = rect.width() * .17f, cx = face.right - r * .55f, cy = face.top + r * .55f;
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(PANEL);
            canvas.drawCircle(cx, cy, r, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.2f * density);
            paint.setColor(ACTIVE_EDGE);
            canvas.drawCircle(cx, cy, r, paint);
            drawIcon(canvas, skull, cx, cy, r * 1.25f, SILVER_LIGHT);
        }
    }

    /** A raised key: dark lip, gradient face and outline. The face sinks onto the lip while pressed. */
    private void drawFace(Canvas canvas, RectF rect, boolean active, boolean pressed) {
        float corner = Math.min(12 * density, rect.width() * .24f), lip = 2.5f * density;
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(LIP);
        canvas.drawRoundRect(rect, corner, corner, paint);
        float shift = pressed ? lip : 0;
        face.set(rect.left, rect.top + shift, rect.right, rect.bottom - lip + shift);
        boolean blue = active || pressed;
        paint.setShader(new LinearGradient(0, face.top, 0, face.bottom, blue ? ACTIVE_TOP : KEY_TOP,
                blue ? ACTIVE_BOTTOM : KEY_BOTTOM, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(face, corner, corner, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.2f * density);
        paint.setColor(blue ? ACTIVE_EDGE : KEY_EDGE);
        canvas.drawRoundRect(face, corner, corner, paint);
    }

    private void drawIconLabel(Canvas canvas, RectF area, Drawable icon, String label, boolean active) {
        float size = layout.textSize;
        text.setTypeface(medium);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(size);
        float width = text.measureText(label);
        if (width > area.width() * .9f) text.setTextSize(size * area.width() * .9f / width);
        float iconSize = Math.min(area.width(), area.height()) * .42f, gap = area.height() * .05f;
        float top = area.centerY() - (iconSize + gap + text.getTextSize()) / 2;
        drawIcon(canvas, icon, area.centerX(), top + iconSize / 2, iconSize, active ? WHITE : SILVER_LIGHT);
        text.setColor(active ? WHITE : SILVER);
        canvas.drawText(label, area.centerX(), top + iconSize + gap - text.ascent() * .92f, text);
    }

    private void drawIcon(Canvas canvas, Drawable icon, float cx, float cy, float size, int tint) {
        icon.setBounds(Math.round(cx - size / 2), Math.round(cy - size / 2), Math.round(cx + size / 2), Math.round(cy + size / 2));
        if (tint != 0) icon.setTint(tint);
        icon.draw(canvas);
    }

    /** Play and Menus as one switch. The current mode is always the blue half. */
    private void drawSwitch(Canvas canvas) {
        RectF play = buttons[MobileTouchLayout.PLAY], menus = buttons[MobileTouchLayout.MENUS];
        scratch.set(Math.min(play.left, menus.left), Math.min(play.top, menus.top),
                Math.max(play.right, menus.right), Math.max(play.bottom, menus.bottom));
        drawFace(canvas, scratch, false, false);
        RectF outer = new RectF(face);
        boolean horizontal = menus.left >= play.right - 1;
        for (int key : new int[]{MobileTouchLayout.PLAY, MobileTouchLayout.MENUS}) {
            RectF segment = new RectF(buttons[key]);
            if (!segment.intersect(outer)) continue;
            boolean current = (key == MobileTouchLayout.MENUS) == input.isUiMode();
            boolean pressed = !current && input.isPressed(ZONES[key]);
            if (current || pressed) {
                float inset = 3 * density, corner = Math.min(10 * density, segment.width() * .22f);
                scratch.set(segment.left + inset, segment.top + inset, segment.right - inset, segment.bottom - inset);
                paint.setStyle(Paint.Style.FILL);
                paint.setShader(new LinearGradient(0, scratch.top, 0, scratch.bottom, ACTIVE_TOP, ACTIVE_BOTTOM, Shader.TileMode.CLAMP));
                if (pressed) paint.setAlpha(140);
                canvas.drawRoundRect(scratch, corner, corner, paint);
                paint.setShader(null);
                paint.setAlpha(255);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.2f * density);
                paint.setColor(ACTIVE_EDGE);
                canvas.drawRoundRect(scratch, corner, corner, paint);
            }
            drawIconLabel(canvas, segment, icons[key], labels[key], current);
        }
        // A hairline between the halves keeps them readable as two choices.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density);
        paint.setColor(KEY_EDGE);
        if (horizontal) canvas.drawLine(menus.left, outer.top + outer.height() * .2f, menus.left, outer.bottom - outer.height() * .2f, paint);
        else canvas.drawLine(outer.left + outer.width() * .2f, menus.top, outer.right - outer.width() * .2f, menus.top, paint);
    }

    private void drawHandle(Canvas canvas) {
        RectF rect = buttons[MobileTouchLayout.HANDLE];
        drawFace(canvas, rect, false, input.isPressed(MobileTouchInput.Zone.HANDLE));
        float h = face.height(), icon = h * .7f;
        drawIcon(canvas, icons[MobileTouchLayout.HANDLE], face.left + h * .55f, face.centerY(), icon, 0);
        drawIcon(canvas, expand, face.right - h * .42f, face.centerY(), h * .42f, SILVER_LIGHT);
        text.setTypeface(medium);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(Math.min(13 * density, h * .34f));
        text.setColor(SILVER_LIGHT);
        float labelX = (face.left + h * .95f + face.right - h * .7f) / 2;
        canvas.drawText(labels[MobileTouchLayout.HANDLE], labelX, face.centerY() - (text.ascent() + text.descent()) / 2, text);
    }

    private void drawStick(Canvas canvas) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x59091628);
        canvas.drawCircle(stickX, stickY, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2 * density);
        paint.setColor(input.isAutoRunning() ? BLUE_LIGHT : 0xB38CC4F7);
        canvas.drawCircle(stickX, stickY, radius, paint);
        // Small arrows at the four edges, like the controller overlay's D-pad.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x80EFF4F8);
        float tip = radius * .9f, base = radius * .76f, half = radius * .08f;
        for (int i = 0; i < 4; i++) {
            float dx = i == 1 ? 1 : i == 3 ? -1 : 0, dy = i == 0 ? -1 : i == 2 ? 1 : 0;
            path.reset();
            path.moveTo(stickX + dx * tip, stickY + dy * tip);
            path.lineTo(stickX + dx * base - dy * half, stickY + dy * base + dx * half);
            path.lineTo(stickX + dx * base + dy * half, stickY + dy * base - dx * half);
            path.close();
            canvas.drawPath(path, paint);
        }
        if (input.isRunLockShown()) {
            // Hold on the runner until it turns blue, then release to keep running.
            float cx = stickX, cy = stickY - radius * MobileTouchInput.RUN_LOCK_OFFSET, r = radius * MobileTouchInput.RUN_LOCK_RADIUS;
            boolean armed = input.isRunLockArmed();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * density);
            paint.setColor(0x998CC4F7);
            paint.setPathEffect(new DashPathEffect(new float[]{4 * density, 4 * density}, 0));
            canvas.drawLine(cx, stickY - radius, cx, cy + r, paint);
            paint.setPathEffect(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(armed ? new LinearGradient(0, cy - r, 0, cy + r, ACTIVE_TOP, ACTIVE_BOTTOM, Shader.TileMode.CLAMP) : null);
            if (!armed) paint.setColor(PANEL);
            canvas.drawCircle(cx, cy, r, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(ACTIVE_EDGE);
            canvas.drawCircle(cx, cy, r, paint);
            drawIcon(canvas, runner, cx, cy, r * 1.15f, WHITE);
            pill(canvas, getContext().getString(R.string.wehavephones_touch_auto_run), cx, cy - r - 6 * density, 0, 1);
        }
        paint.setStyle(Paint.Style.FILL);
        float knob = radius * .38f;
        paint.setShader(new LinearGradient(0, knobY - knob, 0, knobY + knob, ACTIVE_TOP, ACTIVE_BOTTOM, Shader.TileMode.CLAMP));
        canvas.drawCircle(knobX, knobY, knob, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f * density);
        paint.setColor(ACTIVE_EDGE);
        canvas.drawCircle(knobX, knobY, knob, paint);
        if (input.isAutoRunning()) drawIcon(canvas, runner, knobX, knobY, knob * 1.1f, WHITE);
    }

    /** Menus turn the whole game into a pointer surface. Corner brackets mark that state. */
    private void drawBrackets(Canvas canvas) {
        MobileTouchLayout.Bounds viewport = viewport();
        float inset = 8 * density, length = Math.min(34 * density, viewport.width() * .06f);
        float l = Math.max(insetLeft, viewport.left) + inset, t = Math.max(insetTop, viewport.top) + inset;
        float r = Math.min(getWidth() - insetRight, viewport.right) - inset, b = Math.min(getHeight() - insetBottom, viewport.bottom) - inset;
        path.reset();
        path.moveTo(l, t + length); path.lineTo(l, t); path.lineTo(l + length, t);
        path.moveTo(r - length, t); path.lineTo(r, t); path.lineTo(r, t + length);
        path.moveTo(r, b - length); path.lineTo(r, b); path.lineTo(r - length, b);
        path.moveTo(l + length, b); path.lineTo(l, b); path.lineTo(l, b - length);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(7 * density);
        paint.setColor(SHADE);
        canvas.drawPath(path, paint);
        paint.setStrokeWidth(3.5f * density);
        paint.setColor(input.isInspectMode() ? SILVER_LIGHT : BLUE_LIGHT);
        canvas.drawPath(path, paint);
    }

    private void drawBanner(Canvas canvas, long age) {
        float alpha = Math.min(1, Math.min(age / 150f, (BANNER_MS - age) / 400f));
        MobileTouchLayout.Bounds viewport = viewport();
        float titleSize = 15 * density, hintSize = 12.5f * density, iconSize = 24 * density, pad = 12 * density;
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(bold);
        text.setTextSize(titleSize);
        float titleWidth = text.measureText(bannerTitle);
        text.setTypeface(medium);
        text.setTextSize(hintSize);
        float maxText = viewport.width() * .82f - iconSize - 3 * pad;
        float hintWidth = bannerHint == null ? 0 : text.measureText(bannerHint);
        if (hintWidth > maxText) { hintSize *= maxText / hintWidth; hintWidth = maxText; }
        float width = 3 * pad + iconSize + Math.max(titleWidth, hintWidth);
        float height = pad * 1.6f + titleSize + (bannerHint == null ? 0 : hintSize * 1.35f);
        // Over the world while playing. In Menus, over the unused action bars so windows stay readable.
        float cx = viewport.left + viewport.width() / 2, top = input.isUiMode()
                ? Math.min(viewport.bottom, getHeight() - insetBottom) - height - 14 * density
                : viewport.top + viewport.height() * .27f;
        scratch.set(cx - width / 2, top, cx + width / 2, top + height);
        int a = (int)(255 * alpha);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(PANEL);
        paint.setAlpha((int)(0xF2 * alpha));
        canvas.drawRoundRect(scratch, 14 * density, 14 * density, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f * density);
        paint.setColor(ACTIVE_EDGE);
        paint.setAlpha(a);
        canvas.drawRoundRect(scratch, 14 * density, 14 * density, paint);
        paint.setAlpha(255);
        if (bannerIcon != null) {
            bannerIcon.setAlpha(a);
            drawIcon(canvas, bannerIcon, scratch.left + pad + iconSize / 2, scratch.centerY(), iconSize,
                    bannerIcon == icons[MobileTouchLayout.HANDLE] ? 0 : SILVER_LIGHT);
            bannerIcon.setAlpha(255);
        }
        float x = scratch.left + 2 * pad + iconSize, y = scratch.top + pad * .8f;
        text.setTypeface(bold);
        text.setTextSize(titleSize);
        text.setColor(WHITE);
        text.setAlpha(a);
        canvas.drawText(bannerTitle, x, y - text.ascent(), text);
        if (bannerHint != null) {
            text.setTypeface(medium);
            text.setTextSize(hintSize);
            text.setColor(SILVER);
            text.setAlpha(a);
            canvas.drawText(bannerHint, x, y + titleSize * 1.15f - text.ascent(), text);
        }
        text.setAlpha(255);
    }

    /** First use, or Menu, Touch guide: outline every control and say what it does. */
    private void drawGuide(Canvas canvas) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xB8071C39);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        boolean menus = input.isUiMode();
        if (menus) drawBrackets(canvas);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2 * density);
        paint.setColor(BLUE_LIGHT);
        for (int i = 0; i < buttons.length; i++) {
            if (buttons[i].isEmpty() || i == MobileTouchLayout.HANDLE) continue;
            scratch.set(buttons[i]);
            scratch.inset(-3 * density, -3 * density);
            canvas.drawRoundRect(scratch, 12 * density, 12 * density, paint);
        }
        for (int i = 0; i < buttons.length; i++) {
            if (buttons[i].isEmpty() || i == MobileTouchLayout.MENUS || i == MobileTouchLayout.HANDLE) continue;
            if (i == MobileTouchLayout.PLAY) drawSwitch(canvas);
            else drawKey(canvas, i);
        }
        if (!menus) {
            MobileTouchLayout.Bounds viewport = viewport();
            float[][] rects = MobileTouchInput.cameraRects(viewport.width(), viewport.height());
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * density);
            paint.setColor(SILVER_LIGHT);
            paint.setPathEffect(new DashPathEffect(new float[]{8 * density, 6 * density}, 0));
            for (float[] rect : rects) {
                scratch.set(viewport.left + rect[0] * viewport.width(), viewport.top + rect[1] * viewport.height(),
                        viewport.left + rect[2] * viewport.width(), viewport.top + rect[3] * viewport.height());
                canvas.drawRoundRect(scratch, 10 * density, 10 * density, paint);
            }
            paint.setPathEffect(null);
            float[] first = rects[0];
            pill(canvas, getContext().getString(R.string.wehavephones_touch_guide_camera),
                    viewport.left + (first[0] + first[2]) / 2 * viewport.width(),
                    viewport.top + (first[1] + first[3]) / 2 * viewport.height(), 0, 0);
            drawStick(canvas);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * density);
            paint.setColor(BLUE_LIGHT);
            canvas.drawCircle(stickX, stickY, radius * 1.2f, paint);
            pill(canvas, getContext().getString(R.string.wehavephones_touch_guide_stick), stickX, stickY - radius * 1.2f - 8 * density, 0, 1);
            String[] thumbs = {getContext().getString(R.string.wehavephones_touch_guide_target),
                    getContext().getString(R.string.wehavephones_touch_guide_use), getContext().getString(R.string.wehavephones_touch_guide_jump)};
            for (int i = 0; i < 3; i++) {
                RectF key = buttons[MobileTouchLayout.TARGET + i];
                if (!key.isEmpty()) pill(canvas, thumbs[i], key.left - 10 * density, key.centerY(), 1, 0);
            }
        }
        String[] legend = menus
                ? new String[]{getContext().getString(R.string.wehavephones_touch_guide_menus_switch),
                        getContext().getString(R.string.wehavephones_touch_guide_keyboard),
                        getContext().getString(R.string.wehavephones_touch_guide_inspect),
                        getContext().getString(R.string.wehavephones_touch_guide_more),
                        getContext().getString(R.string.wehavephones_touch_guide_escape)}
                : new String[]{getContext().getString(R.string.wehavephones_touch_guide_play_switch),
                        getContext().getString(R.string.wehavephones_touch_guide_more),
                        getContext().getString(R.string.wehavephones_touch_guide_bags),
                        getContext().getString(R.string.wehavephones_touch_guide_escape)};
        // Toolbar notes stack beside the toolbar: below a top row, or left of a rail column.
        MobileTouchLayout.Bounds toolbar = layout.toolbar;
        float line = 30 * density;
        for (int i = 0; i < legend.length; i++) {
            if (layout.column) pill(canvas, legend[i], toolbar.left - 10 * density, toolbar.top + line * (i + .5f), 1, 0);
            else pill(canvas, legend[i], toolbar.right, toolbar.bottom + 10 * density + line * i, 1, -1);
        }
        MobileTouchLayout.Bounds viewport = viewport();
        String taps = getContext().getString(menus ? R.string.wehavephones_touch_guide_menus_taps : R.string.wehavephones_touch_guide_play_taps);
        float cx = viewport.left + viewport.width() / 2;
        pill(canvas, taps, cx, viewport.top + viewport.height() * (menus ? .5f : .8f), 0, 0);
        pill(canvas, getContext().getString(R.string.wehavephones_touch_guide_dismiss), cx, getHeight() - insetBottom - 12 * density, 0, 1);
    }

    /**
     * A label pill. {@code alignX}: 0 centers on x, 1 ends at x. {@code alignY}: 0 centers on y,
     * 1 ends at y, -1 starts at y. Pills are clamped inside the safe area.
     */
    private void pill(Canvas canvas, String label, float x, float y, int alignX, int alignY) {
        float size = 12.5f * density, padX = 10 * density, padY = 6 * density;
        text.setTypeface(medium);
        text.setTextSize(size);
        text.setTextAlign(Paint.Align.LEFT);
        float maxWidth = getWidth() * .6f, width = text.measureText(label);
        if (width > maxWidth) { text.setTextSize(size * maxWidth / width); width = maxWidth; }
        float w = width + 2 * padX, h = text.getTextSize() + 2 * padY;
        float left = alignX == 0 ? x - w / 2 : x - w;
        float top = alignY == 0 ? y - h / 2 : alignY == 1 ? y - h : y;
        left = Math.max(insetLeft + 4 * density, Math.min(getWidth() - insetRight - 4 * density - w, left));
        top = Math.max(insetTop + 4 * density, Math.min(getHeight() - insetBottom - 4 * density - h, top));
        scratch.set(left, top, left + w, top + h);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(PANEL);
        canvas.drawRoundRect(scratch, h / 2, h / 2, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.2f * density);
        paint.setColor(ACTIVE_EDGE);
        canvas.drawRoundRect(scratch, h / 2, h / 2, paint);
        text.setColor(SILVER_LIGHT);
        canvas.drawText(label, left + padX, top + padY - text.ascent() * .95f, text);
    }

    private void banner(Drawable icon, String title, String hint) {
        if (hint != null && !sessionHints.add(hint)) return;
        bannerIcon = icon;
        bannerTitle = title;
        bannerHint = hint;
        bannerAt = SystemClock.uptimeMillis();
        invalidate();
    }

    private void modeBanner() {
        if (input.isInspectMode()) banner(icons[MobileTouchLayout.INSPECT], labels[MobileTouchLayout.INSPECT],
                getContext().getString(R.string.wehavephones_touch_inspect_hint));
        else if (input.isUiMode()) banner(icons[MobileTouchLayout.MENUS], labels[MobileTouchLayout.MENUS],
                getContext().getString(R.string.wehavephones_touch_menus_hint));
        else banner(icons[MobileTouchLayout.PLAY], labels[MobileTouchLayout.PLAY],
                getContext().getString(R.string.wehavephones_touch_play_hint));
    }

    private void maybeShowGuide() {
        String mode = input.isUiMode() ? "menus" : "play";
        if (toolbarCollapsed || WeHavePhonesConfig.isMobileTouchGuideSeen(getContext(), mode)) return;
        WeHavePhonesConfig.setMobileTouchGuideSeen(getContext(), mode);
        showGuide();
    }

    private void showGuide() {
        cancelTouches();
        bannerAt = -BANNER_MS;
        guide = true;
        invalidate();
    }

    @Override public void key(MobileTouchInput.Key key, boolean down) {
        switch (key) {
            case MORE:
                if (!down) post(this::showMenu);
                return;
            case PLAY:
            case MENUS:
            case HANDLE:
            case KEYBOARD:
            case INSPECT:
                if (!down) post(() -> {
                    if (key == MobileTouchInput.Key.HANDLE) {
                        toolbarCollapsed = false;
                        refreshLayout();
                        modeBanner();
                        maybeShowGuide();
                    } else if (key == MobileTouchInput.Key.PLAY) {
                        if (input.isUiMode()) setWindows(false);
                        else modeBanner();
                    } else if (key == MobileTouchInput.Key.MENUS) {
                        if (!input.isUiMode() || input.isInspectMode()) setWindows(true);
                        else modeBanner();
                    } else if (key == MobileTouchInput.Key.KEYBOARD) showKeyboard();
                    else setInspect(!input.isInspectMode());
                });
                return;
            case CORPSE:
                if (down) {
                    RectF target = buttons[MobileTouchLayout.TARGET];
                    pulse(target.centerX(), target.centerY());
                }
                break;
            case AUTORUN:
                if (!down) post(() -> banner(runner, getContext().getString(R.string.wehavephones_touch_auto_run),
                        getContext().getString(R.string.wehavephones_touch_auto_run_hint)));
                break;
            default:
                break;
        }
        XKeycode code;
        switch (key) {
            case FORWARD: code = XKeycode.KEY_W; break;
            case BACK: code = XKeycode.KEY_S; break;
            case LEFT: code = XKeycode.KEY_A; break;
            case RIGHT: code = XKeycode.KEY_D; break;
            case JUMP: code = diving ? XKeycode.KEY_F11 : XKeycode.KEY_SPACE; break;
            case TARGET: code = XKeycode.KEY_TAB; break;
            case CORPSE: code = XKeycode.KEY_F10; break;
            case INTERACT: code = XKeycode.KEY_F; break;
            case BAGS: code = XKeycode.KEY_B; break;
            case MAP: code = XKeycode.KEY_M; break;
            case BAR: code = XKeycode.KEY_N; break;
            case AUTORUN: code = XKeycode.KEY_R; break;
            default: code = XKeycode.KEY_ESC;
        }
        if (down) xServer.injectKeyPress(code);
        else xServer.injectKeyRelease(code);
        // Use also loots corpses. Keep the current mode so movement and camera
        // controls stay available between interactions.
        if (!down && (key == MobileTouchInput.Key.MAP || key == MobileTouchInput.Key.BAGS))
            post(() -> setWindows(true));
    }

    @Override public void click(float x, float y) {
        if (!position(x, y)) return;
        rippleX = x;
        rippleY = y;
        rippleAt = SystemClock.uptimeMillis();
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
        boolean fullscreen = xServer.getRenderer() != null && xServer.getRenderer().isFullscreen();
        return MobileTouchLayout.viewport(Math.max(1, getWidth()), Math.max(1, getHeight()), xServer.screenInfo.width,
                xServer.screenInfo.height, fullscreen);
    }

    @Override public void hover(float x, float y) { position(x, y); }

    @Override public void contextClick(float x, float y) {
        if (!position(x, y)) return;
        pulse(x, y);
        pressModifier();
        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
        releaseModifier();
    }

    private void pulse(float x, float y) {
        pulseX = x;
        pulseY = y;
        pulseAt = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
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

    /** Session-only override bindings from the touch addon. Function keys with Ctrl or Shift, never Alt. */
    private void chord(XKeycode modifier, XKeycode key) {
        xServer.injectKeyPress(modifier);
        tap(key);
        xServer.injectKeyRelease(modifier);
    }

    private void chord(XKeycode first, XKeycode second, XKeycode key) {
        xServer.injectKeyPress(first);
        xServer.injectKeyPress(second);
        tap(key);
        xServer.injectKeyRelease(second);
        xServer.injectKeyRelease(first);
    }

    private void showMenu() {
        if (activity.isFinishing() || !activity.hasWindowFocus()) return;
        cancelTouches();
        guide = false;
        // Surface windows while playing, and typing and item modifiers while using windows.
        menu.show(input.isUiMode() ? MobileTouchMenu.Category.TYPING : MobileTouchMenu.Category.WINDOWS);
    }

    @Override public void run(MobileTouchMenu.Action action) {
        // Let the game window regain focus before the bindings arrive.
        postDelayed(() -> runAction(action), 180);
    }

    @Override public boolean isActive(MobileTouchMenu.Action action) {
        switch (action) {
            case SIZE_125: return WeHavePhonesConfig.getMobileTouchScale(getContext()) == 1.25f;
            case SIZE_150: return WeHavePhonesConfig.getMobileTouchScale(getContext()) == 1.5f;
            case SIZE_175: return WeHavePhonesConfig.getMobileTouchScale(getContext()) == 1.75f;
            case DIVE: return diving;
            case SHIFT: return nextModifier == XKeycode.KEY_SHIFT_L;
            case CTRL: return nextModifier == XKeycode.KEY_CTRL_L;
            case ALT: return nextModifier == XKeycode.KEY_ALT_L;
            case INSPECT: return input.isInspectMode();
            case AUTO_RUN: return input.isAutoRunning();
            default: return false;
        }
    }

    @Override public String modeName() {
        return labels[input.isInspectMode() ? MobileTouchLayout.INSPECT : input.isUiMode() ? MobileTouchLayout.MENUS : MobileTouchLayout.PLAY];
    }

    private void runAction(MobileTouchMenu.Action action) {
        XKeycode ctrl = XKeycode.KEY_CTRL_L, shift = XKeycode.KEY_SHIFT_L, alt = XKeycode.KEY_ALT_L;
        switch (action) {
            case CHARACTER: chord(ctrl, shift, XKeycode.KEY_F1); setWindows(true); break;
            case SPELLBOOK: chord(ctrl, shift, XKeycode.KEY_F2); setWindows(true); break;
            case TALENTS: chord(ctrl, shift, XKeycode.KEY_F3); setWindows(true); break;
            case QUESTS: chord(ctrl, shift, XKeycode.KEY_F4); setWindows(true); break;
            case SOCIAL: chord(ctrl, shift, XKeycode.KEY_F5); setWindows(true); break;
            case SKILLS: chord(ctrl, shift, XKeycode.KEY_F6); setWindows(true); break;
            case REPUTATION: chord(ctrl, shift, XKeycode.KEY_F7); setWindows(true); break;
            case HONOR: chord(ctrl, shift, XKeycode.KEY_F8); setWindows(true); break;
            case PET_SPELLS: chord(ctrl, shift, XKeycode.KEY_F9); setWindows(true); break;
            case GAME_MENU: chord(ctrl, shift, XKeycode.KEY_F10); setWindows(true); break;
            case MACROS: chord(shift, XKeycode.KEY_F1); setWindows(true); break;
            case HELP: chord(shift, XKeycode.KEY_F2); setWindows(true); break;
            case MAP: tap(XKeycode.KEY_M); setWindows(true); break;
            case BAGS: tap(XKeycode.KEY_B); setWindows(true); break;
            case SELF: tap(XKeycode.KEY_F1); break;
            case PARTY1: tap(XKeycode.KEY_F2); break;
            case PARTY2: tap(XKeycode.KEY_F3); break;
            case PARTY3: tap(XKeycode.KEY_F4); break;
            case PARTY4: tap(XKeycode.KEY_F5); break;
            case FRIEND: tap(XKeycode.KEY_F6); break;
            case ASSIST: tap(XKeycode.KEY_G); break;
            case PREVIOUS:
                xServer.injectKeyPress(shift);
                tap(XKeycode.KEY_TAB);
                xServer.injectKeyRelease(shift);
                break;
            case CORPSE: tap(XKeycode.KEY_F10); break;
            case NAMEPLATES: chord(shift, XKeycode.KEY_F7); break;
            case AUTO_RUN:
                tap(XKeycode.KEY_R);
                input.toggleAutoRun();
                if (input.isAutoRunning()) banner(runner, getContext().getString(R.string.wehavephones_touch_auto_run),
                        getContext().getString(R.string.wehavephones_touch_auto_run_hint));
                break;
            case FOLLOW: chord(ctrl, shift, XKeycode.KEY_F11); break;
            case WALK: tap(XKeycode.KEY_F7); break;
            case SIT: tap(XKeycode.KEY_X); break;
            case DIVE:
                diving = !diving;
                refreshLayout();
                if (diving) banner(dive, labels[MobileTouchLayout.JUMP], getContext().getString(R.string.wehavephones_touch_dive_hint));
                else banner(icons[MobileTouchLayout.JUMP], labels[MobileTouchLayout.JUMP], getContext().getString(R.string.wehavephones_touch_jump_hint));
                break;
            case CHAT: parkPointerForChat(); tap(XKeycode.KEY_ENTER); postDelayed(this::showKeyboard, 150); break;
            case REPLY: parkPointerForChat(); chord(ctrl, shift, XKeycode.KEY_F12); postDelayed(this::showKeyboard, 150); break;
            case KEYBOARD: showKeyboard(); break;
            case INSPECT: setInspect(!input.isInspectMode()); break;
            case SHIFT: armModifier(shift); break;
            case CTRL: armModifier(ctrl); break;
            case ALT: armModifier(alt); break;
            case NO_MODIFIER:
                nextModifier = null;
                banner(icons[MobileTouchLayout.MORE], getContext().getString(R.string.wehavephones_touch_modifier_cleared), null);
                break;
            case SIZE_125: setScale(1.25f, XKeycode.KEY_F4); break;
            case SIZE_150: setScale(1.5f, XKeycode.KEY_F5); break;
            case SIZE_175: setScale(1.75f, XKeycode.KEY_F6); break;
            case GUIDE: showGuide(); break;
            case HIDE:
                setWindows(true);
                nextModifier = null;
                toolbarCollapsed = true;
                refreshLayout();
                banner(icons[MobileTouchLayout.HANDLE], labels[MobileTouchLayout.HANDLE],
                        getContext().getString(R.string.wehavephones_touch_handle_hint));
                break;
            case UNDO: chord(shift, XKeycode.KEY_F3); break;
            case PAGE: tap(XKeycode.KEY_N); break;
        }
        invalidate();
    }

    /**
     * The renderer pans the game to keep the pointer above the keyboard. The touch addon raises
     * the chat box to the upper part of the game, so a pointer there leaves the game unpanned.
     */
    private void parkPointerForChat() {
        xServer.injectPointerMove(xServer.screenInfo.width / 2, xServer.screenInfo.height / 5);
    }

    private void setScale(float scale, XKeycode key) {
        WeHavePhonesConfig.setMobileTouchScale(getContext(), scale);
        chord(XKeycode.KEY_SHIFT_L, key);
        banner(activity.getDrawable(R.drawable.wehavephones_touch_ic_text_aa).mutate(),
                getContext().getString(R.string.wehavephones_touch_size_hint, Math.round(scale * 100)), null);
    }

    private void setWindows(boolean enabled) {
        boolean changed = enabled != input.isUiMode() || input.isInspectMode() || toolbarCollapsed;
        cancelTouches();
        input.setUiMode(enabled);
        toolbarCollapsed = false;
        refreshLayout();
        if (changed) {
            modeBanner();
            maybeShowGuide();
        }
    }

    private void setInspect(boolean enabled) {
        cancelTouches();
        if (enabled) {
            input.setInspectMode(true);
            nextModifier = null;
            toolbarCollapsed = false;
            refreshLayout();
            modeBanner();
        } else setWindows(true);
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
        invalidate();
    }

    private String modifierName() {
        return nextModifier == XKeycode.KEY_SHIFT_L ? "Shift" : nextModifier == XKeycode.KEY_CTRL_L ? "Ctrl" : "Alt";
    }

    private void armModifier(XKeycode code) {
        nextModifier = code;
        banner(icons[MobileTouchLayout.MORE], getContext().getString(R.string.wehavephones_touch_next_modifier, modifierName()),
                getContext().getString(R.string.wehavephones_touch_modifier_hint));
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
        menu.dismiss();
        super.onDetachedFromWindow();
    }
}
