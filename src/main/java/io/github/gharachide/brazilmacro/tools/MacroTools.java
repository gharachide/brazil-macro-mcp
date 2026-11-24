package io.github.gharachide.brazilmacro.tools;

import static io.github.gharachide.brazilmacro.tools.ToolCallRecorder.args;

import io.github.gharachide.brazilmacro.service.MacroDataService;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The MCP tools. All of them are read-only and idempotent; every successful result carries a
 * {@code citation}, and every failure is an {@code isError} result with a code and guidance
 * instead of a best guess.
 */
@Component
public class MacroTools {

    private static final String SERIES_PARAM = "Series alias from list_series: IPCA, IPCA_12M, INPC, IGPM, "
            + "SELIC_TARGET, SELIC_MONTHLY, SELIC_ANNUALIZED, CDI_MONTHLY or USD_BRL.";

    private final MacroDataService service;
    private final ToolCallRecorder recorder;

    public MacroTools(MacroDataService service, ToolCallRecorder recorder) {
        this.service = service;
        this.recorder = recorder;
    }

    @McpTool(name = "list_series", title = "List available series",
            description = "Lists the Banco Central do Brasil series this server serves (alias, SGS code, unit, "
                    + "frequency, whether values can be compounded, caveats) and the Focus survey indicators. "
                    + "Call it when unsure which alias or unit applies.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public CallToolResult listSeries() {
        return recorder.run("list_series", args(), service::catalog);
    }

    @McpTool(name = "get_series", title = "Get official observations",
            description = "Official observations of one series between two dates (inclusive), with a summary "
                    + "(first, last, min, max) and a citation with the exact BCB URLs. Monthly values are dated on "
                    + "the first day of the reference month. Ranges: up to 10 years for daily series, 40 years for "
                    + "monthly series, 1000 observations per call.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = true))
    public CallToolResult getSeries(
            @McpToolParam(description = SERIES_PARAM) String series,
            @McpToolParam(description = "First date, ISO format yyyy-MM-dd.") String startDate,
            @McpToolParam(description = "Last date, ISO format yyyy-MM-dd. Must not be before startDate.") String endDate) {
        return recorder.run("get_series", args("series", series, "startDate", startDate, "endDate", endDate),
                () -> service.getSeries(series, startDate, endDate));
    }

    @McpTool(name = "get_latest", title = "Get latest observations",
            description = "The most recent official observations of one series, dated up to today in Brazil, with "
                    + "a citation. Use it for questions such as 'what is the Selic rate now' or 'latest IPCA'.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = true))
    public CallToolResult getLatest(
            @McpToolParam(description = SERIES_PARAM) String series,
            @McpToolParam(description = "How many observations, 1 to 20. Default 1.", required = false) Integer count) {
        return recorder.run("get_latest", args("series", series, "count", count),
                () -> service.getLatest(series, count));
    }

    @McpTool(name = "accumulated_change", title = "Compound a monthly series",
            description = "Accumulated change of a monthly rate or price index over a period of whole months "
                    + "(for example IPCA in a year, or CDI over 12 months), compounded in exact decimal arithmetic. "
                    + "Returns the monthly values used and a citation. Fails with INCOMPLETE_DATA rather than "
                    + "compute over a period with missing months. Only IPCA, INPC, IGPM, SELIC_MONTHLY and "
                    + "CDI_MONTHLY can be compounded.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = true))
    public CallToolResult accumulatedChange(
            @McpToolParam(description = "IPCA, INPC, IGPM, SELIC_MONTHLY or CDI_MONTHLY.") String series,
            @McpToolParam(description = "First month, ISO format yyyy-MM (inclusive).") String startMonth,
            @McpToolParam(description = "Last month, ISO format yyyy-MM (inclusive).") String endMonth) {
        return recorder.run("accumulated_change",
                args("series", series, "startMonth", startMonth, "endMonth", endMonth),
                () -> service.accumulatedChange(series, startMonth, endMonth));
    }

    @McpTool(name = "real_return", title = "Real return of an interest rate",
            description = "Real (inflation-adjusted) return of CDI or Selic over a period of whole months: both "
                    + "series are compounded over the same months and combined by the Fisher relation "
                    + "(1 + nominal) / (1 + inflation) - 1. Returns both legs and both citations.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = true))
    public CallToolResult realReturn(
            @McpToolParam(description = "CDI_MONTHLY or SELIC_MONTHLY.") String nominalSeries,
            @McpToolParam(description = "IPCA, INPC or IGPM.") String inflationSeries,
            @McpToolParam(description = "First month, ISO format yyyy-MM (inclusive).") String startMonth,
            @McpToolParam(description = "Last month, ISO format yyyy-MM (inclusive).") String endMonth) {
        return recorder.run("real_return",
                args("nominalSeries", nominalSeries, "inflationSeries", inflationSeries, "startMonth", startMonth,
                        "endMonth", endMonth),
                () -> service.realReturn(nominalSeries, inflationSeries, startMonth, endMonth));
    }

    @McpTool(name = "market_expectations", title = "Focus market expectations",
            description = "Market expectations from the BCB Focus survey for one indicator and reference year: "
                    + "median, mean, standard deviation, range and number of respondents on the latest survey "
                    + "dates, with a citation. Indicators: IPCA, SELIC, PIB_TOTAL, CAMBIO, IGPM.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = true))
    public CallToolResult marketExpectations(
            @McpToolParam(description = "IPCA, SELIC, PIB_TOTAL, CAMBIO or IGPM.") String indicator,
            @McpToolParam(description = "Year the projection refers to, e.g. 2027. Default: current year.",
                    required = false) Integer referenceYear,
            @McpToolParam(description = "How many recent survey dates to return, 1 to 12. Default 1.",
                    required = false) Integer surveys) {
        return recorder.run("market_expectations",
                args("indicator", indicator, "referenceYear", referenceYear, "surveys", surveys),
                () -> service.marketExpectations(indicator, referenceYear, surveys));
    }
}
