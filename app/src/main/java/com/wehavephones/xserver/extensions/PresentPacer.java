package com.wehavephones.xserver.extensions;

import java.util.ArrayDeque;

/**
 * Paces the Idle/Complete notifications of presented frames. A swapchain
 * cannot reuse an image before its notifications arrive, so spacing them
 * holds the client to that frame rate. Used while the session is hidden: the
 * game keeps running (and stays online) without rendering at full speed for
 * nobody. Notifications always go out in presentation order.
 */
final class PresentPacer {
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private long intervalMs;
    private long lastDueMs;

    /** Minimum time between two frames' notifications; 0 sends them right away. */
    synchronized void setIntervalMs(long intervalMs) {
        this.intervalMs = Math.max(0, intervalMs);
    }

    /**
     * Queues a frame's notifications, presented at nowMs (a monotonic clock).
     * Returns -1 when nothing is queued and there is no interval: the caller
     * sends them right away itself and nothing is queued. Otherwise returns
     * the delay after which the caller must call sendNext() once.
     */
    synchronized long submit(Runnable notify, long nowMs) {
        if (intervalMs == 0 && queue.isEmpty()) return -1;
        long dueMs = Math.max(nowMs, lastDueMs + intervalMs);
        lastDueMs = dueMs;
        queue.add(notify);
        return dueMs - nowMs;
    }

    /** Sends the oldest queued notifications; the lock keeps them ahead of any later frame's. */
    synchronized void sendNext() {
        Runnable notify = queue.poll();
        if (notify != null) notify.run();
    }
}
