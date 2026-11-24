package io.github.gharachide.brazilmacro.upstream;

import java.time.Instant;

/**
 * Provenance of one upstream response.
 *
 * @param url exact URL that produced the data; anyone can open it to check the numbers
 * @param retrievedAt when the data was fetched from the BCB (not when it was served)
 * @param cache whether the data was fetched now, served from cache, or a stale fallback
 */
public record SourceRequest(String url, Instant retrievedAt, CacheStatus cache) {

    public boolean stale() {
        return cache == CacheStatus.STALE_FALLBACK;
    }
}
