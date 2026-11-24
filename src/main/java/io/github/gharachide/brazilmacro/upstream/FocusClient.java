package io.github.gharachide.brazilmacro.upstream;

import io.github.gharachide.brazilmacro.catalog.FocusIndicator;
import io.github.gharachide.brazilmacro.domain.FocusSurvey;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Client for the Focus market survey (Olinda OData service {@code Expectativas}). Uses the
 * annual statistics computed over projections submitted in the 30 days up to each survey date
 * ({@code baseCalculo = 0}), the basis of the weekly Focus report.
 */
public final class FocusClient {

    public record Response(List<FocusSurvey> surveys, SourceRequest source) {
    }

    private static final String FIELDS =
            "Indicador,Data,DataReferencia,Media,Mediana,DesvioPadrao,Minimo,Maximo,numeroRespondentes,baseCalculo";

    private final CachedFetcher fetcher;
    private final String baseUrl;
    private final JsonMapper mapper;

    public FocusClient(CachedFetcher fetcher, URI baseUrl, JsonMapper mapper) {
        this.fetcher = fetcher;
        this.baseUrl = SgsClient.stripTrailingSlash(baseUrl.toString());
        this.mapper = mapper;
    }

    /** The most recent {@code surveys} survey dates for one indicator and reference year. */
    public Response annual(FocusIndicator indicator, int referenceYear, int surveys) {
        String filter = "Indicador eq '" + indicator.apiName() + "' and DataReferencia eq '" + referenceYear
                + "' and baseCalculo eq 0";
        URI uri = URI.create(baseUrl + "/ExpectativasMercadoAnuais?$top=" + surveys
                + "&$filter=" + encode(filter)
                + "&$orderby=" + encode("Data desc")
                + "&$select=" + encode(FIELDS)
                + "&$format=json");
        CachedFetcher.Fetched fetched = fetcher.get(uri);
        try {
            return new Response(parse(fetched.body(), uri, indicator, referenceYear), fetched.source());
        } catch (UpstreamException e) {
            fetcher.evict(uri);
            throw e;
        }
    }

    public CircuitBreaker.State circuitState() {
        return fetcher.circuitState();
    }

    /**
     * Parses the OData payload. Rows that do not match the requested indicator, reference year
     * and calculation basis mean the filter was not applied, so the whole response is rejected.
     */
    List<FocusSurvey> parse(String body, URI uri, FocusIndicator indicator, int referenceYear) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (JacksonException e) {
            throw malformed("the response is not valid JSON", uri);
        }
        JsonNode rows = root == null ? null : root.get("value");
        if (rows == null || !rows.isArray()) {
            throw malformed("expected an OData object with a 'value' array", uri);
        }
        List<FocusSurvey> surveys = new ArrayList<>(rows.size());
        for (JsonNode row : rows.values()) {
            if (!indicator.apiName().equals(row.path("Indicador").asString(""))
                    || !String.valueOf(referenceYear).equals(row.path("DataReferencia").asString(""))
                    || row.path("baseCalculo").asInt(-1) != 0) {
                throw malformed("a row does not match the requested indicator, year and basis", uri);
            }
            surveys.add(new FocusSurvey(
                    parseDate(row.path("Data"), uri),
                    decimal(row.path("Mediana"), uri),
                    decimal(row.path("Media"), uri),
                    decimal(row.path("DesvioPadrao"), uri),
                    decimal(row.path("Minimo"), uri),
                    decimal(row.path("Maximo"), uri),
                    row.path("numeroRespondentes").isIntegralNumber() ? row.path("numeroRespondentes").intValue() : null));
        }
        surveys.sort(Comparator.comparing(FocusSurvey::surveyDate).reversed());
        return List.copyOf(surveys);
    }

    private static LocalDate parseDate(JsonNode node, URI uri) {
        if (!node.isString() || node.stringValue().length() < 10) {
            throw malformed("a row has no 'Data' field", uri);
        }
        try {
            return LocalDate.parse(node.stringValue().substring(0, 10));
        } catch (DateTimeParseException e) {
            throw malformed("unexpected date '" + node.stringValue() + "'", uri);
        }
    }

    private static BigDecimal decimal(JsonNode node, URI uri) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isString()) {
            try {
                return new BigDecimal(node.stringValue().trim());
            } catch (NumberFormatException e) {
                throw malformed("unexpected number '" + node.stringValue() + "'", uri);
            }
        }
        throw malformed("unexpected value " + node, uri);
    }

    private static UpstreamException malformed(String reason, URI uri) {
        return new UpstreamException(UpstreamException.Kind.MALFORMED,
                "Unexpected response from the BCB Focus API: " + reason, uri, 200);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
