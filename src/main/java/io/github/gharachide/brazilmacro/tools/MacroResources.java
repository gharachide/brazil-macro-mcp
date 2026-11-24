package io.github.gharachide.brazilmacro.tools;

import io.github.gharachide.brazilmacro.config.JsonSupport;
import io.github.gharachide.brazilmacro.service.MacroDataService;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Static context an agent (or a person) can read before calling tools. */
@Component
public class MacroResources {

    static final String CATALOG_URI = "brazil-macro://catalog";
    static final String METHODOLOGY_URI = "brazil-macro://methodology";

    private final MacroDataService service;
    private final String methodology;

    public MacroResources(MacroDataService service) {
        this.service = service;
        this.methodology = readClasspath("methodology.md");
    }

    @McpResource(uri = CATALOG_URI, name = "series-catalog", title = "Series catalog",
            description = "Every series and Focus indicator this server serves, with units, frequency and caveats.",
            mimeType = "application/json")
    public ReadResourceResult catalog() {
        String json = JsonSupport.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(service.catalog());
        return new ReadResourceResult(List.of(new TextResourceContents(CATALOG_URI, "application/json", json)));
    }

    @McpResource(uri = METHODOLOGY_URI, name = "methodology", title = "Methodology and guardrails",
            description = "How numbers are sourced, dated, compounded and cited, and when the server refuses to answer.",
            mimeType = "text/markdown")
    public ReadResourceResult methodology() {
        return new ReadResourceResult(List.of(new TextResourceContents(METHODOLOGY_URI, "text/markdown", methodology)));
    }

    private static String readClasspath(String name) {
        try (InputStream in = new ClassPathResource(name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing classpath resource " + name, e);
        }
    }
}
