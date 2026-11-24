package io.github.gharachide.brazilmacro.tools;

import io.github.gharachide.brazilmacro.audit.AuditLog;
import io.github.gharachide.brazilmacro.config.BrazilMacroProperties;
import io.github.gharachide.brazilmacro.config.JsonSupport;
import io.github.gharachide.brazilmacro.error.ErrorCode;
import io.github.gharachide.brazilmacro.error.ToolException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a tool body and turns its outcome into an MCP result: the payload as JSON text (for
 * clients that only read text) and as {@code structuredContent}, with {@code isError} set on
 * failures. Every call is observed (metrics, traces) and appended to the audit chain.
 */
@Component
public class ToolCallRecorder {

    private static final Logger log = LoggerFactory.getLogger(ToolCallRecorder.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JsonMapper json;
    private final AuditLog audit;
    private final boolean auditFailClosed;
    private final ObservationRegistry observations;
    private final Tracer tracer;

    public ToolCallRecorder(ObjectProvider<AuditLog> audit, BrazilMacroProperties properties,
            ObjectProvider<ObservationRegistry> observations, ObjectProvider<Tracer> tracer) {
        this.json = JsonSupport.MAPPER;
        this.audit = audit.getIfAvailable();
        this.auditFailClosed = properties.audit().failClosed();
        this.observations = observations.getIfAvailable(() -> ObservationRegistry.NOOP);
        this.tracer = tracer.getIfAvailable(() -> Tracer.NOOP);
    }

    /**
     * One observation per tool call ({@code brazil_macro.tool.calls}): a timer tagged with the tool
     * and the outcome, and a span that parents the BCB requests made by the call. The trace id is
     * written to the audit record, so an audited answer can be matched with its trace and logs.
     */
    public CallToolResult run(String tool, Map<String, ?> arguments, Supplier<?> body) {
        Observation observation = Observation.createNotStarted("brazil_macro.tool.calls", observations)
                .contextualName("tool " + tool)
                .lowCardinalityKeyValue("tool", tool)
                .start();
        long started = System.nanoTime();
        String text;
        ErrorCode error = null;
        String traceId = null;
        try (Observation.Scope scope = observation.openScope()) {
            Span span = tracer.currentSpan();
            if (span != null && !span.isNoop()) {
                traceId = span.context().traceId();
            }
            try {
                text = json.writeValueAsString(body.get());
            } catch (ToolException e) {
                error = e.code();
                text = json.writeValueAsString(errorPayload(e));
                log.info("{} -> {}: {}", tool, e.code(), e.getMessage());
            } catch (RuntimeException e) {
                error = ErrorCode.INTERNAL_ERROR;
                text = json.writeValueAsString(errorPayload(
                        new ToolException(ErrorCode.INTERNAL_ERROR, "Unexpected server error; no data was returned.")));
                observation.error(e);
                log.error("{} failed unexpectedly", tool, e);
            }
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            if (audit != null) {
                try {
                    audit.append(tool, arguments, error == null ? "ok" : "error", error == null ? null : error.name(),
                            sha256(text), elapsedMs, traceId);
                } catch (Exception e) {
                    log.error("Could not write the audit record of {}", tool, e);
                    if (auditFailClosed) {
                        error = ErrorCode.INTERNAL_ERROR;
                        text = json.writeValueAsString(errorPayload(new ToolException(ErrorCode.INTERNAL_ERROR,
                                "The audit log is unavailable, and this server is configured not to serve unaudited "
                                        + "results.")));
                    }
                }
            }
        } finally {
            observation.lowCardinalityKeyValue("outcome", error == null ? "ok" : error.name());
            observation.stop();
        }
        return CallToolResult.builder()
                .addTextContent(text)
                .structuredContent(json.readValue(text, MAP))
                .isError(error != null)
                .build();
    }

    static Map<String, Object> errorPayload(ToolException e) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", e.code().name());
        error.put("message", e.getMessage());
        error.put("retryable", e.code().retryable());
        error.put("guidance", e.code().guidance());
        if (!e.details().isEmpty()) {
            error.put("details", e.details());
        }
        return Map.of("error", error);
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Keeps argument order stable in the audit log and accepts null values. */
    static Map<String, Object> args(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }
}
