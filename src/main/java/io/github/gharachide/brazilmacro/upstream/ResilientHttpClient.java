package io.github.gharachide.brazilmacro.upstream;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GET-only HTTP client for one Banco Central host: per-request timeout, retries with exponential
 * backoff and jitter on network errors, HTTP 429 and 5xx, and a circuit breaker so that an
 * outage fails fast instead of piling up slow requests.
 *
 * <p>Each logical request is one observation ({@code brazil_macro.upstream.requests}: a timer
 * tagged with the upstream and the outcome, and a span when tracing is on). Retries are counted
 * and the circuit state is published as a gauge (0 closed, 1 open, 2 half-open).
 */
public final class ResilientHttpClient {

    /** Pauses between retries; replaced in tests so they run instantly. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    public static final String USER_AGENT =
            "brazil-macro-mcp/0.1.0 (+https://github.com/gharachide/brazil-macro-mcp)";

    private static final Logger log = LoggerFactory.getLogger(ResilientHttpClient.class);

    private final String name;
    private final HttpClient http;
    private final Duration requestTimeout;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final CircuitBreaker breaker;
    private final Sleeper sleeper;
    private final ObservationRegistry observations;
    private final String upstream;
    private final Counter retries;

    /**
     * @param name human-readable name used in error messages, e.g. "BCB SGS API"
     * @param upstream short tag value for metrics and traces, e.g. "sgs"
     */
    public ResilientHttpClient(String name, String upstream, HttpClient http, Duration requestTimeout, int maxAttempts,
            Duration initialBackoff, CircuitBreaker breaker, Sleeper sleeper, MeterRegistry meters,
            ObservationRegistry observations) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        this.name = name;
        this.http = http;
        this.requestTimeout = requestTimeout;
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.breaker = breaker;
        this.sleeper = sleeper;
        this.observations = observations;
        this.upstream = upstream;
        this.retries = Counter.builder("brazil_macro.upstream.retries")
                .description("Retries after a network error, timeout, HTTP 429 or 5xx")
                .tag("upstream", upstream)
                .register(meters);
        Gauge.builder("brazil_macro.upstream.circuit.state", breaker, b -> b.state().ordinal())
                .description("Circuit breaker state: 0 closed, 1 open, 2 half-open")
                .tag("upstream", upstream)
                .register(meters);
    }

    /** Returns the body of a 2xx response or throws {@link UpstreamException}. */
    public String get(URI uri) {
        Observation observation = Observation.createNotStarted("brazil_macro.upstream.requests", observations)
                .contextualName("GET " + name)
                .lowCardinalityKeyValue("upstream", upstream)
                .highCardinalityKeyValue("url", uri.toString())
                .start();
        try (Observation.Scope scope = observation.openScope()) {
            String body = exchange(uri, observation);
            observation.lowCardinalityKeyValue("outcome", "ok");
            return body;
        } catch (UpstreamException e) {
            observation.lowCardinalityKeyValue("outcome", outcome(e));
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }

    public CircuitBreaker.State circuitState() {
        return breaker.state();
    }

    private String exchange(URI uri, Observation observation) {
        if (!breaker.tryAcquire()) {
            throw new UpstreamException(UpstreamException.Kind.UNAVAILABLE,
                    name + " is temporarily unavailable (circuit open after repeated failures)", uri, 0);
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .build();
        String lastFailure = "no attempt made";
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            observation.highCardinalityKeyValue("attempts", String.valueOf(attempt));
            try {
                HttpResponse<String> response =
                        http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                observation.highCardinalityKeyValue("http.status", String.valueOf(status));
                if (status >= 200 && status < 300) {
                    breaker.onSuccess();
                    return response.body();
                }
                if (status == 404) {
                    breaker.onSuccess();
                    throw new UpstreamException(UpstreamException.Kind.NOT_FOUND,
                            name + " has no data for this request (HTTP 404)", uri, status);
                }
                if (status != 429 && status < 500) {
                    breaker.onSuccess();
                    throw new UpstreamException(UpstreamException.Kind.REJECTED,
                            name + " rejected the request (HTTP " + status + ")", uri, status);
                }
                lastFailure = "HTTP " + status;
            } catch (HttpTimeoutException e) {
                lastFailure = "timed out after " + requestTimeout.toMillis() + " ms";
            } catch (IOException e) {
                lastFailure = e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                breaker.onAbandoned();
                throw new UpstreamException(UpstreamException.Kind.UNAVAILABLE,
                        "Interrupted while calling " + name, uri, 0);
            }
            log.warn("{} attempt {}/{} failed: {} [{}]", name, attempt, maxAttempts, lastFailure, uri);
            if (attempt < maxAttempts) {
                retries.increment();
                if (!pause(attempt)) {
                    breaker.onAbandoned();
                    throw new UpstreamException(UpstreamException.Kind.UNAVAILABLE,
                            "Interrupted while calling " + name, uri, 0);
                }
            }
        }
        breaker.onFailure();
        throw new UpstreamException(UpstreamException.Kind.UNAVAILABLE,
                name + " is unavailable after " + maxAttempts + " attempts (" + lastFailure + ")", uri, 0);
    }

    private static String outcome(UpstreamException e) {
        return switch (e.kind()) {
            case NOT_FOUND -> "not_found";
            case REJECTED -> e.status() == 304 ? "not_modified" : "rejected";
            case MALFORMED -> "malformed";
            case UNAVAILABLE -> e.getMessage().contains("circuit open") ? "circuit_open" : "unavailable";
        };
    }

    private boolean pause(int attempt) {
        long base = initialBackoff.toMillis() << (attempt - 1);
        long jitter = initialBackoff.toMillis() > 0
                ? ThreadLocalRandom.current().nextLong(initialBackoff.toMillis())
                : 0;
        try {
            sleeper.sleep(Duration.ofMillis(base + jitter));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
