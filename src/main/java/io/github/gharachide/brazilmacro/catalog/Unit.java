package io.github.gharachide.brazilmacro.catalog;

import com.fasterxml.jackson.annotation.JsonValue;

/** Unit of the values in a series, in the notation the BCB uses. */
public enum Unit {
    PERCENT_PER_MONTH("% a.m."),
    PERCENT_PER_YEAR("% a.a."),
    PERCENT_OVER_12_MONTHS("% em 12 meses"),
    BRL_PER_USD("R$/US$");

    private final String label;

    Unit(String label) {
        this.label = label;
    }

    @JsonValue
    public String label() {
        return label;
    }
}
