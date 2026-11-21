package com.combatweevil886.smoothsailing.core;

import com.combatweevil886.smoothsailing.SmoothSailing;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Logs a given key at most once per interval so a broken ship can't flood the log 60 times a second. */
public final class RateLimitedLog {
    private static final long INTERVAL_MS = 30_000L;
    private static final Map<String, Long> LAST = new ConcurrentHashMap<>();

    private RateLimitedLog() {
    }

    public static boolean shouldLog(String key) {
        long now = System.currentTimeMillis();
        Long prev = LAST.get(key);
        if (prev != null && now - prev < INTERVAL_MS) {
            return false;
        }
        LAST.put(key, now);
        return true;
    }

    public static void warn(String key, String message, Object... args) {
        if (shouldLog(key)) {
            SmoothSailing.LOGGER.warn(message, args);
        }
    }

    public static void error(String key, String message, Throwable t) {
        if (shouldLog(key)) {
            SmoothSailing.LOGGER.error(message, t);
        }
    }
}
