package io.github.gharachide.brazilmacro.upstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gharachide.brazilmacro.catalog.FocusIndicator;
import io.github.gharachide.brazilmacro.support.FakeBcbServer.Response;
import io.github.gharachide.brazilmacro.support.TestStack;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class UpstreamClientsTest {

    private static final String TWO_MONTHS = "[{\"data\":\"01/02/2023\",\"valor\":\"0.84\"},"
            + "{\"data\":\"01/01/2023\",\"valor\":\"0.53\"}]";

    private TestStack stack = TestStack.start();

    @AfterEach
    void stop() {
        stack.close();
    }

    @Test
    void buildsTheSgsUrlAndParsesObservationsInDateOrder() {
        stack.server.respond(r -> Response.json(TWO_MONTHS));

        SgsClient.Response response = stack.sgs.range(433, LocalDate.of(2023, 1, 1), LocalDate.of(2023, 2, 28));

        assertThat(response.observations()).extracting(o -> o.date().toString())
                .containsExactly("2023-01-01", "2023-02-01");
        assertThat(response.observations().getFirst().value()).isEqualByComparingTo("0.53");
        assertThat(response.source().url()).endsWith(
                "/dados/serie/bcdata.sgs.433/dados?formato=json&dataInicial=01/01/2023&dataFinal=28/02/2023");
        assertThat(response.source().cache()).isEqualTo(CacheStatus.MISS);
        assertThat(stack.server.requests().getFirst().userAgent()).startsWith("brazil-macro-mcp/");
    }

    @Test
    void retriesServerErrorsWithBackoff() {
        AtomicInteger calls = new AtomicInteger();
        stack.server.respond(r -> calls.incrementAndGet() < 3 ? Response.status(503) : Response.json(TWO_MONTHS));

        SgsClient.Response response = stack.sgs.latest(433, 2);

        assertThat(response.observations()).hasSize(2);
        assertThat(calls).hasValue(3);
        assertThat(stack.sleeps).hasSize(2);
        assertThat(stack.sleeps.get(0).toMillis()).as("100 ms + jitter").isBetween(100L, 199L);
        assertThat(stack.sleeps.get(1).toMillis()).as("doubled + jitter").isBetween(200L, 299L);
    }

    @Test
    void bypassesTheSgsServerCacheWhenItAnswersNotModifiedToAPlainGet() {
        // Seen against the real API: HTTP 304 to an unconditional GET for some URLs.
        stack.server.respond(r -> "true".equals(r.query().get("nocache"))
                ? Response.json(TWO_MONTHS)
                : new Response(304, "", 0));

        SgsClient.Response response = stack.sgs.latest(433, 4);

        assertThat(response.observations()).hasSize(2);
        assertThat(response.source().url()).endsWith("/ultimos/4?formato=json&nocache=true");
        assertThat(stack.server.requests()).hasSize(2);
    }

    @Test
    void publishesRequestRetryCacheAndCircuitMetrics() {
        AtomicInteger calls = new AtomicInteger();
        stack.server.respond(r -> calls.incrementAndGet() == 1 ? Response.status(503) : Response.json(TWO_MONTHS));

        stack.sgs.latest(433, 2);
        stack.sgs.latest(433, 2);

        var meters = stack.meters;
        assertThat(meters.get("brazil_macro.upstream.requests").tags("upstream", "sgs", "outcome", "ok").timer().count())
                .isEqualTo(1);
        assertThat(meters.get("brazil_macro.upstream.retries").tag("upstream", "sgs").counter().count()).isEqualTo(1);
        assertThat(meters.get("brazil_macro.cache.lookups").tags("upstream", "sgs", "result", "miss").counter().count())
                .isEqualTo(1);
        assertThat(meters.get("brazil_macro.cache.lookups").tags("upstream", "sgs", "result", "hit").counter().count())
                .isEqualTo(1);
        assertThat(meters.get("brazil_macro.upstream.circuit.state").tag("upstream", "sgs").gauge().value()).isZero();
    }

    @Test
    void tagsFailedRequestsWithTheirOutcome() {
        stack.server.respond(r -> Response.status(500));
        for (int i = 1; i <= 3; i++) {
            int n = i;
            assertThatThrownBy(() -> stack.sgs.latest(433, n)).isInstanceOf(UpstreamException.class);
        }
        assertThatThrownBy(() -> stack.sgs.latest(433, 9)).isInstanceOf(UpstreamException.class);

        var meters = stack.meters;
        assertThat(meters.get("brazil_macro.upstream.requests").tags("outcome", "unavailable").timer().count())
                .isEqualTo(3);
        assertThat(meters.get("brazil_macro.upstream.requests").tags("outcome", "circuit_open").timer().count())
                .isEqualTo(1);
        assertThat(meters.get("brazil_macro.upstream.circuit.state").tag("upstream", "sgs").gauge().value()).isEqualTo(1);
    }

    @Test
    void doesNotRetryNotFound() {
        stack.server.respond(r -> Response.status(404));

        assertThatThrownBy(() -> stack.sgs.latest(433, 1))
                .isInstanceOfSatisfying(UpstreamException.class,
                        e -> assertThat(e.kind()).isEqualTo(UpstreamException.Kind.NOT_FOUND));
        assertThat(stack.server.requests()).hasSize(1);
    }

    @Test
    void reportsUnavailableAfterAllAttemptsAndThenOpensTheCircuit() {
        stack.server.respond(r -> Response.status(500));

        for (int i = 0; i < 3; i++) {
            int count = i + 1;
            assertThatThrownBy(() -> stack.sgs.latest(433, count))
                    .isInstanceOfSatisfying(UpstreamException.class,
                            e -> assertThat(e.kind()).isEqualTo(UpstreamException.Kind.UNAVAILABLE));
        }
        assertThat(stack.server.requests()).hasSize(9);
        assertThat(stack.sgs.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> stack.sgs.latest(433, 4)).hasMessageContaining("circuit open");
        assertThat(stack.server.requests()).as("no request while the circuit is open").hasSize(9);
    }

    @Test
    void timesOutSlowResponses() {
        stack.close();
        stack = TestStack.startWithTimeout(Duration.ofMillis(200));
        stack.server.respond(r -> Response.json(TWO_MONTHS).delayed(1_000));

        assertThatThrownBy(() -> stack.sgs.latest(433, 2))
                .isInstanceOf(UpstreamException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    void rejectsMalformedPayloadsInsteadOfUsingThemPartially() {
        stack.server.respond(r -> Response.json("[{\"data\":\"01/01/2023\",\"valor\":\"0.53\"},{\"data\":\"2023-02-01\",\"valor\":\"0.84\"}]"));

        assertThatThrownBy(() -> stack.sgs.latest(433, 2))
                .isInstanceOfSatisfying(UpstreamException.class,
                        e -> assertThat(e.kind()).isEqualTo(UpstreamException.Kind.MALFORMED));
    }

    @Test
    void rejectsHtmlErrorPagesServedWithStatus200() {
        stack.server.respond(r -> Response.json("<html>maintenance</html>"));

        assertThatThrownBy(() -> stack.sgs.latest(433, 1)).hasMessageContaining("not valid JSON");
    }

    @Test
    void servesFromCacheWithinTheTtl() {
        stack.server.respond(r -> Response.json(TWO_MONTHS));
        stack.sgs.latest(433, 2);

        SgsClient.Response second = stack.sgs.latest(433, 2);

        assertThat(second.source().cache()).isEqualTo(CacheStatus.HIT);
        assertThat(stack.server.requests()).hasSize(1);
    }

    @Test
    void fallsBackToAStaleCopyFlaggedAsSuchWhenTheBcbIsDown() {
        stack.server.respond(r -> Response.json(TWO_MONTHS));
        SgsClient.Response fresh = stack.sgs.latest(433, 2);
        stack.clock.advance(Duration.ofHours(2));
        stack.server.respond(r -> Response.status(503));

        SgsClient.Response stale = stack.sgs.latest(433, 2);

        assertThat(stale.source().cache()).isEqualTo(CacheStatus.STALE_FALLBACK);
        assertThat(stack.meters.get("brazil_macro.cache.lookups").tags("upstream", "sgs", "result", "stale_fallback").counter().count())
                .isEqualTo(1);
        assertThat(stale.source().retrievedAt()).isEqualTo(fresh.source().retrievedAt());
        assertThat(stale.observations()).isEqualTo(fresh.observations());
    }

    @Test
    void refusesStaleCopiesOlderThanTheLimit() {
        stack.server.respond(r -> Response.json(TWO_MONTHS));
        stack.sgs.latest(433, 2);
        stack.clock.advance(Duration.ofHours(25));
        stack.server.respond(r -> Response.status(503));

        assertThatThrownBy(() -> stack.sgs.latest(433, 2)).isInstanceOf(UpstreamException.class);
    }

    @Test
    void focusQueryIsEncodedAndRowsAreValidated() {
        stack.server.respond(r -> Response.json("{\"value\":[{\"Indicador\":\"Câmbio\",\"Data\":\"2026-11-20\","
                + "\"DataReferencia\":\"2026\",\"Media\":5.41,\"Mediana\":5.4,\"DesvioPadrao\":0.1,\"Minimo\":5.1,"
                + "\"Maximo\":5.8,\"numeroRespondentes\":95,\"baseCalculo\":0}]}"));

        FocusClient.Response response = stack.focus.annual(FocusIndicator.CAMBIO, 2026, 1);

        assertThat(response.surveys()).singleElement().satisfies(s -> {
            assertThat(s.median()).isEqualByComparingTo("5.4");
            assertThat(s.respondents()).isEqualTo(95);
            assertThat(s.surveyDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        });
        assertThat(stack.server.requests().getFirst().query())
                .containsEntry("$filter", "Indicador eq 'Câmbio' and DataReferencia eq '2026' and baseCalculo eq 0")
                .containsEntry("$orderby", "Data desc")
                .containsEntry("$top", "1");
        assertThat(stack.server.requests().getFirst().rawQuery()).doesNotContain(" ").contains("C%C3%A2mbio");
    }

    @Test
    void focusRowsThatIgnoreTheFilterAreRejected() {
        stack.server.respond(r -> Response.json("{\"value\":[{\"Indicador\":\"IPCA\",\"Data\":\"2026-11-20\","
                + "\"DataReferencia\":\"2027\",\"Mediana\":4.0,\"baseCalculo\":0}]}"));

        assertThatThrownBy(() -> stack.focus.annual(FocusIndicator.IPCA, 2026, 1))
                .isInstanceOfSatisfying(UpstreamException.class,
                        e -> assertThat(e.kind()).isEqualTo(UpstreamException.Kind.MALFORMED));
    }
}
