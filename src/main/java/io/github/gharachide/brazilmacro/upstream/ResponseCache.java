package io.github.gharachide.brazilmacro.upstream;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Bounded, least-recently-used cache of raw response bodies keyed by URL. */
public final class ResponseCache {

    public record Entry(String body, Instant fetchedAt) {
    }

    private final Map<String, Entry> entries;

    public ResponseCache(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be at least 1");
        }
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > maxEntries;
            }
        };
    }

    public synchronized Optional<Entry> get(String key) {
        return Optional.ofNullable(entries.get(key));
    }

    public synchronized void put(String key, String body, Instant fetchedAt) {
        entries.put(key, new Entry(body, fetchedAt));
    }

    public synchronized void evict(String key) {
        entries.remove(key);
    }

    public synchronized int size() {
        return entries.size();
    }
}
