package com.combatweevil886.smoothsailing.core;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe counters. Two kinds:
 * <ul>
 *     <li><b>events</b> - something was prevented or fixed (shown by {@code /smoothsailing status});</li>
 *     <li><b>hooks</b> - a mixin handler ran at least once. Proves the hook is live in this environment and is
 *     asserted by the CI self-test.</li>
 * </ul>
 */
public final class Stats {
    private static final Map<String, AtomicLong> EVENTS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> HOOKS = new ConcurrentHashMap<>();

    // Hot-path counters get dedicated fields to avoid a map lookup per physics tick.
    public static final AtomicLong PHYS_TICKS = new AtomicLong();
    public static final AtomicLong FORCES_CHECKED = new AtomicLong();

    private Stats() {
    }

    public static void event(String name) {
        EVENTS.computeIfAbsent(name, k -> new AtomicLong()).incrementAndGet();
    }

    public static void event(String name, long amount) {
        EVENTS.computeIfAbsent(name, k -> new AtomicLong()).addAndGet(amount);
    }

    /** Marks a mixin handler as live. Cheap after the first call for a given name. */
    public static void hook(String name) {
        AtomicLong c = HOOKS.get(name);
        if (c == null) {
            c = HOOKS.computeIfAbsent(name, k -> new AtomicLong());
        }
        c.incrementAndGet();
    }

    public static long hookCount(String name) {
        AtomicLong c = HOOKS.get(name);
        return c == null ? 0 : c.get();
    }

    public static long eventCount(String name) {
        AtomicLong c = EVENTS.get(name);
        return c == null ? 0 : c.get();
    }

    public static Map<String, Long> events() {
        Map<String, Long> out = new TreeMap<>();
        EVENTS.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }

    public static Map<String, Long> hooks() {
        Map<String, Long> out = new TreeMap<>();
        HOOKS.forEach((k, v) -> out.put(k, v.get()));
        out.put("physics.tick", PHYS_TICKS.get());
        out.put("physics.forceChecked", FORCES_CHECKED.get());
        return out;
    }
}
