package com.wehavephones.mobile;

import java.util.HashMap;
import java.util.Map;

/** Pointer ownership for simultaneous movement, camera and direct UI taps. No Android dependencies. */
public final class MobileTouchInput {
    public enum Key { FORWARD, BACK, LEFT, RIGHT, JUMP, TARGET, INTERACT, BAGS, MAP, ESCAPE, BAR, MORE, CORPSE, MODE, KEYBOARD, INSPECT }
    public enum Zone { STICK, CAMERA, UI, JUMP, TARGET, INTERACT, BAGS, MAP, ESCAPE, BAR, MORE, MODE, KEYBOARD, INSPECT }
    public interface Sink {
        void key(Key key, boolean down);
        void click(float x, float y);
        void camera(boolean down);
        void moveCamera(float dx, float dy);
        void contextClick(float x, float y);
        void drag(float x, float y, boolean down);
        void hover(float x, float y);
        void scroll(float x, float y, int clicks);
    }

    private static final class Finger {
        final Zone zone;
        final float startX, startY;
        float x, y;
        boolean dragged;
        boolean consumed;
        final long downAt;
        Finger(Zone zone, float x, float y, long now) {
            this.zone = zone;
            downAt = now;
            this.x = startX = x;
            this.y = startY = y;
        }
    }
    private final Map<Integer, Finger> fingers = new HashMap<>();
    private final Sink sink;
    private final float slop;
    private int stick = -1, camera = -1;
    private boolean cameraDown;
    private boolean uiMode;
    private boolean inspectMode;
    private int drag = -1, multiA = -1, multiB = -1;
    private float multiDistance, multiY;
    private int multiMode;
    public static final long HOLD_MS = 550;
    private final boolean[] keys = new boolean[Key.values().length];

    public MobileTouchInput(Sink sink, float slop) { this.sink = sink; this.slop = slop; }

    /** Wide camera swipes with room reserved for the enlarged bars at every UI preset. */
    public static boolean isCameraArea(float x, float y, float width, float height) {
        float layoutWidth = Math.min(width * .80f, height * 16 / 9);
        float horizontal = (x - (width - layoutWidth) / 2) / layoutWidth, vertical = y / height;
        if (width / height < 1.6f) {
            // The 4x3 action grid leaves a tall camera strip beside it.
            return (horizontal > .38f && horizontal < .90f && vertical > .31f && vertical < (width / height < 1.25f ? .45f : .50f))
                    || (horizontal > .32f && horizontal < .54f && vertical > .52f && vertical < .72f);
        }
        return horizontal > .38f && horizontal < .90f && vertical > .31f
                && vertical < (horizontal >= .66f ? .60f : .72f);
    }

    public void down(int id, Zone zone, float x, float y, long now) {
        if (fingers.containsKey(id)) return;
        if (uiMode && zone == Zone.CAMERA) zone = Zone.UI;
        // A second thumb in the stick must never click or drag the game underneath it.
        if (zone == Zone.STICK && stick != -1) {
            Finger ignored = new Finger(zone, x, y, now);
            ignored.consumed = true;
            fingers.put(id, ignored);
            return;
        }
        fingers.put(id, new Finger(zone, x, y, now));
        if (inspectMode && (zone == Zone.UI || zone == Zone.CAMERA)) {
            fingers.get(id).consumed = true;
            setCamera(false);
            sink.hover(x, y);
            return;
        }
        if (drag != -1 && (zone == Zone.UI || zone == Zone.CAMERA)) fingers.get(id).consumed = true;
        if (zone == Zone.STICK) stick = id;
        else if (zone == Zone.CAMERA && camera == -1) camera = id;
        // Pair only fingers in the same gesture area. A spell tap must stay independent
        // from the camera finger during three-finger movement/camera/casting.
        if (multiA == -1 && drag == -1 && (zone == Zone.CAMERA || zone == Zone.UI)) {
            for (Map.Entry<Integer, Finger> entry : fingers.entrySet()) {
                Finger other = entry.getValue();
                if (entry.getKey() == id || other.zone != zone || other.consumed) continue;
                multiA = entry.getKey();
                multiB = id;
                multiDistance = (float)Math.hypot(other.x - x, other.y - y);
                multiY = (other.y + y) / 2;
                multiMode = 0;
                other.consumed = fingers.get(id).consumed = true;
                setCamera(false);
                break;
            }
        }
        refreshKeys();
    }

    public void advanceTime(long now) {
        if (drag != -1 || multiA != -1) return;
        for (Finger finger : fingers.values()) {
            if (finger.zone == Zone.TARGET && !finger.dragged && !finger.consumed && now - finger.downAt >= HOLD_MS) {
                finger.consumed = true;
                setCamera(false);
                pulse(Key.CORPSE);
            } else if ((finger.zone == Zone.UI || finger.zone == Zone.CAMERA) && !finger.dragged
                    && !finger.consumed && now - finger.downAt >= HOLD_MS) {
                finger.consumed = true;
                setCamera(false);
                sink.contextClick(finger.x, finger.y);
            }
        }
    }

    public boolean isUiMode() { return uiMode; }
    public void setUiMode(boolean enabled) { cancel(); uiMode = enabled; inspectMode = false; }
    public boolean isInspectMode() { return inspectMode; }
    public void setInspectMode(boolean enabled) { cancel(); inspectMode = enabled; if (enabled) uiMode = true; }

    /** Supply both paired positions before interpreting one Android move event. */
    public void updateMultiPosition(int id, float x, float y) {
        if (id != multiA && id != multiB) return;
        Finger finger = fingers.get(id);
        if (finger != null) { finger.x = x; finger.y = y; }
    }

    public void move(int id, float x, float y, float stickX, float stickY, float radius) {
        Finger finger = fingers.get(id);
        if (finger == null) return;
        float dx = x - finger.x, dy = y - finger.y;
        finger.x = x;
        finger.y = y;
        if (Math.hypot(x - finger.startX, y - finger.startY) > slop) finger.dragged = true;
        if (id == multiA || id == multiB) {
            Finger a = fingers.get(multiA), b = fingers.get(multiB);
            if (a == null || b == null) return;
            float distance = (float)Math.hypot(a.x - b.x, a.y - b.y);
            float centerY = (a.y + b.y) / 2;
            if (multiMode == 0) {
                if (Math.abs(distance - multiDistance) > slop * 3) multiMode = 1;
                else if (Math.abs(centerY - multiY) > slop * 3) multiMode = 2;
            }
            float delta = multiMode == 1 ? distance - multiDistance : centerY - multiY;
            int clicks = multiMode == 0 ? 0 : (int)(delta / (slop * 4));
            if (clicks != 0) {
                sink.scroll((a.x + b.x) / 2, centerY, Math.max(-4, Math.min(4, clicks)));
                if (multiMode == 1) multiDistance = distance;
                else multiY = centerY;
            }
            return;
        }
        if (id == stick) {
            setKey(Key.LEFT, x - stickX < -radius * .24f);
            setKey(Key.RIGHT, x - stickX > radius * .24f);
            setKey(Key.FORWARD, y - stickY < -radius * .24f);
            setKey(Key.BACK, y - stickY > radius * .24f);
        } else if (inspectMode && finger.zone == Zone.UI) sink.hover(x, y);
        else if (finger.consumed) return;
        else if (id == camera && finger.dragged && (dx != 0 || dy != 0)) {
            setCamera(true);
            sink.moveCamera(dx, dy);
        } else if (finger.zone == Zone.UI && finger.dragged) {
            if (drag == -1) {
                drag = id;
                setCamera(false);
                sink.drag(finger.startX, finger.startY, true);
            }
            if (drag == id) sink.hover(x, y);
        }
    }

    public void up(int id) {
        Finger finger = fingers.remove(id);
        if (finger == null) return;
        if (id == multiA || id == multiB) {
            multiA = multiB = -1;
        }
        if (id == drag) {
            sink.drag(finger.x, finger.y, false);
            drag = -1;
        }
        if (id == stick) {
            stick = -1;
            setKey(Key.FORWARD, false);
            setKey(Key.BACK, false);
            setKey(Key.LEFT, false);
            setKey(Key.RIGHT, false);
        } else if (id == camera) {
            camera = -1;
            setCamera(false);
        }
        if ((finger.zone == Zone.UI || finger.zone == Zone.CAMERA) && !finger.dragged && !finger.consumed) {
            // WoW captures the mouse during right-drag. Drop the grab before an absolute UI tap.
            setCamera(false);
            sink.click(finger.x, finger.y);
            // A held camera finger re-arms only on its next movement.
        }
        if (!finger.consumed && !finger.dragged) {
            if (finger.zone == Zone.TARGET) pulse(Key.TARGET);
            if (finger.zone == Zone.MORE) pulse(Key.MORE);
            if (finger.zone == Zone.MODE) pulse(Key.MODE);
            if (finger.zone == Zone.KEYBOARD) pulse(Key.KEYBOARD);
            if (finger.zone == Zone.INSPECT) pulse(Key.INSPECT);
        }
        refreshKeys();
    }

    private void refreshKeys() {
        for (int i = Key.JUMP.ordinal(); i < Key.values().length; i++) {
            Key key = Key.values()[i];
            // Target is a tap or hold action. Never cycle a living enemy before a corpse hold.
            if (key == Key.TARGET || key == Key.CORPSE || key == Key.MORE || key == Key.MODE
                    || key == Key.KEYBOARD || key == Key.INSPECT) continue;
            boolean held = false;
            for (Finger finger : fingers.values()) if (finger.zone.name().equals(key.name())) held = true;
            setKey(key, held);
        }
    }

    private void pulse(Key key) {
        sink.key(key, true);
        sink.key(key, false);
    }

    private void setKey(Key key, boolean down) {
        if (keys[key.ordinal()] == down) return;
        keys[key.ordinal()] = down;
        sink.key(key, down);
    }

    private void setCamera(boolean down) {
        if (cameraDown == down) return;
        cameraDown = down;
        sink.camera(down);
    }

    public void cancel() {
        if (drag != -1) {
            Finger finger = fingers.get(drag);
            if (finger != null) sink.drag(finger.x, finger.y, false);
        }
        fingers.clear();
        stick = camera = drag = multiA = multiB = -1;
        setCamera(false);
        for (Key key : Key.values()) setKey(key, false);
    }
}
