package io.futurecode;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BoundedLimiter {
    private record Entry(long until, int count) {}
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Clock clock;
    private final int capacity;
    public BoundedLimiter() { this(Clock.systemUTC(), 2048); }
    BoundedLimiter(Clock clock, int capacity) { this.clock = clock; this.capacity = capacity; }

    public synchronized void consume(String key, int limit, long interval) {
        long now = clock.millis();
        entries.entrySet().removeIf(entry -> entry.getValue().until <= now);
        Entry entry = entries.get(key);
        if (entry == null) {
            if (entries.size() >= capacity) throw new ApiError(503, "The service is busy. Retry shortly.", "rate_capacity");
            entries.put(key, new Entry(now + interval, 1));
        } else {
            if (entry.count >= limit) throw new ApiError(429, "Too many requests. Wait before retrying.", "rate_limited");
            entries.put(key, new Entry(entry.until, entry.count + 1));
        }
    }
}
