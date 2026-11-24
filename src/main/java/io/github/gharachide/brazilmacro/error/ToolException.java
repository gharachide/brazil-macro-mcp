package io.github.gharachide.brazilmacro.error;

import java.util.Map;

/**
 * A failure that is reported to the agent as a structured tool error ({@code isError: true}),
 * never as a guess.
 */
public class ToolException extends RuntimeException {

    private final ErrorCode code;
    private final transient Map<String, Object> details;

    public ToolException(ErrorCode code, String message) {
        this(code, message, Map.of(), null);
    }

    public ToolException(ErrorCode code, String message, Map<String, Object> details) {
        this(code, message, details, null);
    }

    public ToolException(ErrorCode code, String message, Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.details = Map.copyOf(details);
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static ToolException invalid(String message) {
        return new ToolException(ErrorCode.INVALID_ARGUMENT, message);
    }
}
