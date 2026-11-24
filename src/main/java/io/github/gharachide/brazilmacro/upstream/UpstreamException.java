package io.github.gharachide.brazilmacro.upstream;

import java.net.URI;

/** A request to a Banco Central API did not produce usable data. */
public class UpstreamException extends RuntimeException {

    public enum Kind {
        /** Network error, timeout, HTTP 429 or 5xx after all retries, or circuit open. */
        UNAVAILABLE,
        /** HTTP 404: the BCB has no data for the request. */
        NOT_FOUND,
        /** Any other HTTP 4xx: the BCB rejected the request. */
        REJECTED,
        /** The response could not be parsed into the expected shape. */
        MALFORMED
    }

    private final Kind kind;
    private final URI uri;
    private final int status;

    public UpstreamException(Kind kind, String message, URI uri, int status) {
        super(message);
        this.kind = kind;
        this.uri = uri;
        this.status = status;
    }

    public Kind kind() {
        return kind;
    }

    public URI uri() {
        return uri;
    }

    /** HTTP status, or 0 when no response was received. */
    public int status() {
        return status;
    }
}
