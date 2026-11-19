package com.wehavephones.mobile;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;

import com.wehavephones.XServerDisplayActivity;
import com.wehavephones.inputcontrols.ExternalController;
import com.wehavephones.inputcontrols.GamepadState;
import com.wehavephones.winhandler.WinHandler;
import com.wehavephones.xserver.Pointer;
import com.wehavephones.xserver.XKeycode;
import com.wehavephones.xserver.XServer;

import java.lang.ref.WeakReference;

/**
 * Lets a controller drive Battle.net's window through {@link BattleNetPadInput}:
 * a poller turns the bridge on while Battle.net runs without a game and Play's
 * loading screen is not covering it. Battle.net's own controller navigation
 * keeps the pad, and pointer mode (View button) moves the X pointer and sends
 * keys instead. WoW's own screens keep their controller
 * handling ({@link GlueControllerBridge}, ConsolePort).
 */
public final class BattleNetControllerBridge {
    private static final String TAG = "WeHavePhonesPadBnet";
    private static final long POLL_MS = 1_000L;
    private static final long GAME_POLL_MS = 5_000L;

    private static final BattleNetPadInput INPUT = new BattleNetPadInput();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean active;
    private static Thread poller;
    private static WeakReference<XServerDisplayActivity> session = new WeakReference<>(null);
    private static boolean ticking;
    private static long lastFrameNanos;

    private BattleNetControllerBridge() {}

    public static synchronized void start(XServerDisplayActivity activity) {
        session = new WeakReference<>(activity);
        if (poller != null && poller.isAlive()) poller.interrupt();
        poller = new Thread(() -> poll(activity), "wehavephones-bnet-pad");
        poller.setDaemon(true);
        poller.start();
    }

    /** True while the controller drives Battle.net. */
    public static boolean isActive() {
        return active;
    }

    /** Pointer mode: the guest sees an idle pad. */
    public static boolean holdsGamepad() {
        return active && INPUT.pointerMode();
    }

    private static void poll(XServerDisplayActivity activity) {
        while (!Thread.currentThread().isInterrupted() && !activity.isDestroyed()) {
            boolean on;
            boolean gameRunning = false;
            try {
                gameRunning = ProcessScanner.findWowProcess() != null;
                on = !gameRunning && activity.isSessionActive() && !BattleNetAgentJobs.isAgentSession(activity) && !debugOff()
                        && !LaunchOverlayController.isCovering() && ProcessScanner.isBattleNetRunning();
            }
            catch (Throwable throwable) {
                on = false;
            }
            if (on != active) {
                active = on;
                Log.i(TAG, on ? "controller drives Battle.net" : "controller left to the game");
                if (!on) MAIN.post(() -> release(activity));
            }
            try {
                // While the game runs the bridge only waits for it to close.
                Thread.sleep(gameRunning ? GAME_POLL_MS : POLL_MS);
            }
            catch (InterruptedException e) {
                break;
            }
        }
        active = false;
    }

    /** Debug builds: {@code adb shell setprop debug.whp.bnetpad 0} turns the bridge off for comparisons. */
    private static boolean debugOff() {
        if (!com.wehavephones.BuildConfig.DEBUG) return false;
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            return "0".equals(properties.getMethod("get", String.class).invoke(null, "debug.whp.bnetpad"));
        }
        catch (Exception e) {
            return false;
        }
    }

    /** Controller input that WinHandler consumed; UI thread. */
    public static void onControllerInput(XServerDisplayActivity activity) {
        if (!active) return;
        WinHandler winHandler = activity.getWinHandler();
        GamepadState state = winHandler != null ? winHandler.getGamepadState() : null;
        XServer xServer = activity.getXServer();
        if (xServer == null) return;
        BattleNetPadInput.State input = new BattleNetPadInput.State();
        if (state != null) {
            int b = 0;
            if (state.isPressed(ExternalController.IDX_BUTTON_A)) b |= BattleNetPadInput.A;
            if (state.isPressed(ExternalController.IDX_BUTTON_B)) b |= BattleNetPadInput.B;
            if (state.isPressed(ExternalController.IDX_BUTTON_X)) b |= BattleNetPadInput.X;
            if (state.isPressed(ExternalController.IDX_BUTTON_Y)) b |= BattleNetPadInput.Y;
            if (state.isPressed(ExternalController.IDX_BUTTON_L1)) b |= BattleNetPadInput.LB;
            if (state.isPressed(ExternalController.IDX_BUTTON_R1)) b |= BattleNetPadInput.RB;
            if (state.isPressed(ExternalController.IDX_BUTTON_L2)) b |= BattleNetPadInput.LT;
            if (state.isPressed(ExternalController.IDX_BUTTON_R2)) b |= BattleNetPadInput.RT;
            if (state.isPressed(ExternalController.IDX_BUTTON_START)) b |= BattleNetPadInput.START;
            if (state.isPressed(ExternalController.IDX_BUTTON_SELECT)) b |= BattleNetPadInput.SELECT;
            if (state.dpad[0]) b |= BattleNetPadInput.UP;
            if (state.dpad[1]) b |= BattleNetPadInput.RIGHT;
            if (state.dpad[2]) b |= BattleNetPadInput.DOWN;
            if (state.dpad[3]) b |= BattleNetPadInput.LEFT;
            input.buttons = b;
            input.leftX = state.thumbLX;
            input.leftY = state.thumbLY;
            input.rightX = state.thumbRX;
            input.rightY = state.thumbRY;
        }
        INPUT.onState(input, SystemClock.uptimeMillis(), sink(activity, xServer));
        if (INPUT.moving()) startTicks();
    }

    private static void release(XServerDisplayActivity activity) {
        XServer xServer = activity.getXServer();
        if (xServer != null) INPUT.reset(sink(activity, xServer));
    }

    private static void startTicks() {
        if (ticking) return;
        ticking = true;
        lastFrameNanos = 0;
        Choreographer.getInstance().postFrameCallback(FRAME);
    }

    private static final Choreographer.FrameCallback FRAME = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            XServerDisplayActivity activity = session.get();
            XServer xServer = activity != null ? activity.getXServer() : null;
            if (!active || xServer == null || !INPUT.moving()) {
                ticking = false;
                return;
            }
            float seconds = lastFrameNanos == 0 ? 1f / 60f : (frameTimeNanos - lastFrameNanos) / 1e9f;
            lastFrameNanos = frameTimeNanos;
            INPUT.tick(SystemClock.uptimeMillis(), seconds, xServer.screenInfo.width, sink(activity, xServer));
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private static BattleNetPadInput.Sink sink(XServerDisplayActivity activity, XServer xServer) {
        return new BattleNetPadInput.Sink() {
            @Override
            public void key(int key, boolean down) {
                XKeycode code = keycode(key);
                if (down) xServer.injectKeyPress(code);
                else xServer.injectKeyRelease(code);
            }

            @Override
            public void button(int button, boolean down) {
                Pointer.Button code = button == BattleNetPadInput.BUTTON_RIGHT ? Pointer.Button.BUTTON_RIGHT : Pointer.Button.BUTTON_LEFT;
                if (down) xServer.injectPointerButtonPress(code);
                else xServer.injectPointerButtonRelease(code);
            }

            @Override
            public void move(int dx, int dy) {
                int x = Math.max(0, Math.min(xServer.screenInfo.width - 1, xServer.pointer.getX() + dx));
                int y = Math.max(0, Math.min(xServer.screenInfo.height - 1, xServer.pointer.getY() + dy));
                xServer.injectPointerMove(x, y);
            }

            @Override
            public void scroll(int steps) {
                Pointer.Button wheel = steps > 0 ? Pointer.Button.BUTTON_SCROLL_DOWN : Pointer.Button.BUTTON_SCROLL_UP;
                for (int i = 0; i < Math.abs(steps); i++) {
                    xServer.injectPointerButtonPress(wheel);
                    xServer.injectPointerButtonRelease(wheel);
                }
            }

            @Override
            public void keyboard() {
                activity.showKeyboardForController();
            }

            @Override
            public void pointerMode(boolean on) {
                Log.i(TAG, "pointer mode " + (on ? "on" : "off"));
                // Battle.net's own navigation sees an idle pad while the pointer is on, and the pad again after.
                WinHandler winHandler = activity.getWinHandler();
                if (winHandler != null) winHandler.sendGamepadState();
                MAIN.post(() -> android.widget.Toast.makeText(activity, on
                        ? com.wehavephones.R.string.wehavephones_pad_pointer_on
                        : com.wehavephones.R.string.wehavephones_pad_pointer_off, android.widget.Toast.LENGTH_SHORT).show());
            }
        };
    }

    private static XKeycode keycode(int key) {
        switch (key) {
            case BattleNetPadInput.KEY_ENTER: return XKeycode.KEY_ENTER;
            case BattleNetPadInput.KEY_ESCAPE: return XKeycode.KEY_ESC;
            case BattleNetPadInput.KEY_TAB: return XKeycode.KEY_TAB;
            case BattleNetPadInput.KEY_SHIFT: return XKeycode.KEY_SHIFT_L;
            case BattleNetPadInput.KEY_UP: return XKeycode.KEY_UP;
            case BattleNetPadInput.KEY_DOWN: return XKeycode.KEY_DOWN;
            case BattleNetPadInput.KEY_LEFT: return XKeycode.KEY_LEFT;
            case BattleNetPadInput.KEY_RIGHT:
            default: return XKeycode.KEY_RIGHT;
        }
    }
}
