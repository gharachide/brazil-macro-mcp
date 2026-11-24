package io.github.gharachide.brazilmacro.upstream;

import io.github.gharachide.brazilmacro.domain.Observation;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Client for the SGS JSON API ({@code bcdata.sgs.{code}/dados}). Responses are arrays of
 * {@code {"data": "dd/MM/yyyy", "valor": "1.23"}}; anything else is rejected as malformed rather
 * than partially used.
 */
public final class SgsClient {

    public record Response(List<Observation> observations, SourceRequest source) {
    }

    static final DateTimeFormatter BR_DATE =
            DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);

    private static final Logger log = LoggerFactory.getLogger(SgsClient.class);

    private final CachedFetcher fetcher;
    private final String baseUrl;
    private final JsonMapper mapper;

    public SgsClient(CachedFetcher fetcher, URI baseUrl, JsonMapper mapper) {
        this.fetcher = fetcher;
        this.baseUrl = stripTrailingSlash(baseUrl.toString());
        this.mapper = mapper;
    }

    /** Observations between two dates, inclusive. The SGS serves at most 10 years per call. */
    public Response range(int code, LocalDate start, LocalDate end) {
        URI uri = URI.create(baseUrl + "/bcdata.sgs." + code + "/dados?formato=json"
                + "&dataInicial=" + BR_DATE.format(start)
                + "&dataFinal=" + BR_DATE.format(end));
        return fetch(uri);
    }

    /** The last {@code count} observations (the SGS allows at most 20). */
    public Response latest(int code, int count) {
        URI uri = URI.create(baseUrl + "/bcdata.sgs." + code + "/dados/ultimos/" + count + "?formato=json");
        return fetch(uri);
    }

    public CircuitBreaker.State circuitState() {
        return fetcher.circuitState();
    }

    private Response fetch(URI uri) {
        CachedFetcher.Fetched fetched;
        URI used = uri;
        try {
            fetched = fetcher.get(uri);
        } catch (UpstreamException e) {
            if (e.status() != 304) {
                throw e;
            }
            // Observed in production: the SGS sometimes answers 304 Not Modified to requests that are
            // not conditional. Its own nocache=true parameter bypasses the stale server-side entry.
            log.warn("SGS answered 304 to an unconditional request; retrying with nocache=true [{}]", uri);
            used = URI.create(uri + "&nocache=true");
            fetched = fetcher.get(used);
        }
        try {
            return new Response(parse(fetched.body(), used), fetched.source());
        } catch (UpstreamException e) {
            fetcher.evict(used);
            throw e;
        }
    }

    List<Observation> parse(String body, URI uri) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (JacksonException e) {
            throw malformed("the response is not valid JSON", uri);
        }
        if (root == null || !root.isArray()) {
            throw malformed("expected a JSON array of observations", uri);
        }
        List<Observation> observations = new ArrayList<>(root.size());
        for (JsonNode node : root.values()) {
            observations.add(new Observation(parseDate(node.path("data"), uri), parseValue(node.path("valor"), uri)));
        }
        observations.sort(Comparator.comparing(Observation::date));
        return List.copyOf(observations);
    }

    private static LocalDate parseDate(JsonNode node, URI uri) {
        if (!node.isString()) {
            throw malformed("an observation has no 'data' field", uri);
        }
        try {
            return LocalDate.parse(node.stringValue().trim(), BR_DATE);
        } catch (DateTimeParseException e) {
            throw malformed("unexpected date '" + node.stringValue() + "'", uri);
        }
    }

    private static BigDecimal parseValue(JsonNode node, URI uri) {
        try {
            if (node.isString()) {
                return new BigDecimal(node.stringValue().trim());
            }
            if (node.isNumber()) {
                return node.decimalValue();
            }
        } catch (NumberFormatException e) {
            throw malformed("unexpected value '" + node.asString() + "'", uri);
        }
        throw malformed("an observation has no 'valor' field", uri);
    }

    private static UpstreamException malformed(String reason, URI uri) {
        return new UpstreamException(UpstreamException.Kind.MALFORMED,
                "Unexpected response from the BCB SGS API: " + reason, uri, 200);
    }

    static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
