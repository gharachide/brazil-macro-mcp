package io.github.gharachide.brazilmacro.support;

import io.github.gharachide.brazilmacro.support.FakeBcbServer.Request;
import io.github.gharachide.brazilmacro.support.FakeBcbServer.Response;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Emulates the SGS and Focus APIs over in-memory data: date filters, {@code ultimos/N}, the
 * 10-year limit for daily series (HTTP 400) and HTTP 404 when a range has no data.
 */
public final class BcbFixtures implements Function<Request, Response> {

    private static final Pattern SGS_LATEST = Pattern.compile("/dados/serie/bcdata\\.sgs\\.(\\d+)/dados/ultimos/(\\d+)");
    private static final Pattern SGS_RANGE = Pattern.compile("/dados/serie/bcdata\\.sgs\\.(\\d+)/dados");
    private static final Pattern FOCUS_FILTER = Pattern.compile(
            "Indicador eq '(.+?)' and DataReferencia eq '(\\d{4})' and baseCalculo eq 0");
    private static final DateTimeFormatter BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public record FocusRow(String indicador, LocalDate data, int referencia, String mediana, String media,
            int respondentes) {
    }

    private final Map<Integer, NavigableMap<LocalDate, String>> series = new HashMap<>();
    private final Set<Integer> dailyCodes = new HashSet<>();
    private final List<FocusRow> focus = new ArrayList<>();

    /** Fixture used by the end-to-end tests. "Today" is 2024-01-20. Values are test data. */
    public static BcbFixtures standard() {
        BcbFixtures f = new BcbFixtures();
        f.monthly(433, YearMonth.of(2022, 1),
                "0.54", "1.01", "1.62", "1.06", "0.47", "0.67", "-0.68", "-0.36", "-0.29", "0.59", "0.41", "0.62",
                "0.53", "0.84", "0.71", "0.61", "0.23", "-0.08", "0.12", "0.23", "0.26", "0.24", "0.28", "0.56");
        f.monthly(4391, YearMonth.of(2022, 1),
                "0.73", "0.76", "0.93", "0.83", "1.03", "1.02", "1.03", "1.17", "1.07", "1.02", "1.02", "1.12",
                "1.12", "0.92", "1.17", "0.92", "1.12", "1.07", "1.07", "1.14", "0.97", "1.00", "0.92", "0.89");
        // January 2024 is the month in progress on 2024-01-20: the SGS already shows a running total.
        f.monthly(4391, YearMonth.of(2024, 1), "0.62");
        // INPC is published only up to October 2023 in this fixture.
        f.monthly(188, YearMonth.of(2023, 1), "0.46", "0.77", "0.64", "0.53", "0.36", "-0.10", "-0.09", "0.20",
                "0.11", "0.12");
        f.monthly(13522, YearMonth.of(2023, 10), "4.82", "4.68", "4.62");
        for (LocalDate d = LocalDate.of(2023, 12, 1); !d.isAfter(LocalDate.of(2024, 2, 29)); d = d.plusDays(1)) {
            f.daily(432, d, d.isBefore(LocalDate.of(2023, 12, 14)) ? "12.25" : "11.75");
        }
        f.daily(1, LocalDate.of(2024, 1, 16), "4.9187");
        f.daily(1, LocalDate.of(2024, 1, 17), "4.9381");
        f.daily(1, LocalDate.of(2024, 1, 18), "4.9230");
        f.daily(1, LocalDate.of(2024, 1, 19), "4.9112");
        f.focus("IPCA", LocalDate.of(2024, 1, 12), 2024, "3.87", "3.90", 98);
        f.focus("IPCA", LocalDate.of(2024, 1, 19), 2024, "3.81", "3.85", 101);
        return f;
    }

    public BcbFixtures monthly(int code, YearMonth first, String... values) {
        NavigableMap<LocalDate, String> data = series.computeIfAbsent(code, c -> new TreeMap<>());
        for (int i = 0; i < values.length; i++) {
            data.put(first.plusMonths(i).atDay(1), values[i]);
        }
        return this;
    }

    public BcbFixtures daily(int code, LocalDate date, String value) {
        dailyCodes.add(code);
        series.computeIfAbsent(code, c -> new TreeMap<>()).put(date, value);
        return this;
    }

    public BcbFixtures focus(String indicador, LocalDate data, int referencia, String mediana, String media,
            int respondentes) {
        focus.add(new FocusRow(indicador, data, referencia, mediana, media, respondentes));
        return this;
    }

    @Override
    public Response apply(Request request) {
        Matcher latest = SGS_LATEST.matcher(request.path());
        if (latest.matches()) {
            NavigableMap<LocalDate, String> data = series.getOrDefault(Integer.parseInt(latest.group(1)), new TreeMap<>());
            int n = Integer.parseInt(latest.group(2));
            if (n > 20) {
                return Response.status(400);
            }
            List<Map.Entry<LocalDate, String>> entries = new ArrayList<>(data.entrySet());
            return sgsJson(entries.subList(Math.max(0, entries.size() - n), entries.size()));
        }
        Matcher range = SGS_RANGE.matcher(request.path());
        if (range.matches()) {
            int code = Integer.parseInt(range.group(1));
            LocalDate start = LocalDate.parse(request.query().get("dataInicial"), BR);
            LocalDate end = LocalDate.parse(request.query().get("dataFinal"), BR);
            if (dailyCodes.contains(code) && !start.plusYears(10).isAfter(end)) {
                return Response.status(400);
            }
            NavigableMap<LocalDate, String> data = series.getOrDefault(code, new TreeMap<>());
            return sgsJson(new ArrayList<>(data.subMap(start, true, end, true).entrySet()));
        }
        if (request.path().endsWith("/ExpectativasMercadoAnuais")) {
            Matcher filter = FOCUS_FILTER.matcher(request.query().getOrDefault("$filter", ""));
            if (!filter.matches()) {
                return Response.status(400);
            }
            int top = Integer.parseInt(request.query().getOrDefault("$top", "100"));
            String rows = focus.stream()
                    .filter(r -> r.indicador().equals(filter.group(1)) && r.referencia() == Integer.parseInt(filter.group(2)))
                    .sorted(Comparator.comparing(FocusRow::data).reversed())
                    .limit(top)
                    .map(r -> "{\"Indicador\":\"" + r.indicador() + "\",\"Data\":\"" + r.data() + "\",\"DataReferencia\":\""
                            + r.referencia() + "\",\"Media\":" + r.media() + ",\"Mediana\":" + r.mediana()
                            + ",\"DesvioPadrao\":0.21,\"Minimo\":3.2,\"Maximo\":4.6,\"numeroRespondentes\":"
                            + r.respondentes() + ",\"baseCalculo\":0}")
                    .collect(Collectors.joining(","));
            return Response.json("{\"@odata.context\":\"fake\",\"value\":[" + rows + "]}");
        }
        return Response.status(404);
    }

    private static Response sgsJson(List<Map.Entry<LocalDate, String>> entries) {
        if (entries.isEmpty()) {
            return Response.status(404);
        }
        return Response.json(entries.stream()
                .map(e -> "{\"data\":\"" + BR.format(e.getKey()) + "\",\"valor\":\"" + e.getValue() + "\"}")
                .collect(Collectors.joining(",", "[", "]")));
    }
}
