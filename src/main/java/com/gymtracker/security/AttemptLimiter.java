package com.gymtracker.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/** At most {@code max} attempts per key in any sliding {@code window}. In memory: fine for one instance. */
public class AttemptLimiter {

    private final int max;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> attempts = new HashMap<>();

    public AttemptLimiter(int max, Duration window, Clock clock) {
        this.max = max;
        this.window = window;
        this.clock = clock;
    }

    public synchronized boolean tryAcquire(String key) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(window);
        attempts.values().removeIf(times -> {
            while (!times.isEmpty() && !times.peekFirst().isAfter(cutoff)) {
                times.pollFirst();
            }
            return times.isEmpty();
        });
        Deque<Instant> times = attempts.computeIfAbsent(key, k -> new ArrayDeque<>());
        if (times.size() >= max) {
            return false;
        }
        times.addLast(now);
        return true;
    }

    public synchronized void reset() {
        attempts.clear();
    }
}
