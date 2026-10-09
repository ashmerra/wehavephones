package com.wehavephones.mobile;

import android.os.Handler;
import android.os.Looper;

import com.wehavephones.winhandler.WinHandler;
import com.wehavephones.xserver.Pointer;
import com.wehavephones.xserver.Window;
import com.wehavephones.xserver.WindowManager;
import com.wehavephones.xserver.XServer;

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
