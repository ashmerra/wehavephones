package com.wehavephones.mobile;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.wehavephones.winhandler.WinHandler;
import com.wehavephones.xserver.Pointer;
import com.wehavephones.xserver.Window;
import com.wehavephones.xserver.WindowManager;
import com.wehavephones.xserver.XKeycode;
import com.wehavephones.xserver.XLock;
import com.wehavephones.xserver.XServer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * X input for the launcher (the Play click on Battle.net), called from a
 * worker thread: every X call runs on the main thread and waits for it.
 */
final class SessionInput {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private SessionInput() {}

    /** Left click at X screen coordinates. */
    static void click(final XServer xServer, final int x, final int y) {
        runOnMainAndWait(() -> {
            xServer.injectPointerMove(x, y);
            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
        });
        sleep(80);
        runOnMainAndWait(() -> xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT));
    }

    /** Press and release a key in the focused Wine window. */
    static void key(final XServer xServer, final XKeycode key) {
        runOnMainAndWait(() -> xServer.injectKeyPress(key));
        sleep(80);
        runOnMainAndWait(() -> xServer.injectKeyRelease(key));
    }

    /**
     * Gives {@code window} the X focus and the Wine foreground, as a tap on
     * it would (DesktopHelper.setFocusedWindow). Call with the window manager locked.
     */
    static boolean focus(XServer xServer, WinHandler winHandler, Window window) {
        Window withHandle = window;
        while (withHandle != null && withHandle.getHandle() == 0) withHandle = withHandle.getParent();
        boolean parentIsRoot = window.getParent() == xServer.windowManager.rootWindow;
        xServer.windowManager.setFocus(window, parentIsRoot ? WindowManager.FocusRevertTo.POINTER_ROOT : WindowManager.FocusRevertTo.PARENT);
        if (withHandle != null) winHandler.bringToFront(withHandle.getClassName(), withHandle.getHandle());
        return withHandle != null;
    }

    static void collectMapped(Window parent, ArrayList<Window> out, int depth, int maxDepth) {
        if (depth > maxDepth) return;
        for (Window child : parent.getChildren()) {
            if (!child.attributes.isMapped() || child.getWidth() <= 1 || child.getHeight() <= 1) continue;
            out.add(child);
            collectMapped(child, out, depth + 1, maxDepth);
        }
    }

    /**
     * Average RGB ({@code 0xRRGGBB}) of a small square around X root
     * coordinates {@code (x, y)}, read from the topmost mapped window there
     * (what the renderer paints), or -1 when no window's pixels are readable.
     * Used to tell Battle.net's disabled Play button from its enabled one.
     */
    static int averagePixel(final XServer xServer, final int x, final int y, final int radius) {
        final int[] result = {-1};
        runOnMainAndWait(() -> {
            try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                Window window = xServer.windowManager.rootWindow;
                Window child;
                while ((child = window.getChildByCoords((short) x, (short) y)) != null) window = child;
                if (window == xServer.windowManager.rootWindow || window.getContent() == null) return;
                ByteBuffer data = window.getContent().getData();
                if (data == null) return;
                short[] local = window.rootPointToLocal((short) x, (short) y);
                int width = window.getWidth();
                int height = window.getHeight();
                long red = 0;
                long green = 0;
                long blue = 0;
                int count = 0;
                for (int py = local[1] - radius; py <= local[1] + radius; py++) {
                    if (py < 0 || py >= height) continue;
                    for (int px = local[0] - radius; px <= local[0] + radius; px++) {
                        if (px < 0 || px >= width) continue;
                        int offset = (py * width + px) * 4;
                        if (offset + 2 >= data.capacity()) return;
                        red += data.get(offset) & 0xFF;
                        green += data.get(offset + 1) & 0xFF;
                        blue += data.get(offset + 2) & 0xFF;
                        count++;
                    }
                }
                if (count == 0) return;
                result[0] = (int)(red / count) << 16 | (int)(green / count) << 8 | (int)(blue / count);
            }
            catch (Exception e) {
                Log.w("WeHavePhonesLaunch", "pixel probe failed", e);
            }
        });
        return result[0];
    }

    static void runOnMainAndWait(final Runnable runnable) {
        final CountDownLatch latch = new CountDownLatch(1);
        MAIN.post(() -> {
            try {
                runnable.run();
            }
            finally {
                latch.countDown();
            }
        });
        try {
            latch.await(10, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
