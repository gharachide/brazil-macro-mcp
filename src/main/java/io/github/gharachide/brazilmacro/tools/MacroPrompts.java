package io.github.gharachide.brazilmacro.tools;

import io.github.gharachide.brazilmacro.service.MacroDataService;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.stereotype.Component;

/** A reusable prompt that turns the tools into a cited, abstain-on-error briefing. */
@Component
public class MacroPrompts {

    private final Clock clock;

    public MacroPrompts(Clock clock) {
        this.clock = clock;
    }

    @McpPrompt(name = "macro_briefing", title = "Cited briefing on Brazil's monetary conditions",
            description = "Policy rate, inflation, real CDI return and Focus expectations, with every number cited.")
    public GetPromptResult macroBriefing(
            @McpArg(name = "referenceYear", description = "Year for the Focus expectations (default: current year).",
                    required = false) String referenceYear) {
        int currentYear = LocalDate.now(clock.withZone(MacroDataService.BRAZIL)).getYear();
        String year = referenceYear != null && referenceYear.trim().matches("\\d{4}")
                ? referenceYear.trim()
                : String.valueOf(currentYear);
        String text = """
                Write a short briefing on Brazil's monetary conditions using only the brazil-macro tools.

                1. get_latest with series SELIC_TARGET: the policy rate in force.
                2. get_latest with series IPCA_12M: inflation over the last 12 months.
                3. real_return with nominalSeries CDI_MONTHLY and inflationSeries IPCA over the 12 months ending in \
                the month of the latest IPCA_12M observation.
                4. market_expectations for IPCA and for SELIC with referenceYear %s.

                Rules: quote every number exactly as returned and cite it with its citation.citeAs. If a tool \
                returns an error, say which figure is unavailable and why; do not estimate it or use remembered \
                values. End with one sentence on what these figures do not show.
                """.formatted(year);
        return new GetPromptResult("Cited briefing on Brazil's monetary conditions",
                List.of(new PromptMessage(Role.USER, new TextContent(text))));
    }
}
