package com.example.sso.ratelimit.internal;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory fixed-window rate limiter keyed by an arbitrary string (e.g. client IP).
 * Adequate for a single node; a clustered deployment would back this with Redis.
 */
@Component
public class InMemoryRateLimiter {

    private static final int MAX_TRACKED_KEYS = 50_000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /** Returns true if the call is allowed, false if the limit for the window is exceeded. */
    public boolean tryAcquire(String key, int limit, long windowMillis, long now) {
        if (windows.size() > MAX_TRACKED_KEYS) {
            windows.values().removeIf(w -> now - w.start() >= windowMillis);
        }
        Window window = windows.compute(key, (k, current) ->
                (current == null || now - current.start() >= windowMillis)
                        ? new Window(now, 1)
                        : new Window(current.start(), current.count() + 1));
        return window.count() <= limit;
    }

    private record Window(long start, int count) {
    }
}
