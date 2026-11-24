package io.github.gharachide.brazilmacro.config;

import io.github.gharachide.brazilmacro.audit.AuditLog;
import io.github.gharachide.brazilmacro.upstream.CachedFetcher;
import io.github.gharachide.brazilmacro.upstream.CircuitBreaker;
import io.github.gharachide.brazilmacro.upstream.FocusClient;
import io.github.gharachide.brazilmacro.upstream.ResilientHttpClient;
import io.github.gharachide.brazilmacro.upstream.ResponseCache;
import io.github.gharachide.brazilmacro.upstream.SgsClient;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.nio.file.Path;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the BCB clients (one circuit breaker and cache per host) and the audit log. */
@Configuration(proxyBeanMethods = false)
public class BrazilMacroConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    HttpClient bcbHttpClient(BrazilMacroProperties properties) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.upstream().connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL);
        ProxySelector proxy = ProxySelector.getDefault();
        if (proxy != null) {
            builder.proxy(proxy); // honours the standard -Dhttps.proxyHost settings
        }
        return builder.build();
    }

    @Bean
    SgsClient sgsClient(HttpClient bcbHttpClient, BrazilMacroProperties properties, Clock clock,
            MeterRegistry meters, ObservationRegistry observations) {
        return new SgsClient(fetcher("sgs", "BCB SGS API", bcbHttpClient, properties, clock, meters, observations),
                properties.upstream().sgsBaseUrl(), JsonSupport.MAPPER);
    }

    @Bean
    FocusClient focusClient(HttpClient bcbHttpClient, BrazilMacroProperties properties, Clock clock,
            MeterRegistry meters, ObservationRegistry observations) {
        return new FocusClient(fetcher("focus", "BCB Focus API", bcbHttpClient, properties, clock, meters, observations),
                properties.upstream().focusBaseUrl(), JsonSupport.MAPPER);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "brazil-macro.audit", name = "enabled", havingValue = "true", matchIfMissing = true)
    AuditLog auditLog(BrazilMacroProperties properties, Clock clock) throws IOException {
        Path path = properties.audit().path() != null
                ? properties.audit().path()
                : Path.of(System.getProperty("user.home"), ".brazil-macro-mcp", "audit.jsonl");
        return new AuditLog(path, clock);
    }

    private static CachedFetcher fetcher(String upstream, String name, HttpClient http, BrazilMacroProperties properties,
            Clock clock, MeterRegistry meters, ObservationRegistry observations) {
        BrazilMacroProperties.Upstream upstreamProps = properties.upstream();
        BrazilMacroProperties.Cache cache = properties.cache();
        CircuitBreaker breaker = new CircuitBreaker(upstreamProps.circuitFailureThreshold(),
                upstreamProps.circuitOpenDuration(), clock);
        ResilientHttpClient client = new ResilientHttpClient(name, upstream, http, upstreamProps.requestTimeout(),
                upstreamProps.maxAttempts(), upstreamProps.initialBackoff(), breaker, Thread::sleep, meters, observations);
        return new CachedFetcher(upstream, client, new ResponseCache(cache.maxEntries()), clock, cache.ttl(),
                cache.maxStale(), meters);
    }
}
