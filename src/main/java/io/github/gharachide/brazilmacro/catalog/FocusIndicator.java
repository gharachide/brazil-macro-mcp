package io.github.gharachide.brazilmacro.catalog;

import io.github.gharachide.brazilmacro.error.ToolException;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Indicators of the Focus survey (Sistema Expectativas de Mercado) that this server serves,
 * with the exact name the OData API uses.
 */
public enum FocusIndicator {
    IPCA("IPCA", "% (December over December)", "Expected IPCA inflation for the reference year."),
    SELIC("Selic", "% a.a.", "Expected Selic target at the end of the reference year."),
    PIB_TOTAL("PIB Total", "% (real growth)", "Expected real GDP growth in the reference year."),
    CAMBIO("Câmbio", "R$/US$", "Expected BRL per USD exchange rate at the end of the reference year."),
    IGPM("IGP-M", "% (December over December)", "Expected IGP-M inflation for the reference year.");

    private final String apiName;
    private final String unit;
    private final String meaning;

    FocusIndicator(String apiName, String unit, String meaning) {
        this.apiName = apiName;
        this.unit = unit;
        this.meaning = meaning;
    }

    /** Name used in the {@code Indicador} field of the Focus OData API. */
    public String apiName() {
        return apiName;
    }

    public String unit() {
        return unit;
    }

    public String meaning() {
        return meaning;
    }

    public static FocusIndicator require(String value) {
        if (value == null || value.isBlank()) {
            throw ToolException.invalid("indicator is required. Valid values: " + names() + ".");
        }
        String normalized = compact(value);
        return Arrays.stream(values())
                .filter(i -> compact(i.name()).equals(normalized) || i.apiName().equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElseThrow(() -> ToolException.invalid(
                        "Unknown indicator '" + value + "'. Valid values: " + names() + "."));
    }

    /** "PIB Total", "pib_total" and "PIB-TOTAL" all become "PIBTOTAL". */
    private static String compact(String value) {
        return value.trim().toUpperCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
    }

    public static String names() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));
    }
}
