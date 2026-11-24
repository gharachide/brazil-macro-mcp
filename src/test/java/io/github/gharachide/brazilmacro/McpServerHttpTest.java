package io.github.gharachide.brazilmacro;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gharachide.brazilmacro.audit.AuditVerifier;
import io.github.gharachide.brazilmacro.support.BcbFixtures;
import io.github.gharachide.brazilmacro.support.FakeBcbServer;
import io.github.gharachide.brazilmacro.support.TestStack;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** Talks to the running server over Streamable HTTP with the MCP Java SDK client, as an agent would. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpServerHttpTest {

    private static final FakeBcbServer BCB = FakeBcbServer.start().respond(BcbFixtures.standard());
    private static final Path AUDIT = tempFile();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("brazil-macro.upstream.sgs-base-url", () -> BCB.sgsBaseUrl().toString());
        registry.add("brazil-macro.upstream.focus-base-url", () -> BCB.focusBaseUrl().toString());
        registry.add("brazil-macro.upstream.initial-backoff", () -> "1ms");
        registry.add("brazil-macro.audit.path", AUDIT::toString);
    }

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(TestStack.NOW, ZoneOffset.UTC);
        }
    }

    @LocalServerPort
    int port;

    private McpSyncClient client;
    private InitializeResult initialize;

    @BeforeEach
    void connect() {
        HttpClientStreamableHttpTransport transport =
                HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port).endpoint("/mcp").build();
        client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(30))
                .clientInfo(new Implementation("e2e-test", "1.0.0"))
                .build();
        initialize = client.initialize();
    }

    @AfterEach
    void disconnect() {
        client.closeGracefully();
    }

    @AfterAll
    static void stopBcb() {
        BCB.close();
    }

    @Test
    void serverAnnouncesItselfAndItsRules() {
        assertThat(initialize.serverInfo().name()).isEqualTo("brazil-macro-mcp");
        assertThat(initialize.instructions()).contains("do not estimate").contains("citation.citeAs");
    }

    @Test
    void exposesSixReadOnlyToolsWithTypedInputs() {
        List<Tool> tools = client.listTools().tools();

        assertThat(tools).extracting(Tool::name).containsExactlyInAnyOrder("list_series", "get_series", "get_latest",
                "accumulated_change", "real_return", "market_expectations");
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.annotations().readOnlyHint()).isTrue();
            assertThat(tool.annotations().destructiveHint()).isFalse();
            assertThat(tool.annotations().idempotentHint()).isTrue();
        });
        Tool getSeries = tools.stream().filter(t -> t.name().equals("get_series")).findFirst().orElseThrow();
        assertThat(schemaProperties(getSeries)).containsOnlyKeys("series", "startDate", "endDate");
        assertThat(required(getSeries)).containsExactlyInAnyOrder("series", "startDate", "endDate");
        Tool latest = tools.stream().filter(t -> t.name().equals("get_latest")).findFirst().orElseThrow();
        assertThat(required(latest)).containsExactly("series");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schemaProperties(Tool tool) {
        return (Map<String, Object>) tool.inputSchema().get("properties");
    }

    @SuppressWarnings("unchecked")
    private static List<String> required(Tool tool) {
        return (List<String>) tool.inputSchema().get("required");
    }

    @Test
    void successfulCallsReturnTheSamePayloadAsTextAndStructuredContent() {
        CallToolResult result = call("get_series",
                Map.of("series", "IPCA", "startDate", "2023-01-01", "endDate", "2023-12-31"));

        assertThat(result.isError()).isFalse();
        Map<String, Object> structured = structured(result);
        assertThat(structured).containsKeys("series", "observations", "summary", "citation");
        // Same JSON document (compared as trees, so number representations do not matter).
        assertThat(JsonMapper.shared().readTree(text(result))).isEqualTo(JsonMapper.shared().valueToTree(structured));
        assertThat(text(result)).contains("\"date\":\"2023-01-01\"").contains("\"citeAs\"");
    }

    @Test
    void compoundingIsDoneByTheServer() {
        CallToolResult result = call("real_return", Map.of("nominalSeries", "CDI_MONTHLY", "inflationSeries", "IPCA",
                "startMonth", "2023-01", "endMonth", "2023-12"));

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("\"realReturnPercent\":8.0358");
    }

    @Test
    void failuresAreToolErrorsWithGuidanceNotGuesses() {
        CallToolResult result = call("accumulated_change", Map.of("series", "INPC", "startMonth", "2023-01",
                "endMonth", "2023-12"));

        assertThat(result.isError()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) structured(result).get("error");
        assertThat(error).containsEntry("code", "INCOMPLETE_DATA").containsEntry("retryable", false);
        assertThat((String) error.get("guidance")).contains("Do not estimate");
    }

    @Test
    void unknownArgumentsAreReportedAsInvalid() {
        CallToolResult result = call("get_latest", Map.of("series", "BITCOIN"));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("INVALID_ARGUMENT").contains("Valid values");
    }

    @Test
    void resourcesDescribeTheCatalogAndTheMethodology() {
        assertThat(client.listResources().resources()).extracting(r -> r.uri())
                .contains("brazil-macro://catalog", "brazil-macro://methodology");

        var catalog = (TextResourceContents) client.readResource(new ReadResourceRequest("brazil-macro://catalog"))
                .contents().getFirst();
        assertThat(catalog.mimeType()).isEqualTo("application/json");
        assertThat(catalog.text()).contains("CDI_MONTHLY").contains("seriesPage");

        var methodology = (TextResourceContents) client
                .readResource(new ReadResourceRequest("brazil-macro://methodology")).contents().getFirst();
        assertThat(methodology.text()).contains("Fisher relation");
    }

    @Test
    void briefingPromptUsesTheRequestedYear() {
        assertThat(client.listPrompts().prompts()).extracting(p -> p.name()).contains("macro_briefing");

        var prompt = client.getPrompt(new GetPromptRequest("macro_briefing", Map.of("referenceYear", "2025")));

        assertThat(((TextContent) prompt.messages().getFirst().content()).text())
                .contains("referenceYear 2025").contains("do not estimate");
    }

    @Test
    void everyCallIsAppendedToAnIntactAuditChain() throws IOException {
        call("list_series", Map.of());
        call("get_latest", Map.of("series", "USD_BRL", "count", 2));

        AuditVerifier.Result audit = AuditVerifier.verify(AUDIT);

        assertThat(audit.valid()).isTrue();
        assertThat(audit.entries()).isGreaterThanOrEqualTo(2);
        assertThat(Files.readString(AUDIT)).contains("\"tool\":\"get_latest\"").contains("\"series\":\"USD_BRL\"");
    }

    @Test
    void auditRecordsCarryTheTraceIdOfTheToolCall() throws IOException {
        call("get_latest", Map.of("series", "IPCA_12M"));

        String last = Files.readAllLines(AUDIT).getLast();

        assertThat(last).contains("\"tool\":\"get_latest\"").containsPattern("\"traceId\":\"[0-9a-f]{32}\"");
    }

    @Test
    void prometheusExposesToolUpstreamAndCacheMetrics() throws Exception {
        call("get_latest", Map.of("series", "USD_BRL"));
        call("accumulated_change", Map.of("series", "SELIC_TARGET", "startMonth", "2023-01", "endMonth", "2023-12"));

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/prometheus")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("brazil_macro_tool_calls_seconds_count{")
                .containsPattern("brazil_macro_tool_calls_seconds_count\\{[^}]*outcome=\"INVALID_ARGUMENT\"[^}]*tool=\"accumulated_change\"")
                .containsPattern("brazil_macro_upstream_requests_seconds_count\\{[^}]*outcome=\"ok\"[^}]*upstream=\"sgs\"")
                .contains("brazil_macro_cache_lookups_total{")
                .contains("brazil_macro_upstream_circuit_state{")
                .contains("brazil_macro_tool_calls_seconds_bucket");
    }

    @Test
    void browserRequestsFromUnknownOriginsAreRejected() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Origin", "https://attacker.example")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void healthReportsTheCircuitBreakers() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"sgsCircuit\":\"CLOSED\"");
    }

    private CallToolResult call(String tool, Map<String, Object> arguments) {
        return client.callTool(new CallToolRequest(tool, arguments));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }

    private static Path tempFile() {
        try {
            return Files.createTempDirectory("brazil-macro-audit").resolve("audit.jsonl");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
