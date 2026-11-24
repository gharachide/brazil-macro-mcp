package io.github.gharachide.brazilmacro.error;

/**
 * Closed set of failure reasons a tool can report. Every error tells the agent whether a retry
 * can help and repeats the abstain rule: no estimated or remembered numbers in place of data.
 */
public enum ErrorCode {

    INVALID_ARGUMENT(false, "Fix the arguments and call the tool again."),
    RANGE_TOO_LARGE(false, "Narrow the date range or split it into smaller periods."),
    NO_DATA(false, "Tell the user that the BCB has published no data for this request."),
    INCOMPLETE_DATA(false, "Tell the user which months are missing; do not compute a partial result."),
    UPSTREAM_UNAVAILABLE(true, "The Banco Central API is unavailable; retry later."),
    INTERNAL_ERROR(false, "Report the failure to the user.");

    private static final String ABSTAIN_RULE =
            "Do not estimate, interpolate or recall values from memory to replace the missing data.";

    private final boolean retryable;
    private final String nextStep;

    ErrorCode(boolean retryable, String nextStep) {
        this.retryable = retryable;
        this.nextStep = nextStep;
    }

    public boolean retryable() {
        return retryable;
    }

    /** Guidance text returned to the agent with every error of this kind. */
    public String guidance() {
        return nextStep + " " + ABSTAIN_RULE;
    }
}
