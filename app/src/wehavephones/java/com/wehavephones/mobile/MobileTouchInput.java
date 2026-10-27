package com.wehavephones.mobile;

import java.util.HashMap;
import java.util.Map;

/** Pointer ownership for simultaneous movement, camera and direct UI taps. No Android dependencies. */
public final class MobileTouchInput {
    public enum Key { FORWARD, BACK, LEFT, RIGHT, JUMP, TARGET, INTERACT, BAGS, MAP, ESCAPE, BAR }
    public enum Zone { STICK, CAMERA, UI, JUMP, TARGET, INTERACT, BAGS, MAP, ESCAPE, BAR }
    public interface Sink {
        void key(Key key, boolean down);
        void click(float x, float y);
        void camera(boolean down);
        void moveCamera(float dx, float dy);
    }

    private static final class Finger {
        final Zone zone;
        final float startX, startY;
        float x, y;
        boolean dragged;
        Finger(Zone zone, float x, float y) {
            this.zone = zone;
            this.x = startX = x;
            this.y = startY = y;
        }
    }
    private final Map<Integer, Finger> fingers = new HashMap<>();
    private final Sink sink;
    private final float slop;
    private int stick = -1, camera = -1;
    private boolean cameraDown;
    private final boolean[] keys = new boolean[Key.values().length];

    public MobileTouchInput(Sink sink, float slop) { this.sink = sink; this.slop = slop; }

    public void down(int id, Zone zone, float x, float y) {
        if (fingers.containsKey(id)) return;
        if (zone == Zone.STICK && stick != -1 || zone == Zone.CAMERA && camera != -1) zone = Zone.UI;
        fingers.put(id, new Finger(zone, x, y));
        if (zone == Zone.STICK) stick = id;
        else if (zone == Zone.CAMERA) camera = id;
        refreshKeys();
    }

    public void move(int id, float x, float y, float stickX, float stickY, float radius) {
        Finger finger = fingers.get(id);
        if (finger == null) return;
        float dx = x - finger.x, dy = y - finger.y;
        finger.x = x;
        finger.y = y;
        if (Math.hypot(x - finger.startX, y - finger.startY) > slop) finger.dragged = true;
        if (id == stick) {
            setKey(Key.LEFT, x - stickX < -radius * .24f);
            setKey(Key.RIGHT, x - stickX > radius * .24f);
            setKey(Key.FORWARD, y - stickY < -radius * .24f);
            setKey(Key.BACK, y - stickY > radius * .24f);
        } else if (id == camera && finger.dragged && (dx != 0 || dy != 0)) {
            setCamera(true);
            sink.moveCamera(dx, dy);
        }
    }

    public void up(int id) {
        Finger finger = fingers.remove(id);
        if (finger == null) return;
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
        if ((finger.zone == Zone.UI || finger.zone == Zone.CAMERA) && !finger.dragged) {
            // WoW captures the mouse during right-drag. Drop the grab before an absolute UI tap.
            setCamera(false);
            sink.click(finger.x, finger.y);
            // A held camera finger re-arms only on its next movement.
        }
        refreshKeys();
    }

    private void refreshKeys() {
        for (int i = Key.JUMP.ordinal(); i < Key.values().length; i++) {
            Key key = Key.values()[i];
            boolean held = false;
            for (Finger finger : fingers.values()) if (finger.zone.name().equals(key.name())) held = true;
            setKey(key, held);
        }
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
        fingers.clear();
        stick = camera = -1;
        setCamera(false);
        for (Key key : Key.values()) setKey(key, false);
    }
}
