package io.github.gharachide.brazilmacro.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.gharachide.brazilmacro.catalog.SeriesDefinition;
import io.github.gharachide.brazilmacro.domain.FocusSurvey;
import io.github.gharachide.brazilmacro.domain.Observation;
import io.github.gharachide.brazilmacro.upstream.SourceRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Payloads returned by the tools, serialized as both JSON text and structured content. */
public final class Results {

    private Results() {
    }

    /**
     * Where the numbers came from. {@code citeAs} is a ready-to-quote reference; {@code requests}
     * lists the exact API calls so anyone can reproduce the figures.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Citation(
            String provider,
            String dataset,
            Integer seriesCode,
            String seriesName,
            String humanUrl,
            String terms,
            List<SourceRequest> requests,
            boolean stale,
            String citeAs) {
    }

    public record SeriesInfo(String alias, int sgsCode, String name, String unit, String frequency, String kind,
            String producer) {

        public static SeriesInfo of(SeriesDefinition definition) {
            return new SeriesInfo(definition.alias(), definition.sgsCode(), definition.name(),
                    definition.unit().label(), definition.frequency().name(), definition.kind().name(),
                    definition.producer());
        }
    }

    public record Summary(Observation first, Observation last, Observation min, Observation max) {
    }

    public record SeriesResult(SeriesInfo series, LocalDate startDate, LocalDate endDate, int count,
            Summary summary, List<Observation> observations, List<String> notes, Citation citation) {
    }

    public record LatestResult(SeriesInfo series, LocalDate asOf, int count, Observation latest,
            List<Observation> observations, List<String> notes, Citation citation) {
    }

    public record MonthlyValue(String month, BigDecimal value) {
    }

    public record AccumulatedChangeResult(SeriesInfo series, String startMonth, String endMonth, int months,
            BigDecimal accumulatedPercent, BigDecimal factor, String method, List<MonthlyValue> monthlyValues,
            Citation citation) {
    }

    public record Leg(SeriesInfo series, BigDecimal accumulatedPercent, BigDecimal factor) {
    }

    public record RealReturnResult(String startMonth, String endMonth, int months, Leg nominal, Leg inflation,
            BigDecimal realReturnPercent, String method, List<Citation> citations) {
    }

    public record ExpectationsResult(String indicator, String focusName, String unit, String meaning,
            int referenceYear, String basis, FocusSurvey latest, List<FocusSurvey> surveys, Citation citation) {
    }

    public record FocusIndicatorInfo(String indicator, String focusName, String unit, String meaning) {
    }

    public record CatalogResult(List<SeriesDefinition> series, List<FocusIndicatorInfo> focusIndicators,
            List<String> rules) {
    }
}
