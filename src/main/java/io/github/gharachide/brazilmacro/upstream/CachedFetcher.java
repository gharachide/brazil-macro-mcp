package io.github.gharachide.brazilmacro.upstream;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves a response from memory while it is fresh, otherwise fetches it. When the BCB is
 * unavailable and a copy younger than {@code maxStale} exists, the copy is returned and flagged
 * {@link CacheStatus#STALE_FALLBACK}, so the agent can say how old the data is.
 */
public final class CachedFetcher {

    public record Fetched(String body, SourceRequest source) {
    }

    private static final Logger log = LoggerFactory.getLogger(CachedFetcher.class);

    private final ResilientHttpClient http;
    private final ResponseCache cache;
    private final Clock clock;
    private final Duration ttl;
    private final Duration maxStale;
    private final Counter hits;
    private final Counter misses;
    private final Counter staleFallbacks;

    public CachedFetcher(String upstream, ResilientHttpClient http, ResponseCache cache, Clock clock, Duration ttl,
            Duration maxStale, MeterRegistry meters) {
        this.http = http;
        this.cache = cache;
        this.clock = clock;
        this.ttl = ttl;
        this.maxStale = maxStale;
        this.hits = lookups(meters, upstream, "hit");
        this.misses = lookups(meters, upstream, "miss");
        this.staleFallbacks = lookups(meters, upstream, "stale_fallback");
    }

    private static Counter lookups(MeterRegistry meters, String upstream, String result) {
        return Counter.builder("brazil_macro.cache.lookups")
                .description("Responses served from memory (hit), fetched (miss) or served stale while the BCB was down")
                .tag("upstream", upstream)
                .tag("result", result)
                .register(meters);
    }

    public Fetched get(URI uri) {
        String key = uri.toString();
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Optional<ResponseCache.Entry> cached = cache.get(key);
        if (cached.isPresent() && !cached.get().fetchedAt().plus(ttl).isBefore(now)) {
            hits.increment();
            return new Fetched(cached.get().body(), new SourceRequest(key, cached.get().fetchedAt(), CacheStatus.HIT));
        }
        misses.increment();
        try {
            String body = http.get(uri);
            cache.put(key, body, now);
            return new Fetched(body, new SourceRequest(key, now, CacheStatus.MISS));
        } catch (UpstreamException e) {
            if (e.kind() == UpstreamException.Kind.UNAVAILABLE && cached.isPresent()
                    && !cached.get().fetchedAt().plus(maxStale).isBefore(now)) {
                staleFallbacks.increment();
                log.warn("Serving stale copy fetched at {} because the upstream is unavailable: {}",
                        cached.get().fetchedAt(), e.getMessage());
                return new Fetched(cached.get().body(),
                        new SourceRequest(key, cached.get().fetchedAt(), CacheStatus.STALE_FALLBACK));
            }
            throw e;
        }
    }

    /** Drops a cached body that turned out to be unusable. */
    public void evict(URI uri) {
        cache.evict(uri.toString());
    }

    public CircuitBreaker.State circuitState() {
        return http.circuitState();
    }
}
