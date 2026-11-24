package io.github.gharachide.brazilmacro.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gharachide.brazilmacro.error.ErrorCode;
import io.github.gharachide.brazilmacro.error.ToolException;
import io.github.gharachide.brazilmacro.service.Results.AccumulatedChangeResult;
import io.github.gharachide.brazilmacro.service.Results.LatestResult;
import io.github.gharachide.brazilmacro.service.Results.RealReturnResult;
import io.github.gharachide.brazilmacro.service.Results.SeriesResult;
import io.github.gharachide.brazilmacro.support.BcbFixtures;
import io.github.gharachide.brazilmacro.support.FakeBcbServer.Response;
import io.github.gharachide.brazilmacro.support.TestStack;
import java.time.LocalDate;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MacroDataServiceTest {

    private final TestStack stack = TestStack.start();
    private final MacroDataService service = stack.service;

    @BeforeEach
    void fixtures() {
        stack.server.respond(BcbFixtures.standard());
    }

    @AfterEach
    void stop() {
        stack.close();
    }

    private static void assertError(ThrowingCallable call, ErrorCode code, String messagePart) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(ToolException.class, e -> assertThat(e.code()).isEqualTo(code))
                .hasMessageContaining(messagePart);
    }

    @Test
    void getSeriesReturnsObservationsSummaryAndCitation() {
        SeriesResult result = service.getSeries("IPCA", "2023-01-01", "2023-12-31");

        assertThat(result.count()).isEqualTo(12);
        assertThat(result.summary().max().value()).isEqualByComparingTo("0.84");
        assertThat(result.summary().min().date()).isEqualTo(LocalDate.of(2023, 6, 1));
        assertThat(result.citation().seriesCode()).isEqualTo(433);
        assertThat(result.citation().requests()).singleElement()
                .satisfies(r -> assertThat(r.url()).contains("bcdata.sgs.433").contains("dataInicial=01/01/2023"));
        assertThat(result.citation().citeAs()).contains("SGS series 433").contains("retrieved 2024-01-20T15:00:00Z");
        assertThat(result.citation().stale()).isFalse();
    }

    @Test
    void longMonthlyRangesAreFetchedInTenYearWindows() {
        SeriesResult result = service.getSeries("IPCA", "1990-01-01", "2023-12-31");

        assertThat(result.count()).isEqualTo(24);
        assertThat(result.citation().requests()).hasSize(4);
        assertThat(stack.server.requests()).hasSize(4);
    }

    @Test
    void dailyRangesAreLimitedToTenYears() {
        assertError(() -> service.getSeries("USD_BRL", "2014-01-01", "2024-01-01"), ErrorCode.RANGE_TOO_LARGE,
                "10 years");
        assertThat(stack.server.requests()).as("rejected before calling the BCB").isEmpty();
    }

    @Test
    void validatesDatesStrictly() {
        assertError(() -> service.getSeries("IPCA", "2023-02-30", "2023-03-31"), ErrorCode.INVALID_ARGUMENT,
                "valid ISO date");
        assertError(() -> service.getSeries("IPCA", "2023-12-01", "2023-01-01"), ErrorCode.INVALID_ARGUMENT,
                "before startDate");
        assertError(() -> service.getSeries("IPCA", "2025-01-01", "2025-12-31"), ErrorCode.INVALID_ARGUMENT,
                "in the future");
        assertError(() -> service.getSeries(null, "2023-01-01", "2023-12-31"), ErrorCode.INVALID_ARGUMENT,
                "series is required");
    }

    @Test
    void emptyRangeIsNoData() {
        assertError(() -> service.getSeries("IPCA", "2010-01-01", "2010-12-31"), ErrorCode.NO_DATA, "433");
    }

    @Test
    void selicTargetNoteExplainsFutureDatedValues() {
        SeriesResult result = service.getSeries("SELIC_TARGET", "2024-01-01", "2024-02-29");

        assertThat(result.observations().getLast().date()).isAfter(LocalDate.of(2024, 1, 20));
        assertThat(result.notes()).anySatisfy(n -> assertThat(n).contains("not forecasts"));
    }

    @Test
    void latestOfADailySeriesIgnoresFutureDates() {
        LatestResult result = service.getLatest("SELIC_TARGET", 3);

        assertThat(result.observations()).extracting(o -> o.date().toString())
                .containsExactly("2024-01-18", "2024-01-19", "2024-01-20");
        assertThat(result.latest().value()).isEqualByComparingTo("11.75");
        assertThat(stack.server.requests().getFirst().query())
                .containsEntry("dataInicial", "21/11/2023").containsEntry("dataFinal", "20/01/2024");
    }

    @Test
    void latestOfAMonthlySeriesUsesTheUltimosEndpoint() {
        LatestResult result = service.getLatest("IPCA_12M", null);

        assertThat(result.count()).isEqualTo(1);
        assertThat(result.latest().date()).isEqualTo(LocalDate.of(2023, 12, 1));
        assertThat(result.latest().value()).isEqualByComparingTo("4.62");
        assertThat(stack.server.requests().getFirst().path()).endsWith("/bcdata.sgs.13522/dados/ultimos/2");
    }

    @Test
    void latestCountIsBounded() {
        assertError(() -> service.getLatest("IPCA", 21), ErrorCode.INVALID_ARGUMENT, "between 1 and 20");
    }

    @Test
    void accumulatedChangeCompoundsEveryMonth() {
        AccumulatedChangeResult result = service.accumulatedChange("IPCA", "2023-01", "2023-12");

        assertThat(result.months()).isEqualTo(12);
        assertThat(result.accumulatedPercent()).isEqualByComparingTo("4.6211");
        assertThat(result.factor()).isEqualByComparingTo("1.0462111393");
        assertThat(result.monthlyValues()).hasSize(12).first()
                .satisfies(v -> assertThat(v.month()).isEqualTo("2023-01"));
        assertThat(result.method()).contains("34 significant digits");
    }

    @Test
    void refusesToCompoundAnnualOrAccumulatedSeries() {
        assertError(() -> service.accumulatedChange("IPCA_12M", "2023-01", "2023-12"), ErrorCode.INVALID_ARGUMENT,
                "cannot be compounded");
        assertError(() -> service.accumulatedChange("SELIC_TARGET", "2023-01", "2023-12"), ErrorCode.INVALID_ARGUMENT,
                "% a.a.");
        assertThat(stack.server.requests()).isEmpty();
    }

    @Test
    void refusesToCompoundAPeriodWithMissingMonths() {
        assertThatThrownBy(() -> service.accumulatedChange("INPC", "2023-01", "2023-12"))
                .isInstanceOfSatisfying(ToolException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INCOMPLETE_DATA);
                    assertThat(e.details()).containsEntry("missingMonths", List.of("2023-11", "2023-12"))
                            .containsEntry("latestAvailableMonth", "2023-10");
                })
                .hasMessageContaining("nothing was computed");
    }

    @Test
    void refusesFutureMonths() {
        assertError(() -> service.accumulatedChange("IPCA", "2023-01", "2024-02"), ErrorCode.INVALID_ARGUMENT,
                "in the future");
    }

    @Test
    void refusesToCompoundTheMonthInProgress() {
        // The SGS already has a CDI value for January 2024, but it is a running total.
        assertError(() -> service.accumulatedChange("CDI_MONTHLY", "2023-12", "2024-01"), ErrorCode.INVALID_ARGUMENT,
                "still in progress");
    }

    @Test
    void getSeriesFlagsTheMonthInProgressAsPartial() {
        SeriesResult result = service.getSeries("CDI_MONTHLY", "2023-11-01", "2024-01-31");

        assertThat(result.observations().getLast().date()).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(result.observations().getLast().partial()).isTrue();
        assertThat(result.observations().getFirst().partial()).isNull();
        assertThat(result.summary().min().value()).as("partial values are not ranked").isEqualByComparingTo("0.89");
        assertThat(result.notes()).anySatisfy(n -> assertThat(n).contains("2024-01").contains("partial"));
    }

    @Test
    void latestLeavesOutTheMonthInProgress() {
        LatestResult result = service.getLatest("CDI_MONTHLY", 1);

        assertThat(result.latest().date()).isEqualTo(LocalDate.of(2023, 12, 1));
        assertThat(result.latest().value()).isEqualByComparingTo("0.89");
        assertThat(result.notes()).anySatisfy(n -> assertThat(n).contains("left out"));
    }

    @Test
    void realReturnCombinesBothLegsByFisher() {
        RealReturnResult result = service.realReturn("CDI_MONTHLY", "IPCA", "2023-01", "2023-12");

        assertThat(result.nominal().accumulatedPercent()).isEqualByComparingTo("13.0282");
        assertThat(result.inflation().accumulatedPercent()).isEqualByComparingTo("4.6211");
        assertThat(result.realReturnPercent()).isEqualByComparingTo("8.0358");
        assertThat(result.citations()).extracting(Results.Citation::seriesCode).containsExactly(4391, 433);
    }

    @Test
    void realReturnRequiresARateAndAPriceIndex() {
        assertError(() -> service.realReturn("IPCA", "CDI_MONTHLY", "2023-01", "2023-12"), ErrorCode.INVALID_ARGUMENT,
                "nominalSeries must be a monthly interest rate");
        assertError(() -> service.realReturn("CDI_MONTHLY", "IPCA_12M", "2023-01", "2023-12"),
                ErrorCode.INVALID_ARGUMENT, "inflationSeries must be a monthly price index");
    }

    @Test
    void marketExpectationsReturnLatestSurveyFirst() {
        Results.ExpectationsResult result = service.marketExpectations("ipca", 2024, 2);

        assertThat(result.surveys()).hasSize(2);
        assertThat(result.latest().surveyDate()).isEqualTo(LocalDate.of(2024, 1, 19));
        assertThat(result.latest().median()).isEqualByComparingTo("3.81");
        assertThat(result.basis()).contains("30 days");
        assertThat(result.citation().citeAs()).contains("Focus").contains("IPCA for 2024");
    }

    @Test
    void marketExpectationsWithoutRowsIsNoData() {
        assertError(() -> service.marketExpectations("SELIC", 2024, 1), ErrorCode.NO_DATA, "SELIC");
    }

    @Test
    void marketExpectationsValidatesTheYear() {
        assertError(() -> service.marketExpectations("IPCA", 2035, 1), ErrorCode.INVALID_ARGUMENT, "referenceYear");
    }

    @Test
    void upstreamOutageBecomesARetryableError() {
        stack.server.respond(r -> Response.status(502));

        assertThatThrownBy(() -> service.getLatest("IPCA", 1))
                .isInstanceOfSatisfying(ToolException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
                    assertThat(e.code().retryable()).isTrue();
                    assertThat(e.code().guidance()).contains("Do not estimate");
                });
    }
}
