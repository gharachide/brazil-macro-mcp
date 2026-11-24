package io.github.gharachide.brazilmacro.support;

import io.github.gharachide.brazilmacro.config.BrazilMacroProperties;
import io.github.gharachide.brazilmacro.config.JsonSupport;
import io.github.gharachide.brazilmacro.service.MacroDataService;
import io.github.gharachide.brazilmacro.upstream.CachedFetcher;
import io.github.gharachide.brazilmacro.upstream.CircuitBreaker;
import io.github.gharachide.brazilmacro.upstream.FocusClient;
import io.github.gharachide.brazilmacro.upstream.ResilientHttpClient;
import io.github.gharachide.brazilmacro.upstream.ResponseCache;
import io.github.gharachide.brazilmacro.upstream.SgsClient;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** The real clients and service, wired to a {@link FakeBcbServer}, with retries that do not sleep. */
public final class TestStack implements AutoCloseable {

    /** Saturday 2024-01-20, noon in Brasília. */
    public static final Instant NOW = Instant.parse("2024-01-20T15:00:00Z");

    public final FakeBcbServer server;
    public final MutableClock clock = new MutableClock(NOW);
    public final List<Duration> sleeps = new CopyOnWriteArrayList<>();
    public final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    public final ObservationRegistry observations = ObservationRegistry.create();
    public final SgsClient sgs;
    public final FocusClient focus;
    public final MacroDataService service;

    private TestStack(FakeBcbServer server, Duration requestTimeout) {
        this.server = server;
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        BrazilMacroProperties properties = properties(server, requestTimeout);
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        this.sgs = new SgsClient(fetcher("SGS", "sgs", http, properties), properties.upstream().sgsBaseUrl(), JsonSupport.MAPPER);
        this.focus = new FocusClient(fetcher("Focus", "focus", http, properties), properties.upstream().focusBaseUrl(),
                JsonSupport.MAPPER);
        this.service = new MacroDataService(sgs, focus, properties, clock);
    }

    public static TestStack start() {
        return new TestStack(FakeBcbServer.start(), Duration.ofSeconds(5));
    }

    public static TestStack startWithTimeout(Duration requestTimeout) {
        return new TestStack(FakeBcbServer.start(), requestTimeout);
    }

    public static BrazilMacroProperties properties(FakeBcbServer server, Duration requestTimeout) {
        return new BrazilMacroProperties(
                new BrazilMacroProperties.Upstream(server.sgsBaseUrl(), server.focusBaseUrl(), Duration.ofSeconds(2),
                        requestTimeout, 3, Duration.ofMillis(100), 3, Duration.ofSeconds(30)),
                new BrazilMacroProperties.Cache(Duration.ofMinutes(10), Duration.ofHours(24), 64),
                new BrazilMacroProperties.Limits(10, 40, 1000, 20),
                new BrazilMacroProperties.Audit(false, null, false),
                new BrazilMacroProperties.Http(List.of(), null));
    }

    private CachedFetcher fetcher(String name, String upstream, HttpClient http, BrazilMacroProperties properties) {
        BrazilMacroProperties.Upstream u = properties.upstream();
        CircuitBreaker breaker = new CircuitBreaker(u.circuitFailureThreshold(), u.circuitOpenDuration(), clock);
        ResilientHttpClient client = new ResilientHttpClient(name, upstream, http, u.requestTimeout(), u.maxAttempts(),
                u.initialBackoff(), breaker, sleeps::add, meters, observations);
        BrazilMacroProperties.Cache c = properties.cache();
        return new CachedFetcher(upstream, client, new ResponseCache(c.maxEntries()), clock, c.ttl(), c.maxStale(),
                meters);
    }

    @Override
    public void close() {
        server.close();
    }
}
