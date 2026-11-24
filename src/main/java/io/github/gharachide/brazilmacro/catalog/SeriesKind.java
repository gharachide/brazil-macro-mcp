package io.github.gharachide.brazilmacro.catalog;

/**
 * What a number in a series means. The kind decides which calculations are valid: only
 * month-by-month rates can be chained into an accumulated change.
 */
public enum SeriesKind {

    /** Monthly percent change of a price index (IPCA, INPC, IGP-M). Compoundable. */
    PRICE_INDEX_CHANGE(true),

    /** Interest accumulated within each month, in % per month (Selic, CDI). Compoundable. */
    MONTHLY_RATE(true),

    /** Interest rate expressed per year. Never compounded month by month. */
    ANNUAL_RATE(false),

    /** Change already accumulated over the previous 12 months. Never chained or summed. */
    TWELVE_MONTH_CHANGE(false),

    /** Price of one US dollar in reais. */
    EXCHANGE_RATE(false);

    private final boolean compoundable;

    SeriesKind(boolean compoundable) {
        this.compoundable = compoundable;
    }

    public boolean compoundable() {
        return compoundable;
    }
}
