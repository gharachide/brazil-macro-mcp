package io.github.gharachide.brazilmacro;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gharachide.brazilmacro.support.BcbFixtures;
import io.github.gharachide.brazilmacro.support.FakeBcbServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Black-box test of the packaged jar over STDIO, launched the way MCP clients launch a local
 * server. A stray line on stdout (banner, log) would break the protocol and fail this test.
 */
class McpServerStdioIT {

    private static FakeBcbServer bcb;
    private static McpSyncClient client;

    @TempDir
    static Path dir;

    @BeforeAll
    static void launch() {
        String jar = System.getProperty("app.jar");
        assertThat(jar).as("app.jar system property (set by maven-failsafe-plugin)").isNotBlank();
        assertThat(Files.isRegularFile(Path.of(jar))).isTrue();
        bcb = FakeBcbServer.start().respond(BcbFixtures.standard());

        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ServerParameters server = ServerParameters.builder(java)
                .args("-jar", jar,
                        "--spring.profiles.active=stdio",
                        "--brazil-macro.upstream.sgs-base-url=" + bcb.sgsBaseUrl(),
                        "--brazil-macro.upstream.focus-base-url=" + bcb.focusBaseUrl(),
                        "--brazil-macro.audit.path=" + dir.resolve("audit.jsonl"))
                .build();
        client = McpClient.sync(new StdioClientTransport(server, McpJsonDefaults.getMapper()))
                .requestTimeout(Duration.ofSeconds(30))
                .initializationTimeout(Duration.ofSeconds(60))
                .clientInfo(new Implementation("stdio-it", "1.0.0"))
                .build();
        client.initialize();
    }

    @AfterAll
    static void shutdown() {
        if (client != null) {
            client.closeGracefully();
        }
        if (bcb != null) {
            bcb.close();
        }
    }

    @Test
    void listsTheToolsOverStdio() {
        assertThat(client.listTools().tools()).hasSize(6);
    }

    @Test
    void returnsCitedDataOverStdio() {
        CallToolResult result = client.callTool(new CallToolRequest("get_latest", Map.of("series", "IPCA", "count", 2)));

        assertThat(result.isError()).isFalse();
        String text = ((TextContent) result.content().getFirst()).text();
        assertThat(text).contains("\"date\":\"2023-12-01\"").contains("\"value\":0.56").contains("bcdata.sgs.433");
    }

    @Test
    void reportsErrorsAsToolResultsOverStdio() {
        CallToolResult result = client.callTool(new CallToolRequest("accumulated_change",
                Map.of("series", "SELIC_TARGET", "startMonth", "2023-01", "endMonth", "2023-12")));

        assertThat(result.isError()).isTrue();
        assertThat(((TextContent) result.content().getFirst()).text()).contains("cannot be compounded");
    }
}
