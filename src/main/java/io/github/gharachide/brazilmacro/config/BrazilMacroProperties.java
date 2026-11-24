package io.github.gharachide.brazilmacro.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * All tunables of the server, bound from {@code brazil-macro.*}.
 *
 * @param upstream BCB endpoints, timeouts, retries and circuit breaker
 * @param cache in-memory response cache and stale fallback
 * @param limits request limits enforced before calling the BCB
 * @param audit tamper-evident audit log of tool calls
 * @param http protections for the Streamable HTTP endpoint
 */
@ConfigurationProperties(prefix = "brazil-macro")
public record BrazilMacroProperties(
        @DefaultValue Upstream upstream,
        @DefaultValue Cache cache,
        @DefaultValue Limits limits,
        @DefaultValue Audit audit,
        @DefaultValue Http http) {

    /**
     * @param sgsBaseUrl base URL of the SGS JSON API
     * @param focusBaseUrl base URL of the Focus (Expectativas de Mercado) OData API
     * @param connectTimeout TCP connect timeout
     * @param requestTimeout timeout for a whole HTTP exchange
     * @param maxAttempts attempts per request, including the first one
     * @param initialBackoff first retry delay, doubled on each retry, with jitter
     * @param circuitFailureThreshold consecutive failed requests that open the circuit
     * @param circuitOpenDuration how long the circuit stays open before a trial request
     */
    public record Upstream(
            @DefaultValue("https://api.bcb.gov.br/dados/serie") URI sgsBaseUrl,
            @DefaultValue("https://olinda.bcb.gov.br/olinda/servico/Expectativas/versao/v1/odata") URI focusBaseUrl,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("15s") Duration requestTimeout,
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("250ms") Duration initialBackoff,
            @DefaultValue("5") int circuitFailureThreshold,
            @DefaultValue("30s") Duration circuitOpenDuration) {
    }

    /**
     * @param ttl how long a response is served from memory without calling the BCB
     * @param maxStale how old a cached response may be when it is served because the BCB is down
     * @param maxEntries upper bound on cached responses (least recently used are evicted)
     */
    public record Cache(
            @DefaultValue("10m") Duration ttl,
            @DefaultValue("24h") Duration maxStale,
            @DefaultValue("256") int maxEntries) {
    }

    /**
     * @param maxYearsDaily widest date range for daily series (the SGS serves at most 10 years)
     * @param maxYearsMonthly widest date range for monthly series, fetched in 10-year windows
     * @param maxObservations most observations returned by a single get_series call
     * @param maxLatest most observations returned by get_latest (the SGS limit is 20)
     */
    public record Limits(
            @DefaultValue("10") int maxYearsDaily,
            @DefaultValue("40") int maxYearsMonthly,
            @DefaultValue("1000") int maxObservations,
            @DefaultValue("20") int maxLatest) {
    }

    /**
     * @param enabled whether tool calls are recorded
     * @param path JSON Lines file holding the hash chain
     * @param failClosed when true, a tool call fails if its audit record cannot be written
     */
    public record Audit(
            @DefaultValue("true") boolean enabled,
            Path path,
            @DefaultValue("false") boolean failClosed) {
    }

    /**
     * @param allowedOrigins browser origins allowed to call /mcp; requests without an Origin
     *     header (non-browser clients) are always allowed
     * @param bearerToken when set, /mcp requires {@code Authorization: Bearer <token>}
     */
    public record Http(
            @DefaultValue List<String> allowedOrigins,
            String bearerToken) {
    }
}
