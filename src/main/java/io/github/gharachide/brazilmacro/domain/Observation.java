package io.github.gharachide.brazilmacro.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One official value. For monthly series the date is the first day of the reference month.
 *
 * @param date observation date
 * @param value value exactly as published by the BCB
 * @param partial {@code true} when the value covers a month still in progress (the SGS publishes
 *     the Selic and CDI accumulated "so far" for the current month); absent otherwise
 */
public record Observation(
        LocalDate date,
        BigDecimal value,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean partial) {

    public Observation(LocalDate date, BigDecimal value) {
        this(date, value, null);
    }

    public Observation asPartial() {
        return new Observation(date, value, Boolean.TRUE);
    }

    /** Not a bean getter on purpose, so that JSON output only carries {@code partial}. */
    public boolean inProgress() {
        return Boolean.TRUE.equals(partial);
    }
}
