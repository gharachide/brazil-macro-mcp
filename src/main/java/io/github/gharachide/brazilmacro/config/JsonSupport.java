package io.github.gharachide.brazilmacro.config;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON mapper for BCB payloads and tool results. Kept out of the Spring context on purpose,
 * so that its settings never change how Spring MVC or the MCP transport read JSON-RPC messages.
 */
public final class JsonSupport {

    /** Decimals stay {@link java.math.BigDecimal}; dates are ISO-8601 strings. */
    public static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private JsonSupport() {
    }
}
