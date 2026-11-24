package io.github.gharachide.brazilmacro.service;

import io.github.gharachide.brazilmacro.error.ToolException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;

/** Strict parsing of tool arguments, with messages written for the agent that sent them. */
final class Args {

    private Args() {
    }

    static LocalDate date(String name, String value) {
        if (value == null || value.isBlank()) {
            throw ToolException.invalid(name + " is required, as an ISO date (yyyy-MM-dd).");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw ToolException.invalid(name + " must be a valid ISO date (yyyy-MM-dd), got '" + value + "'.");
        }
    }

    static YearMonth month(String name, String value) {
        if (value == null || value.isBlank()) {
            throw ToolException.invalid(name + " is required, as an ISO month (yyyy-MM).");
        }
        try {
            return YearMonth.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw ToolException.invalid(name + " must be a valid ISO month (yyyy-MM), got '" + value + "'.");
        }
    }

    static int intInRange(String name, Integer value, int defaultValue, int min, int max) {
        int actual = value == null ? defaultValue : value;
        if (actual < min || actual > max) {
            throw ToolException.invalid(name + " must be between " + min + " and " + max + ", got " + actual + ".");
        }
        return actual;
    }
}
