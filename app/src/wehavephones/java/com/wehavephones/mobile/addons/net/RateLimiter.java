package com.wehavephones.mobile.addons.net;

import java.util.concurrent.ConcurrentHashMap;

/** Minimal per-host token bucket: at most one request per interval. */
public final class RateLimiter {
    private static final ConcurrentHashMap<String, Long> NEXT_ALLOWED = new ConcurrentHashMap<>();
    private static final long DEFAULT_INTERVAL_MS = 1000;

    private RateLimiter() {}

    public static void acquire(String host) {
        acquire(host, DEFAULT_INTERVAL_MS);
    }

    public static void acquire(String host, long intervalMs) {
        if (host == null || host.isEmpty()) return;
        long now = System.currentTimeMillis();
        while (true) {
            Long next = NEXT_ALLOWED.get(host);
            long wait;
            if (next == null || next <= now) {
                NEXT_ALLOWED.put(host, now + intervalMs);
                return;
            }
            wait = next - now;
            try {
                Thread.sleep(Math.min(wait, 1000));
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            now = System.currentTimeMillis();
        }
    }
}
