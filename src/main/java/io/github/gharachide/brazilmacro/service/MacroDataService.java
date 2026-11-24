package io.github.gharachide.brazilmacro.service;

import io.github.gharachide.brazilmacro.calc.RateMath;
import io.github.gharachide.brazilmacro.catalog.FocusIndicator;
import io.github.gharachide.brazilmacro.catalog.Frequency;
import io.github.gharachide.brazilmacro.catalog.SeriesCatalog;
import io.github.gharachide.brazilmacro.catalog.SeriesDefinition;
import io.github.gharachide.brazilmacro.catalog.SeriesKind;
import io.github.gharachide.brazilmacro.config.BrazilMacroProperties;
import io.github.gharachide.brazilmacro.domain.Observation;
import io.github.gharachide.brazilmacro.error.ErrorCode;
import io.github.gharachide.brazilmacro.error.ToolException;
import io.github.gharachide.brazilmacro.service.Results.AccumulatedChangeResult;
import io.github.gharachide.brazilmacro.service.Results.CatalogResult;
import io.github.gharachide.brazilmacro.service.Results.Citation;
import io.github.gharachide.brazilmacro.service.Results.ExpectationsResult;
import io.github.gharachide.brazilmacro.service.Results.FocusIndicatorInfo;
import io.github.gharachide.brazilmacro.service.Results.LatestResult;
import io.github.gharachide.brazilmacro.service.Results.Leg;
import io.github.gharachide.brazilmacro.service.Results.MonthlyValue;
import io.github.gharachide.brazilmacro.service.Results.RealReturnResult;
import io.github.gharachide.brazilmacro.service.Results.SeriesInfo;
import io.github.gharachide.brazilmacro.service.Results.SeriesResult;
import io.github.gharachide.brazilmacro.service.Results.Summary;
import io.github.gharachide.brazilmacro.upstream.CacheStatus;
import io.github.gharachide.brazilmacro.upstream.FocusClient;
import io.github.gharachide.brazilmacro.upstream.SgsClient;
import io.github.gharachide.brazilmacro.upstream.SourceRequest;
import io.github.gharachide.brazilmacro.upstream.UpstreamException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * Validates requests, fetches official data and performs the calculations. Every successful
 * result carries a citation; every failure is a {@link ToolException} with a code from a closed
 * set, so the agent never receives a partial or guessed number.
 */
@Service
public class MacroDataService {

    public static final ZoneId BRAZIL = ZoneId.of("America/Sao_Paulo");

    static final String PROVIDER = "Banco Central do Brasil";
    static final String TERMS = "Open data published by Banco Central do Brasil (dadosabertos.bcb.gov.br); "
            + "most datasets are licensed under ODbL 1.0.";
    static final String FOCUS_PAGE = "https://dadosabertos.bcb.gov.br/dataset/expectativas-mercado";
    static final String FOCUS_BASIS = "Statistics of the projections submitted in the 30 days up to each survey "
            + "date (Focus baseCalculo 0), the basis of the weekly Focus report.";
    static final String COMPOUNDING_METHOD =
            "Product of (1 + monthly rate / 100) over every month in the period, minus 1, in exact decimal "
                    + "arithmetic (34 significant digits); rounded to 4 decimal places for display.";
    static final String FISHER_METHOD = "Fisher relation: (1 + nominal) / (1 + inflation) - 1, from the "
            + "compounded factors of both series over the same months.";

    /** The SGS serves at most 10 years per request, so wider ranges are fetched in windows. */
    private static final int SGS_WINDOW_YEARS = 10;
    /** Calendar days scanned to find the latest observations of a daily series. */
    private static final int DAILY_LOOKBACK_DAYS = 60;

    private final SgsClient sgs;
    private final FocusClient focus;
    private final BrazilMacroProperties.Limits limits;
    private final Clock clock;

    public MacroDataService(SgsClient sgs, FocusClient focus, BrazilMacroProperties properties, Clock clock) {
        this.sgs = sgs;
        this.focus = focus;
        this.limits = properties.limits();
        this.clock = clock;
    }

    public CatalogResult catalog() {
        List<FocusIndicatorInfo> indicators = Arrays.stream(FocusIndicator.values())
                .map(i -> new FocusIndicatorInfo(i.name(), i.apiName(), i.unit(), i.meaning()))
                .toList();
        return new CatalogResult(SeriesCatalog.all(), indicators, List.of(
                "Quote numbers exactly as returned and cite them (citation.citeAs or citation.requests).",
                "If a tool returns an error, do not estimate or recall values; say which data is unavailable.",
                "Use accumulated_change and real_return for compounding instead of doing the arithmetic yourself.",
                "Monthly observations are dated on the first day of the reference month."));
    }

    public SeriesResult getSeries(String alias, String startDate, String endDate) {
        SeriesDefinition definition = SeriesCatalog.require(alias);
        LocalDate start = Args.date("startDate", startDate);
        LocalDate end = Args.date("endDate", endDate);
        if (end.isBefore(start)) {
            throw ToolException.invalid("endDate (" + end + ") is before startDate (" + start + ").");
        }
        LocalDate today = today();
        if (start.isAfter(today)) {
            throw ToolException.invalid("startDate (" + start + ") is in the future; today is " + today + ".");
        }
        int maxYears = definition.frequency() == Frequency.DAILY ? limits.maxYearsDaily() : limits.maxYearsMonthly();
        if (!start.plusYears(maxYears).isAfter(end)) {
            throw new ToolException(ErrorCode.RANGE_TOO_LARGE, "The range " + start + " to " + end + " is wider than "
                    + maxYears + " years, the limit for " + definition.frequency().name().toLowerCase()
                    + " series.", Map.of("maxYears", maxYears));
        }
        Fetch fetch = fetchRange(definition, start, end);
        List<Observation> observations = flagMonthInProgress(definition, fetch.observations(), today);
        if (observations.isEmpty()) {
            throw noData(definition, "between " + start + " and " + end);
        }
        if (observations.size() > limits.maxObservations()) {
            throw new ToolException(ErrorCode.RANGE_TOO_LARGE, "The range holds " + observations.size()
                    + " observations; at most " + limits.maxObservations() + " are returned per call.",
                    Map.of("maxObservations", limits.maxObservations()));
        }
        List<String> notes = new ArrayList<>(List.of(definition.notes()));
        if ("SELIC_TARGET".equals(definition.alias()) && observations.getLast().date().isAfter(today)) {
            notes.add("Observations dated after " + today + " repeat the target currently in force, which the "
                    + "SGS publishes until the next Copom meeting. They are not forecasts.");
        }
        if (observations.getLast().inProgress()) {
            notes.add(monthInProgressNote(YearMonth.from(today)));
        }
        return new SeriesResult(SeriesInfo.of(definition), start, end, observations.size(), summarize(observations),
                observations, List.copyOf(notes), sgsCitation(definition, fetch.sources()));
    }

    public LatestResult getLatest(String alias, Integer count) {
        SeriesDefinition definition = SeriesCatalog.require(alias);
        int n = Args.intInRange("count", count, 1, 1, limits.maxLatest());
        LocalDate today = today();
        Fetch fetch;
        if (definition.frequency() == Frequency.MONTHLY) {
            // One extra value, in case the newest one is the month in progress and is dropped below.
            int request = Math.min(n + 1, limits.maxLatest());
            SgsClient.Response response = call(definition, () -> sgs.latest(definition.sgsCode(), request));
            fetch = new Fetch(response.observations(), List.of(response.source()));
        } else {
            // Daily series are read through a date range: the SGS requires date filters for them, and
            // the Selic target is published ahead for future days.
            fetch = fetchRange(definition, today.minusDays(DAILY_LOOKBACK_DAYS), today);
        }
        List<Observation> upToToday = flagMonthInProgress(definition, fetch.observations(), today).stream()
                .filter(o -> !o.date().isAfter(today))
                .toList();
        List<Observation> complete = upToToday.stream().filter(o -> !o.inProgress()).toList();
        if (complete.isEmpty()) {
            throw noData(definition, "up to " + today);
        }
        List<Observation> latest = complete.subList(Math.max(0, complete.size() - n), complete.size());
        List<String> notes = new ArrayList<>(List.of(definition.notes()));
        if (complete.size() < upToToday.size()) {
            notes.add("The value of the month in progress (" + YearMonth.from(today) + ") is partial and was left "
                    + "out; get_series returns it, flagged partial.");
        }
        return new LatestResult(SeriesInfo.of(definition), today, latest.size(), latest.getLast(), List.copyOf(latest),
                List.copyOf(notes), sgsCitation(definition, fetch.sources()));
    }

    public AccumulatedChangeResult accumulatedChange(String alias, String startMonth, String endMonth) {
        SeriesDefinition definition = SeriesCatalog.require(alias);
        requireCompoundable(definition, "series");
        Period period = period(startMonth, endMonth);
        Compounded compounded = compound(definition, period);
        return new AccumulatedChangeResult(SeriesInfo.of(definition), period.start().toString(),
                period.end().toString(), period.months(), RateMath.percentForDisplay(compounded.percent()),
                RateMath.factorForDisplay(compounded.factor()), COMPOUNDING_METHOD, compounded.monthlyValues(),
                compounded.citation());
    }

    public RealReturnResult realReturn(String nominalAlias, String inflationAlias, String startMonth,
            String endMonth) {
        SeriesDefinition nominal = SeriesCatalog.require(nominalAlias);
        SeriesDefinition inflation = SeriesCatalog.require(inflationAlias);
        if (nominal.kind() != SeriesKind.MONTHLY_RATE) {
            throw ToolException.invalid("nominalSeries must be a monthly interest rate (SELIC_MONTHLY or CDI_MONTHLY), "
                    + "got " + nominal.alias() + ".");
        }
        if (inflation.kind() != SeriesKind.PRICE_INDEX_CHANGE) {
            throw ToolException.invalid("inflationSeries must be a monthly price index (IPCA, INPC or IGPM), got "
                    + inflation.alias() + ".");
        }
        Period period = period(startMonth, endMonth);
        Compounded n = compound(nominal, period);
        Compounded i = compound(inflation, period);
        BigDecimal real = RateMath.realReturnPercent(n.factor(), i.factor());
        return new RealReturnResult(period.start().toString(), period.end().toString(), period.months(),
                leg(nominal, n), leg(inflation, i), RateMath.percentForDisplay(real), FISHER_METHOD,
                List.of(n.citation(), i.citation()));
    }

    public ExpectationsResult marketExpectations(String indicatorName, Integer referenceYear, Integer surveys) {
        FocusIndicator indicator = FocusIndicator.require(indicatorName);
        int currentYear = today().getYear();
        int year = Args.intInRange("referenceYear", referenceYear, currentYear, 2000, currentYear + 5);
        int count = Args.intInRange("surveys", surveys, 1, 1, 12);
        FocusClient.Response response;
        try {
            response = focus.annual(indicator, year, count);
        } catch (UpstreamException e) {
            throw translate(e, "Focus statistics for " + indicator.name() + " in " + year);
        }
        if (response.surveys().isEmpty()) {
            throw new ToolException(ErrorCode.NO_DATA, "The Focus survey has no statistics for " + indicator.name()
                    + " with reference year " + year + ".", Map.of("url", response.source().url()));
        }
        boolean stale = response.source().stale();
        Citation citation = new Citation(PROVIDER, "Focus - Expectativas de Mercado Anuais", null,
                indicator.apiName() + ", reference year " + year, FOCUS_PAGE, TERMS, List.of(response.source()), stale,
                PROVIDER + ", Focus market survey (Expectativas de Mercado Anuais), " + indicator.apiName() + " for "
                        + year + ", retrieved " + response.source().retrievedAt() + ": " + response.source().url()
                        + staleSuffix(stale));
        return new ExpectationsResult(indicator.name(), indicator.apiName(), indicator.unit(), indicator.meaning(),
                year, FOCUS_BASIS, response.surveys().getFirst(), response.surveys(), citation);
    }

    // ---------------------------------------------------------------------------------------------

    private record Fetch(List<Observation> observations, List<SourceRequest> sources) {
    }

    private record Period(YearMonth start, YearMonth end, int months) {
    }

    private record Compounded(BigDecimal factor, BigDecimal percent, List<MonthlyValue> monthlyValues,
            Citation citation) {
    }

    private Period period(String startMonth, String endMonth) {
        YearMonth start = Args.month("startMonth", startMonth);
        YearMonth end = Args.month("endMonth", endMonth);
        if (end.isBefore(start)) {
            throw ToolException.invalid("endMonth (" + end + ") is before startMonth (" + start + ").");
        }
        YearMonth current = YearMonth.from(today());
        if (end.isAfter(current)) {
            throw ToolException.invalid("endMonth (" + end + ") is in the future; the current month is " + current + ".");
        }
        if (end.equals(current)) {
            throw ToolException.invalid("endMonth (" + end + ") is still in progress, so its value is not final. "
                    + "The latest month that can be compounded is " + current.minusMonths(1) + ".");
        }
        int months = (int) start.until(end, ChronoUnit.MONTHS) + 1;
        int maxMonths = limits.maxYearsMonthly() * 12;
        if (months > maxMonths) {
            throw new ToolException(ErrorCode.RANGE_TOO_LARGE, "The period has " + months + " months; at most "
                    + maxMonths + " are supported.", Map.of("maxMonths", maxMonths));
        }
        return new Period(start, end, months);
    }

    private Compounded compound(SeriesDefinition definition, Period period) {
        Fetch fetch = fetchRange(definition, period.start().atDay(1), period.end().atEndOfMonth());
        Map<YearMonth, BigDecimal> byMonth = new TreeMap<>();
        for (Observation observation : fetch.observations()) {
            YearMonth month = YearMonth.from(observation.date());
            if (byMonth.putIfAbsent(month, observation.value()) != null) {
                throw new ToolException(ErrorCode.UPSTREAM_UNAVAILABLE, "The BCB returned two values for "
                        + definition.alias() + " in " + month + "; nothing was computed.");
            }
        }
        List<String> missing = new ArrayList<>();
        List<MonthlyValue> values = new ArrayList<>(period.months());
        for (YearMonth month = period.start(); !month.isAfter(period.end()); month = month.plusMonths(1)) {
            BigDecimal value = byMonth.get(month);
            if (value == null) {
                missing.add(month.toString());
            } else {
                values.add(new MonthlyValue(month.toString(), value));
            }
        }
        if (!missing.isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("missingMonths", missing);
            byMonth.keySet().stream().reduce((a, b) -> b).ifPresent(m -> details.put("latestAvailableMonth", m.toString()));
            throw new ToolException(ErrorCode.INCOMPLETE_DATA, "No official value of " + definition.alias()
                    + " for " + String.join(", ", abbreviate(missing)) + ". The BCB may not have published "
                    + "these months yet; nothing was computed.", details);
        }
        BigDecimal factor = RateMath.compoundFactor(values.stream().map(MonthlyValue::value).toList());
        return new Compounded(factor, RateMath.factorToPercent(factor), List.copyOf(values),
                sgsCitation(definition, fetch.sources()));
    }

    /** Fetches a date range in windows the SGS accepts and merges them in date order. */
    private Fetch fetchRange(SeriesDefinition definition, LocalDate start, LocalDate end) {
        TreeMap<LocalDate, Observation> merged = new TreeMap<>();
        List<SourceRequest> sources = new ArrayList<>();
        LocalDate windowStart = start;
        while (!windowStart.isAfter(end)) {
            LocalDate windowEnd = min(windowStart.plusYears(SGS_WINDOW_YEARS).minusDays(1), end);
            LocalDate from = windowStart;
            try {
                SgsClient.Response response = sgs.range(definition.sgsCode(), from, windowEnd);
                for (Observation observation : response.observations()) {
                    if (!observation.date().isBefore(from) && !observation.date().isAfter(windowEnd)) {
                        merged.putIfAbsent(observation.date(), observation);
                    }
                }
                sources.add(response.source());
            } catch (UpstreamException e) {
                if (e.kind() != UpstreamException.Kind.NOT_FOUND) {
                    throw translate(e, definition.alias());
                }
                // No data in this window (for example before the series starts): keep the request as provenance.
                sources.add(new SourceRequest(e.uri().toString(), clock.instant(), CacheStatus.MISS));
            }
            windowStart = windowEnd.plusDays(1);
        }
        return new Fetch(List.copyOf(merged.values()), List.copyOf(sources));
    }

    /**
     * The SGS publishes the Selic and CDI "accumulated in the month" for the current month too,
     * as a running total. A monthly observation of the month in progress is therefore flagged
     * partial, so that it is never read, or compounded, as a final value.
     */
    private static List<Observation> flagMonthInProgress(SeriesDefinition definition, List<Observation> observations,
            LocalDate today) {
        if (definition.frequency() != Frequency.MONTHLY) {
            return observations;
        }
        YearMonth current = YearMonth.from(today);
        return observations.stream()
                .map(o -> YearMonth.from(o.date()).equals(current) ? o.asPartial() : o)
                .toList();
    }

    private static String monthInProgressNote(YearMonth current) {
        return "The observation for " + current + " covers the month so far (partial): it is not final and must "
                + "not be compared with complete months.";
    }

    private <T> T call(SeriesDefinition definition, Supplier<T> request) {
        try {
            return request.get();
        } catch (UpstreamException e) {
            throw translate(e, definition.alias());
        }
    }

    private static ToolException translate(UpstreamException e, String what) {
        Map<String, Object> details = Map.of("url", String.valueOf(e.uri()));
        return switch (e.kind()) {
            case NOT_FOUND -> new ToolException(ErrorCode.NO_DATA, "The BCB has no data for " + what + ".", details, e);
            case REJECTED -> new ToolException(ErrorCode.INVALID_ARGUMENT, "The BCB rejected the request for " + what
                    + " (HTTP " + e.status() + "). Check the series and the dates.", details, e);
            case MALFORMED -> new ToolException(ErrorCode.UPSTREAM_UNAVAILABLE, e.getMessage()
                    + ". Nothing from this response was used.", details, e);
            case UNAVAILABLE -> new ToolException(ErrorCode.UPSTREAM_UNAVAILABLE, e.getMessage() + ".", details, e);
        };
    }

    private static ToolException noData(SeriesDefinition definition, String when) {
        return new ToolException(ErrorCode.NO_DATA, "The BCB has published no observation of " + definition.alias()
                + " (SGS " + definition.sgsCode() + ") " + when + ".");
    }

    private static void requireCompoundable(SeriesDefinition definition, String argument) {
        if (!definition.compoundable()) {
            throw ToolException.invalid(argument + " " + definition.alias() + " is in " + definition.unit().label()
                    + " (" + definition.kind().name() + ") and cannot be compounded month by month. Use one of: "
                    + compoundableAliases() + ".");
        }
    }

    private static String compoundableAliases() {
        return String.join(", ", SeriesCatalog.all().stream()
                .filter(SeriesDefinition::compoundable).map(SeriesDefinition::alias).toList());
    }

    private static Citation sgsCitation(SeriesDefinition definition, List<SourceRequest> sources) {
        boolean stale = sources.stream().anyMatch(SourceRequest::stale);
        Instant retrievedAt = sources.stream().map(SourceRequest::retrievedAt).min(Comparator.naturalOrder())
                .orElse(null);
        String citeAs = PROVIDER + ", SGS series " + definition.sgsCode() + " (" + definition.name() + "), retrieved "
                + retrievedAt + ": " + definition.seriesPage() + staleSuffix(stale);
        return new Citation(PROVIDER, "SGS - Sistema Gerenciador de Séries Temporais", definition.sgsCode(),
                definition.name(), definition.seriesPage(), TERMS, sources, stale, citeAs);
    }

    private static String staleSuffix(boolean stale) {
        return stale ? " (stale copy, served while the BCB API was unavailable)" : "";
    }

    private static Leg leg(SeriesDefinition definition, Compounded compounded) {
        return new Leg(SeriesInfo.of(definition), RateMath.percentForDisplay(compounded.percent()),
                RateMath.factorForDisplay(compounded.factor()));
    }

    /** First and last as published; min and max over final values only, when there are any. */
    private static Summary summarize(List<Observation> observations) {
        Comparator<Observation> byValue = Comparator.comparing(Observation::value);
        List<Observation> complete = observations.stream().filter(o -> !o.inProgress()).toList();
        List<Observation> ranked = complete.isEmpty() ? observations : complete;
        return new Summary(observations.getFirst(), observations.getLast(),
                ranked.stream().min(byValue).orElseThrow(), ranked.stream().max(byValue).orElseThrow());
    }

    private static List<String> abbreviate(List<String> months) {
        return months.size() <= 6 ? months : List.of(months.getFirst(), "... (" + (months.size() - 2) + " more)",
                months.getLast());
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(BRAZIL));
    }
}
