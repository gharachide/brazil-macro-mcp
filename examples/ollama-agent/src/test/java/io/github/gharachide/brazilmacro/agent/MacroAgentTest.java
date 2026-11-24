package io.github.gharachide.brazilmacro.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import io.github.gharachide.brazilmacro.agent.eval.EvalRunner;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs the whole loop without a GPU: a scripted stand-in for Ollama's /api/chat asks for a tool,
 * the real brazil-macro-mcp jar answers it over STDIO (with a stand-in for the BCB API), and the
 * tool result goes back to the "model". Proves the wiring, not the quality of any model.
 */
@SpringBootTest
class MacroAgentTest {

    private static final JsonMapper JSON = JsonMapper.shared();
    private static final List<JsonNode> CHAT_REQUESTS = new CopyOnWriteArrayList<>();
    private static final HttpServer FAKES = startFakes();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + FAKES.getAddress().getPort();
        String jar = System.getProperty("server.jar");
        registry.add("spring.ai.ollama.base-url", () -> base);
        registry.add("spring.ai.ollama.chat.model", () -> "scripted-model");
        registry.add("spring.ai.mcp.client.stdio.connections.brazil-macro.args[0]", () -> "-jar");
        registry.add("spring.ai.mcp.client.stdio.connections.brazil-macro.args[1]", () -> jar);
        registry.add("spring.ai.mcp.client.stdio.connections.brazil-macro.args[2]", () -> "--spring.profiles.active=stdio");
        registry.add("spring.ai.mcp.client.stdio.connections.brazil-macro.args[3]",
                () -> "--brazil-macro.upstream.sgs-base-url=" + base + "/dados/serie");
        registry.add("spring.ai.mcp.client.stdio.connections.brazil-macro.args[4]", () -> "--brazil-macro.audit.enabled=false");
    }

    @Autowired
    MacroAgent agent;

    @AfterAll
    static void stop() {
        FAKES.stop(0);
    }

    @Test
    void theModelCallsAnMcpToolAndAnswersWithTheCitedValue() {
        assertThat(Files.isRegularFile(Path.of(System.getProperty("server.jar"))))
                .as("build the server first: ./mvnw package in the repository root").isTrue();

        String answer = agent.ask("Qual é a meta Selic hoje?");

        assertThat(answer).contains("13.75").contains("SGS series 432");
        JsonNode first = CHAT_REQUESTS.getFirst();
        assertThat(first.path("tools").values()).extracting(t -> t.path("function").path("name").asString())
                .contains("get_latest", "real_return", "accumulated_change");
        assertThat(first.path("messages").get(0).path("content").asString()).contains("citation.citeAs");
        JsonNode toolMessage = CHAT_REQUESTS.getLast().path("messages").values().stream()
                .filter(m -> "tool".equals(m.path("role").asString()))
                .findFirst().orElseThrow();
        assertThat(toolMessage.path("content").asString()).contains("bcdata.sgs.432").contains("13.75");
    }

    @Test
    void toolErrorsReachTheModelWithTheAbstainGuidance() {
        String answer = agent.ask("Qual foi a meta Selic acumulada em 2025?");

        assertThat(answer).contains("cannot be compounded").contains("Do not estimate");
    }

    @Test
    void theEvalGradesRealTranscriptsAndWritesAReport(@TempDir Path dir) throws IOException {
        Path cases = dir.resolve("cases.json");
        Files.writeString(cases, """
                [{"id":"latest-selic","category":"latest","question":"Qual é a meta Selic hoje?",
                  "expectTools":["get_latest"],"expectArgs":{"series":"SELIC_TARGET"}},
                 {"id":"trap","category":"abstain","abstain":true,"question":"Qual foi o IPCA de dezembro de 2030?"}]
                """);
        EvalRunner.Options options = new EvalRunner.Options(List.of("scripted-model"), 2, Set.of(), cases,
                dir.resolve("out"), 0.9);

        EvalRunner.Report report = new EvalRunner(agent, Clock.systemUTC()).run(options);

        EvalRunner.Summary summary = report.summaries().getFirst();
        assertThat(summary.attempts()).isEqualTo(4);
        assertThat(summary.passRate()).isEqualTo(0.5);
        assertThat(summary.tool()).isEqualTo(1.0);
        assertThat(summary.abstained()).isZero();
        assertThat(report.belowThreshold()).isTrue();
        String markdown = Files.readString(report.markdown());
        assertThat(markdown).contains("| scripted-model | **50%**").contains("| latest-selic | latest | 2/2 |")
                .contains("did not abstain");
        assertThat(Files.readString(report.json())).contains("\"tool\" : \"get_latest\"").contains("13.75");
    }

    @Test
    void theBaselineAsksTheSameQuestionsWithoutToolsAndCatchesInventedNumbers(@TempDir Path dir) throws IOException {
        Path cases = dir.resolve("cases.json");
        Files.writeString(cases, """
                [{"id":"latest-selic","category":"latest","question":"Qual é a meta Selic hoje?",
                  "expectTools":["get_latest"],"expectArgs":{"series":"SELIC_TARGET"}},
                 {"id":"trap","category":"abstain","abstain":true,"question":"Qual foi o IPCA de dezembro de 2030?"}]
                """);
        EvalRunner.Options options = new EvalRunner.Options(List.of("scripted-model"), 1, Set.of(), cases,
                dir.resolve("out"), null, true);

        EvalRunner.Report report = new EvalRunner(agent, Clock.systemUTC()).run(options);

        assertThat(report.summaries()).extracting(EvalRunner.Summary::model)
                .containsExactly("scripted-model", "scripted-model (no tools)");
        EvalRunner.Summary withTools = report.summaries().get(0);
        EvalRunner.Summary withoutTools = report.summaries().get(1);
        assertThat(withTools.invented()).isZero();
        assertThat(withoutTools.invented()).isEqualTo(1.0);
        assertThat(withoutTools.passRate()).isZero();
        assertThat(withoutTools.tool()).isNaN();
        assertThat(Files.readString(report.markdown())).contains("| scripted-model (no tools) | **0%** | 100% | – | – |")
                .contains("ungrounded [10.50]");
    }

    @Test
    void aModelThatIsNotPulledIsSkippedAfterTheFirstAttempt(@TempDir Path dir) {
        EvalRunner.Options options = new EvalRunner.Options(List.of("missing-model"), 3,
                Set.of("latest-selic", "latest-ipca"), null, dir, null);

        EvalRunner.Summary summary = new EvalRunner(agent, Clock.systemUTC()).run(options).summaries().getFirst();

        assertThat(summary.attempts()).isEqualTo(1);
        assertThat(summary.failures()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ scripted stand-ins

    private static HttpServer startFakes() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/chat", MacroAgentTest::chat);
            server.createContext("/dados/serie/", MacroAgentTest::sgs);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * First turn: ask for a tool (get_latest, or accumulated_change when the question asks to
     * compound). Second turn: answer from the tool result, or relay the tool error.
     */
    private static void chat(HttpExchange exchange) throws IOException {
        JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
        if ("missing-model".equals(request.path("model").asString())) {
            byte[] body = "{\"error\":\"model 'missing-model' not found\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            return;
        }
        CHAT_REQUESTS.add(request);
        if (request.path("tools").isMissingNode() || request.path("tools").isEmpty()) {
            // No tools: answer from "memory", with a number the official data does not have.
            ObjectNode remembered = JSON.createObjectNode().put("role", "assistant")
                    .put("content", "Segundo o Banco Central, o valor é de 10.50%.");
            ObjectNode response = JSON.createObjectNode().put("model", "scripted-model")
                    .put("created_at", "2026-11-24T12:00:00Z").put("done", true).put("done_reason", "stop");
            response.set("message", remembered);
            send(exchange, JSON.writeValueAsString(response));
            return;
        }
        JsonNode toolResult = request.path("messages").values().stream()
                .filter(m -> "tool".equals(m.path("role").asString()))
                .findFirst().orElse(null);
        ObjectNode message = JSON.createObjectNode().put("role", "assistant");
        String question = request.path("messages").values().stream()
                .filter(m -> "user".equals(m.path("role").asString()))
                .map(m -> m.path("content").asString()).findFirst().orElse("");
        if (toolResult == null) {
            message.put("content", "");
            ArrayNode calls = message.putArray("tool_calls");
            if (question.contains("acumulad")) {
                ObjectNode function = calls.addObject().putObject("function").put("name", "accumulated_change");
                function.putObject("arguments").put("series", "SELIC_TARGET").put("startMonth", "2025-01")
                        .put("endMonth", "2025-12");
            } else {
                ObjectNode function = calls.addObject().putObject("function").put("name", "get_latest");
                function.putObject("arguments").put("series", "SELIC_TARGET");
            }
        } else if (toolResult.path("content").asString().contains("\"error\"")) {
            message.put("content", "Não posso calcular isso: " + toolResult.path("content").asString());
        } else {
            // Spring AI hands the model the MCP content list: [{"type":"text","text":"<payload>"}].
            JsonNode content = JSON.readTree(toolResult.path("content").asString());
            JsonNode result = JSON.readTree(content.get(0).path("text").asString());
            message.put("content", "A meta Selic é " + result.path("latest").path("value").asString() + "% a.a. Fonte: "
                    + result.path("citation").path("citeAs").asString());
        }
        ObjectNode response = JSON.createObjectNode()
                .put("model", "scripted-model")
                .put("created_at", "2026-11-24T12:00:00Z")
                .put("done", true)
                .put("done_reason", "stop");
        response.set("message", message);
        send(exchange, JSON.writeValueAsString(response));
    }

    private static void sgs(HttpExchange exchange) throws IOException {
        String today = LocalDate.now(ZoneId.of("America/Sao_Paulo")).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        send(exchange, "[{\"data\":\"" + today + "\",\"valor\":\"13.75\"}]");
    }

    private static void send(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
