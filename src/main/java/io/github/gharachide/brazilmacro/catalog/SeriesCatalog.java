package io.github.gharachide.brazilmacro.catalog;

import static io.github.gharachide.brazilmacro.catalog.Frequency.DAILY;
import static io.github.gharachide.brazilmacro.catalog.Frequency.MONTHLY;

import io.github.gharachide.brazilmacro.error.ToolException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The closed list of series this server serves. Only series whose unit and meaning are known
 * are exposed, so that the server can refuse invalid calculations (for example compounding an
 * annual rate) instead of returning a plausible but wrong number.
 */
public final class SeriesCatalog {

    private static final String MONTH_DATING =
            "Monthly observations are dated on the first day of the reference month.";
    private static final String RUNNING_TOTAL =
            "The SGS also publishes the month in progress as a running total; that value is flagged partial.";

    private static final List<SeriesDefinition> SERIES = List.of(
            new SeriesDefinition("IPCA", 433,
                    "Índice nacional de preços ao consumidor-amplo (IPCA) - variação mensal",
                    SeriesKind.PRICE_INDEX_CHANGE, Unit.PERCENT_PER_MONTH, MONTHLY, "IBGE",
                    "Brazil's official inflation index. " + MONTH_DATING),
            new SeriesDefinition("IPCA_12M", 13522,
                    "IPCA - variação acumulada em 12 meses",
                    SeriesKind.TWELVE_MONTH_CHANGE, Unit.PERCENT_OVER_12_MONTHS, MONTHLY, "IBGE",
                    "Already accumulated over the 12 months ending in the reference month: never chain "
                            + "or sum these values. " + MONTH_DATING),
            new SeriesDefinition("INPC", 188,
                    "Índice nacional de preços ao consumidor (INPC) - variação mensal",
                    SeriesKind.PRICE_INDEX_CHANGE, Unit.PERCENT_PER_MONTH, MONTHLY, "IBGE",
                    "Consumer prices for lower-income households; used to adjust wages and pensions. "
                            + MONTH_DATING),
            new SeriesDefinition("IGPM", 189,
                    "Índice geral de preços do mercado (IGP-M) - variação mensal",
                    SeriesKind.PRICE_INDEX_CHANGE, Unit.PERCENT_PER_MONTH, MONTHLY, "FGV",
                    "General price index often used in rent contracts. " + MONTH_DATING),
            new SeriesDefinition("SELIC_TARGET", 432,
                    "Taxa de juros - Meta Selic definida pelo Copom",
                    SeriesKind.ANNUAL_RATE, Unit.PERCENT_PER_YEAR, DAILY, "BCB (Copom)",
                    "Policy rate target, per year. Published for every calendar day, and dates can extend "
                            + "into the future while the current target is in force. Do not compound."),
            new SeriesDefinition("SELIC_MONTHLY", 4390,
                    "Taxa de juros - Selic acumulada no mês",
                    SeriesKind.MONTHLY_RATE, Unit.PERCENT_PER_MONTH, MONTHLY, "BCB",
                    "Effective Selic accumulated within each month. " + MONTH_DATING + " " + RUNNING_TOTAL),
            new SeriesDefinition("SELIC_ANNUALIZED", 4189,
                    "Taxa de juros - Selic acumulada no mês anualizada base 252",
                    SeriesKind.ANNUAL_RATE, Unit.PERCENT_PER_YEAR, MONTHLY, "BCB",
                    "The effective Selic of each month expressed per year (252 business days). "
                            + "Do not compound. " + MONTH_DATING + " " + RUNNING_TOTAL),
            new SeriesDefinition("CDI_MONTHLY", 4391,
                    "Taxa de juros - CDI acumulada no mês",
                    SeriesKind.MONTHLY_RATE, Unit.PERCENT_PER_MONTH, MONTHLY, "B3",
                    "CDI accumulated within each month; the usual benchmark for Brazilian fixed income. "
                            + MONTH_DATING + " " + RUNNING_TOTAL),
            new SeriesDefinition("USD_BRL", 1,
                    "Taxa de câmbio - Livre - Dólar americano (venda) - diário",
                    SeriesKind.EXCHANGE_RATE, Unit.BRL_PER_USD, DAILY, "BCB",
                    "Selling rate, published on business days."));

    private static final Map<String, SeriesDefinition> BY_ALIAS = SERIES.stream()
            .collect(Collectors.toUnmodifiableMap(SeriesDefinition::alias, Function.identity()));

    private SeriesCatalog() {
    }

    public static List<SeriesDefinition> all() {
        return SERIES;
    }

    /** Looks up an alias, case-insensitively, or fails with the list of valid aliases. */
    public static SeriesDefinition require(String alias) {
        if (alias == null || alias.isBlank()) {
            throw ToolException.invalid("series is required. Valid values: " + aliases() + ".");
        }
        SeriesDefinition definition = BY_ALIAS.get(alias.trim().toUpperCase(Locale.ROOT));
        if (definition == null) {
            throw ToolException.invalid("Unknown series '" + alias + "'. Valid values: " + aliases()
                    + ". Call list_series for units and meaning.");
        }
        return definition;
    }

    public static String aliases() {
        return SERIES.stream().map(SeriesDefinition::alias).collect(Collectors.joining(", "));
    }
}
