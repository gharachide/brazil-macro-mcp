package io.github.gharachide.brazilmacro;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gharachide.brazilmacro.catalog.FocusIndicator;
import io.github.gharachide.brazilmacro.catalog.SeriesCatalog;
import io.github.gharachide.brazilmacro.catalog.SeriesDefinition;
import io.github.gharachide.brazilmacro.service.MacroDataService;
import io.github.gharachide.brazilmacro.service.Results;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Contract test against the real Banco Central APIs. Off by default, because the build must not
 * depend on a third-party service; run it with
 * {@code BCB_LIVE=true ./mvnw verify -Dit.test=LiveBcbContractIT} or through the scheduled
 * "BCB contract" workflow.
 */
@EnabledIfEnvironmentVariable(named = "BCB_LIVE", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"brazil-macro.audit.enabled=false", "spring.ai.mcp.server.enabled=false"})
class LiveBcbContractIT {

    @Autowired
    MacroDataService service;

    static Stream<String> aliases() {
        return SeriesCatalog.all().stream().map(SeriesDefinition::alias);
    }

    @ParameterizedTest
    @MethodSource("aliases")
    void everyCatalogSeriesHasRecentData(String alias) {
        Results.LatestResult latest = service.getLatest(alias, 3);

        assertThat(latest.observations()).isNotEmpty();
        assertThat(latest.latest().date()).isAfter(LocalDate.now(MacroDataService.BRAZIL).minusMonths(4));
        assertThat(latest.citation().requests())
                .allSatisfy(r -> assertThat(r.url()).startsWith("https://api.bcb.gov.br/"));
    }

    @Test
    void ipca2023CompoundsToTheOfficialFigure() {
        Results.AccumulatedChangeResult result = service.accumulatedChange("IPCA", "2023-01", "2023-12");

        assertThat(result.months()).isEqualTo(12);
        assertThat(result.accumulatedPercent().setScale(2, RoundingMode.HALF_EVEN)).isEqualByComparingTo("4.62");
    }

    @Test
    void realReturnOf2024HasEveryMonthOnBothLegs() {
        Results.RealReturnResult result = service.realReturn("CDI_MONTHLY", "IPCA", "2024-01", "2024-12");

        assertThat(result.months()).isEqualTo(12);
        assertThat(result.realReturnPercent()).isBetween(new BigDecimal("-10"), new BigDecimal("20"));
    }

    @ParameterizedTest
    @EnumSource(FocusIndicator.class)
    void focusSurveyAnswersForEveryIndicator(FocusIndicator indicator) {
        int nextYear = LocalDate.now(MacroDataService.BRAZIL).getYear() + 1;

        Results.ExpectationsResult result = service.marketExpectations(indicator.name(), nextYear, 2);

        assertThat(result.surveys()).isNotEmpty();
        assertThat(result.latest().median()).isNotNull();
        assertThat(result.latest().surveyDate()).isAfter(LocalDate.now(MacroDataService.BRAZIL).minusDays(30));
    }
}
