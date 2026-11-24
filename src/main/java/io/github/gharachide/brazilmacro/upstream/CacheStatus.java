package io.github.gharachide.brazilmacro.upstream;

/** Where the data behind a response came from. */
public enum CacheStatus {
    /** Fetched from the BCB for this request. */
    MISS,
    /** Served from memory; fetched within the cache TTL. */
    HIT,
    /** The BCB was unavailable; served from an older copy, flagged as stale. */
    STALE_FALLBACK
}
